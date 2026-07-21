package uk.co.riera.talkbalance.ml

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import uk.co.riera.talkbalance.audio.SpeechChunk

/** One enrolled person's stable id (see [SherpaSpeakerMatcher] doc for what the id means per mode). */
data class EnrolledPerson(val id: String, val embeddings: Array<FloatArray>)

/**
 * How [SherpaSpeakerMatcher] attributes a chunk. A property of the matcher instance, not of
 * [uk.co.riera.talkbalance.session.SessionEngine]'s own `Mode` (which only decides the "everyone
 * else" bucket's *label*) — see `ml/SpeakerMatcher.kt`'s class doc.
 */
enum class MatcherMode {
    /** Table Mode: search across every enrolled person. */
    TABLE,

    /** Me Mode: verify against the single enrolled person under [SherpaSpeakerMatcher.ME_ID]. */
    ME,
}

/**
 * Real [SpeakerMatcher] implementation (T7): a façade over sherpa-onnx's native
 * `SpeakerEmbeddingManager`. Computes a chunk's embedding via the shared [extractor] (owned by
 * `SherpaEngines`; not released by this class), then either `search`s across every enrolled
 * person (Table Mode) or `verify`s against the single "me" entry (Me Mode).
 *
 * [MatchResult.similarity] is *not* read off the manager (native `search`/`verify` only return a
 * name / boolean) — it is computed separately here as the best cosine similarity against each
 * enrolled person's centroid ([bestCentroidSimilarity] over [centroid]s of their embeddings), so
 * the engine always gets a real diagnostic number regardless of which mode decided the match.
 *
 * **Thread-safety**: sherpa JNI objects ([extractor], the internal `SpeakerEmbeddingManager`, and
 * each per-call `OnlineStream`) are single-threaded / single-instance, like the rest of the
 * sherpa-onnx surface (see `SpeechSegmenter`'s class doc). This class must only be driven by the
 * engine's single consumer coroutine — [match] is called serially from that one dispatcher, never
 * concurrently, so no internal locking is needed here.
 */
class SherpaSpeakerMatcher(
    private val extractor: SpeakerEmbeddingExtractor,
    enrolled: List<EnrolledPerson>,
    private val threshold: Float,
    private val mode: MatcherMode,
    private val sampleRate: Int = SAMPLE_RATE,
) : SpeakerMatcher {

    private val manager = SpeakerEmbeddingManager(extractor.dim())
    private val centroids: Map<String, FloatArray>

    init {
        val centroidById = mutableMapOf<String, FloatArray>()
        for (person in enrolled) {
            manager.add(person.id, person.embeddings)
            centroidById[person.id] = centroid(person.embeddings)
        }
        centroids = centroidById

        require(mode != MatcherMode.ME || centroids.containsKey(ME_ID)) {
            "MatcherMode.ME requires an enrolled person with id \"$ME_ID\" (got ${centroids.keys})"
        }
        diagLine("=== session start === mode=$mode dim=${extractor.dim()} threshold=$threshold enrolled=${enrolled.map { it.id + ":" + it.embeddings.size }}")
    }

    // TEMP diagnostic (diag-matcher branch only): persist to a file so timing/logcat-rolling can't lose it.
    private fun diagLine(msg: String) {
        android.util.Log.i("MatcherDiag", msg)
        runCatching {
            diagDir?.let { java.io.File(it, "matcher-diag.txt").appendText(msg + "\n") }
        }
    }

    override fun match(chunk: SpeechChunk): MatchResult {
        val embedding = extractEmbedding(chunk.samples)
        val similarity = bestCentroidSimilarity(embedding, centroids.values)

        val speakerId = when (mode) {
            MatcherMode.TABLE -> manager.search(embedding, threshold).ifEmpty { null }
            MatcherMode.ME -> if (manager.verify(ME_ID, embedding, threshold)) ME_ID else null
        }

        val bestId = if (mode == MatcherMode.TABLE) manager.search(embedding, 0.0f) else "-"
        diagLine("segMs=${chunk.endMs - chunk.startMs} bestSim=${"%.3f".format(similarity)} threshold=$threshold sherpaBest=$bestId -> speakerId=$speakerId")
        return MatchResult(speakerId, similarity)
    }

    /** Releases the native `SpeakerEmbeddingManager`. Does NOT release [extractor] — the caller owns it. */
    fun close() {
        manager.release()
    }

    private fun extractEmbedding(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** Reserved enrollment id for the single person tracked in [MatcherMode.ME]. */
        const val ME_ID = "me"

        /** TEMP diagnostic (diag-matcher branch): set to the app filesDir so [diagLine] can persist. */
        @JvmStatic
        var diagDir: java.io.File? = null
    }
}
