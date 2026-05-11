package com.videoplayer

/**
 * Parse SRT subtitle files into timed cues.
 */
object SrtParser {

    data class Cue(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String
    ) {
        val startSec: Double get() = startMs / 1000.0
        val endSec: Double get() = endMs / 1000.0
    }

    fun parse(srtContent: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = srtContent.trim().split(Regex("\\n\\s*\\n"))

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 2) continue

            val index = lines[0].trim().toIntOrNull() ?: continue
            val timeLine = lines[1].trim()
            val timeParts = timeLine.split("-->")
            if (timeParts.size != 2) continue

            val startMs = parseTimestamp(timeParts[0].trim())
            val endMs = parseTimestamp(timeParts[1].trim())
            if (startMs < 0 || endMs < 0) continue

            val text = lines.drop(2).joinToString("\n").trim()
            if (text.isNotEmpty()) {
                cues.add(Cue(index, startMs, endMs, text))
            }
        }

        return cues.sortedBy { it.startMs }
    }

    fun cueAt(cues: List<Cue>, positionMs: Long): Cue? =
        cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    fun nextCueAfter(cues: List<Cue>, positionMs: Long): Cue? =
        cues.firstOrNull { it.startMs > positionMs + 50 }

    fun prevCueBefore(cues: List<Cue>, positionMs: Long): Cue? =
        cues.lastOrNull { it.startMs < positionMs - 300 }

    private fun parseTimestamp(ts: String): Long {
        // Format: HH:MM:SS,mmm or HH:MM:SS.mmm
        val clean = ts.replace(',', '.')
        val parts = clean.split(":")
        if (parts.size != 3) return -1
        val h = parts[0].toLongOrNull() ?: return -1
        val m = parts[1].toLongOrNull() ?: return -1
        val secParts = parts[2].split(".")
        val s = secParts[0].toLongOrNull() ?: return -1
        val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0 else 0
        return h * 3600000 + m * 60000 + s * 1000 + ms
    }
}
