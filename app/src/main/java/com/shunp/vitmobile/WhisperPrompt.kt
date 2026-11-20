package com.shunp.vitmobile

/**
 * Whisper に渡す prompt を作る（PC 版 get_prompt と同じ形）。
 *
 * 2026-11-20 駿平さん「カタカナ指定してないのに、カタカナになっちゃう時あるな。特にスマホ版」:
 * スマホは辞書の言葉（grok・グロック・キオクシア・ミルトコ）だけを改行で並べて prompt にしていた。
 * Whisper は prompt の書き方をまねる（OpenAI の Whisper prompting guide）ので、聞き取りにくい所が
 * 「シンゲキノキョジン」「オンセイガナガレル」のように読みのカタカナで出ていた。
 * PC と同じく、直前の発言の終わり（ふつうの漢字かな混じり）を先に置き、辞書の言葉を「、」でつないで後ろに置く。
 * 直前の発言が無い時も、ふつうの日本語の文を先に置く。
 */
object WhisperPrompt {
    const val MAX_CHARS = 80
    private const val PREV_CHARS = 30
    private const val ANCHOR = "こんにちは。今日はいい天気ですね。"

    fun build(prev: String?, dictWords: List<String>): String {
        val p = prev?.trim().orEmpty()
        val head = if (p.isNotEmpty()) p.takeLast(PREV_CHARS) + " " else ANCHOR
        val available = MAX_CHARS - head.length
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (raw in dictWords) {
            val w = raw.trim()
            if (w.isEmpty() || w.length > 30 || !seen.add(w)) continue
            val add = if (sb.isEmpty()) w else "、$w"
            if (sb.length + add.length > available) break
            sb.append(add)
        }
        return (head + sb).takeLast(MAX_CHARS)
    }
}
