package uk.co.riera.talkbalance.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DiscoveredSpeakerLabels] — the pure "Speaker N" labelling, rename overrides and
 * first-seen colour mapping used by Discover mode.
 */
class DiscoveredSpeakerLabelsTest {

    @Test fun defaultLabelsAreSpeakerNInFirstSeenOrder() {
        val labels = DiscoveredSpeakerLabels(order = listOf("s1", "s2", "s3"))
        assertEquals("Speaker 1", labels.label("s1"))
        assertEquals("Speaker 2", labels.label("s2"))
        assertEquals("Speaker 3", labels.label("s3"))
    }

    @Test fun labelFollowsFirstSeenOrderNotIdNumber() {
        // Discovery order can differ from the numeric id (e.g. s2 discovered before s1 is impossible
        // for the matcher, but the label must track the given order regardless).
        val labels = DiscoveredSpeakerLabels(order = listOf("s3", "s1"))
        assertEquals("Speaker 1", labels.label("s3"))
        assertEquals("Speaker 2", labels.label("s1"))
    }

    @Test fun renameOverridesTheDefaultLabel() {
        val labels = DiscoveredSpeakerLabels(
            order = listOf("s1", "s2"),
            renames = mapOf("s2" to "Dad"),
        )
        assertEquals("Speaker 1", labels.label("s1"))
        assertEquals("Dad", labels.label("s2"))
    }

    @Test fun blankRenameFallsBackToDefault() {
        val labels = DiscoveredSpeakerLabels(
            order = listOf("s1"),
            renames = mapOf("s1" to "   "),
        )
        assertEquals("Speaker 1", labels.label("s1"))
    }

    @Test fun colorIndexTracksFirstSeenOrder() {
        val labels = DiscoveredSpeakerLabels(order = listOf("s1", "s2", "s3"))
        assertEquals(0, labels.colorIndex("s1"))
        assertEquals(1, labels.colorIndex("s2"))
        assertEquals(2, labels.colorIndex("s3"))
    }

    @Test fun unknownIdFallsBackToIdAndColorZero() {
        val labels = DiscoveredSpeakerLabels(order = listOf("s1"))
        assertEquals("s9", labels.label("s9"))
        assertEquals(0, labels.colorIndex("s9"))
    }

    @Test fun mergeOrderAppendsOnlyUnseenIdsPreservingExistingOrder() {
        val first = DiscoveredSpeakerLabels.mergeOrder(emptyList(), listOf("s1"))
        assertEquals(listOf("s1"), first)

        val second = DiscoveredSpeakerLabels.mergeOrder(first, listOf("s2", "s1"))
        // s1 already present keeps its slot; s2 appended.
        assertEquals(listOf("s1", "s2"), second)

        val stable = DiscoveredSpeakerLabels.mergeOrder(second, listOf("s2", "s1"))
        assertEquals(listOf("s1", "s2"), stable) // no churn when nothing new appears
    }
}
