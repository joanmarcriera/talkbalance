package uk.co.riera.talkbalance.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The microphone-frame producer [AudioCapture] drives, factored out so the capture/backpressure
 * loop is unit-testable on the plain JVM without a real `AudioRecord` (which needs a device). The
 * production implementation is [AudioRecordFrameSource]; tests supply a synthetic one.
 */
internal interface FrameSource {
    /** Opens the recorder. Returns `null` on success, or a human error message on failure. */
    fun open(): String?

    /**
     * Reads up to `out.size` samples into [out]; returns the count read (`>= 0`), or a negative
     * `AudioRecord.ERROR_*` code. May block until a frame is available.
     */
    fun read(out: ShortArray): Int

    /** Stops and releases the recorder. Safe to call more than once. */
    fun close()
}

/**
 * Non-blocking backpressure accounting for the capture thread: real-time audio must never block, so
 * a full downstream channel means we drop the frame rather than stall the mic. Pure (no Android /
 * coroutine types) so the count-and-alert policy is fully unit-testable.
 *
 * - Every dropped frame is counted ([droppedFrames]).
 * - After [alertThreshold] *consecutive* drops we ask (once) for a single [AudioEvent.Error] to be
 *   surfaced so the UI can tell the user the pipeline is overloaded; the alert re-arms only after a
 *   frame is delivered again.
 */
internal class OverflowPolicy(private val alertThreshold: Int = DEFAULT_ALERT_THRESHOLD) {
    var droppedFrames: Long = 0L
        private set

    private var consecutiveDrops = 0
    private var alerted = false

    /** Record a successful delivery: resets the consecutive-drop run and re-arms the alert. */
    fun onDelivered() {
        consecutiveDrops = 0
        alerted = false
    }

    /** Record a dropped frame. Returns true exactly once per overload burst, when the alert fires. */
    fun onDropped(): Boolean {
        droppedFrames++
        consecutiveDrops++
        if (consecutiveDrops >= alertThreshold && !alerted) {
            alerted = true
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_ALERT_THRESHOLD = 32
    }
}

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
 *
 * **Backpressure:** frame delivery is **non-blocking** ([kotlinx.coroutines.channels.Channel.trySend]).
 * If the consumer stalls and the channel fills, frames are dropped and counted rather than blocking
 * the real-time capture thread (a blocking send into a full channel with a dead consumer is exactly
 * the "Stop hangs forever / mic never released" wedge this class must avoid). After a burst of
 * consecutive drops a single [AudioEvent.Error] is surfaced. [stop] flips `running` and closes the
 * channel, so the thread is never left blocked on I/O and always joins.
 */
class AudioCapture internal constructor(
    private val sampleRate: Int,
    private val chunkSamples: Int,
    channelCapacity: Int,
    private val frameSourceFactory: () -> FrameSource,
    alertThreshold: Int = OverflowPolicy.DEFAULT_ALERT_THRESHOLD,
) {
    /** Production constructor: a real `AudioRecord`-backed [FrameSource]. */
    constructor(
        sampleRate: Int = SAMPLE_RATE,
        chunkSamples: Int = CHUNK_SAMPLES,
        channelCapacity: Int = 64,
    ) : this(
        sampleRate = sampleRate,
        chunkSamples = chunkSamples,
        channelCapacity = channelCapacity,
        frameSourceFactory = { AudioRecordFrameSource(sampleRate, chunkSamples) },
    )

    private val channel = Channel<AudioEvent>(capacity = channelCapacity)
    private val overflow = OverflowPolicy(alertThreshold)

    /** Downstream consumer end; only [AudioCapture] ever sends on it. */
    val events: ReceiveChannel<AudioEvent> get() = channel

    @Volatile private var running = false
    private var thread: Thread? = null

    /** True between a successful [start] and the matching [stop] returning. */
    val isRunning: Boolean get() = running

    /** Total frames dropped due to downstream backpressure over this capture's lifetime. */
    val droppedFrameCount: Long get() = overflow.droppedFrames

    /**
     * Starts the capture thread. Throws [IllegalStateException] if a previous capture is still
     * active (call [stop] first) — this is deliberate: silently starting a second `AudioRecord`
     * while one is already running is exactly the leak this class exists to prevent.
     */
    fun start() {
        check(thread == null) { "AudioCapture.start() called while a capture thread is still active" }
        running = true
        thread = Thread({ runLoop() }, THREAD_NAME).also { it.start() }
    }

    /**
     * Signals the capture thread to stop and blocks until it has actually exited (its
     * `AudioRecord` is guaranteed released by the time this returns). Safe to call when not
     * running. Because delivery is non-blocking, the thread can never be wedged inside a send, so
     * it always observes `running == false` on its next loop turn and exits well within
     * [JOIN_TIMEOUT_MS]. The channel is also closed here so any consumer blocked on `receive()`
     * unblocks. Throws [IllegalStateException] if the thread still fails to exit — that indicates a
     * genuinely stuck blocking read, surfaced loudly rather than silently leaving a second
     * `AudioRecord` for a subsequent [start] to collide with.
     */
    fun stop() {
        val worker = thread
        running = false
        // Closing the channel unblocks a consumer waiting on receive() and makes any late
        // trySend from the still-exiting thread a harmless no-op.
        channel.close()
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
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val source = frameSourceFactory()
        try {
            // open() is INSIDE the try/finally: whether it returns an error or throws, the finally
            // below still closes the source and terminally signals the channel, so a failed open can
            // neither leak the recorder nor leave a consumer hanging on events.receive().
            val openError = source.open()
            if (openError != null) {
                sendError(openError)
                return
            }
            val shorts = ShortArray(chunkSamples)
            while (running) {
                val n = source.read(shorts)
                if (n < 0) {
                    sendError(readErrorMessage(n))
                    break
                }
                if (n == 0) continue
                val samples = FloatArray(n) { shorts[it] / 32768f }
                offer(AudioEvent.Samples(samples))
            }
        } catch (error: Throwable) {
            sendError(error.message ?: error.javaClass.simpleName, error)
        } finally {
            runCatching { source.close() }
            // Terminally close the event channel so any consumer blocked on receive() unblocks
            // instead of waiting forever for frames the (now-dead) thread will never produce. This
            // is a no-op if stop() already closed it.
            channel.close()
            running = false
        }
    }

    /**
     * Non-blocking delivery. On a full channel the frame is dropped and counted; after a burst of
     * consecutive drops a single overload [AudioEvent.Error] is (best-effort) surfaced.
     */
    private fun offer(event: AudioEvent) {
        val result = channel.trySend(event)
        if (result.isSuccess) {
            overflow.onDelivered()
            return
        }
        if (overflow.onDropped()) {
            Log.w(
                TAG,
                "audio pipeline overloaded: dropped ${overflow.droppedFrames} frame(s); consumer not keeping up",
            )
            // Best-effort: if the channel is still full this is itself dropped, but by then the
            // consumer is effectively gone and stop() is the real recovery path.
            channel.trySend(
                AudioEvent.Error(
                    "Audio pipeline overloaded — some audio was dropped because the app couldn't keep up.",
                ),
            )
        }
    }

    private fun sendError(message: String, cause: Throwable? = null) {
        channel.trySend(AudioEvent.Error(message, cause))
    }

    /** Real `AudioRecord`-backed frame source. Not thread-safe; used only from the capture thread. */
    private class AudioRecordFrameSource(
        private val sampleRate: Int,
        private val chunkSamples: Int,
    ) : FrameSource {
        private var record: AudioRecord? = null

        @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before start()
        override fun open(): String? {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, ENCODING)
            if (minBuffer <= 0) {
                return "AudioRecord.getMinBufferSize returned $minBuffer; device cannot do 16kHz mono PCM16"
            }
            val bufferBytes = maxOf(minBuffer, chunkSamples * BYTES_PER_SAMPLE * BUFFER_CHUNK_MULTIPLE)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, CHANNEL_CONFIG, ENCODING, bufferBytes)
            } catch (error: Throwable) {
                return error.message ?: error.javaClass.simpleName
            }
            record = rec
            // Validate state + startRecording() under one guard so ANY failure path — a bad state or
            // a startRecording() that throws (e.g. mic policy denial) — releases the just-allocated
            // AudioRecord and returns a message, rather than leaking the recorder or throwing out of
            // the capture thread and stranding a consumer.
            val error: String? = try {
                when {
                    rec.state != AudioRecord.STATE_INITIALIZED ->
                        "AudioRecord failed to initialize (state=${rec.state})"
                    else -> {
                        rec.startRecording()
                        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            "AudioRecord did not enter RECORDING state after startRecording()"
                        } else {
                            null
                        }
                    }
                }
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (error != null) close()
            return error
        }

        override fun read(out: ShortArray): Int = record?.read(out, 0, out.size) ?: AudioRecord.ERROR_INVALID_OPERATION

        override fun close() {
            val rec = record ?: return
            record = null
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
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
