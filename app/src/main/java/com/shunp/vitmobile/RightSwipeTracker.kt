package com.shunp.vitmobile

import kotlin.math.abs

/** Coordinates and threshold use the same unit (pixels in the service). */
internal class RightSwipeTracker(private val threshold: Float) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    fun begin(x: Float, y: Float, screenWidth: Float = 0f) {
        startX = x
        startY = y
        // 左のいちばん端から始めた時は追わない（One Hand Operation+ の操作。SwipeEdge）
        tracking = !(screenWidth > 0f && x < screenWidth * SwipeEdge.FRACTION)
    }

    fun progress(x: Float, y: Float): Float {
        if (!tracking) return 0f
        val dx = x - startX
        val dy = abs(y - startY)
        return if (dx > 0f && dx >= 2f * dy) (dx / threshold).coerceIn(0f, 1f) else 0f
    }

    fun end(x: Float, y: Float): Boolean {
        val detected = progress(x, y) >= 1f
        cancel()
        return detected
    }

    fun cancel() { tracking = false }
}
