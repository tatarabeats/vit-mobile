package com.shunp.vitmobile

/**
 * Whisper の無音時幻覚フィルタ。PC版 (app_core.py) のロジックを移植。
 * 駿平が本当に「ご視聴ありがとうございました」と発話した場合は durationMs >= 800 で残す。
 */
object Hallucination {
    private val EXACT = setOf(
        "ご視聴ありがとうございました", "ご視聴ありがとうございます",
        "ご視聴ありがとうございした", "ありがとうございました",
        "ありがとうございます", "チャンネル登録お願いします",
        "チャンネル登録よろしくお願いします", "おやすみなさい",
        "お疲れ様でした", "最後までご視聴いただきありがとうございます",
        "お待ちしております", "お待ちしています",
        "よろしくお願いします", "よろしくお願い致します",
        "次回もよろしくお願いします", "また次回",
        "Thank you for watching", "Thanks for watching",
        "Bye bye", "See you next time", "Bye"
    )

    private val PATTERNS = Regex(
        "ご視聴(?:いただき|くださり)?ありがとうございまし(?:た|て)|" +
        "チャンネル登録(?:と高評価)?(?:を?)?(?:よろしく)?お願い(?:します|致します|いたします)|" +
        "最後まで(?:ご視聴)?(?:いただき)?ありがとう(?:ございます|ございました)?|" +
        "お待ちしており?ます|" +
        "次回もよろしくお願い(?:します|致します)|" +
        "thank you for watching|thanks for watching|see you next|bye\\s*bye",
        RegexOption.IGNORE_CASE
    )

    private val TRAILING = Regex(
        "[。、,.\\s]*(?:ありがとうございました|ありがとうございます|お疲れ様でした|おやすみなさい|お待ちしており?ます|次回もよろしくお願いします)[。.]*$"
    )

    /**
     * @param text Whisper出力テキスト
     * @param durationMs 録音時間ミリ秒。>= 800 なら明確な発話と判定して幻覚フィルタを緩和
     * @return フィルタ後テキスト（全部削除なら空文字列）
     */
    fun filter(text: String, durationMs: Long = 0): String {
        if (text.isBlank()) return text
        val isClearSpeech = durationMs >= 800
        if (isClearSpeech) return text

        val clean = text.trim().trimEnd('。', '.', '、', ',', '！', '!', '？', '?')
        // 完全一致 → 全削除
        if (clean in EXACT) return ""
        // 短文かつ部分一致 → パターン削除して残りを返す
        if (clean.length < 30 && PATTERNS.containsMatchIn(text)) {
            val cleaned = PATTERNS.replace(text, "").trim()
            if (cleaned.isBlank()) return ""
            return TRAILING.replace(cleaned, "").trim()
        }
        // 文末の定型句を削除
        return TRAILING.replace(text, "").trim()
    }
}
