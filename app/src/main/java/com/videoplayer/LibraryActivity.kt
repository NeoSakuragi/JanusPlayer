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
        // TODO: make this configurable
        private const val SERVER_URL = "http://192.168.1.29:8900"
    }

    enum class Screen { SERIES_LIST, EPISODE_LIST }

    private val screen = mutableStateOf(Screen.SERIES_LIST)
    private val seriesFocus = mutableIntStateOf(0)
    private val episodeFocus = mutableIntStateOf(0)
    private val library = mutableStateListOf<JanusApi.Series>()
    private val loading = mutableStateOf(true)

    private lateinit var api: JanusApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        api = JanusApi(SERVER_URL)

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
                    // Header
                    androidx.compose.material3.Text(
                        "Janus",
                        color = Color(0xFFBB86FC),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                    )

                    when (currentScreen) {
                        Screen.SERIES_LIST -> SeriesRow(sFocus)
                        Screen.EPISODE_LIST -> {
                            val series = library.getOrNull(sFocus)
                            if (series != null) EpisodeList(series, eFocus)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SeriesRow(focusIdx: Int) {
        Column {
            androidx.compose.material3.Text(
                "Continue Watching",
                color = Color(0xFFCCCCCC),
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
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
            // Cover placeholder
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(200.dp)
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF2A1A3A), Color(0xFF1A1A2E))
                        )
                    )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Text(
                        series.titleJa,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.Text(
                        series.titleEn,
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Episode count + progress
            androidx.compose.material3.Text(
                "${series.episodeCount} episodes",
                color = if (focused) Color.White else Color(0xFF888888),
                fontSize = 12.sp
            )
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

            // Watch progress
            if (ep.watchProgressSec > 0 && !ep.completed) {
                Spacer(Modifier.height(6.dp))
                val progress = (ep.watchProgressSec / ep.durationSec).toFloat().coerceIn(0f, 1f)
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
                            .fillMaxWidth(progress)
                            .background(Color(0xFFBB86FC), RoundedCornerShape(2.dp))
                    )
                }
            }
            if (ep.completed) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text("✓ Watched", color = Color(0xFF81C784), fontSize = 11.sp)
            }
        }
    }

    // D-pad navigation
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (screen.value) {
            Screen.SERIES_LIST -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (seriesFocus.intValue > 0) seriesFocus.intValue--
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (seriesFocus.intValue < library.size - 1) seriesFocus.intValue++
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
        }
        return true
    }

    private fun playEpisode(series: JanusApi.Series, episode: JanusApi.Episode) {
        val videoUrl = api.videoUrl(series.id, episode.filename)
        val subsUrl = if (episode.hasJaSubs && episode.jaSrtFile != null)
            api.subsUrl(series.id, episode.jaSrtFile) else null

        startActivity(Intent(this, ExoPlayerActivity::class.java).apply {
            putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            putExtra(ExoPlayerActivity.EXTRA_TITLE, "${series.titleEn} - Episode ${episode.episode}")
            putExtra(ExoPlayerActivity.EXTRA_START_POSITION, (episode.watchProgressSec * 1000).toLong())
        })
    }
}
