package com.janusplus.v2

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import com.janusplus.FontAtlas
import com.janusplus.QuadBatch
import com.janusplus.ShaderProgram
import com.janusplus.TextureArray
import com.janusplus.ThumbnailAtlas
import com.janusplus.VideoSurface
import com.janusplus.JanusApi
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.ConcurrentLinkedQueue

// ── Input ──

data class Touch(val action: Int, val x: Float, val y: Float)
data class HitRect(val x: Float, val y: Float, val w: Float, val h: Float, val action: () -> Unit)

// ── State machine ──

enum class Screen { HOME, SERIES, MOVIE, SETTINGS, PLAYER }

interface GameState {
    fun init(app: App)
    fun update(app: App, touches: List<Touch>)
    fun draw(app: App, rc: RC)
    fun cleanup(app: App)
}

// ── Render context ──

class RC(
    val batch: QuadBatch,
    val font: FontAtlas,
    val texArray: TextureArray,
    val coverAtlas: ThumbnailAtlas,
    val thumbAtlas: ThumbnailAtlas,
    val w: Float,
    val h: Float,
    val density: Float,
) {
    val hitRects = mutableListOf<HitRect>()

    fun solid(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        batch.addQuad(x, y, w, h, font.whiteU, font.whiteV, font.whiteU, font.whiteV, r, g, b, a,
            layer = TextureArray.LAYER_FONT.toFloat())
    }

    fun gradient(x: Float, y: Float, w: Float, h: Float,
                 tl: FloatArray, tr: FloatArray, br: FloatArray, bl: FloatArray) {
        batch.addGradientQuad(x, y, w, h, tl, tr, br, bl, font.whiteU, font.whiteV,
            TextureArray.LAYER_FONT.toFloat())
    }

    fun text(s: String, x: Float, y: Float, size: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
        font.addText(batch, s, x, y, size, r, g, b, a)
    }

    fun textClipped(s: String, x: Float, y: Float, size: Int, maxW: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        font.addTextClipped(batch, s, x, y, size, maxW, r, g, b, a)
    }

    fun textWrapped(s: String, x: Float, y: Float, size: Int, maxW: Float, maxLines: Int,
                    r: Float, g: Float, b: Float, a: Float = 1f): Float {
        return font.addTextWrapped(batch, s, x, y, size, maxW, maxLines, r, g, b, a)
    }

    fun cover(key: String, x: Float, y: Float, w: Float, h: Float): Boolean {
        val uv = coverAtlas.getUV(key) ?: return false
        if (!coverAtlas.isReady()) return false
        batch.addQuad(x, y, w, h, uv.u0, uv.v0, uv.u1, uv.v1, layer = coverAtlas.layerIndex.toFloat())
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
}

// ── Main app ──

class App(private val context: Context, private val assets: android.content.res.AssetManager, val density: Float) : GLSurfaceView.Renderer {

    lateinit var shader: ShaderProgram
    lateinit var batch: QuadBatch
    lateinit var font: FontAtlas
    lateinit var texArray: TextureArray
    val coverAtlas = ThumbnailAtlas()
    val thumbAtlas = ThumbnailAtlas()
    val videoSurface = VideoSurface()

    val projMatrix = FloatArray(16)
    var width = 0f; private set
    var height = 0f; private set

    // Input
    val touchQueue = ConcurrentLinkedQueue<Touch>()
    @Volatile var hitRects: List<HitRect> = emptyList()

    // Scroll — VelocityTracker + OverScroller, same physics as native Android
    @Volatile var scrollY = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var touchDownY = 0f
    private var scrollAtDown = 0f
    private var isTouchScrolling = false

    // Navigation stack
    private val backStack = mutableListOf<Pair<Screen, GameState>>()

    // State machine
    var currentScreen = Screen.HOME
    var currentState: GameState = HomeState()
    var pendingTransition: Pair<Screen, GameState>? = null
    private var isBackNavigation = false

    // Shared data
    var api: JanusApi? = null
    var library: List<JanusApi.LibraryItem> = emptyList()

    // FPS
    private var frameCount = 0; private var fpsTimer = 0L; var fps = 0

    fun transition(screen: Screen, state: GameState) {
        pendingTransition = screen to state
    }

    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        val prev = backStack.removeAt(backStack.lastIndex)
        isBackNavigation = true
        pendingTransition = prev
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.039f, 0.039f, 0.102f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shader = ShaderProgram()
        shader.compile()
        shader.compileExternal()

        batch = QuadBatch()
        batch.initGL()

        texArray = TextureArray(4096, TextureArray.LAYER_THUMB_FIRST + TextureArray.LAYER_THUMB_COUNT)
        texArray.initGL()

        font = FontAtlas(assets)
        font.initGL(texArray)

        coverAtlas.texArray = texArray
        coverAtlas.layerIndex = TextureArray.LAYER_COVERS
        thumbAtlas.texArray = texArray
        thumbAtlas.layerIndex = texArray.nextThumbLayer()

        videoSurface.initGL()

        currentState.init(this)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = w.toFloat(); height = h.toFloat()
        Matrix.orthoM(projMatrix, 0, 0f, width, height, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        frameCount++
        if (now - fpsTimer > 1_000_000_000L) { fps = frameCount; frameCount = 0; fpsTimer = now }

        // Transition
        val trans = pendingTransition
        if (trans != null) {
            pendingTransition = null
            if (!isBackNavigation) {
                backStack.add(currentScreen to currentState)
            }
            isBackNavigation = false
            currentState.cleanup(this)
            currentScreen = trans.first
            currentState = trans.second
            currentState.init(this)
        }

        // Uploads
        texArray.processUploads()
        coverAtlas.uploadIfNeeded()
        thumbAtlas.uploadIfNeeded()

        // Poll input
        val touches = mutableListOf<Touch>()
        while (true) { touches.add(touchQueue.poll() ?: break) }

        // Scroll fling (OverScroller — same deceleration curve as native Android)
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceAtLeast(0f)
        }

        // Update
        currentState.update(this, touches)

        // Draw
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()
        GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shader.uTex, 0)
        texArray.bind()
        batch.begin()

        val rc = RC(batch, font, texArray, coverAtlas, thumbAtlas, width, height, density)
        currentState.draw(this, rc)

        batch.flush()
        hitRects = rc.hitRects.toList()
    }

    fun onTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        val x = event.x
        val y = event.y

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                touchDownY = y
                scrollAtDown = scrollY
                isTouchScrolling = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = touchDownY - y
                if (!isTouchScrolling && Math.abs(dy) > 12f) isTouchScrolling = true
                if (isTouchScrolling) {
                    scrollY = (scrollAtDown + dy).coerceAtLeast(0f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isTouchScrolling && action == MotionEvent.ACTION_UP) {
                    for (hr in hitRects) {
                        if (x >= hr.x && x <= hr.x + hr.w && y >= hr.y && y <= hr.y + hr.h) {
                            hr.action()
                            velocityTracker?.recycle(); velocityTracker = null
                            return
                        }
                    }
                }
                if (isTouchScrolling) {
                    velocityTracker?.apply {
                        addMovement(event)
                        computeCurrentVelocity(1000, 8000f * density)
                        val vy = -yVelocity.toInt()
                        scroller.fling(0, scrollY.toInt(), 0, vy,
                            0, 0, 0, Int.MAX_VALUE / 2)
                    }
                }
                isTouchScrolling = false
                velocityTracker?.recycle(); velocityTracker = null
            }
        }
    }
}
