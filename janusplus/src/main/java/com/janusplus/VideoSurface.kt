package com.janusplus

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class VideoSurface {
    var oesTextureId = 0; private set
    var rgbTextureId = 0; private set
    var surfaceTexture: SurfaceTexture? = null; private set
    var surface: Surface? = null; private set
    private val newFrame = AtomicBoolean(false)
    val transformMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    private var fboWidth = 0
    private var fboHeight = 0
    var frameReady = false; private set

    fun initGL() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture!!.setOnFrameAvailableListener { newFrame.set(true) }
        surface = Surface(surfaceTexture!!)
    }

    var fbo = 0; private set

    fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboWidth == w && fboHeight == h) return
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(rgbTextureId), 0)
        }
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        rgbTextureId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTextureId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rgbTextureId, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fboWidth = w; fboHeight = h
    }

    var lastUpdateMs = 0f; private set

    fun updateTexture(): Boolean {
        if (newFrame.getAndSet(false)) {
            val t0 = System.nanoTime()
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(transformMatrix)
            lastUpdateMs = (System.nanoTime() - t0) / 1_000_000f
            frameReady = true
            return true
        }
        return false
    }

    fun bindOes() {
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    }

    fun bindRgb() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTextureId)
    }

    fun release() {
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
        if (oesTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (rgbTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(rgbTextureId), 0)
            rgbTextureId = 0
        }
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
    }
}
