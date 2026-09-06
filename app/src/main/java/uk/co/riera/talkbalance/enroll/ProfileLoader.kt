package uk.co.riera.talkbalance.enroll

import kotlinx.coroutines.flow.first
import uk.co.riera.talkbalance.data.EmbeddingCodec
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.PersonEntity

/**
 * Bridges the Room layer to the [SimilarVoiceCheck]. Decoding is a pure mapping over the entity
 * list ([toEnrolledProfiles]); [loadProfiles] is the DB-touching convenience the UI (T10) calls to
 * get the current profiles before enrolling a new voice.
 */

/**
 * Pure: decode each person's stored embeddings BLOB into an [EnrolledProfile].
 *
 * A person whose BLOB is corrupt is skipped rather than throwing: this feeds the similar-voice
 * check at enrollment, and one damaged row must not block enrolling anyone else. The affected
 * person simply stops matching and can be deleted or re-enrolled.
 */
fun toEnrolledProfiles(persons: List<PersonEntity>): List<EnrolledProfile> =
    persons.mapNotNull { person ->
        EmbeddingCodec.decodeOrNull(person.embeddings)?.let { embeddings ->
            EnrolledProfile(name = person.name, embeddings = embeddings, id = person.id)
        }
    }

/** Snapshot the currently-enrolled people from the DAO as [EnrolledProfile]s for the similar-voice check. */
suspend fun loadProfiles(dao: PersonDao): List<EnrolledProfile> =
    toEnrolledProfiles(dao.observeAll().first())
