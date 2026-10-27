package com.jiraiyaai.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FloatingJiraiyaService — High-Performance Character-Only Floating Overlay Service
 *
 * Strictly displays ONLY the existing Maxter J character (`assets/images/21574.png` / `21769.png`)
 * over other apps using `WindowManager` (`TYPE_APPLICATION_OVERLAY`) with a 100% transparent
 * background (`PixelFormat.TRANSLUCENT` and `Color.TRANSPARENT`).
 *
 * Key Upgrades (v4.0):
 * - Ultra-fast speech end-of-utterance detection (`350ms` complete silence / `280ms` possibly complete silence).
 * - Visual state animations on the character for `listening`, `processing`, `executing`, `speaking`, and `idle`.
 * - Activates on custom wake words (`Yow`, `Yoww`, `Dei`, `Dai`, `Bro`, `Jiraiya`, `Master`, `Maxter`) AND
 *   executes high-confidence direct device commands when the user omits the wake word during active listening.
 * - Coordinates microphone handoff with `MainActivity` so there is never a duplicate overlay or
 *   competing `SpeechRecognizer`.
 * - Respects Android 15 (`targetSdk 35`) foreground-service (`specialUse|microphone`) and permission rules.
 */
class FloatingJiraiyaService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var commandController: MaxterVoiceCommandController
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayContainer: FrameLayout? = null
    private var characterImageView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var stateAnimator: ValueAnimator? = null
    private var currentVisualState: String = "idle"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var prebuiltRecognizerIntent: Intent? = null

    @Volatile
    private var isListening = false
    @Volatile
    private var isSpeaking = false
    @Volatile
    private var isProcessingCommand = false
    @Volatile
    private var manualTapTriggered = false
    @Volatile
    private var backgroundWakeStandbyActive = false
    @Volatile
    private var activeWakeWindowUntil = 0L
    @Volatile
    private var lastWakeActivationRealtime = 0L
    @Volatile
    private var offlinePrefUnsupported = false

    private var voiceLocale = "ta-IN"
    private var currentWidthDp = 120
    private var currentOpacityPct = 100
    private var showCharacterOnly = true
    private var rememberPosition = true
    private var positionMode = "free"
    private var animStyle = "breathing"
    private var animSpeed = 1.0f
    private var aspectRatio = 807f / 545f

    // Auto-Hide Timer configuration
    private var autoHideEnabled = true
    private var autoHideMinutes = 3

    private val overlayWakeRestartRunnable = Runnable {
        if (shouldRunOverlayWakeStandby()) {
            startOverlayVoiceListening(isManualTap = false)
        }
    }

    private val autoHideRunnable = Runnable {
        if (!autoHideEnabled) return@Runnable
        if (isListening || isSpeaking || isProcessingCommand || SystemClock.elapsedRealtime() < activeWakeWindowUntil) {
            scheduleOrResetAutoHideTimer()
            return@Runnable
        }
        prefs.edit().putBoolean("overlay_enabled", false).apply()
        cancelOverlayWakeStandby()
        removeOverlayViewSafely()
        isRunning = false
        MainActivity.activeInstance?.syncOverlayStateToWebView()
        val isTa = voiceLocale.startsWith("ta")
        Toast.makeText(
            applicationContext,
            if (isTa) "Maxter J ஓவர்லே தானாக மறைக்கப்பட்டது (${autoHideMinutes} நிமிடம்)"
            else "Maxter J overlay auto-hidden after $autoHideMinutes min of inactivity",
            Toast.LENGTH_SHORT
        ).show()
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "maxter_j_overlay_channel"
        const val NOTIFICATION_ID = 707
        const val ACTION_START = "com.jiraiyaai.app.ACTION_START_OVERLAY"
        const val ACTION_UPDATE_CONFIG = "com.jiraiyaai.app.ACTION_UPDATE_OVERLAY_CONFIG"
        const val ACTION_RESET_CONFIG = "com.jiraiyaai.app.ACTION_RESET_OVERLAY_CONFIG"
        const val ACTION_LISTEN = "com.jiraiyaai.app.ACTION_LISTEN_OVERLAY"
        const val ACTION_WAKE_STANDBY_START = "com.jiraiyaai.app.ACTION_WAKE_STANDBY_START"
        const val ACTION_WAKE_STANDBY_YIELD = "com.jiraiyaai.app.ACTION_WAKE_STANDBY_YIELD"
        const val ACTION_SET_VISUAL_STATE = "com.jiraiyaai.app.ACTION_SET_VISUAL_STATE"
        const val ACTION_STOP = "com.jiraiyaai.app.ACTION_STOP_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        loadSettingsFromPrefs()

        tts = TextToSpeech(this, this)
        commandController = MaxterVoiceCommandController(
            context = this,
            activity = null,
            prefs = prefs,
            speakCallback = { msg, lang, _ -> speakFromOverlay(msg, lang) }
        )

        startForegroundNotification()

        if (canDrawOverlayWindow()) {
            ensureSingleOverlayViewCreated()
            applyOverlayConfiguration(repositionIfPreset = !rememberPosition)
            isRunning = true
            prefs.edit().putBoolean("overlay_enabled", true).apply()
            scheduleOrResetAutoHideTimer()
        } else {
            isRunning = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlayWindow()) {
            cancelAutoHideTimer()
            cancelOverlayWakeStandby()
            removeOverlayViewSafely()
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                cancelAutoHideTimer()
                cancelOverlayWakeStandby()
                prefs.edit().putBoolean("overlay_enabled", false).apply()
                removeOverlayViewSafely()
                isRunning = false
                MainActivity.activeInstance?.syncOverlayStateToWebView()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET_CONFIG -> {
                resetPrefsToDefault()
                loadSettingsFromPrefs()
                ensureSingleOverlayViewCreated()
                applyOverlayConfiguration(repositionIfPreset = true)
                scheduleOrResetAutoHideTimer()
            }
            ACTION_UPDATE_CONFIG -> {
                val prevPosMode = positionMode
                loadSettingsFromPrefs()
                if (!prefs.getBoolean("overlay_enabled", true)) {
                    cancelAutoHideTimer()
                    cancelOverlayWakeStandby()
                    removeOverlayViewSafely()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureSingleOverlayViewCreated()
                val shouldReposition = (positionMode != prevPosMode && positionMode != "free") ||
                        intent.getBooleanExtra("force_reposition", false)
                applyOverlayConfiguration(repositionIfPreset = shouldReposition)
                scheduleOrResetAutoHideTimer()
            }
            ACTION_SET_VISUAL_STATE -> {
                val targetState = intent.getStringExtra("visual_state") ?: "idle"
                ensureSingleOverlayViewCreated()
                setOverlayVisualState(targetState)
                scheduleOrResetAutoHideTimer()
            }
            ACTION_WAKE_STANDBY_START -> {
                backgroundWakeStandbyActive = true
                scheduleOverlayWakeLoop(300L)
            }
            ACTION_WAKE_STANDBY_YIELD -> {
                backgroundWakeStandbyActive = false
                cancelOverlayWakeStandby()
            }
            ACTION_LISTEN -> {
                scheduleOrResetAutoHideTimer()
                startOverlayVoiceListening(isManualTap = true)
            }
            ACTION_START, null -> {
                loadSettingsFromPrefs()
                prefs.edit().putBoolean("overlay_enabled", true).apply()
                ensureSingleOverlayViewCreated()
                applyOverlayConfiguration(repositionIfPreset = !rememberPosition)
                isRunning = true
                scheduleOrResetAutoHideTimer()
                if (MainActivity.activeInstance == null || MainActivity.activeInstance?.isActivityInForeground == false) {
                    backgroundWakeStandbyActive = true
                    scheduleOverlayWakeLoop(400L)
                }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val lp = layoutParams ?: return
        val container = overlayContainer ?: return
        val (screenW, screenH) = getUsableScreenSize()
        val maxX = (screenW - lp.width).coerceAtLeast(0)
        val maxY = (screenH - lp.height).coerceAtLeast(0)

        if (positionMode != "free") {
            val (px, py) = computePresetCoordinates(positionMode, lp.width, lp.height, screenW, screenH)
            lp.x = px
            lp.y = py
        } else {
            lp.x = lp.x.coerceIn(0, maxX)
            lp.y = lp.y.coerceIn(0, maxY)
        }
        try {
            windowManager.updateViewLayout(container, lp)
        } catch (_: Exception) {
        }
    }

    private fun canDrawOverlayWindow(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadSettingsFromPrefs() {
        val sizePreset = prefs.getString("overlay_size_preset", "medium") ?: "medium"
        currentWidthDp = when (sizePreset) {
            "small" -> 85
            "medium" -> 120
            "large" -> 165
            else -> prefs.getInt("overlay_width_dp", 120).coerceIn(65, 240)
        }
        currentOpacityPct = prefs.getInt("overlay_opacity", 100).coerceIn(20, 100)
        showCharacterOnly = prefs.getBoolean("overlay_show_character", true)
        rememberPosition = prefs.getBoolean("overlay_remember_position", true)
        positionMode = prefs.getString("overlay_position_mode", "free") ?: "free"
        animStyle = prefs.getString("overlay_anim_style", "breathing") ?: "breathing"
        animSpeed = prefs.getFloat("overlay_anim_speed", 1.0f).coerceIn(0.5f, 2.0f)
        voiceLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"

        autoHideEnabled = prefs.getBoolean("overlay_auto_hide_enabled", true)
        val presetMin = prefs.getString("overlay_auto_hide_preset", "3") ?: "3"
        autoHideMinutes = when (presetMin) {
            "1" -> 1
            "3" -> 3
            "5" -> 5
            "10" -> 10
            else -> prefs.getInt("overlay_auto_hide_minutes", 3).coerceIn(1, 60)
        }
    }

    private fun scheduleOrResetAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (!autoHideEnabled || !showCharacterOnly) return
        if (isListening || isSpeaking || isProcessingCommand) return
        val delayMs = autoHideMinutes.coerceIn(1, 60) * 60_000L
        mainHandler.postDelayed(autoHideRunnable, delayMs)
    }

    private fun cancelAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    private fun shouldRunOverlayWakeStandby(): Boolean {
        if (!isRunning || !backgroundWakeStandbyActive) return false
        if (MainActivity.activeInstance?.isActivityInForeground == true) return false
        if (!prefs.getBoolean("wake_word_enabled", false)) return false
        if (!hasRecordAudioPermission()) return false
        if (isSpeaking || isListening || isProcessingCommand) return false

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return false

        if (prefs.getBoolean("battery_saving_wake_mode", true)) {
            val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bm != null) {
                val level = bm.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bm.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0 && (level * 100 / scale) < 15) {
                    return false
                }
            }
        }
        return true
    }

    private fun scheduleOverlayWakeLoop(delayMs: Long = 450L) {
        mainHandler.removeCallbacks(overlayWakeRestartRunnable)
        if (!prefs.getBoolean("wake_word_enabled", false)) return
        mainHandler.postDelayed(overlayWakeRestartRunnable, delayMs)
    }

    private fun cancelOverlayWakeStandby() {
        mainHandler.removeCallbacks(overlayWakeRestartRunnable)
        manualTapTriggered = false
        activeWakeWindowUntil = 0L
        if (isListening) {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            isListening = false
        }
        setOverlayVisualState("idle")
    }

    private fun resetPrefsToDefault() {
        val (screenW, _) = getUsableScreenSize()
        val defaultW = dpToPx(120)
        val defaultX = (screenW - defaultW - dpToPx(16)).coerceAtLeast(0)
        val defaultY = dpToPx(160)
        prefs.edit()
            .putBoolean("overlay_enabled", true)
            .putBoolean("overlay_show_character", true)
            .putString("overlay_size_preset", "medium")
            .putInt("overlay_width_dp", 120)
            .putString("overlay_position_mode", "free")
            .putInt("overlay_opacity", 100)
            .putBoolean("overlay_remember_position", true)
            .putString("overlay_anim_style", "breathing")
            .putFloat("overlay_anim_speed", 1.0f)
            .putBoolean("overlay_auto_hide_enabled", true)
            .putString("overlay_auto_hide_preset", "3")
            .putInt("overlay_auto_hide_minutes", 3)
            .putInt("overlay_x", defaultX)
            .putInt("overlay_y", defaultY)
            .apply()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Maxter J Character Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the floating Maxter J character over other apps"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val listenIntent = Intent(this, FloatingJiraiyaService::class.java).apply {
            action = ACTION_LISTEN
        }
        val pendingListen = PendingIntent.getService(
            this, 1, listenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val stopIntent = Intent(this, FloatingJiraiyaService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val wakeActive = prefs.getBoolean("wake_word_enabled", false) && hasRecordAudioPermission()
        val subtitle = if (wakeActive) {
            "Say 'Dei / Yow / Jiraiya / Master' or tap character"
        } else {
            "Tap character to speak • Drag to move"
        }

        val notification = builder
            .setContentTitle("Maxter J Character Overlay")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak", pendingListen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Overlay", pendingStop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            val fgsType = if (hasRecordAudioPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun loadExistingCharacterBitmap(): Bitmap? {
        val candidatePaths = listOf(
            "images/21574.png",
            "images/21769.png",
            "images/maxter-j-main.png",
            "21574.png",
            "21769.png",
            "maxter-j-main.png"
        )
        for (path in candidatePaths) {
            try {
                val stream: InputStream = assets.open(path)
                val decoded = BitmapFactory.decodeStream(stream)
                stream.close()
                if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                    aspectRatio = decoded.height.toFloat() / decoded.width.toFloat()
                    return ensureTransparentBackground(decoded)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun ensureTransparentBackground(src: Bitmap): Bitmap {
        if (src.hasAlpha()) {
            val cornerAlpha = Color.alpha(src.getPixel(0, 0))
            if (cornerAlpha == 0) {
                return src
            }
        }
        return try {
            val width = src.width
            val height = src.height
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(width * height)
            out.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r < 10 && g < 10 && b < 10) {
                    pixels[i] = Color.TRANSPARENT
                }
            }
            out.setPixels(pixels, 0, width, 0, 0, width, height)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun ensureSingleOverlayViewCreated() {
        if (overlayContainer != null && layoutParams != null) {
            return
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bitmap = loadExistingCharacterBitmap()
        val widthPx = dpToPx(currentWidthDp)
        val heightPx = (widthPx * aspectRatio).roundToInt()
        val (screenW, screenH) = getUsableScreenSize()

        val initialX: Int
        val initialY: Int
        if (rememberPosition && prefs.contains("overlay_x") && prefs.contains("overlay_y")) {
            val maxX = (screenW - widthPx).coerceAtLeast(0)
            val maxY = (screenH - heightPx).coerceAtLeast(0)
            initialX = prefs.getInt("overlay_x", maxX - dpToPx(12)).coerceIn(0, maxX)
            initialY = prefs.getInt("overlay_y", dpToPx(160)).coerceIn(0, maxY)
        } else {
            val (px, py) = computePresetCoordinates(positionMode, widthPx, heightPx, screenW, screenH)
            initialX = px
            initialY = py
        }

        val lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            alpha = currentOpacityPct / 100f
        }
        layoutParams = lp

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val imgView = ImageView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            val pad = dpToPx(5)
            setPadding(pad, pad, pad, pad)
            if (bitmap != null) {
                setImageBitmap(bitmap)
            }
            visibility = if (showCharacterOnly) View.VISIBLE else View.INVISIBLE
            alpha = currentOpacityPct / 100f
        }

        container.addView(
            imgView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlayContainer = container
        characterImageView = imgView

        setupTouchDragTapAndPinchListener(container)

        try {
            windowManager.addView(container, lp)
            setOverlayVisualState("idle")
        } catch (_: Exception) {
        }
    }

    private fun applyOverlayConfiguration(repositionIfPreset: Boolean) {
        val container = overlayContainer ?: return
        val imgView = characterImageView ?: return
        val lp = layoutParams ?: return

        val widthPx = dpToPx(currentWidthDp)
        val heightPx = (widthPx * aspectRatio).roundToInt()
        val (screenW, screenH) = getUsableScreenSize()
        val maxX = (screenW - widthPx).coerceAtLeast(0)
        val maxY = (screenH - heightPx).coerceAtLeast(0)

        lp.width = widthPx
        lp.height = heightPx
        lp.alpha = currentOpacityPct / 100f

        if (repositionIfPreset && positionMode != "free") {
            val (px, py) = computePresetCoordinates(positionMode, widthPx, heightPx, screenW, screenH)
            lp.x = px
            lp.y = py
            if (rememberPosition) {
                prefs.edit().putInt("overlay_x", lp.x).putInt("overlay_y", lp.y).apply()
            }
        } else {
            lp.x = lp.x.coerceIn(0, maxX)
            lp.y = lp.y.coerceIn(0, maxY)
        }

        imgView.visibility = if (showCharacterOnly) View.VISIBLE else View.INVISIBLE
        imgView.alpha = currentOpacityPct / 100f

        try {
            windowManager.updateViewLayout(container, lp)
        } catch (_: Exception) {
        }

        setOverlayVisualState(currentVisualState, forceRestart = true)
    }

    private fun computePresetCoordinates(
        mode: String,
        widthPx: Int,
        heightPx: Int,
        screenW: Int,
        screenH: Int
    ): Pair<Int, Int> {
        val margin = dpToPx(12)
        val maxX = (screenW - widthPx).coerceAtLeast(0)
        val maxY = (screenH - heightPx).coerceAtLeast(0)
        val centerX = (maxX / 2).coerceIn(0, maxX)
        val centerY = (maxY / 2).coerceIn(0, maxY)

        return when (mode.lowercase(Locale.ROOT)) {
            "left" -> Pair(margin.coerceAtMost(maxX), centerY)
            "right" -> Pair((maxX - margin).coerceAtLeast(0), centerY)
            "top" -> Pair(centerX, dpToPx(44).coerceAtMost(maxY))
            "bottom" -> Pair(centerX, (maxY - dpToPx(64)).coerceAtLeast(0))
            else -> {
                val savedX = prefs.getInt("overlay_x", (maxX - margin).coerceAtLeast(0)).coerceIn(0, maxX)
                val savedY = prefs.getInt("overlay_y", dpToPx(160).coerceAtMost(maxY)).coerceIn(0, maxY)
                Pair(savedX, savedY)
            }
        }
    }

    fun setOverlayVisualState(state: String, forceRestart: Boolean = false) {
        mainHandler.post {
            if (!forceRestart && currentVisualState == state && stateAnimator?.isRunning == true) {
                return@post
            }
            currentVisualState = state
            stateAnimator?.cancel()
            stateAnimator = null

            val img = characterImageView ?: return@post
            img.translationY = 0f
            img.rotation = 0f
            img.scaleX = 1.0f
            img.scaleY = 1.0f

            if (!showCharacterOnly) return@post

            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) return@post

            when (state) {
                "listening" -> {
                    stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 360L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            val s = 1.0f + (0.075f * f)
                            img.scaleX = s
                            img.scaleY = s
                            img.translationY = -dpToPx(3) * f
                        }
                        start()
                    }
                }
                "processing", "thinking", "executing" -> {
                    stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 280L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            img.translationY = -dpToPx(5) * f
                            img.rotation = -2.0f * f
                        }
                        start()
                    }
                }
                "speaking", "talking" -> {
                    stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 340L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            val s = 1.0f + (0.045f * f)
                            img.scaleX = s
                            img.scaleY = s
                            img.translationY = -dpToPx(4) * f
                        }
                        start()
                    }
                }
                else -> {
                    if (animStyle == "none") return@post
                    val baseDurationMs = when (animStyle) {
                        "float" -> 2600L
                        "pulse" -> 1800L
                        else -> 2800L
                    }
                    val scaledDuration = (baseDurationMs / animSpeed.coerceIn(0.5f, 2.0f)).toLong().coerceAtLeast(500L)
                    val maxLiftPx = dpToPx(4).toFloat()

                    stateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = scaledDuration
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            when (animStyle) {
                                "float" -> img.translationY = -maxLiftPx * f
                                "pulse" -> {
                                    val s = 1.0f + (0.03f * f)
                                    img.scaleX = s
                                    img.scaleY = s
                                }
                                else -> {
                                    img.translationY = -(maxLiftPx * 0.75f) * f
                                    val s = 1.0f + (0.018f * f)
                                    img.scaleX = s
                                    img.scaleY = s
                                }
                            }
                        }
                        start()
                    }
                }
            }
        }
    }

    private fun setupTouchDragTapAndPinchListener(targetView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTimestamp = 0L
        var isDragging = false

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scheduleOrResetAutoHideTimer()
                    val newWidthDp = (currentWidthDp * detector.scaleFactor).toInt().coerceIn(65, 240)
                    if (newWidthDp != currentWidthDp) {
                        currentWidthDp = newWidthDp
                        prefs.edit()
                            .putString("overlay_size_preset", "custom")
                            .putInt("overlay_width_dp", currentWidthDp)
                            .apply()
                        applyOverlayConfiguration(repositionIfPreset = false)
                    }
                    return true
                }
            }
        )

        targetView.setOnTouchListener { _, event ->
            val lp = layoutParams ?: return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            scheduleOrResetAutoHideTimer()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTimestamp = SystemClock.uptimeMillis()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > dpToPx(6) || abs(dy) > dpToPx(6)) {
                            isDragging = true
                            val (screenW, screenH) = getUsableScreenSize()
                            val maxX = (screenW - lp.width).coerceAtLeast(0)
                            val maxY = (screenH - lp.height).coerceAtLeast(0)

                            lp.x = (initialX + dx).coerceIn(0, maxX)
                            lp.y = (initialY + dy).coerceIn(0, maxY)
                            overlayContainer?.let {
                                try {
                                    windowManager.updateViewLayout(it, lp)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = SystemClock.uptimeMillis() - downTimestamp
                    if (isDragging) {
                        if (rememberPosition) {
                            prefs.edit()
                                .putInt("overlay_x", lp.x)
                                .putInt("overlay_y", lp.y)
                                .apply()
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && elapsed < 500L) {
                        startOverlayVoiceListening(isManualTap = true)
                    }
                    scheduleOrResetAutoHideTimer()
                    true
                }
                else -> false
            }
        }
    }

    private fun getUsableScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width().coerceAtLeast(320), bounds.height().coerceAtLeast(480))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(320), metrics.heightPixels.coerceAtLeast(480))
        }
    }

    private fun ensureOverlaySpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    cancelAutoHideTimer()
                    val isActiveSession = manualTapTriggered || SystemClock.elapsedRealtime() < activeWakeWindowUntil
                    setOverlayVisualState(if (isActiveSession) "listening" else "idle")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    if (manualTapTriggered || SystemClock.elapsedRealtime() < activeWakeWindowUntil) {
                        setOverlayVisualState("processing")
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    manualTapTriggered = false
                    setOverlayVisualState("idle")
                    scheduleOrResetAutoHideTimer()

                    if (error == 12 || error == 13 || error == 14) {
                        offlinePrefUnsupported = true
                    }
                    if (shouldRunOverlayWakeStandby()) {
                        val backoff = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            850L
                        } else {
                            1600L
                        }
                        scheduleOverlayWakeLoop(backoff)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = partials?.firstOrNull()?.trim().orEmpty()
                    if (partialText.isNotEmpty() && !manualTapTriggered) {
                        val wakeCheck = commandController.inspectWakeWord(partialText)
                        if (wakeCheck.hasWakeWord) {
                            setOverlayVisualState("listening")
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val wasManual = manualTapTriggered
                    manualTapTriggered = false

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                    val topConfidence = if (scores != null && scores.isNotEmpty()) scores[0] else -1.0f

                    if (spokenText.isEmpty()) {
                        setOverlayVisualState("idle")
                        scheduleOrResetAutoHideTimer()
                        if (shouldRunOverlayWakeStandby()) scheduleOverlayWakeLoop(550L)
                        return
                    }

                    val now = SystemClock.elapsedRealtime()
                    val inWakeWindow = now < activeWakeWindowUntil
                    val minConfidence = prefs.getFloat("wake_confidence_threshold", 0.40f)
                    val wakeInspection = commandController.inspectWakeWord(spokenText)
                    val confidencePass = topConfidence < 0f || topConfidence == 0f || topConfidence >= minConfidence

                    // Support both wake-word commands ("Dei, light on pannu") AND high-confidence direct device commands ("Light on pannu", "Instagram open pannu")
                    val allowDirectWithoutWake = prefs.getBoolean("allow_direct_commands_without_wake", false) &&
                            commandController.isHighConfidenceLocalDeviceCommand(spokenText)

                    if (!wasManual && !inWakeWindow && !allowDirectWithoutWake) {
                        if (!wakeInspection.hasWakeWord || !confidencePass) {
                            setOverlayVisualState("idle")
                            scheduleOrResetAutoHideTimer()
                            if (shouldRunOverlayWakeStandby()) scheduleOverlayWakeLoop(500L)
                            return
                        }
                    }

                    val commandToRun = if (wakeInspection.hasWakeWord) {
                        wakeInspection.strippedCommand
                    } else {
                        spokenText
                    }

                    // Case 1: User spoke ONLY the wake word (e.g. "Dei, Jiraiya!", "Yow Jiraiya", "Master")
                    if (commandToRun.isBlank()) {
                        if ((now - lastWakeActivationRealtime) < 1200L) {
                            if (shouldRunOverlayWakeStandby()) scheduleOverlayWakeLoop(250L)
                            return
                        }
                        lastWakeActivationRealtime = now
                        val windowSec = prefs.getInt("wake_listening_window_sec", 6).coerceIn(3, 12)
                        activeWakeWindowUntil = now + (windowSec * 1000L)
                        setOverlayVisualState("listening")
                        Toast.makeText(
                            applicationContext,
                            if (voiceLocale.startsWith("ta")) "🎤 சொல்லுங்கள் நண்பா!" else "🎤 Maxter J Listening...",
                            Toast.LENGTH_SHORT
                        ).show()
                        scheduleOverlayWakeLoop(120L)
                        return
                    }

                    // Case 2: Execute command immediately
                    activeWakeWindowUntil = 0L
                    isProcessingCommand = true
                    setOverlayVisualState("executing")
                    try {
                        val handled = commandController.processVoiceCommand(
                            rawInput = commandToRun,
                            isFromVerifiedSession = wasManual,
                            asrConfidence = topConfidence
                        )
                        if (!handled) {
                            setOverlayVisualState("processing")
                            commandController.handleGeneralConversationOrAiQuery(commandToRun)
                        }
                    } finally {
                        isProcessingCommand = false
                        if (!isSpeaking) {
                            setOverlayVisualState("idle")
                        }
                        scheduleOrResetAutoHideTimer()
                        if (shouldRunOverlayWakeStandby()) {
                            scheduleOverlayWakeLoop(750L)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startOverlayVoiceListening(isManualTap: Boolean) {
        if (!hasRecordAudioPermission()) {
            if (isManualTap) {
                Toast.makeText(this, "Grant Microphone permission inside Maxter J first", Toast.LENGTH_SHORT).show()
            }
            return
        }
        voiceLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        mainHandler.post {
            try {
                mainHandler.removeCallbacks(overlayWakeRestartRunnable)
                if (isSpeaking) {
                    if (isManualTap) {
                        tts?.stop()
                        isSpeaking = false
                    } else {
                        scheduleOverlayWakeLoop(800L)
                        return@post
                    }
                }
                ensureOverlaySpeechRecognizer()
                if (isListening && isManualTap) {
                    speechRecognizer?.stopListening()
                    isListening = false
                    setOverlayVisualState("idle")
                    scheduleOrResetAutoHideTimer()
                    return@post
                } else if (isListening) {
                    return@post
                }

                manualTapTriggered = isManualTap
                val isStandby = !isManualTap && SystemClock.elapsedRealtime() >= activeWakeWindowUntil
                val useOffline = isStandby && prefs.getBoolean("prefer_offline_wake", false) && !offlinePrefUnsupported

                val recognizerIntent = (prebuiltRecognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLocale)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline)
                    // Fast end-of-speech silence thresholds for snappy command response
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 350L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 280L)
                }
                prebuiltRecognizerIntent = recognizerIntent
                cancelAutoHideTimer()
                speechRecognizer?.startListening(recognizerIntent)
                if (isManualTap) {
                    setOverlayVisualState("listening")
                    Toast.makeText(
                        applicationContext,
                        if (voiceLocale.startsWith("ta")) "🎤 Maxter J கேட்கிறது..." else "🎤 Maxter J Listening...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                isListening = false
                setOverlayVisualState("idle")
                scheduleOrResetAutoHideTimer()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            configureOverlayVoice(voiceLocale)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    cancelAutoHideTimer()
                    setOverlayVisualState("speaking")
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    setOverlayVisualState("idle")
                    scheduleOrResetAutoHideTimer()
                    if (shouldRunOverlayWakeStandby()) {
                        scheduleOverlayWakeLoop(400L)
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    setOverlayVisualState("idle")
                    scheduleOrResetAutoHideTimer()
                    if (shouldRunOverlayWakeStandby()) {
                        scheduleOverlayWakeLoop(550L)
                    }
                }
            })
        }
    }

    private fun configureOverlayVoice(langTag: String) {
        val engine = tts ?: return
        val cleanTag = if (langTag.lowercase().startsWith("ta")) "ta-IN" else "en-IN"
        engine.language = Locale.forLanguageTag(cleanTag)
        val pitch = prefs.getFloat("voice_pitch", 0.68f).coerceIn(0.45f, 1.5f)
        val rate = prefs.getFloat("voice_rate", 0.92f).coerceIn(0.5f, 1.5f)
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    private fun speakFromOverlay(text: String, langTag: String) {
        if (!ttsReady || text.isBlank()) return
        if (!prefs.getBoolean("voice_response_enabled", true)) return
        mainHandler.post {
            if (isListening) {
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                isListening = false
            }
            configureOverlayVoice(langTag)
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            isSpeaking = true
            cancelAutoHideTimer()
            setOverlayVisualState("speaking")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "overlay_cmd_${System.currentTimeMillis()}")
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun removeOverlayViewSafely() {
        stateAnimator?.cancel()
        stateAnimator = null
        overlayContainer?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayContainer = null
        characterImageView = null
        layoutParams = null
    }

    override fun onDestroy() {
        isRunning = false
        cancelAutoHideTimer()
        cancelOverlayWakeStandby()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        removeOverlayViewSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
