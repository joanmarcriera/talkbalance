package uk.co.riera.talkbalance.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun overCapReservesHalfTheCapacityForSource() {
        // Target has 6, source brings 5 -> 11 total, capped to 8. Source is reserved cap/2 = 4,
        // so the target's first 4 are kept and the source's first 4 fill the rest.
        val target = Array(6) { marker(it.toFloat()) }
        val source = Array(5) { marker(100f + it) }

        val combined = combineEmbeddings(target, source, cap = 8)

        assertEquals(8, combined.size)
        for (i in 0 until 4) assertArrayEquals(marker(i.toFloat()), combined[i], 0f)
        for (i in 0 until 4) assertArrayEquals(marker(100f + i), combined[4 + i], 0f)
    }

    /**
     * The defect this rule exists to prevent: "Add to <name> instead" folding a fresh recording into
     * an already-full profile used to keep the target's 8 and drop the recording entirely, while the
     * UI reported success. The incoming voice must always land.
     */
    @Test
    fun targetAlreadyAtCapStillAbsorbsSource() {
        val target = Array(8) { marker(it.toFloat()) }
        val source = Array(3) { marker(100f + it) }

        val combined = combineEmbeddings(target, source, cap = 8)

        assertEquals(8, combined.size)
        // Source's 3 all survive (3 <= cap/2), target keeps its first 5.
        for (i in 0 until 5) assertArrayEquals(marker(i.toFloat()), combined[i], 0f)
        for (i in 0 until 3) assertArrayEquals(marker(100f + i), combined[5 + i], 0f)
    }

    @Test
    fun sourceAlwaysContributesAtLeastOneEmbeddingWhateverTheCap() {
        // Property check across the shapes that can reach this: a non-empty source is never
        // silently discarded, at any cap or target size.
        for (cap in 1..12) {
            for (targetSize in 0..12) {
                for (sourceSize in 1..12) {
                    val target = Array(targetSize) { marker(it.toFloat()) }
                    val source = Array(sourceSize) { marker(100f + it) }

                    val combined = combineEmbeddings(target, source, cap = cap)

                    assertTrue(
                        "cap=$cap target=$targetSize source=$sourceSize produced ${combined.size}",
                        combined.size <= cap,
                    )
                    val absorbed = combined.count { it[0] >= 100f }
                    assertTrue(
                        "cap=$cap target=$targetSize source=$sourceSize absorbed nothing of source",
                        absorbed >= 1,
                    )
                }
            }
        }
    }

    @Test
    fun emptySourceLeavesAnAtCapTargetUntouched() {
        val target = Array(8) { marker(it.toFloat()) }

        val combined = combineEmbeddings(target, emptyArray(), cap = 8)

        assertEquals(8, combined.size)
        for (i in 0 until 8) assertArrayEquals(marker(i.toFloat()), combined[i], 0f)
    }

    @Test
    fun nonPositiveCapYieldsNothing() {
        val combined = combineEmbeddings(arrayOf(marker(1f)), arrayOf(marker(2f)), cap = 0)
        assertEquals(0, combined.size)
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
        // 3 target + 2 source = 5, capped to 4. Source is reserved cap/2 = 2, so target keeps 2.
        val target = arrayOf(marker(1f), marker(2f), marker(3f))
        val source = arrayOf(marker(4f), marker(5f))

        val combined = combineEmbeddings(target, source, cap = 4)

        assertEquals(4, combined.size)
        assertArrayEquals(marker(1f), combined[0], 0f)
        assertArrayEquals(marker(2f), combined[1], 0f)
        assertArrayEquals(marker(4f), combined[2], 0f)
        assertArrayEquals(marker(5f), combined[3], 0f)
    }

    // --- combineSpeakerStats (same-session dual-row consolidation) -------------------------------

    @Test
    fun combineSpeakerStatsSumsTalkAndTurnsButMaxesLongestMonologue() {
        val combined = combineSpeakerStats(
            target = SpeakerStatValues(talkMs = 400L, turns = 3, longestMonologueMs = 250L),
            source = SpeakerStatValues(talkMs = 600L, turns = 2, longestMonologueMs = 500L),
        )

        assertEquals(1000L, combined.talkMs)
        assertEquals(5, combined.turns)
        assertEquals(500L, combined.longestMonologueMs)
    }

    @Test
    fun combineSpeakerStatsKeepsTargetLongestMonologueWhenItIsLarger() {
        val combined = combineSpeakerStats(
            target = SpeakerStatValues(talkMs = 100L, turns = 1, longestMonologueMs = 900L),
            source = SpeakerStatValues(talkMs = 100L, turns = 1, longestMonologueMs = 300L),
        )

        assertEquals(900L, combined.longestMonologueMs)
    }
}
