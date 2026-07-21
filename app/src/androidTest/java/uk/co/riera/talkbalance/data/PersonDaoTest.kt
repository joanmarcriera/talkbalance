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
class PersonDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PersonDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.personDao()
        sessionDao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRetrievePerson() = runBlocking {
        val embeddings = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f, 0.2f)))
        val person = PersonEntity(
            id = 0,
            name = "Alice",
            colorIndex = 0,
            embeddings = embeddings,
            createdAtEpochMs = 1000L,
        )

        val id = dao.insert(person)
        val retrieved = dao.getById(id)

        assertEquals("Alice", retrieved?.name)
        assertEquals(0, retrieved?.colorIndex)
        assertEquals(1000L, retrieved?.createdAtEpochMs)
    }

    @Test
    fun observeAllPersons() = runBlocking {
        val embeddings1 = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f)))
        val embeddings2 = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.2f)))

        val person1 = PersonEntity(
            name = "Alice",
            colorIndex = 0,
            embeddings = embeddings1,
            createdAtEpochMs = 1000L,
        )
        val person2 = PersonEntity(
            name = "Bob",
            colorIndex = 1,
            embeddings = embeddings2,
            createdAtEpochMs = 2000L,
        )

        dao.insert(person1)
        dao.insert(person2)

        val all = dao.observeAll().first()
        assertEquals(2, all.size)
    }

    @Test
    fun updatePerson() = runBlocking {
        val embeddings = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f)))
        val person = PersonEntity(
            name = "Alice",
            colorIndex = 0,
            embeddings = embeddings,
            createdAtEpochMs = 1000L,
        )
        val id = dao.insert(person)

        val updated = person.copy(id = id, name = "Alicia")
        dao.update(updated)

        val retrieved = dao.getById(id)
        assertEquals("Alicia", retrieved?.name)
    }

    @Test
    fun deletePerson() = runBlocking {
        val embeddings = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f)))
        val person = PersonEntity(
            name = "Alice",
            colorIndex = 0,
            embeddings = embeddings,
            createdAtEpochMs = 1000L,
        )
        val id = dao.insert(person)
        dao.delete(person.copy(id = id))

        val retrieved = dao.getById(id)
        assertNull(retrieved)
    }

    @Test
    fun getByIdReturnsNullForNonexistentId() = runBlocking {
        val retrieved = dao.getById(999L)
        assertNull(retrieved)
    }

    @Test
    fun deleteAllRemovesAllPersons() = runBlocking {
        val embeddings1 = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f)))
        val embeddings2 = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.2f)))

        val person1 = PersonEntity(
            name = "Alice",
            colorIndex = 0,
            embeddings = embeddings1,
            createdAtEpochMs = 1000L,
        )
        val person2 = PersonEntity(
            name = "Bob",
            colorIndex = 1,
            embeddings = embeddings2,
            createdAtEpochMs = 2000L,
        )

        dao.insert(person1)
        dao.insert(person2)

        val beforeDelete = dao.observeAll().first()
        assertEquals(2, beforeDelete.size)

        dao.deleteAll()

        val afterDelete = dao.observeAll().first()
        assertEquals(0, afterDelete.size)
    }

    // --- mergePersons ---------------------------------------------------------------------------

    private fun person(name: String, embeddings: Array<FloatArray>) = PersonEntity(
        name = name,
        colorIndex = 0,
        embeddings = EmbeddingCodec.encode(embeddings),
        createdAtEpochMs = 0L,
    )

    @Test
    fun mergePersonsReassignsStatsToTargetAndDeletesSource() = runBlocking {
        val sourceId = dao.insert(person("Kid (dup)", arrayOf(floatArrayOf(1f))))
        val targetId = dao.insert(person("Kid", arrayOf(floatArrayOf(2f))))

        val session = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        sessionDao.insertSessionWithStats(
            session,
            listOf(
                SpeakerStatEntity(
                    sessionId = 0,
                    personId = sourceId,
                    isEveryoneElse = false,
                    talkMs = 500L,
                    turns = 2,
                    longestMonologueMs = 300L,
                ),
            ),
        )

        dao.mergePersons(sourceId = sourceId, targetId = targetId)

        val sessions = sessionDao.observeSessionsNewestFirst().first()
        val withStats = sessionDao.getSessionWithStats(sessions[0].id)
        assertEquals(1, withStats?.stats?.size)
        assertEquals(targetId, withStats?.stats?.first()?.personId)

        assertNull(dao.getById(sourceId))
        assertEquals("Kid", dao.getById(targetId)?.name)
    }

    @Test
    fun mergePersonsCombinesEmbeddingsTargetFirst() = runBlocking {
        val sourceId = dao.insert(person("Source", arrayOf(floatArrayOf(3f))))
        val targetId = dao.insert(person("Target", arrayOf(floatArrayOf(1f), floatArrayOf(2f))))

        dao.mergePersons(sourceId = sourceId, targetId = targetId)

        val combined = EmbeddingCodec.decode(dao.getById(targetId)!!.embeddings)
        assertEquals(3, combined.size)
        assertEquals(1f, combined[0][0], 0f)
        assertEquals(2f, combined[1][0], 0f)
        assertEquals(3f, combined[2][0], 0f)
    }

    @Test
    fun mergePersonsCapsCombinedEmbeddingsAtEightPreferringTarget() = runBlocking {
        val sourceId = dao.insert(person("Source", Array(5) { floatArrayOf(100f + it) }))
        val targetId = dao.insert(person("Target", Array(6) { floatArrayOf(it.toFloat()) }))

        dao.mergePersons(sourceId = sourceId, targetId = targetId)

        val combined = EmbeddingCodec.decode(dao.getById(targetId)!!.embeddings)
        assertEquals(8, combined.size)
        for (i in 0 until 6) assertEquals(i.toFloat(), combined[i][0], 0f)
        assertEquals(100f, combined[6][0], 0f)
        assertEquals(101f, combined[7][0], 0f)
    }

    @Test
    fun mergePersonsIsNoOpWhenSourceEqualsTarget() = runBlocking {
        val id = dao.insert(person("Solo", arrayOf(floatArrayOf(9f))))

        dao.mergePersons(sourceId = id, targetId = id)

        val retrieved = dao.getById(id)
        assertEquals("Solo", retrieved?.name)
        assertEquals(1, EmbeddingCodec.decode(retrieved!!.embeddings).size)
    }

    @Test
    fun mergePersonsIsNoOpWhenSourceMissing() = runBlocking {
        val targetId = dao.insert(person("Target", arrayOf(floatArrayOf(1f))))

        dao.mergePersons(sourceId = 999L, targetId = targetId)

        assertEquals(1, EmbeddingCodec.decode(dao.getById(targetId)!!.embeddings).size)
    }

    @Test
    fun mergePersonsConsolidatesDualRowsInTheSameSession() = runBlocking {
        // Both people appeared in ONE historical session (each has a speaker_stats row for it).
        val sourceId = dao.insert(person("Kid (dup)", arrayOf(floatArrayOf(1f))))
        val targetId = dao.insert(person("Kid", arrayOf(floatArrayOf(2f))))

        val session = SessionEntity(
            startedAtEpochMs = 1000L,
            endedAtEpochMs = 2000L,
            mode = "table",
            durationMs = 1000L,
        )
        sessionDao.insertSessionWithStats(
            session,
            listOf(
                SpeakerStatEntity(
                    sessionId = 0,
                    personId = targetId,
                    isEveryoneElse = false,
                    talkMs = 400L,
                    turns = 3,
                    longestMonologueMs = 250L,
                ),
                SpeakerStatEntity(
                    sessionId = 0,
                    personId = sourceId,
                    isEveryoneElse = false,
                    talkMs = 600L,
                    turns = 2,
                    longestMonologueMs = 500L,
                ),
            ),
        )

        dao.mergePersons(sourceId = sourceId, targetId = targetId)

        val sessions = sessionDao.observeSessionsNewestFirst().first()
        val withStats = sessionDao.getSessionWithStats(sessions[0].id)

        // The two rows collapse into ONE combined target row, not two.
        assertEquals(1, withStats?.stats?.size)
        val row = withStats!!.stats.first()
        assertEquals(targetId, row.personId)
        assertEquals(1000L, row.talkMs) // 400 + 600
        assertEquals(5, row.turns) // 3 + 2
        assertEquals(500L, row.longestMonologueMs) // max(250, 500)
        assertNull(dao.getById(sourceId))
    }

    @Test
    fun mergePersonsAcrossDifferentSessionsKeepsBothRows() = runBlocking {
        // Source and target each appear in a DIFFERENT session: nothing to combine, both survive
        // under the target after the merge (one repointed row per session).
        val sourceId = dao.insert(person("Dup", arrayOf(floatArrayOf(1f))))
        val targetId = dao.insert(person("Main", arrayOf(floatArrayOf(2f))))

        sessionDao.insertSessionWithStats(
            SessionEntity(startedAtEpochMs = 1000L, endedAtEpochMs = 2000L, mode = "table", durationMs = 1000L),
            listOf(SpeakerStatEntity(sessionId = 0, personId = targetId, isEveryoneElse = false, talkMs = 100L, turns = 1, longestMonologueMs = 100L)),
        )
        sessionDao.insertSessionWithStats(
            SessionEntity(startedAtEpochMs = 3000L, endedAtEpochMs = 4000L, mode = "table", durationMs = 1000L),
            listOf(SpeakerStatEntity(sessionId = 0, personId = sourceId, isEveryoneElse = false, talkMs = 200L, turns = 2, longestMonologueMs = 150L)),
        )

        dao.mergePersons(sourceId = sourceId, targetId = targetId)

        val allStats = dao.statsForPerson(targetId)
        assertEquals(2, allStats.size) // one per session, both now under the target
        assertNull(dao.getById(sourceId))
    }
}
