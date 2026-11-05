package com.shunp.vitmobile

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ブラウザーで Amazon.co.jp の商品ページを開いている間、画面の下に Keepa の価格推移グラフと
 * さくらチェッカー・Keepa へのボタンを出す（2026-11-05 駿平さん「スマホでも Keepa とさくらチェッカーを
 * Chrome 拡張と同じように」）。Brave は拡張機能を入れられないので、ユーザー補助の重ね表示で出す。
 * グラフをタップすると小さなボタンに畳み、ボタンをタップすると開く。× でその商品の間は出さない。
 */
class AmazonPanel(private val svc: AccessibilityService) {
    companion object {
        val BROWSERS = setOf(
            "com.brave.browser", "com.android.chrome", "com.sec.android.app.sbrowser",
            "com.microsoft.emmx", "org.mozilla.firefox"
        )
        private val ASIN_IN_URL = Regex(
            "amazon\\.co\\.jp/(?:.*?/)?(?:dp|gp/product|gp/aw/d|o/ASIN|exec/obidos/ASIN)/([A-Z0-9]{10})",
            RegexOption.IGNORE_CASE
        )
        private val ASIN_LABEL = Regex("^\\s*(?:ASIN|ISBN-10)\\s*[:：‏‎\\s]*([A-Z0-9]{10})\\s*$")
        private val ASIN_VALUE = Regex("^[\\s‏‎:：]*([B0-9][A-Z0-9]{9})[\\s‏‎]*$")

        /** URL の文字から ASIN を取り出す（テスト用に公開） */
        fun asinFromUrl(s: CharSequence?): String? =
            s?.let { ASIN_IN_URL.find(it)?.groupValues?.get(1)?.uppercase() }

        /** アドレスバーの文字から、商品ページを離れたと言えるか（テスト用に公開） */
        fun shouldHideFor(bar: String): Boolean {
            if (!bar.contains("amazon.co.jp", ignoreCase = true)) return bar.isNotEmpty()
            val path = bar.substringAfter("amazon.co.jp", "")
            return path.length > 1 && asinFromUrl(bar) == null
        }

        fun graphUrl(asin: String) =
            "https://graph.keepa.com/pricehistory.png?asin=$asin&domain=co.jp&range=365&width=800&height=360&amazon=1&new=1&used=1"
        fun sakuraUrl(asin: String) = "https://sakura-checker.jp/search/$asin/"
        fun keepaUrl(asin: String) = "https://keepa.com/#!product/5-$asin"
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = svc.resources.displayMetrics.density
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private var panel: View? = null
    private var chip: View? = null
    private var image: ImageView? = null
    private var asin: String? = null
    private var browserPkg: String? = null
    private var collapsed = false
    private var dismissedAsin: String? = null
    private var lastScanAt = 0L
    private var lastBar: String? = null

    /** ユーザー補助のイベントのたびに呼ぶ（間引きはこちらでする） */
    fun onEvent(root: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        val pkg = root?.packageName?.toString()
        if (pkg == null || pkg !in BROWSERS) {
            // 前面のアプリがブラウザーでなくなったら隠す
            if (pkg != null) hide()
            return
        }
        if (now - lastScanAt < 700) return
        lastScanAt = now
        // 前面のブラウザーの窓を全部見る（お知らせの窓が前にあると、ページの窓が前面の根にならない）
        val roots = listOf(root) + try {
            svc.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }.filter { it.packageName?.toString() == pkg && it != root }
        } catch (_: Exception) { emptyList() }
        var found: String? = null
        lastBar = null
        for (r in roots) { found = findAsin(pkg, r); if (found != null) break }
        if (found != null) {
            show(found, pkg)
        } else {
            // 商品ページの印が見えないだけでは隠さない（お知らせの窓・読み込み中など）。
            // アドレスバーが読めて、Amazon 以外か、Amazon の商品ではないページ（検索結果など）になった時だけ隠す。
            // ドメインだけの表示（amazon.co.jp）の時は判断できないので出したままにする
            val bar = lastBar?.trim()
            if (bar != null && asin != null && shouldHideFor(bar)) hide()
        }
    }

    private fun findAsin(pkg: String, root: AccessibilityNodeInfo): String? {
        // 1) アドレスバー
        var bar: String? = null
        try {
            for (n in root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar")) {
                bar = n.text?.toString()
                lastBar = bar
                asinFromUrl(n.text)?.let { log("asin=$it from url_bar"); return it }
            }
        } catch (_: Exception) {}
        // 2) ページの中（リンクの URL・登録情報の「ASIN」行）。アドレスバーがドメインしか出さない時の代わり
        var visited = 0
        var prevWasLabel = false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && visited < 5000) {
            val n = stack.removeLast()
            visited++
            val t = n.text
            val d = n.contentDescription
            asinFromUrl(t)?.let { log("asin=$it from text"); return it }
            asinFromUrl(d)?.let { log("asin=$it from desc"); return it }
            try {
                val ex = n.extras
                for (k in ex.keySet()) {
                    @Suppress("DEPRECATION")
                    val v = ex.get(k)
                    if (v is CharSequence) asinFromUrl(v)?.let { log("asin=$it from extra $k"); return it }
                }
            } catch (_: Exception) {}
            val s = (t ?: d)?.toString()
            if (s != null) {
                ASIN_LABEL.find(s)?.let { log("asin=${it.groupValues[1]} from label"); return it.groupValues[1] }
                if (prevWasLabel) ASIN_VALUE.find(s)?.let { log("asin=${it.groupValues[1]} from label+value"); return it.groupValues[1] }
                prevWasLabel = s.trim().trimEnd(':', '：', '‏', '‎', ' ').let { it == "ASIN" || it == "ISBN-10" }
            }
            for (i in n.childCount - 1 downTo 0) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        if (bar?.contains("amazon", ignoreCase = true) == true) log("amazon page without asin: bar=$bar nodes=$visited")
        return null
    }

    private var lastLog = ""
    private fun log(line: String) {
        if (line == lastLog) return
        lastLog = line
        try {
            val f = java.io.File(svc.filesDir, "amazon.log")
            if (f.length() > 30_000) f.writeText("")
            f.appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Exception) {}
    }

    private fun show(found: String, pkg: String) {
        browserPkg = pkg
        if (found == dismissedAsin) { removeViews(); return }
        if (found == asin && (panel != null || chip != null)) return
        asin = found
        removeViews()
        if (collapsed) addChip() else addPanel()
        loadGraph(found)
    }

    fun hide() {
        if (panel == null && chip == null) return
        removeViews()
        asin = null
    }

    private fun removeViews() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        chip?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        chip = null
        image = null
    }

    private fun params(w: Int, h: Int, gravity: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { this.gravity = gravity; this.x = x; this.y = y }

    private fun dp(v: Int) = (v * density).toInt()

    private fun button(label: String, onClick: () -> Unit) = TextView(svc).apply {
        text = label
        textSize = 13f
        setTextColor(Color.parseColor("#1A2B4A"))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#FFE7EEF8")) }
        setOnClickListener { onClick() }
    }

    private fun addPanel() {
        val a = asin ?: return
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#33000000")) }
            elevation = dp(6).toFloat()
        }
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(svc).apply {
            text = "Keepa 1年"
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        row.addView(button("さくらチェッカー") { open(sakuraUrl(a)) }, gap)
        row.addView(button("Keepa") { open(keepaUrl(a)) }, gap)
        row.addView(button("×") { dismissedAsin = a; removeViews() }, gap)
        box.addView(row)
        val img = ImageView(svc).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(120)
            setOnClickListener { collapsed = true; removeViews(); addChip() }
        }
        cachedBitmap?.takeIf { cachedFor == a }?.let { img.setImageBitmap(it) }
        box.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        image = img
        val w = svc.resources.displayMetrics.widthPixels - dp(16)
        try {
            wm.addView(box, params(w, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(64)))
            panel = box
        } catch (_: Exception) {}
    }

    private fun addChip() {
        val v = TextView(svc).apply {
            text = "📈 Keepa"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#E01A2B4A")) }
            setOnClickListener { collapsed = false; removeViews(); addPanel() }
        }
        try {
            wm.addView(v, params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END, dp(8), dp(64)))
            chip = v
        } catch (_: Exception) {}
    }

    private var cachedBitmap: android.graphics.Bitmap? = null
    private var cachedFor: String? = null

    private fun loadGraph(a: String) {
        if (cachedFor == a && cachedBitmap != null) { image?.setImageBitmap(cachedBitmap); return }
        Thread {
            val bmp = try {
                client.newCall(Request.Builder().url(graphUrl(a)).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
                }
            } catch (_: Exception) { null }
            main.post {
                if (bmp != null) { cachedBitmap = bmp; cachedFor = a }
                if (asin == a && bmp != null) image?.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun open(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        browserPkg?.let { i.setPackage(it) }
        try { svc.startActivity(i) } catch (_: Exception) {
            try { svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
