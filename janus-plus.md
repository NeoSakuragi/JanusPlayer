# Janus+ — Architecture & Lessons Learned

## What Janus+ Is

OpenGL ES 3.0 video player for Japanese immersion learning. Pure GL rendering — no Android Views, no Compose. ExoPlayer renders video to a SurfaceTexture, composited as a GL quad. All UI (subtitles, dictionary, controls, library browser) rendered as textured quads.

## Target Devices

| Device | Screen | GPU | RAM | Notes |
|--------|--------|-----|-----|-------|
| Huawei AGS2-W09 tablet | 1200×1920, density 2.0 | Mali | 3GB | Primary dev device. GL context recreates 2-4x on startup. `glTexStorage3D` fails silently — use `glTexImage3D`. eglSwapInterval defaults to 2 (30fps) — must set to 1. Battery <20% throttles video to 30fps. |
| Google TV Streamer | 1920×1080 (720p render), density varies | MediaTek | 4GB | `preferredDisplayModeId` must pick highest RESOLUTION not highest refresh rate (all modes are 60fps, picking first = 480p). Can't do 1080p@60fps GL — use 720p. `Typeface.createFromAsset()` costs 80ms on this SoC — cache it. Font texture at 4096 = OOM, use 2048 with inSampleSize. |

## Texture Architecture

One `GL_TEXTURE_2D_ARRAY` for UI elements. Separate texture for video FBO.

### Layer Assignment
```
LAYER_UI     = 0   — white pixel at (0,0), glyph atlas regions
LAYER_COVERS = 1   — cover art for library
LAYER_BANNER = 2   — series banner image
LAYER_THUMB  = 3-5 — episode thumbnail atlas (rotating ring buffer)
```

### Sizing
- Tablet: 4096×4096 (fits in 3GB RAM)
- Google TV: 2048×2048 for UI array (to avoid OOM)
- Total GPU memory: layers × size² × 4 bytes. Keep under 300MB.

### White Pixel
Solid-color quads (borders, backgrounds, seekbar) sample a white pixel at UV (1/4096, 1/4096) from LAYER_UI. Must never be overwritten by covers or glyph data. Reserve (0,0)-(3,3) permanently.

## Text Rendering — GlyphAtlas

### The Problem
22K CJK characters. Baked font atlases at 48px need 4 pages at 4096 = 256MB GPU. SDF needs 11 pages. Full-screen CPU bitmap overlay needs 3.6MB upload per frame. Per-string texture caching needs too many GL uploads.

### The Solution: Dynamic Glyph Cache
1. **Scan** all text needed for a screen (titles, labels, episode names)
2. **Collect** unique codepoints (~200-300 for a typical Japanese page)
3. **Render** each glyph once via `Canvas.drawText()` into a packed atlas bitmap (1024×~256)
4. **Upload** the atlas bitmap to a region of LAYER_UI via `glTexSubImage3D`
5. **Build LUT**: `HashMap<Int, Glyph>` where each codepoint maps to UV coordinates
6. **Draw**: loop through string, lookup each codepoint in LUT, `addQuad` with those UVs

### GlyphAtlas Structure
```kotlin
data class Glyph(
    val u0: Float, val v0: Float,  // UV top-left in texture
    val u1: Float, val v1: Float,  // UV bottom-right
    val w: Float, val h: Float,    // quad size in pixels
    val advance: Float,            // horizontal cursor advance
    val ascent: Float              // vertical offset from baseline
)

// The LUT — this IS the font for this screen
val glyphs: HashMap<Int, Glyph>

// Drawing "食べる":
// 食 → glyphs[0x98DF] → Glyph(u0=0.12, v0=0.34, ..., advance=26)
//   → addQuad(cx, y-ascent, w, h, u0, v0, u1, v1)
//   → cx += advance
// べ → glyphs[0x3079] → ... → cx += advance
// る → glyphs[0x308B] → ... → cx += advance
```

### Multiple Text Sizes
Each text size needs its own GlyphAtlas (different pixel dimensions). Pack them at different Y offsets in LAYER_UI:
```
Y=0:     white pixel (4×4)
Y=100:   title glyphs (28sp)
Y=300:   body glyphs (13sp) — episode titles, synopsis
Y=600:   button glyphs (16sp)
Y=800:   small glyphs (10sp) — durations
Y=950:   settings glyphs (12sp)
Bottom:  subtitle region (dynamic, updated per cue)
```

### Glyph Padding
Each glyph cell has 2px padding around the rendered character for GL_LINEAR filtering. When drawing:
- Quad position: `cx - padding` (offset left to account for padding)
- Quad size: `glyph.w` (includes padding)
- Cursor advance: `glyph.advance` (actual character width, no padding)

## State Machine — Loading/Display Split

### Problem
Mixing data loading, texture uploads, and rendering in `draw()` causes frame drops. Canvas text rendering during draw = jank. Texture uploads during scroll = stutter.

### Solution
Every screen has TWO states:

**LoadingState** — shows spinner at 60fps (just solid quads, zero work)
- Background thread: network fetch, JPEG decode, glyph atlas build
- When ALL data ready: transition to DisplayState with pre-baked page object

**DisplayState** — pure GPU rendering, zero CPU work
- `init()`: upload pre-built bitmaps to VRAM (banner, thumbs, glyph atlas)
- `draw()`: loop through quads, `addQuad`, flush. Nothing else.
- No Canvas, no `measureText`, no `Bitmap.createBitmap`, no `glTexSubImage3D`

### Page Data Object
Everything the display state needs, pre-computed:
```kotlin
data class SeriesDisplayPage(
    val title: String,
    val synopsis: String,
    val episodes: List<Episode>,
    val fullEpisodes: List<JanusApi.Episode>,  // for playback
    val bannerBmp: Bitmap?,       // uploaded in init, then recycled
    val thumbBmp: Bitmap?,        // uploaded in init, then recycled
    val titleAtlas: GlyphAtlas,   // LUT ready, bitmap included
    val bodyAtlas: GlyphAtlas,
    val btnAtlas: GlyphAtlas,
    val smallAtlas: GlyphAtlas,
    val settAtlas: GlyphAtlas,
    val titleBmp: Bitmap,         // atlas bitmap, uploaded in init
    val bodyBmp: Bitmap,
    // ...
)
```

## Video Pipeline

### ExoPlayer → FBO → Quad
1. ExoPlayer decodes video to a `SurfaceTexture` (OES external texture)
2. **VideoBlitThread** (separate EGL context, shared textures): blits OES → FBO on new frames
3. Main thread: draws FBO texture as a regular `GL_TEXTURE_2D` quad (layer = -1)
4. No shader switches on the main thread — video quad is just another textured quad

### Why the Blit Thread
Sampling `GL_TEXTURE_EXTERNAL_OES` in the main render pass caused 5-7ms GPU stalls on the Huawei tablet. Moving the OES→FBO blit to a separate thread with a shared EGL context eliminated the stall completely. Main thread flush dropped from 6ms to 0.4ms.

### Shader
```glsl
if (vUVL.z < 0.0) {
    fragColor = texture(uTexVideo, vUVL.xy) * vColor;      // video FBO (layer -1)
} else if (vUVL.z >= 100.0) {
    fragColor = texture(uTexFont, vec3(...)) * vColor;       // baked font (legacy)
} else if (vUVL.z >= 10.0) {
    fragColor = texture(uTexEtc2, vec3(...)) * vColor;       // ETC2 compressed
} else {
    fragColor = texture(uTex, vUVL) * vColor;                // UI array (covers, glyphs, etc)
}
```

## Player State Machine

Three states: **PLAYING**, **PAUSED**, **SETTINGS**

### Touch Handling
One `handleTap()` with `when(mode)`. Each mode has its own AABB priority chain. No callbacks, no competing gesture detectors, no `rc.tappable()` in player mode.

```
PAUSED:
  1. Dict popup AABB → consume
  2. Subtitle area → nearest word
  3. Back button AABB → go back
  4. Settings button AABB → open settings
  5. Seekbar AABB → seek
  6. Outside everything → resume play
```

### D-pad Navigation (PAUSED mode)
```
Focus areas: TOP_ROW, SUBTITLE, SEEKBAR
  UP: SEEKBAR → SUBTITLE → TOP_ROW
  DOWN: TOP_ROW → SUBTITLE → SEEKBAR → resume
  LEFT/RIGHT: depends on focus area
    TOP_ROW: switch between back/settings buttons
    SUBTITLE: move word cursor
    SEEKBAR: seek ±10s
  CENTER: TOP_ROW=activate button, SUBTITLE/SEEKBAR=resume
  BACK: resume play
```

## Subtitle Rendering

### SuperSRT
Server-side MeCab segmentation with per-kanji furigana. Sent as JSON with word spans, dictionary indices, readings. Client renders using GlyphAtlas — each character is a quad.

### Furigana
Small reading text above kanji. Clamped to kanji width (horizontally compressed via Canvas.scale if wider). Rendered into the subtitle region of the glyph atlas.

### 4 Reading Modes
- PRO: kanji as-is
- ADVANCED: kanji + per-kanji furigana
- INTERMEDIATE: all hiragana with spaces
- NOVICE: romaji with spaces

## Performance Targets

| Metric | Target | Achieved |
|--------|--------|----------|
| Frame interval | 16.6ms (60fps) | ✓ on both devices |
| Draw work time | <10ms | ✓ (1-2ms with GlyphAtlas) |
| Flush time | <1ms | ✓ (0.3-0.5ms) |
| Scroll FPS | 60fps constant | ✓ (821 quads, 11ms) |
| Subtitle change cost | <3ms | ✓ (one-time per cue) |
| Text upload per frame | ≤1 | ✓ (processQueue throttle) |

## Critical Rules

1. **Never call `Typeface.createFromAsset()` per frame** — 80ms on MediaTek. Cache it.
2. **Never call `Canvas.drawText()` in `draw()`** — all text pre-rendered in loading state.
3. **Never include screen position in cache keys** — breaks on scroll.
4. **Never mix loading and rendering** — separate states.
5. **One texture, one flush** — don't switch textures mid-batch.
6. **The display state is dumb** — zero CPU work, just quads.
7. **`eglSwapInterval(1)` on every surface creation** — Huawei defaults to 2.
8. **Pick display mode by resolution, not refresh rate** — all modes are 60fps.
9. **`largeHeap=true`** in manifest — font atlas decode needs heap space.
10. **BACK key through keyQueue** — each state handles it, not Activity.

## File Structure (target)

```
GlyphAtlas.kt          — scan text, render glyphs, build LUT
HomeLoadingState.kt    — spinner + load library
HomeDisplayState.kt    — pure render home page
SeriesLoadingState.kt  — spinner + load series data + build glyph atlas
SeriesDisplayState.kt  — pure render series page
PlayerState.kt         — video player (loading is inline — video streams)
SettingsState.kt       — settings page
VideoSurface.kt        — SurfaceTexture + FBO management
VideoBlitThread.kt     — OES→FBO blit on separate EGL context
TextureArray.kt        — GL texture array wrapper
QuadBatch.kt           — batched quad rendering
ShaderProgram.kt       — vertex + fragment shaders
App.kt                 — GLSurfaceView.Renderer, state machine, input routing
MainActivity.kt        — Activity, ExoPlayer, key dispatch
```

## What Failed and Why

| Approach | Why it failed |
|----------|--------------|
| Baked 48px font atlas (4 pages at 4096) | Pixelated when scaled up. 256MB GPU for fonts. OOM on Google TV. |
| SDF fonts (32px, 11 pages at 2048) | Too many pages. Layer indices shifted, broke covers. Quality loss at 2048. |
| ScreenTextRenderer (full-screen CPU bitmap) | 3.6MB re-upload per frame when FPS counter changes. Extra flush = 51fps. |
| SubtitleBitmap (separate texture) | Extra flush cycle. `Typeface.createFromAsset()` per frame = 80ms. |
| UIAtlas slot allocator | Over-engineered. Lazy rendering during draw() = frame drops on scroll. Position in cache key = re-render on scroll. |
| Mixing loading/rendering in draw() | Frame drops every time new content appears. 150ms spikes. |

## What Works

| Approach | Why it works |
|----------|-------------|
| GlyphAtlas with LUT | Scan once, render once, upload once, draw forever. Per-character quads from HashMap lookup. |
| Loading/Display split | All CPU work in loading state. Display state = pure quads. 60fps always. |
| Video blit thread | OES→FBO off main thread. Zero shader switches in main render. |
| One texture array for everything | Covers, thumbnails, glyphs, white pixel — one bind, one flush. |
| AABB touch handling per mode | No callbacks, no competing detectors, deterministic. |
