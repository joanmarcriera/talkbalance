package uk.co.riera.talkbalance.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.riera.talkbalance.ml.MatchResult
import uk.co.riera.talkbalance.ml.SpeakerMatcher
import uk.co.riera.talkbalance.session.Mode
import uk.co.riera.talkbalance.session.SessionEngine

/**
 * Blocker 2 (Stop must not drop the final utterance), end-to-end at the unit level: a fake VAD that
 * *buffers a pending segment until flush* (exactly the "Stop pressed mid-sentence" case) feeds a
 * real [SessionEngine] through [SpeechSegmenter], mirroring `SessionController.stop`'s
 * drain → flush → engine.onChunk sequence. Without the flush the tail is lost; with it, it is
 * counted in the finished result.
 */
class SegmenterFlushToEngineTest {

    /** Minimal fake VAD: emits nothing during accept(), releases one buffered segment on flush(). */
    private class BufferingVad(private val pending: VadSegment) : VadBoundary {
        private val queue = ArrayDeque<VadSegment>()
        override fun acceptWaveform(samples: FloatArray) {}
        override fun isEmpty(): Boolean = queue.isEmpty()
        override fun front(): VadSegment = queue.first()
        override fun pop() { queue.removeFirst() }
        override fun isSpeechDetected(): Boolean = true
        override fun flush() { queue.addLast(pending) }
    }

    private class FakeMatcher(private val id: String) : SpeakerMatcher {
        override fun match(chunk: SpeechChunk) = MatchResult(id, similarity = 0.9f)
    }

    @Test fun finalBufferedUtteranceIsCountedOnlyAfterFlush() {
        // 2 s tail the VAD is still holding at Stop (32_000 samples @ 16 kHz).
        val tail = VadSegment(startSample = 0, samples = FloatArray(32_000))
        val vad = BufferingVad(tail)
        val segmenter = SpeechSegmenter(vad, sampleRate = 16_000, windowSize = 512, maxChunkMs = 3_500L)
        val engine = SessionEngine(FakeMatcher("A"), Mode.TABLE)
        engine.start()

        // Live audio arrives but the VAD closes no segment yet -> nothing attributed.
        for (chunk in segmenter.accept(FloatArray(512))) engine.onChunk(chunk)
        assertTrue(engine.uiState.value.speakers.isEmpty())

        // stop(): drain (nothing) then flush the buffered tail into the engine.
        for (chunk in segmenter.flush()) engine.onChunk(chunk)
        val result = engine.stop()

        val speakerA = result.speakers.first { it.speakerId == "A" }
        assertEquals("the final 2 s utterance must be counted", 2_000L, speakerA.talkMs)
    }
}
