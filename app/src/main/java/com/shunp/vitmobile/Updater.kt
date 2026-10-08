package com.shunp.vitmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** サービス・画面・手動確認で共通の自己更新処理。VIT独自の確認は出さない。 */
object Updater {
    private const val REPO = "tatarabeats/vit-mobile"
    private const val CHANNEL = "vit_update"
    private const val NOTIF_ID = 42
    internal const val ACTION_INSTALL_RESULT = "com.shunp.vitmobile.INSTALL_RESULT"
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_MS = 30_000L
    private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
    private const val MIN_APK_BYTES = 1024 * 1024L
    private const val MAX_APK_BYTES = 100 * 1024 * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateMutex = Mutex()
    // Receiverがネットワーク処理/録音待ちでgoAsyncの期限を超えないよう別のロックにする。
    private val sessionMutex = Mutex()
    private var confirmationJob: Job? = null
    private var permissionRetryJob: Job? = null
    private var sessionRetryJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private data class Release(val version: String, val apkUrl: String, val size: Long)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vit_updater", Context.MODE_PRIVATE)
    private fun apkFile(ctx: Context) = File(ctx.filesDir, "updates/vit-update.apk")

    /** 数字だけを取り出して比べる。"0.9.15" > "0.9.9" を正しく判定する。 */
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

    private fun fetchLatest(): Release {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VitMobile")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("release HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: throw IOException("release body missing"))
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: throw IOException("release assets missing")
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optString("name").endsWith(".apk")) continue
                val url = asset.optString("browser_download_url")
                if (tag.isBlank() || !url.startsWith("https://github.com/$REPO/releases/download/")) {
                    throw IOException("invalid release metadata")
                }
                return Release(tag, url, asset.optLong("size", 0L))
            }
            throw IOException("release APK missing")
        }
    }

    /** 互換の名前を維持。通常は通知せず、取得からインストールまで進む。 */
    fun checkAndNotify(ctx: Context) = requestUpdate(ctx, manual = false)
    fun checkOnOpen(activity: android.app.Activity) = requestUpdate(activity, manual = false)
    fun checkNow(ctx: Context) = requestUpdate(ctx, manual = true)

    /** 不明なアプリの許可画面から戻った時は、取得済みAPKで再開する。 */
    fun resumeAfterPermission(ctx: Context) {
        if (prefs(ctx).getBoolean("waiting_permission", false) &&
            ctx.packageManager.canRequestPackageInstalls()
        ) requestUpdate(ctx, manual = false, cachedOnly = true)
    }

    private fun requestUpdate(ctx: Context, manual: Boolean, cachedOnly: Boolean = false) {
        val app = ctx.applicationContext
        scope.launch {
            if (!updateMutex.tryLock()) {
                if (manual) toast(app, "更新処理中です（録音中は終了後に続けます）")
                return@launch
            }
            try {
                if (sessionMutex.withLock { hasPendingSession(app) }) {
                    if (manual) toast(app, "Androidの更新確認を待っています")
                    return@launch
                }
                // 設定画面でプロセスが再生成されても、許可待ちのAPKはネット接続なしで再開。
                val resumeCached = cachedOnly || prefs(app).getBoolean("waiting_permission", false)
                val rel = if (resumeCached) cachedRelease(app) ?: fetchLatest() else fetchLatest()
                if (!isNewer(rel.version, BuildInfo.versionName(app))) {
                    app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
                    prefs(app).edit().putBoolean("waiting_permission", false).apply()
                    if (manual) toast(app, "最新です")
                    return@launch
                }
                val file = download(app, rel)
                waitForRecording()
                if (!app.packageManager.canRequestPackageInstalls()) {
                    val alreadyAsked = prefs(app).getBoolean("waiting_permission", false)
                    prefs(app).edit().putBoolean("waiting_permission", true).commit()
                    retryAfterPermission(app)
                    if (!alreadyAsked || manual) {
                        withContext(Dispatchers.Main) {
                            app.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                    .setData(Uri.parse("package:${app.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        reportFailure(app, "install permission required")
                    }
                    return@launch
                }
                prefs(app).edit().putBoolean("waiting_permission", false).apply()
                install(app, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportFailure(app, "update: ${e.javaClass.simpleName}: ${e.message}")
                if (manual) toast(app, "更新できませんでした。もう一度お試しください")
            } finally {
                updateMutex.unlock()
            }
        }
    }

    private fun cachedRelease(ctx: Context): Release? {
        val p = prefs(ctx)
        val version = p.getString("downloaded_version", null) ?: return null
        val url = p.getString("downloaded_url", null) ?: return null
        return Release(version, url, p.getLong("downloaded_size", 0L))
    }

    /** サービスから設定を開いた場合も、戻り先のActivityに依存せず再開する。 */
    private fun retryAfterPermission(ctx: Context) {
        if (permissionRetryJob?.isActive == true) return
        permissionRetryJob = scope.launch {
            repeat(20) {
                delay(RETRY_MS)
                if (ctx.packageManager.canRequestPackageInstalls()) {
                    requestUpdate(ctx, manual = false, cachedOnly = true)
                    return@launch
                }
            }
        }
    }

    private fun download(ctx: Context, rel: Release): File {
        val file = apkFile(ctx)
        val cached = cachedRelease(ctx)
        if (cached != null && cached.version == rel.version && cached.apkUrl == rel.apkUrl &&
            file.isFile && file.length() == cached.size && (rel.size <= 0 || rel.size == cached.size)
        ) {
            try {
                validateApk(ctx, file)
                return file
            } catch (_: IOException) {
                // 破損したキャッシュだけ再取得する。
            }
        }
        val dir = file.parentFile ?: throw IOException("update directory missing")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create update directory")
        val temp = File(dir, "vit-update.part.apk")
        try {
            val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "VitMobile").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("APK HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("APK body missing")
                val expected = body.contentLength()
                var copied = 0L
                body.byteStream().use { input ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_APK_BYTES) throw IOException("APK too large")
                            out.write(buffer, 0, count)
                        }
                        out.fd.sync()
                    }
                }
                if ((expected >= 0 && copied != expected) || (rel.size > 0 && copied != rel.size)) {
                    throw IOException("APK size mismatch")
                }
            }
            validateApk(ctx, temp)
            // 同じディレクトリ内のrename。検証完了前に正常なAPKを上書きしない。
            if (!temp.renameTo(file)) throw IOException("cannot replace APK")
            if (!prefs(ctx).edit().putString("downloaded_version", rel.version)
                    .putString("downloaded_url", rel.apkUrl).putLong("downloaded_size", file.length()).commit()
            ) throw IOException("cannot save downloaded version")
            return file
        } finally {
            temp.delete()
        }
    }

    @Suppress("DEPRECATION") // int flags overloadはminSdk 26でも利用可能。
    private fun validateApk(ctx: Context, file: File) {
        if (file.length() !in MIN_APK_BYTES..MAX_APK_BYTES) throw IOException("APK size out of range")
        val info = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("invalid APK")
        if (info.packageName != ctx.packageName) throw IOException("APK package mismatch")
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (versionCode(info) <= versionCode(installed)) throw IOException("APK versionCode is not newer")
        // 更新署名の一致はPackageInstallerが検証する。
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private suspend fun waitForRecording() {
        while (OverlayService.isRecordingNow) delay(RETRY_MS)
    }

    private suspend fun install(ctx: Context, file: File) {
        waitForRecording()
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            sessionMutex.withLock {
                if (!prefs(ctx).edit().putInt("session_id", sessionId)
                        .putString("session_phase", "staging")
                        .putLong("session_started", System.currentTimeMillis()).commit()
                ) throw IOException("cannot save install session")
            }
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                // API 26–30は既定でmutable。IMMUTABLEでは結果extrasが届かない。
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                // 過去の失敗通知は再試行だけでは消さない。通知タップ/更新完了後の最新確認で解除。
                while (true) {
                    // 録音開始もMainスレッド。書き込み中に録音が始まった場合もcommitを延期する。
                    val committed = sessionMutex.withLock {
                        // commitでプロセスが終了しても、次回起動時に状態を識別できるよう先に保存。
                        if (!prefs(ctx).edit().putString("session_phase", "committed")
                                .putLong("session_started", System.currentTimeMillis()).commit()
                        ) throw IOException("cannot save commit state")
                        val sent = withContext(Dispatchers.Main) {
                            if (OverlayService.isRecordingNow) false else {
                                session.commit(result.intentSender)
                                true
                            }
                        }
                        if (sent) scheduleSessionRetry(ctx, SESSION_TIMEOUT_MS)
                        else prefs(ctx).edit().putString("session_phase", "staging").commit()
                        sent
                    }
                    if (committed) break
                    delay(RETRY_MS)
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            sessionMutex.withLock {
                if (prefs(ctx).getInt("session_id", -1) == sessionId) clearSession(ctx)
            }
            throw e
        }
    }

    /** プロセス再起動後も二重commitを防ぐ。消失・30分経過したセッションは再試行可能に。 */
    private fun hasPendingSession(ctx: Context): Boolean {
        val p = prefs(ctx)
        val id = p.getInt("session_id", -1)
        if (id < 0) return false
        val installer = ctx.packageManager.packageInstaller
        val info = installer.getSessionInfo(id)
        val age = System.currentTimeMillis() - p.getLong("session_started", 0L)
        val phase = p.getString("session_phase", null)
        // staging中はupdateMutexを保持するため、ここに来るstagingは前プロセスの残骸。
        val lostWork = phase == "staging" || (phase == "confirmation" && confirmationJob == null)
        if (info != null && !lostWork &&
            (confirmationJob?.isActive == true || age in 0 until SESSION_TIMEOUT_MS)
        ) {
            scheduleSessionRetry(ctx, if (confirmationJob?.isActive == true) RETRY_MS else SESSION_TIMEOUT_MS - age)
            return true
        }
        if (info != null) installer.abandonSession(id)
        clearSession(ctx)
        if (info != null) reportFailure(ctx, "recover install session=$id phase=$phase age=$age")
        return false
    }

    /** コールバックを失った場合も6時間後まで待たずに取得済みAPKで再試行する。 */
    private fun scheduleSessionRetry(ctx: Context, delayMs: Long) {
        sessionRetryJob?.cancel()
        sessionRetryJob = scope.launch {
            delay(delayMs.coerceAtLeast(RETRY_MS))
            requestUpdate(ctx, manual = false, cachedOnly = true)
        }
    }

    private fun clearSession(ctx: Context) {
        confirmationJob?.cancel()
        confirmationJob = null
        sessionRetryJob?.cancel()
        sessionRetryJob = null
        prefs(ctx).edit().remove("session_id").remove("session_started").remove("session_phase").commit()
    }

    /** ReceiverのgoAsyncを速やかに終了させる。録音待ちは別Jobで行う。 */
    internal fun onInstallResult(ctx: Context, intent: Intent, finished: () -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                sessionMutex.withLock {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (id < 0 || id != prefs(app).getInt("session_id", -1)) return@withLock
                    try {
                        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                val confirmation = confirmationIntent(intent)
                                    ?: throw IOException("confirmation Intent missing")
                                if (!prefs(app).edit().putString("session_phase", "confirmation").commit()) {
                                    throw IOException("cannot save confirmation state")
                                }
                                if (confirmationJob == null) {
                                    confirmationJob = scope.launch { confirmWhenIdle(app, id, confirmation) }
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS -> Unit // アプリが入れ替わるため起動等はしない。
                            else -> throw IOException("install status=$status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                        }
                    } catch (e: Exception) {
                        runCatching { app.packageManager.packageInstaller.abandonSession(id) }
                        clearSession(app)
                        reportFailure(app, "install callback: ${e.message}")
                    }
                }
            } finally {
                finished()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

    private suspend fun confirmWhenIdle(ctx: Context, id: Int, confirmation: Intent) {
        try {
            while (true) {
                waitForRecording()
                val done = sessionMutex.withLock {
                    if (prefs(ctx).getInt("session_id", -1) != id) return@withLock true
                    val shown = withContext(Dispatchers.Main) {
                        if (OverlayService.isRecordingNow) false else {
                            ctx.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            true
                        }
                    }
                    if (shown) {
                        // 長い録音待ちをOS確認画面のタイムアウトに含めない。
                        prefs(ctx).edit().putLong("session_started", System.currentTimeMillis()).commit()
                        scheduleSessionRetry(ctx, SESSION_TIMEOUT_MS)
                    }
                    shown
                }
                if (done) return
                delay(RETRY_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionMutex.withLock {
                if (prefs(ctx).getInt("session_id", -1) == id) {
                    runCatching { ctx.packageManager.packageInstaller.abandonSession(id) }
                    confirmationJob = null // 自分自身のJobをキャンセルしない。
                    clearSession(ctx)
                    reportFailure(ctx, "confirmation: ${e.message}")
                }
            }
        }
    }

    private suspend fun toast(ctx: Context, message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    private fun reportFailure(ctx: Context, message: String) {
        val line = "${System.currentTimeMillis()} ${message.replace(Regex("[\\r\\n]+"), " ")}\n"
        try {
            File(ctx.filesDir, "update.log").appendText(line)
        } catch (e: Exception) {
            Log.w("VitUpdater", "Cannot write update.log", e)
        }
        try {
            notify(ctx)
        } catch (e: Exception) {
            Log.w("VitUpdater", "Cannot show update notification", e)
        }
    }

    /** 自動更新失敗時だけの保険。外部Intentから任意のAPK URLを受け取らない。 */
    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VIT 更新", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RUN_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle("VITの自動更新を完了できませんでした")
            .setContentText("タップして更新を再試行")
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build())
    }
}
