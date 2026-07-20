# Codex review for Claude — 2026-07-20

> **Handoff integrity:** Codex did not modify any implementation, configuration,
> documentation, test, model, or project-state file during this review. The only
> repository file Codex added is this file: `CODEX_REVIEW_FOR_CLAUDE.md`.

Review target: Forgejo `main` at `5e66e4f6b6d330874cd7a32a661117f6a340ec24`.
Codex fetched the remote tip without changing the original working tree, reviewed an
archive in a temporary directory, and ran:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug
BUILD SUCCESSFUL (70 JVM tests, 0 failures; lint warnings only)
```

This is a review and idea backlog, not an assertion that everything below should be
implemented at once. Fix release-blocking claim/data issues first, preserve the current
working build, and keep device evidence attached to each completed gate.

## Release blockers / confirmed defects

### P0 — Public privacy claims contradict the shipped recording implementation

`README.md:15-18` says “No recording” and “Audio is never written to disk.”
`SettingsScreen.kt:139-141` says “No audio is ever stored.” In contrast,
`RecordingService.kt:60-78` creates `files/recordings/conversation-*.m4a`, records AAC
to it, and saves recovery metadata. `TalkBalanceApp.kt:178-180` more accurately says the
app records the conversation and retains aggregate statistics, but the M4A is not
automatically deleted after analysis because the production analysis pipeline does not
yet consume it.

Impact: the strongest public product claim is currently false, and the discrepancy is
material for consent, F-Droid/public release, and privacy review.

Smallest safe correction: until the PCM-only on-device pipeline replaces the M4A path,
change outward-facing copy to say audio is stored locally and identify its deletion
rule. Before release, choose one coherent architecture:

1. preferred: production `AudioCapture -> SpeechSegmenter -> matcher`, never create an
   M4A; or
2. explicitly disclose local M4A retention and implement reliable deletion controls.

Do not publish “never written to disk” until an APK/device test proves it.

### P1 — “Delete all data” does not delete all data

`TalkBalanceApp.kt:71-78` deletes Room persons/sessions/stats only. It leaves:

- `files/recordings/*.m4a`;
- `files/bench-t3.csv`;
- `recording_recovery` SharedPreferences and any referenced active file;
- DataStore settings; and
- any future report/share files.

This is especially serious beside the privacy claim and button label “Delete all data.”
Create one application-level deletion coordinator that first stops/cancels active audio,
then deletes Room data, recordings, bench/debug artifacts, recovery metadata, settings,
and report/share files. Return a result to the UI and test partial filesystem failure.

### P1 — Failed finalisation can leave a live, unrecoverable recorder

In `RecordingService.finishRecording()` (`RecordingService.kt:121-142`), if
`MediaRecorder.stop()` throws, the failure path calls `fail()` but leaves `recorder`
non-null, the foreground notification active, and the timer cancelled. `fail()` stops
the service only when `recorder == null` (`157-163`). The UI changes to `Failed` and
offers Start again, but `startRecording()` immediately returns because the old recorder
is still non-null (`54-55`).

Always release/clear the recorder in a `finally`-style terminal path, retain or delete
the possibly corrupt file under an explicit rule, clear recovery metadata when safe,
and stop the foreground service. Add a test seam around the recorder so `stop()` failure
is deterministic.

### P1 — T3’s provisional model decision is not supported by the recorded experiment

`bench-data/ANALYSIS-2026-07-20.md:5-10` says the CAM++ epoch was lost and the surviving
three scored pairs are ERes2Net only. Yet `bench-data/T3-DECISION.md:14-15` selects CAM++
over ERes2Net because it allegedly has the “same clean separation.” There is no surviving
CAM++ comparison supporting that statement. The document correctly labels the decision
provisional, but it also says this unblocks downstream work.

Treat 0.50 as a provisional threshold hypothesis, not a completed A/B gate. Run the
documented redo with two speakers plus a stranger for both models, persist model identity
in every measurement row, report sample counts/distributions, and choose a model only
after comparable data exists. Include short 1-3 second utterances because that is the
production risk the gate was meant to test.

## High-value engineering corrections

### P1 — `AudioCapture.stop()` can deadlock on a full channel

The urgent-audio thread calls `channel.trySendBlocking()` (`AudioCapture.kt:121-123`).
If the bounded channel is full and the consumer has stopped, the thread blocks inside
the send. Setting `running = false` cannot unblock it; `stop()` then times out after one
second and throws (`68-77`), and `release()` cannot close the channel because it calls
`stop()` first.

Use a non-blocking send with an explicit overflow policy, or make shutdown close/cancel
the producer path before joining. Add a test with capacity 1, no consumer, and enough
input to fill the channel; `stop()` must finish and release the recorder.

### P1 — `SherpaEngines` initializes resources that production code cannot obtain

`TalkBalanceApplication` eagerly initializes `SherpaEngines`, but `SherpaEngines` keeps
its `Vad` and `SpeakerEmbeddingExtractor` private and returns only dimensions/timing.
`SpeechSegmenter` requires a real `Vad`, and the planned matcher requires an extractor.
The current shape therefore spends memory and startup time without providing the
single-threaded ownership seam needed by T7/T9.

Before connecting the pipeline, define one explicit ML owner/executor that creates,
uses, resets, and releases VAD/extractor on one thread. Avoid exposing raw JNI objects
across arbitrary callers, and avoid simultaneously keeping application-global engines
plus benchmark-local engines alive.

### P2 — Threshold help text reverses the matching semantics

`SettingsScreen.kt:128` says “Lower = stricter matching.” With cosine matching and
acceptance at/above threshold, lower is more permissive/looser and higher is stricter.
Correct the text and add a UI/content test so tuning guidance cannot silently invert.

### P2 — Continuation heuristic accepts negative gaps

`SessionEngine.kt:164-171` checks only `gapMs < 500`. An overlapping or out-of-order
unmatched chunk has a negative gap and is therefore credited to the previous enrolled
speaker. If overlap is intentionally unsupported, require `gapMs >= 0` and record
overlap/out-of-order as a warning/everyone-else case. Add boundary tests for negative,
0, 499, and 500 ms.

### P2 — Embedding decoder trusts arbitrary/corrupt lengths

`EmbeddingCodec.decode()` (`EmbeddingCodec.kt:32-39`) trusts the count and every vector
length without checking remaining bytes, positivity, expected dimension, or a maximum.
A damaged DB can cause `BufferUnderflowException`, negative array sizes, or large
allocations during app startup/enrolment. Validate a versioned format, count limits,
dimensions, exact remaining size, and finite floats; return a typed corruption error.

### P2 — Settings singleton should retain the application context only

`SettingsRepository.getInstance(context)` passes the supplied `Context` into a process
singleton (`SettingsRepository.kt:72-79`). Current caller uses the Application, but the
API permits an Activity and would then retain it. Normalize with
`context.applicationContext` inside the factory/constructor.

## Project/release integrity

### P1 — Durable state files were deleted while decisions still require them

Remote commits removed `PROJECT_STATE.md` and `TASKS.md`. This conflicts with the project
execution protocol requiring those files for multi-session work. It also leaves a live
broken instruction: `DECISIONS.md:61-64` tells parallel sessions to check
`PROJECT_STATE.md` ownership before starting work.

Restore concise durable state/backlog files (they can be excluded from a later public
mirror if genuinely necessary) and record current owner, completed gates, exact test
evidence, blockers, and next action. Do not make coordination depend on chat history.

### P1 — README links to a nonexistent release runbook

`README.md:57-58` links to `RELEASE.md`, but that file does not exist at the reviewed
remote tip. Either add the promised public-safe runbook or remove the link. Also verify
the stated F-Droid endpoint and “release pipeline ... in place” claim in a clean install
before treating the README as release copy.

### P2 — Public-scrub rewrite damaged decision prose

`DECISIONS.md:9-25` contains mechanically broken phrases such as “the private Git
server-primary repository” and “scoped the private Git server API token.” Rewrite those
paragraphs manually. Keep internal authentication/topology details out of public copy,
but preserve intelligible rationale in the private source of truth.

### P2 — Plan and code have drifted

Examples: `PLAN.md:35` still names package `com.talkertracker` while code uses
`uk.co.riera.talkbalance`; the plan says target 35 while Gradle targets 36; it still
describes `maxSpeechDuration` as bounding live latency although the anomaly note proves
it does not; and several tasks are implemented while the plan table remains static.
Reconcile the plan after restoring `TASKS.md`; do not use the old table as completion
evidence.

## Test and validation ideas

The 70 JVM tests are a good base, especially the segment boundary and session-stat
coverage. Next tests with the best risk reduction:

1. Instrumented end-to-end delete-all test proving no app-private audio/data artifacts
   remain after success and after an interrupted recording.
2. Recorder fault-injection tests for prepare/start/pause/resume/stop failures and rapid
   stop/start.
3. `AudioCapture` full-channel shutdown test and real incoming-call/mic-contention test.
4. Room migration/schema export policy before beta data exists; `exportSchema=false`
   currently removes migration history.
5. Process-death recovery test that validates M4A integrity rather than accepting any
   file over 1 KiB, and does not clear recovery metadata before validation succeeds.
6. Physical-device 30/60/120-minute runs measuring battery, thermal behaviour, dropped
   chunks, screen-lock survival, and restart recovery.
7. Accessibility tests for large fonts/TalkBack; emoji-only settings control needs an
   accessible label.
8. Release-APK assertions: no `INTERNET`, no debug Bench entry point/data writer, correct
   signing, and no transcript/audio leakage in logs or backups.

## Suggested execution order

1. Reconcile privacy copy with actual audio behaviour and fix comprehensive deletion.
2. Fix recorder terminal cleanup and `AudioCapture` shutdown.
3. Restore `PROJECT_STATE.md`/`TASKS.md` and repair broken release documentation.
4. Redo T3 with comparable CAM++/ERes2Net data; then lock model/threshold.
5. Define the single-thread ML owner and implement T7/T9 against it.
6. Only then continue enrollment/session/report UI and public distribution claims.

## What is already strong

- No `INTERNET` permission in the manifest.
- Pinned/checksummed on-device models and explicit ABI filters.
- Clean JVM test/lint/build result at the reviewed tip.
- Useful pure-Kotlin seams for `SessionStats`, `SessionEngine`, `BenchMath`, and most
  segmenter logic.
- Room foreign keys/cascades and transactional session-with-stats insertion.
- Evidence-first anomaly note correctly documents that sherpa's max-speech setting is
  not a hard chunk cap.

Again: Codex intentionally changed no existing repository file. This handoff file is the
only file added by Codex.
