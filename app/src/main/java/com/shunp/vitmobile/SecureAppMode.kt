package com.shunp.vitmobile

internal data class DebugSettings(val development: Int, val usb: Int, val wifi: Int)

/** Journal is committed before STOP or any setting change. Kept until binder recovery. */
internal data class SecureAppState(
    val original: DebugSettings,
    val phase: String = SECURE,
    val startAttempts: Int = 0,
    val retryAt: Long = 0,
) {
    companion object {
        const val SECURE = "secure"
        const val RESTORING = "restoring"
        const val STARTING = "starting"
    }
}

/** Android-free transition logic; all calls (including scheduled callbacks) run on main. */
internal class SecureAppMode(private val port: Port, initial: SecureAppState?) {
    interface Port {
        fun hasPermission(): Boolean
        fun readSettings(): DebugSettings
        fun writeSettings(values: DebugSettings): Boolean
        fun save(state: SecureAppState?): Boolean
        fun broadcast(start: Boolean)
        fun binderAlive(): Boolean
        fun now(): Long
        fun later(delay: Long, action: () -> Unit)
        fun log(message: String)
    }

    var state = initial
        private set
    private var target: Boolean? = null
    private var generation = 0
    private var leaveGeneration = 0
    private var leaving = false
    private var entering = false
    private var secureApplied = false
    private var restoring = false
    private var permissionWaitLogged = false
    val blocksShizuku: Boolean get() = state?.phase == SecureAppState.SECURE

    /** Null means VIT, IME, system UI, or unknown: retain the last actual app. */
    fun foreground(isTarget: Boolean?) {
        if (isTarget == null) return
        target = isTarget
        if (isTarget) {
            leaving = false
            leaveGeneration++
            enter()
        } else if (state != null && !leaving && !restoring) {
            leaving = true
            val ticket = ++leaveGeneration
            port.later(1_500) {
                if (ticket == leaveGeneration && target == false) {
                    leaving = false
                    restore()
                }
            }
        }
    }

    fun permissionAvailable() {
        if (target == true) enter()
        else if (target == false && state != null) foreground(false)
    }

    fun binderReceived() {
        if (blocksShizuku) {
            // START can finish after re-entry's STOP. Stop that new binder even
            // during the 1s entry timer or the 1.5s leave grace period.
            entering = false
            secureApplied = false
            enter()
        } else if (state?.phase == SecureAppState.STARTING && target == false) {
            complete()
        }
    }

    private fun persist(next: SecureAppState?): Boolean {
        if (!port.save(next)) {
            port.log("journal commit failed; transition deferred")
            return false
        }
        state = next
        return true
    }

    private fun enter() {
        if (entering || (blocksShizuku && secureApplied)) return
        if (!port.hasPermission()) {
            if (!permissionWaitLogged) port.log("WRITE_SECURE_SETTINGS unavailable; keeping Shizuku for grant")
            permissionWaitLogged = true
            return
        }
        permissionWaitLogged = false
        val original = state?.original ?: try { port.readSettings() } catch (e: Exception) {
            port.log("snapshot failed ${e.javaClass.simpleName}")
            return
        }
        if (!persist(SecureAppState(original))) return
        val ticket = ++generation
        restoring = false
        entering = true
        port.log("enter; original=$original")
        // 先に設定をオフにする。カード・銀行アプリは起動した瞬間に開発者向けオプションを見るので、
        // Shizuku の停止を待ってからだと間に合わない（2026-10-08）
        entering = false
        secureApplied = port.writeSettings(DebugSettings(0, 0, 0))
        port.log("debugging disabled=$secureApplied")
        port.broadcast(false)
        if (!secureApplied) {
            port.later(3_000) { if (ticket == generation && target == true) enter() }
        }
    }

    private fun restore() {
        val saved = state ?: return
        if (!port.hasPermission()) {
            port.log("restore waiting for WRITE_SECURE_SETTINGS")
            return
        }
        val ticket = ++generation
        entering = false
        secureApplied = false
        restoring = true
        if (!persist(saved.copy(phase = SecureAppState.RESTORING))) {
            restoring = false
            return
        }
        // The adapter writes development -> USB -> Wi-Fi, only restoring saved 1s.
        if (!port.writeSettings(saved.original)) {
            port.log("restore settings incomplete; retry in 3s")
            restoring = false
            port.later(3_000) { if (ticket == generation && target == false) restore() }
            return
        }
        if (!persist(saved.copy(phase = SecureAppState.STARTING))) {
            restoring = false
            return
        }
        port.log("settings restored; waiting 2s before START")
        port.later(2_000) { if (ticket == generation && target == false) startOrWait(ticket) }
    }

    private fun startOrWait(ticket: Int) {
        val saved = state ?: return
        if (port.binderAlive()) { complete(); return }
        val remaining = saved.retryAt - port.now()
        if (saved.startAttempts > 0 && remaining > 0) {
            // Bound wall-clock jumps; journal prevents extra STARTs after process death.
            port.later(remaining.coerceAtMost(10_000)) {
                if (ticket == generation && target == false) retryOrFinish(ticket)
            }
            return
        }
        retryOrFinish(ticket)
    }

    private fun retryOrFinish(ticket: Int) {
        val saved = state ?: return
        if (port.binderAlive()) { complete(); return }
        if (saved.startAttempts >= 2) {
            port.log("START timed out after one retry; settings restored, journal retained")
            // Keep waiting for a late binder, without restarting on every window event.
            return
        }
        if (!persist(saved.copy(startAttempts = saved.startAttempts + 1,
                retryAt = port.now() + 10_000))) {
            restoring = false
            return
        }
        port.log("START attempt=${state?.startAttempts}")
        port.broadcast(true)
        port.later(10_000) {
            if (ticket == generation && target == false) retryOrFinish(ticket)
        }
    }

    private fun complete() {
        if (!persist(null)) return
        generation++
        restoring = false
        leaving = false
        port.log("normal; Shizuku binder recovered")
    }
}
