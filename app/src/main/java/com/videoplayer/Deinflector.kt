package com.videoplayer

/**
 * Port of Yomitan's Japanese deinflection engine.
 * Given a conjugated form like 食べていた, produces candidate base forms
 * like 食べている, 食べて, 食べる that can be looked up in the dictionary.
 */
object Deinflector {

    // Condition flags (bitmask)
    private const val V1 = 1         // ichidan verb
    private const val V5 = 2         // godan verb
    private const val VK = 4         // kuru verb
    private const val VS = 8         // suru verb
    private const val VZ = 16        // zuru verb
    private const val ADJ_I = 32     // i-adjective
    private const val MASU = 64      // -masu form
    private const val TE = 128       // -te form
    private const val TA = 256       // -ta form
    private const val BA = 512       // -ba form
    private const val V = V1 or V5 or VK or VS or VZ

    data class Rule(val from: String, val to: String, val condIn: Int, val condOut: Int)
    data class Result(val text: String, val conditions: Int)

    private val rules: List<Rule> by lazy { buildRules() }

    fun deinflect(text: String): List<String> {
        if (text.isEmpty()) return listOf(text)

        val results = mutableListOf(Result(text, 0))
        val seen = mutableSetOf(text)

        var i = 0
        while (i < results.size) {
            val (current, cond) = results[i]
            for (rule in rules) {
                if (!current.endsWith(rule.from)) continue
                if (cond != 0 && (cond and rule.condIn) == 0) continue
                val candidate = current.dropLast(rule.from.length) + rule.to
                if (candidate.isEmpty()) continue
                if (candidate in seen) continue
                seen.add(candidate)
                results.add(Result(candidate, rule.condOut))
            }
            i++
        }

        return results.map { it.text }.distinct()
    }

    private fun buildRules(): List<Rule> {
        val r = mutableListOf<Rule>()

        // -ta (past tense)
        r += Rule("かった", "い", TA, ADJ_I)
        r += Rule("た", "る", TA, V1)
        r += Rule("いた", "く", TA, V5)
        r += Rule("いだ", "ぐ", TA, V5)
        r += Rule("した", "す", TA, V5)
        r += Rule("った", "う", TA, V5)
        r += Rule("った", "つ", TA, V5)
        r += Rule("った", "る", TA, V5)
        r += Rule("んだ", "ぬ", TA, V5)
        r += Rule("んだ", "ぶ", TA, V5)
        r += Rule("んだ", "む", TA, V5)
        r += Rule("じた", "ずる", TA, VZ)
        r += Rule("した", "する", TA, VS)
        r += Rule("きた", "くる", TA, VK)
        r += Rule("来た", "来る", TA, VK)
        r += Rule("ました", "ます", TA, MASU)

        // -te form
        r += Rule("くて", "い", TE, ADJ_I)
        r += Rule("て", "る", TE, V1)
        r += Rule("いて", "く", TE, V5)
        r += Rule("いで", "ぐ", TE, V5)
        r += Rule("して", "す", TE, V5)
        r += Rule("って", "う", TE, V5)
        r += Rule("って", "つ", TE, V5)
        r += Rule("って", "る", TE, V5)
        r += Rule("んで", "ぬ", TE, V5)
        r += Rule("んで", "ぶ", TE, V5)
        r += Rule("んで", "む", TE, V5)
        r += Rule("じて", "ずる", TE, VZ)
        r += Rule("して", "する", TE, VS)
        r += Rule("きて", "くる", TE, VK)
        r += Rule("来て", "来る", TE, VK)
        r += Rule("まして", "ます", TE, MASU)

        // -nai (negative)
        r += Rule("くない", "い", ADJ_I, ADJ_I)
        r += Rule("ない", "る", ADJ_I, V1)
        r += Rule("かない", "く", ADJ_I, V5)
        r += Rule("がない", "ぐ", ADJ_I, V5)
        r += Rule("さない", "す", ADJ_I, V5)
        r += Rule("たない", "つ", ADJ_I, V5)
        r += Rule("わない", "う", ADJ_I, V5)
        r += Rule("なない", "ぬ", ADJ_I, V5)
        r += Rule("ばない", "ぶ", ADJ_I, V5)
        r += Rule("まない", "む", ADJ_I, V5)
        r += Rule("らない", "る", ADJ_I, V5)
        r += Rule("じない", "ずる", ADJ_I, VZ)
        r += Rule("しない", "する", ADJ_I, VS)
        r += Rule("こない", "くる", ADJ_I, VK)
        r += Rule("来ない", "来る", ADJ_I, VK)

        // -masu (polite)
        r += Rule("ます", "る", MASU, V1)
        r += Rule("います", "う", MASU, V5)
        r += Rule("きます", "く", MASU, V5)
        r += Rule("ぎます", "ぐ", MASU, V5)
        r += Rule("します", "す", MASU, V5)
        r += Rule("ちます", "つ", MASU, V5)
        r += Rule("にます", "ぬ", MASU, V5)
        r += Rule("びます", "ぶ", MASU, V5)
        r += Rule("みます", "む", MASU, V5)
        r += Rule("ります", "る", MASU, V5)
        r += Rule("じます", "ずる", MASU, VZ)
        r += Rule("します", "する", MASU, VS)
        r += Rule("きます", "くる", MASU, VK)
        r += Rule("来ます", "来る", MASU, VK)

        // -ba (conditional)
        r += Rule("ければ", "い", BA, ADJ_I)
        r += Rule("えば", "う", BA, V5)
        r += Rule("けば", "く", BA, V5)
        r += Rule("げば", "ぐ", BA, V5)
        r += Rule("せば", "す", BA, V5)
        r += Rule("てば", "つ", BA, V5)
        r += Rule("ねば", "ぬ", BA, V5)
        r += Rule("べば", "ぶ", BA, V5)
        r += Rule("めば", "む", BA, V5)
        r += Rule("れば", "る", BA, V1 or V5 or VK or VS or VZ)

        // -tara (conditional past)
        r += Rule("かったら", "い", 0, ADJ_I)
        r += Rule("たら", "る", 0, V1)
        r += Rule("いたら", "く", 0, V5)
        r += Rule("いだら", "ぐ", 0, V5)
        r += Rule("したら", "す", 0, V5)
        r += Rule("ったら", "う", 0, V5)
        r += Rule("ったら", "つ", 0, V5)
        r += Rule("ったら", "る", 0, V5)
        r += Rule("んだら", "ぬ", 0, V5)
        r += Rule("んだら", "ぶ", 0, V5)
        r += Rule("んだら", "む", 0, V5)

        // -tari
        r += Rule("たり", "る", 0, V1)
        r += Rule("いたり", "く", 0, V5)
        r += Rule("いだり", "ぐ", 0, V5)
        r += Rule("したり", "す", 0, V5)
        r += Rule("ったり", "う", 0, V5)
        r += Rule("ったり", "つ", 0, V5)
        r += Rule("ったり", "る", 0, V5)
        r += Rule("んだり", "ぬ", 0, V5)
        r += Rule("んだり", "ぶ", 0, V5)
        r += Rule("んだり", "む", 0, V5)

        // causative
        r += Rule("させる", "る", V1, V1)
        r += Rule("かせる", "く", V1, V5)
        r += Rule("がせる", "ぐ", V1, V5)
        r += Rule("させる", "す", V1, V5)
        r += Rule("たせる", "つ", V1, V5)
        r += Rule("わせる", "う", V1, V5)
        r += Rule("なせる", "ぬ", V1, V5)
        r += Rule("ばせる", "ぶ", V1, V5)
        r += Rule("ませる", "む", V1, V5)
        r += Rule("らせる", "る", V1, V5)
        r += Rule("させる", "する", V1, VS)
        r += Rule("来させる", "来る", V1, VK)
        r += Rule("こさせる", "くる", V1, VK)

        // passive
        r += Rule("かれる", "く", V1, V5)
        r += Rule("がれる", "ぐ", V1, V5)
        r += Rule("される", "す", V1, V5)
        r += Rule("たれる", "つ", V1, V5)
        r += Rule("われる", "う", V1, V5)
        r += Rule("なれる", "ぬ", V1, V5)
        r += Rule("ばれる", "ぶ", V1, V5)
        r += Rule("まれる", "む", V1, V5)
        r += Rule("られる", "る", V1, V1 or V5 or VK or VS or VZ)

        // potential
        r += Rule("える", "う", V1, V5)
        r += Rule("ける", "く", V1, V5)
        r += Rule("げる", "ぐ", V1, V5)
        r += Rule("せる", "す", V1, V5)
        r += Rule("てる", "つ", V1, V5)
        r += Rule("ねる", "ぬ", V1, V5)
        r += Rule("べる", "ぶ", V1, V5)
        r += Rule("める", "む", V1, V5)
        r += Rule("れる", "る", V1, V5)
        r += Rule("できる", "する", V1, VS)
        r += Rule("来れる", "来る", V1, VK)
        r += Rule("これる", "くる", V1, VK)

        // volitional
        r += Rule("よう", "る", 0, V1)
        r += Rule("おう", "う", 0, V5)
        r += Rule("こう", "く", 0, V5)
        r += Rule("ごう", "ぐ", 0, V5)
        r += Rule("そう", "す", 0, V5)
        r += Rule("とう", "つ", 0, V5)
        r += Rule("のう", "ぬ", 0, V5)
        r += Rule("ぼう", "ぶ", 0, V5)
        r += Rule("もう", "む", 0, V5)
        r += Rule("ろう", "る", 0, V5)
        r += Rule("かろう", "い", 0, ADJ_I)

        // imperative
        r += Rule("ろ", "る", 0, V1)
        r += Rule("よ", "る", 0, V1)
        r += Rule("え", "う", 0, V5)
        r += Rule("け", "く", 0, V5)
        r += Rule("げ", "ぐ", 0, V5)
        r += Rule("せ", "す", 0, V5)
        r += Rule("て", "つ", 0, V5)
        r += Rule("ね", "ぬ", 0, V5)
        r += Rule("べ", "ぶ", 0, V5)
        r += Rule("め", "む", 0, V5)
        r += Rule("れ", "る", 0, V5)

        // -iru (progressive) - chains from -te
        r += Rule("ている", "て", V1, TE)
        r += Rule("てる", "て", V1, TE)
        r += Rule("でいる", "で", V1, TE)
        r += Rule("でる", "で", V1, TE)
        r += Rule("とる", "て", V5, TE)
        r += Rule("どる", "で", V5, TE)

        // -ku (adverbial)
        r += Rule("く", "い", 0, ADJ_I)

        // -sa (nominalization)
        r += Rule("さ", "い", 0, ADJ_I)

        // -sugiru (too much)
        r += Rule("すぎる", "る", V1, V1)
        r += Rule("きすぎる", "く", V1, V5)
        r += Rule("ぎすぎる", "ぐ", V1, V5)
        r += Rule("しすぎる", "す", V1, V5)
        r += Rule("過ぎる", "る", V1, V1)

        // -tai (want to)
        r += Rule("たい", "る", ADJ_I, V1)
        r += Rule("きたい", "く", ADJ_I, V5)
        r += Rule("ぎたい", "ぐ", ADJ_I, V5)
        r += Rule("したい", "す", ADJ_I, V5)
        r += Rule("ちたい", "つ", ADJ_I, V5)
        r += Rule("いたい", "う", ADJ_I, V5)
        r += Rule("にたい", "ぬ", ADJ_I, V5)
        r += Rule("びたい", "ぶ", ADJ_I, V5)
        r += Rule("みたい", "む", ADJ_I, V5)
        r += Rule("りたい", "る", ADJ_I, V5)
        r += Rule("したい", "する", ADJ_I, VS)
        r += Rule("きたい", "くる", ADJ_I, VK)

        // -zu/-nu (negative)
        r += Rule("ず", "る", 0, V1)
        r += Rule("かず", "く", 0, V5)
        r += Rule("がず", "ぐ", 0, V5)
        r += Rule("さず", "す", 0, V5)
        r += Rule("たず", "つ", 0, V5)
        r += Rule("わず", "う", 0, V5)
        r += Rule("なず", "ぬ", 0, V5)
        r += Rule("ばず", "ぶ", 0, V5)
        r += Rule("まず", "む", 0, V5)
        r += Rule("らず", "る", 0, V5)
        r += Rule("せず", "する", 0, VS)
        r += Rule("こず", "くる", 0, VK)

        // -nasai (polite imperative)
        r += Rule("なさい", "る", 0, V1)
        r += Rule("いなさい", "う", 0, V5)
        r += Rule("きなさい", "く", 0, V5)
        r += Rule("ぎなさい", "ぐ", 0, V5)
        r += Rule("しなさい", "す", 0, V5)
        r += Rule("ちなさい", "つ", 0, V5)
        r += Rule("になさい", "ぬ", 0, V5)
        r += Rule("びなさい", "ぶ", 0, V5)
        r += Rule("みなさい", "む", 0, V5)
        r += Rule("りなさい", "る", 0, V5)

        return r
    }
}
