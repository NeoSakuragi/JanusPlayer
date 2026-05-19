package com.janusplus.v2

import com.janusplus.GlyphAtlas

class KeyTestState : GameState {

    private val keyLog = mutableListOf<String>()
    private var atlas: GlyphAtlas? = null
    private var atlasPx = 0

    private var logFile: java.io.File? = null

    override fun init(app: App) {
        logFile = java.io.File(app.context.cacheDir, "key_test.log")
        logFile?.writeText("KeyTest started\n")
        val tf = app.defaultTypeface
        val d = app.density
        atlasPx = (16 * d).toInt()
        val a = GlyphAtlas(tf, 16 * d)
        val texts = listOf(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789:_=()x KEY KEYCODE DPAD CENTER ENTER BACK MENU BUTTON MEDIA PLAY PAUSE REWIND FORWARD",
            "Press any button on the remote",
        )
        app.uploadGlyphAtlas(a, a.build(texts, app.texArray.size))
        atlas = a
        keyLog.add("Ready — press buttons")
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        // KeyTest also listens to raw keycodes for diagnostics
        while (true) {
            val code = app.keyQueue.poll() ?: break
            val name = try { android.view.KeyEvent.keyCodeToString(code) } catch (_: Exception) { "UNKNOWN" }
            val action = Input.map(code)
            val entry = "$name ($code) → ${action ?: "unmapped"}"
            keyLog.add(entry)
            logFile?.appendText("$entry\n")
            android.util.Log.i("KeyTest", entry)
            if (keyLog.size > 20) keyLog.removeAt(0)
        }
    }

    override fun draw(app: App, rc: RC) {
        val a = atlas ?: return
        rc.atlases[atlasPx] = a

        rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
        rc.text("Key Test — press any button", 20f, 40f, atlasPx, 0.733f, 0.525f, 0.988f)

        var y = 80f
        for (entry in keyLog) {
            rc.text(entry, 20f, y, atlasPx, 1f, 1f, 1f)
            y += 36f
        }
    }

    override fun cleanup(app: App) {}
}
