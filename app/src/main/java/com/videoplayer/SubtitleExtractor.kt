package com.videoplayer

import android.util.Log
import dev.jdtech.mpv.MPVLib

/**
 * Builds subtitle timeline progressively from mpv events.
 * Each time a subtitle appears, records its start/end time.
 * For gap countdown, does a single invisible sub-seek peek.
 */
object SubtitleExtractor {

    data class SubEntry(
        val startSec: Double,
        val endSec: Double,
        val text: String
    )
}
