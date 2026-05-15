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

    private val projMatrix = FloatArray(16)
    var width = 0f; private set
    var height = 0f; private set
    private var lastFrameTime = 0L

    // Render context passed to screen functions
    val ctx get() = RenderCtx(batch, font, textures, state, dimens, width, height, density)

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
        val now = System.nanoTime()
        val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
        lastFrameTime = now

        textures.processUploads()

        state.seriesScroll.update(dt)
        state.movieScroll.update(dt)
        state.detailScroll.update(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()
        GLES30.glUniformMatrix4fv(shader.uProj, 1, false, projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(shader.uTex, 0)

        // Default texture: font atlas (contains white pixel + all glyphs)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        batch.begin()

        val rc = ctx
        when (state.screen) {
            Screen.HOME -> HomeScreen.render(rc)
            Screen.SERIES_DETAIL, Screen.MOVIE_DETAIL -> DetailScreen.render(rc)
        }

        batch.flush()
    }
}

class RenderCtx(
    val batch: QuadBatch,
    val font: FontAtlas,
    val tex: TextureManager,
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
        batch.addGradientQuad(x, y, w, h, tlColor, trColor, brColor, blColor)
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

    // Draw an image — flushes current batch, binds image texture, draws, restores font atlas
    fun image(texKey: String, x: Float, y: Float, w: Float, h: Float) {
        val texId = tex.get(texKey)
        if (texId == 0) return
        batch.flush()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        batch.begin()
        batch.addQuad(x, y, w, h)
        batch.flush()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, font.textureId)
        batch.begin()
    }

    fun sp(value: Int): Int = (value * density).toInt()
    fun dp(value: Float): Float = value * density
}
