package com.janusplus

import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val level: LogLevel, val message: String, val timestamp: Long = System.currentTimeMillis())

object JanusLog {
    private val entries = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    var consoleAtlas: GlyphAtlas? = null
        private set
    private var consoleReady = false
    @Volatile var statusLine = ""

    fun init(cacheDir: File) {
        val dir = File(cacheDir, "logs")
        dir.mkdirs()
        logFile = File(dir, "janus.log")
        if ((logFile?.length() ?: 0) > 2_000_000) logFile?.writeText("")
        i("JanusLog initialized")
    }

    fun i(msg: String) = log(LogLevel.INFO, msg)
    fun w(msg: String) = log(LogLevel.WARN, msg)
    fun e(msg: String) = log(LogLevel.ERROR, msg)

    private fun log(level: LogLevel, msg: String) {
        val entry = LogEntry(level, msg)
        entries.add(entry)
        while (entries.size > 200) entries.poll()
        val tag = when (level) { LogLevel.INFO -> "I"; LogLevel.WARN -> "W"; LogLevel.ERROR -> "E" }
        val ts = fileDateFormat.format(Date(entry.timestamp))
        val line = "$ts [$tag] $msg\n"
        try { logFile?.appendText(line) } catch (_: Exception) {}
        android.util.Log.d("Janus", "[$tag] $msg")
    }

    fun initGL(texArray: TextureArray, density: Float, typeface: android.graphics.Typeface) {
        if (consoleReady) return
        val atlas = GlyphAtlas(typeface, 11f * density)
        val bmp = atlas.build(listOf(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "0123456789ms%.:| -_=+/()[]{}@#<>,;!?'\"~`\\^&*",
            "日本語漢字読書再生設定"), texArray.size)
        texArray.uploadLayerNow(TextureArray.LAYER_CONSOLE, bmp)
        // Normalize UVs to texture size (build() leaves them in pixel coords)
        val ts = texArray.size.toFloat()
        for (g in atlas.glyphs.values) {
            g.u0 /= ts; g.v0 /= ts; g.u1 /= ts; g.v1 /= ts
            g.page = TextureArray.LAYER_CONSOLE
        }
        consoleAtlas = atlas
        consoleReady = true
        android.util.Log.d("JanusLog", "initGL: ${atlas.glyphs.size} glyphs on layer ${TextureArray.LAYER_CONSOLE}, texSize=${texArray.size}")
    }

    fun onContextLost() {
        consoleReady = false
    }

    fun draw(batch: QuadBatch, w: Float, h: Float, density: Float) {
        if (!consoleReady) return
        val atlas = consoleAtlas ?: return
        val pad = 2f
        val lineH = atlas.lineHeight + density * 2f
        val layer = TextureArray.LAYER_CONSOLE.toFloat()

        // Pinned status line at top
        val statusY = density * 10f
        var cx = density * 6f
        for (ch in statusLine) {
            val g = atlas.glyphs[ch.code] ?: continue
            batch.addQuad(cx - pad, statusY - g.ascent, g.w, g.h,
                g.u0, g.v0, g.u1, g.v1, 0.4f, 0.9f, 0.4f, 1f, layer = layer)
            cx += g.advance
        }

        // Log entries below
        val logTop = statusY + lineH
        val maxLines = ((h - logTop) / lineH).toInt()
        val snapshot = entries.toList()
        val visible = if (snapshot.size > maxLines) snapshot.takeLast(maxLines) else snapshot

        for ((i, entry) in visible.withIndex()) {
            val y = logTop + i * lineH
            val (r, g, b) = when (entry.level) {
                LogLevel.INFO -> Triple(0.4f, 0.9f, 0.4f)
                LogLevel.WARN -> Triple(1f, 0.9f, 0.2f)
                LogLevel.ERROR -> Triple(1f, 0.3f, 0.2f)
            }
            val ts = dateFormat.format(Date(entry.timestamp))
            val tag = when (entry.level) { LogLevel.INFO -> "I"; LogLevel.WARN -> "W"; LogLevel.ERROR -> "E" }
            val line = "$ts $tag ${entry.message}"
            var lx = density * 6f
            for (ch in line) {
                val glyph = atlas.glyphs[ch.code] ?: continue
                batch.addQuad(lx - pad, y - glyph.ascent, glyph.w, glyph.h,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    r, g, b, 1f, layer = layer)
                lx += glyph.advance
            }
        }
    }
}
