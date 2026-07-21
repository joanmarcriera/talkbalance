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
     * at [sourceId] is consolidated onto [targetId] via [consolidateStats] (past session stats
     * survive, just under the target's name — and when both people appeared in the SAME session
     * their two rows are combined into one), the two people's stored voice embeddings are combined
     * via [combineEmbeddings] (target's own embeddings preferred, capped at [MAX_PERSON_EMBEDDINGS])
     * and written back onto the target row, and finally the now-redundant source person row is
     * deleted.
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

        consolidateStats(sourceId = sourceId, targetId = targetId)

        val combined = combineEmbeddings(
            target = EmbeddingCodec.decode(target.embeddings),
            source = EmbeddingCodec.decode(source.embeddings),
        )
        update(target.copy(embeddings = EmbeddingCodec.encode(combined)))
        delete(source)
    }

    /**
     * Moves [sourceId]'s per-session stats onto [targetId]. For a session where the target has no row
     * yet, the source row is simply repointed. For a session where BOTH already have a row, the two
     * are folded into the single target row (talk/turns summed, longest monologue maxed — see
     * [combineSpeakerStats]) and the source's duplicate row deleted, so a session never lists the
     * merged person twice.
     */
    @Transaction
    suspend fun consolidateStats(sourceId: Long, targetId: Long) {
        val targetBySession = statsForPerson(targetId).associateBy { it.sessionId }
        for (sourceStat in statsForPerson(sourceId)) {
            val targetStat = targetBySession[sourceStat.sessionId]
            if (targetStat == null) {
                reassignStatRow(id = sourceStat.id, targetId = targetId)
            } else {
                val merged = combineSpeakerStats(
                    target = SpeakerStatValues(targetStat.talkMs, targetStat.turns, targetStat.longestMonologueMs),
                    source = SpeakerStatValues(sourceStat.talkMs, sourceStat.turns, sourceStat.longestMonologueMs),
                )
                updateStatValues(
                    id = targetStat.id,
                    talkMs = merged.talkMs,
                    turns = merged.turns,
                    longestMonologueMs = merged.longestMonologueMs,
                )
                deleteStatById(sourceStat.id)
            }
        }
    }

    @Query("SELECT * FROM speaker_stats WHERE personId = :personId")
    suspend fun statsForPerson(personId: Long): List<SpeakerStatEntity>

    @Query("UPDATE speaker_stats SET personId = :targetId WHERE id = :id")
    suspend fun reassignStatRow(id: Long, targetId: Long)

    @Query(
        "UPDATE speaker_stats SET talkMs = :talkMs, turns = :turns, longestMonologueMs = :longestMonologueMs " +
            "WHERE id = :id",
    )
    suspend fun updateStatValues(id: Long, talkMs: Long, turns: Int, longestMonologueMs: Long)

    @Query("DELETE FROM speaker_stats WHERE id = :id")
    suspend fun deleteStatById(id: Long)
}
