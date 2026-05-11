package com.videoplayer

import org.junit.Test
import java.sql.DriverManager

/**
 * Test WordScanner against the full DBZ episode subtitle file
 * using the real dictionary database.
 */
class WordScannerTest {

    // Load the real dictionary DB for lookups
    private val dictDb by lazy {
        val conn = DriverManager.getConnection("jdbc:sqlite:/tmp/dictionary_lite.db")
        val stmt = conn.prepareStatement(
            "SELECT COUNT(*) FROM dict_entries WHERE term = ? OR reading = ?"
        )
        object : WordScanner.DictLookup {
            override fun hasEntry(term: String): Boolean {
                stmt.setString(1, term)
                stmt.setString(2, term)
                val rs = stmt.executeQuery()
                rs.next()
                return rs.getInt(1) > 0
            }
        }
    }

    private val allLines = listOf(
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
    fun testFullEpisode() {
        var totalWords = 0
        var foundWords = 0
        var notFound = mutableListOf<String>()

        for (line in allLines) {
            val cleaned = line.replace(Regex("\\([\\u3040-\\u309F\\u30A0-\\u30FF\\s]+\\)"), "")
            val words = WordScanner.scan(cleaned, dictDb)
            print("LINE: $cleaned\n")
            print("  WORDS: ")
            for (w in words) {
                totalWords++
                if (w.found) {
                    foundWords++
                    val baseInfo = if (w.baseForm != w.surface) "→${w.baseForm}" else ""
                    print("[${w.surface}$baseInfo] ")
                } else {
                    notFound.add(w.surface)
                    print("(${w.surface}?) ")
                }
            }
            println("\n")
        }

        println("=== SUMMARY ===")
        println("Total words: $totalWords")
        println("Found in dict: $foundWords (${foundWords * 100 / totalWords}%)")
        println("Not found: ${notFound.size}")
        if (notFound.isNotEmpty()) {
            println("Missing: ${notFound.distinct().take(30)}")
        }
    }

    @Test
    fun testCharHighlight() {
        val testLines = listOf(
            "孫悟空の活躍によって 見事に打ち砕かれ",
            "だが 新たな影が忍び寄っていた",
            "悟飯 お前の尻尾はな 握られると力が抜けるんだ",
        )

        for (line in testLines) {
            val cleaned = line.replace(Regex("\\([\\u3040-\\u309F\\u30A0-\\u30FF\\s]+\\)"), "")
            val words = WordScanner.scan(cleaned, dictDb)

            println("TEXT: $cleaned")
            // Build highlight map
            val charMap = CharArray(cleaned.length) { '.' }
            for ((wi, w) in words.withIndex()) {
                val marker = ('A' + wi % 26)
                for (ci in w.startChar until w.endChar) {
                    if (ci < charMap.size) charMap[ci] = marker
                }
            }
            println("ZONE: ${String(charMap)}")
            println("WORDS:")
            for ((wi, w) in words.withIndex()) {
                val marker = ('A' + wi % 26)
                val baseInfo = if (w.baseForm != w.surface) " → ${w.baseForm}" else ""
                val status = if (w.found) "✓" else "✗"
                println("  $marker: [${w.surface}]$baseInfo $status  chars=${w.startChar}-${w.endChar}")
            }
            println()
        }
    }
}
