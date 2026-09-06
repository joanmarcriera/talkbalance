package uk.co.riera.talkbalance.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JUnit tests for the pure saved-session presentation logic — no Room/Android needed. */
class SessionHistoryTest {

    private fun person(id: Long, name: String, colorIndex: Int = 0) = PersonEntity(
        id = id,
        name = name,
        colorIndex = colorIndex,
        embeddings = ByteArray(0),
        createdAtEpochMs = 0L,
    )

    private fun stat(
        personId: Long? = null,
        isEveryoneElse: Boolean = false,
        talkMs: Long = 0L,
        turns: Int = 0,
        longestMonologueMs: Long = 0L,
        label: String? = null,
    ) = SpeakerStatEntity(
        id = 0L,
        sessionId = 1L,
        personId = personId,
        isEveryoneElse = isEveryoneElse,
        talkMs = talkMs,
        turns = turns,
        longestMonologueMs = longestMonologueMs,
        label = label,
    )

    // --- labelFor -------------------------------------------------------------------------------

    @Test
    fun everyoneElseUsesTheModeLabel() {
        val label = SessionHistory.labelFor(
            stat(isEveryoneElse = true),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )
        assertEquals("Others", label)
    }

    @Test
    fun discoveredSpeakerUsesItsSavedLabel() {
        val label = SessionHistory.labelFor(
            stat(label = "Alice"),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )
        assertEquals("Alice", label)
    }

    @Test
    fun enrolledSpeakerUsesThePersonsCurrentName() {
        // A person renamed on Home after the session was saved shows the NEW name in old reports.
        val label = SessionHistory.labelFor(
            stat(personId = 7L),
            personsById = mapOf(7L to person(7L, "Renamed")),
            everyoneElseLabel = "Others",
        )
        assertEquals("Renamed", label)
    }

    @Test
    fun deletedPersonGetsAnHonestPlaceholderRatherThanAnotherSpeakersName() {
        // The speaker_stats FK is SET_NULL, so deleting a person leaves their past rows behind.
        val label = SessionHistory.labelFor(
            stat(personId = null),
            personsById = mapOf(7L to person(7L, "Still here")),
            everyoneElseLabel = "Others",
        )
        assertEquals(SessionHistory.DELETED_PERSON_LABEL, label)
    }

    @Test
    fun savedLabelWinsOverAResolvablePerson() {
        // Discover-mode renames are captured at save time and must not be overridden by whichever
        // person happens to hold that id now.
        val label = SessionHistory.labelFor(
            stat(personId = 7L, label = "Speaker 2"),
            personsById = mapOf(7L to person(7L, "Bob")),
            everyoneElseLabel = "Others",
        )
        assertEquals("Speaker 2", label)
    }

    // --- rowsFor --------------------------------------------------------------------------------

    @Test
    fun rowsAreOrderedByTalkTimeDescendingWithSharesSummingToOne() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(
                stat(personId = 1L, talkMs = 1_000L),
                stat(personId = 2L, talkMs = 3_000L),
            ),
            personsById = mapOf(1L to person(1L, "Quiet"), 2L to person(2L, "Loud")),
            everyoneElseLabel = "Others",
        )

        assertEquals(listOf("Loud", "Quiet"), rows.map { it.label })
        assertEquals(0.75, rows[0].share, 1e-9)
        assertEquals(0.25, rows[1].share, 1e-9)
        assertEquals(1.0, rows.sumOf { it.share }, 1e-9)
    }

    @Test
    fun sharesAreOfAttributedTalkTimeNotWallClock() {
        // Two speakers with 1 s each: 50/50, regardless of how long the session ran.
        val rows = SessionHistory.rowsFor(
            stats = listOf(stat(label = "A", talkMs = 1_000L), stat(label = "B", talkMs = 1_000L)),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )
        assertTrue(rows.all { it.share == 0.5 })
    }

    @Test
    fun aSessionWithNoTalkYieldsZeroSharesRatherThanDividingByZero() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(stat(personId = 1L, talkMs = 0L)),
            personsById = mapOf(1L to person(1L, "Silent")),
            everyoneElseLabel = "Others",
        )
        assertEquals(1, rows.size)
        assertEquals(0.0, rows[0].share, 0.0)
    }

    @Test
    fun colorIndexIsCarriedForEnrolledSpeakersAndAbsentForEveryoneElseAndDiscovered() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(
                stat(personId = 1L, talkMs = 30L),
                stat(label = "Speaker 1", talkMs = 20L),
                stat(isEveryoneElse = true, talkMs = 10L),
            ),
            personsById = mapOf(1L to person(1L, "Enrolled", colorIndex = 5)),
            everyoneElseLabel = "Everyone else",
        )

        assertEquals(5, rows[0].colorIndex)
        assertNull(rows[1].colorIndex)
        assertNull(rows[2].colorIndex)
        assertTrue(rows[2].isEveryoneElse)
    }

    @Test
    fun perSpeakerStatsAreCarriedThroughUnchanged() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(stat(label = "A", talkMs = 5_000L, turns = 4, longestMonologueMs = 2_500L)),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )
        assertEquals(5_000L, rows[0].talkMs)
        assertEquals(4, rows[0].turns)
        assertEquals(2_500L, rows[0].longestMonologueMs)
    }

    // --- summaryFor -----------------------------------------------------------------------------

    @Test
    fun summaryNamesTheLeaderAndCountsOnlySpeakersWhoActuallySpoke() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(
                stat(label = "A", talkMs = 3_000L),
                stat(label = "B", talkMs = 1_000L),
                stat(label = "C", talkMs = 0L),
            ),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )

        val summary = SessionHistory.summaryFor(rows)!!
        assertEquals("A", summary.leaderLabel)
        assertEquals(75, summary.leaderSharePercent)
        assertEquals(2, summary.speakerCount)
    }

    @Test
    fun summaryIsNullWhenNobodySpoke() {
        val rows = SessionHistory.rowsFor(
            stats = listOf(stat(label = "A", talkMs = 0L)),
            personsById = emptyMap(),
            everyoneElseLabel = "Others",
        )
        assertNull(SessionHistory.summaryFor(rows))
    }

    @Test
    fun summaryIsNullForASessionWithNoSpeakerRowsAtAll() {
        assertNull(SessionHistory.summaryFor(emptyList()))
    }
}
