package uk.co.riera.talkbalance.enroll

import uk.co.riera.talkbalance.audio.SpeechChunk
import kotlin.math.sqrt

/**
 * Pure, Android-free, sherpa-free maths behind [EnrollmentProcessor]: the net-speech quality gate,
 * the thirds-splitting, and the similar-voice cosine check. Kept out of the processor itself so the
 * decision logic is trivially unit-testable on the plain JVM with synthetic inputs (no native
 * extractor / no coroutine needed).
 *
 * The cosine/centroid helpers are a small, deliberately self-contained copy — the enroll package
 * must not assume T7's `ml/` cosine helper exists (separate branch). A later refactor can dedupe
 * against `bench.cosineSimilarity` / any `ml/` helper into one shared function.
 */

/** Quality-gate thresholds. Net speech = total speech duration after VAD silence-trimming. */
const val MIN_NET_SPEECH_MS: Long = 5_000L

/** Below this, the recording is treated as essentially silent (TooQuiet rather than TooShort). */
const val QUIET_FLOOR_MS: Long = 1_000L

/** Total speech samples across all trimmed chunks. */
fun netSpeechSamples(chunks: List<SpeechChunk>): Int = chunks.sumOf { it.samples.size }

/** Net (silence-removed) speech duration in ms, from the trimmed chunks' sample counts. */
fun netSpeechMs(chunks: List<SpeechChunk>, sampleRate: Int): Long =
    netSpeechSamples(chunks).toLong() * 1000L / sampleRate

/**
 * The quality gate as a pure function: returns the typed [EnrollmentResult.Failure] to report, or
 * `null` when there is enough speech to enroll.
 *
 * - `< `[QUIET_FLOOR_MS] → [EnrollmentResult.Failure.TooQuiet] (VAD found almost no speech).
 * - `< `[MIN_NET_SPEECH_MS] → [EnrollmentResult.Failure.TooShort] (some speech, not enough).
 * - otherwise → `null` (pass).
 */
fun gateFailure(netSpeechMs: Long): EnrollmentResult.Failure? = when {
    netSpeechMs < QUIET_FLOOR_MS -> EnrollmentResult.Failure.TooQuiet(
        "We couldn't hear enough speech. Please move somewhere quieter and speak clearly.",
    )

    netSpeechMs < MIN_NET_SPEECH_MS -> EnrollmentResult.Failure.TooShort(
        message = "Keep talking a little longer — we need about " +
            "${MIN_NET_SPEECH_MS / 1000} seconds of speech (got " +
            "${"%.1f".format(netSpeechMs / 1000.0)} s).",
        netSpeechMs = netSpeechMs,
    )

    else -> null
}

/**
 * The three boundary indices that split a run of [total] samples into equal thirds, returned as the
 * four cut points `[0, total/3, 2*total/3, total]`. Integer division sends the remainder to the
 * last third, so the union always covers exactly `0 until total` with no gap or overlap.
 */
fun thirdBoundaries(total: Int): IntArray = intArrayOf(0, total / 3, 2 * total / 3, total)

/** Splits [samples] into three contiguous sub-arrays at [thirdBoundaries]. */
fun splitIntoThirds(samples: FloatArray): List<FloatArray> {
    val b = thirdBoundaries(samples.size)
    return listOf(
        samples.copyOfRange(b[0], b[1]),
        samples.copyOfRange(b[1], b[2]),
        samples.copyOfRange(b[2], b[3]),
    )
}

/** Concatenates the trimmed speech chunks into one contiguous speech-only buffer. */
fun concatSpeech(chunks: List<SpeechChunk>): FloatArray {
    val out = FloatArray(netSpeechSamples(chunks))
    var pos = 0
    for (chunk in chunks) {
        System.arraycopy(chunk.samples, 0, out, pos, chunk.samples.size)
        pos += chunk.samples.size
    }
    return out
}

/** Cosine similarity of two equal-length vectors; 0 when either has zero magnitude. */
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

/** Element-wise mean of a non-empty set of equal-length vectors. */
fun centroid(embeddings: Array<FloatArray>): FloatArray {
    require(embeddings.isNotEmpty()) { "cannot take the centroid of zero embeddings" }
    val dim = embeddings[0].size
    val out = FloatArray(dim)
    for (embedding in embeddings) {
        require(embedding.size == dim) { "embedding size mismatch: ${embedding.size} vs $dim" }
        for (i in 0 until dim) out[i] += embedding[i]
    }
    for (i in 0 until dim) out[i] /= embeddings.size
    return out
}

/**
 * Pure "does this new voice look like an existing one?" check. Compares the centroid of the new
 * [newEmbeddings] against each existing profile's centroid by cosine similarity, and returns the
 * single most-similar profile whose similarity strictly exceeds [threshold] — or `null` if none do,
 * if either side is empty, or if a profile was enrolled with a different-dimension model (skipped
 * rather than crashing). This never blocks enrollment; the caller shows it as a warning only.
 */
object SimilarVoiceCheck {
    /** Warn when centroid cosine similarity to an existing profile exceeds this. */
    const val DEFAULT_THRESHOLD: Float = 0.6f

    fun check(
        newEmbeddings: Array<FloatArray>,
        existing: List<EnrolledProfile>,
        threshold: Float = DEFAULT_THRESHOLD,
    ): SimilarVoiceWarning? {
        if (newEmbeddings.isEmpty() || existing.isEmpty()) return null
        val newCentroid = centroid(newEmbeddings)

        var best: SimilarVoiceWarning? = null
        for (profile in existing) {
            if (profile.embeddings.isEmpty()) continue
            val existingCentroid = centroid(profile.embeddings)
            if (existingCentroid.size != newCentroid.size) continue // different model/dim — skip
            val similarity = cosineSimilarity(newCentroid, existingCentroid)
            if (similarity > threshold && (best == null || similarity > best.similarity)) {
                best = SimilarVoiceWarning(profile.name, similarity)
            }
        }
        return best
    }
}
