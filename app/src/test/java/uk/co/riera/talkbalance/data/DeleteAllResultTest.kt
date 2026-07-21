package uk.co.riera.talkbalance.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Important" fix: "Delete all data" must report the truth per store instead of silently claiming
 * success. Tests the aggregation that turns per-store outcomes into the user-facing summary.
 */
class DeleteAllResultTest {

    @Test fun allStoresClearedReportsCleanSuccess() {
        val result = DeleteAllResult(
            room = StoreDeletion.Success,
            settings = StoreDeletion.Success,
            files = StoreDeletion.Success,
        )
        assertTrue(result.allSucceeded)
        assertEquals("All data deleted.", result.summary)
    }

    @Test fun aPartialFailureNamesExactlyTheStoresThatFailed() {
        val result = DeleteAllResult(
            room = StoreDeletion.Success,
            settings = StoreDeletion.Failed("datastore locked"),
            files = StoreDeletion.Failed("file busy"),
        )
        assertFalse(result.allSucceeded)
        assertTrue(result.summary.contains("settings"))
        assertTrue(result.summary.contains("files"))
        assertFalse("room succeeded, must not be listed as failed", result.summary.contains("people"))
    }

    @Test fun aRoomFailureIsSurfaced() {
        val result = DeleteAllResult(
            room = StoreDeletion.Failed("transaction rolled back"),
            settings = StoreDeletion.Success,
            files = StoreDeletion.Success,
        )
        assertFalse(result.allSucceeded)
        assertTrue(result.summary.contains("people & sessions"))
    }
}
