package com.janusplus.v2

import com.janusplus.GlyphAtlas
import com.janusplus.JanusApi

/**
 * All data needed to render a series page. Built entirely in SeriesLoadingState.
 * SeriesDisplayState receives this and does NOTHING but draw quads.
 */
data class SeriesDisplayPage(
    val item: JanusApi.LibraryItem,
    val title: String,
    val synopsis: String,
    val episodeCount: Int,
    val episodes: List<Episode>,
    val fullEpisodes: List<JanusApi.Episode>,

    // Bitmaps (uploaded to VRAM in display state init, then discarded)
    val bannerBmp: android.graphics.Bitmap?,
    val bannerW: Int, val bannerH: Int,
    val thumbBmp: android.graphics.Bitmap?,
    val thumbW: Float, val thumbH: Float,
    val atlasW: Int, val atlasH: Int, val atlasCols: Int,

    // Glyph atlases per text size — the LUTs
    val titleAtlas: GlyphAtlas,
    val bodyAtlas: GlyphAtlas,
    val btnAtlas: GlyphAtlas,
    val smallAtlas: GlyphAtlas,
    val settAtlas: GlyphAtlas,

    // Pre-rendered atlas bitmaps (uploaded to VRAM in display init, then recycled)
    val titleBmp: android.graphics.Bitmap,
    val bodyBmp: android.graphics.Bitmap,
    val btnBmp: android.graphics.Bitmap,
    val smallBmp: android.graphics.Bitmap,
    val settBmp: android.graphics.Bitmap,

    val density: Float,
) {
    data class Episode(val episode: Int, val titleEn: String, val durationSec: Int)
}
