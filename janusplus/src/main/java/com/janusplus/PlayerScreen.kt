package com.janusplus

object PlayerScreen {

    var subtitleCues: List<SrtParser.Cue> = emptyList()
    var currentCueText: String? = null
    var subtitleTracks: List<JanusApi.SubTrack> = emptyList()
    var selectedSubIdx = 0

    var audioTrackNames: List<String> = emptyList()
    var selectedAudioIdx = 0

    var positionMs = 0L
    var durationMs = 0L
    var isPaused = false
    var showControls = false
    var controlsTimer = 0L

    var showTrackList = false
    var trackListType = ""
    var trackListItems: List<String> = emptyList()
    var trackListFocus = 0

    @Volatile var pendingBack = false
    @Volatile var pendingSeek: Long? = null
    var lastTapX = 0f
    var seekBarX = 0f
    var seekBarW = 0f
    var seekBarY = 0f
    var videoWidth = 0
    var videoHeight = 0
    var debugVideoQuad = ""
    @Volatile var pendingPause: Boolean? = null
    @Volatile var pendingSubChange: Int? = null

    fun reset() {
        subtitleCues = emptyList()
        currentCueText = null
        subtitleTracks = emptyList()
        selectedSubIdx = 0
        audioTrackNames = emptyList()
        selectedAudioIdx = 0
        positionMs = 0L
        durationMs = 0L
        isPaused = false
        showControls = false
        showTrackList = false
        pendingBack = false
        pendingSeek = null
        pendingPause = null
        pendingSubChange = null
    }

    fun render(rc: RenderCtx) {
        if (subtitleCues.isNotEmpty() && !GameLoop.isDragging) {
            currentCueText = SrtParser.cueAt(subtitleCues, positionMs)?.text
        }

        // Subtitle
        currentCueText?.let { renderSubtitle(rc, it) }

        // Controls overlay
        if (showControls || isPaused) renderControls(rc)

        // Debug quad info
        rc.text("Q:$debugVideoQuad", rc.dp(8f), rc.dp(80f), rc.sp(12), 1f, 0f, 0f)

        // Track list
        if (showTrackList) renderTrackList(rc)
    }

    private fun renderSubtitle(rc: RenderCtx, text: String) {
        val sizePx = rc.sp(28)
        val lines = text.split("\n")
        val lineH = rc.font.textHeight(sizePx)
        val totalH = lines.size * lineH * 1.2f
        val baseY = rc.h - rc.dp(60f) - totalH

        for ((i, line) in lines.withIndex()) {
            val textW = rc.font.measureText(line, sizePx)
            val x = (rc.w - textW) / 2f
            val y = baseY + i * lineH * 1.2f
            rc.solid(x - rc.dp(8f), y - rc.dp(4f), textW + rc.dp(16f), lineH + rc.dp(8f), 0f, 0f, 0f, 0.7f)
            rc.text(line, x, y + lineH * 0.8f, sizePx, 1f, 1f, 1f)
        }
    }

    private fun renderControls(rc: RenderCtx) {
        val pad = rc.dp(24f)

        // Top dimming
        rc.gradient(0f, 0f, rc.w, rc.dp(80f),
            floatArrayOf(0f, 0f, 0f, 0.6f), floatArrayOf(0f, 0f, 0f, 0.6f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f))

        // Bottom dimming
        rc.gradient(0f, rc.h - rc.dp(120f), rc.w, rc.dp(120f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.7f), floatArrayOf(0f, 0f, 0f, 0.7f))

        // Pause icon
        if (isPaused) {
            val cx = rc.w / 2f; val cy = rc.h / 2f
            rc.solid(cx - rc.dp(20f), cy - rc.dp(30f), rc.dp(12f), rc.dp(60f), 1f, 1f, 1f, 0.8f)
            rc.solid(cx + rc.dp(8f), cy - rc.dp(30f), rc.dp(12f), rc.dp(60f), 1f, 1f, 1f, 0.8f)
        }

        // Top row: ← back ... ♪ CC ⚙
        val btnY = rc.dp(16f)
        val btnH = rc.dp(40f)
        val btnW = rc.dp(56f)
        val btnSpacing = rc.dp(12f)

        // Back button (top left)
        rc.solid(pad, btnY, btnW, btnH, 0.2f, 0.2f, 0.2f, 0.7f)
        rc.text("←", pad + rc.dp(18f), btnY + btnH * 0.7f, rc.sp(18), 1f, 1f, 1f)
        rc.tappable(pad, btnY, btnW, btnH) { pendingBack = true }

        // Right-side buttons
        var rx = rc.w - pad - btnW

        // Audio
        rc.solid(rx, btnY, btnW, btnH, 0.2f, 0.2f, 0.2f, 0.7f)
        rc.text("♪", rx + rc.dp(18f), btnY + btnH * 0.7f, rc.sp(16), 1f, 1f, 1f)
        rc.tappable(rx, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "audio"
            trackListItems = audioTrackNames.ifEmpty { listOf("Track 1") }
            trackListFocus = selectedAudioIdx
        }
        rx -= btnW + btnSpacing

        // Subtitles
        rc.solid(rx, btnY, btnW, btnH, 0.2f, 0.2f, 0.2f, 0.7f)
        rc.text("CC", rx + rc.dp(12f), btnY + btnH * 0.7f, rc.sp(14), 1f, 1f, 1f)
        rc.tappable(rx, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "subs"
            trackListItems = subtitleTracks.map { "${it.language} - ${it.label}" }.ifEmpty { listOf("None") }
            trackListFocus = selectedSubIdx
        }

        // Seekbar
        val seekY = rc.h - rc.dp(40f)
        val seekW = rc.w - pad * 2
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        rc.solid(pad, seekY, seekW, rc.dp(4f), 0.3f, 0.3f, 0.3f, 0.8f)
        rc.solid(pad, seekY, seekW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)
        rc.solid(pad + seekW * progress - rc.dp(6f), seekY - rc.dp(4f), rc.dp(12f), rc.dp(12f), 1f, 1f, 1f)

        // Time labels
        val posText = formatTime(positionMs)
        val durText = formatTime(durationMs)
        rc.text(posText, pad, seekY - rc.dp(16f), rc.sp(12), 0.8f, 0.8f, 0.8f)
        val durW = rc.font.measureText(durText, rc.sp(12))
        rc.text(durText, rc.w - pad - durW, seekY - rc.dp(16f), rc.sp(12), 0.8f, 0.8f, 0.8f)

        // Seekbar — store geometry for direct seek in Activity
        seekBarX = pad
        seekBarW = seekW
        seekBarY = seekY
        rc.tappable(pad, seekY - rc.dp(20f), seekW, rc.dp(40f)) {
            val frac = ((lastTapX - seekBarX) / seekBarW).coerceIn(0f, 1f)
            pendingSeek = (frac * durationMs).toLong()
        }
    }

    private fun renderTrackList(rc: RenderCtx) {
        val panelW = rc.dp(300f)
        val panelX = rc.w - panelW
        val itemH = rc.dp(48f)

        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f, 0.5f)
        rc.solid(panelX, 0f, panelW, rc.h, 0.1f, 0.1f, 0.15f)

        val title = if (trackListType == "audio") Lang.s("audio") else Lang.s("subs")
        rc.text(title, panelX + rc.dp(16f), rc.dp(40f), rc.sp(18), 0.733f, 0.525f, 0.988f)

        for ((i, item) in trackListItems.withIndex()) {
            val y = rc.dp(60f) + i * itemH
            val focused = i == trackListFocus
            if (focused) rc.solid(panelX, y, panelW, itemH, 0.2f, 0.2f, 0.3f)
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
                    "subs" -> { selectedSubIdx = i; pendingSubChange = i }
                }
                showTrackList = false
            }
        }
    }

    fun toggleControls() {
        showControls = !showControls
        controlsTimer = System.currentTimeMillis()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
