package uk.co.riera.talkbalance.data

/**
 * Pure presentation logic for the saved-session history — turning the Room rows
 * ([SessionEntity] + its [SpeakerStatEntity] children) back into something the history list and a
 * past-session report can draw. Kept free of Room and Android, like [SessionPersistence] on the
 * write side, so every naming and sharing rule below is plain-JUnit testable.
 *
 * History is **stats only**: talk time, turns and longest monologue per speaker. No audio is stored
 * (the privacy claim), and per-segment timings are not persisted either, so a past session shows a
 * talk-share breakdown but not the live session's timeline ribbon.
 *
 * @see SessionPersistence for the write side these rows come from.
 */
object SessionHistory {

    /**
     * Resolve one saved speaker row's display name, given the people still enrolled.
     *
     * There are four distinguishable cases, and the order matters:
     * 1. the everyone-else bucket — labelled per the session's [Mode][uk.co.riera.talkbalance.session.Mode];
     * 2. a discovered speaker — [SpeakerStatEntity.label] holds "Speaker 2" or the user's rename,
     *    captured at save time so it survives regardless of what happens to the person table;
     * 3. an enrolled speaker — [SpeakerStatEntity.personId] still resolves, so use the person's
     *    current name (a rename on Home is reflected in old reports, which is what users expect);
     * 4. an enrolled speaker whose person has since been deleted — the foreign key is `SET_NULL`, so
     *    personId is null with no label. The stats deliberately survive the person (deleting someone
     *    must not silently rewrite past reports), so this row needs an honest placeholder rather
     *    than being hidden or blamed on somebody else.
     */
    fun labelFor(
        stat: SpeakerStatEntity,
        personsById: Map<Long, PersonEntity>,
        everyoneElseLabel: String,
    ): String {
        if (stat.isEveryoneElse) return everyoneElseLabel
        stat.label?.let { return it }
        stat.personId?.let { id -> personsById[id]?.let { return it.name } }
        return DELETED_PERSON_LABEL
    }

    /**
     * Build the display rows for one saved session, ordered by talk time descending (the same order
     * the live report uses, so a session looks the same when revisited).
     *
     * [HistorySpeakerRow.share] is each speaker's fraction of *attributed talk time*, not of the
     * session's wall-clock duration — silence is not a speaker, and the shares therefore sum to 1.
     * A session with no attributed talk yields zero shares rather than a divide-by-zero.
     */
    fun rowsFor(
        stats: List<SpeakerStatEntity>,
        personsById: Map<Long, PersonEntity>,
        everyoneElseLabel: String,
    ): List<HistorySpeakerRow> {
        val totalTalkMs = stats.sumOf { it.talkMs }
        return stats
            .sortedByDescending { it.talkMs }
            .map { stat ->
                HistorySpeakerRow(
                    label = labelFor(stat, personsById, everyoneElseLabel),
                    colorIndex = stat.personId?.let { personsById[it]?.colorIndex },
                    isEveryoneElse = stat.isEveryoneElse,
                    talkMs = stat.talkMs,
                    turns = stat.turns,
                    longestMonologueMs = stat.longestMonologueMs,
                    share = if (totalTalkMs <= 0L) 0.0 else stat.talkMs.toDouble() / totalTalkMs,
                )
            }
    }

    /**
     * One-line summary for the history list: who led the conversation and by how much. Null when the
     * session recorded no talk at all (a session started and stopped without anyone speaking), so
     * the caller can omit the line rather than print a meaningless "— · 0%".
     */
    fun summaryFor(rows: List<HistorySpeakerRow>): HistorySummary? {
        val leader = rows.maxByOrNull { it.talkMs }?.takeIf { it.talkMs > 0L } ?: return null
        return HistorySummary(
            leaderLabel = leader.label,
            leaderSharePercent = (leader.share * 100).toInt(),
            speakerCount = rows.count { it.talkMs > 0L },
        )
    }

    /** Shown for a speaker whose enrolled person was deleted after the session was saved. */
    const val DELETED_PERSON_LABEL = "Deleted person"
}

/**
 * One speaker's line in a saved session's report.
 *
 * [colorIndex] is a [uk.co.riera.talkbalance.ui.SpeakerPalette] index and is non-null only for a
 * speaker still resolvable to an enrolled person. Discovered speakers, the everyone-else bucket and
 * deleted people have no stored colour; the caller assigns one (see the history report screen, which
 * colours discovered speakers by rank so a revisited session looks stable).
 */
data class HistorySpeakerRow(
    val label: String,
    val colorIndex: Int?,
    val isEveryoneElse: Boolean,
    val talkMs: Long,
    val turns: Int,
    val longestMonologueMs: Long,
    val share: Double,
)

/** The "who led" line under a session in the history list. */
data class HistorySummary(
    val leaderLabel: String,
    val leaderSharePercent: Int,
    val speakerCount: Int,
)
