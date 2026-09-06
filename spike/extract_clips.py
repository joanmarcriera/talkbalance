"""T3 redo: extract per-speaker clips from the reference 4-speaker WAV.
Enrolled: spk0 + spk3 (most material). Strangers: spk1 + spk2.
Refs are looped to >=9.5s (ref capture grabs ~8s raw). Tests are single segments."""
import wave, numpy as np, sherpa_onnx, os

with wave.open("0-four-speakers-zh.wav") as f:
    sr = f.getframerate()
    samples = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32)/32768.0

config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
    segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
        pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
            model="sherpa-onnx-pyannote-segmentation-3-0/model.onnx")),
    embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model="3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"),
    clustering=sherpa_onnx.FastClusteringConfig(num_clusters=4, threshold=0.5),
    min_duration_on=0.3, min_duration_off=0.5)
sd = sherpa_onnx.OfflineSpeakerDiarization(config)
segs = sd.process(samples).sort_by_start_time()

def write_wav(path, audio):
    with wave.open(path, "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(audio,-1,1)*32767).astype(np.int16).tobytes())

os.makedirs("t3clips", exist_ok=True)
per = {}
for s in segs:
    per.setdefault(s.speaker, []).append(samples[int(s.start*sr):int(s.end*sr)])

manifest = []
for spk, chunks in sorted(per.items()):
    cat = np.concatenate(chunks)
    ref = cat.copy()
    while len(ref) < int(9.5*sr):
        ref = np.concatenate([ref, cat])
    write_wav(f"t3clips/ref_spk{spk}.wav", ref[:int(10*sr)])
    for i, c in enumerate(chunks):
        if len(c) >= int(1.0*sr):
            clip = c[:int(3.4*sr)]
            name = f"t3clips/test_spk{spk}_{i}.wav"
            write_wav(name, clip)
            manifest.append((name, spk, round(len(clip)/sr,2)))
print("refs + tests written")
for m in manifest: print(m)
