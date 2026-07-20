package uk.co.riera.talkbalance.session

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.riera.talkbalance.audio.AudioCapture
import uk.co.riera.talkbalance.audio.AudioEvent
import uk.co.riera.talkbalance.audio.SegmenterEvent
import uk.co.riera.talkbalance.audio.SpeechSegmenter
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.PersonEntity
import uk.co.riera.talkbalance.data.SessionDao
import uk.co.riera.talkbalance.ml.MatcherMode
import uk.co.riera.talkbalance.ml.SherpaEngines
import uk.co.riera.talkbalance.ml.SherpaSpeakerMatcher
import uk.co.riera.talkbalance.ml.SpeakerMatcherFactory

/**
 * Owns the **in-memory** live-session pipeline. Nothing here ever writes audio to disk — this class
 * is the privacy-honest replacement for the old M4A `RecordingService`.
 *
 * Wiring (single consumer coroutine, because the sherpa JNI surface is not thread-safe):
 * ```
 * AudioCapture ──Channel<AudioEvent>──▶ SpeechSegmenter ──SpeechChunk──▶ SessionEngine.onChunk()
 *                                            └ isSpeechActive ──────────▶ SessionEngine.onSpeechActive()
 * ```
 * All VAD (segmenter) and embedding (matcher, inside the engine) access happens on one
 * single-parallelism dispatcher — the same discipline `EnrollmentProcessor` uses — so the shared
 * [Vad] / [SpeakerEmbeddingExtractor] borrowed from [SherpaEngines] are never touched concurrently.
 * A live session and an enrollment must not run at once (separate screens).
 *
 * [uiState] mirrors the engine's live state for the UI. [stop] halts capture, finalises the engine,
 * and persists the [SessionResult] via [SessionDao]. Build instances with [create].
 */
class SessionController private constructor(
    vad: Vad,
    private val engine: SessionEngine,
    private val matcher: SherpaSpeakerMatcher,
    private val sessionDao: SessionDao,
    /** speakerId (as the engine used it) -> Room person id, for persisting per-speaker rows. */
    private val speakerIdToPersonId: Map<String, Long>,
    private val audioCapture: AudioCapture,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val segmenter = SpeechSegmenter(vad)
    private var consumerJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)

    /** Non-null once the capture pipeline hits an unrecoverable error (mic stolen, etc.). */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The live UI state, straight from the engine. */
    val uiState: StateFlow<SessionUiState> = engine.uiState

    /** Idempotent guard so a double-tap Start doesn't launch two capture threads. */
    private var started = false

    /**
     * Starts the microphone capture and the single consumer coroutine. Caller must already hold
     * `RECORD_AUDIO`. Safe to call once per controller instance.
     */
    fun start() {
        if (started) return
        started = true
        engine.start()
        audioCapture.start()
        consumerJob = scope.launch {
            // Draining the channel with a `for` loop terminates when the channel is closed
            // (AudioCapture.release()), after every buffered event has been processed.
            for (event in audioCapture.events) {
                when (event) {
                    is AudioEvent.Samples -> {
                        for (segEvent in segmenter.process(event)) {
                            when (segEvent) {
                                is SegmenterEvent.Chunk -> engine.onChunk(segEvent.chunk)
                                is SegmenterEvent.Error -> _error.value = segEvent.message
                            }
                        }
                        engine.onSpeechActive(segmenter.isSpeechActive.value)
                    }
                    is AudioEvent.Error -> _error.value = event.message
                }
            }
        }
    }

    /**
     * Stops capture, drains any in-flight audio, finalises the engine and persists the session.
     * Returns the finished [SessionResult]. The blocking [AudioCapture.release] (it joins the
     * capture thread, ≤1 s) is moved off the caller's thread.
     */
    suspend fun stop(): SessionResult {
        // release() stops the capture thread AND closes the channel, which lets the consumer's
        // for-loop finish once buffered events are drained.
        withContext(Dispatchers.IO) { runCatching { audioCapture.release() } }
        consumerJob?.join()

        val result = engine.stop()
        runCatching { matcher.close() }
        persist(result)
        return result
    }

    private suspend fun persist(result: SessionResult) {
        val (session, stats) = SessionPersistence.toEntities(result, speakerIdToPersonId)
        runCatching { sessionDao.insertSessionWithStats(session, stats) }
            .onFailure { Log.e(TAG, "failed to persist session", it) }
    }

    companion object {
        private const val TAG = "SessionController"

        /** Single-parallelism dispatcher so the shared sherpa objects are never touched concurrently. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val PIPELINE_DISPATCHER: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

        /**
         * Builds a ready-to-[start] controller from the app's shared engines and the enrolled
         * people. Runs the (native) matcher construction on the pipeline dispatcher so no sherpa
         * work touches the extractor off-thread.
         *
         * @param mode Table (search across everyone) or Me (verify against [mePersonId] only).
         * @param mePersonId required for [Mode.ME]: the Room id of the "me" person.
         */
        suspend fun create(
            engines: SherpaEngines,
            personDao: PersonDao,
            sessionDao: SessionDao,
            mode: Mode,
            threshold: Float,
            mePersonId: Long? = null,
        ): SessionController {
            val extractor: SpeakerEmbeddingExtractor = engines.requireExtractor()
            val vad: Vad = engines.requireVad()
            vad.reset()

            val people: List<PersonEntity> = personDao.observeAll().first()
            val matcherMode = if (mode == Mode.ME) MatcherMode.ME else MatcherMode.TABLE

            val displayNames: Map<String, String>
            val speakerIdToPersonId: Map<String, Long>
            when (mode) {
                Mode.TABLE -> {
                    displayNames = people.associate { it.id.toString() to it.name }
                    speakerIdToPersonId = people.associate { it.id.toString() to it.id }
                }
                Mode.ME -> {
                    val me = people.first { it.id == mePersonId }
                    displayNames = mapOf(SherpaSpeakerMatcher.ME_ID to me.name)
                    speakerIdToPersonId = mapOf(SherpaSpeakerMatcher.ME_ID to me.id)
                }
            }

            val matcher = withContext(PIPELINE_DISPATCHER) {
                SpeakerMatcherFactory.build(
                    personDao = personDao,
                    extractor = extractor,
                    threshold = threshold,
                    mode = matcherMode,
                    mePersonId = mePersonId,
                )
            }
            val engine = SessionEngine(matcher, mode, displayNames)

            return SessionController(
                vad = vad,
                engine = engine,
                matcher = matcher,
                sessionDao = sessionDao,
                speakerIdToPersonId = speakerIdToPersonId,
                audioCapture = AudioCapture(),
                dispatcher = PIPELINE_DISPATCHER,
            )
        }
    }
}
