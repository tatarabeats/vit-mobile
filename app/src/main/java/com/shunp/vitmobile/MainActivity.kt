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
            Toast.makeText(this, "フロートマイクを起動しました", Toast.LENGTH_SHORT).show()
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

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myId = "$packageName/${InputAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(myId, ignoreCase = true) }
    }
}
