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
 * Combines two people's voice embeddings into the survivor's profile, capped at [cap].
 *
 * [target] is the survivor identity (the person being kept — an existing enrolled person for a
 * merge, or the matched person for "add to existing"). Under the cap, this is a plain
 * target-then-source concatenation, and order and per-embedding dimensionality are preserved
 * verbatim — no re-averaging or resampling.
 *
 * **Over the cap, [source] is guaranteed a share.** At least `min(source.size, max(1, cap / 2))`
 * of [source]'s embeddings survive; [target]'s head fills the rest. This deliberately replaces the
 * earlier "target wins, source fills the remainder" rule, which silently discarded the entire
 * incoming recording whenever the target already held [cap] embeddings — so the enrollment flow's
 * "Add to <name> instead" reported success while the profile it claimed to improve was unchanged.
 * A user who explicitly folds a recording into someone is asking for that person's profile to
 * learn from it, and a merge that provably changes nothing is worse than the storage it saves.
 *
 * The one case where nothing of [source] is absorbed is an empty [source] — which, for a real
 * recording, only happens when the stored BLOB was corrupt and decoded to nothing (see
 * [PersonDao.mergePersons], which treats an unreadable BLOB as "no embeddings" so the merge's
 * primary job — consolidating stats and removing the duplicate — still completes).
 */
fun combineEmbeddings(
    target: Array<FloatArray>,
    source: Array<FloatArray>,
    cap: Int = MAX_PERSON_EMBEDDINGS,
): Array<FloatArray> {
    if (cap <= 0) return emptyArray()
    val combined = target + source
    if (combined.size <= cap) return combined

    // Reserve capacity for the incoming voice before the survivor claims the rest. `max(1, ...)`
    // keeps the guarantee meaningful at tiny caps (at cap = 1 the incoming recording wins outright)
    // rather than letting integer division silently round the reservation away to nothing.
    val sourceReserved = if (source.isEmpty()) 0 else minOf(source.size, maxOf(1, cap / 2))
    val fromTarget = minOf(target.size, cap - sourceReserved)
    val fromSource = minOf(source.size, cap - fromTarget)
    return target.copyOfRange(0, fromTarget) + source.copyOfRange(0, fromSource)
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
