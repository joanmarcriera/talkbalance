package uk.co.riera.talkbalance.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Important" fix: the Me-mode report balance target. Me mode has a single enrolled identity but the
 * report is You-vs-Others (two categories), so a fair split is 50/50 — not "100% each", which the
 * old `100 / meta.size` produced. Table mode still splits evenly across the enrolled people.
 */
class BalanceTargetTest {

    @Test fun meModeTargetsFiftyFiftyWithModeAppropriateCopy() {
        val target = balanceTarget(Mode.ME, enrolledCount = 1)
        assertEquals(50, target.perCategoryPercent)
        assertTrue("copy should state 50% each", target.copy.contains("50%"))
        assertTrue("copy should frame it as you vs others", target.copy.contains("you", ignoreCase = true))
    }

    @Test fun tableModeSplitsEvenlyAcrossEnrolledPeople() {
        assertEquals(25, balanceTarget(Mode.TABLE, enrolledCount = 4).perCategoryPercent)
        assertEquals(33, balanceTarget(Mode.TABLE, enrolledCount = 3).perCategoryPercent)
        assertEquals(50, balanceTarget(Mode.TABLE, enrolledCount = 2).perCategoryPercent)
        assertTrue(balanceTarget(Mode.TABLE, enrolledCount = 4).copy.contains("25%"))
    }

    @Test fun tableModeGuardsAgainstAZeroCount() {
        assertEquals(100, balanceTarget(Mode.TABLE, enrolledCount = 0).perCategoryPercent)
    }
}
