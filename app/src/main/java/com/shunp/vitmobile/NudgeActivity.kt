package com.shunp.vitmobile

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * 何も表示しない透明な画面。一瞬だけ前に出てすぐ閉じ、下のアプリ（PayPay など）に「開き直した」を起こす。
 * AccPause が、ユーザー補助を切った直後に相手のアプリへ調べ直させるために使う。
 */
class NudgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }, 150)
    }
}
