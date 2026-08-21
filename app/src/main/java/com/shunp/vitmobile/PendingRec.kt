package com.shunp.vitmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 録音中のポインタ。プロセス死亡・再起動で m4a を見失わないための印。
 * 中身は履歴にだけ入れる。挿入も自動送信もしない。
 */
object PendingRec {
    const val MARKER_NAME = "pending_rec.json"
    const val TEXT_NAME = "pending_text.json"
    /** 失敗したらあと1回だけ拾う。それ以上は捨てる */
    const val MAX_RETRIES = 1

    data class TextState(val text: String, val ts: Long, val path: String)

    data class State(
        val path: String,
        val startMs: Long,
        val durationMs: Long,
        val amps: List<Int>,
        val retries: Int
    )

    fun markerFile(ctx: Context): File = File(ctx.filesDir, MARKER_NAME)

    fun toJson(state: State): String {
        val o = JSONObject()
            .put("path", state.path)
            .put("startMs", state.startMs)
            .put("durationMs", state.durationMs)
            .put("retries", state.retries)
        val arr = JSONArray()
        for (a in state.amps) arr.put(a)
        o.put("amps", arr)
        return o.toString()
    }

    fun fromJson(raw: String): State? {
        return try {
            val o = JSONObject(raw)
            val path = o.optString("path")
            if (path.isBlank()) return null
            val ampsArr = o.optJSONArray("amps")
            val amps = mutableListOf<Int>()
            if (ampsArr != null) {
                for (i in 0 until ampsArr.length()) amps.add(ampsArr.optInt(i))
            }
            State(
                path = path,
                startMs = o.optLong("startMs"),
                durationMs = o.optLong("durationMs"),
                amps = amps,
                retries = o.optInt("retries")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun load(ctx: Context): State? {
        val f = markerFile(ctx)
        if (!f.exists()) return null
        val raw = try { f.readText() } catch (_: Exception) { return null }
        return fromJson(raw)
    }

    fun save(ctx: Context, state: State) {
        try { markerFile(ctx).writeText(toJson(state)) } catch (_: Exception) {}
    }

    fun write(
        ctx: Context,
        audio: File,
        startMs: Long,
        amps: List<Int>,
        durationMs: Long = 0L,
        retries: Int = 0
    ) {
        save(ctx, State(audio.absolutePath, startMs, durationMs, amps, retries))
    }

    /** この録音の印と音声を消す。他の録音の印は触らない */
    fun clearIfPath(ctx: Context, path: String) {
        val cur = load(ctx)
        try { File(path).delete() } catch (_: Exception) {}
        if (cur == null || cur.path == path) {
            try { markerFile(ctx).delete() } catch (_: Exception) {}
        }
    }

    fun clear(ctx: Context) {
        val cur = load(ctx)
        if (cur != null) {
            try { File(cur.path).delete() } catch (_: Exception) {}
        }
        try { markerFile(ctx).delete() } catch (_: Exception) {}
        clearText(ctx)
    }

    fun textFile(ctx: Context): File = File(ctx.filesDir, TEXT_NAME)

    fun saveText(ctx: Context, text: String, ts: Long, path: String) {
        if (text.isBlank()) return
        val o = JSONObject()
            .put("text", text)
            .put("ts", ts)
            .put("path", path)
        try { textFile(ctx).writeText(o.toString()) } catch (_: Exception) {}
    }

    fun takeText(ctx: Context): TextState? {
        val f = textFile(ctx)
        if (!f.exists()) return null
        val raw = try { f.readText() } catch (_: Exception) { return null }
        try { f.delete() } catch (_: Exception) {}
        return try {
            val o = JSONObject(raw)
            val text = o.optString("text")
            if (text.isBlank()) null
            else TextState(text, o.optLong("ts"), o.optString("path"))
        } catch (_: Exception) {
            null
        }
    }

    fun clearText(ctx: Context) {
        try { textFile(ctx).delete() } catch (_: Exception) {}
    }

    /**
     * 書き起こし失敗。あと1回残すか、諦めて消すか。
     * @return まだ再試行するなら true
     */
    fun noteFailure(ctx: Context, path: String): Boolean {
        val cur = load(ctx) ?: return false
        if (cur.path != path) return false
        val next = cur.retries + 1
        return if (next > MAX_RETRIES) {
            clearIfPath(ctx, path)
            false
        } else {
            save(ctx, cur.copy(retries = next))
            true
        }
    }
}
