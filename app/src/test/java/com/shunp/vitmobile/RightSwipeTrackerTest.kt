package com.shunp.vitmobile

import org.junit.Assert.*
import org.junit.Test

class RightSwipeTrackerTest {
    @Test fun thresholdAndDirection() {
        val swipe = RightSwipeTracker(80f)
        swipe.begin(300f, 400f)
        assertEquals(0.5f, swipe.progress(340f, 410f), 0.001f)
        assertFalse(swipe.end(379f, 400f))
        swipe.begin(300f, 400f)
        assertTrue(swipe.end(380f, 440f)) // equality is allowed at 2:1
        assertFalse(swipe.end(500f, 400f)) // one detection per gesture
    }

    @Test fun rejectVerticalLeftAndDiagonal() {
        val swipe = RightSwipeTracker(80f)
        for ((x, y) in listOf(300f to 700f, 100f to 400f, 380f to 441f)) {
            swipe.begin(300f, 400f)
            assertEquals(0f, swipe.progress(x, y), 0f)
            assertFalse(swipe.end(x, y))
        }
    }

    @Test fun cancelAndReversalUseReleasePosition() {
        val swipe = RightSwipeTracker(80f)
        swipe.begin(300f, 400f)
        assertEquals(1f, swipe.progress(600f, 400f), 0f)
        assertFalse(swipe.end(320f, 400f))
        swipe.begin(300f, 400f)
        swipe.cancel() // multi-touch / CANCEL / target change
        assertEquals(0f, swipe.progress(600f, 400f), 0f)
        assertFalse(swipe.end(600f, 400f))
        swipe.begin(300f, 400f)
        assertTrue(swipe.end(600f, 400f))
    }
}
