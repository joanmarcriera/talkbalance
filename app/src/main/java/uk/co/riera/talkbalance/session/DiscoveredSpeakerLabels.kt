package uk.co.riera.talkbalance.session

/**
 * Pure (Android-free, Compose-free) labelling for Discover mode's dynamically discovered speaker ids
 * ("s1", "s2", …). Assigns each id a stable **first-seen index** — the order voices are discovered —
 * from which the default label "Speaker N" and a palette colour index are derived. A user rename map
 * overrides the default label. Colour index is always the first-seen index (renaming never recolours
 * a speaker).
 *
 * Kept pure so the labelling + rename mapping is unit-tested on the plain JVM (see
 * `DiscoveredSpeakerLabelsTest`); the UI layer turns [colorIndex] into an actual palette `Color`.
 *
 * Usage: rebuild from the full first-seen [order] on every recomposition. [order] must be the ids in
 * discovery order (append-only); the live layer derives it by appending ids as they first appear.
 */
class DiscoveredSpeakerLabels(
    /** Speaker ids in first-seen (discovery) order. Index in this list drives label + colour. */
    private val order: List<String>,
    /** speakerId -> user-chosen name, overriding the "Speaker N" default. Blank names are ignored. */
    private val renames: Map<String, String> = emptyMap(),
) {
    /** First-seen index of [speakerId], or -1 if it has not been seen. */
    fun indexOf(speakerId: String): Int = order.indexOf(speakerId)

    /** The default "Speaker N" label for the 0-based first-seen [index] (N = index + 1). */
    fun defaultLabelForIndex(index: Int): String = "Speaker ${index + 1}"

    /**
     * Display label for [speakerId]: the user's rename if set (non-blank), else "Speaker N" from its
     * first-seen order. An unknown id (not in [order]) falls back to the id itself.
     */
    fun label(speakerId: String): String {
        renames[speakerId]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val index = order.indexOf(speakerId)
        return if (index >= 0) defaultLabelForIndex(index) else speakerId
    }

    /** Palette colour index for [speakerId] = its first-seen order (0 for an unknown id). */
    fun colorIndex(speakerId: String): Int = order.indexOf(speakerId).coerceAtLeast(0)

    companion object {
        /**
         * Appends any ids in [seenNow] not already in [current] (in their [seenNow] order) to produce
         * the updated append-only first-seen order. Ids never move or drop, so labels/colours stay
         * stable across updates even as the live share-sorted list reorders.
         */
        fun mergeOrder(current: List<String>, seenNow: List<String>): List<String> {
            val missing = seenNow.filter { it !in current }
            return if (missing.isEmpty()) current else current + missing
        }
    }
}
