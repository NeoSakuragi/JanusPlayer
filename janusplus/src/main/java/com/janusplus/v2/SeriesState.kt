package com.janusplus.v2

import android.graphics.BitmapFactory
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.TextureArray
import org.json.JSONObject
import kotlin.concurrent.thread

class SeriesState(private val item: JanusApi.LibraryItem) : GameState {

    data class PageData(
        val titleEn: String, val titleJa: String,
        val synopsisEn: String, val synopsisJa: String, val synopsisFr: String,
        val episodeCount: Int,
        val episodes: List<EpisodeCard>,
    )
    data class EpisodeCard(val episode: Int, val titleEn: String, val durationSec: Int)

    // Full episode data for playback — fetched lazily
    @Volatile var fullEpisodes: List<JanusApi.Episode>? = null

    @Volatile var pageData: PageData? = null
    @Volatile var bannerReady = false
    @Volatile var atlasReady = false
    @Volatile var alive = true

    // Banner uploaded to LAYER_BANNER (uncompressed texture array)
    @Volatile var bannerW = 0
    @Volatile var bannerH = 0

    // Atlas uploaded to thumb ring buffer (uncompressed texture array)
    @Volatile var atlasW = 0
    @Volatile var atlasH = 0
    @Volatile var atlasCols = 0
    @Volatile var thumbWPx = 400f
    @Volatile var thumbHPx = 300f
    private var atlasLayer = 0

    // Pending bitmap uploads — background thread decodes JPEG, GL thread uploads
    @Volatile var pendingBannerBmp: android.graphics.Bitmap? = null
    @Volatile var pendingAtlasBmp: android.graphics.Bitmap? = null

    private var startTime = System.nanoTime()

    // ── Fixed layout ──
    private var pad = 0f; private var heroH = 0f
    private var titleY = 0f; private var btnY = 0f; private var btnW = 0f; private var btnH = 0f
    private var metaY = 0f; private var synopsisY = 0f; private var synopsisH = 0f
    private var gridY = 0f; private var gridCols = 0
    private var cardW = 0f; private var cardH = 0f; private var thumbH = 0f
    private var gridSpacing = 0f; private var textPad = 0f; private var contentMaxW = 0f
    private var dp50 = 100f
    private var titleSize = 0; private var btnTextSize = 0; private var metaSize = 0
    private var synopsisSize = 0; private var cardTitleSize = 0; private var cardDurSize = 0
    private var lineH = 0f; private var layoutDone = false

    override fun init(app: App) {
        bannerReady = false
        atlasReady = false
        pageData = null
        startTime = System.nanoTime()
        atlasLayer = app.texArray.nextThumbLayer()

        val api = app.api ?: return

        // Request 1: header — metadata + banner JPEG (~100KB-1MB, fast)
        thread {
            val header = api.fetchPageHeader(item.id, 1) ?: return@thread
            if (!alive) return@thread

            // Parse metadata with locales
            val json = JSONObject(header.metadataJson)
            val locales = json.optJSONObject("locales")
            val eps = json.getJSONArray("episodes")
            val cards = (0 until eps.length()).map { i ->
                val e = eps.getJSONObject(i)
                val epLocales = e.optJSONObject("locales")
                val lang = Lang.current
                val epTitle = epLocales?.optJSONObject(lang)?.optString("title", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: e.optString("titleEn", "")
                EpisodeCard(e.getInt("episode"), epTitle, e.optInt("durationSec", 0))
            }

            val lang = Lang.current
            val localeObj = locales?.optJSONObject(lang)
            pageData = PageData(
                titleEn = locales?.optJSONObject("en")?.optString("title", "")
                    ?: json.optString("titleEn", ""),
                titleJa = locales?.optJSONObject("ja")?.optString("title", "")
                    ?: json.optString("titleJa", ""),
                synopsisEn = locales?.optJSONObject("en")?.optString("synopsis", "") ?: "",
                synopsisJa = locales?.optJSONObject("ja")?.optString("synopsis", "") ?: "",
                synopsisFr = locales?.optJSONObject("fr")?.optString("synopsis", "") ?: "",
                episodeCount = json.optInt("episodeCount", cards.size),
                episodes = cards,
            )

            // Decode banner JPEG → queue bitmap for GL thread
            if (header.bannerJpeg != null && header.bannerW > 0) {
                val bmp = BitmapFactory.decodeByteArray(header.bannerJpeg, 0, header.bannerJpeg.size)
                if (bmp != null && alive) {
                    bannerW = bmp.width; bannerH = bmp.height
                    pendingBannerBmp = bmp
                }
            }
            atlasW = header.atlasW
            atlasH = header.atlasH
            atlasCols = header.atlasCols
            thumbWPx = header.thumbW.toFloat()
            thumbHPx = header.thumbH.toFloat()

            // Fetch full episode data for playback (filenames, subtitles)
            val seasonData = api.fetchSeasonBlob(item.id, 1)
            if (alive && seasonData != null) fullEpisodes = seasonData.episodes
        }

        // Request 2: atlas JPEG (~0.5-2MB, arrives in background)
        thread {
            val atlasBytes = api.fetchPageAtlas(item.id, 1) ?: return@thread
            if (!alive || atlasBytes.isEmpty()) return@thread
            val bmp = BitmapFactory.decodeByteArray(atlasBytes, 0, atlasBytes.size)
            if (bmp != null && alive) {
                pendingAtlasBmp = bmp
            }
        }
    }

    private fun computeLayout(rc: RC) {
        pad = rc.dp(32f); heroH = rc.dp(400f)
        titleSize = rc.sp(28); btnTextSize = rc.sp(16)
        metaSize = rc.sp(13); synopsisSize = rc.sp(13)
        cardTitleSize = rc.sp(13); cardDurSize = rc.sp(10)
        lineH = rc.font.textHeight(synopsisSize)
        titleY = heroH - rc.dp(120f)
        btnY = titleY + rc.dp(40f); btnW = rc.dp(200f); btnH = rc.dp(44f)
        metaY = btnY + btnH + rc.dp(14f)
        synopsisY = metaY + rc.dp(22f)
        synopsisH = lineH * 2 * 1.3f
        gridY = synopsisY + synopsisH
        gridSpacing = rc.dp(12f); textPad = rc.dp(8f)
        val availW = rc.w - pad * 2
        gridCols = 4
        cardW = (availW - gridSpacing * (gridCols - 1)) / gridCols
        thumbH = cardW / (thumbWPx / thumbHPx)
        cardH = thumbH + rc.dp(50f)
        contentMaxW = (rc.w * 0.6f).coerceAtMost(rc.w - pad * 2)
        dp50 = rc.dp(50f)
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        // Recalculate card height when thumb dimensions arrive
        if (layoutDone && cardW > 0) {
            val newThumbH = cardW / (thumbWPx / thumbHPx)
            if (newThumbH != thumbH) {
                thumbH = newThumbH
                cardH = thumbH + dp50
            }
        }

        val bBmp = pendingBannerBmp
        if (bBmp != null) {
            pendingBannerBmp = null
            app.texArray.uploadLayer(TextureArray.LAYER_BANNER, bBmp)
            bannerReady = true
        }
        val aBmp = pendingAtlasBmp
        if (aBmp != null) {
            pendingAtlasBmp = null
            app.texArray.uploadLayer(atlasLayer, aBmp)
            atlasReady = true
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        val scrollY = app.scrollY
        val data = pageData
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        val pulse = (0.08f + 0.04f * kotlin.math.sin(elapsed * 3f).toFloat())

        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        val ht = -scrollY
        if (bannerReady) {
            rc.banner(0f, ht, rc.w, heroH, bannerW, bannerH)
        } else {
            rc.solid(0f, ht, rc.w, heroH, pulse, pulse, pulse + 0.02f)
        }

        val bg = floatArrayOf(0.039f, 0.039f, 0.102f)
        val clear = floatArrayOf(bg[0], bg[1], bg[2], 0f)
        rc.gradient(0f, ht + heroH - rc.dp(120f), rc.w, rc.dp(120f), clear, clear,
            floatArrayOf(bg[0], bg[1], bg[2], 1f), floatArrayOf(bg[0], bg[1], bg[2], 1f))
        rc.gradient(0f, ht, rc.w * 0.4f, heroH,
            floatArrayOf(bg[0], bg[1], bg[2], 0.87f), clear, clear, floatArrayOf(bg[0], bg[1], bg[2], 0.87f))

        // Title
        val title = if (data != null) {
            val lang = Lang.current
            when (lang) {
                "ja" -> data.titleJa.ifEmpty { data.titleEn }
                else -> data.titleEn
            }
        } else item.title()
        rc.text("←", pad, ht + titleY, rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, ht + titleY - rc.dp(20f), rc.dp(60f), rc.dp(60f)) { app.goBack() }
        rc.textClipped(title, pad + rc.dp(34f), ht + titleY, titleSize, contentMaxW, 1f, 1f, 1f)

        // Play button
        rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
        rc.text(Lang.s("play"), pad + rc.dp(20f), ht + btnY + rc.dp(30f), btnTextSize, 1f, 1f, 1f)
        rc.tappable(pad, ht + btnY, btnW, btnH) {
            val first = fullEpisodes?.firstOrNull()
            if (first != null) {
                val baseUrl = app.api?.let { "https://canneji.duckdns.org/janus" } ?: ""
                app.transition(Screen.PLAYER, PlayerState(item, first, baseUrl))
            }
        }

        // Metadata
        if (data != null) {
            rc.text(Lang.s("episodes", data.episodeCount), pad, ht + metaY, metaSize, 0.533f, 0.533f, 0.533f)
        }

        // Synopsis from locales
        val synText = if (data != null) {
            when (Lang.current) {
                "ja" -> data.synopsisJa.ifEmpty { data.synopsisEn }
                "fr" -> data.synopsisFr.ifEmpty { data.synopsisEn }
                else -> data.synopsisEn
            }
        } else ""
        if (synText.isNotEmpty()) {
            rc.textWrapped(synText, pad, ht + synopsisY, synopsisSize, contentMaxW, 2, 0.733f, 0.733f, 0.733f)
        }

        // Episode grid
        val episodes = data?.episodes ?: emptyList()
        val cardCount = if (episodes.isNotEmpty()) episodes.size else 12

        val texSize = app.texArray.size.toFloat()

        for (i in 0 until cardCount) {
            val col = i % gridCols
            val row = i / gridCols
            val x = pad + col * (cardW + gridSpacing)
            val y = ht + gridY + row * (cardH + gridSpacing)
            if (y + cardH < 0 || y > rc.h) continue

            rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)

            if (i < episodes.size) {
                val ep = episodes[i]
                rc.solid(x, y, cardW, thumbH, 0.133f, 0.133f, 0.200f)

                if (atlasReady && atlasCols > 0) {
                    val ac = i % atlasCols
                    val ar = i / atlasCols
                    val u0 = (ac * thumbWPx) / texSize
                    val v0 = (ar * thumbHPx) / texSize
                    val u1 = ((ac + 1) * thumbWPx) / texSize
                    val v1 = ((ar + 1) * thumbHPx) / texSize
                    rc.batch.addQuad(x, y, cardW, thumbH, u0, v0, u1, v1,
                        layer = atlasLayer.toFloat())
                }

                rc.textClipped("${ep.episode}. ${ep.titleEn}", x + textPad,
                    y + thumbH + rc.dp(22f), cardTitleSize, cardW - textPad * 2, 1f, 1f, 1f)
                rc.text("${ep.durationSec / 60} min", x + textPad,
                    y + thumbH + rc.dp(38f), cardDurSize, 0.533f, 0.533f, 0.533f)

                // Tap to play
                val epNum = ep.episode
                rc.tappable(x, y, cardW, cardH) {
                    val full = fullEpisodes?.firstOrNull { it.episode == epNum }
                    if (full != null) {
                        val baseUrl = app.api?.let { "https://canneji.duckdns.org/janus" } ?: ""
                        app.transition(Screen.PLAYER, PlayerState(item, full, baseUrl))
                    }
                }
            } else {
                rc.solid(x, y, cardW, thumbH, pulse, pulse, pulse + 0.02f)
                rc.solid(x, y + thumbH, cardW, cardH - thumbH, pulse * 0.7f, pulse * 0.7f, pulse * 0.7f)
            }
        }

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {
        alive = false
        app.scrollY = 0f
        bannerReady = false
        atlasReady = false
    }
}
