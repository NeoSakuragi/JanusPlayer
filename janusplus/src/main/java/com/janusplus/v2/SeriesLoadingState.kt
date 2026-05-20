package com.janusplus.v2

import android.graphics.BitmapFactory
import com.janusplus.*
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Loading screen: purple spinner at 60fps.
 * Background: fetch data, decode images, build glyph atlas, pre-compute layout.
 * When ready: transition to SeriesDisplayState with everything pre-baked.
 */
class SeriesLoadingState(private val item: JanusApi.LibraryItem) : GameState {

    @Volatile private var ready = false
    @Volatile private var page: SeriesDisplayPage? = null
    private var startTime = System.nanoTime()
    @Volatile var lines = mutableListOf("loading...")

    private var debugAtlas: GlyphAtlas? = null

    override fun init(app: App) {
        startTime = System.nanoTime()
        val tf = app.defaultTypeface
        val size = 12f * app.density
        val atlas = GlyphAtlas(tf, size)
        app.uploadGlyphAtlas(atlas, atlas.build(
            listOf("abcdefghijklmnopqrstuvwxyz.|0123456789ms% "), app.texArray.size))
        debugAtlas = atlas

        thread {
            try { loadPage(app) } catch (e: Exception) {
                android.util.Log.e("SeriesLoading", "Load failed: ${e.message}")
                lines.add("FAILED: ${e.message}")
            }
        }
    }

    private fun loadPage(app: App) {
            val api = app.api ?: return
            val density = app.density
            val lang = Lang.current

            val t = System.currentTimeMillis()
            fun ms() = System.currentTimeMillis() - t

            var header: JanusApi.PageHeader? = null
            var seasonData: JanusApi.SeasonData? = null
            var atlasBytes: ByteArray? = null

            lines.add("header...")
            val headerThread = Thread { header = api.fetchPageHeader(item.id, 1) }.also { it.start() }
            val seasonThread = Thread { seasonData = api.fetchSeason(item.id, 1) }.also { it.start() }
            val atlasThread = Thread { atlasBytes = api.fetchPageAtlas(item.id, 1) }.also { it.start() }

            headerThread.join(); lines.add("header ${ms()}ms")
            seasonThread.join(); lines.add("season ${ms()}ms")
            atlasThread.join(); lines.add("net done ${ms()}ms")
            val hdr = header ?: return

            val json = JSONObject(hdr.metadataJson)
            val locales = json.optJSONObject("locales")
            val eps = json.getJSONArray("episodes")

            val title = locales?.optJSONObject(lang)?.optString("title", "")
                ?.takeIf { it.isNotEmpty() } ?: json.optString("titleEn", "")

            val synopsis = when (lang) {
                "ja" -> locales?.optJSONObject("ja")?.optString("synopsis", "") ?: ""
                "fr" -> locales?.optJSONObject("fr")?.optString("synopsis", "") ?: ""
                else -> locales?.optJSONObject("en")?.optString("synopsis", "") ?: ""
            }

            val episodes = (0 until eps.length()).map { i ->
                val e = eps.getJSONObject(i)
                val epLocales = e.optJSONObject("locales")
                val epTitle = epLocales?.optJSONObject(lang)?.optString("title", "")
                    ?.takeIf { it.isNotEmpty() } ?: e.optString("titleEn", "")
                SeriesDisplayPage.Episode(e.getInt("episode"), epTitle, e.optInt("durationSec", 0))
            }

            val episodeCount = json.optInt("episodeCount", episodes.size)
            val fullEpisodes = seasonData?.episodes ?: emptyList()

            var coverBmp: android.graphics.Bitmap? = null
            if (hdr.coverJpeg != null) {
                coverBmp = BitmapFactory.decodeByteArray(hdr.coverJpeg, 0, hdr.coverJpeg.size)
            }
            var bannerBmp: android.graphics.Bitmap? = null
            if (hdr.bannerJpeg != null && hdr.bannerW > 0) {
                bannerBmp = BitmapFactory.decodeByteArray(hdr.bannerJpeg, 0, hdr.bannerJpeg.size)
            }
            if (coverBmp == null) coverBmp = bannerBmp

            lines.add("decode ${ms()}ms")
            var thumbBmp: android.graphics.Bitmap? = null
            val ab = atlasBytes
            if (ab != null && ab.isNotEmpty()) {
                thumbBmp = BitmapFactory.decodeByteArray(ab, 0, ab.size)
            }

            val allTexts = mutableListOf<String>()
            allTexts.add(title)
            allTexts.add(synopsis)
            allTexts.add(Lang.s("play"))
            allTexts.add(Lang.s("settings"))
            allTexts.add(Lang.s("episodes", episodeCount))
            allTexts.add("←")
            allTexts.add("▶ ")
            allTexts.add("0123456789fps")
            for (ep in episodes) {
                allTexts.add("${ep.episode}. ${ep.titleEn}")
                allTexts.add("${ep.durationSec / 60} min")
            }

            lines.add("parse ${ms()}ms")
            val tf = try { android.graphics.Typeface.createFromAsset(app.context.assets, "fonts/NotoSansJP-Regular.ttf") }
                     catch (_: Exception) { android.graphics.Typeface.DEFAULT }

            val ts = if (app.isTV) 2048 else 4096
            val titleAtlas = GlyphAtlas(tf, 28f * density)
            val titleBmp = titleAtlas.build(listOf(title, "←"), ts)
            val bodyAtlas = GlyphAtlas(tf, 13f * density)
            val bodyBmp = bodyAtlas.build(allTexts, ts)
            val btnAtlas = GlyphAtlas(tf, 16f * density)
            val btnBmp = btnAtlas.build(listOf(Lang.s("play"), "▶ "), ts)
            val smallAtlas = GlyphAtlas(tf, 10f * density)
            val smallBmp = smallAtlas.build(allTexts, ts)
            val settAtlas = GlyphAtlas(tf, 12f * density)
            val settBmp = settAtlas.build(listOf(Lang.s("settings")), ts)

            lines.add("glyphs ${ms()}ms")
            page = SeriesDisplayPage(
                item = item,
                title = title,
                synopsis = synopsis,
                episodeCount = episodeCount,
                episodes = episodes,
                fullEpisodes = fullEpisodes,
                coverBmp = coverBmp,
                bannerBmp = bannerBmp,
                bannerW = hdr.bannerW, bannerH = hdr.bannerH,
                thumbBmp = thumbBmp,
                thumbW = hdr.thumbW.toFloat(), thumbH = hdr.thumbH.toFloat(),
                atlasW = hdr.atlasW, atlasH = hdr.atlasH, atlasCols = hdr.atlasCols,
                titleAtlas = titleAtlas, titleBmp = titleBmp,
                bodyAtlas = bodyAtlas, bodyBmp = bodyBmp,
                btnAtlas = btnAtlas, btnBmp = btnBmp,
                smallAtlas = smallAtlas, smallBmp = smallBmp,
                settAtlas = settAtlas, settBmp = settBmp,
                density = density,
            )
            ready = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        if (ready) {
            val p = page ?: return
            ready = false
            app.lastLoadLog.add("--- series load ---")
            app.lastLoadLog.addAll(lines)
            app.replace(Screen.SERIES, SeriesDisplayState(p))
        }
        for (a in actions) { if (a == Action.BACK) app.goBack() }
    }

    override fun draw(app: App, rc: RC) {
        rc.bg()
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        rc.spinner(rc.w / 2f, rc.h * 0.3f, elapsed)
        val atlas = debugAtlas ?: return
        val pad = 2f
        val lineH = atlas.lineHeight + rc.dp(4f)
        val snapshot = lines.toList()
        for ((i, line) in snapshot.withIndex()) {
            var cx = rc.dp(20f)
            val y = rc.h * 0.45f + i * lineH
            if (y > rc.h) break
            for (ch in line) {
                val g = atlas.glyphs[ch.code] ?: continue
                rc.batch.addQuad(cx - pad, y - g.ascent, g.w, g.h,
                    g.u0, g.v0, g.u1, g.v1, 0.5f, 0.8f, 0.5f, 1f, layer = g.page.toFloat())
                cx += g.advance
            }
        }
        var cx = rc.dp(20f)
        val ty = rc.h * 0.45f + snapshot.size * lineH
        for (ch in "${"%.1f".format(elapsed)}s") {
            val g = atlas.glyphs[ch.code] ?: continue
            rc.batch.addQuad(cx - pad, ty - g.ascent, g.w, g.h,
                g.u0, g.v0, g.u1, g.v1, 1f, 1f, 1f, 1f, layer = g.page.toFloat())
            cx += g.advance
        }
    }

    override fun cleanup(app: App) {}
}
