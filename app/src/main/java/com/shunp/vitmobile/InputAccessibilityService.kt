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
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
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

        // attempt 1: ACTION_PASTE
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Log.d(TAG, "ACTION_PASTE ok")
            return
        }
        // attempt 2: focus → paste
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            Log.d(TAG, "ACTION_PASTE after focus ok")
            return
        }
        // attempt 3: ACTION_SET_TEXT（既存テキスト + 認識テキスト）
        val existing = node.text?.toString() ?: ""
        val combined = if (existing.isEmpty()) text else "$existing$text"
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        val r3 = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Log.d(TAG, "ACTION_SET_TEXT result=$r3 combinedLen=${combined.length}")
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
