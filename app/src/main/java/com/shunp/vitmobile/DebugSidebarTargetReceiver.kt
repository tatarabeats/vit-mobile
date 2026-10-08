package com.shunp.vitmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Explicit adb broadcasts only; release builds cannot change the target. */
class DebugSidebarTargetReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.shunp.vitmobile.DEBUG_SIDEBAR_TARGET"
        private const val PREFS = "sidebar_debug"

        fun targetPackage(context: Context): String =
            if (BuildConfig.DEBUG) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("pkg", InputAccessibilityService.CLAUDE_PACKAGE)
                ?: InputAccessibilityService.CLAUDE_PACKAGE
            else InputAccessibilityService.CLAUDE_PACKAGE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val pkg = intent.getStringExtra("pkg") ?: return
        if (pkg.length > 255 || !pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pkg", pkg).apply()
        InputAccessibilityService.instance?.cancelSidebarSwipe()
        OverlayService.refreshClaudeSwipe()
        Log.i("VIT_SWIPE", "target=$pkg")
        // OverlayService is intentionally not exported. Start it from our own UID;
        // no Groq key or recording action is needed for the sidebar CI check.
        if (android.provider.Settings.canDrawOverlays(context)
            && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                context.startForegroundService(Intent(context, OverlayService::class.java))
            } catch (e: Exception) {
                Log.w("VIT_SWIPE", "overlay start failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
