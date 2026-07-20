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
    ) {
        val sessionId = insertSession(session)
        for (stat in stats) {
            insertStat(stat.copy(sessionId = sessionId))
        }
    }

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertStat(stat: SpeakerStatEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startedAtEpochMs DESC")
    fun observeSessionsNewestFirst(): Flow<List<SessionEntity>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionWithStats(id: Long): SessionWithStats?
}
