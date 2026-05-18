package com.janusplus.v2

import com.janusplus.JanusApi
import com.janusplus.Lang
import com.janusplus.ScrollPhysics
import kotlin.concurrent.thread

class HomeState : GameState {

    private var seriesList = emptyList<JanusApi.LibraryItem>()
    private var movieList = emptyList<JanusApi.LibraryItem>()
    private var focusRow = 0 // 0=series, 1=movies
    private var seriesFocus = 0
    private var movieFocus = 0
    private val seriesScroll = ScrollPhysics()
    private val movieScroll = ScrollPhysics()
    private var loading = true

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
        for (key in keys) {
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focusRow == 0 && seriesFocus > 0) seriesFocus--
                    else if (focusRow == 1 && movieFocus > 0) movieFocus--
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focusRow == 0 && seriesFocus < seriesList.size - 1) seriesFocus++
                    else if (focusRow == 1 && movieFocus < movieList.size - 1) movieFocus++
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focusRow > 0) focusRow--
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focusRow == 0 && movieList.isNotEmpty()) focusRow = 1
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    val item = if (focusRow == 0) seriesList.getOrNull(seriesFocus)
                              else movieList.getOrNull(movieFocus)
                    if (item != null) {
                        app.transition(Screen.SERIES, SeriesState(item))
                    }
                }
            }
        }
    }

    override fun draw(app: App, rc: RC) {
        val pad = rc.dp(32f)
        val cardW = rc.dp(200f); val cardH = rc.dp(280f); val spacing = rc.dp(16f)

        // Background
        rc.solid(0f, 0f, rc.w, rc.h, 0.039f, 0.039f, 0.102f)

        // Header
        rc.text("Janus+", pad, pad + rc.dp(28f), rc.sp(28), 0.733f, 0.525f, 0.988f)
        // Settings button — top right
        val setBtnW = rc.dp(80f)
        val setBtnH = rc.dp(40f)
        val setBtnX = rc.w - pad - setBtnW
        val setBtnY = pad
        rc.solid(setBtnX, setBtnY, setBtnW, setBtnH, 0.102f, 0.102f, 0.180f)
        val setLabel = Lang.s("settings")
        val setLabelW = rc.font.measureText(setLabel, rc.sp(12))
        rc.text(setLabel, setBtnX + (setBtnW - setLabelW) / 2f, setBtnY + rc.dp(26f), rc.sp(12), 0.733f, 0.525f, 0.988f)
        rc.tappable(setBtnX, setBtnY, setBtnW, setBtnH) {
            app.transition(Screen.SETTINGS, SettingsState())
        }

        var sectionY = pad + rc.dp(56f)

        // Series row
        if (seriesList.isNotEmpty()) {
            val focused = focusRow == 0
            rc.text(Lang.s("series"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (focused) 0.733f else 0.8f, if (focused) 0.525f else 0.8f, if (focused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)

            for ((i, item) in seriesList.withIndex()) {
                val x = pad + i * (cardW + spacing) - seriesScroll.offset
                if (x + cardW < 0 || x > rc.w) continue

                rc.solid(x, cardsY, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.cover("cover_${item.id}", x, cardsY, cardW, cardH)

                val gradH = cardH * 0.35f
                val clear = floatArrayOf(0f, 0f, 0f, 0f); val dark = floatArrayOf(0f, 0f, 0f, 0.8f)
                rc.gradient(x, cardsY + cardH - gradH, cardW, gradH, clear, clear, dark, dark)
                rc.textClipped(item.title(), x + rc.dp(8f), cardsY + cardH - rc.dp(10f),
                    rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)

                if (focused && i == seriesFocus) {
                    rc.border(x, cardsY, cardW, cardH, rc.dp(3f), 0.733f, 0.525f, 0.988f)
                }

                val tappedItem = item
                rc.tappable(x, cardsY, cardW, cardH) {
                    app.transition(Screen.SERIES, SeriesState(tappedItem))
                }
            }
            sectionY = cardsY + cardH + rc.dp(24f)
        }

        // Movies row
        if (movieList.isNotEmpty()) {
            val focused = focusRow == 1
            rc.text(Lang.s("movies"), pad, sectionY + rc.dp(16f), rc.sp(16),
                if (focused) 0.733f else 0.8f, if (focused) 0.525f else 0.8f, if (focused) 0.988f else 0.8f)
            val cardsY = sectionY + rc.dp(30f)

            for ((i, item) in movieList.withIndex()) {
                val x = pad + i * (cardW + spacing) - movieScroll.offset
                if (x + cardW < 0 || x > rc.w) continue

                rc.solid(x, cardsY, cardW, cardH, 0.102f, 0.102f, 0.180f)
                rc.cover("cover_${item.id}", x, cardsY, cardW, cardH)

                val gradH = cardH * 0.35f
                val clear = floatArrayOf(0f, 0f, 0f, 0f); val dark = floatArrayOf(0f, 0f, 0f, 0.8f)
                rc.gradient(x, cardsY + cardH - gradH, cardW, gradH, clear, clear, dark, dark)
                rc.textClipped(item.title(), x + rc.dp(8f), cardsY + cardH - rc.dp(10f),
                    rc.sp(14), cardW - rc.dp(16f), 1f, 1f, 1f)

                if (focused && i == movieFocus) {
                    rc.border(x, cardsY, cardW, cardH, rc.dp(3f), 0.733f, 0.525f, 0.988f)
                }

                val tappedItem = item
                rc.tappable(x, cardsY, cardW, cardH) {
                    app.transition(Screen.MOVIE, SeriesState(tappedItem))
                }
            }
        }

        if (loading) {
            val t = Lang.s("loading_library")
            val tw = rc.font.measureText(t, rc.sp(18))
            rc.text(t, (rc.w - tw) / 2f, rc.h / 2f, rc.sp(18), 0.8f, 0.8f, 0.8f)
        }

        // FPS
        rc.text("${app.fps}fps", rc.dp(8f), rc.dp(16f), rc.sp(10), 0.4f, 0.8f, 0.4f)
    }

    override fun cleanup(app: App) {}
}
