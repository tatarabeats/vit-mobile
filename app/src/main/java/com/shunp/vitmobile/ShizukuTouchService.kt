package com.shunp.vitmobile

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Instantiated by Shizuku as shell, NOT an Android Service. No app/UI state here. */
@Keep
class ShizukuTouchService : IShizukuTouchService.Stub() {
    private var process: Process? = null
    private var writer: ParcelFileDescriptor? = null

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
