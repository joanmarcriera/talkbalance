package uk.co.riera.talkbalance.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scriptable fake standing in for the real sherpa [com.k2fsa.sherpa.onnx.Vad] so the segmenter's
 * windowing/splitting logic is testable on the plain JVM. Records every window it was fed (so
 * tests can verify the 1600-input -> 512-window remainder math byte-for-byte) and lets a test
 * enqueue [VadSegment]s to be drained on demand, exactly like the real VAD's internal queue.
 */
private class FakeVadBoundary : VadBoundary {
    val acceptedWindows = mutableListOf<FloatArray>()
    private val queue = ArrayDeque<VadSegment>()

    /** Optional hook fired after each accepted window, e.g. to enqueue a segment on window N. */
    var onAccept: ((windowIndex: Int) -> Unit)? = null
    var speechDetected = false

    override fun acceptWaveform(samples: FloatArray) {
        acceptedWindows += samples
        onAccept?.invoke(acceptedWindows.size - 1)
    }

    override fun isEmpty(): Boolean = queue.isEmpty()
    override fun front(): VadSegment = queue.first()
    override fun pop() {
        queue.removeFirst()
    }
    override fun isSpeechDetected(): Boolean = speechDetected

    fun enqueue(segment: VadSegment) = queue.addLast(segment)
}

class SpeechSegmenterTest {

    // --- window re-buffering: 1600-sample capture chunks vs the VAD's fixed 512-sample window ---

    @Test fun rebuffersUnevenInputIntoExactWindowsWithNoGapOrDuplicate() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512)

        // LCM(1600, 512) = 12_800, i.e. exactly 8 capture-sized chunks divide evenly into 512
        // windows with nothing left over -- a real end-to-end check of the remainder carry math.
        var next = 0f
        fun chunkOf(size: Int): FloatArray = FloatArray(size) { next++ }

        repeat(8) { segmenter.accept(chunkOf(1_600)) }

        val fed = fake.acceptedWindows.flatMap { it.toList() }
        assertEquals(25, fake.acceptedWindows.size) // 12_800 / 512
        assertEquals(12_800, fed.size)
        // Every element in original stream order, exactly once -- proves no sample was dropped,
        // reordered, or duplicated across the remainder-carrying accept() calls.
        assertEquals((0 until 12_800).map { it.toFloat() }, fed)
    }

    @Test fun leftoverRemainderIsCarriedAcrossCallsNotDropped() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512)

        // First 1600-sample chunk: 3 windows (1536 samples) consumed, 64 left pending.
        segmenter.accept(FloatArray(1_600) { it.toFloat() })
        assertEquals(3, fake.acceptedWindows.size)

        // A second chunk of only 100 samples is nowhere near a full window by itself (64 + 100 =
        // 164 < 512) -- if the remainder were dropped instead of carried, this would still fire
        // zero windows either way, so make the pending 64 samples observable via a third chunk
        // that completes a window using content from all three accept() calls.
        segmenter.accept(FloatArray(100) { 1_600f + it })
        assertEquals(3, fake.acceptedWindows.size) // still short of the next 512 boundary (164)

        segmenter.accept(FloatArray(348) { 1_700f + it }) // 164 + 348 = 512 exactly
        assertEquals(4, fake.acceptedWindows.size)

        val fourthWindow = fake.acceptedWindows[3]
        // Must start with the 64 samples carried over from the very first chunk (indices 1536..1599).
        assertEquals(1_536f, fourthWindow[0], 0f)
        assertEquals(1_599f, fourthWindow[63], 0f)
        // ...continue with the second chunk's 100 samples (1600..1699)...
        assertEquals(1_600f, fourthWindow[64], 0f)
        assertEquals(1_699f, fourthWindow[163], 0f)
        // ...and finish with the third chunk's contribution (1700..2047).
        assertEquals(1_700f, fourthWindow[164], 0f)
        assertEquals(2_047f, fourthWindow[511], 0f)
    }

    // --- manual max-length splitting (the maxSpeechDuration anomaly workaround) ---

    @Test fun segmentAtExactlyMaxLengthIsNotSplit() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512, maxChunkMs = 3_500L)
        val maxSamples = 3_500 * 16_000 / 1_000 // 56_000

        fake.onAccept = { windowIndex -> if (windowIndex == 0) fake.enqueue(VadSegment(0, FloatArray(maxSamples))) }
        val chunks = segmenter.accept(FloatArray(512))

        assertEquals(1, chunks.size)
        assertEquals(maxSamples, chunks[0].samples.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(3_500L, chunks[0].endMs)
    }

    @Test fun segmentOverMaxLengthSplitsIntoBoundedConsecutiveChunks() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512, maxChunkMs = 3_500L)
        val maxSamples = 3_500 * 16_000 / 1_000 // 56_000

        // Reproduces the bench anomaly directly: a 9224 ms utterance the VAD never internally cut.
        val observedSamples = (9_224 * 16_000 / 1_000) // 147_584
        fake.onAccept = { windowIndex ->
            if (windowIndex == 0) fake.enqueue(VadSegment(startSample = 1_000, samples = FloatArray(observedSamples)))
        }

        val chunks = segmenter.accept(FloatArray(512))

        // Downstream must never see a chunk longer than the 3.5 s cap.
        assertTrue(chunks.all { it.samples.size <= maxSamples })
        assertEquals(3, chunks.size) // 56_000 + 56_000 + 35_584
        assertEquals(maxSamples, chunks[0].samples.size)
        assertEquals(maxSamples, chunks[1].samples.size)
        assertEquals(observedSamples - 2 * maxSamples, chunks[2].samples.size)

        // Total samples preserved across the split -- nothing dropped or duplicated at the seams.
        assertEquals(observedSamples, chunks.sumOf { it.samples.size })

        // Timing is contiguous: each chunk's startMs picks up exactly where the previous ended.
        assertEquals(chunks[0].endMs, chunks[1].startMs)
        assertEquals(chunks[1].endMs, chunks[2].startMs)
        val startMs = 1_000L * 1000L / 16_000L
        assertEquals(startMs, chunks[0].startMs)
    }

    @Test fun multipleSegmentsInOneAcceptCallAreAllEmittedInOrder() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512, maxChunkMs = 3_500L)
        fake.onAccept = { windowIndex ->
            if (windowIndex == 0) {
                fake.enqueue(VadSegment(0, FloatArray(100)))
                fake.enqueue(VadSegment(200, FloatArray(200)))
            }
        }

        val chunks = segmenter.accept(FloatArray(512))

        assertEquals(2, chunks.size)
        assertEquals(100, chunks[0].samples.size)
        assertEquals(200, chunks[1].samples.size)
    }

    // --- isSpeechActive pulse ---

    @Test fun isSpeechActiveTracksVadStateAfterEachWindow() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512)
        assertFalse(segmenter.isSpeechActive.value)

        fake.speechDetected = true
        segmenter.accept(FloatArray(512))
        assertTrue(segmenter.isSpeechActive.value)

        fake.speechDetected = false
        segmenter.accept(FloatArray(512))
        assertFalse(segmenter.isSpeechActive.value)
    }

    // --- error-event propagation from AudioCapture through the segmenter ---

    @Test fun processRelaysCaptureErrorsWithoutThrowingOrLosingState() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512)

        val before = segmenter.process(AudioEvent.Samples(FloatArray(512)))
        assertTrue(before.isEmpty())

        val cause = RuntimeException("mic stolen by a call")
        val result = segmenter.process(AudioEvent.Error("AudioRecord.read: ERROR_DEAD_OBJECT", cause))

        assertEquals(1, result.size)
        val error = result[0] as SegmenterEvent.Error
        assertEquals("AudioRecord.read: ERROR_DEAD_OBJECT", error.message)
        assertEquals(cause, error.cause)

        // Processing must continue working normally afterwards -- an error event must not corrupt
        // the segmenter's buffered remainder state.
        fake.onAccept = { windowIndex -> if (windowIndex == fake.acceptedWindows.size - 1) fake.enqueue(VadSegment(0, FloatArray(10))) }
        val after = segmenter.process(AudioEvent.Samples(FloatArray(512)))
        assertEquals(1, after.size)
        assertTrue(after[0] is SegmenterEvent.Chunk)
    }

    @Test fun processWrapsAcceptedSamplesAsChunkEvents() {
        val fake = FakeVadBoundary()
        val segmenter = SpeechSegmenter(fake, sampleRate = 16_000, windowSize = 512)
        fake.onAccept = { windowIndex -> if (windowIndex == 0) fake.enqueue(VadSegment(0, FloatArray(50))) }

        val events = segmenter.process(AudioEvent.Samples(FloatArray(512)))

        assertEquals(1, events.size)
        assertTrue(events[0] is SegmenterEvent.Chunk)
        assertEquals(50, (events[0] as SegmenterEvent.Chunk).chunk.samples.size)
    }
}
