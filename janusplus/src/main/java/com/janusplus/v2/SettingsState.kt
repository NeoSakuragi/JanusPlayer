package com.janusplus.v2

import android.view.KeyEvent
import com.janusplus.Lang

class SettingsState : GameState {

    private var pad = 0f
    private var titleSize = 0
    private var labelSize = 0
    private var valueSize = 0
    private var rowH = 0f
    private var sectionGap = 0f
    private var contentW = 0f
    private var layoutDone = false

    override fun init(app: App) {
        app.scrollY = 0f
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(32f)
        titleSize = rc.sp(28)
        labelSize = rc.sp(16)
        valueSize = rc.sp(13)
        rowH = rc.dp(56f)
        sectionGap = rc.dp(24f)
        contentW = rc.w - pad * 2
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        for (key in keys) {
            if (key == KeyEvent.KEYCODE_BACK) {
                app.goBack()
                return
            }
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        val scrollY = app.scrollY

        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        var y = pad - scrollY

        // Header
        rc.text("←", pad, y + rc.dp(28f), rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, y, rc.dp(60f), rc.dp(50f)) { app.goBack() }
        rc.text(Lang.s("settings"), pad + rc.dp(34f), y + rc.dp(28f), titleSize, 1f, 1f, 1f)
        y += rc.dp(50f) + sectionGap

        // ── Account ──
        y = drawSection(rc, y, Lang.s("account"))
        y = drawRow(rc, y, scrollY, Lang.s("logout"), "bruno", 0.9f, 0.3f, 0.3f)
        y += sectionGap

        // ── Server ──
        y = drawSection(rc, y, Lang.s("server"))
        y = drawRow(rc, y, scrollY, Lang.s("server_url"), "canneji.duckdns.org")
        y = drawRow(rc, y, scrollY, Lang.s("check_update"), "")
        y += sectionGap

        // ── Playback ──
        y = drawSection(rc, y, Lang.s("playback"))
        y = drawRow(rc, y, scrollY, Lang.s("hardware_decoding"), "OFF")
        y += sectionGap

        // ── Subtitles ──
        y = drawSection(rc, y, Lang.s("subtitles"))
        y = drawRow(rc, y, scrollY, Lang.s("font"), "Noto Sans JP")
        y = drawRow(rc, y, scrollY, Lang.s("font_size"), "20px")
        y += sectionGap

        // ── Anki ──
        y = drawSection(rc, y, "Anki")
        y = drawRow(rc, y, scrollY, "AnkiConnect", "http://127.0.0.1:8765")
        y = drawRow(rc, y, scrollY, Lang.s("deck"), "Default")
        y += sectionGap

        // ── Downloads ──
        y = drawSection(rc, y, Lang.s("downloads"))
        y = drawRow(rc, y, scrollY, Lang.s("downloaded_episodes"), "0")
        y += sectionGap

        // ── About ──
        y = drawSection(rc, y, Lang.s("about"))
        y = drawRow(rc, y, scrollY, "Version", "0.7")
        y = drawRow(rc, y, scrollY, Lang.s("language"), Lang.current.uppercase())

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    private fun drawSection(rc: RC, y: Float, title: String): Float {
        rc.text(title.uppercase(), pad, y + rc.dp(18f), rc.sp(11), 0.733f, 0.525f, 0.988f)
        rc.solid(pad, y + rc.dp(26f), contentW, rc.dp(1f), 0.2f, 0.2f, 0.3f)
        return y + rc.dp(32f)
    }

    private fun drawRow(rc: RC, y: Float, scrollY: Float, label: String, value: String,
                        vr: Float = 0.533f, vg: Float = 0.533f, vb: Float = 0.533f,
                        action: (() -> Unit)? = null): Float {
        rc.text(label, pad, y + rc.dp(24f), labelSize, 1f, 1f, 1f)
        if (value.isNotEmpty()) {
            val valueW = rc.font.measureText(value, valueSize)
            rc.text(value, rc.w - pad - valueW, y + rc.dp(24f), valueSize, vr, vg, vb)
        }
        if (action != null) {
            rc.tappable(0f, y, rc.w, rowH, action)
        }
        rc.solid(pad, y + rowH - rc.dp(1f), contentW, rc.dp(1f), 0.08f, 0.08f, 0.14f)
        return y + rowH
    }

    override fun cleanup(app: App) {
        app.scrollY = 0f
    }
}
