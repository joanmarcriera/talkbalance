package uk.co.riera.talkbalance.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the pure [SessionStats] accumulator — the most test-critical class
 * in the session layer. Synthetic attributed-segment streams in, exact talkMs / turns /
 * longestMonologue / totalSpeechMs out.
 */
class SessionStatsTest {

    private fun StatsSnapshot.of(id: String): SpeakerStat =
        speakers.first { it.speakerId == id }

    // --- empty session ---

    @Test fun emptySessionHasNoSpeakersAndZeroTotal() {
        val snapshot = SessionStats().snapshot()
        assertEquals(0L, snapshot.totalSpeechMs)
        assertTrue(snapshot.speakers.isEmpty())
    }

    // --- talkMs + totalSpeechMs (the share denominator) ---

    @Test fun talkMsSumsPerSpeakerAndTotalIsTheirSum() {
        val stats = SessionStats()
        stats.add("A", 0, 2_000)      // 2000
        stats.add("B", 5_000, 6_000)  // 1000
        stats.add("A", 10_000, 13_000) // 3000  (new turn: >1s gap)

        val snapshot = stats.snapshot()
        assertEquals(5_000L, snapshot.of("A").talkMs)
        assertEquals(1_000L, snapshot.of("B").talkMs)
        assertEquals(6_000L, snapshot.totalSpeechMs)
        // share = talkMs / totalSpeechMs
        assertEquals(5.0 / 6.0, snapshot.of("A").talkMs.toDouble() / snapshot.totalSpeechMs, 1e-9)
    }

    @Test fun zeroLengthSegmentAddsNoTalkTimeButIsTracked() {
        val stats = SessionStats()
        stats.add("A", 1_000, 1_000) // zero-length
        val snapshot = stats.snapshot()
        assertEquals(0L, snapshot.of("A").talkMs)
        assertEquals(0L, snapshot.totalSpeechMs)
        assertEquals(1, snapshot.of("A").turns) // still counts as a (degenerate) turn
    }

    // --- turns: single segment, speaker change, gap boundary ---

    @Test fun singleSegmentIsOneTurnFoldedIntoSnapshot() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        val a = stats.snapshot().of("A")
        assertEquals(1, a.turns)
        assertEquals(1_000L, a.longestMonologueMs)
    }

    @Test fun speakerChangeStartsANewTurnForEach() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)     // A turn 1
        stats.add("B", 1_000, 2_000) // B turn 1 (back-to-back, but different speaker)
        stats.add("A", 2_000, 3_000) // A turn 2

        val snapshot = stats.snapshot()
        assertEquals(2, snapshot.of("A").turns)
        assertEquals(1, snapshot.of("B").turns)
    }

    @Test fun sameSpeakerRunWithSubSecondGapsIsOneTurn() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add("A", 1_500, 2_000) // gap 500 < 1000 -> same turn
        stats.add("A", 2_400, 3_000) // gap 400 < 1000 -> same turn
        assertEquals(1, stats.snapshot().of("A").turns)
    }

    @Test fun turnGapBoundary999MsContinuesTheSameTurn() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add("A", 1_999, 2_500) // gap = 1999 - 1000 = 999 (< 1000) -> same turn
        val a = stats.snapshot().of("A")
        assertEquals(1, a.turns)
        assertEquals(2_500L, a.longestMonologueMs) // span 0..2500
    }

    @Test fun turnGapBoundary1000MsStartsANewTurn() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add("A", 2_000, 2_500) // gap = exactly 1000 (not < 1000) -> new turn
        val a = stats.snapshot().of("A")
        assertEquals(2, a.turns)
        assertEquals(1_000L, a.longestMonologueMs) // longest of spans {1000, 500}
    }

    @Test fun turnGapBoundary1001MsStartsANewTurn() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add("A", 2_001, 2_500) // gap = 1001 -> new turn
        assertEquals(2, stats.snapshot().of("A").turns)
    }

    // --- longestMonologue = max turn wall-clock span ---

    @Test fun longestMonologueIsMaxTurnSpanIncludingInternalPauses() {
        val stats = SessionStats()
        // Turn 1 (A): 0..1000, then 1900..2000 (gap 900) -> one turn spanning 0..2000 = 2000 ms.
        stats.add("A", 0, 1_000)
        stats.add("A", 1_900, 2_000)
        // Turn 2 (B) forces A's turn 1 to close.
        stats.add("B", 3_500, 4_000)
        // Turn 3 (A): a single 600 ms segment.
        stats.add("A", 6_000, 6_600)

        val a = stats.snapshot().of("A")
        assertEquals(2, a.turns)
        // spans: {2000, 600} -> longest 2000, even though talk time in turn 1 is only 1100 ms.
        assertEquals(2_000L, a.longestMonologueMs)
        assertEquals(1_100L + 600L, a.talkMs)
    }

    // --- everyone-else accrual ---

    @Test fun everyoneElseAccruesUnderTheSentinelBucket() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add(SessionStats.EVERYONE_ELSE, 1_000, 4_000) // 3000 ms unmatched
        stats.add("A", 4_000, 5_000)

        val snapshot = stats.snapshot()
        val everyone = snapshot.of(SessionStats.EVERYONE_ELSE)
        assertEquals(3_000L, everyone.talkMs)
        assertEquals(1, everyone.turns)
        assertEquals(2, snapshot.of("A").turns) // everyone-else in between splits A's turns
        assertEquals(5_000L, snapshot.totalSpeechMs)
    }

    // --- snapshot is non-mutating and re-callable ---

    @Test fun snapshotIsNonMutatingAndReflectsFurtherAdds() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        val first = stats.snapshot()
        assertEquals(1, first.of("A").turns)

        // Calling snapshot() again, then adding more, must not have committed the open turn twice.
        stats.snapshot()
        stats.add("A", 1_200, 1_800) // still same turn (gap 200)
        val second = stats.snapshot()
        assertEquals(1, second.of("A").turns)
        assertEquals(1_600L, second.of("A").talkMs)
        assertEquals(1_800L, second.of("A").longestMonologueMs)
    }

    @Test fun speakersPreserveFirstSeenOrder() {
        val stats = SessionStats()
        stats.add("C", 0, 100)
        stats.add("A", 2_000, 2_100)
        stats.add("B", 4_000, 4_100)
        assertEquals(listOf("C", "A", "B"), stats.snapshot().speakers.map { it.speakerId })
    }

    // --- timeline segments (for the graphs) ---

    @Test fun segmentsRecordEveryAddInArrivalOrder() {
        val stats = SessionStats()
        stats.add("A", 0, 1_000)
        stats.add("B", 1_000, 2_500)
        stats.add("A", 2_500, 3_000)

        val segments = stats.snapshot().segments
        assertEquals(3, segments.size)
        assertEquals(listOf("A", "B", "A"), segments.map { it.speakerId })
        assertEquals(TalkSegment("B", 1_000, 2_500), segments[1])
    }

    @Test fun segmentsAreCappedKeepingTheMostRecent() {
        val stats = SessionStats()
        val n = SessionStats.SEGMENT_CAP + 50
        for (i in 0 until n) stats.add("A", i * 10L, i * 10L + 5L)

        val segments = stats.snapshot().segments
        assertEquals(SessionStats.SEGMENT_CAP, segments.size)
        // Oldest 50 dropped; the newest is the last add.
        assertEquals((n - 1) * 10L, segments.last().startMs)
        assertEquals(50 * 10L, segments.first().startMs)
    }
}
