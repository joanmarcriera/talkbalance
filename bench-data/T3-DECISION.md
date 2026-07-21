# T3 gate decision — CLOSED (2026-07-21)

**Model: ERes2Net** (`3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx`, 192-dim).
**Match threshold: 0.50** (cosine).

## The experiment (reproducible, ground-truth)

Per-speaker clips were extracted from the reference 4-speaker recording used in the
desktop spike (known diarization = ground truth), then played through the Mac's
speakers at the phone on the Bench screen — a real acoustic channel (speaker → air →
phone mic), which is the production condition. Two speakers enrolled as refs A/B
(looped ~10 s refs); 10 test clips (1.3–3.4 s — deliberately short, the production
risk) in fixed order: 3× enrolled-A, 3× enrolled-B, 4× stranger. Both models, same
sequence. Raw data: `session-2026-07-21-redo.csv` + `-groundtruth.csv`.

## Results (accuracy @ threshold 0.50, n=10 per model)

| | ERes2Net | CAM++ |
|---|---|---|
| Correct verdicts | **10/10** | 4/10 |
| Same-speaker sims | 0.62–0.86 (mean 0.75) | −0.07–0.33 (mean **0.13**) |
| Diff/stranger sims | max 0.34 (mean 0.23) | max 0.27 (mean 0.14) |
| Inference / chunk | 81–175 ms | 61–108 ms |

ERes2Net separates cleanly: worst same-speaker 0.62 vs best impostor 0.34 — threshold
0.50 sits mid-gap. **CAM++ collapsed**: through the acoustic channel it matched *no*
enrolled speaker (its 4 "correct" are strangers it rejected like everything else).
This reverses the 2026-07-20 provisional choice and vindicates the review note that
the CAM++ pick had no supporting data. `SherpaEngines.PRIMARY_EMBEDDING_MODEL` now =
ERes2Net (shipped in v0.1.2); CAM++ retained as the bench A/B alternative only.

## Consistency + caveats

- Consistent with the live-voice ERes2Net signal from 2026-07-20 (0.906 vs 0.14–0.17).
- Caveats: source speech is Mandarin (VoxCeleb-trained models are language-robust, and
  the en-voxceleb ERes2Net still separated cleanly); one room, one playback device;
  2 enrolled + 2 strangers. A live family-dinner validation (T18) remains the final
  word for accuracy *claims*; this gate decides the *model*, decisively.
- Follow-up (size): CAM++ (29.3 MB) could be dropped from assets to slim the APK once
  the bench screen retires.
