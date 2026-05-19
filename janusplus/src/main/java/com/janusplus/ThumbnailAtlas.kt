package com.janusplus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class ThumbnailAtlas {

    data class ThumbUV(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                       val srcAspect: Float = 1f)

    @Volatile private var uvMap = HashMap<String, ThumbUV>()
    @Volatile private var ready = false
    @Volatile var layerIndex = 0
    var texArray: TextureArray? = null
    var ownTextureId = 0; private set  // Separate GL_TEXTURE_2D for covers (faster on MediaTek)

    data class TileUpload(val bitmap: Bitmap, val x: Int, val y: Int, val layer: Int)
    data class PendingAtlas(val tiles: List<TileUpload>, val uvMap: HashMap<String, ThumbUV>, val layer: Int)
    val pendingQueue = ConcurrentLinkedQueue<PendingAtlas>()

    fun initCoverGL(size: Int) {
        texSize = size
        if (ownTextureId != 0) {
            android.opengl.GLES30.glDeleteTextures(1, intArrayOf(ownTextureId), 0)
        }
        val ids = IntArray(1)
        android.opengl.GLES30.glGenTextures(1, ids, 0)
        ownTextureId = ids[0]
        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ownTextureId)
        android.opengl.GLES30.glTexImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES30.GL_RGBA,
            size, size, 0, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, null)
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MIN_FILTER, android.opengl.GLES30.GL_LINEAR)
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MAG_FILTER, android.opengl.GLES30.GL_LINEAR)
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_WRAP_S, android.opengl.GLES30.GL_CLAMP_TO_EDGE)
        android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_WRAP_T, android.opengl.GLES30.GL_CLAMP_TO_EDGE)
    }

    fun bindCover() {
        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ownTextureId)
    }

    private var currentTiles: List<TileUpload>? = null
    private var currentUvMap: HashMap<String, ThumbUV>? = null
    private var tileIdx = 0
    private var texSize = 2048

    fun pack(entries: List<Pair<String, Bitmap>>, forLayer: Int) {
        if (entries.isEmpty()) return

        val thumbW = entries.first().second.width
        val thumbH = entries.first().second.height
        val count = entries.size

        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val layerSize = texSize.toFloat()
        val newMap = HashMap<String, ThumbUV>(count * 2)
        val tiles = mutableListOf<TileUpload>()

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

            // Render tile to its own small bitmap
            val tile = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            Canvas(tile).drawBitmap(bmp, srcRect, Rect(0, 0, thumbW, thumbH), null)
            tiles.add(TileUpload(tile, x, y, forLayer))

            newMap[key] = ThumbUV(
                x.toFloat() / layerSize,
                y.toFloat() / layerSize,
                (x + thumbW).toFloat() / layerSize,
                (y + thumbH).toFloat() / layerSize,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
            bmp.recycle()
        }

        pendingQueue.add(PendingAtlas(tiles, newMap, forLayer))
    }

    // Upload one tile per frame — small glTexSubImage3D calls don't break swap cadence
    fun processPending() {
        if (currentTiles == null) {
            val pending = pendingQueue.poll() ?: return
            if (pending.layer != layerIndex) {
                for (t in pending.tiles) t.bitmap.recycle()
                return
            }
            currentTiles = pending.tiles
            currentUvMap = pending.uvMap
            tileIdx = 0
        }

        val tiles = currentTiles ?: return
        val ta = texArray ?: return

        // Composite all tiles into one bitmap, then upload as glTexImage2D
        // (avoids glTexSubImage which permanently breaks MediaTek swap cadence)
        val composite = android.graphics.Bitmap.createBitmap(texSize, texSize, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(composite)
        while (tileIdx < tiles.size) {
            val tile = tiles[tileIdx]
            canvas.drawBitmap(tile.bitmap, tile.x.toFloat(), tile.y.toFloat(), null)
            tile.bitmap.recycle()
            tileIdx++
        }
        // Full texture replace — not a sub-image update
        val buf = ByteBuffer.allocateDirect(texSize * texSize * 4).order(ByteOrder.nativeOrder())
        composite.copyPixelsToBuffer(buf); buf.position(0)
        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ownTextureId)
        android.opengl.GLES30.glTexImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, android.opengl.GLES30.GL_RGBA,
            texSize, texSize, 0, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, buf)
        composite.recycle()

        uvMap = currentUvMap ?: HashMap()
        ready = true
        currentTiles = null
        currentUvMap = null
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]

    fun isReady(): Boolean = ready

    fun invalidate() {
        ready = false
        currentTiles?.forEach { it.bitmap.recycle() }
        currentTiles = null; currentUvMap = null
        while (true) {
            val p = pendingQueue.poll() ?: break
            for (t in p.tiles) t.bitmap.recycle()
        }
    }

    fun clear() {
        uvMap = HashMap()
        ready = false
        invalidate()
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
