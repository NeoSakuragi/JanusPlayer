package com.janusplus

import android.graphics.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlyphAtlas(private val typeface: Typeface, private val textSize: Float) {

    data class Glyph(
        var u0: Float, var v0: Float, var u1: Float, var v1: Float,
        var page: Int,
        val w: Float, val h: Float, val advance: Float, val ascent: Float
    )

    val glyphs = HashMap<Int, Glyph>(512)
    var lineHeight = 0f; private set
    var ascent = 0f; private set
    var bitmapW = 0; internal set
    var bitmapH = 0; internal set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    fun build(texts: List<String>, pageW: Int = 4096): Bitmap {
        paint.textSize = textSize
        paint.typeface = typeface

        val fm = paint.fontMetrics
        lineHeight = -fm.top + fm.bottom
        ascent = -fm.top

        val codepoints = mutableSetOf<Int>()
        for (text in texts) {
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                codepoints.add(cp)
                i += Character.charCount(cp)
            }
        }

        data class GlyphInfo(val cp: Int, val w: Int, val h: Int, val advance: Float)
        val infos = mutableListOf<GlyphInfo>()
        val padding = 2

        for (cp in codepoints) {
            val ch = String(intArrayOf(cp), 0, 1)
            val advance = paint.measureText(ch)
            if (advance <= 0) continue
            val gw = advance.toInt() + padding * 2
            val gh = lineHeight.toInt() + padding * 2
            infos.add(GlyphInfo(cp, gw, gh, advance))
        }

        // Row-pack to compute minimal height
        var curX = 0; var curY = 0; var rowH = 0
        for (info in infos) {
            if (curX + info.w > pageW) { curX = 0; curY += rowH + 1; rowH = 0 }
            if (info.h > rowH) rowH = info.h
            curX += info.w + 1
        }
        bitmapW = pageW
        bitmapH = (curY + rowH + 1).coerceAtLeast(1)
        // Round up to multiple of 4 for GL alignment
        bitmapH = ((bitmapH + 3) / 4) * 4

        android.util.Log.d("GlyphAtlas", "size=${textSize.toInt()} glyphs=${infos.size} bitmap=${bitmapW}x${bitmapH}")

        val bmp = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        curX = 0; curY = 0; rowH = 0
        for (info in infos) {
            if (curX + info.w > pageW) { curX = 0; curY += rowH + 1; rowH = 0 }

            val ch = String(intArrayOf(info.cp), 0, 1)
            canvas.drawText(ch, curX + padding.toFloat(), curY + padding + ascent, paint)

            // UV coords are LOCAL to this bitmap (will be remapped by uploader)
            glyphs[info.cp] = Glyph(
                u0 = curX.toFloat(), v0 = curY.toFloat(),
                u1 = (curX + info.w).toFloat(), v1 = (curY + info.h).toFloat(),
                page = 0,
                w = info.w.toFloat(), h = info.h.toFloat(),
                advance = info.advance, ascent = ascent + padding
            )

            if (info.h > rowH) rowH = info.h
            curX += info.w + 1
        }

        return bmp
    }

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

fun String.toCodePoints(): IntArray {
    val list = mutableListOf<Int>()
    var i = 0
    while (i < length) { val cp = Character.codePointAt(this, i); list.add(cp); i += Character.charCount(cp) }
    return list.toIntArray()
}
