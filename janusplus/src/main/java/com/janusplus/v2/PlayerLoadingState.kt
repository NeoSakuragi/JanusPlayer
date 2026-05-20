package com.janusplus.v2

import android.graphics.Bitmap
import android.graphics.Typeface
import com.janusplus.*
import kotlin.concurrent.thread

class PlayerLoadingState(
    private val item: JanusApi.LibraryItem,
    private val episode: JanusApi.Episode,
    private val baseUrl: String,
) : GameState {

    @Volatile private var ready = false
    @Volatile private var page: PlayerPage? = null
    private var startTime = System.nanoTime()
    @Volatile var lines = mutableListOf("loading...")

    private var debugAtlas: GlyphAtlas? = null

    override fun init(app: App) {
        startTime = System.nanoTime()
        val tf = app.defaultTypeface
        val atlas = GlyphAtlas(tf, 12f * app.density)
        app.uploadGlyphAtlas(atlas, atlas.build(
            listOf("abcdefghijklmnopqrstuvwxyz.|0123456789ms% "), app.texArray.size))
        debugAtlas = atlas

        thread {
            try { loadPage(app) } catch (e: Exception) {
                android.util.Log.e("PlayerLoading", "Load failed: ${e.message}")
                lines.add("FAILED: ${e.message}")
            }
        }
    }

    private fun loadPage(app: App) {
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0
        val api = app.api ?: return
        val density = app.density
        val texW = app.texArray.size
        val token = api.token ?: ""
        val fontAssets = listOf(
            "fonts/NotoSansJP-Regular.ttf", "fonts/NotoSerifJP-Regular.ttf",
            "fonts/ShipporiMincho-Regular.ttf", "fonts/KleeOne-Regular.ttf",
            "fonts/KosugiMaru-Regular.ttf"
        )

        val p = app.context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE)
        val fontIdx = p.getInt("font_idx", 0)
        val prefs = PlayerPrefs(
            deltaFurigana = p.getFloat("df", 0.7f),
            deltaRow = p.getFloat("dr", 1.4f),
            deltaSpacing = p.getFloat("ds", 0f),
            deltaYShift = p.getFloat("dy", 0f),
            subFontSize = p.getInt("font_size", 32),
            fontIdx = fontIdx,
            readingMode = p.getInt("reading_mode", 3),
            condensedMode = p.getBoolean("condensed", false),
            debugBoxes = p.getBoolean("debug_boxes", false),
            einkMode = p.getBoolean("eink_mode", false),
        )

        lines.add("prefs ${ms()}ms")

        var superSRT: JanusApi.SuperSRT? = null
        var cues: List<SrtParser.Cue> = emptyList()

        val superThread = Thread {
            superSRT = try { api.fetchSuperSRT(item.id, episode.season, episode.episode) } catch (_: Exception) { null }
        }.also { it.start() }

        val srtThread = if (episode.subtitles.isNotEmpty()) {
            val jaTrack = episode.subtitles.firstOrNull { it.language == "ja" } ?: episode.subtitles.first()
            Thread {
                try {
                    val srtUrl = "$baseUrl/api/subs/${item.id}/${jaTrack.srtFile}"
                    val request = okhttp3.Request.Builder().url(srtUrl)
                        .header("Authorization", "Bearer $token").build()
                    val response = okhttp3.OkHttpClient().newCall(request).execute()
                    if (response.isSuccessful) cues = SrtParser.parse(response.body?.string() ?: "")
                    response.close()
                } catch (_: Exception) {}
            }.also { it.start() }
        } else null

        superThread.join()
        srtThread?.join()
        lines.add("subs ${ms()}ms")

        val superData = superSRT
        val cueData = cues

        val allSubTexts = mutableListOf<String>()
        if (superData != null) {
            for (cue in superData.cues) {
                for (w in cue.words) {
                    allSubTexts.add(w.surface); allSubTexts.add(w.reading)
                    if (w.inflection.isNotEmpty()) allSubTexts.add(w.inflection)
                    for (f in w.furigana) allSubTexts.add(f.reading)
                }
            }
            for (entry in superData.dict) {
                allSubTexts.add(entry.term); allSubTexts.add(entry.reading)
                allSubTexts.addAll(entry.meanings); allSubTexts.add(entry.jlpt)
            }
        } else {
            for (cue in cueData) allSubTexts.add(cue.text)
        }
        allSubTexts.add("0123456789. ")
        allSubTexts.add("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        allSubTexts.add("()[]{}「」、。！？…～ー;:/")
        allSubTexts.add(ReadingUtils.HIRAGANA)
        allSubTexts.add(ReadingUtils.KATAKANA)

        val tf = try { android.graphics.Typeface.createFromAsset(app.context.assets, fontAssets[fontIdx.coerceIn(0, fontAssets.size - 1)]) }
                 catch (_: Exception) { app.defaultTypeface }
        val uiTexts = listOf(
            "${episode.episode}. ${episode.title()}",
            "←", "▶", "⏮", "⏭", "●", Lang.s("settings"),
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "0123456789:.%()/-+sp <>x",
            "Audio Subtitle Reading Mode Font Size Speed Condensed Theme Debug Boxes Next SKIP INTRO CANCEL Episode in",
            "DF DR DS DY Furigana Row Space Letter Y Offset E-Ink Dark",
            "PRO ADVANCED INTERMEDIATE NOVICE ON OFF Track Japanese",
            "Noto Sans Serif Shippori Klee One",
        )

        val uiAtlases = mutableListOf<Triple<Int, GlyphAtlas, Bitmap>>()
        val uiBase = 18
        val uiBasePx = (uiBase * density).toInt()
        val uiAtlas = GlyphAtlas(tf, uiBase * density)
        uiAtlases.add(Triple(uiBasePx, uiAtlas, uiAtlas.build(uiTexts, texW)))
        val iconAtlas = GlyphAtlas(tf, 36 * density)
        uiAtlases.add(Triple((36 * density).toInt(), iconAtlas, iconAtlas.build(listOf("▶"), texW)))

        var subAtlas: GlyphAtlas? = null; var subBmp: Bitmap? = null
        if (allSubTexts.isNotEmpty()) {
            val sa = GlyphAtlas(tf, prefs.subFontSize * density)
            subBmp = sa.build(allSubTexts, texW); subAtlas = sa
        }

        lines.add("glyphs ${ms()}ms")

        page = PlayerPage(item, episode, baseUrl, prefs, cueData, superData,
            uiAtlases, subAtlas, subBmp, null, null, emptyList())
        ready = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        if (ready) {
            val p = page ?: return
            ready = false
            app.lastLoadLog.add("--- player load ---")
            app.lastLoadLog.addAll(lines)
            app.replace(Screen.PLAYER, PlayerState(p))
        }
        for (a in actions) { if (a == Action.BACK) app.goBack() }
    }

    override fun draw(app: App, rc: RC) {
        rc.bg()
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        rc.spinner(rc.w / 2f, rc.h * 0.3f, elapsed)
        val atlas = debugAtlas ?: return
        val pad = 2f
        val lineH = atlas.lineHeight + rc.dp(3f)
        val snapshot = lines.toList()
        for ((i, line) in snapshot.withIndex()) {
            var cx = rc.dp(16f)
            val y = rc.h * 0.45f + i * lineH
            if (y > rc.h) break
            for (ch in line) {
                val g = atlas.glyphs[ch.code] ?: continue
                rc.batch.addQuad(cx - pad, y - g.ascent, g.w, g.h,
                    g.u0, g.v0, g.u1, g.v1, 0.5f, 0.8f, 0.5f, 1f, layer = g.page.toFloat())
                cx += g.advance
            }
        }
    }

    override fun cleanup(app: App) {}
}
