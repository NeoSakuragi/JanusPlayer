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
    var controlsAlpha = 0f

    var showTrackList = false
    var trackListType = ""
    var trackListItems: List<String> = emptyList()
    var trackListFocus = 0

    // Seek indicator
    var seekIndicator: String? = null
    var seekIndicatorTimer = 0L

    // Episode info
    var episodeTitle = ""

    @Volatile var pendingBack = false
    @Volatile var pendingSeek: Long? = null
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
        controlsAlpha = 0f
        showTrackList = false
        seekIndicator = null
        episodeTitle = ""
        pendingBack = false
        pendingSeek = null
        pendingSubChange = null
    }

    fun render(rc: RenderCtx) {
        if (subtitleCues.isNotEmpty()) {
            currentCueText = SrtParser.cueAt(subtitleCues, positionMs)?.text
        }

        // Animate controls alpha
        val target = if (showControls || isPaused) 1f else 0f
        controlsAlpha += (target - controlsAlpha) * 0.15f
        if (controlsAlpha < 0.01f) controlsAlpha = 0f
        if (controlsAlpha > 0.99f) controlsAlpha = 1f

        // Auto-hide controls after 5s
        if (showControls && !isPaused && System.currentTimeMillis() - controlsTimer > 5000) {
            showControls = false
        }

        // Seek indicator timeout
        if (seekIndicator != null && System.currentTimeMillis() - seekIndicatorTimer > 600) {
            seekIndicator = null
        }

        // Subtitle (always visible when cue active)
        currentCueText?.let { renderSubtitle(rc, it) }

        // Controls overlay with alpha
        if (controlsAlpha > 0f) renderControls(rc, controlsAlpha)

        // Pause icon (center)
        if (isPaused && controlsAlpha > 0.5f) {
            val a = controlsAlpha
            val cx = rc.w / 2f; val cy = rc.h / 2f
            // Dark circle behind pause
            rc.solid(cx - rc.dp(40f), cy - rc.dp(40f), rc.dp(80f), rc.dp(80f), 0f, 0f, 0f, 0.4f * a)
            rc.solid(cx - rc.dp(16f), cy - rc.dp(24f), rc.dp(10f), rc.dp(48f), 1f, 1f, 1f, 0.9f * a)
            rc.solid(cx + rc.dp(6f), cy - rc.dp(24f), rc.dp(10f), rc.dp(48f), 1f, 1f, 1f, 0.9f * a)
        }

        // Seek indicator
        seekIndicator?.let { text ->
            val a = ((600 - (System.currentTimeMillis() - seekIndicatorTimer)) / 600f).coerceIn(0f, 1f)
            val tw = rc.font.measureText(text, rc.sp(22))
            rc.text(text, (rc.w - tw) / 2f, rc.h / 2f + rc.dp(60f), rc.sp(22), 1f, 1f, 1f, a)
        }

        // Track list overlay
        if (showTrackList) renderTrackList(rc)
    }

    private fun renderSubtitle(rc: RenderCtx, text: String) {
        val sizePx = rc.sp(28)
        val lines = text.split("\n")
        val lineH = rc.font.textHeight(sizePx)
        val totalH = lines.size * lineH * 1.2f
        val baseY = rc.h - rc.dp(80f) - totalH

        for ((i, line) in lines.withIndex()) {
            val textW = rc.font.measureText(line, sizePx)
            val x = (rc.w - textW) / 2f
            val y = baseY + i * lineH * 1.2f
            // Rounded backdrop
            rc.solid(x - rc.dp(12f), y - rc.dp(6f), textW + rc.dp(24f), lineH + rc.dp(12f),
                0f, 0f, 0f, 0.65f)
            rc.text(line, x, y + lineH * 0.8f, sizePx, 1f, 1f, 1f)
        }
    }

    private fun renderControls(rc: RenderCtx, alpha: Float) {
        val pad = rc.dp(32f)
        val a = alpha

        // Top gradient
        rc.gradient(0f, 0f, rc.w, rc.dp(100f),
            floatArrayOf(0f, 0f, 0f, 0.7f * a), floatArrayOf(0f, 0f, 0f, 0.7f * a),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f))

        // Bottom gradient
        rc.gradient(0f, rc.h - rc.dp(140f), rc.w, rc.dp(140f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.8f * a), floatArrayOf(0f, 0f, 0f, 0.8f * a))

        // Top row
        val btnH = rc.dp(40f)
        val btnW = rc.dp(50f)
        val btnY = rc.dp(20f)
        val btnSpacing = rc.dp(8f)
        val btnR = 0.165f; val btnG = 0.165f; val btnB = 0.227f

        // Back button (top left)
        rc.solid(pad, btnY, btnW, btnH, btnR, btnG, btnB, 0.8f * a)
        rc.text("←", pad + rc.dp(16f), btnY + btnH * 0.7f, rc.sp(18), 1f, 1f, 1f, a)
        rc.tappable(pad, btnY, btnW, btnH) { pendingBack = true }

        // Title
        if (episodeTitle.isNotEmpty()) {
            rc.textClipped(episodeTitle, pad + btnW + rc.dp(12f), btnY + btnH * 0.65f,
                rc.sp(14), rc.w * 0.4f, 1f, 1f, 1f, a)
        }

        // Right buttons: ♪ CC
        var rx = rc.w - pad

        // CC (subtitle track)
        rx -= btnW
        rc.solid(rx, btnY, btnW, btnH, btnR, btnG, btnB, 0.8f * a)
        rc.text("CC", rx + rc.dp(12f), btnY + btnH * 0.7f, rc.sp(13), 1f, 1f, 1f, a)
        rc.tappable(rx, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "subs"
            trackListItems = subtitleTracks.map { "${it.language} · ${it.label}" }.ifEmpty { listOf("None") }
            trackListFocus = selectedSubIdx
        }
        rx -= btnSpacing

        // ♪ (audio track)
        rx -= btnW
        rc.solid(rx, btnY, btnW, btnH, btnR, btnG, btnB, 0.8f * a)
        rc.text("♪", rx + rc.dp(16f), btnY + btnH * 0.7f, rc.sp(16), 1f, 1f, 1f, a)
        rc.tappable(rx, btnY, btnW, btnH) {
            showTrackList = true
            trackListType = "audio"
            trackListItems = audioTrackNames.ifEmpty { listOf("Track 1") }
            trackListFocus = selectedAudioIdx
        }

        // Seekbar
        val seekY = rc.h - rc.dp(44f)
        val seekX = pad
        val seekW = rc.w - pad * 2
        val barH = rc.dp(6f)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        // Track
        rc.solid(seekX, seekY, seekW, barH, 0.27f, 0.27f, 0.27f, 0.8f * a)
        // Fill
        rc.solid(seekX, seekY, seekW * progress, barH, 0.733f, 0.525f, 0.988f, a)

        // Time labels
        val posText = formatTime(positionMs)
        val durText = formatTime(durationMs)
        rc.text(posText, seekX, seekY - rc.dp(18f), rc.sp(12), 1f, 1f, 1f, 0.8f * a)
        val durW = rc.font.measureText(durText, rc.sp(12))
        rc.text(durText, seekX + seekW - durW, seekY - rc.dp(18f), rc.sp(12), 0.67f, 0.67f, 0.67f, 0.8f * a)

        // Seekbar tap target
        rc.tappable(seekX, seekY - rc.dp(24f), seekW, rc.dp(48f)) {
            // Seek handled in Activity via touch x position
        }
    }

    private fun renderTrackList(rc: RenderCtx) {
        val panelW = rc.dp(320f)
        val panelX = rc.w - panelW
        val itemH = rc.dp(48f)

        // Backdrop
        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f, 0.4f)

        // Panel
        rc.solid(panelX, 0f, panelW, rc.h, 0.063f, 0.063f, 0.110f)

        // Title
        val title = if (trackListType == "audio") "Audio" else "Subtitles"
        rc.text(title, panelX + rc.dp(20f), rc.dp(44f), rc.sp(18), 0.733f, 0.525f, 0.988f)

        // Divider
        rc.solid(panelX + rc.dp(16f), rc.dp(56f), panelW - rc.dp(32f), rc.dp(1f), 0.2f, 0.2f, 0.2f)

        // Items
        for ((i, item) in trackListItems.withIndex()) {
            val y = rc.dp(68f) + i * itemH
            val focused = i == trackListFocus
            val selected = when (trackListType) {
                "audio" -> i == selectedAudioIdx
                "subs" -> i == selectedSubIdx
                else -> false
            }

            if (focused) {
                rc.solid(panelX + rc.dp(8f), y, panelW - rc.dp(16f), itemH, 0.15f, 0.15f, 0.22f)
            }

            // Selected bullet
            if (selected) {
                rc.text("●", panelX + rc.dp(20f), y + itemH * 0.6f, rc.sp(10), 0.506f, 0.780f, 0.518f)
            }

            val textX = panelX + rc.dp(if (selected) 38f else 20f)
            rc.textClipped(item, textX, y + itemH * 0.65f, rc.sp(14), panelW - rc.dp(48f),
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

    fun showSeekIndicator(text: String) {
        seekIndicator = text
        seekIndicatorTimer = System.currentTimeMillis()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
