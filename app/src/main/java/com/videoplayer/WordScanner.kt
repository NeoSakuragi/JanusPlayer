package com.videoplayer

/**
 * Yomitan-style word scanner. No Kuromoji needed.
 * Scans from each character position, tries longest substring first,
 * deinflects it, looks up in dictionary. Longest match wins.
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
     * Scan a subtitle line and return word boundaries.
     * Each position that starts a Japanese word gets a ScannedWord.
     * Non-Japanese characters are gaps between words.
     */
    fun scan(text: String, dict: DictLookup, maxLen: Int = 12): List<ScannedWord> {
        val words = mutableListOf<ScannedWord>()
        var i = 0

        while (i < text.length) {
            if (!isJapaneseChar(text[i])) {
                i++
                continue
            }

            var bestWord: ScannedWord? = null
            val maxEnd = minOf(i + maxLen, text.length)

            // Try longest substring first
            for (len in (maxEnd - i) downTo 1) {
                val chunk = text.substring(i, i + len)
                // Try deinflected forms
                val candidates = Deinflector.deinflect(chunk)
                for (candidate in candidates) {
                    if (dict.hasEntry(candidate)) {
                        bestWord = ScannedWord(i, i + len, chunk, candidate, true)
                        break
                    }
                }
                if (bestWord != null) break
            }

            if (bestWord != null) {
                words.add(bestWord)
                i = bestWord.endChar
            } else {
                // Single character, no match — skip it
                words.add(ScannedWord(i, i + 1, text[i].toString(), text[i].toString(), false))
                i++
            }
        }

        return words
    }
}
