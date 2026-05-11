package com.videoplayer

import org.junit.Test
import java.sql.DriverManager

/**
 * Test cursor-based navigation on real SRT subtitle lines.
 *
 * The cursor is a character position in the subtitle text.
 * LEFT moves cursor to previous Japanese char position.
 * RIGHT moves cursor to next Japanese char position.
 * At each position, WordScanner.scanAt finds the longest dictionary match.
 * That match determines what gets highlighted and looked up.
 */
class CursorNavTest {

    private val dictDb by lazy {
        val conn = DriverManager.getConnection("jdbc:sqlite:/tmp/dictionary_lite.db")
        val stmt = conn.prepareStatement("SELECT 1 FROM dict_entries WHERE term = ? LIMIT 1")
        object : WordScanner.DictLookup {
            override fun hasEntry(term: String): Boolean {
                stmt.setString(1, term)
                val rs = stmt.executeQuery()
                return rs.next()
            }
        }
    }

    private fun jaPositions(text: String): List<Int> = WordScanner.findJapanesePositions(text)

    private fun simulateNav(text: String) {
        val positions = jaPositions(text)
        println("TEXT: $text")
        println("POSITIONS: ${positions.size} Japanese chars")
        println()

        // Simulate: start at position 0, then RIGHT through all positions
        for ((navIdx, charPos) in positions.withIndex()) {
            val word = WordScanner.scanAt(text, charPos, dictDb)
            if (word != null && word.found) {
                val baseInfo = if (word.baseForm != word.surface) "→${word.baseForm}" else ""
                println("  cursor=$navIdx char=$charPos → highlight[${word.startChar}-${word.endChar}] \"${word.surface}\"$baseInfo")
            } else {
                println("  cursor=$navIdx char=$charPos → '${text[charPos]}' (no match)")
            }
        }
        println()
    }

    @Test
    fun testCursorNavOnRealSubs() {
        val lines = listOf(
            "北欧 ｱｽｶﾞﾙﾄﾞの神 ｵｰﾃﾞｨｰﾝの地上代行者であるヒルダのもとに",
            "伝説の神闘衣をまとった７人の神闘士が新たに結集した",
            "ここに打倒 聖域を目指した",
            "熱き死闘の火蓋が切って落とされたのだった",
            "まず 黄金聖闘士のアルデバランを一撃のもとに倒した",
        )

        for (line in lines) {
            simulateNav(line)
        }
    }

    @Test
    fun testLeftRightMovement() {
        val text = "熱き死闘の火蓋が切って落とされたのだった"
        val positions = jaPositions(text)

        println("TEXT: $text")
        println("Simulating: start → RIGHT RIGHT RIGHT → LEFT LEFT\n")

        var cursor = 0

        // Show initial
        val w0 = WordScanner.scanAt(text, positions[cursor], dictDb)
        println("  START cursor=$cursor → \"${w0?.surface}\" [${w0?.startChar}-${w0?.endChar}]")

        // RIGHT 3 times
        for (i in 1..3) {
            cursor++
            val w = WordScanner.scanAt(text, positions[cursor], dictDb)
            println("  RIGHT cursor=$cursor → \"${w?.surface}\" [${w?.startChar}-${w?.endChar}]")
        }

        // LEFT 2 times
        for (i in 1..2) {
            cursor--
            val w = WordScanner.scanAt(text, positions[cursor], dictDb)
            println("  LEFT  cursor=$cursor → \"${w?.surface}\" [${w?.startChar}-${w?.endChar}]")
        }
    }

    @Test
    fun testHighlightConsistency() {
        // Verify: at every cursor position, the highlight range contains the cursor
        val text = "まず 黄金聖闘士のアルデバランを一撃のもとに倒した"
        val positions = jaPositions(text)
        var errors = 0

        println("TEXT: $text\n")
        for ((navIdx, charPos) in positions.withIndex()) {
            val word = WordScanner.scanAt(text, charPos, dictDb)
            if (word != null) {
                val contains = charPos >= word.startChar && charPos < word.endChar
                val status = if (contains) "OK" else "FAIL"
                if (!contains) errors++
                println("  cursor=$navIdx char=$charPos highlight=[${word.startChar},${word.endChar}) contains=$status \"${word.surface}\"")
            }
        }
        println("\nErrors: $errors")
        assert(errors == 0) { "$errors positions where highlight doesn't contain cursor" }
    }
}
