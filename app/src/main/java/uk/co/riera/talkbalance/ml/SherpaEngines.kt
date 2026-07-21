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

/**
 * Thrown when a caller asks for the shared native engines while another owner (a live session or an
 * in-flight enrollment) already holds the [SherpaEngines.EngineLease]. The sherpa-onnx `Vad` /
 * `SpeakerEmbeddingExtractor` are single-instance, single-owner; overlapping `reset/accept/flush/
 * compute` from two owners is the JNI-contract violation the lease exists to prevent.
 */
class EngineBusyException(val holder: String) :
    IllegalStateException("Speech engine is busy (held by \"$holder\")")

class SherpaEngines(
    private val assetManager: AssetManager,
) {
    @Volatile
    private var snapshot: SherpaEngineSnapshot? = null

    private var vad: Vad? = null
    private var extractor: SpeakerEmbeddingExtractor? = null

    /** Single-owner mutual exclusion over the shared native engines. */
    private val leaseHolder = EngineLeaseHolder()

    /**
     * Non-blocking, exclusive acquire of the shared engines. Returns a lease, or null if another
     * owner already holds it. Session and enrollment must both go through this before touching the
     * shared [Vad]/[SpeakerEmbeddingExtractor].
     */
    fun tryAcquireLease(owner: String): EngineLeaseHolder.Lease? = leaseHolder.tryAcquire(owner)

    /** Name of the current lease owner, or null when free. For diagnostics / busy messages. */
    val leaseOwner: String? get() = leaseHolder.owner

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

    /**
     * The app's single initialised [Vad]. Throws if [initialize] has not succeeded yet.
     *
     * **Single-threaded contract:** this is the one shared instance the live session pipeline
     * ([uk.co.riera.talkbalance.session.SessionController]) and enrollment
     * ([uk.co.riera.talkbalance.enroll.EnrollmentProcessor]) both borrow. sherpa-onnx JNI objects
     * are not thread-safe, so a borrower must drive it from a single consumer only, and a live
     * session and an enrollment must never run at the same time (they are separate screens).
     */
    fun requireVad(): Vad = vad ?: error("SherpaEngines.initialize() has not completed successfully")

    /**
     * The app's single initialised [SpeakerEmbeddingExtractor]. Same single-threaded borrowing
     * contract as [requireVad] — see that doc.
     */
    fun requireExtractor(): SpeakerEmbeddingExtractor =
        extractor ?: error("SherpaEngines.initialize() has not completed successfully")

    /** True once [initialize] has built the native engines. */
    val isInitialized: Boolean get() = snapshot != null

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
        // T3 A/B on-device (2026-07-21, bench-data/T3-DECISION.md): ERes2Net scored 10/10
        // at threshold 0.50 through a real acoustic channel; CAM++ collapsed to 4/10 with
        // same-speaker similarity ~0.13. ERes2Net is the production model.
        const val PRIMARY_EMBEDDING_MODEL =
            "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
        const val BENCHMARK_EMBEDDING_MODEL = "wespeaker_en_voxceleb_CAM++_LM.onnx"
        private const val THREAD_COUNT = 2
    }
}
