package uk.co.riera.talkbalance.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingCodecTest {
    @Test
    fun emptyArrayRoundtrip() {
        val embeddings = arrayOf<FloatArray>()
        val encoded = EmbeddingCodec.encode(embeddings)
        val decoded = EmbeddingCodec.decode(encoded)
        assertEquals(0, decoded.size)
    }

    @Test
    fun singleArrayRoundtrip() {
        val embeddings = arrayOf(floatArrayOf(0.1f, 0.2f, 0.3f))
        val encoded = EmbeddingCodec.encode(embeddings)
        val decoded = EmbeddingCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertArrayEquals(embeddings[0], decoded[0], 0.0001f)
    }

    @Test
    fun multipleArraysRoundtrip() {
        val embeddings = arrayOf(
            floatArrayOf(0.1f, 0.2f),
            floatArrayOf(0.3f, 0.4f, 0.5f),
            floatArrayOf(0.6f),
        )
        val encoded = EmbeddingCodec.encode(embeddings)
        val decoded = EmbeddingCodec.decode(encoded)

        assertEquals(3, decoded.size)
        assertArrayEquals(embeddings[0], decoded[0], 0.0001f)
        assertArrayEquals(embeddings[1], decoded[1], 0.0001f)
        assertArrayEquals(embeddings[2], decoded[2], 0.0001f)
    }

    @Test
    fun nonFiniteValuesPreserved() {
        val embeddings = arrayOf(
            floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f),
        )
        val encoded = EmbeddingCodec.encode(embeddings)
        val decoded = EmbeddingCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals(4, decoded[0].size)
        assertEquals(true, decoded[0][0].isNaN())
        assertEquals(Float.POSITIVE_INFINITY, decoded[0][1])
        assertEquals(Float.NEGATIVE_INFINITY, decoded[0][2])
        assertEquals(0.0f, decoded[0][3])
    }

    @Test
    fun largeArrayRoundtrip() {
        val largeArray = FloatArray(10000) { it.toFloat() / 10000 }
        val embeddings = arrayOf(largeArray)
        val encoded = EmbeddingCodec.encode(embeddings)
        val decoded = EmbeddingCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertArrayEquals(embeddings[0], decoded[0], 0.0001f)
    }

    // ---- Corruption handling -------------------------------------------------------------------
    // A damaged blob previously threw BufferUnderflowException / NegativeArraySizeException or tried
    // a huge allocation, crashing session start, enrollment load or a merge with no user escape.

    private fun bytes(vararg ints: Int): ByteArray {
        val buffer = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        ints.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    @Test
    fun emptyBlobIsCorrupt() {
        assertThrows(CorruptEmbeddingException::class.java) { EmbeddingCodec.decode(ByteArray(0)) }
    }

    @Test
    fun truncatedBlobIsCorrupt() {
        val encoded = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.1f, 0.2f, 0.3f)))
        val truncated = encoded.copyOf(encoded.size - 4)
        assertThrows(CorruptEmbeddingException::class.java) { EmbeddingCodec.decode(truncated) }
    }

    @Test
    fun negativeCountIsCorrupt() {
        assertThrows(CorruptEmbeddingException::class.java) { EmbeddingCodec.decode(bytes(-1)) }
    }

    @Test
    fun negativeLengthIsCorrupt() {
        // count = 1, length = -5
        assertThrows(CorruptEmbeddingException::class.java) { EmbeddingCodec.decode(bytes(1, -5)) }
    }

    @Test
    fun implausiblyLargeCountIsCorrupt() {
        assertThrows(CorruptEmbeddingException::class.java) { EmbeddingCodec.decode(bytes(1_000_000)) }
    }

    @Test
    fun lengthLongerThanRemainingBytesIsCorruptAndAllocatesNothing() {
        // count = 1, length = Int.MAX_VALUE, then no data: must not attempt an 8 GB allocation.
        assertThrows(CorruptEmbeddingException::class.java) {
            EmbeddingCodec.decode(bytes(1, Int.MAX_VALUE))
        }
    }

    @Test
    fun trailingGarbageIsCorrupt() {
        val encoded = EmbeddingCodec.encode(arrayOf(floatArrayOf(1f)))
        assertThrows(CorruptEmbeddingException::class.java) {
            EmbeddingCodec.decode(encoded + byteArrayOf(9, 9, 9, 9))
        }
    }

    @Test
    fun decodeOrNullReturnsNullForCorruptAndValueForValid() {
        assertNull(EmbeddingCodec.decodeOrNull(bytes(1, -5)))
        val valid = EmbeddingCodec.encode(arrayOf(floatArrayOf(0.5f, 0.25f)))
        val decoded = EmbeddingCodec.decodeOrNull(valid)
        assertNotNull(decoded)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), decoded!![0], 0.0001f)
    }

    @Test
    fun zeroLengthEmbeddingRoundtrips() {
        // Legal (if useless): an empty inner array must not be mistaken for corruption.
        val decoded = EmbeddingCodec.decode(EmbeddingCodec.encode(arrayOf(FloatArray(0))))
        assertEquals(1, decoded.size)
        assertEquals(0, decoded[0].size)
    }
}
