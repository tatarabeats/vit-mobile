package com.shunp.vitmobile

import android.content.Context
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 使いながらフィードバックを送るための経路。
 * 透明ゾーンを長押し → 喋る → タップで送信 で、GitHub の Issue になる。
 * トークン未設定・送信失敗時は端末内 feedback.jsonl に積んで、次回まとめて送る。
 */
object Feedback {
    const val REPO = "tatarabeats/vit-mobile"
    private const val QUEUE_FILE = "feedback.jsonl"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 端末情報つきでフィードバックを送る。送れなければローカルに積む */
    fun submit(ctx: Context, text: String) {
        if (text.isBlank()) return
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("text", text)
            .put("version", BuildInfo.versionName(ctx))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        enqueue(ctx, entry)
        flush(ctx, quiet = false)
    }

    /** 溜まっているフィードバックを順に送る。成功したものはキューから消す */
    fun flush(ctx: Context, quiet: Boolean = true) {
        val token = Prefs.getGithubToken(ctx)
        if (token.isNullOrBlank()) {
            if (!quiet) toast(ctx, "端末に保存した（GitHubトークン未設定）")
            return
        }
        scope.launch {
            val pending = readQueue(ctx)
            if (pending.isEmpty()) return@launch
            val remaining = mutableListOf<JSONObject>()
            var sent = 0
            for (entry in pending) {
                if (postIssue(token, entry)) sent++ else remaining.add(entry)
            }
            writeQueue(ctx, remaining)
            if (!quiet) {
                withContext(Dispatchers.Main) {
                    val msg = if (sent > 0) "フィードバック送信 ($sent件)"
                    else "送信失敗。端末に保存した"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pendingCount(ctx: Context): Int = readQueue(ctx).size

    private fun postIssue(token: String, entry: JSONObject): Boolean {
        val text = entry.optString("text")
        val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(entry.optLong("ts")))
        val title = text.take(60).replace("\n", " ")
        val body = buildString {
            append(text).append("\n\n---\n")
            append("- 発話時刻: ").append(stamp).append("\n")
            append("- アプリ: v").append(entry.optString("version")).append("\n")
            append("- 端末: ").append(entry.optString("device")).append("\n")
        }
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("labels", JSONArray().put("feedback").put("mobile"))
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/issues")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VitMobile")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun queueFile(ctx: Context) = File(ctx.filesDir, QUEUE_FILE)

    private fun enqueue(ctx: Context, entry: JSONObject) {
        try { queueFile(ctx).appendText(entry.toString() + "\n") } catch (_: Exception) {}
    }

    private fun readQueue(ctx: Context): List<JSONObject> {
        val f = queueFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }.mapNotNull {
                try { JSONObject(it) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeQueue(ctx: Context, entries: List<JSONObject>) {
        try {
            if (entries.isEmpty()) queueFile(ctx).delete()
            else queueFile(ctx).writeText(entries.joinToString("\n") { it.toString() } + "\n")
        } catch (_: Exception) {}
    }

    private fun toast(ctx: Context, msg: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}

object BuildInfo {
    fun versionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
