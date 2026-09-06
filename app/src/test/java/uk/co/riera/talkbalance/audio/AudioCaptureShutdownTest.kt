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

    /** A [FrameSource] whose open() fails a chosen way; records whether close() was called. */
    private class FailingOpenSource(private val mode: Mode) : FrameSource {
        enum class Mode { RETURNS_ERROR, THROWS }
        val closed = AtomicBoolean(false)
        override fun open(): String? = when (mode) {
            Mode.RETURNS_ERROR -> "mic unavailable"
            Mode.THROWS -> throw IllegalStateException("boom in open")
        }
        override fun read(out: ShortArray): Int = 0
        override fun close() {
            closed.set(true)
        }
    }

    /** Waits (bounded) for the capture thread to exit; fails loudly instead of hanging on a wedge. */
    private fun awaitStopped(capture: AudioCapture) {
        val deadline = System.currentTimeMillis() + 2_000
        while (capture.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("capture thread must exit when open() fails", capture.isRunning)
    }

    @Test fun openReturningErrorSurfacesItAndStillReleasesTheSource() {
        // open() fails AFTER (conceptually) allocating: the thread must both surface the error AND
        // run source.close() on the way out — the leak the old code missed.
        val source = FailingOpenSource(FailingOpenSource.Mode.RETURNS_ERROR)
        val capture = AudioCapture(16_000, 100, 8, { source })

        capture.start()
        awaitStopped(capture)

        val events = generateSequence { capture.events.tryReceive().getOrNull() }.toList()
        assertTrue(events.any { it is AudioEvent.Error && (it as AudioEvent.Error).message.contains("mic unavailable") })
        assertTrue("source must be closed even when open() returns an error", source.closed.get())
        // Channel must be terminally closed so a consumer on receive() can't hang.
        assertTrue("event channel must be closed after open failure", capture.events.tryReceive().isClosed)

        capture.stop()
        assertFalse(capture.isRunning)
    }

    @Test fun openThrowingDoesNotHangSurfacesErrorAndReleasesTheSource() {
        // A throwing open() previously killed the capture thread silently: no error reached the
        // consumer and events.receive() could hang forever. Now it must surface an error, close the
        // source, and terminally close the channel.
        val source = FailingOpenSource(FailingOpenSource.Mode.THROWS)
        val capture = AudioCapture(16_000, 100, 8, { source })

        capture.start()
        awaitStopped(capture)

        val events = generateSequence { capture.events.tryReceive().getOrNull() }.toList()
        assertTrue(events.any { it is AudioEvent.Error && (it as AudioEvent.Error).message.contains("boom", ignoreCase = true) })
        assertTrue("source must be closed even when open() throws", source.closed.get())
        assertTrue("event channel must be closed so a receive() never hangs", capture.events.tryReceive().isClosed)

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
