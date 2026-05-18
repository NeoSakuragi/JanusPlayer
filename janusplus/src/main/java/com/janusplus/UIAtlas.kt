package com.janusplus

import android.graphics.*
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CPU-rendered text regions within a texture array layer.
 * Each region has a fixed offset in the atlas, rendered via Canvas,
 * uploaded via glTexSubImage3D only when content changes.
 *
 * All text quads use the same texture as covers/thumbnails — one flush.
 */
class UIAtlas(private val texArray: TextureArray, private val layer: Int) {

    data class Region(
        val x: Int, val y: Int, val w: Int, val h: Int,
        var lastHash: Long = 0
    ) {
        val u0 get() = x.toFloat() / 4096f  // UVs are fractions — valid at any atlas size
        val v0 get() = y.toFloat() / 4096f
        val u1 get() = (x + w).toFloat() / 4096f
        val v1 get() = (y + h).toFloat() / 4096f
    }

    // Fixed regions — offsets chosen to never overlap
    val subtitle = Region(0, 100, 2048, 160)
    val dictPopup = Region(0, 280, 800, 500)
    val titleBar = Region(0, 800, 2048, 60)
    val controls = Region(0, 880, 2048, 200)
    val settingsPanel = Region(0, 1100, 800, 900)

    // Shared paint
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
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

    /**
     * Render text into a region. Returns true if content changed and was uploaded.
     */
    fun renderText(
        region: Region,
        text: String,
        textSize: Float,
        color: Int = Color.WHITE,
        bgColor: Int = Color.TRANSPARENT,
        outlineWidth: Float = 0f,
        outlineColor: Int = Color.BLACK,
        shadowRadius: Float = 0f,
        centerH: Boolean = false,
    ): Boolean {
        val hash = text.hashCode().toLong() * 31 + textSize.toLong() * 17 + color.toLong()
        if (hash == region.lastHash) return false
        region.lastHash = hash

        val bmp = Bitmap.createBitmap(region.w, region.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)

        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = color
        if (shadowRadius > 0f) {
            paint.setShadowLayer(shadowRadius, 2f, 2f, Color.argb(128, 0, 0, 0))
        } else {
            paint.clearShadowLayer()
        }

        strokePaint.textSize = textSize
        strokePaint.typeface = typeface
        strokePaint.strokeWidth = outlineWidth
        strokePaint.color = outlineColor

        val fm = paint.fontMetrics
        val lineH = -fm.top + fm.bottom

        val lines = text.split("\n")
        var y = -fm.top + 4f

        for (line in lines) {
            val x = if (centerH) (region.w - paint.measureText(line)) / 2f else 8f
            if (outlineWidth > 0f) canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)
            y += lineH * 1.2f
        }

        uploadRegion(region.x, region.y, bmp)
        bmp.recycle()
        return true
    }

    /**
     * Render subtitle with furigana into the subtitle region.
     */
    fun renderSubtitle(
        text: String,
        words: List<SubtitleWord>,
        textSize: Float,
        furiganaScale: Float = 0.45f,
        furiganaGap: Float = 0.7f,
        rowSpacing: Float = 1.4f,
        letterSpacing: Float = 0f,
        bgColor: Int = Color.argb(178, 0, 0, 0),
        textColor: Int = Color.WHITE,
        outlineWidth: Float = 3f,
        eink: Boolean = false,
    ): Boolean {
        val hash = text.hashCode().toLong() * 31 + textSize.toLong() * 17 + textColor.toLong() + (if (eink) 1 else 0)
        if (hash == subtitle.lastHash) return false
        subtitle.lastHash = hash

        val bmp = Bitmap.createBitmap(subtitle.w, subtitle.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = textColor
        paint.letterSpacing = letterSpacing / textSize
        if (!eink) {
            paint.setShadowLayer(4f, 1.5f, 1.5f, Color.argb(128, 0, 0, 0))
            strokePaint.textSize = textSize
            strokePaint.typeface = typeface
            strokePaint.letterSpacing = paint.letterSpacing
            strokePaint.strokeWidth = outlineWidth
            strokePaint.color = Color.BLACK
        } else {
            paint.clearShadowLayer()
        }

        val fm = paint.fontMetrics
        val lineH = -fm.top + fm.bottom
        val furiSize = textSize * furiganaScale
        val furiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = furiSize
            this.typeface = this@UIAtlas.typeface
            this.color = if (eink) Color.argb(200, 50, 50, 80) else Color.argb(200, 180, 180, 220)
            this.textAlign = Paint.Align.CENTER
        }

        val lines = text.split("\n")
        val lineWidths = lines.map { paint.measureText(it) }
        val maxW = lineWidths.maxOrNull() ?: 0f
        val hasFuri = words.any { it.furigana.isNotEmpty() }
        val furiH = if (hasFuri) furiSize * 1.3f else 0f

        val totalH = lines.size * lineH * rowSpacing + furiH
        val shadeW = maxW + 20f
        val shadeX = (subtitle.w - shadeW) / 2f
        val shadeY = (subtitle.h - totalH) / 2f - 8f

        // Background shade
        val shadePaint = Paint().apply { color = bgColor }
        canvas.drawRect(shadeX, shadeY, shadeX + shadeW, shadeY + totalH + 16f, shadePaint)

        var y = shadeY + furiH + (-fm.top) + 8f

        for ((lineIdx, line) in lines.withIndex()) {
            val lineW = lineWidths[lineIdx]
            val x = (subtitle.w - lineW) / 2f

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

        uploadRegion(subtitle.x, subtitle.y, bmp)
        bmp.recycle()
        return true
    }

    data class SubtitleWord(val start: Int, val end: Int, val furigana: List<FuriSpan>)
    data class FuriSpan(val charIdx: Int, val reading: String)

    /**
     * Clear a region (make transparent).
     */
    fun clearRegion(region: Region) {
        region.lastHash = 0
        val bmp = Bitmap.createBitmap(region.w, region.h, Bitmap.Config.ARGB_8888)
        uploadRegion(region.x, region.y, bmp)
        bmp.recycle()
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

    private fun uploadRegion(x: Int, y: Int, bmp: Bitmap) {
        val src = if (bmp.config != Bitmap.Config.ARGB_8888)
            bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() } else bmp
        val w = src.width; val h = src.height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        src.copyPixelsToBuffer(buf)
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, texArray.textureId)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
            x, y, layer, w, h, 1,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
    }
}
