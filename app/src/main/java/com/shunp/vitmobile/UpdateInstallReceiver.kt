package com.shunp.vitmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** PackageInstallerに渡した明示的なPendingIntentからのみ受信する。 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_INSTALL_RESULT) return
        val pending = goAsync()
        Updater.onInstallResult(context, intent) { pending.finish() }
    }
}
