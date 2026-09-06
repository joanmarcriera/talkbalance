package uk.co.riera.talkbalance.session

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import uk.co.riera.talkbalance.audio.AudioCapture
import uk.co.riera.talkbalance.audio.AudioEvent
import uk.co.riera.talkbalance.audio.SegmenterEvent
import uk.co.riera.talkbalance.audio.SpeechSegmenter
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.PersonEntity
import uk.co.riera.talkbalance.data.SessionDao
import uk.co.riera.talkbalance.ml.EngineLeaseHolder
import uk.co.riera.talkbalance.ml.MatcherMode
import uk.co.riera.talkbalance.ml.SherpaEngines
import uk.co.riera.talkbalance.ml.SherpaSpeakerMatcher
import uk.co.riera.talkbalance.ml.SpeakerMatcher
import uk.co.riera.talkbalance.ml.SpeakerMatcherFactory

/** Whether the finished session was written to Room. Surfaced to the report so a save failure is honest. */
sealed interface PersistState {
    /** Not yet attempted / in progress. */
    data object Saving : PersistState

    /** Written to Room successfully. */
    data object Saved : PersistState

    /** Room write failed; [message] is user-facing and a retry is available. */
    data class Failed(val message: String) : PersistState
}

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
 * A live session and an enrollment must not run at once: this is now enforced by an
 * [SherpaEngines.EngineLease] the controller holds for its whole life (acquired in [create]).
 *
 * **Foreground-only for this release.** There is no foreground service or recording notification, so
 * a session is only valid while its UI is on screen. [close] is the hard stop for every teardown
 * path (ViewModel `onCleared`, navigation dispose, Activity recreation): it cancels the owned scope,
 * releases capture, the matcher, and the engine lease, and is idempotent + safe from any state.
 *
 * [uiState] mirrors the engine's live state for the UI. [stop] halts capture, drains and flushes the
 * final utterance, finalises the engine, and persists the [SessionResult] via [SessionDao]. Build
 * instances with [create].
 */
class SessionController private constructor(
    vad: Vad,
    private val engine: SessionEngine,
    /** Releases the matcher's native resources (no-op for the enrollment-free discover matcher). */
    private val closeMatcher: () -> Unit,
    private val sessionDao: SessionDao,
    /** speakerId (as the engine used it) -> Room person id, for persisting per-speaker rows. */
    private val speakerIdToPersonId: Map<String, Long>,
    private val audioCapture: AudioCapture,
    private val dispatcher: CoroutineDispatcher,
    /** Held for the whole session; released by [stop]/[close] so enrollment can run afterwards. */
    private val lease: EngineLeaseHolder.Lease,
) {
    /**
     * speakerId -> final display label, persisted for discovered speakers so a renamed/auto-labelled
     * voice keeps its name in the saved report. The UI updates this live (renames + first-seen
     * "Speaker N" defaults); [persist] reads whatever is current at save time. Empty for enrolled
     * modes (names come from the person rows).
     */
    @Volatile
    var speakerLabels: Map<String, String> = emptyMap()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val segmenter = SpeechSegmenter(vad)
    private var consumerJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)

    /** Non-null once the capture pipeline hits an unrecoverable error (mic stolen, etc.). */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The live UI state, straight from the engine. */
    val uiState: StateFlow<SessionUiState> = engine.uiState

    private val _persistState = MutableStateFlow<PersistState>(PersistState.Saving)

    /** Whether the finished session has been saved; drives the report's saved/retry affordance. */
    val persistState: StateFlow<PersistState> = _persistState.asStateFlow()

    /** Idempotent guard so a double-tap Start doesn't launch two capture threads. */
    private var started = false

    /** Atomic single-shot guards so double Stop / Stop-after-close never re-run teardown. */
    private val stopStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val nativeReleased = AtomicBoolean(false)
    private val stopResult = CompletableDeferred<SessionResult>()

    /** Kept so a persist retry doesn't need the engine (which is finalised by then). */
    @Volatile private var finishedResult: SessionResult? = null

    /** Row id of the currently-saved session, so a label re-save replaces it instead of duplicating. */
    @Volatile private var savedSessionId: Long? = null

    /**
     * Starts the microphone capture and the single consumer coroutine. Caller must already hold
     * `RECORD_AUDIO`. Safe to call once per controller instance.
     */
    fun start() {
        if (started || closed.get()) return
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
     * Stops capture, drains any in-flight audio, **flushes the final utterance** out of the VAD,
     * finalises the engine and persists the session. Returns the finished [SessionResult].
     * Idempotent: a second call (e.g. a double Stop tap) awaits and returns the same result rather
     * than finalising the non-idempotent engine twice.
     */
    suspend fun stop(): SessionResult {
        if (!stopStarted.compareAndSet(false, true)) {
            return stopResult.await()
        }
        try {
            // release() stops the capture thread AND closes the channel, which lets the consumer's
            // for-loop finish once buffered events are drained. It's blocking (joins the thread),
            // so move it off the caller's dispatcher.
            withContext(Dispatchers.IO) { runCatching { audioCapture.release() } }
            consumerJob?.join()

            // Flush the segmenter tail on the single pipeline thread and feed it to the engine, so
            // an utterance still buffered by the VAD when Stop was pressed is counted, not dropped.
            // Skip if an abort (close) already released the matcher — engine.onChunk would touch it.
            if (!closed.get()) {
                withContext(dispatcher) {
                    if (engine.state == EngineState.RUNNING) {
                        for (chunk in segmenter.flush()) engine.onChunk(chunk)
                    }
                }
            }

            val result = engine.stop()
            finishedResult = result
            releaseNative()
            persist(result)
            stopResult.complete(result)
            return result
        } catch (t: Throwable) {
            releaseNative()
            stopResult.completeExceptionally(t)
            throw t
        }
    }

    /**
     * Hard abort for every UI-teardown path. Cancels the owned scope (killing the consumer and
     * capture), releases the matcher and the engine lease, and stops the microphone. Idempotent and
     * safe to call from any state, multiple times — leaving the session screen must never leave the
     * microphone alive.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Always kill the microphone immediately, whatever else is happening.
        runCatching { audioCapture.release() }
        // If a stop() is already finalising, it owns the engine/matcher/lease teardown — don't
        // cancel it or release native state out from under it; it will release everything itself.
        if (stopStarted.get()) return
        scope.cancel()
        releaseNative()
    }

    /** Retry a failed persist (the report's "couldn't save — retry" action). No-op if already saved. */
    fun retryPersist() {
        val result = finishedResult ?: return
        if (_persistState.value is PersistState.Saved) return
        scope.launch { persist(result) }
    }

    /**
     * Re-save the finished session with the current [speakerLabels] (a rename on the report screen).
     * No-op before the session is finished — during a live session, renames only update
     * [speakerLabels] and are written by the [stop] persist. Replaces the saved row rather than
     * inserting a duplicate.
     */
    fun resaveWithCurrentLabels() {
        val result = finishedResult ?: return
        scope.launch { persist(result) }
    }

    /** Releases the native matcher and the engine lease exactly once (from stop or close). */
    private fun releaseNative() {
        if (!nativeReleased.compareAndSet(false, true)) return
        runCatching { closeMatcher() }
        lease.release()
    }

    private suspend fun persist(result: SessionResult) {
        _persistState.value = PersistState.Saving
        val (session, stats) =
            SessionPersistence.toEntities(result, speakerIdToPersonId, speakerLabels)
        runCatching {
            // Replace any previous save (a label re-save) so a renamed session never duplicates.
            // One transaction: a failed insert must not leave the earlier save already deleted.
            sessionDao.replaceSessionWithStats(savedSessionId, session, stats)
        }
            .onSuccess {
                savedSessionId = it
                _persistState.value = PersistState.Saved
            }
            .onFailure {
                Log.e(TAG, "failed to persist session", it)
                _persistState.value = PersistState.Failed("Couldn't save this session.")
            }
    }

    companion object {
        private const val TAG = "SessionController"

        /** Engine-lease owner tag for a live session (see [SherpaEngines.tryAcquireLease]). */
        private const val LEASE_OWNER = "session"

        /** Single-parallelism dispatcher so the shared sherpa objects are never touched concurrently. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val PIPELINE_DISPATCHER: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

        /**
         * Builds a ready-to-[start] controller from the app's shared engines and the enrolled
         * people. Runs the (native) matcher construction on the pipeline dispatcher so no sherpa
         * work touches the extractor off-thread.
         *
         * Acquires the [SherpaEngines.EngineLease] up front and throws
         * [uk.co.riera.talkbalance.ml.EngineBusyException] if enrollment (or another session) holds
         * it — so two owners can never drive the shared VAD/extractor at once.
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
            val lease = engines.tryAcquireLease(LEASE_OWNER)
                ?: throw uk.co.riera.talkbalance.ml.EngineBusyException(engines.leaseOwner ?: "unknown")
            try {
                val extractor: SpeakerEmbeddingExtractor = engines.requireExtractor()
                val vad: Vad = engines.requireVad()
                vad.reset()

                // Build the mode's matcher, its display-name map and the speakerId -> personId map.
                // Discover mode needs no enrolled people: an OnlineClusterMatcher discovers voices
                // live as "s1"/"s2"/…, so there are no person rows and nothing native to close.
                val matcher: SpeakerMatcher
                val closeMatcher: () -> Unit
                val displayNames: Map<String, String>
                val speakerIdToPersonId: Map<String, Long>

                if (mode == Mode.DISCOVER) {
                    // Discover uses the same tunable threshold slider (Settings > Developer) so it
                    // can be dialled in live: higher = splits more (more speakers), lower = merges more.
                    matcher = withContext(PIPELINE_DISPATCHER) {
                        uk.co.riera.talkbalance.ml.OnlineClusterMatcher.create(
                            extractor,
                            joinThreshold = threshold,
                        )
                    }
                    closeMatcher = {}
                    displayNames = emptyMap() // UI assigns "Speaker N" labels itself.
                    speakerIdToPersonId = emptyMap()
                } else {
                    val people: List<PersonEntity> = personDao.observeAll().first()
                    val matcherMode = if (mode == Mode.ME) MatcherMode.ME else MatcherMode.TABLE
                    when (mode) {
                        Mode.TABLE -> {
                            displayNames = people.associate { it.id.toString() to it.name }
                            speakerIdToPersonId = people.associate { it.id.toString() to it.id }
                        }
                        Mode.ME -> {
                            // firstOrNull + a written message: first{} surfaces
                            // "Collection contains no element matching the predicate" straight into
                            // Phase.Failed as user-facing copy. SpeakerMatcherFactory already
                            // handles the same case this way, but runs after this block.
                            val me = people.firstOrNull { it.id == mePersonId }
                                ?: error(
                                    "The person selected for Me mode is no longer enrolled — " +
                                        "pick someone else or re-enrol them.",
                                )
                            displayNames = mapOf(SherpaSpeakerMatcher.ME_ID to me.name)
                            speakerIdToPersonId = mapOf(SherpaSpeakerMatcher.ME_ID to me.id)
                        }
                        Mode.DISCOVER -> error("unreachable")
                    }
                    val sherpaMatcher = withContext(PIPELINE_DISPATCHER) {
                        SpeakerMatcherFactory.build(
                            personDao = personDao,
                            extractor = extractor,
                            threshold = threshold,
                            mode = matcherMode,
                            mePersonId = mePersonId,
                        )
                    }
                    matcher = sherpaMatcher
                    closeMatcher = { sherpaMatcher.close() }
                }

                val engine = SessionEngine(matcher, mode, displayNames)

                return SessionController(
                    vad = vad,
                    engine = engine,
                    closeMatcher = closeMatcher,
                    sessionDao = sessionDao,
                    speakerIdToPersonId = speakerIdToPersonId,
                    audioCapture = AudioCapture(),
                    dispatcher = PIPELINE_DISPATCHER,
                    lease = lease,
                )
            } catch (t: Throwable) {
                // Build failed after acquiring the lease — don't strand it.
                lease.release()
                throw t
            }
        }
    }
}
