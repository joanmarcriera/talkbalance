package uk.co.riera.talkbalance.audio

/**
 * What [AudioCapture] pushes onto its output channel. Kept sealed (rather than throwing) because
 * the capture thread is not the consumer's thread: a mic failure (another app/call stealing the
 * microphone, a misbehaving driver, `AudioRecord` refusing to initialize) must reach whoever is
 * downstream as data, never as an uncaught exception on a background thread.
 */
sealed interface AudioEvent {
    /** One capture window, already converted to normalised `[-1, 1]` float PCM. */
    data class Samples(val samples: FloatArray) : AudioEvent

    /** Capture stopped (or never started) because of a runtime failure. Non-fatal to the app. */
    data class Error(val message: String, val cause: Throwable? = null) : AudioEvent
}
