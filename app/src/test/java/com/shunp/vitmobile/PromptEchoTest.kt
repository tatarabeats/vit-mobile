package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptEchoTest {
    private val dict = listOf("grok", "グロック", "キオクシア", "ミルトコ")
    private val prompt = WhisperPrompt.build("コンテキストがやばいってさ、どうにかして。", dict)

    @Test fun listOnlySegmentIsEcho() {
        assertTrue(PromptEcho.isEcho("グロック、キオクシア、ミルトコ", dict, prompt))
        assertTrue(PromptEcho.isEcho(" grok、グロック、キオクシア、ミルトコ", dict, prompt))
    }

    @Test fun singleWordIsKept() {
        assertFalse(PromptEcho.isEcho("ミルトコ。", dict, prompt))
    }

    @Test fun promptCopyIsEcho() {
        assertTrue(PromptEcho.isEcho("どうにかして。", dict, prompt))
    }

    @Test fun normalSentenceKept() {
        assertFalse(PromptEcho.isEcho("ミルトコの検索を直して。", dict, prompt))
    }

    @Test fun stripsTrailingList() {
        assertEquals("…イメージしてるね。",
            PromptEcho.stripTrailing("…イメージしてるね。grok、グロック、キオクシア、ミルトコ", dict))
        assertEquals("コンテキストがやばいってさ、どうにかして。",
            PromptEcho.stripTrailing("コンテキストがやばいってさ、どうにかして。 grok、グロック、キオクシア、ミルトコ", dict))
    }

    @Test fun keepsSingleTrailingWord() {
        val t = "それはミルトコ"
        assertEquals(t, PromptEcho.stripTrailing(t, dict))
        val t2 = "検索サイトはミルトコ、キオクシアは会社"
        assertEquals(t2, PromptEcho.stripTrailing(t2, dict))
    }
}
