package uk.co.riera.talkbalance.domain

import java.util.UUID

data class ConversationGroup(
    val id: UUID,
    val displayName: String,
    val participants: List<Participant>,
    val defaultLocale: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class Participant(
    val id: UUID,
    val displayName: String,
    val colourIndex: Int,
)

enum class SessionState {
    PREPARING, RECORDING, RECORDED, UPLOADING, PROCESSING,
    NEEDS_MAPPING, COMPLETE, FAILED, DELETING, DELETED,
}

data class IntroWindow(
    val participantId: UUID,
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0) { "Introduction cannot start before the recording" }
        require(endMs > startMs) { "Introduction must have positive duration" }
    }
}

data class SpeakerSegment(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val wordCount: Int,
) {
    init {
        require(speakerId.isNotBlank())
        require(startMs >= 0)
        require(endMs > startMs)
        require(wordCount >= 0)
    }
}

data class SpeakerMetrics(
    val speakerId: String,
    val speakingTimeMs: Long,
    val speechShare: Double,
    val wordCount: Int,
    val turnCount: Int,
    val averageTurnMs: Long,
    val longestTurnMs: Long,
)

enum class ConfidenceGrade { HIGH, MEDIUM, LOW }

data class ConversationReport(
    val durationMs: Long,
    val detectedSpeechMs: Long,
    val estimatedSilenceMs: Long,
    val balanceScore: Int,
    val speakers: List<SpeakerMetrics>,
    val confidence: ConfidenceGrade,
    val warnings: List<String>,
)
