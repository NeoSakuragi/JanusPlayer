package com.videoplayer

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class ExoPlayerActivity : ComponentActivity() {

    enum class ReadingMode { PRO, ADVANCED, INTERMEDIATE, NOVICE }
    private val readingMode = mutableStateOf(ReadingMode.PRO)

    private fun loadReadingMode() {
        val saved = getSharedPreferences("player_prefs", MODE_PRIVATE).getInt("reading_mode", 3)
        readingMode.value = when (saved) { 0 -> ReadingMode.NOVICE; 1 -> ReadingMode.INTERMEDIATE; 2 -> ReadingMode.ADVANCED; else -> ReadingMode.PRO }
    }

    private fun saveReadingMode() {
        val value = when (readingMode.value) { ReadingMode.NOVICE -> 0; ReadingMode.INTERMEDIATE -> 1; ReadingMode.ADVANCED -> 2; ReadingMode.PRO -> 3 }
        getSharedPreferences("player_prefs", MODE_PRIVATE).edit().putInt("reading_mode", value).apply()
    }

    companion object {
        private const val TAG = "ExoPlayer"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_SUBS_URL = "subs_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_POSITION = "start_position"
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_EPISODE_NUM = "episode_num"
    }

    // ── State Machine ────────────────────────────────────────────────
    //
    //  Layout top→bottom: BUTTONS | DICT | WORDS | SUBS | SEEKBAR
    //
    //  PLAYING:
    //    LEFT/RIGHT     → prev/next subtitle
    //    UP             → pause + BUTTONS
    //    CENTER/DOWN    → pause + WORD_NAV (if subs) or CONTROLS(SEEK)
    //    BACK           → exit
    //
    //  CONTROLS(focus = SEEK | AUDIO | SUBS | FONTSIZE | FONT):
    //    UP from SEEK   → WORD_NAV (if subs) or BUTTONS
    //    UP from BUTTONS → exit to PLAYING
    //    DOWN from BUTTONS → WORD_NAV (if subs) or SEEK
    //    DOWN from SEEK → nothing
    //    LEFT/RIGHT     → seek on SEEK, move focus on buttons
    //    CENTER on SEEK → resume
    //    CENTER on AUDIO/SUBS → LIST_SELECT
    //    CENTER on FONTSIZE/FONT → cycle
    //    BACK           → resume
    //
    //  WORD_NAV(wordFocus):
    //    LEFT/RIGHT     → move between words
    //    UP             → CONTROLS(BUTTONS)
    //    DOWN           → CONTROLS(SEEK)
    //    CENTER         → (future: Anki card)
    //    BACK           → resume
    //    300ms dwell    → dict lookup
    //
    //  LIST_SELECT(listFocus):
    //    UP/DOWN        → move focus
    //    LEFT/BACK      → CONTROLS
    //    CENTER         → apply + CONTROLS

    enum class Screen { PLAYING, CONTROLS, WORD_NAV, LIST_SELECT, SETTINGS }

    private val CTRL_SEEK = 0
    private val CTRL_SETTINGS = 1

    private val FONT_SIZES = listOf(24, 32, 44)
    private val FONT_KEYS = listOf("noto_sans", "noto_serif", "kosugi_maru", "shippori_mincho")
    private val FONT_NAMES = listOf("Noto Sans", "Noto Serif", "Kosugi", "Shippori")
    private val FONT_ASSETS = listOf(
        "fonts/NotoSansJP-Regular.ttf", "fonts/NotoSerifJP-Regular.ttf",
        "fonts/KosugiMaru-Regular.ttf", "fonts/ShipporiMincho-Regular.ttf"
    )

    // Settings panel
    private val settingsFocus = mutableIntStateOf(0)

    data class SettingsRow(val icon: String, val label: String, val value: String, val indent: Boolean = false, val selected: Boolean = false, val action: () -> Unit)
    private val condensedMode = mutableStateOf(false)
    private val condensedSpeedLabel = mutableStateOf<String?>(null)
    private var lastCondensedSpeed = 1f

    private fun seriesPrefsKey(key: String): String {
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: return key
        return "series_${seriesId}_$key"
    }

    private fun saveSeriesPref(key: String, value: Int) {
        getSharedPreferences("player_prefs", MODE_PRIVATE).edit()
            .putInt(seriesPrefsKey(key), value).apply()
    }

    private fun loadSeriesPref(key: String, default: Int): Int =
        getSharedPreferences("player_prefs", MODE_PRIVATE)
            .getInt(seriesPrefsKey(key), default)

    private val debugEvents = mutableListOf<org.json.JSONObject>()
    private val debugSession = java.util.UUID.randomUUID().toString().take(8)

    private fun debugEvent(event: String, speed: Float = 1f, detail: String = "") {
        if (!condensedMode.value && event != "condensed_off") return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, 0)
        val pos = if (::player.isInitialized) player.currentPosition else 0L
        synchronized(debugEvents) {
            debugEvents.add(org.json.JSONObject().apply {
                put("session", debugSession)
                put("item_id", seriesId)
                put("episode", epNum)
                put("ts_client", System.currentTimeMillis() / 1000.0)
                put("event", event)
                put("position_ms", pos)
                put("target_ms", 0)
                put("speed", speed.toDouble())
                put("detail", detail)
            })
        }
        if (debugEvents.size >= 10) flushDebugEvents()
    }

    private fun flushDebugEvents() {
        val batch: List<org.json.JSONObject>
        synchronized(debugEvents) {
            if (debugEvents.isEmpty()) return
            batch = debugEvents.toList()
            debugEvents.clear()
        }
        val serverUrl = intent.getStringExtra(EXTRA_VIDEO_URL)?.substringBefore("/api/") ?: return
        Thread {
            try {
                val arr = org.json.JSONArray(batch)
                val body = arr.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = okhttp3.Request.Builder().url("$serverUrl/api/debug/events")
                PlayerManager.authToken?.let { req.header("Authorization", "Bearer $it") }
                okhttp3.OkHttpClient().newCall(req.post(body).build()).execute().close()
            } catch (_: Exception) {}
        }.start()
    }


    private val dlLabel = mutableStateOf("↓ DL")

    private val screen = mutableStateOf(Screen.PLAYING)
    private val controlFocus = mutableIntStateOf(CTRL_SEEK)

    // Player state
    private val isPaused = mutableStateOf(false)
    private val positionMs = mutableLongStateOf(0L)
    private val durationMs = mutableLongStateOf(0L)
    private val currentSubText = mutableStateOf<String?>(null)
    private val titleText = mutableStateOf("")

    private val currentRubySpans = mutableStateOf<List<RubySpan>>(emptyList())

    // Word navigation
    private val cursorIdx = mutableIntStateOf(0)
    private val hlStart = mutableIntStateOf(-1)
    private val hlEnd = mutableIntStateOf(-1)
    private var wordNavSubText = ""

    // Subtitle rendering state
    private val deltaFurigana = mutableFloatStateOf(0.9f)  // DF: furigana distance = cueRowHeight * DF
    private val deltaRow = mutableFloatStateOf(1.4f)        // DR: row spacing = cueRowHeight * DR
    private val deltaSpacing = mutableFloatStateOf(-0.5f)   // DS: letter spacing in sp
    private val deltaYShift = mutableFloatStateOf(5f)       // DY: Y shift from seekbar in dp

    // Opening/ending/next episode overlay
    private val showSkipOpening = mutableStateOf(false)
    private val showEndingCountdown = mutableStateOf(false)
    private val endingCountdown = mutableIntStateOf(5)
    private val endingCancelled = mutableStateOf(false)
    private val hasNextEpisode = mutableStateOf(false)
    private var openingMs = 0L
    private var endingMs = 0L

    private var subCharBoxes = emptyArray<androidx.compose.ui.geometry.Rect>()
    private var subTextOffsetX = 0f
    private var subTextOffsetY = 0f
    private var subLineBaselines = listOf<Pair<Float, Float>>()  // (baseline, lineTop) per line

    // Supercharged SRT
    data class WordSpan(val start: Int, val end: Int, val word: String, val dictIdx: Int, val inflection: String,
                        val reading: String = "", val subReadings: List<String> = emptyList(),
                        val furigana: List<JanusApi.FuriganaSpan> = emptyList())
    private var wordSpans = listOf<WordSpan>()
    private var superSRT: JanusApi.SuperSRT? = null
    private var superCues = listOf<JanusApi.SuperCue>()

    // Anki mining
    private lateinit var ankiSync: AnkiSyncManager
    private val minedToast = mutableStateOf<String?>(null)

    // Dictionary
    private val dictTerm = mutableStateOf("")
    private val dictReading = mutableStateOf("")
    private val dictMeanings = mutableStateOf(listOf<String>())
    private val dictFreq = mutableIntStateOf(0)
    private val dictTags = mutableStateOf("")
    private val dictJlpt = mutableStateOf("")
    private val dictFreqs = mutableStateOf(mapOf<String, Int>())
    private val dictVisible = mutableStateOf(false)

    // List select
    data class ListItem(val label: String, val subtitle: String? = null, val selected: Boolean = false)
    private val listItems = mutableStateListOf<ListItem>()
    private val listTitle = mutableStateOf("")
    private val listFocus = mutableIntStateOf(0)
    private var listCallback: ((Int) -> Unit)? = null
    private var listReturnFocus = CTRL_SETTINGS

    // Font
    private val fontSizeIdx = mutableIntStateOf(0)
    private val fontIdx = mutableIntStateOf(0)

    private var pendingVideoUrl: String? = null
    private var pendingStartPos: Long = 0L

    // Backend
    private lateinit var player: ExoPlayer
    private var subtitleCues = listOf<SrtParser.Cue>()  // legacy fallback
    private var currentSuperCue: JanusApi.SuperCue? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppNavigator.onActivityResumed(AppNavigator.Screen.VIDEO_PLAYER)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        loadReadingMode()
        ankiSync = AnkiSyncManager(this)
        val appSettings = AppSettings(this)
        val savedSizeIdx = FONT_SIZES.indexOf(appSettings.fontSize)
        if (savedSizeIdx >= 0) fontSizeIdx.intValue = savedSizeIdx
        val savedFontIdx = FONT_KEYS.indexOf(appSettings.fontKey)
        if (savedFontIdx >= 0) fontIdx.intValue = savedFontIdx

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: run { finish(); return }
        val subsUrl = intent.getStringExtra(EXTRA_SUBS_URL)
        titleText.value = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val startPos = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        openingMs = 0L
        endingMs = 0L

        // Fetch season settings in background
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        val seasonNum = intent.getIntExtra("season_num", 1)
        val episodeNum = intent.getIntExtra(EXTRA_EPISODE_NUM, 1)
        if (seriesId != null) {
            val prefs = getSharedPreferences("janus_settings", MODE_PRIVATE)
            val baseUrl = prefs.getString("server_url", "") ?: ""
            val authToken = prefs.getString("auth_token", null)
            Thread {
                val api = JanusApi(baseUrl).apply { this.token = authToken }
                val settings = api.fetchSeasonSettings(seriesId, seasonNum)
                openingMs = (settings.openingSec * 1000).toLong()
                endingMs = (settings.endingSec * 1000).toLong()
                // Load supercharged SRT
                val data = api.fetchSuperSRT(seriesId, seasonNum, episodeNum)
                if (data != null) {
                    superSRT = data
                    superCues = data.cues
                    Log.d(TAG, "Super-SRT: ${data.dict.size} dict, ${data.cues.size} cues")
                }
            }.start()
        }

        DownloadManager.init(this)

        // Own player — not shared with preview, software decode fallback enabled
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(this, renderersFactory)
        val token = PlayerManager.authToken
        if (token != null) {
            val headers = mapOf("Authorization" to "Bearer $token")
            val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
            builder.setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory))
        }
        player = builder.build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setMediaItem(MediaItem.fromUri(videoUrl))
        player.prepare()
        if (startPos > 0) player.seekTo(startPos)
        player.play()
        startService(android.content.Intent(this, BackgroundPlayService::class.java))
        updateDlLabel()

        // Restore per-series settings when player is ready
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != androidx.media3.common.Player.STATE_READY) return
                player.removeListener(this)

                condensedMode.value = loadSeriesPref("condensed", 0) == 1

                val savedAudio = loadSeriesPref("audio_track", -1)
                if (savedAudio >= 0) selectAudioTrack(savedAudio)

                val savedSub = loadSeriesPref("sub_track", -1)
                val allSubsList = intent.getStringArrayListExtra("all_subs") ?: arrayListOf()
                val subUrlToLoad = when {
                    savedSub == 0 -> null
                    savedSub > 0 && savedSub <= allSubsList.size ->
                        allSubsList[savedSub - 1].split("|", limit = 2).getOrNull(1)
                    else -> subsUrl
                }
                if (subUrlToLoad != null) {
                    Thread {
                        val srt = try {
                            val reqBuilder = okhttp3.Request.Builder().url(subUrlToLoad)
                            PlayerManager.authToken?.let { reqBuilder.header("Authorization", "Bearer $it") }
                            okhttp3.OkHttpClient().newCall(reqBuilder.build()).execute().body?.string()
                        } catch (_: Exception) { null }
                        if (srt != null) {
                            subtitleCues = SrtParser.parse(srt)
                            Log.d(TAG, "Loaded ${subtitleCues.size} cues")
                        }
                    }.start()
                }
            }
        })

        // Position updater + condensed mode + periodic save
        var saveCounter = 0
        handler.post(object : Runnable {
            override fun run() {
                if (::player.isInitialized) {
                    val pos = player.currentPosition

                    // Save progress every ~30s (150 ticks × 200ms)
                    if (player.isPlaying && ++saveCounter >= 150) {
                        saveCounter = 0
                        saveProgress()
                    }
                    positionMs.longValue = pos
                    updateDlLabel()
                    durationMs.longValue = player.duration.coerceAtLeast(0)
                    if (lastCondensedSpeed <= 1f) isPaused.value = !player.isPlaying
                    // Super-SRT cue lookup
                    val sCue = superCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
                    if (sCue != null && sCue != currentSuperCue) {
                        currentSuperCue = sCue
                        val display = StringBuilder()
                        val spans = mutableListOf<WordSpan>()
                        val mode = readingMode.value
                        for (w in sCue.words) {
                            if (w.surface.isBlank() || w.surface == "\n") {
                                if (mode == ReadingMode.PRO) display.append(w.surface)
                                else if (w.surface == "\n") display.append("\n")
                                // Skip original spaces in non-PRO modes — words already have trailing spaces
                                continue
                            }
                            val start = display.length
                            when (mode) {
                                ReadingMode.PRO, ReadingMode.ADVANCED -> display.append(w.surface)
                                ReadingMode.INTERMEDIATE -> {
                                    val hira = kata2hira(w.reading.ifEmpty { w.surface })
                                    display.append(hira)
                                    display.append(" ")
                                }
                                ReadingMode.NOVICE -> {
                                    display.append(kata2romaji(w.reading.ifEmpty { w.surface }))
                                    display.append(" ")
                                }
                            }
                            val hasTrailingSpace = mode == ReadingMode.INTERMEDIATE || mode == ReadingMode.NOVICE
                            val spanEnd = if (hasTrailingSpace && display.length > start + 1) display.length - 1 else display.length
                            spans.add(WordSpan(start, spanEnd, w.surface, w.dictIdx, w.inflection, w.reading, w.subReadings, w.furigana))
                        }
                        currentSubText.value = display.toString().trimEnd()
                        // Generate furigana for Advanced mode using per-kanji spans
                        if (mode == ReadingMode.ADVANCED) {
                            val rubys = mutableListOf<RubySpan>()
                            for (span in spans) {
                                if (span.furigana.isEmpty()) continue
                                if (span.furigana.size == 1 && span.word.length > 1 && span.word.all { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }) {
                                    // Ateji: one reading spans all kanji in the word
                                    rubys.add(RubySpan(span.start, span.word.length, span.furigana[0].reading))
                                } else {
                                    for (f in span.furigana) {
                                        rubys.add(RubySpan(span.start + f.charIdx, 1, f.reading))
                                    }
                                }
                            }
                            currentRubySpans.value = rubys
                        } else {
                            currentRubySpans.value = emptyList()
                        }
                        wordSpans = spans
                    } else if (sCue == null) {
                        // Fallback to legacy SRT
                        val cue = SrtParser.cueAt(subtitleCues, pos)
                        if (cue?.text != null) {
                            val (clean, rubys) = stripFurigana(cue.text)
                            currentSubText.value = clean
                            currentRubySpans.value = rubys
                            wordSpans = emptyList()
                        } else {
                            currentSubText.value = null
                            currentRubySpans.value = emptyList()
                            wordSpans = emptyList()
                        }
                        currentSuperCue = null
                    }

                    // Check for next episode availability
                    hasNextEpisode.value = intent.getStringExtra("next_video_url") != null

                    // Opening skip button
                    showSkipOpening.value = openingMs > 0 && pos < openingMs && pos > 1000

                    // Ending countdown
                    val inEndingZone = endingMs > 0 && player.duration > 0 && pos >= player.duration - endingMs
                    if (inEndingZone && !endingCancelled.value && hasNextEpisode.value) {
                        if (!showEndingCountdown.value) {
                            showEndingCountdown.value = true
                            endingCountdown.intValue = 5
                        }
                        val remaining = ((player.duration - pos) / 1000).toInt().coerceAtLeast(0)
                        endingCountdown.intValue = remaining.coerceAtMost(5)
                        if (remaining <= 0) {
                            showEndingCountdown.value = false
                            playNextEpisode()
                        }
                    } else if (!inEndingZone) {
                        showEndingCountdown.value = false
                        endingCancelled.value = false
                    }

                    // Auto-next at very end (no ending data)
                    if (player.isPlaying && player.duration > 0 && pos >= player.duration - 1000 && endingMs == 0L) {
                        playNextEpisode()
                    }

                    // Condensed: speed up through gaps, skip opening/ending
                    if (condensedMode.value && player.isPlaying && screen.value == Screen.PLAYING) {
                        if (openingMs > 0 && pos < openingMs && pos < 5000) {
                            player.seekTo(openingMs)
                        }
                        if (endingMs > 0 && player.duration > 0 && pos >= player.duration - endingMs) {
                            playNextEpisode()
                        }

                        val midSub = sCue != null
                        val allCues = if (superCues.isNotEmpty()) superCues.map { SrtParser.Cue(0, it.startMs, it.endMs, "") } else subtitleCues
                        val prev = allCues.lastOrNull { it.endMs <= pos }
                        val next = allCues.firstOrNull { it.startMs > pos }
                        val deltaBefore = if (prev != null) pos - prev.endMs else Long.MAX_VALUE
                        val deltaAfter = if (next != null) next.startMs - pos else Long.MAX_VALUE

                        val speed = when {
                            midSub || deltaBefore < 500 -> 1f
                            deltaAfter > 10000          -> 16f
                            deltaAfter > 3000           -> 8f
                            deltaAfter > 900            -> 2f
                            else                        -> 1f
                        }

                        if (speed != lastCondensedSpeed) {
                            player.setPlaybackSpeed(speed)
                            if (speed > 1f) {
                                player.volume = 0f
                                condensedSpeedLabel.value = "▸▸ ${speed.toInt()}x"
                            } else {
                                player.volume = 1f
                                condensedSpeedLabel.value = null
                            }
                            debugEvent(
                                if (speed > lastCondensedSpeed) "ff_start" else if (speed == 1f) "ff_stop" else "ff_decel",
                                speed, "deltaBefore=${deltaBefore}ms,deltaAfter=${deltaAfter}ms"
                            )
                            lastCondensedSpeed = speed
                        }
                    }
                }
                handler.postDelayed(this, if (lastCondensedSpeed > 1f) 50 else 200)
            }
        })

        setContent {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val dimens = computeDimens(config.screenWidthDp.dp)
            androidx.compose.runtime.CompositionLocalProvider(LocalDimens provides dimens) {
                PlayerScreen()
            }
        }
    }

    // ── Compose UI ───────────────────────────────────────────────────

    @Composable
    private fun PlayerScreen() {
        val dimens = LocalDimens.current
        val scr by screen
        val paused by isPaused
        val pos by positionMs
        val dur by durationMs
        val sub by currentSubText
        val cFocus by controlFocus
        val wFocus by cursorIdx
        val lTitle by listTitle
        val lFocus by listFocus
        val dVis by dictVisible
        val dTermV by dictTerm
        val dReadV by dictReading
        val dMeanV by dictMeanings
        val dFreqV by dictFreq
        val dTagsV by dictTags
        val dJlptV by dictJlpt
        val dFreqsV by dictFreqs
        val fSizeIdx by fontSizeIdx
        val fIdx by fontIdx
        val title by titleText

        val subFontSize = FONT_SIZES[fSizeIdx].sp
        val fontAsset = FONT_ASSETS[fIdx]
        val subFontFamily = remember(fontAsset) {
            try { FontFamily(Typeface.createFromAsset(assets, fontAsset)) }
            catch (_: Exception) { FontFamily.Default }
        }

        var subTopY by remember { mutableStateOf(0f) }
        var dictPopupRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // ExoPlayer surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setOnClickListener(null)
                    }
                },
                update = { view ->
                    view.player = this@ExoPlayerActivity.player
                },
                modifier = Modifier.fillMaxSize()
            )

            // Touch overlay: single tap = toggle controls, double tap = seek ±10s
            var seekIndicator by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(seekIndicator) {
                if (seekIndicator != null) { delay(600); seekIndicator = null }
            }
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        var lastTapTime = 0L
                        var lastTapX = 0f
                        detectTapGestures(
                            onTap = { offset ->
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    // Double tap — seek
                                    val halfWidth = size.width / 2
                                    val seekMs = if (lastTapX < halfWidth) -10_000L else 10_000L
                                    val newPos = (player.currentPosition + seekMs).coerceIn(0, player.duration.coerceAtLeast(0))
                                    player.seekTo(newPos)
                                    if (screen.value != Screen.PLAYING) { player.play(); goto(Screen.PLAYING) }
                                    seekIndicator = if (seekMs < 0) "« 10s" else "10s »"
                                    lastTapTime = 0L
                                } else {
                                    // Single tap — immediate
                                    lastTapTime = now
                                    lastTapX = offset.x
                                    val tapOnSub = subTopY > 0 && offset.y >= subTopY && wordSpans.isNotEmpty() && currentSubText.value != null
                                    when (screen.value) {
                                        Screen.PLAYING -> {
                                            player.pause()
                                            if (tapOnSub) {
                                                onSubtitleTapAt(offset)
                                            } else {
                                                if (!enterWordNav()) goto(Screen.CONTROLS, CTRL_SEEK)
                                            }
                                        }
                                        Screen.WORD_NAV -> {
                                            val inPopup = dictPopupRect?.contains(offset) == true
                                            if (inPopup) {
                                                // tap inside dictionary popup — ignore
                                            } else if (tapOnSub) {
                                                onSubtitleTapAt(offset)
                                            } else {
                                                clearDict(); player.play(); goto(Screen.PLAYING)
                                            }
                                        }
                                        Screen.CONTROLS -> {
                                            clearDict(); player.play(); goto(Screen.PLAYING)
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = seekIndicator != null,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(300))
                ) {
                    androidx.compose.material3.Text(
                        seekIndicator ?: "",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                val speedLabel by condensedSpeedLabel
                AnimatedVisibility(
                    visible = speedLabel != null,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(200))
                ) {
                    androidx.compose.material3.Text(
                        speedLabel ?: "",
                        color = Color(0xCCFFFFFF),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                val mined by minedToast
                AnimatedVisibility(
                    visible = mined != null,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(400))
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC1B5E20), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        androidx.compose.material3.Text(
                            mined ?: "",
                            color = Color(0xFF81C784),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Skip Opening button — left side, above subs
            val skipOpening by showSkipOpening
            AnimatedVisibility(
                visible = skipOpening && scr == Screen.PLAYING,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(bottom = dimens.subBottomPadding + 80.dp, start = dimens.rowPadding)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC222222), RoundedCornerShape(8.dp))
                        .clickable { player.seekTo(intent.getLongExtra("opening_ms_end", 0L).let { if (it > 0) it else openingMs }); showSkipOpening.value = false }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    androidx.compose.material3.Text("Skip Opening ▶", color = Color.White, fontSize = 14.sp)
                }
            }

            // Next Episode countdown — right side, above subs (Netflix-style)
            val showCountdown by showEndingCountdown
            val countdown by endingCountdown
            val hasNext by hasNextEpisode
            AnimatedVisibility(
                visible = showCountdown && scr == Screen.PLAYING,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(bottom = dimens.subBottomPadding + 80.dp, end = dimens.rowPadding)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFBB86FC), RoundedCornerShape(8.dp))
                            .clickable { showEndingCountdown.value = false; playNextEpisode() }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.Text("Next episode in $countdown", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0x88444444), RoundedCornerShape(8.dp))
                            .clickable { endingCancelled.value = true; showEndingCountdown.value = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.Text("Cancel", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                    }
                }
            }

            // Pause overlay: subtitle nav (touch only, left side)
            val hasTouchScreen = remember { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) }
            AnimatedVisibility(
                visible = (scr == Screen.CONTROLS || scr == Screen.WORD_NAV) && hasTouchScreen,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(bottom = dimens.subBottomPadding + 80.dp, start = dimens.rowPadding)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC222222), RoundedCornerShape(8.dp))
                            .clickable {
                                val p = player.currentPosition
                                val prev = superCues.lastOrNull { it.startMs < p - 300 }
                                if (prev != null) player.seekTo(prev.startMs) else player.seekTo((p - 10000).coerceAtLeast(0))
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.Text("⏮", color = Color.White, fontSize = 18.sp)
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC222222), RoundedCornerShape(8.dp))
                            .clickable {
                                val p = player.currentPosition
                                val next = superCues.firstOrNull { it.startMs > p }
                                if (next != null) player.seekTo(next.startMs) else player.seekTo(p + 10000)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.Text("⏭", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
            if (hasNext) {
                AnimatedVisibility(
                    visible = scr == Screen.CONTROLS || scr == Screen.WORD_NAV,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(bottom = dimens.subBottomPadding + 80.dp, end = dimens.rowPadding)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC222222), RoundedCornerShape(8.dp))
                            .clickable { playNextEpisode() }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.material3.Text("Next ⏭", color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // Top: buttons row
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV || scr == Screen.SETTINGS,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp, end = 24.dp)
                ) {
                    CtrlBtn("⚙", Lang.s("settings"), CTRL_SETTINGS, cFocus) { showSettings() }
                }
            }

            // Title + back button
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV || scr == Screen.SETTINGS,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp, start = 24.dp)
                ) {
                    CtrlBtn("←", Lang.s("back_label"), -1, cFocus) { saveProgress(); finish() }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Text(title, color = Color.White, fontSize = 14.sp)
                }
            }

            // Dictionary popup
            AnimatedVisibility(
                visible = dVis && (scr == Screen.WORD_NAV),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = 24.dp, end = 24.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            val x = (constraints.maxWidth - placeable.width) / 2
                            val y = (subTopY - placeable.height - 16.dp.toPx()).toInt().coerceAtLeast(0)
                            placeable.placeRelative(x, y)
                        }
                    }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xEE1E1E2E), RoundedCornerShape(10.dp))
                        .padding(16.dp)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            val size = coords.size
                            dictPopupRect = androidx.compose.ui.geometry.Rect(
                                pos.x, pos.y, pos.x + size.width, pos.y + size.height
                            )
                        }
                ) {
                    // Reading
                    if (dReadV.isNotEmpty()) {
                        androidx.compose.material3.Text(dReadV, color = Color(0xFFAAAAAA), fontSize = 14.sp, fontFamily = subFontFamily)
                    }
                    // Term
                    androidx.compose.material3.Text(dTermV, color = Color.White, fontSize = dimens.dictTermSize, fontWeight = FontWeight.Bold, fontFamily = subFontFamily)
                    Spacer(Modifier.height(6.dp))
                    // Badges: tag + JLPT + frequencies
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (dTagsV.isNotBlank()) {
                            androidx.compose.material3.Text(
                                dTagsV, color = Color(0xFF7986CB), fontSize = 10.sp,
                                modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        if (dJlptV.isNotEmpty()) {
                            androidx.compose.material3.Text(
                                dJlptV, color = Color(0xFF4FC3F7), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                        for ((source, rank) in dFreqsV) {
                            androidx.compose.material3.Text(
                                "$source #$rank", color = Color(0xFF81C784), fontSize = 9.sp,
                                modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Meanings
                    dMeanV.forEachIndexed { i, m ->
                        androidx.compose.material3.Text("${i + 1}. $m", color = Color(0xFFCCCCCC), fontSize = 14.sp, lineHeight = 18.sp)
                    }
                    // Mine button (touch)
                    if (hasTouchScreen) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                                .clickable { mineCurrentWord() }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            androidx.compose.material3.Text("Mine", color = Color(0xFF81C784), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Subtitle — Canvas-based, bottom-up rendering
            if (sub != null && sub!!.isNotBlank()) {
                val hS by hlStart
                val hE by hlEnd
                val subText = sub!!
                val subLines = subText.split("\n")
                val rubyFontSize = subFontSize * 0.45f
                val rubyList by currentRubySpans
                val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
                val df = deltaFurigana.floatValue
                val dr = deltaRow.floatValue
                val subTextColor = JanusTheme.subtitleText
                val subShadowColor = JanusTheme.subtitleShadow
                val shadeBgColor = JanusTheme.subtitleBg
                val rubyColor = JanusTheme.subtitleRuby
                val hlColor = JanusTheme.subtitleHighlight
                val subStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = subFontSize, fontFamily = subFontFamily, color = subTextColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    letterSpacing = deltaSpacing.floatValue.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(color = subShadowColor, blurRadius = 8f)
                )
                val rubyStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = rubyFontSize, fontFamily = subFontFamily, color = rubyColor
                )

                // Pre-measure all lines
                val measured = subLines.map { line -> textMeasurer.measure(line, style = subStyle) }
                val cueRowHeight = measured.maxOfOrNull { it.size.height.toFloat() } ?: 0f

                // Calculate total height needed
                val numLines = subLines.size
                val totalHeight = cueRowHeight * numLines + cueRowHeight * (dr - 1f) * (numLines - 1).coerceAtLeast(0) +
                    (if (rubyList.isNotEmpty()) cueRowHeight * df else 0f)

                // Background + Canvas
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = dimens.subBottomPadding + deltaYShift.floatValue.dp, start = dimens.rowPadding, end = dimens.rowPadding)
                        .onGloballyPositioned { coords ->
                            subTopY = coords.positionInParent().y
                            val rootPos = coords.positionInRoot()
                            subTextOffsetX = rootPos.x
                            subTextOffsetY = rootPos.y
                        }
                ) {
                    // Single Canvas: shade + text + furigana
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(androidx.compose.ui.platform.LocalDensity.current) { totalHeight.toDp() + 16.dp })
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val padH = 12f * density
                        val padV = 8f * density

                        // Compute shade bounds
                        val lastLineY = canvasHeight - padV - cueRowHeight
                        val firstLineY = canvasHeight - padV - cueRowHeight - (numLines - 1) * cueRowHeight * dr
                        // Compute actual topmost furigana Y for line 0
                        val firstLineRubys = rubyList.filter { it.start < (subLines.firstOrNull()?.length ?: 0) }
                        var topFuriganaY = firstLineY
                        if (firstLineRubys.isNotEmpty()) {
                            val sampleRm = textMeasurer.measure("あ", style = androidx.compose.ui.text.TextStyle(fontSize = rubyFontSize, fontFamily = subFontFamily))
                            topFuriganaY = firstLineY - cueRowHeight * df + (cueRowHeight - sampleRm.firstBaseline)
                        }
                        val shadeTop = topFuriganaY
                        val shadeBottom = lastLineY + cueRowHeight + padV
                        val shadePad = padH
                        drawRoundRect(
                            shadeBgColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, shadeTop),
                            size = androidx.compose.ui.geometry.Size(canvasWidth, shadeBottom - shadeTop),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * density)
                        )

                        // Build char boxes for tap detection
                        val allBoxes = mutableListOf<androidx.compose.ui.geometry.Rect>()

                        // Iterate lines forward, compute Y bottom-up
                        for (lineIdx in subLines.indices) {
                            val lineText = subLines[lineIdx]
                            val lineMeasured = measured[lineIdx]
                            val linesFromBottom = numLines - 1 - lineIdx

                            // Line Y: last line at bottom, previous lines above
                            val lineY = canvasHeight - padV - cueRowHeight - linesFromBottom * cueRowHeight * dr

                            val lineX = (canvasWidth - lineMeasured.size.width) / 2f
                            val lineStart = subLines.take(lineIdx).sumOf { it.length + 1 }

                            // Draw text with highlight
                            if (hS >= 0 && hE > hS) {
                                val hlLocalStart = (hS - lineStart).coerceIn(0, lineText.length)
                                val hlLocalEnd = (hE - lineStart).coerceIn(0, lineText.length)
                                if (hlLocalStart < hlLocalEnd) {
                                    val hlLeft = lineMeasured.getBoundingBox(hlLocalStart.coerceAtMost(lineText.length - 1)).left
                                    val hlRight = lineMeasured.getBoundingBox((hlLocalEnd - 1).coerceAtMost(lineText.length - 1)).right
                                    drawRect(
                                        hlColor,
                                        topLeft = androidx.compose.ui.geometry.Offset(lineX + hlLeft, lineY),
                                        size = androidx.compose.ui.geometry.Size(hlRight - hlLeft, cueRowHeight)
                                    )
                                }
                            }

                            // Draw text
                            drawContext.canvas.save()
                            drawContext.canvas.translate(lineX, lineY)
                            lineMeasured.multiParagraph.paint(drawContext.canvas)
                            drawContext.canvas.restore()

                            // Store char boxes in screen space for tap detection
                            for (ci in lineText.indices) {
                                val box = lineMeasured.getBoundingBox(ci)
                                allBoxes.add(androidx.compose.ui.geometry.Rect(
                                    box.left + lineX, box.top + lineY,
                                    box.right + lineX, box.bottom + lineY
                                ))
                            }
                            if (lineIdx < numLines - 1) allBoxes.add(androidx.compose.ui.geometry.Rect.Zero) // placeholder for \n

                            // Draw furigana for this line
                            val lineRubys = rubyList.filter { it.start >= lineStart && it.start < lineStart + lineText.length }
                            for (ruby in lineRubys) {
                                val ls = ruby.start - lineStart
                                val le = ls + ruby.length - 1
                                if (ls < 0 || le >= lineText.length) continue
                                val left = lineMeasured.getBoundingBox(ls).left
                                val right = lineMeasured.getBoundingBox(le).right
                                val kanjiWidth = right - left
                                val rm = textMeasurer.measure(ruby.reading, style = rubyStyle)
                                val rx = lineX + left + (kanjiWidth - rm.size.width) / 2f
                                val ry = lineY - cueRowHeight * df + (cueRowHeight - rm.firstBaseline)
                                drawContext.canvas.save()
                                drawContext.canvas.translate(rx, ry)
                                rm.multiParagraph.paint(drawContext.canvas)
                                drawContext.canvas.restore()
                            }

                        }

                        subCharBoxes = allBoxes.toTypedArray()
                    }
                }
            }

            // Seekbar
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV || scr == Screen.SETTINGS,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val progress = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                val seekFocused = cFocus == CTRL_SEEK && scr == Screen.CONTROLS
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                        .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        androidx.compose.material3.Text(fmtTime(pos), color = Color.White, fontSize = 13.sp)
                        androidx.compose.material3.Text(fmtTime(dur), color = Color(0xFFAAAAAA), fontSize = 13.sp)
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent().changes.firstOrNull() ?: continue
                                        if (!down.pressed) continue
                                        val wasPlaying = player.isPlaying
                                        player.pause()
                                        controlFocus.intValue = CTRL_SEEK
                                        if (screen.value == Screen.PLAYING) goto(Screen.CONTROLS, CTRL_SEEK)
                                        fun seekToX(x: Float) {
                                            val fraction = (x / size.width).coerceIn(0f, 1f)
                                            player.seekTo((fraction * durationMs.longValue).toLong())
                                        }
                                        seekToX(down.position.x)
                                        down.consume()
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) { change.consume(); break }
                                            seekToX(change.position.x)
                                            change.consume()
                                        }
                                        if (wasPlaying) { player.play(); goto(Screen.PLAYING) }
                                    }
                                }
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF444444))
                        ) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(progress)
                                    .background(Color(0xFFBB86FC), RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }
            }

            // List select overlay
            AnimatedVisibility(
                visible = scr == Screen.LIST_SELECT,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(150)) + fadeOut(tween(150)),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x44000000)).clickable { goto(Screen.CONTROLS, listReturnFocus) }) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).width(IntrinsicSize.Max).widthIn(min = 160.dp, max = dimens.listPanelWidth).fillMaxHeight()
                            .background(Color(0xFF1A1A2E)).padding(vertical = 12.dp)
                    ) {
                        androidx.compose.material3.Text(
                            lTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        val scrollState = rememberScrollState()
                        LaunchedEffect(lFocus) { scrollState.animateScrollTo((lFocus * 44).coerceAtLeast(0)) }
                        Column(Modifier.verticalScroll(scrollState)) {
                            listItems.forEachIndexed { idx, item ->
                                val focused = idx == lFocus
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
                                        .clickable {
                                            listFocus.intValue = idx
                                            listCallback?.invoke(idx)
                                            player.play(); goto(Screen.PLAYING)
                                        }
                                        .background(
                                            when { focused -> Color(0xFFBB86FC); item.selected -> Color(0xFF2A2A4A); else -> Color.Transparent },
                                            RoundedCornerShape(6.dp)
                                        ).padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    if (item.selected) {
                                        androidx.compose.material3.Text("●", color = Color(0xFF81C784), fontSize = 8.sp)
                                        Spacer(Modifier.width(8.dp))
                                    } else Spacer(Modifier.width(16.dp))
                                    Column {
                                        androidx.compose.material3.Text(item.label, color = if (focused) Color.White else Color(0xFFEEEEEE), fontSize = 14.sp, fontWeight = if (item.selected || focused) FontWeight.SemiBold else FontWeight.Normal)
                                        item.subtitle?.let { androidx.compose.material3.Text(it, color = Color(0xFFAAAAAA), fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Settings panel
            AnimatedVisibility(
                visible = scr == Screen.SETTINGS,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(150)) + fadeOut(tween(150)),
            ) {
                val sFocus by settingsFocus
                Box(Modifier.fillMaxSize().background(Color(0x44000000)).clickable { goto(Screen.CONTROLS, CTRL_SETTINGS) }) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).widthIn(min = 200.dp, max = dimens.listPanelWidth).fillMaxHeight()
                            .background(Color(0xFF1A1A2E))
                            .clickable { /* consume taps on panel — don't dismiss */ }
                            .padding(vertical = 12.dp)
                    ) {
                        androidx.compose.material3.Text(
                            Lang.s("settings"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        val rows = buildSettingsRows()
                        val scrollState = rememberScrollState()
                        LaunchedEffect(sFocus) { scrollState.animateScrollTo((sFocus * 48).coerceAtLeast(0)) }
                        Column(Modifier.verticalScroll(scrollState)) {
                            rows.forEachIndexed { idx, row ->
                                val focused = idx == sFocus
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
                                        .clickable { settingsFocus.intValue = idx; row.action() }
                                        .background(
                                            when { focused -> Color(0xFFBB86FC); row.selected -> Color(0xFF2A2A4A); else -> Color.Transparent },
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(start = if (row.indent) 42.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
                                ) {
                                    if (!row.indent) {
                                        androidx.compose.material3.Text(row.icon, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                                    }
                                    if (row.selected && row.indent) {
                                        androidx.compose.material3.Text("●", color = Color(0xFF81C784), fontSize = 8.sp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        androidx.compose.material3.Text(row.label, color = if (focused) Color.White else Color(0xFFEEEEEE), fontSize = if (row.indent) 13.sp else 14.sp)
                                    }
                                    if (row.value.isNotEmpty()) {
                                        androidx.compose.material3.Text(row.value, color = Color(0xFF81C784), fontSize = 13.sp)
                                    }
                                }
                                // Slider for furigana Y
                                if (row.icon == "DF") {
                                    var sliderVal by remember { mutableFloatStateOf(deltaFurigana.floatValue) }
                                    androidx.compose.material3.Slider(
                                        value = sliderVal,
                                        onValueChange = { sliderVal = it; deltaFurigana.floatValue = it },
                                        valueRange = 0.5f..1.5f,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                                    )
                                }
                                if (row.icon == "DR") {
                                    var sliderVal by remember { mutableFloatStateOf(deltaRow.floatValue) }
                                    androidx.compose.material3.Slider(
                                        value = sliderVal,
                                        onValueChange = { sliderVal = it; deltaRow.floatValue = it },
                                        valueRange = 1.0f..3.0f,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                                    )
                                }
                                if (row.icon == "DS") {
                                    var sliderVal by remember { mutableFloatStateOf(deltaSpacing.floatValue) }
                                    androidx.compose.material3.Slider(
                                        value = sliderVal,
                                        onValueChange = { sliderVal = it; deltaSpacing.floatValue = it },
                                        valueRange = -4f..8f,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                                    )
                                }
                                if (row.icon == "DY") {
                                    var sliderVal by remember { mutableFloatStateOf(deltaYShift.floatValue) }
                                    androidx.compose.material3.Slider(
                                        value = sliderVal,
                                        onValueChange = { sliderVal = it; deltaYShift.floatValue = it },
                                        valueRange = -50f..50f,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildSettingsRows(): List<SettingsRow> {
        val rows = mutableListOf<SettingsRow>()

        // Audio tracks
        rows.add(SettingsRow("♪", Lang.s("audio"), "", action = {}))
        if (::player.isInitialized) {
            var trackIdx = 0
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language?.uppercase() ?: "Track ${trackIdx + 1}"
                    val selected = group.isTrackSelected(i)
                    val idx = trackIdx
                    rows.add(SettingsRow("", label, "", indent = true, selected = selected) { selectAudioTrack(idx); saveSeriesPref("audio_track", idx) })
                    trackIdx++
                }
            }
        }

        // Subtitle tracks
        rows.add(SettingsRow("CC", Lang.s("subs"), "", action = {}))
        rows.add(SettingsRow("", "Off", "", indent = true, selected = subtitleCues.isEmpty() && superCues.isEmpty()) {
            subtitleCues = emptyList(); superCues = emptyList(); superSRT = null
            currentSubText.value = null; currentSuperCue = null
        })
        val allSubs = intent.getStringArrayListExtra("all_subs") ?: arrayListOf()
        allSubs.forEachIndexed { i, entry ->
            val parts = entry.split("|", limit = 2)
            val name = parts[0]
            val url = if (parts.size == 2) parts[1] else ""
            val label = when {
                name.contains("_ja") -> "Japanese"
                name.contains("_en") -> "English"
                name.contains("_fr") -> "French"
                else -> name
            }
            rows.add(SettingsRow("", label, "", indent = true, selected = false) {
                if (url.isNotEmpty()) {
                    Thread {
                        val srt = try {
                            val reqBuilder = okhttp3.Request.Builder().url(url)
                            PlayerManager.authToken?.let { reqBuilder.header("Authorization", "Bearer $it") }
                            okhttp3.OkHttpClient().newCall(reqBuilder.build()).execute().body?.string()
                        } catch (_: Exception) { null }
                        if (srt != null) { subtitleCues = SrtParser.parse(srt) }
                    }.start()
                }
                saveSeriesPref("sub_track", i + 1)
            })
        }

        // Reading level
        val rmLabel = when (readingMode.value) {
            ReadingMode.NOVICE -> "1 · Novice"
            ReadingMode.INTERMEDIATE -> "2 · Intermediate"
            ReadingMode.ADVANCED -> "3 · Advanced"
            ReadingMode.PRO -> "4 · Pro"
        }
        rows.add(SettingsRow("読", "Reading", rmLabel) {
            setReadingMode(when (readingMode.value) {
                ReadingMode.PRO -> ReadingMode.NOVICE
                ReadingMode.NOVICE -> ReadingMode.INTERMEDIATE
                ReadingMode.INTERMEDIATE -> ReadingMode.ADVANCED
                ReadingMode.ADVANCED -> ReadingMode.PRO
            })
        })

        // Font
        rows.add(SettingsRow("F", "Font", FONT_NAMES[fontIdx.intValue].take(8)) { cycleFont() })

        // Font size
        rows.add(SettingsRow("Aa", "Size", "${FONT_SIZES[fontSizeIdx.intValue]}sp") { cycleFontSize() })

        // Background play
        val bgLabel = if (backgroundPlay.value) "ON" else "OFF"
        rows.add(SettingsRow("🎧", "Background play", bgLabel) {
            backgroundPlay.value = !backgroundPlay.value
            if (backgroundPlay.value) {
                startService(android.content.Intent(this@ExoPlayerActivity, BackgroundPlayService::class.java))
            } else {
                stopService(android.content.Intent(this@ExoPlayerActivity, BackgroundPlayService::class.java))
            }
        })

        // Condensed
        rows.add(SettingsRow("⏩", "Condensed", if (condensedMode.value) "ON" else "OFF") { toggleCondensed() })

        // DF: furigana gap
        rows.add(SettingsRow("DF", "Furigana gap", "%.1f".format(deltaFurigana.floatValue)) {})
        // DR: row spacing
        rows.add(SettingsRow("DR", "Row spacing", "%.1f".format(deltaRow.floatValue)) {})
        // DS: letter spacing
        rows.add(SettingsRow("DS", "Letter spacing", "%.1f".format(deltaSpacing.floatValue)) {})
        // DY: Y shift
        rows.add(SettingsRow("DY", "Y offset", "%.0f".format(deltaYShift.floatValue)) {})

        // Download
        rows.add(SettingsRow("↓", "Download", dlLabel.value) { toggleDownload() })

        return rows
    }

    private fun showSettings() {
        settingsFocus.intValue = 0
        screen.value = Screen.SETTINGS
    }

    @Composable
    private fun CtrlBtn(icon: String, label: String, index: Int, focusIdx: Int, onTap: () -> Unit) {
        val focused = focusIdx == index && screen.value == Screen.CONTROLS
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(width = LocalDimens.current.ctrlBtnWidth, height = LocalDimens.current.ctrlBtnHeight)
                .clickable { controlFocus.intValue = index; onTap() }
                .background(if (focused) Color(0xFFBB86FC) else Color(0xFF2A2A3A), RoundedCornerShape(12.dp))
                .then(if (focused) Modifier.border(1.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            androidx.compose.material3.Text(icon, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(label, color = if (focused) Color.White else Color(0xFFAAAAAA), fontSize = 7.sp)
        }
    }

    // ── State Machine ────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return transition(screen.value, event.keyCode)
    }

    private fun transition(state: Screen, key: Int): Boolean {
        when (state) {
            Screen.PLAYING -> when (key) {
                KeyEvent.KEYCODE_BACK -> { saveProgress(); finish(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val pos = player.currentPosition
                    val prev = superCues.lastOrNull { it.startMs < pos - 300 }
                        ?: subtitleCues.lastOrNull { it.startMs < pos - 300 }?.let { JanusApi.SuperCue(it.startMs, it.endMs, emptyList()) }
                    if (prev != null) player.seekTo(prev.startMs) else player.seekTo((pos - 10000).coerceAtLeast(0))
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val pos = player.currentPosition
                    val next = superCues.firstOrNull { it.startMs > pos }
                        ?: subtitleCues.firstOrNull { it.startMs > pos }?.let { JanusApi.SuperCue(it.startMs, it.endMs, emptyList()) }
                    if (next != null) player.seekTo(next.startMs) else player.seekTo(pos + 10000)
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (showSkipOpening.value) {
                        player.seekTo(openingMs); showSkipOpening.value = false
                    } else if (showEndingCountdown.value) {
                        showEndingCountdown.value = false; playNextEpisode()
                    } else {
                        player.pause(); goto(Screen.CONTROLS, CTRL_SETTINGS)
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (showEndingCountdown.value) {
                        endingCancelled.value = true; showEndingCountdown.value = false
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play() }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (showSkipOpening.value) {
                        player.seekTo(openingMs); showSkipOpening.value = false
                    } else if (showEndingCountdown.value) {
                        showEndingCountdown.value = false; playNextEpisode()
                    } else {
                        player.pause()
                        if (!enterWordNav()) goto(Screen.CONTROLS, CTRL_SEEK)
                    }
                }
                else -> return false
            }

            Screen.CONTROLS -> {
                val f = controlFocus.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { player.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play() }
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        f == CTRL_SEEK -> if (!enterWordNav()) controlFocus.intValue = CTRL_SETTINGS
                        else -> { player.play(); goto(Screen.PLAYING) }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        f == CTRL_SEEK -> {}
                        else -> if (!enterWordNav()) controlFocus.intValue = CTRL_SEEK
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        f == CTRL_SEEK -> player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        f == CTRL_SEEK -> player.seekTo(player.currentPosition + 10000)
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (f) {
                        CTRL_SEEK -> { player.play(); goto(Screen.PLAYING) }
                        CTRL_SETTINGS -> showSettings()
                    }
                    else -> return false
                }
            }

            Screen.WORD_NAV -> {
                val ci = cursorIdx.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { clearDict(); player.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_DPAD_UP -> { clearDict(); goto(Screen.CONTROLS, CTRL_SETTINGS) }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { clearDict(); goto(Screen.CONTROLS, CTRL_SEEK) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (ci > 0) { cursorIdx.intValue = ci - 1; updateWordAtCursor() }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (ci < wordSpans.size - 1) { cursorIdx.intValue = ci + 1; updateWordAtCursor() }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { mineCurrentWord() }
                    else -> return false
                }
            }

            Screen.LIST_SELECT -> when (key) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { goto(Screen.SETTINGS) }
                KeyEvent.KEYCODE_DPAD_UP -> { listFocus.intValue = (listFocus.intValue - 1).coerceAtLeast(0) }
                KeyEvent.KEYCODE_DPAD_DOWN -> { listFocus.intValue = (listFocus.intValue + 1).coerceAtMost(listItems.size - 1) }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    listCallback?.invoke(listFocus.intValue)
                    goto(Screen.SETTINGS)
                }
                else -> return false
            }

            Screen.SETTINGS -> {
                val rows = buildSettingsRows()
                val focusedIcon = rows.getOrNull(settingsFocus.intValue)?.icon ?: ""
                val isSlider = focusedIcon in listOf("DF", "DR", "DS", "DY")
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { goto(Screen.CONTROLS, CTRL_SETTINGS) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isSlider) {
                            when (focusedIcon) {
                                "DF" -> deltaFurigana.floatValue = (deltaFurigana.floatValue - 0.1f).coerceAtLeast(0.5f)
                                "DR" -> deltaRow.floatValue = (deltaRow.floatValue - 0.1f).coerceAtLeast(1.0f)
                                "DS" -> deltaSpacing.floatValue = (deltaSpacing.floatValue - 0.5f).coerceAtLeast(-4f)
                                "DY" -> deltaYShift.floatValue = (deltaYShift.floatValue - 5f).coerceAtLeast(-50f)
                            }
                        } else { goto(Screen.CONTROLS, CTRL_SETTINGS) }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isSlider) {
                            when (focusedIcon) {
                                "DF" -> deltaFurigana.floatValue = (deltaFurigana.floatValue + 0.1f).coerceAtMost(1.5f)
                                "DR" -> deltaRow.floatValue = (deltaRow.floatValue + 0.1f).coerceAtMost(3.0f)
                                "DS" -> deltaSpacing.floatValue = (deltaSpacing.floatValue + 0.5f).coerceAtMost(8f)
                                "DY" -> deltaYShift.floatValue = (deltaYShift.floatValue + 5f).coerceAtMost(50f)
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> { settingsFocus.intValue = (settingsFocus.intValue - 1).coerceAtLeast(0) }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { settingsFocus.intValue = (settingsFocus.intValue + 1).coerceAtMost(rows.size - 1) }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { rows.getOrNull(settingsFocus.intValue)?.action?.invoke() }
                    else -> return false
                }
            }
        }
        return true
    }

    private fun goto(s: Screen, focus: Int = -1) {
        if (lastCondensedSpeed > 1f && s != Screen.PLAYING) {
            player.setPlaybackSpeed(1f)
            player.volume = 1f
            lastCondensedSpeed = 1f
            condensedSpeedLabel.value = null
        }
        if (s == Screen.PLAYING) {
            hlStart.intValue = -1
            hlEnd.intValue = -1
        }
        screen.value = s
        if (focus >= 0) controlFocus.intValue = focus
    }

    // ── Reading mode converters ────────────────────────────────────

    private fun kata2hira(s: String): String = buildString {
        for (c in s) {
            if (c in 'ァ'..'ヶ') append(c - 0x60)
            else append(c)
        }
    }

    private val romajiMap = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヲ' to "wo", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "di", 'ヅ' to "du", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ッ' to "q", 'ー' to "-",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
    )

    private fun kata2romaji(s: String): String = buildString {
        val chars = s.toList()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            // Small tsu: double the next consonant
            if (c == 'ッ' || c == 'っ') {
                if (i + 1 < chars.size) {
                    val next = romajiMap[chars[i + 1]]
                    if (next != null && next.isNotEmpty()) append(next[0]) else append("t")
                }
                i++
                continue
            }
            // Small kana combos: キャ→kya, シュ→shu, チョ→cho, etc.
            if (i + 1 < chars.size && chars[i + 1] in "ャュョァィゥェォ") {
                val base = romajiMap[c]
                val mod = romajiMap[chars[i + 1]]
                if (base != null && mod != null) {
                    append(base.dropLast(1))
                    append(mod)
                    i += 2
                    continue
                }
            }
            val r = romajiMap[c]
            if (r != null) append(r) else append(c)
            i++
        }
    }

    private fun setReadingMode(mode: ReadingMode) {
        val wasInWordNav = screen.value == Screen.WORD_NAV
        val savedCursorIdx = cursorIdx.intValue
        readingMode.value = mode
        saveReadingMode()
        currentSuperCue = null
        // Restore highlight after re-render
        if (wasInWordNav) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (wordSpans.isNotEmpty()) {
                    cursorIdx.intValue = savedCursorIdx.coerceIn(0, wordSpans.size - 1)
                    val span = wordSpans[cursorIdx.intValue]
                    hlStart.intValue = span.start
                    hlEnd.intValue = span.end
                }
            }, 250)
        }
    }

    // ── Word Navigation ──────────────────────────────────────────────

    private fun onSubtitleTapAt(tapOffset: androidx.compose.ui.geometry.Offset) {
        val boxes = subCharBoxes
        if (boxes.isEmpty()) return
        val localX = tapOffset.x - subTextOffsetX
        val localY = tapOffset.y - subTextOffsetY
        val local = androidx.compose.ui.geometry.Offset(localX, localY)
        for (i in boxes.indices) {
            if (boxes[i].contains(local)) {
                onSubtitleTap(i)
                return
            }
        }
    }

    private fun onSubtitleTap(charOffset: Int) {
        val text = currentSubText.value ?: return
        if (wordSpans.isEmpty()) return
        val spanIdx = wordSpans.indexOfFirst { charOffset in it.start until it.end }
            .let { if (it < 0) wordSpans.indices.minByOrNull { i -> kotlin.math.abs(wordSpans[i].start - charOffset) } ?: 0 else it }
        player.pause()
        wordNavSubText = text
        cursorIdx.intValue = spanIdx
        updateWordAtCursor()
        screen.value = Screen.WORD_NAV
    }

    private fun enterWordNav(): Boolean {
        // Force-update cue from current position in case tick hasn't run yet
        if (wordSpans.isEmpty() && superCues.isNotEmpty()) {
            val pos = player.currentPosition
            val sCue = superCues.firstOrNull { pos >= it.startMs && pos < it.endMs }
            if (sCue != null) {
                currentSuperCue = sCue
                val display = StringBuilder()
                val spans = mutableListOf<WordSpan>()
                for (w in sCue.words) {
                    if (w.surface.isBlank() || w.surface == "\n") { display.append(w.surface); continue }
                    val start = display.length
                    display.append(w.surface)
                    spans.add(WordSpan(start, display.length, w.surface, w.dictIdx, w.inflection, w.reading, w.subReadings))
                }
                currentSubText.value = display.toString()
                wordSpans = spans
            }
        }
        val text = currentSubText.value ?: return false
        if (wordSpans.isEmpty()) return false
        wordNavSubText = text
        cursorIdx.intValue = 0
        updateWordAtCursor()
        screen.value = Screen.WORD_NAV
        return true
    }

    private fun updateWordAtCursor() {
        val span = wordSpans.getOrNull(cursorIdx.intValue) ?: return
        hlStart.intValue = span.start
        hlEnd.intValue = span.end

        val dict = superSRT?.dict
        val entry = if (dict != null && span.dictIdx >= 0 && span.dictIdx < dict.size) dict[span.dictIdx] else null

        if (entry != null) {
            dictTerm.value = entry.term
            dictReading.value = if (entry.reading != entry.term) entry.reading else ""
            dictMeanings.value = entry.meanings
            dictTags.value = span.inflection
            dictJlpt.value = entry.jlpt
            dictFreq.intValue = entry.freq
            dictFreqs.value = emptyMap()
            dictVisible.value = true
        } else {
            dictVisible.value = false
        }
    }

    private fun clearDict() {
        dictVisible.value = false
    }

    // ── Anki Mining ──────────────────────────────────────────────────

    private fun mineCurrentWord() {
        val term = dictTerm.value
        if (term.isEmpty() || !dictVisible.value) return

        val sentence = currentSubText.value ?: ""
        val cue = currentSuperCue
        val source = buildString {
            append(intent.getStringExtra(EXTRA_TITLE) ?: "")
            val ep = intent.getIntExtra(EXTRA_EPISODE_NUM, 0)
            if (ep > 0) append(" EP$ep")
            if (cue != null) {
                val min = (cue.startMs / 60000).toInt()
                val sec = ((cue.startMs % 60000) / 1000).toInt()
                append(" ${min}:${"%02d".format(sec)}")
            }
        }

        // Build furigana sentence from SuperSRT data
        val sentenceFurigana = cue?.words?.joinToString("") { w ->
            if (w.reading.isNotEmpty() && w.reading != w.surface && w.surface.any { it.code > 0x3000 })
                "<ruby>${w.surface}<rt>${w.reading}</rt></ruby>"
            else w.surface
        } ?: sentence

        // Screenshot capture (from current video frame)
        var screenshotFile: java.io.File? = null
        try {
            val view = window.decorView.rootView
            view.isDrawingCacheEnabled = true
            val bitmap = android.graphics.Bitmap.createBitmap(view.drawingCache)
            view.isDrawingCacheEnabled = false
            val f = java.io.File(cacheDir, "anki_mine_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(f).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            screenshotFile = f
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot failed: ${e.message}")
        }

        // Audio extract (subtitle timing window)
        var audioFile: java.io.File? = null
        if (cue != null) {
            try {
                val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
                val capture = MediaCapture(this)
                val padBefore = 0.3
                val padAfter = 0.3
                val startSec = maxOf(0.0, cue.startMs / 1000.0 - padBefore)
                val endSec = cue.endMs / 1000.0 + padAfter
                audioFile = capture.extractAudio(videoUrl, startSec, endSec)
            } catch (e: Exception) {
                Log.w(TAG, "Audio extract failed: ${e.message}")
            }
        }

        val card = AnkiSyncManager.PendingCard(
            word = term,
            reading = dictReading.value,
            meaning = dictMeanings.value.joinToString("; "),
            sentence = sentence,
            sentenceFurigana = sentenceFurigana,
            source = source,
            screenshotFile = screenshotFile,
            audioFile = audioFile
        )
        ankiSync.queueCard(card)

        val count = ankiSync.pendingCount()
        minedToast.value = "⛏ $term ($count)"
        handler.postDelayed({ minedToast.value = null }, 1500)
    }

    // ── Track Selection ──────────────────────────────────────────────

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showAudioList() {
        val tracks = player.currentTracks
        listItems.clear()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language?.uppercase() ?: "Track ${i + 1}"
                val codec = format.codecs ?: ""
                listItems.add(ListItem(label, codec, group.isTrackSelected(i)))
            }
        }
        listTitle.value = Lang.s("audio")
        listFocus.intValue = listItems.indexOfFirst { it.selected }.coerceAtLeast(0)
        listReturnFocus = CTRL_SETTINGS
        listCallback = { idx -> selectAudioTrack(idx); saveSeriesPref("audio_track", idx) }
        screen.value = Screen.LIST_SELECT
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun selectAudioTrack(idx: Int) {
        var trackIdx = 0
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (trackIdx == idx) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    return
                }
                trackIdx++
            }
        }
    }

    private fun showSubsList() {
        val allSubs = intent.getStringArrayListExtra("all_subs") ?: arrayListOf()
        val currentSubUrl = intent.getStringExtra(EXTRA_SUBS_URL)

        listItems.clear()
        listItems.add(ListItem("Off", selected = subtitleCues.isEmpty()))

        val subEntries = allSubs.map { entry ->
            val parts = entry.split("|", limit = 2)
            val name = parts[0]
            val url = if (parts.size == 2) parts[1] else ""
            val label = when {
                name.contains("_ja") -> "Japanese"
                name.contains("_en") -> "English"
                name.contains("_fr") -> "French"
                else -> name
            }
            // Add track number for multi-track
            val trackNum = name.replace(Regex("[^0-9]"), "").takeLast(1)
            val fullLabel = if (trackNum.isNotEmpty() && trackNum != "0") "$label $trackNum" else label
            Triple(fullLabel, url, name)
        }

        subEntries.forEachIndexed { i, (label, url, _) ->
            val isActive = url == currentSubUrl || (i == 0 && subtitleCues.isNotEmpty() && subEntries.size == 1)
            listItems.add(ListItem(label, selected = isActive))
        }

        listTitle.value = Lang.s("subs")
        listFocus.intValue = listItems.indexOfFirst { it.selected }.coerceAtLeast(0)
        listReturnFocus = CTRL_SETTINGS
        listCallback = { idx ->
            saveSeriesPref("sub_track", idx)
            if (idx == 0) {
                subtitleCues = emptyList()
                currentSubText.value = null
            } else {
                val (_, url, _) = subEntries[idx - 1]
                Thread {
                    val srt = try {
                        val reqBuilder = okhttp3.Request.Builder().url(url)
                        PlayerManager.authToken?.let { reqBuilder.header("Authorization", "Bearer $it") }
                        okhttp3.OkHttpClient().newCall(reqBuilder.build()).execute().body?.string()
                    } catch (_: Exception) { null }
                    runOnUiThread {
                        if (srt != null) {
                            subtitleCues = SrtParser.parse(srt)
                            Log.d(TAG, "Switched to ${subEntries[idx - 1].first}: ${subtitleCues.size} cues")
                        }
                    }
                }.start()
            }
        }
        screen.value = Screen.LIST_SELECT
    }

    private fun toggleDownload() {
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: return
        val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, -1)
        if (epNum < 0) return
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
        val subsUrl = intent.getStringExtra(EXTRA_SUBS_URL)

        val existing = DownloadManager.getItemState(seriesId, epNum)
        when (existing?.state) {
            DownloadManager.State.QUEUED, DownloadManager.State.DOWNLOADING -> {
                DownloadManager.delete(seriesId, epNum)
                dlLabel.value = "↓ DL"
            }
            DownloadManager.State.COMPLETED -> {
                DownloadManager.delete(seriesId, epNum)
                dlLabel.value = "↓ DL"
            }
            else -> {
                val filename = videoUrl.substringAfterLast("/")
                val srtFiles = mutableListOf<Pair<String, String>>()
                val allSubs = intent.getStringArrayListExtra("all_subs")
                if (allSubs != null) {
                    for (entry in allSubs) {
                        val parts = entry.split("|", limit = 2)
                        if (parts.size == 2) srtFiles.add(parts[0] to parts[1])
                    }
                } else if (subsUrl != null) {
                    srtFiles.add(subsUrl.substringAfterLast("/") to subsUrl)
                }
                DownloadManager.enqueueEpisode(
                    seriesId, epNum,
                    videoFilename = filename, videoUrl = videoUrl,
                    srtFiles = srtFiles,
                    titleEn = Lang.s("episode", epNum), seriesTitleEn = ""
                )
                startService(android.content.Intent(this, DownloadService::class.java))
                dlLabel.value = "QUEUED"
            }
        }
    }

    private fun updateDlLabel() {
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: return
        val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, -1)
        if (epNum < 0) return
        val item = DownloadManager.getItemState(seriesId, epNum)
        dlLabel.value = when (item?.state) {
            DownloadManager.State.COMPLETED -> "✓ DL"
            DownloadManager.State.DOWNLOADING -> "↓${item.progress}%"
            DownloadManager.State.QUEUED -> "QUEUED"
            DownloadManager.State.FAILED -> "FAILED"
            null -> "↓ DL"
        }
    }


    private fun playNextEpisode() {
        val nextUrl = intent.getStringExtra("next_video_url") ?: return
        val nextSubs = intent.getStringExtra("next_subs_url")
        val nextTitle = intent.getStringExtra("next_title") ?: ""
        val nextEpNum = intent.getIntExtra("next_episode_num", -1)
        if (nextEpNum < 0) return

        saveProgress()
        player.setMediaItem(MediaItem.fromUri(nextUrl))
        player.prepare()
        player.play()
        titleText.value = nextTitle
        intent.putExtra(EXTRA_VIDEO_URL, nextUrl)
        intent.putExtra(EXTRA_SUBS_URL, nextSubs)
        intent.putExtra(EXTRA_TITLE, nextTitle)
        intent.putExtra(EXTRA_EPISODE_NUM, nextEpNum)
        intent.putExtra(EXTRA_START_POSITION, 0L)
        // Clear next episode (no chain beyond one)
        intent.removeExtra("next_video_url")
        intent.removeExtra("next_subs_url")
        intent.removeExtra("next_title")
        intent.removeExtra("next_episode_num")

        // Load new super-SRT
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        val seasonNum = intent.getIntExtra("season_num", 1)
        if (seriesId != null && nextEpNum > 0) {
            Thread {
                val prefs = getSharedPreferences("janus_settings", MODE_PRIVATE)
                val baseUrl = prefs.getString("server_url", "") ?: ""
                val authToken = prefs.getString("auth_token", null)
                val data = JanusApi(baseUrl).apply { this.token = authToken }
                    .fetchSuperSRT(seriesId, seasonNum, nextEpNum)
                if (data != null) {
                    superSRT = data
                    superCues = data.cues
                    currentSuperCue = null
                }
            }.start()
        }
    }

    private fun toggleCondensed() {
        condensedMode.value = !condensedMode.value
        saveSeriesPref("condensed", if (condensedMode.value) 1 else 0)
        if (!condensedMode.value) {
            player.setPlaybackSpeed(1f)
            player.volume = 1f
            lastCondensedSpeed = 1f
            condensedSpeedLabel.value = null
            debugEvent("condensed_off")
            flushDebugEvents()
        } else {
            debugEvent("condensed_on")
        }
    }

    private fun cycleFontSize() {
        fontSizeIdx.intValue = (fontSizeIdx.intValue + 1) % FONT_SIZES.size
        AppSettings(this).fontSize = FONT_SIZES[fontSizeIdx.intValue]
    }

    private fun cycleFont() {
        fontIdx.intValue = (fontIdx.intValue + 1) % FONT_KEYS.size
        AppSettings(this).fontKey = FONT_KEYS[fontIdx.intValue]
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun saveProgress() {
        if (!::player.isInitialized) return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: return
        val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, -1)
        if (epNum < 0) return
        val pos = player.currentPosition
        val dur = player.duration.coerceAtLeast(1)
        val prefs = getSharedPreferences("watch_progress", MODE_PRIVATE)
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val filename = videoUrl.substringAfterLast("/")
        val seasonNum = intent.getIntExtra("season_num", 1)
        prefs.edit()
            .putLong("${seriesId}_ep${epNum}_pos", pos)
            .putLong("${seriesId}_ep${epNum}_dur", dur)
            .putString("${seriesId}_ep${epNum}_filename", filename)
            .putString("${seriesId}_last_ep", "$epNum")
            .putLong("${seriesId}_last_pos", pos)
            .putInt("${seriesId}_last_season", seasonNum)
            .apply()
    }

    private val backgroundPlay = mutableStateOf(true)

    override fun onPause() {
        super.onPause()
        saveProgress()
        if (!backgroundPlay.value) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        flushDebugEvents()
        saveProgress()
        stopService(android.content.Intent(this, BackgroundPlayService::class.java))
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
