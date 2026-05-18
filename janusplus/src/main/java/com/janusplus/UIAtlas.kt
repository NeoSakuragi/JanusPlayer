package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * All text rendered via Canvas into regions of a texture array layer.
 * Covers: labels, subtitles, dict popup, buttons — everything.
 *
 * Uses a simple strip allocator: regions packed top-to-bottom.
 * Each string gets a slot. Re-rendered only when content changes (hash check).
 * One texture, one batch, one flush.
 */
class UIAtlas(val texArray: TextureArray, val layer: Int) {

    val texSize get() = texArray.size.toFloat()

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    var typeface: Typeface = Typeface.DEFAULT

    // White pixel at (0,0)
    fun uploadWhitePixel() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        uploadRegion(0, 0, bmp)
        bmp.recycle()
    }

    val whiteU = 1f / 4096f
    val whiteV = 1f / 4096f

    // ── Strip allocator ──
    // Packs text slots top-to-bottom starting at y=8 (below white pixel)
    // Resets each frame — all slots re-evaluated

    data class Slot(
        var x: Int, var y: Int, var w: Int, var h: Int,
        var hash: Long = 0,
        var valid: Boolean = false
    ) {
        val u0 get() = x.toFloat()
        val v0 get() = y.toFloat()
        val u1 get() = (x + w).toFloat()
        val v1 get() = (y + h).toFloat()
    }

    private val slots = HashMap<Long, Slot>(256)
    private var cursorX = 0
    private var cursorY = 8
    private var rowH = 0
    private val maxSize get() = texArray.size
    // Reserve bottom 200px for subtitle
    private val maxTextY get() = maxSize - 200

    fun beginFrame() {}

    fun text(
        key: Long,
        text: String,
        textSize: Float,
        color: Int = Color.WHITE,
        maxWidth: Float = 0f,
    ): Slot? {
        if (text.isEmpty()) return null

        val hash = text.hashCode().toLong() * 31 + textSize.toLong() * 17 + color.toLong()

        val existing = slots[key]
        if (existing != null && existing.hash == hash && existing.valid) {
            return existing
        }

        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = color

        val fm = paint.fontMetrics
        val measuredW = paint.measureText(text)
        val w = (if (maxWidth > 0f) minOf(measuredW, maxWidth) else measuredW).toInt() + 8
        val h = (-fm.top + fm.bottom).toInt() + 4

        val slot: Slot
        if (existing != null && existing.w >= w && existing.h >= h) {
            slot = existing
        } else {
            // Row packing: fill horizontally, then next row
            if (cursorX + w > maxSize) {
                cursorX = 0
                cursorY += rowH + 2
                rowH = 0
            }
            if (cursorY + h > maxTextY) return null
            slot = Slot(cursorX, cursorY, w, h)
            slots[key] = slot
            cursorX += w + 2
            if (h > rowH) rowH = h
        }

        slot.hash = hash
        slot.valid = true

        // Render to bitmap
        val bmp = Bitmap.createBitmap(slot.w, slot.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (maxWidth > 0f && measuredW > maxWidth) {
            canvas.save()
            canvas.clipRect(0f, 0f, maxWidth, slot.h.toFloat())
            canvas.drawText(text, 4f, -fm.top + 2f, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, 4f, -fm.top + 2f, paint)
        }

        uploadRegion(slot.x, slot.y, bmp)
        bmp.recycle()
        return slot
    }

    /**
     * Render subtitle with shade, outline, furigana.
     */
    data class SubtitleWord(val start: Int, val end: Int, val furigana: List<FuriSpan>)
    data class FuriSpan(val charIdx: Int, val reading: String)

    private var subtitleSlot = Slot(0, 0, 0, 0)
    private var subtitleHash = 0L

    fun renderSubtitle(
        text: String,
        words: List<SubtitleWord>,
        textSize: Float,
        maxWidth: Int,
        furiganaScale: Float = 0.45f,
        furiganaGap: Float = 0.7f,
        rowSpacing: Float = 1.4f,
        letterSpacing: Float = 0f,
        bgColor: Int = Color.argb(178, 0, 0, 0),
        textColor: Int = Color.WHITE,
        outlineWidth: Float = 3f,
        eink: Boolean = false,
    ): Slot? {
        val hash = text.hashCode().toLong() * 31 + textSize.toLong() * 17 + textColor + (if (eink) 1 else 0)
        if (hash == subtitleHash && subtitleSlot.valid) return subtitleSlot

        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = textColor
        paint.letterSpacing = letterSpacing / textSize

        val fm = paint.fontMetrics
        val lineH = -fm.top + fm.bottom
        val furiSize = textSize * furiganaScale
        val hasFuri = words.any { it.furigana.isNotEmpty() }
        val furiH = if (hasFuri) furiSize * 1.3f else 0f

        val lines = text.split("\n")
        val lineWidths = lines.map { paint.measureText(it) }
        val maxLineW = lineWidths.maxOrNull() ?: 0f

        val bmpW = (maxLineW + 24).toInt().coerceAtMost(maxWidth)
        val bmpH = (lines.size * lineH * rowSpacing + furiH + 20).toInt()

        // Allocate at a fixed position for subtitles (bottom of atlas)
        val subY = texArray.size - bmpH - 4
        subtitleSlot = Slot(0, subY, bmpW, bmpH)
        subtitleSlot.hash = hash
        subtitleSlot.valid = true
        subtitleHash = hash

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Shade background
        val shadePaint = Paint().apply { color = bgColor }
        canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), shadePaint)

        // Outline paint
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.typeface = this@UIAtlas.typeface
            this.letterSpacing = paint.letterSpacing
            this.style = Paint.Style.STROKE
            this.strokeWidth = outlineWidth
            this.color = Color.BLACK
        }

        val furiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = furiSize
            this.typeface = this@UIAtlas.typeface
            this.color = if (eink) Color.argb(200, 50, 50, 80) else Color.argb(200, 180, 180, 220)
            this.textAlign = Paint.Align.CENTER
        }

        if (!eink) {
            paint.setShadowLayer(4f, 1.5f, 1.5f, Color.argb(128, 0, 0, 0))
        } else {
            paint.clearShadowLayer()
        }

        var y = furiH + (-fm.top) + 8f

        for ((lineIdx, line) in lines.withIndex()) {
            val lineW = lineWidths[lineIdx]
            val x = (bmpW - lineW) / 2f

            if (!eink) canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)

            // Furigana
            if (hasFuri) {
                val lineStart = lines.take(lineIdx).sumOf { it.length + 1 }
                for (w in words) {
                    if (w.furigana.isEmpty()) continue
                    if (w.start >= lineStart + line.length || w.end <= lineStart) continue
                    for (furi in w.furigana) {
                        val localIdx = w.start + furi.charIdx - lineStart
                        if (localIdx < 0 || localIdx >= line.length) continue
                        val prefix = line.substring(0, localIdx)
                        val ch = line.substring(localIdx, (localIdx + 1).coerceAtMost(line.length))
                        val prefixW = paint.measureText(prefix)
                        val charW = paint.measureText(ch)
                        val furiW = furiPaint.measureText(furi.reading)
                        val furiX = x + prefixW + charW / 2f
                        val furiY = y - lineH * furiganaGap
                        if (furiW > charW) {
                            canvas.save()
                            canvas.scale(charW / furiW, 1f, furiX, furiY)
                            canvas.drawText(furi.reading, furiX, furiY, furiPaint)
                            canvas.restore()
                        } else {
                            canvas.drawText(furi.reading, furiX, furiY, furiPaint)
                        }
                    }
                }
            }

            y += lineH * rowSpacing
        }

        paint.clearShadowLayer()
        paint.letterSpacing = 0f

        uploadRegion(subtitleSlot.x, subtitleSlot.y, bmp)
        bmp.recycle()
        return subtitleSlot
    }

    fun clearSubtitle() {
        subtitleHash = 0
        subtitleSlot.valid = false
    }

    fun measureText(text: String, textSize: Float): Float {
        paint.textSize = textSize
        paint.typeface = typeface
        return paint.measureText(text)
    }

    fun textHeight(textSize: Float): Float {
        paint.textSize = textSize
        val fm = paint.fontMetrics
        return -fm.top + fm.bottom
    }

    fun textAscent(textSize: Float): Float {
        paint.textSize = textSize
        return -paint.fontMetrics.top
    }

    fun resetSlots() {
        slots.clear()
        cursorX = 0
        cursorY = 8
        rowH = 0
        subtitleHash = 0
        subtitleSlot.valid = false
    }

    private fun uploadRegion(x: Int, y: Int, bmp: Bitmap) {
        val src = if (bmp.config != Bitmap.Config.ARGB_8888)
            bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() } else bmp
        val w = src.width; val h = src.height
        if (x + w > texArray.size || y + h > texArray.size) return
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        src.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray.textureId)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
            x, y, layer, w, h, 1,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    }
}
