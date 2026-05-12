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
import androidx.compose.ui.viewinterop.AndroidView
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

    enum class Screen { PLAYING, CONTROLS, WORD_NAV, LIST_SELECT }

    private val CTRL_SEEK = 0
    private val CTRL_AUDIO = 1
    private val CTRL_SUBS = 2
    private val CTRL_FONTSIZE = 3
    private val CTRL_FONT = 4

    private val FONT_SIZES = listOf(24, 32, 44)
    private val FONT_KEYS = listOf("noto_sans", "noto_serif", "kosugi_maru", "shippori_mincho")
    private val FONT_NAMES = listOf("Noto Sans", "Noto Serif", "Kosugi", "Shippori")
    private val FONT_ASSETS = listOf(
        "fonts/NotoSansJP-Regular.ttf", "fonts/NotoSerifJP-Regular.ttf",
        "fonts/KosugiMaru-Regular.ttf", "fonts/ShipporiMincho-Regular.ttf"
    )

    private val CTRL_CONDENSED = 5
    private val condensedMode = mutableStateOf(false)

    private val CTRL_HWSW = 6
    private val hwDecoding = mutableStateOf(true)

    private val CTRL_DOWNLOAD = 7
    private val dlLabel = mutableStateOf("↓ DL")

    private val screen = mutableStateOf(Screen.PLAYING)
    private val controlFocus = mutableIntStateOf(CTRL_SEEK)

    // Player state
    private val isPaused = mutableStateOf(false)
    private val positionMs = mutableLongStateOf(0L)
    private val durationMs = mutableLongStateOf(0L)
    private val currentSubText = mutableStateOf<String?>(null)
    private val titleText = mutableStateOf("")

    // Word navigation: cursor moves through japanesePositions, scanAt resolves the word
    private val cursorIdx = mutableIntStateOf(0)
    private val hlStart = mutableIntStateOf(-1)
    private val hlEnd = mutableIntStateOf(-1)
    private var currentWord: WordScanner.ScannedWord? = null
    private var japanesePositions = listOf<Int>()
    private var wordNavSubText = ""

    // Dictionary
    private val dictTerm = mutableStateOf("")
    private val dictReading = mutableStateOf("")
    private val dictMeanings = mutableStateOf(listOf<String>())
    private val dictFreq = mutableIntStateOf(0)
    private val dictTags = mutableStateOf("")
    private val dictVisible = mutableStateOf(false)

    // List select
    data class ListItem(val label: String, val subtitle: String? = null, val selected: Boolean = false)
    private val listItems = mutableStateListOf<ListItem>()
    private val listTitle = mutableStateOf("")
    private val listFocus = mutableIntStateOf(0)
    private var listCallback: ((Int) -> Unit)? = null
    private var listReturnFocus = CTRL_AUDIO

    // Font
    private val fontSizeIdx = mutableIntStateOf(0)
    private val fontIdx = mutableIntStateOf(0)

    // Backend
    private lateinit var player: ExoPlayer
    private var subtitleCues = listOf<SrtParser.Cue>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var dictDb: DictionaryDatabase
    private val dictLookup = object : WordScanner.DictLookup {
        override fun hasEntry(term: String): Boolean = dictDb.hasEntry(term)
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppNavigator.onActivityResumed(AppNavigator.Screen.VIDEO_PLAYER)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        dictDb = DictionaryDatabase.getInstance(this)
        dictDb.ensureReady()

        val appSettings = AppSettings(this)
        val savedSizeIdx = FONT_SIZES.indexOf(appSettings.fontSize)
        if (savedSizeIdx >= 0) fontSizeIdx.intValue = savedSizeIdx
        val savedFontIdx = FONT_KEYS.indexOf(appSettings.fontKey)
        if (savedFontIdx >= 0) fontIdx.intValue = savedFontIdx
        hwDecoding.value = appSettings.hardwareDecoding

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: run { finish(); return }
        val subsUrl = intent.getStringExtra(EXTRA_SUBS_URL)
        titleText.value = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val startPos = intent.getLongExtra(EXTRA_START_POSITION, 0L)

        DownloadManager.init(this)
        player = PlayerManager.getPlayer(this)
        PlayerManager.play(this, videoUrl, startPos)
        updateDlLabel()

        // Load subtitles
        if (subsUrl != null) {
            Thread {
                val srt = try {
                    okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(subsUrl).build())
                        .execute().body?.string()
                } catch (_: Exception) { null }
                if (srt != null) {
                    subtitleCues = SrtParser.parse(srt)
                    Log.d(TAG, "Loaded ${subtitleCues.size} cues")
                }
            }.start()
        }

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
                    isPaused.value = !player.isPlaying
                    val cue = SrtParser.cueAt(subtitleCues, pos)
                    currentSubText.value = cue?.text

                    // Condensed: if no current sub, playing, and gap to next > 2s, skip
                    if (condensedMode.value && cue == null && player.isPlaying && screen.value == Screen.PLAYING) {
                        val next = SrtParser.nextCueAfter(subtitleCues, pos)
                        if (next != null && next.startMs - pos > 2000) {
                            player.seekTo(next.startMs - 200)
                        }
                    }
                }
                handler.postDelayed(this, 200)
            }
        })

        setContent { PlayerScreen() }
    }

    // ── Compose UI ───────────────────────────────────────────────────

    @Composable
    private fun PlayerScreen() {
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
        val fSizeIdx by fontSizeIdx
        val fIdx by fontIdx
        val title by titleText

        val subFontSize = FONT_SIZES[fSizeIdx].sp
        val fontAsset = FONT_ASSETS[fIdx]
        val subFontFamily = remember(fontAsset) {
            try { FontFamily(Typeface.createFromAsset(assets, fontAsset)) }
            catch (_: Exception) { FontFamily.Default }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // ExoPlayer surface — tap here to toggle controls
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setOnClickListener {
                            when (screen.value) {
                                Screen.PLAYING -> {
                                    this@ExoPlayerActivity.player.pause()
                                    if (!enterWordNav()) goto(Screen.CONTROLS, CTRL_SEEK)
                                }
                                Screen.CONTROLS, Screen.WORD_NAV -> {
                                    clearDict(); this@ExoPlayerActivity.player.play(); goto(Screen.PLAYING)
                                }
                                else -> {}
                            }
                        }
                    }
                },
                update = { view -> PlayerManager.attachView(view) },
                modifier = Modifier.fillMaxSize()
            )

            // Top: buttons row
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp, end = 24.dp)
                ) {
                    CtrlBtn("♪", "Audio", CTRL_AUDIO, cFocus) { showAudioList() }
                    Spacer(Modifier.width(8.dp))
                    CtrlBtn("CC", "Subs", CTRL_SUBS, cFocus) { showSubsList() }
                    Spacer(Modifier.width(8.dp))
                    CtrlBtn("Aa", "${FONT_SIZES[fSizeIdx]}sp", CTRL_FONTSIZE, cFocus) { cycleFontSize() }
                    Spacer(Modifier.width(8.dp))
                    CtrlBtn("F", FONT_NAMES[fIdx].take(8), CTRL_FONT, cFocus) { cycleFont() }
                    Spacer(Modifier.width(8.dp))
                    val condOn by condensedMode
                    CtrlBtn("⏩", if (condOn) "COND ON" else "COND OFF", CTRL_CONDENSED, cFocus) { condensedMode.value = !condensedMode.value }
                    Spacer(Modifier.width(8.dp))
                    val hwOn by hwDecoding
                    CtrlBtn("⚡", if (hwOn) "HW" else "SW", CTRL_HWSW, cFocus) { toggleHwSwDecoding() }
                    Spacer(Modifier.width(8.dp))
                    val dlText by dlLabel
                    CtrlBtn("↓", dlText, CTRL_DOWNLOAD, cFocus) { toggleDownload() }
                }
            }

            // Title + back button
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp, start = 24.dp)
                ) {
                    CtrlBtn("←", "Back", -1, cFocus) { saveProgress(); finish() }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Text(title, color = Color.White, fontSize = 14.sp)
                }
            }

            // Dictionary popup
            AnimatedVisibility(
                visible = dVis && (scr == Screen.WORD_NAV),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .background(Color(0xEE1E1E2E), RoundedCornerShape(10.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (dReadV.isNotEmpty()) {
                                androidx.compose.material3.Text(dReadV, color = Color(0xFFAAAAAA), fontSize = 14.sp)
                            }
                            androidx.compose.material3.Text(dTermV, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(14.dp))
                        if (dTagsV.isNotBlank()) {
                            androidx.compose.material3.Text(dTagsV, color = Color(0xFF7986CB), fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (dFreqV > 0) {
                            androidx.compose.material3.Text(
                                "#$dFreqV", color = Color(0xFF81C784), fontSize = 12.sp,
                                modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    dMeanV.forEachIndexed { i, m ->
                        androidx.compose.material3.Text("${i + 1}. $m", color = Color(0xFFCCCCCC), fontSize = 14.sp, lineHeight = 18.sp)
                    }
                }
            }

            // Subtitle
            if (sub != null && sub!!.isNotBlank()) {
                val hS by hlStart
                val hE by hlEnd
                val subText = sub!!
                val annotated = buildAnnotatedString {
                    for (i in subText.indices) {
                        if (scr == Screen.WORD_NAV && hS >= 0 && hE > hS && i in hS until hE) {
                            pushStyle(SpanStyle(background = Color(0xFF7986CB)))
                            append(subText[i])
                            pop()
                        } else {
                            append(subText[i])
                        }
                    }
                }

                var charBoxes by remember(subText) { mutableStateOf(emptyArray<androidx.compose.ui.geometry.Rect>()) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 60.dp, start = 32.dp, end = 32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .pointerInput(subText) {
                            detectTapGestures { pos ->
                                val boxes = charBoxes
                                for (i in boxes.indices) {
                                    if (boxes[i].contains(pos)) { onSubtitleTap(i); break }
                                }
                            }
                        }
                ) {
                    androidx.compose.material3.Text(
                        text = annotated, color = Color.Black, fontSize = subFontSize, fontFamily = subFontFamily,
                        style = androidx.compose.ui.text.TextStyle(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                    )
                    androidx.compose.material3.Text(
                        text = annotated, color = Color.White, fontSize = subFontSize, fontFamily = subFontFamily,
                        onTextLayout = { layout ->
                            charBoxes = Array(subText.length) { i -> layout.getBoundingBox(i) }
                        }
                    )
                }
            }

            // Seekbar
            AnimatedVisibility(
                visible = scr == Screen.CONTROLS || scr == Screen.LIST_SELECT || scr == Screen.WORD_NAV,
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
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { goto(Screen.CONTROLS, listReturnFocus) }) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight()
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
        }
    }

    @Composable
    private fun CtrlBtn(icon: String, label: String, index: Int, focusIdx: Int, onTap: () -> Unit) {
        val focused = focusIdx == index && screen.value == Screen.CONTROLS
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(width = 72.dp, height = 56.dp)
                .clickable { controlFocus.intValue = index; onTap() }
                .background(if (focused) Color(0xFFBB86FC) else Color(0xFF2A2A3A), RoundedCornerShape(12.dp))
                .then(if (focused) Modifier.border(1.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            androidx.compose.material3.Text(icon, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(label, color = if (focused) Color.White else Color(0xFFAAAAAA), fontSize = 9.sp)
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
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val prev = SrtParser.prevCueBefore(subtitleCues, player.currentPosition)
                    if (prev != null) player.seekTo(prev.startMs) else player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = SrtParser.nextCueAfter(subtitleCues, player.currentPosition)
                    if (next != null) player.seekTo(next.startMs) else player.seekTo(player.currentPosition + 10000)
                }
                KeyEvent.KEYCODE_DPAD_UP -> { player.pause(); goto(Screen.CONTROLS, CTRL_AUDIO) }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play() }
                else -> {
                    player.pause()
                    if (!enterWordNav()) goto(Screen.CONTROLS, CTRL_SEEK)
                }
            }

            Screen.CONTROLS -> {
                val f = controlFocus.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { player.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play() }
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        f == CTRL_SEEK -> if (!enterWordNav()) controlFocus.intValue = CTRL_AUDIO
                        else -> { player.play(); goto(Screen.PLAYING) }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        f == CTRL_SEEK -> {}
                        else -> if (!enterWordNav()) controlFocus.intValue = CTRL_SEEK
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        f == CTRL_SEEK -> player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                        f > CTRL_AUDIO -> controlFocus.intValue = f - 1
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        f == CTRL_SEEK -> player.seekTo(player.currentPosition + 10000)
                        f < CTRL_DOWNLOAD -> controlFocus.intValue = f + 1
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (f) {
                        CTRL_SEEK -> { player.play(); goto(Screen.PLAYING) }
                        CTRL_AUDIO -> showAudioList()
                        CTRL_SUBS -> showSubsList()
                        CTRL_FONTSIZE -> cycleFontSize()
                        CTRL_FONT -> cycleFont()
                        CTRL_CONDENSED -> condensedMode.value = !condensedMode.value
                        CTRL_HWSW -> toggleHwSwDecoding()
                        CTRL_DOWNLOAD -> toggleDownload()
                    }
                    else -> return false
                }
            }

            Screen.WORD_NAV -> {
                val ci = cursorIdx.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { clearDict(); player.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_DPAD_UP -> { clearDict(); goto(Screen.CONTROLS, CTRL_AUDIO) }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { clearDict(); goto(Screen.CONTROLS, CTRL_SEEK) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (ci > 0) { cursorIdx.intValue = ci - 1; updateWordAtCursor() }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (ci < japanesePositions.size - 1) { cursorIdx.intValue = ci + 1; updateWordAtCursor() }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {}
                    else -> return false
                }
            }

            Screen.LIST_SELECT -> when (key) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { goto(Screen.CONTROLS, listReturnFocus) }
                KeyEvent.KEYCODE_DPAD_UP -> { listFocus.intValue = (listFocus.intValue - 1).coerceAtLeast(0) }
                KeyEvent.KEYCODE_DPAD_DOWN -> { listFocus.intValue = (listFocus.intValue + 1).coerceAtMost(listItems.size - 1) }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    listCallback?.invoke(listFocus.intValue)
                    player.play(); goto(Screen.PLAYING)
                }
                else -> return false
            }
        }
        return true
    }

    private fun goto(s: Screen, focus: Int = -1) {
        screen.value = s
        if (focus >= 0) controlFocus.intValue = focus
    }

    // ── Word Navigation ──────────────────────────────────────────────

    private fun onSubtitleTap(charOffset: Int) {
        val text = currentSubText.value ?: return
        val positions = WordScanner.findJapanesePositions(text)
        if (positions.isEmpty()) return
        val targetIdx = positions.indices.minByOrNull { kotlin.math.abs(positions[it] - charOffset) } ?: 0
        player.pause()
        japanesePositions = positions
        wordNavSubText = text
        cursorIdx.intValue = targetIdx
        updateWordAtCursor()
        screen.value = Screen.WORD_NAV
    }

    private fun enterWordNav(): Boolean {
        val text = currentSubText.value ?: return false
        val positions = WordScanner.findJapanesePositions(text)
        if (positions.isEmpty()) return false
        japanesePositions = positions
        wordNavSubText = text
        cursorIdx.intValue = 0
        updateWordAtCursor()
        screen.value = Screen.WORD_NAV
        return true
    }

    private fun updateWordAtCursor() {
        val charPos = japanesePositions.getOrNull(cursorIdx.intValue) ?: return
        val word = WordScanner.scanAt(wordNavSubText, charPos, dictLookup)
        currentWord = word
        if (word != null) {
            hlStart.intValue = word.startChar
            hlEnd.intValue = word.endChar
            lookupWord(word)
        } else {
            hlStart.intValue = charPos
            hlEnd.intValue = charPos + 1
            dictVisible.value = false
        }
    }

    private fun lookupWord(word: WordScanner.ScannedWord) {
        val results = mutableListOf<DictionaryDatabase.DictEntry>()
        results.addAll(dictDb.lookup(word.baseForm))
        if (word.surface != word.baseForm) results.addAll(dictDb.lookup(word.surface))
        val unique = results.distinctBy { "${it.term}|${it.reading}" }
        if (unique.isNotEmpty()) {
            val best = unique.first()
            dictTerm.value = best.term
            dictReading.value = if (best.reading != best.term) best.reading else ""
            dictMeanings.value = unique.flatMap { it.meanings }.filter { it.isNotBlank() }.distinct().take(5)
            dictFreq.intValue = best.frequency ?: unique.firstNotNullOfOrNull { it.frequency } ?: 0
            dictTags.value = formatTags(best.tags)
            dictVisible.value = true
        }
    }

    private fun clearDict() {
        dictVisible.value = false
    }

    private fun formatTags(tags: String): String = tags.trim().split(" ").joinToString(" ") { tag ->
        when (tag) {
            "v1" -> "ichidan"; "v5" -> "godan"; "vs" -> "suru"; "vt" -> "trans."; "vi" -> "intrans."
            "adj-i" -> "i-adj"; "adj-na" -> "na-adj"; "n" -> "noun"; "adv" -> "adv"; "exp" -> "expr"
            else -> tag
        }
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
        listTitle.value = "Audio"
        listFocus.intValue = listItems.indexOfFirst { it.selected }.coerceAtLeast(0)
        listReturnFocus = CTRL_AUDIO
        listCallback = { idx -> selectAudioTrack(idx) }
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
        // For now, subtitles come from pre-extracted SRT, not embedded tracks
        // Show available SRT languages
        listItems.clear()
        listItems.add(ListItem("Off", selected = subtitleCues.isEmpty()))
        listItems.add(ListItem("Japanese", "Pre-extracted SRT", selected = subtitleCues.isNotEmpty()))
        listTitle.value = "Subtitles"
        listFocus.intValue = if (subtitleCues.isNotEmpty()) 1 else 0
        listReturnFocus = CTRL_SUBS
        listCallback = { idx ->
            if (idx == 0) subtitleCues = emptyList()
            // idx == 1 already loaded
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
                    seriesId, epNum, filename, videoUrl, srtFiles,
                    titleEn = "Episode $epNum", seriesTitleEn = ""
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

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun toggleHwSwDecoding() {
        hwDecoding.value = !hwDecoding.value
        AppSettings(this).hardwareDecoding = hwDecoding.value
        val pos = player.currentPosition
        val wasPlaying = player.isPlaying
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return

        PlayerManager.release()

        val builder = ExoPlayer.Builder(this)
        if (!hwDecoding.value) {
            builder.setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)
            )
        }
        // Rebuild PlayerManager with custom player would require refactoring — for now recreate locally
        player = builder.build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setMediaItem(MediaItem.fromUri(videoUrl))
        player.prepare()
        player.seekTo(pos)
        if (wasPlaying) player.play()
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
        prefs.edit()
            .putLong("${seriesId}_ep${epNum}_pos", pos)
            .putLong("${seriesId}_ep${epNum}_dur", dur)
            .putString("${seriesId}_ep${epNum}_filename", filename)
            .putString("${seriesId}_last_ep", "$epNum")
            .putLong("${seriesId}_last_pos", pos)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        handler.removeCallbacksAndMessages(null)
        PlayerManager.detachView()
    }
}
