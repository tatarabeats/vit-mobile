package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationTest {
    @Test fun silenceOnlyOutroIsDropped() {
        assertEquals("", Hallucination.filter("ご視聴ありがとうございました", 0, 500))
        assertEquals("", Hallucination.filter("ご視聴ありがとうございました。", 300, 500))
    }

    @Test fun outroAfterClearSpeechIsStripped() {
        assertEquals(
            "それが実行されなかったのが",
            Hallucination.filter("それが実行されなかったのが、ご視聴ありがとうございました", 5000, 500)
        )
        assertEquals("教えて", Hallucination.filter("教えてご視聴ありがとうございました。", 1200, 500))
    }

    @Test fun outroSpokenClearlyIsKept() {
        assertEquals("ご視聴ありがとうございました", Hallucination.filter("ご視聴ありがとうございました", 1500, 500))
    }

    @Test fun normalSpeechUntouched() {
        assertEquals("視聴者を引きつけたい", Hallucination.filter("視聴者を引きつけたい", 3000, 500))
    }
}
