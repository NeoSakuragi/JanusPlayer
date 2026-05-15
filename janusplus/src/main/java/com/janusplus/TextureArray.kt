package com.janusplus

import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

class TextureArray(val size: Int = 2048, val layerCount: Int = 4) {

    companion object {
        const val LAYER_FONT = 0
        const val LAYER_COVERS = 1
        const val LAYER_THUMBS = 2
        const val LAYER_BANNER = 3
    }

    var textureId = 0; private set
    private val uploadQueue = ConcurrentLinkedQueue<Pair<Int, Bitmap>>()
    private var reusableBuf: ByteBuffer? = null
    private var reusablePadBmp: Bitmap? = null

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
        Log.i("TexArray", "Created ${size}x${size}x$layerCount array")
    }

    fun uploadLayer(layer: Int, bitmap: Bitmap) {
        uploadQueue.add(layer to bitmap)
    }

    fun processUploads() {
        while (true) {
            val (layer, bmp) = uploadQueue.poll() ?: break
            val src = if (bmp.config != Bitmap.Config.ARGB_8888)
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() } else bmp

            val padded = if (src.width == size && src.height == size) src
            else {
                var p = reusablePadBmp
                if (p == null || p.isRecycled) {
                    p = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    reusablePadBmp = p
                }
                p.eraseColor(0)
                android.graphics.Canvas(p).drawBitmap(src, 0f, 0f, null)
                src.recycle()
                p
            }

            val bufSize = size * size * 4
            val buf = reusableBuf ?: ByteBuffer.allocateDirect(bufSize).order(ByteOrder.nativeOrder())
            reusableBuf = buf
            buf.clear()
            padded.copyPixelsToBuffer(buf)
            buf.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer, size, size, 1,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            if (padded !== reusablePadBmp) padded.recycle()
            Log.i("TexArray", "Uploaded layer $layer")
        }
    }

    fun bind() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
    }
}
