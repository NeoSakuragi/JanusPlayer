package com.videoplayer

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Test

/**
 * For each character position in a subtitle, what word gets highlighted?
 * Simulates: "I set my cursor on the Nth character, what lights up?"
 */
class CharPositionTest {

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
        return pos1 == "助動詞" ||
            (pos1 == "動詞" && (pos2 == "接尾" || pos2 == "非自立")) ||
            (pos1 == "助詞" && pos2 == "接続助詞")
    }

    data class WordGroup(
        val tokenRange: IntRange,
        val charRange: IntRange,
        val highlighted: String,
        val baseForm: String,
        val deinflectCandidates: List<String>
    )

    private fun analyze(text: String): Pair<List<Token>, List<WordGroup>> {
        val tokens = tokenizer.tokenize(text)

        // Map each token to its char range in the original text
        val tokenCharRanges = mutableListOf<IntRange>()
        var pos = 0
        for (token in tokens) {
            val start = text.indexOf(token.surface, pos)
            if (start >= 0) {
                tokenCharRanges.add(start until start + token.surface.length)
                pos = start + token.surface.length
            } else {
                tokenCharRanges.add(pos until pos + token.surface.length)
                pos += token.surface.length
            }
        }

        // Build focusable word groups with highlight ranges
        val focusableIndices = tokens.mapIndexedNotNull { i, t -> if (isFocusableWord(t)) i else null }
        val groups = mutableListOf<WordGroup>()

        for (fi in focusableIndices) {
            var end = fi
            for (j in fi + 1 until tokens.size) {
                if (isInflectionSuffix(tokens[j])) end = j else break
            }

            val charStart = tokenCharRanges[fi].first
            val charEnd = tokenCharRanges[end].last
            val highlighted = text.substring(charStart..charEnd)
            val baseForm = tokens[fi].baseForm ?: tokens[fi].surface
            val fullSurface = tokens.subList(fi, end + 1).joinToString("") { it.surface }
            val candidates = mutableListOf(baseForm)
            if (tokens[fi].surface != baseForm) candidates.add(tokens[fi].surface)
            candidates.addAll(Deinflector.deinflect(fullSurface).drop(1))

            groups.add(WordGroup(
                tokenRange = fi..end,
                charRange = charStart..charEnd,
                highlighted = highlighted,
                baseForm = baseForm,
                deinflectCandidates = candidates.distinct()
            ))
        }

        return tokens to groups
    }

    private fun testLine(text: String) {
        println("\n${"=".repeat(60)}")
        println("TEXT: $text")
        println("      ${"".padStart(text.length, '-')}")
        print("CHAR: ")
        for (i in text.indices) print("${i % 10}")
        println()

        val (tokens, groups) = analyze(text)

        // For each character, show which group it belongs to
        println("\nCHAR → HIGHLIGHT mapping:")
        val charToGroup = IntArray(text.length) { -1 }
        for ((gi, group) in groups.withIndex()) {
            for (ci in group.charRange) {
                if (ci < text.length) charToGroup[ci] = gi
            }
        }

        // Print the highlight visualization
        val highlightLine = CharArray(text.length) { ' ' }
        for ((gi, group) in groups.withIndex()) {
            val marker = ('A' + gi % 26)
            for (ci in group.charRange) {
                if (ci < text.length) highlightLine[ci] = marker
            }
        }
        println("ZONE: ${String(highlightLine)}")

        // Show each group
        println("\nWORD GROUPS (what LEFT/RIGHT navigates between):")
        for ((gi, group) in groups.withIndex()) {
            val marker = ('A' + gi % 26)
            println("  $marker: [${group.highlighted}] chars=${group.charRange} base=${group.baseForm} lookup=${group.deinflectCandidates.take(3)}")
        }

        // Character-by-character detail
        println("\nPER-CHARACTER (cursor on char N → highlights group):")
        for (i in text.indices) {
            val gi = charToGroup[i]
            val char = text[i]
            if (gi >= 0) {
                val group = groups[gi]
                println("  char[$i] '$char' → highlights [${group.highlighted}] (lookup: ${group.baseForm})")
            } else {
                println("  char[$i] '$char' → (not focusable)")
            }
        }
    }

    @Test
    fun testCharacterPositions() {
        val lines = listOf(
            "孫悟空の活躍によって 見事に打ち砕かれ",
            "５年余りの月日が あっという間に過ぎた",
            "だが 新たな影が忍び寄っていた",
            "悟飯 お前の尻尾はな 握られると力が抜けるんだ",
            "早く ご飯を食べて出かけねば⸺",
            "信じられるか そんな話",
            "この俺が 震えて動けなかった",
            "全宇宙一の強戦士 サイヤ人の誇りを見失ったのか！",
        )

        for (line in lines) {
            testLine(line)
        }
    }
}
