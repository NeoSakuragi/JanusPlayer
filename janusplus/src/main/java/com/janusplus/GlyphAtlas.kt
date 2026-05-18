package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dynamic glyph cache. Scans text for unique codepoints, renders each glyph
 * once via Canvas, packs into a small atlas bitmap, uploads to VRAM.
 *
 * After build(): every codepoint has UV coordinates. Drawing text = drawing
 * one quad per character with the right UVs. Zero per-frame Canvas work.
 */
class GlyphAtlas(private val typeface: Typeface, private val textSize: Float) {

    data class Glyph(
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val w: Float, val h: Float, val advance: Float, val ascent: Float
    )

    val glyphs = HashMap<Int, Glyph>(512)
    var atlasW = 0; private set
    var atlasH = 0; private set
    var lineHeight = 0f; private set
    var ascent = 0f; private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    /**
     * Scan all text, render unique glyphs into an atlas bitmap.
     * Call from background thread. Returns the bitmap (caller uploads to GL).
     */
    fun build(texts: List<String>): Bitmap {
        paint.textSize = textSize
        paint.typeface = typeface

        val fm = paint.fontMetrics
        lineHeight = -fm.top + fm.bottom
        ascent = -fm.top

        // Collect unique codepoints
        val codepoints = mutableSetOf<Int>()
        for (text in texts) {
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                codepoints.add(cp)
                i += Character.charCount(cp)
            }
        }

        // Measure each glyph
        data class GlyphInfo(val cp: Int, val w: Int, val h: Int, val advance: Float, val bearingY: Float)
        val infos = mutableListOf<GlyphInfo>()
        val padding = 2

        for (cp in codepoints) {
            val ch = String(intArrayOf(cp), 0, 1)
            val advance = paint.measureText(ch)
            if (advance <= 0) continue
            val gw = advance.toInt() + padding * 2
            val gh = lineHeight.toInt() + padding * 2
            infos.add(GlyphInfo(cp, gw, gh, advance, ascent))
        }

        // Compute atlas size (row packing)
        val maxW = 1024
        var curX = 0; var curY = 0; var rowH = 0
        for (info in infos) {
            if (curX + info.w > maxW) {
                curX = 0; curY += rowH + 1; rowH = 0
            }
            if (info.h > rowH) rowH = info.h
            curX += info.w + 1
        }
        atlasW = maxW
        atlasH = curY + rowH + 1

        // Power-of-two height (GL friendly)
        atlasH = Integer.highestOneBit(atlasH - 1).shl(1).coerceAtLeast(64)

        // Render
        val bmp = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        curX = 0; curY = 0; rowH = 0
        for (info in infos) {
            if (curX + info.w > maxW) {
                curX = 0; curY += rowH + 1; rowH = 0
            }

            val ch = String(intArrayOf(info.cp), 0, 1)
            canvas.drawText(ch, curX + padding.toFloat(), curY + padding + ascent, paint)

            glyphs[info.cp] = Glyph(
                u0 = curX.toFloat() / atlasW,
                v0 = curY.toFloat() / atlasH,
                u1 = (curX + info.w).toFloat() / atlasW,
                v1 = (curY + info.h).toFloat() / atlasH,
                w = info.w.toFloat(),
                h = info.h.toFloat(),
                advance = info.advance,
                ascent = info.bearingY + padding
            )

            if (info.h > rowH) rowH = info.h
            curX += info.w + 1
        }

        return bmp
    }

    /**
     * Measure text width using the built glyph data.
     */
    fun measureText(text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val g = glyphs[cp]
            if (g != null) w += g.advance
            i += Character.charCount(cp)
        }
        return w
    }
}
