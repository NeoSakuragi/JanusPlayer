package com.janusplus

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FontAtlas(private val assets: AssetManager) {

    data class GlyphKey(val codePoint: Int, val sizePx: Int)
    data class GlyphMetrics(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                            val w: Float, val h: Float, val advance: Float, val ascent: Float,
                            val layer: Float = 0f)

    private val cache = HashMap<GlyphKey, GlyphMetrics>(16384)
    private val knownStrings = HashSet<Long>()
    private val metricsCache = HashMap<Long, Array<GlyphMetrics>>(128)
    data class BakedText(val floats: FloatArray, val quadCount: Int, val width: Float)
    private val bakedCache = HashMap<Long, BakedText>(128)

    var atlasSize = 4096

    var texArray: TextureArray? = null
    private val bakedSizes = HashSet<Int>()
    private var fontPageCount = 0

    var whiteU = 0f; private set
    var whiteV = 0f; private set

    var measureCount = 0L
    var measureTimeNs = 0L
    var glyphsEmitted = 0
    var addTextCalls = 0
    var ensureCalls = 0

    fun initGL(texArr: TextureArray) {
        texArray = texArr
        atlasSize = texArr.size
        val bakedName = "noto_sans"

        try {
            val binStream = assets.open("baked_fonts/${bakedName}.bin")
            val binBytes = binStream.readBytes()
            binStream.close()
            val buf = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
            fontPageCount = buf.int
            val glyphCount = buf.int
            for (i in 0 until glyphCount) {
                val cp = buf.int; val sz = buf.int; val page = buf.int
                val u0 = buf.float; val v0 = buf.float; val u1 = buf.float; val v1 = buf.float
                val w = buf.float; val h = buf.float; val advance = buf.float; val ascent = buf.float
                cache[GlyphKey(cp, sz)] = GlyphMetrics(u0, v0, u1, v1, w, h, advance, ascent,
                    (TextureArray.LAYER_FONT_BASE + page).toFloat())
                bakedSizes.add(sz)
            }
            Log.i("FontAtlas", "Loaded $glyphCount glyphs across $fontPageCount pages, sizes=$bakedSizes")

            for (p in 0 until fontPageCount) {
                val pngStream = assets.open("baked_fonts/${bakedName}_page${p}.png")
                val bmp = BitmapFactory.decodeStream(pngStream)
                pngStream.close()
                texArr.uploadLayer(TextureArray.LAYER_FONT_BASE + p, bmp)
            }
            whiteU = 2f / atlasSize
            whiteV = 2f / atlasSize
        } catch (e: Exception) {
            Log.e("FontAtlas", "Failed to load baked font: ${e.message}")
            whiteU = 2f / atlasSize
            whiteV = 2f / atlasSize
        }
    }


    fun ensureGlyphs(text: String, sizePx: Int) {
        val hash = text.hashCode().toLong() * 31 + sizePx
        if (knownStrings.contains(hash)) return

        knownStrings.add(hash)
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
        var metrics = metricsCache[hash]
        if (metrics == null) {
            ensureGlyphs(text, sizePx)
            val cps = text.toCodePoints()
            metrics = Array(cps.size) { i -> cache[GlyphKey(cps[i], sizePx)] ?: EMPTY_GLYPH }
            metricsCache[hash] = metrics
        }
        val fpv = 9; val fpq = fpv * 4
        val floats = FloatArray(metrics.size * fpq)
        var cx = 0f; var count = 0
        for (m in metrics) {
            if (m === EMPTY_GLYPH) continue
            val off = count * fpq; val gx = cx; val gy = -m.ascent
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b] = vx; floats[b+1] = vy; floats[b+2] = vu; floats[b+3] = vv; floats[b+4] = m.layer
                floats[b+5] = 1f; floats[b+6] = 1f; floats[b+7] = 1f; floats[b+8] = 1f
            }
            v(off, gx, gy, m.u0, m.v0); v(off+fpv, gx+m.w, gy, m.u1, m.v0)
            v(off+fpv*2, gx+m.w, gy+m.h, m.u1, m.v1); v(off+fpv*3, gx, gy+m.h, m.u0, m.v1)
            cx += m.advance; count++
        }
        val trimmed = floats.copyOf(count * fpq)
        bakedCache[hash] = BakedText(trimmed, count, cx)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
        glyphsEmitted += count
    }

    fun measureText(text: String, sizePx: Int): Float {
        var total = 0f
        for (cp in text.toCodePoints()) {
            val m = cache[GlyphKey(cp, sizePx)]
            if (m != null) total += m.advance
        }
        return total
    }

    fun textHeight(sizePx: Int): Float {
        // Use baked glyph height from a reference character
        val ref = cache[GlyphKey('あ'.code, sizePx)] ?: cache[GlyphKey('A'.code, sizePx)]
        return ref?.h ?: (sizePx * 1.2f)
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
        val floats = FloatArray((metrics.size + 1) * fpq)
        var cx = 0f; var count = 0
        for ((i, m) in metrics.withIndex()) {
            if (m === EMPTY_GLYPH) continue
            val remaining = metrics.size - i - 1
            if (remaining > 0 && cx + m.advance + ellipsisW > maxWidth) {
                val eHash = "…".hashCode().toLong() * 31 + sizePx
                val eBaked = bakedCache[eHash]
                if (eBaked != null) {
                    System.arraycopy(eBaked.floats, 0, floats, count * fpq, eBaked.floats.size)
                    for (j in 0 until eBaked.quadCount * 4) { floats[count * fpq + j * fpv] += cx }
                    count += eBaked.quadCount
                }
                break
            }
            if (cx + m.advance > maxWidth) break
            val off = count * fpq
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b] = vx; floats[b+1] = vy; floats[b+2] = vu; floats[b+3] = vv; floats[b+4] = m.layer
                floats[b+5] = 1f; floats[b+6] = 1f; floats[b+7] = 1f; floats[b+8] = 1f
            }
            v(off, cx, -m.ascent, m.u0, m.v0); v(off+fpv, cx+m.w, -m.ascent, m.u1, m.v0)
            v(off+fpv*2, cx+m.w, -m.ascent+m.h, m.u1, m.v1); v(off+fpv*3, cx, -m.ascent+m.h, m.u0, m.v1)
            cx += m.advance; count++
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
                    e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a, layer = e.m.layer)
            }
            return y + cached.second
        }
        ensureGlyphs(text, sizePx)
        val lineH = textHeight(sizePx)
        val entries = mutableListOf<LayoutEntry>()
        var cx = 0f; var cy = 0f; var line = 1
        val words = text.split(" ")
        for (word in words) {
            val wordW = measureText(word, sizePx)
            val spaceW = measureText(" ", sizePx)
            if (cx > 0f && cx + wordW > maxWidth) {
                line++
                if (line > maxLines) { layoutCache[hash] = entries to (cy + lineH); break }
                cx = 0f; cy += lineH * 1.3f
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
                e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a, layer = e.m.layer)
        }
        return y + totalH
    }

    private companion object {
        val EMPTY_GLYPH = GlyphMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
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
