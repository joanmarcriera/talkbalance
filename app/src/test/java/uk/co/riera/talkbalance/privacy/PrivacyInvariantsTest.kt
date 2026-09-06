package uk.co.riera.talkbalance.privacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable guards for Talk Balance's public privacy claim.
 *
 * The app tells users: *"Talk Balance does not request Internet permission and never sends audio off
 * the device — microphone audio is processed in memory and is never written to disk."* That claim was
 * once false (a `RecordingService` wrote `conversation-*.m4a` to app-private storage while the README
 * said "no audio is ever stored"), which is exactly the kind of drift a review catches late and a
 * test catches immediately.
 *
 * These tests read the source tree rather than exercising behaviour on purpose: the claim is about
 * what the shipped artifact *can* do, not only about what the current call graph happens to do. A
 * reintroduced recorder fails here the moment it is written, even if nothing calls it yet.
 *
 * Scope note: only `src/main` and `src/release` are shipped. `src/debug` may open the microphone and
 * write measurement files (the T3 bench harness) because it never reaches a user.
 */
class PrivacyInvariantsTest {

    /**
     * File-writing and media-encoding APIs that must not appear in shipped code. Talk Balance
     * persists only Room rows and DataStore settings, both through their libraries, so shipped
     * sources have no legitimate reason to touch raw file output or a media encoder at all — which
     * makes "none of these, anywhere" a clean, unambiguous invariant to hold.
     */
    private val forbiddenApis = listOf(
        "FileOutputStream",
        "RandomAccessFile",
        "MediaMuxer",
        "MediaRecorder(",
        "setOutputFile",
        ".writeBytes(",
        ".writeText(",
        ".appendText(",
        ".m4a",
        ".aac",
        ".wav",
    )

    @Test
    fun `shipped sources contain no audio-to-disk or file-writing code`() {
        val offenders = shippedKotlinSources().flatMap { file ->
            val text = file.readText()
            forbiddenApis.filter { text.contains(it) }.map { "${file.name} uses $it" }
        }
        assertEquals(
            "Shipped code must never write audio (or anything else) to disk directly. " +
                "If a write is genuinely needed, update the privacy copy in SettingsScreen/README " +
                "in the same commit, then relax this test deliberately.",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `only AudioCapture opens the microphone in shipped code`() {
        val micUsers = shippedKotlinSources()
            .filter { it.readText().contains("AudioRecord") }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            "The microphone must have exactly one owner in shipped code so its lifecycle " +
                "(and therefore the privacy guarantee) is auditable in one place.",
            sortedSetOf("AudioCapture.kt", "AudioEvent.kt"),
            micUsers,
        )
    }

    @Test
    fun `manifest declares no INTERNET permission`() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        assertFalse(
            "The headline claim is that the app cannot reach the network. Declaring INTERNET " +
                "would make it false.",
            manifest.contains("android.permission.INTERNET"),
        )
    }

    /**
     * The bench package is debug-only apart from one file. Rather than blacklisting the bench files
     * we know about today, this whitelists the single file that is allowed to ship — so a NEW bench
     * file added to `src/main` fails here by default instead of quietly riding along in the APK.
     * That is how `BenchMath.kt` (the harness's CSV line formatter) went unnoticed into the shipped
     * 0.1.3 artifact even after the mic-and-CSV screen itself had been moved to `src/debug`.
     */
    @Test
    fun `only the BenchEntry seam may ship from the bench package`() {
        val allowedInShippedBenchPackage = setOf("BenchEntry.kt")
        for (shipped in listOf("main", "release")) {
            val benchDir = File(moduleDir(), "src/$shipped/java/uk/co/riera/talkbalance/bench")
            if (!benchDir.isDirectory) continue
            val unexpected = benchDir.listFiles().orEmpty()
                .filter { it.isFile && it.name !in allowedInShippedBenchPackage }
                .map { "src/$shipped/.../bench/${it.name}" }
                .sorted()
            assertEquals(
                "Only ${allowedInShippedBenchPackage.joinToString()} may live in a shipped bench " +
                    "package — everything else about the T3 harness (its screen, its ViewModel, its " +
                    "CSV formatting, its model A/B) belongs in src/debug so no trace of measurement " +
                    "code is discoverable in a release APK. Move the file to src/debug and put its " +
                    "unit test in src/testDebug.",
                emptyList<String>(),
                unexpected,
            )
        }
    }

    @Test
    fun `the bench-only embedding model is not a shipped asset`() {
        val shippedAssets = File(moduleDir(), "src/main/assets").listFiles().orEmpty().map { it.name }
        assertFalse(
            "The CAM++ model is the LOSING side of the T3 A/B and is used only by the debug bench. " +
                "Shipping it puts ~28 MB of benchmark material — and the visible fact of the A/B — " +
                "in every user's APK. It belongs in src/debug/assets.",
            shippedAssets.any { it.contains("CAM++") },
        )
    }

    @Test
    fun `bench screen and view model live only in debug`() {
        for (name in listOf("BenchScreen.kt", "BenchViewModel.kt", "BenchMath.kt")) {
            assertTrue(
                "$name must live in src/debug — the bench harness opens the microphone and " +
                    "formats a measurement CSV.",
                File(moduleDir(), "src/debug/java/uk/co/riera/talkbalance/bench/$name").exists(),
            )
        }
    }

    @Test
    fun `BenchEntry variant twins disagree about availability`() {
        val debug = File(moduleDir(), "src/debug/java/uk/co/riera/talkbalance/bench/BenchEntry.kt")
        val release = File(moduleDir(), "src/release/java/uk/co/riera/talkbalance/bench/BenchEntry.kt")
        assertTrue("debug BenchEntry seam is missing", debug.exists())
        assertTrue("release BenchEntry seam is missing", release.exists())
        assertTrue(
            "debug must expose the bench",
            debug.readText().contains("const val BENCH_AVAILABLE = true"),
        )
        assertTrue(
            "release must not expose the bench",
            release.readText().contains("const val BENCH_AVAILABLE = false"),
        )
    }

    /** Every Kotlin file that ends up in a release build. */
    private fun shippedKotlinSources(): List<File> =
        listOf("src/main/java", "src/release/java")
            .map { File(moduleDir(), it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    /**
     * The `app` module directory. Unit tests run with the module as the working directory, but walk
     * upwards anyway so the test also passes when run from the repository root or an IDE.
     */
    private fun moduleDir(): File {
        val cwd = System.getProperty("user.dir") ?: error("user.dir is not set")
        var dir: File? = File(cwd)
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            val app = File(dir, "app/src/main/AndroidManifest.xml")
            if (app.exists()) return File(dir, "app")
            dir = dir.parentFile
        }
        error("could not locate the app module from $cwd")
    }
}
