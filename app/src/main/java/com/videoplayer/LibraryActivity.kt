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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.delay

/**
 * Netflix-style library browser.
 * Shows series covers in a horizontal carousel, then episode list.
 */
class LibraryActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Library"
        private const val DEFAULT_SERVER_URL = "https://canneji.duckdns.org/janus"
        private const val PREFS_NAME = "janus_settings"
    }

    enum class Screen { LOGIN, MAIN, ITEM_DETAIL, DOWNLOADS }
    enum class LibraryRow { HEADER, CONTINUE, SERIES, MOVIES }
    enum class DetailFocus { HERO, SEASON, GRID }

    private val screen = mutableStateOf(Screen.MAIN)
    private val currentRow = mutableStateOf(LibraryRow.SERIES)
    private val continueFocus = mutableIntStateOf(0)
    private val seriesFocus = mutableIntStateOf(0)
    private val movieFocus = mutableIntStateOf(0)
    private val episodeFocus = mutableIntStateOf(0)
    private val library = mutableStateListOf<JanusApi.LibraryItem>()
    private val seriesList = mutableStateListOf<JanusApi.LibraryItem>()
    private val movieList = mutableStateListOf<JanusApi.LibraryItem>()
    private val loading = mutableStateOf(true)
    private val isOffline = mutableStateOf(false)
    private val serverUrl = mutableStateOf(DEFAULT_SERVER_URL)
    private val showCursorState = mutableStateOf(true)
    private val loginError = mutableStateOf("")
    private var pendingDetailItemId: String? = null
    private val selectedLibItem = mutableStateOf<JanusApi.LibraryItem?>(null)
    private val detailEpisodes = mutableStateListOf<JanusApi.Episode>()
    private val detailCards = mutableStateListOf<JanusApi.CardEpisode>()
    private var gridColumnCount = 4
    private val detailSeasons = mutableStateListOf<JanusApi.SeasonInfo>()
    private val selectedSeason = mutableIntStateOf(0)
    private val seasonCache = mutableMapOf<Int, List<JanusApi.Episode>>()
    private val thumbCache = mutableStateMapOf<Int, android.graphics.Bitmap>()
    private val detailSynopsis = mutableStateOf("")
    private val detailLoading = mutableStateOf(false)
    private val previewRequested = mutableStateOf(false)
    private val showPreview = mutableStateOf(false)
    private val detailFocus = mutableStateOf(DetailFocus.HERO)
    private val heroButtonFocus = mutableIntStateOf(0) // 0=play, 1=download

    private lateinit var api: JanusApi
    private lateinit var imageLoader: ImageLoader

    private fun buildImageLoader(): ImageLoader {
        val dispatcher = okhttp3.Dispatcher().apply { maxRequestsPerHost = 20 }
        val httpClient = okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(20, 2, java.util.concurrent.TimeUnit.MINUTES))
            .addInterceptor { chain ->
                val request = api.token?.let {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $it")
                        .build()
                } ?: chain.request()
                chain.proceed(request)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(httpClient)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        JanusTheme.isDark.value = AppSettings(this).darkMode
        serverUrl.value = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        api = JanusApi(serverUrl.value)
        @Suppress("DEPRECATION")
        api.appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        imageLoader = buildImageLoader()
        DownloadManager.init(this)

        // Check for saved session
        Lang.current.value = prefs.getString("app_language", "ja") ?: "ja"
        val savedToken = prefs.getString("auth_token", null)
        if (savedToken != null) {
            api.token = savedToken
            PlayerManager.authToken = savedToken
            loadLibrary()
            Thread { syncLanguageFromServer() }.start()
        } else {
            screen.value = Screen.LOGIN
        }

        AppNavigator.registerOnExit(AppNavigator.Screen.ITEM_DETAIL) { releasePreviewPlayer() }

        val openScreen = intent.getStringExtra("open_screen")
        if (openScreen == "downloads") screen.value = Screen.DOWNLOADS
        else if (openScreen == "login") { logout() }

        // Restore state after recreation
        savedInstanceState?.let {
            val savedScreen = it.getString("screen")
            val savedItemId = it.getString("selectedItemId")
            if (savedScreen == "ITEM_DETAIL" && savedItemId != null) {
                val item = library.firstOrNull { lib -> lib.id == savedItemId }
                    ?: seriesList.firstOrNull { lib -> lib.id == savedItemId }
                    ?: movieList.firstOrNull { lib -> lib.id == savedItemId }
                if (item != null) {
                    openItemDetail(item)
                } else {
                    // Library not loaded yet — save ID and open detail after load
                    pendingDetailItemId = savedItemId
                }
            }
        }

        setContent {
            val config = LocalConfiguration.current
            val dimens = computeDimens(config.screenWidthDp.dp)
            CompositionLocalProvider(LocalDimens provides dimens) {
                LibraryScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.value.name)
        selectedLibItem.value?.let { outState.putString("selectedItemId", it.id) }
    }

    override fun onResume() {
        super.onResume()
        JanusTheme.isDark.value = AppSettings(this).darkMode
        val navScreen = when (screen.value) {
            Screen.LOGIN -> AppNavigator.Screen.MAIN
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
        val dimens = LocalDimens.current
        val currentScreen by screen
        val isLoading by loading
        val sFocus by seriesFocus
        val eFocus by episodeFocus
        val showCursor by showCursorState

        Box(
            modifier = Modifier.fillMaxSize().background(JanusTheme.bg)
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
                    if (currentScreen != Screen.ITEM_DETAIL && currentScreen != Screen.LOGIN) Modifier.padding(top = dimens.rowPadding) else Modifier
                )
            ) {
                // Header (hidden on login and item detail)
                if (currentScreen != Screen.ITEM_DETAIL && currentScreen != Screen.LOGIN) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.rowPadding, vertical = 8.dp)
                    ) {
                        androidx.compose.material3.Text(
                            Lang.s("app_name"),
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
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                val active = DownloadManager.items.count { it.state == DownloadManager.State.DOWNLOADING || it.state == DownloadManager.State.QUEUED }
                                androidx.compose.material3.Text(
                                    if (active > 0) "↓ $active" else "↓",
                                    color = Color(0xFFCCCCCC), fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        val lang by Lang.current
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = when (lang) { "ja" -> "en"; "en" -> "fr"; else -> "ja" }
                                    setLanguage(next)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            androidx.compose.material3.Text(
                                Lang.s("lang_label"),
                                color = Color(0xFFCCCCCC), fontSize = 14.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .then(if (headerFocused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                                .background(if (headerFocused) Color(0xFF3A3A5A) else Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                .clickable { openSettings() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            androidx.compose.material3.Text(
                                "⚙",
                                color = if (headerFocused) Color.White else Color(0xFFCCCCCC),
                                fontSize = 16.sp
                            )
                        }
                    }

                }

                // Content area
                if (currentScreen == Screen.LOGIN) {
                    LoginScreen()
                } else if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Text(
                            Lang.s("loading_library"), color = Color.White, fontSize = 18.sp
                        )
                    }
                } else if (library.isEmpty() && currentScreen == Screen.MAIN) {
                    var editUrl by remember { mutableStateOf(serverUrl.value) }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = dimens.rowPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.material3.Text(
                            Lang.s("no_connection"), color = Color(0xFF888888), fontSize = 18.sp
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
                            modifier = Modifier.fillMaxWidth(dimens.loginFieldFraction)
                        )
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                                .clickable {
                                    var newUrl = editUrl.trim()
                                    if (newUrl.isNotEmpty()) {
                                        if (!newUrl.startsWith("http")) newUrl = "http://$newUrl"
                                        serverUrl.value = newUrl
                                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                            .putString("server_url", newUrl).apply()
                                        val oldToken = api.token
                                        api = JanusApi(newUrl)
                                        api.token = oldToken
                                        imageLoader = buildImageLoader()
                                        reloadLibrary()
                                    }
                                }
                                .padding(horizontal = 32.dp, vertical = 12.dp)
                        ) {
                            androidx.compose.material3.Text(
                                Lang.s("connect"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        if (DownloadManager.items.any { it.state == DownloadManager.State.COMPLETED }) {
                            Spacer(Modifier.height(24.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                    .clickable { screen.value = Screen.DOWNLOADS }
                                    .padding(horizontal = 32.dp, vertical = 12.dp)
                            ) {
                                val count = DownloadManager.items.count { it.state == DownloadManager.State.COMPLETED }
                                androidx.compose.material3.Text(
                                    Lang.s("watch_offline", count), color = Color(0xFFCCCCCC), fontSize = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    when (currentScreen) {
                        Screen.LOGIN -> {} // handled above
                        Screen.MAIN -> LibraryRows(sFocus, showCursor)
                        Screen.ITEM_DETAIL -> {
                            val item = selectedLibItem.value
                            if (item != null) ItemDetailPage(item, eFocus, showCursor)
                            else screen.value = Screen.MAIN
                        }
                        Screen.DOWNLOADS -> DownloadsScreen()
                    }
                }
            }
        }
    }

    data class ContinueItem(
        val libItem: JanusApi.LibraryItem,
        val episodeNum: Int,
        val season: Int,
        val positionMs: Long,
        val durationMs: Long,
    )

    private fun getContinueWatching(): List<ContinueItem> {
        val prefs = getSharedPreferences("watch_progress", MODE_PRIVATE)
        return library.mapNotNull { item ->
            val lastEpStr = prefs.getString("${item.id}_last_ep", null) ?: return@mapNotNull null
            val lastEp = lastEpStr.toIntOrNull() ?: return@mapNotNull null
            val pos = prefs.getLong("${item.id}_last_pos", 0L)
            if (pos < 5000) return@mapNotNull null
            val dur = prefs.getLong("${item.id}_ep${lastEp}_dur", 0L)
            if (dur > 0 && pos.toFloat() / dur > 0.95f) return@mapNotNull null
            val season = prefs.getInt("${item.id}_last_season", 1)
            ContinueItem(item, lastEp, season, pos, dur)
        }
    }

    @Composable
    private fun LibraryRows(focusIdx: Int, showCursor: Boolean) {
        val dimens = LocalDimens.current
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
                    Lang.s("continue_watching"),
                    color = if (showCursor && activeRow == LibraryRow.CONTINUE) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = dimens.rowPadding, vertical = 8.dp)
                )
                LazyRow(
                    state = continueListState,
                    contentPadding = PaddingValues(horizontal = dimens.rowPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(continueItems) { idx, item ->
                        val focused = showCursor && activeRow == LibraryRow.CONTINUE && idx == cFocus
                        val progress = if (item.durationMs > 0) (item.positionMs.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f
                        Column(
                            modifier = Modifier.width(dimens.continueCardWidth)
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
                                item.libItem.title(),
                                color = Color(0xFFBB86FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            androidx.compose.material3.Text(
                                if (item.libItem.type == "MOVIE") item.libItem.title()
                                else Lang.s("episode", item.episodeNum),
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
                    Lang.s("series"),
                    color = if (showCursor && activeRow == LibraryRow.SERIES) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = dimens.rowPadding, vertical = 8.dp)
                )
                LazyRow(
                    state = seriesListState,
                    contentPadding = PaddingValues(horizontal = dimens.rowPadding),
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
                    Lang.s("movies"),
                    color = if (showCursor && activeRow == LibraryRow.MOVIES) Color(0xFFBB86FC) else Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = dimens.rowPadding, vertical = 8.dp)
                )
                LazyRow(
                    state = moviesListState,
                    contentPadding = PaddingValues(horizontal = dimens.rowPadding),
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
    private fun SeriesCard(series: JanusApi.LibraryItem, focused: Boolean, onTap: () -> Unit) {
        val dimens = LocalDimens.current
        val offline by isOffline
        val hasCached = remember(series.id, DownloadManager.items.size) {
            DownloadManager.items.any { it.seriesId == series.id && it.state == DownloadManager.State.COMPLETED }
        }
        val greyed = offline && !hasCached
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(dimens.cardWidth)
                .then(if (!greyed) Modifier.clickable { onTap() } else Modifier)
                .then(
                    if (focused && !greyed) Modifier.border(3.dp, Color(0xFFBB86FC), RoundedCornerShape(12.dp))
                    else Modifier
                )
                .then(if (greyed) Modifier.graphicsLayer { alpha = 0.3f } else Modifier)
        ) {
            // Cover image
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(dimens.cardWidth)
                    .height(dimens.cardHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A2E))
            ) {
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/covers/${series.id}.jpg",
                    contentDescription = series.titleEn,
                    imageLoader = imageLoader,
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
                            series.title(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    @Composable
    private fun LoginScreen() {
        val dimens = LocalDimens.current
        var serverInput by remember { mutableStateOf(serverUrl.value) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val error by loginError

        val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFFBB86FC), unfocusedBorderColor = Color(0xFF444444),
            cursorColor = Color(0xFFBB86FC),
            focusedContainerColor = Color(0xFF1E1E2E), unfocusedContainerColor = Color(0xFF1E1E2E),
        )

        val doLogin = {
            var url = serverInput.trim()
            if (url.isNotEmpty() && !url.startsWith("http")) url = "http://$url"
            val user = username.trim()
            val pass = password.trim()
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                loginError.value = Lang.s("all_fields_required")
            } else {
                loginError.value = ""
                serverUrl.value = url
                api = JanusApi(url)
                imageLoader = buildImageLoader()
                Thread {
                    try {
                        val result = api.login(user, pass)
                        runOnUiThread {
                            if (result != null) {
                                PlayerManager.authToken = result.token
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                    .putString("server_url", url)
                                    .putString("auth_token", result.token)
                                    .putString("username", result.username)
                                    .apply()
                                screen.value = Screen.MAIN
                                loadLibrary()
                            } else {
                                loginError.value = Lang.s("invalid_credentials")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { loginError.value = "Connection failed: ${e.message}" }
                    }
                }.start()
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(JanusTheme.bg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 400.dp).padding(horizontal = 32.dp)
            ) {
                androidx.compose.material3.Text(
                    Lang.s("app_name"), color = Color(0xFFBB86FC), fontSize = 42.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(48.dp))

                androidx.compose.material3.OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { androidx.compose.material3.Text(Lang.s("username")) },
                    singleLine = true, colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                androidx.compose.material3.OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { androidx.compose.material3.Text(Lang.s("password")) },
                    singleLine = true, colors = fieldColors,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Text(error, color = Color(0xFFFF5252), fontSize = 14.sp)
                }

                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFFBB86FC), RoundedCornerShape(12.dp))
                        .clickable { doLogin() }
                        .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(Lang.s("login"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            }
        }
    }

    private fun logout() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove("auth_token")
            .remove("username")
            .apply()
        api.token = null
        library.clear()
        seriesList.clear()
        movieList.clear()
        loading.value = false
        isOffline.value = false
        screen.value = Screen.LOGIN
    }

    private fun releasePreviewPlayer() {
        PlayerManager.stop()
        PlayerManager.detachView()
        showPreview.value = false
        previewRequested.value = false
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    @Composable
    private fun ItemDetailPage(item: JanusApi.LibraryItem, focusIdx: Int, showCursor: Boolean) {
        val dimens = LocalDimens.current
        val scrollState = rememberScrollState()
        val showPreviewState by showPreview

        val epCardRefs = remember { mutableStateMapOf<Int, androidx.compose.ui.layout.LayoutCoordinates>() }
        var detailViewportHeight by remember { mutableIntStateOf(1080) }
        LaunchedEffect(focusIdx) {
            if (item.type != "MOVIE" && detailCards.isNotEmpty()) {
                val coords = epCardRefs[focusIdx] ?: return@LaunchedEffect
                if (!coords.isAttached) return@LaunchedEffect
                val cardTop = coords.positionInRoot().y.toInt()
                val cardBottom = cardTop + coords.size.height
                val scrollY = scrollState.value
                val pageScrollY = when {
                    cardBottom > detailViewportHeight -> scrollY + (cardBottom - detailViewportHeight) + 32
                    cardTop < 0 -> (scrollY + cardTop - 32).coerceAtLeast(0)
                    else -> null
                }
                if (pageScrollY != null) scrollState.animateScrollTo(pageScrollY)
            }
        }

        DisposableEffect(item.id) {
            onDispose { releasePreviewPlayer() }
        }
        val shouldStartPreview by previewRequested
        LaunchedEffect(shouldStartPreview) {
            if (shouldStartPreview) {
                showPreview.value = false
                delay(3000)
                showPreview.value = true
                previewRequested.value = false
            }
        }

        val isDetailLoading by detailLoading
        if (isDetailLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(Lang.s("loading"), color = Color(0xFF888888), fontSize = 16.sp)
            }
            return
        }

        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { detailViewportHeight = it.size.height }) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
        ) {
            // Hero area
            Box(
                modifier = Modifier.fillMaxWidth().height(dimens.heroHeight)
            ) {
                // Backdrop: cover image (banner if available, else cover)
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/covers/${item.id}-banner.jpg",
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Video preview fades in after 3s
                androidx.compose.animation.AnimatedVisibility(
                    visible = showPreviewState,
                    enter = fadeIn(tween(1500)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val firstCard = detailCards.firstOrNull() ?: detailEpisodes.firstOrNull()?.let { JanusApi.CardEpisode(it.episode, it.titleEn, it.durationSec) }
                    if (firstCard != null) {
                        val selSeason = selectedSeason.intValue.let { if (it > 0) it else 1 }
                        val previewUrl = remember(item.id, selSeason, firstCard.episode) { api.streamUrl(item.id, selSeason, firstCard.episode) }
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    PlayerManager.preview(ctx, previewUrl, 8 * 60 * 1000L)
                                    PlayerManager.attachView(this)
                                }
                            },
                            update = { view ->
                                if (view.player == null) PlayerManager.attachView(view)
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
                    .background(Brush.verticalGradient(listOf(Color.Transparent, JanusTheme.bg)))
                )
                Box(modifier = Modifier.fillMaxWidth().height(60.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(JanusTheme.bg.copy(alpha = 0.67f), Color.Transparent)))
                )
                Box(modifier = Modifier.fillMaxHeight().width(80.dp).align(Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, JanusTheme.bg.copy(alpha = 0.67f)))
                ))

                // Content overlaid on hero
                Column(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = dimens.rowPadding, bottom = 16.dp, end = dimens.rowPadding)
                        .widthIn(max = dimens.heroContentMaxWidth)
                ) {
                    // Back + Title
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Text(
                            "←", color = Color(0xFF888888), fontSize = 22.sp,
                            modifier = Modifier.clickable { closeItemDetail() }
                                .padding(end = 12.dp)
                        )
                        androidx.compose.material3.Text(
                            item.title(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Play button
                    val lastWatched = getLastWatched(item.id)
                    val resumeLabel = when {
                        lastWatched != null && item.type == "MOVIE" -> Lang.s("resume")
                        lastWatched != null -> Lang.s("resume_ep", lastWatched.first)
                        item.type == "MOVIE" -> Lang.s("play")
                        else -> {
                            val firstCard = detailCards.firstOrNull()
                            if (firstCard != null) Lang.s("play_ep", firstCard.episode) else Lang.s("play")
                        }
                    }
                    val dFocus by detailFocus
                    val hFocus by heroButtonFocus
                    val playFocused = showCursor && dFocus == DetailFocus.HERO && hFocus == 0
                    val dlFocused = showCursor && dFocus == DetailFocus.HERO && hFocus == 1
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.weight(1f)
                                .then(if (playFocused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                                .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                                .clickable { releasePreviewPlayer(); playItem() }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.Text(
                                    resumeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            if (lastWatched != null) {
                                val epNum = lastWatched.first
                                val pos = getWatchProgress(item.id, epNum)
                                val dur = getSharedPreferences("watch_progress", MODE_PRIVATE).getLong("${item.id}_ep${epNum}_dur", 0L)
                                if (dur > 0 && pos > 0) {
                                    val progress = (pos.toFloat() / dur).coerceIn(0f, 1f)
                                    Box(
                                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                                            .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                            .background(Color(0x44000000))
                                    ) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White, RoundedCornerShape(bottomStart = 8.dp)))
                                    }
                                }
                            }
                        }
                        if (item.type == "MOVIE") {
                            val allDownloaded = detailEpisodes.all { ep ->
                                DownloadManager.getItemState(item.id, ep.episode)?.state == DownloadManager.State.COMPLETED
                            }
                            if (!allDownloaded) {
                                Box(
                                    modifier = Modifier
                                        .then(if (dlFocused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                                        .background(Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                        .clickable { startDownload() }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.material3.Text(
                                        Lang.s("download"), color = Color(0xFFCCCCCC), fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Metadata line
                    val metaParts = mutableListOf<String>()
                    if (item.type == "MOVIE") {
                        val mins = detailEpisodes.firstOrNull()?.let { (it.durationSec / 60).toInt() } ?: 0
                        metaParts.add(Lang.s("min", mins))
                    } else {
                        metaParts.add(Lang.s("episodes", item.episodeCount))
                    }
                    val jaCount = detailEpisodes.count { it.hasSubs("ja") }
                    if (jaCount > 0) metaParts.add(Lang.s("jp_subs"))
                    androidx.compose.material3.Text(
                        metaParts.joinToString("  ·  "),
                        color = Color(0xFF888888), fontSize = 13.sp
                    )

                    val synopsis = detailSynopsis.value
                    if (synopsis.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.Text(
                            synopsis, color = Color(0xFFBBBBBB), fontSize = 13.sp,
                            lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Season selector + Episode grid (for series)
            if (item.type != "MOVIE" && (detailCards.isNotEmpty() || detailSeasons.isNotEmpty())) {
                Spacer(Modifier.height(8.dp))

                val selSeason by selectedSeason
                if (detailSeasons.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    val currentSeason = detailSeasons.firstOrNull { it.season == selSeason }
                    Box(modifier = Modifier.padding(horizontal = dimens.rowPadding, vertical = 8.dp)) {
                        val seasonFocused = showCursor && detailFocus.value == DetailFocus.SEASON
                        Box(
                            modifier = Modifier
                                .then(if (seasonFocused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                                .background(if (seasonFocused) Color(0xFF3A3A5A) else Color(0xFF2A2A3A), RoundedCornerShape(8.dp))
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val seasonLabel = currentSeason?.let { s ->
                                    val prefix = "S${String.format("%02d", s.season)}"
                                    val name = s.name()
                                    if (name.isNotEmpty()) "$prefix · $name (${s.episodeCount})"
                                    else "$prefix (${s.episodeCount})"
                                } ?: "S01"
                                androidx.compose.material3.Text(
                                    seasonLabel,
                                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                androidx.compose.material3.Text(
                                    if (expanded) "▲" else "▼",
                                    color = Color(0xFFBB86FC), fontSize = 12.sp
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(Color(0xFF2A2A3A)),
                        ) {
                            for (s in detailSeasons) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        val prefix = "S${String.format("%02d", s.season)}"
                                        val name = s.name()
                                        val label = if (name.isNotEmpty()) "$prefix · $name (${s.episodeCount})"
                                            else "$prefix (${s.episodeCount})"
                                        androidx.compose.material3.Text(
                                            label,
                                            color = if (s.season == selSeason) Color(0xFFBB86FC) else Color.White
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        if (s.season != selSeason) loadSeason(item.id, s.season)
                                    }
                                )
                            }
                        }
                    }
                }

            if (detailCards.isNotEmpty()) {
                val minCardWidth = dimens.gridMinCardWidth
                val horizontalPadding = dimens.rowPadding
                val cardSpacing = 12.dp
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)
                ) {
                    val availableWidth = maxWidth
                    val colCount = maxOf(1, ((availableWidth + cardSpacing) / (minCardWidth + cardSpacing)).toInt())
                    gridColumnCount = colCount
                    val rows = (detailCards.size + colCount - 1) / colCount
                    val gridHeight = (rows * dimens.gridRowHeight + (rows - 1) * 12).dp
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minCardWidth),
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(cardSpacing),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(detailCards) { idx, card ->
                        Box(modifier = Modifier.onGloballyPositioned { coords ->
                            epCardRefs[idx] = coords
                        }) {
                            EpisodeCardSlim(card, idx, focused = showCursor && detailFocus.value == DetailFocus.GRID && idx == focusIdx) {
                                episodeFocus.intValue = idx
                                releasePreviewPlayer()
                                launchPlayerByEpisode(item, card.episode)
                            }
                        }
                    }
                }
                }
            } // end episode grid
            } // end season selector + episodes

            Spacer(Modifier.height(32.dp))
        }
        } // end viewport Box
    }

    @Composable
    private fun DownloadsScreen() {
        val dimens = LocalDimens.current
        val dlItems = DownloadManager.items

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = dimens.rowPadding).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Text(
                    Lang.s("back"), color = Color(0xFF888888), fontSize = 13.sp,
                    modifier = Modifier.clickable { screen.value = Screen.MAIN }.padding(end = 12.dp)
                )
                androidx.compose.material3.Text(
                    Lang.s("downloads"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                val totalMb = remember(dlItems.size) { DownloadManager.totalDiskUsage() / (1024 * 1024) }
                androidx.compose.material3.Text(
                    if (totalMb > 1024) "%.1f GB".format(totalMb / 1024f) else "$totalMb MB",
                    color = Color(0xFF888888), fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))

            if (dlItems.isEmpty()) {
                androidx.compose.material3.Text(Lang.s("no_downloads"), color = Color(0xFF888888), fontSize = 16.sp)
            } else {
                val grouped = dlItems.groupBy { it.seriesId }
                for ((seriesId, episodes) in grouped) {
                    val seriesTitle = episodes.first().seriesTitleEn.ifEmpty { seriesId }
                    val completedCount = episodes.count { it.state == DownloadManager.State.COMPLETED }
                    val seriesBytes = episodes.filter { it.state == DownloadManager.State.COMPLETED }.sumOf { it.totalBytes }
                    val seriesMb = seriesBytes / (1024 * 1024)
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
                            "×", color = Color(0xFFFF5252), fontSize = 16.sp,
                            modifier = Modifier.clickable { DownloadManager.deleteSeries(seriesId) }
                        )
                    }
                    for (item in episodes.sortedBy { it.episodeNum }) {
                        val completed = item.state == DownloadManager.State.COMPLETED
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, top = 2.dp, bottom = 2.dp)
                                .background(Color(0xFF1A1A2E), RoundedCornerShape(6.dp))
                                .clickable { if (completed) playDownloadedItem(item) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            when (item.state) {
                                DownloadManager.State.COMPLETED -> {
                                    val mb = item.totalBytes / (1024 * 1024)
                                    androidx.compose.material3.Text("✓", color = Color(0xFF81C784), fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.Text(
                                        item.titleEn.ifEmpty { Lang.s("episode", item.episodeNum) },
                                        color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    androidx.compose.material3.Text("$mb MB", color = Color(0xFF888888), fontSize = 11.sp)
                                }
                                DownloadManager.State.DOWNLOADING -> {
                                    androidx.compose.material3.Text("↓", color = Color(0xFFBB86FC), fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.Text(
                                        item.titleEn.ifEmpty { Lang.s("episode", item.episodeNum) },
                                        color = Color(0xFFBB86FC), fontSize = 14.sp, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    androidx.compose.material3.Text("${item.progress}%", color = Color(0xFFBB86FC), fontSize = 11.sp)
                                }
                                DownloadManager.State.QUEUED -> {
                                    androidx.compose.material3.Text("·", color = Color(0xFF888888), fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.Text(
                                        item.titleEn.ifEmpty { Lang.s("episode", item.episodeNum) },
                                        color = Color(0xFF888888), fontSize = 14.sp, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DownloadManager.State.FAILED -> {
                                    androidx.compose.material3.Text("✗", color = Color(0xFFFF5252), fontSize = 13.sp)
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.Text(
                                        item.titleEn.ifEmpty { Lang.s("episode", item.episodeNum) },
                                        color = Color(0xFFFF5252), fontSize = 14.sp, modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Text(
                                "×", color = Color(0xFFFF5252), fontSize = 14.sp,
                                modifier = Modifier.clickable { DownloadManager.delete(item.seriesId, item.episodeNum) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    @Composable
    private fun EpisodeGridCard(seriesId: String, ep: JanusApi.Episode, focused: Boolean, onTap: () -> Unit) {
        val savedPos = remember(seriesId, ep.episode) { getWatchProgress(seriesId, ep.episode) }
        val progressFraction = if (ep.durationSec > 0) (savedPos / 1000.0 / ep.durationSec).toFloat().coerceIn(0f, 1f) else 0f
        val mins = (ep.durationSec / 60).toInt()

        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (focused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                .background(if (focused) Color(0xFF2A2A4A) else Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                .clickable { onTap() }
        ) {
            val bitmap = thumbCache[ep.episode]
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
            } else if (ep.thumb != null) {
                coil.compose.AsyncImage(
                    model = "${serverUrl.value}/api/${ep.thumb}",
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
            }

            Column(modifier = Modifier.padding(8.dp)) {
                // Episode number + title
                val epTitle = ep.title().takeIf { it.isNotEmpty() } ?: ep.titleEn
                val title = if (epTitle.isNotEmpty()) "${ep.episode}. $epTitle" else Lang.s("episode", ep.episode)
                androidx.compose.material3.Text(
                    title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )

                // Synopsis
                val epSynopsis = ep.synopsis()
                if (epSynopsis.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text(
                        epSynopsis, color = Color(0xFF999999), fontSize = 11.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Metadata + download state
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subs = ep.subtitles.map { it.language.uppercase() }.distinct()
                    androidx.compose.material3.Text(
                        "${mins} min" + if (subs.isNotEmpty()) " · ${subs.joinToString(" ")}" else "",
                        color = Color(0xFF888888), fontSize = 10.sp
                    )
                    Spacer(Modifier.weight(1f))
                    val dlItem = DownloadManager.getItemState(seriesId, ep.episode)
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
                        androidx.compose.material3.Text(Lang.s("watched"), color = Color(0xFF81C784), fontSize = 10.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun EpisodeCardSlim(card: JanusApi.CardEpisode, idx: Int, focused: Boolean, onTap: () -> Unit) {
        val mins = (card.durationSec / 60).toInt()
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (focused) Modifier.border(2.dp, Color(0xFFBB86FC), RoundedCornerShape(8.dp)) else Modifier)
                .background(if (focused) Color(0xFF2A2A4A) else Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                .clickable { onTap() }
        ) {
            val bitmap = thumbCache[card.episode]
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
            } else {
                Box(Modifier.fillMaxWidth().height(130.dp)
                    .background(Color(0xFF222233), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)))
            }
            Column(modifier = Modifier.padding(8.dp)) {
                val title = card.title().takeIf { it.isNotEmpty() }
                    ?.let { "${card.episode}. $it" }
                    ?: Lang.s("episode", card.episode)
                androidx.compose.material3.Text(
                    title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    "$mins min", color = Color(0xFF888888), fontSize = 10.sp
                )
            }
        }
    }

    private fun launchPlayerByEpisode(item: JanusApi.LibraryItem, episodeNum: Int) {
        releasePreviewPlayer()
        val season = selectedSeason.intValue
        val videoUrl = api.streamUrl(item.id, season, episodeNum)
        val subsUrl = api.streamSubsUrl(item.id, season, episodeNum, "ja")
        val savedPos = getWatchProgress(item.id, episodeNum)
        val title = if (item.type == "MOVIE") item.title() else "${item.title()} - ${Lang.s("episode", episodeNum)}"
        val nextEp = detailCards.firstOrNull { it.episode > episodeNum }

        AppNavigator.navigate(this, AppNavigator.Action.PLAY_VIDEO) { intent ->
            intent.putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_TITLE, title)
            intent.putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            intent.putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, item.id)
            intent.putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episodeNum)
            intent.putExtra("season_num", season)
            if (nextEp != null) {
                intent.putExtra("next_video_url", api.streamUrl(item.id, season, nextEp.episode))
                intent.putExtra("next_subs_url", api.streamSubsUrl(item.id, season, nextEp.episode, "ja"))
                intent.putExtra("next_episode_num", nextEp.episode)
                intent.putExtra("next_title", "${item.title()} - ${Lang.s("episode", nextEp.episode)}")
            }
        }
    }

    private fun loadLibrary() {
        loading.value = true
        library.clear()
        seriesList.clear()
        movieList.clear()
        Thread {
            try {
                val items = api.fetchLibrary()
                if (items.isEmpty() && api.token != null) {
                    runOnUiThread {
                        Log.w(TAG, "Library empty with token set — token may be invalid, redirecting to login")
                        logout()
                    }
                    return@Thread
                }
                cacheLibrary(items)
                runOnUiThread {
                    isOffline.value = false
                    populateLibrary(items)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load library: ${e.message}")
                val cached = loadCachedLibrary()
                runOnUiThread {
                    if (cached.isNotEmpty()) {
                        isOffline.value = true
                        populateLibrary(cached)
                    } else {
                        loading.value = false
                    }
                }
            }
        }.start()
    }

    private fun populateLibrary(items: List<JanusApi.LibraryItem>) {
        library.addAll(items)
        seriesList.addAll(items.filter { it.type == "TV_SERIES" })
        movieList.addAll(items.filter { it.type == "MOVIE" })
        loading.value = false
        Log.d(TAG, "Library loaded: ${seriesList.size} series, ${movieList.size} movies (offline=${isOffline.value})")
        pendingDetailItemId?.let { id ->
            pendingDetailItemId = null
            library.firstOrNull { it.id == id }?.let { openItemDetail(it) }
        }
    }

    private fun cacheLibrary(items: List<JanusApi.LibraryItem>) {
        val arr = org.json.JSONArray()
        for (item in items) {
            arr.put(org.json.JSONObject().apply {
                put("id", item.id); put("type", item.type)
                put("title_en", item.titleEn); put("title_ja", item.titleJa)
                put("cover", item.cover); put("episode_count", item.episodeCount)
                put("season_count", item.seasonCount); put("duration_min", item.durationMin)
                val locObj = org.json.JSONObject()
                for ((lang, loc) in item.locales) {
                    locObj.put(lang, org.json.JSONObject().apply {
                        put("title", loc.title); put("synopsis", loc.synopsis)
                    })
                }
                put("locales", locObj)
            })
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("library_cache", arr.toString()).apply()
    }

    private fun loadCachedLibrary(): List<JanusApi.LibraryItem> {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("library_cache", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val locObj = obj.optJSONObject("locales")
                val locales = if (locObj != null) {
                    locObj.keys().asSequence().associate { lang ->
                        val l = locObj.getJSONObject(lang)
                        lang to JanusApi.Locale(l.optString("title", ""), l.optString("synopsis", ""))
                    }
                } else emptyMap()
                JanusApi.LibraryItem(
                    id = obj.getString("id"), type = obj.getString("type"),
                    titleEn = obj.getString("title_en"), titleJa = obj.getString("title_ja"),
                    cover = obj.optString("cover", ""), episodeCount = obj.optInt("episode_count", 1),
                    seasonCount = obj.optInt("season_count", 1), durationMin = obj.optInt("duration_min", 0),
                    locales = locales,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun reloadLibrary() = loadLibrary()

    private fun syncLanguageFromServer() {
        val settings = api.fetchSettings()
        val lang = settings["language"]
        if (lang != null && lang in listOf("ja", "en", "fr")) {
            runOnUiThread { Lang.current.value = lang }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("app_language", lang).apply()
        }
    }

    private fun setLanguage(lang: String) {
        Lang.current.value = lang
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("app_language", lang).apply()
        Thread { api.saveSettings(mapOf("language" to lang)) }.start()
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
            Screen.LOGIN -> return super.dispatchKeyEvent(event)
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
                val item = selectedLibItem.value ?: return false
                val cols = gridColumnCount
                val maxIdx = detailCards.size - 1
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> { closeItemDetail(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val hasSeasons = detailSeasons.size > 1
                        when (detailFocus.value) {
                            DetailFocus.HERO -> {
                                if (item.type != "MOVIE" && hasSeasons) {
                                    detailFocus.value = DetailFocus.SEASON
                                } else if (item.type != "MOVIE" && detailCards.isNotEmpty()) {
                                    detailFocus.value = DetailFocus.GRID
                                }
                            }
                            DetailFocus.SEASON -> {
                                if (detailCards.isNotEmpty()) detailFocus.value = DetailFocus.GRID
                            }
                            DetailFocus.GRID -> {
                                val next = episodeFocus.intValue + cols
                                if (next <= maxIdx) episodeFocus.intValue = next
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        when (detailFocus.value) {
                            DetailFocus.HERO -> {}
                            DetailFocus.SEASON -> detailFocus.value = DetailFocus.HERO
                            DetailFocus.GRID -> {
                                val prev = episodeFocus.intValue - cols
                                if (prev >= 0) episodeFocus.intValue = prev
                                else detailFocus.value = if (detailSeasons.size > 1) DetailFocus.SEASON else DetailFocus.HERO
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        when (detailFocus.value) {
                            DetailFocus.HERO -> { if (heroButtonFocus.intValue > 0) heroButtonFocus.intValue-- }
                            DetailFocus.SEASON -> {
                                val idx = detailSeasons.indexOfFirst { it.season == selectedSeason.intValue }
                                if (idx > 0) loadSeason(item.id, detailSeasons[idx - 1].season)
                            }
                            DetailFocus.GRID -> { if (episodeFocus.intValue > 0) episodeFocus.intValue-- }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (detailFocus.value) {
                            DetailFocus.HERO -> { val max = if (item.type == "MOVIE") 1 else 0; if (heroButtonFocus.intValue < max) heroButtonFocus.intValue++ }
                            DetailFocus.SEASON -> {
                                val idx = detailSeasons.indexOfFirst { it.season == selectedSeason.intValue }
                                if (idx < detailSeasons.size - 1) loadSeason(item.id, detailSeasons[idx + 1].season)
                            }
                            DetailFocus.GRID -> { if (episodeFocus.intValue < maxIdx) episodeFocus.intValue++ }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        when (detailFocus.value) {
                            DetailFocus.HERO -> {
                                when (heroButtonFocus.intValue) {
                                    0 -> playItem()
                                    1 -> if (item.type == "MOVIE") startDownload()
                                }
                            }
                            DetailFocus.SEASON -> {} // left/right already switches seasons
                            DetailFocus.GRID -> {
                                val card = detailCards.getOrNull(episodeFocus.intValue)
                                if (card != null) { releasePreviewPlayer(); launchPlayerByEpisode(item, card.episode) }
                            }
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
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }

    private fun openItemDetail(item: JanusApi.LibraryItem) {
        selectedLibItem.value = item
        episodeFocus.intValue = 0
        detailFocus.value = DetailFocus.HERO
        heroButtonFocus.intValue = 0
        detailEpisodes.clear()
        detailCards.clear()
        detailSeasons.clear()
        detailSynopsis.value = ""
        seasonCache.clear()
        thumbCache.clear()
        detailLoading.value = true
        AppNavigator.navigate(this, AppNavigator.Action.OPEN_ITEM)
        screen.value = Screen.ITEM_DETAIL

        // Wave 1: fetch hero blob
        Thread {
            try {
                val hero = api.fetchHeroBlob(item.id)
                if (hero == null) {
                    runOnUiThread { detailLoading.value = false }
                    return@Thread
                }
                if (hero.type == "MOVIE") {
                    runOnUiThread {
                        if (hero.episode != null) detailEpisodes.add(hero.episode)
                        detailLoading.value = false
                        refreshItemDetail()
                        previewRequested.value = true
                    }
                    return@Thread
                }
                val firstSeason = hero.seasons.firstOrNull()?.season ?: 1
                selectedSeason.intValue = firstSeason
                // Render wave 1, then kick off wave 2
                runOnUiThread {
                    detailSeasons.addAll(hero.seasons)
                    val lang = Lang.current.value
                    detailSynopsis.value = hero.locales[lang]?.synopsis?.takeIf { it.isNotEmpty() }
                        ?: hero.locales["en"]?.synopsis ?: ""
                    detailLoading.value = false
                    refreshItemDetail()
                    fetchWave2(item.id, firstSeason, hero.seasons)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detail fetch failed: ${e.message}")
                val cached = DownloadManager.items.filter {
                    it.seriesId == item.id && it.state == DownloadManager.State.COMPLETED
                }.sortedBy { it.episodeNum }
                runOnUiThread {
                    if (cached.isNotEmpty()) {
                        detailEpisodes.addAll(cached.map { dl ->
                            JanusApi.Episode(
                                season = dl.season, episode = dl.episodeNum,
                                filename = dl.videoFilename, durationSec = dl.durationSec,
                                watchProgressSec = 0.0, completed = false,
                                titleEn = dl.titleEn, synopsisEn = "", synopsisJa = "", synopsisFr = "", thumb = null,
                                subtitles = dl.srtFiles.map { (name, _) ->
                                    val lang = when {
                                        name.contains("_ja") -> "ja"
                                        name.contains("_en") -> "en"
                                        name.contains("_fr") -> "fr"
                                        else -> "unknown"
                                    }
                                    JanusApi.SubTrack(lang, lang.uppercase(), name)
                                }
                            )
                        })
                    }
                    detailLoading.value = false
                }
            }
        }.start()
    }

    private fun fetchWave2(itemId: String, seasonNum: Int, allSeasons: List<JanusApi.SeasonInfo>) {
        Thread {
            val cards = api.fetchSeasonCards(itemId, seasonNum)
            if (cards != null) {
                runOnUiThread {
                    detailCards.clear()
                    detailCards.addAll(cards.episodes)
                    fetchWave3(itemId, seasonNum, allSeasons)
                }
            }
        }.start()
    }

    private fun fetchWave3(itemId: String, seasonNum: Int, allSeasons: List<JanusApi.SeasonInfo>) {
        Thread {
            val thumbs = api.fetchThumbsBlob(itemId, seasonNum)
            val bitmaps = mutableMapOf<Int, android.graphics.Bitmap>()
            for (t in thumbs) {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(t.data, 0, t.data.size)
                if (bmp != null) bitmaps[t.episode] = bmp
            }
            runOnUiThread {
                thumbCache.putAll(bitmaps)
                previewRequested.value = true
            }
        }.start()
    }

    private fun swapEpisodes(episodes: List<JanusApi.Episode>) {
        detailEpisodes.clear()
        detailEpisodes.addAll(episodes)
    }

    private fun loadSeason(seriesId: String, seasonNum: Int) {
        selectedSeason.intValue = seasonNum
        episodeFocus.intValue = 0
        detailCards.clear()
        thumbCache.clear()
        Thread {
            val cards = api.fetchSeasonCards(seriesId, seasonNum)
            if (cards != null) {
                runOnUiThread {
                    detailCards.addAll(cards.episodes)
                    window.decorView.post {
                        Thread {
                            val thumbs = api.fetchThumbsBlob(seriesId, seasonNum)
                            val bitmaps = mutableMapOf<Int, android.graphics.Bitmap>()
                            for (t in thumbs) {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(t.data, 0, t.data.size)
                                if (bmp != null) bitmaps[t.episode] = bmp
                            }
                            if (bitmaps.isNotEmpty()) {
                                runOnUiThread { thumbCache.putAll(bitmaps) }
                            }
                        }.start()
                    }
                }
            }
        }.start()
    }

    private fun refreshItemDetail() {
        releasePreviewPlayer()
    }

    private fun closeItemDetail() {
        releasePreviewPlayer()
        AppNavigator.navigate(this, AppNavigator.Action.CLOSE_ITEM)
        screen.value = Screen.MAIN
    }

    private fun playItem() {
        releasePreviewPlayer()
        val item = selectedLibItem.value ?: return
        val season = selectedSeason.intValue.let { if (it > 0) it else 1 }
        val lastWatched = getLastWatched(item.id)
        if (lastWatched != null) {
            launchPlayerByEpisode(item, lastWatched.first)
            return
        }
        val firstEp = detailCards.firstOrNull()?.episode
            ?: detailEpisodes.firstOrNull()?.episode ?: return
        launchPlayerByEpisode(item, firstEp)
    }

    private fun launchPlayer(item: JanusApi.LibraryItem, episode: JanusApi.Episode) {
        releasePreviewPlayer()
        val localVideo = DownloadManager.getLocalVideoPath(item.id, episode.filename)
        val videoUrl = localVideo ?: api.streamUrl(item.id, episode.season, episode.episode)
        val jaSub = episode.subtitles.firstOrNull { it.language == "ja" }
        val localSubs = jaSub?.let { DownloadManager.getLocalSubsPath(item.id, it.srtFile) }
        val subsUrl = localSubs ?: jaSub?.let { api.streamSubsUrl(item.id, episode.season, episode.episode, "ja") }
        val savedPos = getWatchProgress(item.id, episode.episode)
        val title = if (item.type == "MOVIE") item.title() else "${item.title()} - ${Lang.s("episode", episode.episode)}"

        val allSubs = episode.subtitles.map { sub ->
            val langIdx = if (episode.subtitles.count { it.language == sub.language } > 1)
                "/${episode.subtitles.filter { it.language == sub.language }.indexOf(sub) + 1}" else ""
            sub.srtFile + "|" + api.streamSubsUrl(item.id, episode.season, episode.episode, sub.language + langIdx)
        }

        AppNavigator.navigate(this, AppNavigator.Action.PLAY_VIDEO) { intent ->
            intent.putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsUrl)
            intent.putExtra(ExoPlayerActivity.EXTRA_TITLE, title)
            intent.putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            intent.putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, item.id)
            intent.putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, episode.episode)
            intent.putStringArrayListExtra("all_subs", ArrayList(allSubs))
        }
    }

    private fun playContinueItem(item: ContinueItem) {
        selectedSeason.intValue = item.season
        launchPlayerByEpisode(item.libItem, item.episodeNum)
    }

    private fun playDownloadedItem(item: DownloadManager.DownloadItem) {
        val localPath = DownloadManager.getLocalVideoPath(item.seriesId, item.videoFilename)
        if (localPath == null) {
            Log.e(TAG, "Local video not found: ${item.seriesId}/${item.videoFilename}")
            return
        }
        val jaSrt = item.srtFiles.firstOrNull { it.first.contains("_ja") }?.first
        val subsPath = jaSrt?.let { DownloadManager.getLocalSubsPath(item.seriesId, it) }
        val savedPos = getWatchProgress(item.seriesId, item.episodeNum)
        val title = "${item.seriesTitleEn} - ${Lang.s("episode", item.episodeNum)}"

        val allSubs = item.srtFiles.mapNotNull { (name, _) ->
            val local = DownloadManager.getLocalSubsPath(item.seriesId, name) ?: return@mapNotNull null
            "$name|file://$local"
        }

        val intent = Intent(this, ExoPlayerActivity::class.java).apply {
            putExtra(ExoPlayerActivity.EXTRA_VIDEO_URL, "file://$localPath")
            putExtra(ExoPlayerActivity.EXTRA_SUBS_URL, subsPath?.let { "file://$it" })
            putExtra(ExoPlayerActivity.EXTRA_TITLE, title)
            putExtra(ExoPlayerActivity.EXTRA_START_POSITION, savedPos)
            putExtra(ExoPlayerActivity.EXTRA_SERIES_ID, item.seriesId)
            putExtra(ExoPlayerActivity.EXTRA_EPISODE_NUM, item.episodeNum)
            putStringArrayListExtra("all_subs", ArrayList(allSubs))
        }
        startActivity(intent)
    }

    private fun startDownload() {
        val item = selectedLibItem.value ?: return
        for (ep in detailEpisodes) {
            val srtFiles = ep.subtitles.map { it.srtFile to api.subsUrl(item.id, it.srtFile) }
            val thumbFile = ep.thumb?.substringAfterLast("/") ?: ""
            val thumbUrl = if (ep.thumb != null) "${serverUrl.value}/api/${ep.thumb}" else ""
            DownloadManager.enqueueEpisode(
                item.id, ep.episode, season = ep.season,
                videoFilename = ep.filename,
                videoUrl = api.videoUrl(item.id, ep.filename),
                srtFiles = srtFiles,
                thumbUrl = thumbUrl, thumbFile = thumbFile,
                durationSec = ep.durationSec,
                titleEn = ep.titleEn.ifEmpty { Lang.s("episode", ep.episode) },
                seriesTitleEn = item.titleEn
            )
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

    override fun onPause() {
        super.onPause()
        PlayerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        PlayerManager.release()
    }
}
