package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InputAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_PASTE = "com.shunp.vitmobile.ACTION_PASTE"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE) pasteOrSetText()
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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    private fun pasteOrSetText() {
        // フォーカス中のノードを優先。無ければツリー全探索。
        val node = findFocusedNode() ?: findInputNodeInTree() ?: return
        // ACTION_PASTE を無条件で試す（isEditable に依存しない）
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return
        // フォールバック: ACTION_SET_TEXT で既存テキスト + クリップボードを結合
        val clipText = getClipboardText() ?: return
        val existing = node.text?.toString() ?: ""
        val combined = if (existing.isEmpty()) clipText else "$existing $clipText"
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    /**
     * rootInActiveWindow + 他ウィンドウを再帰探索し、
     * 入力候補ノード（editable もしくは EditText 系 class / TextField / WebView 内 input）を見つける。
     */
    private fun findInputNodeInTree(): AccessibilityNodeInfo? {
        // rootInActiveWindow 優先
        rootInActiveWindow?.let { searchInput(it)?.let { n -> return n } }
        // 他ウィンドウも念のため見る（IME ウィンドウは除外）
        val windowList = try { windows } catch (_: Exception) { null } ?: return null
        for (w in windowList) {
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = w.root ?: continue
            searchInput(root)?.let { return it }
        }
        return null
    }

    private fun searchInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cn = node.className?.toString() ?: ""
        // 優先順1: focused + editable
        if (node.isFocused && node.isEditable) return node
        // 優先順2: focused + EditText / TextField 系（isEditable が false を返す Compose 対策）
        if (node.isFocused && (
            cn.contains("EditText", ignoreCase = true)
            || cn.contains("TextField", ignoreCase = true)
            || cn.contains("TextInput", ignoreCase = true)
        )) return node
        // 優先順3: editable
        if (node.isEditable) return node
        // 優先順4: EditText / TextField クラス名
        if (cn.contains("EditText") || cn.contains("TextField") || cn.contains("TextInput")) return node
        // 再帰
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchInput(child)
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
