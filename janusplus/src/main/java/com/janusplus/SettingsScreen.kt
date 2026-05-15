package com.janusplus

object SettingsScreen {

    @Volatile var pendingLogout = false

    fun render(rc: RenderCtx) {
        val pad = rc.dp(32f)
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        var y = pad

        // Header
        rc.text("← " + Lang.s("settings"), pad, y + rc.dp(28f), rc.sp(24), 1f, 1f, 1f)
        rc.tappable(0f, y, rc.dp(100f), rc.dp(40f)) { rc.state.screen = Screen.HOME }
        y += rc.dp(60f)

        // Server URL
        rc.text(Lang.s("server"), pad, y, rc.sp(12), 0.533f, 0.533f, 0.533f)
        y += rc.dp(20f)
        rc.text(LoginScreen.serverUrl, pad, y, rc.sp(14), 0.8f, 0.8f, 0.8f)
        y += rc.dp(40f)

        // Language
        rc.text("Language", pad, y, rc.sp(12), 0.533f, 0.533f, 0.533f)
        y += rc.dp(20f)
        val langs = listOf("ja" to "日本語", "en" to "English", "fr" to "Français")
        var lx = pad
        for ((code, label) in langs) {
            val selected = Lang.current == code
            val btnW = rc.font.measureText(label, rc.sp(14)) + rc.dp(24f)
            val bgR = if (selected) 0.733f else 0.165f
            val bgG = if (selected) 0.525f else 0.165f
            val bgB = if (selected) 0.988f else 0.227f
            rc.solid(lx, y, btnW, rc.dp(36f), bgR, bgG, bgB)
            rc.text(label, lx + rc.dp(12f), y + rc.dp(24f), rc.sp(14), 1f, 1f, 1f)
            val langCode = code
            rc.tappable(lx, y, btnW, rc.dp(36f)) { Lang.current = langCode }
            lx += btnW + rc.dp(8f)
        }
        y += rc.dp(60f)

        // Logout
        val logoutW = rc.dp(160f)
        rc.solid(pad, y, logoutW, rc.dp(44f), 0.3f, 0.1f, 0.1f)
        rc.text(Lang.s("logout"), pad + rc.dp(20f), y + rc.dp(30f), rc.sp(14), 1f, 0.4f, 0.4f)
        rc.tappable(pad, y, logoutW, rc.dp(44f)) { pendingLogout = true }
    }
}
