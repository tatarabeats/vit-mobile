package com.shunp.vitmobile

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class VoiceRecorder(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun start(): Boolean {
        val file = File(ctx.cacheDir, "vit_${System.currentTimeMillis()}.m4a")
        currentFile = file
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(ctx)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        return try {
            r.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            true
        } catch (e: Exception) {
            toast("録音失敗: ${e.message}")
            try { r.release() } catch (_: Exception) {}
            false
        }
    }

    fun stopAndTranscribe(onResult: (String?) -> Unit) {
        val file = currentFile
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        if (file == null) { onResult(null); return }
        scope.launch {
            val text = transcribe(file)
            withContext(Dispatchers.Main) { onResult(text) }
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private suspend fun transcribe(file: File): String? = withContext(Dispatchers.IO) {
        val key = Prefs.getGroqKey(ctx)
        if (key.isNullOrBlank()) { toast("APIキー未設定"); return@withContext null }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("audio/m4a".toMediaType())
            )
            .addFormDataPart("model", "whisper-large-v3-turbo")
            .addFormDataPart("language", "ja")
            .addFormDataPart("response_format", "json")
            .build()

        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "VitMobile/0.1")
            .post(body)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    toast("Groq ${resp.code}")
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("text").trim()
            }
        } catch (e: Exception) {
            toast("Groq失敗: ${e.message}")
            null
        }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun release() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        scope.cancel()
    }
}
