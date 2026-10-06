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

    /** これ以上の大きさがあれば、短い声（「はい」「OK」等）でも声とみなす */
    const val LOUD_SHORT = 2500

    /**
     * 送ってよい中身か:
     *  voicedMs < 200 → 捨てる（物音1発）
     *  voicedMs < 350 で、ピークが LOUD_SHORT 未満 → 捨てる
     *    以前は 350ms 未満を一律に捨てていて、「はい」等の短い返事が入らなかった（2026-10-06）
     *  duration ≥ 3000 かつ (voicedMs < 800 または 比率 < 6%) → 捨てる
     */
    fun hasVoice(amps: List<Int>, durationMs: Long): Boolean {
        val usable = amps.filter { it > 0 }
        val threshold = voicedThreshold(usable)
        val peak = usable.maxOrNull() ?: 0
        val voiced = voicedMs(usable, threshold)
        if (peak < AMP_FLOOR) return false
        if (voiced < 200) return false
        if (voiced < 350 && peak < LOUD_SHORT) return false
        if (durationMs >= 3000 && voiced < 800) return false
        if (durationMs >= 3000 && durationMs > 0 && voiced.toFloat() / durationMs < 0.06f) {
            return false
        }
        return true
    }
}
