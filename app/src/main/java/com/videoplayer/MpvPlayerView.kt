package com.videoplayer

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

class MpvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, MPVLib.EventObserver {

    companion object {
        private const val TAG = "MpvPlayer"
    }

    interface Listener {
        fun onSubtitleTextChanged(text: String)
        fun onPauseChanged(paused: Boolean)
        fun onPositionChanged(positionSec: Double, durationSec: Double)
        fun onFileLoaded()
        fun onFileEnded()
        fun onTracksChanged()
        fun onError(message: String)
    }

    private var listener: Listener? = null
    private var initialized = false
    private var surfaceReady = false
    private var pendingFile: String? = null
    private var fileLoaded = false

    var onTapInterceptor: (() -> Boolean)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (onTapInterceptor?.invoke() == true) return true
            togglePause()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap left half to seek back, right half to seek forward
            if (e.x < width / 2) seekRelative(-10) else seekRelative(10)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    fun setListener(l: Listener) { listener = l }

    private fun isTvDevice(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun initialize() {
        if (initialized) return
        holder.removeCallback(this)
        holder.addCallback(this)

        try {
            MPVLib.create(context.applicationContext)
            MPVLib.setOptionString("config", "no")
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("gpu-dumb-mode", "yes")
            MPVLib.setOptionString("ao", "audiotrack,opensles")
            val isTv = isTvDevice()
            val hwdec = if (isTv || AppSettings(context).hardwareDecoding) "mediacodec-copy,mediacodec,no" else "no"
            Log.d(TAG, "Device isTv=$isTv, hwdec=$hwdec")
            MPVLib.setOptionString("hwdec", hwdec)
            MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("sub-visibility", "no")
            MPVLib.setOptionString("keep-open", "yes")
            MPVLib.setOptionString("save-position-on-quit", "no")
            MPVLib.setOptionString("vd-lavc-dr", "no")
            // Fallbacks for older GPUs
            MPVLib.setOptionString("gpu-shader-cache-dir", context.cacheDir.absolutePath)
            MPVLib.setOptionString("gpu-sw", "yes") // allow software fallback
            // Log mpv messages for diagnostics
            MPVLib.setOptionString("msg-level", "all=v")
            MPVLib.init()
        } catch (e: Exception) {
            Log.e(TAG, "mpv init failed: ${e.message}", e)
            post { listener?.onError("Player init failed: ${e.message}") }
            return
        }

        MPVLib.addObserver(this)
        MPVLib.observeProperty("sub-text", MPVLib.MPV_FORMAT_STRING)
        MPVLib.observeProperty("sub-text-ass", MPVLib.MPV_FORMAT_STRING)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("track-list", MPVLib.MPV_FORMAT_NONE)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)

        initialized = true
        Log.d(TAG, "mpv initialized")
    }

    // ── Playback controls ────────────────────────────────────────────

    fun loadFile(path: String) {
        if (!initialized) {
            Log.w(TAG, "loadFile called but mpv not initialized")
            post { listener?.onError("Player not initialized") }
            return
        }
        if (!surfaceReady) {
            pendingFile = path
            return
        }
        fileLoaded = false
        try {
            MPVLib.setPropertyString("sid", "auto")
            MPVLib.setPropertyString("aid", "auto")
        } catch (_: Exception) {}
        Log.d(TAG, "loadFile: $path (surfaceReady=$surfaceReady)")
        try {
            MPVLib.command(arrayOf("loadfile", path))
        } catch (e: Exception) {
            Log.e(TAG, "loadFile failed: ${e.message}", e)
            post { listener?.onError("Failed to load: ${e.message}") }
        }
    }

    fun play() = MPVLib.setPropertyBoolean("pause", false)
    fun pause() = MPVLib.setPropertyBoolean("pause", true)
    fun togglePause() = MPVLib.command(arrayOf("cycle", "pause"))

    val isPaused: Boolean get() = try { MPVLib.getPropertyBoolean("pause") } catch (_: Exception) { true }
    val position: Double get() = try { MPVLib.getPropertyDouble("time-pos") } catch (_: Exception) { 0.0 }
    val duration: Double get() = try { MPVLib.getPropertyDouble("duration") } catch (_: Exception) { 0.0 }

    fun seekTo(seconds: Double) = MPVLib.setPropertyDouble("time-pos", seconds)
    fun seekRelative(seconds: Int) = MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))

    fun stop() {
        try { MPVLib.command(arrayOf("stop")) } catch (_: Exception) {}
    }

    fun reinitialize() {
        destroy()
        initialize()
    }

    /**
     * Capture the current frame via Android's PixelCopy API.
     * Must be called from the main thread.
     */
    fun captureFrame(callback: (Bitmap?) -> Unit) {
        if (!surfaceReady) { callback(null); return }
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(this, bitmap, { result ->
                callback(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "PixelCopy failed: ${e.message}")
            callback(null)
        }
    }

    // ── Track management ─────────────────────────────────────────────

    data class Track(val id: Int, val type: String, val lang: String?, val title: String?, val codec: String?)

    fun getTracks(): List<Track> {
        val tracks = mutableListOf<Track>()
        val count = try { MPVLib.getPropertyInt("track-list/count") } catch (_: Exception) { 0 }
        for (i in 0 until count) {
            tracks.add(Track(
                id = try { MPVLib.getPropertyInt("track-list/$i/id") } catch (_: Exception) { 0 },
                type = try { MPVLib.getPropertyString("track-list/$i/type") } catch (_: Exception) { "" },
                lang = try { MPVLib.getPropertyString("track-list/$i/lang") } catch (_: Exception) { null },
                title = try { MPVLib.getPropertyString("track-list/$i/title") } catch (_: Exception) { null },
                codec = try { MPVLib.getPropertyString("track-list/$i/codec") } catch (_: Exception) { null }
            ))
        }
        return tracks
    }

    fun setAudioTrack(id: Int) = MPVLib.setPropertyInt("aid", id)
    fun setSubtitleTrack(id: Int) = MPVLib.setPropertyInt("sid", id)
    fun disableSubtitles() = MPVLib.setPropertyString("sid", "no")

    // ── Surface callbacks ────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        if (!initialized) return
        try {
            MPVLib.attachSurface(holder.surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
        } catch (e: Exception) {
            Log.e(TAG, "surfaceCreated failed: ${e.message}", e)
            post { listener?.onError("Video output failed: ${e.message}") }
            return
        }
        surfaceReady = true
        pendingFile?.let { loadFile(it); pendingFile = null }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        surfaceReady = false
        if (!initialized) return
        try {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        } catch (_: Exception) {}
    }

    // ── MPVLib event callbacks (called on mpv thread) ────────────────

    override fun eventProperty(property: String) {
        if (property == "track-list") {
            post { listener?.onTracksChanged() }
        }
    }

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            post { listener?.onPauseChanged(value) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos") {
            val dur = duration
            post { listener?.onPositionChanged(value, dur) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        Log.d(TAG, "string property: $property = ${value.take(50)}")
        if (property == "sub-text" || property == "sub-text-ass") {
            val cleanText = if (property == "sub-text-ass") {
                value.replace(Regex("\\{[^}]*\\}"), "").trim()
            } else value
            post { listener?.onSubtitleTextChanged(cleanText) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                Log.d(TAG, "MPV_EVENT_FILE_LOADED")
                post { listener?.onFileLoaded() }
            }
            MPVLib.MPV_EVENT_END_FILE -> {
                if (fileLoaded) {
                    fileLoaded = false
                    Log.d(TAG, "MPV_EVENT_END_FILE (normal)")
                    post { listener?.onFileEnded() }
                } else {
                    Log.e(TAG, "MPV_EVENT_END_FILE without FILE_LOADED — playback failed")
                    post { listener?.onError("Playback failed — file could not be played") }
                }
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    fun destroy() {
        if (!initialized) return
        try { MPVLib.removeObserver(this) } catch (_: Exception) {}
        try { MPVLib.destroy() } catch (_: Exception) {}
        initialized = false
        surfaceReady = false
        fileLoaded = false
    }
}
