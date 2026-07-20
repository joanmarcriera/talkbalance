package uk.co.riera.talkbalance.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking

/**
 * Production 16 kHz mono PCM16 mic capture: a dedicated [Thread.MAX_PRIORITY] /
 * `THREAD_PRIORITY_URGENT_AUDIO` thread does blocking [AudioRecord.read] in 100 ms (1600-sample)
 * windows, converts each window to `[-1, 1]` floats, and pushes it onto a bounded [Channel] that
 * [SpeechSegmenter] (or a test) drains.
 *
 * Caller contract:
 * - Gate on the `RECORD_AUDIO` permission before calling [start].
 * - [start]/[stop] may be called repeatedly (e.g. pause/resume around a phone call), but [stop]
 *   always blocks until the capture thread has actually exited and released its `AudioRecord`
 *   before returning — this is what prevents the two-AudioRecord leak that
 *   `bench.BenchViewModel.stop()` has (it nulls the thread reference without joining, so a
 *   quick stop-then-start can race a still-exiting old thread and end up with two live
 *   `AudioRecord`s, only one of which is ever released).
 * - Call [release] exactly once when the capture is no longer needed; it stops the thread and
 *   closes the channel.
 * - Read errors (e.g. the mic get stolen by a phone call, `AudioRecord.read` returning a negative
 *   error code) never throw out of the capture thread — they are sent as [AudioEvent.Error] and
 *   end the capture loop cleanly.
 */
class AudioCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val chunkSamples: Int = CHUNK_SAMPLES,
    channelCapacity: Int = 64,
) {
    private val channel = Channel<AudioEvent>(capacity = channelCapacity)

    /** Downstream consumer end; only [AudioCapture] ever sends on it. */
    val events: ReceiveChannel<AudioEvent> get() = channel

    @Volatile private var running = false
    private var thread: Thread? = null

    /** True between a successful [start] and the matching [stop] returning. */
    val isRunning: Boolean get() = running

    /**
     * Starts the capture thread. Throws [IllegalStateException] if a previous capture is still
     * active (call [stop] first) — this is deliberate: silently starting a second `AudioRecord`
     * while one is already running is exactly the leak this class exists to prevent.
     */
    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before start()
    fun start() {
        check(thread == null) { "AudioCapture.start() called while a capture thread is still active" }
        running = true
        thread = Thread({ runLoop() }, THREAD_NAME).also { it.start() }
    }

    /**
     * Signals the capture thread to stop and blocks until it has actually exited (its
     * `AudioRecord` is guaranteed released by the time this returns). Safe to call when not
     * running. Throws [IllegalStateException] if the thread fails to exit within
     * [JOIN_TIMEOUT_MS] — that indicates a stuck blocking read and is surfaced loudly rather than
     * silently leaving a second `AudioRecord` for a subsequent [start] to collide with.
     */
    fun stop() {
        val worker = thread
        running = false
        if (worker == null) return
        worker.join(JOIN_TIMEOUT_MS)
        check(!worker.isAlive) {
            "AudioCapture worker did not stop within ${JOIN_TIMEOUT_MS}ms; refusing to clear " +
                "state to avoid a duplicate AudioRecord on the next start()"
        }
        thread = null
    }

    /** Stops (if running) and closes the output channel. Call once the capture is done for good. */
    fun release() {
        stop()
        channel.close()
    }

    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before start()
    @OptIn(DelicateCoroutinesApi::class)
    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, ENCODING)
        if (minBuffer <= 0) {
            sendError("AudioRecord.getMinBufferSize returned $minBuffer; device cannot do 16kHz mono PCM16")
            running = false
            return
        }
        val bufferBytes = maxOf(minBuffer, chunkSamples * BYTES_PER_SAMPLE * BUFFER_CHUNK_MULTIPLE)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, CHANNEL_CONFIG, ENCODING, bufferBytes)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                sendError("AudioRecord failed to initialize (state=${record.state})")
                return
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                sendError("AudioRecord did not enter RECORDING state after startRecording()")
                return
            }

            val shorts = ShortArray(chunkSamples)
            while (running) {
                val n = record.read(shorts, 0, chunkSamples)
                if (n < 0) {
                    sendError(readErrorMessage(n))
                    break
                }
                if (n == 0) continue
                val samples = FloatArray(n) { shorts[it] / 32768f }
                channel.trySendBlocking(AudioEvent.Samples(samples))
            }
        } catch (error: Throwable) {
            sendError(error.message ?: error.javaClass.simpleName, error)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            running = false
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun sendError(message: String, cause: Throwable? = null) {
        channel.trySendBlocking(AudioEvent.Error(message, cause))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_CHUNK_MULTIPLE = 4
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val THREAD_NAME = "audio-capture"

        /** Pure mapping from an `AudioRecord.read` negative result code to a human message. */
        internal fun readErrorMessage(code: Int): String = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord.read: ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "AudioRecord.read: ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT ->
                "AudioRecord.read: ERROR_DEAD_OBJECT (microphone likely taken by another app or a call)"
            AudioRecord.ERROR -> "AudioRecord.read: ERROR"
            else -> "AudioRecord.read failed with code $code"
        }
    }
}
