package com.janusplus.v2

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
        allText.add(Lang.s("subtitles")); allText.add(Lang.s("font")); allText.add("Noto Sans JP")
        allText.add(Lang.s("font_size")); allText.add("20px")
        allText.add("Anki"); allText.add("AnkiConnect"); allText.add("http://127.0.0.1:8765")
        allText.add(Lang.s("deck")); allText.add("Default")
        allText.add(Lang.s("downloads")); allText.add(Lang.s("downloaded_episodes")); allText.add("0")
        allText.add(Lang.s("about")); allText.add("Version"); allText.add("0.7")
        allText.add(Lang.s("language")); allText.add(Lang.current.uppercase())
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
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

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
        y = drawRow(rc, y, Lang.s("check_update"), updateStatus) {
            checkForUpdate(app)
        }
        y += sectionGap

        y = drawSection(rc, y, Lang.s("subtitles"))
        y = drawRow(rc, y, Lang.s("font"), "Noto Sans JP")
        y = drawRow(rc, y, Lang.s("font_size"), "20px")
        y += sectionGap

        y = drawSection(rc, y, "Anki")
        y = drawRow(rc, y, "AnkiConnect", "http://127.0.0.1:8765")
        y = drawRow(rc, y, Lang.s("deck"), "Default")
        y += sectionGap

        y = drawSection(rc, y, Lang.s("downloads"))
        y = drawRow(rc, y, Lang.s("downloaded_episodes"), "0")
        y += sectionGap

        y = drawSection(rc, y, Lang.s("about"))
        y = drawRow(rc, y, "Version", "0.7")
        y = drawRow(rc, y, Lang.s("language"), Lang.current.uppercase())

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
