package com.janusplus

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoSurface {
    var textureId = 0; private set
    var surfaceTexture: SurfaceTexture? = null; private set
    var surface: Surface? = null; private set
    private val newFrame = AtomicBoolean(false)
    val transformMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun initGL() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture!!.setOnFrameAvailableListener { newFrame.set(true) }
        surface = Surface(surfaceTexture!!)
    }

    private var loggedMatrix = false

    fun updateTexture() {
        if (newFrame.getAndSet(false)) {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transformMatrix)
            if (!loggedMatrix) {
                android.util.Log.e("VideoSurface", "Transform: [${transformMatrix.take(4).map { "%.2f".format(it) }}] [${transformMatrix.drop(4).take(4).map { "%.2f".format(it) }}] [${transformMatrix.drop(8).take(4).map { "%.2f".format(it) }}] [${transformMatrix.drop(12).map { "%.2f".format(it) }}]")
                loggedMatrix = true
            }
        }
    }

    fun bind() {
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    }

    fun release() {
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
