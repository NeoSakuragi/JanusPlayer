package com.janusplus.v2

import com.janusplus.CompressedTextureArray
import com.janusplus.JanusApi
import com.janusplus.Lang
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class SeriesState(private val item: JanusApi.LibraryItem) : GameState {

    // Page blob data — set atomically from background thread
    data class PageData(
        val titleEn: String, val titleJa: String,
        val synopsisEn: String, val synopsisJa: String,
        val episodeCount: Int,
        val episodes: List<EpisodeCard>,
    )
    data class EpisodeCard(val episode: Int, val titleEn: String, val durationSec: Int)

    @Volatile var pageData: PageData? = null
    @Volatile var bannerW = 0
    @Volatile var bannerH = 0
    @Volatile var bannerLayer = -1
    @Volatile var bannerReady = false
    @Volatile var atlasW = 0
    @Volatile var atlasH = 0
    @Volatile var atlasCols = 0
    @Volatile var atlasLayer = -1
    @Volatile var atlasReady = false
    @Volatile var alive = true

    // Pending ETC2 uploads — background thread sets, GL thread consumes
    data class Etc2Upload(val layer: Int, val w: Int, val h: Int, val data: ByteBuffer)
    @Volatile var pendingBanner: Etc2Upload? = null
    @Volatile var pendingAtlas: Etc2Upload? = null

    private var startTime = System.nanoTime()

    // ── Fixed layout ──

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
    private var dp50 = 100f

    private var titleSize = 0
    private var btnTextSize = 0
    private var metaSize = 0
    private var synopsisSize = 0
    private var cardTitleSize = 0
    private var cardDurSize = 0
    private var lineH = 0f
    private var layoutDone = false

    override fun init(app: App) {
        bannerReady = false
        atlasReady = false
        pageData = null
        startTime = System.nanoTime()

        // Grab layers from the ETC2 ring buffer
        bannerLayer = CompressedTextureArray.LAYER_BANNER
        atlasLayer = app.etc2Array.nextThumbLayer()

        val api = app.api ?: return

        thread {
            val blob = api.fetchPageBlob(item.id, 1) ?: return@thread
            if (!alive) return@thread

            // Parse metadata
            val json = JSONObject(blob.metadataJson)
            val eps = json.getJSONArray("episodes")
            val cards = (0 until eps.length()).map { i ->
                val e = eps.getJSONObject(i)
                EpisodeCard(e.getInt("episode"), e.optString("titleEn", ""), e.optInt("durationSec", 0))
            }
            val data = PageData(
                titleEn = json.optString("titleEn", ""),
                titleJa = json.optString("titleJa", ""),
                synopsisEn = json.optString("synopsisEn", ""),
                synopsisJa = json.optString("synopsisJa", ""),
                episodeCount = json.optInt("episodeCount", cards.size),
                episodes = cards,
            )
            if (!alive) return@thread
            pageData = data

            // Queue ETC2 banner upload
            if (blob.bannerEtc2 != null && blob.bannerW > 0) {
                val buf = ByteBuffer.allocateDirect(blob.bannerEtc2.size).order(ByteOrder.nativeOrder())
                buf.put(blob.bannerEtc2)
                buf.position(0)
                bannerW = blob.bannerW
                bannerH = blob.bannerH
                pendingBanner = Etc2Upload(bannerLayer, blob.bannerW, blob.bannerH, buf)
            }

            // Queue ETC2 atlas upload
            if (blob.atlasEtc2 != null && blob.atlasW > 0) {
                val buf = ByteBuffer.allocateDirect(blob.atlasEtc2.size).order(ByteOrder.nativeOrder())
                buf.put(blob.atlasEtc2)
                buf.position(0)
                atlasW = blob.atlasW
                atlasH = blob.atlasH
                atlasCols = blob.atlasCols
                pendingAtlas = Etc2Upload(atlasLayer, blob.atlasW, blob.atlasH, buf)
            }
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

        titleY = heroH - rc.dp(120f)
        btnY = titleY + rc.dp(40f)
        btnW = rc.dp(200f)
        btnH = rc.dp(44f)
        metaY = btnY + btnH + rc.dp(14f)
        synopsisY = metaY + rc.dp(22f)
        synopsisH = lineH * 2 * 1.3f
        gridY = synopsisY + synopsisH

        gridSpacing = rc.dp(12f)
        textPad = rc.dp(8f)
        val availW = rc.w - pad * 2
        gridCols = ((availW + gridSpacing) / (rc.dp(160f) + gridSpacing)).toInt().coerceAtLeast(1)
        cardW = (availW - gridSpacing * (gridCols - 1)) / gridCols
        thumbH = cardW / (400f / 224f)
        cardH = thumbH + rc.dp(50f)

        contentMaxW = (rc.w * 0.6f).coerceAtMost(rc.w - pad * 2)
        dp50 = rc.dp(50f)

        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>) {
        // Process pending ETC2 uploads on GL thread
        val banner = pendingBanner
        if (banner != null) {
            pendingBanner = null
            app.etc2Array.uploadCompressedLayer(banner.layer, banner.w, banner.h, banner.data)
            bannerReady = true
        }
        val atlas = pendingAtlas
        if (atlas != null) {
            pendingAtlas = null
            app.etc2Array.uploadCompressedLayer(atlas.layer, atlas.w, atlas.h, atlas.data)
            atlasReady = true
        }
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)

        val scrollY = app.scrollY
        val data = pageData
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000f
        val pulse = (0.08f + 0.04f * kotlin.math.sin(elapsed * 3f).toFloat())

        // ── Background ──
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // ── Hero ──
        val ht = -scrollY
        if (bannerReady && bannerW > 0) {
            // ETC2 banner — compute center-crop UVs
            val texSize = app.etc2Array.size.toFloat()
            val srcAspect = bannerW.toFloat() / bannerH
            val dstAspect = rc.w / heroH
            val maxU = bannerW / texSize; val maxV = bannerH / texSize
            val cu0: Float; val cv0: Float; val cu1: Float; val cv1: Float
            if (srcAspect < dstAspect) {
                val f = srcAspect / dstAspect; val crop = maxV * (1f - f) / 2f
                cu0 = 0f; cu1 = maxU; cv0 = crop; cv1 = maxV - crop
            } else {
                val f = dstAspect / srcAspect; val crop = maxU * (1f - f) / 2f
                cv0 = 0f; cv1 = maxV; cu0 = crop; cu1 = maxU - crop
            }
            rc.etc2Quad(0f, ht, rc.w, heroH, cu0, cv0, cu1, cv1, bannerLayer)
        } else {
            rc.solid(0f, ht, rc.w, heroH, pulse, pulse, pulse + 0.02f)
        }

        // Gradients
        val bg = floatArrayOf(0.039f, 0.039f, 0.102f)
        val clear = floatArrayOf(bg[0], bg[1], bg[2], 0f)
        rc.gradient(0f, ht + heroH - rc.dp(120f), rc.w, rc.dp(120f), clear, clear,
            floatArrayOf(bg[0], bg[1], bg[2], 1f), floatArrayOf(bg[0], bg[1], bg[2], 1f))
        rc.gradient(0f, ht, rc.w * 0.4f, heroH,
            floatArrayOf(bg[0], bg[1], bg[2], 0.87f), clear, clear, floatArrayOf(bg[0], bg[1], bg[2], 0.87f))

        // ── Title ──
        val title = if (data != null) {
            val lang = Lang.current
            if (lang == "ja" && data.titleJa.isNotEmpty()) data.titleJa else data.titleEn
        } else item.title()
        rc.text("←", pad, ht + titleY, rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, ht + titleY - rc.dp(20f), rc.dp(60f), rc.dp(60f)) {
            app.goBack()
        }
        rc.textClipped(title, pad + rc.dp(34f), ht + titleY, titleSize, contentMaxW, 1f, 1f, 1f)

        // ── Play button ──
        rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
        rc.text(Lang.s("play"), pad + rc.dp(20f), ht + btnY + rc.dp(30f), btnTextSize, 1f, 1f, 1f)

        // ── Metadata ──
        if (data != null) {
            rc.text(Lang.s("episodes", data.episodeCount), pad, ht + metaY, metaSize, 0.533f, 0.533f, 0.533f)
        }

        // ── Synopsis ──
        val synText = if (data != null) {
            val lang = Lang.current
            if (lang == "ja" && data.synopsisJa.isNotEmpty()) data.synopsisJa else data.synopsisEn
        } else ""
        if (synText.isNotEmpty()) {
            rc.textWrapped(synText, pad, ht + synopsisY, synopsisSize, contentMaxW, 2, 0.733f, 0.733f, 0.733f)
        }

        // ── Episode grid ──
        val episodes = data?.episodes ?: emptyList()
        val cardCount = if (episodes.isNotEmpty()) episodes.size else 12

        val texSize = app.etc2Array.size.toFloat()
        val thumbW = 400f  // matches build_etc2.py THUMB_W
        val thumbHPx = 224f  // matches build_etc2.py THUMB_H

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

                // ETC2 atlas thumbnail
                if (atlasReady && atlasCols > 0) {
                    val atlasCol = i % atlasCols
                    val atlasRow = i / atlasCols
                    val u0 = (atlasCol * thumbW) / texSize
                    val v0 = (atlasRow * thumbHPx) / texSize
                    val u1 = ((atlasCol + 1) * thumbW) / texSize
                    val v1 = ((atlasRow + 1) * thumbHPx) / texSize
                    rc.etc2Quad(x, y, cardW, thumbH, u0, v0, u1, v1, atlasLayer)
                }

                val titleStr = "${ep.episode}. ${ep.titleEn}"
                rc.textClipped(titleStr, x + textPad, y + thumbH + rc.dp(22f), cardTitleSize,
                    cardW - textPad * 2, 1f, 1f, 1f)
                rc.text("${ep.durationSec / 60} min", x + textPad,
                    y + thumbH + rc.dp(38f), cardDurSize, 0.533f, 0.533f, 0.533f)
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
