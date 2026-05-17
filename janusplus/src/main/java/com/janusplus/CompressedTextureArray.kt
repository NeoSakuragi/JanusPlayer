package com.janusplus

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class CompressedTextureArray(val size: Int = 4096, val layerCount: Int = 4) {

    companion object {
        const val GL_COMPRESSED_RGBA8_ETC2_EAC = 0x9278
        const val LAYER_BANNER = 0
        const val LAYER_THUMB_FIRST = 1
        const val LAYER_THUMB_COUNT = 3
    }

    var textureId = 0; private set
    private val uploadQueue = ConcurrentLinkedQueue<CompressedUpload>()
    private var nextThumbSlot = 0

    data class CompressedUpload(val layer: Int, val width: Int, val height: Int, val data: ByteBuffer)

    fun initGL() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
        GLES30.glTexStorage3D(
            GLES30.GL_TEXTURE_2D_ARRAY, 1,
            GL_COMPRESSED_RGBA8_ETC2_EAC,
            size, size, layerCount
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun nextThumbLayer(): Int {
        val layer = LAYER_THUMB_FIRST + (nextThumbSlot % LAYER_THUMB_COUNT)
        nextThumbSlot++
        return layer
    }

    fun uploadCompressedLayer(layer: Int, width: Int, height: Int, data: ByteBuffer) {
        uploadQueue.add(CompressedUpload(layer, width, height, data))
    }

    fun processUploads() {
        while (true) {
            val upload = uploadQueue.poll() ?: break
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
            GLES30.glCompressedTexSubImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, upload.layer,
                upload.width, upload.height, 1,
                GL_COMPRESSED_RGBA8_ETC2_EAC,
                upload.data.remaining(),
                upload.data
            )
        }
    }

    fun bind(textureUnit: Int = GLES30.GL_TEXTURE1) {
        GLES30.glActiveTexture(textureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textureId)
    }
}
