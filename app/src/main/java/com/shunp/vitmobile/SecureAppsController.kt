package com.shunp.vitmobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import rikka.shizuku.Shizuku
import java.io.File

/** Process-owned so activity/service recreation cannot leave delayed transitions orphaned. */
internal class SecureAppsController private constructor(context: Context) : SecureAppMode.Port {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val machine = SecureAppMode(this, Prefs.getSecureAppState(this.context))
    private val received = Shizuku.OnBinderReceivedListener {
        main.post { reviveAttempts = 0; machine.binderReceived() }
    }

    // Shizuku のウォッチドッグは切ってある（カードアプリ用にデバッグを切った瞬間を「異常停止」と見て
    // デバッグをオンに戻してしまうため・2026-10-09）。代わりに、カードアプリを使っていない時に
    // Shizuku が落ちたら VIT が起動し直す。
    private var reviveAttempts = 0
    private val dead = Shizuku.OnBinderDeadListener { main.post { scheduleRevive(5_000) } }

    private fun scheduleRevive(delay: Long) {
        main.postDelayed({
            if (Shizuku.pingBinder()) { reviveAttempts = 0; return@postDelayed }
            if (machine.state != null) return@postDelayed // カードアプリ中・切り替え中は触らない
            if (reviveAttempts >= 5) { log("revive gave up"); return@postDelayed }
            reviveAttempts++
            log("revive Shizuku attempt=$reviveAttempts")
            broadcast(true)
            scheduleRevive(30_000)
        }, delay)
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        if (machine.state != null) log("recovery pending phase=${machine.state?.phase}")
    }

    val blocksShizuku: Boolean get() = machine.blocksShizuku && hasPermission()

    /** Called with the focused application window, not an arbitrary event's package. */
    fun foreground(pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == context.packageName || pkg == "com.android.systemui") return
        val ime = context.getSystemService(InputMethodManager::class.java)
        if (ime.inputMethodList.any { it.packageName == pkg }) return
        val secure = Prefs.isSecureApp(context, pkg)
        val now = System.currentTimeMillis()
        if (secure) {
            if (pkg != securePkg) { securePkg = pkg; secureSince = now }
        } else if (securePkg != null) {
            val p = securePkg!!
            securePkg = null
            // カードアプリは起動直後（スプラッシュ）に開発者向けオプションを調べて自分で閉じる。
            // VIT がオフにするのが一瞬遅れるので、数秒で閉じたらオフのまま1回だけ開き直す（2026-10-10 Vpass が開けなかった件）
            if (now - secureSince < 6_000 && now - (relaunchedAt[p] ?: 0L) > 120_000) {
                val intent = context.packageManager.getLaunchIntentForPackage(p)
                if (intent != null) {
                    relaunchedAt[p] = now
                    try {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        log("relaunch $p after early close")
                        return
                    } catch (e: Exception) { log("relaunch failed ${e.javaClass.simpleName}") }
                }
            }
        }
        machine.foreground(secure)
    }

    private var securePkg: String? = null
    private var secureSince = 0L
    private val relaunchedAt = HashMap<String, Long>()

    fun permissionAvailable() = machine.permissionAvailable()

    /** If the observer is explicitly disabled, it cannot report the next launcher window. */
    fun observerStopped() = machine.foreground(false)

    /** Explicit VIT launch is also a recovery path when accessibility is disconnected. */
    fun activityStarted() {
        if (InputAccessibilityService.instance == null && machine.state != null) machine.foreground(false)
    }

    override fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

    override fun readSettings() = DebugSettings(
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0),
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0),
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0),
    )

    override fun writeSettings(values: DebugSettings): Boolean {
        val development = Settings.Global.DEVELOPMENT_SETTINGS_ENABLED to values.development
        val usb = Settings.Global.ADB_ENABLED to values.usb
        val wifi = "adb_wifi_enabled" to values.wifi
        val order = if (values == DebugSettings(0, 0, 0)) listOf(wifi, usb, development)
            else listOf(development, usb, wifi)
        var success = true
        for ((key, original) in order) {
            // Restore only switches originally ON. Never enable a previously disabled transport.
            val value = if (original == 1) 1 else 0
            try {
                val written = Settings.Global.putInt(context.contentResolver, key, value)
                val actual = Settings.Global.getInt(context.contentResolver, key, -1)
                if (!written || actual != value) {
                    log("setting $key requested=$value actual=$actual written=$written")
                    success = false
                }
            } catch (e: Exception) {
                log("setting $key failed ${e.javaClass.simpleName}: ${e.message}")
                success = false
            }
        }
        return success
    }

    override fun save(state: SecureAppState?): Boolean {
        val saved = Prefs.setSecureAppState(context, state)
        // Binder may have stayed alive during a very short transition. Refresh even
        // without a new received event, after the machine has cleared its journal.
        if (saved && state == null) main.post { InputAccessibilityService.instance?.refreshShizukuSwipe() }
        return saved
    }

    override fun broadcast(start: Boolean) {
        val action = "moe.shizuku.privileged.api." + if (start) "START" else "STOP"
        try {
            // 合言葉（auth）が無いと Shizuku は命令を無視する（2026-10-10 再起動できずスワイプが止まった原因）
            val auth = Prefs.getShizukuAuth(context)
            context.sendBroadcast(Intent(action).setPackage("moe.shizuku.privileged.api").putExtra("auth", auth))
            log("broadcast ${if (start) "START" else "STOP"} auth=${if (auth.isEmpty()) "missing" else "set"}")
        } catch (e: Exception) { log("broadcast failed ${e.javaClass.simpleName}: ${e.message}") }
    }

    override fun binderAlive(): Boolean = Shizuku.pingBinder()
    override fun now(): Long = System.currentTimeMillis()
    override fun later(delay: Long, action: () -> Unit) { main.postDelayed({ action() }, delay) }
    override fun log(message: String) = log(context, message)

    companion object {
        private var instance: SecureAppsController? = null
        fun get(context: Context): SecureAppsController = instance ?: SecureAppsController(context).also { instance = it }

        /** Worker-thread IPC. The command is fixed/allowlisted by the shell-side service. */
        fun grantPermission(context: Context, remote: IShizukuTouchService) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) return
            val user = android.os.Process.myUid() / 100_000
            try {
                val result = remote.runShell("pm grant --user $user com.shunp.vitmobile android.permission.WRITE_SECURE_SETTINGS")
                val granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                log(context, "permission grant exit=$result granted=$granted")
            } catch (e: Exception) { log(context, "permission grant failed ${e.javaClass.simpleName}: ${e.message}") }
        }

        @Synchronized
        internal fun log(context: Context, message: String) {
            val line = "secure: ${message.replace('\n', ' ').replace('\r', ' ')}"
            Log.i("VIT_SECURE", line)
            try {
                val file = File(context.filesDir, "autosend.log")
                if (file.length() > 40_000) file.writeText("")
                file.appendText("$line\n")
            } catch (_: Exception) {}
        }
    }
}
