package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaModeTest {
    @Test fun detectsHiraganaPrefix() {
        val r = KanaMode.detect("ひらがなで、今日は6時です。")!!
        assertEquals(KanaMode.Kind.HIRAGANA, r.kind)
        assertEquals("今日は6時です", r.rest)  // 変えるのは文の終わりの手前まで
        assertEquals("。", r.tail)
    }

    @Test fun detectsKatakanaWithVerb() {
        val r = KanaMode.detect("カタカナで書いてアイスクリーム")!!
        assertEquals(KanaMode.Kind.KATAKANA, r.kind)
        assertEquals("アイスクリーム", r.rest)
        assertEquals(KanaMode.Kind.HIRAGANA, KanaMode.detect("ひらがなだけで入力して東京")!!.kind)
    }

    @Test fun detectsAfterSentenceEnd() {
        val r = KanaMode.detect("これはテストですカタカナでyoutube.com")!!
        assertEquals(KanaMode.Kind.KATAKANA, r.kind)
        assertEquals("これはテストです", r.head)
        assertEquals("youtube.com", r.rest)
        assertNull(KanaMode.detect("全然カタカナになんないし"))
        assertNull(KanaMode.detect("この文はカタカナでいいよ"))
    }

    @Test fun ordinarySentencesAreNotCommands() {
        assertNull(KanaMode.detect("ひらがなでいいよ"))
        assertNull(KanaMode.detect("ひらがなでも書けるよ"))
        assertNull(KanaMode.detect("ひらがなで"))
        assertNull(KanaMode.detect("普通の文章です。"))
    }

    @Test fun normalizesScript() {
        assertEquals("きょうは6じ。", KanaMode.normalize(KanaMode.Kind.HIRAGANA, "キョウハ6ジ。"))
        assertEquals("アイスクリーム", KanaMode.normalize(KanaMode.Kind.KATAKANA, "あいすくりーむ"))
        assertTrue(KanaMode.hasKanji("今日"))
    }
}
