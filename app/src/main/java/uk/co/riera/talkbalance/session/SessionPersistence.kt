package uk.co.riera.talkbalance.session

import uk.co.riera.talkbalance.data.SessionEntity
import uk.co.riera.talkbalance.data.SpeakerStatEntity

/**
 * Pure (Android-free, Room-free-of-logic) mapping from a finished [SessionResult] to the Room rows
 * [SessionDao.insertSessionWithStats] persists. Kept separate from [SessionController] so the
 * speaker-id -> personId resolution and the everyone-else bucketing are trivially unit-testable
 * without a device or a database.
 *
 * The engine credits each [SpeakerStat] to one of:
 * - an **enrolled speaker id** — in Table Mode that is the person's `id.toString()`, in Me Mode it
 *   is [uk.co.riera.talkbalance.ml.SherpaSpeakerMatcher.ME_ID] (`"me"`). Either way [speakerIdToPersonId]
 *   maps it back to the Room [uk.co.riera.talkbalance.data.PersonEntity] primary key.
 * - the [SessionStats.EVERYONE_ELSE] sentinel — persisted with `personId = null`,
 *   `isEveryoneElse = true`.
 */
object SessionPersistence {

    /**
     * Build the [SessionEntity] + its [SpeakerStatEntity] rows. The stats' `sessionId` is left at 0
     * here; [SessionDao.insertSessionWithStats] rewrites it to the inserted session's id.
     *
     * @param speakerIdToPersonId maps each enrolled speaker id the engine used to its Room person
     * id (Table: `id.toString() -> id`; Me: `"me" -> mePersonId`). An id missing from the map (should
     * not happen) is stored with a null personId so the row is never dropped.
     */
    fun toEntities(
        result: SessionResult,
        speakerIdToPersonId: Map<String, Long>,
        /** speakerId -> final display label; persisted for discovered speakers (else null). */
        speakerLabels: Map<String, String> = emptyMap(),
    ): Pair<SessionEntity, List<SpeakerStatEntity>> {
        val session = SessionEntity(
            startedAtEpochMs = result.startedAtMs,
            endedAtEpochMs = result.endedAtMs,
            mode = modeTag(result.mode),
            durationMs = result.durationMs,
        )
        val stats = result.speakers.map { stat ->
            val isEveryoneElse = stat.speakerId == SessionStats.EVERYONE_ELSE
            SpeakerStatEntity(
                sessionId = 0L,
                personId = if (isEveryoneElse) null else speakerIdToPersonId[stat.speakerId],
                isEveryoneElse = isEveryoneElse,
                talkMs = stat.talkMs,
                turns = stat.turns,
                longestMonologueMs = stat.longestMonologueMs,
                label = if (isEveryoneElse) null else speakerLabels[stat.speakerId],
            )
        }
        return session to stats
    }

    /** Persisted [SessionEntity.mode] tag for a session [Mode]. */
    fun modeTag(mode: Mode): String = when (mode) {
        Mode.TABLE -> "table"
        Mode.ME -> "me"
        Mode.DISCOVER -> "discover"
    }
}
