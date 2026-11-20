package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperPromptTest {
    private val dict = listOf("grok", "グロック", "キオクシア", "ミルトコ")

    @Test fun noPrevStartsWithNormalJapanese() {
        val p = WhisperPrompt.build(null, dict)
        assertTrue(p, p.startsWith("こんにちは。"))
        assertTrue(p, p.endsWith("grok、グロック、キオクシア、ミルトコ"))
        assertTrue(p, !p.contains("\n"))
    }

    @Test fun prevTailComesFirst() {
        val prev = "あ".repeat(40) + "スマホ版で試してみて。"
        val p = WhisperPrompt.build(prev, dict)
        assertTrue(p, p.startsWith(prev.takeLast(30) + " "))
        assertTrue(p, p.endsWith("ミルトコ"))
    }

    @Test fun fitsMaxAndSkipsDuplicates() {
        val many = (1..50).map { "ことば$it" } + listOf("ことば1")
        val p = WhisperPrompt.build("前の発言です。", many)
        assertTrue(p.length <= WhisperPrompt.MAX_CHARS)
        assertEquals(1, Regex("ことば1(?!\\d)").findAll(p).count())
    }
}
