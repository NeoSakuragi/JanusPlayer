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
    private val knownStrings = HashSet<Long>()
    private val metricsCache = HashMap<Long, Array<GlyphMetrics>>(128)
    // Pre-baked vertex data per (text, sizePx, color) — one arraycopy per string
    data class BakedText(val floats: FloatArray, val quadCount: Int, val width: Float)
    private val bakedCache = HashMap<Long, BakedText>(128)
    private val atlasSize = 1024
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
    private var dirtyRegions = mutableListOf<IntArray>() // [x, y, w, h] regions to upload

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
        // Fast path: skip if we've seen this exact string+size before
        val hash = text.hashCode().toLong() * 31 + sizePx
        if (knownStrings.contains(hash)) return

        paint.textSize = sizePx.toFloat()
        var allCached = true
        for (cp in text.toCodePoints()) {
            val key = GlyphKey(cp, sizePx)
            if (cache.containsKey(key)) continue
            rasterizeGlyph(cp, sizePx)
            allCached = false
        }
        if (allCached) knownStrings.add(hash)

        if (dirtyRegions.isNotEmpty()) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            for (region in dirtyRegions) {
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, region[1],
                    Bitmap.createBitmap(bitmap, 0, region[1], atlasSize, region[3]))
            }
            dirtyRegions.clear()
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
        // Track dirty row region for incremental upload
        val rowTop = cursorY
        val rowBot = cursorY + rowHeight + 1
        if (dirtyRegions.isEmpty() || dirtyRegions.last()[1] != rowTop) {
            dirtyRegions.add(intArrayOf(0, rowTop, atlasSize, (rowBot - rowTop).coerceAtMost(atlasSize - rowTop)))
        } else {
            dirtyRegions.last()[3] = (rowBot - rowTop).coerceAtMost(atlasSize - rowTop)
        }
    }

    fun addText(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                r: Float, g: Float, b: Float, a: Float = 1f) {
        addTextCalls++
        val hash = text.hashCode().toLong() * 31 + sizePx
        val baked = bakedCache[hash]
        if (baked != null) {
            batch.addBaked(baked.floats, baked.quadCount, x, y, r, g, b, a)
            glyphsEmitted += baked.quadCount
            return
        }
        // First time — build metrics, bake vertex data at origin (0,0)
        var metrics = metricsCache[hash]
        if (metrics == null) {
            ensureGlyphs(text, sizePx)
            val cps = text.toCodePoints()
            metrics = Array(cps.size) { i -> cache[GlyphKey(cps[i], sizePx)] ?: EMPTY_GLYPH }
            metricsCache[hash] = metrics
        }
        val floats = FloatArray(metrics.size * 32) // max 4 verts × 8 floats per glyph
        var cx = 0f
        var count = 0
        for (m in metrics) {
            if (m === EMPTY_GLYPH) continue
            val off = count * 32
            val gx = cx; val gy = -m.ascent
            // TL
            floats[off]    = gx;       floats[off+1]  = gy
            floats[off+2]  = m.u0;     floats[off+3]  = m.v0
            floats[off+4]  = 1f; floats[off+5] = 1f; floats[off+6] = 1f; floats[off+7] = 1f
            // TR
            floats[off+8]  = gx+m.w;   floats[off+9]  = gy
            floats[off+10] = m.u1;     floats[off+11] = m.v0
            floats[off+12] = 1f; floats[off+13] = 1f; floats[off+14] = 1f; floats[off+15] = 1f
            // BR
            floats[off+16] = gx+m.w;   floats[off+17] = gy+m.h
            floats[off+18] = m.u1;     floats[off+19] = m.v1
            floats[off+20] = 1f; floats[off+21] = 1f; floats[off+22] = 1f; floats[off+23] = 1f
            // BL
            floats[off+24] = gx;       floats[off+25] = gy+m.h
            floats[off+26] = m.u0;     floats[off+27] = m.v1
            floats[off+28] = 1f; floats[off+29] = 1f; floats[off+30] = 1f; floats[off+31] = 1f
            cx += m.advance
            count++
        }
        val trimmed = floats.copyOf(count * 32)
        bakedCache[hash] = BakedText(trimmed, count, cx)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
        glyphsEmitted += count
    }

    private companion object {
        val EMPTY_GLYPH = GlyphMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    var measureCount = 0L
    var measureTimeNs = 0L
    var glyphsEmitted = 0
    var addTextCalls = 0
    var ensureCalls = 0

    fun measureText(text: String, sizePx: Int): Float {
        val t = System.nanoTime()
        paint.textSize = sizePx.toFloat()
        val r = paint.measureText(text)
        measureTimeNs += System.nanoTime() - t
        measureCount++
        return r
    }

    fun textHeight(sizePx: Int): Float {
        paint.textSize = sizePx.toFloat()
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    fun addTextClipped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        addTextCalls++
        val clippedHash = text.hashCode().toLong() * 31 + sizePx + (maxWidth * 100).toLong() * 97
        val baked = bakedCache[clippedHash]
        if (baked != null) {
            batch.addBaked(baked.floats, baked.quadCount, x, y, r, g, b, a)
            glyphsEmitted += baked.quadCount
            return
        }
        // First time — compute clipped layout and bake
        val hash = text.hashCode().toLong() * 31 + sizePx
        var metrics = metricsCache[hash]
        if (metrics == null) {
            ensureGlyphs(text, sizePx)
            ensureGlyphs("…", sizePx)
            val cps = text.toCodePoints()
            metrics = Array(cps.size) { i -> cache[GlyphKey(cps[i], sizePx)] ?: EMPTY_GLYPH }
            metricsCache[hash] = metrics
        }
        val ellipsisMetrics = metricsCache["…".hashCode().toLong() * 31 + sizePx]
        val ellipsisW = ellipsisMetrics?.firstOrNull()?.advance ?: measureText("…", sizePx)

        val floats = FloatArray((metrics.size + 1) * 32)
        var cx = 0f
        var count = 0
        for ((i, m) in metrics.withIndex()) {
            if (m === EMPTY_GLYPH) continue
            val remaining = metrics.size - i - 1
            if (remaining > 0 && cx + m.advance + ellipsisW > maxWidth) {
                val eHash = "…".hashCode().toLong() * 31 + sizePx
                val eBaked = bakedCache[eHash]
                if (eBaked != null) {
                    System.arraycopy(eBaked.floats, 0, floats, count * 32, eBaked.floats.size)
                    for (j in 0 until eBaked.quadCount * 4) {
                        floats[count * 32 + j * 8] += cx
                    }
                    count += eBaked.quadCount
                }
                break
            }
            if (cx + m.advance > maxWidth) break
            val off = count * 32
            floats[off]    = cx;      floats[off+1]  = -m.ascent
            floats[off+2]  = m.u0;    floats[off+3]  = m.v0
            floats[off+4]  = 1f; floats[off+5] = 1f; floats[off+6] = 1f; floats[off+7] = 1f
            floats[off+8]  = cx+m.w;  floats[off+9]  = -m.ascent
            floats[off+10] = m.u1;    floats[off+11] = m.v0
            floats[off+12] = 1f; floats[off+13] = 1f; floats[off+14] = 1f; floats[off+15] = 1f
            floats[off+16] = cx+m.w;  floats[off+17] = -m.ascent+m.h
            floats[off+18] = m.u1;    floats[off+19] = m.v1
            floats[off+20] = 1f; floats[off+21] = 1f; floats[off+22] = 1f; floats[off+23] = 1f
            floats[off+24] = cx;      floats[off+25] = -m.ascent+m.h
            floats[off+26] = m.u0;    floats[off+27] = m.v1
            floats[off+28] = 1f; floats[off+29] = 1f; floats[off+30] = 1f; floats[off+31] = 1f
            cx += m.advance
            count++
        }
        val trimmed = floats.copyOf(count * 32)
        bakedCache[clippedHash] = BakedText(trimmed, count, cx)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
        glyphsEmitted += count
    }

    data class LayoutEntry(val dx: Float, val dy: Float, val m: GlyphMetrics)
    private val layoutCache = HashMap<Long, Pair<List<LayoutEntry>, Float>>(32)

    fun addTextWrapped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, maxLines: Int,
                       r: Float, g: Float, b: Float, a: Float = 1f): Float {
        val hash = text.hashCode().toLong() * 31 + sizePx + (maxWidth * 100).toLong() * 97 + maxLines * 53
        val cached = layoutCache[hash]
        if (cached != null) {
            for (e in cached.first) {
                batch.addQuad(x + e.dx, y + e.dy - e.m.ascent, e.m.w, e.m.h,
                    e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a)
            }
            return y + cached.second
        }

        ensureGlyphs(text, sizePx)
        val lineH = textHeight(sizePx)
        val entries = mutableListOf<LayoutEntry>()
        var cx = 0f
        var cy = 0f
        var line = 1

        val words = text.split(" ")
        for (word in words) {
            val wordW = measureText(word, sizePx)
            val spaceW = measureText(" ", sizePx)
            if (cx > 0f && cx + wordW > maxWidth) {
                line++
                if (line > maxLines) { layoutCache[hash] = entries to (cy + lineH); break }
                cx = 0f
                cy += lineH * 1.3f
            }
            for (cp in word.toCodePoints()) {
                val m = cache[GlyphKey(cp, sizePx)] ?: continue
                entries.add(LayoutEntry(cx, cy, m))
                cx += m.advance
            }
            cx += spaceW
        }
        val totalH = cy + lineH
        layoutCache[hash] = entries to totalH
        for (e in entries) {
            batch.addQuad(x + e.dx, y + e.dy - e.m.ascent, e.m.w, e.m.h,
                e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a)
        }
        return y + totalH
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
