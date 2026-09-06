# Anomaly: 9224 ms segments despite `maxSpeechDuration=3.0f` (T4 investigation)

## Question

`bench-data/ANALYSIS-2026-07-20.md` recorded a 9224 ms VAD segment even though
`SileroVadModelConfig.maxSpeechDuration = 3.0f`. Does `maxSpeechDuration` cap emitted segment
length, and if not, what actually bounds it?

## Source read

sherpa-onnx v1.13.4, `sherpa-onnx/csrc/voice-activity-detector.cc`,
`VoiceActivityDetector::Impl::AcceptWaveform` (and `Impl::Init`):

```cpp
void Init() {
  max_utterance_length_ = config_.sample_rate * config_.silero_vad.max_speech_duration;
}

void AcceptWaveform(const float *samples, int32_t n) {
  if (buffer_.Size() > max_utterance_length_) {
    model_->SetMinSilenceDuration(new_min_silence_duration_s_);  // 0.1 s
    model_->SetThreshold(new_threshold_);                       // 0.90
  } else {
    // restore configured min_silence_duration / threshold
  }
  ...
}
```

(`new_min_silence_duration_s_ = 0.1`, `new_threshold_ = 0.90` — private members of
`VoiceActivityDetector::Impl`.)

## Finding

**`maxSpeechDuration` does not cap emitted segment length.** It is a *soft* nudge, not a hard cut:

- `max_utterance_length_` is only ever compared against `buffer_.Size()` (the VAD's rolling
  buffer of not-yet-popped samples, roughly the in-flight utterance).
- Once that buffer exceeds the configured max-speech-duration-in-samples, the VAD temporarily
  swaps in a much shorter `min_silence_duration` (0.1 s, down from our configured 0.5 s) and a
  much higher `threshold` (0.90, up from our configured 0.5) — both are fed into
  `SileroVadModel::IsSpeech` (`sherpa-onnx/csrc/silero-vad-model.cc`), which decides per-window
  whether the running utterance has ended.
- Effect: past the 3 s mark it becomes *easier* for a short pause to end the segment, and *harder*
  for continued speech to keep registering as speech (0.90 vs 0.5 probability threshold). But if
  the speaker never produces ≥100 ms below the raised threshold — a genuinely continuous
  conversational turn, or noise keeping the probability above 0.90 — the segment keeps growing.
  There is no branch anywhere in `AcceptWaveform` that force-ends or force-splits the segment at
  `max_utterance_length_`; it only ever calls `SetMinSilenceDuration`/`SetThreshold`.
- 9224 ms is exactly consistent with this: a real ~9 s conversational turn with pauses shorter
  than the (even the *tightened*) 100 ms silence window.

`VoiceActivityDetector::Flush()` is the only other place a segment gets force-ended, and that only
fires on an explicit caller-invoked flush (e.g. end of stream), not automatically at 3 s.

## What actually bounds a segment

Nothing, in the library, short of an actual detected silence or an explicit `Flush()`/`Reset()`
call. `maxSpeechDuration` only changes how *sensitive* silence detection becomes past that point.

## Fix implemented

`SpeechSegmenter.accept()` (`app/src/main/java/uk/co/riera/talkbalance/audio/SpeechSegmenter.kt`)
does the hard cap itself: every VAD-emitted segment (`vad.front()`) is split into consecutive
sub-chunks of at most `MAX_CHUNK_MS = 3_500` ms before being handed downstream, so nothing past
`SpeechSegmenter` ever sees a chunk longer than 3.5 s regardless of how long the VAD's own
internal segment runs. Unit-tested in `SpeechSegmenterTest`
(`segmentOverMaxLengthSplitsIntoBoundedConsecutiveChunks` reproduces the exact 9224 ms case from
this bench session and asserts the 3-way split, contiguous timing, and no sample loss at the
seams).

## Consequence for T11 (live bars)

The bars can still lag up to ~3.5 s behind real speech during a long continuous turn (matches the
"≤~3.2 s" cadence PLAN.md already assumed from `maxSpeechDuration`), it's just enforced in our
code rather than by the VAD config. `maxSpeechDuration=3.0f` is still worth keeping as configured —
it does make the *tightened* threshold kick in a little earlier, which helps in most real
conversational speech with normal pauses — but it must not be relied on as a hard bound.
