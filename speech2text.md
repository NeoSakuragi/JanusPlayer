# Speech-to-Text Setup — Janus Subtitle Generation

## Stack
- **Model**: OpenAI Whisper large-v3
- **Wrapper**: stable-ts (adds improved timestamps + Silero VAD)
- **GPU**: AMD Radeon RX 7800 XT via PyTorch ROCm 6.3
- **Venv**: `/data/whisper-rocm` (symlinked from `~/whisper-rocm`)
- **Cache**: HuggingFace models at `/data/.cache-hf`, Whisper models at `~/.cache/whisper` (symlinked to `/data/.cache-whisper`)

## Critical: HSA_OVERRIDE_GFX_VERSION
The 7800 XT is gfx1101 (RDNA 3, Navi 32). ROCm officially supports gfx1100 (7900 XT/XTX). This env var makes it work:
```bash
export HSA_OVERRIDE_GFX_VERSION=11.0.0
```
Without this, PyTorch won't see the GPU.

## Running Transcription

### Quick test
```bash
source /data/whisper-rocm/bin/activate
HSA_OVERRIDE_GFX_VERSION=11.0.0 python3 -c "
import stable_whisper
model = stable_whisper.load_model('large-v3', device='cuda')
result = model.transcribe('audio.mp3', language='ja', vad=True)
result.to_srt_vtt('output.srt', segment_level=True, word_level=False)
"
```

### Batch transcription (The Running Man example)
```bash
source /data/whisper-rocm/bin/activate
HSA_OVERRIDE_GFX_VERSION=11.0.0 python3 /data/janus/subs/the-running-man/transcribe_all.py
```

## Key Parameters

| Parameter | Value | Why |
|-----------|-------|-----|
| `model` | `large-v3` | Best accuracy for Japanese. Turbo is faster but worse on JP |
| `language` | `ja` | Force Japanese — don't let it auto-detect |
| `vad` | `True` | **CRITICAL**. Without VAD, Whisper hallucinates "ご視聴ありがとうございました" through silence/music |
| `segment_level` | `True` | One SRT cue per sentence |
| `word_level` | `False` | No per-word highlighting in SRT output |

## Performance (7800 XT)
- 30s clip: ~6s (5x realtime)
- 1h40m movie: ~10-12 min per track
- 4 tracks total: ~45 min
- Model load: ~5s (cached), ~30s (first download of 3GB)

## VAD (Voice Activity Detection)
stable-ts uses Silero VAD to detect speech segments before sending to Whisper. Without it:
- Whisper processes silence/music and hallucinates repetitive text
- Common hallucination: "ご視聴ありがとうございました" (thank you for watching) on loop
- Also: "字幕翻訳", "提供", random timestamps

## Models Tested (Maison Ikkoku benchmark, 60s clip)

| Model | Accuracy | Speed | Notes |
|-------|----------|-------|-------|
| **stable-ts + large-v3** | Best | 6.5s/60s | Clear winner. Use this |
| large-v3-turbo | Worse | 5.3s/60s | Hallucinated silence, split words |
| kotoba-whisper-v2.2 | Worse | 2.9s/60s | Hallucinated, wrong words |
| SenseVoice-Small | Decent | 0.8s/60s | No timestamps, one big block |
| Qwen3-ASR-1.7B | Untested | — | Too new for current transformers |

## Known Limitations
- **Proper nouns**: Character names often wrong (音無響子 → おとなし京子)
- **Overlapping dialogue**: Accuracy drops significantly when multiple people talk
- **Stutters**: Whisper drops stutters (もっ もう → もう) — acceptable for immersion
- **Long sentences**: Some segments could be shorter. stable-ts `max_word_duration` or post-processing could help
- **Music/SFX**: VAD handles most of it, but loud SFX during speech can still cause issues

## Sentence Length Tuning (not yet applied)
If segments are too long, stable-ts supports:
```python
result = model.transcribe(audio, language='ja', vad=True)
result.split_by_punctuation(['.', '。', '！', '？', '!', '?'])
# or
result.split_by_length(max_chars=40)
```

## Alternative: Subtitle Edit (Windows laptop)
For higher quality, use Subtitle Edit with faster-whisper-xxl on the NVIDIA laptop:
- VAD + MDX23 vocal extraction + denoising built-in
- CUDA acceleration
- Better on noisy audio (overlapping dialogue, background music)
- MP3 tracks are on the NAS at `Films VJ/audio_jpn{1-4}.mp3`

## Audio Extraction (from MKV source)
```bash
# Extract all Japanese audio tracks as MP3 for Whisper
INPUT='source.mkv'
for i in 3 4 5 6; do
  ffmpeg -y -i "$INPUT" -map 0:$i -c:a mp3 -b:a 128k -ac 1 audio_jpn$((i-2)).mp3
done
```
Stream indices depend on the MKV — check with `ffprobe` first.

## File Locations
- Venv: `/data/whisper-rocm/`
- Whisper models: `/data/.cache-whisper/` (large-v3.pt = 3GB)
- HF models: `/data/.cache-hf/`
- Running Man SRTs: `/data/janus/subs/the-running-man/movie_ja{1-4}.srt`
- Running Man audio: `/data/janus/subs/the-running-man/audio_jpn{1-4}.mp3`
- Transcription script: `/data/janus/subs/the-running-man/transcribe_all.py`

## The Running Man Results
| Track | Segments | File size | Duration |
|-------|----------|-----------|----------|
| jpn1 | 670 | 61K | 1h41m |
| jpn2 | 820 | 68K | 1h41m |
| jpn3 | 738 | 60K | 1h41m |
| jpn4 | 793 | 67K | 1h41m |

Different segment counts across tracks confirms they are genuinely different dubs with different dialogue timing.
