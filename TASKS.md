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
- [x] Persist groups and sessions with Room (schema + DAOs; PLAN T6).
  - Evidence: 9/9 instrumented DAO tests pass on CPH2609 (merge 25d02ae);
    Room 2.7.1 + KSP 2.1.20-1.0.32; embeddings BLOB codec round-trip unit-tested.
  - Note: launch-time recovery of unfinished sessions remains open — moved to
    the session-service task (PLAN T9/T17).
- [x] Vendor sherpa-onnx AAR and runtime models (PLAN T2).
  - Completion: on-device log confirms VAD initialization and embedding dimension.
  - Evidence (real, BenchT3 logcat on CPH2609, 2026-07-20, commit 184a2bb):
    `init model=wespeaker_en_voxceleb_CAM++_LM.onnx dim=512 initMs=916` and
    `init model=3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx dim=192 initMs=161`;
    ambient VAD segment processed at inferMs=100 for a 984 ms chunk.
    (Historical note: an earlier fabricated evidence line was struck — see
    PROJECT_STATE.md "Evidence-integrity correction".)
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
