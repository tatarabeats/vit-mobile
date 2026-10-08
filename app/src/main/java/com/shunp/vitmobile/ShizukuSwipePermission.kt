package com.shunp.vitmobile

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Owned by MainActivity; never displays a permission prompt from the background service. */
internal class ShizukuSwipePermission(context: Context) {
    companion object {
        const val REQUEST_CODE = 0x5653
        // One request per app process, including activity recreation and binder restarts.
        private var requested = false
    }

    private val context = context.applicationContext
    private var active = false
    private val received = Shizuku.OnBinderReceivedListener { checkPermission() }
    private val result = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == REQUEST_CODE) {
            ShizukuSwipeLog.write(this.context, "permission result granted=${grant == PackageManager.PERMISSION_GRANTED}")
            InputAccessibilityService.instance?.refreshShizukuSwipe()
        }
    }

    fun start() {
        if (active) return
        active = true
        Shizuku.addRequestPermissionResultListener(result)
        Shizuku.addBinderReceivedListenerSticky(received)
        checkPermission()
    }

    fun stop() {
        active = false
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeRequestPermissionResultListener(result)
    }

    private fun checkPermission() {
        if (!active || !Shizuku.pingBinder()) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                InputAccessibilityService.instance?.refreshShizukuSwipe()
            } else if (!requested) {
                requested = true
                ShizukuSwipeLog.write(context, "permission request")
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            ShizukuSwipeLog.write(context, "permission unavailable ${e.javaClass.simpleName}")
        }
    }
}
