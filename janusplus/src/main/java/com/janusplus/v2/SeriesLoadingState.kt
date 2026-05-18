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
            val api = app.api ?: return@thread
            val density = app.density

            // 1. Fetch page data
            val header = api.fetchPageHeader(item.id, 1) ?: return@thread
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
            for (ep in episodes) {
                allTexts.add("${ep.episode}. ${ep.titleEn}")
                allTexts.add("${ep.durationSec / 60} min")
            }

            val tf = try { android.graphics.Typeface.createFromAsset(app.context.assets, "fonts/NotoSansJP-Regular.ttf") }
                     catch (_: Exception) { android.graphics.Typeface.DEFAULT }

            // Build glyph atlases at the sizes we need
            val titleAtlas = GlyphAtlas(tf, 28f * density).also { it.build(listOf(title, "←")) }
            val bodyAtlas = GlyphAtlas(tf, 13f * density).also { it.build(allTexts) }
            val btnAtlas = GlyphAtlas(tf, 16f * density).also { it.build(listOf(Lang.s("play"), "▶ ")) }
            val smallAtlas = GlyphAtlas(tf, 10f * density).also { it.build(allTexts) }
            val settAtlas = GlyphAtlas(tf, 12f * density).also { it.build(listOf(Lang.s("settings"))) }

            // 6. Build the page
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
                titleAtlas = titleAtlas,
                bodyAtlas = bodyAtlas,
                btnAtlas = btnAtlas,
                smallAtlas = smallAtlas,
                settAtlas = settAtlas,
                density = density,
            )
            ready = true
        }
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        if (ready) {
            val p = page ?: return
            app.transition(Screen.SERIES, SeriesDisplayState(p))
        }
        for (key in keys) {
            if (key == android.view.KeyEvent.KEYCODE_BACK) app.goBack()
        }
    }

    override fun draw(app: App, rc: RC) {
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Spinner
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        val cx = rc.w / 2f; val cy = rc.h / 2f; val radius = rc.dp(24f)
        for (i in 0 until 12) {
            val angle = (i.toFloat() / 12) * 2f * Math.PI.toFloat() + elapsed * 6f
            val dotX = cx + kotlin.math.cos(angle) * radius
            val dotY = cy + kotlin.math.sin(angle) * radius
            val alpha = i.toFloat() / 12
            val dotR = rc.dp(3f + alpha * 2f)
            rc.solid(dotX - dotR, dotY - dotR, dotR * 2, dotR * 2, 0.733f, 0.525f, 0.988f, alpha)
        }
    }

    override fun cleanup(app: App) {}
}
