package com.shunp.vitmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shunp.vitmobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        /** 更新通知から開かれた時 */
        const val ACTION_RUN_UPDATE = "com.shunp.vitmobile.RUN_UPDATE"
    }

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.apiKeyInput.setText(Prefs.getGroqKey(this) ?: "")
        b.saveApiKey.setOnClickListener {
            val key = b.apiKeyInput.text.toString().trim()
            Prefs.setGroqKey(this, key)
            Toast.makeText(this, "Groq APIキーを保存しました", Toast.LENGTH_SHORT).show()
        }

        b.anthropicKeyInput.setText(Prefs.getAnthropicKey(this) ?: "")
        b.saveAnthropicKey.setOnClickListener {
            val key = b.anthropicKeyInput.text.toString().trim()
            Prefs.setAnthropicKey(this, key)
            Toast.makeText(this, "Anthropic APIキーを保存しました", Toast.LENGTH_SHORT).show()
        }

        b.llmFixSwitch.isChecked = Prefs.isLlmFixEnabled(this)
        b.llmFixSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setLlmFixEnabled(this, checked)
        }

        b.dictionaryInput.setText(Prefs.getDictionary(this))
        b.saveDictionary.setOnClickListener {
            Prefs.setDictionary(this, b.dictionaryInput.text.toString())
            Toast.makeText(this, "辞書を保存しました", Toast.LENGTH_SHORT).show()
        }

        b.snippetsInput.setText(Prefs.getSnippets(this))
        b.saveSnippets.setOnClickListener {
            Prefs.setSnippets(this, b.snippetsInput.text.toString())
            Toast.makeText(this, "ショートカットを保存しました", Toast.LENGTH_SHORT).show()
        }

        b.excludedInput.setText(Prefs.getExcludedPackages(this))
        b.saveExcluded.setOnClickListener {
            Prefs.setExcludedPackages(this, b.excludedInput.text.toString())
            Toast.makeText(this, "保存した", Toast.LENGTH_SHORT).show()
        }

        b.autoEnterInput.setText(Prefs.getAutoEnterPackages(this))
        b.saveAutoEnter.setOnClickListener {
            Prefs.setAutoEnterPackages(this, b.autoEnterInput.text.toString())
            Toast.makeText(this, "保存した", Toast.LENGTH_SHORT).show()
        }

        b.pickAutoEnter.setOnClickListener { pickAppForAutoEnter() }

        b.btnAutoSendDiag.setOnClickListener {
            val f = java.io.File(filesDir, "autosend.log")
            val body = if (f.exists()) f.readText().takeLast(4000) else ""
            AlertDialog.Builder(this)
                .setTitle("診断")
                .setMessage(if (body.isBlank()) "まだ記録がありません" else body)
                .setPositiveButton("コピー") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT diag", body))
                    Toast.makeText(this, "コピーした", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("消す") { _, _ -> try { f.delete() } catch (_: Exception) {} }
                .show()
        }

        b.githubTokenInput.setText(Prefs.getGithubToken(this) ?: "")
        b.saveGithubToken.setOnClickListener {
            Prefs.setGithubToken(this, b.githubTokenInput.text.toString().trim())
            Toast.makeText(this, "GitHubトークンを保存しました", Toast.LENGTH_SHORT).show()
            Feedback.flush(this)
        }

        b.btnSendFeedback.setOnClickListener {
            val n = Feedback.pendingCount(this)
            if (n == 0) {
                Toast.makeText(this, "未送信のフィードバックはありません", Toast.LENGTH_SHORT).show()
            } else {
                Feedback.flush(this, quiet = false)
            }
        }

        b.btnOverlayPerm.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        b.btnAccessibilityPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "「VIT Mobile テキスト挿入」をONにしてください",
                Toast.LENGTH_LONG
            ).show()
        }

        b.btnStartOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先にオーバーレイ権限をONにしてください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Prefs.getGroqKey(this).isNullOrBlank()) {
                Toast.makeText(this, "Groq APIキーを入力・保存してください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                Toast.makeText(
                    this,
                    "ユーザー補助で『VIT Mobile テキスト挿入』をONにしてください",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            val intent = Intent(this, OverlayService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "起動した", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        b.btnUpdate.text = "更新を確認  (v" + BuildInfo.versionName(this) + ")"
        b.btnUpdate.setOnClickListener { Updater.checkNow(this) }

        // 開いた時に自動で確認する（通知を切っていても気づけるように）
        Updater.checkOnOpen(this)

        // 更新通知から来た時は、そのまま取得からインストールまで進める
        if (intent?.action == ACTION_RUN_UPDATE) {
            val url = intent.getStringExtra("url")
            val ver = intent.getStringExtra("version") ?: ""
            if (!url.isNullOrBlank()) {
                Toast.makeText(this, ver + " を取得中…", Toast.LENGTH_SHORT).show()
                Updater.download(this, Updater.Release(ver, url))
            }
        }

        b.btnToggleAdvanced.setOnClickListener {
            val open = b.advancedBox.visibility != android.view.View.VISIBLE
            b.advancedBox.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            b.btnToggleAdvanced.text = if (open) "設定を閉じる" else "設定を開く"
        }

        b.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "停止しました", Toast.LENGTH_SHORT).show()
        }

    }

    /** インストール済みアプリから選んで、自動Enterの対象に追加する（アイコン付き） */
    private fun pickAppForAutoEnter() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map {
                Triple(
                    it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName,
                    it.activityInfo.packageName,
                    it.loadIcon(pm)
                )
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
        if (apps.isEmpty()) {
            Toast.makeText(this, "アプリ一覧を取得できなかった", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = object : android.widget.ArrayAdapter<Triple<String, String, android.graphics.drawable.Drawable>>(
            this, 0, apps
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val row = convertView as? android.widget.LinearLayout ?: android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = (resources.displayMetrics.density * 12).toInt()
                    setPadding(pad, pad, pad, pad)
                    addView(android.widget.ImageView(context).apply {
                        val sz = (resources.displayMetrics.density * 40).toInt()
                        layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply {
                            rightMargin = (resources.displayMetrics.density * 14).toInt()
                        }
                    })
                    addView(android.widget.TextView(context).apply {
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                    })
                }
                val (label, pkg, icon) = getItem(position)!!
                (row.getChildAt(0) as android.widget.ImageView).setImageDrawable(icon)
                (row.getChildAt(1) as android.widget.TextView).text = label
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("アプリを選ぶ")
            .setAdapter(adapter) { _, which ->
                val pkg = apps[which].second
                val cur = b.autoEnterInput.text.toString().trimEnd()
                if (!cur.lineSequence().any { it.trim() == pkg }) {
                    b.autoEnterInput.setText(if (cur.isBlank()) pkg else cur + "\n" + pkg)
                }
                Prefs.setAutoEnterPackages(this, b.autoEnterInput.text.toString())
                Toast.makeText(this, apps[which].first + " を追加した", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** OverlayService が動いているか。API 26+ では自分のサービスだけが返る */
    private fun isOverlayRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OverlayService::class.java.name }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myId = "$packageName/${InputAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(myId, ignoreCase = true) }
    }
}
