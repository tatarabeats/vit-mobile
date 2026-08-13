package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InputAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_PASTE = "com.shunp.vitmobile.ACTION_PASTE"
        const val EXTRA_TEXT = "text"
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
                svc.rootInActiveWindow?.packageName?.toString()
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
                val root = svc.rootInActiveWindow ?: return false
                val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                n.isEditable || (n.className?.toString()?.contains("Edit", true) == true)
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
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        instance = null
        return super.onUnbind(intent)
    }

    /** フォーカスイベントを常時監視して、最後にフォーカスされた入力欄を記憶 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
            && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            && e.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return
        val src = e.source ?: return
        if (looksLikeInput(src)) {
            val r = Rect()
            src.getBoundsInScreen(r)
            lastFocusedBounds = r
            lastFocusedPackage = src.packageName?.toString()
            lastFocusedClass = src.className?.toString()
            Log.d(TAG, "remember input: pkg=${lastFocusedPackage} class=${lastFocusedClass} bounds=$r")
        }
    }

    override fun onInterrupt() {}

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
        val recording = OverlayService.isRecordingNow
        if (!recording && !Prefs.isVolumeTrigger(this)) return false
        val now = System.currentTimeMillis()
        if (now - lastVolKeyAt in 1..450) {
            lastVolKeyAt = 0
            try {
                startForegroundService(
                    Intent(this, OverlayService::class.java).setAction(
                        if (recording) OverlayService.ACTION_CANCEL
                        else OverlayService.ACTION_TRIGGER
                    )
                )
            } catch (_: Exception) {}
        } else {
            lastVolKeyAt = now
        }
        return false
    }

    private fun pasteOrSetText(providedText: String?) {
        Log.d(TAG, "=== pasteOrSetText text=${providedText?.take(30)} ===")
        val node = findFocusedNode() ?: findInputNodeInTree() ?: findByLastBounds()
        if (node == null) {
            Log.d(TAG, "no node found")
            return
        }
        Log.d(TAG, "node class=${node.className} pkg=${node.packageName} focused=${node.isFocused} editable=${node.isEditable}")
        Log.d(TAG, "node actions=${node.actionList.map { it.id to it.label }}")

        val text = providedText ?: getClipboardText()
        if (text.isNullOrEmpty()) {
            Log.d(TAG, "no text to paste")
            return
        }

        // クリップボードを使わなくなったので、テキストを渡された時は SET_TEXT を先に使う。
        // （ACTION_PASTE はクリップボードの中身を貼るので、古い内容が入ってしまう）
        if (providedText != null) {
            if (setTextOnNode(node, providedText)) {
                maybeSendEnter(node)
                return
            }
        }

        // テキストが渡されていない場合のみクリップボード経由
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Log.d(TAG, "ACTION_PASTE ok")
            maybeSendEnter(node)
            return
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Log.d(TAG, "ACTION_PASTE after focus ok")
            maybeSendEnter(node)
            return
        }
        if (setTextOnNode(node, text)) maybeSendEnter(node)
    }

    /** 既存テキストの後ろに追記する形で入力欄へ書き込む */
    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val existing = node.text?.toString() ?: ""
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

    /**
     * 対象アプリなら挿入後に Enter を送って送信まで済ませる。
     * IME の実行キーを押すのと同じ ACTION_IME_ENTER を使う（Android 11+）。
     */
    private fun maybeSendEnter(node: AccessibilityNodeInfo) {
        val pkg = node.packageName?.toString() ?: currentPackage()
        if (!Prefs.isAutoEnter(this, pkg)) {
            Log.d(TAG, "auto enter: skip (not target) pkg=$pkg")
            return
        }
        // 入力が画面に反映されるまでの間が読めないので、時間差で3回まで試す。
        // Compose 製アプリは送信ボタンが「文字が入るまで無効」なことがある。
        val delays = listOf(250L, 700L, 1400L)
        val h = android.os.Handler(mainLooper)
        for ((i, d) in delays.withIndex()) {
            h.postDelayed({
                if (autoEnterDone == pkg) return@postDelayed
                if (trySend(node, pkg, i)) autoEnterDone = pkg
            }, d)
        }
        h.postDelayed({ autoEnterDone = null }, 2500)
    }

    private var autoEnterDone: String? = null

    private fun trySend(fallback: AccessibilityNodeInfo, pkg: String?, attempt: Int): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        val focus = try {
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: fallback
        } catch (_: Exception) { fallback }

        // 1) IME の実行キー相当
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (focus.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                ) {
                    Log.d(TAG, "auto enter: ime_enter ok (attempt=$attempt) pkg=$pkg")
                    return true
                }
            } catch (_: Exception) {}
        }

        // 2) 送信ボタンらしきものを押す
        val btn = findSendButton(root)
        if (btn != null) {
            val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "auto enter: click=$ok (attempt=$attempt) pkg=$pkg")
            if (ok) return true
        } else {
            Log.d(TAG, "auto enter: send button not found (attempt=$attempt) pkg=$pkg")
        }
        return false
    }

    private val sendWords = listOf("送信", "送る", "send", "submit", "post", "reply")

    /**
     * 「送信」に見えるノードを探す。
     * ラベルは contentDescription / text / viewId のどれに入っているか分からないので全部見る。
     * 見つかったノードが押せない時は、押せる親まで登る（Compose はここでよく外す）。
     */
    private fun findSendButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestY = -1

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

        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val label = buildString {
                append(n.contentDescription?.toString() ?: "")
                append(" ")
                append(n.text?.toString() ?: "")
                append(" ")
                append(n.viewIdResourceName?.substringAfterLast('/') ?: "")
            }.trim().lowercase()
            if (label.isNotEmpty() && label.length <= 40 &&
                sendWords.any { label.contains(it) }
            ) {
                val target = clickableSelfOrParent(n)
                if (target != null) {
                    val r = Rect()
                    target.getBoundsInScreen(r)
                    // 画面下部にあるものを優先（上部の「投稿」等を拾わないため）
                    if (r.centerY() > bestY) { bestY = r.centerY(); best = target }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        // focused が入力欄っぽくない場合は無効（android.view.View で actions がペースト非対応等）
        if (focused != null && !looksLikeInput(focused)) return null
        return focused
    }

    private fun findInputNodeInTree(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { searchInput(it)?.let { n -> return n } }
        val windowList = try { windows } catch (_: Exception) { null } ?: return null
        for (w in windowList) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = w.root ?: continue
            searchInput(root)?.let { return it }
        }
        return null
    }

    /** 画面全走査でも見つからない場合、前回記憶した bounds 近辺で再探索 */
    private fun findByLastBounds(): AccessibilityNodeInfo? {
        val bounds = lastFocusedBounds ?: return null
        val root = rootInActiveWindow ?: return null
        return searchByBounds(root, bounds)
    }

    private fun looksLikeInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cn = node.className?.toString() ?: return false
        return cn.contains("EditText", ignoreCase = true)
                || cn.contains("TextField", ignoreCase = true)
                || cn.contains("TextInput", ignoreCase = true)
    }

    private fun searchInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && looksLikeInput(node)) return node
        if (looksLikeInput(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchInput(child)
            if (found != null) return found
        }
        return null
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
