package com.janusplus

object PlayerScreen {

    enum class PlayerState { PLAYING, CONTROLS, WORD_NAV }

    var playerState = PlayerState.PLAYING
    var showControls = false
    var controlsTimer = 0L

    var subtitleCues: List<SrtParser.Cue> = emptyList()
    var currentCueText: String? = null
    var subtitleTracks: List<JanusApi.SubTrack> = emptyList()
    var selectedSubIdx = 0

    var audioTrackNames: List<String> = emptyList()
    var selectedAudioIdx = 0

    var positionMs = 0L
    var durationMs = 0L
    var isPaused = false

    // Track selection overlay
    var showTrackList = false
    var trackListType = "" // "audio" or "subs"
    var trackListItems: List<String> = emptyList()
    var trackListFocus = 0

    fun reset() {
        playerState = PlayerState.PLAYING
        showControls = false
        subtitleCues = emptyList()
        currentCueText = null
        subtitleTracks = emptyList()
        selectedSubIdx = 0
        audioTrackNames = emptyList()
        selectedAudioIdx = 0
        positionMs = 0L
        durationMs = 0L
        isPaused = false
        showTrackList = false
    }

    fun render(rc: RenderCtx) {
        // Update current subtitle
        if (subtitleCues.isNotEmpty()) {
            val cue = SrtParser.cueAt(subtitleCues, positionMs)
            currentCueText = cue?.text
        }

        // Subtitle display
        val subText = currentCueText
        if (subText != null) {
            renderSubtitle(rc, subText)
        }

        // Controls overlay
        if (showControls || isPaused) {
            renderControls(rc)
        }

        // Track selection list
        if (showTrackList) {
            renderTrackList(rc)
        }
    }

    private fun renderSubtitle(rc: RenderCtx, text: String) {
        val sizePx = rc.sp(28)
        val lines = text.split("\n")
        val lineH = rc.font.textHeight(sizePx)
        val totalH = lines.size * lineH * 1.2f
        val baseY = rc.h - rc.dp(60f) - totalH

        // Background behind subtitle text
        for ((i, line) in lines.withIndex()) {
            val textW = rc.font.measureText(line, sizePx)
            val x = (rc.w - textW) / 2f
            val y = baseY + i * lineH * 1.2f
            // Dark backdrop
            rc.solid(x - rc.dp(8f), y - rc.dp(4f), textW + rc.dp(16f), lineH + rc.dp(8f),
                0f, 0f, 0f, 0.7f)
            // Text
            rc.text(line, x, y + lineH * 0.8f, sizePx, 1f, 1f, 1f)
        }
    }

    private fun renderControls(rc: RenderCtx) {
        val pad = rc.dp(24f)

        // Top dimming gradient
        rc.gradient(0f, 0f, rc.w, rc.dp(80f),
            floatArrayOf(0f, 0f, 0f, 0.6f), floatArrayOf(0f, 0f, 0f, 0.6f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f))

        // Bottom dimming gradient
        rc.gradient(0f, rc.h - rc.dp(120f), rc.w, rc.dp(120f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.7f), floatArrayOf(0f, 0f, 0f, 0.7f))

        // Pause icon
        if (isPaused) {
            val cx = rc.w / 2f
            val cy = rc.h / 2f
            rc.solid(cx - rc.dp(20f), cy - rc.dp(30f), rc.dp(12f), rc.dp(60f), 1f, 1f, 1f, 0.8f)
            rc.solid(cx + rc.dp(8f), cy - rc.dp(30f), rc.dp(12f), rc.dp(60f), 1f, 1f, 1f, 0.8f)
        }

        // Seekbar
        val seekY = rc.h - rc.dp(40f)
        val seekW = rc.w - pad * 2
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        // Track background
        rc.solid(pad, seekY, seekW, rc.dp(4f), 0.3f, 0.3f, 0.3f, 0.8f)
        // Progress fill
        rc.solid(pad, seekY, seekW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)
        // Seek handle
        rc.solid(pad + seekW * progress - rc.dp(6f), seekY - rc.dp(4f), rc.dp(12f), rc.dp(12f),
            1f, 1f, 1f)

        // Time labels
        val posText = formatTime(positionMs)
        val durText = formatTime(durationMs)
        rc.text(posText, pad, seekY - rc.dp(16f), rc.sp(12), 0.8f, 0.8f, 0.8f)
        val durW = rc.font.measureText(durText, rc.sp(12))
        rc.text(durText, rc.w - pad - durW, seekY - rc.dp(16f), rc.sp(12), 0.8f, 0.8f, 0.8f)

        // Top control buttons
        val btnY = rc.dp(16f)
        val btnH = rc.dp(40f)
        val btnW = rc.dp(56f)
        val btnSpacing = rc.dp(12f)
        var btnX = pad

        // Audio button
        rc.solid(btnX, btnY, btnW, btnH, 0.2f, 0.2f, 0.2f, 0.7f)
        rc.text("♪", btnX + rc.dp(18f), btnY + btnH * 0.7f, rc.sp(16), 1f, 1f, 1f)
        rc.tappable(btnX, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "audio"
            trackListItems = audioTrackNames.ifEmpty { listOf("Track 1") }
            trackListFocus = selectedAudioIdx
        }
        btnX += btnW + btnSpacing

        // Subtitle button
        rc.solid(btnX, btnY, btnW, btnH, 0.2f, 0.2f, 0.2f, 0.7f)
        rc.text("CC", btnX + rc.dp(12f), btnY + btnH * 0.7f, rc.sp(14), 1f, 1f, 1f)
        rc.tappable(btnX, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "subs"
            trackListItems = subtitleTracks.map { "${it.language} - ${it.label}" }.ifEmpty { listOf("None") }
            trackListFocus = selectedSubIdx
        }
        btnX += btnW + btnSpacing

        // Seekbar tap target
        rc.tappable(pad, seekY - rc.dp(20f), seekW, rc.dp(40f)) {
            // Seek handled via touch in InputHandler
        }
    }

    private fun renderTrackList(rc: RenderCtx) {
        val panelW = rc.dp(300f)
        val panelX = rc.w - panelW
        val itemH = rc.dp(48f)

        // Backdrop
        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f, 0.5f)
        // Panel
        rc.solid(panelX, 0f, panelW, rc.h, 0.1f, 0.1f, 0.15f)

        // Title
        val title = if (trackListType == "audio") Lang.s("audio") else Lang.s("subs")
        rc.text(title, panelX + rc.dp(16f), rc.dp(40f), rc.sp(18), 0.733f, 0.525f, 0.988f)

        // Items
        for ((i, item) in trackListItems.withIndex()) {
            val y = rc.dp(60f) + i * itemH
            val focused = i == trackListFocus
            if (focused) {
                rc.solid(panelX, y, panelW, itemH, 0.2f, 0.2f, 0.3f)
            }
            val selected = when (trackListType) {
                "audio" -> i == selectedAudioIdx
                "subs" -> i == selectedSubIdx
                else -> false
            }
            val prefix = if (selected) "● " else "  "
            rc.text(prefix + item, panelX + rc.dp(16f), y + itemH * 0.65f, rc.sp(14),
                if (selected) 0.733f else 0.8f,
                if (selected) 0.525f else 0.8f,
                if (selected) 0.988f else 0.8f)

            rc.tappable(panelX, y, panelW, itemH) {
                when (trackListType) {
                    "audio" -> selectedAudioIdx = i
                    "subs" -> selectedSubIdx = i
                }
                showTrackList = false
            }
        }
    }

    fun toggleControls() {
        showControls = !showControls
        controlsTimer = System.currentTimeMillis()
    }

    fun togglePause() {
        isPaused = !isPaused
        if (!isPaused) showControls = false
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
