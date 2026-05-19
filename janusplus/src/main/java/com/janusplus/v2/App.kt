package com.janusplus.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import com.janusplus.CompressedTextureArray
import com.janusplus.CoverCache
import com.janusplus.GlyphAtlas
import com.janusplus.QuadBatch
import com.janusplus.ShaderProgram
import com.janusplus.TextureArray
import com.janusplus.ThumbnailAtlas
import com.janusplus.VideoBlitThread
import com.janusplus.VideoSurface
import com.janusplus.JanusApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.ConcurrentLinkedQueue

data class Touch(val action: Int, val x: Float, val y: Float)
data class HitRect(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)

enum class Screen { HOME, SERIES, MOVIE, SETTINGS, PLAYER }

interface GameState {
    fun init(app: App)
    fun update(app: App, touches: List<Touch>, actions: List<Action>)
    fun draw(app: App, rc: RC)
    fun cleanup(app: App)
}

class RC(
    val batch: QuadBatch,
    val texArray: TextureArray,
    val coverAtlas: ThumbnailAtlas,
    val coverCache: CoverCache,
    val thumbAtlas: ThumbnailAtlas,
    val w: Float, val h: Float,
    val density: Float,
    val eink: Boolean = false,
    val typeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT,
) {
    var whiteU = 0f
    var whiteV = 0f
    val atlases = HashMap<Int, GlyphAtlas>()
    val hitRects = mutableListOf<HitRect>()

    val textR get() = if (eink) 0.1f else 1f
    val textG get() = if (eink) 0.1f else 1f
    val textB get() = if (eink) 0.1f else 1f
    val dimR get() = if (eink) 0.4f else 0.7f
    val dimG get() = if (eink) 0.4f else 0.7f
    val dimB get() = if (eink) 0.4f else 0.7f
    val accentR get() = if (eink) 0.3f else 0.733f
    val accentG get() = if (eink) 0.2f else 0.525f
    val accentB get() = if (eink) 0.6f else 0.988f
    val panelR get() = if (eink) 0.9f else 0.102f
    val panelG get() = if (eink) 0.9f else 0.102f
    val panelB get() = if (eink) 0.88f else 0.180f

    var whiteLayer = TextureArray.LAYER_UI.toFloat()

    fun solid(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        batch.addQuad(x, y, w, h, whiteU, whiteV, whiteU, whiteV, r, g, b, a,
            layer = whiteLayer)
    }

    fun gradient(x: Float, y: Float, w: Float, h: Float,
                 tl: FloatArray, tr: FloatArray, br: FloatArray, bl: FloatArray) {
        batch.addGradientQuad(x, y, w, h, tl, tr, br, bl, whiteU, whiteV, whiteLayer)
    }

    private val glyphPad = 2f

    fun text(s: String, x: Float, y: Float, size: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
        val atlas = atlases[size] ?: return
        var cx = x
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val glyph = atlas.glyphs[cp]
            if (glyph != null) {
                batch.addQuad(cx - glyphPad, y - glyph.ascent, glyph.w, glyph.h,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    r, g, b, a, layer = glyph.page.toFloat())
                cx += glyph.advance
            }
            i += Character.charCount(cp)
        }
    }

    fun textClipped(s: String, x: Float, y: Float, size: Int, maxW: Float,
                    r: Float, g: Float, b: Float, a: Float = 1f) {
        val atlas = atlases[size] ?: return
        var cx = x
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val glyph = atlas.glyphs[cp]
            if (glyph != null) {
                if (cx + glyph.advance - x > maxW) break
                batch.addQuad(cx - glyphPad, y - glyph.ascent, glyph.w, glyph.h,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    r, g, b, a, layer = glyph.page.toFloat())
                cx += glyph.advance
            }
            i += Character.charCount(cp)
        }
    }

    fun textScaled(s: String, x: Float, y: Float, size: Int, scale: Float,
                   r: Float, g: Float, b: Float, a: Float = 1f) {
        val atlas = atlases[size] ?: return
        var cx = x
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val glyph = atlas.glyphs[cp]
            if (glyph != null) {
                batch.addQuad(cx - glyphPad * scale, y - glyph.ascent * scale,
                    glyph.w * scale, glyph.h * scale,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    r, g, b, a, layer = glyph.page.toFloat())
                cx += glyph.advance * scale
            }
            i += Character.charCount(cp)
        }
    }

    fun measureText(s: String, size: Int): Float = atlases[size]?.measureText(s) ?: 0f
    fun textHeight(size: Int): Float = atlases[size]?.lineHeight ?: 0f
    fun textAscent(size: Int): Float = atlases[size]?.ascent ?: 0f

    fun cover(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = coverCache.getUV(key)
        if (uv != null) {
            batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1, layer = -2f)
            return true
        }
        val oldUv = coverAtlas.getUV(key) ?: return false
        if (!coverAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, oldUv.u0, oldUv.v0, oldUv.u1, oldUv.v1, layer = -2f)
        return true
    }

    fun thumb(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = thumbAtlas.getUV(key) ?: return false
        if (!thumbAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1, layer = thumbAtlas.layerIndex.toFloat())
        return true
    }

    fun banner(x: Float, y: Float, w: Float, h: Float, bannerW: Int, bannerH: Int): Boolean {
        if (bannerW == 0 || bannerH == 0) return false
        val texSize = texArray.size.toFloat()
        val srcAspect = bannerW.toFloat() / bannerH
        val dstAspect = w / h
        val maxU = bannerW / texSize; val maxV = bannerH / texSize
        val cu0: Float; val cv0: Float; val cu1: Float; val cv1: Float
        if (srcAspect < dstAspect) {
            val f = srcAspect / dstAspect; val crop = maxV * (1f - f) / 2f
            cu0 = 0f; cu1 = maxU; cv0 = crop; cv1 = maxV - crop
        } else {
            val f = dstAspect / srcAspect; val crop = maxU * (1f - f) / 2f
            cv0 = 0f; cv1 = maxV; cu0 = crop; cu1 = maxU - crop
        }
        batch.addQuad(x, y, w, h, cu0, cv0, cu1, cv1, layer = TextureArray.LAYER_BANNER.toFloat())
        return true
    }

    fun tappable(x: Float, y: Float, w: Float, h: Float, action: () -> Unit) {
        hitRects.add(HitRect(x, y, w, h, action))
    }

    fun border(x: Float, y: Float, w: Float, h: Float, t: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        solid(x, y, w, t, r, g, b, a)
        solid(x, y + h - t, w, t, r, g, b, a)
        solid(x, y, t, h, r, g, b, a)
        solid(x + w - t, y, t, h, r, g, b, a)
    }

    fun sp(v: Int): Int = (v * density).toInt()
    fun dp(v: Float): Float = v * density

    fun spinner(cx: Float, cy: Float, elapsed: Float) {
        val radius = dp(48f)
        for (i in 0 until 12) {
            val angle = (i.toFloat() / 12) * 2f * Math.PI.toFloat() + elapsed * 6f
            val dotX = cx + kotlin.math.cos(angle) * radius
            val dotY = cy + kotlin.math.sin(angle) * radius
            val alpha = i.toFloat() / 12
            val dotR = dp(3f + alpha * 2f)
            solid(dotX - dotR, dotY - dotR, dotR * 2, dotR * 2, 0.733f, 0.525f, 0.988f, alpha)
        }
    }
}

class App(val context: Context, private val assets: android.content.res.AssetManager, val density: Float) : GLSurfaceView.Renderer {

    lateinit var shader: ShaderProgram
    lateinit var batch: QuadBatch
    lateinit var texArray: TextureArray
    lateinit var etc2Array: CompressedTextureArray
    val coverAtlas = ThumbnailAtlas().apply { layerIndex = TextureArray.LAYER_COVERS }
    val coverCache = CoverCache()
    val thumbAtlas = ThumbnailAtlas()
    val videoSurface = VideoSurface()
    var blitThread: VideoBlitThread? = null
    var einkMode = false
    var isTV = false
    @Volatile var lastLoadLog: List<String> = emptyList()
    var defaultTypeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT

    var whiteU = 0f; private set
    var whiteV = 0f; private set
    private var glyphLayer = TextureArray.LAYER_GLYPH_FIRST
    private var glyphY = 0
    val currentGlyphLayer get() = glyphLayer
    val currentGlyphY get() = glyphY
    fun resetGlyphCursor(layer: Int, y: Int) { glyphLayer = layer; glyphY = y }

    val projMatrix = FloatArray(16)
    var width = 0f; private set
    var height = 0f; private set

    val touchQueue = ConcurrentLinkedQueue<Touch>()
    val keyQueue = ConcurrentLinkedQueue<Int>()
    val actionQueue = ConcurrentLinkedQueue<Action>()
    @Volatile var hitRects: List<HitRect> = emptyList()

    @Volatile var scrollY = 0f
    private val scroller = OverScroller(context)

    fun smoothScrollTo(targetY: Float) {
        scroller.forceFinished(true)
        val dy = (targetY - scrollY).toInt()
        scroller.startScroll(0, scrollY.toInt(), 0, dy, 300)
    }
    private var velocityTracker: VelocityTracker? = null
    private var touchDownX = 0f
    @Volatile var touchDownY = 0f
    private var scrollAtDown = 0f
    private var isTouchScrolling = false
    private var scrollAxis = 0 // 0=undecided, 1=vertical, 2=horizontal

    // Horizontal scroll callback — set by HomeState to receive swipe deltas
    @Volatile var onHorizontalScroll: ((dx: Float) -> Unit)? = null
    @Volatile var onHorizontalFling: ((vx: Float) -> Unit)? = null

    // Navigation — back stack stores intents, not state objects
    sealed class Nav {
        object Home : Nav()
        data class Series(val item: JanusApi.LibraryItem) : Nav()
        data class Player(val item: JanusApi.LibraryItem, val episode: JanusApi.Episode, val baseUrl: String) : Nav()
        object Settings : Nav()
    }

    private val navStack = mutableListOf<Nav>()
    var currentNav: Nav = Nav.Home
    var currentScreen = Screen.HOME
    var currentState: GameState = HomeState()
    private var pendingNav: Nav? = null
    private var pendingReplace: Pair<Screen, GameState>? = null
    private var isBack = false

    var api: JanusApi? = null
    var library: List<JanusApi.LibraryItem> = emptyList()

    @Volatile var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    var onMainThread: ((Runnable) -> Unit)? = null

    private var frameCount = 0; private var fpsTimer = 0L; var fps = 0

    @Volatile var screenshotPath: String? = null
    @Volatile var screenshotSeq = 0

    fun navigate(nav: Nav) {
        pendingNav = nav
    }

    fun replace(screen: Screen, state: GameState) {
        pendingReplace = screen to state
    }

    fun goBack(): Boolean {
        if (navStack.isEmpty()) return false
        isBack = true
        pendingNav = navStack.removeAt(navStack.lastIndex)
        return true
    }

    private fun createState(nav: Nav): Pair<Screen, GameState> = when (nav) {
        Nav.Home -> Screen.HOME to HomeState()
        is Nav.Series -> Screen.SERIES to SeriesLoadingState(nav.item)
        is Nav.Player -> Screen.PLAYER to PlayerLoadingState(nav.item, nav.episode, nav.baseUrl)
        Nav.Settings -> Screen.SETTINGS to SettingsState()
    }

    // Legacy bridge — screens that still call transition() directly
    fun transition(screen: Screen, state: GameState) {
        pendingReplace = screen to state
    }

    fun uploadGlyphAtlas(atlas: GlyphAtlas, bmp: Bitmap) {
        uploadSinglePage(atlas, bmp, 0)
        for ((i, extra) in atlas.extraPages.withIndex()) {
            uploadSinglePage(atlas, extra, i + 1)
        }
    }

    private fun uploadSinglePage(atlas: GlyphAtlas, bmp: Bitmap, pageIdx: Int) {
        val h = bmp.height
        val ts = texArray.size.toFloat()

        if (glyphY + h > texArray.size) {
            glyphLayer++
            glyphY = 0
            if (glyphLayer >= TextureArray.LAYER_GLYPH_FIRST + TextureArray.LAYER_GLYPH_COUNT) {
                android.util.Log.e("App", "Glyph layer overflow! layer=$glyphLayer")
                bmp.recycle()
                return
            }
        }

        val w = bmp.width
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf); buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray.textureId)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
            0, glyphY, glyphLayer, w, h, 1,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

        val glyphs = atlas.glyphs
        val keys = glyphs.keys.toIntArray()
        for (cp in keys) {
            val g = glyphs[cp] ?: continue
            if (g.page != pageIdx) continue
            g.u0 = g.u0 / ts
            g.v0 = (g.v0 + glyphY) / ts
            g.u1 = g.u1 / ts
            g.v1 = (g.v1 + glyphY) / ts
            g.page = glyphLayer
        }

        glyphY += h + 2
        bmp.recycle()
    }

    fun resetGlyphPages() {
        glyphLayer = TextureArray.LAYER_GLYPH_FIRST
        glyphY = 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        swapIntervalFrames = 0
        android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 1)

        GLES30.glClearColor(0.039f, 0.039f, 0.102f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shader = ShaderProgram()
        shader.compile()
        shader.compileExternal()

        batch = QuadBatch()
        batch.initGL()

        val maxTexSize = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)
        isTV = context.packageManager.hasSystemFeature("android.software.leanback")
        defaultTypeface = try { android.graphics.Typeface.createFromAsset(assets, "fonts/NotoSansJP-Regular.ttf") }
                          catch (_: Exception) { android.graphics.Typeface.DEFAULT }

        val uiTexSize = if (isTV) 2048 else maxTexSize[0].coerceAtMost(4096)
        android.util.Log.i("App", "GL maxTexture=${maxTexSize[0]} using=$uiTexSize isTV=$isTV")
        texArray = TextureArray(uiTexSize)
        texArray.initGL()

        // White pixel on LAYER_UI (layer 0) — dedicated, never overwritten by glyphs
        resetGlyphPages()
        val white = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        white.eraseColor(Color.WHITE)
        texArray.uploadLayerNow(TextureArray.LAYER_UI, white)
        white.recycle()
        whiteU = 1f / texArray.size
        whiteV = 1f / texArray.size

        etc2Array = CompressedTextureArray(4096, 4)
        etc2Array.initGL()

        coverAtlas.texArray = texArray
        coverAtlas.layerIndex = TextureArray.LAYER_COVERS
        coverAtlas.initCoverGL(texArray.size)
        coverAtlas.invalidate()
        coverCache.initGL()
        thumbAtlas.texArray = texArray
        thumbAtlas.layerIndex = texArray.nextThumbLayer()
        thumbAtlas.invalidate()

        videoSurface.initGL()

        blitThread?.stop()
        val eglDisplay = android.opengl.EGL14.eglGetCurrentDisplay()
        val eglContext = android.opengl.EGL14.eglGetCurrentContext()
        val eglConfigs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        android.opengl.EGL14.eglChooseConfig(eglDisplay, intArrayOf(
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, 0x40,
            android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8,
            android.opengl.EGL14.EGL_BLUE_SIZE, 8, android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
            android.opengl.EGL14.EGL_NONE
        ), 0, eglConfigs, 0, 1, numConfigs, 0)
        val eglConfig = eglConfigs[0]
        if (eglConfig != null) {
            blitThread = VideoBlitThread(videoSurface).also {
                it.start(eglDisplay, eglConfig, eglContext)
            }
        }

        // GL context recreated — all VRAM gone. Recreate state from nav intent.
        currentState.cleanup(this)
        val (screen, state) = createState(currentNav)
        currentScreen = screen
        currentState = state
        currentState.init(this)

        // Re-fetch covers since GPU texture was destroyed
        val curApi = api
        if (curApi != null && library.isNotEmpty()) {
            kotlin.concurrent.thread {
                val covers = curApi.fetchLibraryCovers()
                for ((key, bmp) in covers) coverCache.uploadFromBitmap(key, bmp)
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = w.toFloat(); height = h.toFloat()
        Matrix.orthoM(projMatrix, 0, 0f, width, height, 0f, -1f, 1f)
    }

    private var lastFrameNano = 0L
    private var frameIntervalMs = 0f
    private var swapIntervalFrames = 0

    override fun onDrawFrame(gl: GL10?) {
        // Force swap interval every frame — some devices reset it
        android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 1)
        val now = System.nanoTime()
        if (lastFrameNano > 0) frameIntervalMs = frameIntervalMs * 0.9f + (now - lastFrameNano) / 1_000_000f * 0.1f
        lastFrameNano = now
        frameCount++
        if (now - fpsTimer > 1_000_000_000L) { fps = frameCount; frameCount = 0; fpsTimer = now }

        val nav = pendingNav
        if (nav != null) {
            pendingNav = null
            if (!isBack) navStack.add(currentNav)
            isBack = false
            currentState.cleanup(this)
            resetGlyphPages()
            currentNav = nav
            val (screen, state) = createState(nav)
            currentScreen = screen
            currentState = state
            currentState.init(this)
        }

        val repl = pendingReplace
        if (repl != null) {
            pendingReplace = null
            currentState.cleanup(this)
            resetGlyphPages()
            currentScreen = repl.first
            currentState = repl.second
            currentState.init(this)
        }

        val touches = mutableListOf<Touch>()
        while (true) { touches.add(touchQueue.poll() ?: break) }
        // Convert raw keycodes → actions
        val actions = mutableListOf<Action>()
        while (true) {
            val code = keyQueue.poll() ?: break
            Input.map(code)?.let { actions.add(it) }
        }
        while (true) { actions.add(actionQueue.poll() ?: break) }

        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceAtLeast(0f)
        }

        currentState.update(this, touches, actions)

        coverAtlas.processPending()
        coverCache.processPending()
        thumbAtlas.processPending()
        texArray.processUploads()

        if (einkMode) GLES30.glClearColor(0.95f, 0.95f, 0.93f, 1f)
        else GLES30.glClearColor(0.039f, 0.039f, 0.102f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        etc2Array.processUploads()
        shader.use()
        GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shader.uTex, 0)
        texArray.bind()
        etc2Array.bind(GLES30.GL_TEXTURE1)
        GLES30.glUniform1i(shader.uTexEtc2, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        videoSurface.bindRgb()
        GLES30.glUniform1i(shader.uTexVideo, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        if (currentScreen == Screen.HOME) coverCache.bind()
        else coverAtlas.bindCover()
        GLES30.glUniform1i(shader.uTexCover, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        batch.begin()

        val rc = RC(batch, texArray, coverAtlas, coverCache, thumbAtlas, width, height, density, einkMode, defaultTypeface)
        rc.whiteU = whiteU; rc.whiteV = whiteV
        rc.whiteLayer = TextureArray.LAYER_UI.toFloat()
        currentState.draw(this, rc)

        val flushT0 = System.nanoTime()
        batch.flush()
        val flushMs = (System.nanoTime() - flushT0) / 1_000_000f
        val totalMs = (System.nanoTime() - now) / 1_000_000f
        hitRects = rc.hitRects.toList()

        val ssPath = screenshotPath
        if (ssPath != null) {
            captureScreenshot(ssPath)
            screenshotPath = null
            screenshotSeq++
        }

        if (frameCount % 60 == 0) {
            android.util.Log.d("PERF", "[$currentScreen] interval:${"%.1f".format(frameIntervalMs)}ms work:${"%.1f".format(totalMs)}ms flush:${"%.1f".format(flushMs)}ms quads:${batch.lastQuadCount}")
        }
    }

    private fun captureScreenshot(path: String) {
        val w = width.toInt(); val h = height.toInt()
        if (w <= 0 || h <= 0) return
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        try {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val stride = w * 4
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val srcRow = h - 1 - row
                    val i = srcRow * stride + col * 4
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    val a = buf.get(i + 3).toInt() and 0xFF
                    bmp.setPixel(col, row, android.graphics.Color.argb(a, r, g, b))
                }
            }
            java.io.FileOutputStream(path).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.e("App", "Screenshot failed: ${e.message}")
        }
    }

    fun onTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        if (currentScreen == Screen.PLAYER) {
            touchQueue.add(Touch(action, x, y))
            return
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                touchDownX = x
                touchDownY = y
                scrollAtDown = scrollY
                isTouchScrolling = false
                scrollAxis = 0
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = touchDownX - x
                val dy = touchDownY - y
                if (!isTouchScrolling && (Math.abs(dx) > 12f || Math.abs(dy) > 12f)) {
                    isTouchScrolling = true
                    scrollAxis = if (Math.abs(dx) > Math.abs(dy)) 2 else 1
                }
                if (isTouchScrolling) {
                    if (scrollAxis == 1) {
                        scrollY = (scrollAtDown + dy).coerceAtLeast(0f)
                    } else if (scrollAxis == 2) {
                        onHorizontalScroll?.invoke(dx)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isTouchScrolling && action == MotionEvent.ACTION_UP) {
                    var hitFound = false
                    for (hr in hitRects) {
                        if (x >= hr.x && x <= hr.x + hr.w && y >= hr.y && y <= hr.y + hr.h) {
                            hr.action()
                            hitFound = true
                            break
                        }
                    }
                    if (!hitFound) {
                        touchQueue.add(Touch(1, x, y))
                    }
                    if (hitFound) {
                        velocityTracker?.recycle(); velocityTracker = null
                        return
                    }
                }
                if (isTouchScrolling) {
                    velocityTracker?.apply {
                        addMovement(event)
                        computeCurrentVelocity(1000, 8000f * density)
                        if (scrollAxis == 1) {
                            scroller.fling(0, scrollY.toInt(), 0, -yVelocity.toInt(), 0, 0, 0, Int.MAX_VALUE / 2)
                        } else if (scrollAxis == 2) {
                            onHorizontalFling?.invoke(-xVelocity)
                        }
                    }
                }
                isTouchScrolling = false
                if (scrollAxis != 2) onHorizontalFling?.invoke(0f)
                scrollAxis = 0
                velocityTracker?.recycle(); velocityTracker = null
            }
        }
    }
}
