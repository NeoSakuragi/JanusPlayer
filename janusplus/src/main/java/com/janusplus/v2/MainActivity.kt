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

        val isTV = packageManager.hasSystemFeature("android.software.leanback")
        val density = if (isTV) 1.5f else resources.displayMetrics.density
        app = App(this, assets, density)

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
        glView.isFocusable = true
        glView.isFocusableInTouchMode = true
        setContentView(glView)
        glView.requestFocus()

        // Force 60Hz refresh on Huawei (drops to 30Hz during video decode)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = display?.supportedModes
                    ?.maxByOrNull { it.refreshRate }?.modeId ?: 0
            }
        } else {
            window.attributes = window.attributes.apply {
                @Suppress("DEPRECATION")
                preferredRefreshRate = 60f
            }
        }

        // Load library on background thread with retry
        val debugPlay = intent.getStringExtra("play") // e.g. "maison-ikkoku/1" or "dbz/200"
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
                    if (debugPlay != null) {
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

        // If requested ep differs from hero's default, fetch the right season
        val ep = if (episode.episode != epNum) {
            val seasonCards = api.fetchSeasonCards(item.id, episode.season)
            val targetCard = seasonCards?.episodes?.firstOrNull { it.episode == epNum }
            if (targetCard != null) {
                episode.copy(episode = epNum, filename = episode.filename.replace(
                    Regex("\\d+\\.mkv$"), "$epNum.mkv"))
            } else episode
        } else episode

        android.util.Log.d("DEBUG", "Direct play: ${item.titleEn} EP${ep.episode}")
        val baseUrl = "https://canneji.duckdns.org/janus"
        app.transition(Screen.PLAYER, PlayerState(item, ep, baseUrl))
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
                        .setPositiveButton("Install") { _, _ ->
                            updater.downloadAndInstall(info)
                        }
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
        android.util.Log.d("KEY", "dispatchKeyEvent: code=$code action=${event.action}")
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
