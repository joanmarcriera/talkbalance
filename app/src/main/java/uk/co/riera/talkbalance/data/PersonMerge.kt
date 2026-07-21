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
