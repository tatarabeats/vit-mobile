package com.shunp.vitmobile

import android.content.Context
import android.util.Log
import java.io.File

internal object ShizukuSwipeLog {
    @Synchronized
    fun write(context: Context, message: String) {
        val line = "sidebar: shizuku ${message.replace('\n', ' ').replace('\r', ' ')}"
        Log.i("VIT_SWIPE", line)
        try {
            val file = File(context.filesDir, "autosend.log")
            if (file.length() > 40_000) file.writeText("")
            file.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " $line\n")  // 時刻が無く、直した後に効いたかを記録から確かめられなかった（2026-11-12 の点検）
        } catch (_: Exception) {}
    }
}
