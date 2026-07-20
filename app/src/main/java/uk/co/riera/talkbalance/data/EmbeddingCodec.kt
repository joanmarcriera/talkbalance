package uk.co.riera.talkbalance.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes and deserializes Array<FloatArray> embeddings to/from ByteArray.
 * Format: little-endian ByteBuffer with:
 * - int: count (number of arrays)
 * - for each array:
 *   - int: array length
 *   - N × float: array elements
 */
object EmbeddingCodec {
    fun encode(embeddings: Array<FloatArray>): ByteArray {
        val buffer = ByteBuffer.allocate(
            4 + // count
                embeddings.sumOf { 4 + it.size * 4 } // per-array: length + floats
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

    fun decode(bytes: ByteArray): Array<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val count = buffer.int
        return Array(count) {
            val length = buffer.int
            FloatArray(length) { buffer.float }
        }
    }
}
