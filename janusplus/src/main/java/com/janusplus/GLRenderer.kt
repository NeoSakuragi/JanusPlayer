package com.janusplus

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRenderer(
    private val assets: AssetManager,
    val state: AppState,
    private val density: Float,
) : GLSurfaceView.Renderer {

    lateinit var shader: ShaderProgram
    lateinit var batch: QuadBatch
    lateinit var textures: TextureManager
    lateinit var font: FontAtlas
    lateinit var texArray: TextureArray
    lateinit var dimens: Dimens
    val thumbAtlas = ThumbnailAtlas()
    val videoSurface = VideoSurface()

    private val projMatrix = FloatArray(16)
    var width = 0f; private set
    var height = 0f; private set
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fpsTimer = 0L
    var fps = 0; private set
    private var drawCalls = 0
    private var lastFrameMs = 0f
    private var lastBuildMs = 0f
    private var lastFlushMs = 0f
    private var lastUploadMs = 0f
    // Frame cache — replay quads if state unchanged
    private var cachedQuads: FloatArray? = null
    private var cachedQuadCount = 0
    private var cachedStateHash = 0L

    // Render context passed to screen functions
    val ctx get() = RenderCtx(batch, font, textures, thumbAtlas, state.coverAtlas, state, dimens, width, height, density)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.039f, 0.039f, 0.102f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shader = ShaderProgram()
        shader.compile()
        shader.compileExternal()
        videoSurface.initGL()

        batch = QuadBatch()
        batch.initGL()

        textures = TextureManager()
        textures.initGL()

        texArray = TextureArray(4096, 4)
        texArray.initGL()

        font = FontAtlas(assets)
        font.initGL(texArray)

        // Wire atlases to texture array
        thumbAtlas.texArray = texArray
        thumbAtlas.layerIndex = TextureArray.LAYER_THUMBS
        state.coverAtlas.texArray = texArray
        state.coverAtlas.layerIndex = TextureArray.LAYER_COVERS

        lastFrameTime = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
        Matrix.orthoM(projMatrix, 0, 0f, width, height, 0f, -1f, 1f)
        dimens = computeDimens(width, density)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStart = System.nanoTime()
        val gapMs = (frameStart - lastFrameTime) / 1_000_000f
        val dt = ((frameStart - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameTime = frameStart
        frameCount++

        // Upload phase — all VRAM writes happen here, before any rendering
        val uploadStart = System.nanoTime()
        textures.processUploads()
        texArray.processUploads()
        thumbAtlas.uploadIfNeeded()
        state.coverAtlas.uploadIfNeeded()
        font.uploadDirtyGlyphs()
        lastUploadMs = (System.nanoTime() - uploadStart) / 1_000_000f

        state.seriesScroll.update(dt)
        state.movieScroll.update(dt)
        state.detailScroll.update(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()
        GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shader.uTex, 0)

        texArray.bind()

        if (state.screen == Screen.PLAYING) {
            // ── TICK ──
            Engine.tick()

            // ── UPDATE ──
            Engine.updatePlayer()

            // ── RENDER ──
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            // Video quad
            videoSurface.updateTexture()
            shader.useExternal()
            GLES30.glUniformMatrix4fv(shader.uProjExt, 1, false, projMatrix, 0)
            GLES30.glUniformMatrix4fv(shader.uTexMatExt, 1, false, videoSurface.transformMatrix, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1i(shader.uTexExt, 0)
            videoSurface.bind()
            batch.begin()
            val vw = Engine.videoWidth.toFloat()
            val vh = Engine.videoHeight.toFloat()
            val qx: Float; val qy: Float; val qw: Float; val qh: Float
            if (vw > 0 && vh > 0) {
                val videoAspect = vw / vh
                val screenAspect = width / height
                if (videoAspect > screenAspect) {
                    qw = width; qh = width / videoAspect; qx = 0f; qy = (height - qh) / 2f
                } else {
                    qh = height; qw = height * videoAspect; qy = 0f; qx = (width - qw) / 2f
                }
            } else { qx = 0f; qy = 0f; qw = width; qh = height }
            batch.addQuad(qx, qy, qw, qh, 0f, 1f, 1f, 0f)
            batch.flush()

            // UI overlay
            shader.use()
            GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
            GLES30.glUniform1i(shader.uTex, 0)
            texArray.bind()
            batch.begin()
            val rc = ctx
            rc.hitRects.clear()
            renderPlayerUI(rc)
            batch.flush()
            Engine.setHitRects(rc.hitRects.toList())

            // Subtitle change callback (needs network)
            val subCmd = Engine.cmdLoadSub
            if (subCmd != null) { Engine.cmdLoadSub = null; onSubChange?.invoke(subCmd) }
            return
        }

        val rc = ctx
        rc.hitRects.clear()

        val buildStart = System.nanoTime()

        texArray.bind()
        batch.begin()

        when (state.screen) {
            Screen.LOGIN -> LoginScreen.render(rc)
            Screen.HOME -> HomeScreen.render(rc)
            Screen.SERIES_DETAIL, Screen.MOVIE_DETAIL -> DetailScreen.render(rc)
            Screen.SETTINGS -> SettingsScreen.render(rc)
            Screen.PLAYING -> {}
        }
        val g = font.glyphsEmitted; val tc = font.addTextCalls
        font.glyphsEmitted = 0; font.addTextCalls = 0
        val perfText = "${fps}fps ${lastFrameMs.toInt()}ms b=${lastBuildMs.toInt()} f=${lastFlushMs.toInt()} g=$g tc=$tc"
        rc.text(perfText, rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
        lastBuildMs = (System.nanoTime() - buildStart) / 1_000_000f

        // ONE flush — texture array already bound, all layers accessible
        val flushStart = System.nanoTime()
        batch.flush()
        lastFlushMs = (System.nanoTime() - flushStart) / 1_000_000f

        inputHandler?.hitRects = rc.hitRects.toList()

        val tapped = state.pendingTap
        if (tapped != null) {
            state.pendingTap = null
            onItemTapped?.invoke(tapped)
        }
        val seasonChange = state.pendingSeasonChange
        if (seasonChange != null) {
            state.pendingSeasonChange = null
            onSeasonChanged?.invoke(seasonChange)
        }
        if (LoginScreen.pendingLogin) {
            LoginScreen.pendingLogin = false
            onLogin?.invoke()
        }
        if (SettingsScreen.pendingLogout) {
            SettingsScreen.pendingLogout = false
            onLogout?.invoke()
        }
        if (PlayerScreen.pendingBack) {
            PlayerScreen.pendingBack = false
            onPlayerBack?.invoke()
        }
        val seek = PlayerScreen.pendingSeek
        if (seek != null) {
            PlayerScreen.pendingSeek = null
            onPlayerSeek?.invoke(seek)
        }
        val pause = PlayerScreen.pendingPause
        if (pause != null) {
            PlayerScreen.pendingPause = null
            onPlayerPause?.invoke(pause)
        }
        val subIdx = PlayerScreen.pendingSubChange
        if (subIdx != null) {
            PlayerScreen.pendingSubChange = null
            onSubChange?.invoke(subIdx)
        }

        lastFrameMs = (System.nanoTime() - frameStart) / 1_000_000f

        // Log every second
        if (frameStart - fpsTimer > 1_000_000_000L) {
            fps = frameCount
            android.util.Log.i("PERF", "fps=$fps frame=${lastFrameMs}ms gap=${gapMs.toInt()}ms build=${lastBuildMs}ms flush=${lastFlushMs}ms upload=${lastUploadMs}ms quads=${batch.quadCount} screen=${state.screen}")
            frameCount = 0
            fpsTimer = frameStart
        }
    }

    private fun renderPlayerUI(rc: RenderCtx) {
        val pad = rc.dp(32f)
        val pos = Engine.displayPosition
        val dur = Engine.displayDuration
        val paused = Engine.displayPaused
        val progress = if (dur > 0) pos.toFloat() / dur else 0f

        // Subtitle
        Engine.currentCueText?.let { text ->
            val sizePx = rc.sp(28)
            val lines = text.split("\n")
            val lineH = rc.font.textHeight(sizePx)
            val totalH = lines.size * lineH * 1.2f
            val baseY = rc.h - rc.dp(80f) - totalH
            for ((i, line) in lines.withIndex()) {
                val textW = rc.font.measureText(line, sizePx)
                val x = (rc.w - textW) / 2f
                val y = baseY + i * lineH * 1.2f
                rc.solid(x - rc.dp(12f), y - rc.dp(6f), textW + rc.dp(24f), lineH + rc.dp(12f), 0f, 0f, 0f, 0.65f)
                rc.text(line, x, y + lineH * 0.8f, sizePx, 1f, 1f, 1f)
            }
        }

        if (!Engine.showControls && !paused) return

        // Top/bottom gradients
        rc.gradient(0f, 0f, rc.w, rc.dp(100f),
            floatArrayOf(0f, 0f, 0f, 0.7f), floatArrayOf(0f, 0f, 0f, 0.7f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f))
        rc.gradient(0f, rc.h - rc.dp(140f), rc.w, rc.dp(140f),
            floatArrayOf(0f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0.8f), floatArrayOf(0f, 0f, 0f, 0.8f))

        // Pause icon
        if (paused) {
            val cx = rc.w / 2f; val cy = rc.h / 2f
            rc.solid(cx - rc.dp(40f), cy - rc.dp(40f), rc.dp(80f), rc.dp(80f), 0f, 0f, 0f, 0.4f)
            rc.solid(cx - rc.dp(16f), cy - rc.dp(24f), rc.dp(10f), rc.dp(48f), 1f, 1f, 1f, 0.9f)
            rc.solid(cx + rc.dp(6f), cy - rc.dp(24f), rc.dp(10f), rc.dp(48f), 1f, 1f, 1f, 0.9f)
        }

        // Top buttons
        val btnY = rc.dp(20f); val btnH = rc.dp(40f); val btnW = rc.dp(50f)
        rc.solid(pad, btnY, btnW, btnH, 0.165f, 0.165f, 0.227f, 0.8f)
        rc.text("←", pad + rc.dp(16f), btnY + btnH * 0.7f, rc.sp(18), 1f, 1f, 1f)
        rc.tappable(pad, btnY, btnW, btnH) { Engine.cmdBack = true }

        var rx = rc.w - pad
        rx -= btnW
        rc.solid(rx, btnY, btnW, btnH, 0.165f, 0.165f, 0.227f, 0.8f)
        rc.text("CC", rx + rc.dp(12f), btnY + btnH * 0.7f, rc.sp(13), 1f, 1f, 1f)
        rc.tappable(rx, btnY, btnW, btnH) {
            Engine.showTrackList = true; Engine.trackListType = "subs"
            Engine.trackListItems = Engine.subtitleTracks.map { "${it.language} · ${it.label}" }.ifEmpty { listOf("None") }
        }
        rx -= btnW + rc.dp(8f)
        rc.solid(rx, btnY, btnW, btnH, 0.165f, 0.165f, 0.227f, 0.8f)
        rc.text("♪", rx + rc.dp(16f), btnY + btnH * 0.7f, rc.sp(16), 1f, 1f, 1f)
        rc.tappable(rx, btnY, btnW, btnH) {
            Engine.showTrackList = true; Engine.trackListType = "audio"
            Engine.trackListItems = Engine.audioTrackNames.ifEmpty { listOf("Track 1") }
        }

        // Seekbar
        val seekY = rc.h - rc.dp(64f)
        val seekW = rc.w - pad * 2
        Engine.seekBarX = pad; Engine.seekBarY = seekY; Engine.seekBarW = seekW
        rc.solid(pad, seekY, seekW, rc.dp(6f), 0.27f, 0.27f, 0.27f, 0.8f)
        rc.solid(pad, seekY, seekW * progress, rc.dp(6f), 0.733f, 0.525f, 0.988f)
        val posText = formatTime(pos); val durText = formatTime(dur)
        rc.text(posText, pad, seekY - rc.dp(18f), rc.sp(12), 1f, 1f, 1f, 0.8f)
        val durW = rc.font.measureText(durText, rc.sp(12))
        rc.text(durText, pad + seekW - durW, seekY - rc.dp(18f), rc.sp(12), 0.67f, 0.67f, 0.67f, 0.8f)

        // Track list
        if (Engine.showTrackList) {
            val panelW = rc.dp(320f); val panelX = rc.w - panelW; val itemH = rc.dp(48f)
            rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f, 0.4f)
            rc.solid(panelX, 0f, panelW, rc.h, 0.063f, 0.063f, 0.110f)
            val title = if (Engine.trackListType == "audio") "Audio" else "Subtitles"
            rc.text(title, panelX + rc.dp(20f), rc.dp(44f), rc.sp(18), 0.733f, 0.525f, 0.988f)
            for ((i, item) in Engine.trackListItems.withIndex()) {
                val y = rc.dp(68f) + i * itemH
                val selected = when (Engine.trackListType) {
                    "audio" -> i == Engine.selectedAudioIdx; "subs" -> i == Engine.selectedSubIdx; else -> false
                }
                if (selected) rc.solid(panelX + rc.dp(8f), y, panelW - rc.dp(16f), itemH, 0.15f, 0.15f, 0.22f)
                rc.text((if (selected) "● " else "  ") + item, panelX + rc.dp(20f), y + itemH * 0.65f, rc.sp(14),
                    if (selected) 0.733f else 0.8f, if (selected) 0.525f else 0.8f, if (selected) 0.988f else 0.8f)
                rc.tappable(panelX, y, panelW, itemH) {
                    when (Engine.trackListType) {
                        "audio" -> Engine.selectedAudioIdx = i
                        "subs" -> { Engine.selectedSubIdx = i; Engine.cmdLoadSub = i }
                    }
                    Engine.showTrackList = false
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    var inputHandler: InputHandler? = null
    var onItemTapped: ((JanusApi.LibraryItem) -> Unit)? = null
    var onSeasonChanged: ((Int) -> Unit)? = null
    var onLogin: (() -> Unit)? = null
    var onLogout: (() -> Unit)? = null
    var onPlayerBack: (() -> Unit)? = null
    var onPlayerSeek: ((Long) -> Unit)? = null
    var onPlayerPause: ((Boolean) -> Unit)? = null
    var onSubChange: ((Int) -> Unit)? = null
}

class RenderCtx(
    val batch: QuadBatch,
    val font: FontAtlas,
    val tex: TextureManager,
    val thumbAtlas: ThumbnailAtlas,
    val coverAtlas: ThumbnailAtlas,
    val state: AppState,
    val dimens: Dimens,
    val w: Float,
    val h: Float,
    val density: Float,
) {
    fun solid(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        val u = font.whiteU; val v = font.whiteV
        batch.addQuad(x, y, w, h, u, v, u, v, r, g, b, a, layer = TextureArray.LAYER_FONT.toFloat())
    }

    fun gradient(x: Float, y: Float, w: Float, h: Float,
                 tlColor: FloatArray, trColor: FloatArray,
                 brColor: FloatArray, blColor: FloatArray) {
        batch.addGradientQuad(x, y, w, h, tlColor, trColor, brColor, blColor, font.whiteU, font.whiteV, TextureArray.LAYER_FONT.toFloat())
    }

    fun border(x: Float, y: Float, w: Float, h: Float, t: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        solid(x, y, w, t, r, g, b, a)                 // top
        solid(x, y + h - t, w, t, r, g, b, a)         // bottom
        solid(x, y, t, h, r, g, b, a)                 // left
        solid(x + w - t, y, t, h, r, g, b, a)         // right
    }

    fun text(str: String, x: Float, y: Float, sizePx: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
        font.addText(batch, str, x, y, sizePx, r, g, b, a)
    }

    fun textClipped(str: String, x: Float, y: Float, sizePx: Int, maxW: Float,
                    r: Float, g: Float, b: Float, a: Float = 1f) {
        font.addTextClipped(batch, str, x, y, sizePx, maxW, r, g, b, a)
    }

    fun textWrapped(str: String, x: Float, y: Float, sizePx: Int, maxW: Float, maxLines: Int,
                    r: Float, g: Float, b: Float, a: Float = 1f): Float {
        return font.addTextWrapped(batch, str, x, y, sizePx, maxW, maxLines, r, g, b, a)
    }

    // Images are now inline via texture array layers — no deferred system needed

    fun thumb(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = thumbAtlas.getUV(key) ?: return false
        if (!thumbAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1, layer = thumbAtlas.layerIndex.toFloat())
        return true
    }

    fun cover(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = coverAtlas.getUV(key) ?: return false
        if (!coverAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1, layer = coverAtlas.layerIndex.toFloat())
        return true
    }

    fun banner(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (!state.bannerReady) return false
        val texSize = 4096f
        val srcAspect = state.bannerW.toFloat() / state.bannerH
        val dstAspect = w / h
        // UVs cover the banner portion of the 2048x2048 layer
        val maxU = state.bannerW / texSize
        val maxV = state.bannerH / texSize
        val cu0: Float; val cv0: Float; val cu1: Float; val cv1: Float
        if (srcAspect < dstAspect) {
            val f = srcAspect / dstAspect; val crop = maxV * (1f - f) / 2f
            cu0 = 0f; cu1 = maxU; cv0 = crop; cv1 = maxV - crop
        } else {
            val f = dstAspect / srcAspect; val crop = maxU * (1f - f) / 2f
            cv0 = 0f; cv1 = maxV; cu0 = crop; cu1 = maxU - crop
        }
        batch.addQuad(x, y, w, h, cu0, cv0, cu1, cv1, layer = 3f)
        return true
    }

    fun tappable(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        hitRects.add(HitRect(x, y, w, h, action))
    }

    var hitRects = mutableListOf<HitRect>()

    fun sp(value: Int): Int = (value * density).toInt()
    fun dp(value: Float): Float = value * density
}
