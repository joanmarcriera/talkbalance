package uk.co.riera.talkbalance.domain

import kotlin.math.ln
import kotlin.math.roundToInt

object ReportCalculator {
    const val TURN_GAP_MS = 1_200L

    fun calculate(
        durationMs: Long,
        expectedSpeakerCount: Int,
        segments: List<SpeakerSegment>,
        introWindows: List<LongRange> = emptyList(),
        mappedSpeakerIds: Set<String> = segments.map { it.speakerId }.toSet(),
        manualMappingUsed: Boolean = false,
    ): ConversationReport {
        require(durationMs > 0)
        require(expectedSpeakerCount in 2..6)

        val dinnerSegments = segments
            .flatMap { subtractWindows(it, introWindows) }
            .sortedBy { it.startMs }
        val detectedSpeechMs = unionDuration(dinnerSegments)
        val grouped = dinnerSegments.groupBy { it.speakerId }
        val totalAttributedMs = grouped.values.sumOf { speakerDuration(it) }

        val metrics = grouped.map { (speakerId, speakerSegments) ->
            val turns = mergeTurns(speakerSegments)
            val speakingMs = speakerDuration(speakerSegments)
            SpeakerMetrics(
                speakerId = speakerId,
                speakingTimeMs = speakingMs,
                speechShare = if (totalAttributedMs == 0L) 0.0 else speakingMs.toDouble() / totalAttributedMs,
                wordCount = speakerSegments.sumOf { it.wordCount },
                turnCount = turns.size,
                averageTurnMs = if (turns.isEmpty()) 0 else turns.sumOf { it.last - it.first }.div(turns.size),
                longestTurnMs = turns.maxOfOrNull { it.last - it.first } ?: 0,
            )
        }.sortedByDescending { it.speakingTimeMs }

        val mappedMs = metrics.filter { it.speakerId in mappedSpeakerIds }.sumOf { it.speakingTimeMs }
        val mappedRatio = if (totalAttributedMs == 0L) 0.0 else mappedMs.toDouble() / totalAttributedMs
        val warnings = buildList {
            if (grouped.size != expectedSpeakerCount) add("The detected speaker count does not match the group.")
            if (mappedRatio < 0.9) add("Some speech could not be confidently assigned.")
            if (metrics.any { it.speakingTimeMs < 15_000 }) add("At least one participant had very little detected speech.")
        }
        val confidence = when {
            grouped.size != expectedSpeakerCount || mappedRatio < 0.75 || metrics.any { it.speakingTimeMs < 15_000 } -> ConfidenceGrade.LOW
            manualMappingUsed || mappedRatio < 0.9 -> ConfidenceGrade.MEDIUM
            else -> ConfidenceGrade.HIGH
        }

        return ConversationReport(
            durationMs = durationMs,
            detectedSpeechMs = detectedSpeechMs.coerceAtMost(durationMs),
            estimatedSilenceMs = (durationMs - detectedSpeechMs).coerceAtLeast(0),
            balanceScore = balanceScore(metrics.map { it.speechShare }),
            speakers = metrics,
            confidence = confidence,
            warnings = warnings,
        )
    }

    fun balanceScore(shares: List<Double>): Int {
        if (shares.size < 2 || shares.sum() <= 0.0) return 0
        val entropy = -shares.filter { it > 0.0 }.sumOf { it * ln(it) }
        return (100.0 * entropy / ln(shares.size.toDouble())).roundToInt().coerceIn(0, 100)
    }

    private fun speakerDuration(segments: List<SpeakerSegment>) =
        segments.sumOf { it.endMs - it.startMs }

    private fun mergeTurns(segments: List<SpeakerSegment>): List<LongRange> {
        val sorted = segments.sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()
        val turns = mutableListOf<LongRange>()
        var start = sorted.first().startMs
        var end = sorted.first().endMs
        for (segment in sorted.drop(1)) {
            if (segment.startMs - end <= TURN_GAP_MS) end = maxOf(end, segment.endMs)
            else {
                turns += start..end
                start = segment.startMs
                end = segment.endMs
            }
        }
        turns += start..end
        return turns
    }

    private fun unionDuration(segments: List<SpeakerSegment>): Long {
        if (segments.isEmpty()) return 0
        val sorted = segments.sortedBy { it.startMs }
        var start = sorted.first().startMs
        var end = sorted.first().endMs
        var total = 0L
        for (segment in sorted.drop(1)) {
            if (segment.startMs <= end) end = maxOf(end, segment.endMs)
            else {
                total += end - start
                start = segment.startMs
                end = segment.endMs
            }
        }
        return total + end - start
    }

    private fun subtractWindows(segment: SpeakerSegment, windows: List<LongRange>): List<SpeakerSegment> {
        var pieces = listOf(segment)
        for (window in windows) {
            pieces = pieces.flatMap { piece ->
                if (window.last <= piece.startMs || window.first >= piece.endMs) listOf(piece)
                else buildList {
                    if (window.first > piece.startMs) add(piece.copy(endMs = window.first, wordCount = 0))
                    if (window.last < piece.endMs) add(piece.copy(startMs = window.last, wordCount = 0))
                }
            }
        }
        return pieces
    }
}
