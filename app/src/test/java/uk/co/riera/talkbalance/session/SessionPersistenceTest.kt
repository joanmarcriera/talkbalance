package uk.co.riera.talkbalance.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SessionPersistence.toEntities] — the pure [SessionResult] -> Room mapping used by
 * [SessionController.persist]. No device / DB needed.
 */
class SessionPersistenceTest {

    private fun stat(id: String, talk: Long, turns: Int = 1, longest: Long = talk) =
        SpeakerStat(speakerId = id, talkMs = talk, turns = turns, longestMonologueMs = longest)

    @Test fun tableModeResolvesPersonIdsAndBucketsEveryoneElse() {
        val result = SessionResult(
            startedAtMs = 1_000,
            endedAtMs = 61_000,
            durationMs = 60_000,
            mode = Mode.TABLE,
            speakers = listOf(
                stat("7", 30_000, turns = 4, longest = 12_000),
                stat("9", 20_000),
                stat(SessionStats.EVERYONE_ELSE, 10_000),
            ),
        )
        val (session, stats) = SessionPersistence.toEntities(
            result,
            speakerIdToPersonId = mapOf("7" to 7L, "9" to 9L),
        )

        assertEquals("table", session.mode)
        assertEquals(1_000L, session.startedAtEpochMs)
        assertEquals(61_000L, session.endedAtEpochMs)
        assertEquals(60_000L, session.durationMs)
        assertEquals(3, stats.size)

        val first = stats[0]
        assertEquals(7L, first.personId)
        assertFalse(first.isEveryoneElse)
        assertEquals(30_000L, first.talkMs)
        assertEquals(4, first.turns)
        assertEquals(12_000L, first.longestMonologueMs)
        assertEquals(0L, first.sessionId) // filled by the DAO on insert

        assertEquals(9L, stats[1].personId)

        val everyoneElse = stats[2]
        assertNull(everyoneElse.personId)
        assertTrue(everyoneElse.isEveryoneElse)
    }

    @Test fun meModeMapsMeSentinelToTheMePerson() {
        val result = SessionResult(
            startedAtMs = 0,
            endedAtMs = 10_000,
            durationMs = 10_000,
            mode = Mode.ME,
            speakers = listOf(
                stat("me", 7_000),
                stat(SessionStats.EVERYONE_ELSE, 3_000),
            ),
        )
        val (session, stats) = SessionPersistence.toEntities(result, mapOf("me" to 42L))

        assertEquals("me", session.mode)
        assertEquals(42L, stats[0].personId)
        assertFalse(stats[0].isEveryoneElse)
        assertNull(stats[1].personId)
        assertTrue(stats[1].isEveryoneElse)
    }

    @Test fun discoverModeTagsSessionAndPersistsSpeakerLabels() {
        val result = SessionResult(
            startedAtMs = 0,
            endedAtMs = 20_000,
            durationMs = 20_000,
            mode = Mode.DISCOVER,
            speakers = listOf(stat("s1", 12_000), stat("s2", 8_000)),
        )
        val (session, stats) = SessionPersistence.toEntities(
            result,
            speakerIdToPersonId = emptyMap(), // no enrolled people in discover
            speakerLabels = mapOf("s1" to "Dad", "s2" to "Speaker 2"),
        )

        assertEquals("discover", session.mode)
        assertEquals("Dad", stats[0].label)
        assertNull(stats[0].personId)
        assertFalse(stats[0].isEveryoneElse)
        assertEquals("Speaker 2", stats[1].label)
    }

    @Test fun unknownSpeakerIdBecomesNullPersonIdRatherThanDropping() {
        val result = SessionResult(
            startedAtMs = 0, endedAtMs = 5_000, durationMs = 5_000, mode = Mode.TABLE,
            speakers = listOf(stat("99", 5_000)),
        )
        val (_, stats) = SessionPersistence.toEntities(result, speakerIdToPersonId = emptyMap())
        assertEquals(1, stats.size)
        assertNull(stats[0].personId)
        assertFalse(stats[0].isEveryoneElse)
    }
}
