package com.shunp.vitmobile

import org.junit.Assert.*
import org.junit.Test

class GeteventSwipeParserTest {
    private val device = TouchDevice("/dev/input/event6", "sec_touchscreen", 4095, 4095)
    private val portrait = SwipeDisplay(1080, 2340, 0)

    private class Stream(device: TouchDevice) {
        val parser = GeteventSwipeParser(device)
        var time = 1000L
        var display = SwipeDisplay(1080, 2340, 0)
        var eligible = true
        fun event(code: String, value: Int = 0): Boolean = parser.accept(
            "${if (code.startsWith("SYN")) "EV_SYN" else "EV_ABS"} $code ${value.toUInt().toString(16).padStart(8, '0')}",
            time, display, eligible)
        fun down(x: Int = 1000, y: Int = 1000, slot: Int = 0) {
            event("ABS_MT_SLOT", slot)
            event("ABS_MT_TRACKING_ID", slot + 1)
            event("ABS_MT_POSITION_X", x)
            event("ABS_MT_POSITION_Y", y)
            event("SYN_REPORT")
        }
        fun move(x: Int, y: Int = 1000): Boolean {
            event("ABS_MT_POSITION_X", x)
            event("ABS_MT_POSITION_Y", y)
            return event("SYN_REPORT")
        }
        fun up(): Boolean {
            event("ABS_MT_TRACKING_ID", -1)
            return event("SYN_REPORT")
        }
        /** なぞりの途中か、離した時のどちらかで判定が出たか（途中で決まる場合がある） */
        fun finish(x: Int, y: Int = 1000): Boolean {
            val mid = move(x, y)
            val end = up()
            return mid || end
        }
    }

    @Test fun discoversTouchscreenInsteadOfOtherInputAndDifferentEventNumber() {
        val listing = """
            add device 1: /dev/input/event6
              name: "buttons"
              ABS_MT_POSITION_X : value 0, min 0, max 4095, fuzz 0
              ABS_MT_POSITION_Y : value 0, min 0, max 4095, fuzz 0
            add device 2: /dev/input/event12
              name: "vendor_TOUCHSCREEN"
              ABS_MT_POSITION_X : value 0, min 0, max 8191, fuzz 0
              ABS_MT_POSITION_Y : value 0, min 0, max 16383, fuzz 0
        """.trimIndent()
        assertEquals(TouchDevice("/dev/input/event12", "vendor_TOUCHSCREEN", 8191, 16383), TouchDeviceDiscovery.parse(listing))
    }

    @Test fun discoveryAcceptsNumericAxisLabelsAndRejectsIncompleteOrZeroAxes() {
        val listing = """
            add device 1: /dev/input/event2
              name: "touchscreen"
              0035 : value 0, min 0, max 4095, fuzz 0
              0036 : value 0, min 0, max 4095, fuzz 0
        """.trimIndent()
        assertEquals(4095, TouchDeviceDiscovery.parse(listing)?.maxY)
        assertNull(TouchDeviceDiscovery.parse(listing.replace("0036", "0037")))
        assertNull(TouchDeviceDiscovery.parse(listing.replace("max 4095", "max 0")))
    }

    @Test fun twelvePercentThresholdOnLiftAfterEarlyWindow() {
        // 途中判定は 450ms まで。それを過ぎたら離した時に 12% で判定する
        val s = Stream(device)
        s.down()
        s.time += 500
        assertFalse(s.move(1491)) // 11.99% of raw width
        assertFalse(s.up())
        s.time += 1000
        s.down()
        s.time += 500
        assertFalse(s.move(1492))
        assertTrue(s.up())
        assertFalse(s.event("SYN_REPORT"))
    }

    @Test fun verticalRatioUsesScreenPixelsNotRawAxisRatio() {
        val s = Stream(device)
        s.down()
        s.time += 300
        assertFalse(s.finish(1600, 1200)) // dx=158px, dy=114px, despite raw dx/dy=3
    }

    @Test fun verticalAndShortMovementsDoNotFire() {
        for ((x, y) in listOf(1010 to 1800, 1200 to 1000)) {
            val s = Stream(device)
            s.down()
            s.time += 100
            assertFalse(s.finish(x, y))
        }
    }

    @Test fun durationBoundary() {
        for (duration in listOf(700L, 701L)) {
            val s = Stream(device)
            s.down()
            s.time += duration
            assertEquals(duration == 700L, s.finish(1700))
        }
    }

    @Test fun secondFingerCancelsUntilAllFingersLift() {
        val s = Stream(device)
        s.down()
        s.down(2000, 2000, slot = 1)
        s.up()
        s.event("ABS_MT_SLOT", 0)
        assertFalse(s.finish(1800))
        s.time += 1000
        s.down()
        assertTrue(s.finish(1800))
    }

    @Test fun secondFingerAppearingAndLeavingInOneReportStillCancels() {
        val s = Stream(device)
        s.down()
        s.event("ABS_MT_SLOT", 1)
        s.event("ABS_MT_TRACKING_ID", 2)
        s.event("ABS_MT_TRACKING_ID", -1)
        s.event("ABS_MT_SLOT", 0)
        assertFalse(s.finish(1800))
    }

    @Test fun cooldownAcrossTouches() {
        val s = Stream(device)
        s.down()
        assertTrue(s.finish(1800))
        s.time += 799
        s.down()
        assertFalse(s.finish(1800))
        s.time += 1
        s.down()
        assertTrue(s.finish(1800))
    }

    @Test fun ignoresLandscapeAndOtherForegroundAndChangeMidGesture() {
        val s = Stream(device)
        s.display = SwipeDisplay(2340, 1080, 1)
        s.down()
        assertFalse(s.finish(1800))
        s.display = portrait
        s.eligible = false
        s.down()
        s.eligible = true
        assertFalse(s.finish(1800))
        s.down()
        s.eligible = false
        assertFalse(s.move(1500))
        s.eligible = true
        assertFalse(s.finish(1800))
    }

    @Test fun leftSwipeIsDetectedAsCloseDirection() {
        val s = Stream(device)
        s.down(3000)
        assertTrue(s.finish(2000))
        assertEquals(-1, s.parser.lastDirection)
    }

    @Test fun rightSwipeDecidesMidGesture() {
        val s = Stream(device)
        s.down(1000)
        s.time += 120
        assertTrue(s.move(1500))
        assertEquals(1, s.parser.lastDirection)
        assertFalse(s.up())
    }

    @Test fun verticalScrollDriftingRightAtTheEndDoesNotOpen() {
        val s = Stream(device)
        s.down(1000, 2000)
        s.time += 60
        assertFalse(s.move(1050, 1600))
        s.time += 60
        assertFalse(s.move(1100, 1200))
        s.time += 60
        assertFalse(s.finish(1600, 1150))
    }

    @Test fun reversePortraitTransformsDirection() {
        val s = Stream(device)
        s.display = SwipeDisplay(1080, 2340, 2)
        s.down(2000)
        assertTrue(s.finish(1400))
    }

    @Test fun retainedAxisValuesSupportKernelSuppressingUnchangedCoordinate() {
        val s = Stream(device)
        s.down()
        s.up()
        s.event("ABS_MT_TRACKING_ID", 4)
        s.event("ABS_MT_POSITION_X", 1100) // Y=1000 unchanged, no new Y event
        s.event("SYN_REPORT")
        assertTrue(s.finish(1700))
    }

    @Test fun timestampedAndDevicePrefixedLinesUseKernelTime() {
        val p = GeteventSwipeParser(device)
        fun e(t: String, code: String, value: String = "00000000") = p.accept(
            "[ $t] /dev/input/event6: ${if (code.startsWith("SYN")) "EV_SYN" else "EV_ABS"} $code $value",
            2000, portrait, true)
        e("1.400000", "ABS_MT_TRACKING_ID", "00000001")
        e("1.400000", "ABS_MT_POSITION_X", "000003e8")
        e("1.400000", "ABS_MT_POSITION_Y", "000003e8")
        e("1.400000", "SYN_REPORT")
        e("1.900000", "ABS_MT_POSITION_X", "00000708")
        e("1.900000", "ABS_MT_TRACKING_ID", "ffffffff")
        assertTrue(e("1.900000", "SYN_REPORT"))
    }

    @Test fun staleKernelEventsDoNotReplaySwipes() {
        val p = GeteventSwipeParser(device)
        for (line in listOf("EV_ABS ABS_MT_TRACKING_ID 00000001", "EV_ABS ABS_MT_POSITION_X 000003e8",
                "EV_ABS ABS_MT_POSITION_Y 000003e8", "EV_SYN SYN_REPORT 00000000",
                "EV_ABS ABS_MT_POSITION_X 00000708", "EV_ABS ABS_MT_TRACKING_ID ffffffff",
                "EV_SYN SYN_REPORT 00000000")) {
            assertFalse(p.accept("[ 1.000000] $line", 3000, portrait, true))
        }
    }

    @Test(expected = IllegalStateException::class) fun lostEventsRequireResync() {
        Stream(device).event("SYN_DROPPED")
    }

    @Test fun earlyFireThenSecondFingerDoesNotFireAgain() {
        val s = Stream(device)
        s.down()
        s.time += 100
        assertTrue(s.move(1800))
        s.time += 100
        s.down(2000, 2000, 1)
        s.up()
        s.event("ABS_MT_SLOT", 0)
        assertFalse(s.up())
    }

    @Test fun holdingAfterCrossingThresholdTimesOut() {
        val s = Stream(device)
        s.down()
        s.time += 100
        assertFalse(s.move(1800))
        s.time += 601
        assertFalse(s.up())
    }

    @Test fun rotationDuringTouchCancelsWholeGesture() {
        val s = Stream(device)
        s.down()
        s.move(1500)
        s.display = SwipeDisplay(1080, 2340, 2)
        assertFalse(s.finish(1800))
    }

    @Test fun differentAxisRangesStillUseTwelvePercentOfWidth() {
        val s = Stream(TouchDevice("/dev/input/event9", "touchscreen", 8191, 16383))
        s.down()
        assertFalse(s.finish(1982))
        s.down()
        assertTrue(s.finish(1983))
    }

    @Test fun missingInitialAxisDoesNotInventAStartPoint() {
        val s = Stream(device)
        s.event("ABS_MT_TRACKING_ID", 1)
        s.event("ABS_MT_POSITION_X", 1000)
        s.event("SYN_REPORT")
        s.event("ABS_MT_POSITION_X", 1800)
        assertFalse(s.up())
    }

    @Test fun framesFromAnAlreadyHeldFingerWithoutTrackingStartAreIgnored() {
        val s = Stream(device)
        assertFalse(s.move(1000))
        assertFalse(s.finish(1800))
    }
}
