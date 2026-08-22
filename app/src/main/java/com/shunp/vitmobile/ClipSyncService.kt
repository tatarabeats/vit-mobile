package com.shunp.vitmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * PC の新しいコピーをシステムのクリップボードへ載せる。
 * Gボード公式APIは無いので、Gボードが監視しているシステム側に入れる。
 */
class ClipSyncService : Service() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSilent()
        if (!running) {
            running = true
            thread(name = "clip-sync", isDaemon = true) { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun startSilent() {
        val chanId = "vit_clip_min"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(chanId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(chanId, "PCクリップ", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, chanId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PCクリップ")
            .setContentText("Gボードへ同期")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(2, notif)
    }

    private fun loop() {
        while (running) {
            try {
                tick()
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun tick() {
        val base = Prefs.getClipUrl(this)
        val token = Prefs.getClipToken(this)
        if (base.isBlank() || token.isBlank()) return
        val conn = URL("$base/v1/items").openConnection() as HttpURLConnection
        conn.setRequestProperty("X-Clip-Token", token)
        conn.connectTimeout = 2500
        conn.readTimeout = 4000
        val body = conn.inputStream.bufferedReader().readText()
        val arr = JSONArray(body)
        if (arr.length() == 0) return
        val last = Prefs.getLastClipId(this)
        val newest = arr.getJSONObject(0)
        val newestId = newest.optString("id")
        if (newestId.isBlank() || newestId == last) return
        val pending = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == last) break
            pending.add(o)
        }
        val applyList = if (last.isBlank() || pending.size > 8) {
            listOf(newest)
        } else {
            pending.asReversed()
        }
        for (item in applyList) {
            applyItem(base, token, item)
            Thread.sleep(500)
        }
        Prefs.setLastClipId(this, newestId)
    }

    private fun applyItem(base: String, token: String, item: JSONObject) {
        val text = item.optString("text")
        val imageUrl = item.optString("image_url")
        var imageUri: Uri? = null
        if (imageUrl.isNotBlank()) {
            imageUri = downloadImage(base, token, imageUrl)
        }
        val done = Object()
        main.post {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = if (imageUri != null) {
                    grantGboard(imageUri)
                    val data = ClipData.newUri(contentResolver, "image", imageUri)
                    if (text.isNotBlank()) data.addItem(ClipData.Item(text))
                    data
                } else {
                    ClipData.newPlainText("text", text)
                }
                cm.setPrimaryClip(clip)
            } catch (_: Exception) {
            }
            synchronized(done) { done.notifyAll() }
        }
        synchronized(done) {
            try {
                done.wait(800)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun downloadImage(base: String, token: String, imageUrl: String): Uri? {
        return try {
            val abs = if (imageUrl.startsWith("http")) imageUrl else "$base$imageUrl"
            val conn = URL(abs).openConnection() as HttpURLConnection
            conn.setRequestProperty("X-Clip-Token", token)
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            val bytes = conn.inputStream.readBytes()
            if (bytes.isEmpty()) return null
            val dir = File(cacheDir, "clips").apply { mkdirs() }
            val file = File(dir, "gboard.png")
            file.writeBytes(bytes)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    private fun grantGboard(uri: Uri) {
        try {
            grantUriPermission(
                "com.google.android.inputmethod.latin",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        fun start(ctx: Context) {
            if (Prefs.getClipUrl(ctx).isBlank() || Prefs.getClipToken(ctx).isBlank()) return
            ctx.startForegroundService(Intent(ctx, ClipSyncService::class.java))
        }
    }
}
