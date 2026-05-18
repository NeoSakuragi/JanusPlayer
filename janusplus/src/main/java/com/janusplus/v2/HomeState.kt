package com.janusplus.v2

import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.ScrollPhysics

class HomeState : GameState {

    private var seriesList = emptyList<JanusApi.LibraryItem>()
    private var movieList = emptyList<JanusApi.LibraryItem>()
    private var focusRow = 1 // 0=settings, 1=series, 2=movies
    private var seriesFocus = 0
    private var movieFocus = 0
    private val seriesScroll = ScrollPhysics()
    private val movieScroll = ScrollPhysics()
    private var loading = true

    // Cached layout values for scroll-into-view
    private var cachedCardW = 0f; private var cachedSpacing = 0f; private var cachedPad = 0f
    private var cachedScreenW = 0f

    override fun init(app: App) {
        if (app.library.isNotEmpty()) {
            setLibrary(app.library)
            loading = false
        }
    }

    private fun setLibrary(lib: List<JanusApi.LibraryItem>) {
        seriesList = lib.filter { it.type.equals("TV_SERIES", ignoreCase = true) || it.type.equals("series", ignoreCase = true) }
        movieList = lib.filter { it.type.equals("MOVIE", ignoreCase = true) }
    }

    override fun update(app: App, touches: List<Touch>, keys: List<Int>) {
        if (app.library.isNotEmpty() && loading) {
            setLibrary(app.library)
            loading = false
        }
        seriesScroll.update(0.016f)
        movieScroll.update(0.016f)

        for (key in keys) {
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focusRow == 2) focusRow = 1
                    else if (focusRow == 1) focusRow = 0
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focusRow == 0) focusRow = 1
                    else if (focusRow == 1 && movieList.isNotEmpty()) focusRow = 2
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    when (focusRow) {
                        1 -> if (seriesFocus > 0) { seriesFocus--; scrollIntoView(true) }
                        2 -> if (movieFocus > 0) { movieFocus--; scrollIntoView(false) }
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when (focusRow) {
                        1 -> if (seriesFocus < seriesList.size - 1) { seriesFocus++; scrollIntoView(true) }
                        2 -> if (movieFocus < movieList.size - 1) { movieFocus++; scrollIntoView(false) }
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    when (focusRow) {
                        0 -> app.transition(Screen.SETTINGS, SettingsState())
                        1 -> seriesList.getOrNull(seriesFocus)?.let { app.transition(Screen.SERIES, SeriesState(it)) }
                        2 -> movieList.getOrNull(movieFocus)?.let { app.transition(Screen.SERIES, SeriesState(it)) }
                    }
                }
                android.view.KeyEvent.KEYCODE_BACK -> { /* home screen, no-op */ }
            }
        }
    }

    private fun scrollIntoView(isSeries: Boolean) {
        if (cachedCardW <= 0) return
        val focus = if (isSeries) seriesFocus else movieFocus
        val scroll = if (isSeries) seriesScroll else movieScroll
        val cardStride = cachedCardW + cachedSpacing
        val focusedX = cachedPad + focus * cardStride
        val viewRight = cachedScreenW - cachedPad
        if (focusedX + cachedCardW - scroll.offset > viewRight) {
            scroll.snapTo(focusedX + cachedCardW - viewRight + cachedPad)
        }
        if (focusedX - scroll.offset < cachedPad) {
            scroll.snapTo((focusedX - cachedPad).coerceAtLeast(0f))
        }
    }

    override fun draw(app: App, rc: RC) {
        val pad = rc.dp(32f)
        val cardW = rc.dp(200f); val cardH = rc.dp(280f); val spacing = rc.dp(16f)
        cachedCardW = cardW; cachedSpacing = spacing; cachedPad = pad; cachedScreenW = rc.w

        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Header
        rc.text("Janus+", pad, pad + rc.dp(28f), rc.sp(28), 0.733f, 0.525f, 0.988f)

        // Settings button — top right
        val setBtnW = rc.dp(80f); val setBtnH = rc.dp(40f)
        val setBtnX = rc.w - pad - setBtnW; val setBtnY = pad
        val settFocused = focusRow == 0
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = rc.font.measureText(setLabel, rc.sp(12))
        rc.text(setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(26f), rc.sp(12), 0.733f, 0.525f, 0.988f)
        if (settFocused) rc.border(setBtnX, setBtnY, setBtnW, setBtnH, 6f, 0.733f, 0.525f, 0.988f)
        rc.tappable(setBtnX, setBtnY, setBtnW, setBtnH) { app.transition(Screen.SETTINGS, SettingsState()) }

        var sectionY = pad + rc.dp(56f)

        // Series row
        if (seriesList.isNotEmpty()) {
            val rowFocused = focusRow == 1
            rc.text(Lang.s("series"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (rowFocused) 0.733f else 0.8f, if (rowFocused) 0.525f else 0.8f, if (rowFocused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)

            for ((i, item) in seriesList.withIndex()) {
                val baseX = pad + i * (cardW + spacing) - seriesScroll.offset
                if (baseX + cardW < 0 || baseX > rc.w) continue

                val isFocused = rowFocused && i == seriesFocus
                val x = baseX; val y = cardsY

                rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.cover("cover_${item.id}", x, y, cardW, cardH)
                val gradH = cardH * 0.35f
                val clear = floatArrayOf(0f, 0f, 0f, 0f); val dark = floatArrayOf(0f, 0f, 0f, 0.8f)
                rc.gradient(x, y + cardH - gradH, cardW, gradH, clear, clear, dark, dark)
                rc.textClipped(item.title(), x + rc.dp(8f), y + cardH - rc.dp(10f), rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)
                if (isFocused) rc.border(x, y, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)

                val tappedItem = item
                rc.tappable(baseX, cardsY, cardW, cardH) { app.transition(Screen.SERIES, SeriesState(tappedItem)) }
            }
            sectionY = cardsY + cardH + rc.dp(24f)
        }

        // Movies row
        if (movieList.isNotEmpty()) {
            val rowFocused = focusRow == 2
            rc.text(Lang.s("movies"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (rowFocused) 0.733f else 0.8f, if (rowFocused) 0.525f else 0.8f, if (rowFocused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)

            for ((i, item) in movieList.withIndex()) {
                val baseX = pad + i * (cardW + spacing) - movieScroll.offset
                if (baseX + cardW < 0 || baseX > rc.w) continue

                val isFocused = rowFocused && i == movieFocus
                val x = baseX; val y = cardsY

                rc.solid(x, y, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.cover("cover_${item.id}", x, y, cardW, cardH)
                val gradH = cardH * 0.35f
                val clear = floatArrayOf(0f, 0f, 0f, 0f); val dark = floatArrayOf(0f, 0f, 0f, 0.8f)
                rc.gradient(x, y + cardH - gradH, cardW, gradH, clear, clear, dark, dark)
                rc.textClipped(item.title(), x + rc.dp(8f), y + cardH - rc.dp(10f), rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)
                if (isFocused) rc.border(x, y, cardW, cardH, 6f, 0.733f, 0.525f, 0.988f)

                val tappedItem = item
                rc.tappable(baseX, cardsY, cardW, cardH) { app.transition(Screen.MOVIE, SeriesState(tappedItem)) }
            }
        }

        if (loading) {
            val t = Lang.s("loading_library")
            val tw = rc.font.measureText(t, rc.sp(18))
            rc.text(t, (rc.w - tw) / 2f, rc.h / 2f, rc.sp(18), 0.8f, 0.8f, 0.8f)
        }

        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {}
}
