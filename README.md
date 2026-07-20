# TalkerTracker

Android app that sits on the dinner table, tracks how long each person talks
(fully on-device via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
speaker diarization / speaker ID) and produces an end-of-dinner report:
talk share, turns, longest monologue.

- Market research + build/monetisation plan: see Vikunja project 51
  (https://familia.riera.co.uk/projects/51) — validated 2026-07-20: no live
  Android competitor.
- `spike/` — day-1 go/no-go: sherpa-onnx pyannote segmentation + eres2net
  embeddings on a reference 4-speaker WAV. Result: GO (4/4 speakers with known
  count; 4/4 at clustering threshold 0.9 with unknown count; 15.7x real-time
  on an M-series Mac). See `spike/diarize_spike.py` header for usage.

Audio never leaves the phone; sessions store stats only.
