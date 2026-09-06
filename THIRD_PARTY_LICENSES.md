# Third-party components

Talk Balance bundles the following components. Each is redistributed under its own
licence; Talk Balance itself is licensed under GPL-3.0-or-later (see `LICENSE`).

## Runtime / native

| Component | Version | Licence | Use |
|---|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (`app/libs/sherpa-onnx-1.13.4.aar`) | 1.13.4 | Apache-2.0 | On-device VAD, speaker embeddings, JNI runtime |
| ONNX Runtime (bundled inside the sherpa AAR) | — | MIT | Inference engine |

## Models (in `app/src/main/assets/`)

| Model | Source | Licence |
|---|---|---|
| `silero_vad.onnx` | [Silero VAD](https://github.com/snakers4/silero-vad) via sherpa-onnx | MIT |
| `wespeaker_en_voxceleb_CAM++_LM.onnx` | [WeSpeaker](https://github.com/wenet-e2e/wespeaker) / 3D-Speaker, via sherpa-onnx model zoo | Apache-2.0 |
| `3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx` | [3D-Speaker](https://github.com/modelscope/3D-Speaker), via sherpa-onnx model zoo | Apache-2.0 |

Apache-2.0 and MIT are both compatible with distribution inside a GPL-3.0-or-later
work. The pinned SHA-256 sums for these binaries are recorded in `app/libs/README.md`.

## Build / AndroidX

Jetpack Compose, Room, DataStore, Lifecycle, Navigation, Activity, Core-KTX — all
Apache-2.0 (Google). Kotlin stdlib — Apache-2.0 (JetBrains). These are fetched from
Maven at build time, not vendored.
