package uk.co.riera.talkbalance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCalculatorTest {
    @Test fun equalSharesScoreOneHundred() {
        assertEquals(100, ReportCalculator.balanceScore(listOf(0.25, 0.25, 0.25, 0.25)))
    }

    @Test fun dominantSpeakerLowersBalanceScore() {
        val score = ReportCalculator.balanceScore(listOf(0.9, 0.05, 0.05))
        assertTrue(score in 30..40)
    }

    @Test fun overlappingSpeechIsNotDoubleCountedForDetectedSpeech() {
        val report = ReportCalculator.calculate(
            durationMs = 60_000,
            expectedSpeakerCount = 2,
            segments = listOf(
                SpeakerSegment("a", 10_000, 30_000, 20),
                SpeakerSegment("b", 20_000, 40_000, 20),
            ),
        )
        assertEquals(30_000, report.detectedSpeechMs)
        assertEquals(30_000, report.estimatedSilenceMs)
    }

    @Test fun closeSegmentsMergeIntoOneTurn() {
        val report = ReportCalculator.calculate(
            durationMs = 120_000,
            expectedSpeakerCount = 2,
            segments = listOf(
                SpeakerSegment("a", 10_000, 30_000, 10),
                SpeakerSegment("a", 31_200, 50_000, 10),
                SpeakerSegment("b", 60_000, 80_000, 10),
            ),
        )
        assertEquals(1, report.speakers.first { it.speakerId == "a" }.turnCount)
    }

    @Test fun introductionWindowIsExcluded() {
        val report = ReportCalculator.calculate(
            durationMs = 120_000,
            expectedSpeakerCount = 2,
            segments = listOf(
                SpeakerSegment("a", 0, 5_000, 5),
                SpeakerSegment("a", 20_000, 40_000, 20),
                SpeakerSegment("b", 50_000, 70_000, 20),
            ),
            introWindows = listOf(0L..5_000L),
        )
        assertEquals(20_000, report.speakers.first { it.speakerId == "a" }.speakingTimeMs)
    }

    @Test fun veryQuietParticipantProducesLowConfidence() {
        val report = ReportCalculator.calculate(
            durationMs = 120_000,
            expectedSpeakerCount = 2,
            segments = listOf(
                SpeakerSegment("a", 0, 60_000, 100),
                SpeakerSegment("b", 70_000, 75_000, 5),
            ),
        )
        assertEquals(ConfidenceGrade.LOW, report.confidence)
    }
}
