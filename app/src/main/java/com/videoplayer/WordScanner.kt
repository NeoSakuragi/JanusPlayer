package com.videoplayer

/**
 * Yomitan-style word scanner. Scans from a single character position
 * to find the longest dictionary match. No full-line scan needed.
 */
object WordScanner {

    data class ScannedWord(
        val startChar: Int,
        val endChar: Int,
        val surface: String,
        val baseForm: String,
        val found: Boolean
    )

    interface DictLookup {
        fun hasEntry(term: String): Boolean
    }

    private fun isJapaneseChar(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    /**
     * Find the word at a specific character position.
     * Tries longest substring first, deinflects, checks dictionary.
     */
    fun scanAt(text: String, pos: Int, dict: DictLookup, maxLen: Int = 12): ScannedWord? {
        if (pos >= text.length || !isJapaneseChar(text[pos])) return null

        val maxEnd = minOf(pos + maxLen, text.length)
        for (len in (maxEnd - pos) downTo 1) {
            val chunk = text.substring(pos, pos + len)
            for (candidate in Deinflector.deinflect(chunk)) {
                if (dict.hasEntry(candidate)) {
                    return ScannedWord(pos, pos + len, chunk, candidate, true)
                }
            }
        }
        // Single char, no match
        return ScannedWord(pos, pos + 1, text[pos].toString(), text[pos].toString(), false)
    }

    /**
     * Find all navigable word positions in the text.
     * Returns start positions of Japanese character runs.
     * Actual word boundaries resolved lazily via scanAt().
     */
    fun findJapanesePositions(text: String): List<Int> {
        val positions = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            if (isJapaneseChar(text[i])) {
                positions.add(i)
                i++
            } else {
                i++
            }
        }
        return positions
    }

    /**
     * Full line scan (for unit tests). Scans every position.
     */
    fun scan(text: String, dict: DictLookup, maxLen: Int = 12): List<ScannedWord> {
        val words = mutableListOf<ScannedWord>()
        var i = 0
        while (i < text.length) {
            if (!isJapaneseChar(text[i])) { i++; continue }
            val word = scanAt(text, i, dict, maxLen)
            if (word != null) {
                words.add(word)
                i = word.endChar
            } else {
                i++
            }
        }
        return words
    }
}
