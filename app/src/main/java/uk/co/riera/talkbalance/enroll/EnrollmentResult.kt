package uk.co.riera.talkbalance.enroll

/**
 * Outcome of turning a short voice recording into a stored speaker profile — see
 * [EnrollmentProcessor]. Either a [Success] carrying the multi-embedding profile ready for
 * `EmbeddingCodec`/`SpeakerEmbeddingManager`, or a typed [Failure] carrying a user-facing message
 * the enrollment UI (T10) can show verbatim.
 */
sealed interface EnrollmentResult {

    /**
     * Enrollment succeeded. [embeddings] is exactly three vectors, one per equal third of the
     * captured speech (see [EnrollmentProcessor]); hand it straight to
     * `EmbeddingCodec.encode(...)` for storage or `SpeakerEmbeddingManager.add(name, embeddings)`
     * for matching. It carries no equality semantics beyond a plain reference compare — callers
     * read [embeddings] directly rather than comparing [Success] values.
     */
    data class Success(val embeddings: Array<FloatArray>) : EnrollmentResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            if (embeddings.size != other.embeddings.size) return false
            return embeddings.indices.all { embeddings[it].contentEquals(other.embeddings[it]) }
        }

        override fun hashCode(): Int = embeddings.fold(1) { acc, e -> 31 * acc + e.contentHashCode() }
    }

    /** Something about the recording made it unusable for enrollment. [message] is user-facing. */
    sealed interface Failure : EnrollmentResult {
        val message: String

        /**
         * Almost no speech was detected in the recording — the VAD trimmed nearly everything as
         * silence, so the mic likely caught background noise / the speaker was too quiet.
         */
        data class TooQuiet(override val message: String) : Failure

        /**
         * Speech was detected, but not enough of it: net (silence-removed) speech fell below
         * [EnrollmentProcessor.MIN_NET_SPEECH_MS]. [netSpeechMs] is the measured amount, for the UI
         * to show progress ("got 3.2 s of 5 s").
         */
        data class TooShort(override val message: String, val netSpeechMs: Long) : Failure

        /**
         * The shared native speech engine is currently held by a live session, so enrollment can't
         * safely touch the single-owner VAD/extractor. The user must finish the session first.
         */
        data class Busy(
            override val message: String = "Finish the session first, then add a person.",
        ) : Failure
    }
}

/**
 * An already-enrolled speaker's stored profile, as loaded from `PersonDao` + `EmbeddingCodec`, for
 * the [SimilarVoiceCheck]. [embeddings] is the same multi-embedding array that was stored at that
 * person's enrollment. [id] is the `PersonEntity.id` this profile came from — 0L when constructed
 * without a real row (e.g. in tests that only care about the name/embeddings match), so it can be
 * used to target the matched person for "add to existing person" merges.
 */
data class EnrolledProfile(
    val name: String,
    val embeddings: Array<FloatArray>,
    val id: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrolledProfile) return false
        if (id != other.id) return false
        if (name != other.name) return false
        if (embeddings.size != other.embeddings.size) return false
        return embeddings.indices.all { embeddings[it].contentEquals(other.embeddings[it]) }
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embeddings.fold(1) { acc, e -> 31 * acc + e.contentHashCode() }
        return result
    }
}

/**
 * Non-blocking warning that a freshly-enrolled voice is very close to an existing profile (cosine
 * similarity of the two profiles' centroids exceeds [SimilarVoiceCheck.DEFAULT_THRESHOLD]).
 * Enrollment still succeeds — this is only surfaced so the user can double-check they aren't
 * enrolling the same person twice, or so they know two similar voices may be confused live.
 *
 * @param name the existing profile most similar to the new one.
 * @param similarity that best cosine similarity in `[0, 1]`.
 * @param personId the matched profile's `PersonEntity.id`, so the enrollment UI can offer
 *   "add to $name instead" and merge the new recording straight into that person.
 */
data class SimilarVoiceWarning(val name: String, val similarity: Float, val personId: Long = 0L) {
    /** Ready-to-show message, e.g. "Very similar voice to Alex". */
    val message: String get() = "Very similar voice to $name"
}
