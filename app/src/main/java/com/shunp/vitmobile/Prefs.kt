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
    private const val KEY_TRIGGER = "trigger_mode"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_VOLUME_TRIGGER = "volume_trigger"
    private const val KEY_DTAP_MS = "double_tap_ms"
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_SCREEN_TRIGGER = "screen_trigger"

    /** 起動方法: "zone" = 透明ゾーンをダブルタップ（既定） / "mic" = マイクを常時表示 */
    const val TRIGGER_ZONE = "zone"
    const val TRIGGER_MIC = "mic"

    fun getTriggerMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TRIGGER, TRIGGER_ZONE) ?: TRIGGER_ZONE

    fun setTriggerMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRIGGER, mode).apply()
    }

    /**
     * 音量キー2回押しでも起動するか。
     * ACTION_OUTSIDE の座標が取れない端末（Android 12+ の制限）向けの逃げ道。
     * 画面のタッチは絶対に奪わないと決めたので、占有方式は持たない。
     */
    fun isVolumeTrigger(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOLUME_TRIGGER, false)

    fun setVolumeTrigger(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VOLUME_TRIGGER, on).apply()
    }

    /**
     * ダブルタップとみなす間隔（ミリ秒）。短いほど誤爆しない。
     * スクロールの指下ろしは間隔が空くので、ここを詰めるのが一番効く。
     */
    fun getDoubleTapMs(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DTAP_MS, 140)

    fun setDoubleTapMs(ctx: Context, ms: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DTAP_MS, ms).apply()
    }

    /**
     * 起動ゾーンを無効にするアプリ（パッケージ名・改行区切り）。
     * ダブルタップに別の意味があるアプリ（YouTubeの10秒送り、写真の拡大、SNSのいいね）で
     * 録音が始まると邪魔でしかない。
     */
    private val DEFAULT_EXCLUDED = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.twitter.android",
        "com.android.chrome",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.samsung.android.gallery3d",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
    ).joinToString("\n")

    fun getExcludedPackages(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXCLUDED, DEFAULT_EXCLUDED) ?: DEFAULT_EXCLUDED

    fun setExcludedPackages(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXCLUDED, text).apply()
    }

    fun isExcluded(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return getExcludedPackages(ctx).lineSequence()
            .map { it.trim() }
            .any { it.isNotEmpty() && it.equals(pkg, ignoreCase = true) }
    }

    /**
     * 画面のダブルタップで起動するか。**既定OFF**（2026-08-13）。
     * 他アプリのダブルタップ動作との干渉が多く、実用に耐えなかった。
     * 通常は One Hand Operation+ 等のジェスチャーから TriggerActivity を叩く。
     */
    fun isScreenTrigger(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_TRIGGER, false)

    fun setScreenTrigger(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCREEN_TRIGGER, on).apply()
    }

    fun getGithubToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GITHUB_TOKEN, null)

    fun setGithubToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GITHUB_TOKEN, token).apply()
    }

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
