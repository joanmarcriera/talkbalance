package uk.co.riera.talkbalance.enroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.riera.talkbalance.audio.SpeechChunk

/**
 * Drives [EnrollmentProcessor] end-to-end with a fake trimmer + fake embedder (the native VAD and
 * extractor can't run off-device), proving the gate → thirds → embedding wiring. Runs on the
 * Unconfined dispatcher so the suspend calls resolve synchronously in the test.
 */
class EnrollmentProcessorTest {

    private val sampleRate = 16_000

    /** Ignores its input and returns a fixed set of "speech" chunks — the gate keys off these. */
    private class FakeTrimmer(private val chunks: List<SpeechChunk>) : SpeechTrimmer {
        override fun trim(samples: FloatArray): List<SpeechChunk> = chunks
    }

    /** Records every array it was asked to embed; returns a 1-D marker embedding of its length. */
    private class RecordingEmbedder : EnrollmentEmbedder {
        val seen = mutableListOf<FloatArray>()
        override fun embed(samples: FloatArray): FloatArray {
            seen += samples
            return floatArrayOf(samples.size.toFloat())
        }
    }

    private fun speechMs(ms: Long): SpeechChunk {
        val n = (ms * sampleRate / 1000L).toInt()
        return SpeechChunk(FloatArray(n) { it.toFloat() }, 0, ms)
    }

    private fun processor(trimmer: SpeechTrimmer, embedder: EnrollmentEmbedder) =
        EnrollmentProcessor(trimmer, embedder, sampleRate, Dispatchers.Unconfined)

    @Test fun rejectsTooShortWithoutComputingEmbeddings() = runBlocking {
        val embedder = RecordingEmbedder()
        val result = processor(FakeTrimmer(listOf(speechMs(3_000))), embedder).process(FloatArray(0))

        assertTrue(result is EnrollmentResult.Failure.TooShort)
        assertEquals(3_000L, (result as EnrollmentResult.Failure.TooShort).netSpeechMs)
        assertTrue("must not run the extractor on a failed gate", embedder.seen.isEmpty())
    }

    @Test fun rejectsTooQuietWhenBarelyAnySpeech() = runBlocking {
        val result = processor(FakeTrimmer(listOf(speechMs(400))), RecordingEmbedder()).process(FloatArray(0))
        assertTrue(result is EnrollmentResult.Failure.TooQuiet)
    }

    @Test fun succeedsWithThreeEmbeddingsFromEqualThirds() = runBlocking {
        // Two chunks totalling 6 s of net speech -> passes the 5 s gate. 96_000 samples / 3 = 32_000.
        val trimmer = FakeTrimmer(listOf(speechMs(4_000), speechMs(2_000)))
        val embedder = RecordingEmbedder()

        val result = processor(trimmer, embedder).process(FloatArray(0))

        assertTrue(result is EnrollmentResult.Success)
        val embeddings = (result as EnrollmentResult.Success).embeddings
        assertEquals(3, embeddings.size)

        // Exactly three embeds, one per equal third of the 96_000 concatenated speech samples.
        assertEquals(3, embedder.seen.size)
        assertEquals(32_000, embedder.seen[0].size)
        assertEquals(32_000, embedder.seen[1].size)
        assertEquals(32_000, embedder.seen[2].size)
        // The marker embedding carries the third's length, confirming that's what was stored.
        assertEquals(32_000f, embeddings[0][0], 0f)
    }

    @Test fun flowOverloadCollectsChunksThenProcessesTheWholeBuffer() = runBlocking {
        val trimmer = FakeTrimmer(listOf(speechMs(6_000)))
        val embedder = RecordingEmbedder()
        // Feed the ~10 s recording as a stream of 100 ms windows.
        val windows = List(100) { FloatArray(1_600) }.asFlow()

        val result = processor(trimmer, embedder).process(windows)

        assertTrue(result is EnrollmentResult.Success)
        assertEquals(3, (result as EnrollmentResult.Success).embeddings.size)
    }

    @Test fun checkSimilarVoiceFlagsAnExistingClose() = runBlocking {
        val trimmer = FakeTrimmer(listOf(speechMs(6_000)))
        // Constant embedder so the new profile's centroid is a fixed direction.
        val embedder = EnrollmentEmbedder { floatArrayOf(1f, 0f) }
        val proc = processor(trimmer, embedder)

        val success = proc.process(FloatArray(0)) as EnrollmentResult.Success
        val warning = proc.checkSimilarVoice(
            success,
            existing = listOf(EnrolledProfile("Alex", arrayOf(floatArrayOf(2f, 0f)))),
        )
        assertEquals("Alex", warning?.name)
    }
}
