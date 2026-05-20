package com.janusplus.v2

import com.janusplus.*
import com.janusplus.CoverCache

class SeriesDisplayState(private val page: SeriesDisplayPage) : GameState {

    private var thumbLayer = -1
    private var hasCoverBg = false
    private var coverU1 = 1f; private var coverV1 = 1f

    // Mutable episode data (updated on season switch)
    private var currentEpisodes = page.episodes.toMutableList()
    private var currentFullEpisodes = page.fullEpisodes.toMutableList()
    private var currentEpisodeCount = page.episodeCount
    private var currentThumbW = page.thumbW
    private var currentThumbH = page.thumbH
    private var currentAtlasW = page.atlasW
    private var currentAtlasH = page.atlasH
    private var currentAtlasCols = page.atlasCols
    @Volatile private var thumbReady = true

    private var pad = 0f; private var heroH = 0f
    private var titleY = 0f; private var btnY = 0f; private var btnW = 0f; private var btnH = 0f
    private var metaY = 0f; private var synopsisY = 0f
    private var gridY = 0f; private var gridCols = 4
    private var cardW = 0f; private var cardH = 0f; private var thumbCardH = 0f
    private var gridSpacing = 0f
    private var layoutDone = false
    private var screenW = 0f; private var screenH = 0f

    enum class FocusArea { PLAY_BUTTON, SEASON_TAB, EPISODE_GRID }
    private var focusArea = FocusArea.PLAY_BUTTON
    private var episodeFocus = 0
    private var selectedSeason = 1
    @Volatile private var loadingSeason = false

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
        pad = rc.dp(24f)
        // Compact layout: back+title row → synopsis → play button → grid
        titleY = rc.dp(28f)
        synopsisY = titleY + rc.dp(8f)
        val synH = page.bodyAtlas.lineHeight * 2 * 1.3f
        btnY = synopsisY + synH + rc.dp(4f); btnW = rc.dp(160f); btnH = rc.dp(38f)
        metaY = btnY; // metadata next to button
        heroH = btnY + btnH + rc.dp(8f)
        gridY = if (page.seasonCount > 1) heroH + rc.dp(40f) else heroH
        gridSpacing = rc.dp(10f)
        val availW = rc.w - pad * 2
        gridCols = 4
        cardW = (availW - gridSpacing * (gridCols - 1)) / gridCols
        thumbCardH = if (currentThumbW > 0f && currentThumbH > 0f) cardW / (currentThumbW / currentThumbH) else 0f
        cardH = thumbCardH + rc.dp(50f)
        layoutDone = true
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        val epCount = currentEpisodes.size
        val hasTabs = page.seasonCount > 1
        for (a in actions) when (a) {
            Action.UP -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> {}
                FocusArea.SEASON_TAB -> { focusArea = FocusArea.PLAY_BUTTON; app.smoothScrollTo(0f) }
                FocusArea.EPISODE_GRID -> {
                    if (episodeFocus >= gridCols) { episodeFocus -= gridCols; scrollIntoView(app) }
                    else if (hasTabs) { focusArea = FocusArea.SEASON_TAB }
                    else { focusArea = FocusArea.PLAY_BUTTON; app.smoothScrollTo(0f) }
                }
            }
            Action.DOWN -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> {
                    if (hasTabs) { focusArea = FocusArea.SEASON_TAB }
                    else if (epCount > 0) { focusArea = FocusArea.EPISODE_GRID; episodeFocus = 0; scrollIntoView(app) }
                }
                FocusArea.SEASON_TAB -> { if (epCount > 0) { focusArea = FocusArea.EPISODE_GRID; episodeFocus = 0; scrollIntoView(app) } }
                FocusArea.EPISODE_GRID -> {
                    if (episodeFocus + gridCols < epCount) { episodeFocus += gridCols; scrollIntoView(app) }
                    else if (episodeFocus < epCount - 1) { episodeFocus = epCount - 1; scrollIntoView(app) }
                }
            }
            Action.LEFT -> when (focusArea) {
                FocusArea.SEASON_TAB -> { if (selectedSeason > 1) switchSeason(app, selectedSeason - 1) }
                FocusArea.EPISODE_GRID -> { if (episodeFocus > 0) { episodeFocus--; scrollIntoView(app) } }
                else -> {}
            }
            Action.RIGHT -> when (focusArea) {
                FocusArea.SEASON_TAB -> { if (selectedSeason < page.seasonCount) switchSeason(app, selectedSeason + 1) }
                FocusArea.EPISODE_GRID -> { if (episodeFocus < epCount - 1) { episodeFocus++; scrollIntoView(app) } }
                else -> {}
            }
            Action.SELECT -> when (focusArea) {
                FocusArea.PLAY_BUTTON -> playEpisode(app, page.fullEpisodes.firstOrNull())
                FocusArea.SEASON_TAB -> {} // already switched on LEFT/RIGHT
                FocusArea.EPISODE_GRID -> {
                    val epCard = page.episodes.getOrNull(episodeFocus) ?: return
                    playEpisode(app, page.fullEpisodes.firstOrNull { it.episode == epCard.episode })
                }
            }
            Action.BACK -> app.goBack()
            else -> {}
        }
    }

    private fun switchSeason(app: App, season: Int) {
        if (loadingSeason || season == selectedSeason) return
        selectedSeason = season
        loadingSeason = true
        thumbReady = false // stop rendering minicards
        episodeFocus = 0

        kotlin.concurrent.thread {
            try {
                val api = app.api ?: return@thread

                // Fetch episode data + thumbnails in parallel
                var seasonData: JanusApi.SeasonData? = null
                var header: JanusApi.PageHeader? = null
                var atlasBytes: ByteArray? = null
                val t1 = Thread { seasonData = api.fetchSeason(page.item.id, season) }.also { it.start() }
                val t2 = Thread { header = api.fetchPageHeader(page.item.id, season) }.also { it.start() }
                val t3 = Thread { atlasBytes = api.fetchPageAtlas(page.item.id, season) }.also { it.start() }
                t1.join(); t2.join(); t3.join()

                val sd = seasonData ?: return@thread
                currentEpisodes = sd.episodes.map { ep ->
                    SeriesDisplayPage.Episode(ep.episode, ep.title(), ep.durationSec.toInt())
                }.toMutableList()
                currentFullEpisodes = sd.episodes.toMutableList()
                currentEpisodeCount = currentEpisodes.size

                // Upload new thumbnail atlas
                val hdr = header
                val ab = atlasBytes
                if (hdr != null && ab != null && ab.isNotEmpty()) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(ab, 0, ab.size)
                    if (bmp != null) {
                        currentThumbW = hdr.thumbW.toFloat()
                        currentThumbH = hdr.thumbH.toFloat()
                        currentAtlasW = hdr.atlasW
                        currentAtlasH = hdr.atlasH
                        currentAtlasCols = hdr.atlasCols
                        app.texArray.uploadLayerNow(thumbLayer, bmp)
                    }
                } else {
                    currentThumbW = 0f; currentThumbH = 0f
                    currentAtlasW = 0; currentAtlasH = 0; currentAtlasCols = 0
                }

                layoutDone = false
                thumbReady = true // re-enable minicard rendering
            } catch (e: Exception) {
                android.util.Log.e("Series", "Season switch failed: ${e.message}")
                thumbReady = true
            } finally {
                loadingSeason = false
            }
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
            rc.bg()
        }

        val isMovie = page.item.type.equals("MOVIE", ignoreCase = true)

        // Compact header: ← Title on one row
        val headerY = ht + rc.dp(4f)
        drawText(rc, page.bodyAtlas, "←", pad, headerY + rc.dp(16f), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, headerY, rc.dp(50f), rc.dp(30f)) { app.goBack() }
        drawText(rc, page.titleAtlas, page.title, pad + rc.dp(24f), headerY + rc.dp(20f), 1f, 1f, 1f)

        // Synopsis
        if (page.synopsis.isNotEmpty()) {
            drawTextClipped(rc, page.bodyAtlas, page.synopsis, pad, ht + synopsisY, if (isMovie) rc.w - pad * 2 else rc.w * 0.65f)
        }

        // Play button + episode count
        if (ht + heroH > 0) {
            val playFocused = focusArea == FocusArea.PLAY_BUTTON
            rc.solid(pad, ht + btnY, btnW, btnH, 0.733f, 0.525f, 0.988f)
            drawText(rc, page.btnAtlas, Lang.s("play"), pad + rc.dp(16f), ht + btnY + rc.dp(26f), 1f, 1f, 1f)
            if (playFocused) rc.border(pad, ht + btnY, btnW, btnH, 6f, 1f, 1f, 1f)
            rc.tappable(pad, ht + btnY, btnW, btnH) { playEpisode(app, currentFullEpisodes.firstOrNull()) }

            if (!isMovie) {
                drawText(rc, page.bodyAtlas, Lang.s("episodes", currentEpisodeCount), pad + btnW + rc.dp(16f), ht + btnY + rc.dp(22f), 0.533f, 0.533f, 0.533f)
            } else {
                val dur = currentFullEpisodes.firstOrNull()?.durationSec?.toInt() ?: 0
                if (dur > 0) drawText(rc, page.bodyAtlas, "${dur / 60} min", pad + btnW + rc.dp(16f), ht + btnY + rc.dp(22f), 0.533f, 0.533f, 0.533f)
            }
        }

        // Settings button — always visible (fixed position)
        val setBtnW = rc.dp(80f); val setBtnH = rc.dp(36f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = ht + rc.dp(12f)
        if (setBtnY + setBtnH > 0) {
            rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, rc.panelR, rc.panelG, rc.panelB)
            val setLabel = Lang.s("settings")
            val setLabelW = page.settAtlas.measureText(setLabel)
            drawText(rc, page.settAtlas, setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(24f), 0.733f, 0.525f, 0.988f)
        }

        if (isMovie) {
            // Movie page — just title, synopsis, play button. No episodes/seasons.
            drawText(rc, page.smallAtlas, "${app.fps}fps", rc.dp(8f), rc.dp(16f), 0.4f, 0.8f, 0.4f)
            return
        }

        // Season tabs with names
        if (page.seasonCount > 1) {
            val tabH = rc.dp(32f); val tabGap = rc.dp(8f)
            val tabY = ht + gridY - tabH - rc.dp(8f)
            val tabFocused = focusArea == FocusArea.SEASON_TAB
            var tabX = pad
            for (s in 1..page.seasonCount) {
                val seasonInfo = page.seasons.firstOrNull { it.season == s }
                val name = seasonInfo?.name() ?: ""
                val label = if (name.isNotEmpty()) "S${String.format("%02d", s)} · $name"
                            else "S${String.format("%02d", s)}"
                val lw = page.bodyAtlas.measureText(label)
                val tabW = lw + rc.dp(24f)
                val selected = s == selectedSeason
                if (selected) rc.solid(tabX, tabY, tabW, tabH, 0.733f, 0.525f, 0.988f, 0.9f)
                else rc.solid(tabX, tabY, tabW, tabH, 0.15f, 0.15f, 0.25f, 0.8f)
                drawText(rc, page.bodyAtlas, label, tabX + rc.dp(12f), tabY + rc.dp(22f), 1f, 1f, 1f)
                if (tabFocused && s == selectedSeason) rc.border(tabX, tabY, tabW, tabH, rc.dp(3f), 1f, 1f, 1f)
                val season = s
                rc.tappable(tabX, tabY, tabW, tabH) { switchSeason(app, season) }
                tabX += tabW + tabGap
            }
        }

        // Episode grid
        val texSize = app.texArray.size.toFloat()
        for (i in currentEpisodes.indices) {
            val col = i % gridCols; val row = i / gridCols
            val x = pad + col * (cardW + gridSpacing)
            val y = ht + gridY + row * (cardH + gridSpacing)
            if (y + cardH < 0 || y > rc.h) continue


            var hasThumb = false
            if (thumbReady && currentAtlasCols > 0 && currentAtlasW > 0 && currentAtlasH > 0) {
                val ac = i % currentAtlasCols; val ar = i / currentAtlasCols
                val aw = currentAtlasW.toFloat(); val ah = currentAtlasH.toFloat()
                val scale = minOf(texSize / aw, texSize / ah, 1f)
                val tw = currentThumbW * scale; val th = currentThumbH * scale
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
            if (!hasThumb) rc.solid(x, y, cardW, cardH, rc.panelR, rc.panelG, rc.panelB)

            val ep = currentEpisodes[i]
            drawTextClipped(rc, page.bodyAtlas, "${ep.episode}. ${ep.titleEn}", x + rc.dp(8f), y + thumbCardH + rc.dp(22f), cardW - rc.dp(16f))
            drawText(rc, page.smallAtlas, "${ep.durationSec / 60} min", x + rc.dp(8f), y + thumbCardH + rc.dp(38f), 0.533f, 0.533f, 0.533f)

            if (focusArea == FocusArea.EPISODE_GRID && i == episodeFocus) {
                rc.border(x, y, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)
            }

            val epCard = ep
            rc.tappable(x, y, cardW, cardH) {
                playEpisode(app, currentFullEpisodes.firstOrNull { it.episode == epCard.episode })
            }
        }

        drawText(rc, page.smallAtlas, "${app.fps}fps", rc.dp(8f), rc.dp(16f), 0.4f, 0.8f, 0.4f)
        // Debug overlay handled by App.onDrawFrame
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
