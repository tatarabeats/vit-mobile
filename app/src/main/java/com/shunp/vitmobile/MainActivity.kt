package com.shunp.vitmobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shunp.vitmobile.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // 起動方法: 透明ゾーン（既定） ⇔ 常時表示マイク
        b.zoneModeSwitch.isChecked = Prefs.getTriggerMode(this) == Prefs.TRIGGER_ZONE
        b.zoneModeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setTriggerMode(this, if (checked) Prefs.TRIGGER_ZONE else Prefs.TRIGGER_MIC)
            if (isOverlayRunning()) {
                startForegroundService(
                    Intent(this, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_RELOAD_TRIGGER)
                )
            }
        }

        b.zonePassiveSwitch.isChecked = Prefs.isZonePassive(this)
        b.zonePassiveSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setZonePassive(this, checked)
            if (isOverlayRunning()) {
                // サービスを入れ直して方式を切り替える
                stopService(Intent(this, OverlayService::class.java))
                startForegroundService(Intent(this, OverlayService::class.java))
            }
            Toast.makeText(
                this,
                if (checked) "キワのタップを奪わない方式にした" else "帯を占有する確実方式にした",
                Toast.LENGTH_SHORT
            ).show()
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

        b.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "停止しました", Toast.LENGTH_SHORT).show()
        }

        b.btnHistory.setOnClickListener { showHistoryDialog() }
    }

    private fun showHistoryDialog() {
        val items = Prefs.getHistory(this)
        if (items.isEmpty()) {
            Toast.makeText(this, "履歴はまだありません", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        val labels = items.map { (ts, text) ->
            "[${fmt.format(Date(ts))}] $text"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("履歴 ${items.size} 件 (タップでコピー)")
            .setItems(labels) { _, which ->
                val text = items[which].second
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VIT", text))
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("全クリア") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("履歴を全部消しますか？")
                    .setPositiveButton("消す") { _, _ ->
                        Prefs.clearHistory(this)
                        Toast.makeText(this, "履歴を削除しました", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .setPositiveButton("閉じる", null)
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
