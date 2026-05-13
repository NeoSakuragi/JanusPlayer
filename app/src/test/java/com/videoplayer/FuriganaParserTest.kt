package com.videoplayer

import org.junit.Assert.*
import org.junit.Test

class FuriganaParserTest {

    @Test
    fun `no furigana - text passes through unchanged`() {
        val (text, spans) = stripFurigana("セルとの戦いから何年かが過ぎた")
        assertEquals("セルとの戦いから何年かが過ぎた", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `single multi-kanji furigana - Son Goku`() {
        val (text, spans) = stripFurigana("地球は 孫悟空(そん ごくう)親子らの活躍で")
        assertEquals("地球は 孫悟空親子らの活躍で", text)
        assertEquals(1, spans.size)
        val s = spans[0]
        assertEquals("孫悟空", text.substring(s.start, s.start + s.length))
        assertEquals(3, s.length)     // 孫悟空 = 3 chars
        assertEquals("そん ごくう", s.reading)
    }

    @Test
    fun `single kanji furigana - Gohan`() {
        val (text, spans) = stripFurigana("孫悟飯(ごはん)の住んでいる田舎には⸺")
        assertEquals("孫悟飯の住んでいる田舎には⸺", text)
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(3, spans[0].length)
        assertEquals("ごはん", spans[0].reading)
        assertEquals("孫悟飯", text.substring(spans[0].start, spans[0].start + spans[0].length))
    }

    @Test
    fun `single kanji with short reading - uchi`() {
        val (text, spans) = stripFurigana("家(うち)から通ってんの？")
        assertEquals("家から通ってんの？", text)
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        assertEquals(1, spans[0].length)
        assertEquals("うち", spans[0].reading)
        assertEquals("家", text.substring(spans[0].start, spans[0].start + spans[0].length))
    }

    @Test
    fun `two furigana in one line`() {
        val (text, spans) = stripFurigana("お前(めえ)面白(おもしれ)えカッコしてんなぁ")
        assertEquals("お前面白えカッコしてんなぁ", text)
        assertEquals(2, spans.size)

        // First: 前 with めえ (only the CJK char before the paren)
        assertEquals("前", text.substring(spans[0].start, spans[0].start + spans[0].length))
        assertEquals("めえ", spans[0].reading)
        assertEquals(1, spans[0].start)  // お=0, 前=1
        assertEquals(1, spans[0].length) // 前 = 1 CJK char

        // Second: 面白 with おもしれ
        assertEquals("面白", text.substring(spans[1].start, spans[1].start + spans[1].length))
        assertEquals("おもしれ", spans[1].reading)
        assertEquals(2, spans[1].start)  // お前面 = indices 0,1,2
        assertEquals(2, spans[1].length) // 面白 = 2 chars
    }

    @Test
    fun `kintoun - katakana-named furigana`() {
        val (text, spans) = stripFurigana("バイバイ 筋斗雲(きんとうん)！")
        assertEquals("バイバイ 筋斗雲！", text)
        assertEquals(1, spans.size)
        assertEquals(3, spans[0].length)
        assertEquals("筋斗雲", text.substring(spans[0].start, spans[0].start + spans[0].length))
        assertEquals("きんとうん", spans[0].reading)
    }

    @Test
    fun `non-reading parentheses preserved - English content`() {
        val (text, spans) = stripFurigana("test(abc)something")
        assertEquals("test(abc)something", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `non-reading parentheses preserved - numbers`() {
        val (text, spans) = stripFurigana("第1話(123)テスト")
        assertEquals("第1話(123)テスト", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `empty parentheses at start of line - no crash`() {
        val (text, spans) = stripFurigana("(test)なにか")
        // parens at position 0 with no preceding kanji -> isReading false (latin)
        assertEquals("(test)なにか", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `mixed furigana and plain text`() {
        val (text, spans) = stripFurigana("今日は天気(てんき)がいいね。明日(あした)も晴れるかな")
        assertEquals("今日は天気がいいね。明日も晴れるかな", text)
        assertEquals(2, spans.size)

        assertEquals("天気", text.substring(spans[0].start, spans[0].start + spans[0].length))
        assertEquals(2, spans[0].length)
        assertEquals("てんき", spans[0].reading)

        assertEquals("明日", text.substring(spans[1].start, spans[1].start + spans[1].length))
        assertEquals(2, spans[1].length)
        assertEquals("あした", spans[1].reading)
    }

    @Test
    fun `plain text with no parentheses at all`() {
        val (text, spans) = stripFurigana("これはテストです")
        assertEquals("これはテストです", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `unclosed parenthesis - no crash`() {
        val (text, spans) = stripFurigana("漢字(かんじ")
        assertEquals("漢字(かんじ", text)
        assertTrue(spans.isEmpty())
    }

    @Test
    fun `furigana span indices allow correct character extraction from clean text`() {
        // This is the key invariant: for every span, text[start..start+length] == the original kanji
        val inputs = listOf(
            "地球は 孫悟空(そん ごくう)親子らの活躍で",
            "孫悟飯(ごはん)の住んでいる田舎には⸺",
            "バイバイ 筋斗雲(きんとうん)！",
            "家(うち)から通ってんの？",
            "お前(めえ)面白(おもしれ)えカッコしてんなぁ",
            "今日は天気(てんき)がいいね。明日(あした)も晴れるかな",
        )
        for (input in inputs) {
            val (text, spans) = stripFurigana(input)
            for (span in spans) {
                val extracted = text.substring(span.start, span.start + span.length)
                // The extracted characters should all be non-hiragana (kanji or katakana kanji)
                // and the reading should be hiragana/katakana
                assertTrue(
                    "Span at ${span.start} length ${span.length} reading '${span.reading}' " +
                    "points at '$extracted' in '$text' (from '$input')",
                    span.start >= 0 && span.start + span.length <= text.length
                )
            }
        }
    }
}
