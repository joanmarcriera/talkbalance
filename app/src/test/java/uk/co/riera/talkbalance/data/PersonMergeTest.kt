package uk.co.riera.talkbalance.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain-JUnit tests for the pure [combineEmbeddings] merge logic — no Room/Android needed. */
class PersonMergeTest {

    private fun marker(value: Float) = floatArrayOf(value)

    @Test
    fun concatenatesTargetThenSourceWhenUnderCap() {
        val target = arrayOf(marker(1f), marker(2f))
        val source = arrayOf(marker(3f))

        val combined = combineEmbeddings(target, source, cap = 8)

        assertEquals(3, combined.size)
        assertArrayEquals(marker(1f), combined[0], 0f)
        assertArrayEquals(marker(2f), combined[1], 0f)
        assertArrayEquals(marker(3f), combined[2], 0f)
    }

    @Test
    fun cappedAtDefaultEight() {
        val target = Array(5) { marker(it.toFloat()) }
        val source = Array(5) { marker(100f + it) }

        val combined = combineEmbeddings(target, source)

        assertEquals(8, combined.size)
    }

    @Test
    fun targetEmbeddingsPreferredOverSourceWhenTruncating() {
        // Target already has 6, source brings 5 more -> 11 total, capped to 8: all 6 target's kept,
        // only the first 2 of source's fill the remaining capacity.
        val target = Array(6) { marker(it.toFloat()) }
        val source = Array(5) { marker(100f + it) }

        val combined = combineEmbeddings(target, source, cap = 8)

        assertEquals(8, combined.size)
        for (i in 0 until 6) assertArrayEquals(marker(i.toFloat()), combined[i], 0f)
        assertArrayEquals(marker(100f), combined[6], 0f)
        assertArrayEquals(marker(101f), combined[7], 0f)
    }

    @Test
    fun targetAloneAtOrAboveCapDropsAllOfSource() {
        val target = Array(8) { marker(it.toFloat()) }
        val source = Array(3) { marker(100f + it) }

        val combined = combineEmbeddings(target, source, cap = 8)

        assertEquals(8, combined.size)
        for (i in 0 until 8) assertArrayEquals(marker(i.toFloat()), combined[i], 0f)
    }

    @Test
    fun dimensionalityOfEachEmbeddingIsPreserved() {
        val target = arrayOf(floatArrayOf(1f, 2f, 3f))
        val source = arrayOf(floatArrayOf(4f, 5f))

        val combined = combineEmbeddings(target, source)

        assertEquals(3, combined[0].size)
        assertEquals(2, combined[1].size)
    }

    @Test
    fun emptySourceKeepsTargetUnchanged() {
        val target = arrayOf(marker(1f), marker(2f))
        val combined = combineEmbeddings(target, emptyArray())
        assertEquals(2, combined.size)
    }

    @Test
    fun emptyTargetKeepsSourceUnchanged() {
        val source = arrayOf(marker(1f), marker(2f))
        val combined = combineEmbeddings(emptyArray(), source)
        assertEquals(2, combined.size)
        assertArrayEquals(marker(1f), combined[0], 0f)
    }

    @Test
    fun customCapIsRespected() {
        val target = arrayOf(marker(1f), marker(2f), marker(3f))
        val source = arrayOf(marker(4f), marker(5f))

        val combined = combineEmbeddings(target, source, cap = 4)

        assertEquals(4, combined.size)
        assertArrayEquals(marker(4f), combined[3], 0f)
    }
}
