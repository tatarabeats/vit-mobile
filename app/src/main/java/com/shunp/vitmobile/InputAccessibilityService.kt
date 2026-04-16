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
        val node = findFocusedEditable() ?: findEditableInTree() ?: return
        // まず ACTION_PASTE を試す
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) return
        // ACTION_PASTE 失敗 → ACTION_SET_TEXT でフォールバック
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

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    /** rootInActiveWindow を再帰探索して editable なノードを見つける */
    private fun findEditableInTree(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchEditable(root)
    }

    private fun searchEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchEditable(child)
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
