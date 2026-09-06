package uk.co.riera.talkbalance.ml

import kotlin.math.sqrt

/**
 * Pure, sherpa-free maths backing [SherpaSpeakerMatcher]'s diagnostic [MatchResult.similarity]
 * number. Kept free of any JNI/Android type (unlike `SpeakerEmbeddingManager`'s native `search`/
 * `verify`, which decide the actual match) so it is unit-testable on the plain JVM without a
 * device — see `SpeakerMatchingTest`.
 */

/** Cosine similarity of two equal-length embeddings, in `[-1, 1]`; 0 when either vector is zero-length. */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "embedding size mismatch: ${a.size} vs ${b.size}" }
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom == 0f) 0f else dot / denom
}

/**
 * Element-wise average of one or more equal-length embeddings — a person's enrollment centroid,
 * used only for the diagnostic similarity number returned alongside the manager's real match.
 */
fun centroid(embeddings: Array<FloatArray>): FloatArray {
    require(embeddings.isNotEmpty()) { "cannot average zero embeddings" }
    val dim = embeddings[0].size
    val sum = FloatArray(dim)
    for (embedding in embeddings) {
        require(embedding.size == dim) { "embedding size mismatch within centroid: ${embedding.size} vs $dim" }
        for (i in 0 until dim) sum[i] += embedding[i]
    }
    for (i in 0 until dim) sum[i] /= embeddings.size
    return sum
}

/**
 * Highest cosine similarity of [embedding] against a set of per-person [centroids]; `0f` when
 * [centroids] is empty (nobody enrolled).
 */
fun bestCentroidSimilarity(embedding: FloatArray, centroids: Collection<FloatArray>): Float =
    centroids.maxOfOrNull { cosineSimilarity(embedding, it) } ?: 0f
