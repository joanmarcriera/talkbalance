package uk.co.riera.talkbalance.enroll

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import uk.co.riera.talkbalance.audio.SpeechChunk
import uk.co.riera.talkbalance.ml.SherpaEngines

/** Trims a raw recording down to just its speech, using a VAD. Injected so the processor's own
 *  logic is testable on the JVM with a synthetic fake (real trimming needs the native VAD). */
fun interface SpeechTrimmer {
    fun trim(samples: FloatArray): List<SpeechChunk>
}

/** Computes one speaker embedding from a run of 16 kHz mono samples. Injected for the same reason —
 *  the native extractor cannot run off-device, so tests supply a deterministic fake. */
fun interface EnrollmentEmbedder {
    fun embed(samples: FloatArray): FloatArray
}

/**
 * Turns ~10 s of captured audio into a stored speaker profile.
 *
 * Pipeline (all pure logic lives in `EnrollmentMath.kt`, unit-tested):
 * 1. VAD-trim the recording to speech only ([SpeechTrimmer]).
 * 2. Quality gate on **net** (silence-removed) speech: `>=` [MIN_NET_SPEECH_MS] or return a typed
 *    [EnrollmentResult.Failure] (TooQuiet / TooShort) with a user-facing message.
 * 3. Concatenate the speech, split it into three equal thirds, and compute one embedding per third
 *    ([EnrollmentEmbedder]) → `Array<FloatArray>` of size 3, ready for `EmbeddingCodec` /
 *    `SpeakerEmbeddingManager.add(name, embeddings)`.
 *
 * ## Threading — sherpa is single-threaded, keep it off the main thread
 * sherpa-onnx's `Vad` and `SpeakerEmbeddingExtractor` are **not thread-safe** and must be driven
 * from one thread at a time. [process] therefore runs on [dispatcher] — a single-parallelism
 * Default dispatcher by default, so concurrent [process] calls are serialised and none of the
 * native work ever lands on the main thread. If you pass a shared `Vad`/extractor that the live
 * session pipeline also uses, make sure enrollment and a live session never run at once (they don't
 * in the app: enrollment is its own screen). The [create] factory `reset()`s the VAD before each
 * trim so no state leaks between recordings.
 *
 * The similar-voice warning is intentionally **not** part of [process]; call [SimilarVoiceCheck]
 * (or the [checkSimilarVoice] convenience) after a [EnrollmentResult.Success], so the UI can decide
 * whether to surface it. Load the existing profiles for that check via [loadProfiles].
 */
class EnrollmentProcessor internal constructor(
    private val trimmer: SpeechTrimmer,
    private val embedder: EnrollmentEmbedder,
    private val sampleRate: Int = SherpaEngines.SAMPLE_RATE,
    private val dispatcher: CoroutineDispatcher = DEFAULT_DISPATCHER,
) {
    /** Clean entry point for the enrollment UI (T10): raw recording in, typed result out. */
    suspend fun process(samples: FloatArray): EnrollmentResult = withContext(dispatcher) {
        val speech = trimmer.trim(samples)
        gateFailure(netSpeechMs(speech, sampleRate))?.let { return@withContext it }

        val thirds = splitIntoThirds(concatSpeech(speech))
        val embeddings = Array(thirds.size) { embedder.embed(thirds[it]) }
        EnrollmentResult.Success(embeddings)
    }

    /** Convenience for a streamed capture: collects the chunks, then [process]es the whole buffer. */
    suspend fun process(chunks: Flow<FloatArray>): EnrollmentResult {
        val collected = chunks.toList()
        val total = collected.sumOf { it.size }
        val samples = FloatArray(total)
        var pos = 0
        for (chunk in collected) {
            System.arraycopy(chunk, 0, samples, pos, chunk.size)
            pos += chunk.size
        }
        return process(samples)
    }

    /** Convenience wrapper over [SimilarVoiceCheck.check] for a successful enrollment. */
    fun checkSimilarVoice(
        result: EnrollmentResult.Success,
        existing: List<EnrolledProfile>,
        threshold: Float = SimilarVoiceCheck.DEFAULT_THRESHOLD,
    ): SimilarVoiceWarning? = SimilarVoiceCheck.check(result.embeddings, existing, threshold)

    companion object {
        /** Single-parallelism so the non-thread-safe sherpa objects are never touched concurrently. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val DEFAULT_DISPATCHER: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

        /** VAD window size (samples) required by the sherpa Silero VAD. */
        private const val VAD_WINDOW = 512

        /**
         * Production factory: wraps the app's single [Vad] + [SpeakerEmbeddingExtractor] (the same
         * instances `SherpaEngines` builds) into the two seams. Both must be called single-threaded
         * — [process] guarantees that via its dispatcher, so obtain these from `SherpaEngines` and
         * do not touch them elsewhere while an enrollment is in flight.
         */
        fun create(
            vad: Vad,
            extractor: SpeakerEmbeddingExtractor,
            sampleRate: Int = SherpaEngines.SAMPLE_RATE,
        ): EnrollmentProcessor = EnrollmentProcessor(
            trimmer = { samples -> vadTrim(vad, samples, sampleRate) },
            embedder = { samples -> extract(extractor, samples, sampleRate) },
            sampleRate = sampleRate,
        )

        /**
         * Feeds the whole recording through the VAD in [VAD_WINDOW]-sample windows, `flush()`es to
         * force out any trailing utterance that had no closing silence (a 10 s clip usually ends
         * mid-speech), and drains every emitted segment as a [SpeechChunk]. Resets the VAD first so
         * no state carries over from a previous recording.
         */
        private fun vadTrim(vad: Vad, samples: FloatArray, sampleRate: Int): List<SpeechChunk> {
            vad.reset()
            val chunks = mutableListOf<SpeechChunk>()
            fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val startMs = segment.start.toLong() * 1000L / sampleRate
                    val endMs = startMs + segment.samples.size.toLong() * 1000L / sampleRate
                    chunks += SpeechChunk(segment.samples, startMs, endMs)
                }
            }
            var offset = 0
            while (offset + VAD_WINDOW <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + VAD_WINDOW))
                offset += VAD_WINDOW
                drain()
            }
            vad.flush()
            drain()
            return chunks
        }

        private fun extract(
            extractor: SpeakerEmbeddingExtractor,
            samples: FloatArray,
            sampleRate: Int,
        ): FloatArray {
            val stream = extractor.createStream()
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            val embedding = extractor.compute(stream)
            stream.release()
            return embedding
        }
    }
}
