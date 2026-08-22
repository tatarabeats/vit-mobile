package com.shunp.vitmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 端末を再起動しても自分で復帰する。
 * これが無いと再起動のたびにアプリを開いて「起動」を押す必要があり、
 * 「ゾーンをダブルタップするだけ」という前提が崩れる。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 端末の再起動に加えて、**アプリを更新した直後**にも自分で起動し直す。
        // 更新するたびに手で「起動」を押すのは無駄（駿平 2026-08-14）。
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        ClipSyncService.start(ctx)
        // 権限もキーも無い状態で起こすと通知だけ出て邪魔になるので、揃っている時だけ
        if (!Settings.canDrawOverlays(ctx)) return
        if (Prefs.getGroqKey(ctx).isNullOrBlank()) return
        ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
    }
}
