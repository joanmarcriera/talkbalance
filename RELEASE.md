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
./gradlew assembleRelease verifyReleaseSigning -PrequireSigning
```

`-PrequireSigning` (a) refuses to configure a release build when the signing env vars are absent,
and (b) automatically runs `verifyReleaseSigning` after `assembleRelease`.

## Notes

- Signed output: `app/build/outputs/apk/release/app-release.apk`.
- Unsigned output (contributor/no-secrets builds only): `app-release-unsigned.apk`.
- Sessions and enrollment are **foreground-only** in this release: there is no foreground service or
  recording notification, and the microphone is released when the app leaves the foreground
  (Home / app-switch / screen lock) or the screen is left.
