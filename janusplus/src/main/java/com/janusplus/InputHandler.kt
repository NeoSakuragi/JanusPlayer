package com.janusplus

import android.view.KeyEvent
import android.view.MotionEvent

class InputHandler(private val state: AppState) {

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownTime = 0L
    private var scrolling = false
    var density = 1f
    var screenWidth = 0f
    var screenHeight = 0f

    // Callbacks
    var onItemSelected: ((JanusApi.LibraryItem) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    fun handleKey(keyCode: Int, action: Int): Boolean {
        if (action != KeyEvent.ACTION_DOWN) return false
        return when (state.screen) {
            Screen.HOME -> handleHomeKey(keyCode)
            Screen.SERIES_DETAIL, Screen.MOVIE_DETAIL -> handleDetailKey(keyCode)
        }
    }

    private fun handleHomeKey(keyCode: Int): Boolean {
        val series = state.seriesList
        val movies = state.movieList
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (state.homeRow == HomeRow.MOVIES && series.isNotEmpty()) state.homeRow = HomeRow.SERIES
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (state.homeRow == HomeRow.SERIES && movies.isNotEmpty()) state.homeRow = HomeRow.MOVIES
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (state.homeRow) {
                    HomeRow.SERIES -> if (state.seriesFocus > 0) state.seriesFocus--
                    HomeRow.MOVIES -> if (state.movieFocus > 0) state.movieFocus--
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (state.homeRow) {
                    HomeRow.SERIES -> if (state.seriesFocus < series.size - 1) state.seriesFocus++
                    HomeRow.MOVIES -> if (state.movieFocus < movies.size - 1) state.movieFocus++
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val item = when (state.homeRow) {
                    HomeRow.SERIES -> series.getOrNull(state.seriesFocus)
                    HomeRow.MOVIES -> movies.getOrNull(state.movieFocus)
                }
                if (item != null) onItemSelected?.invoke(item)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                onBack?.invoke()
                true
            }
            else -> false
        }
    }

    private fun handleDetailKey(keyCode: Int): Boolean {
        val cards = state.seasonCards?.episodes ?: emptyList()
        val cols = state.gridColumns
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                when (state.detailFocus) {
                    DetailFocus.HERO -> {} // already at top
                    DetailFocus.GRID -> {
                        val newIdx = state.episodeFocus - cols
                        if (newIdx < 0) state.detailFocus = DetailFocus.HERO
                        else state.episodeFocus = newIdx
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (state.detailFocus) {
                    DetailFocus.HERO -> {
                        if (cards.isNotEmpty()) {
                            state.detailFocus = DetailFocus.GRID
                            state.episodeFocus = 0
                        }
                    }
                    DetailFocus.GRID -> {
                        val newIdx = state.episodeFocus + cols
                        if (newIdx < cards.size) state.episodeFocus = newIdx
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (state.detailFocus) {
                    DetailFocus.HERO -> if (state.heroButtonFocus > 0) state.heroButtonFocus--
                    DetailFocus.GRID -> if (state.episodeFocus > 0) state.episodeFocus--
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when (state.detailFocus) {
                    DetailFocus.HERO -> state.heroButtonFocus = 1
                    DetailFocus.GRID -> if (state.episodeFocus < cards.size - 1) state.episodeFocus++
                }
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                state.closeDetail()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
            else -> false
        }
    }

    fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                touchDownTime = System.currentTimeMillis()
                scrolling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (!scrolling && (kotlin.math.abs(event.x - touchDownX) > 20f || kotlin.math.abs(event.y - touchDownY) > 20f)) {
                    scrolling = true
                }
                if (scrolling) {
                    when (state.screen) {
                        Screen.HOME -> {
                            val scroll = when (state.homeRow) {
                                HomeRow.SERIES -> state.seriesScroll
                                HomeRow.MOVIES -> state.movieScroll
                            }
                            scroll.offset -= dx
                        }
                        Screen.SERIES_DETAIL, Screen.MOVIE_DETAIL -> {
                            state.detailScroll.offset -= dy
                        }
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!scrolling) {
                    handleTap(touchDownX, touchDownY)
                }
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        // Hit testing against rendered positions will be implemented
        // when we have the quad positions from the last frame
    }
}
