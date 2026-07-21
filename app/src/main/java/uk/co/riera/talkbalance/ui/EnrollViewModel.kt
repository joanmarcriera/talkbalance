package uk.co.riera.talkbalance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.riera.talkbalance.audio.AudioCapture
import uk.co.riera.talkbalance.audio.AudioEvent
import uk.co.riera.talkbalance.data.EmbeddingCodec
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.PersonEntity
import uk.co.riera.talkbalance.enroll.EnrollmentProcessor
import uk.co.riera.talkbalance.enroll.EnrollmentResult
import uk.co.riera.talkbalance.enroll.SimilarVoiceWarning
import uk.co.riera.talkbalance.enroll.loadProfiles
import uk.co.riera.talkbalance.ml.SherpaEngines
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drives the enrollment screen: records ~10 s from the mic in memory (live RMS level for the mic
 * indicator), hands the buffer to [EnrollmentProcessor], runs the similar-voice check, and on
 * success stores a [PersonEntity] via [PersonDao] with the chosen colour. No audio ever hits disk.
 */
class EnrollViewModel(
    private val engines: SherpaEngines,
    private val personDao: PersonDao,
) : ViewModel() {

    sealed interface State {
        /** Waiting to record. */
        data object Idle : State

        /** Capturing: [elapsedMs] of [EnrollViewModel.RECORD_MS], [level] is a 0..1 mic amplitude. */
        data class Recording(val elapsedMs: Long, val level: Float) : State

        /** Buffer captured, running the (native) VAD-trim + embedding off the main thread. */
        data object Processing : State

        /**
         * Enrolled and stored as a new person with id [personId]. [warning] present when the voice
         * is close to an existing profile; [mergedIntoName] is set once the user has instead chosen
         * to fold this recording into that matched person via [addToExistingPerson] (at which point
         * [warning] is cleared — the standalone person created for this recording no longer exists).
         */
        data class Success(
            val name: String,
            val personId: Long,
            val warning: SimilarVoiceWarning?,
            val mergedIntoName: String? = null,
        ) : State

        /** Enrollment failed; [message] is user-facing (too quiet / too short / an error). */
        data class Failure(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Covers BOTH the mic capture and the (native) processing/insert — one cancellable job. */
    private var recordJob: Job? = null

    /**
     * Set by [abort]; guards the person insert so an enrollment that was aborted mid-capture or
     * mid-processing can never insert a [PersonEntity] after the user has left the screen (which
     * would otherwise race — and survive — a "Delete all data" wipe). Reset when a fresh [record]
     * begins.
     */
    private val aborted = AtomicBoolean(false)

    /** The live capture, tracked so [abort] can release the microphone immediately from any state. */
    @Volatile private var activeCapture: AudioCapture? = null

    /** Begin (or restart) a recording, then process and store under [name] with [colorIndex]. */
    fun record(name: String, colorIndex: Int) {
        if (recordJob?.isActive == true) return
        aborted.set(false)
        recordJob = viewModelScope.launch {
            val samples = try {
                captureSamples()
            } catch (e: Throwable) {
                _state.value = State.Failure(e.message ?: "Microphone error")
                return@launch
            }
            _state.value = State.Processing
            processAndStore(name.trim(), colorIndex, samples)
        }
    }

    /** Reset to Idle so the user can retry after a failure or a success. */
    fun reset() {
        _state.value = State.Idle
    }

    /**
     * Idempotent hard abort for every teardown path (screen ON_STOP, disposal, [onCleared]). Cancels
     * the record + processing job, releases the microphone immediately, and latches [aborted] so any
     * work still unwinding cannot insert a person afterward. Safe to call repeatedly and from any
     * state — a finished enrollment has nothing to cancel and no mic to release.
     */
    fun abort() {
        aborted.set(true)
        recordJob?.cancel()
        recordJob = null
        activeCapture?.let { runCatching { it.release() } }
        activeCapture = null
    }

    /** Foreground-only guarantee: releasing the ViewModel must never leave an invisible microphone. */
    override fun onCleared() {
        abort()
        super.onCleared()
    }

    /**
     * Marc's exact duplicate-enrollment case: instead of keeping the just-created person, fold this
     * recording's embeddings into the matched person from [State.Success.warning] and drop the new
     * one. Reuses [PersonDao.mergePersons] with the new person as the (stats-free) source and the
     * matched person as the target, so its embeddings combine the same way a manual "Merge into…"
     * would. No-op if the current state isn't a [State.Success] with a warning.
     */
    fun addToExistingPerson() {
        val current = _state.value as? State.Success ?: return
        val warning = current.warning ?: return
        viewModelScope.launch {
            try {
                personDao.mergePersons(sourceId = current.personId, targetId = warning.personId)
                _state.value = current.copy(warning = null, mergedIntoName = warning.name)
            } catch (e: Throwable) {
                _state.value = State.Failure(e.message ?: "Couldn't add to ${warning.name}")
            }
        }
    }

    /** Captures [RECORD_MS] of mic audio into one buffer, publishing live level + elapsed. */
    private suspend fun captureSamples(): FloatArray = withContext(Dispatchers.IO) {
        val capture = AudioCapture()
        activeCapture = capture
        val collected = ArrayList<FloatArray>()
        var total = 0
        val startMs = System.currentTimeMillis()
        capture.start()
        try {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= RECORD_MS) break
                val event = capture.events.receive()
                if (event is AudioEvent.Samples) {
                    collected += event.samples
                    total += event.samples.size
                    _state.value = State.Recording(min(elapsed, RECORD_MS), rms(event.samples))
                } else if (event is AudioEvent.Error) {
                    throw IllegalStateException(event.message)
                }
            }
        } finally {
            capture.release()
            activeCapture = null
        }
        val out = FloatArray(total)
        var pos = 0
        for (chunk in collected) {
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        out
    }

    private suspend fun processAndStore(name: String, colorIndex: Int, samples: FloatArray) {
        if (!engines.isInitialized) {
            _state.value = State.Failure("Speech engine is still starting — try again in a moment.")
            return
        }
        // Exclusive access to the single-owner native engines: if a live session holds them, refuse
        // rather than driving the shared VAD/extractor concurrently (Busy is surfaced verbatim).
        val lease = engines.tryAcquireLease(LEASE_OWNER)
        if (lease == null) {
            _state.value = State.Failure(EnrollmentResult.Failure.Busy().message)
            return
        }
        try {
            val processor = EnrollmentProcessor.create(engines.requireVad(), engines.requireExtractor())
            when (val result = processor.process(samples)) {
                is EnrollmentResult.Failure -> _state.value = State.Failure(result.message)
                is EnrollmentResult.Success -> {
                    val warning = runCatching {
                        processor.checkSimilarVoice(result, loadProfiles(personDao))
                    }.getOrNull()
                    // Final guard against the Delete-all race: if the user left during the ~10 s
                    // capture or this processing, do NOT create the person. `aborted` is latched by
                    // [abort]; `isActive` catches cooperative cancellation between the checks.
                    if (aborted.get() || !coroutineContext.isActive) return
                    val id = personDao.insert(
                        PersonEntity(
                            name = name,
                            colorIndex = colorIndex,
                            embeddings = EmbeddingCodec.encode(result.embeddings),
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    _state.value = State.Success(name, id, warning)
                }
            }
        } finally {
            lease.release()
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        val rms = sqrt(sum / samples.size).toFloat()
        // Speech RMS is typically ~0.02..0.2; scale so normal speech fills most of the meter.
        return (rms * 5f).coerceIn(0f, 1f)
    }

    companion object {
        /** Target enrollment recording length. The processor needs ≥5 s of net (trimmed) speech. */
        const val RECORD_MS: Long = 10_000L

        /** Engine-lease owner tag for enrollment (see [SherpaEngines.tryAcquireLease]). */
        private const val LEASE_OWNER = "enrollment"

        fun factory(engines: SherpaEngines, personDao: PersonDao): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EnrollViewModel(engines, personDao) as T
            }
    }
}
