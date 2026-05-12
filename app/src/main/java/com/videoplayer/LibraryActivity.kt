package com.videoplayer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

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

    enum class Screen { MAIN, SETTINGS, ITEM_DETAIL }
    enum class LibraryRow { CONTINUE, SERIES, MOVIES }

    private val screen = mutableStateOf(Screen.MAIN)
    private val currentRow = mutableStateOf(LibraryRow.SERIES)
    private val continueFocus = mutableIntStateOf(0)
    private val seriesFocus = mutableIntStateOf(0)
    private val movieFocus = mutableIntStateOf(0)
    private val episodeFocus = mutableIntStateOf(0)
    private val library = mutableStateListOf<JanusApi.Series>()
    private val seriesList = mutableStateListOf<JanusApi.Series>()
    private val movieList = mutableStateListOf<JanusApi.Series>()
    private val loading = mutableStateOf(true)
    private val serverUrl = mutableStateOf(DEFAULT_SERVER_URL)
    private val settingsEditUrl = mutableStateOf("")
    private val settingsCursorPos = mutableIntStateOf(0)
    private val updateAvailable = mutableStateOf<AppUpdater.UpdateInfo?>(null)
    private val updateDownloading = mutableStateOf(false)
    private var appUpdater: AppUpdater? = null
    private val dpadDetected = mutableStateOf(false)
    private val selectedItem = mutableStateOf<JanusApi.Series?>(null)
    private val detailVisitCount = mutableIntStateOf(0)

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
                    seriesList.addAll(items.filter { it.type == "TV_SERIES" })
                    movieList.addAll(items.filter { it.type == "MOVIE" })
                    loading.value = false
                    Log.d(TAG, "Library loaded: ${seriesList.size} series, ${movieList.size} movies")
                    appUpdater = AppUpdater(this@LibraryActivity)
                    appUpdater?.checkForUpdate { info -> updateAvailable.value = info }
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
        val showCursor by dpadDetected

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
                    modifier = Modifier.fillMaxSize().then(
                        if (currentScreen != Screen.ITEM_DETAIL) Modifier.padding(top = 32.dp) else Modifier
                    )
                ) {
                    // Header with settings gear (not on item detail)
                    if (currentScreen != Screen.ITEM_DETAIL) Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            "Janus",
                            color = Color(0xFFBB86FC),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                if (currentScreen != Screen.MAIN) screen.value = Screen.MAIN
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Text(
                            "⚙",
                            color = if (currentScreen == Screen.SETTINGS) Color(0xFFBB86FC) else Color(0xFF666666),
                            fontSize = 22.sp,
                            modifier = Modifier.clickable { openSettings() }
                        )
                    }

                    // Update banner (not on item detail)
                    val update by updateAvailable
                    val downloading by updateDownloading
                    if (update != null && currentScreen != Screen.ITEM_DETAIL) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 4.dp)
                                .background(Color(0xFF2A2A4A), RoundedCornerShape(8.dp))
                                .clickable {
                                    if (!downloading) {
                                        updateDownloading.value = true
                                        appUpdater?.downloadAndInstall(update!!.apkName)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            androidx.compose.material3.Text(
                                if (downloading) "Downloading update..."
                                else "New version available (${update!!.versionName})",
                                color = Color.White, fontSize = 14.sp
                            )
                            Spacer(Modifier.weight(1f))
                            if (!downloading) {
                                androidx.compose.material3.Text(
                                    "Update",
                                    color = Color(0xFFBB86FC), fontSize = 14.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    when (currentScreen) {
                        Screen.MAIN -> LibraryRows(sFocus, showCursor)
                        Screen.SETTINGS -> SettingsScreen()
                        Screen.ITEM_DETAIL -> {
                            val item = selectedItem.value
                            if (item != null) ItemDetailPage(item, eFocus, showCursor)
                            else screen.value = Screen.MAIN
                        }
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
    private fun LibraryRows(focusIdx: Int, showCursor: Boolean) {
        val continueItems = remember { getContinueWatching() }
        val activeRow by currentRow
        val cFocus by continueFocus
        val mFocus by movieFocus
        val continueListState = rememberLazyListState()
        val seriesListState = rememberLazyListState()
        val moviesListState = rememberLazyListState()

        LaunchedEffect(cFocus, activeRow) {
            if (activeRow == LibraryRow.CONTINUE) continueListState.animateScrollToItem(cFocus.coerceAtLeast(0))
        }
        LaunchedEffect(focusIdx, activeRow) {
            if (activeRow == LibraryRow.SERIES) seriesListState.animateScrollToItem(focusIdx.coerceAtLeast(0))
        }
        LaunchedEffect(mFocus, activeRow) {
            if (activeRow == LibraryRow.MOVIES) moviesListState.animateScrollToItem(mFocus.coerceAtLeast(0))
        }

        Column {
            // Continue Watching row
            if (continueItems.isNotEmpty()) {
                androidx.compose.material3.Text(
                    "Continue Watching",
                    color = if (showCursor && activeRow == LibraryRow.CONTINUE) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                LazyRow(
                    state = continueListState,
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(continueItems) { idx, item ->
                        val focused = showCursor && activeRow == LibraryRow.CONTINUE && idx == cFocus
                        val progress = (item.positionMs / 1000.0 / item.episode.durationSec).toFloat().coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier.width(220.dp)
                                .then(if (focused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                                .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                                .clickable {
                                    currentRow.value = LibraryRow.CONTINUE
                                    continueFocus.intValue = idx
                                    playContinueItem(item)
                                }
                                .padding(12.dp)
                        ) {
                            androidx.compose.material3.Text(
                                item.series.titleEn,
                                color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            androidx.compose.material3.Text(
                                if (item.series.type == "MOVIE") item.series.titleEn
                                else "Episode ${item.episode.episode}",
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

            // Series row
            if (seriesList.isNotEmpty()) {
                androidx.compose.material3.Text(
                    "Series",
                    color = if (showCursor && activeRow == LibraryRow.SERIES) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                LazyRow(
                    state = seriesListState,
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(seriesList) { idx, series ->
                        SeriesCard(series, focused = showCursor && activeRow == LibraryRow.SERIES && idx == focusIdx) {
                            currentRow.value = LibraryRow.SERIES
                            seriesFocus.intValue = idx
                            openItemDetail(series)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Movies row
            if (movieList.isNotEmpty()) {
                androidx.compose.material3.Text(
                    "Movies",
                    color = if (showCursor && activeRow == LibraryRow.MOVIES) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                LazyRow(
                    state = moviesListState,
                    contentPadding = PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(movieList) { idx, movie ->
                        SeriesCard(movie, focused = showCursor && activeRow == LibraryRow.MOVIES && idx == mFocus) {
                            currentRow.value = LibraryRow.MOVIES
                            movieFocus.intValue = idx
                            openItemDetail(movie)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SeriesCard(series: JanusApi.Series, focused: Boolean, onTap: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(200.dp)
                .clickable { onTap() }
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
            if (lastWatched != null && series.type != "MOVIE") {
                androidx.compose.material3.Text(
                    "▶ Episode ${lastWatched.first}",
                    color = Color(0xFFBB86FC),
                    fontSize = 12.sp
                )
            } else if (series.type == "MOVIE") {
                val mins = series.episodes.firstOrNull()?.let { (it.durationSec / 60).toInt() } ?: 0
                androidx.compose.material3.Text(
                    "${mins} min",
                    color = if (focused) Color.White else Color(0xFF888888),
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

    private var previewPlayer: ExoPlayer? = null

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable
    private fun ItemDetailPage(item: JanusApi.Series, focusIdx: Int, showCursor: Boolean) {
        val scrollState = rememberScrollState()
        var showPreview by remember { mutableStateOf(false) }

        // Scroll to keep focused episode visible
        val heroHeight = 400
        val rowHeight = 200
        val cols = 4
        LaunchedEffect(focusIdx) {
            if (item.type != "MOVIE" && item.episodes.isNotEmpty()) {
                val row = focusIdx / cols
                val targetScroll = heroHeight + 40 + (row * rowHeight) - 100
                scrollState.animateScrollTo(targetScroll.coerceAtLeast(0))
            }
        }

        val visitKey by detailVisitCount
        DisposableEffect(visitKey) {
            onDispose { releasePreviewPlayer() }
        }
        LaunchedEffect(visitKey) {
            showPreview = false
            delay(3000)
            showPreview = true
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
        ) {
            // Hero area
            Box(
                modifier = Modifier.fillMaxWidth().height(400.dp)
            ) {
                // Backdrop: cover image (banner if available, else cover)
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/covers/${item.id}-banner.jpg",
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Video preview fades in after 3s
                androidx.compose.animation.AnimatedVisibility(
                    visible = showPreview,
                    enter = fadeIn(tween(1500)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val firstEp = item.episodes.firstOrNull()
                    if (firstEp != null) {
                        AndroidView(
                            factory = { ctx ->
                                releasePreviewPlayer()
                                val player = ExoPlayer.Builder(ctx).build().apply {
                                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                        .build()
                                    val url = api.videoUrl(item.id, firstEp.filename)
                                    setMediaItem(MediaItem.fromUri(url))
                                    prepare()
                                    seekTo(8 * 60 * 1000L)
                                    volume = 1f
                                    play()
                                }
                                previewPlayer = player
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Dark gradient on left for text readability
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Brush.horizontalGradient(
                            listOf(Color(0xDD0A0A1A), Color(0x880A0A1A), Color.Transparent),
                            startX = 0f, endX = 800f
                        ))
                )
                // Bottom fade
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0A0A1A))))
                )

                // Content overlaid on hero
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 32.dp, bottom = 16.dp, end = 32.dp)
                        .widthIn(max = 500.dp)
                ) {
                    // Back
                    androidx.compose.material3.Text(
                        "← Back", color = Color(0xFF888888), fontSize = 13.sp,
                        modifier = Modifier.clickable { releasePreviewPlayer(); screen.value = Screen.MAIN }
                            .padding(bottom = 12.dp)
                    )

                    // Title
                    androidx.compose.material3.Text(
                        item.titleJa, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold
                    )
                    androidx.compose.material3.Text(
                        item.titleEn, color = Color(0xFFCCCCCC), fontSize = 16.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Play button
                    val lastWatched = getLastWatched(item.id)
                    val resumeLabel = when {
                        lastWatched != null && item.type == "MOVIE" -> "▶  Resume"
                        lastWatched != null -> "▶  Resume Ep. ${lastWatched.first}"
                        item.type == "MOVIE" -> "▶  Play"
                        else -> "▶  Play Episode 1"
                    }
                    Box(
                        modifier = Modifier.width(240.dp)
                            .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                            .clickable { releasePreviewPlayer(); playItem(item) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            resumeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Metadata line
                    val metaParts = mutableListOf<String>()
                    if (item.type == "MOVIE") {
                        val mins = item.episodes.firstOrNull()?.let { (it.durationSec / 60).toInt() } ?: 0
                        metaParts.add("${mins} min")
                    } else {
                        metaParts.add("${item.episodeCount} episodes")
                    }
                    val jaCount = item.episodes.count { it.hasJaSubs }
                    if (jaCount > 0) metaParts.add("JP subs")
                    androidx.compose.material3.Text(
                        metaParts.joinToString("  ·  "),
                        color = Color(0xFF888888), fontSize = 13.sp
                    )
                }
            }

            // Episode grid (for series)
            if (item.type != "MOVIE" && item.episodes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    "${item.episodeCount} episodes",
                    color = Color(0xFFCCCCCC), fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                // Use a fixed-height grid since we're inside a scrollable Column
                val rows = (item.episodes.size + 3) / 4
                val gridHeight = (rows * 200).dp
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(gridHeight).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(item.episodes) { idx, ep ->
                        EpisodeGridCard(item, ep, focused = showCursor && idx == focusIdx) {
                            episodeFocus.intValue = idx
                            releasePreviewPlayer()
                            playEpisode(item, ep)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    @Composable
    private fun EpisodeGridCard(series: JanusApi.Series, ep: JanusApi.Episode, focused: Boolean, onTap: () -> Unit) {
        val savedPos = remember(series.id, ep.episode) { getWatchProgress(series.id, ep.episode) }
        val progressFraction = if (ep.durationSec > 0) (savedPos / 1000.0 / ep.durationSec).toFloat().coerceIn(0f, 1f) else 0f
        val mins = (ep.durationSec / 60).toInt()

        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (focused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                .background(if (focused) Color(0xFF2A2A4A) else Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                .clickable { onTap() }
                .padding(8.dp)
        ) {
            // Episode number + title
            androidx.compose.material3.Text(
                "${ep.episode}. Episode ${ep.episode}",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(4.dp))

            // Metadata row
            Row(verticalAlignment = Alignment.CenterVertically) {
                val subs = mutableListOf<String>()
                if (ep.hasJaSubs) subs.add("JP")
                if (ep.hasEnSubs) subs.add("EN")
                if (ep.hasFrSubs) subs.add("FR")
                androidx.compose.material3.Text(
                    "${mins} min" + if (subs.isNotEmpty()) "  ·  ${subs.joinToString(" ")}" else "",
                    color = Color(0xFF888888), fontSize = 11.sp
                )
            }

            // Watch progress
            if (progressFraction > 0.01f) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progressFraction).background(Color(0xFFBB86FC), RoundedCornerShape(2.dp)))
                }
                if (progressFraction >= 0.95f) {
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text("✓ Watched", color = Color(0xFF81C784), fontSize = 10.sp)
                }
            }
        }
    }

    @Composable
    private fun EpisodeCard(series: JanusApi.Series, ep: JanusApi.Episode, focused: Boolean, onTap: () -> Unit) {
        val savedPos = remember(series.id, ep.episode) { getWatchProgress(series.id, ep.episode) }
        val progressFraction = if (ep.durationSec > 0) (savedPos / 1000.0 / ep.durationSec).toFloat().coerceIn(0f, 1f) else 0f
        Column(
            modifier = Modifier
                .width(180.dp)
                .clickable { onTap() }
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
        seriesList.clear()
        movieList.clear()
        Thread {
            try {
                val items = api.fetchLibrary()
                runOnUiThread {
                    library.addAll(items)
                    seriesList.addAll(items.filter { it.type == "TV_SERIES" })
                    movieList.addAll(items.filter { it.type == "MOVIE" })
                    loading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reload failed: ${e.message}")
                runOnUiThread { loading.value = false }
            }
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val isDpad = event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
        if (isDpad) dpadDetected.value = true

        when (screen.value) {
            Screen.MAIN -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> rowMoveUp()
                KeyEvent.KEYCODE_DPAD_DOWN -> rowMoveDown()
                KeyEvent.KEYCODE_DPAD_LEFT -> rowMoveLeft()
                KeyEvent.KEYCODE_DPAD_RIGHT -> rowMoveRight()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> rowSelect()
                else -> return false
            }

            Screen.SETTINGS -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { screen.value = Screen.MAIN }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val continueItems = getContinueWatching()
                    currentRow.value = when {
                        continueItems.isNotEmpty() -> LibraryRow.CONTINUE
                        seriesList.isNotEmpty() -> LibraryRow.SERIES
                        movieList.isNotEmpty() -> LibraryRow.MOVIES
                        else -> LibraryRow.SERIES
                    }
                    screen.value = Screen.MAIN
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val newUrl = settingsEditUrl.value.trim()
                    if (newUrl.isNotEmpty()) {
                        serverUrl.value = newUrl
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString("server_url", newUrl).apply()
                        api = JanusApi(newUrl)
                        reloadLibrary()
                    }
                    screen.value = Screen.MAIN
                }
                else -> return super.dispatchKeyEvent(event)
            }

            Screen.ITEM_DETAIL -> {
                val item = selectedItem.value ?: return false
                val cols = 4
                val maxIdx = item.episodes.size - 1
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { releasePreviewPlayer(); screen.value = Screen.MAIN; return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (item.type != "MOVIE" && episodeFocus.intValue > 0) episodeFocus.intValue--
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (item.type != "MOVIE" && episodeFocus.intValue < maxIdx) episodeFocus.intValue++
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (item.type != "MOVIE") {
                            val next = episodeFocus.intValue + cols
                            if (next <= maxIdx) episodeFocus.intValue = next
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (item.type != "MOVIE") {
                            val prev = episodeFocus.intValue - cols
                            if (prev >= 0) episodeFocus.intValue = prev
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (item.type == "MOVIE") {
                            playItem(item)
                        } else {
                            val ep = item.episodes.getOrNull(episodeFocus.intValue)
                            if (ep != null) { releasePreviewPlayer(); playEpisode(item, ep) }
                        }
                    }
                    else -> return false
                }
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

    private fun rowFocus(): MutableIntState = when (currentRow.value) {
        LibraryRow.CONTINUE -> continueFocus
        LibraryRow.SERIES -> seriesFocus
        LibraryRow.MOVIES -> movieFocus
    }

    private fun rowSize(): Int = when (currentRow.value) {
        LibraryRow.CONTINUE -> getContinueWatching().size
        LibraryRow.SERIES -> seriesList.size
        LibraryRow.MOVIES -> movieList.size
    }

    private fun rowMoveLeft() {
        val focus = rowFocus()
        if (focus.intValue > 0) focus.intValue--
    }

    private fun rowMoveRight() {
        val focus = rowFocus()
        if (focus.intValue < rowSize() - 1) focus.intValue++
    }

    private fun rowSelect() {
        val idx = rowFocus().intValue
        when (currentRow.value) {
            LibraryRow.CONTINUE -> {
                val items = getContinueWatching()
                items.getOrNull(idx)?.let { playContinueItem(it) }
            }
            LibraryRow.SERIES -> seriesList.getOrNull(idx)?.let { openItemDetail(it) }
            LibraryRow.MOVIES -> movieList.getOrNull(idx)?.let { openItemDetail(it) }
        }
    }

    private fun availableRows(): List<LibraryRow> {
        val rows = mutableListOf<LibraryRow>()
        if (getContinueWatching().isNotEmpty()) rows.add(LibraryRow.CONTINUE)
        if (seriesList.isNotEmpty()) rows.add(LibraryRow.SERIES)
        if (movieList.isNotEmpty()) rows.add(LibraryRow.MOVIES)
        return rows
    }

    private fun rowMoveUp() {
        val rows = availableRows()
        val curIdx = rows.indexOf(currentRow.value)
        if (curIdx > 0) currentRow.value = rows[curIdx - 1]
        else openSettings()
    }

    private fun rowMoveDown() {
        val rows = availableRows()
        val curIdx = rows.indexOf(currentRow.value)
        if (curIdx < rows.size - 1) currentRow.value = rows[curIdx + 1]
    }

    private fun openSettings() {
        settingsEditUrl.value = serverUrl.value
        screen.value = Screen.SETTINGS
    }

    private fun openItemDetail(item: JanusApi.Series) {
        selectedItem.value = item
        episodeFocus.intValue = 0
        detailVisitCount.intValue++
        screen.value = Screen.ITEM_DETAIL
    }

    private fun playItem(item: JanusApi.Series) {
        releasePreviewPlayer()
        val lastWatched = getLastWatched(item.id)
        if (lastWatched != null) {
            val ep = item.episodes.firstOrNull { it.episode == lastWatched.first }
            if (ep != null) {
                if (item.type == "MOVIE") playMovie(item, ep) else playEpisode(item, ep)
                return
            }
        }
        val ep = item.episodes.firstOrNull() ?: return
        if (item.type == "MOVIE") playMovie(item, ep) else playEpisode(item, ep)
    }

    private fun playContinueItem(item: ContinueItem) {
        if (item.series.type == "MOVIE") playMovie(item.series, item.episode)
        else playEpisode(item.series, item.episode)
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

    private fun playMovie(movie: JanusApi.Series, episode: JanusApi.Episode) {
        val videoUrl = api.videoUrl(movie.id, episode.filename)
        val subsUrl = if (episode.hasJaSubs && episode.jaSrtFile != null)
            api.subsUrl(movie.id, episode.jaSrtFile) else null
        val savedPos = getWatchProgress(movie.id, episode.episode)

        startActivity(Intent(this, ExoPlayerActivity::class.java).apply {
            putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            putExtra(ExoPlayerActivity.EXTRA_TITLE, movie.titleEn)
            putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, movie.id)
            putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episode.episode)
        })
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePreviewPlayer()
    }
}
