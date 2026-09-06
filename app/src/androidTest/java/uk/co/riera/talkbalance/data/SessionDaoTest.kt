package uk.co.riera.talkbalance.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao
    private lateinit var personDao: PersonDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.sessionDao()
        personDao = db.personDao()
    }

    /** Stats referencing a person must point at a real row — the FK is enforced. */
    private suspend fun insertPerson(): Long = personDao.insert(
        PersonEntity(
            name = "Test Person",
            colorIndex = 0,
            embeddings = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f, 0.2f))),
            createdAtEpochMs = 0L,
        ),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertSessionWithStatsAndRetrieve() = runBlocking {
        val personId = insertPerson()
        val session = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        val stats = listOf(
            SpeakerStatEntity(
                sessionId = 0,
                personId = null,
                isEveryoneElse = true,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
            SpeakerStatEntity(
                sessionId = 0,
                personId = personId,
                isEveryoneElse = false,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
        )

        dao.insertSessionWithStats(session, stats)

        val all = dao.observeSessionsNewestFirst().first()
        assertEquals(1, all.size)
    }

    @Test
    fun observeSessionsNewestFirst() = runBlocking {
        val session1 = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        val session2 = SessionEntity(
            startedAtEpochMs = 3000L,
            endedAtEpochMs = 4000L,
            mode = "me",
            durationMs = 1000L,
        )

        dao.insertSessionWithStats(session1, emptyList())
        dao.insertSessionWithStats(session2, emptyList())

        val sessions = dao.observeSessionsNewestFirst().first()
        assertEquals(2, sessions.size)
        // Newest first (higher startedAtEpochMs comes first)
        assertEquals(3000L, sessions[0].startedAtEpochMs)
        assertEquals(1000L, sessions[1].startedAtEpochMs)
    }

    @Test
    fun getSessionWithStatsReturnsStatsRelation() = runBlocking {
        val personId = insertPerson()
        val session = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        val stats = listOf(
            SpeakerStatEntity(
                sessionId = 0,
                personId = null,
                isEveryoneElse = true,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
            SpeakerStatEntity(
                sessionId = 0,
                personId = personId,
                isEveryoneElse = false,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
        )

        dao.insertSessionWithStats(session, stats)
        val sessions = dao.observeSessionsNewestFirst().first()
        val sessionId = sessions[0].id

        val withStats = dao.getSessionWithStats(sessionId)
        assertEquals("table", withStats?.session?.mode)
        assertEquals(2, withStats?.stats?.size)
    }

    @Test
    fun getSessionWithStatsReturnsNullForNonexistentId() = runBlocking {
        val withStats = dao.getSessionWithStats(999L)
        assertNull(withStats)
    }

    @Test
    fun observeSessionsWithStatsReturnsEachSessionWithItsOwnRowsNewestFirst() = runBlocking {
        val personId = insertPerson()
        dao.insertSessionWithStats(
            SessionEntity(startedAtEpochMs = 1000L, endedAtEpochMs = 2000L, mode = "table", durationMs = 1000L),
            listOf(
                SpeakerStatEntity(
                    sessionId = 0, personId = personId, isEveryoneElse = false,
                    talkMs = 400L, turns = 2, longestMonologueMs = 300L,
                ),
            ),
        )
        dao.insertSessionWithStats(
            SessionEntity(startedAtEpochMs = 3000L, endedAtEpochMs = 4000L, mode = "discover", durationMs = 1000L),
            listOf(
                SpeakerStatEntity(
                    sessionId = 0, personId = null, isEveryoneElse = false,
                    talkMs = 700L, turns = 3, longestMonologueMs = 500L, label = "Speaker 1",
                ),
                SpeakerStatEntity(
                    sessionId = 0, personId = null, isEveryoneElse = false,
                    talkMs = 200L, turns = 1, longestMonologueMs = 200L, label = "Speaker 2",
                ),
            ),
        )

        val all = dao.observeSessionsWithStatsNewestFirst().first()

        assertEquals(2, all.size)
        // Newest first, and each session carries ONLY its own stats (the history list relies on
        // this to show who led each conversation without an N+1 query).
        assertEquals(3000L, all[0].session.startedAtEpochMs)
        assertEquals("discover", all[0].session.mode)
        assertEquals(2, all[0].stats.size)
        assertEquals(1000L, all[1].session.startedAtEpochMs)
        assertEquals(1, all[1].stats.size)
    }

    @Test
    fun deletingASessionRemovesItFromTheHistoryFlowAndCascadesItsStats() = runBlocking {
        val personId = insertPerson()
        dao.insertSessionWithStats(
            SessionEntity(startedAtEpochMs = 1000L, endedAtEpochMs = 2000L, mode = "table", durationMs = 1000L),
            listOf(
                SpeakerStatEntity(
                    sessionId = 0, personId = personId, isEveryoneElse = false,
                    talkMs = 400L, turns = 2, longestMonologueMs = 300L,
                ),
            ),
        )
        val sessionId = dao.observeSessionsWithStatsNewestFirst().first()[0].session.id

        dao.deleteSession(sessionId)

        assertEquals(0, dao.observeSessionsWithStatsNewestFirst().first().size)
        assertNull(dao.getSessionWithStats(sessionId))
        // The person outlives their stats — deleting a session must not delete people.
        assertEquals("Test Person", personDao.getById(personId)?.name)
    }

    @Test
    fun deleteAllSessionsRemovesAllSessions() = runBlocking {
        val session1 = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        val session2 = SessionEntity(
            startedAtEpochMs = 3000L,
            endedAtEpochMs = 4000L,
            mode = "me",
            durationMs = 1000L,
        )

        dao.insertSessionWithStats(session1, emptyList())
        dao.insertSessionWithStats(session2, emptyList())

        val beforeDelete = dao.observeSessionsNewestFirst().first()
        assertEquals(2, beforeDelete.size)

        dao.deleteAllSessions()

        val afterDelete = dao.observeSessionsNewestFirst().first()
        assertEquals(0, afterDelete.size)
    }

    @Test
    fun deleteAllStatsRemovesAllStats() = runBlocking {
        val personId = insertPerson()
        val session = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        val stats = listOf(
            SpeakerStatEntity(
                sessionId = 0,
                personId = null,
                isEveryoneElse = true,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
            SpeakerStatEntity(
                sessionId = 0,
                personId = personId,
                isEveryoneElse = false,
                talkMs = 500L,
                turns = 2,
                longestMonologueMs = 300L,
            ),
        )

        dao.insertSessionWithStats(session, stats)

        val sessions = dao.observeSessionsNewestFirst().first()
        val sessionId = sessions[0].id
        val withStats = dao.getSessionWithStats(sessionId)
        assertEquals(2, withStats?.stats?.size)

        dao.deleteAllStats()

        val afterDelete = dao.getSessionWithStats(sessionId)
        assertEquals(0, afterDelete?.stats?.size ?: 0)
    }
}
