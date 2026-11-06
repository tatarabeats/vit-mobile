package com.shunp.vitmobile

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** Owned by the accessibility service. Lifecycle state and callbacks stay on the main thread. */
internal class ShizukuSwipeMonitor(private val owner: InputAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val power = owner.getSystemService(PowerManager::class.java)
    private val displays = owner.getSystemService(DisplayManager::class.java)
    private val args = Shizuku.UserServiceArgs(ComponentName(owner, ShizukuTouchService::class.java))
        .daemon(false).processNameSuffix("sidebar_touch").debuggable(BuildConfig.DEBUG).version(2)
    private var active = false
    private var screenOn = false
    private var session: Session? = null
    private var lastFire: Long? = null
    /** 左へ送った表・グラフ（判定の名前ごと）と、戻す分の回数 */
    private val scrolledLeft = mutableMapOf<String, Int>()
    private var scrolledAt = 0L
    private var permissionState: Boolean? = null
    private var needsConnectionCleanup = true
    @Volatile private var display = SwipeDisplay(0, 0, 0)
    private val retry = Runnable { refresh() }
    private val received = Shizuku.OnBinderReceivedListener {
        if (active) { log("binder received"); refresh() }
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (active) {
            log("binder dead")
            permissionState = null
            main.removeCallbacks(retry)
            stopSession("binder dead")
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        // Includes 180-degree rotation, which need not cause a configuration broadcast.
        override fun onDisplayChanged(displayId: Int) { updateDisplay() }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (active && code == ShizukuSwipePermission.REQUEST_CODE) {
            log("permission granted=${grant == PackageManager.PERMISSION_GRANTED}")
            refresh()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    main.removeCallbacks(retry)
                    stopSession("screen off")
                }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; refresh() }
                Intent.ACTION_CONFIGURATION_CHANGED -> updateDisplay()
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        screenOn = power.isInteractive
        updateDisplay()
        displays.registerDisplayListener(displayListener, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) owner.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            owner.registerReceiver(receiver, filter)
        }
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        Shizuku.addBinderReceivedListenerSticky(received)
        refresh()
    }

    fun close() {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        displays.unregisterDisplayListener(displayListener)
        owner.unregisterReceiver(receiver)
        stopSession("accessibility stopped")
    }

    fun refresh() {
        if (!active) return
        if (SecureAppsController.get(owner).blocksShizuku) {
            main.removeCallbacks(retry)
            stopSession("secure app")
            return
        }
        val allowed = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        if (Shizuku.pingBinder() && permissionState != allowed) {
            permissionState = allowed
            log("permission available=$allowed")
        }
        if (!screenOn || !power.isInteractive || !allowed) {
            main.removeCallbacks(retry)
            stopSession("screen/binder/permission unavailable")
            return
        }
        if (session != null) return
        main.removeCallbacks(retry)
        updateDisplay()
        val next = Session()
        session = next
        log("start user service")
        main.postDelayed(next.timeout, 10_000)
        try {
            detachConnection()
            Shizuku.bindUserService(args, next)
        }
        catch (e: Exception) { restart(next, "bind failed ${e.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun updateDisplay() {
        val screen = owner.getSystemService(WindowManager::class.java).defaultDisplay
        val metrics = DisplayMetrics()
        screen.getRealMetrics(metrics)
        display = SwipeDisplay(metrics.widthPixels, metrics.heightPixels, screen.rotation)
    }

    private fun stopSession(reason: String) {
        val old = session ?: return
        session = null
        old.cancelled = true
        main.removeCallbacks(old.timeout)
        log("stop $reason")
        // The user-service binder is independent of the Shizuku server binder.
        // Stop the child even if the latter has already died and unbind cannot run.
        try { old.remote?.destroy() } catch (_: Exception) {}
        try {
            // remove=true calls the reserved destroy transaction, killing getevent too.
            Shizuku.unbindUserService(args, old, true)
        } catch (_: Exception) {}
        // 13.1.5's remove=true does not clear the SDK connection cache. In particular,
        // a bind timeout may never receive a binder-death callback to clear it for us.
        needsConnectionCleanup = true
        try { detachConnection() } catch (_: Exception) {}
        try { old.descriptor?.close() } catch (_: Exception) {}
    }

    private fun detachConnection() {
        if (!needsConnectionCleanup) return
        Shizuku.unbindUserService(args, null, false)
        needsConnectionCleanup = false
    }

    private fun restart(old: Session, reason: String) {
        if (session !== old || old.cancelled) return
        stopSession(reason)
        if (active && screenOn) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, 3_000)
        }
    }

    private fun log(message: String) = ShizukuSwipeLog.write(owner, message)

    private inner class Session : ServiceConnection {
        @Volatile var cancelled = false
        @Volatile var descriptor: ParcelFileDescriptor? = null
        var remote: IShizukuTouchService? = null
        private var connected = false
        val timeout = Runnable { restart(this, "user service/discovery timeout") }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (cancelled || session !== this || connected || binder == null) return
            connected = true
            val remote = IShizukuTouchService.Stub.asInterface(binder)
            this.remote = remote
            thread(name = "vit-shizuku-swipe", isDaemon = true) {
                try {
                    SecureAppsController.grantPermission(owner, remote)
                    main.post { SecureAppsController.get(owner).permissionAvailable() }
                    if (cancelled) return@thread
                    val listing = readPipe(remote.listDevices()) { it.readText() } ?: return@thread
                    val device = TouchDeviceDiscovery.parse(listing)
                        ?: error("touchscreen with zero-based MT X/Y axes unavailable")
                    log("device name=${device.name} path=${device.path} max=${device.maxX},${device.maxY}")
                    if (cancelled) return@thread
                    val parser = GeteventSwipeParser(device)
                    readPipe(remote.readEvents(device.path)) { reader ->
                        main.post { if (session === this) main.removeCallbacks(timeout) }
                        while (!cancelled) {
                            val line = reader.readLine() ?: break
                            val now = SystemClock.uptimeMillis()
                            val accepted = parser.accept(line, now, display,
                                owner.sidebarForegroundPackage == InputAccessibilityService.CLAUDE_PACKAGE)
                            parser.lastGesture?.let {
                                parser.lastGesture = null
                                if (owner.sidebarForegroundPackage == InputAccessibilityService.CLAUDE_PACKAGE) log("gesture $it")
                            }
                            parser.earlyLeft?.let {
                                parser.earlyLeft = null
                                main.post {
                                    if (!owner.isClaudeForegroundForShizuku()) return@post
                                    // メニューが開いている時の左なぞりは、戻るボタンでメニューを閉じて直前のスレッドへ戻す。
                                    // Claude に任せると、指の下のスレッドが押されたことになり、別のスレッドが開いていた（2026-11-06）
                                    if (owner.openClaudeSidebar(checkOnly = true)) {
                                        val ok = owner.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                                        lastFire = SystemClock.uptimeMillis()
                                        log("left swipe on open menu -> back (close menu) ok=$ok")
                                    }
                                }
                            }
                            parser.lastLeftSwipe?.let { (lx, ly) ->
                                parser.lastLeftSwipe = null
                                main.post {
                                    if (!owner.isClaudeForegroundForShizuku()) return@post
                                    // 表・グラフを左へ送った。次の右なぞりは、送った分を戻す操作として1回だけ開かずに通す。
                                    // 戻す分は1回だけ（足し上げない）。メニューを閉じる左なぞり（開いて3秒以内）は数えない
                                    // （2026-11-06 駿平さん「長文を送って Claude が考えている画面で小さくスワイプしても全く動かない」:
                                    // メニューを閉じる左なぞりのたびに「グラフを送った」と数え、戻す分が 89 回まで溜まって、
                                    // 部品の無い所での右なぞりを 88 回続けて捨てていた）
                                    if (lastFire?.let { SystemClock.uptimeMillis() - it < 3000 } == true) return@post
                                    if (owner.openClaudeSidebar(checkOnly = true)) return@post  // メニューが開いている＝閉じる操作
                                    InputAccessibilityService.horizontalScrollerAt(lx.toInt(), ly.toInt())?.let {
                                        scrolledLeft[it] = 1
                                        scrolledAt = SystemClock.uptimeMillis()
                                        log("scroller moved left ($it) credit=${scrolledLeft[it]}")
                                    }
                                }
                            }
                            if (accepted) {
                                main.post {
                                    if (cancelled || session !== this || !screenOn || !power.isInteractive
                                        || !display.portrait || SystemClock.uptimeMillis() - now > 700) return@post
                                    if (!owner.isClaudeForegroundForShizuku()) return@post
                                    val current = SystemClock.uptimeMillis()
                                    if (lastFire?.let { current - it < 300 } == true) return@post
                                    // キーボードの上で始まったなぞり（フリック入力）は無視する（2026-10-09 駿平さん）
                                    val imeTop = InputAccessibilityService.imeTop()
                                    if (imeTop >= 0 && parser.lastStartY >= imeTop) {
                                        log("swipe ignored (started on keyboard y=${parser.lastStartY.toInt()} imeTop=$imeTop)")
                                        return@post
                                    }
                                    // 横スクロールする表・グラフの上で始まったなぞりは、先に左へ送ってあって
                                    // 戻す分がある時だけ開かない（2026-10-13 駿平さん）。
                                    // 以前は表らしい物の上では毎回開かず、表の無い画面でも開かなかった
                                    // （2026-11-03 駿平さん「左から右にスワイプする動作が全然効かない」。記録で
                                    // 同じ部品 984x380 の上の右なぞりが7回続けて捨てられていた）。
                                    // 表は最初は左端にあり、右になぞっても動かない。左へ送った後だけ戻す余地がある
                                    InputAccessibilityService.horizontalScrollerAt(parser.lastStartX.toInt(), parser.lastStartY.toInt())?.let {
                                        // 戻す分は、表なら2分、部品の無い所（グラフと推定）なら15秒で忘れる
                                        val keep = if (it.startsWith("no-node")) 15_000 else 120_000
                                        if (current - scrolledAt > keep) scrolledLeft.clear()
                                        val credit = scrolledLeft[it] ?: 0
                                        if (credit > 0) {
                                            scrolledLeft[it] = credit - 1
                                            log("swipe ignored (scroll back on $it, credit left=${credit - 1})")
                                            return@post
                                        }
                                        log("swipe on $it but nothing scrolled left; open")
                                    }
                                    lastFire = current
                                    log("swipe detected at ${parser.lastStartX.toInt()},${parser.lastStartY.toInt()} under=[${InputAccessibilityService.lastNodesAtPoint}]")
                                    owner.openClaudeSidebar()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!cancelled) log("reader failed ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    main.post { restart(this, "getevent exited") }
                }
            }
        }

        private fun <T> readPipe(fd: ParcelFileDescriptor, read: (java.io.BufferedReader) -> T): T? {
            descriptor = fd
            return try {
                if (cancelled) null
                else ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use(read)
            } finally {
                try { fd.close() } catch (_: Exception) {}
                descriptor = null
            }
        }

        // Do not clear Shizuku's connection set while it is iterating callbacks.
        override fun onServiceDisconnected(name: ComponentName?) {
            main.post { restart(this, "user service disconnected") }
        }
        override fun onBindingDied(name: ComponentName?) {
            main.post { restart(this, "user service binding died") }
        }
        override fun onNullBinding(name: ComponentName?) {
            main.post { restart(this, "user service null binding") }
        }
    }
}
