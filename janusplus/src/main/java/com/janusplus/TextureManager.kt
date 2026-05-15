package com.janusplus

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.util.concurrent.ConcurrentLinkedQueue

class TextureManager {

    private val textures = HashMap<String, Int>()
    private val uploadQueue = ConcurrentLinkedQueue<Pair<String, Bitmap>>()
    var whiteTexture = 0; private set

    fun initGL() {
        val whiteBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        whiteBmp.setPixel(0, 0, 0xFFFFFFFF.toInt())
        whiteTexture = uploadBitmap(whiteBmp)
        whiteBmp.recycle()
    }

    fun enqueue(key: String, bitmap: Bitmap) {
        uploadQueue.add(key to bitmap)
    }

    fun processUploads(maxPerFrame: Int = 4) {
        var count = 0
        while (count < maxPerFrame) {
            val (key, bmp) = uploadQueue.poll() ?: break
            textures[key] = uploadBitmap(bmp)
            bmp.recycle()
            count++
        }
    }

    fun get(key: String): Int = textures[key] ?: 0

    fun has(key: String): Boolean = textures.containsKey(key)

    fun bind(texId: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (texId != 0) texId else whiteTexture)
    }

    fun bindWhite() = bind(whiteTexture)

    private fun uploadBitmap(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        return ids[0]
    }

    fun delete(key: String) {
        val id = textures.remove(key) ?: return
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun clear() {
        val ids = textures.values.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
        textures.clear()
    }
}
