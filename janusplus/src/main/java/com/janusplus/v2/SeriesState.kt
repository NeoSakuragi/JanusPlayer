package com.janusplus.v2

import android.graphics.BitmapFactory
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.ScrollPhysics
import com.janusplus.TextureArray
import kotlin.concurrent.thread

class SeriesState(private val item: JanusApi.LibraryItem) : GameState {

    // Data — written by background threads, read by draw
    @Volatile var heroBlob: JanusApi.HeroBlob? = null
    @Volatile var seasonCards: JanusApi.SeasonCards? = null
    @Volatile var bannerW = 0
    @Volatile var bannerH = 0
    @Volatile var bannerReady = false

    private val scroll = ScrollPhysics()

    override fun init(app: App) {
        // Screen is already showing. Launch background loads.
        val api = app.api ?: return

        thread {
            val blob = api.fetchHeroBlob(item.id)
            if (blob != null) {
                heroBlob = blob
                blob.bannerBytes?.let { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        bannerW = bmp.width; bannerH = bmp.height
                        app.texArray.uploadLayer(TextureArray.LAYER_BANNER, bmp)
                        bannerReady = true
                    }
                }
            }
        }

        thread {
            val cards = api.fetchSeasonCards(item.id, 1)
            if (cards != null) seasonCards = cards
        }

        thread {
            val thumbs = api.fetchThumbsBlob(item.id, 1)
            val decoded = thumbs.mapNotNull { entry ->
                val bmp = BitmapFactory.decodeByteArray(entry.data, 0, entry.data.size)
                if (bmp != null) "thumb_${item.id}_${entry.episode}" to bmp else null
            }
            if (decoded.isNotEmpty()) app.thumbAtlas.pack(decoded)
        }
    }

    override fun update(app: App, touches: List<Touch>) {
        scroll.update(0.016f)
    }

    override fun draw(app: App, rc: RC) {
        val pad = rc.dp(32f)
        val heroH = rc.dp(400f)
        val scrollY = scroll.offset
        val blob = heroBlob

        // Background
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Hero
        val heroTop = -scrollY
        if (bannerReady) {
            rc.banner(0f, heroTop, rc.w, heroH, bannerW, bannerH)
        } else {
            rc.cover("cover_${item.id}", 0f, heroTop, rc.w, heroH)
        }

        // Gradients
        val bg = floatArrayOf(0.039f, 0.039f, 0.102f)
        val clear = floatArrayOf(bg[0], bg[1], bg[2], 0f)
        rc.gradient(0f, heroTop + heroH - rc.dp(120f), rc.w, rc.dp(120f), clear, clear,
            floatArrayOf(bg[0], bg[1], bg[2], 1f), floatArrayOf(bg[0], bg[1], bg[2], 1f))
        rc.gradient(0f, heroTop, rc.w * 0.4f, heroH,
            floatArrayOf(bg[0], bg[1], bg[2], 0.87f), clear, clear, floatArrayOf(bg[0], bg[1], bg[2], 0.87f))

        // Title
        val title = blob?.let { b ->
            val lang = Lang.current
            b.locales[lang]?.title?.takeIf { it.isNotEmpty() }
                ?: if (lang == "ja") b.titleJa.ifEmpty { b.titleEn } else b.titleEn
        } ?: item.title()

        val titleY = heroTop + heroH - rc.dp(120f)
        rc.text("←", pad, titleY, rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, titleY - rc.dp(20f), rc.dp(60f), rc.dp(60f)) {
            app.transition(Screen.HOME, HomeState())
        }
        rc.textClipped(title, pad + rc.dp(34f), titleY, rc.sp(28), rc.w * 0.6f, 1f, 1f, 1f)

        // Play button
        val btnY = titleY + rc.dp(40f)
        rc.solid(pad, btnY, rc.dp(200f), rc.dp(44f), 0.733f, 0.525f, 0.988f)
        rc.text(Lang.s("play"), pad + rc.dp(20f), btnY + rc.dp(30f), rc.sp(16), 1f, 1f, 1f)

        // Metadata
        if (blob != null) {
            rc.text(Lang.s("episodes", blob.episodeCount), pad, btnY + rc.dp(58f), rc.sp(13), 0.533f, 0.533f, 0.533f)
        }

        // Synopsis
        val synText = blob?.let { b ->
            val lang = Lang.current
            b.locales[lang]?.synopsis?.takeIf { it.isNotEmpty() }
                ?: when (lang) { "ja" -> b.synopsisJa.ifEmpty { b.synopsisEn }; else -> b.synopsisEn }
        } ?: ""
        if (synText.isNotEmpty()) {
            rc.textWrapped(synText, pad, btnY + rc.dp(78f), rc.sp(13), rc.w * 0.6f, 4, 0.733f, 0.733f, 0.733f)
        }

        // Episode grid
        val cards = seasonCards?.episodes ?: emptyList()
        if (cards.isNotEmpty()) {
            val gridY = heroH - scrollY + rc.dp(16f)
            val gridSpacing = rc.dp(12f)
            val availW = rc.w - pad * 2
            val cols = ((availW + gridSpacing) / (rc.dp(160f) + gridSpacing)).toInt().coerceAtLeast(1)
            val cardW = (availW - gridSpacing * (cols - 1)) / cols
            val firstUV = app.thumbAtlas.getUV("thumb_${item.id}_${cards.first().episode}")
            val thumbAspect = firstUV?.srcAspect ?: 1.33f
            val thumbH = cardW / thumbAspect
            val cardH = thumbH + rc.dp(50f)

            for ((i, card) in cards.withIndex()) {
                val col = i % cols; val row = i / cols
                val x = pad + col * (cardW + gridSpacing)
                val y = gridY + row * (cardH + gridSpacing)
                if (y + cardH < 0 || y > rc.h) continue

                rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.solid(x, y, cardW, thumbH, 0.133f, 0.133f, 0.200f)
                rc.thumb("thumb_${item.id}_${card.episode}", x, y, cardW, thumbH)

                val titleStr = "${card.episode}. ${card.title()}"
                rc.textClipped(titleStr, x + rc.dp(8f), y + thumbH + rc.dp(22f), rc.sp(13), cardW - rc.dp(16f), 1f, 1f, 1f)
                rc.text("${(card.durationSec / 60).toInt()} min", x + rc.dp(8f), y + thumbH + rc.dp(38f), rc.sp(10), 0.533f, 0.533f, 0.533f)
            }
        } else if (cards.isEmpty()) {
            // Skeleton
            val gridY = heroH - scrollY + rc.dp(16f)
            val skW = (rc.w - pad * 2 - rc.dp(60f)) / 6
            for (i in 0 until 6) {
                rc.solid(pad + i * (skW + rc.dp(12f)), gridY, skW, skW / 1.33f + rc.dp(50f), 0.102f, 0.102f, 0.180f, 0.5f)
            }
        }

        // FPS
        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {
        app.thumbAtlas.clear()
        bannerReady = false
    }
}
