package com.janusplus

enum class Screen { HOME, SERIES_DETAIL, MOVIE_DETAIL }

enum class HomeRow { SERIES, MOVIES }

enum class DetailFocus { HERO, GRID }

class AppState {
    var screen = Screen.HOME
    var loading = true

    // Data
    var library: List<JanusApi.LibraryItem> = emptyList()
    val seriesList get() = library.filter { it.type.equals("TV_SERIES", ignoreCase = true) || it.type.equals("series", ignoreCase = true) }
    val movieList get() = library.filter { it.type.equals("MOVIE", ignoreCase = true) }

    // Home focus
    var homeRow = HomeRow.SERIES
    var seriesFocus = 0
    var movieFocus = 0
    val seriesScroll = ScrollPhysics()
    val movieScroll = ScrollPhysics()

    // Detail
    var selectedItem: JanusApi.LibraryItem? = null
    var heroBlob: JanusApi.HeroBlob? = null
    var seasonCards: JanusApi.SeasonCards? = null
    var selectedSeason = 1
    var detailFocus = DetailFocus.HERO
    var heroButtonFocus = 0
    var episodeFocus = 0
    var gridColumns = 1
    val detailScroll = ScrollPhysics()
    var detailLoading = true

    fun openItem(item: JanusApi.LibraryItem) {
        selectedItem = item
        heroBlob = null
        seasonCards = null
        selectedSeason = 1
        detailFocus = DetailFocus.HERO
        heroButtonFocus = 0
        episodeFocus = 0
        detailScroll.offset = 0f
        detailScroll.velocity = 0f
        detailLoading = true
        screen = if (item.type.equals("MOVIE", ignoreCase = true)) Screen.MOVIE_DETAIL else Screen.SERIES_DETAIL
    }

    fun closeDetail() {
        screen = Screen.HOME
        selectedItem = null
        heroBlob = null
        seasonCards = null
    }
}
