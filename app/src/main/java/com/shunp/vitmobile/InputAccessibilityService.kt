package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InputAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_PASTE = "com.shunp.vitmobile.ACTION_PASTE"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE) pasteClipboard()
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

    private fun pasteClipboard() {
        val node = findFocusedEditable() ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
