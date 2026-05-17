package com.janusplus.v2

import android.graphics.BitmapFactory
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.TextureArray
import kotlin.concurrent.thread

class SeriesState(private val item: JanusApi.LibraryItem) : GameState {

    @Volatile var heroBlob: JanusApi.HeroBlob? = null
    @Volatile var seasonCards: JanusApi.SeasonCards? = null
    @Volatile var bannerW = 0
    @Volatile var bannerH = 0
    @Volatile var bannerReady = false

    // ── Fixed layout — computed once in init, never changes ──

    private var pad = 0f
    private var heroH = 0f
    private var titleY = 0f
    private var btnY = 0f
    private var btnW = 0f
    private var btnH = 0f
    private var metaY = 0f
    private var synopsisY = 0f
    private var synopsisH = 0f
    private var gridY = 0f
    private var gridCols = 0
    private var cardW = 0f
    private var cardH = 0f
    private var thumbH = 0f
    private var gridSpacing = 0f
    private var textPad = 0f
    private var contentMaxW = 0f

    // Sizes in pixels
    private var titleSize = 0
    private var btnTextSize = 0
    private var metaSize = 0
    private var synopsisSize = 0
    private var cardTitleSize = 0
    private var cardDurSize = 0
    private var lineH = 0f

    private var layoutDone = false

    override fun init(app: App) {
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

    private fun computeLayout(rc: RC) {
        pad = rc.dp(32f)
        heroH = rc.dp(400f)

        titleSize = rc.sp(28)
        btnTextSize = rc.sp(16)
        metaSize = rc.sp(13)
        synopsisSize = rc.sp(13)
        cardTitleSize = rc.sp(13)
        cardDurSize = rc.sp(10)

        lineH = rc.font.textHeight(synopsisSize)

        // Fixed positions from top
        titleY = heroH - rc.dp(120f)
        btnY = titleY + rc.dp(40f)
        btnW = rc.dp(200f)
        btnH = rc.dp(44f)
        metaY = btnY + btnH + rc.dp(14f)
        synopsisY = metaY + rc.dp(22f)
        synopsisH = lineH * 2 * 1.3f
        gridY = synopsisY + synopsisH

        // Grid layout
        gridSpacing = rc.dp(12f)
        textPad = rc.dp(8f)
        val availW = rc.w - pad * 2
        gridCols = ((availW + gridSpacing) / (rc.dp(160f) + gridSpacing)).toInt().coerceAtLeast(1)
        cardW = (availW - gridSpacing * (gridCols - 1)) / gridCols
        thumbH = cardW / 1.33f  // 4:3 default, updated when atlas arrives
        cardH = thumbH + rc.dp(50f)

        contentMaxW = (rc.w * 0.6f).coerceAtMost(rc.w - pad * 2)
        rc_dp50 = rc.dp(50f)

        layoutDone = true
    }

    private var rc_dp50 = 100f

    override fun update(app: App, touches: List<Touch>) {
        val cards = seasonCards?.episodes
        if (cards != null && cards.isNotEmpty()) {
            val uv = app.thumbAtlas.getUV("thumb_${item.id}_${cards.first().episode}")
            if (uv != null && thumbH != cardW / uv.srcAspect) {
                thumbH = cardW / uv.srcAspect
                cardH = thumbH + rc_dp50
            }
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        val scrollY = app.scrollY
        val blob = heroBlob

        // ── Background ──
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // ── Hero (fixed: 0 to heroH) ──
        val ht = -scrollY
        if (bannerReady) {
            rc.banner(0f, ht, rc.w, heroH, bannerW, bannerH)
        } else {
            rc.cover("cover_${item.id}", 0f, ht, rc.w, heroH)
        }

        // Gradients
        val bg = floatArrayOf(0.039f, 0.039f, 0.102f)
        val clear = floatArrayOf(bg[0], bg[1], bg[2], 0f)
        rc.gradient(0f, ht + heroH - rc.dp(120f), rc.w, rc.dp(120f), clear, clear,
            floatArrayOf(bg[0], bg[1], bg[2], 1f), floatArrayOf(bg[0], bg[1], bg[2], 1f))
        rc.gradient(0f, ht, rc.w * 0.4f, heroH,
            floatArrayOf(bg[0], bg[1], bg[2], 0.87f), clear, clear, floatArrayOf(bg[0], bg[1], bg[2], 0.87f))

        // ── Title (fixed: titleY) ──
        val title = blob?.let { b ->
            val lang = Lang.current
            b.locales[lang]?.title?.takeIf { it.isNotEmpty() }
                ?: if (lang == "ja") b.titleJa.ifEmpty { b.titleEn } else b.titleEn
        } ?: item.title()
        rc.text("←", pad, ht + titleY, rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, ht + titleY - rc.dp(20f), rc.dp(60f), rc.dp(60f)) {
            app.goBack()
        }
        rc.textClipped(title, pad + rc.dp(34f), ht + titleY, titleSize, contentMaxW, 1f, 1f, 1f)

        // ── Play button (fixed: btnY) ──
        rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
        rc.text(Lang.s("play"), pad + rc.dp(20f), ht + btnY + rc.dp(30f), btnTextSize, 1f, 1f, 1f)

        // ── Metadata (fixed: metaY) ──
        if (blob != null) {
            rc.text(Lang.s("episodes", blob.episodeCount), pad, ht + metaY, metaSize, 0.533f, 0.533f, 0.533f)
        }

        // ── Synopsis (fixed: synopsisY, fixed height: synopsisH) ──
        val synText = blob?.let { b ->
            val lang = Lang.current
            b.locales[lang]?.synopsis?.takeIf { it.isNotEmpty() }
                ?: when (lang) { "ja" -> b.synopsisJa.ifEmpty { b.synopsisEn }; else -> b.synopsisEn }
        } ?: ""
        if (synText.isNotEmpty()) {
            rc.textWrapped(synText, pad, ht + synopsisY, synopsisSize, contentMaxW, 2, 0.733f, 0.733f, 0.733f)
        }

        // ── Episode grid (fixed: gridY, each card at deterministic position) ──
        val cards = seasonCards?.episodes ?: emptyList()
        val cardCount = if (cards.isNotEmpty()) cards.size else 12 // skeleton count

        for (i in 0 until cardCount) {
            val col = i % gridCols
            val row = i / gridCols
            val x = pad + col * (cardW + gridSpacing)
            val y = ht + gridY + row * (cardH + gridSpacing)

            if (y + cardH < 0 || y > rc.h) continue

            // Card background — always at this position
            rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)

            if (i < cards.size) {
                val card = cards[i]

                // Thumbnail placeholder + actual
                rc.solid(x, y, cardW, thumbH, 0.133f, 0.133f, 0.200f)
                rc.thumb("thumb_${item.id}_${card.episode}", x, y, cardW, thumbH)

                // Title
                val titleStr = "${card.episode}. ${card.title()}"
                rc.textClipped(titleStr, x + textPad, y + thumbH + rc.dp(22f), cardTitleSize,
                    cardW - textPad * 2, 1f, 1f, 1f)

                // Duration
                rc.text("${(card.durationSec / 60).toInt()} min", x + textPad,
                    y + thumbH + rc.dp(38f), cardDurSize, 0.533f, 0.533f, 0.533f)
            } else {
                // Skeleton — same position, just gray
                rc.solid(x, y, cardW, thumbH, 0.133f, 0.133f, 0.200f, 0.5f)
            }
        }

        // FPS
        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {
        app.thumbAtlas.clear()
        app.scrollY = 0f
        bannerReady = false
    }
}
