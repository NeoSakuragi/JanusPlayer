package com.videoplayer

import org.junit.Test

class DeinflectorTest {

    @Test
    fun testBasicDeinflections() {
        val cases = mapOf(
            "食べた" to "食べる",
            "食べている" to "食べる",
            "食べていた" to "食べる",
            "過ぎた" to "過ぎる",
            "忍び寄っていた" to "忍び寄る",
            "打ち砕かれ" to "打ち砕く",
            "握られる" to "握る",
            "戦っている" to "戦う",
            "抜ける" to "抜ける",
            "走った" to "走る",
            "読んだ" to "読む",
            "飲んで" to "飲む",
            "書いて" to "書く",
            "泳いで" to "泳ぐ",
            "話した" to "話す",
            "待って" to "待つ",
            "死んだ" to "死ぬ",
            "遊んだ" to "遊ぶ",
            "食べない" to "食べる",
            "行かない" to "行く",
            "食べます" to "食べる",
            "食べたい" to "食べる",
            "食べれば" to "食べる",
            "食べよう" to "食べる",
            "食べろ" to "食べる",
            "美しくない" to "美しい",
            "美しかった" to "美しい",
            "高さ" to "高い",
            "近寄る" to "近寄る",
        )

        var passed = 0
        var failed = 0
        for ((inflected, expectedBase) in cases) {
            val candidates = Deinflector.deinflect(inflected)
            if (expectedBase in candidates) {
                println("OK: $inflected → $expectedBase (among ${candidates.size} candidates)")
                passed++
            } else {
                println("FAIL: $inflected → expected $expectedBase, got ${candidates.take(5)}")
                failed++
            }
        }
        println("\n$passed passed, $failed failed")
        assert(failed == 0) { "$failed tests failed" }
    }
}
