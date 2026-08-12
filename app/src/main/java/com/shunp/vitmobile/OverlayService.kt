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
        /** 音量キー2回押しなど、画面外からの起動 */
        const val ACTION_TRIGGER = "com.shunp.vitmobile.TRIGGER"

        /**
          * 左右のキワの帯の幅。タッチを横取りしないので、広めに取っても下のアプリに影響しない。
          */
        const val BAND_W_DP = 40
    }

    private lateinit var wm: WindowManager
    private lateinit var micButton: ImageView
    private lateinit var collapseTab: View
    private lateinit var micParams: WindowManager.LayoutParams
    private lateinit var tabParams: WindowManager.LayoutParams
    private var hotZone: View? = null
    private var zoneParams: WindowManager.LayoutParams? = null
    private val zoneViews = mutableListOf<View>()
    private var watcherView: View? = null
    private var stripsAttached = false
    private var zeroCoordCount = 0
    private var statusView: View? = null
    private var statusText: android.widget.TextView? = null
    private var statusDot: View? = null
    private var historyCard: View? = null
    private var tapCount = 0
    private var lastTapAt = 0L
    private var lastTapX = -9999f
    private var lastTapY = -9999f
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
            ACTION_TRIGGER -> onExternalTrigger()
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

    // ==================== 起動ゾーン（既定の起動方法） ====================
    // 画面の左右のキワ（縦帯）をダブルタップすると録音が始まる。
    //
    // **タッチは一切横取りしない**（2026-08-12 の作り直し）。
    // 帯に触れる窓を置くと、そこから始まるスクロールも普通のタップも死ぬ。
    // 代わりに 1×1px の監視窓を置き、画面のどこに指が降りたかを
    // FLAG_WATCH_OUTSIDE_TOUCH の ACTION_OUTSIDE の座標で受け取る。
    // ACTION_OUTSIDE はイベントを消費しないので、下のアプリの操作は完全に無傷。
    //
    //   2回タップ … 録音開始   3回タップ … 履歴   4回タップ … フィードバック録音
    //   録音中のタップ … 確定して挿入 / 録音中の2回タップ … キャンセル
    //   帯そのものは常に FLAG_NOT_TOUCHABLE。見せるだけで、触れる窓は一切置かない
    private fun setupHotZone(overlayType: Int) {
        setupWatcher(overlayType)
        showZoneHint(overlayType, 3000)
    }

    /**
     * 監視窓は **1×1px** で画面の隅に置く（v0.7.1 で作り直し）。
     * v0.7.0 は画面中央を覆う窓に FLAG_NOT_TOUCHABLE を付けて置いたが、実機では
     * その窓がタッチを飲み込み **画面中央が反応しなくなった**。覆う設計自体をやめる。
     * 1px なら奪うのは1ピクセルだけで、画面のどこを触っても ACTION_OUTSIDE が届く。
     * どこを触ったかは ACTION_OUTSIDE の座標で判定する。
     */
    private fun setupWatcher(overlayType: Int) {
        val params = WindowManager.LayoutParams(
            1, 1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) onOutsideTouch(ev.rawX, ev.rawY)
            false
        }
        watcherView = view
        try { wm.addView(view, params) } catch (_: Exception) { watcherView = null }
    }

    /**
     * 画面のどこかに指が降りた。左右のキワに入っている時だけ数える。
     * Android 12+ は他アプリ上のタッチ座標を隠す仕様があり、その場合は常に (0,0) で届く。
     * 3回続けて座標が取れなかったら、座標に頼らない「帯を占有する方式」へ自動で切り替える。
     */
    private fun onOutsideTouch(x: Float, y: Float) {
        if (x == 0f && y == 0f) {
            zeroCoordCount++
            if (zeroCoordCount >= 3 && !Prefs.isVolumeTrigger(this)) {
                // 画面のタッチは絶対に奪わない。座標が取れない端末では
                // 音量キー2回押しへ逃がす（画面には一切触らない）
                Prefs.setVolumeTrigger(this, true)
                flashStatus("音量キー2回押しで起動に切り替えた")
            }
            return
        }
        zeroCoordCount = 0
        val band = density * BAND_W_DP
        if (x > band && x < screenWidth - band) return

        // キーボードが出ている間、その上（バックスペース連打など）では反応させない。
        // 文字を消すためのダブルタップで録音が始まると邪魔でしかない。
        val imeTop = InputAccessibilityService.imeTop()
        if (imeTop in 0..screenHeight && y >= imeTop) return

        // スワイプの連打で誤爆しないよう、2打目は「同じ場所・素早く」を要求する。
        // 本物のダブルタップはほぼ同じ点を220ms以内に叩く。スクロールの指下ろしは
        // 位置が離れるか間隔が空くので、ここで弾ける（2026-08-12 実使用で更に短縮）。
        val now = System.currentTimeMillis()
        val near = abs(x - lastTapX) < density * 30 && abs(y - lastTapY) < density * 30
        val quick = now - lastTapAt < 220
        lastTapX = x
        lastTapY = y
        if (!quick || !near) {
            tapCount = 0
            lastTapAt = now
            onEdgeTap(reset = true)
            return
        }
        lastTapAt = now
        onEdgeTap(reset = false)
    }

    /**
     * キワに指が降りた。回数で意味を分ける。
     * 遅延なしで進めたいので「2回目で即開始し、3回目で取り消して履歴」という積み上げにする。
     */
    /** 音量キー2回押しなどからの起動。押すたびに開始 → 確定 のトグル */
    private fun onExternalTrigger() {
        if (isRecording) stopRecording() else startRecording(feedback = false)
    }

    private fun onEdgeTap(reset: Boolean) {
        tapCount = if (reset) 1 else tapCount + 1
        if (isRecording) {
            // 録音中: 1回=確定 / 2回=キャンセル。1回目は2打目が来ないと確定できないので少し待つ
            if (tapCount == 1) {
                mainHandler.postDelayed({
                    if (isRecording && tapCount == 1) stopRecording()
                }, 320)
            } else if (tapCount == 2) {
                cancelRecording()
                flashStatus("キャンセルした")
            }
            return
        }
        when (tapCount) {
            2 -> {
                flashZone()
                startRecording(feedback = false)
            }
            3 -> {
                cancelRecording()
                showHistoryCard()
            }
            4 -> {
                hideHistoryCard()
                startRecording(feedback = true)
            }
        }
    }

    // ---- 帯の実体（録音中と、場所を見せる時だけ出す） ----

    private fun stripParams(overlayType: Int, left: Boolean): WindowManager.LayoutParams {
        val band = (density * BAND_W_DP).toInt()
        return WindowManager.LayoutParams(
            band, screenHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (left) 0 else screenWidth - band
            y = 0
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** 録音中だけ帯を触れる状態で出す（確定タップ・キャンセル長押しを確実に拾うため） */
    /**
     * 帯は **常に FLAG_NOT_TOUCHABLE**（見せるだけ）。
     * 触れる帯を置くと端でのスクロールが死ぬ。v0.7.x で実際にそうなった。二度とやらない。
     */
    private fun attachStrips(touchable: Boolean = false) {
        if (stripsAttached) return
        val type = overlayType()
        for (left in listOf(true, false)) {
            val params = stripParams(type, left)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            zoneViews.add(view)
            if (!left) { hotZone = view; zoneParams = params }
            try { wm.addView(view, params) } catch (_: Exception) {}
        }
        stripsAttached = true
        updateZoneVisual()
    }

    /** タッチを横取りしない方式では、録音が終わったら帯を消して完全に無干渉へ戻す */
    private fun releaseStripsIfPassive() {
        if (!zoneEditMode) detachStrips()
    }

    private fun detachStrips() {
        zoneViews.forEach { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        zoneViews.clear()
        hotZone = null
        zoneParams = null
        stripsAttached = false
    }

    /** 帯の場所を一定時間だけ見せる（触れない状態で出すので操作は邪魔しない） */
    private fun showZoneHint(overlayType: Int, ms: Long) {
        if (stripsAttached) return
        attachStrips()
        zoneEditMode = true
        zoneViews.forEach { it.setBackgroundResource(R.drawable.zone_edit) }
        mainHandler.postDelayed({
            zoneEditMode = false
            if (!isRecording) detachStrips() else updateZoneVisual()
        }, ms)
    }

    /** 帯の場所を一定時間だけ見せる（触れない状態で出すので操作は邪魔しない） */
    private fun showZoneHint(overlayType: Int, ms: Long) {
        if (stripsAttached) return
        attachStrips()
        zoneEditMode = true
        zoneViews.forEach { it.setBackgroundResource(R.drawable.zone_edit) }
        mainHandler.postDelayed({
            zoneEditMode = false
            if (!isRecording) detachStrips() else updateZoneVisual()
        }, ms)
    }

    private fun attachZoneTouchListener(view: View) {
        val detector = android.view.GestureDetector(this, object :
            android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoneEditMode) return true
                flashZone()
                // 録音中のダブルタップは **キャンセル**（長押しより速くて確実、駿平指定）
                if (isRecording) {
                    cancelRecording()
                    flashStatus("キャンセルした")
                } else {
                    startRecording(feedback = false)
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (zoneEditMode) return true
                if (isRecording) stopRecording()
                return true
            }
        })

        view.setOnTouchListener { _, ev ->
            // 3回タップ = 履歴（帯を実体化している時の経路）
            if (!zoneEditMode && ev.action == MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt < 450) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 3) {
                    tapCount = 0
                    if (isRecording) cancelRecording()
                    showHistoryCard()
                    return@setOnTouchListener true
                }
            }
            detector.onTouchEvent(ev)
            true
        }
    }

    /** 帯がどこにあるかを5秒だけ見せる（触れない状態で出すので操作は邪魔しない） */
    private fun enterZoneEditMode() {
        if (triggerMode != Prefs.TRIGGER_ZONE) return
        Toast.makeText(this, "この帯の中ならどこでもダブルタップで起動する", Toast.LENGTH_LONG).show()
        showZoneHint(overlayType(), 5000)
    }

    /** 起動方法の設定変更を反映（バー ⇔ 透明ゾーン） */
    private fun reloadTrigger() {
        val newMode = Prefs.getTriggerMode(this)
        if (newMode == triggerMode) return
        if (isRecording) cancelRecording()
        removeAllViews()
        triggerMode = newMode
        if (newMode == Prefs.TRIGGER_ZONE) {
            setupHotZone(overlayType())
        } else {
            isCollapsed = false
            try { wm.addView(micButton, micParams) } catch (_: Exception) {}
        }
    }

    private fun removeAllViews() {
        stopLevelMeter()
        hideHistoryCard()
        detachStrips()
        watcherView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        watcherView = null
        statusView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        statusView = null
        statusText = null
        statusDot = null
        zoneEditMode = false
        try { wm.removeView(micButton) } catch (_: Exception) {}
        try { wm.removeView(collapseTab) } catch (_: Exception) {}
    }

    /** 録音状態をゾーンの見た目に反映（待機中は完全に透明） */
    private fun updateZoneVisual() {
        if (zoneEditMode) return
        zoneViews.forEach { view ->
            when {
                isRecording && isFeedback -> view.setBackgroundResource(R.drawable.zone_feedback)
                isRecording -> view.setBackgroundResource(R.drawable.zone_recording)
                else -> view.background = null
            }
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
            startLevelMeter()
            if (triggerMode == Prefs.TRIGGER_ZONE) attachStrips()
            buzz(40)
            showStatus(if (feedback) "フィードバック録音中 — タップで送信" else "録音中 — タップで確定", feedback)
        }
    }

    private fun stopRecording() {
        isRecording = false
        val wasFeedback = isFeedback
        isFeedback = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        stopLevelMeter()
        releaseStripsIfPassive()
        updateZoneVisual()
        buzz(20, 60, 20)
        showStatus(if (wasFeedback) "送信中…" else "変換中…", wasFeedback)
        recorder?.stopAndTranscribe { text ->
            hideStatus()
            if (text.isNullOrBlank()) { flashStatus("聞き取れなかった"); return@stopAndTranscribe }
            if (wasFeedback) Feedback.submit(this, text) else copyAndPaste(text)
        }
    }

    private fun cancelRecording() {
        isRecording = false
        isFeedback = false
        micButton.setBackgroundResource(R.drawable.mic_button_background)
        micButton.imageTintList = ColorStateList.valueOf(gold)
        stopLevelMeter()
        releaseStripsIfPassive()
        updateZoneVisual()
        buzz(120)
        hideStatus()
        recorder?.cancel()
    }

    // ==================== 状態表示（自前オーバーレイ） ====================
    // Toast はシステムの待ち行列を通るので、混んでいると数秒遅れて出る。
    // 録音中かどうかは即座に分からないと使えないので、自分のウィンドウで出す。

    private fun showStatus(msg: String, feedback: Boolean) {
        mainHandler.post {
            if (statusView == null) buildStatusView()
            statusText?.text = msg
            statusDot?.setBackgroundResource(
                if (feedback) R.drawable.zone_feedback else R.drawable.zone_recording
            )
            statusView?.visibility = View.VISIBLE
        }
    }

    private fun hideStatus() {
        mainHandler.post { statusView?.visibility = View.GONE }
    }

    /** 一瞬だけ出して自動で消す（エラー通知用） */
    private fun flashStatus(msg: String) {
        showStatus(msg, false)
        mainHandler.postDelayed({ hideStatus() }, 1500)
    }

    private fun buildStatusView() {
        val padH = (density * 16).toInt()
        val padV = (density * 10).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.history_card_bg)
            setPadding(padH, padV, padH, padV)
        }
        val dotSize = (density * 12).toInt()
        val dot = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                rightMargin = (density * 10).toInt()
            }
            setBackgroundResource(R.drawable.zone_recording)
        }
        val tv = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        row.addView(dot)
        row.addView(tv)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (density * 72).toInt()
        }
        statusView = row
        statusText = tv
        statusDot = dot
        try { wm.addView(row, params) } catch (_: Exception) { statusView = null }
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

    // ==================== 履歴カード（3回タップ） ====================

    private fun showHistoryCard() {
        hideHistoryCard()
        val items = Prefs.getHistory(this)
        if (items.isEmpty()) { flashStatus("履歴はまだ無い"); return }

        val pad = (density * 14).toInt()
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        for ((_, text) in items) {
            val row = android.widget.TextView(this).apply {
                this.text = if (text.length > 90) text.take(90) + "…" else text
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                setOnClickListener {
                    hideHistoryCard()
                    copyAndPaste(text)
                    buzz(30)
                }
            }
            list.addView(row)
        }
        // 過去まで遡れるようスクロールさせる（画面の6割までで打ち止め）
        val card = android.widget.ScrollView(this).apply {
            setBackgroundResource(R.drawable.history_card_bg)
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        // カードの外を触ったら閉じる（閉じるボタンを押させない）。
        // FLAG_WATCH_OUTSIDE_TOUCH で、外側のタップは下のアプリに通しつつ通知だけ受け取る
        card.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { hideHistoryCard(); true } else false
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val cardW = minOf((density * 300).toInt(), screenWidth - (density * 24).toInt())
        val zp = zoneParams
        // 入力欄のフォーカスを奪わないよう NOT_FOCUSABLE のまま出す（挿入先が変わらない）
        val maxH = (screenHeight * 0.6f).toInt()
        val params = WindowManager.LayoutParams(
            cardW, maxH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((zp?.x ?: screenWidth) - cardW).coerceIn(0, maxOf(0, screenWidth - cardW))
            y = (zp?.y ?: (screenHeight / 2)).coerceIn(0, maxOf(0, screenHeight - (density * 200).toInt()))
        }
        historyCard = card
        try { wm.addView(card, params) } catch (_: Exception) { historyCard = null }
        // 放置されても邪魔にならないよう自動で消す（遡って読む時間を考えて長めに）
        mainHandler.postDelayed({ hideHistoryCard() }, 20000)
    }

    private fun hideHistoryCard() {
        historyCard?.let { c -> try { wm.removeView(c) } catch (_: Exception) {} }
        historyCard = null
    }

    // ==================== 録音中の音量表示 ====================

    private val levelTick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val ampNow = (recorder?.amplitude() ?: 0).coerceIn(0, 12000) / 12000f
            statusDot?.alpha = 0.35f + 0.65f * ampNow
            if (zoneViews.isNotEmpty() && !zoneEditMode) {
                // maxAmplitude(0-32767) を 0.45〜1.0 の濃さに割り当てる。
                // 声を出している間だけドットが濃くなる＝拾えているのが目で分かる
                val amp = (recorder?.amplitude() ?: 0).coerceIn(0, 12000) / 12000f
                val a = 0.45f + 0.55f * amp
                zoneViews.forEach { it.alpha = a }
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    private fun startLevelMeter() {
        mainHandler.removeCallbacks(levelTick)
        mainHandler.postDelayed(levelTick, 100)
    }

    private fun stopLevelMeter() {
        mainHandler.removeCallbacks(levelTick)
        zoneViews.forEach { it.alpha = 1f }
    }

    /** ダブルタップが当たった事を目でも分かるように一瞬光らせる */
    private fun flashZone() {
        if (zoneEditMode || zoneViews.isEmpty()) return
        zoneViews.forEach { it.setBackgroundColor(Color.parseColor("#55F0C040")) }
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

        if (zoneViews.isNotEmpty()) {
            // 帯は新しい画面サイズで作り直す（回転で縦横が入れ替わるため位置計算をやり直す）
            screenWidth = newWidth
            screenHeight = newHeight
            hideHistoryCard()
            detachStrips()
            watcherView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
            watcherView = null
            setupHotZone(overlayType())
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
