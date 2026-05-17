package com.janusplus.v2

/**
 * Reading mode conversion utilities for Japanese text.
 * - kata2hira: Katakana → Hiragana (offset 0x60)
 * - toRomaji: Katakana/Hiragana → Romaji
 */
object ReadingUtils {

    fun kata2hira(text: String): String = buildString {
        for (c in text) {
            if (c in 'ァ'..'ヶ') append(c - 0x60)
            else append(c)
        }
    }

    private val romajiMap = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヲ' to "wo", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "di", 'ヅ' to "du", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ッ' to "q", 'ー' to "-",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
    )

    /** Convert hiragana/katakana to katakana for romaji lookup */
    private fun hira2kata(c: Char): Char =
        if (c in 'ぁ'..'ゖ') c + 0x60 else c

    fun toRomaji(text: String): String = buildString {
        val chars = text.toList()
        var i = 0
        while (i < chars.size) {
            val c = hira2kata(chars[i])
            // Small tsu: double the next consonant
            if (c == 'ッ') {
                if (i + 1 < chars.size) {
                    val next = romajiMap[hira2kata(chars[i + 1])]
                    if (next != null && next.isNotEmpty()) append(next[0]) else append("t")
                }
                i++
                continue
            }
            // Small kana combos: キャ→kya, シュ→shu, チョ→cho, etc.
            if (i + 1 < chars.size) {
                val nextKata = hira2kata(chars[i + 1])
                if (nextKata in "ャュョァィゥェォ") {
                    val base = romajiMap[c]
                    val mod = romajiMap[nextKata]
                    if (base != null && mod != null) {
                        append(base.dropLast(1))
                        append(mod)
                        i += 2
                        continue
                    }
                }
            }
            val r = romajiMap[c]
            if (r != null) append(r)
            else append(chars[i]) // pass through non-kana (kanji, latin, punctuation)
            i++
        }
    }
}
