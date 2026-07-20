# Project State

## Current objective

Integrate the Android foundation with the existing TalkerTracker history and
prepare the on-device sherpa-onnx benchmark gate.

## Completed work

- Product and implementation plan agreed.
- Existing TalkerTracker research, implementation plan, and diarization spike
  discovered and preserved as the authoritative product history.
- Private Forgejo repository configured at
  `https://git.lab.riera.co.uk/marc/talkertracker.git`; `main` tracks
  `origin/main` and the complete four-commit history has been pushed.
- Android API 36 command-line toolchain installed locally.
- Buildable Kotlin/Compose application and project-local Gradle wrapper created.
- Two-to-six participant setup and explicit consent flow implemented.
- Deterministic report engine implements balance score, turn merging, interval
  union, introduction exclusion, speaker metrics, and confidence grading.
- Foreground microphone service records app-private mono AAC/M4A, supports
  pause/resume/finish/cancel, preserves recovery metadata, verifies SHA-256,
  enforces a 250 MB free-space floor, and publishes a persistent notification.
- Pinned sherpa-onnx 1.13.4 AAR, Silero VAD, CAM++ primary embedding model, and
  English ERes2Net benchmark model are checksum-documented and vendored in Git.
- `SherpaEngines` initializes VAD and the primary embedding extractor off the main
  thread and logs its runtime embedding dimension and initialization duration.

## Current implementation state

The debug app builds and reaches a functional local recording flow. Recording
has not yet been exercised on physical Android hardware. The earlier cloud plan
has been superseded by the repository's validated fully on-device architecture.
Room persistence, guided enrollment, production sherpa-onnx inference, and reports
remain. T2 compiles and packages successfully but cannot be closed until a connected
Android device produces the runtime initialization log.

## Files changed

- Android Gradle project, wrapper, app module, Compose UI, domain engine,
  recording foreground service, manifest/resources, and unit tests
- Durable project state documents and local build instructions

## Tests run

- `./gradlew test assembleDebug lintDebug --no-daemon` — passed; 6 unit tests,
  debug APK built, and lint completed with no errors. The remaining 12 warnings
  are dependency-update suggestions and SharedPreferences KTX style suggestions.
- Packaged-manifest inspection with Android `aapt` confirms microphone,
  notification, and foreground-service permissions only; no Internet permission.
- Sherpa-enabled APK is 144 MB and packages only `arm64-v8a` and `x86_64` native
  libraries; runtime device verification is pending because `adb devices` was empty.
- Deliverable APK SHA-256:
  `24a56e9bfc6fc70db4f7c145ecfe7fb6abbeb9caf7ea7c37788bfab6ddcf9214`.

## Risks and blockers

- Real Android diarization performance remains unvalidated against consented
  dinner audio; the Mac spike is positive but not a device benchmark.
- Foreground recording requires physical-device verification across screen lock,
  pause/resume, interruption, and process termination.
- No Android device is currently visible to ADB, so the T2 runtime log and T3
  threshold/model benchmark cannot yet be captured.
- Forgejo's authenticated Git LFS batch endpoint returns 404, so the approximately
  101 MB pinned model/runtime set must currently be stored as ordinary Git blobs.
- The `git.lab.riera.co.uk` SSH endpoint is not configured for the existing key;
  repository access currently uses HTTPS with the scoped token from `~/.tokens`.

## Next recommended action

Vendor the sherpa-onnx AAR and selected assets, implement the debug benchmark
screen from `PLAN.md` T2/T3, and run it on a physical Android phone. Room and
production session work remain gated on that result.
