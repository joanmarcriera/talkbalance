package uk.co.riera.talkbalance.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocker 6 (exclusive native-engine access): the [EngineLeaseHolder] must refuse a second owner
 * while a lease is held — the mechanism that turns "enrollment while a session runs" into a typed
 * Busy failure — and release must be idempotent and not steal a newer owner's lease.
 */
class EngineLeaseTest {

    @Test fun aSecondAcquireIsRefusedWhileTheLeaseIsHeld() {
        val holder = EngineLeaseHolder()

        val session = holder.tryAcquire("session")
        assertNotNull(session)
        assertTrue(holder.isHeld)
        assertEquals("session", holder.owner)

        // Enrollment asking while the session owns the engines gets nothing back -> Busy path.
        assertNull(holder.tryAcquire("enrollment"))

        session!!.release()
        assertFalse(holder.isHeld)
        assertNull(holder.owner)

        // Free again: enrollment can now take it.
        val enrollment = holder.tryAcquire("enrollment")
        assertNotNull(enrollment)
        assertEquals("enrollment", holder.owner)
    }

    @Test fun releaseIsIdempotentAndDoesNotFreeANewerOwner() {
        val holder = EngineLeaseHolder()

        val first = holder.tryAcquire("session")!!
        first.release()
        first.release() // idempotent: no throw, no effect

        val second = holder.tryAcquire("enrollment")!!
        // A late/duplicate release of the *first* lease must not free the second owner.
        first.release()
        assertEquals("enrollment", holder.owner)
        assertNull(holder.tryAcquire("other"))

        second.release()
        assertFalse(holder.isHeld)
    }
}
