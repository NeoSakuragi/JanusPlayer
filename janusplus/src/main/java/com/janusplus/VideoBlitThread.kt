package com.janusplus

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the OES → FBO blit on a dedicated thread with a shared EGL context.
 *
 * Flow:
 * 1. Main thread calls videoSurface.updateTexImage() — latches new video frame (0.5ms)
 * 2. Main thread calls requestBlit() — signals this thread
 * 3. This thread: FBO bind → draw OES quad → glFinish → done
 * 4. Main thread samples the FBO texture on the NEXT frame (one frame latency, imperceptible)
 *
 * The main GL thread never switches shaders or binds FBOs for video.
 */
class VideoBlitThread(
    private val videoSurface: VideoSurface,
) {
    companion object {
        private const val TAG = "VideoBlit"
    }

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var screenWidth = 0
    @Volatile var screenHeight = 0
    @Volatile var lastBlitMs = 0f
    @Volatile var frameReady = false

    @Volatile private var alive = false
    private var thread: Thread? = null
    private val hasWork = AtomicBoolean(false)
    private val projMatrix = FloatArray(16)

    fun start(mainDisplay: EGLDisplay, mainConfig: EGLConfig, mainContext: EGLContext) {
        alive = true
        thread = Thread({
            val ctx = createSharedContext(mainDisplay, mainConfig, mainContext)
            if (ctx == null) { Log.e(TAG, "No shared context"); return@Thread }
            val pbuf = createPbuffer(mainDisplay, mainConfig)
            EGL14.eglMakeCurrent(mainDisplay, pbuf, pbuf, ctx)

            val shader = ShaderProgram()
            shader.compileExternal()
            val batch = QuadBatch(maxQuads = 4)
            batch.initGL()

            Log.d(TAG, "Started")

            while (alive) {
                synchronized(this) {
                    while (!hasWork.get() && alive) {
                        try { (this as Object).wait(100) } catch (_: InterruptedException) {}
                    }
                }
                if (!alive) break
                hasWork.set(false)

                val sw = screenWidth; val sh = screenHeight
                if (sw <= 0 || sh <= 0) continue

                val t0 = System.nanoTime()

                videoSurface.ensureFbo(sw, sh)
                android.opengl.Matrix.orthoM(projMatrix, 0, 0f, sw.toFloat(), sh.toFloat(), 0f, -1f, 1f)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, videoSurface.fbo)
                GLES30.glViewport(0, 0, sw, sh)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

                shader.useExternal()
                GLES30.glUniformMatrix4fv(shader.uProjExt, 1, false, projMatrix, 0)
                GLES30.glUniformMatrix4fv(shader.uTexMatExt, 1, false, videoSurface.transformMatrix, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glUniform1i(shader.uTexExt, 0)
                videoSurface.bindOes()

                val vw = if (videoWidth > 0) videoWidth.toFloat() else 4f
                val vh = if (videoHeight > 0) videoHeight.toFloat() else 3f
                val aspect = vw / vh
                val sAspect = sw.toFloat() / sh
                val qx: Float; val qy: Float; val qw: Float; val qh: Float
                if (sAspect > aspect) {
                    qh = sh.toFloat(); qw = qh * aspect; qx = (sw - qw) / 2f; qy = 0f
                } else {
                    qw = sw.toFloat(); qh = qw / aspect; qx = 0f; qy = (sh - qh) / 2f
                }

                batch.begin()
                batch.addQuad(qx, qy, qw, qh, 0f, 1f, 1f, 0f, layer = 0f)
                batch.flush()
                GLES30.glFinish()

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                frameReady = true
                lastBlitMs = (System.nanoTime() - t0) / 1_000_000f
            }

            EGL14.eglMakeCurrent(mainDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(mainDisplay, pbuf)
            EGL14.eglDestroyContext(mainDisplay, ctx)
            Log.d(TAG, "Stopped")
        }, "VideoBlit")
        thread!!.start()
    }

    fun requestBlit() {
        hasWork.set(true)
        synchronized(this) { (this as Object).notifyAll() }
    }

    fun stop() {
        alive = false
        synchronized(this) { (this as Object).notifyAll() }
        thread?.join(1000)
        thread = null
    }

    private fun createSharedContext(d: EGLDisplay, c: EGLConfig, share: EGLContext): EGLContext? {
        val a = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(d, c, share, a, 0)
        return if (ctx == EGL14.EGL_NO_CONTEXT) null else ctx
    }

    private fun createPbuffer(d: EGLDisplay, c: EGLConfig): EGLSurface {
        val a = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        return EGL14.eglCreatePbufferSurface(d, c, a, 0)
    }
}
