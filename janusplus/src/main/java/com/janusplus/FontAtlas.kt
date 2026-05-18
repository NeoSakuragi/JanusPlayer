package com.janusplus

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FontAtlas(private val assets: AssetManager) {

    data class GlyphKey(val codePoint: Int, val sizePx: Int)
    data class GlyphMetrics(val u0: Float, val v0: Float, val u1: Float, val v1: Float,
                            val w: Float, val h: Float, val advance: Float, val ascent: Float,
                            val layer: Int)

    private val cache = HashMap<GlyphKey, GlyphMetrics>(24000)
    private val bakedCache = HashMap<Long, BakedText>(256)
    data class BakedText(val floats: FloatArray, val quadCount: Int, val width: Float)

    var atlasSize = 4096
    var texArray: TextureArray? = null
    var baseLayer = TextureArray.LAYER_FONT
    var pageCount = 0
    private var bakedSize = 32  // SDF cell size
    private var bakedAscent = 0f
    private var bakedDescent = 0f

    var whiteU = 0f; private set
    var whiteV = 0f; private set

    private val EMPTY = GlyphMetrics(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0)

    fun initGL(texArr: TextureArray) {
        texArray = texArr
        atlasSize = texArr.size

        val name = "noto_sans_sdf"
        try {
            val binStream = assets.open("baked_fonts/$name.bin")
            val bytes = binStream.readBytes()
            binStream.close()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            pageCount = buf.int
            val glyphCount = buf.int
            for (i in 0 until glyphCount) {
                val cp = buf.int
                val page = buf.int
                val u0 = buf.float; val v0 = buf.float; val u1 = buf.float; val v1 = buf.float
                val w = buf.float; val h = buf.float; val advance = buf.float; val ascent = buf.float
                cache[GlyphKey(cp, bakedSize)] = GlyphMetrics(u0, v0, u1, v1, w, h, advance, ascent, baseLayer + page)
            }

            var maxAsc = 0f; var maxDesc = 0f
            for ((_, m) in cache) {
                if (m.ascent > maxAsc) maxAsc = m.ascent
                val desc = m.h - m.ascent
                if (desc > maxDesc) maxDesc = desc
            }
            bakedAscent = maxAsc; bakedDescent = maxDesc

            for (p in 0 until pageCount) {
                val pngStream = assets.open("baked_fonts/${name}_p$p.png")
                val bmp = BitmapFactory.decodeStream(pngStream)
                pngStream.close()
                texArr.uploadLayer(baseLayer + p, bmp)
            }

            whiteU = 1f / atlasSize
            whiteV = 1f / atlasSize

            Log.i("FontAtlas", "Loaded $glyphCount glyphs at ${bakedSize}px across $pageCount pages")
        } catch (e: Exception) {
            Log.e("FontAtlas", "Failed: ${e.message}")
            whiteU = 0f; whiteV = 0f
        }
    }

    fun ensureGlyphs(text: String, sizePx: Int) {}
    fun uploadDirtyGlyphs() {}

    private fun getGlyph(cp: Int): GlyphMetrics? = cache[GlyphKey(cp, bakedSize)]

    fun measureText(text: String, sizePx: Int): Float {
        val scale = sizePx.toFloat() / bakedSize
        var total = 0f
        for (cp in text.toCodePoints()) {
            val m = getGlyph(cp) ?: continue
            total += m.advance * scale
        }
        return total
    }

    private val sdfSpread = 4f

    fun textHeight(sizePx: Int): Float {
        val scale = sizePx.toFloat() / bakedSize
        return (bakedAscent + bakedDescent - sdfSpread * 2) * scale
    }

    fun textAscent(sizePx: Int): Float {
        val scale = sizePx.toFloat() / bakedSize
        return (bakedAscent - sdfSpread) * scale
    }

    fun addTextScaled(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                      scaleX: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        val scale = sizePx.toFloat() / bakedSize
        val cps = text.toCodePoints()
        val fpv = 9; val fpq = fpv * 4
        val floats = FloatArray(cps.size * fpq)
        var cx = 0f; var count = 0
        for (cp in cps) {
            val m = getGlyph(cp) ?: continue
            val sw = m.w * scale * scaleX; val sh = m.h * scale; val sa = m.ascent * scale
            val L = m.layer.toFloat()
            val off = count * fpq
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b]=vx; floats[b+1]=vy; floats[b+2]=vu; floats[b+3]=vv; floats[b+4]=L
                floats[b+5]=1f; floats[b+6]=1f; floats[b+7]=1f; floats[b+8]=1f
            }
            v(off, cx, -sa, m.u0, m.v0); v(off+fpv, cx+sw, -sa, m.u1, m.v0)
            v(off+fpv*2, cx+sw, -sa+sh, m.u1, m.v1); v(off+fpv*3, cx, -sa+sh, m.u0, m.v1)
            cx += m.advance * scale * scaleX; count++
        }
        val trimmed = floats.copyOf(count * fpq)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
    }

    fun addText(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                r: Float, g: Float, b: Float, a: Float = 1f) {
        val scale = sizePx.toFloat() / bakedSize
        val hash = text.hashCode().toLong() * 31 + sizePx
        val baked = bakedCache[hash]
        if (baked != null) {
            batch.addBaked(baked.floats, baked.quadCount, x, y, r, g, b, a)
            return
        }
        val cps = text.toCodePoints()
        val fpv = 9; val fpq = fpv * 4
        val floats = FloatArray(cps.size * fpq)
        var cx = 0f; var count = 0
        for (cp in cps) {
            val m = getGlyph(cp) ?: continue
            val sw = m.w * scale; val sh = m.h * scale; val sa = m.ascent * scale
            val L = m.layer.toFloat()
            val off = count * fpq
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b]=vx; floats[b+1]=vy; floats[b+2]=vu; floats[b+3]=vv; floats[b+4]=L
                floats[b+5]=1f; floats[b+6]=1f; floats[b+7]=1f; floats[b+8]=1f
            }
            v(off, cx, -sa, m.u0, m.v0); v(off+fpv, cx+sw, -sa, m.u1, m.v0)
            v(off+fpv*2, cx+sw, -sa+sh, m.u1, m.v1); v(off+fpv*3, cx, -sa+sh, m.u0, m.v1)
            cx += m.advance * scale; count++
        }
        val trimmed = floats.copyOf(count * fpq)
        bakedCache[hash] = BakedText(trimmed, count, cx)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
    }

    fun addTextClipped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        val scale = sizePx.toFloat() / bakedSize
        val hash = text.hashCode().toLong() * 31 + sizePx + (maxWidth * 100).toLong() * 97
        val baked = bakedCache[hash]
        if (baked != null) {
            batch.addBaked(baked.floats, baked.quadCount, x, y, r, g, b, a)
            return
        }
        val ellipsisAdv = (getGlyph('…'.code)?.advance ?: 0f) * scale
        val cps = text.toCodePoints()
        val fpv = 9; val fpq = fpv * 4
        val floats = FloatArray((cps.size + 1) * fpq)
        var cx = 0f; var count = 0
        for ((i, cp) in cps.withIndex()) {
            val m = getGlyph(cp) ?: continue
            val adv = m.advance * scale
            val L = m.layer.toFloat()
            if (cps.size - i > 1 && cx + adv + ellipsisAdv > maxWidth) {
                val em = getGlyph('…'.code)
                if (em != null) {
                    val off = count * fpq; val sw = em.w*scale; val sh = em.h*scale; val sa = em.ascent*scale
                    val eL = em.layer.toFloat()
                    fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                        floats[b]=vx; floats[b+1]=vy; floats[b+2]=vu; floats[b+3]=vv; floats[b+4]=eL
                        floats[b+5]=1f; floats[b+6]=1f; floats[b+7]=1f; floats[b+8]=1f
                    }
                    v(off, cx, -sa, em.u0, em.v0); v(off+fpv, cx+sw, -sa, em.u1, em.v0)
                    v(off+fpv*2, cx+sw, -sa+sh, em.u1, em.v1); v(off+fpv*3, cx, -sa+sh, em.u0, em.v1)
                    count++
                }
                break
            }
            if (cx + adv > maxWidth) break
            val off = count * fpq; val sw = m.w*scale; val sh = m.h*scale; val sa = m.ascent*scale
            fun v(b: Int, vx: Float, vy: Float, vu: Float, vv: Float) {
                floats[b]=vx; floats[b+1]=vy; floats[b+2]=vu; floats[b+3]=vv; floats[b+4]=L
                floats[b+5]=1f; floats[b+6]=1f; floats[b+7]=1f; floats[b+8]=1f
            }
            v(off, cx, -sa, m.u0, m.v0); v(off+fpv, cx+sw, -sa, m.u1, m.v0)
            v(off+fpv*2, cx+sw, -sa+sh, m.u1, m.v1); v(off+fpv*3, cx, -sa+sh, m.u0, m.v1)
            cx += adv; count++
        }
        val trimmed = floats.copyOf(count * fpq)
        bakedCache[hash] = BakedText(trimmed, count, cx)
        batch.addBaked(trimmed, count, x, y, r, g, b, a)
    }

    data class LayoutEntry(val dx: Float, val dy: Float, val m: GlyphMetrics)
    private val layoutCache = HashMap<Long, Pair<List<LayoutEntry>, Float>>(32)

    fun addTextWrapped(batch: QuadBatch, text: String, x: Float, y: Float, sizePx: Int,
                       maxWidth: Float, maxLines: Int,
                       r: Float, g: Float, b: Float, a: Float = 1f): Float {
        val scale = sizePx.toFloat() / bakedSize
        val hash = text.hashCode().toLong() * 31 + sizePx + (maxWidth * 100).toLong() * 97 + maxLines * 53
        val cached = layoutCache[hash]
        if (cached != null) {
            for (e in cached.first) {
                val sw = e.m.w * scale; val sh = e.m.h * scale; val sa = e.m.ascent * scale
                batch.addQuad(x + e.dx, y + e.dy - sa, sw, sh,
                    e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a, layer = e.m.layer.toFloat())
            }
            return y + cached.second
        }
        val lineH = textHeight(sizePx)
        val entries = mutableListOf<LayoutEntry>()
        var cx = 0f; var cy = 0f; var line = 1
        for (word in text.split(" ")) {
            val wordW = measureText(word, sizePx)
            val spaceW = measureText(" ", sizePx)
            if (cx > 0f && cx + wordW > maxWidth) {
                line++; if (line > maxLines) break
                cx = 0f; cy += lineH * 1.3f
            }
            for (cp in word.toCodePoints()) {
                val m = getGlyph(cp) ?: continue
                entries.add(LayoutEntry(cx, cy, m))
                cx += m.advance * scale
            }
            cx += spaceW
        }
        val totalH = cy + lineH
        layoutCache[hash] = entries to totalH
        for (e in entries) {
            val sw = e.m.w * scale; val sh = e.m.h * scale; val sa = e.m.ascent * scale
            batch.addQuad(x + e.dx, y + e.dy - sa, sw, sh,
                e.m.u0, e.m.v0, e.m.u1, e.m.v1, r, g, b, a, layer = e.m.layer.toFloat())
        }
        return y + totalH
    }
}

fun String.toCodePoints(): IntArray {
    val list = mutableListOf<Int>()
    var i = 0
    while (i < length) { val cp = Character.codePointAt(this, i); list.add(cp); i += Character.charCount(cp) }
    return list.toIntArray()
}
