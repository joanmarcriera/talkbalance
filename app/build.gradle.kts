import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "uk.co.riera.talkbalance"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.riera.talkbalance"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing is read from the environment (sourced from the shell environment) so the keystore and
    // its password never live in the repo. When the vars are absent (contributors, CI without
    // secrets) the release build is simply left unsigned rather than failing.
    val ksPath = System.getenv("TALKBALANCE_KEYSTORE_PATH")
    val ksPassword = System.getenv("TALKBALANCE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("TALKBALANCE_KEY_ALIAS")
    val hasSigning = ksPath != null && ksPassword != null && keyAlias != null

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                this.keyAlias = keyAlias
                keyPassword = ksPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += "onnx" }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // Let JVM unit tests exercise the capture thread without a device: un-mocked android.* stubs
    // (Process.setThreadPriority, Log.w) return defaults instead of throwing "not mocked".
    testOptions { unitTests { isReturnDefaultValues = true } }
}

// --- Release-signing distribution gate -------------------------------------------------------------
//
// The graceful-unsigned path above is kept ON PURPOSE so contributors / CI without the keystore
// secrets can still run a plain `assembleRelease` (it just produces app-release-unsigned.apk).
//
// A build meant for DISTRIBUTION must not silently ship that unsigned artifact. `verifyReleaseSigning`
// fails fast if the release output is missing or unsigned, so the documented release command is:
//
//     ./gradlew assembleRelease verifyReleaseSigning
//
// Passing `-PrequireSigning` additionally (a) refuses to configure a release without the signing
// env vars and (b) auto-runs the verification after assembleRelease — use it on the release/CI path.
// See RELEASE.md.
val requireSigning = project.hasProperty("requireSigning")
// Recomputed here because the `hasSigning` val above is scoped to the android {} block.
val signingEnvPresent = System.getenv("TALKBALANCE_KEYSTORE_PATH") != null &&
    System.getenv("TALKBALANCE_KEYSTORE_PASSWORD") != null &&
    System.getenv("TALKBALANCE_KEY_ALIAS") != null
if (requireSigning && !signingEnvPresent) {
    throw GradleException(
        "requireSigning was set but signing env vars are absent. Set TALKBALANCE_KEYSTORE_PATH, " +
            "TALKBALANCE_KEYSTORE_PASSWORD and TALKBALANCE_KEY_ALIAS before a distributable release build.",
    )
}

tasks.register("verifyReleaseSigning") {
    description = "Fails if the release APK is missing or was produced unsigned (distribution gate)."
    group = "verification"
    mustRunAfter("assembleRelease")
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val signed = releaseDir.resolve("app-release.apk")
        val unsigned = releaseDir.resolve("app-release-unsigned.apk")
        when {
            unsigned.exists() && !signed.exists() -> throw GradleException(
                "Release build produced only ${unsigned.name} (UNSIGNED). Set the TALKBALANCE_KEYSTORE_* / " +
                    "TALKBALANCE_KEY_ALIAS env vars and re-run `./gradlew assembleRelease verifyReleaseSigning`.",
            )
            !signed.exists() -> throw GradleException(
                "Expected a signed ${signed.name} at ${releaseDir.absolutePath}, but it is absent. " +
                    "Run `./gradlew assembleRelease verifyReleaseSigning` with signing env vars set.",
            )
        }
        logger.lifecycle("verifyReleaseSigning: signed ${signed.name} present at ${releaseDir.absolutePath}")
    }
}

if (requireSigning) {
    tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("verifyReleaseSigning") }
}

// --- Release-artifact privacy gate -----------------------------------------------------------------
//
// PrivacyInvariantsTest guards the SOURCE tree; this guards the thing users actually download.
// The two are not the same check, and the gap between them is not hypothetical: the published 0.1.3
// APK shipped the bench harness's CSV formatter and its 28 MB model A/B because those files sat in
// `src/main` while only the bench *screen* had been moved to `src/debug`. A source-level test that
// asks "is BenchScreen.kt in the right folder?" says yes to that APK. This task opens the APK.
//
// It asserts, against the built artifact:
//   * no bench/measurement or audio-recording identifier appears in any dex,
//   * no bench-only asset is packaged,
//   * the manifest declares RECORD_AUDIO and does NOT declare INTERNET.
//
// Markers are matched as raw bytes across the dex files. Dex stores identifiers and string literals
// as MUTF-8, so a class name or literal that is present anywhere — reachable or not — is found. That
// is deliberately stricter than reachability: the claim being defended is about what a decompiler
// can show a sceptical reader, not about what the current call graph happens to invoke.
val releaseArtifactForbiddenMarkers = listOf(
    "BenchScreen", "BenchViewModel", "BenchMath", "BenchModel", "bench-t3",
    "MediaMuxer", "MediaRecorder", "RecordingService", ".m4a",
)
val releaseArtifactForbiddenAssets = listOf("CAM++")

tasks.register("verifyReleaseArtifact") {
    description = "Opens the release APK and fails if it contains bench/recording code, bench-only " +
        "assets, or the INTERNET permission (artifact-level counterpart to PrivacyInvariantsTest)."
    group = "verification"
    mustRunAfter("assembleRelease")

    val releaseDir = layout.buildDirectory.dir("outputs/apk/release")
    val sdkDir = android.sdkDirectory
    val markers = releaseArtifactForbiddenMarkers
    val forbiddenAssets = releaseArtifactForbiddenAssets

    doLast {
        val dir = releaseDir.get().asFile
        val apk = dir.resolve("app-release.apk").takeIf { it.exists() }
            ?: dir.resolve("app-release-unsigned.apk").takeIf { it.exists() }
            ?: throw GradleException(
                "No release APK in ${dir.absolutePath}. Run `./gradlew assembleRelease` first.",
            )

        val failures = mutableListOf<String>()

        ZipFile(apk).use { zip ->
            // 1. Dex contents: no bench/measurement or recording identifiers, anywhere.
            val dexEntries = zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            if (dexEntries.isEmpty()) failures += "no classes*.dex found inside ${apk.name}"
            for (entry in dexEntries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                for (marker in markers) {
                    if (indexOfBytes(bytes, marker.toByteArray(Charsets.UTF_8)) >= 0) {
                        failures += "${entry.name} contains the forbidden marker \"$marker\""
                    }
                }
            }

            // 2. Packaged assets: nothing that exists only to serve the bench.
            for (entry in zip.entries().toList()) {
                if (!entry.name.startsWith("assets/")) continue
                for (asset in forbiddenAssets) {
                    if (entry.name.contains(asset)) {
                        failures += "bench-only asset packaged: ${entry.name}"
                    }
                }
            }
        }

        // 3. Declared permissions, read from the built manifest rather than the source one.
        val aapt2 = newestBuildToolsBinary(sdkDir, "aapt2")
        if (aapt2 == null) {
            logger.warn(
                "verifyReleaseArtifact: no aapt2 found under ${sdkDir}/build-tools — dex and asset " +
                    "checks ran, but the permission check was SKIPPED.",
            )
        } else {
            val badging = providers.exec {
                commandLine(aapt2.absolutePath, "dump", "badging", apk.absolutePath)
            }.standardOutput.asText.get()
            val permissions = badging.lineSequence()
                .filter { it.startsWith("uses-permission:") }
                .map { it.substringAfter("name='").substringBefore("'") }
                .toList()
            if (permissions.any { it == "android.permission.INTERNET" }) {
                failures += "the APK declares android.permission.INTERNET — the headline privacy " +
                    "claim is that the app cannot reach the network"
            }
            if (permissions.none { it == "android.permission.RECORD_AUDIO" }) {
                failures += "the APK does not declare RECORD_AUDIO — it cannot work; is this the " +
                    "right artifact?"
            }
            logger.lifecycle("verifyReleaseArtifact: declared permissions = ${permissions.joinToString()}")
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Release artifact ${apk.name} failed the privacy gate:\n" +
                    failures.joinToString("\n") { "  - $it" } +
                    "\n\nThe shipped APK must contain no measurement or recording code. Move the " +
                    "offending code to src/debug (and its unit test to src/testDebug), then rebuild.",
            )
        }
        logger.lifecycle("verifyReleaseArtifact: ${apk.name} is clean (${markers.size} markers checked).")
    }
}

/** Naive byte-sequence search — enough to answer "does this literal appear in the dex at all?". */
fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > haystack.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** Newest build-tools copy of [name], or null when the SDK has no build-tools installed. */
fun newestBuildToolsBinary(sdkDir: File, name: String): File? =
    sdkDir.resolve("build-tools").listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .reversed()
        .firstNotNullOfOrNull { it.resolve(name).takeIf { bin -> bin.canExecute() } }

if (requireSigning) {
    tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("verifyReleaseArtifact") }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
