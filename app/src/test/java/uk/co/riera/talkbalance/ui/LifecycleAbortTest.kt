package uk.co.riera.talkbalance.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated evidence for the P0 microphone-lifecycle fix WITHOUT a live mic or a device: drives a
 * real [LifecycleRegistry] through the foreground lifecycle and asserts that the exact observer both
 * [SessionScreen] and [EnrollScreen] install ([abortOnStopObserver]) fires its abort callback on
 * `ON_STOP` — the signal that a Home press / app-switch / screen-lock actually produces (and that
 * composable disposal alone does not). This is the unit-testable core of "backgrounding aborts the
 * session/enrollment and releases the mic".
 */
class LifecycleAbortTest {

    /** Minimal owner backed by an unsafe registry (createUnsafe skips the main-thread assertion). */
    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun abortFiresExactlyOnOnStop() {
        val owner = FakeOwner()
        var aborts = 0
        owner.registry.addObserver(abortOnStopObserver { aborts++ })

        // Foreground: created -> started -> resumed. No ON_STOP yet, so no abort.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertFalse("must not abort while in the foreground", aborts > 0)

        // Home / app-switch / screen-lock: resumed -> paused -> stopped emits ON_STOP.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals("ON_STOP must trigger the mic abort exactly once", 1, aborts)
    }

    @Test
    fun abortFiresAgainOnReturnAndBackground() {
        val owner = FakeOwner()
        var aborts = 0
        owner.registry.addObserver(abortOnStopObserver { aborts++ })

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) // ON_CREATE + ON_START
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        // Return to foreground and background again.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertTrue("each backgrounding must abort", aborts == 2)
    }
}
