package com.jiraiyaai.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FloatingJiraiyaService — Native Android Character-Only Floating Overlay Service
 *
 * Strictly displays ONLY the existing Maxter J character (`assets/images/21574.png` / `21769.png`)
 * over other apps using `WindowManager` (`TYPE_APPLICATION_OVERLAY`) with a 100% transparent
 * background (`PixelFormat.TRANSLUCENT` and `Color.TRANSPARENT`).
 *
 * - Contains ZERO text labels, ZERO buttons, ZERO panels, and ZERO rectangular backgrounds.
 * - Controlled entirely from the app's Settings screen via JavaScript bridge & SharedPreferences.
 * - Supports real-time updates for Size (Small, Medium, Large, Custom), Position (Left, Right,
 *   Top, Bottom, Free Drag), Transparency/Opacity, Remember Position, Idle Animations
 *   (Breathing, Float, Pulse, None), and Animation Speed (Slow, Normal, Fast).
 * - Handles screen rotation (`onConfigurationChanged`), screen boundary clamping, permission
 *   checks, and single-instance deduplication so the overlay never duplicates itself.
 */
class FloatingJiraiyaService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var commandController: MaxterVoiceCommandController

    private var overlayContainer: FrameLayout? = null
    private var characterImageView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var idleAnimator: ValueAnimator? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var voiceLocale = "ta-IN"

    private var currentWidthDp = 120
    private var currentOpacityPct = 100
    private var showCharacterOnly = true
    private var rememberPosition = true
    private var positionMode = "free"
    private var animStyle = "breathing"
    private var animSpeed = 1.0f
    private var aspectRatio = 807f / 545f // Exact proportions of existing 545x807 character asset

    companion object {
        const val CHANNEL_ID = "maxter_j_overlay_channel"
        const val NOTIFICATION_ID = 707
        const val ACTION_START = "com.jiraiyaai.app.ACTION_START_OVERLAY"
        const val ACTION_UPDATE_CONFIG = "com.jiraiyaai.app.ACTION_UPDATE_OVERLAY_CONFIG"
        const val ACTION_RESET_CONFIG = "com.jiraiyaai.app.ACTION_RESET_OVERLAY_CONFIG"
        const val ACTION_LISTEN = "com.jiraiyaai.app.ACTION_LISTEN_OVERLAY"
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
        } else {
            isRunning = false
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlayWindow()) {
            removeOverlayViewSafely()
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("overlay_enabled", false).apply()
                removeOverlayViewSafely()
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET_CONFIG -> {
                resetPrefsToDefault()
                loadSettingsFromPrefs()
                ensureSingleOverlayViewCreated()
                applyOverlayConfiguration(repositionIfPreset = true)
            }
            ACTION_UPDATE_CONFIG -> {
                val prevPosMode = positionMode
                loadSettingsFromPrefs()
                if (!prefs.getBoolean("overlay_enabled", true)) {
                    removeOverlayViewSafely()
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureSingleOverlayViewCreated()
                val shouldReposition = (positionMode != prevPosMode && positionMode != "free") ||
                        intent.getBooleanExtra("force_reposition", false)
                applyOverlayConfiguration(repositionIfPreset = shouldReposition)
            }
            ACTION_LISTEN -> {
                startOverlayVoiceListening()
            }
            ACTION_START, null -> {
                loadSettingsFromPrefs()
                prefs.edit().putBoolean("overlay_enabled", true).apply()
                ensureSingleOverlayViewCreated()
                applyOverlayConfiguration(repositionIfPreset = !rememberPosition)
                isRunning = true
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the floating character within visible screen boundaries on screen rotation
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

        val notification = builder
            .setContentTitle("Maxter J Character Overlay")
            .setContentText("Floating character active • Customize or stop in Settings")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Overlay", pendingStop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Loads the exact existing Maxter J character bitmap from `assets/images/21574.png`
     * (or `21769.png` / `maxter-j-main.png`) and ensures transparent background.
     */
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

    /**
     * If a character image asset has an opaque solid black background at its corners,
     * converts the outer background pixels to transparent while preserving the character's
     * exact colors, proportions, and visual identity. If already RGBA transparent, returns as-is.
     */
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

    /**
     * Creates the single floating character window if not already added.
     * Contains ONLY `characterImageView` on a transparent background — no text, buttons, or panels.
     */
    private fun ensureSingleOverlayViewCreated() {
        if (overlayContainer != null && layoutParams != null) {
            return // Already created — prevents duplicate overlays
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
            // Slight padding so idle breathing/floating animation never clips at window edge
            val pad = dpToPx(4)
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

        setupTouchDragAndPinchListener(container)

        try {
            windowManager.addView(container, lp)
            applyCharacterIdleAnimation()
        } catch (_: Exception) {
        }
    }

    /**
     * Applies size, position, transparency, visibility, and animation settings immediately.
     */
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

        applyCharacterIdleAnimation()
    }

    /**
     * Computes screen-bounded coordinates for Left, Right, Top, Bottom, or Free Drag presets.
     */
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

    /**
     * Applies smooth 2D idle animations to the character (`breathing`, `float`, `pulse`, `none`)
     * with adjustable animation speed (`0.5x` - `2.0x`).
     */
    private fun applyCharacterIdleAnimation() {
        idleAnimator?.cancel()
        idleAnimator = null

        val img = characterImageView ?: return
        img.translationY = 0f
        img.scaleX = 1.0f
        img.scaleY = 1.0f

        if (animStyle == "none" || !showCharacterOnly) {
            return
        }

        val baseDurationMs = when (animStyle) {
            "float" -> 2600L
            "pulse" -> 1800L
            else -> 2800L // "breathing"
        }
        val scaledDuration = (baseDurationMs / animSpeed.coerceIn(0.5f, 2.0f)).toLong().coerceAtLeast(500L)
        val maxLiftPx = dpToPx(4).toFloat()

        idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = scaledDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                when (animStyle) {
                    "float" -> {
                        img.translationY = -maxLiftPx * f
                    }
                    "pulse" -> {
                        val s = 1.0f + (0.03f * f)
                        img.scaleX = s
                        img.scaleY = s
                    }
                    else -> {
                        // Gentle Breathing
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

    /**
     * Enables smooth dragging anywhere within visible screen boundaries and pinch-to-resize.
     */
    private fun setupTouchDragAndPinchListener(targetView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
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

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 5 || abs(dy) > 5) {
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
                    if (isDragging && rememberPosition) {
                        prefs.edit()
                            .putInt("overlay_x", lp.x)
                            .putInt("overlay_y", lp.y)
                            .apply()
                    }
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

    private fun startOverlayVoiceListening() {
        voiceLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { isListening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening = false }
                    override fun onError(error: Int) { isListening = false }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                        if (spokenText.isNotEmpty()) {
                            commandController.processVoiceCommand(spokenText, isFromVerifiedSession = false)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLocale)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(recognizerIntent)
        } catch (_: Exception) {
            isListening = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            configureOverlayVoice(voiceLocale)
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
        configureOverlayVoice(langTag)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "overlay_cmd_${System.currentTimeMillis()}")
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun removeOverlayViewSafely() {
        idleAnimator?.cancel()
        idleAnimator = null
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
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        removeOverlayViewSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}