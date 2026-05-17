package com.janusplus.v2

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.janusplus.JanusApi
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        app = App(this, assets, resources.displayMetrics.density)

        // ExoPlayer with aggressive buffering — start playback ASAP
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // minBufferMs (default 50s)
                30_000,   // maxBufferMs (default 50s)
                500,      // bufferForPlaybackMs (default 2500ms) — start after 0.5s
                1_000     // bufferForPlaybackAfterRebufferMs (default 5000ms)
            )
            .build()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        app.exoPlayer = player
        app.onMainThread = { runnable -> runOnUiThread(runnable) }

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(app)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setContentView(glView)

        // Load library on background thread with retry
        thread {
            val api = JanusApi("https://canneji.duckdns.org/janus")
            api.cacheDir = cacheDir
            for (attempt in 1..10) {
                val result = api.login("bruno", "janus2026")
                if (result != null) {
                    app.api = api
                    val library = api.fetchLibrary()
                    app.library = library
                    loadCovers(api, library)
                    break
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun loadCovers(api: JanusApi, items: List<JanusApi.LibraryItem>) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        thread {
            for (attempt in 1..5) {
                val entries = mutableListOf<Pair<String, android.graphics.Bitmap>>()
                for (item in items) {
                    try {
                        val request = okhttp3.Request.Builder().url(api.coverUrl(item.id))
                            .header("Authorization", "Bearer ${api.token}").build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) entries.add("cover_${item.id}" to bmp)
                            }
                        }
                        response.close()
                    } catch (_: Exception) {}
                }
                if (entries.isNotEmpty()) {
                    app.coverAtlas.pack(entries, app.coverAtlas.layerIndex)
                    break
                }
                Thread.sleep(3000)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.onTouchEvent(event)
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        // Let system handle volume and back
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE ||
            code == android.view.KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            app.keyQueue.add(code)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Player handles back specially (resume before exit)
        val state = app.currentState
        if (state is PlayerState) {
            if (state.mode != PlayerState.Mode.PLAYING) {
                state.mode = PlayerState.Mode.PLAYING
                state.play()
                return
            }
            state.cleanup(app)
        }
        if (!app.goBack()) super.onBackPressed()
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { super.onPause(); if (::glView.isInitialized) glView.onPause() }
    override fun onDestroy() {
        app.exoPlayer?.release()
        app.exoPlayer = null
        super.onDestroy()
    }
}
