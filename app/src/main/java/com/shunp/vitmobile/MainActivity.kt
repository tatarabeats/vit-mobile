package com.shunp.vitmobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shunp.vitmobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        /** 更新通知から開かれた時 */
        const val ACTION_RUN_UPDATE = "com.shunp.vitmobile.RUN_UPDATE"
        private const val NAVY = 0xFF0A0E1A.toInt()
        private const val GOLD = 0xFFF0C040.toInt()
    }

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        VoiceRecorder.recoverFrom(this)

        // ---- 書いたらその場で保存（保存ボタンは置かない・駿平 2026-10-06） ----
        b.apiKeyInput.setText(Prefs.getGroqKey(this) ?: "")
        b.apiKeyInput.onEdited { Prefs.setGroqKey(this, it.trim()); refreshState() }
        b.anthropicKeyInput.setText(Prefs.getAnthropicKey(this) ?: "")
        b.anthropicKeyInput.onEdited { Prefs.setAnthropicKey(this, it.trim()) }
        b.dictionaryInput.setText(Prefs.getDictionary(this))
        b.dictionaryInput.onEdited { Prefs.setDictionary(this, it) }
        b.snippetsInput.setText(Prefs.getSnippets(this))
        b.snippetsInput.onEdited { Prefs.setSnippets(this, it) }

        b.llmFixSwitch.isChecked = Prefs.isLlmFixEnabled(this)
        b.llmFixSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setLlmFixEnabled(this, checked) }

        b.autoEnterSwitch.isChecked = Prefs.isAutoEnterEnabled(this)
        b.autoEnterSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setAutoEnterEnabled(this, checked) }

        // ---- アプリの一覧（自動送信・除外とも同じ選択画面） ----
        b.pickAutoEnter.setOnClickListener {
            pickApps("自動送信するアプリ", { Prefs.getAutoEnterPackages(this) }) {
                Prefs.setAutoEnterPackages(this, it); showAppLists()
            }
        }
        b.pickExcluded.setOnClickListener {
            pickApps("除外するアプリ", { Prefs.getExcludedPackages(this) }) {
                Prefs.setExcludedPackages(this, it); showAppLists()
            }
        }
        b.autoEnterApps.setOnClickListener { b.pickAutoEnter.performClick() }
        b.excludedApps.setOnClickListener { b.pickExcluded.performClick() }

        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        // ---- 準備 ----
        b.btnOverlayPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val openAccessibility = View.OnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "「VIT Mobile テキスト挿入」をONにしてください", Toast.LENGTH_LONG).show()
        }
        b.btnAccessibilityPerm.setOnClickListener(openAccessibility)
        b.btnOpenAccessibility.setOnClickListener(openAccessibility)
        b.btnMicPerm.setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }

        // ---- 詳細設定 ----
        b.btnToggleAdvanced.setOnClickListener {
            val open = b.advancedBox.visibility != View.VISIBLE
            b.advancedBox.visibility = if (open) View.VISIBLE else View.GONE
            b.btnToggleAdvanced.text = if (open) "詳細設定を閉じる" else "詳細設定を開く"
        }
        b.btnAutoSendDiag.setOnClickListener { showDiag() }
        b.btnRestart.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            if (ensureRunning()) Toast.makeText(this, "再起動した", Toast.LENGTH_SHORT).show()
            refreshState()
        }
        b.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "止めた（VIT録音を呼ぶとまた動きます）", Toast.LENGTH_SHORT).show()
            b.root.postDelayed({ refreshState() }, 300)
        }

        b.btnUpdate.text = "更新を確認（v" + BuildInfo.versionName(this) + "）"
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

        if (!hasMic()) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onResume() {
        super.onResume()
        showAppLists()
        // 権限を付けて戻ってきた時に、そのまま動き出す（「起動」ボタンは不要にした）
        ensureRunning()
        refreshState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshState()
    }

    // ==================== 状態 ====================

    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isReady(): Boolean =
        Settings.canDrawOverlays(this) && isAccessibilityEnabled() && hasMic() &&
            !Prefs.getGroqKey(this).isNullOrBlank()

    /** 準備が揃っていれば常駐を起こす。揃っていなければ何もしない */
    private fun ensureRunning(): Boolean {
        if (!isReady()) return false
        if (isOverlayRunning()) return true
        return try {
            startForegroundService(Intent(this, OverlayService::class.java))
            true
        } catch (_: Exception) { false }
    }

    private fun refreshState() {
        val overlay = Settings.canDrawOverlays(this)
        val acc = isAccessibilityEnabled()
        val mic = hasMic()
        val key = !Prefs.getGroqKey(this).isNullOrBlank()
        b.btnOverlayPerm.visibility = if (overlay) View.GONE else View.VISIBLE
        b.btnAccessibilityPerm.visibility = if (acc) View.GONE else View.VISIBLE
        b.btnMicPerm.visibility = if (mic) View.GONE else View.VISIBLE
        b.needKeyText.visibility = if (key) View.GONE else View.VISIBLE
        val ready = overlay && acc && mic && key
        b.setupCard.visibility = if (ready) View.GONE else View.VISIBLE
        if (!key) {
            b.advancedBox.visibility = View.VISIBLE
            b.btnToggleAdvanced.text = "詳細設定を閉じる"
        }
        b.statusText.text = when {
            !ready -> "設定が足りないため止まっています"
            isOverlayRunning() -> "● 待機中　VIT録音のジェスチャーで話せます"
            else -> "止まっています（VIT録音を呼ぶと動きます）"
        }
        b.statusText.setTextColor(if (ready) 0xFF4ECB8B.toInt() else 0xFFFF9A60.toInt())
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

    // ==================== アプリの一覧 ====================

    private fun parseList(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** パッケージ名ではなくアプリ名で見せる */
    private fun showAppLists() {
        fun render(list: List<String>): String =
            if (list.isEmpty()) "（なし）" else list.map { appLabel(it) }.sortedBy { it.lowercase() }.joinToString("、")
        b.autoEnterApps.text = render(parseList(Prefs.getAutoEnterPackages(this)))
        b.excludedApps.text = render(parseList(Prefs.getExcludedPackages(this)))
    }

    /**
     * インストール済みアプリから選ぶ。チェックを付けた／外した時点で保存する（保存ボタンは無い）。
     * 上の欄で名前検索できる。選択済みは上に並ぶ。
     */
    private fun pickApps(title: String, load: () -> String, save: (String) -> Unit) {
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
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "アプリ一覧を取得できなかった", Toast.LENGTH_SHORT).show()
            return
        }

        val chosen = parseList(load()).toMutableList()
        fun isOn(pkg: String) = chosen.any { it.equals(pkg, ignoreCase = true) }
        val sorted = apps.sortedBy { if (isOn(it.second)) 0 else 1 }
        val shown = sorted.toMutableList()
        val d = resources.displayMetrics.density

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = convertView as? android.widget.LinearLayout ?: android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = (d * 10).toInt()
                    setPadding(pad, pad, pad, pad)
                    addView(android.widget.ImageView(context).apply {
                        val sz = (d * 36).toInt()
                        layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).apply {
                            rightMargin = (d * 14).toInt()
                        }
                    })
                    addView(android.widget.TextView(context).apply {
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(android.widget.CheckBox(context).apply {
                        isClickable = false
                        isFocusable = false
                        buttonTintList = android.content.res.ColorStateList.valueOf(GOLD)
                    })
                }
                val (label, pkg, icon) = shown[position]
                (row.getChildAt(0) as android.widget.ImageView).setImageDrawable(icon)
                (row.getChildAt(1) as android.widget.TextView).text = label
                (row.getChildAt(2) as android.widget.CheckBox).isChecked = isOn(pkg)
                return row
            }
        }

        val search = EditText(this).apply {
            hint = "アプリ名で検索"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundResource(R.drawable.field_bg)
            val pad = (d * 12).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val list = android.widget.ListView(this).apply {
            this.adapter = adapter
            divider = ColorDrawable(0x22FFFFFF)
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                val pkg = shown[position].second
                if (isOn(pkg)) chosen.removeAll { it.equals(pkg, ignoreCase = true) } else chosen.add(pkg)
                save(chosen.joinToString("\n"))
                adapter.notifyDataSetChanged()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown.clear()
                shown.addAll(if (q.isEmpty()) sorted else sorted.filter {
                    it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
                })
                adapter.notifyDataSetChanged()
            }
        })
        val titleView = android.widget.TextView(this).apply {
            text = title
            setTextColor(GOLD)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = (d * 18).toInt()
            setPadding(pad, pad, pad, (d * 6).toInt())
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (d * 16).toInt()
            setPadding(pad, 0, pad, 0)
            addView(search)
            addView(list, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt()
            ).apply { topMargin = (d * 8).toInt() })
        }
        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setView(box)
            .setPositiveButton("閉じる", null)
            .create()
        dlg.setOnShowListener {
            dlg.window?.setBackgroundDrawable(ColorDrawable(NAVY))
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(GOLD)
        }
        dlg.show()
    }

    // ==================== その他 ====================

    private fun showDiag() {
        val f = java.io.File(filesDir, "autosend.log")
        val body = if (f.exists()) f.readText().takeLast(4000) else ""
        AlertDialog.Builder(this)
            .setTitle("自動送信の記録")
            .setMessage(if (body.isBlank()) "まだ記録がありません" else body)
            .setPositiveButton("コピー") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT diag", body))
                Toast.makeText(this, "コピーした", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("消す") { _, _ -> try { f.delete() } catch (_: Exception) {} }
            .show()
    }

    private fun EditText.onEdited(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { block(s?.toString() ?: "") }
        })
    }
}
