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
            file.appendText("$line\n")
        } catch (_: Exception) {}
    }
}
