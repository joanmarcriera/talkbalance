package uk.co.riera.talkbalance.ml

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import uk.co.riera.talkbalance.audio.SpeechChunk

/**
 * "Start and discover" [SpeakerMatcher]: no enrollment. Instead of matching against a fixed set of
 * enrolled people, it grows a set of clusters live — one per distinct voice it hears — and labels
 * them with stable synthetic ids `"s1"`, `"s2"`, … in first-seen order. Every chunk is attributed
 * to a cluster (there is **no** "everyone else" bucket in discover mode), so
 * [uk.co.riera.talkbalance.session.SessionEngine] credits each returned id directly.
 *
 * Clustering (single-threaded, incremental):
 * - embed the chunk via the shared [extractor] (owned by `SherpaEngines`; not released here);
 * - compute the best cosine similarity against every existing cluster's running-mean centroid;
 * - **best >= [joinThreshold]** -> join that cluster: return its id and fold the new embedding into
 *   its centroid (incremental mean);
 * - **below threshold** and under the [maxSpeakers] cap -> spawn a new cluster with the next id;
 * - at the cap -> assign to the nearest existing cluster (and still update its centroid), so the id
 *   space never grows past [maxSpeakers].
 *
 * [joinThreshold] is a **live-to-live** decision (comparing two on-device utterance embeddings),
 * unlike the enrolled matchers which compare a live embedding to a multi-sample enrollment centroid.
 * Live-to-live similarities run lower and noisier, so the default is deliberately below the enrolled
 * [uk.co.riera.talkbalance.settings.SettingsRepository] threshold and is expected to be tuned on a
 * real device (see [JOIN_THRESHOLD]).
 *
 * **Thread-safety**: like every sherpa-onnx surface (see [SherpaSpeakerMatcher]), the [extractor] is
 * single-threaded. Drive [match] only from the engine's single consumer coroutine.
 *
 * The pure clustering decision ([assign]) is separated from embedding extraction so it can be
 * unit-tested with injected embeddings without a device — see `OnlineClusterMatcherTest`.
 */
class OnlineClusterMatcher(
    /** Turns a chunk's raw samples into an embedding vector. Injected so [assign] stays device-free. */
    private val embed: (FloatArray) -> FloatArray,
    private val joinThreshold: Float = JOIN_THRESHOLD,
    private val maxSpeakers: Int = MAX_SPEAKERS,
    private val minSpawnMs: Long = MIN_SPAWN_MS,
) : SpeakerMatcher {

    /** One discovered voice: its running-mean [centroid] over [count] folded-in embeddings. */
    private class Cluster(val id: String, var centroid: FloatArray, var count: Int)

    private val clusters = mutableListOf<Cluster>()

    /** Number of clusters discovered so far (test/diagnostic visibility). */
    val speakerCount: Int get() = clusters.size

    override fun match(chunk: SpeechChunk): MatchResult =
        assign(embed(chunk.samples), chunk.endMs - chunk.startMs)

    /**
     * Pure clustering decision for one already-computed [embedding]. Always returns a non-null
     * [MatchResult.speakerId] (discover mode attributes every voice). [MatchResult.similarity] is the
     * best cosine similarity against the chosen cluster before the update (0 for a brand-new cluster).
     *
     * [durationMs] is the chunk's speech length: a *new* speaker is only spawned from a chunk at least
     * [minSpawnMs] long, so short interjections / laughter fold into the nearest existing voice
     * instead of inflating the speaker count.
     */
    fun assign(embedding: FloatArray, durationMs: Long = Long.MAX_VALUE): MatchResult {
        // First voice ever: seed cluster s1.
        if (clusters.isEmpty()) return spawn(embedding).let { MatchResult(it, 0f) }

        var bestIdx = 0
        var bestSim = cosineSimilarity(embedding, clusters[0].centroid)
        for (i in 1 until clusters.size) {
            val sim = cosineSimilarity(embedding, clusters[i].centroid)
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }

        // Spawn a new voice only for a confidently-novel AND long-enough chunk; otherwise fold into
        // the nearest cluster (this also handles the maxSpeakers cap).
        val canSpawn = bestSim < joinThreshold && clusters.size < maxSpeakers && durationMs >= minSpawnMs
        return if (canSpawn) {
            MatchResult(spawn(embedding), 0f)
        } else {
            update(clusters[bestIdx], embedding)
            MatchResult(clusters[bestIdx].id, bestSim)
        }
    }

    /** Creates a new cluster seeded with [embedding] and returns its id. */
    private fun spawn(embedding: FloatArray): String {
        val id = "s${clusters.size + 1}"
        clusters.add(Cluster(id, embedding.copyOf(), count = 1))
        return id
    }

    /** Folds [embedding] into [cluster]'s centroid via the incremental-mean update. */
    private fun update(cluster: Cluster, embedding: FloatArray) {
        val n = cluster.count + 1
        val c = cluster.centroid
        for (i in c.indices) {
            c[i] += (embedding[i] - c[i]) / n
        }
        cluster.count = n
    }

    companion object {
        /**
         * Cosine-similarity threshold for two *live* utterance embeddings to be judged the same
         * speaker. Deliberately lower than the enrolled match threshold because live-to-live
         * similarity is noisier than live-vs-enrollment-centroid. Start ~0.5 and tune on device.
         */
        const val JOIN_THRESHOLD: Float = 0.32f

        /** Hard cap on discovered speakers; beyond this, voices fold into the nearest cluster. */
        const val MAX_SPEAKERS: Int = 10

        /**
         * Minimum speech length for a chunk to be allowed to spawn a *new* speaker. Short bursts
         * (one-word interjections, laughter, cross-talk fragments) fold into the nearest existing
         * voice instead — the main cause of speaker-count inflation on real, lively conversation.
         */
        const val MIN_SPAWN_MS: Long = 800L

        /**
         * Builds a device-backed matcher over the shared [extractor]. The embedding extraction here
         * mirrors [SherpaSpeakerMatcher]'s pattern (one throwaway `OnlineStream` per chunk).
         */
        fun create(
            extractor: SpeakerEmbeddingExtractor,
            joinThreshold: Float = JOIN_THRESHOLD,
            maxSpeakers: Int = MAX_SPEAKERS,
            minSpawnMs: Long = MIN_SPAWN_MS,
            sampleRate: Int = 16_000,
        ): OnlineClusterMatcher = OnlineClusterMatcher(
            embed = { samples ->
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    stream.inputFinished()
                    extractor.compute(stream)
                } finally {
                    stream.release()
                }
            },
            joinThreshold = joinThreshold,
            maxSpeakers = maxSpeakers,
            minSpawnMs = minSpawnMs,
        )
    }
}
