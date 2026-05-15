package com.janusplus

object DetailScreen {

    fun render(rc: RenderCtx) {
        val state = rc.state
        val d = rc.dimens
        val pad = d.padding
        val heroH = d.heroH
        val blob = state.heroBlob
        val scrollY = state.detailScroll.offset

        // Background
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Hero section
        val heroTop = -scrollY
        if (heroTop + heroH > 0) {
            renderHero(rc, heroTop, heroH, blob, pad)
        }

        // Episode grid (series only)
        val cards = state.seasonCards?.episodes ?: emptyList()
        if (cards.isNotEmpty() && state.screen == Screen.SERIES_DETAIL) {
            renderEpisodeGrid(rc, cards, heroH - scrollY)

            // Thumbnails drawn in post-pass by GLRenderer
        }
    }

    fun renderThumbs(rc: RenderCtx, cards: List<JanusApi.CardEpisode>, startY: Float) {
        val state = rc.state
        val d = rc.dimens
        val pad = d.padding
        val availW = rc.w - pad * 2
        val gridSpacing = d.gridSpacing
        val minCardW = d.gridMinCardW
        val cols = ((availW + gridSpacing) / (minCardW + gridSpacing)).toInt().coerceAtLeast(1)
        val cardW = (availW - gridSpacing * (cols - 1)) / cols
        val firstThumbKey = "thumb_${state.selectedItem?.id}_${cards.firstOrNull()?.episode}"
        val srcAspect = rc.thumbAtlas.getUV(firstThumbKey)?.srcAspect ?: 1.33f
        val thumbH = cardW / srcAspect

        var gridY = startY + rc.dp(16f)
        val seasons = state.heroBlob?.seasons ?: emptyList()
        if (seasons.size > 1) gridY += rc.dp(36f)

        for ((i, card) in cards.withIndex()) {
            val col = i % cols
            val row = i / cols
            val x = pad + col * (cardW + gridSpacing)
            val y = gridY + row * (thumbH + rc.dp(50f) + gridSpacing)
            if (y + thumbH < 0 || y > rc.h) continue
            rc.thumb("thumb_${state.selectedItem?.id}_${card.episode}", x, y, cardW, thumbH)
        }
    }

    private fun renderHero(rc: RenderCtx, heroTop: Float, heroH: Float,
                           blob: JanusApi.HeroBlob?, pad: Float) {
        val state = rc.state
        val d = rc.dimens

        // Banner drawn in pre-pass by GLRenderer. Only draw placeholder if no banner.
        val itemId = state.selectedItem?.id
        val hasBanner = itemId != null && rc.tex.get("banner_$itemId") != 0
        if (!hasBanner) {
            rc.solid(0f, heroTop, rc.w, heroH, 0.102f, 0.102f, 0.180f)
        }

        // Gradient overlays
        val bg = floatArrayOf(0.039f, 0.039f, 0.102f)
        val clear = floatArrayOf(bg[0], bg[1], bg[2], 0f)
        val solid87 = floatArrayOf(bg[0], bg[1], bg[2], 0.87f)
        val solid100 = floatArrayOf(bg[0], bg[1], bg[2], 1f)
        val solid67 = floatArrayOf(bg[0], bg[1], bg[2], 0.67f)

        // Bottom fade
        rc.gradient(0f, heroTop + heroH - rc.dp(120f), rc.w, rc.dp(120f), clear, clear, solid100, solid100)
        // Left fade
        rc.gradient(0f, heroTop, rc.w * 0.4f, heroH, solid87, clear, clear, solid87)
        // Top fade
        rc.gradient(0f, heroTop, rc.w, rc.dp(60f), solid67, solid67, clear, clear)

        // Content overlay
        val contentMaxW = d.heroContentMaxW.coerceAtMost(rc.w - pad * 2)

        // Title
        val title = blob?.let {
            val lang = Lang.current
            it.locales[lang]?.title?.takeIf { t -> t.isNotEmpty() }
                ?: if (lang == "ja") it.titleJa.ifEmpty { it.titleEn } else it.titleEn
        } ?: state.selectedItem?.title() ?: ""

        val titleY = heroTop + heroH - rc.dp(120f)
        rc.text("←", pad, titleY, rc.sp(22), 0.533f, 0.533f, 0.533f)
        rc.tappable(0f, titleY - rc.dp(20f), rc.dp(60f), rc.dp(60f)) { state.closeDetail() }
        rc.textClipped(title, pad + rc.dp(34f), titleY, rc.sp(28),
            contentMaxW - rc.dp(34f), 1f, 1f, 1f)

        // Buttons
        val btnY = titleY + rc.dp(40f)
        val btnH = rc.dp(44f)
        val playW = rc.dp(200f)
        val isFocusHero = state.detailFocus == DetailFocus.HERO

        // Play button
        rc.solid(pad, btnY, playW, btnH, 0.733f, 0.525f, 0.988f)
        rc.text(Lang.s("play"), pad + rc.dp(20f), btnY + btnH * 0.65f, rc.sp(16), 1f, 1f, 1f)
        if (isFocusHero && state.heroButtonFocus == 0) {
            rc.border(pad, btnY, playW, btnH, rc.dp(2f), 1f, 1f, 1f)
        }
        // Tap play button → play first episode or movie
        val playItemId = state.selectedItem?.id ?: ""
        val playSeason = state.selectedSeason
        rc.tappable(pad, btnY, playW, btnH) {
            val ep = if (state.screen == Screen.MOVIE_DETAIL) 1
                     else state.seasonCards?.episodes?.firstOrNull()?.episode ?: 1
            state.playingUrl = "https://canneji.duckdns.org/janus/api/stream/$playItemId/$playSeason/$ep"
            state.returnScreen = state.screen
            state.screen = Screen.PLAYING
        }

        // Download button (movies only)
        if (state.screen == Screen.MOVIE_DETAIL) {
            val dlX = pad + playW + rc.dp(12f)
            val dlW = rc.dp(160f)
            rc.solid(dlX, btnY, dlW, btnH, 0.165f, 0.165f, 0.227f)
            rc.text(Lang.s("download"), dlX + rc.dp(16f), btnY + btnH * 0.65f, rc.sp(14), 0.8f, 0.8f, 0.8f)
            if (isFocusHero && state.heroButtonFocus == 1) {
                rc.border(dlX, btnY, dlW, btnH, rc.dp(2f), 0.733f, 0.525f, 0.988f)
            }
        }

        // Metadata line
        val metaY = btnY + btnH + rc.dp(14f)
        val metaParts = mutableListOf<String>()
        if (blob != null) {
            if (blob.type.equals("MOVIE", ignoreCase = true)) metaParts.add(Lang.s("min", blob.durationMin))
            else metaParts.add(Lang.s("episodes", blob.episodeCount))
        }
        if (metaParts.isNotEmpty()) {
            rc.text(metaParts.joinToString(" · "), pad, metaY, rc.sp(13), 0.533f, 0.533f, 0.533f)
        }

        // Synopsis
        val synText = blob?.let {
            val lang = Lang.current
            it.locales[lang]?.synopsis?.takeIf { s -> s.isNotEmpty() }
                ?: when (lang) {
                    "ja" -> it.synopsisJa.ifEmpty { it.synopsisEn }
                    "fr" -> it.synopsisFr.ifEmpty { it.synopsisEn }
                    else -> it.synopsisEn
                }
        } ?: ""
        if (synText.isNotEmpty()) {
            rc.textWrapped(synText, pad, metaY + rc.dp(22f), rc.sp(13),
                contentMaxW, 4, 0.733f, 0.733f, 0.733f)
        }
    }

    private fun renderEpisodeGrid(rc: RenderCtx, cards: List<JanusApi.CardEpisode>, startY: Float) {
        val state = rc.state
        val d = rc.dimens
        val pad = d.padding

        val availW = rc.w - pad * 2
        val minCardW = d.gridMinCardW
        val gridSpacing = d.gridSpacing
        val cols = ((availW + gridSpacing) / (minCardW + gridSpacing)).toInt().coerceAtLeast(1)
        state.gridColumns = cols
        val cardW = (availW - gridSpacing * (cols - 1)) / cols
        val firstThumbKey = "thumb_${state.selectedItem?.id}_${cards.firstOrNull()?.episode}"
        val srcAspect = rc.thumbAtlas.getUV(firstThumbKey)?.srcAspect ?: 1.33f
        val thumbH = cardW / srcAspect
        val textPad = rc.dp(8f)
        val cardH = thumbH + rc.dp(50f)

        var gridY = startY + rc.dp(16f)

        // Season label
        val seasons = state.heroBlob?.seasons ?: emptyList()
        if (seasons.size > 1) {
            val si = seasons.find { it.season == state.selectedSeason }
            val label = "S${state.selectedSeason.toString().padStart(2, '0')}" +
                (si?.name()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
                " (${si?.episodeCount ?: cards.size})"
            rc.text(label, pad, gridY + rc.dp(14f), rc.sp(15), 1f, 1f, 1f)
            gridY += rc.dp(36f)
        }

        // Auto-scroll to keep focused episode visible
        if (state.detailFocus == DetailFocus.GRID) {
            val focusRow = state.episodeFocus / cols
            val focusY = gridY + state.detailScroll.offset + focusRow * (cardH + gridSpacing)
            if (focusY + cardH > rc.h) {
                state.detailScroll.snapTo(state.detailScroll.offset + (focusY + cardH - rc.h) + rc.dp(32f))
            } else if (focusY < d.heroH * 0.5f) {
                state.detailScroll.snapTo((state.detailScroll.offset - (d.heroH * 0.5f - focusY)).coerceAtLeast(0f))
            }
        }

        for ((i, card) in cards.withIndex()) {
            val col = i % cols
            val row = i / cols
            val x = pad + col * (cardW + gridSpacing)
            val y = gridY + row * (cardH + gridSpacing)

            if (y + cardH < 0 || y > rc.h) continue

            val focused = state.detailFocus == DetailFocus.GRID && i == state.episodeFocus
            val bgR = if (focused) 0.165f else 0.102f
            val bgG = if (focused) 0.165f else 0.102f
            val bgB = if (focused) 0.290f else 0.180f

            // Card background
            rc.solid(x, y, cardW, cardH, bgR, bgG, bgB)

            // Thumbnail — drawn from atlas in the thumb batch pass
            rc.solid(x, y, cardW, thumbH, 0.133f, 0.133f, 0.200f)

            // Title
            val titleStr = "${card.episode}. ${card.title()}"
            rc.textClipped(titleStr, x + textPad, y + thumbH + textPad + rc.dp(14f),
                rc.sp(13), cardW - textPad * 2, 1f, 1f, 1f)

            // Duration
            val durMin = (card.durationSec / 60).toInt()
            rc.text("$durMin min", x + textPad, y + thumbH + textPad + rc.dp(30f),
                rc.sp(10), 0.533f, 0.533f, 0.533f)

            // Tap target — play episode
            val itemId = state.selectedItem?.id ?: ""
            val season = state.selectedSeason
            val epNum = card.episode
            rc.tappable(x, y, cardW, cardH) {
                state.playingUrl = "https://canneji.duckdns.org/janus/api/stream/$itemId/$season/$epNum"
                state.returnScreen = state.screen
                state.screen = Screen.PLAYING
            }

            // Focus border
            if (focused) {
                rc.border(x, y, cardW, cardH, rc.dp(2f), 0.733f, 0.525f, 0.988f)
            }
        }

        // Update scroll max
        val totalRows = (cards.size + cols - 1) / cols
        val gridHeight = totalRows * (cardH + gridSpacing)
        state.detailScroll.max = (gridY + state.detailScroll.offset + gridHeight - rc.h + rc.dp(32f))
            .coerceAtLeast(0f)
    }
}
