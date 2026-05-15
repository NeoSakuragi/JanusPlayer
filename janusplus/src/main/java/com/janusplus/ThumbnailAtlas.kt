package com.janusplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.opengl.GLES30
import android.opengl.GLUtils

class ThumbnailAtlas {

    data class ThumbUV(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                       val srcAspect: Float = 1f)

    private val uvMap = HashMap<String, ThumbUV>()
    var textureId = 0; private set
    private var ready = false

    @Volatile var pendingBitmap: Bitmap? = null

    fun pack(entries: List<Pair<String, Bitmap>>) {
        if (entries.isEmpty()) return

        val thumbW = entries.first().second.width
        val thumbH = entries.first().second.height
        val count = entries.size

        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val rows = (count + cols - 1) / cols
        val atlasW = nextPow2(cols * thumbW)
        val atlasH = nextPow2(rows * thumbH)

        val atlas = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.RGB_565)
        val canvas = Canvas(atlas)

        for ((i, pair) in entries.withIndex()) {
            val (key, bmp) = pair
            val col = i % cols
            val row = i / cols
            val x = col * thumbW
            val y = row * thumbH

            // Center-crop: compute source rect to preserve aspect ratio
            val srcAspect = bmp.width.toFloat() / bmp.height.toFloat()
            val dstAspect = thumbW.toFloat() / thumbH.toFloat()
            val srcRect = if (srcAspect > dstAspect) {
                val visW = (bmp.height * dstAspect).toInt()
                val off = (bmp.width - visW) / 2
                Rect(off, 0, off + visW, bmp.height)
            } else {
                val visH = (bmp.width / dstAspect).toInt()
                val off = (bmp.height - visH) / 2
                Rect(0, off, bmp.width, off + visH)
            }
            canvas.drawBitmap(bmp, srcRect, Rect(x, y, x + thumbW, y + thumbH), null)
            bmp.recycle()

            uvMap[key] = ThumbUV(
                x.toFloat() / atlasW,
                y.toFloat() / atlasH,
                (x + thumbW).toFloat() / atlasW,
                (y + thumbH).toFloat() / atlasH,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
        }

        pendingBitmap = atlas
    }

    fun uploadIfNeeded() {
        val bmp = pendingBitmap ?: return
        pendingBitmap = null

        if (textureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        ready = true
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]

    fun isReady(): Boolean = ready

    fun clear() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        uvMap.clear()
        ready = false
        pendingBitmap?.recycle()
        pendingBitmap = null
    }

    private fun nextPow2(v: Int): Int {
        var n = v - 1
        n = n or (n shr 1)
        n = n or (n shr 2)
        n = n or (n shr 4)
        n = n or (n shr 8)
        n = n or (n shr 16)
        return (n + 1).coerceAtMost(4096)
    }
}
