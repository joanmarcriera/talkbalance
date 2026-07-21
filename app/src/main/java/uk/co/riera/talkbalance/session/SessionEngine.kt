package uk.co.riera.talkbalance.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.riera.talkbalance.audio.SpeechChunk
import uk.co.riera.talkbalance.ml.SpeakerMatcher

/** Lifecycle of a single measured session. */
enum class EngineState { IDLE, RUNNING, ENDED }

/**
 * Session mode. Only affects how the "everyone else" bucket is *labelled* in the UI — the actual
 * me/not-me (`verify`) vs multi-speaker (`search`) difference lives inside the [SpeakerMatcher]
 * implementation (T7), not here.
 */
enum class Mode(val everyoneElseLabel: String) {
    /** Table Mode: several enrolled people; unmatched speech is "Everyone else". */
    TABLE("Everyone else"),

    /** Me Mode: only "me" is enrolled; everything not-me is "Others". */
    ME("Others"),

    /**
     * Discover Mode (the default): no enrollment. An [uk.co.riera.talkbalance.ml.OnlineClusterMatcher]
     * discovers voices live and attributes **every** chunk to a discovered speaker id ("s1", "s2", …),
     * so the "everyone else" bucket never appears. The label here is only a never-used fallback.
     */
    DISCOVER("Others"),
}

/** One bar in the live UI, already resolved to a display label with its talk-share computed. */
data class SpeakerShare(
    val speakerId: String,
    val label: String,
    val talkMs: Long,
    /** Fraction of [SessionUiState]'s total speech in `[0, 1]` (0 when no speech yet). */
    val share: Double,
    val isEveryoneElse: Boolean,
)

/**
 * Everything the session UI (T11) renders, as one immutable value. Emitted on [SessionEngine.uiState].
 */
data class SessionUiState(
    val state: EngineState,
    val elapsedMs: Long,
    /** True while the VAD currently hears speech; drives the "someone is speaking" pulse. */
    val isSpeechActive: Boolean,
    /** Per-speaker bars, sorted by [SpeakerShare.share] descending (everyone-else last on ties). */
    val speakers: List<SpeakerShare>,
    /** Recent attributed segments (oldest first, capped) for the live timeline ribbon. */
    val segments: List<TalkSegment> = emptyList(),
) {
    companion object {
        fun idle(): SessionUiState =
            SessionUiState(EngineState.IDLE, elapsedMs = 0L, isSpeechActive = false, speakers = emptyList())
    }
}

/**
 * Finalized session output, produced by [SessionEngine.stop]. Maps onto `data/SessionEntity` +
 * `data/SpeakerStatEntity`: T9 supplies the DB ids, resolves each [SpeakerStat.speakerId] to a
 * `personId` (or the [SessionStats.EVERYONE_ELSE] sentinel -> `isEveryoneElse = true`, `personId =
 * null`), and persists. The engine itself stays Room-free.
 */
data class SessionResult(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val mode: Mode,
    val speakers: List<SpeakerStat>,
    /** Whole-session attributed timeline (capped) for the report's timeline graph. */
    val segments: List<TalkSegment> = emptyList(),
)

/**
 * State machine (IDLE -> RUNNING -> ENDED) that turns the raw [SpeechChunk] stream into live talk
 * statistics.
 *
 * For each chunk it calls [SpeakerMatcher], applies the `PLAN.md` §Pipeline attribution rules, feeds
 * the *decided* segment into a pure [SessionStats], and re-emits [uiState]. Attribution:
 * - **match** (matcher returns a non-null speaker id at/above threshold) => credit that speaker;
 * - **no match / below threshold** => credit [SessionStats.EVERYONE_ELSE] — UNLESS the
 *   **continuation heuristic** applies: a short segment (`< [CONTINUATION_MAX_MS]`, 0.8 s) arriving
 *   within [CONTINUATION_GAP_MS] (0.5 s) of the previous *enrolled* speaker's last segment is
 *   credited to that previous speaker instead (a brief dip below threshold mid-utterance).
 *
 * Minimal Android surface: only [StateFlow] (coroutines), no `android.*`. Single-consumer: drive
 * [onChunk] / [onSpeechActive] from the same thread as the segmenter (sherpa JNI is not
 * thread-safe). [now] is injectable so elapsed-time and the whole engine are deterministically
 * testable.
 */
class SessionEngine(
    private val matcher: SpeakerMatcher,
    private val mode: Mode,
    /** speakerId -> human display name for the UI bars; unknown ids fall back to the id itself. */
    private val displayNames: Map<String, String> = emptyMap(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stats = SessionStats()

    var state: EngineState = EngineState.IDLE
        private set

    private var startedAtMs: Long = 0L
    private var endedAtMs: Long = 0L
    private var speechActive: Boolean = false

    // Most recent segment credited to an *enrolled* speaker (never the everyone-else bucket) —
    // the continuation heuristic's anchor.
    private var lastEnrolledSpeakerId: String? = null
    private var lastEnrolledEndMs: Long = 0L

    private val _uiState = MutableStateFlow(SessionUiState.idle())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /** IDLE -> RUNNING. Marks the session start and emits the first (empty) running state. */
    fun start() {
        check(state == EngineState.IDLE) { "start() valid only from IDLE (was $state)" }
        state = EngineState.RUNNING
        startedAtMs = now()
        emit()
    }

    /**
     * Consume one finished speech chunk: attribute it and update the live stats. No-op unless
     * [state] is RUNNING (in-flight chunks may arrive just after [stop]; they are dropped rather
     * than throwing).
     */
    fun onChunk(chunk: SpeechChunk) {
        if (state != EngineState.RUNNING) return

        val attributedId = attribute(chunk)
        if (attributedId != SessionStats.EVERYONE_ELSE) {
            lastEnrolledSpeakerId = attributedId
            lastEnrolledEndMs = chunk.endMs
        }
        stats.add(attributedId, chunk.startMs, chunk.endMs)
        emit()
    }

    /** Feed the segmenter's speech-active pulse through to the UI state. Ignored unless RUNNING. */
    fun onSpeechActive(active: Boolean) {
        if (state != EngineState.RUNNING) return
        if (active == speechActive) return
        speechActive = active
        emit()
    }

    /**
     * RUNNING -> ENDED. Freezes elapsed time, clears the speech pulse, emits the final state and
     * returns the persistable [SessionResult].
     */
    fun stop(): SessionResult {
        check(state == EngineState.RUNNING) { "stop() valid only from RUNNING (was $state)" }
        endedAtMs = now()
        speechActive = false
        state = EngineState.ENDED
        emit()
        val finalSnapshot = stats.snapshot()
        return SessionResult(
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            durationMs = endedAtMs - startedAtMs,
            mode = mode,
            speakers = finalSnapshot.speakers,
            segments = finalSnapshot.segments,
        )
    }

    /** Applies the §Pipeline attribution rules to one chunk, returning the credited speaker id. */
    private fun attribute(chunk: SpeechChunk): String {
        val result = matcher.match(chunk)
        result.speakerId?.let { return it }

        // Below threshold / no match: continuation heuristic before falling back to everyone-else.
        val anchor = lastEnrolledSpeakerId
        val durationMs = chunk.endMs - chunk.startMs
        val gapMs = chunk.startMs - lastEnrolledEndMs
        val isContinuation = anchor != null &&
            durationMs < CONTINUATION_MAX_MS &&
            gapMs < CONTINUATION_GAP_MS
        return if (isContinuation) anchor!! else SessionStats.EVERYONE_ELSE
    }

    private fun emit() {
        val snapshot = stats.snapshot()
        val total = snapshot.totalSpeechMs
        val shares = snapshot.speakers
            .map { stat ->
                val isEveryoneElse = stat.speakerId == SessionStats.EVERYONE_ELSE
                SpeakerShare(
                    speakerId = stat.speakerId,
                    label = if (isEveryoneElse) mode.everyoneElseLabel
                        else displayNames[stat.speakerId] ?: stat.speakerId,
                    talkMs = stat.talkMs,
                    share = if (total > 0L) stat.talkMs.toDouble() / total.toDouble() else 0.0,
                    isEveryoneElse = isEveryoneElse,
                )
            }
            .sortedWith(compareByDescending<SpeakerShare> { it.share }.thenBy { it.isEveryoneElse })

        _uiState.value = SessionUiState(
            state = state,
            elapsedMs = elapsedMs(),
            isSpeechActive = speechActive,
            speakers = shares,
            segments = snapshot.segments,
        )
    }

    private fun elapsedMs(): Long = when (state) {
        EngineState.IDLE -> 0L
        EngineState.RUNNING -> now() - startedAtMs
        EngineState.ENDED -> endedAtMs - startedAtMs
    }

    companion object {
        /** A no-match segment shorter than this (ms) can be a continuation of the prior speaker. */
        const val CONTINUATION_MAX_MS: Long = 800L

        /** Max gap (ms) from the prior enrolled speaker for the continuation heuristic to apply. */
        const val CONTINUATION_GAP_MS: Long = 500L
    }
}
