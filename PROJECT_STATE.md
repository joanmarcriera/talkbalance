# Project State

## Current objective

Integrate the Android foundation with the existing TalkerTracker history and
prepare the on-device sherpa-onnx benchmark gate.

## Completed work

- Product and implementation plan agreed.
- Existing TalkerTracker research, implementation plan, and diarization spike
  discovered and preserved as the authoritative product history.
- Android API 36 command-line toolchain installed locally.
- Buildable Kotlin/Compose application and project-local Gradle wrapper created.
- Two-to-six participant setup and explicit consent flow implemented.
- Deterministic report engine implements balance score, turn merging, interval
  union, introduction exclusion, speaker metrics, and confidence grading.
- Foreground microphone service records app-private mono AAC/M4A, supports
  pause/resume/finish/cancel, preserves recovery metadata, verifies SHA-256,
  enforces a 250 MB free-space floor, and publishes a persistent notification.

## Current implementation state

The debug app builds and reaches a functional local recording flow. Recording
has not yet been exercised on physical Android hardware. The earlier cloud plan
has been superseded by the repository's validated fully on-device architecture.
Room persistence, guided enrollment, sherpa-onnx inference, and reports remain.

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
- Deliverable APK SHA-256:
  `77854f4440f332657a16e05a2c5bc386390cd7a62c036e3a55fe3c5760dacc22`.

## Risks and blockers

- Real Android diarization performance remains unvalidated against consented
  dinner audio; the Mac spike is positive but not a device benchmark.
- Foreground recording requires physical-device verification across screen lock,
  pause/resume, interruption, and process termination.
- The requested new Forgejo host currently has no authorised SSH key or signed-in
  browser session, so remote creation/push requires authentication.

## Next recommended action

Vendor the sherpa-onnx AAR and selected assets, implement the debug benchmark
screen from `PLAN.md` T2/T3, and run it on a physical Android phone. Room and
production session work remain gated on that result.
