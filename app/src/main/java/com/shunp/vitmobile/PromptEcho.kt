package com.shunp.vitmobile

/**
 * Whisper が prompt（直前の発言の終わり＋辞書の言葉）をそのまま書き出した所を捨てる。
 *
 * 2026-11-23 駿平さん「長文喋った後、辞書登録した単語をただ羅列して勝手に貼り付けられるみたいなのが、結構頻発してる」:
 * 履歴に「…イメージしてるね。grok、グロック、キオクシア、ミルトコ」「…どうにかして。 grok、グロック、キオクシア、ミルトコ」。
 * 雑音だけの音（3秒）を今の prompt で送ると、Whisper が「グロック、キオクシア、ミルトコ」を
 * no_speech 0.02・avg_logprob -0.36（自信あり）で返すのを実測で確かめた。今までの区間フィルタでは落ちない。
 * 辞書の言葉が1つだけの区間は、本当に言った可能性があるので残す。
 */
object PromptEcho {
    private val SEP = Regex("[、,，。.．・\\s]+")

    private fun norm(s: String) = s.replace(SEP, "")

    private fun words(dictWords: List<String>) =
        dictWords.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** 区間の文が、辞書の言葉2つ以上を区切りで並べただけ、または prompt の一部（6文字以上）をそのまま写した物なら true */
    fun isEcho(segText: String, dictWords: List<String>, prompt: String?): Boolean {
        val body = norm(segText)
        if (body.isEmpty()) return false
        val dict = words(dictWords)
        val tokens = segText.split(SEP).filter { it.isNotEmpty() }
        if (tokens.size >= 2 && tokens.all { it in dict }) return true
        if (prompt != null && body.length >= 6 && norm(prompt).contains(body)) return true
        return false
    }

    /** 文の終わりに付いた「grok、グロック、キオクシア、ミルトコ」のような辞書の言葉の並び（2つ以上）を切り落とす */
    fun stripTrailing(text: String, dictWords: List<String>): String {
        val dict = words(dictWords).sortedByDescending { it.length }
        if (dict.isEmpty()) return text
        var end = text.length
        var cut = text.length
        var count = 0
        while (true) {
            var e = end
            while (e > 0 && SEP.matches(text[e - 1].toString())) e--
            val w = dict.firstOrNull { e - it.length >= 0 && text.regionMatches(e - it.length, it, 0, it.length) } ?: break
            val start = e - w.length
            // 区切りの無い所（「ニュースグロック」など、言葉の途中）では止める
            if (start > 0 && !SEP.matches(text[start - 1].toString())) {
                // 最初の1語目の前は本文。本文の直後に区切り無しで付くことは無いので止める
                break
            }
            count++
            cut = start
            end = start
        }
        if (count < 2) return text
        return text.substring(0, cut).trimEnd { it.isWhitespace() || it in "、,，" }
    }
}
