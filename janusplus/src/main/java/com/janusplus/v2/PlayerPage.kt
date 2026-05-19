package com.janusplus.v2

import android.graphics.Bitmap
import com.janusplus.GlyphAtlas
import com.janusplus.JanusApi
import com.janusplus.SrtParser

data class PlayerPrefs(
    val deltaFurigana: Float, val deltaRow: Float, val deltaSpacing: Float, val deltaYShift: Float,
    val subFontSize: Int, val readingMode: Int, val condensedMode: Boolean,
    val debugBoxes: Boolean, val einkMode: Boolean,
)

data class PlayerPage(
    val item: JanusApi.LibraryItem,
    val episode: JanusApi.Episode,
    val baseUrl: String,
    val prefs: PlayerPrefs,

    // Subtitle data
    val cues: List<SrtParser.Cue>,
    val superSRT: JanusApi.SuperSRT?,

    // Pre-built atlas bitmaps — uploaded once in PlayerState.init(), then recycled
    val uiAtlases: List<Triple<Int, GlyphAtlas, Bitmap>>,   // (pxSize, atlas, bitmap)
    val subAtlas: GlyphAtlas?, val subBmp: Bitmap?,
    val furiAtlas: GlyphAtlas?, val furiBmp: Bitmap?,
    val dictAtlases: List<Triple<Int, GlyphAtlas, Bitmap>>,  // (pxSize, atlas, bitmap)
)
