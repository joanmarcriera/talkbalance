package uk.co.riera.talkbalance.audio

import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One bounded chunk of speech, ready to hand to the embedding extractor. */
data class SpeechChunk(
    val samples: FloatArray,
    val startMs: Long,
    val endMs: Long,
)

/** What [SpeechSegmenter] emits: a finished chunk, or an upstream [AudioCapture] error relayed as-is. */
sealed interface SegmenterEvent {
    data class Chunk(val chunk: SpeechChunk) : SegmenterEvent
    data class Error(val message: String, val cause: Throwable? = null) : SegmenterEvent
}

/**
 * A minimal seam over the sherpa-onnx `Vad` so [SpeechSegmenter]'s windowing/splitting logic is
 * unit-testable on the plain JVM with a fake, without touching JNI or `android.*` types. See
 * [SherpaVadBoundary] for the real adapter used in production.
 */
internal interface VadBoundary {
    fun acceptWaveform(samples: FloatArray)
    fun isEmpty(): Boolean
    fun front(): VadSegment
    fun pop()
    fun isSpeechDetected(): Boolean

    /**
     * Force the VAD to close any in-flight utterance that has no trailing silence yet, so a segment
     * that was still being buffered when capture stopped is emitted rather than lost (mirrors
     * `Vad.flush()`; see `EnrollmentProcessor.vadTrim`).
     */
    fun flush()
}

/** Mirrors `com.k2fsa.sherpa.onnx.SpeechSegment`: [startSample] is in samples, not ms. */
internal data class VadSegment(val startSample: Int, val samples: FloatArray)

/** Thin production adapter: [SpeechSegmenter]'s public constructor wraps a real [Vad] in this. */
internal class SherpaVadBoundary(private val vad: Vad) : VadBoundary {
    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
    override fun isEmpty(): Boolean = vad.empty()
    override fun front(): VadSegment = vad.front().let { VadSegment(it.start, it.samples) }
    override fun pop() = vad.pop()
    override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
    override fun flush() = vad.flush()
}

/**
 * Consumes [AudioCapture]'s 100 ms (1600-sample) float windows, re-buffers them into the exact
 * 512-sample windows the sherpa Vad requires, and drains finished segments into [SpeechChunk]s no
 * longer than [maxChunkMs].
 *
 * **Not thread-safe** — sherpa's `Vad` (like the rest of the sherpa-onnx JNI surface) is
 * single-instance / single-thread only. Feed this class from one consumer only (e.g. a
 * single-threaded coroutine dispatcher), matching the pipeline design in `PLAN.md`.
 *
 * ## The maxSpeechDuration anomaly (see `bench-data/ANOMALY-maxspeech.md`)
 * `SileroVadModelConfig.maxSpeechDuration` does **not** cap emitted segment length. Reading
 * sherpa-onnx v1.13.4's `voice-activity-detector.cc` (`VoiceActivityDetector::Impl::AcceptWaveform`):
 * once the in-flight buffered utterance exceeds `sampleRate * maxSpeechDuration` samples, the
 * *only* effect is that `min_silence_duration` and `threshold` are temporarily swapped to much
 * more aggressive values (0.1 s / 0.90) to make it more likely a short pause ends the segment —
 * it is a soft nudge, not a hard cut. A continuous talker with no pause long enough to cross that
 * lowered silence threshold keeps extending the segment indefinitely (observed: 9224 ms with
 * `maxSpeechDuration=3.0f`). [accept] therefore does the hard cap itself: any VAD-emitted segment
 * longer than [maxChunkMs] is split into consecutive sub-chunks before being handed downstream.
 */
class SpeechSegmenter internal constructor(
    private val boundary: VadBoundary,
    private val sampleRate: Int = SAMPLE_RATE,
    private val windowSize: Int = WINDOW_SIZE,
    private val maxChunkMs: Long = MAX_CHUNK_MS,
) {
    /** Production entry point: wraps a real sherpa [Vad] instance (built by `SherpaEngines`). */
    constructor(vad: Vad, sampleRate: Int = SAMPLE_RATE) : this(SherpaVadBoundary(vad), sampleRate)

    // Remainder from the previous accept() call that didn't fill a full window yet. Only ever
    // touched by the single consumer thread/coroutine — see the class doc.
    private var pendingSamples: FloatArray = FloatArray(0)

    // Absolute count of samples handed to the VAD so far, for computing each window's position.
    private var windowsFed: Long = 0

    private val maxChunkSamples: Int = ((maxChunkMs * sampleRate) / 1000L).toInt()

    private val mutableIsSpeechActive = MutableStateFlow(false)

    /** True while the VAD considers the stream to currently be mid-utterance; drives the UI pulse. */
    val isSpeechActive: StateFlow<Boolean> = mutableIsSpeechActive.asStateFlow()

    /**
     * Feeds one [AudioCapture] event through the segmenter. [AudioEvent.Error] is relayed
     * unchanged (capture failures must reach the UI without the segmenter itself crashing);
     * [AudioEvent.Samples] is windowed into the VAD and may yield zero or more finished chunks.
     */
    fun process(event: AudioEvent): List<SegmenterEvent> = when (event) {
        is AudioEvent.Samples -> accept(event.samples).map { SegmenterEvent.Chunk(it) }
        is AudioEvent.Error -> listOf(SegmenterEvent.Error(event.message, event.cause))
    }

    /**
     * Feeds an arbitrary-length input array (production chunks are 1600 samples = 100 ms at
     * 16 kHz, never a multiple of the VAD's 512-sample window) into the VAD in exact
     * [windowSize]-sample windows, carrying any remainder over to the next call. Returns every
     * [SpeechChunk] the VAD finished as a result, each already capped at [maxChunkMs].
     */
    fun accept(input: FloatArray): List<SpeechChunk> {
        val combined = if (pendingSamples.isEmpty()) input else pendingSamples + input
        var offset = 0
        val emitted = mutableListOf<SpeechChunk>()

        while (combined.size - offset >= windowSize) {
            val window = combined.copyOfRange(offset, offset + windowSize)
            offset += windowSize
            windowsFed += windowSize

            boundary.acceptWaveform(window)
            mutableIsSpeechActive.value = boundary.isSpeechDetected()

            while (!boundary.isEmpty()) {
                val segment = boundary.front()
                boundary.pop()
                emitted += splitIfNeeded(segment)
            }
        }

        pendingSamples = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else EMPTY
        return emitted
    }

    /**
     * Finalises the stream: feeds any sub-window remainder as a final zero-padded window, flushes
     * the VAD to close a still-open utterance (one with no trailing silence, e.g. Stop pressed
     * mid-sentence), and drains every remaining segment — each still capped at [maxChunkMs]. Call
     * once, after the last [accept]/[process], when capture has stopped. Clears the speech pulse.
     */
    fun flush(): List<SpeechChunk> {
        val emitted = mutableListOf<SpeechChunk>()

        // Any buffered samples below a full VAD window would otherwise never reach the VAD; pad
        // to one window with trailing zeros so the tail of the utterance is still seen.
        if (pendingSamples.isNotEmpty()) {
            val padded = pendingSamples.copyOf(windowSize) // zero-filled up to windowSize
            windowsFed += windowSize
            boundary.acceptWaveform(padded)
            drainInto(emitted)
        }
        pendingSamples = EMPTY

        boundary.flush()
        drainInto(emitted)

        mutableIsSpeechActive.value = false
        return emitted
    }

    private fun drainInto(out: MutableList<SpeechChunk>) {
        while (!boundary.isEmpty()) {
            val segment = boundary.front()
            boundary.pop()
            out += splitIfNeeded(segment)
        }
    }

    /** Resets buffered state; does not touch the underlying VAD (call `Vad.reset()` for that). */
    fun reset() {
        pendingSamples = EMPTY
        windowsFed = 0
        mutableIsSpeechActive.value = false
    }

    private fun splitIfNeeded(segment: VadSegment): List<SpeechChunk> {
        val samples = segment.samples
        if (samples.size <= maxChunkSamples) return listOf(toChunk(segment.startSample, samples))

        val chunks = mutableListOf<SpeechChunk>()
        var pos = 0
        while (pos < samples.size) {
            val len = minOf(maxChunkSamples, samples.size - pos)
            chunks += toChunk(segment.startSample + pos, samples.copyOfRange(pos, pos + len))
            pos += len
        }
        return chunks
    }

    private fun toChunk(startSample: Int, samples: FloatArray): SpeechChunk {
        val startMs = startSample.toLong() * 1000L / sampleRate
        val endMs = startMs + samples.size.toLong() * 1000L / sampleRate
        return SpeechChunk(samples, startMs, endMs)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
        const val MAX_CHUNK_MS = 3_500L
        private val EMPTY = FloatArray(0)
    }
}
