package com.shunp.vitmobile

/**
 * Whisper（large-v3, language=ja）が、日本語の文のすぐ後ろに同じ内容をローマ字で書いた文と、
 * 同じ日本語の文をもう一度出すことがある（2026-11-21 16:36「酒みりん…大変だな。 sake mirin、sato wo yoi…。酒みりん…」）。
 * 文に分けて、かな・漢字が1字も無く言葉のほとんどがローマ字の音（sa / shi / tsu / n …）だけの文を捨て、
 * すぐ前と同じ文の繰り返しも捨てる。英語（YouTube・hello など）はローマ字の音にならないので残る。
 */
object RomajiFilter {
    private val SYLLABLES = Regex(
        "^(?:(?:ky|gy|sh|ch|ny|hy|by|py|my|ry|ts|[kgsztdnhbpmyrwfjv])?[aiueo]|n|[kgsztdcpbhmr](?=[kgsztdcpbhmr]))+$"
    )
    // 文の切れ目: 「。」などの後ろ／日本語の直後の空白で英字が始まる所
    private val BOUNDARY = Regex(
        "(?<=[。．！？!?])\\s*|(?<=[\\u3040-\\u30ff\\u4e00-\\u9fff])\\s+(?=[A-Za-z])"
    )

    private fun hasJapanese(s: String) = s.any {
        it in '぀'..'ヿ' || it in '一'..'鿿' || it in '㐀'..'䶿'
    }

    /** かな・漢字が無く、3語以上で、言葉の 7 割以上がローマ字の音だけの文 */
    fun isRomaji(piece: String): Boolean {
        if (hasJapanese(piece)) return false
        val words = piece.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.size < 3) return false
        val hit = words.count { SYLLABLES.matches(it) }
        return hit * 10 >= words.size * 7
    }

    private fun norm(s: String) = s.filter { !it.isWhitespace() && it !in "。、,.!！?？・，．" }

    /** 日本語が入っている書き起こしだけ、ローマ字の文と直前と同じ文を捨てる */
    fun filterText(text: String): String {
        if (!hasJapanese(text)) return text
        val pieces = ArrayList<Pair<String, String>>()  // 文, 後ろの区切り
        var start = 0
        for (m in BOUNDARY.findAll(text)) {
            if (m.range.first < start) continue
            val end = m.range.last + 1
            if (m.range.first == start && end == start) continue
            pieces.add(text.substring(start, m.range.first) to text.substring(m.range.first, end))
            start = end
        }
        if (start < text.length) pieces.add(text.substring(start) to "")
        val sb = StringBuilder()
        var last: String? = null
        var pending = ""      // 前に残した文の後ろの区切り。間の文を捨てた時は使わない
        var dropped = false
        for ((p, sep) in pieces) {
            val n = norm(p)
            if (p.isNotBlank() && (isRomaji(p) || (n.isNotEmpty() && n == last))) { dropped = true; continue }
            if (!dropped) sb.append(pending)
            sb.append(p)
            pending = sep
            dropped = false
            if (n.isNotEmpty()) last = n
        }
        return sb.toString().trim()
    }
}
