package com.shunp.vitmobile

import org.junit.Assert.*
import org.junit.Test

class SecureAppModeTest {
    private class Fake : SecureAppMode.Port {
        var clock = 0L
        var permission = true
        var alive = false
        var saveOk = true
        var writeOk = true
        var settings = DebugSettings(1, 1, 1)
        var journal: SecureAppState? = null
        val broadcasts = mutableListOf<Boolean>()
        val writes = mutableListOf<DebugSettings>()
        val events = mutableListOf<String>()
        private val queue = mutableListOf<Pair<Long, () -> Unit>>()
        override fun hasPermission() = permission
        override fun binderAlive() = alive
        override fun readSettings() = settings
        override fun writeSettings(values: DebugSettings): Boolean {
            events += "write"
            writes += values
            if (writeOk) settings = values
            return writeOk
        }
        override fun save(state: SecureAppState?): Boolean {
            events += "save"
            if (saveOk) journal = state
            return saveOk
        }
        override fun broadcast(start: Boolean) { events += if (start) "start" else "stop"; broadcasts += start }
        override fun now() = clock
        override fun later(delay: Long, action: () -> Unit) { queue += (clock + delay) to action }
        override fun log(message: String) {}
        fun advance(ms: Long) {
            val until = clock + ms
            while (true) {
                val next = queue.minByOrNull { it.first } ?: break
                if (next.first > until) break
                queue.remove(next)
                clock = next.first
                next.second()
            }
            clock = until
        }
        fun killProcess() { queue.clear() }
    }

    @Test fun commitsThenDisablesImmediatelyThenStops() {
        // カードアプリは起動直後に見るので、待たずにすぐオフにしてから Shizuku を止める
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
        assertEquals("save", p.events.first())
        assertEquals("stop", p.events.last())
        assertEquals(DebugSettings(1, 1, 1), p.journal!!.original)
    }

    @Test fun neverStopsUntilGrantAndJournalCommitSucceed() {
        val p = Fake().apply { permission = false }
        val m = SecureAppMode(p, null)
        m.foreground(true)
        assertTrue(p.broadcasts.isEmpty())
        p.permission = true
        p.saveOk = false
        m.permissionAvailable()
        assertTrue(p.broadcasts.isEmpty())
        p.saveOk = true
        m.permissionAvailable()
        assertEquals(listOf(false), p.broadcasts)
    }

    @Test fun protectedTransitionsAndIgnoredWindowsDoNotToggle() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        repeat(4) { m.foreground(true); m.foreground(null); p.advance(2_000) }
        assertEquals(listOf(false), p.broadcasts)
        assertEquals(1, p.writes.size)
    }

    @Test fun leaveDebounceIsNotExtendedByRepeatedWindowEvents() {
        val p = Fake().apply { settings = DebugSettings(1, 0, 1) }
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(1_000)
        m.foreground(false)
        p.advance(499)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
        p.advance(1)
        assertEquals(DebugSettings(1, 0, 1), p.settings)
        p.advance(1_999)
        assertEquals(listOf(false), p.broadcasts)
        p.advance(1)
        assertEquals(listOf(false, true), p.broadcasts)
    }

    @Test fun returnToProtectedAppCancelsPendingLeave() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(1_499)
        m.foreground(true)
        p.advance(20_000)
        assertEquals(listOf(false), p.broadcasts)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
    }

    @Test fun reentryDuringStartDelayCancelsStartAndRetainsOriginalSnapshot() {
        val p = Fake().apply { settings = DebugSettings(1, 1, 0) }
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(1_500)
        m.foreground(true)
        p.advance(20_000)
        assertEquals(listOf(false, false), p.broadcasts)
        assertEquals(DebugSettings(1, 1, 0), p.journal!!.original)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
    }

    @Test fun onlyOneStartRetryAndLateBinderStillCompletes() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(3_500)
        p.advance(9_999)
        assertEquals(1, p.broadcasts.count { it })
        p.advance(1)
        assertEquals(2, p.broadcasts.count { it })
        repeat(5) { m.foreground(false); p.advance(20_000) }
        assertEquals(2, p.broadcasts.count { it })
        assertNotNull(p.journal)
        p.alive = true
        m.binderReceived()
        assertNull(p.journal)
    }

    @Test fun crashDuringSecureRecoversWithoutShizukuPermissionOrBinder() {
        val p = Fake().apply { settings = DebugSettings(1, 0, 1) }
        SecureAppMode(p, null).foreground(true)
        p.advance(1_000)
        p.killProcess()
        val m = SecureAppMode(p, p.journal)
        m.foreground(null)
        p.advance(5_000)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
        m.foreground(false)
        p.advance(1_500)
        assertEquals(DebugSettings(1, 0, 1), p.settings)
        p.advance(2_000)
        assertEquals(1, p.broadcasts.count { it })
    }

    @Test fun crashWhileStillInProtectedAppNeverSnapshotsDisabledValues() {
        val p = Fake()
        SecureAppMode(p, null).foreground(true)
        p.advance(1_000)
        p.killProcess()
        val m = SecureAppMode(p, p.journal)
        m.foreground(true)
        p.advance(1_000)
        assertEquals(DebugSettings(1, 1, 1), p.journal!!.original)
        assertFalse(p.broadcasts.any { it })
    }

    @Test fun retryCountSurvivesProcessDeath() {
        val p = Fake()
        var m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(3_500)
        assertEquals(1, p.journal!!.startAttempts)
        p.killProcess()
        m = SecureAppMode(p, p.journal)
        m.foreground(false)
        p.advance(9_999)
        assertEquals(1, p.broadcasts.count { it })
        p.advance(1)
        assertEquals(2, p.broadcasts.count { it })
        p.killProcess()
        m = SecureAppMode(p, p.journal)
        m.foreground(false)
        p.advance(30_000)
        assertEquals(2, p.broadcasts.count { it })
    }

    @Test fun partialRestoreFailureKeepsJournalAndDoesNotStart() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        p.writeOk = false
        m.foreground(false)
        p.advance(1_500)
        assertEquals(SecureAppState.RESTORING, p.journal!!.phase)
        assertFalse(p.broadcasts.any { it })
        p.writeOk = true
        p.advance(5_000)
        assertEquals(DebugSettings(1, 1, 1), p.settings)
        assertEquals(1, p.broadcasts.count { it })
    }

    @Test fun unexpectedBinderInSecureModeIsStoppedAgain() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.binderReceived()
        p.advance(1_000)
        assertEquals(listOf(false, false), p.broadcasts)
        assertEquals(DebugSettings(1, 1, 1), p.journal!!.original)
    }

    @Test fun lateBinderAfterReentryCannotClearSecureJournal() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(3_500)
        m.foreground(true)
        p.alive = true
        m.binderReceived()
        p.advance(30_000)
        assertEquals(SecureAppState.SECURE, p.journal!!.phase)
        assertEquals(1, p.broadcasts.count { it })
        assertEquals(listOf(false, true, false, false), p.broadcasts)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
    }

    @Test fun unexpectedBinderDuringLeaveGraceIsStoppedBeforePossibleReentry() {
        val p = Fake()
        val m = SecureAppMode(p, null)
        m.foreground(true)
        p.advance(1_000)
        m.foreground(false)
        p.advance(500)
        m.binderReceived()
        m.foreground(true)
        p.advance(20_000)
        assertEquals(listOf(false, false), p.broadcasts)
        assertEquals(DebugSettings(0, 0, 0), p.settings)
    }
}
