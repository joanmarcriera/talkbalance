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

/** Pure: decode each person's stored embeddings BLOB into an [EnrolledProfile]. */
fun toEnrolledProfiles(persons: List<PersonEntity>): List<EnrolledProfile> =
    persons.map { EnrolledProfile(name = it.name, embeddings = EmbeddingCodec.decode(it.embeddings), id = it.id) }

/** Snapshot the currently-enrolled people from the DAO as [EnrolledProfile]s for the similar-voice check. */
suspend fun loadProfiles(dao: PersonDao): List<EnrolledProfile> =
    toEnrolledProfiles(dao.observeAll().first())
