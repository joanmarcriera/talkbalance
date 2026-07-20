package uk.co.riera.talkbalance.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.riera.talkbalance.audio.SpeechChunk

/** Unit tests for the pure enrollment maths: net-speech gate, thirds split, similar-voice cosine. */
class EnrollmentMathTest {

    private val sampleRate = 16_000

    private fun chunkOfMs(ms: Long): SpeechChunk {
        val n = (ms * sampleRate / 1000L).toInt()
        return SpeechChunk(FloatArray(n), 0, ms)
    }

    // --- net-speech-duration gate --------------------------------------------------------------

    @Test fun netSpeechMsSumsChunkSampleCounts() {
        val chunks = listOf(chunkOfMs(2_000), chunkOfMs(1_500))
        assertEquals(3_500L, netSpeechMs(chunks, sampleRate))
    }

    @Test fun gatePassesAtExactlyFiveSeconds() {
        // Boundary: exactly MIN_NET_SPEECH_MS must pass (>= gate, not >).
        assertNull(gateFailure(MIN_NET_SPEECH_MS))
    }

    @Test fun gatePassesComfortablyAboveFiveSeconds() {
        assertNull(gateFailure(7_200L))
    }

    @Test fun gateRejectsJustUnderFiveSecondsAsTooShort() {
        val failure = gateFailure(MIN_NET_SPEECH_MS - 1)
        assertTrue(failure is EnrollmentResult.Failure.TooShort)
        assertEquals(MIN_NET_SPEECH_MS - 1, (failure as EnrollmentResult.Failure.TooShort).netSpeechMs)
        assertTrue(failure.message.isNotBlank())
    }

    @Test fun gateRejectsNearSilenceAsTooQuiet() {
        val failure = gateFailure(QUIET_FLOOR_MS - 1)
        assertTrue(failure is EnrollmentResult.Failure.TooQuiet)
        assertTrue((failure as EnrollmentResult.Failure.TooQuiet).message.isNotBlank())
    }

    @Test fun quietFloorIsTheBoundaryBetweenQuietAndShort() {
        // At exactly QUIET_FLOOR_MS we are no longer "too quiet" but still "too short".
        assertTrue(gateFailure(QUIET_FLOOR_MS) is EnrollmentResult.Failure.TooShort)
        assertTrue(gateFailure(QUIET_FLOOR_MS - 1) is EnrollmentResult.Failure.TooQuiet)
    }

    // --- thirds-splitting math -----------------------------------------------------------------

    @Test fun thirdBoundariesSplitEvenlyDivisibleLength() {
        assertTrue(intArrayOf(0, 3, 6, 9).contentEquals(thirdBoundaries(9)))
    }

    @Test fun thirdBoundariesSendRemainderToLastThird() {
        // 10 -> last third gets the extra sample; 11 -> 2*11/3 == 7 so middle gets it.
        assertTrue(intArrayOf(0, 3, 6, 10).contentEquals(thirdBoundaries(10)))
        assertTrue(intArrayOf(0, 3, 7, 11).contentEquals(thirdBoundaries(11)))
    }

    @Test fun splitIntoThirdsCoversAllSamplesContiguouslyWithoutOverlap() {
        val samples = FloatArray(3_331) { it.toFloat() }
        val thirds = splitIntoThirds(samples)

        assertEquals(3, thirds.size)
        // Reassembling the thirds must reproduce the original stream exactly.
        val reassembled = thirds.flatMap { it.toList() }
        assertEquals(samples.toList(), reassembled)
        // First two thirds equal (floor division), remainder lands in the last.
        assertEquals(3_331 / 3, thirds[0].size)
        assertEquals(3_331 / 3, thirds[1].size)
        assertEquals(3_331 - 2 * (3_331 / 3), thirds[2].size)
    }

    @Test fun concatSpeechJoinsChunkSamplesInOrder() {
        val a = SpeechChunk(floatArrayOf(1f, 2f), 0, 0)
        val b = SpeechChunk(floatArrayOf(3f, 4f, 5f), 0, 0)
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), concatSpeech(listOf(a, b)).toList())
    }

    // --- similar-voice cosine threshold logic --------------------------------------------------

    @Test fun cosineOfIdenticalVectorsIsOne() {
        assertEquals(1f, cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), 1e-6f)
    }

    @Test fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test fun centroidIsElementWiseMean() {
        val c = centroid(arrayOf(floatArrayOf(0f, 2f), floatArrayOf(2f, 4f)))
        assertTrue(floatArrayOf(1f, 3f).contentEquals(c))
    }

    @Test fun similarVoiceWarnsOnlyAboveThreshold() {
        val newVoice = arrayOf(floatArrayOf(1f, 0f))
        // Alex: identical direction (cos 1.0) -> warn. Sam: orthogonal (cos 0) -> ignored.
        val existing = listOf(
            EnrolledProfile("Alex", arrayOf(floatArrayOf(2f, 0f))),
            EnrolledProfile("Sam", arrayOf(floatArrayOf(0f, 5f))),
        )
        val warning = SimilarVoiceCheck.check(newVoice, existing)
        assertEquals("Alex", warning?.name)
        assertEquals(1f, warning!!.similarity, 1e-6f)
        assertTrue(warning.message.contains("Alex"))
    }

    @Test fun similarVoiceReturnsNullWhenAllBelowThreshold() {
        val newVoice = arrayOf(floatArrayOf(1f, 0f))
        val existing = listOf(EnrolledProfile("Sam", arrayOf(floatArrayOf(0f, 1f)))) // cos 0
        assertNull(SimilarVoiceCheck.check(newVoice, existing))
    }

    @Test fun similarVoicePicksTheMostSimilarWhenSeveralExceedThreshold() {
        // Both exceed 0.6, but Bo is a closer direction to the new voice than Cy.
        val newVoice = arrayOf(floatArrayOf(1f, 0.1f))
        val existing = listOf(
            EnrolledProfile("Cy", arrayOf(floatArrayOf(1f, 0.9f))),
            EnrolledProfile("Bo", arrayOf(floatArrayOf(1f, 0.05f))),
        )
        val warning = SimilarVoiceCheck.check(newVoice, existing, threshold = 0.6f)
        assertEquals("Bo", warning?.name)
    }

    @Test fun similarVoiceIgnoresProfilesEnrolledWithADifferentDimensionModel() {
        val newVoice = arrayOf(floatArrayOf(1f, 0f, 0f))
        val existing = listOf(EnrolledProfile("OldModel", arrayOf(floatArrayOf(1f, 0f)))) // dim 2 vs 3
        assertNull(SimilarVoiceCheck.check(newVoice, existing))
    }

    @Test fun similarVoiceReturnsNullWhenNoExistingProfiles() {
        assertNull(SimilarVoiceCheck.check(arrayOf(floatArrayOf(1f)), emptyList()))
    }
}
