package uk.co.riera.talkbalance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.SessionDao
import uk.co.riera.talkbalance.ml.EngineBusyException
import uk.co.riera.talkbalance.ml.SherpaEngines
import uk.co.riera.talkbalance.ml.SherpaSpeakerMatcher
import uk.co.riera.talkbalance.session.Mode
import uk.co.riera.talkbalance.session.PersistState
import uk.co.riera.talkbalance.session.SessionController
import uk.co.riera.talkbalance.session.SessionResult
import uk.co.riera.talkbalance.session.SessionUiState
import uk.co.riera.talkbalance.settings.SettingsRepository

/**
 * Owns one live session for the UI: builds a [SessionController] (in-memory pipeline), surfaces its
 * live [uiState], and on stop keeps the finished [SessionResult] for the report screen. Never
 * touches disk for audio.
 */
class SessionViewModel(
    private val engines: SherpaEngines,
    private val personDao: PersonDao,
    private val sessionDao: SessionDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    sealed interface Phase {
        /** Building the matcher / not yet capturing. */
        data object Starting : Phase

        /** Capturing live. */
        data object Running : Phase

        /** Stopped; [result] ready for the report. */
        data class Finished(val result: SessionResult) : Phase

        /** Could not start (e.g. no enrolled people, engine not ready). */
        data class Failed(val message: String) : Phase
    }

    /** Display name + palette index for one enrolled speaker id the engine uses. */
    data class SpeakerMeta(val name: String, val colorIndex: Int)

    private var controller: SessionController? = null

    /**
     * speakerId (as the engine uses it: person `id.toString()` in Table, `"me"` in Me) -> its
     * name + colour, so the live bars and the report can paint each speaker in their enrolled
     * colour. The everyone-else bucket is absent (painted grey by the UI).
     */
    var metaBySpeakerId: Map<String, SpeakerMeta> = emptyMap()
        private set

    private val _phase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _live = MutableStateFlow(SessionUiState.idle())
    val live: StateFlow<SessionUiState> = _live.asStateFlow()

    /** Whether the pipeline reported an unrecoverable capture error (mic stolen, etc.). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Whether the finished session was saved to Room; drives the report's saved/retry affordance. */
    private val _persistState = MutableStateFlow<PersistState>(PersistState.Saving)
    val persistState: StateFlow<PersistState> = _persistState.asStateFlow()

    /** Start a session in [mode]. [mePersonId] required for Me mode. Call once per instance. */
    fun start(mode: Mode, mePersonId: Long?) {
        if (controller != null) return
        viewModelScope.launch {
            try {
                if (!engines.isInitialized) {
                    _phase.value = Phase.Failed("Speech engine is still starting — try again in a moment.")
                    return@launch
                }
                val threshold = settings.matchThreshold.first()
                val people = personDao.observeAll().first()
                metaBySpeakerId = when (mode) {
                    Mode.TABLE -> people.associate {
                        it.id.toString() to SpeakerMeta(it.name, it.colorIndex)
                    }
                    Mode.ME -> people.firstOrNull { it.id == mePersonId }?.let {
                        mapOf(SherpaSpeakerMatcher.ME_ID to SpeakerMeta(it.name, it.colorIndex))
                    } ?: emptyMap()
                }
                val c = SessionController.create(
                    engines = engines,
                    personDao = personDao,
                    sessionDao = sessionDao,
                    mode = mode,
                    threshold = threshold,
                    mePersonId = mePersonId,
                )
                controller = c
                // Relay the controller's flows into our own so the UI subscribes once.
                viewModelScope.launch { c.uiState.collect { _live.value = it } }
                viewModelScope.launch { c.error.collect { if (it != null) _error.value = it } }
                viewModelScope.launch { c.persistState.collect { _persistState.value = it } }
                c.start()
                _phase.value = Phase.Running
            } catch (e: EngineBusyException) {
                _phase.value = Phase.Failed("The speech engine is busy — finish adding a person first.")
            } catch (e: Throwable) {
                _phase.value = Phase.Failed(e.message ?: "Could not start the session.")
            }
        }
    }

    /** Stop the session; transitions to [Phase.Finished] with the persisted result. */
    fun stop() {
        val c = controller ?: return
        viewModelScope.launch {
            val result = c.stop()
            _phase.value = Phase.Finished(result)
        }
    }

    /** Retry saving the finished session (report's "couldn't save — retry" action). */
    fun retryPersist() {
        controller?.retryPersist()
    }

    /**
     * Hard-stop the underlying session (cancels capture + pipeline, releases the engine lease).
     * Called from the session screen's dispose path so leaving the screen never leaves the mic on.
     */
    fun abort() {
        controller?.close()
    }

    /**
     * Foreground-only guarantee: when the ViewModel is cleared (screen left, back, Activity
     * recreation, process teardown), abort the controller so no invisible microphone survives.
     */
    override fun onCleared() {
        controller?.close()
        super.onCleared()
    }

    companion object {
        fun factory(
            engines: SherpaEngines,
            personDao: PersonDao,
            sessionDao: SessionDao,
            settings: SettingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionViewModel(engines, personDao, sessionDao, settings) as T
        }
    }
}
