package com.janusplus

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "JanusPlus"

class JanusPlusActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private lateinit var state: AppState
    private lateinit var input: InputHandler
    private var api: JanusApi? = null
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var rootLayout: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        state = AppState()
        input = InputHandler(state)
        input.density = resources.displayMetrics.density

        input.onItemSelected = { item ->
            state.openItem(item)
            loadDetail(item)
        }
        input.onBack = { finish() }

        initGL()
        // Auto-login: skip dialog entirely during development
        thread {
            val devApi = JanusApi("https://canneji.duckdns.org/janus")
            val result = devApi.login("bruno", "janus2026")
            if (result != null) {
                api = devApi
                val library = devApi.fetchLibrary()
                Log.i(TAG, "Library: ${library.size} items")
                runOnUiThread {
                    state.library = library
                    state.loading = false
                    loadCovers(library)
                }
            } else {
                Log.e(TAG, "Dev login failed")
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initGL() {
        val density = resources.displayMetrics.density
        renderer = GLRenderer(assets, state, density)

        rootLayout = FrameLayout(this)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        renderer.inputHandler = input
        renderer.onItemTapped = { item ->
            runOnUiThread {
                state.openItem(item)
                loadDetail(item)
            }
        }

        playerView = PlayerView(this).apply {
            visibility = View.GONE
            useController = true
        }

        rootLayout.addView(glView)
        rootLayout.addView(playerView)
        setContentView(rootLayout)

        // Poll for play requests from the GL thread
        val checkPlay = object : Runnable {
            override fun run() {
                if (state.screen == Screen.PLAYING && player == null && state.playingUrl != null) {
                    startPlayer(state.playingUrl!!)
                }
                glView.postDelayed(this, 100)
            }
        }
        glView.post(checkPlay)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startPlayer(url: String) {
        Log.i(TAG, "Playing: $url")
        val token = api?.token ?: return
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()

        playerView?.player = exo
        playerView?.visibility = View.VISIBLE
        glView.visibility = View.GONE
        player = exo
    }

    private fun stopPlayer() {
        player?.release()
        player = null
        playerView?.player = null
        playerView?.visibility = View.GONE
        glView.visibility = View.VISIBLE
        state.screen = state.returnScreen
        state.playingUrl = null
    }

    private fun tryAutoLogin(url: String, token: String) {
        thread {
            Log.i(TAG, "Auto-login attempt to $url")
            val testApi = JanusApi(url)
            testApi.token = token
            try {
                val library = testApi.fetchLibrary()
                Log.i(TAG, "Library fetched: ${library.size} items")
                if (library.isNotEmpty()) {
                    api = testApi
                    runOnUiThread {
                        state.library = library
                        state.loading = false
                        Log.i(TAG, "Series: ${state.seriesList.size}, Movies: ${state.movieList.size}")
                        loadCovers(library)
                    }
                } else {
                    Log.w(TAG, "Empty library, showing login")
                    runOnUiThread { showLoginDialog() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-login failed: ${e.message}")
                runOnUiThread { showLoginDialog() }
            }
        }
    }

    private fun showLoginDialog() {
        val prefs = getSharedPreferences("janusplus", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val serverInput = EditText(this).apply {
            hint = "Server URL"
            setText(prefs.getString("server_url", "https://canneji.duckdns.org/janus"))
        }
        val userInput = EditText(this).apply { hint = "Username" }
        val passInput = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(serverInput)
        layout.addView(userInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Janus+ Login")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Connect") { _, _ ->
                val url = serverInput.text.toString().trimEnd('/')
                val user = userInput.text.toString()
                val pass = passInput.text.toString()
                doLogin(url, user, pass)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun doLogin(url: String, username: String, password: String) {
        thread {
            val loginApi = JanusApi(url)
            val result = loginApi.login(username, password)
            if (result != null) {
                api = loginApi
                getSharedPreferences("janusplus", Context.MODE_PRIVATE).edit()
                    .putString("server_url", url)
                    .putString("token", result.token)
                    .apply()
                runOnUiThread {
                    if (!::glView.isInitialized) initGL()
                    loadLibrary()
                }
            } else {
                runOnUiThread { showLoginDialog() }
            }
        }
    }

    private fun loadLibrary() {
        state.loading = true
        thread {
            val library = api?.fetchLibrary() ?: emptyList()
            runOnUiThread {
                state.library = library
                state.loading = false
                loadCovers(library)
            }
        }
    }

    private fun loadCovers(items: List<JanusApi.LibraryItem>) {
        val currentApi = api ?: return
        Log.i(TAG, "Loading covers for ${items.size} items")
        val client = okhttp3.OkHttpClient()
        thread {
            val entries = mutableListOf<Pair<String, android.graphics.Bitmap>>()
            for (item in items) {
                try {
                    val request = okhttp3.Request.Builder().url(currentApi.coverUrl(item.id))
                        .header("Authorization", "Bearer ${currentApi.token}").build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) entries.add("cover_${item.id}" to bmp)
                        }
                    }
                    response.close()
                } catch (_: Exception) {}
            }
            if (entries.isNotEmpty()) {
                state.coverAtlas.pack(entries)
                Log.i(TAG, "Cover atlas packed: ${entries.size} covers")
            }
        }
    }

    private fun loadDetail(item: JanusApi.LibraryItem) {
        val currentApi = api ?: return
        Log.i(TAG, "Loading detail for ${item.id} (${item.type})")
        thread {
            val blob = currentApi.fetchHeroBlob(item.id)
            Log.i(TAG, "Hero blob: ${if (blob != null) "OK, seasons=${blob.seasons.size}" else "null"}")
            if (blob != null) {
                state.heroBlob = blob
                state.detailLoading = false

                // Upload banner image
                blob.bannerBytes?.let { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) renderer.textures.enqueue("banner_${item.id}", bmp)
                }
                blob.coverBytes?.let { bytes ->
                    if (!renderer.textures.has("cover_${item.id}")) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) renderer.textures.enqueue("cover_${item.id}", bmp)
                    }
                }
            }

            if (item.type.equals("TV_SERIES", ignoreCase = true) || item.type.equals("series", ignoreCase = true)) {
                val season = state.selectedSeason
                val cards = currentApi.fetchSeasonCards(item.id, season)
                Log.i(TAG, "Season cards: ${cards?.episodes?.size ?: "null"}")
                if (cards != null) state.seasonCards = cards

                val thumbs = currentApi.fetchThumbsBlob(item.id, season)
                Log.i(TAG, "Thumbs: ${thumbs.size}")
                val decoded = thumbs.mapNotNull { entry ->
                    val bmp = BitmapFactory.decodeByteArray(entry.data, 0, entry.data.size)
                    if (bmp != null) "thumb_${item.id}_${entry.episode}" to bmp else null
                }
                if (decoded.isNotEmpty()) {
                    renderer.thumbAtlas.pack(decoded)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (state.screen == Screen.PLAYING && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_DEL)) {
            stopPlayer()
            return true
        }
        if (input.handleKey(event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state.screen == Screen.PLAYING) return super.onTouchEvent(event)
        if (input.handleTouch(event)) return true
        return super.onTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::glView.isInitialized) glView.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
