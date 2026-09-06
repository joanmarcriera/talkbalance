package uk.co.riera.talkbalance.bench

import android.annotation.SuppressLint
import android.app.Application
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * T3 benchmark harness (throwaway scaffolding). Captures mic audio, runs the app's sherpa VAD, and
 * for every emitted speech segment computes a speaker embedding, measuring segment duration and
 * inference latency and scoring it against up to three on-screen reference embeddings.
 *
 * sherpa objects are not thread-safe, so all VAD/extractor access happens on a single [worker]
 * thread; the UI communicates via @Volatile command flags that the loop drains.
 */
class BenchViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val listening: Boolean = false,
        val model: BenchModel = BenchModel.CAMPP,
        val initLine: String? = null,
        val refsCaptured: List<Boolean> = listOf(false, false, false),
        val capturingRef: Int = -1,
        val lines: List<String> = emptyList(),
        val error: String? = null,
    )

    private val assets: AssetManager = application.assets

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    @Volatile private var running = false
    @Volatile private var pendingModel: BenchModel? = null
    @Volatile private var pendingRef: Int = -1
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        _state.update { it.copy(listening = true, error = null) }
        worker = Thread({ loop() }, "bench-t3").also { it.start() }
    }

    fun stop() {
        running = false
        worker = null
        _state.update { it.copy(listening = false, capturingRef = -1) }
    }

    /** Toggle the embedding model; the loop re-creates the extractor and clears now-stale refs. */
    fun setModel(model: BenchModel) {
        if (model == _state.value.model) return
        _state.update { it.copy(model = model) }
        if (running) pendingModel = model
    }

    /** Request an ~8 s reference capture into slot 0/1/2 (A/B/C). */
    fun captureRef(slot: Int) {
        if (running) pendingRef = slot
    }

    fun clearLines() = _state.update { it.copy(lines = emptyList()) }

    override fun onCleared() {
        stop()
    }

    // --- worker thread ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller gates on RECORD_AUDIO before start()
    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recordBuffer = maxOf(minBuffer, WINDOW * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            recordBuffer,
        )
        var vad: Vad? = null
        var extractor: SpeakerEmbeddingExtractor? = null
        // Reference embeddings + the 8 s raw-sample collector, only touched on this thread.
        val refs = arrayOfNulls<FloatArray>(3)
        var refSlot = -1
        var refBuffer: FloatArray? = null
        var refFilled = 0

        try {
            vad = buildVad()
            extractor = buildExtractor(_state.value.model)
            record.startRecording()

            val shorts = ShortArray(WINDOW)
            while (running) {
                // Model toggle: re-create the extractor (keep VAD), invalidate refs (dim/space differ).
                pendingModel?.let { model ->
                    pendingModel = null
                    extractor?.release()
                    extractor = buildExtractor(model)
                    refs.fill(null)
                    refSlot = -1
                    refBuffer = null
                    _state.update { it.copy(refsCaptured = listOf(false, false, false), capturingRef = -1) }
                }
                // Start of a reference capture.
                if (pendingRef >= 0 && refSlot < 0) {
                    refSlot = pendingRef
                    pendingRef = -1
                    refBuffer = FloatArray(REF_SAMPLES)
                    refFilled = 0
                    _state.update { it.copy(capturingRef = refSlot) }
                }

                val n = record.read(shorts, 0, WINDOW)
                if (n <= 0) continue
                val samples = FloatArray(n) { shorts[it] / 32768f }

                // Accumulate the raw reference window if a capture is in progress.
                if (refSlot >= 0) {
                    val buffer = refBuffer!!
                    val take = minOf(n, buffer.size - refFilled)
                    System.arraycopy(samples, 0, buffer, refFilled, take)
                    refFilled += take
                    if (refFilled >= buffer.size) {
                        val ex = extractor!!
                        refs[refSlot] = embed(ex, buffer)
                        val captured = _state.value.refsCaptured.toMutableList().also { it[refSlot] = true }
                        _state.update { it.copy(refsCaptured = captured, capturingRef = -1) }
                        refSlot = -1
                        refBuffer = null
                    }
                }

                if (n < WINDOW) continue
                vad!!.acceptWaveform(samples)
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val segSamples = segment.samples
                    val segMs = segSamples.size * 1000L / SAMPLE_RATE
                    val startNs = System.nanoTime()
                    val emb = embed(extractor!!, segSamples)
                    val inferMs = (System.nanoTime() - startNs) / 1_000_000
                    val sims = refs.map { ref -> ref?.let { cosineSimilarity(emb, it) } }
                    emit(formatBenchCsv(System.currentTimeMillis(), segMs, inferMs, sims))
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "bench loop failed", error)
            _state.update { it.copy(error = error.message ?: error.javaClass.simpleName, listening = false) }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            extractor?.release()
            vad?.release()
        }
    }

    private fun buildVad(): Vad = Vad(
        assetManager = assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = VAD_MODEL,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.3f,
                windowSize = WINDOW,
                maxSpeechDuration = 3.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = THREADS,
            provider = "cpu",
            debug = false,
        ),
    )

    /** Build an extractor for [model], measuring real init time and logging the T3 init evidence line. */
    private fun buildExtractor(model: BenchModel): SpeakerEmbeddingExtractor {
        val startNs = System.nanoTime()
        val extractor = SpeakerEmbeddingExtractor(
            assetManager = assets,
            config = SpeakerEmbeddingExtractorConfig(
                model = model.file,
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
        )
        val initMs = (System.nanoTime() - startNs) / 1_000_000
        val line = formatBenchInit(model.file, extractor.dim(), initMs)
        Log.i(TAG, line)
        appendToBenchFile(line)
        _state.update { it.copy(initLine = line) }
        return extractor
    }

    private fun embed(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        stream.inputFinished()
        val embedding = extractor.compute(stream)
        stream.release()
        return embedding
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        appendToBenchFile(line)
        _state.update { current ->
            val next = (current.lines + line).takeLast(MAX_LINES)
            current.copy(lines = next)
        }
    }

    /**
     * Measurements must survive process death and logcat rolling (a ~5 min session already
     * outlives this device's main log buffer). Pull with:
     * adb exec-out run-as uk.co.riera.talkbalance cat files/bench-t3.csv
     */
    private fun appendToBenchFile(line: String) {
        runCatching {
            java.io.File(getApplication<Application>().filesDir, BENCH_FILE)
                .appendText(line + "\n")
        }.onFailure { Log.e(TAG, "bench file write failed", it) }
    }

    companion object {
        const val TAG = "BenchT3"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val WINDOW = 512
        private const val THREADS = 2
        private const val REF_SAMPLES = SAMPLE_RATE * 8 // ~8 s reference capture
        private const val MAX_LINES = 200
        private const val VAD_MODEL = "silero_vad.onnx"
        private const val BENCH_FILE = "bench-t3.csv"
    }
}
