package com.shunp.vitmobile

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        /** 透明ゾーンを見える化してドラッグで動かせるようにする */
        const val ACTION_EDIT_ZONE = "com.shunp.vitmobile.EDIT_ZONE"
        /** 起動方法の設定が変わった時に呼ぶ（オーバーレイを作り直す） */
        const val ACTION_RELOAD_TRIGGER = "com.shunp.vitmobile.RELOAD_TRIGGER"

        val ZONE_W_DP = 44
        val ZONE_H_DP = 150
    }

    private lateinit var wm: WindowManager
    private lateinit var micButton: ImageView
    private lateinit var collapseTab: View
    private lateinit var micParams: WindowManager.LayoutParams
    private lateinit var tabParams: WindowManager.LayoutParams
    private var hotZone: View? = null
    private var zoneParams: WindowManager.LayoutParams? = null
    private var zoneEditMode = false
    private var isFeedback = false
    private var triggerMode = Prefs.TRIGGER_ZONE
    private var recorder: VoiceRecorder? = null
    private var isRecording = false
    private var isCollapsed = false
    private var density = 1f
    private var screenWidth = 0
    private var screenHeight = 0
    private var lastMicY = 0  // 収納時の高さを保持

    private val mainHandler = Handler(Looper.getMainLooper())

    private val gold = Color.parseColor("#FFF0C040")
    private val navy = Color.parseColor("#FF0A0E1A")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        setupOverlay()
        recorder = VoiceRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EDIT_ZONE -> enterZoneEditMode()
            ACTION_RELOAD_TRIGGER -> reloadTrigger()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val chanId = "vit_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(chanId) == null) {
            val chan = NotificationChannel(chanId, "VIT Overlay", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(chan)
        }
        val notif: Notification = NotificationCompat.Builder(this, chanId)
            .setContentTitle("VIT Mobile 起動中")
            .setContentText(
                if (Prefs.getTriggerMode(this) == Prefs.TRIGGER_ZONE)
                    "起動ゾーンをダブルタップで音声入力"
                else "マイクアイコンタップで音声入力"
            )
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, notif)
        }
    }

    private fun setupOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        // マイクボタン
        micButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.mic_button_background)
            imageTintList = ColorStateList.valueOf(gold)
            val pad = (density * 10).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        // 収納タブ（タップで展開・上下ドラッグで位置調整、タッチリスナーは attachTabTouchListener で登録）
        collapseTab = View(this).apply {
            setBackgroundResource(R.drawable.collapse_tab_bg)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (density * 56).toInt()
        // 起動時は画面右下に配置
        val initialX = screenWidth - sizePx - (density * 16).toInt()
        val initialY = screenHeight - sizePx - (density * 200).toInt()
        lastMicY = initialY

        micParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // 収納タブ: 画面右端に張り付く（半分はみ出る）
        val tabW = (density * 14).toInt()
        val tabH = (density * 70).toInt()
        tabParams = WindowManager.LayoutParams(
            tabW, tabH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - tabW
            y = initialY
        }

        attachTouchListener()
        attachTabTouchListener()

        triggerMode = Prefs.getTriggerMode(this)
        if (triggerMode == Prefs.TRIGGER_ZONE) {
            setupHotZone(overlayType)
        } else {
            wm.addView(micButton, micParams)
        }
    }

    // ==================== 透明ゾーン（既定の起動方法） ====================
    // 画面に何も見えない小さな当たり判定を1つ置き、そこを **ダブルタップ** した時だけ録音を始める。
    // 常時表示のバーが目障りという問題への対処（2026-08-12）。
    //   ダブルタップ … 録音開始 / 録音中はシングルタップで確定して挿入
    //   長押し（待機中）… フィードバック録音（結果は GitHub Issue へ）
    //   長押し（録音中）… キャンセル
    private fun setupHotZone(overlayType: Int) {
        val zoneW = (density * ZONE_W_DP).toInt()
        val zoneH = (density * ZONE_H_DP).toInt()
        val (savedX, savedY) = Prefs.getZonePos(this)
        val zx = if (savedX >= 0) savedX else screenWidth - zoneW
        val zy = if (savedY >= 0) savedY else (screenHeight / 2) - (zoneH / 2)

        val params = WindowManager.LayoutParams(
            zoneW, zoneH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = zx
            y = zy
        }
        val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        attachZoneTouchListener(view, params)
        hotZone = view
        zoneParams = params
        try { wm.addView(view, params) } catch (_: Exception) {}
        // 起動直後の3秒だけ枠を見せる。見えない当たり判定は場所が分からないと使えない
        view.setBackgroundResource(R.drawable.zone_edit)
        mainHandler.postDelayed({ updateZoneVisual() }, 3000)
    }

    private fun attachZoneTouchListener(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val zoneW = (density * ZONE_W_DP).toInt()
        val zoneH = (density * ZONE_H_DP).toInt()
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragged = false

        val detector = android.view.GestureDetector(this, object :
            android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoneEditMode) { exitZoneEditMode(); return true }
                flashZone()
                if (!isRecording) startRecording(feedback = false)
                else stopRecording()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (zoneEditMode) return true
                if (isRecording) stopRecording()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (zoneEditMode) return
                if (isRecording) {
                    cancelRecording()
                    Toast.makeText(this@OverlayService, "キャンセルしました", Toast.LENGTH_SHORT).show()
                } else {
                    startRecording(feedback = true)
                    Toast.makeText(
                        this@OverlayService,
                        "フィードバック録音中。タップで送信",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        view.setOnTouchListener { _, ev ->
            if (zoneEditMode) {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        dragged = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - touchStartX).toInt()
                        val dy = (ev.rawY - touchStartY).toInt()
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                        if (dragged) {
                            params.x = (initialX + dx).coerceIn(0, maxOf(0, screenWidth - zoneW))
                            params.y = (initialY + dy).coerceIn(0, maxOf(0, screenHeight - zoneH))
                            try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragged) Prefs.setZonePos(this, params.x, params.y)
                    }
                }
            }
            detector.onTouchEvent(ev)
            true
        }
    }

    private fun enterZoneEditMode() {
        if (triggerMode != Prefs.TRIGGER_ZONE) return
        val view = hotZone ?: return
        zoneEditMode = true
        view.setBackgroundResource(R.drawable.zone_edit)
        Toast.makeText(this, "ドラッグで位置を決めて、ダブルタップで確定", Toast.LENGTH_LONG).show()
    }

    private fun exitZoneEditMode() {
        val view = hotZone ?: return
        zoneEditMode = false
        view.background = null
        zoneParams?.let { Prefs.setZonePos(this, it.x, it.y) }
        Toast.makeText(this, "起動ゾーンの位置を保存した", Toast.LENGTH_SHORT).show()
    }

    /** 起動方法の設定変更を反映（バー ⇔ 透明ゾーン） */
    private fun reloadTrigger() {
        val newMode = Prefs.getTriggerMode(this)
        if (newMode == triggerMode) return
        if (isRecording) cancelRecording()
        removeAllViews()
        triggerMode = newMode
        if (newMode == Prefs.TRIGGER_ZONE) {
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            setupHotZone(overlayType)
        } else {
            isCollapsed = false
            try { wm.addView(micButton, micParams) } catch (_: Exception) {}
        }
    }

    private fun removeAllViews() {
        hotZone?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        hotZone = null
        zoneParams = null
        zoneEditMode = false
        try { wm.removeView(micButton) } catch (_: Exception) {}
        try { wm.removeView(collapseTab) } catch (_: Exception) {}
    }

    /** 録音状態をゾーンの見た目に反映（待機中は完全に透明） */
    private fun updateZoneVisual() {
        val view = hotZone ?: return
        if (zoneEditMode) return
        when {
            isRecording && isFeedback -> view.setBackgroundResource(R.drawable.zone_feedback)
            isRecording -> view.setBackgroundResource(R.drawable.zone_recording)
            else -> view.background = null
        }
    }

    private fun attachTabTouchListener() {
        var initialY = 0
        var touchStartY = 0f
        var dragged = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val tabH = (density * 70).toInt()
        val edgeMargin = (density * 20).toInt()

        collapseTab.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = tabParams.y
                    touchStartY = ev.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - touchStartY).toInt()
                    if (abs(dy) > touchSlop) {
                        dragged = true
                        tabParams.y = (initialY + dy).coerceIn(edgeMargin, screenHeight - tabH - edgeMargin)
                        try { wm.updateViewLayout(collapseTab, tabParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged) expand() else lastMicY = tabParams.y
                    true
                }
                else -> false
            }
        }
    }

    private fun attachTouchListener() {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragged = false
        var longPressed = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        // 画面外に4dp以上はみ出した時だけ収納（画面端スレスレでも維持）
        val edgeOverflowPx = (density * 4).toInt()

        val longPressRunnable = Runnable {
            if (!dragged) {
                longPressed = true
                if (isRecording) {
                    cancelRecording()
                    Toast.makeText(this, "キャンセルしました", Toast.LENGTH_SHORT).show()
                }
            }
        }

        micButton.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = micParams.x
                    initialY = micParams.y
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    dragged = false
                    longPressed = false
                    if (isRecording) {
                        mainHandler.postDelayed(longPressRunnable, 600)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchStartX).toInt()
                    val dy = (ev.rawY - touchStartY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragged = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragged) {
                        micParams.x = initialX + dx
                        micParams.y = initialY + dy
                        try { wm.updateViewLayout(micButton, micParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!dragged && !longPressed) {
                        toggleRecord()
                    } else if (dragged) {
                        // アイコンが画面外にはみ出した時だけ収納
                        val sizePx = (density * 56).toInt()
                        val rightEdge = micParams.x + sizePx
                        if (rightEdge > screenWidth + edgeOverflowPx) {
                            collapse()
                        } else {
                            // 近い方の画面端へスムーズに吸着
                            snapMicToNearestEdge()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapMicToNearestEdge() {
        val sizePx = (density * 56).toInt()
        val marginX = (density * 8).toInt()
        val centerX = micParams.x + sizePx / 2
        val targetX = if (centerX < screenWidth / 2) marginX else screenWidth - sizePx - marginX
        if (micParams.x == targetX) return
        val animator = ValueAnimator.ofInt(micParams.x, targetX)
        animator.duration = 220
        animator.interpolator = DecelerateInterpolator(2.0f)
        animator.addUpdateListener { va ->
            micParams.x = va.animatedValue as Int
            try { wm.updateViewLayout(micButton, micParams) } catch (_: Exception) {}
        }
        animator.start()
    }

    private fun collapse() {
        if (isCollapsed) return
        if (isRecording) cancelRecording()
        val tabH = (density * 70).toInt()
        val edgeMargin = (density * 20).toInt()
        // 指を離した位置でバーが出るように、y の clamp 範囲を緩めて micParams.y をそのまま使う
        lastMicY = micParams.y.coerceIn(edgeMargin, screenHeight - tabH - edgeMargin)
        try { wm.removeView(micButton) } catch (_: Exception) {}
        tabParams.y = lastMicY
        try { wm.addView(collapseTab, tabParams) } catch (_: Exception) {}
        isCollapsed = true
    }

    private fun expand() {
        if (!isCollapsed) return
        try { wm.removeView(collapseTab) } catch (_: Exception) {}
        val sizePx = (density * 56).toInt()
        micParams.x = screenWidth - sizePx - (density * 16).toInt()
        micParams.y = lastMicY
        try { wm.addView(micButton, micParams) } catch (_: Exception) {}
        isCollapsed = false
    }

    private fun toggleRecord() {
        if (isRecording) stopRecording() else startRecording(feedback = false)
    }

    private fun startRecording(feedback: Boolean) {
        if (recorder?.start() == true) {
            isRecording = true
            isFeedback = feedback
            micButton.setBackgroundResource(R.drawable.mic_button_recording)
            micButton.imageTintList = ColorStateList.valueOf(navy)
            updateZoneVisual()
            buzz(40)
            if (!feedback) toast("● 録音中 — タップで確定")
        }
    }

    private fun stopRecording() {
        isRecording = false
        val wasFeedback = isFeedback
        isFeedback = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        updateZoneVisual()
        buzz(20, 60, 20)
        toast(if (wasFeedback) "フィードバック送信中…" else "変換中…")
        recorder?.stopAndTranscribe { text ->
            if (text.isNullOrBlank()) { toast("聞き取れなかった"); return@stopAndTranscribe }
            if (wasFeedback) Feedback.submit(this, text) else copyAndPaste(text)
        }
    }

    private fun cancelRecording() {
        isRecording = false
        isFeedback = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        updateZoneVisual()
        buzz(120)
        recorder?.cancel()
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /** 見えないゾーンを触った事を指で分かるようにする（画面を見なくても判る） */
    private fun buzz(vararg patternMs: Long) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (patternMs.size == 1) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(patternMs[0], 180))
            } else {
                val pattern = LongArray(patternMs.size + 1)
                patternMs.forEachIndexed { i, v -> pattern[i + 1] = v }
                vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (_: Exception) {}
    }

    /** ダブルタップが当たった事を目でも分かるように一瞬光らせる */
    private fun flashZone() {
        val view = hotZone ?: return
        if (zoneEditMode) return
        view.setBackgroundColor(Color.parseColor("#55F0C040"))
        mainHandler.postDelayed({ updateZoneVisual() }, 160)
    }

    private fun copyAndPaste(text: String) {
        Prefs.addHistory(this, text)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VIT", text))
        val intent = Intent(InputAccessibilityService.ACTION_PASTE).apply {
            setPackage(packageName)
            putExtra(InputAccessibilityService.EXTRA_TEXT, text)
        }
        sendBroadcast(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 画面回転時に新しい画面サイズを取得して、バーを再配置する
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        val newWidth = dm.widthPixels
        val newHeight = dm.heightPixels

        val sizePx = (density * 56).toInt()
        val marginX = (density * 16).toInt()
        val edgeMargin = (density * 20).toInt()
        val tabH = (density * 70).toInt()

        val zone = hotZone
        val zp = zoneParams
        if (zone != null && zp != null) {
            // 透明ゾーンは画面比率を保って再配置（縦横の切り替えで画面外に消えないように）
            val zoneW = (density * ZONE_W_DP).toInt()
            val zoneH = (density * ZONE_H_DP).toInt()
            val xRatio = if (screenWidth > 0) zp.x.toFloat() / screenWidth else 1f
            val yRatio = if (screenHeight > 0) zp.y.toFloat() / screenHeight else 0.5f
            zp.x = (newWidth * xRatio).toInt().coerceIn(0, maxOf(0, newWidth - zoneW))
            zp.y = (newHeight * yRatio).toInt().coerceIn(0, maxOf(0, newHeight - zoneH))
            try { wm.updateViewLayout(zone, zp) } catch (_: Exception) {}
            screenWidth = newWidth
            screenHeight = newHeight
            return
        }

        if (isCollapsed) {
            // 収納タブは常に画面右端に張り付く。y は旧画面高さに対する比率で新画面高さに再配置
            val tabW = (density * 14).toInt()
            val yRatio = if (screenHeight > 0) tabParams.y.toFloat() / screenHeight else 0.5f
            tabParams.x = newWidth - tabW
            tabParams.y = (newHeight * yRatio).toInt().coerceIn(edgeMargin, newHeight - tabH - edgeMargin)
            try { wm.updateViewLayout(collapseTab, tabParams) } catch (_: Exception) {}
            lastMicY = tabParams.y
        } else {
            // 回転前のx座標の画面内比率を保って、どちらの端寄りかで吸着/clamp を判定
            val oldCenterX = micParams.x + sizePx / 2f
            val oldRatio = if (screenWidth > 0) oldCenterX / screenWidth else 0.5f
            micParams.x = when {
                oldRatio >= 0.7f -> newWidth - sizePx - marginX   // 右寄り → 右端吸着
                oldRatio <= 0.3f -> marginX                        // 左寄り → 左端吸着
                else -> (newWidth / 2f - sizePx / 2f).toInt()      // 中央寄り → 中央配置
            }
            // y も画面高さに対する比率で再配置（下端に置いたら下端に居続けるように）
            val yRatio = if (screenHeight > 0) micParams.y.toFloat() / screenHeight else 0.5f
            micParams.y = (newHeight * yRatio).toInt().coerceIn(edgeMargin, newHeight - sizePx - edgeMargin)
            try { wm.updateViewLayout(micButton, micParams) } catch (_: Exception) {}
        }

        screenWidth = newWidth
        screenHeight = newHeight
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        removeAllViews()
        recorder?.release()
    }
}
