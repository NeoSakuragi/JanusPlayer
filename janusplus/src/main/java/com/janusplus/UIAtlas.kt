package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CPU-rendered text in a texture array layer.
 *
 * Usage:
 * 1. Screen init: call prepareText() for each label → renders bitmap, queues for upload
 * 2. Each frame: call processQueue(1) → uploads 1 pending bitmap to VRAM
 * 3. Draw: call drawSlot() → adds quad if slot is uploaded
 *
 * Text renders once, uploads gradually (1/frame), draws for free forever.
 * Call reset() on screen transition.
 */
class UIAtlas(val texArray: TextureArray, val layer: Int) {

    val texSize get() = texArray.size.toFloat()

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    var typeface: Typeface = Typeface.DEFAULT

    // White pixel
    fun uploadWhitePixel() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        uploadImmediate(0, 0, bmp)
        bmp.recycle()
    }

    val whiteU = 1f / 4096f
    val whiteV = 1f / 4096f

    // ── Slot management ──

    data class Slot(
        val x: Int, val y: Int, val w: Int, val h: Int,
        var uploaded: Boolean = false
    )

    private val slots = mutableMapOf<String, Slot>()
    private val uploadQueue = ArrayDeque<Pair<Slot, Bitmap>>()

    // Row packing cursor
    private var cursorX = 0
    private var cursorY = 8
    private var rowH = 0
    private val maxTextY get() = texArray.size - 200 // reserve bottom for subtitle

    /**
     * Render text to a bitmap and queue for VRAM upload.
     * Returns a Slot with UV coordinates. Slot.uploaded = false until processQueue uploads it.
     */
    fun prepareText(
        id: String,
        text: String,
        textSize: Float,
        color: Int = Color.WHITE,
        maxWidth: Float = 0f,
    ): Slot? {
        if (text.isEmpty()) return null
        slots[id]?.let { if (it.uploaded) return it }

        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = color
        paint.clearShadowLayer()

        val fm = paint.fontMetrics
        val measuredW = paint.measureText(text)
        val w = (if (maxWidth > 0f) minOf(measuredW, maxWidth) else measuredW).toInt() + 8
        val h = (-fm.top + fm.bottom).toInt() + 4

        // Allocate position (row packing)
        if (cursorX + w > texArray.size) {
            cursorX = 0
            cursorY += rowH + 2
            rowH = 0
        }
        if (cursorY + h > maxTextY) return null

        val slot = Slot(cursorX, cursorY, w, h)
        cursorX += w + 2
        if (h > rowH) rowH = h
        slots[id] = slot

        // Render bitmap
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (maxWidth > 0f && measuredW > maxWidth) {
            canvas.save()
            canvas.clipRect(0f, 0f, maxWidth, h.toFloat())
            canvas.drawText(text, 4f, -fm.top + 2f, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, 4f, -fm.top + 2f, paint)
        }

        uploadQueue.addLast(slot to bmp)
        return slot
    }

    /**
     * Upload up to N pending bitmaps to VRAM. Call once per frame.
     */
    fun processQueue(maxUploads: Int = 1) {
        var count = 0
        while (uploadQueue.isNotEmpty() && count < maxUploads) {
            val (slot, bmp) = uploadQueue.removeFirst()
            uploadImmediate(slot.x, slot.y, bmp)
            bmp.recycle()
            slot.uploaded = true
            count++
        }
    }

    fun getSlot(id: String): Slot? = slots[id]

    fun hasPending(): Boolean = uploadQueue.isNotEmpty()

    // ── Subtitle (special — immediate upload, allocated at bottom) ──

    data class SubtitleWord(val start: Int, val end: Int, val furigana: List<FuriSpan>)
    data class FuriSpan(val charIdx: Int, val reading: String)

    private var subtitleSlot: Slot? = null
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
        if (hash == subtitleHash && subtitleSlot != null) return subtitleSlot

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

        val subY = texArray.size - bmpH - 4
        val slot = Slot(0, subY, bmpW, bmpH, uploaded = true)

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val shadePaint = Paint().apply { color = bgColor }
        canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), shadePaint)

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

        if (!eink) paint.setShadowLayer(4f, 1.5f, 1.5f, Color.argb(128, 0, 0, 0))
        else paint.clearShadowLayer()

        var y = furiH + (-fm.top) + 8f
        for ((lineIdx, line) in lines.withIndex()) {
            val lineW = lineWidths[lineIdx]
            val x = (bmpW - lineW) / 2f
            if (!eink) canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)
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

        uploadImmediate(slot.x, slot.y, bmp)
        bmp.recycle()
        subtitleSlot = slot
        subtitleHash = hash
        return slot
    }

    // ── Helpers ──

    fun measureText(text: String, textSize: Float): Float {
        paint.textSize = textSize
        paint.typeface = typeface
        return paint.measureText(text)
    }

    fun textHeight(textSize: Float): Float {
        paint.textSize = textSize
        return -paint.fontMetrics.top + paint.fontMetrics.bottom
    }

    fun textAscent(textSize: Float): Float {
        paint.textSize = textSize
        return -paint.fontMetrics.top
    }

    fun reset() {
        slots.clear()
        uploadQueue.forEach { it.second.recycle() }
        uploadQueue.clear()
        cursorX = 0
        cursorY = 8
        rowH = 0
        subtitleSlot = null
        subtitleHash = 0
    }

    private fun uploadImmediate(x: Int, y: Int, bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        if (x + w > texArray.size || y + h > texArray.size) return
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf); buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray.textureId)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
            x, y, layer, w, h, 1,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    }
}
