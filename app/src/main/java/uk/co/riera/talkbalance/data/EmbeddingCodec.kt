package uk.co.riera.talkbalance.data

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A stored embedding BLOB could not be read back. [reason] is for logs, never for users. */
class CorruptEmbeddingException(val reason: String) : IllegalArgumentException(reason)

/**
 * Serializes and deserializes `Array<FloatArray>` embeddings to/from `ByteArray` for the Room
 * `person.embeddings` column.
 *
 * Format (little-endian):
 * - int: count (number of arrays)
 * - for each array:
 *   - int: array length
 *   - N × float: array elements
 *
 * ## Why decoding validates
 * [decode] runs on data read back from SQLite at session start, at enrollment load and during a
 * person merge. A truncated or damaged BLOB previously produced a `BufferUnderflowException`, a
 * `NegativeArraySizeException` or a multi-gigabyte allocation from a garbage length field — i.e. a
 * crash on a path the user cannot escape without clearing app data. Decoding therefore checks that
 * every length is non-negative, is actually backed by the bytes that remain, and that the buffer is
 * consumed exactly, and reports a typed [CorruptEmbeddingException] instead.
 *
 * Non-finite floats are deliberately preserved rather than rejected: NaN/±Inf are legal bit patterns
 * to round-trip, and a matcher that receives them simply fails to match rather than misbehaving.
 *
 * The format carries no version tag. That is acceptable while the app is pre-1.0 (schema changes are
 * destructive by decision), but a version int should be added before any release that must migrate
 * existing enrollments rather than ask users to re-enrol.
 */
object EmbeddingCodec {
    /**
     * Sanity ceiling on the number of embeddings per person. Enrollment stores 3 and a merge caps at
     * 8; anything near this bound is corruption, not data.
     */
    private const val MAX_ARRAYS = 64

    /**
     * Sanity ceiling on one embedding's length. Real speaker embeddings are 192–512 floats; the
     * bound only exists to reject absurd length fields early.
     */
    private const val MAX_LENGTH = 16_384

    private const val BYTES_PER_INT = 4
    private const val BYTES_PER_FLOAT = 4

    fun encode(embeddings: Array<FloatArray>): ByteArray {
        val buffer = ByteBuffer.allocate(
            BYTES_PER_INT + // count
                embeddings.sumOf { BYTES_PER_INT + it.size * BYTES_PER_FLOAT }
        ).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(embeddings.size)
        for (array in embeddings) {
            buffer.putInt(array.size)
            for (value in array) {
                buffer.putFloat(value)
            }
        }

        return buffer.array()
    }

    /**
     * Decodes a BLOB written by [encode].
     *
     * @throws CorruptEmbeddingException if [bytes] is not a complete, self-consistent encoding.
     */
    fun decode(bytes: ByteArray): Array<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        try {
            require(buffer.remaining() >= BYTES_PER_INT) { "empty blob (${bytes.size} bytes)" }
            val count = buffer.int
            require(count in 0..MAX_ARRAYS) { "implausible embedding count $count" }

            val result = Array(count) {
                require(buffer.remaining() >= BYTES_PER_INT) { "truncated before array $it length" }
                val length = buffer.int
                require(length in 0..MAX_LENGTH) { "implausible embedding length $length" }
                // The decisive check: a garbage length cannot outrun the bytes actually present, so
                // no oversized allocation is ever attempted.
                require(length.toLong() * BYTES_PER_FLOAT <= buffer.remaining()) {
                    "array $it claims $length floats but only ${buffer.remaining()} bytes remain"
                }
                FloatArray(length) { buffer.float }
            }

            require(buffer.remaining() == 0) { "${buffer.remaining()} trailing bytes" }
            return result
        } catch (e: IllegalArgumentException) {
            throw CorruptEmbeddingException(e.message ?: "malformed embedding blob")
        } catch (e: BufferUnderflowException) {
            throw CorruptEmbeddingException("truncated embedding blob")
        }
    }

    /**
     * Decodes like [decode] but returns null instead of throwing, for the read paths that should
     * skip one unreadable person rather than fail the whole session or merge.
     */
    fun decodeOrNull(bytes: ByteArray): Array<FloatArray>? =
        runCatching { decode(bytes) }.getOrNull()
}
