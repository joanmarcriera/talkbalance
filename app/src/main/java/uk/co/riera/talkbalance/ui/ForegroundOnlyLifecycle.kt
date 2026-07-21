package uk.co.riera.talkbalance.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Foreground-only microphone guarantee for this release.
 *
 * Talk Balance intentionally has **no** foreground service or recording notification: a live
 * capture (session or enrollment) is only valid while its Activity is actually in the foreground.
 * The canonical Android signal for "the app is no longer in the foreground" is the process/Activity
 * lifecycle reaching [Lifecycle.Event.ON_STOP] — which fires for Home, Recents/app-switch, screen
 * lock and Back/Activity-finish. Composable *disposal* is NOT that signal: pressing Home or
 * switching apps leaves the composition alive in the stopped state, so a `DisposableEffect.onDispose`
 * hook never runs and the microphone would keep recording invisibly (the P0 blocker in REVIEW.md).
 *
 * Both [EnrollScreen] and [SessionScreen] therefore observe the Activity lifecycle and call their
 * ViewModel's `abort()` on [Lifecycle.Event.ON_STOP]. This function builds that observer so the
 * exact abort-on-stop wiring is shared and unit-testable (see `LifecycleAbortTest`).
 */
internal fun abortOnStopObserver(onStop: () -> Unit): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) onStop()
    }
