package com.videoplayer

data class RubySpan(val start: Int, val length: Int, val reading: String)

fun stripFurigana(text: String): Pair<String, List<RubySpan>> {
    val spans = mutableListOf<RubySpan>()
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val parenOpen = text.indexOf('(', i)
        if (parenOpen == -1) { out.append(text, i, text.length); break }
        val parenClose = text.indexOf(')', parenOpen)
        if (parenClose == -1) { out.append(text, i, text.length); break }
        val reading = text.substring(parenOpen + 1, parenClose)
        val isReading = reading.all { c -> c == ' ' || c == '　' || c.isHiragana() || c.isKatakana() }
        if (!isReading || parenOpen == i) {
            out.append(text, i, parenClose + 1)
            i = parenClose + 1
            continue
        }
        // Find the kanji base: scan backwards from parenOpen to find the run of kanji/CJK chars
        var kanjiStart = parenOpen
        while (kanjiStart > i && text[kanjiStart - 1].isCjkIdeograph()) {
            kanjiStart--
        }
        if (kanjiStart == parenOpen) {
            // No kanji before the paren — treat as non-reading
            out.append(text, i, parenClose + 1)
            i = parenClose + 1
            continue
        }
        // Append text before the kanji, then the kanji
        out.append(text, i, kanjiStart)
        val kanjiStr = text.substring(kanjiStart, parenOpen)
        val spanStart = out.length
        out.append(kanjiStr)
        spans.add(RubySpan(spanStart, kanjiStr.length, reading.trim()))
        i = parenClose + 1
    }
    return out.toString() to spans
}

private fun Char.isCjkIdeograph(): Boolean {
    val block = Character.UnicodeBlock.of(this)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}

private fun Char.isHiragana() = this in '぀'..'ゟ'
private fun Char.isKatakana() = this in '゠'..'ヿ'
