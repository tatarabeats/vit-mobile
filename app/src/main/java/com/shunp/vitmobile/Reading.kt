package com.shunp.vitmobile

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * 日本語の読み（Kuromoji）。PC 版が Sudachi でしていることをスマホでもする（2026-11-06）。
 * - 「カタカナで」「ひらがなで」の変換を、AI に頼らず辞書の読みで行う
 * - 辞書のカタカナ・ひらがなの言葉と読みが同じ部分を、辞書の書き方に直す（「見るとこ」→「ミルトコ」）
 */
object Reading {
    @Volatile private var tokenizer: Tokenizer? = null

    @Synchronized
    private fun tok(): Tokenizer = tokenizer ?: Tokenizer().also { tokenizer = it }

    /** 最初の読み込み（1〜2秒）を、録音を始めた時などに裏で済ませておく */
    fun warm() {
        if (tokenizer == null) Thread({ runCatching { tok() } }, "vit-reading").apply { isDaemon = true }.start()
    }

    private val KANA = Regex("[ぁ-ゖァ-ヺー]+")
    private val HAS_JA_OR_LATIN = Regex("[ぁ-ゖァ-ヺー一-鿿㐀-䶿A-Za-z]")

    /** 文全体を、読みのとおりのカタカナにする。記号・数字はそのまま */
    fun toKatakana(text: String): String = tok().tokenize(text).joinToString("") { t ->
        val surf = t.surface
        val read = t.reading
        when {
            !HAS_JA_OR_LATIN.containsMatchIn(surf) -> surf
            read.isNullOrBlank() || read == "*" -> KanaMode.toKatakana(surf)
            else -> read
        }
    }

    private data class Piece(val surf: String, val read: String, val last: Boolean)

    private fun pieces(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        for (t in tok().tokenize(text)) {
            val surf = t.surface
            val read = KanaMode.toHiragana(t.reading?.takeIf { it != "*" } ?: surf)
            if (KANA.matches(surf) && KanaMode.toHiragana(surf).length == read.length) {
                // かなだけの部分は1文字ずつ（「見る|とこの」のように言葉の途中で切れても合わせられるように）
                surf.forEachIndexed { k, c -> out.add(Piece(c.toString(), read[k].toString(), k == surf.length - 1)) }
            } else out.add(Piece(surf, read, true))
        }
        return out
    }

    private const val PARTICLES = "のはがをにでともへやっ"

    /** 辞書のかなの言葉（3文字以上）と読みが同じ部分を、辞書の書き方に直す */
    fun applyDictionary(text: String, words: List<String>): String {
        val targets = words.map { it.trim() }.filter { it.length >= 3 && KANA.matches(it) }
            .associateBy { KanaMode.toHiragana(it) }
        if (text.isBlank() || targets.isEmpty()) return text
        val ps = try { pieces(text) } catch (_: Throwable) { return text }
        val sb = StringBuilder()
        var i = 0
        while (i < ps.size) {
            var hit: Pair<Int, String>? = null
            for (n in minOf(10, ps.size - i) downTo 1) {
                val read = (i until i + n).joinToString("") { ps[it].read }
                var w = targets[read] ?: continue
                val surf = (i until i + n).joinToString("") { ps[it].surf }
                // 言葉の途中で終わる時は、続きが助詞の時だけ（「見るとこの」は直す、「見るところ」は直さない）
                if (!ps[i + n - 1].last && (i + n >= ps.size || ps[i + n].surf !in PARTICLES.map { it.toString() })) continue
                if (surf == w) break
                hit = n to w
                break
            }
            if (hit != null) {
                sb.append(hit.second)
                i += hit.first
            } else {
                sb.append(ps[i].surf)
                i++
            }
        }
        return sb.toString()
    }
}
