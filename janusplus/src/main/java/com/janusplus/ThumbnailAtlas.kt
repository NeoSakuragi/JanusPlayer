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
    private var ready = false
    var layerIndex = TextureArray.LAYER_COVERS
    var texArray: TextureArray? = null

    @Volatile var pendingBitmap: Bitmap? = null

    fun pack(entries: List<Pair<String, Bitmap>>) {
        if (entries.isEmpty()) return

        val thumbW = entries.first().second.width
        val thumbH = entries.first().second.height
        val count = entries.size
        android.util.Log.i("ThumbAtlas", "Packing $count entries at ${thumbW}x${thumbH}")

        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val rows = (count + cols - 1) / cols
        val atlasW = nextPow2(cols * thumbW)
        val atlasH = nextPow2(rows * thumbH)

        val atlas = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
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

            val layerSize = texArray?.size?.toFloat() ?: 4096f
            uvMap[key] = ThumbUV(
                x.toFloat() / layerSize,
                y.toFloat() / layerSize,
                (x + thumbW).toFloat() / layerSize,
                (y + thumbH).toFloat() / layerSize,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
        }

        pendingBitmap = atlas
    }

    fun uploadIfNeeded() {
        if (needsClear) { needsClear = false; ready = false }
        val bmp = pendingBitmap ?: return
        pendingBitmap = null
        val ta = texArray ?: return
        android.util.Log.i("ThumbAtlas", "Uploading layer $layerIndex: ${bmp.width}x${bmp.height}")
        ta.uploadLayer(layerIndex, bmp)
        ready = true
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]

    fun isReady(): Boolean = ready

    @Volatile var needsClear = false

    fun clear() {
        uvMap.clear()
        ready = false
        needsClear = true
        pendingBitmap?.recycle()
        pendingBitmap = null
    }

    // No GL cleanup needed — texture array layer is reused

    private fun nextPow2(v: Int): Int {
        var n = v - 1
        n = n or (n shr 1)
        n = n or (n shr 2)
        n = n or (n shr 4)
        n = n or (n shr 8)
        n = n or (n shr 16)
        val texSize = texArray?.size ?: 4096
        return (n + 1).coerceAtMost(texSize)
    }
}
