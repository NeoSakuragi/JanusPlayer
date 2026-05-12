# Speech-to-Text — Janus Subtitle Generation

## Stack
- **Model**: OpenAI Whisper large-v3 via stable-ts
- **GPU**: AMD Radeon RX 7800 XT via PyTorch ROCm 6.3
- **Venv**: `/data/whisper-rocm` (symlinked from `~/whisper-rocm`)
- **Cache**: HF models at `/data/.cache-hf`, Whisper models at `~/.cache/whisper` (symlinked to `/data/.cache-whisper`)

## Critical: HSA_OVERRIDE_GFX_VERSION
The 7800 XT is gfx1101 (RDNA 3). ROCm officially supports gfx1100. This env var makes it work:
```bash
export HSA_OVERRIDE_GFX_VERSION=11.0.0
```

## Running Transcription
```bash
source /data/whisper-rocm/bin/activate
HSA_OVERRIDE_GFX_VERSION=11.0.0 python3 -c "
import stable_whisper
model = stable_whisper.load_model('large-v3', device='cuda')
result = model.transcribe('audio.mp3', language='ja', vad=True)
result.to_srt_vtt('output.srt', segment_level=True, word_level=False)
"
```

## Key Parameters

| Parameter | Value | Why |
|-----------|-------|-----|
| `model` | `large-v3` | Best accuracy for Japanese |
| `language` | `ja` | Force Japanese |
| `vad` | `True` | **CRITICAL**. Without VAD, Whisper hallucinates through silence/music |

## Performance (7800 XT)
- 30s clip: ~6s (5x realtime)
- 1h40m movie: ~10-12 min per track
- 4 tracks: ~45 min total

## VAD (Voice Activity Detection)
Without VAD, Whisper produces "ご視聴ありがとうございました" on repeat through silent sections. Always enable `vad=True`.

## Models Tested

| Model | Accuracy | Speed | Notes |
|-------|----------|-------|-------|
| **stable-ts + large-v3** | Best | 6.5s/60s | Use this |
| large-v3-turbo | Worse | 5.3s/60s | Hallucinated, split words |
| kotoba-whisper-v2.2 | Worse | 2.9s/60s | Wrong words, hallucinated |
| SenseVoice-Small | Decent | 0.8s/60s | No timestamps |

## Alternative: Subtitle Edit (Windows laptop with NVIDIA)
For higher quality on noisy audio, use Subtitle Edit with faster-whisper-xxl:
- VAD + MDX23 vocal extraction + denoising
- CUDA acceleration
- AFTV code for sideloading: aftv.news/7996988

## File Locations
- Venv: `/data/whisper-rocm/`
- Whisper models: `/data/.cache-whisper/` (large-v3.pt = 3GB)
- Running Man SRTs: `/data/janus/subs/the-running-man/movie_ja{1-4}.srt`
- Running Man audio: `/data/janus/subs/the-running-man/audio_jpn{1-4}.mp3`
