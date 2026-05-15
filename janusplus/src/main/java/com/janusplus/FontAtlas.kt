package com.janusplus

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils

class FontAtlas(assets: AssetManager) {

    data class GlyphKey(val codePoint: Int, val sizePx: Int)
    data class GlyphMetrics(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                            val w: Float, val h: Float, val advance: Float, val ascent: Float)

    private val typeface: Typeface = Typeface.createFromAsset(assets, "fonts/NotoSansJP-Regular.ttf")
    private val cache = HashMap<GlyphKey, GlyphMetrics>(512)
    private val atlasSize = 2048
    private val bitmap = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = this@FontAtlas.typeface
    }

    private var cursorX = 0
    private var cursorY = 0
    private var rowHeight = 0
    var textureId = 0; private set
    private var dirty = false

    // White pixel UV — solids use this so everything shares the font atlas texture
    var whiteU = 0f; private set
    var whiteV = 0f; private set

    fun initGL() {
        // Draw a 4x4 white block at (0,0) for solid-color quads
        val whitePaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawRect(0f, 0f, 4f, 4f, whitePaint)
        whiteU = 2f / atlasSize  // center of white block
        whiteV = 2f / atlasSize
        cursorX = 5
        cursorY = 0
        rowHeight = 4

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun ensureGlyphs(text: String, sizePx: Int) {
        paint.textSize = sizePx.toFloat()
        var added = false
        for (cp in text.toCodePoints()) {
            val key = GlyphKey(cp, sizePx)
            if (cache.containsKey(key)) continue
            rasterizeGlyph(cp, sizePx)
            added = true
        }
        if (added || dirty) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            dirty = false
        }
    }

    private fun rasterizeGlyph(codePoint: Int, sizePx: Int) {
        paint.textSize = sizePx.toFloat()
        val ch = String(Character.toChars(codePoint))
        val charWidth = paint.measureText(ch)
        val fm = paint.fontMetrics
        val charHeight = fm.descent - fm.ascent
        val glyphW = (charWidth + 2).toInt().coerceAtLeast(1)
        val glyphH = (charHeight + 2).toInt().coerceAtLeast(1)

        if (cursorX + glyphW > atlasSize) {
            cursorX = 0
            cursorY += rowHeight + 1
            rowHeight = 0
        }
        if (cursorY + glyphH > atlasSize) return

        canvas.drawText(ch, cursorX + 1f, cursorY + 1f - fm.ascent, paint)

        val u0 = cursorX.toFloat() / atlasSize
        val v0 = cursorY.toFloat() / atlasSize
        val u1 = (cursorX + glyphW).toFloat() / atlasSize
        val v1 = (cursorY + glyphH).toFloat() / atlasSize

        cache[GlyphKey(codePoint, sizePx)] = GlyphMetrics(
            u0, v0, u1, v1,
            glyphW.toFloat(), glyphH.toFloat(),
            charWidth, -fm.ascent
        )

        cursorX += glyphW + 1
        if (glyphH > rowHeight) rowHeight = glyphH
        dirty = true
    }

    fun addText(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                r: Float, g: Float, b: Float, a: Float = 1f) {
        ensureGlyphs(text, sizePx)
        var cx = x
        for (cp in text.toCodePoints()) {
            val m = cache[GlyphKey(cp, sizePx)] ?: continue
            batch.addQuad(cx, y - m.ascent, m.w, m.h, m.u0, m.v0, m.u1, m.v1, r, g, b, a)
            cx += m.advance
        }
    }

    fun measureText(text: String, sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        return paint.measureText(text)
    }

    fun textHeight(sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    fun addTextClipped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        ensureGlyphs(text, sizePx)
        val ellipsis = "…"
        ensureGlyphs(ellipsis, sizePx)
        val ellipsisW = measureText(ellipsis, sizePx)

        var cx = x
        val cps = text.toCodePoints()
        for ((i, cp) in cps.withIndex()) {
            val m = cache[GlyphKey(cp, sizePx)] ?: continue
            val remaining = cps.size - i - 1
            if (remaining > 0 && cx + m.advance + ellipsisW > x + maxWidth) {
                addText(batch, ellipsis, cx, y, sizePx, r, g, b, a)
                return
            }
            if (cx + m.advance > x + maxWidth) return
            batch.addQuad(cx, y - m.ascent, m.w, m.h, m.u0, m.v0, m.u1, m.v1, r, g, b, a)
            cx += m.advance
        }
    }

    fun addTextWrapped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, maxLines: Int,
                       r: Float, g: Float, b: Float, a: Float = 1f): Float {
        ensureGlyphs(text, sizePx)
        val lineH = textHeight(sizePx)
        var cx = x
        var cy = y
        var line = 1

        val words = text.split(" ")
        for (word in words) {
            val wordW = measureText(word, sizePx)
            val spaceW = measureText(" ", sizePx)
            if (cx > x && cx + wordW > x + maxWidth) {
                line++
                if (line > maxLines) return cy + lineH
                cx = x
                cy += lineH * 1.3f
            }
            for (cp in word.toCodePoints()) {
                val m = cache[GlyphKey(cp, sizePx)] ?: continue
                batch.addQuad(cx, cy - m.ascent, m.w, m.h, m.u0, m.v0, m.u1, m.v1, r, g, b, a)
                cx += m.advance
            }
            cx += spaceW
        }
        return cy + lineH
    }
}

fun String.toCodePoints(): IntArray {
    val list = mutableListOf<Int>()
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        list.add(cp)
        i += Character.charCount(cp)
    }
    return list.toIntArray()
}
