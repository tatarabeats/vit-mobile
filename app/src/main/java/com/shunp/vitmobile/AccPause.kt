package com.shunp.vitmobile

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * PayPay のように「ユーザー補助がオンだと使えない」アプリが前面にある間だけ、VIT のユーザー補助を自分で切り、
 * そのアプリから離れたら自分で戻す（2026-11-14 駿平さん「VITモバイルテキスト挿入の存在のせいで、PayPayのアプリが使えない」。
 * PayPay 公式: 一部のユーザー補助機能が有効だと利用を制限する。https://paypay.ne.jp/help/c0561/）。
 *
 * 切った後は前面のアプリをユーザー補助から知れないので、使用状況の記録（UsageStats）で「離れた」を見る。
 * 戻せる条件（WRITE_SECURE_SETTINGS と使用状況の許可）がそろっていない時は切らない（切ったまま戻らないのを防ぐ）。
 * 切っている印は先に保存し、プロセスが作り直されても（OverlayService・画面を開いた時）続きから見張る。
 */
internal object AccPause {
    private const val TAG = "VIT_ACCPAUSE"
    private const val POLL_MS = 700L
    private const val AWAY_MS = 3_000L
    private val main = Handler(Looper.getMainLooper())
    private var polling = false
    private var target: String? = null
    private var foreground: String? = null
    private var lastQuery = 0L
    private var awaySince = 0L
    private var skipLogged = false

    /** 切っている間は true（ユーザー補助が外れても、カードアプリ用の設定を戻さないため） */
    val active: Boolean get() = target != null

    private fun component(ctx: Context) = ComponentName(ctx, InputAccessibilityService::class.java)

    private fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun usageAllowed(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    /** Worker-thread IPC（Shizuku）。使用状況の許可を自分に付ける。命令は shell 側で固定の物だけ通る。 */
    fun grantUsageAccess(ctx: Context, remote: IShizukuTouchService) {
        if (usageAllowed(ctx)) return
        val user = android.os.Process.myUid() / 100_000
        try {
            val result = remote.runShell("cmd appops set --user $user com.shunp.vitmobile GET_USAGE_STATS allow")
            log(ctx, "usage access grant exit=$result allowed=${usageAllowed(ctx)}")
        } catch (e: Exception) { log(ctx, "usage access grant failed ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun enabledNow(ctx: Context): Boolean {
        val mine = component(ctx)
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return raw.split(':').any { ComponentName.unflattenFromString(it.trim()) == mine }
    }

    /** ユーザー補助から: 前面のアプリが分かるたびに呼ぶ。切った時だけ true。 */
    fun onForeground(service: InputAccessibilityService, pkg: String?): Boolean {
        if (pkg == null || target != null || !Prefs.isAccPauseApp(service, pkg)) return false
        val ctx = service.applicationContext
        if (!canWriteSecure(ctx) || !usageAllowed(ctx)) {
            // 前面の確認は毎秒来るので、記録は 1 回だけ
            if (!skipLogged) log(ctx, "not pausing for $pkg (cannot restore: secure=${canWriteSecure(ctx)} usage=${usageAllowed(ctx)})")
            skipLogged = true
            return false
        }
        skipLogged = false
        if (!Prefs.setAccPauseState(ctx, pkg)) return false
        target = pkg
        foreground = pkg
        awaySince = 0L
        lastQuery = System.currentTimeMillis()
        log(ctx, "pause for $pkg")
        try { service.disableSelf() } catch (e: Exception) {
            log(ctx, "disableSelf failed ${e.javaClass.simpleName}")
            finish(ctx, "disable failed")
            return false
        }
        startPolling(ctx)
        // 相手のアプリが先に調べ終えて制限の画面を出していても、開き直した時に調べ直す。
        // 透明な画面を一瞬だけ前に出して、相手のアプリに「開き直した」を起こす。
        main.postDelayed({
            if (target != pkg) return@postDelayed
            try {
                ctx.startActivity(Intent(ctx, NudgeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                log(ctx, "nudge shown")
            } catch (e: Exception) { log(ctx, "nudge failed ${e.javaClass.simpleName}") }
        }, 600)
        return true
    }

    /** プロセスが作り直された時（OverlayService・画面を開いた時）: 切ったままなら続きから見張る。 */
    fun resumeIfNeeded(context: Context) {
        val ctx = context.applicationContext
        if (target != null) return
        val saved = Prefs.getAccPauseState(ctx) ?: return
        if (enabledNow(ctx)) { Prefs.setAccPauseState(ctx, null); return }
        target = saved
        val now = System.currentTimeMillis()
        // 直前に何が前面だったか分からない時は「離れた」とみなして戻す
        foreground = lastResumed(ctx, now - 10 * 60_000, now) ?: ""
        awaySince = 0L
        lastQuery = now
        log(ctx, "recovered pause for $saved; foreground=$foreground")
        startPolling(ctx)
    }

    private fun startPolling(ctx: Context) {
        if (polling) return
        polling = true
        main.postDelayed(object : Runnable {
            override fun run() {
                val pkg = target
                if (pkg == null) { polling = false; return }
                val now = System.currentTimeMillis()
                val seen = lastResumed(ctx, lastQuery - 1_500, now)
                lastQuery = now
                if (seen != null && seen != ctx.packageName) foreground = seen
                if (foreground == pkg) {
                    awaySince = 0L
                } else {
                    if (awaySince == 0L) awaySince = now
                    if (now - awaySince >= AWAY_MS) {
                        polling = false
                        finish(ctx, "left $pkg for $foreground")
                        return
                    }
                }
                main.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
    }

    private fun lastResumed(ctx: Context, from: Long, to: Long): String? = try {
        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, to)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == 1) last = event.packageName // ACTIVITY_RESUMED
        }
        last
    } catch (_: Exception) { null }

    /** ユーザー補助をオンに戻し、カードアプリ用に切っていた設定も戻させる。 */
    private fun finish(ctx: Context, why: String) {
        val ok = enable(ctx)
        log(ctx, "restore ($why) enabled=$ok")
        if (!ok) {
            // 戻せなかった: 印を残したまま 3 秒後にやり直す
            main.postDelayed({ if (target != null) finish(ctx, "retry") }, 3_000)
            return
        }
        target = null
        Prefs.setAccPauseState(ctx, null)
        // ユーザー補助がつながり直すまでの間に、カードアプリ用の設定（デバッグ・Shizuku）を戻し始める
        SecureAppsController.get(ctx).observerStopped()
    }

    private fun enable(ctx: Context): Boolean = try {
        val resolver = ctx.contentResolver
        if (!enabledNow(ctx)) {
            val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() } + component(ctx).flattenToString()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        }
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        enabledNow(ctx)
    } catch (e: Exception) {
        log(ctx, "enable failed ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    @Synchronized
    private fun log(ctx: Context, message: String) {
        Log.i(TAG, message)
        try {
            val file = File(ctx.filesDir, "autosend.log")
            if (file.length() > 40_000) file.writeText("")
            file.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                + " accpause: " + message.replace('\n', ' ') + "\n")
        } catch (_: Exception) {}
    }
}
