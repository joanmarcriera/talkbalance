package uk.co.riera.talkbalance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Transaction
    suspend fun insertSessionWithStats(
        session: SessionEntity,
        stats: List<SpeakerStatEntity>,
    ): Long {
        val sessionId = insertSession(session)
        for (stat in stats) {
            insertStat(stat.copy(sessionId = sessionId))
        }
        return sessionId
    }

    /** Deletes one session by id (its speaker_stats cascade). Used to re-save after a label edit. */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertStat(stat: SpeakerStatEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeSessionsNewestFirst(): Flow<List<SessionEntity>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionWithStats(id: Long): SessionWithStats?

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM speaker_stats")
    suspend fun deleteAllStats()

    /**
     * Deletes every persons/sessions/speaker_stats row in a single transaction, so a "delete all
     * data" wipe can never leave the DB half-cleared. `persons` is included here (rather than via
     * [PersonDao]) so all three deletes share one atomic transaction.
     */
    @Transaction
    suspend fun deleteAllPersonsSessionsStatsTx() {
        deleteAllPersons()
        deleteAllSessions()
        deleteAllStats()
    }

    @Query("DELETE FROM persons")
    suspend fun deleteAllPersons()
}
