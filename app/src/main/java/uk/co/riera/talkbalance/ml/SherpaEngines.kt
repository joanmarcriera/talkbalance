package uk.co.riera.talkbalance.ml

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.time.measureTimedValue

data class SherpaEngineSnapshot(
    val embeddingDimension: Int,
    val embeddingModel: String,
    val initializationMs: Long,
)

class SherpaEngines(
    private val assetManager: AssetManager,
) {
    @Volatile
    private var snapshot: SherpaEngineSnapshot? = null

    private var vad: Vad? = null
    private var extractor: SpeakerEmbeddingExtractor? = null

    @Synchronized
    fun initialize(): Result<SherpaEngineSnapshot> = runCatching {
        snapshot?.let { return@runCatching it }

        val timed = measureTimedValue {
            val newVad = Vad(
                assetManager = assetManager,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VAD_MODEL,
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.3f,
                        windowSize = 512,
                        maxSpeechDuration = 3.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = THREAD_COUNT,
                    provider = "cpu",
                    debug = false,
                ),
            )
            val newExtractor = try {
                SpeakerEmbeddingExtractor(
                    assetManager = assetManager,
                    config = SpeakerEmbeddingExtractorConfig(
                        model = PRIMARY_EMBEDDING_MODEL,
                        numThreads = THREAD_COUNT,
                        debug = false,
                        provider = "cpu",
                    ),
                )
            } catch (error: Throwable) {
                newVad.release()
                throw error
            }

            vad = newVad
            extractor = newExtractor
            newExtractor.dim()
        }

        SherpaEngineSnapshot(
            embeddingDimension = timed.value,
            embeddingModel = PRIMARY_EMBEDDING_MODEL,
            initializationMs = timed.duration.inWholeMilliseconds,
        ).also { snapshot = it }
    }

    @Synchronized
    fun close() {
        extractor?.release()
        extractor = null
        vad?.release()
        vad = null
        snapshot = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val VAD_MODEL = "silero_vad.onnx"
        const val PRIMARY_EMBEDDING_MODEL = "wespeaker_en_voxceleb_CAM++_LM.onnx"
        const val BENCHMARK_EMBEDDING_MODEL =
            "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
        private const val THREAD_COUNT = 2
    }
}
