package com.janusplus

import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class TextureArray(val size: Int = 2048, val layerCount: Int = LAYER_THUMB_FIRST + LAYER_THUMB_COUNT) {

    companion object {
        const val LAYER_FONT = 0
        const val FONT_PAGE_COUNT = 11
        const val LAYER_COVERS = LAYER_FONT + FONT_PAGE_COUNT   // 4
        const val LAYER_BANNER = LAYER_COVERS + 1               // 5
        const val LAYER_THUMB_FIRST = LAYER_BANNER + 1           // 6
        const val LAYER_THUMB_COUNT = 3
    }

    private var nextThumbSlot = 0

    fun nextThumbLayer(): Int {
        val layer = LAYER_THUMB_FIRST + (nextThumbSlot % LAYER_THUMB_COUNT)
        nextThumbSlot++
        return layer
    }

    var textureId = 0; private set
    private val uploadQueue = ConcurrentLinkedQueue<Pair<Int, Bitmap>>()

    fun initGL() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_RGBA,
            size, size, layerCount, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun uploadLayer(layer: Int, bitmap: Bitmap) {
        uploadQueue.add(layer to bitmap)
    }

    // Immediate GL upload — must be called on GL thread
    fun uploadLayerNow(layer: Int, bitmap: Bitmap) {
        val src = if (bitmap.config != Bitmap.Config.ARGB_8888)
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() } else bitmap
        val w = src.width; val h = src.height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        src.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
            0, 0, layer, w, h, 1,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        src.recycle()
    }

    fun processUploads() {
        while (true) {
            val (layer, bmp) = uploadQueue.poll() ?: break
            val src = if (bmp.config != Bitmap.Config.ARGB_8888)
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() } else bmp

            // Upload only the actual bitmap size — no padding to 4096
            val w = src.width; val h = src.height
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            src.copyPixelsToBuffer(buf)
            buf.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer, w, h, 1,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            src.recycle()
        }
    }

    fun uploadCount(): Int = uploadQueue.size

    fun bind() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
    }
}
