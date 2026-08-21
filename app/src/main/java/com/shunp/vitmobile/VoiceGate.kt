package com.shunp.vitmobile

/**
 * 録音を Groq に送ってよいかのゲート。
 * PC版は `threshold = max(SILENCE_TRIM_RMS, percentile95(frame_rms) * 0.22)` で
 * 無音を切る。Android は m4a を PCM トリムできないので、同じ式で
 * 「声のコマ」を数えて送る／捨てるを決める。
 *
 * 固定 2200 は部屋の物音と重なり、黙っているのに Whisper へ渡す原因になる。
 * Android 依存なし。単体テストから直接叩ける。
 */
object VoiceGate {
    /** これ未満はデジタル無音扱い（PC の SILENCE_TRIM_RMS 相当の床） */
    const val AMP_FLOOR = 400
    /** PC: speech_ref * 0.22 */
    const val P95_RATIO = 0.22
    const val FRAME_MS = 100L

    fun percentile95(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * 0.95).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    /** このセッションの振幅から「声」と数える閾値を決める */
    fun voicedThreshold(amps: List<Int>): Int {
        val usable = amps.filter { it > 0 }
        if (usable.isEmpty()) return AMP_FLOOR
        val p95 = percentile95(usable)
        return maxOf(AMP_FLOOR, (p95 * P95_RATIO).toInt())
    }

    fun voicedMs(amps: List<Int>, threshold: Int = voicedThreshold(amps)): Long {
        val usable = amps.filter { it > 0 }
        return usable.count { it > threshold } * FRAME_MS
    }

    /**
     * 送ってよい中身か。時間ゲートは従来どおり:
     *  voicedMs < 350 → 捨てる
     *  duration ≥ 3000 かつ (voicedMs < 800 または 比率 < 6%) → 捨てる
     */
    fun hasVoice(amps: List<Int>, durationMs: Long): Boolean {
        val usable = amps.filter { it > 0 }
        val threshold = voicedThreshold(usable)
        val peak = usable.maxOrNull() ?: 0
        val voiced = voicedMs(usable, threshold)
        if (peak < AMP_FLOOR) return false
        if (voiced < 350) return false
        if (durationMs >= 3000 && voiced < 800) return false
        if (durationMs >= 3000 && durationMs > 0 && voiced.toFloat() / durationMs < 0.06f) {
            return false
        }
        return true
    }
}
