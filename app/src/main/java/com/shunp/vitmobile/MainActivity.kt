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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shunp.vitmobile.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    companion object {
        /** 更新通知から開かれた時 */
        const val ACTION_RUN_UPDATE = "com.shunp.vitmobile.RUN_UPDATE"
        private const val GOLD = 0xFFF0C040.toInt()
    }

    private lateinit var b: ActivityMainBinding
    private val shizukuPermission by lazy { ShizukuSwipePermission(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        VoiceRecorder.recoverFrom(this)

        // ---- 書いたらその場で保存（保存ボタンは置かない・駿平 2026-10-06） ----
        b.apiKeyInput.setText(Prefs.getGroqKey(this) ?: "")
        b.apiKeyInput.onEdited { Prefs.setGroqKey(this, it.trim()); refreshState() }
        b.anthropicKeyInput.setText(Prefs.getAnthropicKey(this) ?: "")
        b.anthropicKeyInput.onEdited { Prefs.setAnthropicKey(this, it.trim()) }
        loadDictionary()
        b.btnAddDictionary.setOnClickListener { addDictionaryRow("", focus = true) }
        loadSnippets()
        b.btnAddSnippet.setOnClickListener { addSnippetRow("", "", focus = true) }

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
        // タップは選択画面、長押しはそのアプリを一覧から外す。
        b.autoEnterApps.tag = Runnable { b.pickAutoEnter.performClick() }
        b.excludedApps.tag = Runnable { b.pickExcluded.performClick() }
        b.autoEnterApps.setOnClickListener { b.pickAutoEnter.performClick() }
        b.excludedApps.setOnClickListener { b.pickExcluded.performClick() }

        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        // ---- 準備 ----
        b.btnOverlayPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val openAccessibility = View.OnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "「VIT Mobile テキスト挿入」をONにすると使えます", Toast.LENGTH_LONG).show()
        }
        b.btnAccessibilityPerm.setOnClickListener(openAccessibility)
        b.btnOpenAccessibility.setOnClickListener(openAccessibility)
        b.btnMicPerm.setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }

        b.bottomNavigation.setOnItemSelectedListener { item ->
            showSection(item.itemId)
            true
        }
        showSection(R.id.nav_home)
        b.btnSetupKey.setOnClickListener {
            b.bottomNavigation.selectedItemId = R.id.nav_details
            b.detailsSection.smoothScrollTo(0, 0)
            b.apiKeyInput.requestFocus()
        }
        b.btnRun.setOnClickListener {
            if (!ensureRunning()) Toast.makeText(this, "起動できませんでした", Toast.LENGTH_SHORT).show()
            refreshState()
            b.root.postDelayed({ refreshState() }, 300)
        }

        // ---- 詳細設定 ----
        b.btnAutoSendDiag.setOnClickListener { showDiag() }
        b.btnRestart.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            if (ensureRunning()) Toast.makeText(this, "再起動した", Toast.LENGTH_SHORT).show()
            refreshState()
            b.root.postDelayed({ refreshState() }, 300)
        }
        b.btnStopOverlay.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "止めた（VIT録音を呼ぶとまた動きます）", Toast.LENGTH_SHORT).show()
            b.root.postDelayed({ refreshState() }, 300)
        }

        b.btnUpdate.text = "更新を確認（v" + BuildInfo.versionName(this) + "）"
        b.btnUpdate.setOnClickListener { Updater.checkNow(this) }

        // 通知経由も通常起動も同じ更新処理。URLはGitHubから取得する。
        if (intent?.action == ACTION_RUN_UPDATE) {
            Updater.checkNow(this)
        } else {
            Updater.checkOnOpen(this)
        }

        if (!hasMic()) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onStart() {
        super.onStart()
        shizukuPermission.start()
    }

    override fun onStop() {
        shizukuPermission.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Updater.resumeAfterPermission(this)
        showAppLists()
        showRecentHistory()
        showDiagSummary()
        // 権限を付けて戻ってきた時は、従来どおり自動で動き出す。
        ensureRunning()
        refreshState()
        b.root.postDelayed({ refreshState() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensureRunning()
        refreshState()
        b.root.postDelayed({ refreshState() }, 300)
    }

    private fun showSection(id: Int) {
        // 非表示タブの入力欄にフォーカスやキーボードを残さない。
        currentFocus?.let { focused ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        b.homeSection.visibility = if (id == R.id.nav_home) View.VISIBLE else View.GONE
        b.appsSection.visibility = if (id == R.id.nav_apps) View.VISIBLE else View.GONE
        b.wordsSection.visibility = if (id == R.id.nav_words) View.VISIBLE else View.GONE
        b.detailsSection.visibility = if (id == R.id.nav_details) View.VISIBLE else View.GONE
        if (id == R.id.nav_home) { refreshState(); showRecentHistory() }
        if (id == R.id.nav_details) showDiagSummary()
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
        b.btnSetupKey.visibility = if (key) View.GONE else View.VISIBLE
        val ready = overlay && acc && mic && key
        val running = ready && isOverlayRunning()
        b.btnRun.visibility = if (ready && !running) View.VISIBLE else View.GONE
        b.statusText.text = when {
            !ready -> "設定が足りません"
            running -> "待機中"
            else -> "止まっています"
        }
        b.statusHint.text = when {
            !ready -> "下のボタンから必要な設定を確認できます"
            running -> "VIT録音のジェスチャーで話せます"
            else -> "「動かす」かVIT録音のジェスチャーで再開できます"
        }
        val color = when {
            !ready -> 0xFFFF9A60.toInt()
            running -> 0xFF4ECB8B.toInt()
            else -> 0xFFB0B7C3.toInt()
        }
        b.statusText.setTextColor(color)
        b.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun showRecentHistory() {
        b.recentHistoryBox.removeAllViews()
        val history = Prefs.getHistory(this).take(3)
        if (history.isEmpty()) {
            b.recentHistoryBox.addView(noteView("まだありません"))
        }
        for ((timestamp, text) in history) {
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.field_bg)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(48)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                isFocusable = true
                contentDescription = "$text。タップでコピー"
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                if (ripple.resourceId != 0) foreground = getDrawable(ripple.resourceId)
                setOnClickListener { copyText(text) }
            }
            card.addView(android.widget.TextView(this).apply {
                setTextAppearance(R.style.Vit_TextBody)
                this.text = text
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            card.addView(noteView(android.text.format.DateFormat.format("M/d HH:mm", timestamp).toString()))
            b.recentHistoryBox.addView(card)
        }
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

    /** 選んだアプリをアイコンで並べる（一目で分かるように・駿平 2026-10-06） */
    private fun showAppLists() {
        renderIcons(b.autoEnterApps, parseList(Prefs.getAutoEnterPackages(this))) { pkg ->
            Prefs.setAutoEnterPackages(this, parseList(Prefs.getAutoEnterPackages(this))
                .filterNot { it.equals(pkg, ignoreCase = true) }.joinToString("\n"))
        }
        renderIcons(b.excludedApps, parseList(Prefs.getExcludedPackages(this))) { pkg ->
            Prefs.setExcludedPackages(this, parseList(Prefs.getExcludedPackages(this))
                .filterNot { it.equals(pkg, ignoreCase = true) }.joinToString("\n"))
        }
    }

    private fun renderIcons(grid: android.widget.GridLayout, pkgs: List<String>, remove: (String) -> Unit) {
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        val cell = (d * 52).toInt()
        // 画面幅から1行に並ぶ数を決める（外側の余白: 画面18dp×2・カード16dp×2・枠8dp×2）
        val usable = resources.displayMetrics.widthPixels - (d * (36 + 32 + 16)).toInt()
        grid.columnCount = (usable / cell).coerceAtLeast(1)
        // スマホに入っていないアプリ（最初から一覧に入れてある Gemini 等）は出さない。
        // 一覧からは消さないので、後で入れればそのまま効く
        val items = pkgs.mapNotNull { pkg ->
            val icon = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
            icon?.let { Triple(pkg, appLabel(pkg), it) }
        }.sortedBy { it.second.lowercase() }
        if (items.isEmpty()) {
            grid.addView(android.widget.TextView(this).apply {
                text = "（なし）　タップして選ぶ"
                setTextAppearance(R.style.Vit_TextNote)
                setPadding((d * 6).toInt(), (d * 12).toInt(), 0, 0)
            })
            return
        }
        for ((pkg, label, icon) in items) {
            val v = android.widget.ImageView(this).apply {
                setImageDrawable(icon)
                contentDescription = label
                isFocusable = true
                val pad = (d * 6).toInt()
                setPadding(pad, pad, pad, pad)
            }
            v.setOnLongClickListener {
                remove(pkg)
                showAppLists()
                Toast.makeText(this, "${label}を外した", Toast.LENGTH_SHORT).show()
                true
            }
            v.setOnClickListener { (grid.tag as? Runnable)?.run() }
            grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
                width = cell
                height = cell
            })
        }
    }

    /**
     * インストール済みアプリから選ぶ。チェックを付けた／外した時点で保存する（保存ボタンは無い）。
     * 上の欄で名前検索できる。選択済みは上に並ぶ。
     */
    private fun pickApps(title: String, load: () -> String, save: (String) -> Unit) {
        val builder = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_VitMobile_MainDialog)
        val dialogContext = builder.context
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
                val row = convertView as? android.widget.LinearLayout ?: android.widget.LinearLayout(dialogContext).apply {
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
                        setTextAppearance(R.style.Vit_TextBody)
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

        val search = EditText(dialogContext).apply {
            setTextAppearance(R.style.Vit_TextBody)
            hint = "アプリ名で検索"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            setBackgroundResource(R.drawable.field_bg)
            val pad = (d * 12).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val list = android.widget.ListView(dialogContext).apply {
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
        val titleView = android.widget.TextView(dialogContext).apply {
            text = title
            setTextColor(GOLD)
            setTextAppearance(R.style.Vit_TextHeading)
            val pad = (d * 18).toInt()
            setPadding(pad, pad, pad, (d * 6).toInt())
        }
        val box = android.widget.LinearLayout(dialogContext).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (d * 16).toInt()
            setPadding(pad, 0, pad, 0)
            addView(search)
            addView(list, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt()
            ).apply { topMargin = (d * 8).toInt() })
        }
        builder
            .setCustomTitle(titleView)
            .setView(box)
            .setPositiveButton("閉じる", null)
            .show()
    }

    // ==================== 辞書 ====================

    private fun loadDictionary() {
        b.dictionaryBox.removeAllViews()
        Prefs.getDictionary(this).lineSequence().filter { it.isNotBlank() }
            .forEach { addDictionaryRow(it, focus = false) }
    }

    private fun saveDictionary() {
        val words = (0 until b.dictionaryBox.childCount).mapNotNull { index ->
            val row = b.dictionaryBox.getChildAt(index) as? android.widget.LinearLayout
            (row?.getChildAt(0) as? EditText)?.text?.toString()
                ?.replace("\r", " ")?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
        }
        Prefs.setDictionary(this, words.joinToString("\n"))
    }

    private fun addDictionaryRow(word: String, focus: Boolean) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        val input = EditText(this).apply {
            setTextAppearance(R.style.Vit_TextBody)
            setSingleLine()
            hint = "よく使う言葉"
            setText(word)
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(48)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            onEdited { saveDictionary() }
        }
        row.addView(input)
        row.addView(removeRowButton("この言葉を消す") {
            b.dictionaryBox.removeView(row)
            saveDictionary()
        })
        b.dictionaryBox.addView(row)
        if (focus) input.requestFocus()
    }

    private fun removeRowButton(description: String, remove: () -> Unit) = MaterialButton(this).apply {
        text = "✕"
        contentDescription = description
        setPadding(0, 0, 0, 0)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) }
        setOnClickListener { remove() }
    }

    // ==================== 置き換え ====================
    // 保存形式は従来どおり「言う言葉|入る文字」を1行に1つ（Prefs.applySnippets が読む）

    private fun loadSnippets() {
        b.snippetsBox.removeAllViews()
        for (line in Prefs.getSnippets(this).lines()) {
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && (parts[0].isNotBlank() || parts[1].isNotBlank())) {
                addSnippetRow(parts[0].trim(), parts[1].trim(), focus = false)
            }
        }
    }

    private fun saveSnippets() {
        val lines = mutableListOf<String>()
        for (i in 0 until b.snippetsBox.childCount) {
            val row = b.snippetsBox.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val key = (row.getChildAt(0) as EditText).text.toString().replace("|", "").replace("\n", " ").trim()
            val value = (row.getChildAt(2) as EditText).text.toString().replace("\n", " ").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) lines.add("$key|$value")
        }
        Prefs.setSnippets(this, lines.joinToString("\n"))
    }

    private fun addSnippetRow(key: String, value: String, focus: Boolean) {
        val d = resources.displayMetrics.density
        fun field(text: String, hint: String, weight: Float) = EditText(this).apply {
            setText(text)
            this.hint = hint
            setSingleLine()
            setTextAppearance(R.style.Vit_TextBody)
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setBackgroundResource(R.drawable.field_bg)
            val pad = (d * 10).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            onEdited { saveSnippets() }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (d * 8).toInt() }
        }
        val k = field(key, "言う言葉", 1f)
        row.addView(k)
        row.addView(android.widget.TextView(this).apply {
            text = "→"
            setTextColor(GOLD)
            setTextAppearance(R.style.Vit_TextBody)
            val pad = (d * 6).toInt()
            setPadding(pad, 0, pad, 0)
        })
        row.addView(field(value, "入る文字", 1.6f))
        row.addView(removeRowButton("この置き換えを消す") {
            b.snippetsBox.removeView(row)
            saveSnippets()
        })
        b.snippetsBox.addView(row)
        if (focus) k.requestFocus()
    }

    // ==================== その他 ====================

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun noteView(body: String) = android.widget.TextView(this).apply {
        setTextAppearance(R.style.Vit_TextNote)
        text = body
        layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun copyText(body: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("VIT", body))
        Toast.makeText(this, "コピーした", Toast.LENGTH_SHORT).show()
    }

    private data class SendCounts(var sent: Int = 0, var gaveUp: Int = 0, var notFound: Int = 0)

    private fun showDiagSummary() {
        b.diagSummaryBox.removeAllViews()
        val counts = linkedMapOf<String, SendCounts>()
        val pkgPattern = Regex("(?:^|\\s)pkg=([a-zA-Z0-9_.]+)")
        val file = java.io.File(filesDir, "autosend.log")
        try {
            if (file.exists()) file.useLines { lines ->
                lines.forEach { line ->
                    val sent = line.contains("sent (input cleared)")
                    val gaveUp = line.contains("gave up")
                    val notFound = line.contains("send button not found")
                    if (sent || gaveUp || notFound) {
                        // 旧ログではnot foundにpkgが付かない。直前のアプリから推測しない。
                        val pkg = pkgPattern.find(line)?.groupValues?.get(1)
                            ?.takeUnless { it == "null" } ?: ""
                        val count = counts.getOrPut(pkg) { SendCounts() }
                        if (sent) count.sent++
                        if (gaveUp) count.gaveUp++
                        if (notFound) count.notFound++
                    }
                }
            }
        } catch (_: Exception) {
            b.diagSummaryBox.addView(noteView("記録を読み込めませんでした"))
            return
        }
        if (counts.isEmpty()) b.diagSummaryBox.addView(noteView("まだ記録がありません"))
        for ((pkg, count) in counts.entries.sortedBy { if (it.key.isEmpty()) "\uffff" else appLabel(it.key) }) {
            b.diagSummaryBox.addView(android.widget.TextView(this).apply {
                setTextAppearance(R.style.Vit_TextBody)
                text = if (pkg.isEmpty()) "アプリ不明" else appLabel(pkg)
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            })
            b.diagSummaryBox.addView(noteView(
                "送信できた：${count.sent}回\nあきらめた：${count.gaveUp}回\nボタンが見つからない：${count.notFound}回"
            ))
        }
        if (counts.containsKey("")) {
            b.diagSummaryBox.addView(noteView("アプリ名のない記録は「アプリ不明」に含まれます"))
        }
    }

    private fun showDiag() {
        val f = java.io.File(filesDir, "autosend.log")
        val body = try { if (f.exists()) f.readText() else "" } catch (_: Exception) {
            Toast.makeText(this, "記録を読み込めませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_VitMobile_MainDialog)
            .setTitle("自動送信の記録")
            .setMessage(if (body.isBlank()) "まだ記録がありません" else body)
            .setPositiveButton("コピー") { _, _ -> copyText(body) }
            .setNegativeButton("消す") { _, _ ->
                val deleted = try { !f.exists() || f.delete() } catch (_: Exception) { false }
                Toast.makeText(this, if (deleted) "記録を消した" else "記録を消せませんでした", Toast.LENGTH_SHORT).show()
                showDiagSummary()
            }
            .setNeutralButton("閉じる", null)
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
