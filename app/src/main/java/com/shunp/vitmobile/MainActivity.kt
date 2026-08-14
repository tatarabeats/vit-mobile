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

        // 画面のダブルタップで起動するか（既定OFF。ジェスチャー起動が主役）
        b.zoneModeSwitch.isChecked = Prefs.isScreenTrigger(this)
        b.zoneModeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setScreenTrigger(this, checked)
            if (isOverlayRunning()) {
                stopService(Intent(this, OverlayService::class.java))
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }

        b.zonePassiveSwitch.isChecked = Prefs.isVolumeTrigger(this)
        b.zonePassiveSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setVolumeTrigger(this, checked)
            Toast.makeText(
                this,
                if (checked) "音量キー2回押しでも起動する" else "音量キー2回押しをやめた",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ダブルタップ判定の詰め幅（100〜300ms）。誤爆が続くならここを短くする
        b.dtapSeek.progress = (Prefs.getDoubleTapMs(this) - 100).coerceIn(0, 200)
        b.dtapLabel.text = "ダブルタップの判定: ${Prefs.getDoubleTapMs(this)}ms"
        b.dtapSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                b.dtapLabel.text = "ダブルタップの判定: ${v + 100}ms"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                Prefs.setDoubleTapMs(this@MainActivity, (sb?.progress ?: 40) + 100)
            }
        })

        b.excludedInput.setText(Prefs.getExcludedPackages(this))
        b.saveExcluded.setOnClickListener {
            Prefs.setExcludedPackages(this, b.excludedInput.text.toString())
            Toast.makeText(this, "除外アプリを保存しました", Toast.LENGTH_SHORT).show()
        }

        b.autoEnterInput.setText(Prefs.getAutoEnterPackages(this))
        b.saveAutoEnter.setOnClickListener {
            Prefs.setAutoEnterPackages(this, b.autoEnterInput.text.toString())
            Toast.makeText(this, "自動Enterの対象を保存しました", Toast.LENGTH_SHORT).show()
        }

        b.pickAutoEnter.setOnClickListener { pickAppForAutoEnter() }

        b.btnAutoSendDiag.setOnClickListener {
            val f = java.io.File(filesDir, "autosend.log")
            val body = if (f.exists()) f.readText().takeLast(4000) else ""
            AlertDialog.Builder(this)
                .setTitle("自動送信の診断")
                .setMessage(if (body.isBlank()) "まだ記録がありません" else body)
                .setPositiveButton("コピー") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT diag", body))
                    Toast.makeText(this, "コピーした", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("消す") { _, _ -> try { f.delete() } catch (_: Exception) {} }
                .show()
        }

        b.btnEditZone.setOnClickListener {
            if (Prefs.getTriggerMode(this) != Prefs.TRIGGER_ZONE) {
                Toast.makeText(this, "起動ゾーンをONにしてから確認してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isOverlayRunning()) {
                Toast.makeText(this, "先に「起動」してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_EDIT_ZONE)
            )
            moveTaskToBack(true)
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
            val msg = if (Prefs.getTriggerMode(this) == Prefs.TRIGGER_ZONE)
                "起動した。ゾーンをダブルタップで録音"
            else "フロートマイクを起動しました"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        b.btnHistory.setOnClickListener { showHistoryDialog() }

        b.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "停止しました", Toast.LENGTH_SHORT).show()
        }

    }

    /**
     * 喋った内容の一覧。挿入したものも、取り消したものも全部ここに残る。
     * クリップボードには書かない方針にしたので、拾い直す場所がここになる。
     */
    private fun showHistoryDialog() {
        val items = Prefs.getHistory(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "まだ何もありません", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN)
        val labels = items.map { (ts, text) -> "[" + fmt.format(java.util.Date(ts)) + "] " + text }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("喋った内容 " + items.size + " 件（タップでコピー）")
            .setItems(labels) { _, which ->
                val text = items[which].second
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT", text))
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("全消去") { _, _ ->
                Prefs.clearHistory(this)
                Toast.makeText(this, "消しました", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("閉じる", null)
            .show()
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
            .setTitle("送信まで行うアプリを追加")
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
