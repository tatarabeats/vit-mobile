package com.shunp.vitmobile

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/** One updateMutex-owned attempt. A separate tag keeps screen-off/getevent cleanup independent. */
internal object ShizukuApkInstaller {
    suspend fun install(ctx: Context, file: File, beforeInstall: suspend () -> Unit): Boolean {
        val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuTouchService::class.java))
            .tag("silent_update").processNameSuffix("silent_update")
            // The calling app dies during self-update; do not kill pm along with it.
            .daemon(true).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        val connected = CompletableDeferred<IShizukuTouchService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    connected.complete(IShizukuTouchService.Stub.asInterface(binder))
                } else connected.completeExceptionally(IllegalStateException("null/dead user service"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("user service disconnected"))
            }
            override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
        }
        var binding = false
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Updater.log(ctx, "shizuku unavailable/permission denied; fallback")
                return false
            }
            withContext(Dispatchers.Main) {
                // SDK 13.1.5 caches connections; remove an earlier attempt's cache first.
                Shizuku.unbindUserService(args, null, false)
                binding = true
                Shizuku.bindUserService(args, connection)
            }
            val remote = withTimeoutOrNull(10_000L) { connected.await() }
            if (remote == null) {
                Updater.log(ctx, "shizuku bind timeout; fallback")
                return false
            }
            // Binding can take time. Recheck recording and permission just before the IPC.
            beforeInstall()
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ||
                !remote.asBinder().pingBinder()
            ) {
                Updater.log(ctx, "shizuku disconnected before install; fallback")
                return false
            }
            val code = withContext(Dispatchers.IO) {
                Updater.log(ctx, "shizuku install start size=${file.length()}")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    remote.installApk(it, file.length())
                }
            }
            // A logging RPC failure must not turn a successful install into a fallback.
            val output = runCatching { remote.getLastInstallOutput() }.getOrElse { "output unavailable: ${it.message}" }
            Updater.log(ctx, "shizuku install exit=$code output=$output")
            return code == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Updater.log(ctx, "shizuku failed ${e.javaClass.simpleName}: ${e.message}; fallback")
            return false
        } finally {
            connected.cancel()
            if (binding) withContext(NonCancellable + Dispatchers.Main) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                runCatching { Shizuku.unbindUserService(args, null, false) }
            }
        }
    }
}
