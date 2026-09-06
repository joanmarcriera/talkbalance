package uk.co.riera.talkbalance.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val embeddings: ByteArray, // Serialized Array<FloatArray>
    val createdAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PersonEntity

        if (id != other.id) return false
        if (name != other.name) return false
        if (colorIndex != other.colorIndex) return false
        if (!embeddings.contentEquals(other.embeddings)) return false
        if (createdAtEpochMs != other.createdAtEpochMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + colorIndex
        result = 31 * result + embeddings.contentHashCode()
        result = 31 * result + createdAtEpochMs.hashCode()
        return result
    }
}
