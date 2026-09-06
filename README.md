# Talk Balance

**An Android app that measures how much each person talks — entirely on your phone.**

Put your phone on the table at dinner or in a meeting. Talk Balance tracks how long
each person speaks and shows a fair-share report at the end: talk share, turns taken,
and the longest monologue. It's a friendly nudge toward more balanced conversations —
not a surveillance tool.

## Private by design

- **No internet permission.** The app declares none — you can verify it yourself under
  Android Settings → Apps → Talk Balance → Permissions. Talk Balance cannot open a
  network connection, so it does not and cannot send your audio anywhere.
- **No audio is ever stored.** Speech is processed on the phone in memory
  ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): voice-activity detection +
  speaker embeddings) and written nowhere — no recording file (verified on-device by
  inspecting the app's storage after a session, and enforced by two automated gates:
  `PrivacyInvariantsTest` on the source tree, and `verifyReleaseArtifact`, which opens the
  built APK and fails the release if any recording or measurement code, any bench-only
  asset, or the INTERNET permission made it in). No cloud, no account, no per-use cost.
- **What *is* kept on the phone.** If you enrol people, Talk Balance stores their names,
  their voiceprints (numeric embeddings, not audio) and per-session talk statistics in an
  app-private database. That data is not encrypted beyond Android's own full-disk
  encryption and app sandboxing, so it is readable by anyone who unlocks your phone or by
  a root/forensic tool. Backups and device-to-device transfer are switched off, and
  **Settings → Delete all data** removes all of it and tells you honestly whether every
  store was cleared. Discover mode needs no enrolment at all and stores no voiceprints.
- **Scope of the claim.** These statements are about Talk Balance itself. No app can make
  promises about the rest of your device — the OS, another app with the microphone, an
  accessibility service or screen sharing.

## Install

**Via F-Droid (recommended — you get auto-updates):** add this repository to the
[F-Droid](https://f-droid.org/) app (or Droid-ify / Neo Store):

```
https://fdroid.joanmarcriera.es/repo
```

Or scan the QR / tap "Add to F-Droid" at **https://fdroid.joanmarcriera.es**.

**Direct APK:** download the latest build from the same page. Requires Android 10+.

> **Status: early access (v0.1.4).** Enrolment, the live session (coloured bars,
> current-speaker highlight, running leader), the report and the saved-session history
> all work on-device. It's young and still being tuned for real-world accuracy — built
> in the open, expect rapid iteration.

## How it works

1. **Enrol** each person once (a short voice sample, stored only on the phone).
2. **Run a session** — the phone listens, detects speech, matches each utterance to an
   enrolled speaker on-device, and fills a live bar per person.
3. **Read the report** — talk share, turns, longest monologue; share it as an image if
   you like.
4. **Look back** — finished sessions are saved under **Past sessions** on the home
   screen. Statistics only: the talk-time numbers, never any audio.

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
