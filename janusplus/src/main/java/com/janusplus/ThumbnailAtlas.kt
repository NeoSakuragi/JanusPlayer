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
    var ownTextureId = 0; private set
    private var texSize = 2048

    data class PendingAtlas(val bitmap: Bitmap, val uvMap: HashMap<String, ThumbUV>)
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

    /**
     * Pack all covers into one sprite sheet on the background thread.
     * The resulting bitmap is queued for a single GPU upload on the GL thread.
     */
    fun pack(entries: List<Pair<String, Bitmap>>, forLayer: Int) {
        if (entries.isEmpty()) return

        val thumbW = entries.first().second.width
        val thumbH = entries.first().second.height
        val count = entries.size

        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val rows = (count + cols - 1) / cols
        val atlasW = (cols * thumbW).coerceAtMost(texSize)
        val atlasH = (rows * thumbH).coerceAtMost(texSize)

        val atlas = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(atlas)
        val newMap = HashMap<String, ThumbUV>(count * 2)
        val layerSize = texSize.toFloat()

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

            newMap[key] = ThumbUV(
                x.toFloat() / layerSize,
                y.toFloat() / layerSize,
                (x + thumbW).toFloat() / layerSize,
                (y + thumbH).toFloat() / layerSize,
                srcAspect = bmp.width.toFloat() / bmp.height.toFloat(),
            )
            bmp.recycle()
        }

        pendingQueue.add(PendingAtlas(atlas, newMap))
    }

    /**
     * GL thread: upload the pre-built sprite sheet in one call.
     */
    fun processPending() {
        val pending = pendingQueue.poll() ?: return
        val w = pending.bitmap.width; val h = pending.bitmap.height

        if (ownTextureId != 0) {
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            pending.bitmap.copyPixelsToBuffer(buf); buf.position(0)
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ownTextureId)
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0,
                0, 0, w, h,
                android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, buf)
        } else {
            texArray?.uploadLayerNow(layerIndex, pending.bitmap)
        }

        pending.bitmap.recycle()
        uvMap = pending.uvMap
        ready = true
    }

    fun getUV(key: String): ThumbUV? = uvMap[key]
    fun isReady(): Boolean = ready

    fun invalidate() {
        ready = false
        while (true) { (pendingQueue.poll() ?: break).bitmap.recycle() }
    }

    fun clear() {
        uvMap = HashMap()
        ready = false
        invalidate()
        val ta = texArray
        if (ta != null) { layerIndex = ta.nextThumbLayer() }
    }
}
