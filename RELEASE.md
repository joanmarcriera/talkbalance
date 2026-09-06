# Releasing Talk Balance

Talk Balance ships as a signed APK. Signing inputs are read from the environment so the keystore
and its password never live in the repository.

## Signing environment variables

| Variable | Meaning |
| --- | --- |
| `TALKBALANCE_KEYSTORE_PATH` | Path to the release keystore file. |
| `TALKBALANCE_KEYSTORE_PASSWORD` | Keystore (and key) password. |
| `TALKBALANCE_KEY_ALIAS` | Key alias inside the keystore. |

When **all three** are present, `release` builds are signed. When any is absent the build is left
**unsigned on purpose** so contributors and CI without the secrets can still run `assembleRelease`
(it produces `app-release-unsigned.apk`).

## Building a distributable release — REQUIRED

A build meant for distribution must never silently ship the unsigned artifact. Always run:

```bash
export TALKBALANCE_KEYSTORE_PATH=/path/to/talkbalance.keystore
export TALKBALANCE_KEYSTORE_PASSWORD=…
export TALKBALANCE_KEY_ALIAS=talkbalance

./gradlew assembleRelease verifyReleaseSigning
```

`verifyReleaseSigning` **fails the build** if the release output is missing or if only
`app-release-unsigned.apk` was produced. It is the gate that catches "the release quietly built
unsigned" — the exact failure mode from the pre-release audits.

On the release/CI path, add `-PrequireSigning`:

```bash
./gradlew assembleRelease verifyReleaseSigning verifyReleaseArtifact -PrequireSigning
```

`-PrequireSigning` (a) refuses to configure a release build when the signing env vars are absent,
and (b) automatically runs `verifyReleaseSigning` **and** `verifyReleaseArtifact` after
`assembleRelease`.

`verifyReleaseArtifact` is the privacy gate on the artifact itself — see "Why 0.1.4 supersedes
0.1.3" below for what it caught and why the source-level test was not enough.

## Released versions

Each release is tagged so a published APK can be traced back to the source that built it — the
"read exactly what it does" claim is only checkable if you can tell *which* source.

| Version | versionCode | Tag | Source commit | Published |
| --- | --- | --- | --- | --- |
| 0.1.0 | 1 | `v0.1.0` | `701e79dc` | alpha, setup flow only |
| 0.1.1 | 2 | `v0.1.1` | `97e1641f` | — |
| 0.1.2 | 3 | `v0.1.2` | `358c81de` | F-Droid; APK SHA-256 `4429ccba…f2b8ff` |
| 0.1.3 | 4 | `v0.1.3` | `85e5f7b7` | F-Droid 2026-07-21, `talkbalance-0.1.3.apk` |
| 0.1.4 | 5 | `v0.1.4` | _pending_ | not yet published — supersedes 0.1.3 (see below) |

Tags were reconstructed from the commit that declared each `versionName` in
`app/build.gradle.kts`. The 0.1.2 mapping is independently corroborated: `358c81de` is the exact
commit the 2026-07-21 release audit reviewed, and that audit verified the published APK's SHA-256
matched both the F-Droid index and the app installed on the test phone.

**Builds are not reproducible across machines**, so recompute and record the APK SHA-256 at the
moment you publish, rather than reusing a hash from an earlier build of the same tree:

```bash
git tag -a v0.1.4 -m "Talk Balance 0.1.4 (versionCode 5)" && git push origin v0.1.4
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

### Why 0.1.4 supersedes 0.1.3

The T3 bench harness opens the microphone and appends measurements to `files/bench-t3.csv`. Its UI
entry point was `BuildConfig.DEBUG`-gated, so it is **unreachable** in the published 0.1.3 build and
no user's audio was ever written to disk — but the classes were compiled into the release APK.
That contradicts the "microphone audio is processed in memory and is never written to disk" claim
for anyone who reads the source or inspects the artifact rather than tracing reachability.

The fix landed in two passes, and the second pass is the instructive one:

1. `BenchScreen`/`BenchViewModel` moved to `src/debug/` behind a `BenchEntry` variant seam, with
   `PrivacyInvariantsTest` enforcing it.
2. That was **not sufficient**, and the source-level test said it was. `BenchMath.kt` — the
   harness's CSV line formatter and its model A/B enum — was still in `src/main`, and the 28 MB
   CAM++ model it names (the losing side of the T3 A/B, used by nothing in shipped code) was still
   a `src/main` asset. Both were still in the artifact. Opening the APK is what found them.

Hence `verifyReleaseArtifact`, which opens the built APK and asserts no bench or recording marker
appears in any dex, no bench-only asset is packaged, and INTERNET is absent while RECORD_AUDIO is
present. **Run it on every distributable build** — a source-level test cannot see a packaging
mistake:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleRelease \
    verifyReleaseSigning verifyReleaseArtifact -PrequireSigning
```

`-PrequireSigning` runs both verifications automatically after `assembleRelease`.

## Notes

- Signed output: `app/build/outputs/apk/release/app-release.apk`.
- Unsigned output (contributor/no-secrets builds only): `app-release-unsigned.apk`.
- Sessions and enrollment are **foreground-only** in this release: there is no foreground service or
  recording notification, and the microphone is released when the app leaves the foreground
  (Home / app-switch / screen lock) or the screen is left.
