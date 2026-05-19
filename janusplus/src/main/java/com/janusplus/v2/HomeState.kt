package com.janusplus.v2

import com.janusplus.GlyphAtlas
import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.ScrollPhysics

class HomeState : GameState {

    private var seriesList = emptyList<JanusApi.LibraryItem>()
    private var movieList = emptyList<JanusApi.LibraryItem>()
    private var focusRow = 1
    private var seriesFocus = 0
    private var movieFocus = 0
    private val seriesScroll = ScrollPhysics()
    private val movieScroll = ScrollPhysics()
    private var loading = true

    private var cachedCardW = 0f; private var cachedSpacing = 0f; private var cachedPad = 0f
    private var cachedScreenW = 0f; private var cachedScreenH = 0f
    private var cachedMovieBottomY = 0f
    private var cachedSeriesCardsY = 0f; private var cachedMovieCardsY = 0f
    private var cachedCardH = 0f
    private var hScrollAtDown = 0f

    // Glyph atlases per text size (pixel size → atlas)
    private val sizedAtlases = HashMap<Int, GlyphAtlas>()
    private var atlasesUploaded = false
    private var libraryAtlasesBuilt = false
    private var density = 1f

    override fun init(app: App) {
        density = app.density
        if (app.library.isNotEmpty()) {
            setLibrary(app.library)
            loading = false
        }
        buildStaticAtlases(app)
        if (!loading) buildLibraryAtlases(app)

        app.onHorizontalScroll = { dx ->
            val scroll = activeRowScroll(app)
            if (scroll != null) {
                scroll.snapTo(hScrollAtDown + dx)
            }
        }
        app.onHorizontalFling = { vx ->
            activeRowScroll(app)?.fling(vx)
            activeScroll = null
        }
    }

    @Volatile private var activeScroll: ScrollPhysics? = null

    private fun activeRowScroll(app: App): ScrollPhysics? {
        val cached = activeScroll
        if (cached != null) return cached
        val touchY = app.touchDownY
        if (cachedCardH <= 0) return null
        val seriesTop = cachedSeriesCardsY
        val seriesBot = seriesTop + cachedCardH
        val movieTop = cachedMovieCardsY
        val movieBot = movieTop + cachedCardH
        val result = when {
            touchY in seriesTop..seriesBot -> seriesScroll
            touchY in movieTop..movieBot -> movieScroll
            else -> null
        }
        if (result != null) {
            hScrollAtDown = result.offset
            activeScroll = result
        }
        return result
    }

    private fun sp(v: Int): Int = (v * density).toInt()

    private fun buildStaticAtlases(app: App) {
        val tf = app.defaultTypeface
        val d = app.density
        val allText = mutableListOf<String>()
        allText.add("Janus+")
        allText.add(Lang.s("series"))
        allText.add(Lang.s("movies"))
        allText.add(Lang.s("settings"))
        allText.add(Lang.s("loading_library"))
        allText.add("0123456789fps")
        allText.add("←")

        val ts = app.texArray.size
        val sizes = listOf(28, 16, 14, 12, 10, 18)
        for (spVal in sizes) {
            val pxSize = sp(spVal)
            val atlas = GlyphAtlas(tf, spVal * d)
            app.uploadGlyphAtlas(atlas, atlas.build(allText, ts))
            sizedAtlases[pxSize] = atlas
        }
        atlasesUploaded = true
    }

    private fun buildLibraryAtlases(app: App) {
        val tf = app.defaultTypeface
        val d = app.density
        val titles = mutableListOf<String>()
        for (item in seriesList + movieList) titles.add(item.title())

        val pxSize = sp(14)
        val atlas = GlyphAtlas(tf, 14f * d)
        app.uploadGlyphAtlas(atlas, atlas.build(titles, app.texArray.size))
        sizedAtlases[pxSize] = atlas
        libraryAtlasesBuilt = true
    }

    private fun setLibrary(lib: List<JanusApi.LibraryItem>) {
        seriesList = lib.filter { it.type.equals("TV_SERIES", ignoreCase = true) || it.type.equals("series", ignoreCase = true) }
        movieList = lib.filter { it.type.equals("MOVIE", ignoreCase = true) }
    }

    override fun update(app: App, touches: List<Touch>, actions: List<Action>) {
        if (app.library.isNotEmpty() && loading) {
            setLibrary(app.library)
            loading = false
            val t0 = System.currentTimeMillis()
            buildLibraryAtlases(app)
            android.util.Log.d("Startup", "buildLibraryAtlases ${System.currentTimeMillis() - t0}ms")
        }
        seriesScroll.update(0.016f)
        movieScroll.update(0.016f)

        for (a in actions) when (a) {
            Action.UP -> {
                if (focusRow == 2) { focusRow = 1; app.smoothScrollTo(0f) }
                else if (focusRow == 1) { focusRow = 0; app.smoothScrollTo(0f) }
            }
            Action.DOWN -> {
                if (focusRow == 0) { focusRow = 1; app.smoothScrollTo(0f) }
                else if (focusRow == 1 && movieList.isNotEmpty()) { focusRow = 2; scrollMovieRowIntoView(app) }
            }
            Action.LEFT -> when (focusRow) {
                1 -> if (seriesFocus > 0) { seriesFocus--; scrollIntoView(true) }
                2 -> if (movieFocus > 0) { movieFocus--; scrollIntoView(false) }
            }
            Action.RIGHT -> when (focusRow) {
                1 -> if (seriesFocus < seriesList.size - 1) { seriesFocus++; scrollIntoView(true) }
                2 -> if (movieFocus < movieList.size - 1) { movieFocus++; scrollIntoView(false) }
            }
            Action.SELECT -> when (focusRow) {
                0 -> app.navigate(App.Nav.Settings)
                1 -> seriesList.getOrNull(seriesFocus)?.let { app.navigate(App.Nav.Series(it)) }
                2 -> movieList.getOrNull(movieFocus)?.let { app.navigate(App.Nav.Series(it)) }
            }
            else -> {}
        }
    }

    private fun scrollIntoView(isSeries: Boolean) {
        if (cachedCardW <= 0) return
        val focus = if (isSeries) seriesFocus else movieFocus
        val scroll = if (isSeries) seriesScroll else movieScroll
        val cardStride = cachedCardW + cachedSpacing
        val focusedLeft = cachedPad + focus * cardStride
        val focusedRight = focusedLeft + cachedCardW
        val visLeft = scroll.offset + cachedPad
        val visRight = scroll.offset + cachedScreenW - cachedPad
        if (focusedRight > visRight) {
            scroll.snapTo(focusedRight - cachedScreenW + cachedPad * 2)
        } else if (focusedLeft < visLeft) {
            scroll.snapTo((focusedLeft - cachedPad).coerceAtLeast(0f))
        }
    }

    private fun scrollMovieRowIntoView(app: App) {
        if (cachedMovieBottomY <= 0 || cachedScreenH <= 0) return
        val overflow = cachedMovieBottomY - cachedScreenH
        if (overflow > 0) app.smoothScrollTo(overflow + cachedPad)
    }

    override fun draw(app: App, rc: RC) {
        // Register atlases with RC
        for ((size, atlas) in sizedAtlases) rc.atlases[size] = atlas

        val pad = rc.dp(32f)
        val cardW = rc.dp(200f); val cardH = rc.dp(280f); val spacing = rc.dp(16f)
        cachedCardW = cardW; cachedCardH = cardH; cachedSpacing = spacing; cachedPad = pad
        cachedScreenW = rc.w; cachedScreenH = rc.h

        val seriesContentW = pad + seriesList.size * (cardW + spacing) - spacing + pad
        seriesScroll.max = (seriesContentW - rc.w).coerceAtLeast(0f)
        val movieContentW = pad + movieList.size * (cardW + spacing) - spacing + pad
        movieScroll.max = (movieContentW - rc.w).coerceAtLeast(0f)

        val ht = -app.scrollY

        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        rc.text("Janus+", pad, ht + pad + rc.dp(28f), rc.sp(28), 0.733f, 0.525f, 0.988f)

        val setBtnW = rc.dp(80f); val setBtnH = rc.dp(40f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = ht + pad
        val settFocused = focusRow == 0
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = rc.measureText(setLabel, rc.sp(12))
        rc.text(setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(26f), rc.sp(12), 0.733f, 0.525f, 0.988f)
        if (settFocused) rc.border(setBtnX, setBtnY, setBtnW, setBtnH, 6f, 0.733f, 0.525f, 0.988f)
        rc.tappable(setBtnX, setBtnY, setBtnW, setBtnH) { app.navigate(App.Nav.Settings) }

        var sectionY = ht + pad + rc.dp(56f)

        if (seriesList.isNotEmpty()) {
            val rowFocused = focusRow == 1
            rc.text(Lang.s("series"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (rowFocused) 0.733f else 0.8f, if (rowFocused) 0.525f else 0.8f, if (rowFocused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)
            cachedSeriesCardsY = cardsY

            for ((i, item) in seriesList.withIndex()) {
                val baseX = pad + i * (cardW + spacing) - seriesScroll.offset
                if (baseX + cardW < 0 || baseX > rc.w) continue

                val isFocused = rowFocused && i == seriesFocus

                if (!rc.cover("cover_${item.id}", baseX, cardsY, cardW, cardH))
                    rc.solid(baseX, cardsY, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.textClipped(item.title(), baseX + rc.dp(8f), cardsY + cardH - rc.dp(10f), rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)
                if (isFocused) rc.border(baseX, cardsY, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)

                val tappedItem = item
                rc.tappable(baseX, cardsY, cardW, cardH) { app.navigate(App.Nav.Series(tappedItem)) }
            }
            sectionY = cardsY + cardH + rc.dp(24f)
        }

        if (movieList.isNotEmpty()) {
            val rowFocused = focusRow == 2
            rc.text(Lang.s("movies"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (rowFocused) 0.733f else 0.8f, if (rowFocused) 0.525f else 0.8f, if (rowFocused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)
            cachedMovieCardsY = cardsY

            for ((i, item) in movieList.withIndex()) {
                val baseX = pad + i * (cardW + spacing) - movieScroll.offset
                if (baseX + cardW < 0 || baseX > rc.w) continue

                val isFocused = rowFocused && i == movieFocus

                if (!rc.cover("cover_${item.id}", baseX, cardsY, cardW, cardH))
                    rc.solid(baseX, cardsY, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.textClipped(item.title(), baseX + rc.dp(8f), cardsY + cardH - rc.dp(10f), rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)
                if (isFocused) rc.border(baseX, cardsY, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)

                val tappedItem = item
                rc.tappable(baseX, cardsY, cardW, cardH) { app.navigate(App.Nav.Series(tappedItem)) }
            }
            cachedMovieBottomY = cardsY + cardH + app.scrollY
        }

        if (loading) {
            val t = Lang.s("loading_library")
            val tw = rc.measureText(t, rc.sp(18))
            rc.text(t, (rc.w - tw) / 2f, rc.h / 2f, rc.sp(18), 0.8f, 0.8f, 0.8f)
        }

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {
        app.scrollY = 0f
        app.onHorizontalScroll = null
        app.onHorizontalFling = null
    }
}
