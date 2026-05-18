package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders text strings to GL textures via Canvas. Cached by content hash.
 * Used on devices where baked font atlas is too low resolution (TV at 2048).
 */
class TextBitmapCache(private val typeface: Typeface = Typeface.DEFAULT) {

    data class Entry(val textureId: Int, val w: Int, val h: Int, val baselineY: Float)

    private val cache = LinkedHashMap<Long, Entry>(128, 0.75f, true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val maxEntries = 64

    fun get(text: String, sizePx: Int, r: Float, g: Float, b: Float, a: Float): Entry? {
        val hash = text.hashCode().toLong() * 31 + sizePx
        cache[hash]?.let { return it }

        if (text.isEmpty()) return null

        paint.textSize = sizePx.toFloat()
        paint.typeface = typeface
        paint.color = Color.argb((a * 255).toInt(), (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())

        val fm = paint.fontMetrics
        val w = (paint.measureText(text) + 4).toInt().coerceAtLeast(1)
        val h = (-fm.top + fm.bottom + 4).toInt().coerceAtLeast(1)
        val baselineY = -fm.top + 2

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawText(text, 2f, baselineY, paint)

        val texId = uploadBitmap(bmp)
        bmp.recycle()

        val entry = Entry(texId, w, h, baselineY)

        // Evict old entries
        if (cache.size >= maxEntries) {
            val oldest = cache.entries.first()
            GLES30.glDeleteTextures(1, intArrayOf(oldest.value.textureId), 0)
            cache.remove(oldest.key)
        }
        cache[hash] = entry
        return entry
    }

    fun measureText(text: String, sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        paint.typeface = typeface
        return paint.measureText(text)
    }

    fun textHeight(sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        val fm = paint.fontMetrics
        return -fm.top + fm.bottom
    }

    fun textAscent(sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        return -paint.fontMetrics.top
    }

    private fun uploadBitmap(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf); buf.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bmp.width, bmp.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        return ids[0]
    }

    fun clear() {
        for ((_, e) in cache) GLES30.glDeleteTextures(1, intArrayOf(e.textureId), 0)
        cache.clear()
    }
}
