package com.janusplus

enum class Screen { HOME, SERIES_DETAIL, MOVIE_DETAIL, PLAYING }

enum class HomeRow { SERIES, MOVIES }

enum class DetailFocus { HERO, GRID }

class AppState {
    var screen = Screen.HOME
    var loading = true
    @Volatile var pendingTap: JanusApi.LibraryItem? = null
    val coverAtlas = ThumbnailAtlas()

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
    var moviesRowY = 0f

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

    // Player
    var playingUrl: String? = null
    var returnScreen = Screen.HOME

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

    fun stateHash(): Long {
        var h = screen.ordinal.toLong() * 31
        h += homeRow.ordinal * 37
        h += seriesFocus * 41
        h += movieFocus * 43
        h += detailFocus.ordinal * 47
        h += heroButtonFocus * 53
        h += episodeFocus * 59
        h += selectedSeason * 61
        h += (seriesScroll.offset * 10).toLong() * 67
        h += (movieScroll.offset * 10).toLong() * 71
        h += (detailScroll.offset * 10).toLong() * 73
        h += library.size * 79
        h += (seasonCards?.episodes?.size ?: 0) * 83
        h += if (heroBlob != null) 89 else 0
        h += if (loading) 97 else 0
        return h
    }

    fun closeDetail() {
        screen = Screen.HOME
        selectedItem = null
        heroBlob = null
        seasonCards = null
    }
}
