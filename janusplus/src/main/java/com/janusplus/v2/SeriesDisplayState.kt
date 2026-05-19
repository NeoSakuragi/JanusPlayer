package com.janusplus.v2

import com.janusplus.*

class SeriesDisplayState(private val page: SeriesDisplayPage) : GameState {

    private var thumbLayer = -1

    private var pad = 0f; private var heroH = 0f
    private var titleY = 0f; private var btnY = 0f; private var btnW = 0f; private var btnH = 0f
    private var metaY = 0f; private var synopsisY = 0f
    private var gridY = 0f; private var gridCols = 4
    private var cardW = 0f; private var cardH = 0f; private var thumbCardH = 0f
    private var gridSpacing = 0f
    private var layoutDone = false
    private var screenW = 0f; private var screenH = 0f

    enum class FocusArea { PLAY_BUTTON, EPISODE_GRID }
    private var focusArea = FocusArea.PLAY_BUTTON
    private var episodeFocus = 0

    override fun init(app: App) {
        if (thumbLayer < 0) thumbLayer = app.texArray.nextThumbLayer()
        page.bannerBmp?.let { app.texArray.uploadLayerNow(TextureArray.LAYER_BANNER, it) }
        page.thumbBmp?.let { app.texArray.uploadLayerNow(thumbLayer, it) }
        app.uploadGlyphAtlas(page.titleAtlas, page.titleBmp)
        app.uploadGlyphAtlas(page.bodyAtlas, page.bodyBmp)
        app.uploadGlyphAtlas(page.btnAtlas, page.btnBmp)
        app.uploadGlyphAtlas(page.smallAtlas, page.smallBmp)
        app.uploadGlyphAtlas(page.settAtlas, page.settBmp)
    }

    private fun computeLayout(rc: RC) {
        screenW = rc.w; screenH = rc.h
        pad = rc.dp(32f); heroH = rc.dp(400f)
        titleY = heroH - rc.dp(120f)
        btnY = titleY + rc.dp(40f); btnW = rc.dp(200f); btnH = rc.dp(44f)
        metaY = btnY + btnH + rc.dp(14f)
        synopsisY = metaY + rc.dp(22f)
        val synH = page.bodyAtlas.lineHeight * 2 * 1.3f
        gridY = synopsisY + synH
        gridSpacing = rc.dp(12f)
        val availW = rc.w - pad * 2
        gridCols = 4
        cardW = (availW - gridSpacing * (gridCols - 1)) / gridCols
        thumbCardH = if (page.thumbW > 0f && page.thumbH > 0f) cardW / (page.thumbW / page.thumbH) else 0f
        cardH = thumbCardH + rc.dp(50f)
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        val epCount = page.episodes.size
        for (a in actions) when (a) {
            Action.UP -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> {}
                FocusArea.EPISODE_GRID -> {
                    if (episodeFocus >= gridCols) { episodeFocus -= gridCols; scrollIntoView(app) }
                    else { focusArea = FocusArea.PLAY_BUTTON; app.smoothScrollTo(0f) }
                }
            }
            Action.DOWN -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> { if (epCount > 0) { focusArea = FocusArea.EPISODE_GRID; episodeFocus = 0; scrollIntoView(app) } }
                FocusArea.EPISODE_GRID -> {
                    if (episodeFocus + gridCols < epCount) { episodeFocus += gridCols; scrollIntoView(app) }
                    else if (episodeFocus < epCount - 1) { episodeFocus = epCount - 1; scrollIntoView(app) }
                }
            }
            Action.LEFT -> { if (focusArea == FocusArea.EPISODE_GRID && episodeFocus > 0) { episodeFocus--; scrollIntoView(app) } }
            Action.RIGHT -> { if (focusArea == FocusArea.EPISODE_GRID && episodeFocus < epCount - 1) { episodeFocus++; scrollIntoView(app) } }
            Action.SELECT -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> playEpisode(app, page.fullEpisodes.firstOrNull())
                FocusArea.EPISODE_GRID -> {
                    val epCard = page.episodes.getOrNull(episodeFocus) ?: return
                    playEpisode(app, page.fullEpisodes.firstOrNull { it.episode == epCard.episode })
                }
            }
            Action.BACK -> app.goBack()
            else -> {}
        }
    }

    private fun playEpisode(app: App, ep: JanusApi.Episode?) {
        if (ep == null) return
        app.navigate(App.Nav.Player(page.item, ep, "https://canneji.duckdns.org/janus"))
    }

    private fun scrollIntoView(app: App) {
        if (!layoutDone || cardH <= 0) return
        val row = episodeFocus / gridCols
        val cardAbsY = gridY + row * (cardH + gridSpacing)
        val cardBottom = cardAbsY + cardH + 32f
        if (cardBottom > app.scrollY + screenH) app.smoothScrollTo(cardBottom - screenH + 32f)
        if (cardAbsY < app.scrollY + 32f) app.smoothScrollTo((cardAbsY - 32f).coerceAtLeast(0f))
    }

    override fun draw(app: App, rc: RC) {
        if (!layoutDone) computeLayout(rc)
        val scrollY = app.scrollY
        val ht = -scrollY

        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Banner
        if (page.bannerBmp != null) {
            val texSize = app.texArray.size.toFloat()
            val bw = page.bannerW.toFloat(); val bh = page.bannerH.toFloat()
            val bannerAspect = bw / bh; val screenAspect = rc.w / heroH
            val cu0: Float; val cv0: Float; val cu1: Float; val cv1: Float
            val maxU = bw / texSize; val maxV = bh / texSize
            if (bannerAspect < screenAspect) {
                val f = bannerAspect / screenAspect; val crop = maxV * (1f - f) / 2f
                cu0 = 0f; cu1 = maxU; cv0 = crop; cv1 = maxV - crop
            } else {
                val f = screenAspect / bannerAspect; val crop = maxU * (1f - f) / 2f
                cu0 = crop; cu1 = maxU - crop; cv0 = 0f; cv1 = maxV
            }
            rc.batch.addQuad(0f, ht, rc.w, heroH, cu0, cv0, cu1, cv1, layer = TextureArray.LAYER_BANNER.toFloat())
        }

        drawText(rc, page.titleAtlas, page.title, pad, ht + titleY, 1f, 1f, 1f)

        // Back arrow
        drawText(rc, page.titleAtlas, "←", pad, ht + titleY - rc.dp(30f), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, ht + titleY - rc.dp(50f), rc.dp(60f), rc.dp(60f)) { app.goBack() }

        // Play button
        val playFocused = focusArea == FocusArea.PLAY_BUTTON
        rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
        drawText(rc, page.btnAtlas, Lang.s("play"), pad + rc.dp(20f), ht + btnY + rc.dp(30f), 1f, 1f, 1f)
        if (playFocused) rc.border(pad, ht + btnY, btnW, btnH, 6f, 1f, 1f, 1f)
        rc.tappable(pad, ht + btnY, btnW, btnH) { playEpisode(app, page.fullEpisodes.firstOrNull()) }

        drawText(rc, page.bodyAtlas, Lang.s("episodes", page.episodeCount), pad, ht + metaY, 0.533f, 0.533f, 0.533f)

        if (page.synopsis.isNotEmpty()) {
            drawText(rc, page.bodyAtlas, page.synopsis, pad, ht + synopsisY, 0.733f, 0.733f, 0.733f)
        }

        // Settings button
        val setBtnW = rc.dp(80f); val setBtnH = rc.dp(36f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = ht + rc.dp(12f)
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = page.settAtlas.measureText(setLabel)
        drawText(rc, page.settAtlas, setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(24f), 0.733f, 0.525f, 0.988f)

        // Episode grid
        val texSize = app.texArray.size.toFloat()
        for (i in page.episodes.indices) {
            val col = i % gridCols; val row = i / gridCols
            val x = pad + col * (cardW + gridSpacing)
            val y = ht + gridY + row * (cardH + gridSpacing)
            if (y + cardH < 0 || y > rc.h) continue

            rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)

            if (page.thumbBmp != null && page.atlasCols > 0 && page.atlasW > 0 && page.atlasH > 0) {
                val ac = i % page.atlasCols; val ar = i / page.atlasCols
                val scale = minOf(texSize / page.atlasW, texSize / page.atlasH, 1f)
                val sw = page.atlasW * scale; val sh = page.atlasH * scale
                val u0 = (ac * page.thumbW) / page.atlasW * sw / texSize
                val v0 = (ar * page.thumbH) / page.atlasH * sh / texSize
                val u1 = ((ac + 1) * page.thumbW) / page.atlasW * sw / texSize
                val v1 = ((ar + 1) * page.thumbH) / page.atlasH * sh / texSize
                rc.batch.addQuad(x, y, cardW, thumbCardH, u0, v0, u1, v1, layer = thumbLayer.toFloat())
            }

            val ep = page.episodes[i]
            drawTextClipped(rc, page.bodyAtlas, "${ep.episode}. ${ep.titleEn}", x + rc.dp(8f), y + thumbCardH + rc.dp(22f), cardW - rc.dp(16f))
            drawText(rc, page.smallAtlas, "${ep.durationSec / 60} min", x + rc.dp(8f), y + thumbCardH + rc.dp(38f), 0.533f, 0.533f, 0.533f)

            if (focusArea == FocusArea.EPISODE_GRID && i == episodeFocus) {
                rc.border(x, y, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)
            }

            // Touch tap
            val epCard = ep
            rc.tappable(x, y, cardW, cardH) {
                playEpisode(app, page.fullEpisodes.firstOrNull { it.episode == epCard.episode })
            }
        }

        drawText(rc, page.smallAtlas, "${app.fps}fps", rc.dp(8f), rc.dp(16f), 0.4f, 0.8f, 0.4f)
    }

    private val glyphPad = 2f

    private fun drawText(rc: RC, atlas: GlyphAtlas, text: String, x: Float, y: Float,
                         r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        var cx = x
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val glyph = atlas.glyphs[cp]
            if (glyph != null) {
                rc.batch.addQuad(cx - glyphPad, y - glyph.ascent, glyph.w, glyph.h,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    r, g, b, a, layer = glyph.page.toFloat())
                cx += glyph.advance
            }
            i += Character.charCount(cp)
        }
    }

    private fun drawTextClipped(rc: RC, atlas: GlyphAtlas, text: String, x: Float, y: Float, maxW: Float) {
        var cx = x
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val glyph = atlas.glyphs[cp]
            if (glyph != null) {
                if (cx + glyph.advance - x > maxW) break
                rc.batch.addQuad(cx - glyphPad, y - glyph.ascent, glyph.w, glyph.h,
                    glyph.u0, glyph.v0, glyph.u1, glyph.v1,
                    layer = glyph.page.toFloat())
                cx += glyph.advance
            }
            i += Character.charCount(cp)
        }
    }

    override fun cleanup(app: App) {
        app.scrollY = 0f
    }
}
