package com.shunp.vitmobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ClipActivity : AppCompatActivity() {
    private val navy = Color.parseColor("#FF0A0E1A")
    private val card = Color.parseColor("#FF141A2A")
    private val gold = Color.parseColor("#FFF0C040")
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        reload()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
        }
        status = TextView(this).apply {
            text = "PCクリップ"
            setTextColor(gold)
            textSize = 24f
            setPadding(dp(20), dp(20), dp(20), dp(8))
        }
        root.addView(status)
        val refresh = Button(this).apply {
            text = "再読み込み"
            setOnClickListener { reload() }
        }
        root.addView(refresh)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        scroll.addView(list)
        root.addView(scroll)
        return root
    }

    private fun reload() {
        val url = Prefs.getClipUrl(this)
        val token = Prefs.getClipToken(this)
        if (url.isBlank() || token.isBlank()) {
            status.text = "設定に PC の URL と token を入れて"
            return
        }
        status.text = "読み込み中"
        thread {
            try {
                val conn = URL("$url/v1/items").openConnection() as HttpURLConnection
                conn.setRequestProperty("X-Clip-Token", token)
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                runOnUiThread { render(arr, url, token) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "届かない。PC と同じ Wi-Fi か見て" }
            }
        }
    }

    private fun render(arr: JSONArray, url: String, token: String) {
        list.removeAllViews()
        status.text = if (arr.length() == 0) "まだ何もない" else "PCクリップ  ${arr.length()}"
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val text = o.optString("text")
            val ts = o.optLong("ts")
            val imageUrl = o.optString("image_url")
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10)
                layoutParams = lp
            }
            cardView.addView(TextView(this).apply {
                this.text = fmt.format(Date(ts))
                setTextColor(gold)
                textSize = 12f
            })
            if (text.isNotBlank()) {
                cardView.addView(TextView(this).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(0, dp(6), 0, dp(6))
                    setTextIsSelectable(true)
                })
            }
            if (imageUrl.isNotBlank()) {
                val img = ImageView(this).apply {
                    adjustViewBounds = true
                    maxHeight = dp(220)
                    scaleType = ImageView.ScaleType.FIT_START
                }
                cardView.addView(img)
                thread {
                    try {
                        val conn = URL(imageUrl).openConnection() as HttpURLConnection
                        conn.setRequestProperty("X-Clip-Token", token)
                        val bytes = conn.inputStream.readBytes()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        runOnUiThread { img.setImageBitmap(bmp) }
                    } catch (_: Exception) {}
                }
            }
            cardView.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("clip", text.ifBlank { imageUrl }))
                Toast.makeText(this, "コピーした", Toast.LENGTH_SHORT).show()
            }
            cardView.gravity = Gravity.START
            list.addView(cardView)
        }
    }
}
