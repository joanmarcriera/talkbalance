package uk.co.riera.talkbalance.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "speaker_stats",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("personId"),
    ],
)
data class SpeakerStatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val personId: Long?, // Nullable; null = "everyone else"
    val isEveryoneElse: Boolean,
    val talkMs: Long,
    val turns: Int,
    val longestMonologueMs: Long,
)
