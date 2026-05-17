package com.janusplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicReference

class ThumbnailAtlas {

    data class ThumbUV(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                       val srcAspect: Float = 1f)

    data class PackResult(val uvMap: HashMap<String, ThumbUV>, val bitmap: Bitmap, val layer: Int)

    @Volatile private var uvMap = HashMap<String, ThumbUV>()
    @Volatile private var ready = false
    @Volatile var layerIndex = 0
    var texArray: TextureArray? = null

    // Background thread writes here; GL thread reads and applies
    private val pendingResult = AtomicReference<PackResult?>(null)

    fun pack(entries: List<Pair<String, Bitmap>>, forLayer: Int) {
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
        val newMap = HashMap<String, ThumbUV>(count * 2)

        for ((i, pair) in entries.withIndex()) {
            val (key, bmp) = pair
            val col = i % cols
            val row = i / cols
            val x = col * thumbW
            val y = row * thumbH

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

            val layerSize = texArray?.size?.toFloat() ?: atlasW.toFloat()
            newMap[key] = ThumbUV(
                x.toFloat() / layerSize,
                y.toFloat() / layerSize,
                (x + thumbW).toFloat() / layerSize,
                (y + thumbH).toFloat() / layerSize,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
            bmp.recycle()
        }

        // Atomically publish result — GL thread will only apply if layer matches current
        val old = pendingResult.getAndSet(PackResult(newMap, atlas, forLayer))
        old?.bitmap?.recycle()
    }

    // Called on GL thread only
    fun uploadIfNeeded() {
        val result = pendingResult.getAndSet(null) ?: return
        // Only apply if this result is for the current layer (not a stale page)
        if (result.layer != layerIndex) {
            result.bitmap.recycle()
            return
        }
        val ta = texArray ?: return
        ta.uploadLayer(result.layer, result.bitmap)
        uvMap = result.uvMap
        ready = true
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]

    fun isReady(): Boolean = ready

    fun clear() {
        uvMap = HashMap()
        ready = false
        pendingResult.getAndSet(null)?.bitmap?.recycle()
        val ta = texArray
        if (ta != null) {
            layerIndex = ta.nextThumbLayer()
        }
    }

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
