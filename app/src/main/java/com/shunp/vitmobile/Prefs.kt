package com.shunp.vitmobile

import android.content.Context

object Prefs {
    private const val PREFS = "vit_prefs"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_ANTHROPIC = "anthropic_api_key"
    private const val KEY_LLM_FIX = "llm_fix_enabled"
    private const val KEY_DICT = "dictionary"
    private const val KEY_SNIPPETS = "snippets"

    // 初回起動時のデフォルトスニペット（駿平の和歌山シェアハウス住所）
    private const val DEFAULT_SNIPPETS = "住所|〒646-1402 和歌山県田辺市中辺路町近露1985"

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
            .getString(KEY_SNIPPETS, DEFAULT_SNIPPETS) ?: DEFAULT_SNIPPETS

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
}
