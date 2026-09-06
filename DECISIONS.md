# Decisions

## 2026-07-20 — ML binaries are pinned ordinary Git blobs

- **Decision:** Commit the vendored AAR and ONNX files directly, with release source
  and SHA-256 recorded in `app/libs/README.md`.
- **Alternatives:** Git LFS; download dependencies during every build.
- **Rationale:** The pinned runtime/model set is about 101 MB and must remain
  reproducible. the private Git server's authenticated LFS batch endpoint returns 404, while each
  individual asset remains below 50 MB.
- **Consequences:** Repository clones transfer the model blobs and are larger.
- **Revisit when:** the private Git server LFS is enabled, the models move to a trusted immutable
  package registry, or dynamic delivery is introduced for production.

## 2026-07-20 — Private the private Git server-primary repository

- **Decision:** Use the private `marc/talkertracker` repository on
  the private Git server as `origin`; retain the previous remote URL as the
  `legacy` remote for provenance.
- **Alternatives:** Continue using only the previous hostname; create a private
  GitHub repository.
- **Rationale:** This follows the established private-project hosting convention
  and the requested backend while preserving the existing repository history.
- **Consequences:** Push authentication currently uses the scoped the private Git server API
  token because the new hostname's SSH endpoint does not accept the existing key.
- **Revisit when:** SSH routing/key registration for the private Git server is fixed
  or an off-site push mirror is required.

## 2026-07-20 — Android-first closed beta

- **Decision:** Build a native Kotlin/Compose Android application targeting API 36.
- **Alternatives:** Cross-platform framework; web application.
- **Rationale:** Reliable foreground microphone recording and screen-lock behaviour
  are central to the product and best controlled natively.
- **Consequences:** iOS is deferred; Android-specific recording behaviour must be tested.
- **Revisit when:** The Android beta validates repeat demand.

## 2026-07-20 — Fully on-device diarization

- **Decision:** Use sherpa-onnx VAD, embeddings, and speaker matching entirely
  on the Android device; declare no Internet permission.
- **Alternatives:** EU cloud diarization; live cloud processing.
- **Rationale:** The repository's spike demonstrated viable four-speaker
  diarization and the strongest user-feedback theme is privacy. Avoiding a
  backend also removes processing cost, retention risk, and account setup.
- **Consequences:** APK assets are larger and inference accuracy/performance must
  be gated on representative phones before downstream UI work.
- **Revisit when:** The Android benchmark fails its accuracy or thermal gate on
  the supported device range.

## 2026-07-20 — Parallel-session convergence: this tree is the base; ship name "Talk Balance"

- **Decision:** Marc chose (in the Claude Code session, 2026-07-20 evening) to adopt this
  scaffold as the single base going forward, and confirmed the shipping product name
  **Talk Balance** (package `uk.co.riera.talkbalance`). The Claude Code session's
  independent T0 bootstrap is preserved on branch `t0-bootstrap` (provenance only).
- **Context:** Two AI sessions worked the plan concurrently and both pushed. The Claude
  Code session (Marc's Mac, has the physical phone) verified this tree on hardware:
  `test assembleDebug` green, installed on OnePlus CPH2609 (API 36, adb 763132c9),
  MainActivity resumed, aapt confirms no INTERNET permission.
- **Coordination rule from now on:** work happens on feature branches, merged to `main`
  only after device verification by the session that has the phone. Check
  PROJECT_STATE.md "Current objective" ownership before starting work.
- **Revisit when:** the second session's identity/configuration is confirmed by Marc.

## 2026-07-20 — No LLM in v1

- **Decision:** Derive the report deterministically from diarized speaker segments.
- **Alternatives:** Generate conversational summaries or coaching with an LLM.
- **Rationale:** The desired value is participation statistics, not transcript content.
- **Consequences:** Word count is deferred because v1 does not need speech-to-text;
  lower cost and privacy exposure; qualitative coaching is deferred.
- **Revisit when:** Beta users consistently ask for content-level summaries.

## 2026-09-05 — Voice-derived data stays plaintext in v1; the copy says so

- **Decision:** Keep enrolled names, speaker embeddings and per-session statistics in the
  ordinary app-private Room database, with no application-layer (Keystore-backed)
  encryption, and disclose that plainly in the README and the in-app Privacy section
  rather than implying it is protected.
- **Alternatives:** Keystore-wrapped key encrypting the embedding BLOBs (enabling
  crypto-erasure); SQLCipher for the whole database; storing no voiceprints at all.
- **Rationale:** The threat model this product actually addresses is *off-device*
  exposure — no Internet permission, no audio to disk, no cloud, backups and device
  transfer disabled. The threat it does **not** address is an attacker who already has an
  unlocked phone or root/forensic access; application-layer encryption barely helps there,
  because the app must be able to decrypt the embeddings itself to run a session, and the
  key would live in the same Keystore the same attacker can drive. Adding SQLCipher or a
  Keystore wrapper now would buy little real protection while adding a schema migration
  and a key-loss failure mode to a pre-1.0 app. Being explicit in the copy is the honest
  and more useful mitigation, and Discover mode — now the default — stores no voiceprints
  at all, so most users never create this data.
- **Consequences:** A user who unlocks the phone (or a forensic tool) can read names,
  embeddings and statistics. The privacy claim is therefore scoped to "nothing leaves the
  device", never to "nobody with your phone can read it". "Delete all data" is the
  supported erasure path and reports per-store success or failure honestly.
- **Revisit when:** embeddings are exported/shared off-device in any feature, sessions
  start retaining transcripts or content, or a user population with a stolen-device threat
  model (journalists, clinicians, safeguarding) becomes a target — then Keystore-backed
  encryption of the embedding BLOBs plus crypto-erasure is the first step.
