package com.shunp.vitmobile

/**
 * LLM補正の安全弁。PC版 (`app_core.py`) と同じ考え方:
 *  - 出力が入力より伸びすぎたら回答モードとみなして捨てる
 *  - 句読点を除いた本文が 8% 以上縮んだら、LLM が本文を削ったとみなして捨てる
 *
 * Android 依存なし。単体テストから直接叩ける。
 */
object LlmGuard {
    /** 本文がこれ未満まで縮んだら捨てる（PC: body_out < body_in * 0.92） */
    const val SHRINK_KEEP_RATIO = 0.92

    /**
     * 意味を持つ文字だけ残す（句読点・記号・空白は数えない）。
     * `。、` の増減で長さ判定が揺れるのを防ぐ。
     */
    fun meaningBody(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (isMeaningChar(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    fun meaningBodyLength(text: String): Int = meaningBody(text).length

    /**
     * 句読点・記号・空白以外。英数字と仮名・漢字（Unicode letter/digit）を残す。
     * 長音「ー」は語の一部なので残す。
     */
    fun isMeaningChar(ch: Char): Boolean {
        if (ch.isLetterOrDigit()) return true
        if (ch == 'ー' || ch == 'ｰ' || ch == '〜' || ch == '～') return true
        return false
    }

    /**
     * 句読点LLMの結果を採用してよいか。
     * @return 捨てる理由。null なら採用。
     */
    fun rejectReasonFix(input: String, output: String): String? {
        if (output.isBlank()) return "blank"
        if (output.length > input.length * 1.5 + 10) return "growth"
        val bodyIn = meaningBodyLength(input)
        val bodyOut = meaningBodyLength(output)
        if (bodyIn > 0 && bodyOut < bodyIn * SHRINK_KEEP_RATIO) {
            return "shrink $bodyOut < $bodyIn * 0.92"
        }
        return null
    }

    fun acceptFix(input: String, output: String): Boolean =
        rejectReasonFix(input, output) == null

    /**
     * 辞書修正の結果を採用してよいか。既存の長さ・文字保存に加え、本文縮みも見る。
     * @return 捨てる理由。null なら採用。
     */
    fun rejectReasonDict(input: String, output: String): String? {
        if (output.isBlank()) return "blank"
        if (output.length > input.length * 1.3 + 5) return "growth"
        val bodyIn = meaningBodyLength(input)
        val bodyOut = meaningBodyLength(output)
        if (bodyIn > 0 && bodyOut < bodyIn * SHRINK_KEEP_RATIO) {
            return "shrink $bodyOut < $bodyIn * 0.92"
        }
        val inputChars = input.toSet()
        val ignorable = "。、,.!?！？…「」（）()・ \n".toSet()
        val newChars = output.count { it !in inputChars && it !in ignorable }
        if (newChars > maxOf(8, (input.length * 0.25).toInt())) return "new-chars"
        val preserved = input.count { it in output }
        if (preserved < input.length * 0.5) return "preserve"
        return null
    }

    fun acceptDict(input: String, output: String): Boolean =
        rejectReasonDict(input, output) == null
}
