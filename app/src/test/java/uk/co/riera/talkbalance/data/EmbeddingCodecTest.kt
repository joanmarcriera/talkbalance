package uk.co.riera.talkbalance.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
