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
    // Each maps codepoint → (u0, v0, u1, v1, w, h, advance, ascent)
    val titleAtlas: GlyphAtlas,   // 28sp — series title, back arrow
    val bodyAtlas: GlyphAtlas,    // 13sp — synopsis, episode titles, episode count
    val btnAtlas: GlyphAtlas,     // 16sp — play button
    val smallAtlas: GlyphAtlas,   // 10sp — duration labels
    val settAtlas: GlyphAtlas,    // 12sp — settings button

    val density: Float,
) {
    data class Episode(val episode: Int, val titleEn: String, val durationSec: Int)
}
