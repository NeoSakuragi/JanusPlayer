package com.janusplus

object LoginScreen {

    var serverUrl = "https://canneji.duckdns.org/janus"
    var username = ""
    var password = ""
    var focusedField = 0 // 0=server, 1=username, 2=password
    var errorMessage = ""
    var connecting = false

    @Volatile var pendingLogin = false

    fun render(rc: RenderCtx) {
        // Background
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        val centerX = rc.w / 2f
        val formW = rc.dp(400f).coerceAtMost(rc.w * 0.8f)
        val formX = centerX - formW / 2f
        var y = rc.h * 0.15f

        // Title
        val titleW = rc.font.measureText("Janus+", rc.sp(36))
        rc.text("Janus+", centerX - titleW / 2f, y, rc.sp(36), 0.733f, 0.525f, 0.988f)
        y += rc.dp(60f)

        // Fields
        val fieldH = rc.dp(48f)
        val fieldSpacing = rc.dp(16f)

        renderField(rc, "Server", serverUrl, formX, y, formW, fieldH, focusedField == 0, false)
        rc.tappable(formX, y, formW, fieldH) { focusedField = 0 }
        y += fieldH + fieldSpacing

        renderField(rc, "Username", username, formX, y, formW, fieldH, focusedField == 1, false)
        rc.tappable(formX, y, formW, fieldH) { focusedField = 1 }
        y += fieldH + fieldSpacing

        val maskedPass = "●".repeat(password.length)
        renderField(rc, "Password", maskedPass, formX, y, formW, fieldH, focusedField == 2, true)
        rc.tappable(formX, y, formW, fieldH) { focusedField = 2 }
        y += fieldH + rc.dp(24f)

        // Error
        if (errorMessage.isNotEmpty()) {
            val errW = rc.font.measureText(errorMessage, rc.sp(14))
            rc.text(errorMessage, centerX - errW / 2f, y, rc.sp(14), 1f, 0.32f, 0.32f)
            y += rc.dp(24f)
        }

        // Connect button
        val btnW = rc.dp(180f)
        val btnH = rc.dp(48f)
        val btnX = centerX - btnW / 2f
        rc.solid(btnX, y, btnW, btnH, 0.733f, 0.525f, 0.988f)
        val btnLabel = if (connecting) Lang.s("loading") else Lang.s("connect")
        val btnLabelW = rc.font.measureText(btnLabel, rc.sp(16))
        rc.text(btnLabel, centerX - btnLabelW / 2f, y + btnH * 0.65f, rc.sp(16), 1f, 1f, 1f)
        if (!connecting) {
            rc.tappable(btnX, y, btnW, btnH) { pendingLogin = true }
        }
    }

    private fun renderField(rc: RenderCtx, label: String, value: String, x: Float, y: Float,
                            w: Float, h: Float, focused: Boolean, isPassword: Boolean) {
        // Background
        rc.solid(x, y, w, h, 0.102f, 0.102f, 0.180f)
        if (focused) rc.border(x, y, w, h, rc.dp(2f), 0.733f, 0.525f, 0.988f)

        val textY = y + h * 0.65f
        if (value.isEmpty()) {
            rc.text(label, x + rc.dp(12f), textY, rc.sp(14), 0.4f, 0.4f, 0.4f)
        } else {
            rc.textClipped(value, x + rc.dp(12f), textY, rc.sp(14), w - rc.dp(24f), 1f, 1f, 1f)
        }

        // Cursor blink
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
            val cursorX = x + rc.dp(12f) + rc.font.measureText(value, rc.sp(14))
            rc.solid(cursorX, y + rc.dp(10f), rc.dp(2f), h - rc.dp(20f), 0.733f, 0.525f, 0.988f)
        }
    }

    fun onChar(c: Char) {
        when (focusedField) {
            0 -> serverUrl += c
            1 -> username += c
            2 -> password += c
        }
    }

    fun onBackspace() {
        when (focusedField) {
            0 -> if (serverUrl.isNotEmpty()) serverUrl = serverUrl.dropLast(1)
            1 -> if (username.isNotEmpty()) username = username.dropLast(1)
            2 -> if (password.isNotEmpty()) password = password.dropLast(1)
        }
    }

    fun onTab() {
        focusedField = (focusedField + 1) % 3
    }
}
