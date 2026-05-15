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
    lateinit var dimens: Dimens
    val thumbAtlas = ThumbnailAtlas()

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

        batch = QuadBatch()
        batch.initGL()

        textures = TextureManager()
        textures.initGL()

        font = FontAtlas(assets)
        font.initGL()

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
        val dt = ((frameStart - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameTime = frameStart
        frameCount++

        // Upload phase
        val uploadStart = System.nanoTime()
        textures.processUploads()
        thumbAtlas.uploadIfNeeded()
        state.coverAtlas.uploadIfNeeded()
        lastUploadMs = (System.nanoTime() - uploadStart) / 1_000_000f

        state.seriesScroll.update(dt)
        state.movieScroll.update(dt)
        state.detailScroll.update(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()
        GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shader.uTex, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)

        if (state.screen == Screen.PLAYING) return

        val rc = ctx
        rc.hitRects.clear()
        rc.deferredImages.clear()

        // Pre-pass: draw banner image FIRST if on detail page (avoids mid-frame texture switch)
        val buildStart = System.nanoTime()
        if ((state.screen == Screen.SERIES_DETAIL || state.screen == Screen.MOVIE_DETAIL) && state.selectedItem != null) {
            val bannerId = textures.get("banner_${state.selectedItem!!.id}")
            if (bannerId != 0) {
                val info = textures.getInfo("banner_${state.selectedItem!!.id}")!!
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bannerId)
                batch.begin()
                val heroH = dimens.heroH
                val heroTop = -state.detailScroll.offset
                val srcAspect = info.width.toFloat() / info.height.toFloat()
                val dstAspect = width / heroH
                val u0: Float; val v0: Float; val u1: Float; val v1: Float
                if (srcAspect > dstAspect) {
                    val f = dstAspect / srcAspect; u0 = (1f - f) / 2f; u1 = 1f - u0; v0 = 0f; v1 = 1f
                } else {
                    val f = srcAspect / dstAspect; v0 = (1f - f) / 2f; v1 = 1f - v0; u0 = 0f; u1 = 1f
                }
                batch.addQuad(0f, heroTop, width, heroH, u0, v0, u1, v1)
                batch.flush()
            }
        }

        // Main build phase — font atlas bound
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        batch.begin()

        when (state.screen) {
            Screen.HOME -> HomeScreen.render(rc)
            Screen.SERIES_DETAIL, Screen.MOVIE_DETAIL -> DetailScreen.render(rc)
            Screen.PLAYING -> {}
        }
        val g = font.glyphsEmitted; val tc = font.addTextCalls
        font.glyphsEmitted = 0; font.addTextCalls = 0
        val perfText = "${fps}fps ${lastFrameMs.toInt()}ms b=${lastBuildMs.toInt()} f=${lastFlushMs.toInt()} g=$g tc=$tc"
        rc.text(perfText, rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
        lastBuildMs = (System.nanoTime() - buildStart) / 1_000_000f

        // Flush phase — all GL calls here
        val flushStart = System.nanoTime()
        batch.flush()

        // Cover atlas pass (home page)
        if (state.screen == Screen.HOME && state.coverAtlas.isReady()) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, state.coverAtlas.textureId)
            batch.begin()
            HomeScreen.renderCoverPasses(rc)
            batch.flush()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        }

        // Thumb atlas pass (detail page)
        if ((state.screen == Screen.SERIES_DETAIL || state.screen == Screen.MOVIE_DETAIL) && thumbAtlas.isReady()) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, thumbAtlas.textureId)
            batch.begin()
            val cards = state.seasonCards?.episodes ?: emptyList()
            if (cards.isNotEmpty()) DetailScreen.renderThumbs(rc, cards, dimens.heroH - state.detailScroll.offset)
            batch.flush()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        }

        // Deferred individual images (banner)
        rc.flushImages()
        lastFlushMs = (System.nanoTime() - flushStart) / 1_000_000f

        inputHandler?.hitRects?.clear()
        inputHandler?.hitRects?.addAll(rc.hitRects)

        val tapped = state.pendingTap
        if (tapped != null) {
            state.pendingTap = null
            onItemTapped?.invoke(tapped)
        }

        lastFrameMs = (System.nanoTime() - frameStart) / 1_000_000f

        // Log every second
        if (frameStart - fpsTimer > 1_000_000_000L) {
            fps = frameCount
            android.util.Log.i("PERF", "fps=$fps frame=${lastFrameMs}ms build=${lastBuildMs}ms flush=${lastFlushMs}ms upload=${lastUploadMs}ms quads=${batch.quadCount} screen=${state.screen}")
            frameCount = 0
            fpsTimer = frameStart
        }
    }

    var inputHandler: InputHandler? = null
    var onItemTapped: ((JanusApi.LibraryItem) -> Unit)? = null
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
    // Solid-color quad using the white pixel baked into the font atlas
    fun solid(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        val u = font.whiteU
        val v = font.whiteV
        batch.addQuad(x, y, w, h, u, v, u, v, r, g, b, a)
    }

    fun gradient(x: Float, y: Float, w: Float, h: Float,
                 tlColor: FloatArray, trColor: FloatArray,
                 brColor: FloatArray, blColor: FloatArray) {
        batch.addGradientQuad(x, y, w, h, tlColor, trColor, brColor, blColor, font.whiteU, font.whiteV)
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

    data class DeferredImage(val texId: Int, val x: Float, val y: Float, val w: Float, val h: Float,
                             val u0: Float, val v0: Float, val u1: Float, val v1: Float)
    val deferredImages = mutableListOf<DeferredImage>()

    // Queue an image for deferred drawing — no flush, no bind, just record it
    fun image(texKey: String, x: Float, y: Float, w: Float, h: Float) {
        val info = tex.getInfo(texKey) ?: return
        val srcAspect = info.width.toFloat() / info.height.toFloat()
        val dstAspect = w / h
        val u0: Float; val v0: Float; val u1: Float; val v1: Float
        if (srcAspect > dstAspect) {
            val visibleFrac = dstAspect / srcAspect
            u0 = (1f - visibleFrac) / 2f; u1 = 1f - u0; v0 = 0f; v1 = 1f
        } else {
            val visibleFrac = srcAspect / dstAspect
            v0 = (1f - visibleFrac) / 2f; v1 = 1f - v0; u0 = 0f; u1 = 1f
        }
        deferredImages.add(DeferredImage(info.id, x, y, w, h, u0, v0, u1, v1))
    }

    // Flush all deferred images — sorted by texture, minimal binds
    fun flushImages() {
        if (deferredImages.isEmpty()) return
        deferredImages.sortBy { it.texId }
        var currentTex = -1
        for (img in deferredImages) {
            if (img.texId != currentTex) {
                batch.flush()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, img.texId)
                batch.begin()
                currentTex = img.texId
            }
            batch.addQuad(img.x, img.y, img.w, img.h, img.u0, img.v0, img.u1, img.v1)
        }
        batch.flush()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        batch.begin()
        deferredImages.clear()
    }

    // Draw a thumbnail from the atlas — NO flush/bind, stays in current batch
    fun thumb(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = thumbAtlas.getUV(key) ?: return false
        if (!thumbAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1)
        return true
    }

    // Begin/end a thumb batch — binds the atlas texture once for all thumbs
    fun beginThumbs() {
        if (!thumbAtlas.isReady()) return
        batch.flush()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, thumbAtlas.textureId)
        batch.begin()
    }

    fun endThumbs() {
        if (!thumbAtlas.isReady()) return
        batch.flush()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        batch.begin()
    }

    fun tappable(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        hitRects.add(HitRect(x, y, w, h, action))
    }

    var hitRects = mutableListOf<HitRect>()

    fun sp(value: Int): Int = (value * density).toInt()
    fun dp(value: Float): Float = value * density
}
