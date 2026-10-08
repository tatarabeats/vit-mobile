package com.shunp.vitmobile

import kotlin.math.abs

internal data class TouchDevice(val path: String, val name: String, val maxX: Int, val maxY: Int)

internal object TouchDeviceDiscovery {
    fun parse(output: String): TouchDevice? {
        val header = Regex("add device \\d+:\\s*(/dev/input/event\\d+)")
        val name = Regex("name:\\s*\"([^\"]+)\"")
        val maximum = Regex("\\bmax\\s+(\\d+)")
        val minimum = Regex("\\bmin\\s+(-?\\d+)")
        for (block in output.split(Regex("(?=add device \\d+:)"))) {
            val path = header.find(block)?.groupValues?.get(1) ?: continue
            val label = name.find(block)?.groupValues?.get(1) ?: continue
            if (!label.contains("touchscreen", ignoreCase = true)) continue
            fun axis(symbol: String, code: String): Int? {
                val line = block.lineSequence().firstOrNull {
                    it.contains(symbol) || Regex("\\b$code\\s*:").containsMatchIn(it)
                } ?: return null
                // This coordinate transform supports the usual zero-based touch axes only.
                if (minimum.find(line)?.groupValues?.get(1)?.toIntOrNull() != 0) return null
                return maximum.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            }
            val x = axis("ABS_MT_POSITION_X", "0035") ?: continue
            val y = axis("ABS_MT_POSITION_Y", "0036") ?: continue
            return TouchDevice(path, label, x, y)
        }
        return null
    }
}

internal data class SwipeDisplay(val width: Int, val height: Int, val rotation: Int) {
    val portrait: Boolean get() = width > 0 && height > width && (rotation == 0 || rotation == 2)
}

/** Linux type-B multitouch parser. No Android dependency; one instance per event stream. */
internal class GeteventSwipeParser(private val device: TouchDevice) {
    private data class Slot(var active: Boolean = false, var x: Int? = null, var y: Int? = null)
    private val slots = mutableMapOf<Int, Slot>()
    private var currentSlot = 0
    private var trackedSlot: Int? = null
    private var startTime = 0L
    private var startX: Float? = null
    private var startY: Float? = null
    private var maxAbsDy = 0f
    private var minDx = 0f
    /** 直前の1回のなぞりの縦横の動き（記録用） */
    var lastGesture: String? = null
    private var startDisplay: SwipeDisplay? = null
    private var blocked = false
    private var lifted = false
    private var lastFire: Long? = null
    private val event = Regex("\\b(EV_ABS|EV_SYN)\\s+(\\w+)\\s+([0-9a-fA-F]+)\\s*$")
    private val timestamp = Regex("\\[\\s*(\\d+)\\.(\\d+)\\]")

    fun accept(line: String, now: Long, display: SwipeDisplay, eligible: Boolean): Boolean {
        val match = event.find(line) ?: return false
        val stamp = timestamp.find(line)
        val time = if (stamp == null) now else {
            val seconds = stamp.groupValues[1].toLongOrNull() ?: return false
            seconds * 1000 + stamp.groupValues[2].padEnd(3, '0').take(3).toLong()
        }
        if (!eligible || !display.portrait || (startDisplay != null && startDisplay != display)
            || now - time > 700 || time > now + 100) blocked = true
        val code = match.groupValues[2]
        val value = match.groupValues[3].toLongOrNull(16) ?: return false
        when (code) {
            "SYN_DROPPED" -> {
                // Slot state is unknowable after overflow; restart getevent and discovery.
                throw IllegalStateException("getevent SYN_DROPPED")
            }
            "ABS_MT_SLOT" -> {
                if (value !in 0L..255L) throw IllegalStateException("invalid touch slot")
                currentSlot = value.toInt()
            }
            "ABS_MT_TRACKING_ID" -> {
                val slot = slots.getOrPut(currentSlot) { Slot() }
                if (value == 0xffffffffL) {
                    slot.active = false
                    if (trackedSlot == currentSlot) lifted = true
                } else {
                    if (slots.values.any { it.active } || trackedSlot != null) blocked = true
                    else {
                        trackedSlot = currentSlot
                        startTime = time
                        startDisplay = display
                        startX = null
                        startY = null
                        lifted = false
                    }
                    slot.active = true
                }
            }
            "ABS_MT_POSITION_X" -> slots.getOrPut(currentSlot) { Slot() }.x = value.toInt()
            "ABS_MT_POSITION_Y" -> slots.getOrPut(currentSlot) { Slot() }.y = value.toInt()
            "SYN_REPORT" -> {
                var detected = false
                val slot = trackedSlot?.let { slots[it] }
                val rawX = slot?.x
                val rawY = slot?.y
                if (!blocked && rawX != null && rawY != null
                    && rawX in 0..device.maxX && rawY in 0..device.maxY) {
                    val x = (if (display.rotation == 2) device.maxX - rawX else rawX)
                        .toFloat() / device.maxX * display.width
                    val y = (if (display.rotation == 2) device.maxY - rawY else rawY)
                        .toFloat() / device.maxY * display.height
                    if (startX == null) { startX = x; startY = y; maxAbsDy = 0f; minDx = 0f }
                    val dx = x - startX!!
                    val dy = y - startY!!
                    // 途中の縦の動きも見る。上下スクロールの最後に指が右へ流れただけで開いていた（2026-10-08）
                    if (abs(dy) > maxAbsDy) maxAbsDy = abs(dy)
                    if (dx < minDx) minDx = dx
                    if (lifted && slots.values.none { it.active }) {
                        val ok = time - startTime in 0L..700L
                            && dx >= display.width * 0.12f
                            // 実測: 素早い右スワイプ dx=198/maxDy=69、上下スクロール dx=73/maxDy=302（2026-10-08）
                            && dx >= maxAbsDy * 2.5f
                            && maxAbsDy < display.height * 0.06f
                            && minDx > -display.width * 0.03f
                            && (lastFire == null || time - lastFire!! >= 800)
                        lastGesture = "dx=${dx.toInt()} maxDy=${maxAbsDy.toInt()} ms=${time - startTime} ok=$ok"
                        if (ok) {
                            detected = true
                            lastFire = time
                            blocked = true
                        }
                    }
                }
                if (slots.values.none { it.active }) {
                    trackedSlot = null
                    startDisplay = null
                    startX = null
                    startY = null
                    lifted = false
                    blocked = false
                } else if (lifted) blocked = true
                return detected
            }
        }
        return false
    }
}
