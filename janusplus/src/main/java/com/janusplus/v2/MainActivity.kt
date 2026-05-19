package com.janusplus.v2

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.janusplus.AppUpdater
import com.janusplus.JanusApi
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var app: App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        app = App(this, assets, resources.displayMetrics.density)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 500, 1_000)
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
        glView.isFocusable = true
        glView.isFocusableInTouchMode = true
        setContentView(glView)
        glView.requestFocus()

        // Claim media keys so Fire TV remote buttons reach our dispatchKeyEvent
        val mediaSession = android.media.session.MediaSession(this, "JanusPlus")
        mediaSession.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { app.keyQueue.add(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }
            override fun onPause() { app.keyQueue.add(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) }
            override fun onRewind() { app.keyQueue.add(android.view.KeyEvent.KEYCODE_MEDIA_REWIND) }
            override fun onFastForward() { app.keyQueue.add(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    app.keyQueue.add(event.keyCode)
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        mediaSession.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f)
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY or
                android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_REWIND or
                android.media.session.PlaybackState.ACTION_FAST_FORWARD
            ).build())
        mediaSession.isActive = true

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = display?.supportedModes
                    ?.filter { it.refreshRate >= 59f && it.physicalWidth <= 1280 }
                    ?.maxByOrNull { it.physicalWidth * it.physicalHeight }?.modeId
                    ?: display?.supportedModes?.filter { it.refreshRate >= 59f }
                        ?.maxByOrNull { it.physicalWidth * it.physicalHeight }?.modeId ?: 0
            }
        } else {
            window.attributes = window.attributes.apply {
                @Suppress("DEPRECATION")
                preferredRefreshRate = 60f
            }
        }

        val testMode = intent.getStringExtra("test")
        if (testMode == "glyphs") {
            app.transition(Screen.HOME, GlyphTestState())
            return
        }
        if (testMode == "dpad") {
            kotlin.concurrent.thread { DpadTestState.runFromThread(app) }
        }
        if (testMode == "player") {
            kotlin.concurrent.thread { PlayerLayerTest.runFromThread(app) }
        }
        if (testMode == "keys") {
            app.transition(Screen.HOME, KeyTestState())
            return
        }

        // --es series "choukai" → jump to series page
        val debugSeries = intent.getStringExtra("series")
        val debugPlay = intent.getStringExtra("play")
        thread {
            val api = JanusApi("https://canneji.duckdns.org/janus")
            api.cacheDir = cacheDir
            for (attempt in 1..10) {
                val result = api.login("bruno", "janus2026")
                if (result != null) {
                    app.api = api
                    checkForUpdate(api)
                    val library = api.fetchLibrary()
                    app.library = library
                    if (debugSeries != null) {
                        val item = library.firstOrNull { it.id == debugSeries || it.titleEn.lowercase().contains(debugSeries.lowercase()) }
                        if (item != null) app.navigate(App.Nav.Series(item))
                        else loadCovers(api, library)
                    } else if (debugPlay != null) {
                        launchDirectPlayer(api, library, debugPlay)
                    } else {
                        loadCovers(api, library)
                    }
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

    private fun launchDirectPlayer(api: JanusApi, library: List<JanusApi.LibraryItem>, spec: String) {
        val parts = spec.split("/")
        val query = parts[0].lowercase()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val item = library.firstOrNull { it.titleEn.lowercase().contains(query) || it.id.contains(query) }
        if (item == null) { android.util.Log.e("DEBUG", "No item matching '$query'"); return }

        val hero = api.fetchHeroBlob(item.id)
        val episode = hero?.episode
        if (episode == null) { android.util.Log.e("DEBUG", "No episode data for ${item.id}"); return }

        val ep = if (episode.episode != epNum) {
            val seasonCards = api.fetchSeasonCards(item.id, episode.season)
            val targetCard = seasonCards?.episodes?.firstOrNull { it.episode == epNum }
            if (targetCard != null) {
                episode.copy(episode = epNum, filename = episode.filename.replace(
                    Regex("\\d+\\.mkv$"), "$epNum.mkv"))
            } else episode
        } else episode

        app.navigate(App.Nav.Player(item, ep, "https://canneji.duckdns.org/janus"))
    }

    private fun checkForUpdate(api: JanusApi) {
        val updater = AppUpdater(this, "https://canneji.duckdns.org/janus")
        updater.token = api.token
        updater.checkForUpdate { info ->
            if (info != null) {
                runOnUiThread {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Update Available")
                        .setMessage("Janus+ v${info.versionName} is available. Install now?")
                        .setPositiveButton("Install") { _, _ -> updater.downloadAndInstall(info) }
                        .setNegativeButton("Later", null)
                        .show()
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        app.onTouchEvent(event)
        return true
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        // Let volume through to system
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            app.keyQueue.add(code)
            return true
        }
        return true
    }


    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        app.keyQueue.add(android.view.KeyEvent.KEYCODE_BACK)
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { super.onPause(); if (::glView.isInitialized) glView.onPause() }
    override fun onDestroy() {
        app.exoPlayer?.release(); app.exoPlayer = null
        super.onDestroy()
    }
}
