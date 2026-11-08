package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadingTest {
    private val dict = listOf("grok", "グロック", "キオクシア", "ミルトコ")

    @Test fun dictionaryByReading() {
        assertEquals("ミルトコ。", Reading.applyDictionary("見るとこ。", dict))
        assertEquals("ミルトコのスレッドをリモートにして。", Reading.applyDictionary("見るとこのスレッドをリモートにして。", dict))
        assertEquals("ミルトコって言ったら", Reading.applyDictionary("見るとこって言ったら", dict))
        assertEquals("グロックを使う", Reading.applyDictionary("ぐろっくを使う", dict))
    }

    @Test fun dictionaryLeavesOrdinaryWords() {
        assertEquals("どこを見るところか分からない", Reading.applyDictionary("どこを見るところか分からない", dict))
        assertEquals("普通の文章です。", Reading.applyDictionary("普通の文章です。", dict))
    }

    @Test fun katakanaReading() {
        val k = Reading.toKatakana("今日は6時にアラームを鳴らしてみます。")
        assertEquals("キョウハ6ジニアラームヲナラシテミマス。", k)
        assertFalse(KanaMode.hasKanji(Reading.toKatakana("見るとこ")))
    }

    @Test fun kanaSpanStopsAtQuote() {
        val r = KanaMode.detect("カタカナで見るとこって言ったら、カタカナで表示されるように言ったのに")!!
        assertEquals("見るとこ", r.rest)
        assertEquals("って言ったら、カタカナで表示されるように言ったのに", r.tail)
    }
    @Test fun oneWordUnlessAll() {
        assertEquals("アイスクリーム" to "食べたい", Reading.firstPhrase("アイスクリーム食べたい"))
        assertEquals("東京都" to "に行きたい", Reading.firstPhrase("東京都に行きたい"))
        val all = KanaMode.detect("全部カタカナで今日は晴れ")!!
        assertEquals(true, all.all)
        assertEquals("今日は晴れ", all.rest)
        assertEquals(false, KanaMode.detect("カタカナで今日は晴れ")!!.all)
        // 読点の後は命令と見なさない
        assertEquals(null, KanaMode.detect("出版って言った時に、カタカナで出版の表示になっちゃったら"))
    }

}
