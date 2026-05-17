package com.janusplus.v2

import android.opengl.GLES30
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.SrtParser
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
    enum class Mode { PLAYING, CONTROLS, WORD_NAV, SETTINGS }
    enum class ReadingMode { PRO, ADVANCED, INTERMEDIATE, NOVICE }

    @Volatile var mode = Mode.PLAYING
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var isPlaying = true

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

    // Controls
    private var controlsTimer = 0f
    private val CONTROLS_TIMEOUT = 5f

    // Double-tap detection
    private var lastTapTime = 0L
    private var lastTapX = 0f

    // Seekbar drag
    private var isDraggingSeekbar = false

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

    data class SettingsRow(val label: String, val value: String, val icon: String,
                           val indent: Boolean = false, val selected: Boolean = false,
                           val action: () -> Unit = {})

    // Condensed mode
    var condensedMode = false

    override fun init(app: App) {
        appRef = app
        mode = Mode.PLAYING
        controlsTimer = 0f

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
        // Update video texture
        if (app.videoSurface.updateTexture()) {
            firstFrameReceived = true
        }

        // Find current cue (SuperSRT takes priority)
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

        // Controls auto-hide
        if (mode == Mode.CONTROLS) {
            controlsTimer += 0.016f
            if (controlsTimer > CONTROLS_TIMEOUT && isPlaying) {
                mode = Mode.PLAYING
            }
        }

        // Touch handling — all actions (DOWN=0, MOVE=2, UP=1)
        for (t in touches) {
            when (t.action) {
                0 -> { // ACTION_DOWN
                    // Check if starting a seekbar drag
                    if ((mode == Mode.CONTROLS || mode == Mode.WORD_NAV) && screenW > 0) {
                        val seekTop = barY - 36f
                        val seekBot = barY + 48f
                        if (t.y >= seekTop && t.y <= seekBot && t.x >= pad && t.x <= screenW - pad) {
                            isDraggingSeekbar = true
                            val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                            seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                        }
                    }
                }
                2 -> { // ACTION_MOVE
                    if (isDraggingSeekbar && screenW > 0) {
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        positionMs = (durationMs * progress).toLong().coerceIn(0, durationMs)
                    }
                }
                1 -> { // ACTION_UP
                    if (isDraggingSeekbar) {
                        isDraggingSeekbar = false
                        val progress = ((t.x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                        seekTo((durationMs * progress).toLong().coerceIn(0, durationMs))
                        controlsTimer = 0f
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
                    Mode.PLAYING -> { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
                    Mode.CONTROLS -> { mode = Mode.PLAYING; play() }
                    Mode.WORD_NAV -> { /* mine word — future Anki integration */ }
                    Mode.SETTINGS -> { handleSettingsSelect() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.CONTROLS -> seekRelative(-10000)
                    Mode.WORD_NAV -> { moveCursor(-1) }
                    Mode.SETTINGS -> { handleSettingsLeft() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.CONTROLS -> seekRelative(10000)
                    Mode.WORD_NAV -> { moveCursor(1) }
                    Mode.SETTINGS -> { handleSettingsRight() }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                when (mode) {
                    Mode.PLAYING -> { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
                    Mode.CONTROLS -> {
                        if (currentCueText.isNotEmpty() && wordSpans.isNotEmpty()) {
                            enterWordNav()
                        }
                    }
                    Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f }
                    Mode.SETTINGS -> { settingsFocus = (settingsFocus - 1).coerceAtLeast(0) }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (mode) {
                    Mode.PLAYING -> {
                        pause()
                        if (currentCueText.isNotEmpty() && wordSpans.isNotEmpty()) {
                            enterWordNav()
                        } else {
                            mode = Mode.CONTROLS; controlsTimer = 0f
                        }
                    }
                    Mode.CONTROLS -> { mode = Mode.PLAYING; play() }
                    Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f }
                    Mode.SETTINGS -> { settingsFocus = (settingsFocus + 1).coerceAtMost((settingsRows.size - 1).coerceAtLeast(0)) }
                }
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                when (mode) {
                    Mode.SETTINGS -> { mode = Mode.CONTROLS; controlsTimer = 0f }
                    Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f; hlStart = -1; hlEnd = -1 }
                    else -> {}
                }
            }
            // 'S' key opens settings from controls
            android.view.KeyEvent.KEYCODE_S -> {
                if (mode == Mode.CONTROLS) openSettings()
            }
        }
    }

    private fun enterWordNav() {
        if (wordSpans.isEmpty()) return
        mode = Mode.WORD_NAV
        cursorIdx = 0
        updateHighlight()
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

    private fun handleTap(app: App, x: Float, y: Float) {
        // Seekbar — AABB check, same rect as debug green zone
        val seekTop = barY - 36f  // dp(24f) at density ~1.5
        val seekBottom = barY + 36f  // dp(24f) below
        if ((mode == Mode.CONTROLS || mode == Mode.WORD_NAV) && screenW > 0) {
            if (y >= seekTop && y <= seekBottom + 36f && x >= pad && x <= screenW - pad) {
                val progress = ((x - pad) / (screenW - pad * 2)).coerceIn(0f, 1f)
                val target = (durationMs * progress).toLong().coerceIn(0, durationMs)
                seekTo(target)
                controlsTimer = 0f
                return
            }
        }

        // Subtitle character tap
        if (charBoxes.isNotEmpty() && mode != Mode.SETTINGS) {
            for (box in charBoxes) {
                if (x >= box.x && x <= box.x + box.w && y >= box.y && y <= box.y + box.h) {
                    val charIdx = box.charIdx
                    val spanIdx = wordSpans.indexOfFirst { charIdx >= it.start && charIdx < it.end }
                    if (spanIdx >= 0) {
                        if (mode != Mode.WORD_NAV) pause()
                        mode = Mode.WORD_NAV
                        cursorIdx = spanIdx
                        updateHighlight()
                        return
                    }
                }
            }
        }

        // Settings — tap outside to close
        if (mode == Mode.SETTINGS) {
            val panelX = screenW - screenW * 0.35f
            if (x < panelX) {
                mode = Mode.CONTROLS
                controlsTimer = 0f
            }
            return
        }

        // Double-tap seek
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 300) {
            if (x < screenW / 2) seekRelative(-10000)
            else seekRelative(10000)
            lastTapTime = 0
            return
        }
        lastTapTime = now

        // Single tap — toggle controls
        when (mode) {
            Mode.PLAYING -> { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
            Mode.CONTROLS -> { mode = Mode.PLAYING; play() }
            Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f; hlStart = -1; hlEnd = -1 }
            else -> {}
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        // ── Video quad (full screen) — black until first frame
        if (firstFrameReceived) {
            drawVideoQuad(app, rc)
        } else {
            rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        }

        // Buffering indicator
        if (isBuffering || !firstFrameReceived) {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
            val pulse = 0.5f + 0.3f * kotlin.math.sin(elapsed * 4f).toFloat()
            val loadText = Lang.s("loading")
            val tw = rc.font.measureText(loadText, rc.sp(16))
            rc.text(loadText, (rc.w - tw) / 2f, rc.h / 2f, rc.sp(16), pulse, pulse, pulse)
        }

        // ── Controls overlay (BEHIND subtitles) ──
        if (mode == Mode.CONTROLS || mode == Mode.WORD_NAV) {
            drawControls(app, rc)
        }

        // ── Subtitle (cue layer, ON TOP of controls) ──
        if (currentCueText.isNotEmpty()) {
            drawCueLayer(rc)
        }

        // ── Dictionary popup (above subtitle) ──
        if (mode == Mode.WORD_NAV) {
            drawDictPopup(rc)
        }

        // ── Settings panel ──
        if (mode == Mode.SETTINGS) {
            drawSettingsPanel(rc)
        }

        // ── FPS ──
        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    // ── Cue Layer (subtitle rendering with reading modes) ──

    private fun drawCueLayer(rc: RC) {
        val text = currentCueText
        val fontSize = rc.sp(subFontSize)
        val lineH = rc.font.textHeight(fontSize)
        val furiganaSize = rc.sp((subFontSize * 0.45f).toInt())

        // Convert text per reading mode
        val displayText = convertForReadingMode(text)
        val lines = displayText.split("\n")
        val numLines = lines.size

        // Measure each line
        val lineWidths = lines.map { rc.font.measureText(it, fontSize) }
        val maxLineW = lineWidths.maxOrNull() ?: 0f

        // Total height with row spacing
        val rowGap = lineH * (deltaRow - 1f)
        val totalTextH = lineH * numLines + rowGap * (numLines - 1).coerceAtLeast(0)
        val furiganaExtra = if (readingMode == ReadingMode.ADVANCED && superCues.isNotEmpty()) lineH * deltaFurigana else 0f
        val totalH = totalTextH + furiganaExtra

        // Position: bottom of cue = top of seekbar bbox + padding, ALWAYS
        val seekbarTopY = barY - rc.dp(24f)
        val cueBottomPad = rc.dp(8f)
        val baseY = seekbarTopY - cueBottomPad - deltaYShift * rc.density
        val topY = baseY - totalH

        // ── Background shade ──
        val padH = rc.dp(12f)
        val padV = rc.dp(8f)
        val shadeX = (rc.w - maxLineW) / 2f - padH
        val shadeW = maxLineW + padH * 2
        val shadeY = topY - padV
        val shadeH = totalH + padV * 2
        rc.solid(shadeX, shadeY, shadeW, shadeH, 0f, 0f, 0f, 0.7f)

        // Build character boxes for tap detection
        val boxes = mutableListOf<CharBox>()
        var globalCharIdx = 0

        // Draw lines bottom-up
        for (lineIdx in lines.indices) {
            val lineText = lines[lineIdx]
            val lineW = lineWidths[lineIdx]
            val linesFromBottom = numLines - 1 - lineIdx
            val lineY = baseY - linesFromBottom * (lineH + rowGap)
            val lineX = (rc.w - lineW) / 2f

            // Word highlight
            if (hlStart >= 0 && hlEnd > hlStart) {
                drawLineHighlight(rc, lineText, lineX, lineY, lineH, fontSize, globalCharIdx)
            }

            // Draw text character by character (to track positions)
            var cx = lineX
            val cps = lineText.toCodePoints()
            for (cpIdx in cps.indices) {
                val ch = String(intArrayOf(cps[cpIdx]), 0, 1)
                val chW = rc.font.measureText(ch, fontSize) + deltaSpacing * rc.density

                // Add letter-spacing adjusted text
                rc.text(ch, cx, lineY, fontSize, 1f, 1f, 1f)

                // Store bounding box for tap detection
                boxes.add(CharBox(cx, lineY - lineH, chW, lineH, globalCharIdx))

                cx += chW
                globalCharIdx++
            }
            // Account for \n separator in global index
            if (lineIdx < numLines - 1) globalCharIdx++

            // Draw furigana in ADVANCED mode
            if (readingMode == ReadingMode.ADVANCED && superCues.isNotEmpty()) {
                drawFuriganaForLine(rc, lineIdx, lineX, lineY, fontSize, furiganaSize, lineH)
            }
        }

        charBoxes = boxes
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

        // Draw highlight rect (purple accent)
        rc.solid(lineX + prefixW, lineY - lineH, hlW, lineH, 0.733f, 0.525f, 0.988f, 0.3f)
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

        // Position above subtitle
        val subTopY = rc.h - rc.dp(60f) - deltaYShift * rc.density -
            rc.font.textHeight(rc.sp(subFontSize)) * currentCueText.split("\n").size * deltaRow
        val popupY = (subTopY - contentH - rc.dp(12f)).coerceAtLeast(rc.dp(8f))

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

        // Mine button
        cy += rc.dp(8f)
        val mineText = "Mine"
        val mineW = rc.font.measureText(mineText, rc.sp(13)) + rc.dp(24f)
        val mineH = rc.dp(28f)
        val mineX = popupX + (popupW - mineW) / 2f
        rc.solid(mineX, cy, mineW, mineH, 0.106f, 0.369f, 0.125f)
        rc.text(mineText, mineX + rc.dp(12f), cy + rc.dp(18f), rc.sp(13), 0.506f, 0.78f, 0.522f)
        rc.tappable(mineX, cy, mineW, mineH) {
            // Future: Anki card mining
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

        // Audio tracks section
        rows.add(SettingsRow("Audio", "", "audio"))

        // Subtitle tracks section
        rows.add(SettingsRow("Subtitle", "", "subs"))
        for (sub in episode.subtitles) {
            rows.add(SettingsRow(sub.label, sub.language, "", indent = true,
                selected = true) { /* select sub track */ })
        }

        // Reading mode
        rows.add(SettingsRow("Reading Mode", readingMode.name, "mode") {
            cycleReadingMode()
        })

        // Font size
        rows.add(SettingsRow("Font Size", "${subFontSize}sp", "size") {
            cycleFontSize()
        })

        // Condensed mode
        rows.add(SettingsRow("Condensed", if (condensedMode) "ON" else "OFF", "cond") {
            condensedMode = !condensedMode
        })

        // Typography deltas
        rows.add(SettingsRow("DF (Furigana)", "%.1f".format(deltaFurigana), "DF"))
        rows.add(SettingsRow("DR (Row Space)", "%.1f".format(deltaRow), "DR"))
        rows.add(SettingsRow("DS (Letter Space)", "%.1f".format(deltaSpacing), "DS"))
        rows.add(SettingsRow("DY (Y Offset)", "%.0f".format(deltaYShift), "DY"))

        settingsRows = rows
    }

    private fun drawSettingsPanel(rc: RC) {
        // Panel on right
        val panelW = rc.dp(300f).coerceAtMost(rc.w * 0.4f)
        val panelX = rc.w - panelW
        rc.solid(panelX, 0f, panelW, rc.h, 0.102f, 0.102f, 0.180f)

        // Title
        val titleSize = rc.sp(18)
        rc.text(Lang.s("settings"), panelX + rc.dp(16f), rc.dp(32f), titleSize, 1f, 1f, 1f)

        // Rows
        val rowH = rc.dp(44f)
        val labelSize = rc.sp(14)
        val valueSize = rc.sp(13)
        var y = rc.dp(56f)

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

            // Tappable
            rc.tappable(panelX, rowY, panelW, rowH) {
                settingsFocus = idx
                row.action()
                buildSettingsRows()
            }

            y += rowH
        }
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
            "DR" -> { deltaRow = (deltaRow - 0.1f).coerceIn(1.0f, 3.0f); buildSettingsRows() }
            "DS" -> { deltaSpacing = (deltaSpacing - 0.5f).coerceIn(-4f, 8f); buildSettingsRows() }
            "DY" -> { deltaYShift = (deltaYShift - 5f).coerceIn(-50f, 50f); buildSettingsRows() }
        }
    }

    private fun handleSettingsRight() {
        val row = settingsRows.getOrNull(settingsFocus) ?: return
        when (row.icon) {
            "DF" -> { deltaFurigana = (deltaFurigana + 0.1f).coerceIn(0.3f, 1.5f); buildSettingsRows() }
            "DR" -> { deltaRow = (deltaRow + 0.1f).coerceIn(1.0f, 3.0f); buildSettingsRows() }
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
        // Play/pause icon (centered)
        val centerX = rc.w / 2f
        val centerY = rc.h / 2f
        if (!isPlaying) {
            val playIcon = "▶"
            val iconSize = rc.sp(36)
            val tw = rc.font.measureText(playIcon, iconSize)
            rc.text(playIcon, centerX - tw / 2f, centerY + rc.font.textHeight(iconSize) / 3f,
                iconSize, 1f, 1f, 1f, 0.8f)
        }

        // Episode title at top
        val title = "${episode.episode}. ${episode.title()}"
        rc.text(title, pad, rc.dp(32f), rc.sp(16), 1f, 1f, 1f)

        // Back button
        rc.text("←", pad, rc.dp(60f), rc.sp(22), 0.8f, 0.8f, 0.8f)
        rc.tappable(0f, 0f, rc.dp(80f), rc.dp(80f)) {
            cleanup(app)
            app.goBack()
        }

        // Settings button (top right)
        val gearText = "⚙"
        val gearSize = rc.sp(22)
        val gearW = rc.font.measureText(gearText, gearSize)
        rc.text(gearText, rc.w - pad - gearW, rc.dp(32f), gearSize, 0.8f, 0.8f, 0.8f)
        rc.tappable(rc.w - rc.dp(80f), 0f, rc.dp(80f), rc.dp(60f)) { openSettings() }

        // Seekbar background
        rc.solid(pad, barY, rc.w - pad * 2, rc.dp(4f), 0.3f, 0.3f, 0.4f)

        // Seekbar progress
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val barW = rc.w - pad * 2
        rc.solid(pad, barY, barW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)

        // Seekbar handle
        val handleX = pad + barW * progress
        rc.solid(handleX - rc.dp(6f), barY - rc.dp(6f), rc.dp(12f), rc.dp(16f), 1f, 1f, 1f)

        // Debug: show seekbar hit zone
        rc.solid(pad, barY - rc.dp(24f), barW, rc.dp(48f), 0f, 1f, 0f, 0.15f)

        // Time display
        val posStr = formatTime(positionMs)
        val durStr = formatTime(durationMs)
        rc.text(posStr, pad, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)
        val durW = rc.font.measureText(durStr, rc.sp(12))
        rc.text(durStr, rc.w - pad - durW, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)

        // Prev/Next subtitle buttons when paused
        if (!isPlaying && cues.isNotEmpty()) {
            val btnSize = rc.sp(22)
            val prevText = "⏮"
            val nextText = "⏭"
            val btnY = barY - rc.dp(40f)

            // Prev subtitle
            val prevW = rc.font.measureText(prevText, btnSize)
            rc.text(prevText, centerX - rc.dp(60f) - prevW / 2f, btnY, btnSize, 0.8f, 0.8f, 0.8f)
            rc.tappable(centerX - rc.dp(80f), btnY - rc.dp(20f), rc.dp(40f), rc.dp(40f)) {
                SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) }
            }

            // Next subtitle
            val nextW = rc.font.measureText(nextText, btnSize)
            rc.text(nextText, centerX + rc.dp(60f) - nextW / 2f, btnY, btnSize, 0.8f, 0.8f, 0.8f)
            rc.tappable(centerX + rc.dp(40f), btnY - rc.dp(20f), rc.dp(40f), rc.dp(40f)) {
                SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
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

    private fun drawVideoQuad(app: App, rc: RC) {
        // Flush any pending UI quads
        rc.batch.flush()

        // Black background behind video
        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        rc.batch.flush()

        // Switch to external OES shader
        app.shader.useExternal()
        GLES30.glUniformMatrix4fv(app.shader.uProjExt, 1, false, app.projMatrix, 0)
        GLES30.glUniformMatrix4fv(app.shader.uTexMatExt, 1, false, app.videoSurface.transformMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(app.shader.uTexExt, 0)
        app.videoSurface.bind()

        // Letterbox to correct aspect ratio
        val vw = if (videoWidth > 0) videoWidth.toFloat() else 4f
        val vh = if (videoHeight > 0) videoHeight.toFloat() else 3f
        val videoAspect = vw / vh
        val screenAspect = rc.w / rc.h
        val qx: Float; val qy: Float; val qw: Float; val qh: Float
        if (screenAspect > videoAspect) {
            qh = rc.h; qw = qh * videoAspect
            qx = (rc.w - qw) / 2f; qy = 0f
        } else {
            qw = rc.w; qh = qw / videoAspect
            qx = 0f; qy = (rc.h - qh) / 2f
        }

        // Video quad with OES UVs
        rc.batch.begin()
        rc.batch.addQuad(qx, qy, qw, qh, 0f, 1f, 1f, 0f, layer = 0f)
        rc.batch.flush()

        // Switch back to main shader for UI overlay
        app.shader.use()
        GLES30.glUniformMatrix4fv(app.shader.uProj, 1, false, app.projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(app.shader.uTex, 0)
        app.texArray.bind()
        app.etc2Array.bind(GLES30.GL_TEXTURE1)
        GLES30.glUniform1i(app.shader.uTexEtc2, 1)
        rc.batch.begin()
    }

    @Volatile private var alive = true

    override fun cleanup(app: App) {
        alive = false
        app.onMainThread?.invoke(Runnable {
            app.exoPlayer?.stop()
            app.exoPlayer?.clearMediaItems()
            app.exoPlayer?.setVideoSurface(null)
        })
        appRef = null
    }
}
