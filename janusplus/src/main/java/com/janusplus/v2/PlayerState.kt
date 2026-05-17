package com.janusplus.v2

import android.opengl.GLES11Ext
import android.opengl.GLES30
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.SrtParser
import kotlin.concurrent.thread

/**
 * Video player GameState.
 * ExoPlayer renders into a SurfaceTexture → GL quad.
 * All UI (controls, subtitles, dictionary) drawn as GL quads on top.
 */
class PlayerState(
    private val item: JanusApi.LibraryItem,
    private val episode: JanusApi.Episode,
    private val baseUrl: String,
) : GameState {

    // ── Player sub-states ──
    enum class Mode { PLAYING, CONTROLS, WORD_NAV }

    @Volatile var mode = Mode.PLAYING
    @Volatile var positionMs = 0L
    @Volatile var durationMs = 0L
    @Volatile var isPlaying = true

    // Subtitles
    @Volatile var cues: List<SrtParser.Cue> = emptyList()
    private var currentCue: SrtParser.Cue? = null
    private var currentCueText = ""

    // Controls
    private var controlsTimer = 0f
    private val CONTROLS_TIMEOUT = 5f

    // Seek
    private var isSeeking = false
    private var seekTargetMs = 0L

    // Double-tap detection
    private var lastTapTime = 0L
    private var lastTapX = 0f

    private var startTime = System.nanoTime()

    // Layout
    private var pad = 0f
    private var barH = 0f
    private var barY = 0f
    private var layoutDone = false

    override fun init(app: App) {
        appRef = app
        mode = Mode.PLAYING
        controlsTimer = 0f

        val api = app.api ?: return
        val videoUrl = "$baseUrl/api/video/${item.id}/${episode.filename}"
        val token = api.token ?: ""

        // Start playback on main thread (ExoPlayer requires it)
        app.onMainThread?.invoke(Runnable {
            val player = app.exoPlayer ?: return@Runnable
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))
            player.setVideoSurface(app.videoSurface.surface)
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true

            // Position + video size polling
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val poller = object : Runnable {
                override fun run() {
                    if (!alive || app.exoPlayer == null) return
                    positionMs = player.currentPosition
                    durationMs = player.duration.coerceAtLeast(0)
                    isPlaying = player.isPlaying
                    isBuffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    val format = player.videoFormat
                    if (format != null && videoWidth == 0) {
                        videoWidth = format.width
                        videoHeight = format.height
                    }
                    handler.postDelayed(this, if (isBuffering) 50 else 200)
                }
            }
            handler.postDelayed(poller, 200)
        })

        // Load subtitles
        if (episode.subtitles.isNotEmpty()) {
            val jaTrack = episode.subtitles.firstOrNull { it.language == "ja" }
                ?: episode.subtitles.first()
            thread {
                try {
                    val srtUrl = "$baseUrl/api/subs/${item.id}/${jaTrack.srtFile}"
                    val request = okhttp3.Request.Builder().url(srtUrl)
                        .header("Authorization", "Bearer $token").build()
                    val client = okhttp3.OkHttpClient()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: ""
                        cues = SrtParser.parse(text)
                    }
                    response.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(24f)
        barH = rc.dp(48f)
        barY = rc.h - barH - rc.dp(24f)
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        // Update video texture
        if (app.videoSurface.updateTexture()) {
            firstFrameReceived = true
        }

        // Find current cue
        val pos = positionMs
        val cue = cues.firstOrNull { pos >= it.startMs && pos <= it.endMs }
        if (cue !== currentCue) {
            currentCue = cue
            currentCueText = cue?.text ?: ""
        }

        // Controls auto-hide
        if (mode == Mode.CONTROLS) {
            controlsTimer += 0.016f
            if (controlsTimer > CONTROLS_TIMEOUT && isPlaying) {
                mode = Mode.PLAYING
            }
        }

        // Touch handling
        for (t in touches) {
            if (t.action == 1) handleTap(app, t.x, t.y)
        }

        // D-pad handling
        for (key in keys) {
            handleKey(app, key)
        }
    }

    private fun handleKey(app: App, keyCode: Int) {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                when (mode) {
                    Mode.PLAYING -> { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
                    Mode.CONTROLS -> { mode = Mode.PLAYING; play() }
                    Mode.WORD_NAV -> { /* mine word */ }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.prevCueBefore(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.CONTROLS -> seekRelative(-10000)
                    Mode.WORD_NAV -> { /* prev word */ }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (mode) {
                    Mode.PLAYING -> { SrtParser.nextCueAfter(cues, positionMs)?.let { seekTo(it.startMs) } }
                    Mode.CONTROLS -> seekRelative(10000)
                    Mode.WORD_NAV -> { /* next word */ }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                when (mode) {
                    Mode.PLAYING -> { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
                    Mode.CONTROLS -> if (cues.isNotEmpty()) { mode = Mode.WORD_NAV }
                    Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f }
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (mode) {
                    Mode.PLAYING -> if (cues.isNotEmpty()) { mode = Mode.WORD_NAV; pause() } else { mode = Mode.CONTROLS; controlsTimer = 0f; pause() }
                    Mode.CONTROLS -> { mode = Mode.PLAYING; play() }
                    Mode.WORD_NAV -> { mode = Mode.CONTROLS; controlsTimer = 0f }
                }
            }
            // BACK handled by Activity.onBackPressed
        }
    }

    private fun handleTap(app: App, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val dt = now - lastTapTime

        // Double-tap detection
        if (dt < 300) {
            if (x < app.width / 2) {
                seekRelative(-10000)
            } else {
                seekRelative(10000)
            }
            lastTapTime = 0
            return
        }
        lastTapTime = now
        lastTapX = x

        // Single tap
        when (mode) {
            Mode.PLAYING -> {
                mode = Mode.CONTROLS
                controlsTimer = 0f
                pause()
            }
            Mode.CONTROLS -> {
                mode = Mode.PLAYING
                play()
            }
            Mode.WORD_NAV -> {
                mode = Mode.CONTROLS
                controlsTimer = 0f
            }
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        // ── Video quad (full screen) — black until first frame
        if (firstFrameReceived) {
            drawVideoQuad(app, rc)
        } else {
            rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        }

        // Buffering indicator
        if (isBuffering || !firstFrameReceived) {
            val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
            val pulse = 0.5f + 0.3f * kotlin.math.sin(elapsed * 4f).toFloat()
            val loadText = Lang.s("loading")
            val tw = rc.font.measureText(loadText, rc.sp(16))
            rc.text(loadText, (rc.w - tw) / 2f, rc.h / 2f, rc.sp(16), pulse, pulse, pulse)
        }

        // ── Subtitle (cue layer) ──
        if (currentCueText.isNotEmpty()) {
            drawSubtitle(rc, currentCueText)
        }

        // ── Controls overlay ──
        if (mode != Mode.PLAYING) {
            drawControls(app, rc)
        }

        // ── FPS ──
        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var firstFrameReceived = false
    @Volatile var isBuffering = true

    private fun drawVideoQuad(app: App, rc: RC) {
        // Flush any pending UI quads
        rc.batch.flush()

        // Black background behind video
        // (drawn with main shader, already active)
        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        rc.batch.flush()

        // Switch to external OES shader
        app.shader.useExternal()
        GLES30.glUniformMatrix4fv(app.shader.uProjExt, 1, false, app.projMatrix, 0)
        GLES30.glUniformMatrix4fv(app.shader.uTexMatExt, 1, false, app.videoSurface.transformMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(app.shader.uTexExt, 0)
        app.videoSurface.bind()

        // Letterbox to correct aspect ratio
        val vw = if (videoWidth > 0) videoWidth.toFloat() else 4f
        val vh = if (videoHeight > 0) videoHeight.toFloat() else 3f
        val videoAspect = vw / vh
        val screenAspect = rc.w / rc.h
        val qx: Float; val qy: Float; val qw: Float; val qh: Float
        if (screenAspect > videoAspect) {
            qh = rc.h; qw = qh * videoAspect
            qx = (rc.w - qw) / 2f; qy = 0f
        } else {
            qw = rc.w; qh = qw / videoAspect
            qx = 0f; qy = (rc.h - qh) / 2f
        }

        // Video quad with OES UVs (transform matrix handles orientation)
        rc.batch.begin()
        rc.batch.addQuad(qx, qy, qw, qh, 0f, 1f, 1f, 0f, layer = 0f)
        rc.batch.flush()

        // Switch back to main shader for UI overlay
        app.shader.use()
        GLES30.glUniformMatrix4fv(app.shader.uProj, 1, false, app.projMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(app.shader.uTex, 0)
        app.texArray.bind()
        app.etc2Array.bind(GLES30.GL_TEXTURE1)
        GLES30.glUniform1i(app.shader.uTexEtc2, 1)
        rc.batch.begin()
    }

    private fun drawSubtitle(rc: RC, text: String) {
        val fontSize = rc.sp(22)
        val textW = rc.font.measureText(text, fontSize)
        val textH = rc.font.textHeight(fontSize)
        val x = (rc.w - textW) / 2f
        val y = rc.h - rc.dp(80f)

        // Shadow background
        val px = rc.dp(12f); val py = rc.dp(6f)
        rc.solid(x - px, y - textH - py, textW + px * 2, textH + py * 2,
            0f, 0f, 0f, 0.7f)

        // Text
        rc.text(text, x, y, fontSize, 1f, 1f, 1f)
    }

    private fun drawControls(app: App, rc: RC) {
        // Dim overlay
        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f, 0.4f)

        // Play/pause icon
        val centerX = rc.w / 2f
        val centerY = rc.h / 2f
        val iconSize = rc.dp(48f)
        if (!isPlaying) {
            // Play triangle
            rc.solid(centerX - iconSize / 3, centerY - iconSize / 2, iconSize, iconSize,
                1f, 1f, 1f, 0.8f)
        }

        // Seekbar background
        rc.solid(pad, barY, rc.w - pad * 2, rc.dp(4f), 0.3f, 0.3f, 0.4f)

        // Seekbar progress
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        val barW = rc.w - pad * 2
        rc.solid(pad, barY, barW * progress, rc.dp(4f), 0.733f, 0.525f, 0.988f)

        // Seekbar handle
        val handleX = pad + barW * progress
        rc.solid(handleX - rc.dp(6f), barY - rc.dp(6f), rc.dp(12f), rc.dp(16f),
            1f, 1f, 1f)

        // Time
        val posStr = formatTime(positionMs)
        val durStr = formatTime(durationMs)
        rc.text(posStr, pad, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)
        val durW = rc.font.measureText(durStr, rc.sp(12))
        rc.text(durStr, rc.w - pad - durW, barY + rc.dp(20f), rc.sp(12), 0.8f, 0.8f, 0.8f)

        // Episode title
        val title = "${episode.episode}. ${episode.title()}"
        rc.text(title, pad, rc.dp(32f), rc.sp(16), 1f, 1f, 1f)

        // Back button
        rc.text("←", pad, rc.dp(60f), rc.sp(22), 0.8f, 0.8f, 0.8f)
        rc.tappable(0f, 0f, rc.dp(80f), rc.dp(80f)) {
            cleanup(app)
            app.goBack()
        }
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }

    // ── Player controls ──

    private var appRef: App? = null

    fun play() {
        isPlaying = true
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.play() })
    }

    private fun pause() {
        isPlaying = false
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.pause() })
    }

    private fun seekRelative(deltaMs: Long) {
        val target = (positionMs + deltaMs).coerceIn(0, durationMs)
        positionMs = target
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(target) })
    }

    private fun seekTo(ms: Long) {
        positionMs = ms
        appRef?.onMainThread?.invoke(Runnable { appRef?.exoPlayer?.seekTo(ms) })
    }

    @Volatile private var alive = true

    override fun cleanup(app: App) {
        alive = false
        app.onMainThread?.invoke(Runnable {
            app.exoPlayer?.stop()
            app.exoPlayer?.clearMediaItems()
            app.exoPlayer?.setVideoSurface(null)
        })
        appRef = null
    }
}
