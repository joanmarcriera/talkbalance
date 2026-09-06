package uk.co.riera.talkbalance.bench

import androidx.compose.runtime.Composable

/**
 * Variant seam for the T3 bench harness (debug build).
 *
 * The bench opens the microphone directly and appends measurements to `files/bench-t3.csv`. That is
 * fine for threshold tuning on a dev build, but it must not exist in a shipped artifact: Talk
 * Balance's public claim is that microphone audio is processed in memory and never written to disk,
 * and that claim should hold for anyone who decompiles the release APK — not merely for anyone who
 * traces which branches are reachable. `BenchScreen`/`BenchViewModel` therefore live in
 * `src/debug/` only, and `src/release/` supplies the no-op twin of this file.
 *
 * See `BenchEntryVariantTest` for the invariant that keeps the two twins in sync.
 */
const val BENCH_AVAILABLE = true

/** Shows the T3 bench harness. Debug builds only. */
@Composable
fun BenchEntry(onBack: () -> Unit) = BenchScreen(onBack = onBack)
