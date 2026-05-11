package com.videoplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class PlayerActivity : ComponentActivity(), MpvPlayerView.Listener {

    companion object {
        private const val TAG = "Player"
        const val EXTRA_SMB_SERVER = "smb_server"
        const val EXTRA_SMB_SHARE = "smb_share"
        const val EXTRA_SMB_PATH = "smb_path"
        const val EXTRA_SMB_USER = "smb_user"
        const val EXTRA_SMB_PASS = "smb_pass"
        const val EXTRA_LOCAL_URI = "local_uri"
        const val EXTRA_SEEK_SEC = "seek_sec"
        const val EXTRA_SUB_TRACK = "sub_track"
    }

    // ── UI State Machine ─────────────────────────────────────────────

    enum class Screen { PLAYING, CONTROLS, LIST_SELECT, WORD_NAV, CARD_CREATE }

    // Control bar items: [seekbar, audio, subs]
    private val CTRL_SEEK = 0
    private val CTRL_AUDIO = 1
    private val CTRL_SUBS = 2

    private val screen = mutableStateOf(Screen.PLAYING)
    private val controlFocus = mutableIntStateOf(CTRL_SEEK)
    private val listItems = mutableStateListOf<ListItem>()
    private val listTitle = mutableStateOf("")
    private val listFocus = mutableIntStateOf(0)
    private var listCallback: ((Int) -> Unit)? = null

    // Player state
    private val isPaused = mutableStateOf(false)
    private val positionSec = mutableDoubleStateOf(0.0)
    private val durationSec = mutableDoubleStateOf(0.0)
    private val subtitleText = mutableStateOf<String?>(null)

    // Word navigation state
    private val wordFocus = mutableIntStateOf(0)
    private var wordTokens = listOf<com.atilika.kuromoji.ipadic.Token>()
    private var focusableWordIndices = listOf<Int>()
    private val dictTerm = mutableStateOf("")
    private val dictReading = mutableStateOf("")
    private val dictMeanings = mutableStateOf(listOf<String>())
    private val dictFreq = mutableIntStateOf(0)
    private val dictVisible = mutableStateOf(false)
    private var tokenizer: com.atilika.kuromoji.ipadic.Tokenizer? = null
    private lateinit var dictDb: DictionaryDatabase
    private var dwellJob: Job? = null
    private val dwellScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dictPitch = mutableStateOf("")
    private val dictTags = mutableStateOf("")
    private var currentDictEntries: List<DictionaryDatabase.DictEntry>? = null
    private var lastSubtitleText: String = ""

    // Card creation state
    private val CARD_SEND = 0
    private val CARD_CANCEL = 1
    private val cardFocus = mutableIntStateOf(CARD_SEND)
    private val cardStatus = mutableStateOf("")
    private var currentCardData: CardData? = null
    private lateinit var appSettings: AppSettings
    private lateinit var mediaCapture: MediaCapture

    data class ListItem(val label: String, val subtitle: String? = null, val selected: Boolean = false)

    // ── Backend ──────────────────────────────────────────────────────

    lateinit var playerView: MpvPlayerView
    private val smbStreamServer = SmbStreamServer()
    private var currentPlaybackUrl: String? = null
    private var smbServer: String? = null
    private var smbShare: String? = null
    private var smbPath: String? = null
    private var smbUser = ""
    private var smbPass = ""

    private val subtitleBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { handleSubtitleResult(it) }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    private lateinit var composeView: androidx.compose.ui.platform.ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        dictDb = DictionaryDatabase.getInstance(this)
        dictDb.ensureReady()
        appSettings = AppSettings(this)
        mediaCapture = MediaCapture(this)
        Thread {
            try { tokenizer = com.atilika.kuromoji.ipadic.Tokenizer() } catch (_: Exception) {}
        }.start()

        // Build view hierarchy manually — MpvPlayerView must never be inside Compose
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)

        playerView = MpvPlayerView(this)
        root.addView(playerView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        root.addView(composeView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)

        composeView.setContent {
            PlayerScreen()
        }

        playerView.setListener(this)
        playerView.initialize()

        // Debug receiver: adb shell am broadcast -a com.videoplayer.TEST --ef sec 140
        val testReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                val sec = intent.getFloatExtra("sec", 0f).toDouble()
                val action = intent.getStringExtra("do") ?: "wordnav"
                Log.d(TAG, "TEST: seek=$sec action=$action")
                playerView.seekTo(sec)
                playerView.pause()
                // Wait for sub-text to update after seek
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "TEST: sub='${subtitleText.value}' state=${screen.value}")
                    when (action) {
                        "wordnav" -> {
                            if (enterWordNav()) {
                                Log.d(TAG, "TEST: WORD_NAV entered, dwelling on each word...")
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                var step = 0
                                fun dwellNext() {
                                    val wf = wordFocus.intValue
                                    val word = wordTokens.getOrNull(wf)?.surface ?: return
                                    val base = wordTokens.getOrNull(wf)?.baseForm ?: word
                                    Log.d(TAG, "TEST_WORD[$step]: [$word] base=[$base]")
                                    scheduleDwell()
                                    handler.postDelayed({
                                        val dv = dictVisible.value
                                        val dt = dictTerm.value
                                        val dm = dictMeanings.value.firstOrNull() ?: "(none)"
                                        Log.d(TAG, "TEST_DICT[$step]: visible=$dv term='$dt' meaning='${dm.take(60)}'")
                                        step++
                                        val fiIdx = focusableWordIndices.indexOf(wf)
                                        if (fiIdx < focusableWordIndices.size - 1) {
                                            wordFocus.intValue = focusableWordIndices[fiIdx + 1]
                                            dwellNext()
                                        } else {
                                            Log.d(TAG, "TEST: done, $step words tested")
                                            clearDict()
                                            goto(Screen.PLAYING)
                                            playerView.play()
                                        }
                                    }, 500)
                                }
                                dwellNext()
                            } else {
                                Log.d(TAG, "TEST: no focusable words")
                                playerView.play()
                            }
                        }
                    }
                }, 1500)
            }
        }
        registerReceiver(testReceiver, android.content.IntentFilter("com.videoplayer.TEST"),
            android.content.Context.RECEIVER_EXPORTED)
        startPlayback(savedInstanceState)
    }

    // ── Compose UI ───────────────────────────────────────────────────

    @Composable
    private fun PlayerScreen() {
        val currentScreen by screen
        val paused by isPaused
        val pos by positionSec
        val dur by durationSec
        val subs by subtitleText
        val focusIdx by controlFocus
        val lTitle by listTitle
        val lFocus by listFocus

        val wFocus by wordFocus
        val dVisible by dictVisible
        val dTerm by dictTerm
        val dReading by dictReading
        val dMeanings by dictMeanings
        val dFreq by dictFreq
        val dPitch by dictPitch
        val dTagsVal by dictTags
        val cFocus by cardFocus
        val cStatus by cardStatus

        Box(modifier = Modifier.fillMaxSize()) {

            // Dictionary popup — top
            AnimatedVisibility(
                visible = dVisible && (currentScreen == Screen.WORD_NAV || currentScreen == Screen.CARD_CREATE),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .background(Color(0xEE1E1E2E), RoundedCornerShape(10.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Text(dTerm, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        if (dReading.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.Text(dReading, color = Color(0xFFAAAAAA), fontSize = 16.sp)
                        }
                        if (dFreq > 0) {
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.Text(
                                "#$dFreq", color = Color(0xFF81C784), fontSize = 12.sp,
                                modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (dTagsVal.isNotBlank()) {
                        androidx.compose.material3.Text(dTagsVal, color = Color(0xFF7986CB), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (dPitch.isNotBlank()) {
                        androidx.compose.material3.Text(dPitch, color = Color(0xFFCE93D8), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    dMeanings.forEachIndexed { i, m ->
                        androidx.compose.material3.Text("${i + 1}. $m", color = Color(0xFFCCCCCC), fontSize = 14.sp, lineHeight = 18.sp)
                    }
                    if (currentScreen == Screen.WORD_NAV) {
                        androidx.compose.material3.Text(
                            "Press OK to create Anki card",
                            color = Color(0xFF666666), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Card creation overlay
            AnimatedVisibility(
                visible = currentScreen == Screen.CARD_CREATE,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .background(Color(0xEE1E1E2E), RoundedCornerShape(10.dp))
                        .padding(20.dp)
                ) {
                    val card = currentCardData
                    if (card != null) {
                        androidx.compose.material3.Text(card.word, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (card.sentence.isNotBlank()) {
                            androidx.compose.material3.Text(card.sentence, color = Color(0xFFAAAAAA), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Text(cStatus, color = Color(0xFF81C784), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.Center) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(if (cFocus == CARD_SEND) Color(0xFFBB86FC) else Color(0xFF333344), RoundedCornerShape(8.dp))
                                .then(if (cFocus == CARD_SEND) Modifier.border(1.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            androidx.compose.material3.Text("Send to Anki", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(16.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .background(if (cFocus == CARD_CANCEL) Color(0xFFBB86FC) else Color(0xFF333344), RoundedCornerShape(8.dp))
                                .then(if (cFocus == CARD_CANCEL) Modifier.border(1.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                                .padding(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            androidx.compose.material3.Text("Cancel", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Subtitle display — fixed position, always above controls area
            if (subs != null && subs!!.isNotBlank()) {
                val annotated = if (currentScreen == Screen.WORD_NAV && wordTokens.isNotEmpty()) {
                    val highlightRange = wordHighlightRanges[wFocus] ?: (wFocus..wFocus)
                    androidx.compose.ui.text.buildAnnotatedString {
                        wordTokens.forEachIndexed { idx, token ->
                            if (idx in highlightRange) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(
                                    background = Color(0xFF7986CB)
                                ))
                                append(token.surface)
                                pop()
                            } else {
                                append(token.surface)
                            }
                        }
                    }
                } else {
                    androidx.compose.ui.text.buildAnnotatedString { append(subs!!) }
                }
                androidx.compose.material3.Text(
                    text = annotated,
                    color = Color.White,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 150.dp, start = 32.dp, end = 32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // Transport controls
            AnimatedVisibility(
                visible = currentScreen == Screen.CONTROLS || currentScreen == Screen.LIST_SELECT,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ControlsBar(pos, dur, paused, focusIdx)
            }

            // List selector overlay
            AnimatedVisibility(
                visible = currentScreen == Screen.LIST_SELECT,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(150)) + fadeOut(tween(150)),
            ) {
                ListOverlay(lTitle, listItems, lFocus)
            }
        }
    }

    @Composable
    private fun ControlsBar(pos: Double, dur: Double, paused: Boolean, focusIdx: Int) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 24.dp)
        ) {
            // Progress bar
            val progress = if (dur > 0) (pos / dur).toFloat().coerceIn(0f, 1f) else 0f
            val isFocused = focusIdx == CTRL_SEEK

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    androidx.compose.material3.Text(formatTime(pos), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    androidx.compose.material3.Text(formatTime(dur), color = Color(0xFFAAAAAA), fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isFocused) 8.dp else 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF444444))
                        .then(if (isFocused) Modifier.border(1.dp, Color(0xFFBB86FC), RoundedCornerShape(4.dp)) else Modifier)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(if (isFocused) Color(0xFFBB86FC) else Color(0xFF90CAF9), RoundedCornerShape(4.dp))
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Buttons row: audio, subs
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CtrlButton("♪", "Audio", CTRL_AUDIO, focusIdx)
                Spacer(Modifier.width(12.dp))
                CtrlButton("CC", "Subs", CTRL_SUBS, focusIdx)
            }
        }
    }

    @Composable
    private fun CtrlButton(icon: String, label: String, index: Int, focusIdx: Int) {
        val focused = focusIdx == index
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(width = 72.dp, height = 56.dp)
                .background(
                    if (focused) Color(0xFFBB86FC) else Color(0xFF2A2A3A),
                    RoundedCornerShape(12.dp)
                )
                .then(if (focused) Modifier.border(1.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            androidx.compose.material3.Text(icon, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.Text(label, color = if (focused) Color.White else Color(0xFFAAAAAA), fontSize = 9.sp)
        }
    }

    @Composable
    private fun ListOverlay(title: String, items: List<ListItem>, focusIdx: Int) {
        val scrollState = rememberScrollState()

        LaunchedEffect(focusIdx) {
            val itemHeight = 44
            scrollState.animateScrollTo((focusIdx * itemHeight).coerceAtLeast(0))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xAA000000))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(340.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1A1A2E))
                    .padding(vertical = 12.dp)
            ) {
                androidx.compose.material3.Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    items.forEachIndexed { idx, item ->
                        val focused = idx == focusIdx
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                .background(
                                    when {
                                        focused -> Color(0xFFBB86FC)
                                        item.selected -> Color(0xFF2A2A4A)
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            if (item.selected) {
                                androidx.compose.material3.Text("●", color = Color(0xFF81C784), fontSize = 8.sp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                            Column {
                                androidx.compose.material3.Text(
                                    item.label,
                                    color = if (focused) Color.White else Color(0xFFEEEEEE),
                                    fontSize = 14.sp,
                                    fontWeight = if (item.selected || focused) FontWeight.SemiBold else FontWeight.Normal
                                )
                                item.subtitle?.let {
                                    androidx.compose.material3.Text(it, color = Color(0xFFAAAAAA), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── State Machine ─────────────────────────────────────────────────
    //
    //  PLAYING:
    //    BACK           → exit app
    //    LEFT/RIGHT     → seek ±10s
    //    PLAY_PAUSE     → toggle pause
    //    UP             → if subs: pause + go WORD_NAV. else: pause + go CONTROLS
    //    CENTER/DOWN    → pause + go CONTROLS
    //
    //  CONTROLS(focusIdx):
    //    BACK           → resume + go PLAYING
    //    UP             → if on buttons: SEEK. if SEEK: go WORD_NAV (if subs) or PLAYING
    //    DOWN           → if SEEK: PLAY button
    //    LEFT/RIGHT     → seek on SEEK row, move focus on buttons
    //    CENTER         → activate control
    //    PLAY_PAUSE     → toggle pause
    //
    //  WORD_NAV(wordFocusIdx):
    //    BACK           → go CONTROLS
    //    LEFT           → prev focusable word (clamp)
    //    RIGHT          → next focusable word (clamp)
    //    DOWN           → go CONTROLS(SEEK)
    //    CENTER         → if dict visible: go CARD_CREATE
    //    300ms dwell    → auto dictionary lookup
    //
    //  CARD_CREATE(cardFocus):
    //    BACK           → go WORD_NAV
    //    LEFT/RIGHT     → toggle Send / Cancel
    //    CENTER         → Send: send to Anki. Cancel: go WORD_NAV
    //
    //  LIST_SELECT(focusIdx):
    //    BACK/LEFT      → go CONTROLS (restore focus)
    //    UP/DOWN        → move focus
    //    CENTER         → apply + go CONTROLS
    //

    private var listReturnFocus = CTRL_AUDIO

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return transition(screen.value, event.keyCode)
    }

    private fun transition(state: Screen, key: Int): Boolean {
        when (state) {
            Screen.PLAYING -> when (key) {
                KeyEvent.KEYCODE_BACK -> finish()
                KeyEvent.KEYCODE_DPAD_LEFT -> playerView.subSeekPrev()
                KeyEvent.KEYCODE_DPAD_RIGHT -> playerView.subSeekNext()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playerView.togglePause()
                else -> {
                    playerView.pause()
                    if (!enterWordNav()) goto(Screen.CONTROLS, CTRL_SEEK)
                }
            }

            Screen.CONTROLS -> {
                val f = controlFocus.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { playerView.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playerView.togglePause()
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        f == CTRL_SEEK -> if (!enterWordNav()) { playerView.play(); goto(Screen.PLAYING) }
                        else -> controlFocus.intValue = CTRL_SEEK
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        f == CTRL_SEEK -> controlFocus.intValue = CTRL_AUDIO
                        else -> {}
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        f == CTRL_SEEK -> playerView.seekRelative(-10)
                        f > CTRL_AUDIO -> controlFocus.intValue = f - 1
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        f == CTRL_SEEK -> playerView.seekRelative(10)
                        f < CTRL_SUBS -> controlFocus.intValue = f + 1
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (f) {
                        CTRL_SEEK -> { playerView.play(); goto(Screen.PLAYING) }
                        CTRL_AUDIO -> { listReturnFocus = CTRL_AUDIO; showAudioList() }
                        CTRL_SUBS -> { listReturnFocus = CTRL_SUBS; showSubtitleList() }
                    }
                    else -> return false
                }
            }

            Screen.WORD_NAV -> {
                val wf = wordFocus.intValue
                val fiIdx = focusableWordIndices.indexOf(wf)
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { clearDict(); playerView.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { clearDict(); goto(Screen.CONTROLS, CTRL_SEEK) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (fiIdx > 0) {
                            wordFocus.intValue = focusableWordIndices[fiIdx - 1]
                            Log.d(TAG, "WORD: ← [${wordTokens[focusableWordIndices[fiIdx - 1]].surface}]")
                            scheduleDwell()
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (fiIdx < focusableWordIndices.size - 1) {
                            wordFocus.intValue = focusableWordIndices[fiIdx + 1]
                            Log.d(TAG, "WORD: → [${wordTokens[focusableWordIndices[fiIdx + 1]].surface}]")
                            scheduleDwell()
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (dictVisible.value) openCardCreator()
                    }
                    else -> return false
                }
            }

            Screen.CARD_CREATE -> when (key) {
                KeyEvent.KEYCODE_BACK -> { goto(Screen.WORD_NAV) }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cardFocus.intValue = if (cardFocus.intValue == CARD_SEND) CARD_CANCEL else CARD_SEND
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (cardFocus.intValue == CARD_SEND) sendToAnki()
                    else goto(Screen.WORD_NAV)
                }
                else -> return false
            }

            Screen.LIST_SELECT -> when (key) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    goto(Screen.CONTROLS, listReturnFocus)
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    listFocus.intValue = (listFocus.intValue - 1).coerceAtLeast(0)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    listFocus.intValue = (listFocus.intValue + 1).coerceAtMost(listItems.size - 1)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    listCallback?.invoke(listFocus.intValue)
                    goto(Screen.CONTROLS, listReturnFocus)
                }
                else -> return false
            }
        }
        return true
    }

    private fun goto(s: Screen, focus: Int = -1) {
        Log.d(TAG, "STATE: ${screen.value} → $s" + if (focus >= 0) " focus=$focus" else "")
        screen.value = s
        if (focus >= 0) controlFocus.intValue = focus
    }

    // ── Word navigation helpers ──────────────────────────────────────

    private fun isJapanese(text: String): Boolean = text.any { c ->
        val block = Character.UnicodeBlock.of(c)
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        (block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS && c.isLetterOrDigit())
    }

    private fun isFocusableWord(token: com.atilika.kuromoji.ipadic.Token): Boolean {
        val s = token.surface
        if (!isJapanese(s)) return false
        if (s.length == 1 && Character.UnicodeBlock.of(s[0]) == Character.UnicodeBlock.HIRAGANA) return false
        return true
    }

    // Maps each focusable token index to the range of tokens it highlights (including inflection suffixes)
    private var wordHighlightRanges = mapOf<Int, IntRange>()

    private fun isInflectionSuffix(token: com.atilika.kuromoji.ipadic.Token): Boolean {
        val pos1 = token.partOfSpeechLevel1
        val pos2 = token.partOfSpeechLevel2
        return pos1 == "助動詞" ||
            (pos1 == "動詞" && (pos2 == "接尾" || pos2 == "非自立")) ||
            (pos1 == "助詞" && pos2 == "接続助詞")
    }

    private fun enterWordNav(): Boolean {
        val text = subtitleText.value ?: return false
        val tok = tokenizer ?: run { Log.d(TAG, "WORD_NAV: tokenizer not ready"); return false }
        wordTokens = tok.tokenize(text)
        focusableWordIndices = wordTokens.mapIndexedNotNull { i, t ->
            if (isFocusableWord(t)) i else null
        }
        // Build highlight ranges: each content word extends through following inflection suffixes
        val ranges = mutableMapOf<Int, IntRange>()
        for (fi in focusableWordIndices) {
            var end = fi
            for (j in fi + 1 until wordTokens.size) {
                if (isInflectionSuffix(wordTokens[j])) end = j else break
            }
            ranges[fi] = fi..end
        }
        wordHighlightRanges = ranges
        Log.d(TAG, "WORD_NAV: ${wordTokens.size} tokens, ${focusableWordIndices.size} focusable")
        if (focusableWordIndices.isEmpty()) return false
        wordFocus.intValue = focusableWordIndices[0]
        screen.value = Screen.WORD_NAV
        scheduleDwell()
        return true
    }

    private fun scheduleDwell() {
        dwellJob?.cancel()
        val idx = wordFocus.intValue
        val word = wordTokens.getOrNull(idx)?.surface ?: "?"
        Log.d(TAG, "DWELL: start on [$word] idx=$idx")
        dwellJob = dwellScope.launch {
            delay(300)
            Log.d(TAG, "DWELL: fire lookup for [$word]")
            lookupWord(idx)
        }
    }

    private fun lookupWord(tokenIdx: Int) {
        val token = wordTokens.getOrNull(tokenIdx) ?: return
        val surface = token.surface
        val baseForm = token.baseForm ?: surface
        // Build the full conjugated form from the highlight range
        val range = wordHighlightRanges[tokenIdx] ?: (tokenIdx..tokenIdx)
        val fullSurface = wordTokens.subList(range.first, range.last + 1).joinToString("") { it.surface }
        dwellScope.launch(Dispatchers.IO) {
            // Try: baseForm, surface, fullSurface, then all deinflected candidates
            val candidates = mutableListOf(baseForm)
            if (surface != baseForm) candidates.add(surface)
            if (fullSurface != surface && fullSurface != baseForm) candidates.add(fullSurface)
            candidates.addAll(Deinflector.deinflect(fullSurface).drop(1)) // skip first (original)
            if (fullSurface != surface) candidates.addAll(Deinflector.deinflect(surface).drop(1))

            Log.d(TAG, "LOOKUP: '$fullSurface' base='$baseForm' candidates=${candidates.distinct().take(8)}")
            val results = mutableListOf<DictionaryDatabase.DictEntry>()
            for (candidate in candidates.distinct()) {
                results.addAll(dictDb.lookup(candidate))
                if (results.size >= 5) break
            }
            Log.d(TAG, "LOOKUP: ${results.size} raw results")
            val unique = results.distinctBy { "${it.term}|${it.reading}" }

            val pitchAccents = mutableListOf<DictionaryDatabase.PitchAccent>()
            pitchAccents.addAll(dictDb.lookupPitchAccent(baseForm))
            if (baseForm != surface) pitchAccents.addAll(dictDb.lookupPitchAccent(surface))

            if (unique.isNotEmpty()) {
                val best = unique.first()
                val meanings = unique.flatMap { it.meanings }.filter { it.isNotBlank() }.distinct().take(5)
                val pitchText = formatPitchAccents(pitchAccents)
                val tagsText = formatTags(best.tags)
                Log.d(TAG, "DICT: found ${unique.size} entries for $baseForm/$surface: ${best.term} [${best.reading}] - ${meanings.first()}")
                withContext(Dispatchers.Main) {
                    currentDictEntries = unique
                    dictTerm.value = best.term
                    dictReading.value = if (best.reading != best.term) best.reading else ""
                    dictMeanings.value = meanings
                    dictFreq.intValue = best.frequency ?: unique.firstNotNullOfOrNull { it.frequency } ?: 0
                    dictPitch.value = pitchText
                    dictTags.value = tagsText
                    dictVisible.value = true
                }
            }
        }
    }

    private fun formatTags(tags: String): String = tags.trim().split(" ").joinToString(" ") { tag ->
        when (tag) {
            "v1" -> "ichidan"; "v5" -> "godan"; "vs" -> "suru"
            "vt" -> "trans."; "vi" -> "intrans."
            "adj-i" -> "i-adj"; "adj-na" -> "na-adj"
            "n" -> "noun"; "adv" -> "adv"; "exp" -> "expr"
            "prt" -> "particle"; "conj" -> "conj"; "int" -> "interj"
            else -> tag
        }
    }

    private fun formatPitchAccents(accents: List<DictionaryDatabase.PitchAccent>): String =
        accents.mapNotNull { pa ->
            try {
                val json = org.json.JSONObject(pa.pitchData)
                val reading = json.optString("reading", pa.term)
                val pitches = json.optJSONArray("pitches") ?: return@mapNotNull null
                val positions = (0 until pitches.length()).mapNotNull { i ->
                    val pos = pitches.getJSONObject(i).optInt("position", -1)
                    if (pos >= 0) when (pos) { 0 -> "heiban"; 1 -> "atamadaka"; else -> "[$pos]" } else null
                }
                if (positions.isEmpty()) null else "$reading: ${positions.joinToString(", ")}"
            } catch (_: Exception) { null }
        }.distinct().joinToString("  ")

    private fun clearDict() {
        dwellJob?.cancel()
        dictVisible.value = false
    }

    // ── Card creation ────────────────────────────────────────────────

    private fun openCardCreator() {
        if (!appSettings.ankiEnabled) {
            Toast.makeText(this, "Enable Anki in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val entries = currentDictEntries ?: return
        val best = entries.first()
        val meanings = entries.flatMap { it.meanings }.filter { it.isNotBlank() }.distinct().take(5)
        val timing = mediaCapture.getSubtitleTiming()

        currentCardData = CardData(
            word = best.term,
            reading = best.reading,
            meaning = meanings.joinToString("\n"),
            sentence = lastSubtitleText,
            frequency = best.frequency ?: entries.firstNotNullOfOrNull { it.frequency },
            audioStartSec = timing?.first ?: 0.0,
            audioEndSec = timing?.second ?: 0.0,
            audioPadBefore = appSettings.audioPadBefore,
            audioPadAfter = appSettings.audioPadAfter,
            source = try { dev.jdtech.mpv.MPVLib.getPropertyString("media-title") ?: "" } catch (_: Exception) { "" }
        )
        cardFocus.intValue = CARD_SEND
        cardStatus.value = "Capturing..."
        screen.value = Screen.CARD_CREATE

        playerView.captureFrame { bitmap ->
            val card = currentCardData ?: return@captureFrame
            Thread {
                if (bitmap != null) card.screenshotFile = mediaCapture.saveBitmap(bitmap)
                val sourceUrl = currentPlaybackUrl
                if (sourceUrl != null && (card.audioStartSec > 0 || card.audioEndSec > 0)) {
                    card.audioFile = mediaCapture.extractAudio(sourceUrl, card.adjustedStart, card.adjustedEnd)
                }
                runOnUiThread { cardStatus.value = "Ready to send" }
            }.start()
        }
    }

    private fun sendToAnki() {
        val card = currentCardData ?: return
        cardStatus.value = "Sending..."
        Thread {
            try {
                val client = AnkiConnectClient(appSettings.ankiConnectUrl)
                val fields = mutableMapOf<String, String>()
                fun mapField(key: String, value: String) {
                    val fieldName = appSettings.getFieldMapping(key)
                    if (fieldName.isNotEmpty()) fields[fieldName] = value
                }
                mapField("field_word", card.word)
                mapField("field_word_furigana", card.wordWithFurigana)
                mapField("field_reading", card.reading)
                mapField("field_meaning", card.meaning)
                mapField("field_sentence", card.sentence)
                mapField("field_sentence_furigana", card.sentence)
                mapField("field_frequency", card.frequency?.toString() ?: "")
                mapField("field_source", card.source)
                val tags = appSettings.ankiTags.split(" ").filter { it.isNotBlank() }
                val screenshotField = appSettings.getFieldMapping("field_screenshot")
                val audioField = appSettings.getFieldMapping("field_audio")
                val result = client.addNote(
                    deckName = appSettings.ankiDeck, modelName = appSettings.ankiNoteType,
                    fields = fields, tags = tags,
                    audioFile = card.audioFile, audioFieldName = audioField.ifEmpty { null },
                    imageFile = card.screenshotFile, imageFieldName = screenshotField.ifEmpty { null }
                )
                runOnUiThread {
                    if (result.success) {
                        cardStatus.value = "Card added!"
                        dwellScope.launch { delay(1500); goto(Screen.WORD_NAV) }
                    } else {
                        cardStatus.value = "Error: ${result.message}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { cardStatus.value = "Error: ${e.message}" }
            }
        }.start()
    }

    // ── Per-file track preferences ───────────────────────────────────

    private fun getFileKey(): String {
        val path = smbPath ?: currentPlaybackUrl ?: return ""
        return path.substringAfterLast("/").substringAfterLast("\\")
    }

    private fun saveTrackPrefs() {
        val key = getFileKey()
        if (key.isEmpty()) return
        val prefs = getSharedPreferences("track_prefs", MODE_PRIVATE)
        val aid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("aid") } catch (_: Exception) { 0 }
        val sid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("sid") } catch (_: Exception) { 0 }
        prefs.edit().putInt("${key}_aid", aid).putInt("${key}_sid", sid).apply()
        Log.d(TAG, "TRACK_SAVE: $key aid=$aid sid=$sid")
    }

    private fun restoreTrackPrefs() {
        val key = getFileKey()
        if (key.isEmpty()) return
        val prefs = getSharedPreferences("track_prefs", MODE_PRIVATE)
        val aid = prefs.getInt("${key}_aid", 0)
        val sid = prefs.getInt("${key}_sid", 0)
        Log.d(TAG, "TRACK_RESTORE: $key aid=$aid sid=$sid")
        if (aid > 0) playerView.setAudioTrack(aid)
        if (sid > 0) playerView.setSubtitleTrack(sid)
    }

    // ── List actions ─────────────────────────────────────────────────

    private fun showAudioList() {
        val tracks = playerView.getTracks().filter { it.type == "audio" }
        if (tracks.isEmpty()) return
        val currentAid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("aid") ?: 0 } catch (_: Exception) { 0 }
        listItems.clear()
        listItems.addAll(tracks.map { t ->
            ListItem(
                label = t.title ?: t.lang?.uppercase() ?: "Track ${t.id}",
                subtitle = t.codec,
                selected = t.id == currentAid
            )
        })
        listTitle.value = "Audio Track"
        listFocus.intValue = tracks.indexOfFirst { it.id == currentAid }.coerceAtLeast(0)
        listCallback = { idx -> playerView.setAudioTrack(tracks[idx].id); saveTrackPrefs() }
        screen.value = Screen.LIST_SELECT
    }

    private fun showSubtitleList() {
        val tracks = playerView.getTracks().filter { it.type == "sub" }
        val currentSid = try { dev.jdtech.mpv.MPVLib.getPropertyInt("sid") ?: 0 } catch (_: Exception) { 0 }
        listItems.clear()
        listItems.add(ListItem(label = "Off", selected = currentSid <= 0))
        listItems.addAll(tracks.map { t ->
            ListItem(
                label = t.title ?: t.lang?.uppercase() ?: "Track ${t.id}",
                subtitle = t.codec,
                selected = t.id == currentSid
            )
        })
        listItems.add(ListItem(label = "Load external file..."))
        listTitle.value = "Subtitles"
        val selectedIdx = if (currentSid <= 0) 0 else {
            val idx = tracks.indexOfFirst { it.id == currentSid }
            if (idx >= 0) idx + 1 else 0
        }
        listFocus.intValue = selectedIdx
        listCallback = { idx ->
            when {
                idx == 0 -> { playerView.disableSubtitles(); saveTrackPrefs() }
                idx == listItems.size - 1 -> {
                    subtitleBrowserLauncher.launch(
                        Intent(this, BrowserActivity::class.java)
                            .putExtra(BrowserActivity.EXTRA_FILE_MODE, "subtitle"))
                }
                else -> { playerView.setSubtitleTrack(tracks[idx - 1].id); saveTrackPrefs() }
            }
        }
        screen.value = Screen.LIST_SELECT
    }

    // ── Playback ─────────────────────────────────────────────────────

    fun startPlayback(savedInstanceState: Bundle?) {
        val server = intent.getStringExtra(EXTRA_SMB_SERVER)
        val share = intent.getStringExtra(EXTRA_SMB_SHARE)
        val path = intent.getStringExtra(EXTRA_SMB_PATH)
        val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)

        if (server != null && share != null && path != null) {
            val user = intent.getStringExtra(EXTRA_SMB_USER) ?: ""
            val pass = intent.getStringExtra(EXTRA_SMB_PASS) ?: ""
            playSmbFile(server, share, path, user, pass)
        } else if (localUri != null) {
            playFile(localUri)
        } else if (intent?.data != null) {
            playFile(intent.data.toString())
        } else {
            finish()
        }
    }

    private fun playSmbFile(server: String, share: String, path: String, user: String, pass: String) {
        smbServer = server; smbShare = share; smbPath = path; smbUser = user; smbPass = pass
        Thread {
            try {
                val httpUrl = smbStreamServer.start(server, share, path, user, pass)
                Log.d(TAG, "SMB proxy: $httpUrl")
                currentPlaybackUrl = httpUrl
                runOnUiThread { playerView.loadFile(httpUrl) }
            } catch (e: Exception) {
                Log.e(TAG, "SMB failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "SMB error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun playFile(path: String) {
        currentPlaybackUrl = path
        playerView.loadFile(path)
    }

    // ── MpvPlayerView.Listener ───────────────────────────────────────

    override fun onSubtitleTextChanged(text: String) {
        // Strip furigana annotations like 悟飯(ごはん) → 悟飯
        val clean = text.replace(Regex("\\([\\u3040-\\u309F\\u30A0-\\u30FF\\s]+\\)"), "")
        subtitleText.value = clean.ifEmpty { null }
        if (clean.isNotEmpty()) lastSubtitleText = clean
        if (clean.isNotEmpty()) Log.d(TAG, "SUB: $clean")
    }

    fun seekPauseAndLog(seconds: Double) {
        playerView.seekTo(seconds)
        playerView.pause()
        Log.d(TAG, "SEEKPAUSE: ${seconds}s, waiting for sub-text...")
    }

    override fun onPauseChanged(paused: Boolean) {
        isPaused.value = paused
    }

    override fun onPositionChanged(positionSec: Double, durationSec: Double) {
        this.positionSec.doubleValue = positionSec
        this.durationSec.doubleValue = durationSec
    }

    override fun onFileLoaded() {
        Log.d(TAG, "File loaded")
        // Intent extras override saved prefs (for debug/testing)
        val subTrack = intent.getIntExtra(EXTRA_SUB_TRACK, 0)
        val seekSec = intent.getFloatExtra(EXTRA_SEEK_SEC, 0f).toDouble()
        if (subTrack > 0 || seekSec > 0) {
            if (subTrack > 0) playerView.setSubtitleTrack(subTrack)
            if (seekSec > 0) playerView.seekTo(seekSec)
        } else {
            restoreTrackPrefs()
        }
        Log.d(TAG, "File loaded: key=${getFileKey()} subTrack=$subTrack seekSec=$seekSec")
    }
    override fun onFileEnded() { Log.d(TAG, "File ended"); finish() }
    override fun onTracksChanged() {}
    override fun onError(message: String) {
        Log.e(TAG, "Error: $message")
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    // ── Subtitle file loading ────────────────────────────────────────

    private fun handleSubtitleResult(data: Intent) {
        val mode = data.getStringExtra(BrowserActivity.RESULT_MODE) ?: return
        when (mode) {
            "local" -> {
                val path = Uri.parse(data.getStringExtra(BrowserActivity.RESULT_LOCAL_URI) ?: return).path ?: return
                try { dev.jdtech.mpv.MPVLib.command(arrayOf("sub-add", path, "select")) } catch (_: Exception) {}
            }
            "smb" -> {
                val server = data.getStringExtra(BrowserActivity.RESULT_SMB_SERVER) ?: return
                val share = data.getStringExtra(BrowserActivity.RESULT_SMB_SHARE) ?: return
                val sp = data.getStringExtra(BrowserActivity.RESULT_SMB_PATH) ?: return
                val user = data.getStringExtra(BrowserActivity.RESULT_SMB_USER) ?: ""
                val pass = data.getStringExtra(BrowserActivity.RESULT_SMB_PASS) ?: ""
                Thread {
                    try {
                        val tempFile = java.io.File(cacheDir, "subs_${sp.substringAfterLast("\\")}")
                        val config = com.hierynomus.smbj.SmbConfig.builder().build()
                        val client = com.hierynomus.smbj.SMBClient(config)
                        val conn = client.connect(server)
                        val auth = if (user.isNotEmpty()) com.hierynomus.smbj.auth.AuthenticationContext(user, pass.toCharArray(), "")
                        else com.hierynomus.smbj.auth.AuthenticationContext.guest()
                        val session = conn.authenticate(auth)
                        val ds = session.connectShare(share) as com.hierynomus.smbj.share.DiskShare
                        val file = ds.openFile(sp,
                            java.util.EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                            java.util.EnumSet.of(com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            com.hierynomus.mssmb2.SMB2ShareAccess.ALL,
                            com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
                            java.util.EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java))
                        tempFile.outputStream().use { out -> file.inputStream.copyTo(out) }
                        file.close(); ds.close(); session.close(); conn.close(); client.close()
                        runOnUiThread { dev.jdtech.mpv.MPVLib.command(arrayOf("sub-add", tempFile.absolutePath, "select")) }
                    } catch (_: Exception) {}
                }.start()
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun formatTime(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::playerView.isInitialized) {
            outState.putBoolean("wasPlaying", true)
            outState.putString("smbServer", smbServer)
            outState.putString("smbShare", smbShare)
            outState.putString("smbPath", smbPath)
            outState.putDouble("position", playerView.position)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::playerView.isInitialized) playerView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        dwellScope.cancel()
        smbStreamServer.stop()
        if (::playerView.isInitialized) playerView.destroy()
    }
}

