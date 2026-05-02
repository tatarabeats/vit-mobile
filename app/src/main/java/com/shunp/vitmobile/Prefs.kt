package com.shunp.vitmobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {
    private const val PREFS = "vit_prefs"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val KEY_LLM_FIX = "llm_fix_enabled"
    private const val KEY_DICT = "dictionary"
    private const val KEY_SNIPPETS = "snippets"
    private const val KEY_HISTORY = "history_json"
    private const val MAX_HISTORY = 50

    fun getGroqKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GROQ, null)

    fun setGroqKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROQ, key).apply()
    }

    fun getAnthropicKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ANTHROPIC, null)

    fun setAnthropicKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ANTHROPIC, key).apply()
    }

    fun isLlmFixEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LLM_FIX, true)

    fun setLlmFixEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LLM_FIX, enabled).apply()
    }

    fun getDictionary(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DICT, "") ?: ""

    fun setDictionary(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DICT, text).apply()
    }

    fun getSnippets(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNIPPETS, "") ?: ""

    fun setSnippets(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SNIPPETS, text).apply()
    }

    /**
     * 認識結果に対してスニペット（ショートカット）を適用。
     * 形式: "トリガー|置換テキスト" を1行に1つ。
     * 完全一致（前後の句読点は無視）の場合のみ置換。
     */
    fun applySnippets(ctx: Context, recognized: String): String {
        val snippets = getSnippets(ctx)
        if (snippets.isBlank()) return recognized
        val trimmed = recognized.trim().trimEnd('。', '、', '.', ',', '!', '?', '！', '？', ' ', '　')
        for (line in snippets.lines()) {
            val parts = line.split("|", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.isEmpty()) continue
            if (trimmed == key || trimmed == "${key}。" || trimmed == key.trimEnd('。')) {
                return value
            }
        }
        return recognized
    }

    // --- 履歴（filesDir のJSONファイルに保存。SharedPreferencesと別管理でアップデート時の保持性向上） ---
    private fun historyFile(ctx: Context): File = File(ctx.filesDir, "history.json")

    /** 旧SharedPreferences版から history.json への一回限り移行 */
    private fun migrateLegacyHistoryIfNeeded(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = sp.getString(KEY_HISTORY, null) ?: return
        val file = historyFile(ctx)
        if (!file.exists()) {
            try { file.writeText(legacy) } catch (_: Exception) {}
        }
        sp.edit().remove(KEY_HISTORY).apply()
    }

    fun addHistory(ctx: Context, text: String) {
        if (text.isBlank()) return
        migrateLegacyHistoryIfNeeded(ctx)
        val file = historyFile(ctx)
        val raw = if (file.exists()) {
            try { file.readText() } catch (_: Exception) { "[]" }
        } else "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        newArr.put(JSONObject().put("ts", System.currentTimeMillis()).put("text", text))
        for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) {
            try { newArr.put(arr.getJSONObject(i)) } catch (_: Exception) {}
        }
        try { file.writeText(newArr.toString()) } catch (_: Exception) {}
    }

    /** Pair<タイムスタンプ(ミリ秒), テキスト> のリスト。新しい順 */
    fun getHistory(ctx: Context): List<Pair<Long, String>> {
        migrateLegacyHistoryIfNeeded(ctx)
        val file = historyFile(ctx)
        if (!file.exists()) return emptyList()
        val raw = try { file.readText() } catch (_: Exception) { return emptyList() }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.optLong("ts") to o.optString("text")
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clearHistory(ctx: Context) {
        try { historyFile(ctx).delete() } catch (_: Exception) {}
        // レガシーエントリも一応消す
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }
}
