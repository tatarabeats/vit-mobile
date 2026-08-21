package com.shunp.vitmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PendingRecTest {
    @Test
    fun jsonRoundTrip() {
        val state = PendingRec.State(
            path = "/data/user/0/com.shunp.vitmobile/cache/vit_1.m4a",
            startMs = 1000L,
            durationMs = 2500L,
            amps = listOf(100, 2000, 8000),
            retries = 1
        )
        val parsed = PendingRec.fromJson(PendingRec.toJson(state))
        assertNotNull(parsed)
        assertEquals(state, parsed)
    }

    @Test
    fun blankPathIsRejected() {
        assertNull(PendingRec.fromJson("""{"path":"","startMs":1}"""))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PendingRec.fromJson("not-json"))
    }
}
