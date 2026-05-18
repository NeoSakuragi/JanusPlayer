package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders subtitle text into a Bitmap via Canvas, uploads as a GL texture.
 * Supports: any system font, furigana, outline, shadow, bold, e-ink theme.
 * One texture per subtitle line — re-rendered only when text changes.
 */
class SubtitleBitmap {

    var textureId = 0; private set
    var texW = 0; private set
    var texH = 0; private set
    var lastText = ""
    var lastRenderMs = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
    }
    private val furiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 180, 180, 220)
        textAlign = Paint.Align.CENTER
    }

    data class RenderParams(
        val fontFamily: Typeface = Typeface.DEFAULT,
        val textSizePx: Float = 48f,
        val furiganaScale: Float = 0.45f,
        val outlineWidth: Float = 3f,
        val shadowRadius: Float = 6f,
        val shadowDx: Float = 2f,
        val shadowDy: Float = 2f,
        val eink: Boolean = false,
        val deltaSpacing: Float = 0f,
        val deltaFurigana: Float = 0.7f,
        val deltaRow: Float = 1.4f,
    )

    data class FuriSpan(val charIdx: Int, val reading: String)
    data class WordInfo(val start: Int, val end: Int, val furigana: List<FuriSpan>)

    fun render(
        text: String,
        words: List<WordInfo>,
        params: RenderParams,
        maxWidth: Float,
    ) {
        if (text == lastText && textureId != 0) return
        lastText = text
        val t0 = System.nanoTime()
        android.util.Log.d("SUB", "Render: ${text.take(20)}... w=$maxWidth")

        val textSize = params.textSizePx
        val furiSize = textSize * params.furiganaScale
        fillPaint.textSize = textSize
        fillPaint.typeface = params.fontFamily
        fillPaint.letterSpacing = params.deltaSpacing / textSize
        fillPaint.color = if (params.eink) Color.BLACK else Color.WHITE
        fillPaint.setShadowLayer(
            if (params.eink) 0f else params.shadowRadius,
            params.shadowDx, params.shadowDy, Color.argb(128, 0, 0, 0)
        )

        strokePaint.textSize = textSize
        strokePaint.typeface = params.fontFamily
        strokePaint.letterSpacing = fillPaint.letterSpacing
        strokePaint.strokeWidth = if (params.eink) 0f else params.outlineWidth
        strokePaint.color = if (params.eink) Color.TRANSPARENT else Color.BLACK

        furiPaint.textSize = furiSize
        furiPaint.typeface = params.fontFamily
        furiPaint.color = if (params.eink) Color.argb(200, 50, 50, 80) else Color.argb(200, 180, 180, 220)

        val fm = fillPaint.fontMetrics
        val lineH = (-fm.top + fm.bottom)
        val furiH = furiSize * 1.2f
        val rowGap = lineH * (params.deltaRow - 1f)

        val lines = text.split("\n")
        val lineWidths = lines.map { fillPaint.measureText(it) }
        val bmpW = (lineWidths.maxOrNull()?.plus(params.outlineWidth * 2 + 20) ?: 100f).toInt().coerceAtMost(maxWidth.toInt())
        val hasFuri = words.any { it.furigana.isNotEmpty() }
        val furiExtra = if (hasFuri) furiH else 0f
        val bmpH = (lines.size * lineH + (lines.size - 1).coerceAtLeast(0) * rowGap + furiExtra + params.outlineWidth * 2 + 10).toInt()

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background shade
        val shadePaint = Paint().apply {
            color = if (params.eink) Color.argb(242, 255, 255, 255) else Color.argb(178, 0, 0, 0)
        }
        canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), shadePaint)

        val padX = params.outlineWidth + 5
        var y = furiExtra + (-fm.top) + params.outlineWidth

        for ((lineIdx, line) in lines.withIndex()) {
            val lineW = lineWidths[lineIdx]
            val x = (bmpW - lineW) / 2f

            // Outline
            if (!params.eink) canvas.drawText(line, x, y, strokePaint)
            // Fill
            canvas.drawText(line, x, y, fillPaint)

            // Furigana
            if (hasFuri) {
                drawFurigana(canvas, line, x, y, lineIdx, lines, words, params, fm)
            }

            y += lineH + rowGap
        }

        uploadTexture(bitmap)
        bitmap.recycle()

        texW = bmpW
        texH = bmpH
        lastRenderMs = (System.nanoTime() - t0) / 1_000_000f
    }

    private fun drawFurigana(
        canvas: Canvas, line: String, lineX: Float, lineY: Float,
        lineIdx: Int, lines: List<String>, words: List<WordInfo>,
        params: RenderParams, fm: Paint.FontMetrics
    ) {
        val lineStart = lines.take(lineIdx).sumOf { it.length + 1 }
        for (w in words) {
            if (w.furigana.isEmpty()) continue
            if (w.start >= lineStart + line.length || w.end <= lineStart) continue
            for (furi in w.furigana) {
                val absIdx = w.start + furi.charIdx
                val localIdx = absIdx - lineStart
                if (localIdx < 0 || localIdx >= line.length) continue

                val prefix = line.substring(0, localIdx)
                val ch = line.substring(localIdx, (localIdx + 1).coerceAtMost(line.length))
                val prefixW = fillPaint.measureText(prefix)
                val charW = fillPaint.measureText(ch)
                val furiW = furiPaint.measureText(furi.reading)

                val furiX = lineX + prefixW + charW / 2f
                val furiY = lineY - (-fm.top) * params.deltaFurigana

                // Compress if wider than kanji
                if (furiW > charW) {
                    canvas.save()
                    val scale = charW / furiW
                    canvas.scale(scale, 1f, furiX, furiY)
                    canvas.drawText(furi.reading, furiX, furiY, furiPaint)
                    canvas.restore()
                } else {
                    canvas.drawText(furi.reading, furiX, furiY, furiPaint)
                }
            }
        }
    }

    private fun uploadTexture(bitmap: Bitmap) {
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

        val w = bitmap.width; val h = bitmap.height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buf)
        buf.position(0)

        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}
