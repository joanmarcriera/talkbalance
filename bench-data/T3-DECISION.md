# T3 gate decision (2026-07-20)

**Model: CAM++** (`wespeaker_en_voxceleb_CAM++_LM.onnx`, 512-dim).
**Match threshold: 0.50** (cosine).

## Basis
On-device bench (CPH2609), same session:
- same-speaker similarity ≈ 0.906
- different-speaker similarity ≈ 0.144 / 0.168
- inference 23–102 ms per second of audio (10–40× real-time)

A ~0.75 gap means any threshold in 0.30–0.70 separates cleanly; 0.50 sits in the
middle with wide margin on both sides and is already the app default
(`SettingsRepository.matchThreshold`). CAM++ chosen over ERes2Net as primary: same
clean separation, and it is the model already wired as `PRIMARY_EMBEDDING_MODEL`.

## Status: PROVISIONAL
n=3 scored pairs, single session, 2 speakers. Sufficient to unblock the matcher
(T7) and downstream work. Confidence tuning still wants the fuller redo (both refs
per model, ~5 min, 3+ speakers) — kept as a tuning step, not a blocker. Children's
voices remain untested (see risks in PLAN.md).
