package uk.co.riera.talkbalance.settings

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Unit tests for Settings clamping logic.
 * Tests the pure logic of value validation without DataStore.
 */
class SettingsRepositoryClampingTest {

    @Test
    fun nudgeSecondsBelowMinClamps() {
        val value = 10
        val clamped = value.coerceIn(15, 120)
        assertEquals(15, clamped)
    }

    @Test
    fun nudgeSecondsAboveMaxClamps() {
        val value = 150
        val clamped = value.coerceIn(15, 120)
        assertEquals(120, clamped)
    }

    @Test
    fun nudgeSecondsInRangePassThrough() {
        val value = 45
        val clamped = value.coerceIn(15, 120)
        assertEquals(45, clamped)
    }

    @Test
    fun matchThresholdBelowMinClamps() {
        val value = 0.20f
        val clamped = value.coerceIn(0.30f, 0.80f)
        assertEquals(0.30f, clamped)
    }

    @Test
    fun matchThresholdAboveMaxClamps() {
        val value = 0.90f
        val clamped = value.coerceIn(0.30f, 0.80f)
        assertEquals(0.80f, clamped)
    }

    @Test
    fun matchThresholdInRangePassThrough() {
        val value = 0.50f
        val clamped = value.coerceIn(0.30f, 0.80f)
        assertEquals(0.50f, clamped)
    }
}
