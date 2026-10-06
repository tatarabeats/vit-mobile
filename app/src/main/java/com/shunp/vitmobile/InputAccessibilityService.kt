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
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() != own) return active
        } catch (_: Exception) {}
        val ws = try { windows } catch (_: Exception) { null } ?: return null
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
        insertWithRetry(text, direct = providedText != null, attempt = 0)
    }

    /**
     * 入力欄が見つかるまで少し待ってやり直す。
     * 録音を止めた直後は窓の切り替えが落ち着いておらず、短い発話ほど書き起こしが速く返るので
     * 「まだ VIT の窓が前面」の瞬間に当たりやすかった（短いと入らない、の原因）。
     */
    private fun insertWithRetry(text: String, direct: Boolean, attempt: Int) {
        val node = try { findTargetInput() } catch (_: Exception) { null }
        if (node == null) {
            if (attempt < 6) {
                handler.postDelayed({ insertWithRetry(text, direct, attempt + 1) }, 150)
                return
            }
            diag("insert: no input found pkg=${currentPackage()}")
            toastMain("入力欄が見つからなかった（履歴に残っています）")
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
                if (textLanded(node, text)) {
                    diag("insert: set_text ok pkg=$pkg len=${text.length}")
                    maybeSendEnter(node, text, before)
                } else if (pasteViaClipboard(node, text)) {
                    diag("insert: set_text ignored, pasted pkg=$pkg")
                    maybeSendEnter(node, text, before)
                } else {
                    diag("insert: failed after set_text pkg=$pkg")
                    toastMain("入力できなかった（履歴に残っています）")
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
        toastMain("入力できなかった（履歴に残っています）")
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

    private fun textLanded(node: AccessibilityNodeInfo, text: String): Boolean {
        val probe = text.trim().take(12)
        if (probe.isEmpty()) return true
        val n = refreshed(node)
        val now = n.text?.toString()
        // 中身を読ませないアプリ（null）は確かめようがないので入ったとみなす
        return now == null || now.contains(probe)
    }

    private fun refreshed(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val f = findFocusedInput()
        if (f != null) return f
        try { node.refresh() } catch (_: Exception) {}
        return node
    }

    private fun toastMain(msg: String) {
        handler.post {
            try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
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
        val delays = listOf(150L, 450L, 850L, 1400L, 2100L, 3000L)
        var acted = false
        for ((i, d) in delays.withIndex()) {
            handler.postDelayed({
                if (session != autoEnterSession) return@postDelayed
                if (acted && probe.isNotEmpty() && !inputStillHas(node, probe)) {
                    diag("attempt=$i sent (input cleared) pkg=$pkg")
                    autoEnterSession++
                    return@postDelayed
                }
                if (trySend(node, before, i, last = i == delays.lastIndex)) acted = true
            }, d)
        }
    }

    private var autoEnterSession = 0

    /** 入力欄にまだ挿入した文字が残っているか（残っていれば未送信） */
    private fun inputStillHas(fallback: AccessibilityNodeInfo, probe: String): Boolean {
        val n = refreshed(fallback)
        return n.text?.toString()?.contains(probe) == true
    }

    private fun trySend(fallback: AccessibilityNodeInfo, before: List<Spot>, attempt: Int, last: Boolean): Boolean {
        val root = findAppRoot()
        val focus = findFocusedInput() ?: fallback

        // IME の実行キー（ACTION_IME_ENTER）は true を返すのに実際には送信されない
        // アプリがある（Claude で確認・2026-08-14）。当てにせず、送信ボタンを押しに行く。
        // 位置だけで推測するボタンと IME_ENTER は最後の1回だけ使う（音声モードやモデル選択を押す事故の防止）
        val btn = findSendButton(root, focus, before, allowGuess = last)
        if (btn == null) {
            diag("attempt=$attempt send button not found")
            if (!last) return false
            dumpCandidates(root)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (focus.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    ) {
                        diag("attempt=$attempt fallback ime_enter")
                        return true
                    }
                } catch (_: Exception) {}
            }
            return false
        }
        val r = Rect()
        btn.getBoundsInScreen(r)

        // 2) ノードのクリック
        if (btn.isEnabled && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            diag("attempt=$attempt node click ok rect=$r desc=${btn.contentDescription}")
            return true
        }

        // 3) 実際に指で触るのと同じタップ。
        //    Compose 製アプリは ACTION_CLICK を無視することがあるので、こちらが最後の手段
        val ok = tapAt(r.exactCenterX(), r.exactCenterY())
        diag("attempt=$attempt gesture tap=$ok rect=$r enabled=${btn.isEnabled}")
        return ok
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
            if (label.isNotEmpty() && label.length <= 40 && !bad && sendWords.any { label.contains(it) }) {
                val t = clickableSelfOrParent(n)
                if (t != null) {
                    val tr = Rect()
                    t.getBoundsInScreen(tr)
                    if (nearInput(tr) && tr.centerY() > byLabelY) { byLabelY = tr.centerY(); byLabel = t }
                }
            }
            if (n.isClickable && n.isEnabled && n.isVisibleToUser && !n.isEditable &&
                r.width() > 0 && r.height() > 0 && !bad
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
        val n = searchByBounds(root, bounds) ?: searchInput(root) ?: return null
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

    private fun searchInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
