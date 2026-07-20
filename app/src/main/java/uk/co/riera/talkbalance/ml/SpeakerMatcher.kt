package uk.co.riera.talkbalance.ml

import uk.co.riera.talkbalance.audio.SpeechChunk

/**
 * Attribution seam the [uk.co.riera.talkbalance.session.SessionEngine] depends on.
 *
 * **This is only the interface + result type — T8 does NOT implement it.** T7 provides the real
 * implementation as a façade over sherpa-onnx's `SpeakerEmbeddingManager`: extract an embedding
 * from the chunk's samples, then `search(emb, matchThreshold)` (Table Mode, multi-speaker) or
 * `verify(name, emb, matchThreshold)` (Me Mode, me/not-me). The threshold is read from
 * [uk.co.riera.talkbalance.settings.SettingsRepository.matchThreshold] (default 0.50; the bench
 * gap of same≈0.90 vs different≈0.15 makes that safe — see `bench-data/ANALYSIS-2026-07-20.md`).
 *
 * The mode (Table vs Me) is a property of the matcher *implementation*, not of the engine: the
 * matcher already knows whether it is doing multi-speaker `search` or me-only `verify`. The engine
 * only needs the [MatchResult] and its own [uk.co.riera.talkbalance.session.Mode] for labelling the
 * "everyone else" bucket. Implementations are single-threaded (sherpa JNI is not thread-safe) and
 * must be called from the same single consumer as the segmenter.
 */
interface SpeakerMatcher {
    /**
     * Attribute one finished [SpeechChunk] to an enrolled speaker.
     *
     * @return a [MatchResult] whose [MatchResult.speakerId] is the enrolled speaker's stable id
     * when the best match is at/above the configured threshold, or `null` when the best match is
     * below threshold / there is no enrolled match. The engine buckets `null` results as
     * "everyone else" — subject to the sub-0.8 s continuation heuristic, which may still credit the
     * previous speaker.
     */
    fun match(chunk: SpeechChunk): MatchResult
}

/**
 * Outcome of a single [SpeakerMatcher.match] call.
 *
 * @param speakerId enrolled speaker's stable id when at/above threshold, else `null`.
 * @param similarity best cosine similarity in `[0, 1]` (diagnostics / future tuning; the engine's
 * attribution logic keys off [speakerId] being non-null, not this value).
 */
data class MatchResult(
    val speakerId: String?,
    val similarity: Float,
)
