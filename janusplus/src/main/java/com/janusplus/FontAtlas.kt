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
    var atlasSize = 4096
    private var bitmap = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
    private var canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = this@FontAtlas.typeface
    }

    private var cursorX = 0
    private var cursorY = 0
    private var rowHeight = 0
    private var dirtyRegions = mutableListOf<IntArray>()
    var texArray: TextureArray? = null
    val layer = TextureArray.LAYER_FONT

    var whiteU = 0f; private set
    var whiteV = 0f; private set

    fun initGL(texArr: TextureArray) {
        texArray = texArr
        atlasSize = texArr.size
        bitmap = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)

        val whitePaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
        canvas.drawRect(0f, 0f, 4f, 4f, whitePaint)
        whiteU = 2f / atlasSize
        whiteV = 2f / atlasSize
        cursorX = 5
        cursorY = 0
        rowHeight = 4

        texArr.uploadLayer(layer, bitmap.copy(Bitmap.Config.ARGB_8888, false))
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
            val ta = texArray ?: return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ta.textureId)
            for (region in dirtyRegions) {
                val h = region[3].coerceAtMost(atlasSize - region[1])
                if (h <= 0) continue
                val sub = Bitmap.createBitmap(bitmap, 0, region[1], atlasSize, h)
                val buf = java.nio.ByteBuffer.allocateDirect(atlasSize * h * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                sub.copyPixelsToBuffer(buf)
                buf.position(0)
                GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0,
                    0, region[1], layer, atlasSize, h, 1,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                sub.recycle()
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
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
        val fpv = 9 // floats per vertex
        val fpq = fpv * 4 // floats per quad
        val L = TextureArray.LAYER_FONT.toFloat()
        val floats = FloatArray(metrics.size * fpq)
        var cx = 0f
        var count = 0
        for (m in metrics) {
            if (m === EMPTY_GLYPH) continue
            val off = count * fpq
            val gx = cx; val gy = -m.ascent
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b] = vx; floats[b+1] = vy
                floats[b+2] = vu; floats[b+3] = vv; floats[b+4] = L
                floats[b+5] = 1f; floats[b+6] = 1f; floats[b+7] = 1f; floats[b+8] = 1f
            }
            v(off,          gx,     gy,       m.u0, m.v0)
            v(off + fpv,    gx+m.w, gy,       m.u1, m.v0)
            v(off + fpv*2,  gx+m.w, gy+m.h,   m.u1, m.v1)
            v(off + fpv*3,  gx,     gy+m.h,   m.u0, m.v1)
            cx += m.advance
            count++
        }
        val trimmed = floats.copyOf(count * fpq)
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

        val fpv = 9; val fpq = fpv * 4
        val L = TextureArray.LAYER_FONT.toFloat()
        val floats = FloatArray((metrics.size + 1) * fpq)
        var cx = 0f
        var count = 0
        for ((i, m) in metrics.withIndex()) {
            if (m === EMPTY_GLYPH) continue
            val remaining = metrics.size - i - 1
            if (remaining > 0 && cx + m.advance + ellipsisW > maxWidth) {
                val eHash = "…".hashCode().toLong() * 31 + sizePx
                val eBaked = bakedCache[eHash]
                if (eBaked != null) {
                    System.arraycopy(eBaked.floats, 0, floats, count * fpq, eBaked.floats.size)
                    for (j in 0 until eBaked.quadCount * 4) {
                        floats[count * fpq + j * fpv] += cx
                    }
                    count += eBaked.quadCount
                }
                break
            }
            if (cx + m.advance > maxWidth) break
            val off = count * fpq
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b] = vx; floats[b+1] = vy
                floats[b+2] = vu; floats[b+3] = vv; floats[b+4] = L
                floats[b+5] = 1f; floats[b+6] = 1f; floats[b+7] = 1f; floats[b+8] = 1f
            }
            v(off,          cx,     -m.ascent,       m.u0, m.v0)
            v(off + fpv,    cx+m.w, -m.ascent,       m.u1, m.v0)
            v(off + fpv*2,  cx+m.w, -m.ascent+m.h,   m.u1, m.v1)
            v(off + fpv*3,  cx,     -m.ascent+m.h,   m.u0, m.v1)
            cx += m.advance
            count++
        }
        val trimmed = floats.copyOf(count * fpq)
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
                    e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a, layer = TextureArray.LAYER_FONT.toFloat())
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
