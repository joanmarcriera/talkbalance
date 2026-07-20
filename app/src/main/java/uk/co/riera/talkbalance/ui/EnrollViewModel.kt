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

        /** Enrolled and stored. [warning] present when the voice is close to an existing profile. */
        data class Success(val name: String, val warning: SimilarVoiceWarning?) : State

        /** Enrollment failed; [message] is user-facing (too quiet / too short / an error). */
        data class Failure(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recordJob: Job? = null

    /** Begin (or restart) a recording, then process and store under [name] with [colorIndex]. */
    fun record(name: String, colorIndex: Int) {
        if (recordJob?.isActive == true) return
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

    /** Captures [RECORD_MS] of mic audio into one buffer, publishing live level + elapsed. */
    private suspend fun captureSamples(): FloatArray = withContext(Dispatchers.IO) {
        val capture = AudioCapture()
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
        val processor = EnrollmentProcessor.create(engines.requireVad(), engines.requireExtractor())
        when (val result = processor.process(samples)) {
            is EnrollmentResult.Failure -> _state.value = State.Failure(result.message)
            is EnrollmentResult.Success -> {
                val warning = runCatching {
                    processor.checkSimilarVoice(result, loadProfiles(personDao))
                }.getOrNull()
                personDao.insert(
                    PersonEntity(
                        name = name,
                        colorIndex = colorIndex,
                        embeddings = EmbeddingCodec.encode(result.embeddings),
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                _state.value = State.Success(name, warning)
            }
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

        fun factory(engines: SherpaEngines, personDao: PersonDao): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EnrollViewModel(engines, personDao) as T
            }
    }
}
