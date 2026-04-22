package com.shunp.vitmobile

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
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var micButton: ImageView
    private lateinit var collapseTab: View
    private lateinit var micParams: WindowManager.LayoutParams
    private lateinit var tabParams: WindowManager.LayoutParams
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        val chanId = "vit_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(chanId) == null) {
            val chan = NotificationChannel(chanId, "VIT Overlay", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(chan)
        }
        val notif: Notification = NotificationCompat.Builder(this, chanId)
            .setContentTitle("VIT Mobile 起動中")
            .setContentText("マイクアイコンタップで音声入力")
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
        wm.addView(micButton, micParams)
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
                        }
                    }
                    true
                }
                else -> false
            }
        }
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
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recorder?.start() == true) {
            isRecording = true
            micButton.setBackgroundResource(R.drawable.mic_button_recording)
            micButton.imageTintList = ColorStateList.valueOf(navy)
        }
    }

    private fun stopRecording() {
        isRecording = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        recorder?.stopAndTranscribe { text ->
            if (!text.isNullOrBlank()) copyAndPaste(text)
        }
    }

    private fun cancelRecording() {
        isRecording = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        recorder?.cancel()
    }

    private fun copyAndPaste(text: String) {
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
        try { if (!isCollapsed) wm.removeView(micButton) else wm.removeView(collapseTab) } catch (_: Exception) {}
        recorder?.release()
    }
}
