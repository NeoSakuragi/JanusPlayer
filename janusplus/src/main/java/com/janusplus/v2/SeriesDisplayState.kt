package com.janusplus.v2

import com.janusplus.*
import com.janusplus.CoverCache

class SeriesDisplayState(private val page: SeriesDisplayPage) : GameState {

    private var thumbLayer = -1
    private var hasCoverBg = false
    private var coverU1 = 1f; private var coverV1 = 1f

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

        // Upload cover (or banner fallback) as stretched background via coverAtlas sampler2D
        val bgBmp = page.coverBmp ?: page.bannerBmp
        if (bgBmp != null) {
            val uv = app.coverAtlas.let { ta ->
                if (ta.ownTextureId != 0) {
                    val bmp = if (bgBmp.config != android.graphics.Bitmap.Config.ARGB_8888)
                        bgBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { bgBmp.recycle() } else bgBmp
                    val texSize = app.texArray.size
                    val w = bmp.width.coerceAtMost(texSize)
                    val h = bmp.height.coerceAtMost(texSize)
                    val src = if (bmp.width > w || bmp.height > h)
                        android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true).also { bmp.recycle() } else bmp
                    val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                    src.copyPixelsToBuffer(buf); buf.position(0)
                    android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, ta.ownTextureId)
                    android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0,
                        0, 0, w, h, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_UNSIGNED_BYTE, buf)
                    src.recycle()
                    CoverCache.SlotUV(0f, 0f, w.toFloat() / texSize, h.toFloat() / texSize)
                } else null
            }
            if (uv != null) {
                coverU1 = uv.u1; coverV1 = uv.v1
                hasCoverBg = true
            }
            if (page.coverBmp != null) page.bannerBmp?.recycle()
        }

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

        // Fixed cover background — stays still during scroll, stretched + darkened
        if (hasCoverBg) {
            val coverAspect = (coverU1 * app.texArray.size) / (coverV1 * app.texArray.size)
            val screenAspect = rc.w / rc.h
            val cu0: Float; val cv0: Float; val cu1f: Float; val cv1f: Float
            if (coverAspect < screenAspect) {
                val f = coverAspect / screenAspect; val crop = coverV1 * (1f - f) / 2f
                cu0 = 0f; cu1f = coverU1; cv0 = crop; cv1f = coverV1 - crop
            } else {
                val f = screenAspect / coverAspect; val crop = coverU1 * (1f - f) / 2f
                cu0 = crop; cu1f = coverU1 - crop; cv0 = 0f; cv1f = coverV1
            }
            rc.batch.addQuad(0f, 0f, rc.w, rc.h, cu0, cv0, cu1f, cv1f,
                0.15f, 0.15f, 0.15f, 1f, layer = -2f)
        } else {
            rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)
        }

        // Hero section — only draw if on screen
        if (ht + heroH > 0) {
            drawText(rc, page.titleAtlas, page.title, pad, ht + titleY, 1f, 1f, 1f)
            drawText(rc, page.titleAtlas, "←", pad, ht + titleY - rc.dp(30f), 0.533f, 0.533f, 0.533f)
            rc.tappable(0f, ht + titleY - rc.dp(50f), rc.dp(60f), rc.dp(60f)) { app.goBack() }

            val playFocused = focusArea == FocusArea.PLAY_BUTTON
            rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
            drawText(rc, page.btnAtlas, Lang.s("play"), pad + rc.dp(20f), ht + btnY + rc.dp(30f), 1f, 1f, 1f)
            if (playFocused) rc.border(pad, ht + btnY, btnW, btnH, 6f, 1f, 1f, 1f)
            rc.tappable(pad, ht + btnY, btnW, btnH) { playEpisode(app, page.fullEpisodes.firstOrNull()) }

            drawText(rc, page.bodyAtlas, Lang.s("episodes", page.episodeCount), pad, ht + metaY, 0.533f, 0.533f, 0.533f)

            if (page.synopsis.isNotEmpty()) {
                drawTextClipped(rc, page.bodyAtlas, page.synopsis, pad, ht + synopsisY, rc.w * 0.55f)
            }
        }

        // Settings button — always visible (fixed position)
        val setBtnW = rc.dp(80f); val setBtnH = rc.dp(36f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = ht + rc.dp(12f)
        if (setBtnY + setBtnH > 0) {
            rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
            val setLabel = Lang.s("settings")
            val setLabelW = page.settAtlas.measureText(setLabel)
            drawText(rc, page.settAtlas, setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(24f), 0.733f, 0.525f, 0.988f)
        }

        // Episode grid
        val texSize = app.texArray.size.toFloat()
        for (i in page.episodes.indices) {
            val col = i % gridCols; val row = i / gridCols
            val x = pad + col * (cardW + gridSpacing)
            val y = ht + gridY + row * (cardH + gridSpacing)
            if (y + cardH < 0 || y > rc.h) continue


            var hasThumb = false
            if (page.thumbBmp != null && page.atlasCols > 0 && page.atlasW > 0 && page.atlasH > 0) {
                val ac = i % page.atlasCols; val ar = i / page.atlasCols
                // UVs relative to atlas dimensions (uploadLayerNow scales bitmap to fit texture)
                val aw = page.atlasW.toFloat(); val ah = page.atlasH.toFloat()
                // The bitmap was scaled by uploadLayerNow — UV maps to the scaled version
                val scale = minOf(texSize / aw, texSize / ah, 1f)
                val scaledW = aw * scale; val scaledH = ah * scale
                val tw = page.thumbW * scale; val th = page.thumbH * scale
                val txOrig = ac * tw; val tyOrig = ar * th
                // Center-crop at screen pixel density
                val showW = cardW.coerceAtMost(tw)
                val showH = thumbCardH.coerceAtMost(th)
                val cropX = txOrig + (tw - showW) / 2f
                val cropY = tyOrig + (th - showH) / 2f
                val u0 = cropX / texSize
                val v0 = cropY / texSize
                val u1 = (cropX + showW) / texSize
                val v1 = (cropY + showH) / texSize
                rc.batch.addQuad(x + (cardW - showW) / 2f, y + (thumbCardH - showH) / 2f,
                    showW, showH, u0, v0, u1, v1, layer = thumbLayer.toFloat())
                hasThumb = true
            }
            if (!hasThumb) rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)

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
        val log = app.lastLoadLog
        if (log.isNotEmpty()) {
            val lh = page.smallAtlas.lineHeight + rc.dp(2f)
            for ((i, line) in log.withIndex()) {
                drawText(rc, page.smallAtlas, line, rc.dp(8f), rc.dp(30f) + i * lh, 0.4f, 0.7f, 0.4f)
            }
        }
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
