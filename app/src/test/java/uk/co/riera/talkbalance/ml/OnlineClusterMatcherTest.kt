package uk.co.riera.talkbalance.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [OnlineClusterMatcher]'s pure clustering decision ([assign]) — the
 * new-vs-join branch, the incremental-mean centroid update, and the [MAX_SPEAKERS] cap — driven with
 * injected embeddings (no sherpa / device). The embedding function is identity here; production
 * plugs in the real extractor via [OnlineClusterMatcher.create].
 */
class OnlineClusterMatcherTest {

    /** Identity embedder: [assign] is called directly with the vectors under test. */
    private fun matcher(joinThreshold: Float = 0.5f, maxSpeakers: Int = 10, minSpawnMs: Long = 800L) =
        OnlineClusterMatcher(
            embed = { it },
            joinThreshold = joinThreshold,
            maxSpeakers = maxSpeakers,
            minSpawnMs = minSpawnMs,
        )

    // --- new-vs-join ---

    @Test fun firstVoiceSeedsClusterS1() {
        val m = matcher()
        val r = m.assign(floatArrayOf(1f, 0f, 0f))
        assertEquals("s1", r.speakerId)
        assertEquals(1, m.speakerCount)
        assertEquals(0f, r.similarity, 1e-6f) // no prior cluster to compare against
    }

    @Test fun similarVoiceJoinsExistingCluster() {
        val m = matcher(joinThreshold = 0.8f)
        assertEquals("s1", m.assign(floatArrayOf(1f, 0f)).speakerId)
        // cos([1,0],[1,0.05]) ~= 0.9988 >= 0.8 -> joins s1, no new cluster.
        val r = m.assign(floatArrayOf(1f, 0.05f))
        assertEquals("s1", r.speakerId)
        assertEquals(1, m.speakerCount)
        assertTrue("similarity should be high", r.similarity > 0.9f)
    }

    @Test fun dissimilarVoiceSpawnsNewCluster() {
        val m = matcher(joinThreshold = 0.5f)
        assertEquals("s1", m.assign(floatArrayOf(1f, 0f)).speakerId)
        // Orthogonal vector: cos = 0 < 0.5 -> new cluster s2.
        assertEquals("s2", m.assign(floatArrayOf(0f, 1f)).speakerId)
        assertEquals(2, m.speakerCount)
    }

    @Test fun neverReturnsNullSpeakerIdInDiscover() {
        val m = matcher()
        assertNotNull(m.assign(floatArrayOf(0f, 1f)).speakerId)
        assertNotNull(m.assign(floatArrayOf(1f, 0f)).speakerId)
    }

    // --- incremental-mean centroid update ---

    @Test fun centroidIsRunningMeanOfJoinedEmbeddings() {
        // joinThreshold = -1 forces every vector to fold into s1, so we can verify the mean exactly.
        val m = matcher(joinThreshold = -1f)
        m.assign(floatArrayOf(2f, 0f))            // centroid = [2,0]
        m.assign(floatArrayOf(0f, 2f))            // n=2 -> centroid = [1,1]
        // A probe equal to the expected mean [1,1] must have cosine ~1.0 against the centroid.
        val probe = m.assign(floatArrayOf(1f, 1f))
        assertEquals("s1", probe.speakerId)
        assertEquals(1.0f, probe.similarity, 1e-4f)
    }

    // --- MAX_SPEAKERS cap ---

    @Test fun capAssignsToNearestInsteadOfGrowing() {
        val m = matcher(joinThreshold = 0.99f, maxSpeakers = 2)
        assertEquals("s1", m.assign(floatArrayOf(1f, 0f)).speakerId)
        assertEquals("s2", m.assign(floatArrayOf(0f, 1f)).speakerId)
        assertEquals(2, m.speakerCount)
        // A third, distinct voice at the cap: below threshold but no room -> nearest existing cluster.
        val r = m.assign(floatArrayOf(0.9f, 0.1f)) // closer to s1 ([1,0]) than s2 ([0,1])
        assertEquals("s1", r.speakerId)
        assertEquals(2, m.speakerCount) // cap held
    }

    @Test fun distinctVoicesGetDistinctIdsUpToTheCap() {
        val m = matcher(joinThreshold = 0.9f, maxSpeakers = 3)
        val a = m.assign(floatArrayOf(1f, 0f, 0f)).speakerId
        val b = m.assign(floatArrayOf(0f, 1f, 0f)).speakerId
        val c = m.assign(floatArrayOf(0f, 0f, 1f)).speakerId
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertEquals(3, m.speakerCount)
    }

    // --- minSpawnMs gate on the very first chunk ---

    @Test fun shortFirstChunkDoesNotSeedASpeaker() {
        // A cough / door slam before anyone speaks must not become "Speaker 1" — every later real
        // voice would then be scored against a noise centroid.
        val m = matcher(minSpawnMs = 800L)
        val r = m.assign(floatArrayOf(1f, 0f, 0f), durationMs = 200L)
        assertNull(r.speakerId)
        assertEquals(0, m.speakerCount)
    }

    @Test fun longFirstChunkStillSeedsClusterS1() {
        val m = matcher(minSpawnMs = 800L)
        val r = m.assign(floatArrayOf(1f, 0f, 0f), durationMs = 1_200L)
        assertEquals("s1", r.speakerId)
        assertEquals(1, m.speakerCount)
    }

    @Test fun firstRealVoiceAfterAShortNoiseChunkBecomesS1() {
        val m = matcher(minSpawnMs = 800L)
        m.assign(floatArrayOf(1f, 0f, 0f), durationMs = 200L) // noise, dropped
        val r = m.assign(floatArrayOf(0f, 1f, 0f), durationMs = 1_200L)
        assertEquals("s1", r.speakerId)
        assertEquals(1, m.speakerCount)
    }

    @Test fun shortChunkStillFoldsIntoAnExistingCluster() {
        // Once a voice exists the short chunk has somewhere to go: join, never spawn.
        val m = matcher(joinThreshold = 0.9f, minSpawnMs = 800L)
        assertEquals("s1", m.assign(floatArrayOf(1f, 0f), durationMs = 1_200L).speakerId)
        val r = m.assign(floatArrayOf(0f, 1f), durationMs = 100L) // distinct, but too short to spawn
        assertEquals("s1", r.speakerId)
        assertEquals(1, m.speakerCount)
    }
}
