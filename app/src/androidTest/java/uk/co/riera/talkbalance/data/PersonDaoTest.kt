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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.personDao()
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
}
