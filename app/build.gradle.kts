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
        versionCode = 3
        versionName = "0.1.2"

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
