package com.shunp.vitmobile

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Instantiated by Shizuku as shell, NOT an Android Service. No app/UI state here. */
@Keep
class ShizukuTouchService : IShizukuTouchService.Stub() {
    private var process: Process? = null
    private var writer: ParcelFileDescriptor? = null
    private val installLock = Any()
    @Volatile private var installOutput = ""

    override fun getLastInstallOutput(): String = installOutput

    override fun runShell(cmd: String): Int {
        // No arbitrary shell/interpolation: only this app's one-time settings grant.
        require(Regex("pm grant --user [0-9]+ com\\.shunp\\.vitmobile android\\.permission\\.WRITE_SECURE_SETTINGS").matches(cmd))
        val child = ProcessBuilder(listOf("/system/bin/pm") + cmd.split(' ').drop(1))
            .redirectErrorStream(true).redirectOutput(java.io.File("/dev/null")).start()
        return try {
            if (child.waitFor(10, TimeUnit.SECONDS)) child.exitValue() else -1
        } finally { child.destroyForcibly() }
    }

    // Separate lock/process: installing must not stop or hold the getevent lifecycle lock.
    override fun installApk(apk: ParcelFileDescriptor, size: Long): Int = synchronized(installLock) {
        installOutput = ""
        val output = StringBuffer()
        fun record(text: String) = synchronized(output) {
            val remaining = 16 * 1024 - output.length
            if (remaining > 0) output.append(text.take(remaining))
            Unit
        }
        var child: Process? = null
        try {
            apk.use {
                require(size in 1..(100 * 1024 * 1024L) && apk.statSize == size) {
                    "APK descriptor size mismatch"
                }
                val installer = ProcessBuilder("/system/bin/pm", "install", "-r", "-S", size.toString())
                    .redirectErrorStream(true).start()
                child = installer
                val copyError = AtomicReference<Exception?>()
                val inputThread = thread(name = "vit-apk-input", isDaemon = true) {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(apk).use { input ->
                            installer.outputStream.use { out ->
                                check(input.copyTo(out) == size) { "APK stream size mismatch" }
                            }
                        }
                    } catch (e: Exception) {
                        copyError.set(e)
                        record("\nAPK input: ${e.javaClass.simpleName}: ${e.message}")
                        runCatching { installer.outputStream.close() }
                    }
                }
                // Drain concurrently, even after the retained output cap, to avoid a full pipe deadlock.
                val outputThread = thread(name = "vit-apk-output", isDaemon = true) {
                    try {
                        installer.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(2048)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                record(String(buffer, 0, count))
                            }
                        }
                    } catch (e: Exception) { record("\nAPK output: ${e.message}") }
                }
                if (!installer.waitFor(2, TimeUnit.MINUTES)) {
                    record("\npm install timed out")
                    installer.destroyForcibly()
                    -1
                } else {
                    inputThread.join(1_000)
                    outputThread.join(1_000)
                    val exit = installer.exitValue()
                    if (exit == 0 && (copyError.get() != null || inputThread.isAlive)) -1 else exit
                }
            }
        } catch (e: Exception) {
            record("\ninstall: ${e.javaClass.simpleName}: ${e.message}")
            -1
        } finally {
            runCatching { child?.destroyForcibly() }
            installOutput = synchronized(output) { output.toString() }
        }
    }

    override fun listDevices(): ParcelFileDescriptor = launch("-pl")

    override fun readEvents(device: String): ParcelFileDescriptor {
        require(Regex("/dev/input/event[0-9]+").matches(device))
        // Kernel timestamps keep queued events from becoming a false fast swipe.
        return launch("-lt", device)
    }

    @Synchronized
    private fun launch(vararg arguments: String): ParcelFileDescriptor {
        stopProcess()
        val child = ProcessBuilder(listOf("/system/bin/getevent") + arguments)
            .redirectErrorStream(true).start()
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) {
            child.destroy()
            throw e
        }
        process = child
        writer = pipe[1]
        thread(name = "vit-getevent-pipe", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    child.inputStream.use { it.copyTo(output) }
                }
            } catch (_: Exception) {
                // Normal on screen-off, client disconnect, or getevent exit.
            } finally {
                child.destroy()
                synchronized(this) {
                    if (process === child) {
                        process = null
                        writer = null
                    }
                }
            }
        }
        return pipe[0]
    }

    @Synchronized
    private fun stopProcess() {
        process?.destroy()
        process = null
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    override fun destroy() {
        stopProcess()
        exitProcess(0)
    }
}
