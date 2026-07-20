package uk.co.riera.talkbalance.data

import androidx.room.Embedded
import androidx.room.Relation

data class SessionWithStats(
    @Embedded
    val session: SessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId",
    )
    val stats: List<SpeakerStatEntity>,
)
