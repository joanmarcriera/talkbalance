package uk.co.riera.talkbalance.data

/**
 * Pure logic behind combining two people's stored voice embeddings — shared by
 * [PersonDao.mergePersons] (merging a duplicate person into another) and the enrollment flow's
 * "add this recording to an existing person instead" action (folding a fresh recording straight
 * into an already-enrolled profile rather than creating a new person). Kept dependency-free of
 * Room/Android so it's plain-JUnit testable.
 */

/** Cap on how many per-person voice embeddings we keep — matching storage/match cost is bounded. */
const val MAX_PERSON_EMBEDDINGS = 8

/**
 * Concatenates [target] then [source] and truncates to [cap]. [target] is the survivor identity
 * (the person being kept — an existing enrolled person for a merge, or the matched person for
 * "add to existing"), so its own embeddings are kept in full whenever they fit; [source]'s
 * embeddings only fill whatever capacity remains. Order and per-embedding dimensionality are
 * otherwise preserved verbatim — no re-averaging or resampling.
 */
fun combineEmbeddings(
    target: Array<FloatArray>,
    source: Array<FloatArray>,
    cap: Int = MAX_PERSON_EMBEDDINGS,
): Array<FloatArray> {
    val combined = target + source
    return if (combined.size <= cap) combined else combined.copyOfRange(0, cap)
}

/**
 * The three aggregatable per-session speaker metrics, extracted so the "two rows for the same person
 * in one session" consolidation done during [PersonDao.mergePersons] is pure and plain-JUnit
 * testable (no Room row types).
 */
data class SpeakerStatValues(val talkMs: Long, val turns: Int, val longestMonologueMs: Long)

/**
 * Combine two `speaker_stats` rows that, after a person merge, both belong to the surviving person in
 * the SAME session. Talk time and turns add up; the longest monologue is the max of the two (it's a
 * single continuous stretch, not something you can sum). Used to collapse the two rows into one so a
 * session never shows the merged person twice.
 */
fun combineSpeakerStats(target: SpeakerStatValues, source: SpeakerStatValues) = SpeakerStatValues(
    talkMs = target.talkMs + source.talkMs,
    turns = target.turns + source.turns,
    longestMonologueMs = maxOf(target.longestMonologueMs, source.longestMonologueMs),
)
