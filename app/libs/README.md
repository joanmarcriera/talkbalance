# Vendored sherpa-onnx runtime and models

Pinned for the reproducible Android inference gate on 2026-07-20. All files are
official assets from `k2-fsa/sherpa-onnx` GitHub releases.

| File | Release | SHA-256 |
|---|---|---|
| `sherpa-onnx-1.13.4.aar` | `v1.13.4` | `03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780` |
| `silero_vad.onnx` | `asr-models` | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |
| `wespeaker_en_voxceleb_CAM++_LM.onnx` | `speaker-recongition-models` | `e197af7e9d473030cf486b3124149a19bf37014d0e4485e4c70c483b0ec10cb2` |
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | `speaker-recongition-models` | `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5` |

The three ONNX files live at the root of `app/src/main/assets/`, as required by
the Android API. The AAR is intentionally local because sherpa-onnx 1.13.4 is
not published to Maven Central.
