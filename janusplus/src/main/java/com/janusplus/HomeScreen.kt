package com.janusplus

object HomeScreen {

    fun render(rc: RenderCtx) {
        val state = rc.state
        val d = rc.dimens
        val pad = d.padding

        // Background
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Header: "Janus+" in purple
        val headerY = pad * 0.5f
        rc.text("Janus+", pad, headerY + rc.dp(28f), rc.sp(28), 0.733f, 0.525f, 0.988f)

        var sectionY = headerY + rc.dp(56f)

        // Series row
        val series = state.seriesList
        if (series.isNotEmpty()) {
            sectionY = renderRow(rc, series, Lang.s("series"),
                state.homeRow == HomeRow.SERIES, state.seriesFocus, state.seriesScroll, sectionY)
        }

        // Movies row
        val movies = state.movieList
        if (movies.isNotEmpty()) {
            renderRow(rc, movies, Lang.s("movies"),
                state.homeRow == HomeRow.MOVIES, state.movieFocus, state.movieScroll, sectionY)
        }

        // Loading indicator
        if (state.loading) {
            val loadText = Lang.s("loading_library")
            val textW = rc.font.measureText(loadText, rc.sp(18))
            rc.text(loadText, (rc.w - textW) / 2f, rc.h / 2f, rc.sp(18), 0.8f, 0.8f, 0.8f)
        }
    }

    private fun renderRow(
        rc: RenderCtx, items: List<JanusApi.LibraryItem>, label: String,
        focused: Boolean, focusIdx: Int, scroll: ScrollPhysics, startY: Float
    ): Float {
        val d = rc.dimens
        val pad = d.padding
        val cardW = d.cardW
        val cardH = d.cardH
        val spacing = d.cardSpacing

        // Section label
        val lr = if (focused) 0.733f else 0.8f
        val lg = if (focused) 0.525f else 0.8f
        val lb = if (focused) 0.988f else 0.8f
        rc.text(label, pad, startY + rc.dp(16f), rc.sp(16), lr, lg, lb)
        val cardsY = startY + rc.dp(30f)

        // Update scroll bounds
        scroll.max = ((items.size * (cardW + spacing) - spacing) - (rc.w - pad * 2)).coerceAtLeast(0f)

        // Auto-scroll to keep focused card visible
        if (focused) {
            val focusX = focusIdx * (cardW + spacing)
            val viewStart = scroll.offset
            val viewEnd = scroll.offset + rc.w - pad * 2
            if (focusX < viewStart) scroll.snapTo(focusX)
            else if (focusX + cardW > viewEnd) scroll.snapTo(focusX + cardW - (rc.w - pad * 2))
        }

        val scrollOff = scroll.offset
        for ((i, item) in items.withIndex()) {
            val x = pad + i * (cardW + spacing) - scrollOff
            if (x + cardW < 0 || x > rc.w) continue

            // Card background
            rc.solid(x, cardsY, cardW, cardH, 0.102f, 0.102f, 0.180f)

            // Cover image
            rc.image("cover_${item.id}", x, cardsY, cardW, cardH)

            // Title gradient overlay at bottom
            val gradH = cardH * 0.35f
            val clear = floatArrayOf(0f, 0f, 0f, 0f)
            val dark = floatArrayOf(0f, 0f, 0f, 0.8f)
            rc.gradient(x, cardsY + cardH - gradH, cardW, gradH, clear, clear, dark, dark)

            // Title text
            rc.textClipped(item.title(), x + rc.dp(8f), cardsY + cardH - rc.dp(10f),
                rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)

            // Focus border
            if (focused && i == focusIdx) {
                rc.border(x, cardsY, cardW, cardH, rc.dp(3f), 0.733f, 0.525f, 0.988f)
            }
        }

        return cardsY + cardH + rc.dp(24f)
    }
}
