package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterruptedRecTest {
    @Test fun freshInterruptedIsTakenOnce() {
        VoiceRecorder.interrupted = "途中まで" to System.currentTimeMillis()
        assertEquals("途中まで", VoiceRecorder.takeInterrupted())
        assertNull(VoiceRecorder.takeInterrupted())
    }

    @Test fun oldInterruptedIsIgnored() {
        VoiceRecorder.interrupted = "古い" to System.currentTimeMillis() - 11 * 60_000L
        assertNull(VoiceRecorder.takeInterrupted())
    }
}
