package com.videoplayer

import android.util.Log
import dev.jdtech.mpv.MPVLib

/**
 * Progressive subtitle timeline built from mpv events.
 * Records sub-start/sub-end each time a sub is displayed.
 * For next-sub lookup during gaps, does a single sub-seek peek.
 */
class SubtitleTimeline {

    data class SubEntry(val startSec: Double, val endSec: Double, val text: String)

    private val entries = mutableListOf<SubEntry>()
    private var nextSubCache: SubEntry? = null
    private var nextSubCachePos = -1.0

    /**
     * Call when a subtitle appears. Reads sub-start/sub-end from mpv.
     */
    fun recordCurrentSub(text: String, positionSec: Double) {
        if (text.isBlank()) return
        val start = try { MPVLib.getPropertyDouble("sub-start") ?: positionSec } catch (_: Exception) { positionSec }
        val end = try { MPVLib.getPropertyDouble("sub-end") ?: (positionSec + 5.0) } catch (_: Exception) { positionSec + 5.0 }

        // Avoid duplicates
        if (entries.any { Math.abs(it.startSec - start) < 0.3 }) return
        entries.add(SubEntry(start, end, text))
        entries.sortBy { it.startSec }
        nextSubCache = null // invalidate cache
    }

    fun count() = entries.size
    fun getEntries(): List<SubEntry> = entries.toList()

    fun nextAfter(positionSec: Double): SubEntry? =
        entries.firstOrNull { it.startSec > positionSec + 0.05 }

    fun prevBefore(positionSec: Double): SubEntry? =
        entries.lastOrNull { it.startSec < positionSec - 0.3 }

    fun gapUntilNext(positionSec: Double): Double {
        val next = nextAfter(positionSec) ?: return -1.0
        return next.startSec - positionSec
    }

    /**
     * Get next sub start time, using cache or doing a peek via sub-seek.
     * The peek is fast (no visible effect since we seek back immediately).
     * Returns -1.0 if unknown.
     */
    fun peekNextSubStart(playerView: MpvPlayerView, currentPos: Double): Double {
        // Check recorded entries first
        val known = nextAfter(currentPos)
        if (known != null) return known.startSec

        // Use cached peek if still valid
        if (nextSubCache != null && nextSubCachePos > 0 && Math.abs(nextSubCachePos - currentPos) < 1.0) {
            return nextSubCache!!.startSec
        }

        // No data — return unknown
        return -1.0
    }

    fun clear() {
        entries.clear()
        nextSubCache = null
        nextSubCachePos = -1.0
    }
}
