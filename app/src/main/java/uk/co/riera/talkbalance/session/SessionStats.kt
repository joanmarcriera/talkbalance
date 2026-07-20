package uk.co.riera.talkbalance.session

/**
 * Immutable per-speaker stats for a single speaker at a point in time. Maps 1:1 onto
 * `data/SpeakerStatEntity` (T9 supplies `sessionId` and resolves [speakerId] -> `personId` /
 * `isEveryoneElse` when persisting; the pure accumulator stays free of Room).
 */
data class SpeakerStat(
    val speakerId: String,
    val talkMs: Long,
    val turns: Int,
    val longestMonologueMs: Long,
)

/**
 * Immutable snapshot of the whole session's talk statistics. [totalSpeechMs] is the sum of every
 * speaker's [SpeakerStat.talkMs] (incl. the "everyone else" bucket) and is the denominator the UI
 * uses for talk-share. [speakers] preserves first-seen order; callers that want a share-sorted view
 * (e.g. [SessionEngine]) sort it themselves.
 */
data class StatsSnapshot(
    val totalSpeechMs: Long,
    val speakers: List<SpeakerStat>,
)

/**
 * Pure-Kotlin (no Android imports) accumulator over a stream of already-attributed speech segments.
 *
 * This is the most test-critical class in the session layer: [SessionEngine] does the fuzzy
 * attribution (matching + continuation heuristic + everyone-else bucketing), then feeds the
 * *decided* segment here, so every rule below is deterministic and unit-tested against synthetic
 * streams.
 *
 * ## Definitions (from `PLAN.md` §Pipeline)
 * - **Segment**: one attributed `[startMs, endMs)` interval credited to a single speaker id.
 * - **Turn**: a maximal run of *consecutive same-speaker* segments whose inter-segment gap
 *   (`thisStart - previousEnd`) is **strictly < [TURN_GAP_MS]** (1000 ms). A speaker change, or a
 *   gap `>= [TURN_GAP_MS]` even for the same speaker, starts a new turn. (Boundary: a 999 ms gap
 *   continues the turn; 1000 ms or 1001 ms starts a new one.)
 * - **talkMs**: sum of segment durations credited to the speaker (actual speech only; gaps excluded).
 * - **longestMonologueMs**: the longest single turn's wall-clock span (`turnEnd - turnStart`,
 *   including the sub-second internal pauses that keep the run one turn).
 *
 * Segments are expected in non-decreasing start order (as the segmenter emits them). Not
 * thread-safe: feed from the single pipeline consumer.
 */
class SessionStats {

    private class Acc(
        var talkMs: Long = 0L,
        var turns: Int = 0,
        var longestMonologueMs: Long = 0L,
    )

    // Insertion-ordered so snapshots have a stable, first-seen speaker order.
    private val accumulators = LinkedHashMap<String, Acc>()
    private var totalSpeechMs = 0L

    // The turn currently being extended. Folded into every snapshot() but only committed to the
    // speaker's `turns` / `longestMonologueMs` when a boundary closes it (see [finalizeOpenTurn]).
    private var openSpeaker: String? = null
    private var openTurnStartMs = 0L
    private var openTurnEndMs = 0L

    /**
     * Add one attributed segment. [speakerId] is an enrolled speaker's id or [EVERYONE_ELSE].
     * A zero-length segment (`endMs == startMs`) contributes no talk time but still participates in
     * turn tracking.
     */
    fun add(speakerId: String, startMs: Long, endMs: Long) {
        require(endMs >= startMs) { "endMs ($endMs) must be >= startMs ($startMs)" }

        val acc = accumulators.getOrPut(speakerId) { Acc() }
        val duration = endMs - startMs
        acc.talkMs += duration
        totalSpeechMs += duration

        val current = openSpeaker
        when {
            current == null ->
                openTurn(speakerId, startMs, endMs)

            speakerId == current && startMs - openTurnEndMs < TURN_GAP_MS ->
                // Same speaker, gap under threshold: extend the open turn.
                openTurnEndMs = maxOf(openTurnEndMs, endMs)

            else -> {
                // Speaker change, or same speaker after a >= TURN_GAP_MS gap: boundary.
                finalizeOpenTurn()
                openTurn(speakerId, startMs, endMs)
            }
        }
    }

    /**
     * Immutable snapshot of the session so far. Non-mutating: the still-open turn is folded in
     * (counts as one turn and is a candidate for that speaker's longest monologue) without
     * committing it, so repeated calls and further [add]s stay correct.
     */
    fun snapshot(): StatsSnapshot {
        val speakers = accumulators.map { (id, acc) ->
            var turns = acc.turns
            var longest = acc.longestMonologueMs
            if (id == openSpeaker) {
                turns += 1
                longest = maxOf(longest, openTurnEndMs - openTurnStartMs)
            }
            SpeakerStat(
                speakerId = id,
                talkMs = acc.talkMs,
                turns = turns,
                longestMonologueMs = longest,
            )
        }
        return StatsSnapshot(totalSpeechMs = totalSpeechMs, speakers = speakers)
    }

    private fun openTurn(speakerId: String, startMs: Long, endMs: Long) {
        openSpeaker = speakerId
        openTurnStartMs = startMs
        openTurnEndMs = endMs
    }

    private fun finalizeOpenTurn() {
        val speaker = openSpeaker ?: return
        val acc = accumulators.getValue(speaker)
        acc.turns += 1
        acc.longestMonologueMs = maxOf(acc.longestMonologueMs, openTurnEndMs - openTurnStartMs)
        openSpeaker = null
    }

    companion object {
        /** Sentinel speaker id for the aggregated "not an enrolled match" bucket. */
        const val EVERYONE_ELSE: String = "__everyone_else__"

        /** Inter-segment gap (ms) at/above which a same-speaker run splits into a new turn. */
        const val TURN_GAP_MS: Long = 1_000L
    }
}
