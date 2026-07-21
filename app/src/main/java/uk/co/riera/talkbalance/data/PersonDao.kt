package uk.co.riera.talkbalance.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Insert
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Delete
    suspend fun delete(person: PersonEntity)

    @Query("SELECT * FROM persons")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun getById(id: Long): PersonEntity?

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    /**
     * Merges [sourceId] into [targetId] in one transaction: every `speaker_stats` row that pointed
     * at [sourceId] is repointed at [targetId] (past session stats survive, just under the target's
     * name), the two people's stored voice embeddings are combined via [combineEmbeddings] (target's
     * own embeddings preferred, capped at [MAX_PERSON_EMBEDDINGS]) and written back onto the target
     * row, and finally the now-redundant source person row is deleted.
     *
     * A no-op if [sourceId] equals [targetId], or if either id no longer resolves to a person
     * (e.g. already deleted by a racing action) — callers always load the entity first for the
     * confirmation UI, so this is a defensive guard rather than the expected path.
     */
    @Transaction
    suspend fun mergePersons(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        val source = getById(sourceId) ?: return
        val target = getById(targetId) ?: return

        reassignStats(sourceId = sourceId, targetId = targetId)

        val combined = combineEmbeddings(
            target = EmbeddingCodec.decode(target.embeddings),
            source = EmbeddingCodec.decode(source.embeddings),
        )
        update(target.copy(embeddings = EmbeddingCodec.encode(combined)))
        delete(source)
    }

    @Query("UPDATE speaker_stats SET personId = :targetId WHERE personId = :sourceId")
    suspend fun reassignStats(sourceId: Long, targetId: Long)
}
