package com.shunp.vitmobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 「フィードバックを喋る」ための透明な入口。
 * 画面トリガを既定OFFにしたので、フィードバック録音もジェスチャーから叩けるようにする。
 * One Hand Operation+ → 別のジェスチャー → 「アプリを開く」→「VIT フィードバック」
 */
class FeedbackTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TRIGGER_FEEDBACK)
            )
        } catch (_: Exception) {}
        finish()
        overridePendingTransition(0, 0)
    }
}
