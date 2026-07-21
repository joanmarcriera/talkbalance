package uk.co.riera.talkbalance.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocker 5 (capture shutdown must not wedge): drives the real [AudioCapture] capture thread on the
 * JVM through a synthetic [FrameSource] (no device `AudioRecord`) to prove that (a) a full channel
 * with no consumer drops frames instead of blocking, and (b) [AudioCapture.stop] always completes
 * and joins the thread. Also unit-tests the pure [OverflowPolicy] count/alert accounting.
 */
class AudioCaptureShutdownTest {

    /** Emits a fixed-size frame on every read (sleeping 1 ms to avoid a hot spin). Never ends on its own. */
    private class InfiniteFrameSource(private val chunkSamples: Int) : FrameSource {
        val closed = AtomicBoolean(false)
        override fun open(): String? = null
        override fun read(out: ShortArray): Int {
            Thread.sleep(1)
            return chunkSamples
        }
        override fun close() {
            closed.set(true)
        }
    }

    @Test fun stopCompletesWhenChannelIsFullAndNoOneConsumes() {
        val source = InfiniteFrameSource(chunkSamples = 100)
        val capture = AudioCapture(
            sampleRate = 16_000,
            chunkSamples = 100,
            channelCapacity = 2,
            frameSourceFactory = { source },
            alertThreshold = 4,
        )

        capture.start()
        // Let the producer fill the tiny channel and start dropping — no consumer reads `events`.
        Thread.sleep(60)

        // The wedge case: a blocking send would hang here forever. stop() must return promptly.
        capture.stop()

        assertFalse("capture thread must have exited", capture.isRunning)
        assertTrue("frames must have been dropped, not block the thread", capture.droppedFrameCount > 0)
        assertTrue("frame source must be released on shutdown", source.closed.get())
    }

    @Test fun openFailureIsSurfacedAsAnErrorEventAndThreadExits() {
        val capture = AudioCapture(
            sampleRate = 16_000,
            chunkSamples = 100,
            channelCapacity = 8,
            frameSourceFactory = {
                object : FrameSource {
                    override fun open(): String? = "mic unavailable"
                    override fun read(out: ShortArray): Int = 0
                    override fun close() {}
                }
            },
        )
        capture.start()
        Thread.sleep(50) // let the capture thread run open(), surface the error, and exit

        // Drain the buffered events before closing the channel, so the error isn't raced away.
        val events = generateSequence { capture.events.tryReceive().getOrNull() }.toList()
        assertTrue(events.any { it is AudioEvent.Error && (it as AudioEvent.Error).message.contains("mic unavailable") })

        capture.stop()
        assertFalse(capture.isRunning)
    }

    // --- pure OverflowPolicy accounting ---

    @Test fun overflowPolicyCountsEveryDropAndAlertsOncePerBurst() {
        val policy = OverflowPolicy(alertThreshold = 3)

        assertFalse(policy.onDropped()) // 1
        assertFalse(policy.onDropped()) // 2
        assertTrue(policy.onDropped())  // 3 -> alert fires exactly once
        assertFalse(policy.onDropped()) // 4 -> already alerted this burst
        assertEquals(4L, policy.droppedFrames)

        policy.onDelivered() // channel drained: burst over, re-arm the alert
        assertFalse(policy.onDropped())
        assertFalse(policy.onDropped())
        assertTrue(policy.onDropped()) // alert re-fires on the next burst
        assertEquals(7L, policy.droppedFrames)
    }
}
