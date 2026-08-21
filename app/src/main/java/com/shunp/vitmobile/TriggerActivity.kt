package com.shunp.vitmobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * 「録音を開始/確定するだけ」の透明な入口。
 *
 * One Hand Operation+（や他のジェスチャーアプリ）が指定できるのは **アプリの起動** だけなので、
 * アプリの姿をした入口を用意して、中身は OverlayService へトグルを投げるだけにする。
 * 画面には何も出ない（テーマが NoDisplay、onCreate 内で finish する）。
 *
 * 使い方: One Hand Operation+ → 好きなジェスチャー → 「アプリを開く」→「VIT 録音」
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceRecorder.recoverFrom(this)
        if (!Settings.canDrawOverlays(this) || Prefs.getGroqKey(this).isNullOrBlank()) {
            Toast.makeText(this, "VIT の初期設定が済んでいません", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        try {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TRIGGER)
            )
        } catch (_: Exception) {}
        finish()
        overridePendingTransition(0, 0)
    }
}
