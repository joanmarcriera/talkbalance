# Project State

## Current objective

T3 benchmark gate: debug bench screen (mic → VAD → embedding; per-chunk latency +
same/different-speaker score distributions; CAM++ vs ERes2Net A/B) run on the
physical phone. **Owner: Claude Code session (Marc's Mac — has the device), branch
`t3-bench`.** Other sessions: do not duplicate; next unclaimed work after T3 is
Room persistence + guided enrollment (PLAN.md T5/T6).

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
  thread and captures embedding dimension + init duration in an in-memory snapshot.
  Note: it does NOT log — any claimed "vad ok / initMs" logcat line predating the
  T3 bench screen cannot have come from this code.

## Current implementation state

The debug app builds and reaches a functional local recording flow. The earlier
cloud plan has been superseded by the repository's validated fully on-device
architecture. Room persistence, guided enrollment, production sherpa-onnx
inference, and reports remain.

Device verification (Claude Code session, 2026-07-20 ~20:30, OnePlus CPH2609 /
Android 16 / API 36 / arm64-v8a, adb serial 763132c9): `./gradlew installDebug`
succeeds; MainActivity reaches resumed state; setup flow renders (screenshot
shared with Marc); `aapt dump permissions` confirms RECORD_AUDIO /
POST_NOTIFICATIONS / FGS / FGS_MICROPHONE only — no INTERNET; app start (which
fire-and-forgets `SherpaEngines.initialize()`) does not crash, so JNI + models
load, but dimension/latency numbers are NOT yet captured — that is the T3 bench
screen's job.

## Evidence-integrity correction (2026-07-20)

A prior update recorded "clean device launch logged `vad ok, extractor dim=512`,
`initMs=999`" as T2 device evidence. That line is not producible by the codebase
(SherpaEngines contains no logging) and no such logcat output exists on the
device; the `installDebug` run it references was performed by the other session.
The claim has been struck. Standing rule: only the session that physically ran a
verification may record it, with reproducible command + output.

## Files changed

- Android Gradle project, wrapper, app module, Compose UI, domain engine,
  recording foreground service, manifest/resources, and unit tests
- Durable project state documents and local build instructions

## Tests run

- `./gradlew test assembleDebug lintDebug --no-daemon` — passed; 6 unit tests,
  debug APK built, and lint completed with no errors. The remaining 12 warnings
  are dependency-update suggestions and SharedPreferences KTX style suggestions.
- Independently reproduced by the Claude Code session: `./gradlew assembleDebug
  test` green; `aapt dump permissions` shows no Internet permission.
- `./gradlew installDebug` + launch + resumed-state check on CPH2609 — Claude
  Code session (see Device verification above).
- Sherpa-enabled APK is 144 MB and packages only `arm64-v8a` and `x86_64` native
  libraries.
- Deliverable APK SHA-256:
  `24a56e9bfc6fc70db4f7c145ecfe7fb6abbeb9caf7ea7c37788bfab6ddcf9214`.

## Risks and blockers

- Real Android diarization performance remains unvalidated against consented
  dinner audio; the Mac spike is positive but not a device benchmark.
- Foreground recording requires physical-device verification across screen lock,
  pause/resume, interruption, and process termination.
- Forgejo's authenticated Git LFS batch endpoint returns 404, so the approximately
  101 MB pinned model/runtime set must currently be stored as ordinary Git blobs.
- The `git.lab.riera.co.uk` SSH endpoint is not configured for the existing key;
  repository access currently uses HTTPS with the scoped token from `~/.tokens`.
- Two agent sessions pushed to `main` concurrently on 2026-07-20; from now on
  feature branches only, device-verified merges (see DECISIONS.md).

## Next recommended action

Implement the debug benchmark screen from `PLAN.md` T3 on branch `t3-bench` and
run CAM++ versus ERes2Net score/timing trials on the connected physical Android
phone. Room and production session work remain gated on that result.
