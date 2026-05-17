package com.janusplus

/**
 * Game engine. One tick per frame. No callbacks, no threads, no polling.
 *
 * Frame:
 *   1. tick()    — read all external state (touches, player position, network results)
 *   2. update()  — process input, update game state (screen, focus, scroll, seekbar)
 *   3. render()  — emit quads based on current state, flush
 */
object Engine {

    // ── External state (written by main thread, read by GL thread) ──

    // Touch input
    data class Touch(val action: Int, val x: Float, val y: Float)
    private val touchQueue = java.util.concurrent.ConcurrentLinkedQueue<Touch>()
    fun queueTouch(action: Int, x: Float, y: Float) { touchQueue.add(Touch(action, x, y)) }

    // Player info (main thread writes these atomically)
    @Volatile var extPlayerPosition = 0L
    @Volatile var extPlayerDuration = 0L
    @Volatile var extPlayerPlaying = false
    @Volatile var extVideoWidth = 0
    @Volatile var extVideoHeight = 0

    // ── Per-frame snapshot (read once per tick, used throughout update+render) ──

    var touches = mutableListOf<Touch>()
    var playerPosition = 0L
    var playerDuration = 0L
    var playerPlaying = false
    var videoWidth = 0
    var videoHeight = 0

    // ── Commands (GL thread sets, main thread reads + executes) ──

    @Volatile var cmdSeek: Long? = null
    @Volatile var cmdPause = false
    @Volatile var cmdPlay = false
    @Volatile var cmdBack = false
    @Volatile var cmdLoadSub: Int? = null

    // ── Player UI state ──

    var showControls = false
    var controlsTimer = 0L
    var isDragging = false
    var dragPosition = 0L
    var seekBarX = 0f
    var seekBarY = 0f
    var seekBarW = 0f
    var displayPosition = 0L
    var displayDuration = 0L
    var displayPaused = false

    // ── Subtitle state ──

    var subtitleCues: List<SrtParser.Cue> = emptyList()
    var currentCueText: String? = null
    var subtitleTracks: List<JanusApi.SubTrack> = emptyList()
    var selectedSubIdx = 0
    var audioTrackNames: List<String> = emptyList()
    var selectedAudioIdx = 0

    // Track list
    var showTrackList = false
    var trackListType = ""
    var trackListItems: List<String> = emptyList()

    fun resetPlayer() {
        showControls = false
        isDragging = false
        dragPosition = 0L
        displayPosition = 0L
        displayDuration = 0L
        displayPaused = false
        subtitleCues = emptyList()
        currentCueText = null
        subtitleTracks = emptyList()
        selectedSubIdx = 0
        audioTrackNames = emptyList()
        selectedAudioIdx = 0
        showTrackList = false
        cmdSeek = null
        cmdPause = false
        cmdPlay = false
        cmdBack = false
        cmdLoadSub = null
    }

    // ── TICK: snapshot all external state ──

    fun tick() {
        // Drain touches
        touches.clear()
        while (true) { touches.add(touchQueue.poll() ?: break) }

        // Snapshot player
        if (!isDragging) {
            playerPosition = extPlayerPosition
            playerPlaying = extPlayerPlaying
        }
        playerDuration = extPlayerDuration
        videoWidth = extVideoWidth
        videoHeight = extVideoHeight
    }

    // ── UPDATE: process input, update state ──

    fun updatePlayer() {
        // Process touches
        for (t in touches) {
            val inSeekZone = showControls && seekBarW > 0 && t.y > seekBarY - 80f && t.y < seekBarY + 80f

            when (t.action) {
                0 -> { // DOWN
                    if (inSeekZone) {
                        isDragging = true
                        cmdPause = true
                        dragPosition = seekFrac(t.x)
                    }
                }
                2 -> { // MOVE
                    if (isDragging) {
                        dragPosition = seekFrac(t.x)
                    }
                }
                1 -> { // UP
                    if (isDragging) {
                        cmdSeek = seekFrac(t.x)
                        cmdPlay = true
                        isDragging = false
                    } else {
                        handlePlayerTap(t.x, t.y)
                    }
                }
            }
        }

        // Update display state
        if (isDragging) {
            displayPosition = dragPosition
            displayPaused = true
            showControls = true
            controlsTimer = System.currentTimeMillis()
        } else {
            displayPosition = playerPosition
            displayPaused = !playerPlaying
        }
        displayDuration = playerDuration

        // Auto-hide controls
        if (showControls && playerPlaying && !isDragging &&
            System.currentTimeMillis() - controlsTimer > 5000) {
            showControls = false
        }

        // Update subtitle
        if (subtitleCues.isNotEmpty() && !isDragging) {
            currentCueText = SrtParser.cueAt(subtitleCues, displayPosition)?.text
        }
    }

    private var hitRects: List<HitRect> = emptyList()
    fun setHitRects(rects: List<HitRect>) { hitRects = rects }

    private fun handlePlayerTap(x: Float, y: Float) {
        // Check hit rects
        for (hr in hitRects) {
            if (x >= hr.x && x <= hr.x + hr.w && y >= hr.y && y <= hr.y + hr.h) {
                hr.action()
                return
            }
        }
        if (showTrackList) {
            showTrackList = false
            return
        }
        if (showControls) {
            if (playerPlaying) cmdPause = true else cmdPlay = true
        } else {
            showControls = true
            controlsTimer = System.currentTimeMillis()
        }
    }

    private fun seekFrac(x: Float): Long {
        val frac = ((x - seekBarX) / seekBarW).coerceIn(0f, 1f)
        return (frac * playerDuration).toLong()
    }
}
