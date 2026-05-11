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
    }

    // ── UI State Machine ─────────────────────────────────────────────

    enum class Screen { PLAYING, CONTROLS, LIST_SELECT }

    // Control bar items: [seekbar, rewind, play, forward, audio, subs]
    private val CTRL_SEEK = 0
    private val CTRL_REW = 1
    private val CTRL_PLAY = 2
    private val CTRL_FWD = 3
    private val CTRL_AUDIO = 4
    private val CTRL_SUBS = 5
    private val CTRL_COUNT = 6

    private val screen = mutableStateOf(Screen.PLAYING)
    private val controlFocus = mutableIntStateOf(CTRL_PLAY)
    private val listItems = mutableStateListOf<ListItem>()
    private val listTitle = mutableStateOf("")
    private val listFocus = mutableIntStateOf(0)
    private var listCallback: ((Int) -> Unit)? = null

    // Player state
    private val isPaused = mutableStateOf(false)
    private val positionSec = mutableDoubleStateOf(0.0)
    private val durationSec = mutableDoubleStateOf(0.0)
    private val subtitleText = mutableStateOf<String?>(null)

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

        Box(modifier = Modifier.fillMaxSize()) {

            // Subtitle display
            if (subs != null && subs!!.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (currentScreen == Screen.CONTROLS) 140.dp else 48.dp)
                        .padding(horizontal = 32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    androidx.compose.material3.Text(
                        text = subs!!,
                        color = Color.White,
                        fontSize = 22.sp,
                    )
                }
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

            // Buttons row
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CtrlButton("⏪", "−10s", CTRL_REW, focusIdx)
                Spacer(Modifier.width(16.dp))
                // Play/pause — bigger
                val ppFocused = focusIdx == CTRL_PLAY
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (ppFocused) 68.dp else 60.dp)
                        .background(
                            if (ppFocused) Color(0xFFBB86FC) else Color(0xFF333344),
                            CircleShape
                        )
                        .then(if (ppFocused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                ) {
                    androidx.compose.material3.Text(
                        if (paused) "▶" else "⏸",
                        color = Color.White,
                        fontSize = 26.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                CtrlButton("⏩", "+10s", CTRL_FWD, focusIdx)
                Spacer(Modifier.width(32.dp))
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
    // Every (screen, key) pair maps to exactly one transition.
    // transition() returns: consumed (Boolean)
    // Side effects: screen change, focus change, player action.
    //
    //  PLAYING:
    //    BACK           → exit app
    //    LEFT/RIGHT     → seek ±10s, stay PLAYING
    //    PLAY_PAUSE     → toggle pause, stay PLAYING
    //    any other dpad → pause + go CONTROLS(focus=PLAY)
    //
    //  CONTROLS(focusIdx):
    //    BACK           → resume + go PLAYING
    //    UP             → if on buttons: move to SEEK. if on SEEK: resume + go PLAYING
    //    DOWN           → if on SEEK: move to PLAY button
    //    LEFT           → if SEEK: seek -10s. else: move focus left (clamp)
    //    RIGHT          → if SEEK: seek +10s. else: move focus right (clamp)
    //    CENTER/ENTER   → activate focused control:
    //                       SEEK  → resume + go PLAYING
    //                       REW   → seek -10
    //                       PLAY  → toggle pause
    //                       FWD   → seek +10
    //                       AUDIO → go LIST_SELECT(audio tracks)
    //                       SUBS  → go LIST_SELECT(subtitle tracks)
    //    PLAY_PAUSE     → toggle pause, stay CONTROLS
    //
    //  LIST_SELECT(focusIdx):
    //    BACK           → go CONTROLS (restore focus to AUDIO or SUBS)
    //    UP             → move focus up (clamp)
    //    DOWN           → move focus down (clamp)
    //    CENTER/ENTER   → apply selection + go CONTROLS
    //    LEFT           → same as BACK
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
                KeyEvent.KEYCODE_DPAD_LEFT -> playerView.seekRelative(-10)
                KeyEvent.KEYCODE_DPAD_RIGHT -> playerView.seekRelative(10)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playerView.togglePause()
                else -> { playerView.pause(); goto(Screen.CONTROLS, CTRL_PLAY) }
            }

            Screen.CONTROLS -> {
                val f = controlFocus.intValue
                when (key) {
                    KeyEvent.KEYCODE_BACK -> { playerView.play(); goto(Screen.PLAYING) }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playerView.togglePause()

                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        f == CTRL_SEEK -> { playerView.play(); goto(Screen.PLAYING) }
                        else -> controlFocus.intValue = CTRL_SEEK
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> when {
                        f == CTRL_SEEK -> controlFocus.intValue = CTRL_PLAY
                        else -> {} // already on button row, nowhere to go
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> when {
                        f == CTRL_SEEK -> playerView.seekRelative(-10)
                        f > CTRL_REW -> controlFocus.intValue = f - 1
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                        f == CTRL_SEEK -> playerView.seekRelative(10)
                        f < CTRL_SUBS -> controlFocus.intValue = f + 1
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when (f) {
                        CTRL_SEEK -> { playerView.play(); goto(Screen.PLAYING) }
                        CTRL_REW -> playerView.seekRelative(-10)
                        CTRL_PLAY -> playerView.togglePause()
                        CTRL_FWD -> playerView.seekRelative(10)
                        CTRL_AUDIO -> { listReturnFocus = CTRL_AUDIO; showAudioList() }
                        CTRL_SUBS -> { listReturnFocus = CTRL_SUBS; showSubtitleList() }
                    }
                    else -> return false
                }
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
        screen.value = s
        if (focus >= 0) controlFocus.intValue = focus
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
        listCallback = { idx -> playerView.setAudioTrack(tracks[idx].id) }
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
                idx == 0 -> playerView.disableSubtitles()
                idx == listItems.size - 1 -> {
                    subtitleBrowserLauncher.launch(
                        Intent(this, BrowserActivity::class.java)
                            .putExtra(BrowserActivity.EXTRA_FILE_MODE, "subtitle"))
                }
                else -> playerView.setSubtitleTrack(tracks[idx - 1].id)
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
        subtitleText.value = text.ifEmpty { null }
    }

    override fun onPauseChanged(paused: Boolean) {
        isPaused.value = paused
    }

    override fun onPositionChanged(positionSec: Double, durationSec: Double) {
        this.positionSec.doubleValue = positionSec
        this.durationSec.doubleValue = durationSec
    }

    override fun onFileLoaded() { Log.d(TAG, "File loaded") }
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
        smbStreamServer.stop()
        if (::playerView.isInitialized) playerView.destroy()
    }
}

