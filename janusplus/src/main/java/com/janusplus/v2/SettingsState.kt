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
    private var screenH = 0f

    private var focusIdx = 0
    private var rowCount = 0
    private var rowYPositions = FloatArray(20)
    private var animTime = 0f

    override fun init(app: App) {
        app.scrollY = 0f
        focusIdx = 0
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(32f)
        titleSize = rc.sp(28)
        labelSize = rc.sp(16)
        valueSize = rc.sp(13)
        rowH = rc.dp(56f)
        sectionGap = rc.dp(24f)
        contentW = rc.w - pad * 2
        screenH = rc.h
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        for (key in keys) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focusIdx > 0) {
                        focusIdx--
                        scrollFocusIntoView(app)
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focusIdx < rowCount - 1) {
                        focusIdx++
                        scrollFocusIntoView(app)
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Future: invoke row actions
                }
                KeyEvent.KEYCODE_BACK -> {
                    app.goBack()
                    return
                }
            }
        }
    }

    private fun scrollFocusIntoView(app: App) {
        if (focusIdx >= rowCount || screenH <= 0) return
        val rowAbsY = rowYPositions[focusIdx] + app.scrollY
        val rowBottom = rowAbsY + rowH
        if (rowBottom > app.scrollY + screenH) {
            app.smoothScrollTo(rowBottom - screenH + pad)
        }
        if (rowAbsY < app.scrollY) {
            app.smoothScrollTo((rowAbsY - pad).coerceAtLeast(0f))
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)
        animTime += 0.016f

        val scrollY = app.scrollY
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        var y = pad - scrollY
        var rowIdx = 0

        // Header
        val backFocused = false // back button not in row list, handled by BACK key
        rc.text("←", pad, y + rc.dp(28f), rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, y, rc.dp(60f), rc.dp(50f)) { app.goBack() }
        rc.text(Lang.s("settings"), pad + rc.dp(34f), y + rc.dp(28f), titleSize, 1f, 1f, 1f)
        y += rc.dp(50f) + sectionGap

        // ── Account ──
        y = drawSection(rc, y, Lang.s("account"))
        y = drawRow(rc, y, rowIdx++, Lang.s("logout"), "bruno", 0.9f, 0.3f, 0.3f)
        y += sectionGap

        // ── Server ──
        y = drawSection(rc, y, Lang.s("server"))
        y = drawRow(rc, y, rowIdx++, Lang.s("server_url"), "canneji.duckdns.org")
        y = drawRow(rc, y, rowIdx++, Lang.s("check_update"), "")
        y += sectionGap

        // ── Playback ──
        y = drawSection(rc, y, Lang.s("playback"))
        y = drawRow(rc, y, rowIdx++, Lang.s("hardware_decoding"), "OFF")
        y += sectionGap

        // ── Subtitles ──
        y = drawSection(rc, y, Lang.s("subtitles"))
        y = drawRow(rc, y, rowIdx++, Lang.s("font"), "Noto Sans JP")
        y = drawRow(rc, y, rowIdx++, Lang.s("font_size"), "20px")
        y += sectionGap

        // ── Anki ──
        y = drawSection(rc, y, "Anki")
        y = drawRow(rc, y, rowIdx++, "AnkiConnect", "http://127.0.0.1:8765")
        y = drawRow(rc, y, rowIdx++, Lang.s("deck"), "Default")
        y += sectionGap

        // ── Downloads ──
        y = drawSection(rc, y, Lang.s("downloads"))
        y = drawRow(rc, y, rowIdx++, Lang.s("downloaded_episodes"), "0")
        y += sectionGap

        // ── About ──
        y = drawSection(rc, y, Lang.s("about"))
        y = drawRow(rc, y, rowIdx++, "Version", "0.7")
        y = drawRow(rc, y, rowIdx++, Lang.s("language"), Lang.current.uppercase())

        rowCount = rowIdx

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f, volatile = true)
    }

    private fun drawSection(rc: RC, y: Float, title: String): Float {
        rc.text(title.uppercase(), pad, y + rc.dp(18f), rc.sp(11), 0.733f, 0.525f, 0.988f)
        rc.solid(pad, y + rc.dp(26f), contentW, rc.dp(1f), 0.2f, 0.2f, 0.3f)
        return y + rc.dp(32f)
    }

    private fun drawRow(rc: RC, y: Float, idx: Int, label: String, value: String,
                        vr: Float = 0.533f, vg: Float = 0.533f, vb: Float = 0.533f): Float {
        if (idx < rowYPositions.size) rowYPositions[idx] = y

        val focused = idx == focusIdx
        if (focused) {
            val pulse = 0.15f + 0.05f * kotlin.math.sin(animTime * 4f).toFloat()
            rc.solid(pad - rc.dp(8f), y, contentW + rc.dp(16f), rowH, 0.733f, 0.525f, 0.988f, pulse)
            rc.border(pad - rc.dp(8f), y, contentW + rc.dp(16f), rowH, rc.dp(3f), 0.733f, 0.525f, 0.988f)
        }

        rc.text(label, pad, y + rc.dp(24f), labelSize, 1f, 1f, 1f)
        if (value.isNotEmpty()) {
            val valueW = rc.font.measureText(value, valueSize)
            rc.text(value, rc.w - pad - valueW, y + rc.dp(24f), valueSize, vr, vg, vb)
        }
        rc.solid(pad, y + rowH - rc.dp(1f), contentW, rc.dp(1f), 0.08f, 0.08f, 0.14f)
        return y + rowH
    }

    override fun cleanup(app: App) {
        app.scrollY = 0f
    }
}
