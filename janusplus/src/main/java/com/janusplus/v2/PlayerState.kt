package com.janusplus.v2

import android.opengl.GLES30
import com.janusplus.AnkiDroidClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.janusplus.GlyphAtlas
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.SrtParser
import com.janusplus.TextureArray
import com.janusplus.toCodePoints

class PlayerState(private val page: PlayerPage) : GameState {

    enum class Mode { PLAYING, PAUSED, SETTINGS }
    enum class ReadingMode { PRO, ADVANCED, INTERMEDIATE, NOVICE }

    // Render mask — toggle individual UI elements for isolated testing
    object Layer {
        const val VIDEO     = 1 shl 0
        const val CUE       = 1 shl 1
        const val CONTROLS  = 1 shl 2
        const val SEEKBAR   = 1 shl 3
        const val DICT      = 1 shl 4
        const val SETTINGS  = 1 shl 5
        const val FPS       = 1 shl 6
        const val SPINNER   = 1 shl 7
        const val ALL       = 0xFF
    }
    var renderMask = Layer.ALL

    @Volatile var mode = Mode.PLAYING
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var isPlaying = true

    var debugBoxes = false
    var einkMode = false
    var readingMode = ReadingMode.PRO

    var deltaFurigana = 0.7f
    var deltaRow = 1.4f
    var deltaSpacing = 0f
    var deltaYShift = 0f
    var subFontSize = 32
    var currentFontIdx = 0
    val fontNames = listOf("Noto Sans", "Noto Serif", "Shippori", "Klee One", "Kosugi Maru")
    private val fontAssets = listOf(
        "fonts/NotoSansJP-Regular.ttf", "fonts/NotoSerifJP-Regular.ttf",
        "fonts/ShipporiMincho-Regular.ttf", "fonts/KleeOne-Regular.ttf",
        "fonts/KosugiMaru-Regular.ttf"
    )

    private var cues: List<SrtParser.Cue> = emptyList()
    private var currentCue: SrtParser.Cue? = null
    private var currentCueText = ""

    private var superSRT: JanusApi.SuperSRT? = null
    private var superCues: List<JanusApi.SuperCue> = emptyList()
    private var currentSuperCue: JanusApi.SuperCue? = null

    data class WordSpan(val start: Int, val end: Int, val surface: String,
                        val dictIdx: Int, val inflection: String,
                        val reading: String, val furigana: List<JanusApi.FuriganaSpan>)
    private var wordSpans = listOf<WordSpan>()
    private var cursorIdx = 0
    private var hlStart = -1
    private var hlEnd = -1

    private var charBoxes = listOf<CharBox>()
    data class CharBox(val x: Float, val y: Float, val w: Float, val h: Float, val charIdx: Int)

    data class CueCharDraw(val ch: String, val x: Float, val y: Float)
    data class CueFuriDraw(val text: String, val x: Float, val y: Float, val size: Int, val scaleX: Float, val displayW: Float)
    data class CueLayout(
        val chars: List<CueCharDraw>, val furis: List<CueFuriDraw>,
        val boxes: List<CharBox>, val shadeRect: FloatArray,
        val lineInfos: List<Triple<String, Float, Int>>,
        val fontSize: Int, val lineH: Float, val furiH: Float, val furiAscent: Float, val ascent: Float
    )

    private var lastTapTime = 0L
    private var isDraggingSeekbar = false

    private var backBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var settingsBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var seekbarRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var dictPopupRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var dictPopupVisible = false
    private var subtitleRect = floatArrayOf(0f, 0f, 0f, 0f)

    private var startTime = System.nanoTime()
    private var pad = 0f; private var barH = 0f; private var barY = 0f
    private var screenW = 0f; private var screenH = 0f; private var layoutDone = false

    private var settingsFocus = 0
    private var settingsRows = listOf<SettingsRow>()
    private var settingsScrollY = 0f; private var settingsDragY = 0f; private var settingsDragging = false

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

    var condensedMode = false
    var playbackSpeed = 1.0f
    private var lastCondensedSpeed = 1f

    // Opening/ending skip
    private var openingMs = 0L
    private var endingMs = 0L
    private var showSkipIntro = false
    private var showEndingCountdown = false
    private var endingCountdown = 5
    private var endingCancelled = false
    private var selectedAudioIdx = 0
    private var selectedSubLang = "ja"

    enum class PausedFocus { TOP_ROW, SUBTITLE, SEEKBAR }
    private var pausedFocus = PausedFocus.SUBTITLE
    private var topRowFocus = 0

    // Atlases — uploaded once in init(), no pending/volatile
    private val uiAtlases = HashMap<Int, GlyphAtlas>()
    var subAtlas: GlyphAtlas? = null; private set
    private var uiBaseSp = 18
    private var uiBasePx = 0

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var firstFrameReceived = false
    @Volatile var isBuffering = true
    private var appRef: App? = null
    @Volatile private var alive = true
    private var mineBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var ankiCheckWord = ""
    private var ankiCheckResult = 0 // 0=unchecked, 1=checking, 2=not in anki, 3=already in anki, 4=mining, 5=failed
    @Volatile private var mineStatus = "" // live status shown on button
    private var useTouchNav = true // hide cue buttons when D-pad detected
    private var nextEpBtnRect = floatArrayOf(0f, 0f, 0f, 0f)

    private fun saveProgress() {
        val app = appRef ?: return
        val pos = positionMs; val dur = durationMs
        if (pos <= 0 || dur <= 0) return
        val api = app.api ?: return
        val id = page.item.id; val ep = page.episode.episode
        kotlin.concurrent.thread { api.saveProgress(id, ep, pos, dur) }
    }

    private fun savePrefs() {
        val app = appRef ?: return
        app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putFloat("df", deltaFurigana).putFloat("dr", deltaRow)
            .putFloat("ds", deltaSpacing).putFloat("dy", deltaYShift)
            .putInt("font_size", subFontSize).putInt("font_idx", currentFontIdx)
            .putInt("reading_mode", readingMode.ordinal)
            .putBoolean("condensed", condensedMode).putBoolean("debug_boxes", debugBoxes)
            .putBoolean("eink_mode", einkMode).apply()
    }

    override fun init(app: App) {
        appRef = app
        mode = Mode.PLAYING

        // Apply prefs from loading state
        val prefs = page.prefs
        deltaFurigana = prefs.deltaFurigana; deltaRow = prefs.deltaRow
        deltaSpacing = prefs.deltaSpacing; deltaYShift = prefs.deltaYShift
        subFontSize = prefs.subFontSize
        currentFontIdx = prefs.fontIdx.coerceIn(0, fontNames.size - 1)
        readingMode = ReadingMode.entries.getOrNull(prefs.readingMode) ?: ReadingMode.PRO
        condensedMode = prefs.condensedMode; debugBoxes = prefs.debugBoxes
        einkMode = prefs.einkMode; app.einkMode = einkMode

        // Load subtitle data
        cues = page.cues
        superSRT = page.superSRT
        superCues = page.superSRT?.cues ?: emptyList()

        // Upload atlas bitmaps — one shot, no races
        for ((pxSize, atlas, bmp) in page.uiAtlases) {
            app.uploadGlyphAtlas(atlas, bmp)
            uiAtlases[pxSize] = atlas
        }
        // Find the UI base atlas (18sp)
        uiBasePx = (uiBaseSp * app.density).toInt()

        if (page.subAtlas != null && page.subBmp != null) {
            app.uploadGlyphAtlas(page.subAtlas, page.subBmp)
            subAtlas = page.subAtlas
        }

        // Opening/ending skip — resolved server-side, embedded in episode data
        openingMs = (page.episode.openingSec * 1000).toLong()
        endingMs = (page.episode.endingSec * 1000).toLong()

        // Start video playback
        val api = app.api ?: return
        val videoUrl = "${page.baseUrl}/api/video/${page.item.id}/${page.episode.filename}"
        val token = api.token ?: ""

        app.onMainThread?.invoke(Runnable {
            val player = app.exoPlayer ?: return@Runnable
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))
            player.setVideoSurface(app.videoSurface.surface)
            player.setMediaSource(source)
            player.prepare()
            val resumeMs = page.episode.watchProgressMs
            if (resumeMs > 1000) player.seekTo(resumeMs)
            player.playWhenReady = true

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
                        videoWidth = format.width; videoHeight = format.height
                    }
                    // Save progress every ~30 seconds
                    if (player.currentPosition % 30000 < 250) saveProgress()

                    // Skip intro / ending countdown
                    val pos = player.currentPosition
                    showSkipIntro = openingMs > 0 && pos < openingMs && pos > 1000
                    val inEnding = endingMs > 0 && player.duration > 0 && pos >= player.duration - endingMs
                    if (inEnding && !endingCancelled) {
                        if (!showEndingCountdown) { showEndingCountdown = true; endingCountdown = 3 }
                        endingCountdown = ((player.duration - pos) / 1000).toInt().coerceIn(0, 3)
                        if (endingCountdown <= 0) { showEndingCountdown = false; playNextEpisode(app) }
                    } else if (!inEnding) { showEndingCountdown = false; endingCancelled = false }

                    // Condensed mode: speed up between subtitles
                    if (condensedMode && player.isPlaying && mode == Mode.PLAYING) {
                        val pos = player.currentPosition
                        val midSub = currentCue != null && pos >= (currentCue?.startMs ?: 0) && pos <= (currentCue?.endMs ?: 0)
                        val prev = cues.lastOrNull { it.endMs <= pos }
                        val next = cues.firstOrNull { it.startMs > pos }
                        val deltaBefore = if (prev != null) pos - prev.endMs else Long.MAX_VALUE
                        val deltaAfter = if (next != null) next.startMs - pos else Long.MAX_VALUE

                        val speed = when {
                            midSub || deltaBefore < 500 -> 1f
                            deltaAfter > 10000          -> 16f
                            deltaAfter > 3000           -> 8f
                            deltaAfter > 900            -> 2f
                            else                        -> 1f
                        }
                        if (speed != lastCondensedSpeed) {
                            player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed))
                            player.volume = if (speed > 1f) 0f else 1f
                            lastCondensedSpeed = speed
                        }
                    } else if (lastCondensedSpeed != 1f && !condensedMode) {
                        player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(playbackSpeed))
                        player.volume = 1f
                        lastCondensedSpeed = 1f
                    }

                    handler.postDelayed(this, if (isBuffering) 50 else 200)
                }
            }
            handler.postDelayed(poller, 200)
        })
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(24f); barH = rc.dp(48f); barY = rc.h - barH - rc.dp(24f)
        screenW = rc.w; screenH = rc.h; layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        if (app.videoSurface.updateTexture()) firstFrameReceived = true

        if (mode == Mode.PLAYING) {
            val pos = positionMs
            if (superCues.isNotEmpty()) {
                val sCue = superCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
                if (sCue !== currentSuperCue) {
                    currentSuperCue = sCue
                    if (sCue != null) buildWordSpans(sCue)
                    else { currentCueText = ""; wordSpans = emptyList() }
                }
            } else {
                val cue = cues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
                if (cue !== currentCue) {
                    currentCue = cue; currentCueText = cue?.text ?: ""; wordSpans = emptyList()
                }
            }
        }

        for (t in touches) {
            useTouchNav = true
            when (t.action) {
                0 -> {
                    if (mode == Mode.PAUSED && aabbHit(t.x, t.y, seekbarRect)) {
                        isDraggingSeekbar = true
                        val progress = ((t.x - seekbarRect[0]) / seekbarRect[2]).coerceIn(0f, 1f)
                        seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    } else if (mode == Mode.SETTINGS) {
                        draggingSliderIdx = -1
                        for ((idx, rect) in settingsSliderRects) {
                            if (aabbHit(t.x, t.y, rect)) { draggingSliderIdx = idx; applySliderDrag(idx, t.x, rect); break }
                        }
                        if (draggingSliderIdx < 0) { settingsDragY = t.y; settingsDragging = false }
                    }
                }
                2 -> {
                    if (isDraggingSeekbar && screenW > 0) {
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        positionMs = (durationMs * progress).toLong().coerceIn(0, durationMs)
                    } else if (draggingSliderIdx >= 0) {
                        val rect = settingsSliderRects[draggingSliderIdx]
                        if (rect != null) applySliderDrag(draggingSliderIdx, t.x, rect)
                    } else if (mode == Mode.SETTINGS) {
                        val dy = settingsDragY - t.y
                        if (!settingsDragging && Math.abs(dy) > 8f) settingsDragging = true
                        if (settingsDragging) { settingsScrollY = (settingsScrollY + dy).coerceAtLeast(0f); settingsDragY = t.y }
                    }
                }
                1 -> {
                    if (isDraggingSeekbar) {
                        isDraggingSeekbar = false
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    } else if (draggingSliderIdx >= 0) {
                        draggingSliderIdx = -1; buildSettingsRows()
                    } else if (settingsDragging) {
                        settingsDragging = false
                    } else { handleTap(app, t.x, t.y) }
                }
            }
        }
        for (a in actions) handleAction(app, a)
    }

    private fun buildWordSpans(sCue: JanusApi.SuperCue) {
        val display = StringBuilder()
        val spans = mutableListOf<WordSpan>()
        for (w in sCue.words) {
            if (w.surface.isBlank() || w.surface == "\n") { display.append(w.surface); continue }
            val start = display.length
            display.append(w.surface)
            spans.add(WordSpan(start, display.length, w.surface, w.dictIdx, w.inflection, w.reading, w.furigana))
        }
        currentCueText = display.toString(); wordSpans = spans
    }

    private fun handleAction(app: App, a: Action) {
        if (a != Action.MINE) useTouchNav = false
        when (a) {
            Action.PLAY_PAUSE -> when (mode) {
                Mode.PLAYING -> { pause(); enterPaused() }
                Mode.PAUSED -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                Mode.SETTINGS -> { mode = Mode.PAUSED }
            }
            Action.MENU -> when (mode) {
                Mode.PLAYING -> { pause(); openSettings() }
                Mode.PAUSED -> openSettings()
                Mode.SETTINGS -> { mode = Mode.PLAYING; play() }
            }
            Action.REWIND -> seekRelative(-5000)
            Action.FORWARD -> seekRelative(5000)
            Action.SELECT -> when (mode) {
                Mode.PLAYING -> { pause(); enterPaused() }
                Mode.PAUSED -> when (pausedFocus) {
                    PausedFocus.TOP_ROW -> { if (topRowFocus == 0) { cleanup(app); app.goBack() } else openSettings() }
                    PausedFocus.SUBTITLE -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                    PausedFocus.SEEKBAR -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                }
                Mode.SETTINGS -> handleSettingsSelect()
            }
            Action.LEFT -> when (mode) {
                Mode.PLAYING -> { SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) } }
                Mode.PAUSED -> when (pausedFocus) {
                    PausedFocus.TOP_ROW -> topRowFocus = 0
                    PausedFocus.SUBTITLE -> moveCursor(-1)
                    PausedFocus.SEEKBAR -> seekRelative(-10000)
                }
                Mode.SETTINGS -> handleSettingsLeft()
            }
            Action.RIGHT -> when (mode) {
                Mode.PLAYING -> { SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) } }
                Mode.PAUSED -> when (pausedFocus) {
                    PausedFocus.TOP_ROW -> topRowFocus = 1
                    PausedFocus.SUBTITLE -> moveCursor(1)
                    PausedFocus.SEEKBAR -> seekRelative(10000)
                }
                Mode.SETTINGS -> handleSettingsRight()
            }
            Action.UP -> when (mode) {
                Mode.PLAYING -> { pause(); enterPaused() }
                Mode.PAUSED -> when (pausedFocus) {
                    PausedFocus.TOP_ROW -> {}
                    PausedFocus.SUBTITLE -> pausedFocus = PausedFocus.TOP_ROW
                    PausedFocus.SEEKBAR -> pausedFocus = PausedFocus.SUBTITLE
                }
                Mode.SETTINGS -> { settingsFocus = (settingsFocus - 1).coerceAtLeast(0) }
            }
            Action.DOWN -> when (mode) {
                Mode.PLAYING -> { pause(); enterPaused() }
                Mode.PAUSED -> when (pausedFocus) {
                    PausedFocus.TOP_ROW -> pausedFocus = PausedFocus.SUBTITLE
                    PausedFocus.SUBTITLE -> pausedFocus = PausedFocus.SEEKBAR
                    PausedFocus.SEEKBAR -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                }
                Mode.SETTINGS -> { settingsFocus = (settingsFocus + 1).coerceAtMost((settingsRows.size - 1).coerceAtLeast(0)) }
            }
            Action.BACK -> when (mode) {
                Mode.SETTINGS -> { mode = Mode.PLAYING; play() }
                Mode.PAUSED -> { hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play() }
                Mode.PLAYING -> { cleanup(app); app.goBack() }
            }
            Action.MINE -> mineCurrentWord()
        }
    }

    private fun enterPaused() {
        mode = Mode.PAUSED; pausedFocus = PausedFocus.SUBTITLE; topRowFocus = 0
        if (wordSpans.isNotEmpty()) { cursorIdx = 0; updateHighlight() }
    }

    private fun moveCursor(delta: Int) {
        if (wordSpans.isEmpty()) return
        cursorIdx = (cursorIdx + delta).coerceIn(0, wordSpans.size - 1); updateHighlight()
    }

    private fun updateHighlight() {
        val span = wordSpans.getOrNull(cursorIdx) ?: return
        hlStart = span.start; hlEnd = span.end
    }

    private fun aabbHit(x: Float, y: Float, r: FloatArray) = x >= r[0] && y >= r[1] && x <= r[0] + r[2] && y <= r[1] + r[3]

    private fun nearestWordSpan(x: Float, y: Float): Int {
        if (charBoxes.isEmpty() || wordSpans.isEmpty()) return -1
        var bestDist = Float.MAX_VALUE; var bestCharIdx = -1
        for (box in charBoxes) {
            val cx = box.x + box.w / 2f; val cy = box.y + box.h / 2f
            val dist = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (dist < bestDist) { bestDist = dist; bestCharIdx = box.charIdx }
        }
        if (bestCharIdx < 0) return -1
        return wordSpans.indexOfFirst { bestCharIdx >= it.start && bestCharIdx < it.end }
    }

    private fun handleTap(app: App, x: Float, y: Float) {
        when (mode) {
            Mode.PAUSED -> {
                if (dictPopupVisible && mineBtnRect[2] > 0 && aabbHit(x, y, mineBtnRect)) { mineCurrentWord(); return }
                if (dictPopupVisible && aabbHit(x, y, dictPopupRect)) return
                if (useTouchNav && prevCueBtnRect[2] > 0 && aabbHit(x, y, prevCueBtnRect)) {
                    SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) }; return
                }
                if (useTouchNav && nextCueBtnRect[2] > 0 && aabbHit(x, y, nextCueBtnRect)) {
                    SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) }; return
                }
                if (aabbHit(x, y, backBtnRect)) { cleanup(app); app.goBack(); return }
                if (aabbHit(x, y, settingsBtnRect)) { openSettings(); return }
                if (nextEpBtnRect[2] > 0 && aabbHit(x, y, nextEpBtnRect)) { playNextEpisode(app); return }
                if (aabbHit(x, y, seekbarRect)) {
                    val progress = ((x - seekbarRect[0]) / seekbarRect[2]).coerceIn(0f, 1f)
                    seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                    hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play()
                    return
                }
                if (aabbHit(x, y, subtitleRect)) {
                    val spanIdx = nearestWordSpan(x, y)
                    if (spanIdx >= 0) { cursorIdx = spanIdx; updateHighlight() }; return
                }
                hlStart = -1; hlEnd = -1; mode = Mode.PLAYING; play()
            }
            Mode.SETTINGS -> {
                if (x < settingsPanelX) { mode = Mode.PAUSED; settingsScrollY = 0f; return }
                for ((idx, rect) in settingsRowRects.withIndex()) {
                    if (aabbHit(x, y, rect)) {
                        settingsFocus = idx; settingsRows.getOrNull(idx)?.action?.invoke(); buildSettingsRows(); return
                    }
                }
            }
            Mode.PLAYING -> {
                if (useTouchNav && prevCueBtnRect[2] > 0 && aabbHit(x, y, prevCueBtnRect)) {
                    SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) }; return
                }
                if (useTouchNav && nextCueBtnRect[2] > 0 && aabbHit(x, y, nextCueBtnRect)) {
                    SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) }; return
                }
                if (aabbHit(x, y, subtitleRect)) {
                    val spanIdx = nearestWordSpan(x, y)
                    if (spanIdx >= 0) { pause(); mode = Mode.PAUSED; cursorIdx = spanIdx; updateHighlight(); lastTapTime = 0; return }
                }
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    if (x < screenW / 2) seekRelative(-10000) else seekRelative(10000); lastTapTime = 0; return
                }
                lastTapTime = now; pause(); enterPaused()
            }
        }
    }

    // UI text helpers — draw from 18sp base atlas, scaled to target sp
    private fun uiScale(targetSp: Int): Float = targetSp.toFloat() / uiBaseSp
    private fun uiText(rc: RC, s: String, x: Float, y: Float, sp: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
        val scale = uiScale(sp)
        if (scale > 0.95f && scale < 1.05f) rc.text(s, x, y, uiBasePx, r, g, b, a)
        else rc.textScaled(s, x, y, uiBasePx, scale, r, g, b, a)
    }
    private fun uiMeasure(rc: RC, s: String, sp: Int): Float = rc.measureText(s, uiBasePx) * uiScale(sp)
    private fun uiHeight(rc: RC, sp: Int): Float = rc.textAscent(uiBasePx) * uiScale(sp)

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        for ((size, atlas) in uiAtlases) rc.atlases[size] = atlas
        val subSizePx = rc.sp(subFontSize)
        if (subAtlas != null) rc.atlases[subSizePx] = subAtlas!!

        val m = renderMask
        if (app.videoSurface.frameReady) kickBlitThread(app, rc)

        if (m and Layer.VIDEO != 0) {
            if (firstFrameReceived) drawVideoQuad(app, rc)
            else rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        }

        // Skip Intro button
        if (showSkipIntro && mode == Mode.PLAYING) {
            val skipW = rc.dp(160f); val skipH = rc.dp(44f)
            val skipX = rc.w - pad - skipW; val skipY = rc.h - rc.dp(100f)
            rc.solid(skipX, skipY, skipW, skipH, 0.2f, 0.2f, 0.3f, 0.85f)
            val label = "SKIP INTRO >>"
            val lw = uiMeasure(rc, label, 14)
            uiText(rc, label, skipX + (skipW - lw) / 2f, skipY + rc.dp(28f), 14, 1f, 1f, 1f)
            rc.tappable(skipX, skipY, skipW, skipH) {
                appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(openingMs) })
            }
        }

        // Next Episode countdown
        if (showEndingCountdown && !endingCancelled) {
            val cW = rc.dp(280f); val cH = rc.dp(48f)
            val cX = (rc.w - cW) / 2f; val cY = rc.h - rc.dp(100f)
            rc.solid(cX, cY, cW, cH, 0.1f, 0.1f, 0.2f, 0.9f)
            val label = "Next Episode in ${endingCountdown}s"
            val lw = uiMeasure(rc, label, 14)
            uiText(rc, label, cX + rc.dp(16f), cY + rc.dp(30f), 14, 1f, 1f, 1f)
            // Cancel button
            val cancelW = rc.dp(80f)
            val cancelX = cX + cW - cancelW - rc.dp(8f); val cancelY = cY + rc.dp(8f)
            rc.solid(cancelX, cancelY, cancelW, cH - rc.dp(16f), 0.5f, 0.1f, 0.1f, 0.8f)
            val clw = uiMeasure(rc, "CANCEL", 12)
            uiText(rc, "CANCEL", cancelX + (cancelW - clw) / 2f, cancelY + rc.dp(20f), 12, 1f, 1f, 1f)
            rc.tappable(cancelX, cancelY, cancelW, cH - rc.dp(16f)) { endingCancelled = true }
        }

        if (m and Layer.SPINNER != 0 && (isBuffering || !firstFrameReceived)) {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
            rc.spinner(rc.w / 2f, rc.h / 2f, elapsed)
        }

        if (m and Layer.CONTROLS != 0) {
            if (useTouchNav) drawCueButtons(rc)
            if (mode == Mode.PAUSED) drawControls(rc)
        }

        if (m and Layer.CUE != 0 && currentCueText.isNotEmpty() && subAtlas != null) drawCueLayer(rc)
        else if (m and Layer.CUE == 0 || currentCueText.isEmpty()) { subtitleRect = floatArrayOf(0f, 0f, 0f, 0f); charBoxes = emptyList() }

        if (m and Layer.DICT != 0 && mode == Mode.PAUSED && hlStart >= 0) drawDictPopup(rc) else {
            dictPopupVisible = false; mineBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
            ankiCheckWord = ""; ankiCheckResult = 0
        }

        if (m and Layer.SETTINGS != 0 && mode == Mode.SETTINGS) drawSettingsPanel(rc)

        if (m and Layer.FPS != 0) {
            val blitMs = app.blitThread?.lastBlitMs ?: 0f
            uiText(rc, "${app.fps}fps  blit:${"%.1f".format(blitMs)}", rc.dp(8f), rc.dp(16f), 10, 0.4f, 0.8f, 0.4f)
            // Debug overlay handled by App.onDrawFrame
        }
    }

    // ── Cue Layer ──

    private fun buildCueLayout(rc: RC): CueLayout {
        val text = currentCueText
        val fontSize = rc.sp(subFontSize)
        val lineH = rc.textHeight(fontSize)
        val furiScale = 0.5f
        val ascent = rc.textAscent(fontSize)

        val displayText = convertForReadingMode(text)
        val lines = displayText.split("\n")
        val numLines = lines.size
        val lineWidths = lines.map { rc.measureText(it, fontSize) }
        val rowGap = lineH * (deltaRow - 1f)

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
                val chW = rc.measureText(ch, fontSize) + deltaSpacing * rc.density
                chars.add(CueCharDraw(ch, cx, lineY))
                boxes.add(CharBox(cx, lineY - ascent, chW, lineH, globalCharIdx))
                cx += chW; globalCharIdx++
            }
            if (lineIdx < numLines - 1) globalCharIdx++
        }

        val furiDraws = mutableListOf<CueFuriDraw>()
        val furiH = lineH * furiScale
        val furiAsc = ascent * furiScale

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
                        val prefixW = rc.measureText(prefix, fontSize) + localIdx * deltaSpacing * rc.density
                        val charStr = lineText.substring(localIdx, (localIdx + 1).coerceAtMost(lineText.length))
                        val charW = rc.measureText(charStr, fontSize) + deltaSpacing * rc.density
                        val furiW = rc.measureText(furi.reading, fontSize) * furiScale
                        val sx = if (furiW > charW) charW / furiW else 1f
                        val dw = furiW * sx
                        val fx = lineX + prefixW + (charW - dw) / 2f
                        val fy = lineY - lineH * deltaFurigana
                        furiDraws.add(CueFuriDraw(furi.reading, fx, fy, fontSize, sx * furiScale, dw))
                    }
                }
            }
        }

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
        val layout = buildCueLayout(rc)
        charBoxes = layout.boxes
        subtitleRect = layout.shadeRect

        val s = layout.shadeRect
        if (s[2] > 0f) {
            if (einkMode) rc.solid(s[0], s[1], s[2], s[3], 1f, 1f, 1f, 0.95f)
            else rc.solid(s[0], s[1], s[2], s[3], 0f, 0f, 0f, 0.7f)
        }

        if (hlStart >= 0 && hlEnd > hlStart) {
            for ((lineText, lineY, globalStart) in layout.lineInfos) {
                drawLineHighlight(rc, lineText, (rc.w - rc.measureText(lineText, layout.fontSize)) / 2f,
                    lineY, layout.lineH, layout.fontSize, globalStart)
            }
        }

        val textR = if (einkMode) 0f else 1f
        val textG = if (einkMode) 0f else 1f
        val textB = if (einkMode) 0f else 1f
        for ((lineText, lineY, _) in layout.lineInfos) {
            val lineW = rc.measureText(lineText, layout.fontSize)
            val lineX = (rc.w - lineW) / 2f
            rc.text(lineText, lineX, lineY, layout.fontSize, textR, textG, textB)
        }

        if (debugBoxes) {
            val dbgT = rc.dp(2f)
            for (b in layout.boxes) {
                rc.solid(b.x, b.y, b.w, dbgT, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x, b.y + b.h - dbgT, b.w, dbgT, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x, b.y, dbgT, b.h, 1f, 0f, 0f, 0.8f)
                rc.solid(b.x + b.w - dbgT, b.y, dbgT, b.h, 1f, 0f, 0f, 0.8f)
            }
        }

        val furiR = if (einkMode) 0.2f else 0.7f
        val furiG = if (einkMode) 0.2f else 0.7f
        val furiB = if (einkMode) 0.3f else 0.85f
        for (f in layout.furis) {
            if (f.scaleX < 1f) rc.textScaled(f.text, f.x, f.y, f.size, f.scaleX, furiR, furiG, furiB)
            else rc.text(f.text, f.x, f.y, f.size, furiR, furiG, furiB)
        }
    }

    private fun drawLineHighlight(rc: RC, lineText: String, lineX: Float, lineY: Float,
                                  lineH: Float, fontSize: Int, lineGlobalStart: Int) {
        val hlLocalStart = (hlStart - lineGlobalStart).coerceIn(0, lineText.length)
        val hlLocalEnd = (hlEnd - lineGlobalStart).coerceIn(0, lineText.length)
        if (hlLocalStart >= hlLocalEnd) return
        val prefix = lineText.substring(0, hlLocalStart)
        val highlighted = lineText.substring(hlLocalStart, hlLocalEnd)
        val prefixW = rc.measureText(prefix, fontSize) + hlLocalStart * deltaSpacing * rc.density
        val hlW = rc.measureText(highlighted, fontSize) + (hlLocalEnd - hlLocalStart) * deltaSpacing * rc.density
        val ascent = rc.textAscent(fontSize)
        rc.solid(lineX + prefixW, lineY - ascent, hlW, lineH, 0.733f, 0.525f, 0.988f, 0.3f)
    }

    private fun convertForReadingMode(text: String) = when (readingMode) {
        ReadingMode.PRO, ReadingMode.ADVANCED -> text
        ReadingMode.INTERMEDIATE -> convertToHiragana(text)
        ReadingMode.NOVICE -> convertToRomaji(text)
    }

    private fun convertToHiragana(text: String): String {
        if (wordSpans.isEmpty()) return ReadingUtils.kata2hira(text)
        val sb = StringBuilder(); var pos = 0
        for (span in wordSpans) {
            if (span.start > pos) sb.append(ReadingUtils.kata2hira(text.substring(pos, span.start)))
            sb.append(ReadingUtils.kata2hira(span.reading.ifEmpty { span.surface })); pos = span.end
        }
        if (pos < text.length) sb.append(ReadingUtils.kata2hira(text.substring(pos)))
        return sb.toString()
    }

    private fun convertToRomaji(text: String): String {
        if (wordSpans.isEmpty()) return ReadingUtils.toRomaji(text)
        val sb = StringBuilder(); var pos = 0
        for (span in wordSpans) {
            if (span.start > pos) sb.append(ReadingUtils.toRomaji(text.substring(pos, span.start)))
            sb.append(ReadingUtils.toRomaji(span.reading.ifEmpty { span.surface })); pos = span.end
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

        // Async AnkiDroid check — trigger when word changes
        if (ankiCheckWord != entry.term) {
            ankiCheckWord = entry.term
            ankiCheckResult = 1 // checking
            val app = appRef
            val term = entry.term
            kotlin.concurrent.thread {
                try {
                    val client = app?.ankiClient ?: run { ankiCheckResult = 2; return@thread }
                    if (!client.isAvailable() || !client.hasPermission()) { ankiCheckResult = 2; return@thread }
                    val api = com.ichi2.anki.api.AddContentApi(client.context)
                    val models = api.getModelList(1) ?: run { ankiCheckResult = 2; return@thread }
                    val modelId = models.entries.firstOrNull { it.value == AnkiDroidClient.MODEL_NAME }?.key
                    if (modelId == null) { ankiCheckResult = 2; return@thread }
                    val dupes = api.findDuplicateNotes(modelId, term)
                    if (dupes == null || dupes.isEmpty()) { ankiCheckResult = 2; return@thread }
                    // Check if any dupe is in the Immersion deck
                    val decks = api.deckList
                    val immersionDeckId = decks?.entries?.firstOrNull { it.value == app.ankiDeckName }?.key
                    if (immersionDeckId == null) { ankiCheckResult = 2; return@thread }
                    var found = false
                    val cr = client.context.contentResolver
                    for (dupe in dupes) {
                        val noteId = dupe.key
                        val cardUri = android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes/$noteId/cards")
                        val cursor = cr.query(cardUri, arrayOf("deckId"), null, null, null)
                        if (cursor != null) {
                            while (cursor.moveToNext()) {
                                if (cursor.getLong(0) == immersionDeckId) { found = true; break }
                            }
                            cursor.close()
                        }
                        if (found) break
                    }
                    ankiCheckResult = if (found) 3 else 2
                } catch (_: Exception) { ankiCheckResult = 2 }
            }
        }

        val popupW = rc.dp(300f).coerceAtMost(rc.w * 0.8f)
        val popupX = (rc.w - popupW) / 2f
        val padP = rc.dp(16f)
        val sz = rc.sp(subFontSize)
        val sa = subAtlas ?: return
        val baseAsc = sa.ascent
        val half = 0.5f

        fun scaledW(text: String, scale: Float) = rc.measureText(text, sz) * scale
        fun scaledH(scale: Float) = baseAsc * scale

        // Term with furigana — same layout as cue layer
        val termW = scaledW(entry.term, 1f)
        val lineH = rc.textHeight(sz)
        val furiH = lineH * half
        val hasFuri = span.furigana.isNotEmpty()
        val termBlockH = scaledH(1f) + (if (hasFuri) lineH * deltaFurigana else 0f)

        var contentH = rc.dp(6f)
        contentH += termBlockH + rc.dp(2f)
        if (span.inflection.isNotEmpty() || entry.jlpt.isNotEmpty())
            contentH += scaledH(half) + rc.dp(4f)
        contentH += entry.meanings.size * (scaledH(half) + rc.dp(2f))
        contentH += rc.dp(4f)

        val subTop = subtitleRect[1]
        val popupY = (subTop - contentH - rc.dp(8f)).coerceAtLeast(rc.dp(8f))
        dictPopupRect = floatArrayOf(popupX, popupY, popupW, contentH); dictPopupVisible = true
        rc.solid(popupX, popupY, popupW, contentH, 0.118f, 0.118f, 0.180f, 0.94f)

        var cy = popupY + rc.dp(4f)

        // Term at full size, centered, with furigana above kanji
        val termX = popupX + (popupW - termW) / 2f
        val termY = cy + termBlockH
        rc.text(entry.term, termX, termY, sz, 1f, 1f, 1f)

        // Mine button — top right
        val mineBtnW = rc.dp(40f); val mineBtnH = scaledH(half) + rc.dp(6f)
        val mineBtnX = popupX + popupW - mineBtnW - rc.dp(6f)
        val mineBtnY = cy + (termBlockH - mineBtnH) / 2f
        val label: String; val bgR: Float; val bgG: Float; val bgB: Float; val tR: Float; val tG: Float; val tB: Float
        when (ankiCheckResult) {
            1 -> { label = ".."; bgR = 0.2f; bgG = 0.2f; bgB = 0.3f; tR = 0.6f; tG = 0.6f; tB = 0.6f } // checking
            3 -> { label = "ok"; bgR = 0.15f; bgG = 0.4f; bgB = 0.15f; tR = 0.5f; tG = 0.9f; tB = 0.5f } // in anki
            4 -> { label = mineStatus.ifEmpty { ".." }; bgR = 0.3f; bgG = 0.3f; bgB = 0.1f; tR = 1f; tG = 0.9f; tB = 0.3f } // mining
            5 -> { label = "!"; bgR = 0.5f; bgG = 0.1f; bgB = 0.1f; tR = 1f; tG = 0.3f; tB = 0.3f } // failed
            else -> { label = "+"; bgR = 0.733f; bgG = 0.525f; bgB = 0.988f; tR = 1f; tG = 1f; tB = 1f } // available
        }
        rc.solid(mineBtnX, mineBtnY, mineBtnW, mineBtnH, bgR, bgG, bgB, 0.9f)
        val lw = rc.measureText(label, sz) * half
        rc.textScaled(label, mineBtnX + (mineBtnW - lw) / 2f, mineBtnY + scaledH(half) + rc.dp(1f), sz, half, tR, tG, tB)
        // Only tappable if available (2) or failed (5, retry)
        mineBtnRect = if (ankiCheckResult == 2 || ankiCheckResult == 5) floatArrayOf(mineBtnX, mineBtnY, mineBtnW, mineBtnH)
                      else floatArrayOf(0f, 0f, 0f, 0f)

        // Furigana per-kanji — same positioning as cue layer
        if (hasFuri) {
            for (furi in span.furigana) {
                if (furi.charIdx >= entry.term.length) continue
                val prefix = entry.term.substring(0, furi.charIdx)
                val prefixW = rc.measureText(prefix, sz)
                val ch = entry.term.substring(furi.charIdx, (furi.charIdx + 1).coerceAtMost(entry.term.length))
                val charW = rc.measureText(ch, sz)
                val furiW = rc.measureText(furi.reading, sz) * half
                val sx = if (furiW > charW) (charW / furiW) * half else half
                val dw = rc.measureText(furi.reading, sz) * sx
                val fx = termX + prefixW + (charW - dw) / 2f
                val fy = termY - lineH * deltaFurigana
                rc.textScaled(furi.reading, fx, fy, sz, sx, 0.6f, 0.6f, 0.85f)
            }
        }
        cy += termBlockH + rc.dp(2f)

        var badgeX = popupX + rc.dp(8f)
        if (span.inflection.isNotEmpty()) {
            val bw = scaledW(span.inflection, half) + rc.dp(8f)
            rc.solid(badgeX, cy, bw, scaledH(half) + rc.dp(2f), 0.13f, 0.13f, 0.2f, 0.8f)
            rc.textScaled(span.inflection, badgeX + rc.dp(4f), cy + scaledH(half), sz, half, 0.475f, 0.525f, 0.796f)
            badgeX += bw + rc.dp(2f)
        }
        if (entry.jlpt.isNotEmpty()) {
            val bw = scaledW(entry.jlpt, half) + rc.dp(8f)
            rc.solid(badgeX, cy, bw, scaledH(half) + rc.dp(2f), 0.13f, 0.13f, 0.2f, 0.8f)
            rc.textScaled(entry.jlpt, badgeX + rc.dp(4f), cy + scaledH(half), sz, half, 0.31f, 0.765f, 0.969f)
        }
        if (span.inflection.isNotEmpty() || entry.jlpt.isNotEmpty())
            cy += scaledH(half) + rc.dp(4f)

        for ((i, meaning) in entry.meanings.withIndex()) {
            val mText = "${i + 1}. $meaning"
            rc.textScaled(mText, popupX + rc.dp(8f), cy + scaledH(half), sz, half, 0.8f, 0.8f, 0.8f)
            cy += scaledH(half) + rc.dp(2f)
        }
    }

    // ── Settings Panel ──

    private fun playNextEpisode(app: App) {
        val nextEp = page.episode.copy(episode = page.episode.episode + 1)
        val fn = page.episode.filename
        val nextFn = fn.replace(Regex("\\d+\\.mkv$"), "${nextEp.episode}.mkv")
        cleanup(app)
        app.navigate(App.Nav.Player(page.item, nextEp.copy(filename = nextFn), page.baseUrl))
    }

    private fun applyPlaybackSpeed() {
        appRef?.onMainThread?.invoke(Runnable {
            appRef?.exoPlayer?.setPlaybackParameters(
                androidx.media3.common.PlaybackParameters(playbackSpeed))
        })
    }

    private fun openSettings() { mode = Mode.SETTINGS; settingsFocus = 0; buildSettingsRows() }

    private fun buildSettingsRows() {
        val rows = mutableListOf<SettingsRow>()
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
                        val selected = group.isTrackSelected(i); val idx = trackIdx
                        rows.add(SettingsRow(label, format.language ?: "", "", indent = true, selected = selected) { selectAudioTrack(idx) })
                        trackIdx++
                    }
                }
            }
        } catch (_: Exception) {}
        rows.add(SettingsRow("Subtitle", "", "subs"))
        for (sub in page.episode.subtitles) {
            rows.add(SettingsRow(sub.label, sub.language, "", indent = true, selected = sub.language == selectedSubLang) {
                selectedSubLang = sub.language; loadSubtitleTrack(sub)
            })
        }
        rows.add(SettingsRow("Reading Mode", readingMode.name, "mode") { cycleReadingMode(); savePrefs() })
        rows.add(SettingsRow("Font", fontNames[currentFontIdx], "font") { cycleFont(); savePrefs() })
        rows.add(SettingsRow("Font Size", "${subFontSize}sp", "size") { cycleFontSize(); savePrefs() })
        rows.add(SettingsRow("Condensed", if (condensedMode) "ON" else "OFF", "cond") { condensedMode = !condensedMode; savePrefs() })
        rows.add(SettingsRow("Speed", "%.1fx".format(playbackSpeed), "speed",
            isSlider = true, sliderRange = 0.5f to 2.0f, sliderValue = playbackSpeed,
            onSlide = { playbackSpeed = (Math.round(it * 10) / 10f); applyPlaybackSpeed() }))
        rows.add(SettingsRow("DF (Furigana)", "%.1f".format(deltaFurigana), "DF",
            isSlider = true, sliderRange = 0.3f to 1.5f, sliderValue = deltaFurigana, onSlide = { deltaFurigana = it; savePrefs() }))
        rows.add(SettingsRow("DR (Row Space)", "%.1f".format(deltaRow), "DR",
            isSlider = true, sliderRange = 0.5f to 2.0f, sliderValue = deltaRow, onSlide = { deltaRow = it; savePrefs() }))
        rows.add(SettingsRow("DS (Letter Space)", "%.1f".format(deltaSpacing), "DS",
            isSlider = true, sliderRange = -4f to 8f, sliderValue = deltaSpacing, onSlide = { deltaSpacing = it; savePrefs() }))
        rows.add(SettingsRow("DY (Y Offset)", "%.0f".format(deltaYShift), "DY",
            isSlider = true, sliderRange = -50f to 50f, sliderValue = deltaYShift, onSlide = { deltaYShift = it; savePrefs() }))
        rows.add(SettingsRow("Theme", if (einkMode) "E-Ink" else "Dark", "theme") {
            einkMode = !einkMode; appRef?.einkMode = einkMode; savePrefs()
        })
        rows.add(SettingsRow("Debug Boxes", if (debugBoxes) "ON" else "OFF", "debug") { debugBoxes = !debugBoxes; savePrefs() })
        settingsRows = rows
    }

    private fun drawSettingsPanel(rc: RC) {
        val panelW = rc.dp(300f).coerceAtMost(rc.w * 0.4f)
        val panelX = rc.w - panelW; settingsPanelX = panelX
        rc.solid(panelX, 0f, panelW, rc.h, 0.102f, 0.102f, 0.180f)

        uiText(rc, Lang.s("settings"), panelX + rc.dp(16f), rc.dp(32f), 18, 1f, 1f, 1f)

        val rowH = rc.dp(44f)
        var y = rc.dp(56f) - settingsScrollY
        val rects = mutableListOf<FloatArray>()

        for ((idx, row) in settingsRows.withIndex()) {
            val focused = idx == settingsFocus; val rowY = y
            if (focused) rc.solid(panelX + rc.dp(4f), rowY, panelW - rc.dp(8f), rowH, 0.733f, 0.525f, 0.988f, 0.3f)
            else if (row.selected) rc.solid(panelX + rc.dp(4f), rowY, panelW - rc.dp(8f), rowH, 0.165f, 0.165f, 0.29f, 0.5f)

            val labelX = panelX + if (row.indent) rc.dp(42f) else rc.dp(14f)
            val labelY = rowY + rowH / 2f + uiHeight(rc, 14) / 3f

            if (row.selected && row.indent)
                uiText(rc, "●", labelX - rc.dp(12f), labelY, 8, 0.506f, 0.78f, 0.522f)

            uiText(rc, row.label, labelX, labelY, 14, if (focused) 1f else 0.93f, if (focused) 1f else 0.93f, if (focused) 1f else 0.93f)

            if (row.value.isNotEmpty()) {
                val vw = uiMeasure(rc, row.value, 13)
                uiText(rc, row.value, panelX + panelW - rc.dp(14f) - vw, labelY, 13, 0.506f, 0.78f, 0.522f)
            }
            rects.add(floatArrayOf(panelX, rowY, panelW, rowH))
            y += rowH

            if (row.isSlider) {
                val sliderPad = rc.dp(14f); val sliderX = panelX + sliderPad; val sliderW = panelW - sliderPad * 2
                val sliderY = y + rc.dp(4f); val trackH = rc.dp(4f); val handleR = rc.dp(8f)
                rc.solid(sliderX, sliderY, sliderW, trackH, 0.25f, 0.25f, 0.35f)
                val (lo, hi) = row.sliderRange
                val t = ((row.sliderValue - lo) / (hi - lo)).coerceIn(0f, 1f)
                rc.solid(sliderX, sliderY, sliderW * t, trackH, 0.733f, 0.525f, 0.988f)
                val handleX = sliderX + sliderW * t
                rc.solid(handleX - handleR, sliderY - handleR + trackH / 2f, handleR * 2, handleR * 2, 1f, 1f, 1f)
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
        row.onSlide?.invoke(lo + t * (hi - lo)); buildSettingsRows()
    }

    private fun handleSettingsSelect() { settingsRows.getOrNull(settingsFocus)?.action?.invoke(); buildSettingsRows() }

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

    private fun cycleReadingMode() { readingMode = when (readingMode) {
        ReadingMode.NOVICE -> ReadingMode.INTERMEDIATE; ReadingMode.INTERMEDIATE -> ReadingMode.ADVANCED
        ReadingMode.ADVANCED -> ReadingMode.PRO; ReadingMode.PRO -> ReadingMode.NOVICE
    }}

    private fun cycleFontSize() {
        subFontSize = when (subFontSize) { 24 -> 32; 32 -> 44; else -> 24 }
        rebuildSubtitleAtlases()
    }

    private fun cycleFont() {
        currentFontIdx = (currentFontIdx + 1) % fontNames.size
        rebuildSubtitleAtlases()
    }

    private fun mineCurrentWord() {
        val app = appRef ?: return
        val span = wordSpans.getOrNull(cursorIdx) ?: return
        val dict = superSRT?.dict ?: return
        if (span.dictIdx < 0 || span.dictIdx >= dict.size) return
        val entry = dict[span.dictIdx]
        val cue = findCueAtPosition(positionMs) ?: return

        if (ankiCheckResult == 4) return // already mining

        // Check AnkiDroid permission — request if missing
        if (!app.ankiClient.isAvailable()) {
            app.lastLoadLog.add("mine: ankidroid not installed"); ankiCheckResult = 5; mineStatus = "!"; return
        }
        if (!app.ankiClient.hasPermission()) {
            app.onMainThread?.invoke {
                val activity = app.context as? android.app.Activity
                if (activity != null) app.ankiClient.requestPermission(activity)
            }
            app.lastLoadLog.add("mine: requesting permission")
            ankiCheckResult = 5; mineStatus = "!"
            return
        }

        ankiCheckResult = 4 // mining in progress
        mineStatus = "..."

        fun mlog(msg: String) { app.lastLoadLog.add("mine: $msg"); android.util.Log.d("Mine", msg) }

        val meanings = entry.meanings.joinToString("; ")
        val reading = ReadingUtils.buildFuriganaReading(entry.term, span.furigana)
            .ifEmpty { entry.reading.ifEmpty { entry.term } }

        val expression = entry.term
        val sentence = cue.text
        val jlpt = entry.jlpt

        val superCue = superCues.firstOrNull { it.startMs.toLong() == cue.startMs }
        val sentenceFurigana = if (superCue != null) ReadingUtils.buildFuriganaSentence(superCue.words) else sentence
        val startMs = cue.startMs.toDouble()
        val endMs = cue.endMs.toDouble()
        val baseUrl = page.baseUrl
        val token = app.api?.token ?: ""

        kotlin.concurrent.thread {
            try {
                // Step 1: Resolve media from server
                mlog("resolving $expression...")
                mineStatus = "server"
                val json = org.json.JSONObject().apply {
                    put("item_id", page.item.id); put("season", page.episode.season)
                    put("episode", page.episode.episode)
                    put("start_ms", startMs); put("end_ms", endMs)
                }
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(), json.toString())
                val req = okhttp3.Request.Builder()
                    .url("$baseUrl/api/card-resolve")
                    .header("Authorization", "Bearer $token")
                    .post(body).build()
                val resp = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(req).execute()
                if (!resp.isSuccessful) { mlog("server ${resp.code}"); ankiCheckResult = 5; mineStatus = "!"; return@thread }
                val bytes = resp.body?.bytes() ?: run { mlog("empty response"); ankiCheckResult = 5; mineStatus = "!"; return@thread }

                // Parse blob
                var off = 0
                fun readInt(): Int {
                    val v = (bytes[off].toInt() and 0xFF) or ((bytes[off+1].toInt() and 0xFF) shl 8) or
                            ((bytes[off+2].toInt() and 0xFF) shl 16) or ((bytes[off+3].toInt() and 0xFF) shl 24)
                    off += 4; return v
                }
                val metaLen = readInt(); off += metaLen
                val audioLen = readInt()
                val audioData = if (audioLen > 0) bytes.copyOfRange(off, off + audioLen) else null; off += audioLen
                val imageLen = readInt()
                val imageData = if (imageLen > 0) bytes.copyOfRange(off, off + imageLen) else null

                mlog("audio=${audioLen}B img=${imageLen}B")
                mineStatus = "anki"

                // Step 2: Create card via AnkiDroidClient
                mineStatus = "anki"
                val ts = System.currentTimeMillis()
                var imgFile: java.io.File? = null
                var audioFile: java.io.File? = null
                if (imageData != null) {
                    imgFile = java.io.File(app.context.cacheDir, "mine_$ts.jpg")
                    imgFile.writeBytes(imageData)
                }
                if (audioData != null) {
                    audioFile = java.io.File(app.context.cacheDir, "mine_$ts.mp3")
                    audioFile.writeBytes(audioData)
                }

                val source = "${page.item.title()} E${page.episode.episode}"
                val cardInfo = AnkiDroidClient.CardInfo(
                    expression = expression, reading = reading, meaning = meanings,
                    sentence = sentence, sentenceFurigana = sentenceFurigana,
                    source = source, jlpt = jlpt,
                    screenshotFile = imgFile, audioFile = audioFile,
                )
                val result = app.ankiClient.addCard(cardInfo, app.ankiDeckName)
                imgFile?.delete()
                audioFile?.delete()

                when (result) {
                    is AnkiDroidClient.Result.Success -> mlog("card $expression id=${result.noteId}")
                    is AnkiDroidClient.Result.Duplicate -> mlog("dupe $expression")
                    else -> { mlog("anki failed: $result"); ankiCheckResult = 5; mineStatus = "!"; return@thread }
                }
                ankiCheckResult = 3
                mineStatus = ""

            } catch (e: Exception) {
                mlog("err: ${e.message}")
                ankiCheckResult = 5
                mineStatus = "!"
            }
        }
    }

    private fun findCueAtPosition(posMs: Long): SrtParser.Cue? {
        for (cue in cues) {
            if (posMs >= cue.startMs && posMs <= cue.endMs) return cue
        }
        return null
    }

    private var subGlyphLayer = -1
    private var subGlyphY = -1

    private fun rebuildSubtitleAtlases() {
        val app = appRef ?: return
        val tf = try { android.graphics.Typeface.createFromAsset(app.context.assets, fontAssets[currentFontIdx]) }
                 catch (_: Exception) { app.defaultTypeface }
        val d = app.density
        val texW = app.texArray.size

        val allTexts = mutableListOf<String>()
        val srt = superSRT
        if (srt != null) {
            for (cue in srt.cues) for (w in cue.words) {
                allTexts.add(w.surface); allTexts.add(w.reading)
                if (w.inflection.isNotEmpty()) allTexts.add(w.inflection)
                for (f in w.furigana) allTexts.add(f.reading)
            }
            for (entry in srt.dict) {
                allTexts.add(entry.term); allTexts.add(entry.reading)
                allTexts.addAll(entry.meanings); allTexts.add(entry.jlpt)
            }
        } else {
            for (cue in cues) allTexts.add(cue.text)
        }
        allTexts.add(ReadingUtils.HIRAGANA); allTexts.add(ReadingUtils.KATAKANA)
        allTexts.add("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.();:-/ ")

        // Reset glyph cursor to where subs started — reuse same texture space
        if (subGlyphLayer >= 0) {
            app.resetGlyphCursor(subGlyphLayer, subGlyphY)
        } else {
            subGlyphLayer = app.currentGlyphLayer
            subGlyphY = app.currentGlyphY
        }

        val sa = GlyphAtlas(tf, subFontSize * d)
        app.uploadGlyphAtlas(sa, sa.build(allTexts, texW))
        subAtlas = sa
    }

    // ── Controls ──

    private var prevCueBtnRect = floatArrayOf(0f, 0f, 0f, 0f)
    private var nextCueBtnRect = floatArrayOf(0f, 0f, 0f, 0f)

    private fun drawCueButtons(rc: RC) {
        val alpha = if (mode == Mode.PAUSED) 1f else 0.4f
        val btnW = rc.dp(88f); val btnH = rc.dp(72f)
        val x = pad
        val y = rc.dp(80f)

        // ⏮ prev cue
        rc.solid(x, y, btnW, btnH, 0.13f, 0.13f, 0.2f, alpha * 0.8f)
        val sz = rc.sp(uiBaseSp)
        val pw = rc.measureText("<<", sz)
        uiText(rc, "<<", x + (btnW - pw) / 2f, y + btnH / 2f + rc.dp(8f), uiBaseSp, 1f, 1f, 1f, alpha)
        prevCueBtnRect = floatArrayOf(x, y, btnW, btnH)

        // ⏭ next cue
        val nx = x + btnW + rc.dp(8f)
        rc.solid(nx, y, btnW, btnH, 0.13f, 0.13f, 0.2f, alpha * 0.8f)
        val nw = rc.measureText(">>", sz)
        uiText(rc, ">>", nx + (btnW - nw) / 2f, y + btnH / 2f + rc.dp(8f), uiBaseSp, 1f, 1f, 1f, alpha)
        nextCueBtnRect = floatArrayOf(nx, y, btnW, btnH)
    }

    private fun drawControls(rc: RC) {
        val centerX = rc.w / 2f
        if (!isPlaying) {
            val iconSize = rc.sp(36)
            val tw = rc.measureText("▶", iconSize)
            rc.text("▶", centerX - tw / 2f, rc.h / 2f + rc.textHeight(iconSize) / 3f, iconSize, 1f, 1f, 1f, 0.8f)
        }

        uiText(rc, "${page.episode.episode}. ${page.episode.title()}", pad, rc.dp(40f), 18, 1f, 1f, 1f)

        val backFocused = pausedFocus == PausedFocus.TOP_ROW && topRowFocus == 0
        uiText(rc, "←", pad, rc.dp(60f), 22, if (backFocused) 1f else 0.8f, if (backFocused) 1f else 0.8f, if (backFocused) 1f else 0.8f)
        backBtnRect = floatArrayOf(0f, 0f, rc.dp(80f), rc.dp(80f))
        if (backFocused) rc.border(0f, 0f, rc.dp(80f), rc.dp(80f), rc.dp(3f), 0.733f, 0.525f, 0.988f)

        val setBtnW = rc.dp(120f); val setBtnH = rc.dp(48f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = rc.dp(12f)
        val settFocused = pausedFocus == PausedFocus.TOP_ROW && topRowFocus == 1
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = uiMeasure(rc, setLabel, 12)
        uiText(rc, setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(32f), 16, 0.733f, 0.525f, 0.988f)
        settingsBtnRect = floatArrayOf(setBtnX, setBtnY, setBtnW, setBtnH)
        if (settFocused) rc.border(setBtnX, setBtnY, setBtnW, setBtnH, rc.dp(3f), 0.733f, 0.525f, 0.988f)

        // Next episode button — below settings
        val nextBtnW = rc.dp(120f); val nextBtnH = rc.dp(40f)
        val nextBtnX = rc.w - pad - nextBtnW; val nextBtnY = setBtnY + setBtnH + rc.dp(8f)
        rc.solid(nextBtnX, nextBtnY, nextBtnW, nextBtnH, 0.102f, 0.102f, 0.180f)
        val nextLabel = "Next >>"
        val nextLabelW = uiMeasure(rc, nextLabel, 14)
        uiText(rc, nextLabel, nextBtnX + (nextBtnW - nextLabelW) / 2f, nextBtnY + rc.dp(26f), 14, 0.733f, 0.525f, 0.988f)
        nextEpBtnRect = floatArrayOf(nextBtnX, nextBtnY, nextBtnW, nextBtnH)

        if (renderMask and Layer.SEEKBAR == 0) return
        val seekFocused = pausedFocus == PausedFocus.SEEKBAR
        val barW = rc.w - pad * 2
        rc.solid(pad, barY, barW, rc.dp(4f), 0.3f, 0.3f, 0.4f)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        rc.solid(pad, barY, barW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)
        val handleX = pad + barW * progress
        rc.solid(handleX - rc.dp(6f), barY - rc.dp(6f), rc.dp(12f), rc.dp(16f), 1f, 1f, 1f)
        seekbarRect = floatArrayOf(pad, barY - rc.dp(24f), barW, rc.dp(48f))
        if (seekFocused) rc.border(pad, barY - rc.dp(8f), barW, rc.dp(20f), rc.dp(2f), 0.733f, 0.525f, 0.988f)

        val posStr = formatTime(positionMs); val durStr = formatTime(durationMs)
        uiText(rc, posStr, pad, barY + rc.dp(20f), 12, 0.8f, 0.8f, 0.8f)
        val durW = uiMeasure(rc, durStr, 12)
        uiText(rc, durStr, rc.w - pad - durW, barY + rc.dp(20f), 12, 0.8f, 0.8f, 0.8f)
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt(); val m = s / 60; val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60) else "%d:%02d".format(m, s % 60)
    }

    // ── Video ──

    private fun kickBlitThread(app: App, rc: RC) {
        val bt = app.blitThread ?: return
        bt.videoWidth = videoWidth; bt.videoHeight = videoHeight
        bt.screenWidth = rc.w.toInt(); bt.screenHeight = rc.h.toInt()
        bt.requestBlit()
    }

    private fun drawVideoQuad(app: App, rc: RC) {
        val bt = app.blitThread
        if (bt == null || !bt.frameReady) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        app.videoSurface.bindRgb()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        rc.batch.addQuad(0f, 0f, rc.w, rc.h, 0f, 1f, 1f, 0f, layer = -1f)
        rc.batch.flush()
        rc.batch.begin()
    }

    fun play() { isPlaying = true; appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.play() }) }
    private fun pause() { isPlaying = false; appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.pause() }) }
    private fun seekRelative(deltaMs: Long) {
        val target = (positionMs + deltaMs).coerceIn(0, durationMs); positionMs = target
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(target) })
    }
    private fun seekTo(ms: Long) { positionMs = ms; appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(ms) }) }

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
                        selectedAudioIdx = idx; return@Runnable
                    }
                    trackIdx++
                }
            }
        })
    }

    private fun loadSubtitleTrack(sub: JanusApi.SubTrack) {
        val api = appRef?.api ?: return; val token = api.token ?: ""
        val url = "${page.baseUrl}/api/subs/${page.item.id}/${sub.srtFile}"
        kotlin.concurrent.thread {
            try {
                val request = okhttp3.Request.Builder().url(url).header("Authorization", "Bearer $token").build()
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) { cues = SrtParser.parse(response.body?.string() ?: "") }
                response.close()
            } catch (_: Exception) {}
        }
    }

    override fun reinitGL(app: App) {
        android.util.Log.d("Player", "reinitGL called")
        appRef = app
        app.einkMode = einkMode

        // Rebuild glyph atlases from existing data (no network)
        val tf = try { android.graphics.Typeface.createFromAsset(app.context.assets, fontAssets[currentFontIdx]) }
                 catch (_: Exception) { app.defaultTypeface }
        val d = app.density
        val texW = app.texArray.size

        // UI atlases
        val uiTexts = listOf(
            "${page.episode.episode}. ${page.episode.title()}",
            "←", "▶", "⏮", "⏭", "●", Lang.s("settings"),
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789:.%()/-+sp <>x",
            "Audio Subtitle Reading Mode Font Size Speed Condensed Theme Debug Boxes Next SKIP INTRO CANCEL Episode in",
            "DF DR DS DY Furigana Row Space Letter Y Offset E-Ink Dark",
            "PRO ADVANCED INTERMEDIATE NOVICE ON OFF Track Japanese",
            "Noto Sans Serif Shippori Klee One",
        )
        val uiAtlas = GlyphAtlas(tf, uiBaseSp * d)
        app.uploadGlyphAtlas(uiAtlas, uiAtlas.build(uiTexts, texW))
        uiAtlases[(uiBaseSp * d).toInt()] = uiAtlas
        val iconAtlas = GlyphAtlas(tf, 36 * d)
        app.uploadGlyphAtlas(iconAtlas, iconAtlas.build(listOf("▶"), texW))
        uiAtlases[(36 * d).toInt()] = iconAtlas

        // Subtitle atlas
        subGlyphLayer = -1
        rebuildSubtitleAtlases()

        // Context preserved — ExoPlayer keeps rendering to the same Surface
    }

    override fun cleanup(app: App) {
        saveProgress()
        alive = false
        app.onMainThread?.invoke(Runnable {
            app.exoPlayer?.stop(); app.exoPlayer?.clearMediaItems(); app.exoPlayer?.setVideoSurface(null)
        })
        appRef = null
    }
}
