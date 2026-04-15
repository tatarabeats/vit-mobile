package com.shunp.vitmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var view: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private var recorder: VoiceRecorder? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        setupOverlay()
        recorder = VoiceRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            .setContentText("フロートマイクアイコン表示中")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, notif)
        }
    }

    private fun setupOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.mic_button_background)
            val pad = (resources.displayMetrics.density * 10).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragged = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchStartX).toInt()
                    val dy = (ev.rawY - touchStartY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleRecord()
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
    }

    private fun toggleRecord() {
        if (isRecording) {
            isRecording = false
            view.setBackgroundResource(R.drawable.mic_button_background)
            recorder?.stopAndTranscribe { text ->
                if (!text.isNullOrBlank()) copyAndPaste(text)
            }
        } else {
            if (recorder?.start() == true) {
                isRecording = true
                view.setBackgroundResource(R.drawable.mic_button_recording)
            }
        }
    }

    private fun copyAndPaste(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VIT", text))
        val intent = Intent(InputAccessibilityService.ACTION_PASTE).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(view) } catch (_: Exception) {}
        recorder?.release()
    }
}
