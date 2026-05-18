package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders all text for a frame into a single full-screen RGBA bitmap,
 * uploads once, draws as one quad. Zero per-string texture switches.
 */
class ScreenTextRenderer {

    var textureId = 0; private set
    private var texW = 0
    private var texH = 0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var dirty = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private var frameHash = 0L
    private var lastFrameHash = -1L

    fun beginFrame(w: Int, h: Int) {
        if (bitmap == null || texW != w || texH != h) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap!!)
            texW = w; texH = h
            lastFrameHash = -1
        }
        bitmap!!.eraseColor(Color.TRANSPARENT)
        dirty = false
        frameHash = 0
    }

    fun drawText(text: String, x: Float, y: Float, sizePx: Float, typeface: Typeface,
                 r: Float, g: Float, b: Float, a: Float = 1f) {
        val c = canvas ?: return
        paint.textSize = sizePx
        paint.typeface = typeface
        paint.color = Color.argb((a * 255).toInt(), (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        c.drawText(text, x, y, paint)
        dirty = true
        frameHash = frameHash * 31 + text.hashCode() + (x * 100).toLong() + (y * 100).toLong() + sizePx.toLong()
    }

    fun drawTextClipped(text: String, x: Float, y: Float, sizePx: Float, maxW: Float, typeface: Typeface,
                        r: Float, g: Float, b: Float, a: Float = 1f) {
        val c = canvas ?: return
        paint.textSize = sizePx
        paint.typeface = typeface
        paint.color = Color.argb((a * 255).toInt(), (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())

        if (paint.measureText(text) > maxW) {
            c.save()
            c.clipRect(x, y - sizePx * 1.5f, x + maxW, y + sizePx * 0.5f)
            c.drawText(text, x, y, paint)
            c.restore()
        } else {
            c.drawText(text, x, y, paint)
        }
        dirty = true
    }

    fun measureText(text: String, sizePx: Float, typeface: Typeface): Float {
        paint.textSize = sizePx
        paint.typeface = typeface
        return paint.measureText(text)
    }

    fun textHeight(sizePx: Float): Float {
        paint.textSize = sizePx
        val fm = paint.fontMetrics
        return -fm.top + fm.bottom
    }

    fun textAscent(sizePx: Float): Float {
        paint.textSize = sizePx
        return -paint.fontMetrics.top
    }

    fun endFrame() {
        if (!dirty || bitmap == null) return
        if (frameHash != lastFrameHash) {
            android.util.Log.d("STR", "Upload: hash=$frameHash w=${bitmap!!.width} h=${bitmap!!.height}")
            uploadTexture()
            lastFrameHash = frameHash
        }
    }

    fun draw(batch: QuadBatch, w: Float, h: Float) {
        if (textureId == 0) return
        batch.flush()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        batch.begin()
        batch.addQuad(0f, 0f, w, h, 0f, 0f, 1f, 1f, layer = -1f)
        batch.flush()
        batch.begin()
    }

    private fun uploadTexture() {
        val bmp = bitmap ?: return
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf); buf.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bmp.width, bmp.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    fun release() {
        bitmap?.recycle(); bitmap = null; canvas = null
        if (textureId != 0) { GLES30.glDeleteTextures(1, intArrayOf(textureId), 0); textureId = 0 }
    }
}
