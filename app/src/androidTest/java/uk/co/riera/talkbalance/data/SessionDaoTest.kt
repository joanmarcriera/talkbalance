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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertSessionWithStatsAndRetrieve() = runBlocking {
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
                personId = 1L,
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
                personId = 1L,
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
}
