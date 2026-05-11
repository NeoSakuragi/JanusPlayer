package com.videoplayer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Netflix-style library browser.
 * Shows series covers in a horizontal carousel, then episode list.
 */
class LibraryActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Library"
        private const val DEFAULT_SERVER_URL = "http://10.0.2.2:8900"
        private const val PREFS_NAME = "janus_settings"
    }

    enum class Screen { SERIES_LIST, EPISODE_LIST, SETTINGS }

    private val screen = mutableStateOf(Screen.SERIES_LIST)
    private val seriesFocus = mutableIntStateOf(0)
    private val episodeFocus = mutableIntStateOf(0)
    private val library = mutableStateListOf<JanusApi.Series>()
    private val loading = mutableStateOf(true)
    private val serverUrl = mutableStateOf(DEFAULT_SERVER_URL)
    private val settingsEditUrl = mutableStateOf("")
    private val settingsCursorPos = mutableIntStateOf(0)

    private lateinit var api: JanusApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverUrl.value = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        api = JanusApi(serverUrl.value)

        // Load library
        Thread {
            try {
                val items = api.fetchLibrary()
                runOnUiThread {
                    library.addAll(items)
                    loading.value = false
                    Log.d(TAG, "Library loaded: ${items.size} series")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load library: ${e.message}")
                runOnUiThread { loading.value = false }
            }
        }.start()

        setContent {
            LibraryScreen()
        }
    }

    @Composable
    private fun LibraryScreen() {
        val currentScreen by screen
        val isLoading by loading
        val sFocus by seriesFocus
        val eFocus by episodeFocus

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A1A))
        ) {
            if (isLoading) {
                androidx.compose.material3.Text(
                    "Loading library...",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (library.isEmpty()) {
                androidx.compose.material3.Text(
                    "No series found",
                    color = Color(0xFF888888),
                    fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 32.dp)
                ) {
                    // Header with settings gear
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            "Janus",
                            color = Color(0xFFBB86FC),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Text(
                            "⚙",
                            color = if (currentScreen == Screen.SETTINGS) Color(0xFFBB86FC) else Color(0xFF666666),
                            fontSize = 22.sp
                        )
                    }

                    when (currentScreen) {
                        Screen.SERIES_LIST -> SeriesRow(sFocus)
                        Screen.EPISODE_LIST -> {
                            val series = library.getOrNull(sFocus)
                            if (series != null) EpisodeList(series, eFocus)
                        }
                        Screen.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }

    data class ContinueItem(val series: JanusApi.Series, val episode: JanusApi.Episode, val positionMs: Long)

    private fun getContinueWatching(): List<ContinueItem> {
        val prefs = getSharedPreferences("watch_progress", MODE_PRIVATE)
        return library.mapNotNull { series ->
            val lastEpStr = prefs.getString("${series.id}_last_ep", null) ?: return@mapNotNull null
            val lastEp = lastEpStr.toIntOrNull() ?: return@mapNotNull null
            val pos = prefs.getLong("${series.id}_last_pos", 0L)
            if (pos < 5000) return@mapNotNull null // less than 5s, ignore
            val ep = series.episodes.firstOrNull { it.episode == lastEp } ?: return@mapNotNull null
            val dur = (ep.durationSec * 1000).toLong()
            if (dur > 0 && pos.toFloat() / dur > 0.95f) return@mapNotNull null // finished
            ContinueItem(series, ep, pos)
        }
    }

    @Composable
    private fun SeriesRow(focusIdx: Int) {
        val continueItems = remember { getContinueWatching() }

        Column {
            // Continue Watching row
            if (continueItems.isNotEmpty()) {
                androidx.compose.material3.Text(
                    "Continue Watching",
                    color = Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(continueItems) { _, item ->
                        val progress = (item.positionMs / 1000.0 / item.episode.durationSec).toFloat().coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier.width(220.dp)
                                .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            androidx.compose.material3.Text(
                                item.series.titleEn,
                                color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            androidx.compose.material3.Text(
                                "Episode ${item.episode.episode}",
                                color = Color.White, fontSize = 15.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444))
                            ) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color(0xFFBB86FC), RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Library row
            androidx.compose.material3.Text(
                "Library",
                color = Color(0xFFCCCCCC),
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(library) { idx, series ->
                    SeriesCard(series, focused = idx == focusIdx)
                }
            }
        }
    }

    @Composable
    private fun SeriesCard(series: JanusApi.Series, focused: Boolean) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(200.dp)
                .then(
                    if (focused) Modifier.border(3.dp, Color(0xFFBB86FC), RoundedCornerShape(12.dp))
                    else Modifier
                )
        ) {
            // Cover image
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(200.dp)
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A2E))
            ) {
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/covers/${series.id}.jpg",
                    contentDescription = series.titleEn,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Title overlay at bottom
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(8.dp)
                ) {
                    Column {
                        androidx.compose.material3.Text(
                            series.titleJa, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        androidx.compose.material3.Text(
                            series.titleEn, color = Color(0xFFCCCCCC), fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Episode count + continue watching
            val lastWatched = remember(series.id) { getLastWatched(series.id) }
            if (lastWatched != null) {
                androidx.compose.material3.Text(
                    "▶ Episode ${lastWatched.first}",
                    color = Color(0xFFBB86FC),
                    fontSize = 12.sp
                )
            } else {
                androidx.compose.material3.Text(
                    "${series.episodeCount} episodes",
                    color = if (focused) Color.White else Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    private fun EpisodeList(series: JanusApi.Series, focusIdx: Int) {
        Column(modifier = Modifier.padding(horizontal = 32.dp)) {
            // Series header
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    series.titleJa,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.Text(
                    series.titleEn,
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Episode rows
            LazyRow(
                contentPadding = PaddingValues(end = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(series.episodes) { idx, ep ->
                    EpisodeCard(series, ep, focused = idx == focusIdx)
                }
            }
        }
    }

    @Composable
    private fun EpisodeCard(series: JanusApi.Series, ep: JanusApi.Episode, focused: Boolean) {
        val savedPos = remember(series.id, ep.episode) { getWatchProgress(series.id, ep.episode) }
        val progressFraction = if (ep.durationSec > 0) (savedPos / 1000.0 / ep.durationSec).toFloat().coerceIn(0f, 1f) else 0f
        Column(
            modifier = Modifier
                .width(180.dp)
                .then(
                    if (focused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                    else Modifier
                )
                .background(
                    if (focused) Color(0xFF2A2A4A) else Color(0xFF1A1A2E),
                    RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            // Episode number
            androidx.compose.material3.Text(
                "Episode ${ep.episode}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            // Duration
            val mins = (ep.durationSec / 60).toInt()
            androidx.compose.material3.Text(
                "${mins} min",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )

            // Sub availability
            val subs = mutableListOf<String>()
            if (ep.hasJaSubs) subs.add("🇯🇵")
            if (ep.hasFrSubs) subs.add("🇫🇷")
            if (ep.hasEnSubs) subs.add("🇬🇧")
            if (subs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    subs.joinToString(" "),
                    fontSize = 14.sp
                )
            }

            // Watch progress from saved data
            if (progressFraction > 0.01f && progressFraction < 0.95f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF444444))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(Color(0xFFBB86FC), RoundedCornerShape(2.dp))
                    )
                }
            }
            if (progressFraction >= 0.95f) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text("✓ Watched", color = Color(0xFF81C784), fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val url by settingsEditUrl

        Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
            androidx.compose.material3.Text(
                "Settings",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))

            // Server URL
            androidx.compose.material3.Text("Server URL", color = Color(0xFFAAAAAA), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { settingsEditUrl.value = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFBB86FC),
                    unfocusedBorderColor = Color(0xFF444444),
                    cursorColor = Color(0xFFBB86FC),
                    focusedContainerColor = Color(0xFF1E1E2E),
                    unfocusedContainerColor = Color(0xFF1E1E2E),
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))

            // Anki section
            androidx.compose.material3.Text("Anki Connect", color = Color(0xFFBB86FC), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val ankiSettings = remember { AppSettings(this@LibraryActivity) }
            val ankiEnabled = remember { mutableStateOf(ankiSettings.ankiEnabled) }
            val ankiUrl = remember { mutableStateOf(ankiSettings.ankiConnectUrl) }
            val ankiDeck = remember { mutableStateOf(ankiSettings.ankiDeck) }
            val ankiNoteType = remember { mutableStateOf(ankiSettings.ankiNoteType) }

            // Enabled toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                androidx.compose.material3.Text("Enabled", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Text(
                    if (ankiEnabled.value) "ON" else "OFF",
                    color = if (ankiEnabled.value) Color(0xFF81C784) else Color(0xFF888888),
                    fontSize = 14.sp
                )
            }

            // URL
            androidx.compose.material3.Text("URL", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = ankiUrl.value,
                onValueChange = { ankiUrl.value = it; ankiSettings.ankiConnectUrl = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF444444), unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color(0xFFBB86FC),
                    focusedContainerColor = Color(0xFF1E1E2E), unfocusedContainerColor = Color(0xFF1E1E2E),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            // Deck
            androidx.compose.material3.Text("Deck", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = ankiDeck.value,
                onValueChange = { ankiDeck.value = it; ankiSettings.ankiDeck = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF444444), unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color(0xFFBB86FC),
                    focusedContainerColor = Color(0xFF1E1E2E), unfocusedContainerColor = Color(0xFF1E1E2E),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            // Note type
            androidx.compose.material3.Text("Note Type", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            androidx.compose.material3.OutlinedTextField(
                value = ankiNoteType.value,
                onValueChange = { ankiNoteType.value = it; ankiSettings.ankiNoteType = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF444444), unfocusedBorderColor = Color(0xFF333333),
                    cursorColor = Color(0xFFBB86FC),
                    focusedContainerColor = Color(0xFF1E1E2E), unfocusedContainerColor = Color(0xFF1E1E2E),
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Text(
                "Press OK to save · BACK to cancel",
                color = Color(0xFF666666),
                fontSize = 12.sp
            )
        }
    }

    private fun reloadLibrary() {
        loading.value = true
        library.clear()
        Thread {
            try {
                val items = api.fetchLibrary()
                runOnUiThread {
                    library.addAll(items)
                    loading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reload failed: ${e.message}")
                runOnUiThread { loading.value = false }
            }
        }.start()
    }

    // D-pad navigation
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (screen.value) {
            Screen.SERIES_LIST -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsEditUrl.value = serverUrl.value
                    screen.value = Screen.SETTINGS
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (seriesFocus.intValue > 0) seriesFocus.intValue--
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (seriesFocus.intValue < library.size - 1) seriesFocus.intValue++
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val series = library.getOrNull(seriesFocus.intValue)
                    if (series != null) {
                        val lastWatched = getLastWatched(series.id)
                        if (lastWatched != null) {
                            // Continue watching — play last episode directly
                            val ep = series.episodes.firstOrNull { it.episode == lastWatched.first }
                            if (ep != null) { playEpisode(series, ep); return true }
                        }
                    }
                    episodeFocus.intValue = 0
                    screen.value = Screen.EPISODE_LIST
                }
                else -> return false
            }
            Screen.EPISODE_LIST -> {
                val series = library.getOrNull(seriesFocus.intValue) ?: return false
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { screen.value = Screen.SERIES_LIST; return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (episodeFocus.intValue > 0) episodeFocus.intValue--
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (episodeFocus.intValue < series.episodes.size - 1) episodeFocus.intValue++
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        playEpisode(series, series.episodes[episodeFocus.intValue])
                    }
                    else -> return false
                }
            }
            Screen.SETTINGS -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    screen.value = Screen.SERIES_LIST
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Save and apply new URL
                    val newUrl = settingsEditUrl.value.trim()
                    if (newUrl.isNotEmpty()) {
                        serverUrl.value = newUrl
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString("server_url", newUrl).apply()
                        api = JanusApi(newUrl)
                        reloadLibrary()
                    }
                    screen.value = Screen.SERIES_LIST
                }
                else -> return super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun getWatchProgress(seriesId: String, epNum: Int): Long {
        val prefs = getSharedPreferences("watch_progress", MODE_PRIVATE)
        return prefs.getLong("${seriesId}_ep${epNum}_pos", 0L)
    }

    private fun getLastWatched(seriesId: String): Pair<Int, Long>? {
        val prefs = getSharedPreferences("watch_progress", MODE_PRIVATE)
        val epStr = prefs.getString("${seriesId}_last_ep", null) ?: return null
        val ep = epStr.toIntOrNull() ?: return null
        val pos = prefs.getLong("${seriesId}_last_pos", 0L)
        return ep to pos
    }

    private fun playEpisode(series: JanusApi.Series, episode: JanusApi.Episode) {
        val videoUrl = api.videoUrl(series.id, episode.filename)
        val subsUrl = if (episode.hasJaSubs && episode.jaSrtFile != null)
            api.subsUrl(series.id, episode.jaSrtFile) else null
        val savedPos = getWatchProgress(series.id, episode.episode)

        startActivity(Intent(this, ExoPlayerActivity::class.java).apply {
            putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            putExtra(ExoPlayerActivity.EXTRA_TITLE, "${series.titleEn} - Episode ${episode.episode}")
            putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, series.id)
            putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episode.episode)
        })
    }
}
