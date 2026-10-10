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
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_AUTO_ENTER = "auto_enter_packages"
    private const val KEY_AUTO_ENTER_ON = "auto_enter_enabled"
    private const val KEY_CLAUDE_SWIPE = "claude_sidebar_swipe"
    private const val KEY_SECURE_APPS = "secure_app_packages"
    private const val KEY_SECURE_STATE = "secure_app_state"

    // Verified Google Play IDs (2026-10-08); see docs/secure-apps.md for sources.
    // Olive uses the SMBC/Vpass apps; PayPay Card uses the PayPay app.
    private val DEFAULT_SECURE_APPS = listOf(
        "com.smbc_card.vpass", // 三井住友カード Vpass
        "jp.co.smbc.direct", // 三井住友銀行 / Olive
        "jp.co.rakuten.kc.rakutencardapp.android", // 楽天カード
        "jp.co.rakuten_bank.rakutenbank", // 楽天銀行
        "jp.ne.paypay.android.app", // PayPay / PayPayカード
        "jp.co.japannetbank.smtapp.balance", // PayPay銀行
        "jp.co.jcb.my", // MyJCB
        "jp.co.saisoncard.android.saisonportal", // セゾンPortal
        "jp.co.eposcard.epossupportapp", // エポス
        "jp.co.aeon.credit.android.wallet", // イオンウォレット / AEON Pay
        "com.nttdocomo.dcard", // dカード
        "jp.auone.wallet", // au PAY
        "jp.mufg.bk.applisp.app", // 三菱UFJ銀行
        "jp.co.mizuhobank.banking", // みずほ銀行
        "jp.co.resona_gr.ss.SmartApp", // りそなグループ
        "jp.japanpost.jp_bank.bankbookapp", // ゆうちょ通帳
        "jp.japanpost.jp_bank.FIDOapp", // ゆうちょ認証
        "jp.co.netbk", // 住信SBIネット銀行
        "net.moneykit.SonyBankApp", // ソニー銀行
        "jp.co.sbisec.hyperkabu2", // SBI証券 株
        "jp.co.rakuten_sec.ispeed", // 楽天証券 iSPEED
        "jp.co.mobileit.ispeed_fx", // 楽天証券 iSPEED FX
        "jp.co.monex.comprehensive", // マネックス証券
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "jp.co.rakuten.pay", // 楽天ペイ
        "com.nttdocomo.keitai.payment", // d払い
        "jp.co.jibunbank.jibunmain", // auじぶん銀行
        "jp.co.sevenbank.appMysevenbank", // Myセブン銀行
        "jp.co.aeonbank.android.passbook", // イオン銀行
        "jp.co.rakuten_bank.sapp_jre", // JRE BANK
        "com.MinnaNoGinko.bankapp", // みんなの銀行
        "jp.mufg.cr.app6", // NICOSカード
        "jp.mufg.cr.app4", // MDC
        "com.smbc_card.vpoint", // VポイントPay
        "jp.mufg.cr.fam.brand.app1", // グローバルポイント Wallet
    ).joinToString("\n")

    fun getSecureAppPackages(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SECURE_APPS, DEFAULT_SECURE_APPS) ?: DEFAULT_SECURE_APPS

    fun setSecureAppPackages(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SECURE_APPS, text).apply()
    }

    fun isSecureApp(ctx: Context, pkg: String): Boolean = getSecureAppPackages(ctx)
        .lineSequence().any { it.trim().equals(pkg, ignoreCase = true) }

    internal fun getSecureAppState(ctx: Context): SecureAppState? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SECURE_STATE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            SecureAppState(
                DebugSettings(json.getInt("development"), json.getInt("usb"), json.getInt("wifi")),
                json.getString("phase"), json.optInt("attempts"), json.optLong("retryAt"),
            )
        } catch (e: Exception) {
            // Do not crash accessibility or guess which switches were enabled.
            SecureAppsController.log(ctx, "invalid recovery journal ${e.javaClass.simpleName}; original settings unknown")
            null
        }
    }

    /** Synchronous commit: no STOP/settings writes until the recovery journal is on disk. */
    internal fun setSecureAppState(ctx: Context, state: SecureAppState?): Boolean {
        val edit = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (state == null) edit.remove(KEY_SECURE_STATE)
        else edit.putString(KEY_SECURE_STATE, JSONObject()
            .put("development", state.original.development).put("usb", state.original.usb)
            .put("wifi", state.original.wifi).put("phase", state.phase)
            .put("attempts", state.startAttempts).put("retryAt", state.retryAt).toString())
        return edit.commit()
    }

    /** Shizuku（thedjchi 版）の START/STOP インテントに付ける合言葉。Shizuku の「インテントを表示」画面から自動で覚える */
    fun getShizukuAuth(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("shizuku_auth", "") ?: ""

    fun setShizukuAuth(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("shizuku_auth", token).apply()
    }

    fun isClaudeSwipeEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLAUDE_SWIPE, true)

    fun setClaudeSwipeEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLAUDE_SWIPE, enabled).apply()
    }

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
     * 挿入した後に Enter まで送るアプリ（パッケージ名・改行区切り）。
     * AIチャットは「喋る→挿入→送信」まで一息で終わらせたい（駿平 2026-08-13）。
     * 全アプリ一律にすると、検索欄や本文途中で勝手に確定してしまうのでアプリ単位にする。
     */
    private val DEFAULT_AUTO_ENTER = listOf(
        "com.anthropic.claude",
        "com.openai.chatgpt",
        "com.google.android.apps.bard",
        "ai.perplexity.app.android",
    ).joinToString("\n")

    fun getAutoEnterPackages(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_ENTER, DEFAULT_AUTO_ENTER) ?: DEFAULT_AUTO_ENTER

    fun setAutoEnterPackages(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTO_ENTER, text).apply()
    }

    /** 自動送信そのものの ON/OFF。対象アプリの一覧より上位の判定 */
    fun isAutoEnterEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENTER_ON, true)

    fun setAutoEnterEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ENTER_ON, on).apply()
    }

    fun isAutoEnter(ctx: Context, pkg: String?): Boolean =
        isAutoEnterEnabled(ctx) && isAutoEnterApp(ctx, pkg)

    /** 自動送信の一覧に載っているアプリか（ON/OFF に関係なく）。チャット系として入力欄を自分で選んでよい */
    fun isAutoEnterApp(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return getAutoEnterPackages(ctx).lineSequence()
            .map { it.trim() }
            .any { it.isNotEmpty() && it.equals(pkg, ignoreCase = true) }
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

    private val historyLock = Any()

    /** 書き込み途中で落ちても履歴全体が壊れないよう、別ファイルに書いてから差し替える */
    private fun writeHistoryAtomic(file: File, body: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(file)) {
            file.writeText(body)
            tmp.delete()
        }
    }

    /** 並行して2つの書き起こしが終わっても、片方の履歴が消えないよう直列化する */
    fun addHistory(ctx: Context, text: String, ts: Long = System.currentTimeMillis()) =
        synchronized(historyLock) { addHistoryLocked(ctx, text, ts) }

    private fun addHistoryLocked(ctx: Context, text: String, ts: Long) {
        if (text.isBlank()) return
        migrateLegacyHistoryIfNeeded(ctx)
        val file = historyFile(ctx)
        val raw = if (file.exists()) {
            try { file.readText() } catch (_: Exception) { "[]" }
        } else "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        if (arr.length() > 0) {
            try {
                val newest = arr.getJSONObject(0)
                if (newest.optString("text") == text &&
                    kotlin.math.abs(newest.optLong("ts") - ts) < 5000
                ) return
            } catch (_: Exception) {}
        }
        val newArr = JSONArray()
        newArr.put(JSONObject().put("ts", ts).put("text", text))
        for (i in 0 until minOf(arr.length(), MAX_HISTORY - 1)) {
            try { newArr.put(arr.getJSONObject(i)) } catch (_: Exception) {}
        }
        try { writeHistoryAtomic(file, newArr.toString()) } catch (_: Exception) {}
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
