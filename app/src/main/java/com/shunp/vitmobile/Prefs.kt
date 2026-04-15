package com.shunp.vitmobile

import android.content.Context

object Prefs {
    private const val PREFS = "vit_prefs"
    private const val KEY_GROQ = "groq_api_key"

    fun getGroqKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GROQ, null)

    fun setGroqKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROQ, key).apply()
    }
}
