# Decisions

## 2026-07-20 — ML binaries are pinned ordinary Git blobs

- **Decision:** Commit the vendored AAR and ONNX files directly, with release source
  and SHA-256 recorded in `app/libs/README.md`.
- **Alternatives:** Git LFS; download dependencies during every build.
- **Rationale:** The pinned runtime/model set is about 101 MB and must remain
  reproducible. Forgejo's authenticated LFS batch endpoint returns 404, while each
  individual asset remains below 50 MB.
- **Consequences:** Repository clones transfer the model blobs and are larger.
- **Revisit when:** Forgejo LFS is enabled, the models move to a trusted immutable
  package registry, or dynamic delivery is introduced for production.

## 2026-07-20 — Private Forgejo-primary repository

- **Decision:** Use the private `marc/talkertracker` repository on
  `git.lab.riera.co.uk` as `origin`; retain the previous Forgejo URL as the
  `legacy` remote for provenance.
- **Alternatives:** Continue using only the previous hostname; create a private
  GitHub repository.
- **Rationale:** This follows the established private-project hosting convention
  and the requested backend while preserving the existing repository history.
- **Consequences:** Push authentication currently uses the scoped Forgejo API
  token because the new hostname's SSH endpoint does not accept the existing key.
- **Revisit when:** SSH routing/key registration for `git.lab.riera.co.uk` is fixed
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

## 2026-07-20 — No LLM in v1

- **Decision:** Derive the report deterministically from diarized speaker segments.
- **Alternatives:** Generate conversational summaries or coaching with an LLM.
- **Rationale:** The desired value is participation statistics, not transcript content.
- **Consequences:** Word count is deferred because v1 does not need speech-to-text;
  lower cost and privacy exposure; qualitative coaching is deferred.
- **Revisit when:** Beta users consistently ask for content-level summaries.
