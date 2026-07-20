package uk.co.riera.talkbalance.audio

import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping tests for [AudioCapture.readErrorMessage] -- the piece of the mic-error-surfacing
 * contract ("errors must reach the channel as data, not a crash") that doesn't need a real
 * AudioRecord/thread to exercise. `AudioRecord.ERROR_*` are compile-time `int` constants, so
 * referencing them here does not touch the unmocked Android runtime.
 */
class AudioCaptureTest {

    @Test fun deadObjectMapsToMicStolenMessage() {
        val message = AudioCapture.readErrorMessage(AudioRecord.ERROR_DEAD_OBJECT)
        assertTrue(message.contains("ERROR_DEAD_OBJECT"))
        assertTrue(message.contains("call", ignoreCase = true))
    }

    @Test fun invalidOperationIsMapped() {
        assertEquals(
            "AudioRecord.read: ERROR_INVALID_OPERATION",
            AudioCapture.readErrorMessage(AudioRecord.ERROR_INVALID_OPERATION),
        )
    }

    @Test fun badValueIsMapped() {
        assertEquals(
            "AudioRecord.read: ERROR_BAD_VALUE",
            AudioCapture.readErrorMessage(AudioRecord.ERROR_BAD_VALUE),
        )
    }

    @Test fun genericErrorIsMapped() {
        assertEquals("AudioRecord.read: ERROR", AudioCapture.readErrorMessage(AudioRecord.ERROR))
    }

    @Test fun unknownNegativeCodeFallsBackToRawCode() {
        val message = AudioCapture.readErrorMessage(-999)
        assertTrue(message.contains("-999"))
    }
}
