# T3 gate decision (2026-07-20)

**Model: CAM++** (`wespeaker_en_voxceleb_CAM++_LM.onnx`, 512-dim).
**Match threshold: 0.50** (cosine).

## Basis (honest about what the data does and does NOT show)
On-device bench (CPH2609), single session, **ERes2Net epoch only** — the CAM++ epoch
with ref A was lost to an app restart (see ANALYSIS-2026-07-20.md):
- same-speaker similarity ≈ 0.906
- different-speaker similarity ≈ 0.144 / 0.168  (n = 3 scored pairs)
- inference 23–102 ms per second of audio (10–40× real-time)

The ~0.75 gap shows a threshold near 0.50 separates cleanly **for ERes2Net**. There is
**no surviving CAM++ comparison** — do not claim CAM++ was measured to separate equally.

## Decision (provisional)
- **Threshold 0.50** — a provisional hypothesis, well-supported for ERes2Net, adopted as
  the app default. NOT a completed A/B gate.
- **Model: CAM++** chosen as primary on non-experimental grounds only: it's the model
  already wired as `PRIMARY_EMBEDDING_MODEL` and initialised cleanly on device. This is a
  default, not an experimental winner over ERes2Net.

## Still required before treating the gate as CLOSED
The documented redo: both models, ≥2 speakers + a stranger, **short 1–3 s utterances**
(the actual production risk), model id persisted per row, sample counts reported. Only
then pick a model on evidence. Children's voices remain untested (see risks in PLAN.md).
Enough to unblock T5/T7 build, NOT enough to make public accuracy claims.
