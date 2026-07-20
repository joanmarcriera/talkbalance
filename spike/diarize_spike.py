#!/usr/bin/env python3
"""TalkerTracker day-1 spike (Vikunja task 979).

Runs sherpa-onnx offline speaker diarization on a multi-speaker WAV and prints
per-speaker talk time — the core computation the Android app will do on-device.

Usage: .venv/bin/python diarize_spike.py <wav> <num_speakers>
       (num_speakers=0 lets the clusterer guess — the "anonymous party mode" case)
"""
import sys
import time
import wave

import numpy as np
import sherpa_onnx

wav_path, num_speakers = sys.argv[1], int(sys.argv[2])

with wave.open(wav_path) as f:
    assert f.getframerate() == 16000, "need 16 kHz mono"
    samples = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
    samples = samples.astype(np.float32) / 32768.0
    duration = len(samples) / 16000.0

config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
    segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
        pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
            model="sherpa-onnx-pyannote-segmentation-3-0/model.onnx"
        ),
    ),
    embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model="3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    ),
    clustering=sherpa_onnx.FastClusteringConfig(
        num_clusters=num_speakers, threshold=0.5
    ),
    min_duration_on=0.3,
    min_duration_off=0.5,
)
sd = sherpa_onnx.OfflineSpeakerDiarization(config)

t0 = time.time()
segments = sd.process(samples).sort_by_start_time()
elapsed = time.time() - t0

talk = {}
turns = {}
longest = {}
for seg in segments:
    d = seg.end - seg.start
    talk[seg.speaker] = talk.get(seg.speaker, 0.0) + d
    turns[seg.speaker] = turns.get(seg.speaker, 0) + 1
    longest[seg.speaker] = max(longest.get(seg.speaker, 0.0), d)

total_speech = sum(talk.values())
print(f"audio: {duration:.1f}s | processed in {elapsed:.1f}s "
      f"({duration/elapsed:.1f}x real-time) | speakers found: {len(talk)}")
print(f"total speech: {total_speech:.1f}s ({100*total_speech/duration:.0f}% of session)\n")
print("speaker  talk-time  share  turns  longest-monologue")
for spk in sorted(talk, key=talk.get, reverse=True):
    print(f"  #{spk}     {talk[spk]:6.1f}s  {100*talk[spk]/total_speech:4.0f}%  "
          f"{turns[spk]:4d}   {longest[spk]:.1f}s")

print("\nfirst 10 segments (start-end speaker):")
for seg in segments[:10]:
    print(f"  {seg.start:6.2f}-{seg.end:6.2f}  #{seg.speaker}")
