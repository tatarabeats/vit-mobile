package com.shunp.vitmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * アプリ自身で更新する。
 * QRを読んで、ブラウザで落として、ファイルを開いて…という毎回の手間をなくすため
 * （駿平 2026-08-20）。GitHub の最新リリースを見に行き、新しければ通知を出す。
 * 通知を1回押せばダウンロードしてインストール画面まで進む。
 */
object Updater {
    private const val REPO = "tatarabeats/vit-mobile"
    private const val CHANNEL = "vit_update"
    private const val NOTIF_ID = 42

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Release(val version: String, val apkUrl: String)

    /** 数字だけを取り出して比べる。"0.9.15" > "0.9.9" を正しく判定する */
    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().trimStart('v')
            .split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun fetchLatest(): Release? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VitMobile")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val tag = json.optString("tag_name")
                val assets = json.optJSONArray("assets") ?: return null
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        break
                    }
                }
                if (tag.isBlank() || url.isNullOrBlank()) null else Release(tag, url!!)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 新しい版があれば通知を出す。常駐サービスから定期的に呼ぶ */
    fun checkAndNotify(ctx: Context) {
        scope.launch {
            val rel = fetchLatest() ?: return@launch
            if (!isNewer(rel.version, BuildInfo.versionName(ctx))) return@launch
            withContext(Dispatchers.Main) { notify(ctx, rel) }
        }
    }

    /**
     * アプリを開いた時の自動確認。新しければその場で聞く。
     * 通知を切っていても気づけるようにするため（駿平 2026-08-20）。
     */
    fun checkOnOpen(activity: android.app.Activity) {
        scope.launch {
            val rel = fetchLatest() ?: return@launch
            if (!isNewer(rel.version, BuildInfo.versionName(activity))) return@launch
            withContext(Dispatchers.Main) {
                if (activity.isFinishing) return@withContext
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("${rel.version} が出ています")
                    .setMessage("今すぐ更新する？")
                    .setPositiveButton("更新") { _, _ -> download(activity, rel) }
                    .setNegativeButton("あとで", null)
                    .show()
            }
        }
    }

    /** 画面から押された時。見つからなければその旨を出す */
    fun checkNow(ctx: Context) {
        scope.launch {
            val rel = fetchLatest()
            withContext(Dispatchers.Main) {
                if (rel == null) {
                    Toast.makeText(ctx, "確認できなかった", Toast.LENGTH_SHORT).show()
                } else if (!isNewer(rel.version, BuildInfo.versionName(ctx))) {
                    Toast.makeText(ctx, "最新です (${BuildInfo.versionName(ctx)})", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "${rel.version} を取得中…", Toast.LENGTH_SHORT).show()
                    download(ctx, rel)
                }
            }
        }
    }

    private fun notify(ctx: Context, rel: Release) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VIT 更新", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RUN_UPDATE)
            .putExtra("version", rel.version)
            .putExtra("url", rel.apkUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 77, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle("VIT ${rel.version} が出た")
            .setContentText("押すと更新")
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** APK を落としてインストール画面を開く */
    fun download(ctx: Context, rel: Release) {
        scope.launch {
            try {
                val req = Request.Builder().url(rel.apkUrl)
                    .header("User-Agent", "VitMobile").build()
                val file = File(ctx.cacheDir, "vit-update.apk")
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "ダウンロード失敗", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    file.outputStream().use { out ->
                        resp.body?.byteStream()?.copyTo(out)
                    }
                }
                withContext(Dispatchers.Main) { install(ctx, file) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "更新失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun install(ctx: Context, file: File) {
        try {
            // Android 8+ は「不明なアプリのインストール」の許可が要る。無ければその画面へ送る
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !ctx.packageManager.canRequestPackageInstalls()
            ) {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(ctx, "このアプリからのインストールを許可して、もう一度押す", Toast.LENGTH_LONG).show()
                return
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "インストール開始失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
