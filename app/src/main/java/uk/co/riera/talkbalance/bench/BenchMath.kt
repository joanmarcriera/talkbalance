package uk.co.riera.talkbalance.bench

import kotlin.math.sqrt

/**
 * Pure, Android-free helpers for the T3 benchmark harness so they can be unit-tested on the JVM.
 *
 * The bench deliberately does NOT reuse [uk.co.riera.talkbalance.ml] matcher classes: raw cosine on
 * the extractor's FloatArray output is all the measurement needs, and keeping it here makes the maths
 * trivially testable.
 */

/** Two embedding models compared in the T3 A/B (files already shipped in assets). */
enum class BenchModel(val label: String, val file: String) {
    CAMPP("CAM++", "wespeaker_en_voxceleb_CAM++_LM.onnx"),
    ERES2NET("ERes2Net", "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"),
}

/** Cosine similarity of two equal-length embeddings; 0 when either vector is zero-length. */
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
 * One measurement line in the exact evidence CSV shape logged under tag `BenchT3`:
 * `ts,segMs,inferMs,simA,simB,simC`. A missing reference (not yet captured) is an empty field.
 */
fun formatBenchCsv(ts: Long, segMs: Long, inferMs: Long, sims: List<Float?>): String {
    val simFields = sims.joinToString(",") { value -> value?.let { "%.3f".format(it) } ?: "" }
    return "$ts,$segMs,$inferMs,$simFields"
}

/** The single init evidence line: `init model=<file> dim=<n> initMs=<measured>`. */
fun formatBenchInit(model: String, dim: Int, initMs: Long): String =
    "init model=$model dim=$dim initMs=$initMs"
