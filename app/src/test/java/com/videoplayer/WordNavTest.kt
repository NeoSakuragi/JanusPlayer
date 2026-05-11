package com.videoplayer

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Test

class WordNavTest {

    private val tokenizer = Tokenizer()

    private fun isJapanese(text: String): Boolean = text.any { c ->
        val block = Character.UnicodeBlock.of(c)
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        (block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS && c.isLetterOrDigit())
    }

    data class NavState(
        val tokens: List<Token>,
        val focusableIndices: List<Int>,
        var focusIdx: Int // index into focusableIndices
    ) {
        val focusedTokenIdx get() = focusableIndices[focusIdx]
        val focusedWord get() = tokens[focusedTokenIdx].surface
        val focusedBaseForm get() = tokens[focusedTokenIdx].baseForm ?: focusedWord

        fun right(): Boolean {
            if (focusIdx < focusableIndices.size - 1) { focusIdx++; return true }
            return false
        }

        fun left(): Boolean {
            if (focusIdx > 0) { focusIdx--; return true }
            return false
        }
    }

    private fun isFocusableWord(token: Token): Boolean {
        val s = token.surface
        if (!isJapanese(s)) return false
        if (s.length == 1 && Character.UnicodeBlock.of(s[0]) == Character.UnicodeBlock.HIRAGANA) return false
        return true
    }

    private fun buildNav(text: String): NavState? {
        val tokens = tokenizer.tokenize(text)
        val focusable = tokens.mapIndexedNotNull { i, t ->
            if (isFocusableWord(t)) i else null
        }
        if (focusable.isEmpty()) return null
        return NavState(tokens, focusable, 0)
    }

    private val testLines = listOf(
        "（男の子） わ～い！　待て待て ハハハッ！",
        "（ﾅﾚｰﾀｰ）世界征服という ピッコロの野望も⸺",
        "孫悟空の活躍によって 見事に打ち砕かれ",
        "毎日 世界は平和そのものであった",
        "だが 新たな影が忍び寄っていた",
        "（チチ）悟飯ちゃ～ん！",
        "（チチ）ご飯よ！",
        "（悟空）ああ これがいいな",
        "悟飯 お前の尻尾はな 握られると力が抜けるんだ",
        "だから絶対に誰にも握らせちゃいかんぞ",
        "は～い！",
        "おい 悟空 久しぶりだな",
        "お前に会いに来たんだ",
        "カカロットよ お前は戦闘民族サイヤ人だ",
        "この星の人間ではない",
        "何だと！？ 俺がサイヤ人だと！？",
        "信じられるか そんな話",
        "お前の仲間を殺しに来たのか",
        "違うな 仲間に加わってもらいたいのだ",
        "やめろ！ 悟飯に手を出すな！"
    )

    @Test
    fun testTokenizationAndNavigation() {
        println("=== Word Navigation Unit Test ===\n")

        for (line in testLines) {
            println("LINE: $line")
            val nav = buildNav(line)
            if (nav == null) {
                println("  → no focusable words\n")
                continue
            }

            val allTokens = nav.tokens.map { "${it.surface}[${it.baseForm}]" }
            println("  TOKENS: $allTokens")
            println("  FOCUSABLE: ${nav.focusableIndices.map { nav.tokens[it].surface }}")

            // Simulate full RIGHT traversal
            print("  NAV →: ")
            print("[${nav.focusedWord}](base=${nav.focusedBaseForm}) ")
            while (nav.right()) {
                print("→ [${nav.focusedWord}](base=${nav.focusedBaseForm}) ")
            }
            println()

            // Now go all the way LEFT
            print("  NAV ←: ")
            while (nav.left()) {
                print("← [${nav.focusedWord}](base=${nav.focusedBaseForm}) ")
            }
            println()

            // Verify boundaries
            val atStart = !nav.left()
            nav.focusIdx = nav.focusableIndices.size - 1
            val atEnd = !nav.right()
            println("  BOUNDS: left-clamp=$atStart right-clamp=$atEnd")
            println()
        }
    }

    @Test
    fun testDictionaryQueries() {
        println("=== Dictionary Query Test ===\n")
        println("Shows what base forms would be queried for each focusable word\n")

        for (line in testLines) {
            val nav = buildNav(line) ?: continue
            println("LINE: $line")
            nav.focusIdx = 0
            do {
                val surface = nav.focusedWord
                val baseForm = nav.focusedBaseForm
                val queryInfo = if (baseForm != surface) "query: '$baseForm' then '$surface'" else "query: '$surface'"
                println("  [$surface] → $queryInfo")
            } while (nav.right())
            println()
        }
    }

    @Test
    fun testPunctuationExclusion() {
        println("=== Punctuation Exclusion Test ===\n")

        val puncTests = listOf(
            "（チチ）悟飯ちゃ～ん！",
            "何だと！？ 俺がサイヤ人だと！？",
            "♪～",
            "は～い！"
        )

        for (line in puncTests) {
            val tokens = tokenizer.tokenize(line)
            println("LINE: $line")
            for (t in tokens) {
                val jp = isJapanese(t.surface)
                println("  '${t.surface}' → focusable=$jp")
            }
            println()
        }
    }

    @Test
    fun testPartOfSpeech() {
        val lines = listOf(
            "過ぎた",
            "打ち砕かれ",
            "忍び寄っていた",
            "握られると",
            "抜けるんだ",
            "戦っている"
        )
        for (line in lines) {
            println("LINE: $line")
            val tokens = tokenizer.tokenize(line)
            for (t in tokens) {
                println("  [${t.surface}] base=${t.baseForm} pos=${t.partOfSpeechLevel1}/${t.partOfSpeechLevel2} conj=${t.conjugationForm}")
            }
            println()
        }
    }
}
