package uk.co.riera.talkbalance.ml

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlinx.coroutines.flow.first
import uk.co.riera.talkbalance.data.EmbeddingCodec
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.PersonEntity

/**
 * Builds a ready-to-use [SherpaSpeakerMatcher] straight from Room in one call, so T9/T11 don't
 * need to know about [EmbeddingCodec] or the enrollment-id convention.
 *
 * Each [PersonEntity] row is enrolled under its stable `id.toString()` — the same string
 * [uk.co.riera.talkbalance.session.SessionEngine]'s `displayNames` map is keyed by, so a matched
 * [MatchResult.speakerId] resolves straight back to a person without another lookup.
 *
 * - **Table Mode**: every row in [personDao] is enrolled.
 * - **Me Mode**: only the row identified by [mePersonId] is enrolled, under
 *   [SherpaSpeakerMatcher.ME_ID] (everyone else is not enrolled at all — the engine's
 *   `Mode.ME.everyoneElseLabel` bucket covers them regardless of what's in the DB).
 */
object SpeakerMatcherFactory {
    suspend fun build(
        personDao: PersonDao,
        extractor: SpeakerEmbeddingExtractor,
        threshold: Float,
        mode: MatcherMode,
        mePersonId: Long? = null,
    ): SherpaSpeakerMatcher {
        require(mode != MatcherMode.ME || mePersonId != null) {
            "MatcherMode.ME requires mePersonId"
        }

        val people = personDao.observeAll().first()
        val enrolled = when (mode) {
            // Table: one person with a corrupt embeddings BLOB is dropped so the rest of the
            // table still works; they stop matching until re-enrolled.
            MatcherMode.TABLE -> people.mapNotNull { it.toEnrolledPersonOrNull(it.id.toString()) }
            MatcherMode.ME -> {
                val me = people.firstOrNull { it.id == mePersonId }
                    ?: error("mePersonId=$mePersonId not found among enrolled persons")
                // Me mode has nothing left to compare against if the one profile is unreadable.
                listOf(
                    me.toEnrolledPersonOrNull(SherpaSpeakerMatcher.ME_ID)
                        ?: error("stored voice profile for '${me.name}' is unreadable; re-enrol to use Me mode"),
                )
            }
        }

        return SherpaSpeakerMatcher(extractor, enrolled, threshold, mode)
    }

    private fun PersonEntity.toEnrolledPersonOrNull(id: String): EnrolledPerson? =
        EmbeddingCodec.decodeOrNull(embeddings)?.let { EnrolledPerson(id, it) }
}
