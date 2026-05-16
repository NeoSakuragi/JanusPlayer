package com.janusplus

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "JanusPlus"

class JanusPlusActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private lateinit var state: AppState
    private lateinit var input: InputHandler
    private var api: JanusApi? = null
    private var updater: AppUpdater? = null
    private var player: ExoPlayer? = null

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

        // Check saved credentials
        val prefs = getSharedPreferences("janusplus", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", null)
        val savedToken = prefs.getString("token", null)
        if (savedUrl != null && savedToken != null) {
            LoginScreen.serverUrl = savedUrl
            state.screen = Screen.HOME
            state.loading = true
            thread {
                val testApi = JanusApi(savedUrl)
                testApi.token = savedToken
                try {
                    val library = testApi.fetchLibrary()
                    if (library.isNotEmpty()) {
                        api = testApi
                        runOnUiThread {
                            state.library = library
                            state.loading = false
                            loadCovers(library)
                            checkForAppUpdate(savedUrl, savedToken)
                        }
                    } else {
                        runOnUiThread { state.screen = Screen.LOGIN }
                    }
                } catch (_: Exception) {
                    runOnUiThread { state.screen = Screen.LOGIN }
                }
            }
        } else {
            state.screen = Screen.LOGIN
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initGL() {
        val density = resources.displayMetrics.density
        renderer = GLRenderer(assets, state, density)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        renderer.inputHandler = input
        renderer.onItemTapped = { item ->
            runOnUiThread {
                renderer.thumbAtlas.clear()
                state.bannerReady = false
                // Clear thumb+banner layers to black so stale pixels don't flash
                val black = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                black.setPixel(0, 0, 0xFF000000.toInt())
                renderer.texArray.uploadLayer(TextureArray.LAYER_THUMBS, black.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                renderer.texArray.uploadLayer(TextureArray.LAYER_BANNER, black)
                state.openItem(item)
                loadDetail(item)
            }
        }
        renderer.onSeasonChanged = { season ->
            runOnUiThread {
                val item = state.selectedItem ?: return@runOnUiThread
                state.selectedSeason = season
                state.seasonCards = null
                state.episodeFocus = 0
                state.episodeProgress.clear()
                renderer.thumbAtlas.clear()
                loadSeasonData(item, season)
            }
        }
        renderer.onLogin = {
            runOnUiThread { doGpuLogin() }
        }
        renderer.onPlayerBack = { runOnUiThread { stopPlayer() } }
        renderer.onPlayerSeek = { ms -> player?.seekTo(ms) }
        renderer.onPlayerPause = { pause ->
            if (pause) player?.pause() else player?.play()
        }
        renderer.onSubChange = { idx ->
            val sub = PlayerScreen.subtitleTracks.getOrNull(idx)
            val itemId = state.selectedItem?.id
            if (sub != null && itemId != null) loadSubtitle(itemId, sub.srtFile)
        }
        renderer.onLogout = {
            runOnUiThread {
                getSharedPreferences("janusplus", Context.MODE_PRIVATE).edit().clear().apply()
                api = null
                state.screen = Screen.LOGIN
                state.library = emptyList()
                LoginScreen.username = ""
                LoginScreen.password = ""
                LoginScreen.errorMessage = ""
            }
        }

        setContentView(glView)

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

        @Suppress("DEPRECATION")
        val audioAttrs = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        PlayerScreen.reset()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttrs, false)
            .build()
        exo.setVideoSurface(renderer.videoSurface.surface)
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                PlayerScreen.videoWidth = size.width
                PlayerScreen.videoHeight = size.height
                Log.i(TAG, "Video size: ${size.width}x${size.height}")
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audioNames = mutableListOf<String>()
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val lang = format.language ?: "?"
                            val label = format.label ?: ""
                            audioNames.add(if (label.isNotEmpty()) "$lang · $label" else lang)
                        }
                    }
                }
                if (audioNames.isNotEmpty()) PlayerScreen.audioTrackNames = audioNames
                Log.i(TAG, "Audio tracks: $audioNames")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
        player = exo

        // Load subtitles
        val item = state.selectedItem
        val season = state.selectedSeason
        val heroBlob = state.heroBlob
        if (item != null) {
            val epNum = url.substringAfterLast("/").toIntOrNull() ?: 1
            thread {
                try {
                    // Use regular season endpoint which returns full episode data with subtitles
                    val seasonData = api?.fetchSeason(item.id, season)
                    val episode = seasonData?.episodes?.find { it.episode == epNum }
                    if (episode != null && episode.subtitles.isNotEmpty()) {
                        PlayerScreen.subtitleTracks = episode.subtitles
                        val jaIdx = episode.subtitles.indexOfFirst { it.language == "ja" }
                        val subIdx = if (jaIdx >= 0) jaIdx else 0
                        PlayerScreen.selectedSubIdx = subIdx
                        loadSubtitle(item.id, episode.subtitles[subIdx].srtFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load episode data: ${e.message}")
                }
            }
        }

        // Position update poller
        val updatePosition = object : Runnable {
            override fun run() {
                val p = player ?: return
                PlayerScreen.positionMs = p.currentPosition
                PlayerScreen.durationMs = p.duration.coerceAtLeast(0)
                PlayerScreen.isPaused = !p.isPlaying

                // Auto-hide controls after 4 seconds
                if (PlayerScreen.showControls && !PlayerScreen.isPaused &&
                    System.currentTimeMillis() - PlayerScreen.controlsTimer > 4000) {
                    PlayerScreen.showControls = false
                }

                glView.postDelayed(this, 200)
            }
        }
        glView.post(updatePosition)
    }

    private fun loadSubtitle(itemId: String, srtFile: String) {
        val currentApi = api ?: return
        thread {
            try {
                val url = "https://canneji.duckdns.org/janus/api/subs/$itemId/$srtFile"
                val request = okhttp3.Request.Builder().url(url)
                    .header("Authorization", "Bearer ${currentApi.token}").build()
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) {
                    val srt = response.body?.string() ?: ""
                    val cues = SrtParser.parse(srt)
                    PlayerScreen.subtitleCues = cues
                    Log.i(TAG, "Loaded ${cues.size} subtitle cues from $srtFile")
                }
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load subtitle: ${e.message}")
            }
        }
    }

    private fun stopPlayer() {
        player?.setVideoSurface(null)
        player?.release()
        player = null
        PlayerScreen.reset()
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
                val decoded = mutableListOf<Pair<String, android.graphics.Bitmap>>()
                // Thumbnails first to set the cell size
                for (entry in thumbs) {
                    val bmp = BitmapFactory.decodeByteArray(entry.data, 0, entry.data.size)
                    if (bmp != null) decoded.add("thumb_${item.id}_${entry.episode}" to bmp)
                }
                // Banner gets its own texture array layer at full resolution
                blob?.bannerBytes?.let { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        state.bannerW = bmp.width
                        state.bannerH = bmp.height
                        renderer.texArray.uploadLayer(3, bmp) // queued, processed on GL thread
                        state.bannerReady = true
                    }
                }
                if (decoded.isNotEmpty()) {
                    renderer.thumbAtlas.pack(decoded)
                }

                // Fetch watch progress from full season endpoint
                try {
                    val fullSeason = currentApi.fetchSeason(item.id, season)
                    if (fullSeason != null) {
                        val progress = HashMap<Int, Pair<Double, Boolean>>()
                        for (ep in fullSeason.episodes) {
                            if (ep.watchProgressSec > 0 || ep.completed) {
                                progress[ep.episode] = ep.watchProgressSec to ep.completed
                            }
                        }
                        state.episodeProgress = progress
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkForAppUpdate(serverUrl: String, token: String) {
        val u = AppUpdater(this, serverUrl)
        u.token = token
        updater = u
        u.checkForUpdate { info ->
            if (info != null) {
                Log.i(TAG, "Update available: v${info.versionName} (code ${info.versionCode})")
                u.downloadAndInstall(info) { progress ->
                    when (progress) {
                        101 -> Log.i(TAG, "Update downloaded, installing...")
                        -1 -> Log.e(TAG, "Update failed")
                        else -> if (progress % 25 == 0) Log.i(TAG, "Downloading update: $progress%")
                    }
                }
            } else {
                Log.i(TAG, "App is up to date")
            }
        }
    }

    private fun doGpuLogin() {
        val url = LoginScreen.serverUrl.trimEnd('/')
        val user = LoginScreen.username
        val pass = LoginScreen.password
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            LoginScreen.errorMessage = Lang.s("all_fields_required")
            return
        }
        LoginScreen.connecting = true
        LoginScreen.errorMessage = ""
        thread {
            try {
                val loginApi = JanusApi(url)
                val result = loginApi.login(user, pass)
                if (result != null) {
                    api = loginApi
                    getSharedPreferences("janusplus", Context.MODE_PRIVATE).edit()
                        .putString("server_url", url)
                        .putString("token", result.token)
                        .apply()
                    val library = loginApi.fetchLibrary()
                    runOnUiThread {
                        LoginScreen.connecting = false
                        state.library = library
                        state.loading = false
                        state.screen = Screen.HOME
                        loadCovers(library)
                    }
                } else {
                    runOnUiThread {
                        LoginScreen.connecting = false
                        LoginScreen.errorMessage = Lang.s("invalid_credentials")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    LoginScreen.connecting = false
                    LoginScreen.errorMessage = e.message ?: "Connection failed"
                }
            }
        }
    }

    private fun loadSeasonData(item: JanusApi.LibraryItem, season: Int) {
        val currentApi = api ?: return
        Log.i(TAG, "Loading season $season for ${item.id}")
        thread {
            val cards = currentApi.fetchSeasonCards(item.id, season)
            if (cards != null) state.seasonCards = cards

            val thumbs = currentApi.fetchThumbsBlob(item.id, season)
            val decoded = thumbs.mapNotNull { entry ->
                val bmp = BitmapFactory.decodeByteArray(entry.data, 0, entry.data.size)
                if (bmp != null) "thumb_${item.id}_${entry.episode}" to bmp else null
            }
            if (decoded.isNotEmpty()) renderer.thumbAtlas.pack(decoded)

            try {
                val fullSeason = currentApi.fetchSeason(item.id, season)
                if (fullSeason != null) {
                    val progress = HashMap<Int, Pair<Double, Boolean>>()
                    for (ep in fullSeason.episodes) {
                        if (ep.watchProgressSec > 0 || ep.completed)
                            progress[ep.episode] = ep.watchProgressSec to ep.completed
                    }
                    state.episodeProgress = progress
                }
            } catch (_: Exception) {}
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Login screen keyboard handling
        if (state.screen == Screen.LOGIN && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> { LoginScreen.onBackspace(); return true }
                KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_DOWN -> { LoginScreen.onDown(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { LoginScreen.onUp(); return true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (LoginScreen.focusedField == 3) LoginScreen.pendingLogin = true
                    else showKeyboard()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        if (state.screen == Screen.SETTINGS && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            state.screen = Screen.HOME
            return true
        }
        if (state.screen == Screen.PLAYING && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_DEL)) {
            stopPlayer()
            return true
        }
        if (input.handleKey(event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(glView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state.screen == Screen.LOGIN && event.action == MotionEvent.ACTION_UP) {
            // Check hit rects for login field taps
            for (hr in input.hitRects) {
                if (event.x >= hr.x && event.x <= hr.x + hr.w &&
                    event.y >= hr.y && event.y <= hr.y + hr.h) {
                    hr.action()
                    showKeyboard()
                    return true
                }
            }
        }
        if (state.screen == Screen.SETTINGS && event.action == MotionEvent.ACTION_UP) {
            for (hr in input.hitRects) {
                if (event.x >= hr.x && event.x <= hr.x + hr.w &&
                    event.y >= hr.y && event.y <= hr.y + hr.h) {
                    hr.action()
                    return true
                }
            }
        }
        if (state.screen == Screen.PLAYING) {
            if (event.action == MotionEvent.ACTION_UP) {
                val tx = event.x
                val ty = event.y
                PlayerScreen.lastTapX = tx

                // Direct seekbar: check if tap Y is near the seekbar Y (±40px)
                if (PlayerScreen.showControls && PlayerScreen.seekBarW > 0) {
                    val sy = PlayerScreen.seekBarY
                    if (ty > sy - 60f && ty < sy + 60f) {
                        val frac = ((tx - PlayerScreen.seekBarX) / PlayerScreen.seekBarW).coerceIn(0f, 1f)
                        player?.seekTo((frac * PlayerScreen.durationMs).toLong())
                        return true
                    }
                }

                // Hit rects for buttons
                for (hr in input.hitRects) {
                    if (tx >= hr.x && tx <= hr.x + hr.w && ty >= hr.y && ty <= hr.y + hr.h) {
                        hr.action()
                        if (PlayerScreen.pendingBack) { PlayerScreen.pendingBack = false; stopPlayer() }
                        return true
                    }
                }
                if (PlayerScreen.showTrackList) {
                    PlayerScreen.showTrackList = false
                    return true
                }
                if (PlayerScreen.showControls) {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                } else {
                    PlayerScreen.toggleControls()
                }
            }
            return true
        }
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
