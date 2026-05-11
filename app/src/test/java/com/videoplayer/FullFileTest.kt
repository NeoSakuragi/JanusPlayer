package com.videoplayer

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Test

class FullFileTest {

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

    private fun isFocusableWord(token: Token): Boolean {
        val s = token.surface
        if (!isJapanese(s)) return false
        if (s.length == 1 && Character.UnicodeBlock.of(s[0]) == Character.UnicodeBlock.HIRAGANA) return false
        return true
    }

    private fun isInflectionSuffix(token: Token): Boolean {
        val pos1 = token.partOfSpeechLevel1
        val pos2 = token.partOfSpeechLevel2
        return pos1 == "助動詞" || pos1 == "助詞" ||
            (pos1 == "動詞" && (pos2 == "接尾" || pos2 == "非自立"))
    }

    private val lines = listOf(
        "（男の子） わ～い！　待て待て ハハハッ！",
        "（ﾅﾚｰﾀｰ）世界征服という ピッコロの野望も⸺",
        "孫悟空の活躍によって 見事に打ち砕かれ",
        "５年余りの月日が あっという間に過ぎた",
        "毎日 世界は平和そのものであった",
        "だが 新たな影が忍び寄っていた",
        "（チチ）悟飯ちゃ～ん！",
        "（チチ）ご飯よ！",
        "（悟空）ああ これがいいな",
        "悟飯 お前の尻尾はな 握られると力が抜けるんだ",
        "だから絶対に誰にも握らせちゃいかんぞ",
        "おい 悟空 久しぶりだな",
        "お前に会いに来たんだ",
        "カカロットよ お前は戦闘民族サイヤ人だ",
        "この星の人間ではない",
        "何だと！？ 俺がサイヤ人だと！？",
        "信じられるか そんな話",
        "お前の仲間を殺しに来たのか",
        "違うな 仲間に加わってもらいたいのだ",
        "やめろ！ 悟飯に手を出すな！",
        "ハァ… オラ もう腹ぺこだ",
        "何言ってるだ… 悟飯ちゃん 見なかっただか？",
        "早く ご飯を食べて出かけねば⸺",
        "武天老師さまたちが お待ちかねだべ",
        "よし　オラ 捜してくる",
        "そんなに遠くには 行ってねえと思うだども…",
        "戦闘力 たったの５か… ゴミめ",
        "ち… 近寄るんじゃねえよ！",
        "フッ… なんという もろい民族だ",
        "大きなパワーを持ったヤツがいる",
        "全宇宙一の強戦士 サイヤ人の誇りを見失ったのか！",
        "謎の戦士と悟空 何やら関係がありそうだが…",
        "いよいよ 次回 悟空の過去が明らかに！",
        "下りられないよ… 怖い",
        "分かんない",
        "母ちゃんが心配してるぞ",
        "亀仙人のじっちゃんの所にも 行かなくちゃなんねえしな",
        "くだらん技だな",
        "ただ ホコリを巻き上げるだけか…",
        "今度は俺の番か では 技の見本を見せてやろう",
        "この俺が 震えて動けなかった",
    )

    @Test
    fun testAllLines() {
        var totalWords = 0
        var wordsWithBaseForm = 0
        var wordsWithDeinflection = 0
        var issues = mutableListOf<String>()

        for (line in lines) {
            val tokens = tokenizer.tokenize(line)
            val focusable = tokens.mapIndexedNotNull { i, t -> if (isFocusableWord(t)) i else null }

            for (fi in focusable) {
                val token = tokens[fi]
                totalWords++

                // Build highlight range (include inflection suffixes)
                var end = fi
                for (j in fi + 1 until tokens.size) {
                    if (isInflectionSuffix(tokens[j])) end = j else break
                }
                val fullSurface = tokens.subList(fi, end + 1).joinToString("") { it.surface }
                val baseForm = token.baseForm ?: token.surface

                // Try deinflection on the full surface
                val candidates = mutableListOf(baseForm)
                if (token.surface != baseForm) candidates.add(token.surface)
                if (fullSurface != token.surface && fullSurface != baseForm) candidates.add(fullSurface)
                candidates.addAll(Deinflector.deinflect(fullSurface).drop(1))
                if (fullSurface != token.surface) {
                    candidates.addAll(Deinflector.deinflect(token.surface).drop(1))
                }
                val unique = candidates.distinct()

                val hasDictForm = baseForm != token.surface || baseForm != fullSurface
                if (hasDictForm) wordsWithBaseForm++
                if (unique.size > 1) wordsWithDeinflection++

                // Flag potential issues
                val highlighted = if (fullSurface != token.surface) "$fullSurface (from ${token.surface})" else fullSurface
                if (unique.size == 1 && baseForm == token.surface) {
                    // No transformation at all - might be fine for nouns, but flag verbs
                    if (token.partOfSpeechLevel1 == "動詞") {
                        issues.add("VERB_NO_DEINFLECT: [$highlighted] base=$baseForm pos=${token.partOfSpeechLevel1}")
                    }
                }

                println("  [$highlighted] → base=$baseForm candidates=${unique.take(4)} pos=${token.partOfSpeechLevel1}/${token.partOfSpeechLevel2}")
            }
        }

        println("\n=== SUMMARY ===")
        println("Total focusable words: $totalWords")
        println("Words with Kuromoji base form: $wordsWithBaseForm")
        println("Words with deinflection candidates: $wordsWithDeinflection")
        println("Potential issues: ${issues.size}")
        for (issue in issues) println("  $issue")
    }
}
