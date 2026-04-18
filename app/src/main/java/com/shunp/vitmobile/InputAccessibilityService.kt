package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InputAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_PASTE = "com.shunp.vitmobile.ACTION_PASTE"
        private const val TAG = "VIT_ACC"
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
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    private fun pasteOrSetText() {
        Log.d(TAG, "=== pasteOrSetText ===")
        val node = findFocusedNode() ?: findInputNodeInTree()
        if (node == null) {
            Log.d(TAG, "no node found")
            return
        }
        Log.d(TAG, "node class=${node.className} pkg=${node.packageName} focused=${node.isFocused} editable=${node.isEditable}")
        Log.d(TAG, "node actions=${node.actionList.map { it.id to it.label }}")

        // attempt 1: ACTION_PASTE
        val p1 = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE result=$p1")
        if (p1) return

        // attempt 2: focus → paste
        val f = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Log.d(TAG, "ACTION_FOCUS result=$f")
        val p2 = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.d(TAG, "ACTION_PASTE retry result=$p2")
        if (p2) return

        // attempt 3: ACTION_SET_TEXT with combined text
        val clipText = getClipboardText() ?: run { Log.d(TAG, "clip empty"); return }
        val existing = node.text?.toString() ?: ""
        val combined = if (existing.isEmpty()) clipText else "$existing$clipText"
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        val p3 = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        Log.d(TAG, "ACTION_SET_TEXT result=$p3 len=${combined.length}")
    }

    private fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
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

    private fun searchInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cn = node.className?.toString() ?: ""
        if (node.isFocused && node.isEditable) return node
        if (node.isFocused && (
            cn.contains("EditText", ignoreCase = true)
            || cn.contains("TextField", ignoreCase = true)
            || cn.contains("TextInput", ignoreCase = true)
        )) return node
        if (node.isEditable) return node
        if (cn.contains("EditText") || cn.contains("TextField") || cn.contains("TextInput")) return node
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
