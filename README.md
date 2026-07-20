# TalkerTracker / Talk Balance

Android app that sits on the dinner table, tracks how long each person talks,
and produces an end-of-conversation report: talk share, turns, balance score,
and longest monologue. The working product name is **Talk Balance**; the
repository and internal project name remain **TalkerTracker**.

Speaker diarization is designed to run fully on-device using
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Audio never leaves the
phone and sessions retain aggregate statistics only.

- Market research: `RESEARCH.md`
- Delegation-ready implementation plan: `PLAN.md`
- Durable current state and backlog: `PROJECT_STATE.md` and `TASKS.md`
- Project tracking: [Vikunja project 51](https://familia.riera.co.uk/projects/51)
- `spike/` validated the sherpa-onnx diarization approach on a reference
  four-speaker WAV: four speakers detected with a known count and at threshold
  0.9 with an unknown count; 15.7× real time on an M-series Mac.

The current Android slice provides the Compose foundation, participant/consent
flow, deterministic statistics engine, and a durable local AAC recorder. The
next gate is vendoring sherpa-onnx and benchmarking the Android inference path.

## Local build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew test assembleDebug
```

See `PROJECT_STATE.md`, `TASKS.md`, and `DECISIONS.md` for durable project state.
