# Talk Balance

**An Android app that measures how much each person talks — entirely on your phone.**

Put your phone on the table at dinner or in a meeting. Talk Balance tracks how long
each person speaks and shows a fair-share report at the end: talk share, turns taken,
and the longest monologue. It's a friendly nudge toward more balanced conversations —
not a surveillance tool.

## Private by design

- **No internet permission.** The app declares none — you can verify it yourself under
  Android Settings → Apps → Talk Balance → Permissions. It literally cannot upload
  anything: nothing you say ever leaves the phone.
- **No audio is ever stored.** Speech is processed on the phone in memory
  ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): voice-activity detection +
  speaker embeddings) and written nowhere — no recording file (verified on-device by
  inspecting the app's storage after a session). No cloud, no account, no per-use cost.

## Install

**Via F-Droid (recommended — you get auto-updates):** add this repository to the
[F-Droid](https://f-droid.org/) app (or Droid-ify / Neo Store):

```
https://fdroid.joanmarcriera.es/repo
```

Or scan the QR / tap "Add to F-Droid" at **https://fdroid.joanmarcriera.es**.

**Direct APK:** download the latest build from the same page. Requires Android 10+.

> **Status: early access (v0.1.1).** Enrolment, the live session (coloured bars,
> current-speaker highlight, running leader) and the report all work on-device. It's
> young and still being tuned for real-world accuracy — built in the open, expect rapid
> iteration.

## How it works

1. **Enrol** each person once (a short voice sample, stored only on the phone).
2. **Run a session** — the phone listens, detects speech, matches each utterance to an
   enrolled speaker on-device, and fills a live bar per person.
3. **Read the report** — talk share, turns, longest monologue; share it as an image if
   you like.

A manual chess-clock mode (tap-to-pass, no audio) is planned for larger or noisier
groups where automatic recognition is harder.

## Build from source

Requires the Android SDK (platform 35+) and a JDK 17+ (Android Studio's bundled JBR
works). Then:

```bash
./gradlew test assembleDebug
```

Release builds are signed from a keystore supplied via environment variables; without
them the release build is simply left unsigned. See [`RELEASE.md`](RELEASE.md).

## Tech

Kotlin · Jetpack Compose · Room · [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(on-device VAD + speaker embeddings) · fully offline · minSdk 29 / target 36.

## Licence

[GPL-3.0-or-later](LICENSE). Bundled models and the sherpa-onnx runtime are Apache-2.0 /
MIT — see [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

Built by [Joan Marc Riera](https://joanmarcriera.es).
