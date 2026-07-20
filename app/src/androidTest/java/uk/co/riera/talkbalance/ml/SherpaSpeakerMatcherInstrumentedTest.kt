package uk.co.riera.talkbalance.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uk.co.riera.talkbalance.audio.SpeechChunk

/**
 * Device-only check of [SherpaSpeakerMatcher] against the *real* native `SpeakerEmbeddingManager`
 * and `SpeakerEmbeddingExtractor` (JNI can't run on the plain JVM, hence this lives in androidTest
 * rather than test — see `SpeakerMatchingTest` for the plain-JVM cosine/centroid coverage).
 *
 * The bench decision (`bench-data/T3-DECISION.md`) already validated real-speech accuracy (CAM++,
 * threshold 0.50); this test only proves the T7 wiring is correct — enrollment reaches the
 * manager, `match()` returns the enrolled id for the same voice it was enrolled with, and doesn't
 * cross-match an obviously different signal — using cheap synthetic tones so it doesn't depend on
 * a microphone.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSpeakerMatcherInstrumentedTest {

    private lateinit var extractor: SpeakerEmbeddingExtractor
    private var matcher: SherpaSpeakerMatcher? = null

    @Before
    fun setUp() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        extractor = SpeakerEmbeddingExtractor(
            assetManager = assets,
            config = SpeakerEmbeddingExtractorConfig(
                model = "wespeaker_en_voxceleb_CAM++_LM.onnx",
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
    }

    @After
    fun tearDown() {
        matcher?.close()
        extractor.release()
    }

    @Test
    fun tableModeMatchesTheSameEnrolledVoiceAboveThreshold() {
        val aliceTone = tone(hz = 220.0)
        val bobTone = tone(hz = 880.0)

        val alice = EnrolledPerson("alice", arrayOf(embed(extractor, aliceTone)))
        val bob = EnrolledPerson("bob", arrayOf(embed(extractor, bobTone)))
        matcher = SherpaSpeakerMatcher(extractor, listOf(alice, bob), threshold = 0.5f, mode = MatcherMode.TABLE)

        val result = matcher!!.match(chunkOf(aliceTone))

        assertEquals("alice", result.speakerId)
        assertTrue("expected high similarity for the same tone, got ${result.similarity}", result.similarity > 0.5f)
    }

    @Test
    fun tableModeReturnsNullSpeakerIdWhenNobodyIsEnrolled() {
        matcher = SherpaSpeakerMatcher(extractor, emptyList(), threshold = 0.5f, mode = MatcherMode.TABLE)

        val result = matcher!!.match(chunkOf(tone(hz = 440.0)))

        assertNull(result.speakerId)
        assertEquals(0f, result.similarity, 1e-5f)
    }

    @Test
    fun meModeVerifiesOnlyTheReservedMeId() {
        val meTone = tone(hz = 330.0)
        val me = EnrolledPerson(SherpaSpeakerMatcher.ME_ID, arrayOf(embed(extractor, meTone)))
        matcher = SherpaSpeakerMatcher(extractor, listOf(me), threshold = 0.5f, mode = MatcherMode.ME)

        val result = matcher!!.match(chunkOf(meTone))

        assertEquals(SherpaSpeakerMatcher.ME_ID, result.speakerId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun meModeRequiresAnEnrolledMeEntry() {
        val someone = EnrolledPerson("not-me", arrayOf(embed(extractor, tone(hz = 500.0))))
        matcher = SherpaSpeakerMatcher(extractor, listOf(someone), threshold = 0.5f, mode = MatcherMode.ME)
    }

    private fun embed(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        stream.inputFinished()
        val embedding = extractor.compute(stream)
        stream.release()
        return embedding
    }

    private fun chunkOf(samples: FloatArray): SpeechChunk = SpeechChunk(samples, startMs = 0L, endMs = 1000L)

    /** One second of a pure sine tone at 16 kHz — cheap, deterministic, mic-free test input. */
    private fun tone(hz: Double, seconds: Double = 1.0): FloatArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        return FloatArray(n) { i -> (0.3 * sin(2.0 * PI * hz * i / SAMPLE_RATE)).toFloat() }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
