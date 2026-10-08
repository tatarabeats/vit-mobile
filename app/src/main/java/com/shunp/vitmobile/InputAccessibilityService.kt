package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InputAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_PASTE = "com.shunp.vitmobile.ACTION_PASTE"
        const val EXTRA_TEXT = "text"
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        private const val TAG = "VIT_ACC"
        // 最後にフォーカスされた入力欄の情報（fragment 間遷移で失われるのを補償）
        @Volatile
        var lastFocusedBounds: Rect? = null
        @Volatile
        var lastFocusedPackage: String? = null
        @Volatile
        var lastFocusedClass: String? = null

        /** 起動ゾーンから画面の状態を問い合わせるために保持する */
        @Volatile
        var instance: InputAccessibilityService? = null

        /** 今フォアグラウンドにあるアプリのパッケージ名 */
        fun currentPackage(): String? {
            val svc = instance ?: return null
            return try {
                svc.findAppRoot()?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 入力欄にフォーカスが当たっているか。
         * 除外アプリ（ブラウザ等）でも、文字を打つ場面なら音声入力を使いたい。
         * 動画を見ているだけの時は入力欄が無いので、そこで区別する。
         */
        fun hasFocusedEditable(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.findFocusedInput() != null
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 表示中のソフトキーボードの上端 Y。出ていなければ -1。
         * キーボードの端（バックスペース等）の連打で起動ゾーンが反応しないよう除外するのに使う。
         */
        fun imeTop(): Int {
            val svc = instance ?: return -1
            return try {
                var top = -1
                for (w in svc.windows) {
                    if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    if (r.height() > 0 && (top < 0 || r.top < top)) top = r.top
                }
                top
            } catch (_: Exception) {
                -1
            }
        }

        /**
         * 画面のキワに置いた起動ゾーンは、そこへの普通のタップを飲み込んでしまう。
         * ダブルタップでなかった時は、同じ座標のタップを下のアプリへ流し直す。
         * 呼ぶ側でゾーンを一時的に touch 不可にしてから呼ぶこと（でないと自分で拾い直す）。
         */
        fun passThroughTap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            return try {
                val path = android.graphics.Path().apply { moveTo(x, y) }
                val stroke = android.accessibilityservice.GestureDescription
                    .StrokeDescription(path, 0, 40)
                svc.dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke).build(),
                    null, null
                )
            } catch (_: Exception) {
                false
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                pasteOrSetText(text)
            }
        }
    }
    private val debugSidebarReceiver = DebugSidebarTargetReceiver()
    private var shizukuSwipe: ShizukuSwipeMonitor? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_PASTE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        instance = this
        if (BuildConfig.DEBUG) {
            val debugFilter = IntentFilter(DebugSidebarTargetReceiver.ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugSidebarReceiver, debugFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(debugSidebarReceiver, debugFilter)
            }
        }
        configureSidebarObservation()
        updateSidebarForeground(sidebarWindowPackage())
        if (shizukuSwipe == null) {
            shizukuSwipe = ShizukuSwipeMonitor(this).also { it.start() }
        }
        OverlayService.refreshClaudeSwipe()
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shizukuSwipe?.close()
        shizukuSwipe = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (BuildConfig.DEBUG) try { unregisterReceiver(debugSidebarReceiver) } catch (_: Exception) {}
        cancelSidebarSwipe()
        isSidebarObservationEnabled = false
        instance = null
        OverlayService.refreshClaudeSwipe()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shizukuSwipe?.close()
        shizukuSwipe = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal fun refreshShizukuSwipe() { shizukuSwipe?.refresh() }

    internal fun isClaudeForegroundForShizuku(): Boolean {
        updateSidebarForeground(sidebarWindowPackage())
        return sidebarForegroundPackage == CLAUDE_PACKAGE
    }

    /** フォーカスイベントを常時監視して、最後にフォーカスされた入力欄を記憶 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            updateSidebarForeground(sidebarWindowPackage())
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
            && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            && e.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return
        val src = e.source ?: return
        // 画面の書き換えイベントは、カーソルが入っていない入力欄でも飛んでくる。
        // それを覚えると、触っていない欄（ブラウザの検索欄等）へ入れてしまうので除く
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !src.isFocused) return
        if (src.packageName?.toString() == packageName) return
        if (looksLikeInput(src)) {
            val r = Rect()
            src.getBoundsInScreen(r)
            lastFocusedBounds = r
            lastFocusedPackage = src.packageName?.toString()
            lastFocusedClass = src.className?.toString()
            Log.d(TAG, "remember input: pkg=${lastFocusedPackage} class=${lastFocusedClass} bounds=$r")
        }
    }

    override fun onInterrupt() { cancelSidebarSwipe() }

    var isSidebarObservationEnabled = false
        private set
    private val sidebarSwipe by lazy { RightSwipeTracker(80f * resources.displayMetrics.density) }
    private var sidebarTapInFlight = false

    private fun configureSidebarObservation() {
        isSidebarObservationEnabled = false
        if (Build.VERSION.SDK_INT < 35) {
            sidebarDiag("observation unavailable: API < 35; edge fallback")
            return
        }
        val info = serviceInfo ?: return
        try {
            val setter = AccessibilityServiceInfo::class.java.getMethod(
                "setObservedMotionEventSources", Int::class.javaPrimitiveType!!)
            val getter = AccessibilityServiceInfo::class.java.getMethod("getObservedMotionEventSources")
            // This is a hidden @TestApi guarded by a signature permission, not a
            // public Android 15 API. Never enable consuming touchscreen delivery
            // while probing an ordinary installation without that permission.
            check(checkSelfPermission("android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) { "signature permission unavailable" }
            info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN)
            // setMotionEventSources clears observed sources, so order matters.
            setter.invoke(info, InputDevice.SOURCE_TOUCHSCREEN)
            setServiceInfo(info)
            val applied = serviceInfo ?: error("service info unavailable")
            check(applied.motionEventSources == InputDevice.SOURCE_TOUCHSCREEN
                && getter.invoke(applied) == InputDevice.SOURCE_TOUCHSCREEN) { "observation not retained" }
            isSidebarObservationEnabled = true
            sidebarDiag("observation enabled")
        } catch (e: Exception) {
            // Clear both fields (the public setter clears the observed field too).
            // Never publish an info containing only the consuming source mask.
            info.setMotionEventSources(0)
            try { setServiceInfo(info) } catch (_: Exception) { disableSelf() }
            sidebarDiag("observation unavailable: ${e.javaClass.simpleName} ${e.message}; edge fallback")
        }
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!isSidebarObservationEnabled || sidebarTapInFlight
            || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) updateSidebarForeground(sidebarWindowPackage())
        if (!isSidebarTargetActive()) {
            cancelSidebarSwipe()
            return
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL
            || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            cancelSidebarSwipe()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sidebarSwipe.begin(event.rawX, event.rawY)
                OverlayService.updateSidebarProgress(0f)
            }
            MotionEvent.ACTION_MOVE -> OverlayService.updateSidebarProgress(
                sidebarSwipe.progress(event.rawX, event.rawY))
            MotionEvent.ACTION_UP -> {
                if (sidebarSwipe.end(event.rawX, event.rawY)) completeSidebarSwipe()
                else OverlayService.finishSidebarProgress(false)
            }
        }
    }

    fun cancelSidebarSwipe() {
        sidebarSwipe.cancel()
        OverlayService.finishSidebarProgress(false)
    }

    fun completeSidebarSwipe() {
        if (!isSidebarTargetActive()) { cancelSidebarSwipe(); return }
        if (BuildConfig.DEBUG) Log.i("VIT_SWIPE", "detected")
        OverlayService.finishSidebarProgress(true)
        openClaudeSidebar()
    }

    fun isSidebarTargetActive(): Boolean = Prefs.isClaudeSwipeEnabled(this)
        && sidebarForegroundPackage == DebugSidebarTargetReceiver.targetPackage(this)

    private fun sidebarWindowPackage(): String? = try {
        // Do not reuse findAppRoot(): it deliberately skips VIT/system windows
        // for voice insertion, which could leave Claude stale behind another UI.
        val window = windows.firstOrNull { it.isFocused
            && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
            && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        if (window != null) window.root?.packageName?.toString()
        else rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) { null }

    private fun sidebarDiag(message: String) {
        diag("sidebar: $message")
        if (BuildConfig.DEBUG) Log.i("VIT_SWIPE", "sidebar $message")
    }

    /** サイドバー専用。既存の入力先・録音用 currentPackage() の判定は変えない。 */
    @Volatile
    var sidebarForegroundPackage: String? = null
        private set

    private fun updateSidebarForeground(pkg: String?) {
        if (sidebarForegroundPackage == pkg) return
        sidebarForegroundPackage = pkg
        cancelSidebarSwipe()
        OverlayService.refreshClaudeSwipe()
    }

    /** Return value means click accepted / tap dispatched, not proof the drawer opened. */
    fun openClaudeSidebar(): Boolean {
        updateSidebarForeground(sidebarWindowPackage())
        if (sidebarForegroundPackage != CLAUDE_PACKAGE && !isSidebarTargetActive()) {
            sidebarDiag("skipped (disabled or target not foreground)")
            return false
        }
        val ownedNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            val root = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            }?.root ?: rootInActiveWindow
            if (root == null) {
                sidebarDiag("skipped (target window unavailable)")
                return false
            }
            ownedNodes.add(root)
            if (root.packageName?.toString() != sidebarForegroundPackage) {
                sidebarDiag("skipped (target window changed)")
                return false
            }
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
            val keywords = listOf("sidebar", "menu", "drawer", "navigation", "chats", "history",
                "サイドバー", "メニュー", "チャット", "履歴")
            fun rect(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
            fun inTop(node: AccessibilityNodeInfo): Boolean {
                val r = rect(node)
                return node.isVisibleToUser && node.isEnabled && !r.isEmpty
                    && r.centerY() >= 0 && r.centerY() < dm.heightPixels * 0.2f
            }
            fun inLeft(node: AccessibilityNodeInfo): Boolean {
                val r = rect(node)
                return inTop(node) && r.centerX() >= 0 && r.centerX() < dm.widthPixels / 2
            }
            fun label(value: CharSequence?): String = value?.toString()
                ?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
            fun describe(node: AccessibilityNodeInfo): String =
                "desc=${label(node.contentDescription)} text=${label(node.text)} id=${label(node.viewIdResourceName)} rect=${rect(node)}"
            val topClickables = mutableListOf<AccessibilityNodeInfo>()
            val named = linkedMapOf<AccessibilityNodeInfo, String>()
            val drawerLabels = mutableSetOf<String>()
            var closeMenuVisible = false
            fun walk(node: AccessibilityNodeInfo, ancestors: List<AccessibilityNodeInfo>, depth: Int) {
                if (depth > 60) return
                val bounds = rect(node)
                if (node.isVisibleToUser && !bounds.isEmpty && bounds.left >= 0
                    && bounds.centerX() < dm.widthPixels * 0.65f
                    && bounds.centerY() in 0..dm.heightPixels) {
                    for (text in listOf(node.text, node.contentDescription)) {
                        val value = label(text).trim().lowercase(java.util.Locale.ROOT)
                        when (value) {
                            "チャット", "chats" -> drawerLabels.add("chats")
                            "プロジェクト", "projects" -> drawerLabels.add("projects")
                            "code", "コード" -> drawerLabels.add("code")
                            "メニューを閉じる", "close menu", "close sidebar", "close navigation menu",
                            "サイドバーを閉じる" -> closeMenuVisible = true
                        }
                    }
                }
                if (node.isClickable && inTop(node)) topClickables.add(node)
                if (inLeft(node) && listOf(node.contentDescription, node.text, node.viewIdResourceName)
                    .any { label -> label != null && keywords.any { label.contains(it, ignoreCase = true) } }) {
                    val target = (listOf(node) + ancestors.asReversed().take(5))
                        .firstOrNull { it.isClickable && inLeft(it) }
                    if (target != null) named[target] = describe(node)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    ownedNodes.add(child)
                    walk(child, ancestors + node, depth + 1)
                }
            }
            walk(root, emptyList(), 0)
            // Require two distinct navigation entries, avoiding a matching word in chat text.
            if (closeMenuVisible || drawerLabels.size >= 2) {
                ShizukuSwipeLog.write(this, "sidebar already open; skipped")
                return false
            }
            val orderedNamed = named.keys.sortedWith(compareBy({ rect(it).left }, { rect(it).top }))
            for (candidate in orderedNamed) {
                sidebarDiag("candidate named ${describe(candidate)} matched=${named[candidate]}")
                val clicked = try { candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    catch (_: Exception) { false }
                sidebarDiag("ACTION_CLICK named accepted=$clicked")
                if (clicked) return true
            }
            if (named.isEmpty()) {
                sidebarDiag("no named candidate; top clickables=${topClickables.size}")
                topClickables.forEach { sidebarDiag("top ${describe(it)}") }
            }
            // Small unlabelled toolbar controls near the physical left edge only.
            val positional = topClickables.filter {
                val r = rect(it)
                inLeft(it) && r.left >= 0 && r.left < dm.widthPixels * 0.25f
                    && r.width() < dm.widthPixels * 0.25f && !named.containsKey(it)
            }.minWithOrNull(compareBy({ rect(it).left }, { rect(it).top }))
            if (positional != null) {
                sidebarDiag("candidate positional ${describe(positional)}")
                val clicked = try { positional.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    catch (_: Exception) { false }
                sidebarDiag("ACTION_CLICK positional accepted=$clicked")
                if (clicked) return true
            } else {
                sidebarDiag("no positional candidate")
            }
            val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusHeight = if (statusId != 0) resources.getDimensionPixelSize(statusId)
                else (24 * dm.density).toInt()
            val x = 28 * dm.density
            val y = statusHeight + 28 * dm.density
            val dispatched = tapSidebarAt(x, y)
            sidebarDiag("fallback tap dispatched=$dispatched x=$x y=$y")
            return dispatched
        } catch (e: Exception) {
            sidebarDiag("failed ${e.javaClass.simpleName}")
            return false
        } finally {
            @Suppress("DEPRECATION")
            ownedNodes.forEach { it.recycle() }
        }
    }

    private fun tapSidebarAt(x: Float, y: Float): Boolean {
        // Our injected tap is observable too. It must not reset the panel's
        // completion animation as though the user had started a second gesture.
        sidebarTapInFlight = true
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                    sidebarTapInFlight = false
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                    sidebarTapInFlight = false
                }
            }, null)
            if (!accepted) sidebarTapInFlight = false
            accepted
        } catch (_: Exception) {
            sidebarTapInFlight = false
            false
        }
    }

    private var lastVolKeyAt = 0L

    /**
     * 音量ダウン2回押しでも録音を起動できるようにする（設定でONの時だけ）。
     * 画面のタッチは絶対に奪わない方針なので、ダブルタップが効かない端末の逃げ道はここ。
     * イベントは消費しない（false を返す）ので、音量そのものは普通に変わる。
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        val e = event ?: return false
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (e.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // 録音中は設定に関係なく「取り消し」に使う（ジェスチャー枠を消費しない取り消し手段）
        // 音量キーでの「起動」は廃止。録音中の取り消しだけ受ける（2026-08-14）
        val recording = OverlayService.isRecordingNow
        if (!recording) return false
        val now = System.currentTimeMillis()
        if (now - lastVolKeyAt in 1..450) {
            lastVolKeyAt = 0
            try {
                startForegroundService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_CANCEL)
                )
            } catch (_: Exception) {}
        } else {
            lastVolKeyAt = now
        }
        return false
    }

    private val handler by lazy { android.os.Handler(mainLooper) }

    // ==================== 前面アプリの窓と入力欄 ====================

    /**
     * 前面アプリの窓の根。VIT 自身とキーボードの窓は除く。
     * rootInActiveWindow は、録音を止めるために VIT の帯・マイクに触った直後だと
     * VIT 自身を指すことがあり、入力欄を見失って何も入らない原因になっていた（2026-10-06）。
     */
    fun findAppRoot(): AccessibilityNodeInfo? {
        val own = packageName
        val ws = try { windows } catch (_: Exception) { null }
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() != own) {
                val w = ws?.firstOrNull { it.id == active.windowId }
                // キーボードやシステムの窓が「アクティブ」なこともあるので、アプリの窓の時だけ採用
                if (w == null || w.type == AccessibilityWindowInfo.TYPE_APPLICATION) return active
            }
        } catch (_: Exception) {}
        if (ws == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = try { w.root } catch (_: Exception) { null } ?: continue
            if (r.packageName?.toString() == own) continue
            // 入力フォーカスを持つ窓 > アクティブな窓 > 手前の窓
            val score = (if (w.isFocused) 1_000_000 else 0) +
                (if (w.isActive) 100_000 else 0) + w.layer
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best
    }

    /** 前面アプリで、今カーソルが入っている入力欄 */
    fun findFocusedInput(): AccessibilityNodeInfo? {
        val own = packageName
        val f = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (f != null && f.packageName?.toString() != own && looksLikeInput(f)) return f
        val root = findAppRoot() ?: return null
        val g = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (g != null && looksLikeInput(g)) return g
        val a = try { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) } catch (_: Exception) { null }
        if (a != null && looksLikeInput(a)) return a
        return null
    }

    /**
     * 文字を入れる先を決める。
     *  1) カーソルが入っている入力欄
     *  2) 録音前に触っていた入力欄（同じアプリの中だけ）
     *  3) 自動送信の対象アプリ（AIチャット等）なら、画面の一番下の入力欄を自分で選ぶ。
     *     チャットは入力欄が実質1つなので、タップしていなくても入れてよい（駿平 2026-10-06）。
     *     それ以外のアプリ（ブラウザ等）では勝手に検索欄へ入る事故があったので、選んだ欄にだけ入れる。
     */
    private fun findTargetInput(): AccessibilityNodeInfo? {
        findFocusedInput()?.let { return it }
        recoverLastInput()?.let { return it }
        val root = findAppRoot() ?: return null
        val pkg = root.packageName?.toString()
        if (!Prefs.isAutoEnterApp(this, pkg)) return null
        val n = bottomInput(root) ?: return null
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        diag("insert: picked composer by itself pkg=$pkg")
        return n
    }

    /** 画面に見えている入力欄のうち一番下のもの（チャットの入力欄） */
    private fun bottomInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser && n.isEditable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0 && r.bottom > bestBottom) {
                    bestBottom = r.bottom
                    best = n
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    // ==================== 挿入 ====================

    private fun pasteOrSetText(providedText: String?) {
        Log.d(TAG, "=== pasteOrSetText text=${providedText?.take(30)} ===")
        val text = providedText ?: getClipboardText()
        if (text.isNullOrEmpty()) {
            Log.d(TAG, "no text to paste")
            return
        }
        insertWithRetry(text, direct = providedText != null, attempt = 0, expectPkg = currentPackage())
    }

    /**
     * 入力欄が見つかるまで少し待ってやり直す。
     * 録音を止めた直後は窓の切り替えが落ち着いておらず、短い発話ほど書き起こしが速く返るので
     * 「まだ VIT の窓が前面」の瞬間に当たりやすかった（短いと入らない、の原因）。
     */
    private fun insertWithRetry(text: String, direct: Boolean, attempt: Int, expectPkg: String?) {
        // 待っている間に別のアプリへ移ったら入れない（喋った時のアプリにだけ入れる）
        val nowPkg = currentPackage()
        if (expectPkg != null && nowPkg != null && !nowPkg.equals(expectPkg, ignoreCase = true)) {
            diag("insert: abort, app changed $expectPkg -> $nowPkg")
            return
        }
        val node = try { findTargetInput() } catch (_: Exception) { null }
        if (node == null) {
            if (attempt < 6) {
                handler.postDelayed({ insertWithRetry(text, direct, attempt + 1, expectPkg ?: nowPkg) }, 150)
                return
            }
            diag("insert: no input found pkg=${currentPackage()}")
            return
        }
        Log.d(TAG, "node class=${node.className} pkg=${node.packageName} focused=${node.isFocused} editable=${node.isEditable}")
        val pkg = node.packageName?.toString()
        val before = snapshotClickables(findAppRoot())
        val existing = currentText(node)

        // まず SET_TEXT。クリップボードを汚さずに書き込める
        if (direct && setTextOnNode(node, existing, text)) {
            // SET_TEXT が true を返しても反映しないアプリがあるので、入ったか確かめてから次へ
            handler.postDelayed({
                if (textLanded(node, existing, text)) {
                    diag("insert: set_text ok pkg=$pkg len=${text.length}")
                    maybeSendEnter(node, text, before)
                } else if (pasteViaClipboard(node, text)) {
                    diag("insert: set_text ignored, pasted pkg=$pkg")
                    maybeSendEnter(node, text, before)
                } else {
                    diag("insert: failed after set_text pkg=$pkg")
                }
            }, 120)
            return
        }

        // SET_TEXT を受け付けない入力欄がある（Brave の検索欄など・2026-08-14）。
        // その時だけクリップボード経由で貼り、直後に元の内容へ戻す。
        if (pasteViaClipboard(node, text)) {
            diag("insert: pasted pkg=$pkg")
            maybeSendEnter(node, text, before)
            return
        }
        diag("insert: failed on ${node.className} pkg=$pkg")
    }

    /** 入力欄の今の中身。何も無い時にヒント文（「メッセージ」等）を返すアプリがあるので除く */
    private fun currentText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (node.isShowingHintText) return ""
            val hint = node.hintText?.toString()
            if (!hint.isNullOrEmpty() && t == hint) return ""
        }
        return t
    }

    private fun textLanded(node: AccessibilityNodeInfo, existing: String, text: String): Boolean {
        val probe = text.trim().take(12)
        if (probe.isEmpty()) return true
        val n = refreshed(node)
        val now = n.text?.toString()
        // 中身を読ませないアプリ（null）は確かめようがないので入ったとみなす
        if (now == null) return true
        // 元から同じ言葉が入っていた時に「入った」と誤判定しないよう、前後で変わったかも見る
        return now != existing && now.contains(probe)
    }

    /** 挿入した入力欄そのものを読み直す。画面が作り直されて消えていた時だけ、今の入力欄で代用する */
    private fun refreshed(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val alive = try { node.refresh() } catch (_: Exception) { false }
        if (alive) return node
        return findFocusedInput() ?: node
    }

    /** クリップボードに一時的に置いて ACTION_PASTE で貼る。終わったら元に戻す */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val backup = try { cm.primaryClip } catch (_: Exception) { null }
        return try {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            var ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!ok) {
                Thread.sleep(80)
                ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            Log.d(TAG, "clipboard paste ok=$ok")
            handler.postDelayed({
                try { if (backup != null) cm.setPrimaryClip(backup) } catch (_: Exception) {}
            }, 800)
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** 既存テキストの後ろに追記する形で入力欄へ書き込む */
    private fun setTextOnNode(node: AccessibilityNodeInfo, existing: String, text: String): Boolean {
        val combined = if (existing.isEmpty()) text else "$existing$text"
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Log.d(TAG, "ACTION_SET_TEXT result=$ok len=${combined.length}")
        return ok
    }

    // ==================== 自動送信 ====================

    /**
     * 押せる要素の名前（id・説明・種類）と状態。挿入の前後で比べて「文字が入って出てきた／押せるようになったボタン」を探す。
     * 位置で比べないのは、キーボードが開いたり入力欄が伸びたりすると全部の位置がずれて誤判定するため。
     */
    private data class Spot(val key: String, val enabled: Boolean)

    private fun spotKey(n: AccessibilityNodeInfo): String =
        "${n.viewIdResourceName}|${n.contentDescription}|${n.className}"

    private fun snapshotClickables(root: AccessibilityNodeInfo?): List<Spot> {
        if (root == null) return emptyList()
        val out = mutableListOf<Spot>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable && n.isVisibleToUser) {
                out.add(Spot(spotKey(n), n.isEnabled))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        try { walk(root) } catch (_: Exception) {}
        return out
    }

    /**
     * 対象アプリなら挿入後に送信ボタンを押して送信まで済ませる。
     * 「押せた」ではなく「入力欄から文字が消えた」を成功とみなす。
     * 以前は1回目（250ms）で送信ボタンがまだ出ていないと、IME_ENTER が true を返すだけで
     * 送られず、そこで打ち切っていた。Claude で空振りしていたのはこれ（2026-10-06）。
     */
    private fun maybeSendEnter(node: AccessibilityNodeInfo, inserted: String, before: List<Spot>) {
        val pkg = node.packageName?.toString() ?: currentPackage()
        if (!Prefs.isAutoEnter(this, pkg)) {
            Log.d(TAG, "auto enter: skip (not target) pkg=$pkg")
            return
        }
        val probe = inserted.trim().take(12)
        val session = ++autoEnterSession
        val delays = listOf(150L, 450L, 850L, 1400L, 2100L, 3000L, 3800L)
        var acted = false
        var pressedKey: String? = null
        for ((i, d) in delays.withIndex()) {
            handler.postDelayed({
                if (session != autoEnterSession) return@postDelayed
                // 待っている間に別のアプリへ移ったら、移った先のボタンは押さない
                val nowPkg = currentPackage()
                if (nowPkg != null && !nowPkg.equals(pkg, ignoreCase = true)) {
                    diag("attempt=$i abort: app changed $pkg -> $nowPkg")
                    autoEnterSession++
                    return@postDelayed
                }
                if (acted && probe.isNotEmpty() && !inputStillHas(node, probe)) {
                    diag("attempt=$i sent (input cleared) pkg=$pkg")
                    autoEnterSession++
                    return@postDelayed
                }
                if (i == delays.lastIndex) {
                    // 最後は確認だけ
                    diag("attempt=$i gave up pkg=$pkg")
                    return@postDelayed
                }
                if (acted) {
                    // 一度押しても文字が残っている＝クリックを受け付けたふりのアプリ。
                    // 同じボタンだけを指と同じタップで押し直す。別のボタン（送信後に出る「停止」等）は選ばない
                    val key = pressedKey ?: return@postDelayed
                    retapSame(key, i)
                    return@postDelayed
                }
                val key = trySend(node, before, i, last = i == delays.lastIndex - 1)
                if (key != null) { acted = true; pressedKey = key.ifEmpty { null } }
            }, d)
        }
    }

    private var autoEnterSession = 0

    /** 入力欄にまだ挿入した文字が残っているか（残っていれば未送信） */
    private fun inputStillHas(fallback: AccessibilityNodeInfo, probe: String): Boolean {
        val n = refreshed(fallback)
        return n.text?.toString()?.contains(probe) == true
    }

    /** 押したボタンと同じ名前のボタンを探してタップし直す */
    private fun retapSame(key: String, attempt: Int) {
        val root = findAppRoot() ?: return
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || hit != null) return
            if (n.isClickable && n.isEnabled && n.isVisibleToUser && spotKey(n) == key && !hasBadLabel(n)) {
                hit = n
                return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val b = hit
        if (b == null) {
            diag("attempt=$attempt retap: same button gone")
            return
        }
        val r = Rect()
        b.getBoundsInScreen(r)
        val ok = tapAt(r.exactCenterX(), r.exactCenterY())
        diag("attempt=$attempt retap same button tap=$ok rect=$r")
    }

    /** 本人か子孫（3段まで）に「停止」「音声」などの語があるか */
    private fun hasBadLabel(n: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        val label = ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "") + " " +
            (n.viewIdResourceName?.substringAfterLast('/') ?: "")).lowercase()
        if (notSendWords.any { label.contains(it) }) return true
        if (depth >= 3) return false
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hasBadLabel(c, depth + 1)) return true
        }
        return false
    }

    /**
     * 送信を1回試す。押したらそのボタンの名前（IME_ENTER の時は空文字）、押さなかったら null
     */
    private fun trySend(
        fallback: AccessibilityNodeInfo,
        before: List<Spot>,
        attempt: Int,
        last: Boolean
    ): String? {
        val root = findAppRoot()
        val focus = findFocusedInput() ?: fallback

        // IME の実行キー（ACTION_IME_ENTER）は true を返すのに実際には送信されない
        // アプリがある（Claude で確認・2026-08-14）。当てにせず、送信ボタンを押しに行く。
        // 位置だけで推測するボタンと IME_ENTER は最後の1回だけ使う（音声モードやモデル選択を押す事故の防止）
        val btn = findSendButton(root, focus, before, allowGuess = last)
        if (btn == null) {
            diag("attempt=$attempt send button not found")
            if (!last) return null
            dumpCandidates(root)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (focus.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    ) {
                        diag("attempt=$attempt fallback ime_enter")
                        return ""
                    }
                } catch (_: Exception) {}
            }
            return null
        }
        val r = Rect()
        btn.getBoundsInScreen(r)

        // 2) ノードのクリック
        if (btn.isEnabled && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            diag("attempt=$attempt node click ok rect=$r desc=${btn.contentDescription}")
            return spotKey(btn)
        }

        // 3) 実際に指で触るのと同じタップ。
        //    Compose 製アプリは ACTION_CLICK を無視することがあるので、こちらが最後の手段
        val ok = tapAt(r.exactCenterX(), r.exactCenterY())
        diag("attempt=$attempt gesture tap=$ok rect=$r enabled=${btn.isEnabled}")
        return if (ok) spotKey(btn) else null
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription
                .StrokeDescription(path, 0, 60)
            dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(stroke).build(),
                null, null
            )
        } catch (_: Exception) { false }
    }

    /** 何が起きたかを端末内のファイルに残す（アプリの「自動送信の診断」で読める） */
    private fun diag(line: String) {
        Log.d(TAG, "auto enter: $line")
        try {
            val f = java.io.File(filesDir, "autosend.log")
            if (f.length() > 40_000) f.writeText("")
            f.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    /** 送信ボタンが見つからない時、画面にある押せる要素を全部書き出す */
    private fun dumpCandidates(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val sb = StringBuilder("---- clickable dump ----\n")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append("cls=").append(n.className)
                    .append(" desc=").append(n.contentDescription)
                    .append(" text=").append(n.text)
                    .append(" id=").append(n.viewIdResourceName)
                    .append(" enabled=").append(n.isEnabled)
                    .append(" rect=").append(r.flattenToString())
                    .append("\n")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        diag(sb.toString())
    }

    private val sendWords = listOf("送信", "送る", "send", "submit", "post", "reply")
    /** 送信ボタンの隣にいる、押すと困るもの（音声モード・停止・添付など） */
    private val notSendWords = listOf("voice", "mic", "音声", "マイク", "dictat", "stop", "停止", "attach", "添付", "feedback")

    /**
     * 送信ボタンを探す。3通りで当たりに行く:
     *  1) ラベル（contentDescription / text / viewId）に「送信」系の語があるもの
     *  2) 文字を入れる前には無かった／押せなかったのに、入れた後に押せるようになったもの
     *     （チャットUIは文字が入ると送信ボタンが出る・有効になる。アイコンだけのアプリもこれで拾う）
     *  3) 最後の1回だけ: 入力欄の右側にある一番右の小さい押せるもの
     */
    private fun findSendButton(
        root: AccessibilityNodeInfo?,
        input: AccessibilityNodeInfo,
        before: List<Spot>,
        allowGuess: Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        fun clickableSelfOrParent(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var cur = n
            var depth = 0
            while (cur != null && depth < 5) {
                if (cur.isClickable && cur.isEnabled) return cur
                cur = cur.parent
                depth++
            }
            return null
        }

        val ir = Rect()
        try { input.getBoundsInScreen(ir) } catch (_: Exception) {}
        val band = maxOf(ir.height(), (resources.displayMetrics.density * 72).toInt())
        fun nearInput(r: Rect): Boolean =
            ir.isEmpty || r.centerY() in (ir.top - band)..(ir.bottom + band)

        var byLabel: AccessibilityNodeInfo? = null
        var byLabelY = -1
        val clickables = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = Rect()
            n.getBoundsInScreen(r)

            val label = buildString {
                append(n.contentDescription?.toString() ?: "")
                append(" ")
                append(n.text?.toString() ?: "")
                append(" ")
                append(n.viewIdResourceName?.substringAfterLast('/') ?: "")
            }.trim().lowercase()
            val bad = notSendWords.any { label.contains(it) }
            // 入力欄そのもの（「これを送信して」と喋った本文など）は送信ボタンではない
            if (!n.isEditable && label.isNotEmpty() && label.length <= 40 && !bad &&
                sendWords.any { label.contains(it) }
            ) {
                val t = clickableSelfOrParent(n)
                val tr0 = Rect()
                t?.getBoundsInScreen(tr0)
                // 入力欄ごと包む大きな枠まで親をたどった場合は採らない
                if (t != null && !t.isEditable && !hasBadLabel(t) && (ir.isEmpty || tr0.width() <= ir.width() * 0.6f)) {
                    val tr = Rect()
                    t.getBoundsInScreen(tr)
                    if (nearInput(tr) && tr.centerY() > byLabelY) { byLabelY = tr.centerY(); byLabel = t }
                }
            }
            if (n.isClickable && n.isEnabled && n.isVisibleToUser && !n.isEditable &&
                r.width() > 0 && r.height() > 0 && !bad && !hasBadLabel(n)
            ) {
                clickables.add(n to Rect(r))
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)

        if (byLabel != null) return byLabel

        val maxW = if (ir.isEmpty) Int.MAX_VALUE else (ir.width() * 0.5f).toInt().coerceAtLeast(1)
        val small = clickables.filter { (_, r) -> nearInput(r) && r.width() <= maxW }

        // 2) 文字を入れて新しく押せるようになったもの（同じ名前の部品が前から押せたものは除く）
        val wasEnabled = before.filter { it.enabled }.map { it.key }.toSet()
        val appeared = if (before.isEmpty()) emptyList() else small.filter { (n, _) -> spotKey(n) !in wasEnabled }
        appeared.maxByOrNull { (_, r) -> r.right * 4 + r.bottom }?.let { return it.first }

        if (!allowGuess) return null

        // 3) 入力欄の右側で一番右のもの
        return small
            .filter { (_, r) -> ir.isEmpty || r.centerX() > ir.centerX() }
            .maxByOrNull { (_, r) -> r.right * 4 + r.bottom }
            ?.first
    }

    /**
     * 直前に触っていた入力欄を拾い直す。
     * 条件は「今前面にいるアプリが、その入力欄を覚えた時と同じアプリであること」だけ。
     * これを外すと、別アプリの検索欄に勝手に入る事故に戻る。
     */
    private fun recoverLastInput(): AccessibilityNodeInfo? {
        val root = findAppRoot() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (!pkg.equals(lastFocusedPackage ?: "", ignoreCase = true)) return null
        val bounds = lastFocusedBounds ?: return null
        // 座標が完全一致しなければ、同じ種類で近くにある入力欄だけ拾う（キーボードの出入りで少しずれる分）。
        // 画面のどこかの入力欄に入れると、触っていない検索欄に入る事故になる
        val n = searchByBounds(root, bounds) ?: searchNear(root, bounds) ?: return null
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Log.d(TAG, "recovered last input in $pkg")
        return n
    }

    private fun looksLikeInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cn = node.className?.toString() ?: return false
        return cn.contains("EditText", ignoreCase = true)
                || cn.contains("TextField", ignoreCase = true)
                || cn.contains("TextInput", ignoreCase = true)
    }

    /** 覚えていた位置の近く（縦横 300px 以内）にある、同じ種類の見えている入力欄 */
    private fun searchNear(root: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val limit = 300
        var best: AccessibilityNodeInfo? = null
        var bestD = Int.MAX_VALUE
        var sameKind = 0
        var only: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser && looksLikeInput(n) &&
                (lastFocusedClass == null || n.className?.toString() == lastFocusedClass)
            ) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val d = kotlin.math.abs(r.centerX() - target.centerX()) + kotlin.math.abs(r.centerY() - target.centerY())
                if (d <= limit && d < bestD) { bestD = d; best = n }
                sameKind++
                only = n
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        // キーボードの出入りで大きく動いても、同じ種類の欄が画面に1つしか無ければ取り違えようがない
        return best ?: if (sameKind == 1) only else null
    }

    private fun searchByBounds(node: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r == target && looksLikeInput(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchByBounds(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun getClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.text?.toString()
    }
}
