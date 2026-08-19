package com.shunp.vitmobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 履歴の専用画面。
 * ダイアログの一覧は1行に切り詰められて全文が読めず、消去ボタンが近くて誤爆する。
 * 全文をそのまま出して、文字を直接選べる普通の画面にする（駿平 2026-08-14）。
 */
class HistoryActivity : AppCompatActivity() {

    private val navy = Color.parseColor("#FF0A0E1A")
    private val card = Color.parseColor("#FF141A2A")
    private val gold = Color.parseColor("#FFF0C040")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
        }

        val items = Prefs.getHistory(this)

        val header = TextView(this).apply {
            text = if (items.isEmpty()) "履歴" else "履歴  ${items.size}"
            setTextColor(gold)
            textSize = 24f
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        scroll.addView(list)
        root.addView(scroll)

        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "まだ何もありません"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 15f
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        } else {
            val fmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN)
            for ((ts, text) in items) {
                list.addView(buildCard(fmt.format(java.util.Date(ts)), text))
            }
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        bottom.addView(Button(this).apply {
            text = "閉じる"
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF1A2440"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            setOnClickListener { finish() }
        })
        bottom.addView(Button(this).apply {
            text = "全消去"
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B2C2C"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                marginStart = dp(8)
            }
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setMessage("履歴を全部消す？")
                    .setPositiveButton("消す") { _, _ ->
                        Prefs.clearHistory(this@HistoryActivity)
                        setContentView(build())
                    }
                    .setNegativeButton("やめる", null)
                    .show()
            }
        })
        root.addView(bottom)
        return root
    }

    /** 1件ぶんのカード。全文を折り返して出し、文字はそのまま選べる */
    private fun buildCard(stamp: String, text: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(card)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        box.addView(TextView(this).apply {
            this.text = stamp
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 12f
        })
        box.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(Button(this).apply {
            this.text = "コピー"
            textSize = 12f
            setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(gold)
            minHeight = dp(36)
            minimumHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ).apply { gravity = Gravity.END }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VIT", text))
                Toast.makeText(this@HistoryActivity, "コピーした", Toast.LENGTH_SHORT).show()
            }
        })
        return box
    }
}
