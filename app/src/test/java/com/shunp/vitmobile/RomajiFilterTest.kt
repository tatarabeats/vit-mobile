package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiFilterTest {
    @Test
    fun dropsRomajiAndRepeat() {
        val raw = "酒みりん、砂糖を用意しないといけないのが大変だな。 sake mirin、sato wo yoi shi nai to ike nai no ga taihen da na。" +
            "酒みりん、砂糖を用意しないといけないのが大変だな。てかみりんって酒と砂糖を混ぜたものではないのか。 " +
            "teka mirin te sake to sato wo maze ta mono de wa nai no ka。最低限にしたやつが欲しいな、レシピを。 " +
            "saiteigen ni shita yatsu ga hoshii na, resipiu"
        assertEquals(
            "酒みりん、砂糖を用意しないといけないのが大変だな。てかみりんって酒と砂糖を混ぜたものではないのか。" +
                "最低限にしたやつが欲しいな、レシピを。",
            RomajiFilter.filterText(raw)
        )
    }

    @Test
    fun keepsEnglishAndNormalText() {
        val a = "YouTube の動画を見た。 Hello world, this is a test。それで終わり。"
        assertEquals(a, RomajiFilter.filterText(a))
        val b = "今日は雨。明日も雨。"
        assertEquals(b, RomajiFilter.filterText(b))
        val c = "sake mirin to sato"  // 日本語が無い時は触らない
        assertEquals(c, RomajiFilter.filterText(c))
    }
}
