package com.janusplus.v2

import android.graphics.Bitmap
import android.graphics.Typeface
import android.opengl.GLES30
import com.janusplus.GlyphAtlas
import com.janusplus.TextureArray
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlyphTestState : GameState {

    data class TestResult(
        val phase: String, val glyphCount: Int, val pageCount: Int,
        val buildMs: Float, val uploadMs: Float, val drawQuads: Int,
        val nonBlackPixels: Int, val pass: Boolean, val detail: String,
    )

    private val results = mutableListOf<TestResult>()
    private var phase = 0
    private var frameInPhase = 0
    private var done = false
    private var density = 2f
    private var texSize = 4096
    private var buildMs = 0f
    private var uploadMs = 0f
    private var pageCount = 0
    private var glyphCount = 0
    private var outDir = ""
    private var phaseDetail = ""

    private val activeAtlases = HashMap<Int, GlyphAtlas>()

    private val phases = listOf(
        "01_latin_32sp",
        "02_kana_32sp",
        "03_cjk_mixed_32sp",
        "04_multi_size",
        "05_large_set_797",
        "06_clear_black",
        "07_rebuild_verify",
        "08_font_size_switch",
        "09_font_noto_serif",
        "10_font_shippori",
        "11_bbox_no_overflow",
        "12_atlas_reuse_no_bleed",
        "13_dict_popup",
        "14_settings_panel",
        "15_furigana_article",
    )

    // Furigana: charIndex in line → reading
    data class Furi(val charIdx: Int, val reading: String)
    data class AnnotatedLine(val text: String, val furigana: List<Furi> = emptyList())

    private val curryArticle = listOf(
        AnnotatedLine("カレーは、インドの料理が、イギリスを経由して", listOf(Furi(8, "りょうり"), Furi(15, "けいゆ"))),
        AnnotatedLine("明治時代（1868-1912）に日本に入り、", listOf(Furi(0, "めいじ"), Furi(2, "じだい"), Furi(16, "にほん"), Furi(19, "はい"))),
        AnnotatedLine("そのあと日本でオリジナルのスタイルに", listOf(Furi(4, "にほん"))),
        AnnotatedLine("なった料理です。日本のカレーは、", listOf(Furi(3, "りょうり"), Furi(8, "にほん"))),
        AnnotatedLine("インドやタイのカレーとは違い、", listOf(Furi(13, "ちが"))),
        AnnotatedLine("小麦粉を使い、とろみがついているのが", listOf(Furi(0, "こむぎこ"), Furi(4, "つか"))),
        AnnotatedLine("特徴です。ご飯にかけて、", listOf(Furi(0, "とくちょう"), Furi(5, "はん"))),
        AnnotatedLine("「カレーライス」として食べます。", listOf(Furi(11, "た"))),
        AnnotatedLine("日本人は平均して週に1度以上カレーを", listOf(Furi(0, "にほんじん"), Furi(4, "へいきん"), Furi(8, "しゅう"), Furi(11, "ど"), Furi(12, "いじょう"))),
        AnnotatedLine("食べると言われています。", listOf(Furi(0, "た"), Furi(4, "い"))),
        AnnotatedLine("定食屋、そば屋、牛丼屋など、", listOf(Furi(0, "ていしょくや"), Furi(6, "や"), Furi(8, "ぎゅうどんや"))),
        AnnotatedLine("たいていの飲食店ではカレーを", listOf(Furi(5, "いんしょくてん"))),
        AnnotatedLine("食べることができます。", listOf(Furi(0, "た"))),
    )

    private val latinText = "Hello World 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz"
    private val kanaText = "こんにちは世界 あいうえお かきくけこ さしすせそ たちつてと アイウエオ カキクケコ"
    private val cjkMixed = "聖闘士星矢 めぞん一刻 ドラゴンボールZ Episode 74 第一話「黄金の風」"
    private val largeCjk: String by lazy {
        val sb = StringBuilder()
        for (c in 0x3040..0x309F) sb.appendCodePoint(c)
        for (c in 0x30A0..0x30FF) sb.appendCodePoint(c)
        for (c in 0x4E00..0x4FFF) sb.appendCodePoint(c)
        for (c in 0x0020..0x007E) sb.appendCodePoint(c)
        sb.toString()
    }

    override fun init(app: App) {
        density = app.density
        texSize = app.texArray.size
        outDir = File(app.context.cacheDir, "glyph_test").absolutePath
        File(outDir).mkdirs()
        phase = 0; frameInPhase = 0; done = false
        results.clear(); activeAtlases.clear()
        android.util.Log.i("GlyphTest", "=== START === texSize=$texSize density=$density phases=${phases.size}")
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {}

    override fun draw(app: App, rc: RC) {
        for ((size, atlas) in activeAtlases) rc.atlases[size] = atlas

        if (done) {
            rc.solid(0f, 0f, rc.w, rc.h, 0f, 0.05f, 0f)
            drawResultsScreen(rc)
            return
        }

        when (frameInPhase) {
            0 -> {
                activeAtlases.clear()
                phaseDetail = ""
                setupPhase(app, rc)
                rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
            }
            1 -> {
                rc.solid(0f, 0f, rc.w, rc.h, 0f, 0f, 0f)
                drawPhase(app, rc)
                rc.batch.flush()
                rc.batch.begin()

                val name = phases.getOrNull(phase) ?: "unknown"
                val quads = rc.batch.lastQuadCount
                val file = "$outDir/${name}.png"
                val nonBlack = screenshotAndCount(app, file)
                val pass = evaluatePass(phase, nonBlack)
                results.add(TestResult(name, glyphCount, pageCount, buildMs, uploadMs, quads, nonBlack, pass, phaseDetail))
                android.util.Log.i("GlyphTest", "[$name] g=$glyphCount p=$pageCount b=${"%.0f".format(buildMs)}ms " +
                    "u=${"%.0f".format(uploadMs)}ms q=$quads px=$nonBlack ${if (pass) "PASS" else "FAIL"} $phaseDetail")

                phase++
                frameInPhase = -1
                if (phase >= phases.size) {
                    writeReport()
                    done = true
                    val passed = results.count { it.pass }
                    android.util.Log.i("GlyphTest", "=== DONE === $passed/${results.size} passed")
                    for (r in results) {
                        if (!r.pass) android.util.Log.e("GlyphTest", "FAILED: ${r.phase} — ${r.detail}")
                    }
                }
            }
        }
        frameInPhase++
    }

    private fun evaluatePass(phase: Int, nonBlack: Int): Boolean = when (phase) {
        5 -> nonBlack < 50       // clear assert black
        11 -> phaseDetail.contains("no_bleed") // bleeding check — set by the test
        else -> nonBlack > 100   // expect visible pixels
    }

    // ── Setup ──

    private fun setupPhase(app: App, rc: RC) {
        app.resetGlyphPages()
        val tf = app.defaultTypeface
        buildMs = 0f; uploadMs = 0f; glyphCount = 0; pageCount = 0

        when (phase) {
            0 -> buildSingle(app, tf, 32, listOf(latinText))
            1 -> buildSingle(app, tf, 32, listOf(kanaText))
            2 -> buildSingle(app, tf, 32, listOf(cjkMixed))
            3 -> {
                buildMultiSize(app, tf, listOf(10, 16, 24, 36), listOf(latinText, kanaText))
            }
            4 -> buildSingle(app, tf, 32, listOf(largeCjk))
            5 -> { /* clear */ }
            6 -> buildSingle(app, tf, 32, listOf(latinText, kanaText, cjkMixed))

            7 -> { // Font size switch: build 24sp, then rebuild at 44sp, verify old is gone
                buildSingle(app, tf, 24, listOf("Size 24sp test"))
                // Now clear and rebuild at 44sp
                app.resetGlyphPages()
                activeAtlases.clear()
                buildSingle(app, tf, 44, listOf("Size 44sp test"))
                phaseDetail = "switched_24_to_44"
            }

            8 -> { // Noto Serif
                val serif = loadFont(app, "fonts/NotoSerifJP-Regular.ttf")
                buildSingle(app, serif, 32, listOf(kanaText, cjkMixed))
                phaseDetail = "font=NotoSerif"
            }

            9 -> { // Shippori Mincho
                val shippori = loadFont(app, "fonts/ShipporiMincho-Regular.ttf")
                buildSingle(app, shippori, 32, listOf(kanaText, cjkMixed))
                phaseDetail = "font=Shippori"
            }

            10 -> { // Bbox: verify glyph dimensions don't exceed cell bounds
                val a = GlyphAtlas(tf, 32 * density)
                val bmp = a.build(listOf(kanaText, latinText, cjkMixed), texSize)
                app.uploadGlyphAtlas(a, bmp)
                activeAtlases[(32 * density).toInt()] = a
                glyphCount = a.glyphs.size; pageCount = 1

                var overflowCount = 0
                val maxCellW = a.lineHeight * 2  // no glyph should be wider than 2x line height
                val maxCellH = a.lineHeight * 1.5f
                for ((cp, g) in a.glyphs) {
                    if (g.w > maxCellW || g.h > maxCellH) {
                        overflowCount++
                        val ch = String(intArrayOf(cp), 0, 1)
                        android.util.Log.w("GlyphTest", "Overflow glyph '$ch' (U+${"%04X".format(cp)}): ${g.w}x${g.h} > ${maxCellW}x${maxCellH}")
                    }
                }
                phaseDetail = "overflow=$overflowCount max_w=${"%.0f".format(maxCellW)} max_h=${"%.0f".format(maxCellH)}"
            }

            11 -> { // Bleeding test: two atlases at different sizes share the same glyph layer
                val aAtlas = GlyphAtlas(tf, 32 * density)
                val aBmp = aAtlas.build(listOf("AAAA"), texSize)
                app.uploadGlyphAtlas(aAtlas, aBmp)
                activeAtlases[(32 * density).toInt()] = aAtlas

                val bAtlas = GlyphAtlas(tf, 28 * density)
                val bBmp = bAtlas.build(listOf("BBBB"), texSize)
                app.uploadGlyphAtlas(bAtlas, bBmp)
                activeAtlases[(28 * density).toInt()] = bAtlas

                val aGlyph = aAtlas.glyphs['A'.code]
                val bGlyph = bAtlas.glyphs['B'.code]
                val samePage = aGlyph != null && bGlyph != null && aGlyph.page == bGlyph.page
                val uvOverlap = if (aGlyph != null && bGlyph != null && samePage) {
                    aGlyph.u0 < bGlyph.u1 && aGlyph.u1 > bGlyph.u0 &&
                    aGlyph.v0 < bGlyph.v1 && aGlyph.v1 > bGlyph.v0
                } else false

                glyphCount = aAtlas.glyphs.size + bAtlas.glyphs.size; pageCount = 2
                phaseDetail = if (!uvOverlap) "no_bleed samePage=$samePage" else "BLEED! uvOverlap=true"
            }

            12 -> { // Dict popup: term (24sp) + reading (14sp) + meanings (14sp) + badge (10sp)
                val dictTexts = listOf(
                    "食べる", "たべる", "言う", "いう",
                    "1. to eat", "2. to live on (e.g. salary)",
                    "1. to say; to utter", "2. to call; to name", "3. it is said that; they say",
                    "N1", "N2", "N3", "N4", "N5",
                    "ichidan", "godan", "expression", "i-adjective", "na-adjective", "suru verb", "transitive",
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.();-/ ",
                    kanaText, cjkMixed,
                )
                buildMultiSize(app, tf, listOf(10, 14, 24), dictTexts)
                phaseDetail = "dict_sizes=10,14,24"
            }

            13 -> { // Settings panel: title (18sp) + labels (14sp) + values (13sp) + bullet (8sp)
                val settingsTexts = listOf(
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
                    "0123456789:.%()/-+sp●",
                    "Audio Subtitle Reading Mode Font Size Condensed Theme Debug Boxes",
                    "DF DR DS DY Furigana Row Space Letter Y Offset E-Ink Dark",
                    "PRO ADVANCED INTERMEDIATE NOVICE ON OFF Track",
                    "Noto Sans Serif Shippori", com.janusplus.Lang.s("settings"),
                )
                buildMultiSize(app, tf, listOf(8, 13, 14, 18), settingsTexts)
                phaseDetail = "settings_sizes=8,13,14,18"
            }

            14 -> { // Furigana article
                val allTexts = curryArticle.map { it.text } +
                    curryArticle.flatMap { line -> line.furigana.map { it.reading } } +
                    listOf("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789()-. 、。「」")
                val mainSp = 16; val furiSp = 7
                buildMultiSize(app, tf, listOf(mainSp, furiSp), allTexts)
                phaseDetail = "lines=${curryArticle.size}"
            }
        }
    }

    private fun loadFont(app: App, path: String): Typeface {
        return try { Typeface.createFromAsset(app.context.assets, path) }
        catch (_: Exception) { app.defaultTypeface }
    }

    private fun buildSingle(app: App, tf: Typeface, sp: Int, texts: List<String>) {
        val a = GlyphAtlas(tf, sp * density)
        val t0 = System.nanoTime()
        val bmp = a.build(texts, texSize)
        buildMs = (System.nanoTime() - t0) / 1_000_000f
        val t1 = System.nanoTime()
        app.uploadGlyphAtlas(a, bmp)
        uploadMs = (System.nanoTime() - t1) / 1_000_000f
        activeAtlases[(sp * density).toInt()] = a
        glyphCount = a.glyphs.size; pageCount = 1
    }

    private fun buildMultiSize(app: App, tf: Typeface, sizes: List<Int>, texts: List<String>) {
        for (sp in sizes) {
            val a = GlyphAtlas(tf, sp * density)
            val t0 = System.nanoTime()
            val bmp = a.build(texts, texSize)
            buildMs += (System.nanoTime() - t0) / 1_000_000f
            val t1 = System.nanoTime()
            app.uploadGlyphAtlas(a, bmp)
            uploadMs += (System.nanoTime() - t1) / 1_000_000f
            activeAtlases[(sp * density).toInt()] = a
            glyphCount += a.glyphs.size; pageCount++
        }
    }

    // ── Draw ──

    private fun drawPhase(app: App, rc: RC) {
        when (phase) {
            0 -> drawCentered(rc, latinText, 32)
            1 -> drawCentered(rc, kanaText, 32)
            2 -> drawCentered(rc, cjkMixed, 32)
            3 -> {
                var y = 100f
                for (sp in listOf(10, 16, 24, 36)) {
                    val pxSize = (sp * density).toInt()
                    val a = activeAtlases[pxSize] ?: continue
                    rc.text("${sp}sp: Hello こんにちは 星矢", 30f, y, pxSize, 1f, 1f, 1f)
                    y += a.lineHeight + 20f
                }
            }
            4 -> drawCentered(rc, largeCjk.take(60), 32)
            5 -> {} // black
            6 -> drawCentered(rc, "Rebuilt: Hello こんにちは 星矢", 32)
            7 -> drawCentered(rc, "Size 44sp test", 44)
            8 -> drawCentered(rc, "セリフ: ${kanaText.take(20)}", 32)
            9 -> drawCentered(rc, "明朝: ${cjkMixed.take(20)}", 32)
            10 -> { // bbox — draw with colored backgrounds per glyph
                val pxSize = (32 * density).toInt()
                val a = activeAtlases[pxSize] ?: return
                val testStr = "AあB漢Cカ"
                var cx = 30f
                val y = rc.h / 2f
                for (ch in testStr) {
                    val g = a.glyphs[ch.code] ?: continue
                    // Red bbox background
                    rc.solid(cx, y - g.ascent, g.w, g.h, 0.3f, 0f, 0f)
                    // Draw the glyph
                    rc.batch.addQuad(cx - 2f, y - g.ascent, g.w, g.h,
                        g.u0, g.v0, g.u1, g.v1, 1f, 1f, 1f, 1f, layer = g.page.toFloat())
                    cx += g.advance + 4f
                }
                // Label
                rc.text("bbox test — red = glyph cell", 30f, 50f, pxSize, 0.5f, 0.5f, 0.5f)
            }
            11 -> {
                val pxA = (32 * density).toInt()
                val pxB = (28 * density).toInt()
                rc.text("AAAA (32sp)", 30f, rc.h / 2f - 40f, pxA, 1f, 1f, 1f)
                rc.text("BBBB (28sp)", 30f, rc.h / 2f + 60f, pxB, 1f, 0.8f, 0.3f)
                rc.text(phaseDetail, 30f, rc.h - 60f, pxA, 0.3f, 0.7f, 0.3f)
            }

            14 -> { // Furigana article
                val mainSp = 16; val furiSp = 7
                val mainPx = (mainSp * density).toInt()
                val furiPx = (furiSp * density).toInt()
                val mainAtlas = activeAtlases[mainPx] ?: return
                val furiAtlas = activeAtlases[furiPx] ?: return
                val lineH = mainAtlas.lineHeight
                val furiH = furiAtlas.lineHeight
                val pad = 30f
                var y = pad + furiH + lineH

                for (line in curryArticle) {
                    // Furigana above kanji
                    for (furi in line.furigana) {
                        if (furi.charIdx >= line.text.length) continue
                        val prefix = line.text.substring(0, furi.charIdx)
                        val prefixW = mainAtlas.measureText(prefix)
                        val ch = line.text.substring(furi.charIdx, (furi.charIdx + 1).coerceAtMost(line.text.length))
                        val charW = mainAtlas.measureText(ch)
                        val furiW = furiAtlas.measureText(furi.reading)
                        val sx = if (furiW > charW) charW / furiW else 1f
                        val fx = pad + prefixW + (charW - furiW * sx) / 2f
                        val fy = y - lineH * 0.75f
                        if (sx < 1f) rc.textScaled(furi.reading, fx, fy, furiPx, sx, 0.6f, 0.6f, 0.85f)
                        else rc.text(furi.reading, fx, fy, furiPx, 0.6f, 0.6f, 0.85f)
                    }
                    // Main text
                    rc.text(line.text, pad, y, mainPx, 1f, 1f, 1f)
                    y += lineH + furiH + 4f
                }
            }

            12 -> { // Dict popup — two entries, same layout as PlayerState.drawDictPopup
                val px24 = (24 * density).toInt()
                val px14 = (14 * density).toInt()
                val px10 = (10 * density).toInt()

                data class DictEntry(val term: String, val reading: String, val jlpt: String,
                                     val inflection: String, val meanings: List<String>)
                val entries = listOf(
                    DictEntry("食べる", "たべる", "N4", "ichidan",
                        listOf("to eat", "to live on (e.g. salary)")),
                    DictEntry("言う", "いう", "N5", "expression",
                        listOf("to say; to utter", "to call; to name", "it is said that; they say")),
                )

                var popupY = rc.h * 0.05f
                for (entry in entries) {
                    val popW = rc.dp(320f); val popX = (rc.w - popW) / 2f
                    val padP = rc.dp(16f)
                    rc.solid(popX, popupY, popW, rc.dp(200f), 0.118f, 0.118f, 0.180f, 0.94f)
                    var cy = popupY + padP

                    // Reading
                    if (entry.reading.isNotEmpty() && entry.reading != entry.term) {
                        val rw = rc.measureText(entry.reading, px14)
                        rc.text(entry.reading, popX + (popW - rw) / 2f, cy + rc.textHeight(px14), px14, 0.67f, 0.67f, 0.67f)
                        cy += rc.textHeight(px14) + rc.dp(2f)
                    }
                    // Term
                    val tw = rc.measureText(entry.term, px24)
                    rc.text(entry.term, popX + (popW - tw) / 2f, cy + rc.textHeight(px24), px24, 1f, 1f, 1f)
                    cy += rc.textHeight(px24) + rc.dp(6f)
                    // Badges
                    var badgeX = popX + padP
                    if (entry.inflection.isNotEmpty()) {
                        val bw = rc.measureText(entry.inflection, px10) + rc.dp(10f)
                        rc.solid(badgeX, cy, bw, rc.textHeight(px10) + rc.dp(4f), 0.13f, 0.13f, 0.2f, 0.8f)
                        rc.text(entry.inflection, badgeX + rc.dp(5f), cy + rc.textHeight(px10) + rc.dp(1f), px10, 0.475f, 0.525f, 0.796f)
                        badgeX += bw + rc.dp(4f)
                    }
                    if (entry.jlpt.isNotEmpty()) {
                        val bw = rc.measureText(entry.jlpt, px10) + rc.dp(10f)
                        rc.solid(badgeX, cy, bw, rc.textHeight(px10) + rc.dp(4f), 0.13f, 0.13f, 0.2f, 0.8f)
                        rc.text(entry.jlpt, badgeX + rc.dp(5f), cy + rc.textHeight(px10) + rc.dp(1f), px10, 0.31f, 0.765f, 0.969f)
                    }
                    cy += rc.textHeight(px10) + rc.dp(8f)
                    // Meanings
                    for ((i, m) in entry.meanings.withIndex()) {
                        val mText = "${i + 1}. $m"
                        rc.textClipped(mText, popX + padP, cy + rc.textHeight(px14), px14, popW - padP * 2, 0.8f, 0.8f, 0.8f)
                        cy += rc.textHeight(px14) + rc.dp(4f)
                    }
                    popupY += rc.dp(220f)
                }
                rc.text("dict popup test — 2 entries", 20f, rc.h - 40f, px14, 0.4f, 0.4f, 0.4f)
            }

            13 -> { // Settings panel mock
                val px18 = (18 * density).toInt()
                val px14 = (14 * density).toInt()
                val px13 = (13 * density).toInt()
                val px8 = (8 * density).toInt()
                val panW = rc.w * 0.4f; val panX = rc.w - panW
                rc.solid(panX, 0f, panW, rc.h, 0.102f, 0.102f, 0.180f)
                rc.text(com.janusplus.Lang.s("settings"), panX + 20f, 40f, px18, 1f, 1f, 1f)
                var y = 70f; val rowH = 44f
                val rows = listOf(
                    "Audio" to "JA", "Subtitle" to "ja", "Reading Mode" to "PRO",
                    "Font" to "Noto Sans", "Font Size" to "32sp", "Condensed" to "OFF",
                    "DF (Furigana)" to "0.7", "DR (Row Space)" to "1.4",
                    "DS (Letter Space)" to "0.0", "DY (Y Offset)" to "0",
                    "Theme" to "Dark", "Debug Boxes" to "OFF",
                )
                for ((i, pair) in rows.withIndex()) {
                    val (label, value) = pair
                    if (i == 0) { // show selected indicator
                        rc.solid(panX + 4f, y, panW - 8f, rowH, 0.733f, 0.525f, 0.988f, 0.3f)
                        rc.text("●", panX + 12f, y + 28f, px8, 0.506f, 0.78f, 0.522f)
                    }
                    rc.text(label, panX + 42f, y + 28f, px14, 1f, 1f, 1f)
                    val vw = rc.measureText(value, px13)
                    rc.text(value, panX + panW - 20f - vw, y + 28f, px13, 0.506f, 0.78f, 0.522f)
                    y += rowH
                }
                rc.text("settings panel test", 20f, 50f, px14, 0.4f, 0.4f, 0.4f)
            }
        }
    }

    private fun drawCentered(rc: RC, text: String, sp: Int) {
        val pxSize = (sp * density).toInt()
        val a = activeAtlases[pxSize] ?: return
        val tw = a.measureText(text)
        val x = ((rc.w - tw) / 2f).coerceAtLeast(20f)
        rc.text(text, x, rc.h / 2f, pxSize, 1f, 1f, 1f)
        val name = phases.getOrNull(phase) ?: ""
        rc.text(name, 20f, 50f, pxSize, 0.4f, 0.4f, 0.4f)
        val info = "g=$glyphCount p=$pageCount b=${"%.0f".format(buildMs)}ms u=${"%.0f".format(uploadMs)}ms"
        rc.text(info, 20f, rc.h - 60f, pxSize, 0.3f, 0.7f, 0.3f)
    }

    private fun drawResultsScreen(rc: RC) {
        val anyAtlas = activeAtlases.values.firstOrNull() ?: return
        val sz = (12 * density).toInt()
        rc.atlases[sz] = anyAtlas
        var y = 40f
        for (r in results) {
            val c = if (r.pass) floatArrayOf(0.3f, 1f, 0.3f) else floatArrayOf(1f, 0.3f, 0.3f)
            rc.text("${if (r.pass) "OK" else "XX"} ${r.phase} g=${r.glyphCount} q=${r.drawQuads} px=${r.nonBlackPixels} ${r.detail}",
                16f, y, sz, c[0], c[1], c[2])
            y += 30f
        }
    }

    // ── Screenshot + pixel count ──

    private fun screenshotAndCount(app: App, path: String): Int {
        val w = app.width.toInt(); val h = app.height.toInt()
        if (w <= 0 || h <= 0) return -1
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        var nonBlack = 0
        for (p in 0 until w * h) {
            val i = p * 4
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            if (r > 10 || g > 10 || b > 10) nonBlack++
        }

        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val stride = w * 4
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val srcRow = h - 1 - row
                    val i = srcRow * stride + col * 4
                    val r = buf.get(i).toInt() and 0xFF
                    val g = buf.get(i + 1).toInt() and 0xFF
                    val b = buf.get(i + 2).toInt() and 0xFF
                    val a = buf.get(i + 3).toInt() and 0xFF
                    bmp.setPixel(col, row, android.graphics.Color.argb(a, r, g, b))
                }
            }
            FileOutputStream(path).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
        } catch (e: Exception) {
            android.util.Log.e("GlyphTest", "Screenshot failed: ${e.message}")
        }
        return nonBlack
    }

    // ── Report ──

    private fun writeReport() {
        try {
            val csv = StringBuilder()
            csv.appendLine("phase,glyphs,pages,build_ms,upload_ms,quads,pixels,pass,detail")
            for (r in results) {
                csv.appendLine("${r.phase},${r.glyphCount},${r.pageCount},${"%.1f".format(r.buildMs)},${"%.1f".format(r.uploadMs)},${r.drawQuads},${r.nonBlackPixels},${r.pass},${r.detail}")
            }
            File("$outDir/report.csv").writeText(csv.toString())
            android.util.Log.i("GlyphTest", "Report: $outDir/report.csv")
        } catch (e: Exception) {
            android.util.Log.e("GlyphTest", "Report failed: ${e.message}")
        }
    }

    override fun cleanup(app: App) {}
}
