# Tasks

## P0 — Current slice

- [x] Android API 36 project builds from the Gradle wrapper.
- [x] Two-to-six participant setup and consent journey works.
- [x] Core models and session states are represented locally.
- [x] Balance score, intro exclusion, interval union, and turn merging are tested.
- [x] Final diff and build/test results are recorded in `PROJECT_STATE.md`.

## P0 — Next slice

- [x] Implement foreground microphone recording service.
  - Completion: AAC/M4A recording survives screen lock, supports pause/resume,
    closes safely, computes SHA-256, and preserves recovery metadata. Physical
    screen-lock/process testing remains part of the device-validation task.
- [ ] Persist groups and sessions with Room.
  - Completion: unfinished sessions and valid audio files are recovered at launch.
- [ ] Vendor sherpa-onnx AAR and runtime models (PLAN T2).
  - Completion: on-device log confirms VAD initialization and embedding dimension.
- [ ] Run the Android model/threshold benchmark gate (PLAN T3).
  - Completion: benchmark findings committed before enrollment/session work.

## P1 — On-device processing

- [ ] Implement AudioRecord 16 kHz capture and sherpa VAD segmenter.
- [ ] Implement on-device enrollment and speaker matcher.
- [x] Declare and verify that the APK has no Internet permission.
- [ ] Implement mapping correction and report UI.

## P2 — Beta hardening

- [ ] Run controlled audio fixture matrix.
- [ ] Add accessibility and device instrumentation coverage.
- [ ] Prepare privacy notice and Play Data Safety declarations.
- [ ] Configure closed Play test and privacy-safe telemetry.
