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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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

    enum class Screen { MAIN, ITEM_DETAIL, DOWNLOADS }
    enum class LibraryRow { HEADER, CONTINUE, SERIES, MOVIES }

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
    private val updateAvailable = mutableStateOf<AppUpdater.UpdateInfo?>(null)
    private val updateDownloading = mutableStateOf(false)
    private var appUpdater: AppUpdater? = null
    private val showCursorState = mutableStateOf(true)
    private val selectedItem = mutableStateOf<JanusApi.Series?>(null)
    private val previewRequested = mutableStateOf(false)

    private lateinit var api: JanusApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        serverUrl.value = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        api = JanusApi(serverUrl.value)
        DownloadManager.init(this)

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

        AppNavigator.registerOnExit(AppNavigator.Screen.ITEM_DETAIL) { releasePreviewPlayer() }

        setContent {
            LibraryScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        val navScreen = when (screen.value) {
            Screen.MAIN, Screen.DOWNLOADS -> AppNavigator.Screen.MAIN
            Screen.ITEM_DETAIL -> AppNavigator.Screen.ITEM_DETAIL
        }
        AppNavigator.onActivityResumed(navScreen)
        if (screen.value == Screen.ITEM_DETAIL) {
            refreshItemDetail()
        }
    }

    @Composable
    private fun LibraryScreen() {
        val currentScreen by screen
        val isLoading by loading
        val sFocus by seriesFocus
        val eFocus by episodeFocus
        val showCursor by showCursorState

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A1A))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            showCursorState.value = false
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize().then(
                    if (currentScreen != Screen.ITEM_DETAIL) Modifier.padding(top = 32.dp) else Modifier
                )
            ) {
                // Header (always visible except item detail)
                if (currentScreen != Screen.ITEM_DETAIL) {
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
                        val headerFocused = showCursor && currentScreen == Screen.MAIN && currentRow.value == LibraryRow.HEADER
                        if (DownloadManager.items.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                    .clickable { screen.value = Screen.DOWNLOADS }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                val active = DownloadManager.items.count { it.state == DownloadManager.State.DOWNLOADING || it.state == DownloadManager.State.QUEUED }
                                androidx.compose.material3.Text(
                                    if (active > 0) "↓ Downloads ($active)" else "↓ Downloads",
                                    color = Color(0xFFCCCCCC), fontSize = 14.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(
                            modifier = Modifier
                                .then(if (headerFocused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                                .background(if (headerFocused) Color(0xFF3A3A5A) else Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                .clickable { openSettings() }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            androidx.compose.material3.Text(
                                "⚙ Settings",
                                color = if (headerFocused) Color.White else Color(0xFFCCCCCC),
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Update banner
                    val update by updateAvailable
                    val downloading by updateDownloading
                    if (update != null) {
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
                }

                // Content area
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text(
                            "Loading library...", color = Color.White, fontSize = 18.sp
                        )
                    }
                } else if (library.isEmpty() && currentScreen == Screen.MAIN) {
                    var editUrl by remember { mutableStateOf(serverUrl.value) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.Text(
                            "Could not connect to server", color = Color(0xFF888888), fontSize = 18.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        androidx.compose.material3.Text("Server URL", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
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
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                                .clickable {
                                    val newUrl = editUrl.trim()
                                    if (newUrl.isNotEmpty()) {
                                        serverUrl.value = newUrl
                                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                            .putString("server_url", newUrl).apply()
                                        api = JanusApi(newUrl)
                                        reloadLibrary()
                                    }
                                }
                                .padding(horizontal = 32.dp, vertical = 12.dp)
                        ) {
                            androidx.compose.material3.Text(
                                "Connect", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    when (currentScreen) {
                        Screen.MAIN -> LibraryRows(sFocus, showCursor)
                        Screen.ITEM_DETAIL -> {
                            val item = selectedItem.value
                            if (item != null) ItemDetailPage(item, eFocus, showCursor)
                            else screen.value = Screen.MAIN
                        }
                        Screen.DOWNLOADS -> DownloadsScreen()
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
        val mainScrollState = rememberScrollState()
        val rowPositions = remember { mutableStateMapOf<LibraryRow, Pair<Int, Int>>() }
        var viewportHeight by remember { mutableIntStateOf(1080) }

        LaunchedEffect(cFocus, activeRow) {
            if (activeRow == LibraryRow.CONTINUE) continueListState.animateScrollToItem(cFocus.coerceAtLeast(0))
        }
        LaunchedEffect(focusIdx, activeRow) {
            if (activeRow == LibraryRow.SERIES) seriesListState.animateScrollToItem(focusIdx.coerceAtLeast(0))
        }
        LaunchedEffect(mFocus, activeRow) {
            if (activeRow == LibraryRow.MOVIES) moviesListState.animateScrollToItem(mFocus.coerceAtLeast(0))
        }
        LaunchedEffect(activeRow) {
            val bounds = rowPositions[activeRow] ?: return@LaunchedEffect
            val itemTop = bounds.first
            val itemBottom = bounds.second
            val itemHeight = itemBottom - itemTop
            val visibleTop = mainScrollState.value
            val visibleBottom = visibleTop + viewportHeight
            if (itemHeight <= viewportHeight) {
                // Row fits — make sure both top and bottom are visible
                if (itemBottom > visibleBottom) {
                    mainScrollState.animateScrollTo(itemBottom - viewportHeight + 16)
                } else if (itemTop < visibleTop) {
                    mainScrollState.animateScrollTo((itemTop - 16).coerceAtLeast(0))
                }
            } else {
                // Row taller than viewport — show the top
                mainScrollState.animateScrollTo((itemTop - 16).coerceAtLeast(0))
            }
        }

        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { viewportHeight = it.size.height }) {
        Column(modifier = Modifier.verticalScroll(mainScrollState)) {
            // Continue Watching row
            if (continueItems.isNotEmpty()) {
                Column(modifier = Modifier.onGloballyPositioned { coords ->
                    val y = coords.positionInParent().y.toInt()
                    rowPositions[LibraryRow.CONTINUE] = y to (y + coords.size.height)
                }) {
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
                } // end Continue column
            }

            // Series row
            if (seriesList.isNotEmpty()) {
                Column(modifier = Modifier.onGloballyPositioned { coords ->
                    val y = coords.positionInParent().y.toInt()
                    rowPositions[LibraryRow.SERIES] = y to (y + coords.size.height)
                }) {
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
                } // end Series column
            }

            // Movies row
            if (movieList.isNotEmpty()) {
                Column(modifier = Modifier.onGloballyPositioned { coords ->
                    val y = coords.positionInParent().y.toInt()
                    rowPositions[LibraryRow.MOVIES] = y to (y + coords.size.height)
                }) {
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
                } // end Movies column
            }
        }
        } // end viewport Box
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

        val epCardPositions = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
        var detailViewportHeight by remember { mutableIntStateOf(1080) }
        LaunchedEffect(focusIdx) {
            if (item.type != "MOVIE" && item.episodes.isNotEmpty()) {
                val bounds = epCardPositions[focusIdx] ?: return@LaunchedEffect
                val visibleTop = scrollState.value
                val visibleBottom = visibleTop + detailViewportHeight
                if (bounds.second > visibleBottom) {
                    scrollState.animateScrollTo(bounds.second - detailViewportHeight + 32)
                } else if (bounds.first < visibleTop) {
                    scrollState.animateScrollTo((bounds.first - 32).coerceAtLeast(0))
                }
            }
        }

        DisposableEffect(item.id) {
            onDispose { releasePreviewPlayer() }
        }
        val shouldStartPreview by previewRequested
        LaunchedEffect(shouldStartPreview) {
            if (shouldStartPreview) {
                showPreview = false
                delay(3000)
                showPreview = true
                previewRequested.value = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { detailViewportHeight = it.size.height }) {
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

                // Faded borders — left, bottom, top, right
                Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(
                    listOf(Color(0xDD0A0A1A), Color(0x660A0A1A), Color.Transparent),
                    startX = 0f, endX = 600f
                )))
                Box(modifier = Modifier.fillMaxWidth().height(120.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0A0A1A))))
                )
                Box(modifier = Modifier.fillMaxWidth().height(60.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xAA0A0A1A), Color.Transparent)))
                )
                Box(modifier = Modifier.fillMaxHeight().width(80.dp).align(Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xAA0A0A1A)))
                ))

                // Content overlaid on hero
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 32.dp, bottom = 16.dp, end = 32.dp)
                        .widthIn(max = 500.dp)
                ) {
                    // Back
                    androidx.compose.material3.Text(
                        "← Back", color = Color(0xFF888888), fontSize = 13.sp,
                        modifier = Modifier.clickable { closeItemDetail() }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.weight(1f)
                                .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                                .clickable { releasePreviewPlayer(); playItem(item) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Text(
                                resumeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        val allDownloaded = item.episodes.all { ep ->
                            DownloadManager.getItemState(item.id, ep.episode)?.state == DownloadManager.State.COMPLETED
                        }
                        if (!allDownloaded) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                    .clickable { startDownload(item) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Text(
                                    if (item.type == "MOVIE") "↓ Download" else "↓ Download All",
                                    color = Color(0xFFCCCCCC), fontSize = 14.sp
                                )
                            }
                        }
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
                val gridHeight = (rows * 280).dp
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(gridHeight).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(item.episodes) { idx, ep ->
                        Box(modifier = Modifier.onGloballyPositioned { coords ->
                            val y = coords.positionInParent().y.toInt()
                            epCardPositions[idx] = y to (y + coords.size.height)
                        }) {
                            EpisodeGridCard(item, ep, focused = showCursor && idx == focusIdx) {
                                episodeFocus.intValue = idx
                                releasePreviewPlayer()
                                playEpisode(item, ep)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
        } // end viewport Box
    }

    @Composable
    private fun DownloadsScreen() {
        val dlItems = DownloadManager.items
        val totalSize = remember(dlItems.size) { DownloadManager.totalDiskUsage() }
        val totalMb = totalSize / (1024 * 1024)

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    "Downloads", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Text(
                    if (totalMb > 1024) "%.1f GB".format(totalMb / 1024f) else "$totalMb MB",
                    color = Color(0xFF888888), fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))

            if (dlItems.isEmpty()) {
                androidx.compose.material3.Text(
                    "No downloads yet", color = Color(0xFF888888), fontSize = 16.sp
                )
            } else {
                val grouped = dlItems.groupBy { it.seriesId }
                for ((seriesId, episodes) in grouped) {
                    val seriesTitle = episodes.first().seriesTitleEn.ifEmpty { seriesId }
                    val completedCount = episodes.count { it.state == DownloadManager.State.COMPLETED }
                    val seriesSize = episodes.filter { it.state == DownloadManager.State.COMPLETED }.sumOf { it.totalBytes }
                    val seriesMb = seriesSize / (1024 * 1024)

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        androidx.compose.material3.Text(
                            "$seriesTitle ($completedCount/${episodes.size})",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.Text(
                            if (seriesMb > 1024) "%.1f GB".format(seriesMb / 1024f) else "$seriesMb MB",
                            color = Color(0xFF888888), fontSize = 13.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.Text(
                            "Delete All", color = Color(0xFFFF5252), fontSize = 12.sp,
                            modifier = Modifier.clickable { DownloadManager.deleteSeries(seriesId) }
                        )
                    }

                    for (item in episodes) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                .background(Color(0xFF1A1A2E), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            val stateText = when (item.state) {
                                DownloadManager.State.COMPLETED -> "✓"
                                DownloadManager.State.DOWNLOADING -> "↓ ${item.progress}%"
                                DownloadManager.State.QUEUED -> "⏳"
                                DownloadManager.State.FAILED -> "✗ Failed"
                            }
                            val stateColor = when (item.state) {
                                DownloadManager.State.COMPLETED -> Color(0xFF81C784)
                                DownloadManager.State.DOWNLOADING -> Color(0xFFBB86FC)
                                DownloadManager.State.QUEUED -> Color(0xFF888888)
                                DownloadManager.State.FAILED -> Color(0xFFFF5252)
                            }
                            androidx.compose.material3.Text(stateText, color = stateColor, fontSize = 13.sp)
                            Spacer(Modifier.width(12.dp))
                            androidx.compose.material3.Text(
                                item.titleEn.ifEmpty { "Episode ${item.episodeNum}" },
                                color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f)
                            )
                            if (item.state == DownloadManager.State.COMPLETED) {
                                val mb = item.totalBytes / (1024 * 1024)
                                androidx.compose.material3.Text("$mb MB", color = Color(0xFF888888), fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Text(
                                "×", color = Color(0xFFFF5252), fontSize = 16.sp,
                                modifier = Modifier.clickable { DownloadManager.delete(item.seriesId, item.episodeNum) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF442222), RoundedCornerShape(8.dp))
                        .clickable { DownloadManager.deleteAll() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    androidx.compose.material3.Text(
                        "Delete All Downloads", color = Color(0xFFFF5252), fontSize = 14.sp
                    )
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
        ) {
            // Thumbnail
            if (ep.thumb != null) {
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/${ep.thumb}",
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
            }

            Column(modifier = Modifier.padding(8.dp)) {
                // Episode number + title
                val title = if (ep.titleEn.isNotEmpty()) "${ep.episode}. ${ep.titleEn}" else "Episode ${ep.episode}"
                androidx.compose.material3.Text(
                    title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )

                // Synopsis
                if (ep.synopsisEn.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text(
                        ep.synopsisEn, color = Color(0xFF999999), fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Metadata + download state
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subs = mutableListOf<String>()
                    if (ep.hasJaSubs) subs.add("JP")
                    if (ep.hasEnSubs) subs.add("EN")
                    if (ep.hasFrSubs) subs.add("FR")
                    androidx.compose.material3.Text(
                        "${mins} min" + if (subs.isNotEmpty()) " · ${subs.joinToString(" ")}" else "",
                        color = Color(0xFF888888), fontSize = 10.sp
                    )
                    Spacer(Modifier.weight(1f))
                    val dlItem = DownloadManager.getItemState(series.id, ep.episode)
                    when (dlItem?.state) {
                        DownloadManager.State.COMPLETED -> androidx.compose.material3.Text("✓", color = Color(0xFF81C784), fontSize = 12.sp)
                        DownloadManager.State.DOWNLOADING -> androidx.compose.material3.Text("↓${dlItem.progress}%", color = Color(0xFFBB86FC), fontSize = 10.sp)
                        DownloadManager.State.QUEUED -> androidx.compose.material3.Text("⏳", color = Color(0xFF888888), fontSize = 10.sp)
                        DownloadManager.State.FAILED -> androidx.compose.material3.Text("✗", color = Color(0xFFFF5252), fontSize = 12.sp)
                        null -> {}
                    }
                }

                // Watch progress
                if (progressFraction > 0.01f) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progressFraction).background(Color(0xFFBB86FC), RoundedCornerShape(2.dp)))
                    }
                    if (progressFraction >= 0.95f) {
                        Spacer(Modifier.height(2.dp))
                        androidx.compose.material3.Text("✓ Watched", color = Color(0xFF81C784), fontSize = 10.sp)
                    }
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
        if (isDpad) showCursorState.value = true

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

            Screen.ITEM_DETAIL -> {
                val item = selectedItem.value ?: return false
                val cols = 4
                val maxIdx = item.episodes.size - 1
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { closeItemDetail(); return true }
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

            Screen.DOWNLOADS -> when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> { screen.value = Screen.MAIN; return true }
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

    private fun rowFocus(): MutableIntState = when (currentRow.value) {
        LibraryRow.HEADER -> seriesFocus
        LibraryRow.CONTINUE -> continueFocus
        LibraryRow.SERIES -> seriesFocus
        LibraryRow.MOVIES -> movieFocus
    }

    private fun rowSize(): Int = when (currentRow.value) {
        LibraryRow.HEADER -> 1
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
            LibraryRow.HEADER -> openSettings()
            LibraryRow.CONTINUE -> {
                val items = getContinueWatching()
                items.getOrNull(idx)?.let { playContinueItem(it) }
            }
            LibraryRow.SERIES -> seriesList.getOrNull(idx)?.let { openItemDetail(it) }
            LibraryRow.MOVIES -> movieList.getOrNull(idx)?.let { openItemDetail(it) }
        }
    }

    private fun availableRows(): List<LibraryRow> {
        val rows = mutableListOf(LibraryRow.HEADER)
        if (getContinueWatching().isNotEmpty()) rows.add(LibraryRow.CONTINUE)
        if (seriesList.isNotEmpty()) rows.add(LibraryRow.SERIES)
        if (movieList.isNotEmpty()) rows.add(LibraryRow.MOVIES)
        return rows
    }

    private fun rowMoveUp() {
        val rows = availableRows()
        val curIdx = rows.indexOf(currentRow.value)
        if (curIdx > 0) currentRow.value = rows[curIdx - 1]
    }

    private fun rowMoveDown() {
        val rows = availableRows()
        val curIdx = rows.indexOf(currentRow.value)
        if (curIdx < rows.size - 1) currentRow.value = rows[curIdx + 1]
    }

    private fun openSettings() {
        AppNavigator.navigate(this, AppNavigator.Action.OPEN_SETTINGS)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openItemDetail(item: JanusApi.Series) {
        selectedItem.value = item
        episodeFocus.intValue = 0
        AppNavigator.navigate(this, AppNavigator.Action.OPEN_ITEM)
        screen.value = Screen.ITEM_DETAIL
        refreshItemDetail()
    }

    private fun refreshItemDetail() {
        releasePreviewPlayer()
        previewRequested.value = true
    }

    private fun closeItemDetail() {
        releasePreviewPlayer()
        AppNavigator.navigate(this, AppNavigator.Action.CLOSE_ITEM)
        screen.value = Screen.MAIN
    }

    private fun playItem(item: JanusApi.Series) {
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

    private fun startDownload(item: JanusApi.Series) {
        if (item.type == "MOVIE") {
            val ep = item.episodes.firstOrNull() ?: return
            val srtFiles = mutableListOf<Pair<String, String>>()
            if (ep.hasJaSubs && ep.jaSrtFile != null) srtFiles.add(ep.jaSrtFile to api.subsUrl(item.id, ep.jaSrtFile))
            DownloadManager.enqueueEpisode(
                item.id, ep.episode, ep.filename,
                api.videoUrl(item.id, ep.filename), srtFiles,
                titleEn = item.titleEn, seriesTitleEn = ""
            )
        } else {
            DownloadManager.enqueueSeries(item, api)
        }
        startService(Intent(this, DownloadService::class.java))
    }

    private fun resolveVideoUrl(seriesId: String, filename: String): String {
        val local = DownloadManager.getLocalVideoPath(seriesId, filename)
        return if (local != null) "file://$local" else api.videoUrl(seriesId, filename)
    }

    private fun resolveSubsUrl(seriesId: String, srtFile: String?): String? {
        if (srtFile == null) return null
        val local = DownloadManager.getLocalSubsPath(seriesId, srtFile)
        return if (local != null) "file://$local" else api.subsUrl(seriesId, srtFile)
    }

    private fun playEpisode(series: JanusApi.Series, episode: JanusApi.Episode) {
        val videoUrl = resolveVideoUrl(series.id, episode.filename)
        val subsUrl = if (episode.hasJaSubs) resolveSubsUrl(series.id, episode.jaSrtFile) else null
        val savedPos = getWatchProgress(series.id, episode.episode)

        AppNavigator.navigate(this, AppNavigator.Action.PLAY_VIDEO) { intent ->
            intent.putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_TITLE, "${series.titleEn} - Episode ${episode.episode}")
            intent.putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            intent.putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, series.id)
            intent.putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episode.episode)
        }
    }

    private fun playMovie(movie: JanusApi.Series, episode: JanusApi.Episode) {
        val videoUrl = resolveVideoUrl(movie.id, episode.filename)
        val subsUrl = if (episode.hasJaSubs) resolveSubsUrl(movie.id, episode.jaSrtFile) else null
        val savedPos = getWatchProgress(movie.id, episode.episode)

        AppNavigator.navigate(this, AppNavigator.Action.PLAY_VIDEO) { intent ->
            intent.putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_TITLE, movie.titleEn)
            intent.putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            intent.putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, movie.id)
            intent.putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episode.episode)
        }
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
