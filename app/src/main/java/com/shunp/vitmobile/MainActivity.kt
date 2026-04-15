package com.shunp.vitmobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
            Toast.makeText(this, "APIキーを保存しました", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "APIキーを入力・保存してください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                Toast.makeText(
                    this,
                    "ユーザー補助で『VIT Mobile テキスト挿入』をONにしてください。これをONにしないと入力欄に自動挿入されません。",
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
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myId = "$packageName/${InputAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(myId, ignoreCase = true) }
    }
}
