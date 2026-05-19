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

    override fun init(app: App) {
        startTime = System.nanoTime()

        thread {
            try { loadPage(app) } catch (e: Exception) {
                android.util.Log.e("SeriesLoading", "Load failed: ${e.message}")
            }
        }
    }

    private fun loadPage(app: App) {
            val api = app.api ?: return
            val density = app.density

            val header = api.fetchPageHeader(item.id, 1) ?: return
            val json = JSONObject(header.metadataJson)
            val locales = json.optJSONObject("locales")
            val eps = json.getJSONArray("episodes")
            val lang = Lang.current

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

            // 2. Fetch full episode data (filenames, subtitles) for playback
            val seasonData = api.fetchSeason(item.id, 1)
            val fullEpisodes = seasonData?.episodes ?: emptyList()

            // 3. Decode banner
            var bannerBmp: android.graphics.Bitmap? = null
            if (header.bannerJpeg != null && header.bannerW > 0) {
                bannerBmp = BitmapFactory.decodeByteArray(header.bannerJpeg, 0, header.bannerJpeg.size)
            }

            // 4. Fetch + decode thumbnail atlas
            var thumbBmp: android.graphics.Bitmap? = null
            val atlasBytes = api.fetchPageAtlas(item.id, 1)
            if (atlasBytes != null && atlasBytes.isNotEmpty()) {
                thumbBmp = BitmapFactory.decodeByteArray(atlasBytes, 0, atlasBytes.size)
            }

            // 5. Build glyph atlas — scan ALL text for unique characters
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

            page = SeriesDisplayPage(
                item = item,
                title = title,
                synopsis = synopsis,
                episodeCount = episodeCount,
                episodes = episodes,
                fullEpisodes = fullEpisodes,
                bannerBmp = bannerBmp,
                bannerW = header.bannerW, bannerH = header.bannerH,
                thumbBmp = thumbBmp,
                thumbW = header.thumbW.toFloat(), thumbH = header.thumbH.toFloat(),
                atlasW = header.atlasW, atlasH = header.atlasH, atlasCols = header.atlasCols,
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
            app.replace(Screen.SERIES, SeriesDisplayState(p))
        }
        for (a in actions) { if (a == Action.BACK) app.goBack() }
    }

    override fun draw(app: App, rc: RC) {
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Spinner
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        rc.spinner(rc.w / 2f, rc.h / 2f, elapsed)
    }

    override fun cleanup(app: App) {}
}
