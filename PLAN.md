# TalkerTracker — implementation plan (delegation-ready)

Status 2026-07-20. Inputs: [README.md](README.md) (spike GO), [RESEARCH.md](RESEARCH.md)
(what users value). Tracking: the project tracker. Marc has: physical Android phone,
wants full Android Studio install.

## Verified integration facts (do not re-research)

- **sherpa-onnx is NOT on Maven Central.** Use the prebuilt **`sherpa-onnx-1.13.4.aar`
  (48.8 MB)** from the GitHub v1.13.4 release, vendored into `app/libs/`. Bundles the
  Kotlin API (`com.k2fsa.sherpa.onnx.*`) + JNI for all ABIs; `ndk.abiFilters
  += ["arm64-v8a","x86_64"]`.
- **Models ship in APK assets** (root of `assets/`, not a subdir):
  `silero_vad.onnx` (0.64 MB, release tag `asr-models`) +
  `wespeaker_en_voxceleb_CAM++_LM.onnx` (29.3 MB, tag `speaker-recongition-models` —
  typo is real). Alt A/B for the T3 bench: `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx`
  (26.5 MB), `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` (28.3 MB).
  **No INTERNET permission in the manifest — this is the headline privacy claim.**
- **`SpeakerEmbeddingManager` already IS the matcher**: `add(name, Array<FloatArray>)`
  (multi-embedding enrollment), `search(emb, threshold)` returns `""` = our
  "everyone else" bucket, `verify(name, emb, threshold)` = Me Mode. No cosine math to write.
- **Kotlin API**: `Vad(assetManager, VadModelConfig(SileroVadModelConfig(...),
  sampleRate=16000))` with `acceptWaveform/front/pop`; `SpeakerEmbeddingExtractor`
  with `createStream/compute/dim()` (read dim at runtime, never hardcode).
- **FGS**: permissions `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE` (API 34+), `POST_NOTIFICATIONS`;
  `android:foregroundServiceType="microphone"`; request RECORD_AUDIO *before*
  `startForeground` (API 34 verifies at start). Mic FGS must start from foreground;
  Android 15 FGS timeout does NOT apply to microphone type. targetSdk 35 ⇒ edge-to-edge.
- Reference implementations to crib: sherpa-onnx repo `android/SherpaOnnxSpeakerIdentification`,
  `android/SherpaOnnxVad` (AudioRecord loops), `android/SherpaOnnxAar`.

## Architecture (single :app module, package-by-feature)

`com.talkertracker`: `audio/AudioCapture` (AudioRecord 16k mono, 100 ms FloatArrays →
Channel) · `ml/SherpaEngines` (asset init) · `ml/SpeechSegmenter` (512-sample VAD windows
→ SpeechChunk) · `ml/SpeakerMatcher` (wraps SpeakerEmbeddingManager, loads profiles from
DB) · `ml/EnrollmentProcessor` (10 s → VAD-trim → ≥5 s net speech gate → 3 embeddings +
similar-voice warning) · `session/SessionEngine` (state machine + attribution + StateFlow)
· `session/SessionStats` (pure-Kotlin accumulator: talkMs/turns/longestMonologue — unit
tested) · `session/SessionService` (mic FGS) · `session/NudgeController` ·
`chessclock/ChessClockEngine` · `data/` (Room: Person[embeddings BLOB], Session,
SpeakerStat) · `ui/` (Compose + Navigation: home, enroll, session, report, history,
settings, chessclock). No DI framework; DataStore for settings; monetisation gated behind
a single `Entitlements.isPro()` call site.

**Pipeline**: capture thread (URGENT_AUDIO priority) → Channel(64) → single-threaded
Default-dispatcher consumer (sherpa objects are not thread-safe): VAD
(threshold .5, minSilence .5 s, minSpeech .3 s, maxSpeech 3 s ⇒ bars update ≤~3.2 s,
plus instant "someone speaking" pulse from `isSpeechDetected()`) → embedding →
`search(emb, 0.50)` → SessionStats → StateFlow → Compose bars. Attribution rules: no
match ⇒ grey "Everyone else" bar; sub-0.8 s below-threshold segment within 0.5 s of the
previous speaker ⇒ continuation. Turn = same-speaker run with <1 s gaps. Never display
per-utterance attributions — aggregate bars only.

## Work breakdown × delegation tier

Tier legend — **OPUS** (architectural judgement/tuning), **SONNET** (standard
implementation), **HAIKU** (boilerplate/mechanical). Orchestrator = main session:
dispatches, reviews diffs, runs device tests. Every subagent packet gets: the task row,
relevant PLAN.md sections, acceptance criteria, and the escalation rule (2 failed
attempts or genuine ambiguity ⇒ STOP, return `ESCALATE: <what+why>` — never thrash).
Parallel file-touching tasks run in git worktrees.

| # | Task | Acceptance criteria | Tier |
|---|------|--------------------|------|
| T0 | Mac env: Android Studio cask, SDK 35, JAVA_HOME→Studio JBR, SDK adb over brew adb, phone USB-debug paired | hello-Compose app builds in Studio AND `./gradlew installDebug` on phone | orchestrator+Marc (needs the Mac + phone approval dialogs) |
| T1 | Project skeleton: Kotlin DSL, version catalog, AGP 8.x, min 26/target 35, NavHost + 6 stub screens, theme + 8-colour palette, edge-to-edge | navigates on device; `assembleDebug` green | HAIKU |
| T2 | Vendor AAR + models, abiFilters, `SherpaEngines` init | logcat: `vad ok, extractor dim=N`; APK size noted | SONNET |
| T3 | **Bench harness (GATE)**: debug screen logging per-chunk inference ms + same/different-speaker score distributions for 2 speakers + stranger; A/B CAM++ vs eres2net; pick model + threshold | findings note committed; go for downstream | OPUS |
| T4 | Productionise `AudioCapture` + `SpeechSegmenter` (lifecycle, no leaks) | window-slice unit test; sane segment boundaries live; silence ⇒ nothing | SONNET |
| T5 | `EnrollmentProcessor` (trim, quality gate, 3 embeddings, similar-voice warning) | quiet/noisy/good recordings accept/reject correctly | OPUS |
| T6 | Room layer + BLOB converters + queries | DAO tests pass | HAIKU |
| T7 | `SpeakerMatcher` façade over SpeakerEmbeddingManager, threshold from DataStore | bench screen shows names | SONNET |
| T8 | **`SessionEngine` + `SessionStats`** (attribution rules, continuation heuristic, turns/monologue) | pure-Kotlin unit tests: synthetic streams ⇒ exact expected stats | OPUS |
| T9 | `SessionService` mic FGS + notification + persist-on-stop | counts 10 min with screen off; no orphan notification; stats in DB | SONNET |
| T10 | Enrollment UI (list, add flow, level meter, delete) | enrol 4 people end-to-end; survives restart | SONNET |
| T11 | Session screen (mode pick, permissions→service, live bars, pulse, Stop→Report, keepScreenOn) | bars rebalance in live 3-person test; ≤3 s latency feel | SONNET |
| T12 | Me Mode + `NudgeController` (45 s default, gap/re-arm logic, vibrate) | deterministic unit test on synthetic timeline; buzz on device | SONNET |
| T13 | Report screen + share card (donut/bars, benchmark line "balanced ≈ 1/N", 1080×1350 offscreen ComposeView→Bitmap→FileProvider→share) | renders from DB; PNG opens in WhatsApp | SONNET |
| T14 | History screen (list, tap→report, swipe-delete) | works over existing DAOs | HAIKU |
| T15 | Chess-clock mode (tap-to-pass tiles → same SessionStats/Report) | 6-person manual session ⇒ normal report; no mic permission in this mode | SONNET |
| T16 | Settings (nudge, threshold dev section, delete-all, privacy blurb, licenses) | DataStore-backed, affects engine | HAIKU |
| T17 | Hardening (permission denial, mic contention/calls ⇒ pause not crash, rotation, process death ⇒ 30 s stat persist, empty states, icon) | ~10-case abuse checklist passes on device | SONNET |
| T18 | **Field test + tune** (2 real dinners Table Mode, 1 workday Me Mode, vs hand-labelled 10-min sample) | tuning constants + accuracy note committed; store-claim go/no-go | OPUS (+Marc) |

Critical path: T0→T1→T2→**T3**→T4/T5→T8→T9→T11→T18. Parallel lanes after T3:
{T6,T16} HAIKU · {T10,T12,T13,T15} SONNET once their deps land. ~15 dev-days serial,
less with lanes. T14 waits for T13; T17 last before field test.

## Delegation protocol (how the fan-out runs)

1. Orchestrator (this session) prepares a packet per task: goal, acceptance criteria,
   PLAN.md excerpts, files owned. One task = one subagent = one conventional commit.
2. `Agent` tool with `model: "haiku" | "sonnet" | "opus"` per the tier column;
   `isolation: "worktree"` when lanes touch overlapping files.
3. Every implementation subagent must run `./gradlew test` (and `assembleDebug` for
   app-facing tasks) and report the real output — no green claims without evidence.
   Orchestrator reviews the diff, runs device install (`adb install`) for UI tasks,
   then commits/merges.
4. Escalation ladder per Marc's conventions: stuck HAIKU→SONNET→OPUS→orchestrator;
   a stuck agent returns `ESCALATE:` after ≤2 failed attempts, never loops.
5. Pair each OPUS task with a SONNET review subagent on the diff before merge.

## Risks (top 4)

1. Short-chunk (1–3 s) embedding accuracy → T3 gate measures the real gap; fallback:
   maxSpeech 5 s, slower bars. 2. Children's voices may collide (adult-trained models) →
  everyone-else bucket + enrollment similarity warning + chess-clock fallback; be honest
  in listing. 3. Noisy table/overlap → absorbed by aggregate-only display. 4. Mic
  contention (incoming call) → pause gracefully (T17).

## Verification (end-to-end)

- Unit: SessionStats/NudgeController/segmenter logic (`./gradlew test`).
- Instrumented: Room DAOs (`connectedAndroidTest` on the phone).
- Device milestones: T3 bench note; T9 screen-off session; T11 live 3-person test;
  T13 WhatsApp share; T18 accuracy vs hand-labelled sample (±10 % target on shares).
- Ship gate: `aapt dump permissions` shows NO android.permission.INTERNET.
