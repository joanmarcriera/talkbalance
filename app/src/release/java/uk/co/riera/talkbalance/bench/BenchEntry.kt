package uk.co.riera.talkbalance.bench

import androidx.compose.runtime.Composable

/**
 * Variant seam for the T3 bench harness (release build) — deliberately empty.
 *
 * No bench code ships. `BenchScreen`/`BenchViewModel` exist only in `src/debug/`, so a release APK
 * contains no path that opens the microphone for benchmarking and no path that appends captured
 * measurements to app-private storage. This keeps the privacy claim ("microphone audio is processed
 * in memory and is never written to disk") verifiable from the shipped artifact itself.
 */
const val BENCH_AVAILABLE = false

/** No-op: the bench harness is not part of a release build. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun BenchEntry(onBack: () -> Unit) = Unit
