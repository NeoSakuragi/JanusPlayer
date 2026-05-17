package com.janusplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.util.concurrent.ConcurrentLinkedQueue

class ThumbnailAtlas {

    data class ThumbUV(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                       val srcAspect: Float = 1f)

    @Volatile private var uvMap = HashMap<String, ThumbUV>()
    @Volatile private var ready = false
    @Volatile var layerIndex = 0
    var texArray: TextureArray? = null

    // Background thread builds atlas + UV map, GL thread applies after upload
    data class PendingAtlas(val bitmap: Bitmap, val uvMap: HashMap<String, ThumbUV>, val layer: Int)
    val pendingQueue = ConcurrentLinkedQueue<PendingAtlas>()

    fun pack(entries: List<Pair<String, Bitmap>>, forLayer: Int) {
        if (entries.isEmpty()) return
        val ta = texArray ?: return

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

            val layerSize = ta.size.toFloat()
            newMap[key] = ThumbUV(
                x.toFloat() / layerSize,
                y.toFloat() / layerSize,
                (x + thumbW).toFloat() / layerSize,
                (y + thumbH).toFloat() / layerSize,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
            bmp.recycle()
        }

        pendingQueue.add(PendingAtlas(atlas, newMap, forLayer))
    }

    // Called on GL thread — uploads bitmap directly to GPU and THEN sets ready
    fun processPending() {
        val pending = pendingQueue.poll() ?: return
        if (pending.layer != layerIndex) {
            pending.bitmap.recycle()
            return
        }
        val ta = texArray ?: return
        ta.uploadLayerNow(pending.layer, pending.bitmap)
        uvMap = pending.uvMap
        ready = true
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]

    fun isReady(): Boolean = ready

    fun clear() {
        uvMap = HashMap()
        ready = false
        while (true) { (pendingQueue.poll() ?: break).bitmap.recycle() }
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
