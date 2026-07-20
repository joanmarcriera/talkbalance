package uk.co.riera.talkbalance.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val mode: String, // "table" or "me" or "chessclock"
    val durationMs: Long?,
)
