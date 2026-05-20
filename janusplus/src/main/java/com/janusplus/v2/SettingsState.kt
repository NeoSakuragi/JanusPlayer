package com.janusplus.v2

import com.janusplus.AnkiDroidClient
import com.janusplus.AppUpdater
import com.janusplus.GlyphAtlas
import com.janusplus.Lang

class SettingsState : GameState {

    private var pad = 0f; private var titleSize = 0; private var labelSize = 0; private var valueSize = 0
    private var rowH = 0f; private var sectionGap = 0f; private var contentW = 0f
    private var layoutDone = false; private var screenH = 0f

    private var focusIdx = 0; private var rowCount = 0
    private var rowYPositions = FloatArray(20); private var animTime = 0f
    private val rowActions = mutableListOf<(() -> Unit)?>()

    private val sizedAtlases = HashMap<Int, GlyphAtlas>()
    private var density = 1f

    @Volatile private var updateStatus = ""
    @Volatile private var cacheStatus = ""
    @Volatile private var ankiStatus = ""
    @Volatile private var modelStatus = ""

    override fun init(app: App) {
        app.scrollY = 0f; focusIdx = 0; density = app.density
        buildAtlases(app)
    }

    private fun sp(v: Int): Int = (v * density).toInt()

    private fun buildAtlases(app: App) {
        val tf = app.defaultTypeface
        val d = app.density
        val allText = mutableListOf<String>()
        allText.add("←"); allText.add(Lang.s("settings"))
        allText.add(Lang.s("account")); allText.add(Lang.s("logout")); allText.add("bruno")
        allText.add(Lang.s("server")); allText.add(Lang.s("server_url")); allText.add("canneji.duckdns.org")
        allText.add(Lang.s("check_update"))
        allText.add("Checking..."); allText.add("Up to date"); allText.add("Downloading...")
        allText.add("Installing..."); allText.add("Failed")
        allText.add("ON"); allText.add("OFF")
        allText.add("Display"); allText.add("E-Ink mode")
        allText.add("Debug"); allText.add("Show load timings")
        allText.add("Clear cache"); allText.add("cleared files KB")
        allText.add("Test Anki card"); allText.add("sent"); allText.add("dupe"); allText.add("no ankidroid"); allText.add("no perm")
        allText.add(Lang.s("subtitles")); allText.add(Lang.s("font")); allText.add("Noto Sans JP")
        allText.add(Lang.s("font_size")); allText.add("20px")
        allText.add("Anki"); allText.add("AnkiConnect"); allText.add("http://127.0.0.1:8765")
        allText.add(Lang.s("deck")); allText.add("Default")
        allText.add(Lang.s("downloads")); allText.add(Lang.s("downloaded_episodes")); allText.add("0")
        allText.add(Lang.s("about")); allText.add("Version"); allText.add("0.7")
        allText.add(Lang.s("language")); allText.add("English"); allText.add("Français"); allText.add("日本語")
        allText.add("0123456789fps")

        val ts = app.texArray.size
        for (spVal in listOf(10, 11, 13, 16, 22, 28)) {
            val pxSize = sp(spVal)
            val atlas = GlyphAtlas(tf, spVal * d)
            app.uploadGlyphAtlas(atlas, atlas.build(allText, ts))
            sizedAtlases[pxSize] = atlas
        }
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(32f); titleSize = rc.sp(28); labelSize = rc.sp(16)
        valueSize = rc.sp(13); rowH = rc.dp(56f); sectionGap = rc.dp(24f)
        contentW = rc.w - pad * 2; screenH = rc.h; layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        for (a in actions) when (a) {
            Action.UP -> { if (focusIdx > 0) { focusIdx--; scrollFocusIntoView(app) } }
            Action.DOWN -> { if (focusIdx < rowCount - 1) { focusIdx++; scrollFocusIntoView(app) } }
            Action.SELECT -> { rowActions.getOrNull(focusIdx)?.invoke() }
            Action.BACK -> { app.goBack(); return }
            else -> {}
        }
    }

    private fun scrollFocusIntoView(app: App) {
        if (focusIdx >= rowCount || screenH <= 0) return
        val rowAbsY = rowYPositions[focusIdx] + app.scrollY
        val rowBottom = rowAbsY + rowH
        if (rowBottom > app.scrollY + screenH) app.smoothScrollTo(rowBottom - screenH + pad)
        if (rowAbsY < app.scrollY) app.smoothScrollTo((rowAbsY - pad).coerceAtLeast(0f))
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)
        animTime += 0.016f

        for ((size, atlas) in sizedAtlases) rc.atlases[size] = atlas

        val scrollY = app.scrollY
        rc.bg()

        var y = pad - scrollY
        rowActions.clear()

        rc.text("←", pad, y + rc.dp(28f), rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, y, rc.dp(60f), rc.dp(50f)) { app.goBack() }
        rc.text(Lang.s("settings"), pad + rc.dp(34f), y + rc.dp(28f), titleSize, 1f, 1f, 1f)
        y += rc.dp(50f) + sectionGap

        y = drawSection(rc, y, Lang.s("account"))
        y = drawRow(rc, y, Lang.s("logout"), "bruno", vr = 0.9f, vg = 0.3f, vb = 0.3f)
        y += sectionGap

        y = drawSection(rc, y, Lang.s("server"))
        y = drawRow(rc, y, Lang.s("server_url"), "canneji.duckdns.org")
        val versionName = try { app.context.packageManager.getPackageInfo(app.context.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        y = drawRow(rc, y, Lang.s("check_update"), updateStatus.ifEmpty { "v$versionName" }) {
            checkForUpdate(app)
        }
        y += sectionGap

        y = drawSection(rc, y, Lang.s("subtitles"))
        y = drawRow(rc, y, Lang.s("font"), "Noto Sans JP")
        y = drawRow(rc, y, Lang.s("font_size"), "20px")
        y += sectionGap

        y = drawSection(rc, y, "Anki")
        y = drawRow(rc, y, Lang.s("deck"), app.ankiDeckName) {
            cycleAnkiDeck(app)
        }
        y += sectionGap

        y = drawSection(rc, y, "Display")
        y = drawRow(rc, y, "E-Ink mode", if (app.einkMode) "ON" else "OFF") {
            app.einkMode = !app.einkMode
            val prefs = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("eink_mode", app.einkMode).apply()
        }
        y += sectionGap

        y = drawSection(rc, y, Lang.s("downloads"))
        y = drawRow(rc, y, Lang.s("downloaded_episodes"), "0")
        y += sectionGap

        y = drawSection(rc, y, "Debug")
        y = drawRow(rc, y, "Show load timings", if (app.debugTimings) "ON" else "OFF") {
            app.debugTimings = !app.debugTimings
            val prefs = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putBoolean("debug_timings", app.debugTimings).apply()
        }
        y = drawRow(rc, y, "Clear local cache", cacheStatus) {
            clearCache(app)
        }
        y = drawRow(rc, y, "Test Anki card", ankiStatus) {
            testAnkiCard(app)
        }
        y = drawRow(rc, y, "List models", modelStatus) {
            listAnkiModels(app)
        }
        y += sectionGap

        y = drawSection(rc, y, Lang.s("about"))
        y = drawRow(rc, y, Lang.s("language"), when (Lang.current) {
            "ja" -> "日本語"; "fr" -> "Français"; else -> "English"
        }) {
            Lang.current = when (Lang.current) { "en" -> "fr"; "fr" -> "ja"; else -> "en" }
            val prefs = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("language", Lang.current).apply()
            buildAtlases(app)
        }

        rowCount = rowActions.size

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    private fun checkForUpdate(app: App) {
        val api = app.api ?: run { updateStatus = "Failed"; return }
        updateStatus = "Checking..."
        val updater = AppUpdater(app.context as android.app.Activity, "https://canneji.duckdns.org/janus")
        updater.token = api.token
        updater.checkForUpdate { info ->
            if (info != null) {
                updateStatus = "v${info.versionName} available"
                app.onMainThread?.invoke {
                    updater.downloadAndInstall(info) { progress ->
                        updateStatus = if (progress < 0) "Failed"
                            else if (progress > 100) "Installing..."
                            else "Downloading... ${progress}%"
                    }
                }
            } else {
                updateStatus = "Up to date"
            }
        }
    }

    private fun testAnkiCard(app: App) {
        try {
            val client = AnkiDroidClient(app.context)
            if (!client.isAvailable()) { ankiStatus = "no ankidroid"; return }
            if (!client.hasPermission()) {
                app.onMainThread?.invoke {
                    val activity = app.context as? android.app.Activity
                    if (activity != null) client.requestPermission(activity)
                }
                ankiStatus = "requesting perm..."
                return
            }
            ankiStatus = "sending..."
            kotlin.concurrent.thread {
                try {
                    // Generate a test screenshot
                    val imgFile = java.io.File(app.context.cacheDir, "anki_test.jpg")
                    val bmp = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(0xFF1A1A2E.toInt())
                    val paint = android.graphics.Paint().apply {
                        color = 0xFFBB86FC.toInt(); textSize = 64f; isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText("飲む", 220f, 200f, paint)
                    imgFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                    bmp.recycle()

                    // Generate a test audio (silent WAV, 1 second)
                    val audioFile = java.io.File(app.context.cacheDir, "anki_test.mp3")
                    val sr = 44100; val samples = sr
                    val wavSize = 44 + samples * 2
                    val wav = java.io.ByteArrayOutputStream(wavSize)
                    fun writeShort(v: Int) { wav.write(v and 0xFF); wav.write((v shr 8) and 0xFF) }
                    fun writeInt(v: Int) { writeShort(v); writeShort(v shr 16) }
                    wav.write("RIFF".toByteArray()); writeInt(wavSize - 8)
                    wav.write("WAVEfmt ".toByteArray()); writeInt(16); writeShort(1); writeShort(1)
                    writeInt(sr); writeInt(sr * 2); writeShort(2); writeShort(16)
                    wav.write("data".toByteArray()); writeInt(samples * 2)
                    for (i in 0 until samples) writeShort((16000 * kotlin.math.sin(440.0 * 2 * Math.PI * i / sr)).toInt())
                    audioFile.writeBytes(wav.toByteArray())

                    val result = client.addCard(AnkiDroidClient.CardInfo(
                        expression = "飲む",
                        reading = "飲[の]む",
                        meaning = "to drink",
                        sentence = "水を飲む",
                        source = "Janus Test",
                        jlpt = "N5",
                        screenshotFile = imgFile,
                        audioFile = audioFile,
                    ))
                    ankiStatus = when (result) {
                        is AnkiDroidClient.Result.Success -> "sent id=${result.noteId}"
                        is AnkiDroidClient.Result.Duplicate -> "dupe"
                        is AnkiDroidClient.Result.NotInstalled -> "no ankidroid"
                        is AnkiDroidClient.Result.NoPermission -> "no perm"
                        is AnkiDroidClient.Result.Error -> "err: ${result.message}"
                    }
                } catch (e: Exception) {
                    ankiStatus = "err: ${e.message}"
                }
            }
        } catch (e: Exception) {
            ankiStatus = "err: ${e.message}"
        }
    }

    private fun listAnkiModels(app: App) {
        val client = AnkiDroidClient(app.context)
        if (!client.isAvailable()) { modelStatus = "no ankidroid"; return }
        if (!client.hasPermission()) { modelStatus = "no perm"; return }
        kotlin.concurrent.thread {
            try {
                val models = client.listModels()
                modelStatus = "${models.size} models"
            } catch (e: Exception) { modelStatus = "err: ${e.message}" }
        }
    }

    private var ankiDecks = listOf<String>()

    private fun cycleAnkiDeck(app: App) {
        if (ankiDecks.isEmpty()) {
            try {
                val api = com.ichi2.anki.api.AddContentApi(app.context)
                val decks = api.deckList
                if (decks != null) ankiDecks = decks.values.sorted()
            } catch (_: Exception) {}
        }
        if (ankiDecks.isEmpty()) return
        val idx = ankiDecks.indexOf(app.ankiDeckName)
        app.ankiDeckName = ankiDecks[(idx + 1) % ankiDecks.size]
        app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("anki_deck", app.ankiDeckName).apply()
    }

    private fun clearCache(app: App) {
        kotlin.concurrent.thread {
            val cacheDir = app.context.cacheDir
            var count = 0
            var bytes = 0L
            cacheDir.walkTopDown().filter { it.isFile }.forEach {
                bytes += it.length()
                it.delete()
                count++
            }
            cacheStatus = "cleared $count files (${bytes / 1024}KB)"
        }
    }

    private fun drawSection(rc: RC, y: Float, title: String): Float {
        rc.text(title.uppercase(), pad, y + rc.dp(18f), rc.sp(11), 0.733f, 0.525f, 0.988f)
        rc.solid(pad, y + rc.dp(26f), contentW, rc.dp(1f), 0.2f, 0.2f, 0.3f)
        return y + rc.dp(32f)
    }

    private fun drawRow(rc: RC, y: Float, label: String, value: String,
                        vr: Float = 0.533f, vg: Float = 0.533f, vb: Float = 0.533f,
                        action: (() -> Unit)? = null): Float {
        val idx = rowActions.size
        rowActions.add(action)
        if (idx < rowYPositions.size) rowYPositions[idx] = y

        val focused = idx == focusIdx
        if (focused) {
            val pulse = 0.15f + 0.05f * kotlin.math.sin(animTime * 4f).toFloat()
            rc.solid(pad - rc.dp(8f), y, contentW + rc.dp(16f), rowH, 0.733f, 0.525f, 0.988f, pulse)
            rc.border(pad - rc.dp(8f), y, contentW + rc.dp(16f), rowH, rc.dp(3f), 0.733f, 0.525f, 0.988f)
        }

        rc.text(label, pad, y + rc.dp(24f), labelSize, 1f, 1f, 1f)
        if (value.isNotEmpty()) {
            val valueW = rc.measureText(value, valueSize)
            rc.text(value, rc.w - pad - valueW, y + rc.dp(24f), valueSize, vr, vg, vb)
        }
        rc.solid(pad, y + rowH - rc.dp(1f), contentW, rc.dp(1f), 0.08f, 0.08f, 0.14f)
        val rowIndex = idx
        rc.tappable(pad - rc.dp(8f), y, contentW + rc.dp(16f), rowH) {
            focusIdx = rowIndex
            action?.invoke()
        }
        return y + rowH
    }

    override fun cleanup(app: App) { app.scrollY = 0f }
}
