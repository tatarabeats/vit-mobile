package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmGuardTest {
    @Test
    fun meaningBodyIgnoresPunctuation() {
        assertEquals("ねえ今日の予定教えて", LlmGuard.meaningBody("ねえ、今日の予定教えて。"))
        assertEquals("11は何ですか", LlmGuard.meaningBody("1+1は何ですか？"))
        assertEquals("ラーメン", LlmGuard.meaningBody("ラーメン。"))
    }

    @Test
    fun punctuationOnlyChangeIsAccepted() {
        val input = "ねえ今日の予定教えて"
        val output = "ねえ、今日の予定教えて。"
        assertNull(LlmGuard.rejectReasonFix(input, output))
        assertTrue(LlmGuard.acceptFix(input, output))
    }

    @Test
    fun shrinkOfEightPercentIsRejected() {
        val input = "あいうえおかきくけこさしすせそ" // 15 meaning chars
        val output = "あいうえおかきくけこさしす" // 13 → 13/15 = 0.866 < 0.92
        val reason = LlmGuard.rejectReasonFix(input, output)
        assertNotNull(reason)
        assertTrue(reason!!.startsWith("shrink"))
        assertFalse(LlmGuard.acceptFix(input, output))
    }

    @Test
    fun shrinkJustUnderEightPercentIsKept() {
        val input = "あいうえおかきくけこさしすせそたちつてと" // 20
        val output = "あいうえおかきくけこさしすせそたちつ" // 18 → 0.90 < 0.92, still reject
        assertNotNull(LlmGuard.rejectReasonFix(input, output))
        val kept = "あいうえおかきくけこさしすせそたちつて" // 19 → 0.95 >= 0.92
        assertNull(LlmGuard.rejectReasonFix(input, kept))
    }

    @Test
    fun growthGuardStillApplies() {
        val input = "今日は雨"
        val output = "今日は雨です。はい、承知しました。他にご用件はありますか。ご質問ください。"
        assertEquals("growth", LlmGuard.rejectReasonFix(input, output))
    }

    @Test
    fun dictShrinkAlsoRejected() {
        val input = "庵野孝博は天才だと思うよ"
        val output = "安野"
        val reason = LlmGuard.rejectReasonDict(input, output)
        assertNotNull(reason)
        assertTrue(reason == "shrink ${LlmGuard.meaningBodyLength(output)} < ${LlmGuard.meaningBodyLength(input)} * 0.92" || reason == "preserve" || reason!!.startsWith("shrink"))
    }

    @Test
    fun dictSameLengthNounSwapIsAccepted() {
        val input = "庵野孝博は天才"
        val output = "安野貴博は天才"
        assertNull(LlmGuard.rejectReasonDict(input, output))
    }
}
