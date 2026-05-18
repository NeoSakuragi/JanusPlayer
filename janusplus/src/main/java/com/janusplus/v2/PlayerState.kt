package com.janusplus.v2

import android.opengl.GLES30
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.SrtParser
import com.janusplus.SubtitleBitmap
import com.janusplus.VideoBlitThread
import com.janusplus.toCodePoints
import kotlin.concurrent.thread

/**
 * Video player GameState.
 * ExoPlayer renders into a SurfaceTexture -> GL quad.
 * All UI (controls, subtitles, dictionary) drawn as GL quads on top.
 */
class PlayerState(
    private val item: JanusApi.LibraryItem,
    private val episode: JanusApi.Episode,
    private val baseUrl: String,
) : GameState {

    // ── Player sub-states ──
    enum class Mode { PLAYING, PAUSED, SETTINGS }
    enum class ReadingMode { PRO, ADVANCED, INTERMEDIATE, NOVICE }

    @Volatile var mode = Mode.PLAYING
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var isPlaying = true

    // Debug
    var debugBoxes = false

    // Theme: false = dark (white text, dark shade), true = e-ink (black text, white shade)
    var einkMode = false

    // Reading mode
    var readingMode = ReadingMode.PRO

    // Typography deltas
    var deltaFurigana = 0.7f   // DF: furigana distance above kanji (fraction of line height)
    var deltaRow = 1.4f        // DR: row spacing multiplier
    var deltaSpacing = 0f      // DS: letter spacing in dp
    var deltaYShift = 0f       // DY: y offset in dp
    var subFontSize = 32       // base subtitle font size in sp

    // Subtitles (basic SRT fallback)
    @Volatile var cues: List<SrtParser.Cue> = emptyList()
    private var currentCue: SrtParser.Cue? = null
    private var currentCueText = ""

    // SuperSRT (rich subtitles with dictionary)
    @Volatile var superSRT: JanusApi.SuperSRT? = null
    @Volatile var superCues: List<JanusApi.SuperCue> = emptyList()
    private var currentSuperCue: JanusApi.SuperCue? = null

    // Word navigation
    data class WordSpan(val start: Int, val end: Int, val surface: String,
                        val dictIdx: Int, val inflection: String,
                        val reading: String, val furigana: List<JanusApi.FuriganaSpan>)
    private var wordSpans = listOf<WordSpan>()
    private var cursorIdx = 0
    private var hlStart = -1
    private var hlEnd = -1

    // Character bounding boxes (screen space) for tap detection
    private var charBoxes = listOf<CharBox>()
    data class CharBox(val x: Float, val y: Float, val w: Float, val h: Float, val charIdx: Int)

    // Cached cue layout — rebuilt only when cue text, reading mode, or typography changes
    private var cachedCueText = ""
    private var cachedReadingMode = ReadingMode.PRO
    private var cachedDF = 0f; private var cachedDR = 0f; private var cachedDS = 0f; private var cachedDY = 0f
    private var cachedCueLayout: CueLayout? = null

    data class CueCharDraw(val ch: String, val x: Float, val y: Float)
    data class CueFuriDraw(val text: String, val x: Float, val y: Float, val size: Int, val scaleX: Float, val displayW: Float)
    data class CueLayout(
        val chars: List<CueCharDraw>,
        val furis: List<CueFuriDraw>,
        val boxes: List<CharBox>,
        val shadeRect: FloatArray,
        val lineInfos: List<Triple<String, Float, Int>>,  // text, lineY, globalStart — for highlight
        val fontSize: Int,
        val lineH: Float,
        val furiH: Float,
        val furiAscent: Float,
        val ascent: Float
    )

    // Controls
    private var controlsTimer = 0f

    // Double-tap detection
    private var lastTapTime = 0L
    private var lastTapX = 0f

    // Seekbar drag
    private var isDraggingSeekbar = false

    // UI bounding boxes (set in draw, checked in handleTap)
    private var backBtnRect = floatArrayOf(0f, 0f, 0f, 0f)     // x, y, w, h
    private var settingsBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var seekbarRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var prevCueRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var nextCueRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var dictPopupRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var dictPopupVisible = false
    private var subtitleRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var prevCueVisible = false
    private var nextCueVisible = false

    private var startTime = System.nanoTime()

    // Layout
    private var pad = 0f
    private var barH = 0f
    private var barY = 0f
    private var screenW = 0f
    private var screenH = 0f
    private var layoutDone = false

    // Settings panel
    private var settingsFocus = 0
    private var settingsRows = listOf<SettingsRow>()
    private var settingsScrollY = 0f
    private var settingsDragY = 0f
    private var settingsDragging = false

    data class SettingsRow(val label: String, val value: String, val icon: String,
                           val indent: Boolean = false, val selected: Boolean = false,
                           val isSlider: Boolean = false,
                           val sliderRange: Pair<Float, Float> = 0f to 1f,
                           val sliderValue: Float = 0f,
                           val onSlide: ((Float) -> Unit)? = null,
                           val action: () -> Unit = {})
    private var settingsRowRects = listOf<FloatArray>()
    private var settingsSliderRects = mutableMapOf<Int, FloatArray>()
    private var settingsPanelX = 0f
    private var draggingSliderIdx = -1

    // Condensed mode
    var condensedMode = false

    // Track selection
    private var selectedAudioIdx = 0
    private var selectedSubLang = "ja"

    // PAUSED mode focus
    enum class PausedFocus { TOP_ROW, SUBTITLE, SEEKBAR }
    private var pausedFocus = PausedFocus.SUBTITLE
    private var topRowFocus = 0 // 0=back, 1=settings

    // Font selection
    var currentFontIdx = 0
    val fontNames = listOf("Noto Sans", "Noto Serif", "Shippori")
    private val fontAssets = listOf("fonts/NotoSansJP-Regular.ttf", "fonts/NotoSerifJP-Regular.ttf", "fonts/ShipporiMincho-Regular.ttf")

    // CPU-rendered subtitle texture
    private val subtitleBmp = SubtitleBitmap()

    private fun invalidateSubtitle() { subtitleBmp.lastText = "" }

    private fun savePrefs() {
        val app = appRef ?: return
        app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putFloat("df", deltaFurigana)
            .putFloat("dr", deltaRow)
            .putFloat("ds", deltaSpacing)
            .putFloat("dy", deltaYShift)
            .putInt("font_size", subFontSize)
            .putInt("reading_mode", readingMode.ordinal)
            .putBoolean("condensed", condensedMode)
            .putInt("font_idx", currentFontIdx)
            .putBoolean("debug_boxes", debugBoxes)
            .putBoolean("eink_mode", einkMode)
            .apply()
    }

    override fun init(app: App) {
        appRef = app
        mode = Mode.PLAYING
        controlsTimer = 0f

        // Load persisted settings
        val prefs = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        deltaFurigana = prefs.getFloat("df", 0.7f)
        deltaRow = prefs.getFloat("dr", 1.4f)
        deltaSpacing = prefs.getFloat("ds", 0f)
        deltaYShift = prefs.getFloat("dy", 0f)
        subFontSize = prefs.getInt("font_size", 32)
        readingMode = ReadingMode.entries.getOrNull(prefs.getInt("reading_mode", 3)) ?: ReadingMode.PRO
        condensedMode = prefs.getBoolean("condensed", false)
        currentFontIdx = prefs.getInt("font_idx", 0)
        debugBoxes = prefs.getBoolean("debug_boxes", false)
        einkMode = prefs.getBoolean("eink_mode", false)
        app.einkMode = einkMode

        val api = app.api ?: return
        val videoUrl = "$baseUrl/api/video/${item.id}/${episode.filename}"
        val token = api.token ?: ""

        // Start playback on main thread (ExoPlayer requires it)
        app.onMainThread?.invoke(Runnable {
            val player = app.exoPlayer ?: return@Runnable
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))
            player.setVideoSurface(app.videoSurface.surface)
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true

            // Position + video size polling
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val poller = object : Runnable {
                override fun run() {
                    if (!alive || app.exoPlayer == null) return
                    if (!isDraggingSeekbar) positionMs = player.currentPosition
                    durationMs = player.duration.coerceAtLeast(0)
                    isPlaying = player.isPlaying
                    isBuffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    val format = player.videoFormat
                    if (format != null && videoWidth == 0) {
                        videoWidth = format.width
                        videoHeight = format.height
                    }
                    handler.postDelayed(this, if (isBuffering) 50 else 200)
                }
            }
            handler.postDelayed(poller, 200)
        })

        // Load subtitles (basic SRT as fallback)
        if (episode.subtitles.isNotEmpty()) {
            val jaTrack = episode.subtitles.firstOrNull { it.language == "ja" }
                ?: episode.subtitles.first()
            thread {
                try {
                    val srtUrl = "$baseUrl/api/subs/${item.id}/${jaTrack.srtFile}"
                    val request = okhttp3.Request.Builder().url(srtUrl)
                        .header("Authorization", "Bearer $token").build()
                    val client = okhttp3.OkHttpClient()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: ""
                        cues = SrtParser.parse(text)
                    }
                    response.close()
                } catch (_: Exception) {}
            }
        }

        // Load SuperSRT (rich subtitle data with dictionary)
        thread {
            try {
                val data = api.fetchSuperSRT(item.id, episode.season, episode.episode)
                if (data != null) {
                    superSRT = data
                    superCues = data.cues
                }
            } catch (_: Exception) {}
        }
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(24f)
        barH = rc.dp(48f)
        barY = rc.h - barH - rc.dp(24f)
        screenW = rc.w
        screenH = rc.h
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        val t0 = System.nanoTime()
        if (app.videoSurface.updateTexture()) {
            firstFrameReceived = true
        }
        dbgUpdate = dbgUpdate * 0.9f + (System.nanoTime() - t0) / 1_000_000f * 0.1f

        // Find current cue — only while playing (don't wipe selection while paused)
        if (mode == Mode.PLAYING) {
            val pos = positionMs
            if (superCues.isNotEmpty()) {
                val sCue = superCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
                if (sCue !== currentSuperCue) {
                    currentSuperCue = sCue
                    if (sCue != null) {
                        buildWordSpans(sCue)
                    } else {
                        currentCueText = ""
                        wordSpans = emptyList()
                    }
                }
            } else {
                val cue = cues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
                if (cue !== currentCue) {
                    currentCue = cue
                    currentCueText = cue?.text ?: ""
                    wordSpans = emptyList()
                }
            }
        }

        // Touch handling — all actions (DOWN=0, MOVE=2, UP=1)
        for (t in touches) {
            when (t.action) {
                0 -> { // ACTION_DOWN
                    if (mode == Mode.PAUSED && aabbHit(t.x, t.y, seekbarRect)) {
                        isDraggingSeekbar = true
                        val progress = ((t.x - seekbarRect[0]) / seekbarRect[2]).coerceIn(0f, 1f)
                        seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    } else if (mode == Mode.SETTINGS) {
                        // Check slider hit first
                        draggingSliderIdx = -1
                        for ((idx, rect) in settingsSliderRects) {
                            if (aabbHit(t.x, t.y, rect)) {
                                draggingSliderIdx = idx
                                applySliderDrag(idx, t.x, rect)
                                break
                            }
                        }
                        if (draggingSliderIdx < 0) {
                            settingsDragY = t.y
                            settingsDragging = false
                        }
                    }
                }
                2 -> { // ACTION_MOVE
                    if (isDraggingSeekbar && screenW > 0) {
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        positionMs = (durationMs * progress).toLong().coerceIn(0, durationMs)
                    } else if (draggingSliderIdx >= 0) {
                        val rect = settingsSliderRects[draggingSliderIdx]
                        if (rect != null) applySliderDrag(draggingSliderIdx, t.x, rect)
                    } else if (mode == Mode.SETTINGS) {
                        val dy = settingsDragY - t.y
                        if (!settingsDragging && Math.abs(dy) > 8f) settingsDragging = true
                        if (settingsDragging) {
                            settingsScrollY = (settingsScrollY + dy).coerceAtLeast(0f)
                            settingsDragY = t.y
                        }
                    }
                }
                1 -> { // ACTION_UP
                    if (isDraggingSeekbar) {
                        isDraggingSeekbar = false
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    } else if (draggingSliderIdx >= 0) {
                        draggingSliderIdx = -1
                        invalidateSubtitle()
                        buildSettingsRows()
                    } else if (settingsDragging) {
                        settingsDragging = false
                    } else {
                        handleTap(app, t.x, t.y)
                    }
                }
            }
        }

        // D-pad handling
        for (key in keys) {
            handleKey(app, key)
        }
    }

    private fun buildWordSpans(sCue: JanusApi.SuperCue) {
        val display = StringBuilder()
        val spans = mutableListOf<WordSpan>()
        for (w in sCue.words) {
            if (w.surface.isBlank() || w.surface == "\n") {
                display.append(w.surface)
                continue
            }
            val start = display.length
            display.append(w.surface)
            spans.add(WordSpan(start, display.length, w.surface, w.dictIdx, w.inflection, w.reading, w.furigana))
        }
        currentCueText = display.toString()
        wordSpans = spans
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleKey(app: App, keyCode: Int) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                when (mode) {
                    Mode.PLAYING -> { pause(); enterPaused() }
                    Mode.PAUSED -> when (pausedFocus) {
                        PausedFocus.TOP_ROW -> {
                            if (topRowFocus == 0) { cleanup(app); app.goBack() }
                            else openSettings()
                        }
                        PausedFocus.SUBTITLE -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                        PausedFocus.SEEKBAR -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                    }
                    Mode.SETTINGS -> { handleSettingsSelect() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.PAUSED -> when (pausedFocus) {
                        PausedFocus.TOP_ROW -> topRowFocus = 0
                        PausedFocus.SUBTITLE -> moveCursor(-1)
                        PausedFocus.SEEKBAR -> seekRelative(-10000)
                    }
                    Mode.SETTINGS -> { handleSettingsLeft() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.PAUSED -> when (pausedFocus) {
                        PausedFocus.TOP_ROW -> topRowFocus = 1
                        PausedFocus.SUBTITLE -> moveCursor(1)
                        PausedFocus.SEEKBAR -> seekRelative(10000)
                    }
                    Mode.SETTINGS -> { handleSettingsRight() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                when (mode) {
                    Mode.PLAYING -> { pause(); enterPaused() }
                    Mode.PAUSED -> when (pausedFocus) {
                        PausedFocus.TOP_ROW -> { /* already at top */ }
                        PausedFocus.SUBTITLE -> pausedFocus = PausedFocus.TOP_ROW
                        PausedFocus.SEEKBAR -> pausedFocus = PausedFocus.SUBTITLE
                    }
                    Mode.SETTINGS -> { settingsFocus = (settingsFocus - 1).coerceAtLeast(0) }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (mode) {
                    Mode.PLAYING -> { pause(); enterPaused() }
                    Mode.PAUSED -> when (pausedFocus) {
                        PausedFocus.TOP_ROW -> pausedFocus = PausedFocus.SUBTITLE
                        PausedFocus.SUBTITLE -> pausedFocus = PausedFocus.SEEKBAR
                        PausedFocus.SEEKBAR -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                    }
                    Mode.SETTINGS -> { settingsFocus = (settingsFocus + 1).coerceAtMost((settingsRows.size - 1).coerceAtLeast(0)) }
                }
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                when (mode) {
                    Mode.SETTINGS -> { mode = Mode.PAUSED }
                    Mode.PAUSED -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                    Mode.PLAYING -> { cleanup(app); app.goBack() }
                }
            }
        }
    }

    private fun enterPaused() {
        mode = Mode.PAUSED
        pausedFocus = PausedFocus.SUBTITLE
        topRowFocus = 0
        if (wordSpans.isNotEmpty()) {
            cursorIdx = 0
            updateHighlight()
        }
    }

    private fun moveCursor(delta: Int) {
        if (wordSpans.isEmpty()) return
        cursorIdx = (cursorIdx + delta).coerceIn(0, wordSpans.size - 1)
        updateHighlight()
    }

    private fun updateHighlight() {
        val span = wordSpans.getOrNull(cursorIdx) ?: return
        hlStart = span.start
        hlEnd = span.end
    }

    private fun aabbHit(x: Float, y: Float, r: FloatArray): Boolean =
        x >= r[0] && y >= r[1] && x <= r[0] + r[2] && y <= r[1] + r[3]

    private fun nearestWordSpan(x: Float, y: Float): Int {
        if (charBoxes.isEmpty() || wordSpans.isEmpty()) return -1
        var bestDist = Float.MAX_VALUE
        var bestCharIdx = -1
        for (box in charBoxes) {
            val cx = box.x + box.w / 2f
            val cy = box.y + box.h / 2f
            val dist = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (dist < bestDist) { bestDist = dist; bestCharIdx = box.charIdx }
        }
        if (bestCharIdx < 0) return -1
        return wordSpans.indexOfFirst { bestCharIdx >= it.start && bestCharIdx < it.end }
    }

    private fun handleTap(app: App, x: Float, y: Float) {
        when (mode) {
            Mode.PAUSED -> {
                // 1. Dict popup — consume
                if (dictPopupVisible && aabbHit(x, y, dictPopupRect)) return
                // 2. Back button
                if (aabbHit(x, y, backBtnRect)) { cleanup(app); app.goBack(); return }
                // 3. Settings button
                if (aabbHit(x, y, settingsBtnRect)) { openSettings(); return }
                // 4. Seekbar
                if (aabbHit(x, y, seekbarRect)) {
                    val progress = ((x - seekbarRect[0]) / seekbarRect[2]).coerceIn(0f, 1f)
                    seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    return
                }
                // 5. Subtitle area — select nearest word
                if (aabbHit(x, y, subtitleRect)) {
                    val spanIdx = nearestWordSpan(x, y)
                    if (spanIdx >= 0) { cursorIdx = spanIdx; updateHighlight() }
                    return
                }
                // 6. Tap outside everything — resume
                hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play()
            }

            Mode.SETTINGS -> {
                if (x < settingsPanelX) { mode = Mode.PAUSED; settingsScrollY = 0f; return }
                for ((idx, rect) in settingsRowRects.withIndex()) {
                    if (aabbHit(x, y, rect)) {
                        settingsFocus = idx
                        settingsRows.getOrNull(idx)?.action?.invoke()
                        buildSettingsRows()
                        return
                    }
                }
            }

            Mode.PLAYING -> {
                // 1. Subtitle tap — pause + select that word directly
                if (aabbHit(x, y, subtitleRect)) {
                    val spanIdx = nearestWordSpan(x, y)
                    if (spanIdx >= 0) {
                        pause(); mode = Mode.PAUSED
                        cursorIdx = spanIdx; updateHighlight()
                        lastTapTime = 0; return
                    }
                }
                // 2. Double-tap seek
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    if (x < screenW / 2) seekRelative(-10000) else seekRelative(10000)
                    lastTapTime = 0; return
                }
                lastTapTime = now
                // 3. Single tap — pause + highlight first word
                pause(); enterPaused()
            }
        }
    }

    private var dbgVideo = 0f; private var dbgCue = 0f; private var dbgControls = 0f
    private var dbgDict = 0f; private var dbgUpdate = 0f; private var dbgFrameCount = 0

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        // Signal blit thread on new video frames (runs on separate GL context)
        if (app.videoSurface.frameReady) {
            kickBlitThread(app, rc)
        }

        var t0 = System.nanoTime()

        // ── Video quad (from FBO, regular texture — no OES in main pass) ──
        if (firstFrameReceived) {
            drawVideoQuad(app, rc)
        } else {
            rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        }
        var t1 = System.nanoTime(); dbgVideo = dbgVideo * 0.9f + (t1 - t0) / 1_000_000f * 0.1f; t0 = t1

        // Buffering spinner
        if (isBuffering || !firstFrameReceived) {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
            val cx = rc.w / 2f
            val cy = rc.h / 2f
            val radius = rc.dp(24f)
            val segments = 12
            for (i in 0 until segments) {
                val angle = (i.toFloat() / segments) * 2f * Math.PI.toFloat() + elapsed * 6f
                val dotX = cx + kotlin.math.cos(angle) * radius
                val dotY = cy + kotlin.math.sin(angle) * radius
                val alpha = (i.toFloat() / segments)
                val dotR = rc.dp(3f + alpha * 2f)
                rc.solid(dotX - dotR, dotY - dotR, dotR * 2, dotR * 2, 0.733f, 0.525f, 0.988f, alpha)
            }
        }

        // ── Controls ──
        if (mode == Mode.PAUSED) {
            drawControls(app, rc)
        }
        t1 = System.nanoTime(); dbgControls = dbgControls * 0.9f + (t1 - t0) / 1_000_000f * 0.1f; t0 = t1

        // ── Subtitle (CPU-rendered bitmap) ──
        if (currentCueText.isNotEmpty()) {
            drawSubtitleBitmap(app, rc)
        }
        t1 = System.nanoTime(); dbgCue = dbgCue * 0.9f + (t1 - t0) / 1_000_000f * 0.1f; t0 = t1

        // ── Dictionary popup ──
        if (mode == Mode.PAUSED && hlStart >= 0) {
            drawDictPopup(rc)
        } else {
            dictPopupVisible = false
        }
        t1 = System.nanoTime(); dbgDict = dbgDict * 0.9f + (t1 - t0) / 1_000_000f * 0.1f; t0 = t1

        // ── Settings panel ──
        if (mode == Mode.SETTINGS) {
            drawSettingsPanel(rc)
        }

        // ── Timing overlay ──
        val y0 = rc.dp(16f)
        val sz = rc.sp(10)
        val blitMs = app.blitThread?.lastBlitMs ?: 0f
        val timingStr = "${app.fps}fps  upd:${"%.1f".format(dbgUpdate)}  vid:${"%.1f".format(dbgVideo)}  blit:${"%.1f".format(blitMs)}  cue:${"%.1f".format(dbgCue)}  ctrl:${"%.1f".format(dbgControls)}"
        rc.text(timingStr, rc.dp(8f), y0, sz, 0.4f, 0.8f, 0.4f)

        // Log every 60 frames
        dbgFrameCount++
        if (dbgFrameCount % 60 == 0) {
            android.util.Log.d("PERF", timingStr)
        }
    }

    // ── Cue Layer (subtitle rendering with reading modes) ──

    private fun buildCueLayout(rc: RC): CueLayout {
        val text = currentCueText
        val fontSize = rc.sp(subFontSize)
        val lineH = rc.font.textHeight(fontSize)
        val furiganaSize = rc.sp((subFontSize * 0.45f).toInt())
        val ascent = rc.font.textAscent(fontSize)

        val displayText = convertForReadingMode(text)
        val lines = displayText.split("\n")
        val numLines = lines.size
        val lineWidths = lines.map { rc.font.measureText(it, fontSize) }
        val rowGap = lineH * (deltaRow - 1f)
        val totalTextH = lineH * numLines + rowGap * (numLines - 1).coerceAtLeast(0)
        val furiganaExtra = if (readingMode == ReadingMode.ADVANCED && superCues.isNotEmpty()) lineH * deltaFurigana else 0f
        val totalH = totalTextH + furiganaExtra
        val seekbarTopY = barY - rc.dp(24f)
        val baseY = seekbarTopY - rc.dp(8f) - deltaYShift * rc.density

        val boxes = mutableListOf<CharBox>()
        val chars = mutableListOf<CueCharDraw>()
        val lineInfoList = mutableListOf<Triple<String, Float, Int>>()
        var globalCharIdx = 0

        for (lineIdx in lines.indices) {
            val lineText = lines[lineIdx]
            val lineW = lineWidths[lineIdx]
            val linesFromBottom = numLines - 1 - lineIdx
            val lineY = baseY - linesFromBottom * (lineH + rowGap)
            val lineX = (rc.w - lineW) / 2f
            lineInfoList.add(Triple(lineText, lineY, globalCharIdx))

            var cx = lineX
            for (cp in lineText.toCodePoints()) {
                val ch = String(intArrayOf(cp), 0, 1)
                val chW = rc.font.measureText(ch, fontSize) + deltaSpacing * rc.density
                chars.add(CueCharDraw(ch, cx, lineY))
                boxes.add(CharBox(cx, lineY - ascent, chW, lineH, globalCharIdx))
                cx += chW
                globalCharIdx++
            }
            if (lineIdx < numLines - 1) globalCharIdx++
        }

        val furiDraws = mutableListOf<CueFuriDraw>()
        val furiH = rc.font.textHeight(furiganaSize)
        val furiAsc = rc.font.textAscent(furiganaSize)

        if (readingMode == ReadingMode.ADVANCED && superCues.isNotEmpty() && wordSpans.isNotEmpty()) {
            val origLines = currentCueText.split("\n")
            for ((lineIdx, triple) in lineInfoList.withIndex()) {
                val (_, lineY, _) = triple
                val lineStart = origLines.take(lineIdx).sumOf { it.length + 1 }
                val lineText = origLines[lineIdx]
                val lineW = lineWidths[lineIdx]
                val lineX = (rc.w - lineW) / 2f
                for (span in wordSpans) {
                    if (span.furigana.isEmpty()) continue
                    if (span.start >= lineStart + lineText.length || span.end <= lineStart) continue
                    for (furi in span.furigana) {
                        val localIdx = span.start + furi.charIdx - lineStart
                        if (localIdx < 0 || localIdx >= lineText.length) continue
                        val prefix = lineText.substring(0, localIdx)
                        val prefixW = rc.font.measureText(prefix, fontSize) + localIdx * deltaSpacing * rc.density
                        val charStr = lineText.substring(localIdx, (localIdx + 1).coerceAtMost(lineText.length))
                        val charW = rc.font.measureText(charStr, fontSize) + deltaSpacing * rc.density
                        val furiW = rc.font.measureText(furi.reading, furiganaSize)
                        val sx = if (furiW > charW) charW / furiW else 1f
                        val dw = furiW * sx
                        val fx = lineX + prefixW + (charW - dw) / 2f
                        val fy = lineY - lineH * deltaFurigana
                        furiDraws.add(CueFuriDraw(furi.reading, fx, fy, furiganaSize, sx, dw))
                    }
                }
            }
        }

        // Shade rect from extents
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (b in boxes) {
            if (b.x < minX) minX = b.x; if (b.y < minY) minY = b.y
            if (b.x + b.w > maxX) maxX = b.x + b.w; if (b.y + b.h > maxY) maxY = b.y + b.h
        }
        for (f in furiDraws) {
            val fy = f.y - furiAsc
            if (f.x < minX) minX = f.x; if (fy < minY) minY = fy
            if (f.x + f.displayW > maxX) maxX = f.x + f.displayW
        }
        val shade = if (boxes.isNotEmpty()) floatArrayOf(minX, minY, maxX - minX, maxY - minY)
                    else floatArrayOf(0f, 0f, 0f, 0f)

        return CueLayout(chars, furiDraws, boxes, shade, lineInfoList, fontSize, lineH, furiH, furiAsc, ascent)
    }

    private fun drawCueLayer(rc: RC) {
        val text = currentCueText
        val layout = buildCueLayout(rc)

        charBoxes = layout.boxes
        subtitleRect = layout.shadeRect

        // Shade
        val s = layout.shadeRect
        if (s[2] > 0f) {
            if (einkMode) rc.solid(s[0], s[1], s[2], s[3], 1f, 1f, 1f, 0.95f)
            else rc.solid(s[0], s[1], s[2], s[3], 0f, 0f, 0f, 0.7f)
        }

        // Highlight
        if (hlStart >= 0 && hlEnd > hlStart) {
            for ((lineText, lineY, globalStart) in layout.lineInfos) {
                drawLineHighlight(rc, lineText, (rc.w - rc.font.measureText(lineText, layout.fontSize)) / 2f,
                    lineY, layout.lineH, layout.fontSize, globalStart)
            }
        }

        // Characters — full lines
        val textR = if (einkMode) 0f else 1f
        val textG = if (einkMode) 0f else 1f
        val textB = if (einkMode) 0f else 1f
        for ((lineText, lineY, _) in layout.lineInfos) {
            val lineW = rc.font.measureText(lineText, layout.fontSize)
            val lineX = (rc.w - lineW) / 2f
            rc.text(lineText, lineX, lineY, layout.fontSize, textR, textG, textB)
        }

        // Debug: char bounding boxes (red)
        if (debugBoxes) {
            val dbgT = rc.dp(2f)
            for (b in layout.boxes) {
                rc.solid(b.x, b.y, b.w, dbgT, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x, b.y + b.h - dbgT, b.w, dbgT, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x, b.y, dbgT, b.h, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x + b.w - dbgT, b.y, dbgT, b.h, 1f, 0f, 0f, 0.8f)
            }
        }

        // Furigana
        val furiR = if (einkMode) 0.2f else 0.7f
        val furiG = if (einkMode) 0.2f else 0.7f
        val furiB = if (einkMode) 0.3f else 0.85f
        for (f in layout.furis) {
            if (f.scaleX < 1f) {
                rc.font.addTextScaled(rc.batch, f.text, f.x, f.y, f.size, f.scaleX, furiR, furiG, furiB)
            } else {
                rc.text(f.text, f.x, f.y, f.size, furiR, furiG, furiB)
            }
        }

        // Debug: furigana bounding boxes (blue)
        if (debugBoxes) {
            val dbgT = rc.dp(2f)
            for (f in layout.furis) {
                val fy = f.y - layout.furiAscent
                rc.solid(f.x, fy, f.displayW, dbgT, 0f, 0.4f, 1f, 0.8f)
                rc.solid(f.x, fy + layout.furiH - dbgT, f.displayW, dbgT, 0f, 0.4f, 1f, 0.8f)
                rc.solid(f.x, fy, dbgT, layout.furiH, 0f, 0.4f, 1f, 0.8f)
                rc.solid(f.x + f.displayW - dbgT, fy, dbgT, layout.furiH, 0f, 0.4f, 1f, 0.8f)
            }
        }
    }

    private fun drawLineHighlight(rc: RC, lineText: String, lineX: Float, lineY: Float,
                                  lineH: Float, fontSize: Int, lineGlobalStart: Int) {
        // Compute highlight region within this line
        val hlLocalStart = (hlStart - lineGlobalStart).coerceIn(0, lineText.length)
        val hlLocalEnd = (hlEnd - lineGlobalStart).coerceIn(0, lineText.length)
        if (hlLocalStart >= hlLocalEnd) return

        // Measure position of highlight start and end
        val prefix = lineText.substring(0, hlLocalStart)
        val highlighted = lineText.substring(hlLocalStart, hlLocalEnd)
        val prefixW = rc.font.measureText(prefix, fontSize) + hlLocalStart * deltaSpacing * rc.density
        val hlW = rc.font.measureText(highlighted, fontSize) + (hlLocalEnd - hlLocalStart) * deltaSpacing * rc.density

        // Draw highlight rect (purple accent) — baseline-relative
        val ascent = rc.font.textAscent(fontSize)
        rc.solid(lineX + prefixW, lineY - ascent, hlW, lineH, 0.733f, 0.525f, 0.988f, 0.3f)
    }

    private fun drawFuriganaForLine(rc: RC, lineIdx: Int, lineX: Float, lineY: Float,
                                    fontSize: Int, furiganaSize: Int, lineH: Float) {
        if (wordSpans.isEmpty()) return
        val lines = currentCueText.split("\n")
        val lineStart = lines.take(lineIdx).sumOf { it.length + 1 }
        val lineText = lines[lineIdx]

        for (span in wordSpans) {
            if (span.furigana.isEmpty()) continue
            // Check if span overlaps this line
            if (span.start >= lineStart + lineText.length || span.end <= lineStart) continue

            for (furi in span.furigana) {
                val absCharIdx = span.start + furi.charIdx
                val localIdx = absCharIdx - lineStart
                if (localIdx < 0 || localIdx >= lineText.length) continue

                // Find the character width and position
                val prefix = lineText.substring(0, localIdx)
                val prefixW = rc.font.measureText(prefix, fontSize) + localIdx * deltaSpacing * rc.density
                val charStr = lineText.substring(localIdx, (localIdx + 1).coerceAtMost(lineText.length))
                val charW = rc.font.measureText(charStr, fontSize) + deltaSpacing * rc.density

                // Center furigana above the character
                val furiW = rc.font.measureText(furi.reading, furiganaSize)
                val furiX = lineX + prefixW + (charW - furiW) / 2f
                val furiY = lineY - lineH * deltaFurigana

                rc.text(furi.reading, furiX, furiY, furiganaSize, 0.7f, 0.7f, 0.85f)
            }
        }
    }

    private fun convertForReadingMode(text: String): String {
        return when (readingMode) {
            ReadingMode.PRO -> text
            ReadingMode.ADVANCED -> text  // same display, furigana added separately
            ReadingMode.INTERMEDIATE -> convertToHiragana(text)
            ReadingMode.NOVICE -> convertToRomaji(text)
        }
    }

    private fun convertToHiragana(text: String): String {
        if (wordSpans.isEmpty()) return ReadingUtils.kata2hira(text)
        // Use word readings to convert kanji to hiragana
        val sb = StringBuilder()
        var pos = 0
        for (span in wordSpans) {
            // Append any text between spans
            if (span.start > pos) {
                sb.append(ReadingUtils.kata2hira(text.substring(pos, span.start)))
            }
            // Use the reading (already hiragana/katakana) for this word
            val reading = span.reading.ifEmpty { span.surface }
            sb.append(ReadingUtils.kata2hira(reading))
            pos = span.end
        }
        if (pos < text.length) sb.append(ReadingUtils.kata2hira(text.substring(pos)))
        return sb.toString()
    }

    private fun convertToRomaji(text: String): String {
        if (wordSpans.isEmpty()) return ReadingUtils.toRomaji(text)
        val sb = StringBuilder()
        var pos = 0
        for (span in wordSpans) {
            if (span.start > pos) {
                sb.append(ReadingUtils.toRomaji(text.substring(pos, span.start)))
            }
            val reading = span.reading.ifEmpty { span.surface }
            sb.append(ReadingUtils.toRomaji(reading))
            pos = span.end
        }
        if (pos < text.length) sb.append(ReadingUtils.toRomaji(text.substring(pos)))
        return sb.toString()
    }

    // ── Dictionary Popup ──

    private fun drawDictPopup(rc: RC) {
        val span = wordSpans.getOrNull(cursorIdx) ?: return
        val dict = superSRT?.dict ?: return
        if (span.dictIdx < 0 || span.dictIdx >= dict.size) return
        val entry = dict[span.dictIdx]

        val popupW = rc.dp(300f).coerceAtMost(rc.w * 0.8f)
        val popupX = (rc.w - popupW) / 2f
        val padP = rc.dp(16f)

        // Compute popup height
        val termSize = rc.sp(24)
        val readingSize = rc.sp(14)
        val meaningSize = rc.sp(14)
        val badgeSize = rc.sp(10)

        var contentH = padP  // top padding
        if (entry.reading.isNotEmpty() && entry.reading != entry.term) {
            contentH += rc.font.textHeight(readingSize) + rc.dp(2f)
        }
        contentH += rc.font.textHeight(termSize) + rc.dp(6f)  // term
        // Badges row
        if (span.inflection.isNotEmpty() || entry.jlpt.isNotEmpty()) {
            contentH += rc.font.textHeight(badgeSize) + rc.dp(8f)
        }
        // Meanings
        contentH += entry.meanings.size * (rc.font.textHeight(meaningSize) + rc.dp(4f))
        // Mine button
        contentH += rc.dp(32f)
        contentH += padP  // bottom padding

        // Position above subtitle — use actual shade rect
        val subTop = subtitleRect[1]
        val popupY = (subTop - contentH - rc.dp(8f)).coerceAtLeast(rc.dp(8f))

        // ── Store AABB for tap detection ──
        dictPopupRect = floatArrayOf(popupX, popupY, popupW, contentH)
        dictPopupVisible = true

        // ── Background ──
        rc.solid(popupX, popupY, popupW, contentH, 0.118f, 0.118f, 0.180f, 0.94f)

        // ── Content ──
        var cy = popupY + padP

        // Reading (gray, smaller)
        if (entry.reading.isNotEmpty() && entry.reading != entry.term) {
            val rw = rc.font.measureText(entry.reading, readingSize)
            rc.text(entry.reading, popupX + (popupW - rw) / 2f, cy + rc.font.textHeight(readingSize),
                readingSize, 0.67f, 0.67f, 0.67f)
            cy += rc.font.textHeight(readingSize) + rc.dp(2f)
        }

        // Term (large white)
        val tw = rc.font.measureText(entry.term, termSize)
        rc.text(entry.term, popupX + (popupW - tw) / 2f, cy + rc.font.textHeight(termSize),
            termSize, 1f, 1f, 1f)
        cy += rc.font.textHeight(termSize) + rc.dp(6f)

        // Badges (inflection + JLPT)
        var badgeX = popupX + padP
        if (span.inflection.isNotEmpty()) {
            val badgeW = rc.font.measureText(span.inflection, badgeSize) + rc.dp(10f)
            rc.solid(badgeX, cy, badgeW, rc.font.textHeight(badgeSize) + rc.dp(4f),
                0.13f, 0.13f, 0.2f, 0.8f)
            rc.text(span.inflection, badgeX + rc.dp(5f), cy + rc.font.textHeight(badgeSize) + rc.dp(1f),
                badgeSize, 0.475f, 0.525f, 0.796f)
            badgeX += badgeW + rc.dp(4f)
        }
        if (entry.jlpt.isNotEmpty()) {
            val badgeW = rc.font.measureText(entry.jlpt, badgeSize) + rc.dp(10f)
            rc.solid(badgeX, cy, badgeW, rc.font.textHeight(badgeSize) + rc.dp(4f),
                0.13f, 0.13f, 0.2f, 0.8f)
            rc.text(entry.jlpt, badgeX + rc.dp(5f), cy + rc.font.textHeight(badgeSize) + rc.dp(1f),
                badgeSize, 0.31f, 0.765f, 0.969f)
            badgeX += badgeW + rc.dp(4f)
        }
        if (span.inflection.isNotEmpty() || entry.jlpt.isNotEmpty()) {
            cy += rc.font.textHeight(badgeSize) + rc.dp(8f)
        }

        // Meanings
        for ((i, meaning) in entry.meanings.withIndex()) {
            val mText = "${i + 1}. $meaning"
            val mW = rc.font.measureText(mText, meaningSize)
            if (mW <= popupW - padP * 2) {
                rc.text(mText, popupX + padP, cy + rc.font.textHeight(meaningSize), meaningSize, 0.8f, 0.8f, 0.8f)
            } else {
                rc.textClipped(mText, popupX + padP, cy + rc.font.textHeight(meaningSize), meaningSize, popupW - padP * 2, 0.8f, 0.8f, 0.8f)
            }
            cy += rc.font.textHeight(meaningSize) + rc.dp(4f)
        }

    }

    // ── Settings Panel ──

    private fun openSettings() {
        mode = Mode.SETTINGS
        settingsFocus = 0
        buildSettingsRows()
    }

    private fun buildSettingsRows() {
        val rows = mutableListOf<SettingsRow>()

        // Audio tracks from ExoPlayer
        rows.add(SettingsRow("Audio", "", "audio"))
        try {
            val player = appRef?.exoPlayer
            if (player != null) {
                var trackIdx = 0
                for (group in player.currentTracks.groups) {
                    if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val label = format.label ?: format.language?.uppercase() ?: "Track ${trackIdx + 1}"
                        val selected = group.isTrackSelected(i)
                        val idx = trackIdx
                        rows.add(SettingsRow(label, format.language ?: "", "", indent = true, selected = selected) {
                            selectAudioTrack(idx)
                        })
                        trackIdx++
                    }
                }
            }
        } catch (_: Exception) {}

        // Subtitle tracks
        rows.add(SettingsRow("Subtitle", "", "subs"))
        for (sub in episode.subtitles) {
            rows.add(SettingsRow(sub.label, sub.language, "", indent = true,
                selected = sub.language == selectedSubLang) {
                selectedSubLang = sub.language
                loadSubtitleTrack(sub)
            })
        }

        // Reading mode
        rows.add(SettingsRow("Reading Mode", readingMode.name, "mode") {
            cycleReadingMode(); invalidateSubtitle(); savePrefs()
        })

        // Font
        rows.add(SettingsRow("Font", fontNames[currentFontIdx], "font") {
            currentFontIdx = (currentFontIdx + 1) % fontNames.size; invalidateSubtitle(); savePrefs()
        })

        // Font size
        rows.add(SettingsRow("Font Size", "${subFontSize}sp", "size") {
            cycleFontSize(); invalidateSubtitle(); savePrefs()
        })

        // Condensed mode
        rows.add(SettingsRow("Condensed", if (condensedMode) "ON" else "OFF", "cond") {
            condensedMode = !condensedMode; savePrefs()
        })

        // Typography sliders
        rows.add(SettingsRow("DF (Furigana)", "%.1f".format(deltaFurigana), "DF",
            isSlider = true, sliderRange = 0.3f to 1.5f, sliderValue = deltaFurigana,
            onSlide = { deltaFurigana = it; savePrefs() }))
        rows.add(SettingsRow("DR (Row Space)", "%.1f".format(deltaRow), "DR",
            isSlider = true, sliderRange = 0.5f to 2.0f, sliderValue = deltaRow,
            onSlide = { deltaRow = it; savePrefs() }))
        rows.add(SettingsRow("DS (Letter Space)", "%.1f".format(deltaSpacing), "DS",
            isSlider = true, sliderRange = -4f to 8f, sliderValue = deltaSpacing,
            onSlide = { deltaSpacing = it; savePrefs() }))
        rows.add(SettingsRow("DY (Y Offset)", "%.0f".format(deltaYShift), "DY",
            isSlider = true, sliderRange = -50f to 50f, sliderValue = deltaYShift,
            onSlide = { deltaYShift = it; savePrefs() }))

        // Theme
        rows.add(SettingsRow("Theme", if (einkMode) "E-Ink" else "Dark", "theme") {
            einkMode = !einkMode; appRef?.einkMode = einkMode; invalidateSubtitle(); savePrefs()
        })

        // Debug
        rows.add(SettingsRow("Debug Boxes", if (debugBoxes) "ON" else "OFF", "debug") {
            debugBoxes = !debugBoxes; savePrefs()
        })

        settingsRows = rows
    }

    private fun drawSettingsPanel(rc: RC) {
        val panelW = rc.dp(300f).coerceAtMost(rc.w * 0.4f)
        val panelX = rc.w - panelW
        settingsPanelX = panelX
        rc.solid(panelX, 0f, panelW, rc.h, 0.102f, 0.102f, 0.180f)

        val titleSize = rc.sp(18)
        rc.text(Lang.s("settings"), panelX + rc.dp(16f), rc.dp(32f), titleSize, 1f, 1f, 1f)

        val rowH = rc.dp(44f)
        val labelSize = rc.sp(14)
        val valueSize = rc.sp(13)
        var y = rc.dp(56f) - settingsScrollY

        val rects = mutableListOf<FloatArray>()
        for ((idx, row) in settingsRows.withIndex()) {
            val focused = idx == settingsFocus
            val rowY = y

            // Focus background
            if (focused) {
                rc.solid(panelX + rc.dp(4f), rowY, panelW - rc.dp(8f), rowH, 0.733f, 0.525f, 0.988f, 0.3f)
            } else if (row.selected) {
                rc.solid(panelX + rc.dp(4f), rowY, panelW - rc.dp(8f), rowH, 0.165f, 0.165f, 0.29f, 0.5f)
            }

            // Label
            val labelX = panelX + if (row.indent) rc.dp(42f) else rc.dp(14f)
            val labelY = rowY + rowH / 2f + rc.font.textHeight(labelSize) / 3f

            if (row.selected && row.indent) {
                rc.text("●", labelX - rc.dp(12f), labelY, rc.sp(8), 0.506f, 0.78f, 0.522f)
            }

            rc.text(row.label, labelX, labelY, labelSize,
                if (focused) 1f else 0.93f, if (focused) 1f else 0.93f, if (focused) 1f else 0.93f)

            // Value (right-aligned)
            if (row.value.isNotEmpty()) {
                val vw = rc.font.measureText(row.value, valueSize)
                rc.text(row.value, panelX + panelW - rc.dp(14f) - vw, labelY, valueSize, 0.506f, 0.78f, 0.522f)
            }

            rects.add(floatArrayOf(panelX, rowY, panelW, rowH))
            y += rowH

            // Slider below the row
            if (row.isSlider) {
                val sliderPad = rc.dp(14f)
                val sliderX = panelX + sliderPad
                val sliderW = panelW - sliderPad * 2
                val sliderY = y + rc.dp(4f)
                val trackH = rc.dp(4f)
                val handleR = rc.dp(8f)

                // Track
                rc.solid(sliderX, sliderY, sliderW, trackH, 0.25f, 0.25f, 0.35f)

                // Fill
                val (lo, hi) = row.sliderRange
                val t = ((row.sliderValue - lo) / (hi - lo)).coerceIn(0f, 1f)
                rc.solid(sliderX, sliderY, sliderW * t, trackH, 0.733f, 0.525f, 0.988f)

                // Handle
                val handleX = sliderX + sliderW * t
                rc.solid(handleX - handleR, sliderY - handleR + trackH / 2f, handleR * 2, handleR * 2, 1f, 1f, 1f)

                // Store slider rect for drag detection (wider touch area)
                settingsSliderRects[idx] = floatArrayOf(sliderX, sliderY - handleR, sliderW, handleR * 2 + trackH)

                y += rc.dp(24f)
            }
        }
        settingsRowRects = rects
    }

    private fun applySliderDrag(idx: Int, touchX: Float, rect: FloatArray) {
        val row = settingsRows.getOrNull(idx) ?: return
        if (!row.isSlider) return
        val (lo, hi) = row.sliderRange
        val t = ((touchX - rect[0]) / rect[2]).coerceIn(0f, 1f)
        val value = lo + t * (hi - lo)
        row.onSlide?.invoke(value)
        buildSettingsRows()
    }

    private fun handleSettingsSelect() {
        val row = settingsRows.getOrNull(settingsFocus) ?: return
        row.action()
        buildSettingsRows()
    }

    private fun handleSettingsLeft() {
        val row = settingsRows.getOrNull(settingsFocus) ?: return
        when (row.icon) {
            "DF" -> { deltaFurigana = (deltaFurigana - 0.1f).coerceIn(0.3f, 1.5f); buildSettingsRows() }
            "DR" -> { deltaRow = (deltaRow - 0.1f).coerceIn(0.5f, 2.0f); buildSettingsRows() }
            "DS" -> { deltaSpacing = (deltaSpacing - 0.5f).coerceIn(-4f, 8f); buildSettingsRows() }
            "DY" -> { deltaYShift = (deltaYShift - 5f).coerceIn(-50f, 50f); buildSettingsRows() }
        }
    }

    private fun handleSettingsRight() {
        val row = settingsRows.getOrNull(settingsFocus) ?: return
        when (row.icon) {
            "DF" -> { deltaFurigana = (deltaFurigana + 0.1f).coerceIn(0.3f, 1.5f); buildSettingsRows() }
            "DR" -> { deltaRow = (deltaRow + 0.1f).coerceIn(0.5f, 2.0f); buildSettingsRows() }
            "DS" -> { deltaSpacing = (deltaSpacing + 0.5f).coerceIn(-4f, 8f); buildSettingsRows() }
            "DY" -> { deltaYShift = (deltaYShift + 5f).coerceIn(-50f, 50f); buildSettingsRows() }
        }
    }

    private fun cycleReadingMode() {
        readingMode = when (readingMode) {
            ReadingMode.NOVICE -> ReadingMode.INTERMEDIATE
            ReadingMode.INTERMEDIATE -> ReadingMode.ADVANCED
            ReadingMode.ADVANCED -> ReadingMode.PRO
            ReadingMode.PRO -> ReadingMode.NOVICE
        }
    }

    private fun cycleFontSize() {
        subFontSize = when (subFontSize) {
            24 -> 32
            32 -> 44
            else -> 24
        }
    }

    // ── Controls Overlay ──

    private fun drawControls(app: App, rc: RC) {
        val centerX = rc.w / 2f
        val centerY = rc.h / 2f

        // Play/pause icon
        if (!isPlaying) {
            val playIcon = "▶"
            val iconSize = rc.sp(36)
            val tw = rc.font.measureText(playIcon, iconSize)
            rc.text(playIcon, centerX - tw / 2f, centerY + rc.font.textHeight(iconSize) / 3f,
                iconSize, 1f, 1f, 1f, 0.8f)
        }

        // Episode title
        rc.text("${episode.episode}. ${episode.title()}", pad, rc.dp(32f), rc.sp(16), 1f, 1f, 1f)

        // Back button — store bbox + highlight
        val backFocused = pausedFocus == PausedFocus.TOP_ROW && topRowFocus == 0
        rc.text("←", pad, rc.dp(60f), rc.sp(22), if (backFocused) 1f else 0.8f, if (backFocused) 1f else 0.8f, if (backFocused) 1f else 0.8f)
        backBtnRect = floatArrayOf(0f, 0f, rc.dp(80f), rc.dp(80f))
        if (backFocused) rc.border(0f, 0f, rc.dp(80f), rc.dp(80f), rc.dp(3f), 0.733f, 0.525f, 0.988f)

        // Settings button — visible pill, store bbox
        val setBtnW = rc.dp(80f)
        val setBtnH = rc.dp(36f)
        val setBtnX = rc.w - pad - setBtnW
        val setBtnY = rc.dp(12f)
        val settFocused = pausedFocus == PausedFocus.TOP_ROW && topRowFocus == 1
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = rc.font.measureText(setLabel, rc.sp(12))
        rc.text(setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(24f), rc.sp(12), 0.733f, 0.525f, 0.988f)
        settingsBtnRect = floatArrayOf(setBtnX, setBtnY, setBtnW, setBtnH)
        if (settFocused) rc.border(setBtnX, setBtnY, setBtnW, setBtnH, rc.dp(3f), 0.733f, 0.525f, 0.988f)

        // Seekbar
        val seekFocused = pausedFocus == PausedFocus.SEEKBAR
        val barW = rc.w - pad * 2
        rc.solid(pad, barY, barW, rc.dp(4f), 0.3f, 0.3f, 0.4f)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        rc.solid(pad, barY, barW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)
        val handleX = pad + barW * progress
        rc.solid(handleX - rc.dp(6f), barY - rc.dp(6f), rc.dp(12f), rc.dp(16f), 1f, 1f, 1f)
        seekbarRect = floatArrayOf(pad, barY - rc.dp(24f), barW, rc.dp(48f))
        if (seekFocused) rc.border(pad, barY - rc.dp(8f), barW, rc.dp(20f), rc.dp(2f), 0.733f, 0.525f, 0.988f)

        // Time
        val posStr = formatTime(positionMs)
        val durStr = formatTime(durationMs)
        rc.text(posStr, pad, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)
        val durW = rc.font.measureText(durStr, rc.sp(12))
        rc.text(durStr, rc.w - pad - durW, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)

        // Prev/Next subtitle buttons
        prevCueVisible = false; nextCueVisible = false
        if (!isPlaying && cues.isNotEmpty()) {
            val btnSize = rc.sp(22)
            val btnY = barY - rc.dp(40f)

            val prevW = rc.font.measureText("⏮", btnSize)
            rc.text("⏮", centerX - rc.dp(60f) - prevW / 2f, btnY, btnSize, 0.8f, 0.8f, 0.8f)
            prevCueRect = floatArrayOf(centerX - rc.dp(80f), btnY - rc.dp(20f), rc.dp(60f), rc.dp(40f))
            prevCueVisible = true

            val nextW = rc.font.measureText("⏭", btnSize)
            rc.text("⏭", centerX + rc.dp(60f) - nextW / 2f, btnY, btnSize, 0.8f, 0.8f, 0.8f)
            nextCueRect = floatArrayOf(centerX + rc.dp(30f), btnY - rc.dp(20f), rc.dp(60f), rc.dp(40f))
            nextCueVisible = true
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }

    // ── Subtitle Bitmap Rendering ──

    private fun drawSubtitleBitmap(app: App, rc: RC) {
        val text = currentCueText
        val displayText = convertForReadingMode(text)

        // Build word info for furigana
        val wordInfos = if (readingMode == ReadingMode.ADVANCED) {
            wordSpans.map { span ->
                SubtitleBitmap.WordInfo(span.start, span.end,
                    span.furigana.map { SubtitleBitmap.FuriSpan(it.charIdx, it.reading) })
            }
        } else emptyList()

        val typeface = try {
            android.graphics.Typeface.createFromAsset(app.context.assets, fontAssets[currentFontIdx])
        } catch (_: Exception) { android.graphics.Typeface.DEFAULT }

        val params = SubtitleBitmap.RenderParams(
            fontFamily = typeface,
            textSizePx = rc.sp(subFontSize).toFloat(),
            outlineWidth = rc.dp(2f),
            shadowRadius = rc.dp(4f),
            shadowDx = rc.dp(1.5f),
            shadowDy = rc.dp(1.5f),
            eink = einkMode,
            deltaSpacing = deltaSpacing * rc.density / rc.sp(subFontSize),
            deltaFurigana = deltaFurigana,
            deltaRow = deltaRow,
        )

        subtitleBmp.render(displayText, wordInfos, params, rc.w)

        if (subtitleBmp.textureId == 0) return

        // Position: center horizontally, bottom above seekbar
        val subW = subtitleBmp.texW.toFloat()
        val subH = subtitleBmp.texH.toFloat()
        val subX = (rc.w - subW) / 2f
        val subY = barY - rc.dp(24f) - subH - deltaYShift * rc.density

        // Store subtitle rect for tap detection
        subtitleRect = floatArrayOf(subX, subY, subW, subH)

        // Bind subtitle texture to unit 2 (reuse uTexVideo sampler) and draw quad
        rc.batch.flush()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, subtitleBmp.textureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        rc.batch.begin()
        rc.batch.addQuad(subX, subY, subW, subH, 0f, 0f, 1f, 1f, layer = -1f)
        rc.batch.flush()

        // Rebind video texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        app.videoSurface.bindRgb()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        rc.batch.begin()
    }

    // ── Player controls ──

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var firstFrameReceived = false
    @Volatile var isBuffering = true

    private var appRef: App? = null

    fun play() {
        isPlaying = true
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.play() })
    }

    private fun pause() {
        isPlaying = false
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.pause() })
    }

    private fun seekRelative(deltaMs: Long) {
        val target = (positionMs + deltaMs).coerceIn(0, durationMs)
        positionMs = target
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(target) })
    }

    private fun seekTo(ms: Long) {
        positionMs = ms
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(ms) })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun selectAudioTrack(idx: Int) {
        appRef?.onMainThread?.invoke(Runnable {
            val player = appRef?.exoPlayer ?: return@Runnable
            var trackIdx = 0
            for (group in player.currentTracks.groups) {
                if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    if (trackIdx == idx) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        selectedAudioIdx = idx
                        return@Runnable
                    }
                    trackIdx++
                }
            }
        })
    }

    private fun loadSubtitleTrack(sub: JanusApi.SubTrack) {
        val api = appRef?.api ?: return
        val token = api.token ?: ""
        val url = "$baseUrl/api/subs/${item.id}/${sub.srtFile}"
        kotlin.concurrent.thread {
            try {
                val request = okhttp3.Request.Builder().url(url)
                    .header("Authorization", "Bearer $token").build()
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) {
                    val text = response.body?.string() ?: ""
                    cues = SrtParser.parse(text)
                }
                response.close()
            } catch (_: Exception) {}
        }
    }

    private fun kickBlitThread(app: App, rc: RC) {
        val bt = app.blitThread ?: return
        bt.videoWidth = videoWidth
        bt.videoHeight = videoHeight
        bt.screenWidth = rc.w.toInt()
        bt.screenHeight = rc.h.toInt()
        bt.requestBlit()
    }

    private fun drawVideoQuad(app: App, rc: RC) {
        val bt = app.blitThread
        if (bt == null || !bt.frameReady) return
        // Rebind FBO texture (blit thread may have recreated it)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        app.videoSurface.bindRgb()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        // Fullscreen quad sampling from uTexVideo (layer = -1)
        // FBO has OpenGL origin (Y=0 at bottom), flip V: top=1, bottom=0
        rc.batch.addQuad(0f, 0f, rc.w, rc.h, 0f, 1f, 1f, 0f, layer = -1f)
    }

    @Volatile private var alive = true

    override fun cleanup(app: App) {
        alive = false
        subtitleBmp.release()
        app.onMainThread?.invoke(Runnable {
            app.exoPlayer?.stop()
            app.exoPlayer?.clearMediaItems()
            app.exoPlayer?.setVideoSurface(null)
        })
        appRef = null
    }
}
