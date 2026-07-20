package uk.co.riera.talkbalance.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.riera.talkbalance.audio.SpeechChunk
import uk.co.riera.talkbalance.ml.MatchResult
import uk.co.riera.talkbalance.ml.SpeakerMatcher

/**
 * Tests for [SessionEngine]'s state machine, §Pipeline attribution rules and the continuation
 * heuristic, using a scriptable fake [SpeakerMatcher] (the real one is T7). The heavy stats maths
 * lives in [SessionStatsTest]; here we assert *what gets attributed to whom* and the UI shape.
 */
class SessionEngineTest {

    /** Fake matcher: returns whatever [fn] decides for each chunk. */
    private class FakeMatcher(val fn: (SpeechChunk) -> MatchResult) : SpeakerMatcher {
        override fun match(chunk: SpeechChunk): MatchResult = fn(chunk)
    }

    private fun matched(id: String, similarity: Float = 0.9f) = MatchResult(id, similarity)
    private val noMatch = MatchResult(null, 0.12f)
    private fun chunk(startMs: Long, endMs: Long) = SpeechChunk(FloatArray(0), startMs, endMs)

    /** Adjustable clock so elapsed-time assertions are deterministic. */
    private class TestClock(var nowMs: Long = 0L) { fun read(): Long = nowMs }

    private fun StateFlowTalk(engine: SessionEngine, id: String) =
        engine.uiState.value.speakers.first { it.speakerId == id }

    // --- match => credit the enrolled speaker ---

    @Test fun matchCreditsTheEnrolledSpeaker() {
        val engine = SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE)
        engine.start()
        engine.onChunk(chunk(0, 2_000))
        engine.onChunk(chunk(2_500, 4_000))

        val speakers = engine.uiState.value.speakers
        assertEquals(listOf("A"), speakers.map { it.speakerId })
        assertEquals(3_500L, speakers.first().talkMs)
        assertEquals(1.0, speakers.first().share, 1e-9)
    }

    // --- no match => everyone-else, labelled per mode ---

    @Test fun noMatchGoesToEveryoneElseWithTableLabel() {
        val engine = SessionEngine(FakeMatcher { noMatch }, Mode.TABLE)
        engine.start()
        engine.onChunk(chunk(0, 3_000))

        val bucket = StateFlowTalk(engine, SessionStats.EVERYONE_ELSE)
        assertTrue(bucket.isEveryoneElse)
        assertEquals("Everyone else", bucket.label)
        assertEquals(3_000L, bucket.talkMs)
    }

    @Test fun meModeLabelsEveryoneElseAsOthers() {
        val engine = SessionEngine(FakeMatcher { noMatch }, Mode.ME)
        engine.start()
        engine.onChunk(chunk(0, 1_000))
        assertEquals("Others", StateFlowTalk(engine, SessionStats.EVERYONE_ELSE).label)
    }

    // --- continuation heuristic ---

    @Test fun shortBelowThresholdSegmentContinuesPreviousSpeaker() {
        // A matches; then a 600 ms no-match arriving 400 ms later => credited to A, not everyone-else.
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs == 0L) matched("A") else noMatch },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 1_000))       // matched A
        engine.onChunk(chunk(1_400, 2_000))   // dur 600 < 800, gap 400 < 500 => continuation to A

        val speakers = engine.uiState.value.speakers
        assertEquals(listOf("A"), speakers.map { it.speakerId }) // no everyone-else bucket at all
        assertEquals(1_600L, speakers.first().talkMs)
        assertNull(speakers.firstOrNull { it.isEveryoneElse })
    }

    @Test fun continuationChainsAcrossConsecutiveShortDips() {
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs == 0L) matched("A") else noMatch },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 1_000))       // A
        engine.onChunk(chunk(1_400, 1_900))   // gap 400, dur 500 => A (anchor end -> 1900)
        engine.onChunk(chunk(2_300, 2_700))   // gap 2300-1900=400, dur 400 => A again

        val speakers = engine.uiState.value.speakers
        assertEquals(listOf("A"), speakers.map { it.speakerId })
        assertEquals(1_000L + 500L + 400L, speakers.first().talkMs)
    }

    @Test fun continuationRejectedWhenSegmentTooLong() {
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs == 0L) matched("A") else noMatch },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 1_000))       // A
        engine.onChunk(chunk(1_400, 2_400))   // dur 1000 >= 800 => everyone-else despite small gap

        assertEquals(1_000L, StateFlowTalk(engine, "A").talkMs)
        assertEquals(1_000L, StateFlowTalk(engine, SessionStats.EVERYONE_ELSE).talkMs)
    }

    @Test fun continuationRejectedWhenGapTooLarge() {
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs == 0L) matched("A") else noMatch },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 1_000))       // A, ends at 1000
        engine.onChunk(chunk(1_500, 2_000))   // gap = exactly 500 (not < 500) => everyone-else

        assertEquals(500L, StateFlowTalk(engine, SessionStats.EVERYONE_ELSE).talkMs)
    }

    @Test fun continuationGapBoundary499MsStillContinues() {
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs == 0L) matched("A") else noMatch },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 1_000))       // A
        engine.onChunk(chunk(1_499, 2_000))   // gap 499 < 500, dur 501 < 800 => A

        assertNull(engine.uiState.value.speakers.firstOrNull { it.isEveryoneElse })
        assertEquals(1_501L, StateFlowTalk(engine, "A").talkMs)
    }

    @Test fun noContinuationWithoutAPriorEnrolledSpeaker() {
        // First-ever chunk is a short no-match: nothing to continue => everyone-else.
        val engine = SessionEngine(FakeMatcher { noMatch }, Mode.TABLE)
        engine.start()
        engine.onChunk(chunk(0, 500)) // short, but no anchor
        assertEquals(500L, StateFlowTalk(engine, SessionStats.EVERYONE_ELSE).talkMs)
    }

    // --- UI shape: shares sorted by share desc, everyone-else last ---

    @Test fun speakersSortedByShareDescendingWithEveryoneElseLast() {
        val engine = SessionEngine(
            FakeMatcher { c ->
                when (c.startMs) {
                    0L -> matched("B")
                    6_000L -> matched("A")
                    else -> noMatch
                }
            },
            Mode.TABLE,
        )
        engine.start()
        engine.onChunk(chunk(0, 5_000))       // B: 5000
        engine.onChunk(chunk(6_000, 8_000))   // A: 2000
        engine.onChunk(chunk(9_000, 10_000))  // everyone-else: 1000 (gap 1000, dur 1000)

        val ids = engine.uiState.value.speakers.map { it.speakerId }
        assertEquals(listOf("B", "A", SessionStats.EVERYONE_ELSE), ids)
        assertEquals(5.0 / 8.0, engine.uiState.value.speakers[0].share, 1e-9)
    }

    @Test fun displayNamesResolveSpeakerLabels() {
        val engine = SessionEngine(FakeMatcher { matched("p1") }, Mode.TABLE, displayNames = mapOf("p1" to "Alice"))
        engine.start()
        engine.onChunk(chunk(0, 1_000))
        assertEquals("Alice", engine.uiState.value.speakers.first().label)
    }

    // --- state machine + elapsed clock ---

    @Test fun stateTransitionsIdleRunningEnded() {
        val engine = SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE)
        assertEquals(EngineState.IDLE, engine.state)
        engine.start()
        assertEquals(EngineState.RUNNING, engine.state)
        engine.stop()
        assertEquals(EngineState.ENDED, engine.state)
    }

    @Test(expected = IllegalStateException::class)
    fun startTwiceThrows() {
        val engine = SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE)
        engine.start()
        engine.start()
    }

    @Test(expected = IllegalStateException::class)
    fun stopBeforeStartThrows() {
        SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE).stop()
    }

    @Test fun chunksBeforeStartAndAfterStopAreIgnored() {
        val engine = SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE)
        engine.onChunk(chunk(0, 1_000)) // before start: ignored
        assertTrue(engine.uiState.value.speakers.isEmpty())

        engine.start()
        engine.onChunk(chunk(0, 1_000))
        engine.stop()
        engine.onChunk(chunk(2_000, 3_000)) // after stop: ignored
        assertEquals(1_000L, engine.uiState.value.speakers.first().talkMs)
    }

    @Test fun elapsedTracksClockWhileRunningAndFreezesOnStop() {
        val clock = TestClock(1_000L)
        val engine = SessionEngine(FakeMatcher { matched("A") }, Mode.TABLE, now = clock::read)
        engine.start() // startedAt = 1000
        assertEquals(0L, engine.uiState.value.elapsedMs)

        clock.nowMs = 3_500L
        engine.onSpeechActive(true) // triggers an emit at t=3500
        assertEquals(2_500L, engine.uiState.value.elapsedMs)
        assertTrue(engine.uiState.value.isSpeechActive)

        clock.nowMs = 5_000L
        val result = engine.stop()
        assertEquals(4_000L, result.durationMs)
        assertEquals(4_000L, engine.uiState.value.elapsedMs) // frozen
        assertFalse(engine.uiState.value.isSpeechActive)      // pulse cleared on stop
    }

    // --- stop() output shape (maps to SessionEntity / SpeakerStatEntity in T9) ---

    @Test fun stopReturnsFinalPerSpeakerStats() {
        val clock = TestClock(100L)
        val engine = SessionEngine(
            FakeMatcher { c -> if (c.startMs < 5_000L) matched("A") else noMatch },
            Mode.TABLE,
            now = clock::read,
        )
        engine.start()
        engine.onChunk(chunk(0, 2_000))       // A
        engine.onChunk(chunk(3_000, 4_000))   // A (gap 1000 -> new turn)
        engine.onChunk(chunk(6_000, 7_000))   // everyone-else
        clock.nowMs = 8_100L
        val result = engine.stop()

        assertEquals(Mode.TABLE, result.mode)
        assertEquals(8_000L, result.durationMs)
        val a = result.speakers.first { it.speakerId == "A" }
        assertEquals(3_000L, a.talkMs)
        assertEquals(2, a.turns)
        val everyone = result.speakers.first { it.speakerId == SessionStats.EVERYONE_ELSE }
        assertEquals(1_000L, everyone.talkMs)
    }
}
