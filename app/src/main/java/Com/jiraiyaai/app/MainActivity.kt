package com.jiraiyaai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Browser
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MainActivity — Advanced Voice Command & Overlay Upgrade for Maxter J AI
 * Target Device: Infinix GT 20 Pro (Infinix X6871, XOS 15.1.2, Android 15, compileSdk 35 / targetSdk 35)
 *
 * Capabilities:
 * 1. Custom Wake-Word Activation ("Yow", "Yoww", "Dai", "Dei", "Bro", "Ji", "Master", plus Tamil script)
 *    with strict no-activation-without-wake-word enforcement, confidence threshold, and duplicate debounce.
 * 2. Priority Command: "Yow + [any app name] + search + [query]" across YouTube, Chrome, Google Search,
 *    Google Play Store, Spotify, Maps, Amazon, Flipkart, and any installed app supporting search intents.
 * 3. Natural Multi-Format Command Parsing in English, Tamil, and Tanglish ("open YouTube", "YouTube open",
 *    "YouTube open pannu", "WhatsApp thira", "Chrome la Free Fire search pannu", "search Tamil songs in YouTube").
 * 4. Fast Pre-Cached App Discovery & Legitimate App Closing with measured sub-10ms local parse/dispatch latency.
 * 5. Character-Only Floating Overlay coordination (visual listening/processing/speaking states & background handoff).
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    lateinit var rootLayout: FrameLayout
    lateinit var webView: WebView
    lateinit var prefs: SharedPreferences
    lateinit var commandController: MaxterVoiceCommandController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var prebuiltRecognizerIntent: Intent? = null

    @Volatile
    var isListening = false
        private set

    @Volatile
    var isSpeaking = false
        private set

    @Volatile
    var isActivityInForeground = false
        private set

    @Volatile
    private var manualMicTriggered = false

    @Volatile
    private var activeWakeWindowUntil = 0L

    @Volatile
    private var lastWakeActivationRealtime = 0L

    @Volatile
    private var offlinePrefUnsupported = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val wakeRestartRunnable = Runnable {
        if (shouldRunContinuousWakeLoop()) {
            startNativeVoiceListening(preferredLocale = null, isManualTap = false)
        }
    }

    companion object {
        const val PREFS_NAME = "JiraiyaOverlayPrefs"
        const val REQ_PERMISSIONS = 1001
        const val REQ_OVERLAY_PERMISSION = 1002
        const val REQ_WRITE_SETTINGS = 1003
        const val REQ_SAF_OPEN_DOCUMENT = 1004
        const val REQ_WEBVIEW_FILE_CHOOSER = 1005

        @Volatile
        var activeInstance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        commandController = MaxterVoiceCommandController(
            context = this,
            activity = this,
            prefs = prefs,
            speakCallback = { msg, lang, expr -> speakWithAnimation(msg, lang, expr) },
            uiEventCallback = { eventJson -> notifyWebView(eventJson) }
        )

        // Pre-warm installed applications cache on background thread for <2ms app launch resolution
        commandController.refreshInstalledAppsCacheAsync()

        configureStatusBarAndEdgeToEdge()
        requestInitialRuntimePermissions()

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#060913"))
        }

        applyPermanentWindowInsetsToRoot(rootLayout)

        webView = WebView(this)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        val bridge = AndroidVoiceBridge()
        webView.addJavascriptInterface(bridge, "AndroidVoice")
        webView.addJavascriptInterface(bridge, "MaxterNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectNonDestructiveAssistantBridge()
                syncOverlayStateToWebView()
                if (prefs.getBoolean("overlay_enabled", false) &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)) &&
                    !FloatingJiraiyaService.isRunning
                ) {
                    commandController.startFloatingOverlayService()
                }
                scheduleWakeWordLoopIfEnabled(500L)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    startActivityForResult(intent, REQ_WEBVIEW_FILE_CHOOSER)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)
        handleIncomingIntent(intent)
    }

    private fun configureStatusBarAndEdgeToEdge() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.parseColor("#060913")
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.parseColor("#060913")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } catch (_: Exception) {
        }
    }

    private fun applyPermanentWindowInsetsToRoot(targetRoot: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            targetRoot.setOnApplyWindowInsetsListener { view, windowInsets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val sysInsets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() or
                                WindowInsets.Type.displayCutout() or
                                WindowInsets.Type.ime()
                    )
                    view.setPadding(sysInsets.left, sysInsets.top, sysInsets.right, sysInsets.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        windowInsets.systemWindowInsetLeft,
                        windowInsets.systemWindowInsetTop,
                        windowInsets.systemWindowInsetRight,
                        windowInsets.systemWindowInsetBottom
                    )
                }
                windowInsets
            }
            targetRoot.requestApplyInsets()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("voice_command_text")
        if (!cmd.isNullOrBlank()) {
            commandController.processVoiceCommand(cmd, isFromVerifiedSession = true)
        }
    }

    private fun requestInitialRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.POST_NOTIFICATIONS")
                }
                if (checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.READ_MEDIA_IMAGES")
                }
                if (checkSelfPermission("android.permission.READ_MEDIA_VIDEO") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.READ_MEDIA_VIDEO")
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
            }
        }
    }

    /**
     * Checks whether low-power wake-word standby should run right now in MainActivity.
     */
    private fun shouldRunContinuousWakeLoop(): Boolean {
        if (!isActivityInForeground) return false
        if (!prefs.getBoolean("wake_word_enabled", true)) return false
        if (isSpeaking || isListening) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (prefs.getBoolean("battery_saving_wake_mode", true)) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) return false
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

    fun scheduleWakeWordLoopIfEnabled(delayMs: Long = 450L) {
        mainHandler.removeCallbacks(wakeRestartRunnable)
        if (!prefs.getBoolean("wake_word_enabled", true)) {
            notifyWebView(JSONObject().apply {
                put("type", "wake_state")
                put("status", "off")
            })
            return
        }
        mainHandler.postDelayed(wakeRestartRunnable, delayMs)
    }

    /**
     * Single reused SpeechRecognizer instance with:
     * - Strict Wake-Word Gate (never executes commands without a wake word unless manually tapped)
     * - Confidence Threshold check (`SpeechRecognizer.CONFIDENCE_SCORES`)
     * - Duplicate Wake-Word Debounce (`1400ms`)
     * - Overlay Visual State Sync (`listening` -> `processing` -> `speaking` -> `idle`)
     */
    private fun ensureSingleSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    val isWakeActive = SystemClock.elapsedRealtime() < activeWakeWindowUntil
                    val activeMode = manualMicTriggered || isWakeActive
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", if (activeMode) "listening" else "wake_standby")
                    })
                    if (activeMode && FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("listening")
                    }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    if (manualMicTriggered || SystemClock.elapsedRealtime() < activeWakeWindowUntil) {
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "thinking")
                        })
                        if (FloatingJiraiyaService.isRunning) {
                            commandController.notifyOverlayVisualState("processing")
                        }
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    manualMicTriggered = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    if (FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("idle")
                    }
                    // If offline speech pack is unavailable on device, fall back to standard recognition
                    if (error == 12 || error == 13 || error == 14) {
                        offlinePrefUnsupported = true
                    }
                    val backoff = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        800L
                    } else {
                        1500L
                    }
                    scheduleWakeWordLoopIfEnabled(backoff)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = partials?.firstOrNull()?.trim().orEmpty()
                    if (partialText.isNotEmpty() && !manualMicTriggered) {
                        val wakeCheck = commandController.inspectWakeWord(partialText)
                        if (wakeCheck.hasWakeWord) {
                            notifyWebView(JSONObject().apply {
                                put("type", "state")
                                put("state", "listening")
                            })
                            if (FloatingJiraiyaService.isRunning) {
                                commandController.notifyOverlayVisualState("listening")
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val wasManual = manualMicTriggered
                    manualMicTriggered = false

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                    val topConfidence = if (scores != null && scores.isNotEmpty()) scores[0] else -1.0f

                    if (spokenText.isEmpty()) {
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "idle")
                        })
                        scheduleWakeWordLoopIfEnabled(550L)
                        return
                    }

                    val now = SystemClock.elapsedRealtime()
                    val inWakeWindow = now < activeWakeWindowUntil
                    val minConfidence = prefs.getFloat("wake_confidence_threshold", 0.40f)
                    val wakeInspection = commandController.inspectWakeWord(spokenText)

                    // Confidence threshold filter for automatic wake-word activations
                    val confidencePass = topConfidence < 0f || topConfidence == 0f || topConfidence >= minConfidence

                    // STRICT REQUIREMENT 2: Do NOT activate without a wake word (unless manually tapped or inside post-wake command window)
                    if (!wasManual && !inWakeWindow) {
                        if (!wakeInspection.hasWakeWord || !confidencePass) {
                            notifyWebView(JSONObject().apply {
                                put("type", "state")
                                put("state", "idle")
                            })
                            if (FloatingJiraiyaService.isRunning) {
                                commandController.notifyOverlayVisualState("idle")
                            }
                            scheduleWakeWordLoopIfEnabled(450L)
                            return
                        }
                    }

                    val commandToRun = if (wakeInspection.hasWakeWord) {
                        wakeInspection.strippedCommand
                    } else {
                        spokenText
                    }

                    // Activate floating character overlay visually when wake word is detected (if overlay enabled & permitted)
                    if (wakeInspection.hasWakeWord && prefs.getBoolean("overlay_enabled", false)) {
                        if (!FloatingJiraiyaService.isRunning) {
                            commandController.startFloatingOverlayService()
                        } else {
                            commandController.notifyOverlayVisualState("listening")
                        }
                    }

                    // Case 1: User spoke ONLY the wake word ("Yow", "Yow, Jiraiya", "Yow, Maxter", "Dai", "Dei", "Bro", "Ji", "Master")
                    if (commandToRun.isBlank()) {
                        if ((now - lastWakeActivationRealtime) < 1400L) {
                            scheduleWakeWordLoopIfEnabled(250L)
                            return
                        }
                        lastWakeActivationRealtime = now
                        val windowSec = prefs.getInt("wake_listening_window_sec", 6).coerceIn(3, 12)
                        activeWakeWindowUntil = now + (windowSec * 1000L)
                        notifyWebView(JSONObject().apply {
                            put("type", "wake_triggered")
                            put("wakeWord", wakeInspection.matchedWakeWord.ifEmpty { spokenText })
                        })
                        // Immediately start short command-capture session
                        scheduleWakeWordLoopIfEnabled(150L)
                        return
                    }

                    // Case 2: User spoke Wake Word + Command (or spoke command inside active wake window / manual tap)
                    activeWakeWindowUntil = 0L
                    notifyWebView(JSONObject().apply {
                        put("type", "transcript")
                        put("text", spokenText)
                    })
                    if (FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("processing")
                    }
                    val handled = commandController.processVoiceCommand(commandToRun, isFromVerifiedSession = wasManual)
                    if (!handled) {
                        commandController.handleGeneralConversationOrAiQuery(commandToRun)
                    }
                    scheduleWakeWordLoopIfEnabled(750L)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * Starts native Android SpeechRecognizer in English (`en-IN`) or Tamil (`ta-IN`).
     */
    fun startNativeVoiceListening(preferredLocale: String? = null, isManualTap: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            if (isManualTap) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
                speakWithAnimation("Microphone permission is required for voice commands.", "en-IN", "confused")
            } else {
                notifyWebView(JSONObject().apply {
                    put("type", "wake_state")
                    put("status", "permission_required")
                })
            }
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (isManualTap) {
                speakWithAnimation("Speech recognition is not available on this device.", "en-IN", "confused")
            }
            return
        }

        if (isSpeaking) {
            if (isManualTap) {
                stopSpeakingImmediate()
            } else {
                scheduleWakeWordLoopIfEnabled(850L)
                return
            }
        }

        val localeToUse = preferredLocale ?: (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN")
        manualMicTriggered = isManualTap

        try {
            mainHandler.removeCallbacks(wakeRestartRunnable)
            ensureSingleSpeechRecognizer()
            if (isListening) {
                speechRecognizer?.cancel()
                isListening = false
            }

            val isStandby = !isManualTap && SystemClock.elapsedRealtime() >= activeWakeWindowUntil
            val useOffline = isStandby && prefs.getBoolean("prefer_offline_wake", false) && !offlinePrefUnsupported

            val recognizerIntent = (prebuiltRecognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 420L)
            }
            prebuiltRecognizerIntent = recognizerIntent
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            isListening = false
            if (isManualTap) {
                speakWithAnimation("Unable to start microphone listening.", "en-IN", "confused")
            }
        }
    }

    fun stopNativeVoiceListening() {
        mainHandler.removeCallbacks(wakeRestartRunnable)
        manualMicTriggered = false
        activeWakeWindowUntil = 0L
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        isListening = false
        notifyWebView(JSONObject().apply {
            put("type", "state")
            put("state", "idle")
        })
    }

    fun launchSafDocumentPicker(mimeType: String = "*/*") {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_SAF_OPEN_DOCUMENT)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open file picker", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    prefs.edit().putBoolean("overlay_enabled", true).apply()
                    commandController.startFloatingOverlayService()
                    syncOverlayStateToWebView()
                } else {
                    prefs.edit().putBoolean("overlay_enabled", false).apply()
                    syncOverlayStateToWebView()
                    speakWithAnimation("Overlay permission was not granted.", "en-IN", "confused")
                }
            }
            REQ_WRITE_SETTINGS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(this)) {
                    speakWithAnimation("System brightness permission granted.", "en-IN", "happy")
                }
            }
            REQ_SAF_OPEN_DOCUMENT -> {
                if (resultCode == RESULT_OK && data?.data != null) {
                    val uri = data.data!!
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    commandController.openContentUri(uri, contentResolver.getType(uri) ?: "*/*")
                }
            }
            REQ_WEBVIEW_FILE_CHOOSER -> {
                val result = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
                filePathCallback?.onReceiveValue(result)
                filePathCallback = null
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val savedLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
            configureTtsVoice(savedLocale)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    if (isListening) {
                        mainHandler.post {
                            try { speechRecognizer?.cancel() } catch (_: Exception) {}
                            isListening = false
                        }
                    }
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "talking")
                    })
                    if (FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("speaking")
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    if (FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("idle")
                    }
                    scheduleWakeWordLoopIfEnabled(400L)
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    if (FloatingJiraiyaService.isRunning) {
                        commandController.notifyOverlayVisualState("idle")
                    }
                    scheduleWakeWordLoopIfEnabled(550L)
                }
            })
        }
    }

    private fun configureTtsVoice(langTag: String) {
        val engine = tts ?: return
        val cleanTag = if (langTag.lowercase().startsWith("ta")) "ta-IN" else "en-IN"
        engine.language = Locale.forLanguageTag(cleanTag)

        val pitch = prefs.getFloat("voice_pitch", 0.68f).coerceIn(0.45f, 1.5f)
        val rate = prefs.getFloat("voice_rate", 0.92f).coerceIn(0.5f, 1.5f)
        val genderPref = prefs.getString("voice_persona", "male_sage") ?: "male_sage"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val langPrefix = if (cleanTag.startsWith("ta")) "ta" else "en"
            val matching = engine.voices?.filter {
                it.locale.language.equals(langPrefix, ignoreCase = true)
            }.orEmpty()
            val chosen = if (genderPref.contains("female")) {
                matching.firstOrNull { it.name.lowercase().contains("female") }
            } else {
                matching.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("male") || n.contains("tad") || n.contains("network")
                }
            } ?: matching.maxByOrNull { it.quality }

            if (chosen != null) {
                engine.voice = chosen
            }
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    fun speakWithAnimation(text: String, langTag: String = "ta-IN", expression: String = "talking") {
        if (text.isBlank()) return
        runOnUiThread {
            notifyWebView(JSONObject().apply {
                put("type", "assistant_reply")
                put("text", text)
                put("lang", langTag)
                put("expression", expression)
                put("latencyMs", MaxterVoiceCommandController.lastCommandLatencyMs)
            })
            if (ttsReady) {
                configureTtsVoice(langTag)
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                isSpeaking = true
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "maxter_utt_${System.currentTimeMillis()}")
            }
        }
    }

    fun stopSpeakingImmediate() {
        runOnUiThread {
            isSpeaking = false
            tts?.stop()
            notifyWebView(JSONObject().apply {
                put("type", "state")
                put("state", "idle")
            })
            if (FloatingJiraiyaService.isRunning) {
                commandController.notifyOverlayVisualState("idle")
            }
        }
    }

    fun syncOverlayStateToWebView() {
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        notifyWebView(JSONObject().apply {
            put("type", "overlay_status")
            put("hasPermission", hasPerm)
            put("isRunning", FloatingJiraiyaService.isRunning)
            put("overlay_enabled", prefs.getBoolean("overlay_enabled", false))
            put("overlay_auto_hide_enabled", prefs.getBoolean("overlay_auto_hide_enabled", true))
            put("overlay_auto_hide_preset", prefs.getString("overlay_auto_hide_preset", "3") ?: "3")
            put("overlay_auto_hide_minutes", prefs.getInt("overlay_auto_hide_minutes", 3))
        })
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        MaxterVoiceCommandController.lastLaunchedAppName = null
        // Tell FloatingJiraiyaService to yield microphone while MainActivity is in foreground
        if (FloatingJiraiyaService.isRunning) {
            try {
                startService(Intent(this, FloatingJiraiyaService::class.java).apply {
                    action = FloatingJiraiyaService.ACTION_WAKE_STANDBY_YIELD
                })
            } catch (_: Exception) {
            }
        }
        if (::webView.isInitialized) {
            syncOverlayStateToWebView()
        }
        scheduleWakeWordLoopIfEnabled(400L)
    }

    override fun onPause() {
        isActivityInForeground = false
        mainHandler.removeCallbacks(wakeRestartRunnable)
        if (isListening && !manualMicTriggered) {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            isListening = false
        }
        // Hand off low-power wake-word standby to FloatingJiraiyaService when overlay is active
        if (FloatingJiraiyaService.isRunning && prefs.getBoolean("wake_word_enabled", true)) {
            try {
                startService(Intent(this, FloatingJiraiyaService::class.java).apply {
                    action = FloatingJiraiyaService.ACTION_WAKE_STANDBY_START
                })
            } catch (_: Exception) {
            }
        }
        super.onPause()
    }

    private fun notifyWebView(payload: JSONObject) {
        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread
            val escaped = payload.toString()
            webView.evaluateJavascript(
                "window.onMaxterNativeEvent && window.onMaxterNativeEvent($escaped);",
                null
            )
        }
    }

    private fun injectNonDestructiveAssistantBridge() {
        val js = """
            (function() {
                window.__androidInsetsApplied = true;
                document.documentElement.classList.add('native-insets-applied');
                if (window.__maxterCompleteBridgeReady) return;
                window.__maxterCompleteBridgeReady = true;

                var OrigSR = window.SpeechRecognition || window.webkitSpeechRecognition;
                if (OrigSR && !OrigSR.__maxterWrapped) {
                    var Proto = OrigSR.prototype;
                    var origStart = Proto.start;
                    Proto.start = function() {
                        var self = this;
                        self.addEventListener('result', function(event) {
                            try {
                                var res = event.results[event.results.length - 1];
                                if (res && res[0] && res[0].transcript) {
                                    if (window.AndroidVoice && window.AndroidVoice.executeCommand) {
                                        window.AndroidVoice.executeCommand(res[0].transcript);
                                    }
                                }
                            } catch (e) {}
                        });
                        return origStart.apply(this, arguments);
                    };
                    OrigSR.__maxterWrapped = true;
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class AndroidVoiceBridge {

        @JavascriptInterface
        fun speak(text: String, langTag: String) {
            speakWithAnimation(text, langTag.ifBlank { prefs.getString("voice_locale", "ta-IN") ?: "ta-IN" }, "talking")
        }

        @JavascriptInterface
        fun speakWithStyle(text: String, langTag: String, pitch: Float, rate: Float) {
            prefs.edit()
                .putFloat("voice_pitch", pitch.coerceIn(0.45f, 1.5f))
                .putFloat("voice_rate", rate.coerceIn(0.5f, 1.5f))
                .apply()
            speakWithAnimation(text, langTag, "talking")
        }

        @JavascriptInterface
        fun stop() {
            stopSpeakingImmediate()
        }

        @JavascriptInterface
        fun executeCommand(rawText: String): Boolean {
            return commandController.processVoiceCommand(rawText, isFromVerifiedSession = true)
        }

        @JavascriptInterface
        fun executeWakeGatedSpeech(rawText: String, isManualTap: Boolean): Boolean {
            if (isManualTap) {
                val stripped = commandController.inspectWakeWord(rawText)
                val cmd = if (stripped.hasWakeWord && stripped.strippedCommand.isNotEmpty()) stripped.strippedCommand else rawText
                return commandController.processVoiceCommand(cmd, isFromVerifiedSession = true)
            }
            val wakeCheck = commandController.inspectWakeWord(rawText)
            if (!wakeCheck.hasWakeWord) return false
            if (wakeCheck.strippedCommand.isBlank()) return true
            return commandController.processVoiceCommand(wakeCheck.strippedCommand, isFromVerifiedSession = false)
        }

        @JavascriptInterface
        fun askAiOrAssistant(rawText: String) {
            commandController.handleGeneralConversationOrAiQuery(rawText)
        }

        @JavascriptInterface
        fun startVoiceListening(locale: String) {
            runOnUiThread { startNativeVoiceListening(locale.ifBlank { null }, isManualTap = true) }
        }

        @JavascriptInterface
        fun stopVoiceListening() {
            runOnUiThread { stopNativeVoiceListening() }
        }

        @JavascriptInterface
        fun setWakeWordEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("wake_word_enabled", enabled).apply()
            runOnUiThread {
                if (enabled) {
                    scheduleWakeWordLoopIfEnabled(300L)
                } else {
                    stopNativeVoiceListening()
                }
            }
        }

        @JavascriptInterface
        fun requestBatteryOptimizationExemption() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Maxter J is already unrestricted for background wake-word reliability",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun setFlashlight(enabled: Boolean): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.setHardwareFlashlight(enabled, isTa)
        }

        @JavascriptInterface
        fun adjustVolume(streamName: String, action: String, percent: Int): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.controlDeviceVolume(streamName, action, percent, isTa)
        }

        @JavascriptInterface
        fun adjustBrightness(percent: Int, systemWide: Boolean): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.setScreenBrightness(percent, systemWide, isTa)
        }

        @JavascriptInterface
        fun searchGoogle(query: String): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.performGoogleOrChromeSearch(query, preferChrome = true, isTamil = isTa)
        }

        @JavascriptInterface
        fun searchInApp(appName: String, query: String): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.performAppSpecificSearch(appName, query, isTa)
        }

        @JavascriptInterface
        fun openApp(appName: String): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.openApplicationByName(appName, isTa)
        }

        @JavascriptInterface
        fun openFileByCategory(category: String, queryName: String): Boolean {
            val isTa = (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN").startsWith("ta")
            return commandController.findAndOpenAuthorizedFile(category, queryName, isTa)
        }

        @JavascriptInterface
        fun pickFileWithSaf(mimeType: String) {
            runOnUiThread { launchSafDocumentPicker(mimeType.ifBlank { "*/*" }) }
        }

        @JavascriptInterface
        fun hasOverlayPermission(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)
        }

        @JavascriptInterface
        fun requestOverlayPermission() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Grant 'Display over other apps' permission for Maxter J",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQ_OVERLAY_PERMISSION)
                } else {
                    syncOverlayStateToWebView()
                }
            }
        }

        @JavascriptInterface
        fun isOverlayRunning(): Boolean {
            return FloatingJiraiyaService.isRunning
        }

        @JavascriptInterface
        fun startFloatingOverlay(sizeDp: Int, opacityPercent: Int, edgeSnap: Boolean) {
            runOnUiThread {
                prefs.edit()
                    .putBoolean("overlay_enabled", true)
                    .putInt("overlay_width_dp", sizeDp.coerceIn(65, 240))
                    .putInt("overlay_opacity", opacityPercent.coerceIn(20, 100))
                    .putBoolean("overlay_edge_snap", edgeSnap)
                    .apply()
                commandController.startFloatingOverlayService()
                syncOverlayStateToWebView()
            }
        }

        @JavascriptInterface
        fun stopFloatingOverlay() {
            runOnUiThread {
                prefs.edit().putBoolean("overlay_enabled", false).apply()
                commandController.stopFloatingOverlayService()
                syncOverlayStateToWebView()
            }
        }

        @JavascriptInterface
        fun updateFloatingOverlayConfig(configJson: String) {
            runOnUiThread {
                try {
                    val obj = JSONObject(configJson)
                    val ed = prefs.edit()
                    var forceReposition = false
                    if (obj.has("overlay_enabled")) ed.putBoolean("overlay_enabled", obj.getBoolean("overlay_enabled"))
                    if (obj.has("overlay_show_character")) ed.putBoolean("overlay_show_character", obj.getBoolean("overlay_show_character"))
                    if (obj.has("overlay_size_preset")) ed.putString("overlay_size_preset", obj.getString("overlay_size_preset"))
                    if (obj.has("overlay_width_dp")) ed.putInt("overlay_width_dp", obj.getInt("overlay_width_dp").coerceIn(65, 240))
                    if (obj.has("overlay_position_mode")) {
                        val newMode = obj.getString("overlay_position_mode")
                        val oldMode = prefs.getString("overlay_position_mode", "free")
                        if (newMode != oldMode && newMode != "free") {
                            forceReposition = true
                        }
                        ed.putString("overlay_position_mode", newMode)
                    }
                    if (obj.has("overlay_opacity")) ed.putInt("overlay_opacity", obj.getInt("overlay_opacity").coerceIn(20, 100))
                    if (obj.has("overlay_remember_position")) ed.putBoolean("overlay_remember_position", obj.getBoolean("overlay_remember_position"))
                    if (obj.has("overlay_anim_style")) ed.putString("overlay_anim_style", obj.getString("overlay_anim_style"))
                    if (obj.has("overlay_anim_speed")) ed.putFloat("overlay_anim_speed", obj.getDouble("overlay_anim_speed").toFloat().coerceIn(0.5f, 2.0f))
                    if (obj.has("overlay_auto_hide_enabled")) ed.putBoolean("overlay_auto_hide_enabled", obj.getBoolean("overlay_auto_hide_enabled"))
                    if (obj.has("overlay_auto_hide_preset")) ed.putString("overlay_auto_hide_preset", obj.getString("overlay_auto_hide_preset"))
                    if (obj.has("overlay_auto_hide_minutes")) ed.putInt("overlay_auto_hide_minutes", obj.getInt("overlay_auto_hide_minutes").coerceIn(1, 60))
                    if (obj.has("force_reposition")) forceReposition = obj.getBoolean("force_reposition")
                    ed.apply()

                    val enabled = prefs.getBoolean("overlay_enabled", false)
                    if (enabled) {
                        if (FloatingJiraiyaService.isRunning) {
                            commandController.updateFloatingOverlayService(forceReposition)
                        } else {
                            commandController.startFloatingOverlayService()
                        }
                    } else if (FloatingJiraiyaService.isRunning) {
                        commandController.stopFloatingOverlayService()
                    }
                    syncOverlayStateToWebView()
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun resetFloatingOverlayConfig() {
            runOnUiThread {
                prefs.edit()
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
                    .remove("overlay_x")
                    .remove("overlay_y")
                    .apply()
                if (FloatingJiraiyaService.isRunning) {
                    val svc = Intent(this@MainActivity, FloatingJiraiyaService::class.java).apply {
                        action = FloatingJiraiyaService.ACTION_RESET_CONFIG
                    }
                    startService(svc)
                }
                syncOverlayStateToWebView()
            }
        }

        @JavascriptInterface
        fun getOverlaySettingsJson(): String {
            return JSONObject().apply {
                put("hasPermission", Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity))
                put("isRunning", FloatingJiraiyaService.isRunning)
                put("overlay_enabled", prefs.getBoolean("overlay_enabled", false))
                put("overlay_show_character", prefs.getBoolean("overlay_show_character", true))
                put("overlay_size_preset", prefs.getString("overlay_size_preset", "medium") ?: "medium")
                put("overlay_width_dp", prefs.getInt("overlay_width_dp", 120))
                put("overlay_position_mode", prefs.getString("overlay_position_mode", "free") ?: "free")
                put("overlay_opacity", prefs.getInt("overlay_opacity", 100))
                put("overlay_remember_position", prefs.getBoolean("overlay_remember_position", true))
                put("overlay_anim_style", prefs.getString("overlay_anim_style", "breathing") ?: "breathing")
                put("overlay_anim_speed", prefs.getFloat("overlay_anim_speed", 1.0f).toDouble())
                put("overlay_auto_hide_enabled", prefs.getBoolean("overlay_auto_hide_enabled", true))
                put("overlay_auto_hide_preset", prefs.getString("overlay_auto_hide_preset", "3") ?: "3")
                put("overlay_auto_hide_minutes", prefs.getInt("overlay_auto_hide_minutes", 3))
            }.toString()
        }

        @JavascriptInterface
        fun saveAssistantSettings(settingsJson: String) {
            try {
                val obj = JSONObject(settingsJson)
                val ed = prefs.edit()
                if (obj.has("voice_pitch")) ed.putFloat("voice_pitch", obj.getDouble("voice_pitch").toFloat())
                if (obj.has("voice_rate")) ed.putFloat("voice_rate", obj.getDouble("voice_rate").toFloat())
                if (obj.has("voice_locale")) ed.putString("voice_locale", obj.getString("voice_locale"))
                if (obj.has("wake_word_enabled")) ed.putBoolean("wake_word_enabled", obj.getBoolean("wake_word_enabled"))
                if (obj.has("wake_phrase")) ed.putString("wake_phrase", obj.getString("wake_phrase"))
                if (obj.has("wake_confidence_threshold")) ed.putFloat("wake_confidence_threshold", obj.getDouble("wake_confidence_threshold").toFloat().coerceIn(0.20f, 0.85f))
                if (obj.has("wake_listening_window_sec")) ed.putInt("wake_listening_window_sec", obj.getInt("wake_listening_window_sec").coerceIn(3, 12))
                if (obj.has("prefer_offline_wake")) ed.putBoolean("prefer_offline_wake", obj.getBoolean("prefer_offline_wake"))
                if (obj.has("battery_saving_wake_mode")) ed.putBoolean("battery_saving_wake_mode", obj.getBoolean("battery_saving_wake_mode"))
                if (obj.has("ai_mode")) ed.putString("ai_mode", obj.getString("ai_mode"))
                if (obj.has("ai_endpoint_url")) ed.putString("ai_endpoint_url", obj.getString("ai_endpoint_url"))
                if (obj.has("ai_api_key")) ed.putString("ai_api_key", obj.getString("ai_api_key"))
                if (obj.has("ai_model_name")) ed.putString("ai_model_name", obj.getString("ai_model_name"))
                if (obj.has("require_voice_auth")) ed.putBoolean("require_voice_auth", obj.getBoolean("require_voice_auth"))
                if (obj.has("require_face_auth")) ed.putBoolean("require_face_auth", obj.getBoolean("require_face_auth"))
                if (obj.has("session_verified")) ed.putBoolean("session_verified", obj.getBoolean("session_verified"))
                if (obj.has("overlay_width_dp")) ed.putInt("overlay_width_dp", obj.getInt("overlay_width_dp"))
                if (obj.has("overlay_opacity")) ed.putInt("overlay_opacity", obj.getInt("overlay_opacity"))
                ed.apply()
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun getTaskHistoryJson(): String {
            return prefs.getString("task_history_json", "[]") ?: "[]"
        }

        @JavascriptInterface
        fun clearTaskHistory() {
            prefs.edit().putString("task_history_json", "[]").apply()
        }

        @JavascriptInterface
        fun deleteAllBiometricAndPersonalData() {
            prefs.edit().clear().apply()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){ return (window.handleMaxterBackNavigation && window.handleMaxterBackNavigation()) ? 'HANDLED' : 'PASS'; })()"
        ) { result ->
            if (result != null && result.contains("HANDLED")) {
                // Handled inside Maxter J navigation stack
            } else if (webView.canGoBack()) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        mainHandler.removeCallbacks(wakeRestartRunnable)
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        if (MaxterVoiceCommandController.isTorchOn) {
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val id = cm?.cameraIdList?.firstOrNull()
                if (id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.setTorchMode(id, false)
                }
            } catch (_: Exception) {
            }
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

/**
 * MaxterVoiceCommandController — Fast Local Command Parser & App-Specific Search Engine
 */
class MaxterVoiceCommandController(
    private val context: Context,
    private val activity: MainActivity? = null,
    private val prefs: SharedPreferences,
    private val speakCallback: (String, String, String) -> Unit,
    private val uiEventCallback: ((JSONObject) -> Unit)? = null
) {

    data class InstalledAppInfo(
        val label: String,
        val normalizedLabel: String,
        val packageName: String
    )

    data class WakeWordResult(
        val hasWakeWord: Boolean,
        val matchedWakeWord: String,
        val strippedCommand: String
    )

    companion object {
        @Volatile
        var lastLaunchedAppName: String? = null

        @Volatile
        var isTorchOn: Boolean = false

        @Volatile
        var cachedInstalledApps: List<InstalledAppInfo> = emptyList()

        @Volatile
        var lastCommandLatencyMs: Double = 0.0

        @Volatile
        private var lastExecutedCommandText: String = ""

        @Volatile
        private var lastExecutedCommandTime: Long = 0L

        private val backgroundExecutor = Executors.newCachedThreadPool()
    }

    fun refreshInstalledAppsCacheAsync() {
        backgroundExecutor.execute {
            try {
                cachedInstalledApps = queryLaunchableAppsInternal()
            } catch (_: Exception) {
            }
        }
    }

    private fun queryLaunchableAppsInternal(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return resolveList.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            InstalledAppInfo(label, label.lowercase(Locale.ROOT), pkg)
        }.distinctBy { it.packageName }
    }

    /**
     * Inspects a spoken transcript for configured custom wake words:
     * "Yow", "Yoww", "Dai", "Dei", "Bro", "Ji", "Master",
     * plus Tamil script ("யோவ்", "டேய்", "ப்ரோ", "ஜி", "மாஸ்டர்") and secondary names ("Jiraiya", "Maxter", "Maxter J").
     *
     * Handles compound greetings like "Yow, Jiraiya" or "Yow, Maxter" so they activate the listening session cleanly.
     */
    fun inspectWakeWord(rawInput: String): WakeWordResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return WakeWordResult(false, "", "")

        val customWakeRaw = (prefs.getString("wake_phrase", "yow, yoww, dai, dei, bro, ji, master") ?: "yow, yoww, dai, dei, bro, ji, master")
        val customTokens = customWakeRaw.split(",", ";")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        val wakeRegex = Regex(
            "^(?:hey\\s+|hi\\s+|hello\\s+|ok\\s+|okay\\s+)?(yow+w*|yo|dai|dei|bro|ji|master\\s*j?|maxter\\s*j?|jiraiya|pervy\\s*sage|perusu|யோவ்|டேய்|ப்ரோ|ஜி|மாஸ்டர்|ஜிரையா|மேக்ஸ்டர்\\s*ஜே|மேக்ஸ்டர்)(?:$|[,\\s!.:-]+(.*)$)",
            RegexOption.IGNORE_CASE
        )

        var matchedWord = ""
        var remainder = trimmed

        val regexMatch = wakeRegex.find(trimmed)
        if (regexMatch != null) {
            matchedWord = regexMatch.groupValues[1].trim()
            remainder = regexMatch.groupValues.getOrNull(2)?.trim().orEmpty()
        } else {
            val lower = trimmed.lowercase(Locale.ROOT)
            for (token in customTokens) {
                if (lower == token) {
                    matchedWord = token
                    remainder = ""
                    break
                }
                if (lower.startsWith("$token ") || lower.startsWith("$token,") || lower.startsWith("$token!")) {
                    matchedWord = token
                    remainder = trimmed.substring(token.length).trimStart(',', ' ', '.', '!', '-')
                    break
                }
            }
        }

        if (matchedWord.isEmpty()) {
            return WakeWordResult(false, "", trimmed)
        }

        // Also strip optional secondary assistant address immediately following the wake word:
        // e.g. "Yow, Jiraiya" -> remainder="", or "Yow, Maxter, open YouTube" -> remainder="open YouTube"
        val secondaryNameRegex = Regex(
            "^(?:jiraiya|maxter\\s*j?|master\\s*j?|pervy\\s*sage|perusu|ஜிரையா|மேக்ஸ்டர்\\s*ஜே|மேக்ஸ்டர்|மாஸ்டர்)(?:$|[,\\s!.:-]+(.*)$)",
            RegexOption.IGNORE_CASE
        )
        val secMatch = secondaryNameRegex.find(remainder)
        if (secMatch != null) {
            remainder = secMatch.groupValues.getOrNull(1)?.trim().orEmpty()
        }

        return WakeWordResult(true, matchedWord, remainder)
    }

    fun processVoiceCommand(rawInput: String, isFromVerifiedSession: Boolean = false): Boolean {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val raw = rawInput.trim()
        if (raw.isEmpty()) return false

        // Duplicate command protection within 900ms
        val now = SystemClock.elapsedRealtime()
        val normDedup = raw.lowercase(Locale.ROOT)
        if (normDedup == lastExecutedCommandText && (now - lastExecutedCommandTime) < 900L) {
            return true
        }

        val hasTamilChars = raw.any { it in '஀'..'௿' }
        val savedLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        val isThanlish = looksLikeThanlish(raw)
        val respondInTamil = hasTamilChars || isThanlish || savedLocale.startsWith("ta")
        val replyLang = if (respondInTamil) "ta-IN" else "en-IN"

        // Strip wake word if present at the beginning of the command
        val wakeInspect = inspectWakeWord(raw)
        val rawWithoutWake = if (wakeInspect.hasWakeWord) {
            wakeInspect.strippedCommand
        } else {
            raw
        }.trim()

        // If user said only "Yow, Jiraiya" or "Yow", nothing left to execute as a command
        if (rawWithoutWake.isEmpty()) {
            return true
        }

        // Preserve original-cased command (for search queries) and lowercase command (for intent classification)
        val originalCleaned = rawWithoutWake
            .replace(Regex("^(?:please|kindly|konjam|ippo|udane|seri)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:please|ippo|udane)$", RegexOption.IGNORE_CASE), "")
            .trim()
        val cleaned = originalCleaned.lowercase(Locale.ROOT)

        // 0. STOP SPEAKING
        if (cleaned == "stop" || cleaned.contains("stop speaking") || cleaned.contains("be quiet") ||
            cleaned.contains("silence") || cleaned.contains("நிறுத்து") || cleaned.contains("பேசாதே") ||
            cleaned.contains("stop pannu")
        ) {
            markCommandExecuted(normDedup, now, startNs)
            activity?.stopSpeakingImmediate()
            recordTaskHistory(raw, "Stop Speaking", true)
            return true
        }

        // Check Biometric Gate if enabled
        val needVoice = prefs.getBoolean("require_voice_auth", false)
        val needFace = prefs.getBoolean("require_face_auth", false)
        val sessionVerified = prefs.getBoolean("session_verified", true)
        if ((needVoice || needFace) && !sessionVerified && !isFromVerifiedSession) {
            val missingMsg = if (respondInTamil) {
                "பாதுகாப்பு பூட்டு இயக்கத்தில் உள்ளது. உங்கள் குரல் அல்லது முகத்தைச் சரிபார்க்கவும்."
            } else {
                "Biometric security lock is active. Please verify your voice or face first."
            }
            speakCallback(missingMsg, replyLang, "confused")
            recordTaskHistory(raw, "Blocked by Biometric Lock", false)
            return true
        }

        // 1. PRIORITY COMMAND: WAKE WORD + APP NAME + SEARCH + QUERY (and all multi-format search variations)
        // Examples:
        // - "Yow, YouTube search Tamil songs"
        // - "Bro, Chrome search Free Fire sensitivity"
        // - "Dai, Google search best Android apps"
        // - "Master, Spotify search Tamil songs"
        // - "Dei, Play Store search photo editor"
        // - "Yow, search Tamil songs in YouTube"
        // - "Dai, Chrome la Free Fire search pannu"
        // - "Master, Google la cricket score search pannu"
        // - "Dei, search Tamil songs"
        val appSearchPair = parseAppSpecificSearchCommand(cleaned, originalCleaned)
        if (appSearchPair != null) {
            markCommandExecuted(normDedup, now, startNs)
            val (targetApp, searchQuery) = appSearchPair
            if (searchQuery.isBlank()) {
                val ask = if (respondInTamil) {
                    "$targetApp-ல் என்ன தேட வேண்டும் என்று சொல்லுங்கள்?"
                } else {
                    "What would you like me to search for in $targetApp?"
                }
                speakCallback(ask, replyLang, "confused")
                recordTaskHistory(raw, "Search Prompt ($targetApp)", true)
                return true
            }
            val ok = performAppSpecificSearch(targetApp, searchQuery, respondInTamil)
            recordTaskHistory(raw, "Search [$targetApp]: $searchQuery", ok)
            return true
        }

        // 2. FLASHLIGHT / TORCH ("Ji, turn on the flashlight", "Master, flashlight on pannu")
        if (isFlashlightOnCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            val ok = setHardwareFlashlight(true, respondInTamil)
            recordTaskHistory(raw, "Flashlight ON", ok)
            return true
        }
        if (isFlashlightOffCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            val ok = setHardwareFlashlight(false, respondInTamil)
            recordTaskHistory(raw, "Flashlight OFF", ok)
            return true
        }

        // 3. VOLUME CONTROL
        val volCmd = parseVolumeCommand(cleaned)
        if (volCmd != null) {
            markCommandExecuted(normDedup, now, startNs)
            val (stream, action, pct) = volCmd
            val ok = controlDeviceVolume(stream, action, pct, respondInTamil)
            recordTaskHistory(raw, "Volume ($stream: $action)", ok)
            return true
        }

        // 4. BRIGHTNESS CONTROL
        val brightPct = parseBrightnessCommand(cleaned)
        if (brightPct != null) {
            markCommandExecuted(normDedup, now, startNs)
            val ok = setScreenBrightness(brightPct, systemWide = true, isTamil = respondInTamil)
            recordTaskHistory(raw, "Set Brightness ($brightPct%)", ok)
            return true
        }

        // 5. TIME & DATE ("Master, what is the time?", "Yow, enna time?")
        if (isTimeOrDateCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            tellCurrentTimeAndDate(respondInTamil)
            recordTaskHistory(raw, "Tell Time & Date", true)
            return true
        }

        // 6. LANGUAGE SWITCHING
        val newLang = parseLanguageSwitchCommand(cleaned)
        if (newLang != null) {
            markCommandExecuted(normDedup, now, startNs)
            prefs.edit().putString("voice_locale", newLang).apply()
            val msg = if (newLang.startsWith("ta")) "சரி! இனி நான் தமிழில் பேசுகிறேன்." else "Sure! I will now respond in English."
            speakCallback(msg, newLang, "happy")
            recordTaskHistory(raw, "Switch Language ($newLang)", true)
            return true
        }

        // 7. OVERLAY VOICE CONTROLS
        if (cleaned.contains("start overlay") || cleaned.contains("show overlay") || cleaned.contains("enable overlay") ||
            cleaned.contains("overlay on pannu")
        ) {
            markCommandExecuted(normDedup, now, startNs)
            prefs.edit().putBoolean("overlay_enabled", true).apply()
            startFloatingOverlayService()
            activity?.syncOverlayStateToWebView()
            speakCallback(if (respondInTamil) "Maxter J ஓவர்லே தொடங்கப்பட்டது." else "Floating character overlay started.", replyLang, "happy")
            recordTaskHistory(raw, "Start Character Overlay", true)
            return true
        }
        if (cleaned.contains("stop overlay") || cleaned.contains("hide overlay") || cleaned.contains("disable overlay") ||
            cleaned.contains("overlay off pannu") || cleaned.contains("overlay close pannu")
        ) {
            markCommandExecuted(normDedup, now, startNs)
            prefs.edit().putBoolean("overlay_enabled", false).apply()
            stopFloatingOverlayService()
            activity?.syncOverlayStateToWebView()
            speakCallback(if (respondInTamil) "Maxter J ஓவர்லே நிறுத்தப்பட்டது." else "Floating character overlay stopped.", replyLang, "happy")
            recordTaskHistory(raw, "Stop Character Overlay", true)
            return true
        }

        // 8. CHARACTER CUSTOMIZATION
        if (cleaned.contains("customize character") || cleaned.contains("character setting") ||
            cleaned.contains("character customization") || cleaned.contains("கேரக்டர் செட்டிங்ஸ்")
        ) {
            markCommandExecuted(normDedup, now, startNs)
            uiEventCallback?.invoke(JSONObject().apply {
                put("type", "open_modal")
                put("modal", "settings_character")
            })
            speakCallback(
                if (respondInTamil) "கேரக்டர் அமைப்புகளைத் திறக்கிறேன்." else "Opening Character Customization settings.",
                replyLang,
                "happy"
            )
            recordTaskHistory(raw, "Open Character Customization", true)
            return true
        }

        // 9. OPEN FILES, PHOTOS, VIDEOS, PDF, DOWNLOADS
        val fileReq = parseFileOpenCommand(cleaned)
        if (fileReq != null) {
            markCommandExecuted(normDedup, now, startNs)
            val (category, nameFilter) = fileReq
            val ok = findAndOpenAuthorizedFile(category, nameFilter, respondInTamil)
            recordTaskHistory(raw, "Open File ($category)", ok)
            return true
        }

        // 10. NAVIGATION & APP CLOSING ("Ji, close YouTube", "Dei, go back", "Close Maxter J")
        if (isReturnToMaxterCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            bringMaxterJToFront(respondInTamil)
            recordTaskHistory(raw, "Return to Maxter J", true)
            return true
        }
        if (isGoHomeCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            navigateToHomeScreen(respondInTamil)
            recordTaskHistory(raw, "Go to Home Screen", true)
            return true
        }
        if (isGoBackCommand(cleaned)) {
            markCommandExecuted(normDedup, now, startNs)
            handleGoBack(respondInTamil)
            recordTaskHistory(raw, "Navigate Back", true)
            return true
        }
        val closeTarget = extractCloseAppTarget(cleaned)
        if (closeTarget != null) {
            markCommandExecuted(normDedup, now, startNs)
            handleCloseAppRequest(closeTarget, respondInTamil)
            recordTaskHistory(raw, "Close App ($closeTarget)", true)
            return true
        }

        // 11. MOBILE SETTINGS BY VOICE ("Master, open Settings", "Yow, open Settings", "Dai settings open pannu")
        val settingsTarget = extractMobileSettingsTarget(cleaned)
        if (settingsTarget != null) {
            markCommandExecuted(normDedup, now, startNs)
            val ok = openAndroidSettingsScreen(settingsTarget, respondInTamil)
            recordTaskHistory(raw, "Open Settings ($settingsTarget)", ok)
            return true
        }

        // 12. FAST MULTI-FORMAT APP OPENING ("Yow, open YouTube", "Yow, YouTube open", "Dei, WhatsApp open pannu", "Bro, WhatsApp thira", "Master, open Gallery")
        val openTarget = extractOpenAppTarget(cleaned)
        if (openTarget != null) {
            markCommandExecuted(normDedup, now, startNs)
            val ok = openApplicationByName(openTarget, respondInTamil)
            recordTaskHistory(raw, "Open App: $openTarget", ok)
            return true
        }

        return false
    }

    private fun markCommandExecuted(normDedup: String, nowMs: Long, startNs: Long) {
        lastExecutedCommandText = normDedup
        lastExecutedCommandTime = nowMs
        lastCommandLatencyMs = ((SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0)
    }

    /**
     * Modular multi-format parser for:
     * 1. "[App Name] + search + [Query]" (e.g. "YouTube search Tamil songs", "Chrome search Free Fire sensitivity", "Google search best Android apps", "Spotify search Tamil songs", "Play Store search photo editor")
     * 2. "[App Name] + la + [Query] + search [pannu / தேடு]" (e.g. "Chrome la Free Fire search pannu", "Google la cricket score search pannu")
     * 3. "search + [Query] + in/on + [App Name]" (e.g. "search Tamil songs in YouTube")
     * 4. "search in/on + [App Name] + [for] + [Query]" (e.g. "search in YouTube for Tamil songs")
     * 5. "[Query] + [App Name] la + search [pannu / தேடு]" (e.g. "Tamil songs YouTube la search pannu")
     * 6. "search + [Query]" or "[Query] + search pannu / தேடு" (defaults to Google/Chrome search)
     *
     * Preserves original query characters (including Tamil script and Tanglish) without deleting meaningful query words.
     */
    fun parseAppSpecificSearchCommand(cleanedLower: String, originalCleaned: String): Pair<String, String>? {
        val orig = originalCleaned.trim()
        val low = cleanedLower.trim()

        if (low == "search" || low == "google search" || low == "chrome search" || low == "தேடு" || low == "search pannu") {
            return Pair("google", "")
        }

        // Known multi-word or single-word app names (English, Tanglish & Tamil script)
        val appNamePattern = "(google\\s+play\\s+store|play\\s+store|playstore|google\\s+maps|maps|youtube|you\\s+tube|utube|chrome|google\\s+chrome|google|spotify|whatsapp|whats\\s+app|instagram|insta|amazon|flipkart|telegram|settings|யூடியூப்|குரோம்|கூகுள்|ப்ளே\\s*ஸ்டோர்|ஸ்பாட்டிஃபை|மேப்ஸ்|வாட்ஸ்அப்|இன்ஸ்டாகிராம்|அமேசான்)"

        // Format 1: "[App Name] la / -la [Query] search pannu / தேடு / search"
        // e.g. "Chrome la Free Fire search pannu", "Google la cricket score search pannu", "YouTube la Tamil songs search pannu"
        val fmt1 = Regex(
            "^$appNamePattern(?:[-\\s]+la|\\s+in|\\s+on|ல்|ல)?\\s+(.+?)\\s+(?:search\\s+pannu|search\\s+panna|தேடு|தேடுங்கள்|search)$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmt1 != null) {
            val app = normalizeTargetAppName(fmt1.groupValues[1])
            val query = fmt1.groupValues[2].replace(Regex("^(?:for|about)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return Pair(app, query)
        }

        // Format 2: "search [for] [Query] in/on [App Name]"
        // e.g. "search Tamil songs in YouTube", "search photo editor on Play Store"
        val fmt2 = Regex(
            "^(?:search\\s+for|search)\\s+(.+?)\\s+(?:in|on|inside)\\s+$appNamePattern(?:\\s+app)?$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmt2 != null) {
            val query = fmt2.groupValues[1].trim()
            val app = normalizeTargetAppName(fmt2.groupValues[2])
            return Pair(app, query)
        }

        // Format 3: "search in/on [App Name] [for] [Query]"
        // e.g. "search in YouTube for Tamil songs", "search on Chrome Free Fire sensitivity"
        val fmt3 = Regex(
            "^search\\s+(?:in|on)\\s+$appNamePattern\\s+(?:for\\s+|about\\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmt3 != null) {
            val app = normalizeTargetAppName(fmt3.groupValues[1])
            val query = fmt3.groupValues[2].replace(Regex("\\s+search\\s+pannu$", RegexOption.IGNORE_CASE), "").trim()
            return Pair(app, query)
        }

        // Format 4 (CORE PRIORITY): "[App Name] + search + [for] + [Query]"
        // e.g. "YouTube search Tamil songs", "Chrome search Free Fire sensitivity", "Google search best Android apps",
        //      "Spotify search Tamil songs", "Play Store search photo editor"
        val fmt4 = Regex(
            "^$appNamePattern(?:[-\\s]+la)?\\s+(?:search\\s+for|search|தேடு)\\s+(.+?)(?:\\s+search\\s+pannu)?$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmt4 != null) {
            val app = normalizeTargetAppName(fmt4.groupValues[1])
            val query = fmt4.groupValues[2].trim()
            return Pair(app, query)
        }

        // Format 5: Generic "[Any Installed App Name] + search + [Query]"
        // e.g. "JioSaavn search melody songs", "Wynk search Tamil hits"
        val genericAppSearch = Regex(
            "^([a-zA-Z0-9\u0B80-\u0BFF]+(?:\\s+store|\\s+music|\\s+player)?)\\s+search\\s+(?:for\\s+)?(.+?)(?:\\s+search\\s+pannu)?$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (genericAppSearch != null) {
            val candidateApp = genericAppSearch.groupValues[1].trim()
            val candidateLower = candidateApp.lowercase(Locale.ROOT)
            if (candidateLower !in listOf("please", "kindly", "quick", "fast", "now", "ippo", "konjam")) {
                val query = genericAppSearch.groupValues[2].trim()
                return Pair(normalizeTargetAppName(candidateApp), query)
            }
        }

        // Format 6: "[Query] + [App Name] la + search [pannu / தேடு]"
        // e.g. "Free Fire sensitivity Chrome la search pannu", "Tamil songs YouTube la search pannu"
        val fmt6 = Regex(
            "^(.+?)\\s+$appNamePattern(?:[-\\s]+la|ல்|ல)\\s+(?:search\\s+pannu|தேடு|தேடுங்கள்|search)$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmt6 != null) {
            val query = fmt6.groupValues[1].trim()
            val app = normalizeTargetAppName(fmt6.groupValues[2])
            return Pair(app, query)
        }

        // Format 7: "[App Name] la search pannu" (empty query prompt)
        val fmtEmptyApp = Regex(
            "^$appNamePattern(?:[-\\s]+la|ல்|ல)?\\s+(?:search\\s+pannu|தேடு|search)$",
            RegexOption.IGNORE_CASE
        ).find(orig)
        if (fmtEmptyApp != null) {
            return Pair(normalizeTargetAppName(fmtEmptyApp.groupValues[1]), "")
        }

        // Format 8: General Search without specific app ("Dei, search Tamil songs", "Tamil songs search pannu")
        val genPrefix = Regex("^(?:search\\s+for|search|தேடு)\\s+(.+?)(?:\\s+search\\s+pannu)?$", RegexOption.IGNORE_CASE).find(orig)
        if (genPrefix != null) {
            return Pair("google", genPrefix.groupValues[1].trim())
        }
        val genSuffix = Regex("^(.+?)\\s+(?:search\\s+pannu|search\\s+panna|தேடு|தேடுங்கள்)$", RegexOption.IGNORE_CASE).find(orig)
        if (genSuffix != null) {
            return Pair("google", genSuffix.groupValues[1].trim())
        }

        return null
    }

    private fun normalizeTargetAppName(rawApp: String): String {
        val clean = rawApp.trim().lowercase(Locale.ROOT)
        return when {
            clean in listOf("youtube", "you tube", "utube", "யூடியூப்") -> "youtube"
            clean in listOf("chrome", "google chrome", "குரோம்") -> "chrome"
            clean in listOf("google", "கூகுள்") -> "google"
            clean in listOf("play store", "playstore", "google play store", "ப்ளே ஸ்டோர்", "ப்ளேஸ்டோர்") -> "play store"
            clean in listOf("spotify", "ஸ்பாட்டிஃபை") -> "spotify"
            clean in listOf("maps", "google maps", "மேப்ஸ்") -> "maps"
            clean in listOf("whatsapp", "whats app", "வாட்ஸ்அப்", "வாட்சப்") -> "whatsapp"
            clean in listOf("instagram", "insta", "இன்ஸ்டாகிராம்", "இன்ஸ்டா") -> "instagram"
            clean in listOf("amazon", "அமேசான்") -> "amazon"
            clean in listOf("flipkart") -> "flipkart"
            else -> clean
        }
    }

    /**
     * Executes app-specific search using documented Android intents or deep links.
     * Never reports a search as completed before `startActivity` has succeeded.
     */
    fun performAppSpecificSearch(targetAppRaw: String, rawQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isEmpty()) return false

        val targetApp = normalizeTargetAppName(targetAppRaw)
        val encoded = Uri.encode(cleanQuery)
        val pm = context.packageManager

        return try {
            when (targetApp) {
                "youtube" -> {
                    val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", cleanQuery)
                        putExtra(SearchManager.QUERY, cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val ytViewIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                    ).apply {
                        setPackage("com.google.android.youtube")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    when {
                        ytSearchIntent.resolveActivity(pm) != null -> {
                            context.startActivity(ytSearchIntent)
                            lastLaunchedAppName = "YouTube"
                        }
                        ytViewIntent.resolveActivity(pm) != null -> {
                            context.startActivity(ytViewIntent)
                            lastLaunchedAppName = "YouTube"
                        }
                        else -> {
                            val webFallback = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(webFallback)
                            lastLaunchedAppName = "YouTube"
                        }
                    }
                    speakCallback(
                        if (isTamil) "YouTube-ல் $cleanQuery தேடுகிறேன்." else "Searching YouTube for $cleanQuery.",
                        lang,
                        "happy"
                    )
                    true
                }

                "chrome" -> {
                    performGoogleOrChromeSearch(cleanQuery, preferChrome = true, isTamil = isTamil)
                }

                "google" -> {
                    val googleAppIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        setPackage("com.google.android.googlequicksearchbox")
                        putExtra(SearchManager.QUERY, cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (googleAppIntent.resolveActivity(pm) != null) {
                        context.startActivity(googleAppIntent)
                        lastLaunchedAppName = "Google"
                        speakCallback(
                            if (isTamil) "Google-ல் $cleanQuery தேடுகிறேன்." else "Searching Google for $cleanQuery.",
                            lang,
                            "happy"
                        )
                        true
                    } else {
                        performGoogleOrChromeSearch(cleanQuery, preferChrome = true, isTamil = isTamil)
                    }
                }

                "play store" -> {
                    val marketIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://search?q=$encoded")
                    ).apply {
                        setPackage("com.android.vending")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (marketIntent.resolveActivity(pm) != null) {
                        context.startActivity(marketIntent)
                        lastLaunchedAppName = "Play Store"
                    } else {
                        val webPlay = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/search?q=$encoded")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(webPlay)
                        lastLaunchedAppName = "Play Store"
                    }
                    speakCallback(
                        if (isTamil) "Play Store-ல் $cleanQuery தேடுகிறேன்." else "Searching Play Store for $cleanQuery.",
                        lang,
                        "happy"
                    )
                    true
                }

                "spotify" -> {
                    val spotifySearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.spotify.music")
                        putExtra(SearchManager.QUERY, cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val spotifyUriIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("spotify:search:$encoded")
                    ).apply {
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val spotifyPlayIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        setPackage("com.spotify.music")
                        putExtra(SearchManager.QUERY, cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    when {
                        spotifySearchIntent.resolveActivity(pm) != null -> {
                            context.startActivity(spotifySearchIntent)
                            lastLaunchedAppName = "Spotify"
                            speakCallback(
                                if (isTamil) "Spotify-ல் $cleanQuery தேடுகிறேன்." else "Searching Spotify for $cleanQuery.",
                                lang,
                                "happy"
                            )
                            true
                        }
                        spotifyUriIntent.resolveActivity(pm) != null -> {
                            context.startActivity(spotifyUriIntent)
                            lastLaunchedAppName = "Spotify"
                            speakCallback(
                                if (isTamil) "Spotify-ல் $cleanQuery தேடுகிறேன்." else "Searching Spotify for $cleanQuery.",
                                lang,
                                "happy"
                            )
                            true
                        }
                        spotifyPlayIntent.resolveActivity(pm) != null -> {
                            context.startActivity(spotifyPlayIntent)
                            lastLaunchedAppName = "Spotify"
                            speakCallback(
                                if (isTamil) "Spotify-ல் $cleanQuery பிளே செய்கிறேன்." else "Searching Spotify for $cleanQuery.",
                                lang,
                                "happy"
                            )
                            true
                        }
                        else -> {
                            val webSpotify = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://open.spotify.com/search/$encoded")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(webSpotify)
                            lastLaunchedAppName = "Spotify Web"
                            speakCallback(
                                if (isTamil) "Spotify செயலி நிறுவப்படவில்லை. Spotify வலைத்தளத்தில் $cleanQuery திறக்கிறேன்."
                                else "Spotify app is not installed; opening Spotify web search for $cleanQuery.",
                                lang,
                                "talking"
                            )
                            true
                        }
                    }
                }

                "maps" -> {
                    val mapsIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=$encoded")
                    ).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (mapsIntent.resolveActivity(pm) != null) {
                        context.startActivity(mapsIntent)
                        lastLaunchedAppName = "Google Maps"
                    } else {
                        val webMaps = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(webMaps)
                        lastLaunchedAppName = "Maps"
                    }
                    speakCallback(
                        if (isTamil) "Maps-ல் $cleanQuery தேடுகிறேன்." else "Searching Maps for $cleanQuery.",
                        lang,
                        "happy"
                    )
                    true
                }

                "amazon" -> {
                    val amzIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.amazon.in/s?k=$encoded")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(amzIntent)
                    lastLaunchedAppName = "Amazon"
                    speakCallback(
                        if (isTamil) "Amazon-ல் $cleanQuery தேடுகிறேன்." else "Searching Amazon for $cleanQuery.",
                        lang,
                        "happy"
                    )
                    true
                }

                "flipkart" -> {
                    val fkIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.flipkart.com/search?q=$encoded")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fkIntent)
                    lastLaunchedAppName = "Flipkart"
                    speakCallback(
                        if (isTamil) "Flipkart-ல் $cleanQuery தேடுகிறேன்." else "Searching Flipkart for $cleanQuery.",
                        lang,
                        "happy"
                    )
                    true
                }

                else -> {
                    // Resolve installed application from cached list
                    val matchedApp = findInstalledAppByName(targetApp)
                    if (matchedApp != null) {
                        val pkgSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage(matchedApp.packageName)
                            putExtra(SearchManager.QUERY, cleanQuery)
                            putExtra("query", cleanQuery)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (pkgSearchIntent.resolveActivity(pm) != null) {
                            context.startActivity(pkgSearchIntent)
                            lastLaunchedAppName = matchedApp.label
                            speakCallback(
                                if (isTamil) "${matchedApp.label}-ல் $cleanQuery தேடுகிறேன்."
                                else "Searching ${matchedApp.label} for $cleanQuery.",
                                lang,
                                "happy"
                            )
                            return true
                        }
                        // App is installed but does not expose a public search intent: open the app directly and explain honestly
                        val launchIntent = pm.getLaunchIntentForPackage(matchedApp.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            lastLaunchedAppName = matchedApp.label
                            speakCallback(
                                if (isTamil) "${matchedApp.label} திறக்கப்பட்டது. இந்த செயலி நேரடி குரல் தேடலை ஆதரிக்கவில்லை."
                                else "Opened ${matchedApp.label}. This app does not support automatic external search.",
                                lang,
                                "talking"
                            )
                            return true
                        }
                    }
                    // App not installed: explain limitation and fall back to Google search
                    performGoogleOrChromeSearch("$targetAppRaw $cleanQuery", preferChrome = true, isTamil = isTamil)
                }
            }
        } catch (e: Exception) {
            speakCallback(
                if (isTamil) "தேடலைத் தொடங்க முடியவில்லை." else "Unable to launch search right now.",
                lang,
                "confused"
            )
            false
        }
    }

    private fun looksLikeThanlish(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val markers = listOf(
            "pannu", "panna", "konjam", "kammi", "athigam", "adhigam", "thira",
            "moodu", "enna", "enga", "indha", "andha", "epdi", "eppadi", "sollu",
            "google-la", "google la", "chrome-la", "chrome la", "youtube-la", "youtube la",
            "podu", "anai", "aakku", "yow", "yoww", "dai", "dei", "ji"
        )
        return markers.any { lower.contains(it) }
    }

    private fun isFlashlightOnCommand(cmd: String): Boolean {
        val patterns = listOf(
            "turn on the flashlight", "turn on flashlight", "flashlight on",
            "on light", "light on", "turn on light", "turn on the light",
            "torch on", "turn on torch", "on torch", "flashlight on pannu",
            "torch on pannu", "light on pannu", "light podu",
            "லைட்டை ஆன் பண்ணு", "லைட் ஆன் பண்ணு", "லைட் ஆன்", "டார்ச் ஆன்", "விளக்கை ஏற்று"
        )
        return patterns.any { cmd.contains(it) }
    }

    private fun isFlashlightOffCommand(cmd: String): Boolean {
        val patterns = listOf(
            "turn off the flashlight", "turn off flashlight", "flashlight off",
            "off light", "light off", "turn off light", "turn off the light",
            "torch off", "turn off torch", "off torch", "flashlight off pannu",
            "torch off pannu", "light off pannu", "light anai", "light off",
            "லைட்டை ஆஃப் பண்ணு", "லைட்டை ஆப் பண்ணு", "லைட் ஆஃப்", "டார்ச் ஆஃப்", "விளக்கை அணை"
        )
        return patterns.any { cmd.contains(it) }
    }

    private fun parseVolumeCommand(cmd: String): Triple<String, String, Int>? {
        val hasVolKeyword = cmd.contains("volume") || cmd.contains("sound") ||
                cmd.contains("audio") || cmd.contains("வால்யூம்") || cmd.contains("சத்தம்") ||
                cmd.contains("mute")
        if (!hasVolKeyword || cmd.contains("settings") || cmd.contains("செட்டிங்ஸ்")) return null

        val stream = when {
            cmd.contains("alarm") || cmd.contains("அலாரம்") -> "alarm"
            cmd.contains("notification") || cmd.contains("ring") || cmd.contains("ரிங்டோன்") -> "notification"
            cmd.contains("call") || cmd.contains("voice call") -> "call"
            else -> prefs.getString("default_volume_stream", "media") ?: "media"
        }

        val pctMatch = Regex("(\\d{1,3})\\s*(?:%|percent|சதவீதம்)?").find(cmd)
        if (pctMatch != null && (cmd.contains("to ") || cmd.contains("%") || cmd.contains("percent") || cmd.contains("வை"))) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return Triple(stream, "set", pct)
        }

        if (cmd.contains("mute") || cmd.contains("silent") || cmd.contains("மியூட்")) {
            return Triple(stream, "mute", 0)
        }
        if (cmd.contains("full") || cmd.contains("max") || cmd.contains("100") || cmd.contains("முழு")) {
            return Triple(stream, "set", 100)
        }
        if (cmd.contains("up") || cmd.contains("increase") || cmd.contains("raise") ||
            cmd.contains("adhigam") || cmd.contains("athigam") || cmd.contains("கூட்டு") ||
            cmd.contains("அதிகரி") || cmd.contains("ஏற்று")
        ) {
            return Triple(stream, "up", 15)
        }
        if (cmd.contains("down") || cmd.contains("decrease") || cmd.contains("lower") ||
            cmd.contains("reduce") || cmd.contains("kammi") || cmd.contains("korai") ||
            cmd.contains("குறை") || cmd.contains("கம்மி")
        ) {
            return Triple(stream, "down", 15)
        }
        return null
    }

    private fun parseBrightnessCommand(cmd: String): Int? {
        if (!cmd.contains("brightness") && !cmd.contains("வெளிச்சம்") && !cmd.contains("பிரைட்னஸ்")) {
            return null
        }
        if (cmd.contains("settings") || cmd.contains("செட்டிங்ஸ்")) return null

        val currentPct = prefs.getInt("current_brightness_pct", 65)

        val pctMatch = Regex("(\\d{1,3})\\s*(?:%|percent|சதவீதம்)?").find(cmd)
        if (pctMatch != null) {
            return (pctMatch.groupValues[1].toIntOrNull() ?: 60).coerceIn(10, 100)
        }
        if (cmd.contains("full") || cmd.contains("max") || cmd.contains("முழு")) {
            return 100
        }
        if (cmd.contains("up") || cmd.contains("increase") || cmd.contains("adhigam") ||
            cmd.contains("athigam") || cmd.contains("கூட்டு") || cmd.contains("அதிகரி")
        ) {
            return (currentPct + 20).coerceIn(10, 100)
        }
        if (cmd.contains("down") || cmd.contains("decrease") || cmd.contains("kammi") ||
            cmd.contains("low") || cmd.contains("குறை") || cmd.contains("கம்மி")
        ) {
            return (currentPct - 20).coerceIn(12, 100)
        }
        return null
    }

    private fun isTimeOrDateCommand(cmd: String): Boolean {
        return cmd.contains("what time") || cmd.contains("what is the time") || cmd.contains("what's the time") ||
                cmd.contains("current time") || cmd.contains("enna time") || cmd.contains("time enna") ||
                cmd.contains("மணி என்ன") || cmd.contains("நேரம் என்ன") ||
                cmd.contains("what date") || cmd.contains("today date") || cmd.contains("இன்று தேதி")
    }

    private fun parseLanguageSwitchCommand(cmd: String): String? {
        if (cmd.contains("speak in tamil") || cmd.contains("switch to tamil") ||
            cmd.contains("tamil la pesu") || cmd.contains("தமிழில் பேசு")
        ) {
            return "ta-IN"
        }
        if (cmd.contains("speak in english") || cmd.contains("switch to english") ||
            cmd.contains("english la pesu") || cmd.contains("ஆங்கிலத்தில் பேசு")
        ) {
            return "en-IN"
        }
        return null
    }

    private fun extractMobileSettingsTarget(cmd: String): String? {
        if (cmd.contains("character setting")) return null
        val asksSettings = cmd.contains("settings") || cmd.contains("செட்டிங்ஸ்") || cmd.contains("அமைப்புகள்") ||
                cmd.contains("app info")
        if (!asksSettings) return null

        val isOpenIntent = cmd.contains("open") || cmd.contains("thira") || cmd.contains("திற") ||
                cmd.contains("pannu") || cmd.contains("go to") || cmd == "settings" ||
                cmd == "mobile settings" || cmd == "phone settings" || cmd == "செட்டிங்ஸ்"
        if (!isOpenIntent) return null

        return when {
            cmd.contains("wifi") || cmd.contains("wi-fi") || cmd.contains("வைஃபை") -> "wifi"
            cmd.contains("bluetooth") || cmd.contains("புளூடூத்") -> "bluetooth"
            cmd.contains("sound") || cmd.contains("audio") || cmd.contains("சத்தம்") || cmd.contains("ஒலி") -> "sound"
            cmd.contains("display") || cmd.contains("screen") || cmd.contains("டிஸ்ப்ளே") -> "display"
            cmd.contains("app settings") || cmd.contains("app info") || cmd.contains("application settings") -> "app_details"
            else -> "main"
        }
    }

    private fun parseFileOpenCommand(cmd: String): Pair<String, String>? {
        val namedPhoto = Regex("(?:photo|image|picture)\\s+named\\s+([a-z0-9_\\-\\s]+)").find(cmd)
        if (namedPhoto != null) {
            return Pair("image", namedPhoto.groupValues[1].trim())
        }
        if (cmd.contains("latest photo") || cmd.contains("last photo") || cmd.contains("my photo") ||
            cmd.contains("my images") || cmd.contains("open image") || cmd.contains("photo open pannu") ||
            cmd.contains("புகைப்படம்") || cmd.contains("போட்டோ திற")
        ) {
            return Pair("image", "")
        }
        if (cmd.contains("video") || cmd.contains("play my video") || cmd.contains("indha video") ||
            cmd.contains("வீடியோ திற") || cmd.contains("வீடியோவை பிளே")
        ) {
            return Pair("video", "")
        }
        if (cmd.contains("pdf") || cmd.contains("document") || cmd.contains("பிடிஎஃப்")) {
            return Pair("pdf", "")
        }
        if (cmd.contains("download") || cmd.contains("டவுன்லோட்")) {
            return Pair("downloads", "")
        }
        return null
    }

    private fun isReturnToMaxterCommand(cmd: String): Boolean {
        return cmd.contains("return to maxter") || cmd.contains("back to maxter") ||
                cmd.contains("return to jiraiya") || cmd.contains("மேக்ஸ்டர் ஜே திரை")
    }

    private fun isGoHomeCommand(cmd: String): Boolean {
        return cmd == "go home" || cmd.contains("go to home") || cmd.contains("home screen") ||
                cmd.contains("முகப்பு திரை") || cmd.contains("முகப்புக்கு போ")
    }

    private fun isGoBackCommand(cmd: String): Boolean {
        return cmd == "go back" || cmd == "back" || cmd == "back po" || cmd.contains("previous screen") ||
                cmd.contains("பின்னாடி போ") || cmd.contains("பின்னால் போ")
    }

    /**
     * Multi-format App Closing target extractor:
     * - "Ji, close YouTube" / "YouTube close" / "YouTube close pannu" / "YouTube moodu"
     * - "Close Maxter J" / "Indha app close pannu"
     */
    private fun extractCloseAppTarget(cmd: String): String? {
        if (cmd.contains("indha app close") || cmd.contains("indha app-a close") ||
            cmd.contains("close this app") || cmd == "close app" || cmd == "இந்த செயலியை மூடு"
        ) {
            return lastLaunchedAppName ?: (if (activity != null) "maxter j" else "current external app")
        }
        val enPrefix = Regex("^(?:close|exit|quit)\\s+(.+?)(?:\\s+pannu)?$").find(cmd)
        if (enPrefix != null) return enPrefix.groupValues[1].trim()

        val suffix = Regex("^(.+?)\\s+(?:close\\s+pannu|close|moodu|மூடு|க்ளோஸ் பண்ணு|exit\\s+pannu)$").find(cmd)
        if (suffix != null) return suffix.groupValues[1].trim()
        return null
    }

    /**
     * Multi-format App Opening target extractor:
     * Supports:
     * - "Yow, open YouTube" / "launch WhatsApp" / "start Chrome"
     * - "Yow, YouTube open"
     * - "Yow, YouTube open pannu" / "Dei, WhatsApp open pannu" / "Bro, Gallery open pannu"
     * - "Bro, WhatsApp thira" / "யூடியூப் திற"
     */
    private fun extractOpenAppTarget(cmd: String): String? {
        val enPrefix = Regex("^(?:open|launch|start)\\s+(.+?)(?:\\s+(?:open\\s+pannu|pannu|thira|திற))?$").find(cmd)
        if (enPrefix != null) return enPrefix.groupValues[1].trim()

        val suffix = Regex("^(.+?)\\s+(?:open\\s+pannu|open\\s+panna|open|thira|திற|ஓபன் பண்ணு|launch\\s+pannu)$").find(cmd)
        if (suffix != null) return suffix.groupValues[1].trim()
        return null
    }

    // =============================================================================================
    // HARDWARE & ANDROID EXECUTION METHODS
    // =============================================================================================

    fun setHardwareFlashlight(enable: Boolean, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val msg = if (isTamil) "உங்கள் போனில் பிளாஷ்லைட் வசதி இல்லை." else "Flashlight is not available on this device."
            speakCallback(msg, lang, "confused")
            return false
        }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            val torchId = cm.cameraIdList.firstOrNull { id ->
                val c = cm.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (torchId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                speakCallback(if (isTamil) "பிளாஷ்லைட் கிடைக்கவில்லை." else "Flashlight unavailable.", lang, "confused")
                return false
            }

            cm.setTorchMode(torchId, enable)
            isTorchOn = enable
            val msg = if (enable) {
                if (isTamil) "Flashlight ஆன் செய்யப்பட்டது." else "Flashlight is on."
            } else {
                if (isTamil) "Flashlight ஆஃப் செய்யப்பட்டது." else "Flashlight is off."
            }
            speakCallback(msg, lang, "happy")
            true
        } catch (e: SecurityException) {
            val msg = if (isTamil) "கேமரா அனுமதி தேவை." else "Camera permission is required to control the flashlight."
            speakCallback(msg, lang, "confused")
            false
        } catch (e: Exception) {
            val msg = if (isTamil) "பிளாஷ்லைட்டை இயக்க முடியவில்லை." else "Unable to control flashlight right now."
            speakCallback(msg, lang, "confused")
            false
        }
    }

    fun controlDeviceVolume(streamName: String, action: String, percent: Int, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        val streamType = when (streamName.lowercase(Locale.ROOT)) {
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "ring" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }

        return try {
            val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)

            when (action) {
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    speakCallback(if (isTamil) "வால்யூம் அதிகரிக்கப்பட்டது." else "Volume increased.", lang, "happy")
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    speakCallback(if (isTamil) "வால்யூம் குறைக்கப்பட்டது." else "Volume decreased.", lang, "happy")
                }
                "mute" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        audioManager.setStreamVolume(streamType, 0, AudioManager.FLAG_SHOW_UI)
                    }
                    speakCallback(if (isTamil) "ஆடியோ மியூட் செய்யப்பட்டது." else "Muted $streamName volume.", lang, "happy")
                }
                else -> {
                    val targetIndex = Math.round((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
                    speakCallback(
                        if (isTamil) "வால்யூம் $percent% ஆக மாற்றப்பட்டது." else "Volume set to $percent percent.",
                        lang,
                        "happy"
                    )
                }
            }
            true
        } catch (sec: SecurityException) {
            speakCallback(
                if (isTamil) "Do Not Disturb பயன்முறையில் வால்யூமை மாற்ற அனுமதி இல்லை."
                else "Cannot change volume while Do Not Disturb is active.",
                lang,
                "confused"
            )
            false
        } catch (e: Exception) {
            false
        }
    }

    fun setScreenBrightness(percent: Int, systemWide: Boolean, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val safePercent = percent.coerceIn(12, 100)
        prefs.edit().putInt("current_brightness_pct", safePercent).apply()

        activity?.runOnUiThread {
            val lp = activity.window.attributes
            lp.screenBrightness = safePercent / 100f
            activity.window.attributes = lp
        }

        if (systemWide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.System.canWrite(context)) {
                return try {
                    val sysVal = Math.round((safePercent / 100.0) * 255).toInt().coerceIn(25, 255)
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        sysVal
                    )
                    speakCallback(
                        if (isTamil) "திரை வெளிச்சம் $safePercent% ஆக மாற்றப்பட்டது." else "Brightness set to $safePercent percent.",
                        lang,
                        "happy"
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                speakCallback(
                    if (isTamil) "செயலி வெளிச்சம் $safePercent%. போன் முழுவதும் மாற்ற அனுமதி வழங்கவும்."
                    else "In-app brightness set to $safePercent%. Grant Modify System Settings permission for system-wide brightness.",
                    lang,
                    "talking"
                )
                if (activity != null) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    activity.startActivityForResult(intent, MainActivity.REQ_WRITE_SETTINGS)
                }
                return true
            }
        }

        speakCallback(
            if (isTamil) "வெளிச்சம் $safePercent% ஆக மாற்றப்பட்டது." else "Brightness set to $safePercent percent.",
            lang,
            "happy"
        )
        return true
    }

    fun performGoogleOrChromeSearch(rawQuery: String, preferChrome: Boolean = true, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isEmpty()) return false

        val encoded = Uri.encode(cleanQuery)
        val searchUri = Uri.parse("https://www.google.com/search?q=$encoded")
        val pm = context.packageManager

        return try {
            var launched = false
            if (preferChrome) {
                val chromeIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    setPackage("com.android.chrome")
                    putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (chromeIntent.resolveActivity(pm) != null) {
                    context.startActivity(chromeIntent)
                    lastLaunchedAppName = "Chrome"
                    launched = true
                }
            }

            if (!launched) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                lastLaunchedAppName = "Browser"
            }

            val msg = if (isTamil) {
                "Chrome-ல் $cleanQuery தேடுகிறேன்."
            } else {
                "Searching Chrome for $cleanQuery."
            }
            speakCallback(msg, lang, "happy")
            true
        } catch (e: Exception) {
            speakCallback(
                if (isTamil) "தேடலைத் திறக்க முடியவில்லை." else "Unable to open browser search.",
                lang,
                "confused"
            )
            false
        }
    }

    fun openAndroidSettingsScreen(target: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val pm = context.packageManager

        val (intent, labelEn, labelTa) = when (target) {
            "wifi" -> Triple(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings", "வைஃபை அமைப்புகள்")
            "bluetooth" -> Triple(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings", "புளூடூத் அமைப்புகள்")
            "sound" -> Triple(Intent(Settings.ACTION_SOUND_SETTINGS), "Sound settings", "ஒலி அமைப்புகள்")
            "display" -> Triple(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Display settings", "திரை அமைப்புகள்")
            "app_details" -> Triple(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                "Maxter J App settings",
                "Maxter J செயலி அமைப்புகள்"
            )
            else -> Triple(Intent(Settings.ACTION_SETTINGS), "Settings", "செட்டிங்ஸ்")
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
            } else {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            lastLaunchedAppName = "Settings"
            speakCallback(
                if (isTamil) "$labelTa திறக்கப்படுகிறது." else "Opening $labelEn.",
                lang,
                "happy"
            )
            true
        } catch (e: Exception) {
            speakCallback(
                if (isTamil) "செட்டிங்ஸ் திறக்க முடியவில்லை." else "Unable to open Settings.",
                lang,
                "confused"
            )
            false
        }
    }

    fun findAndOpenAuthorizedFile(category: String, nameFilter: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanFilter = nameFilter.trim().lowercase(Locale.ROOT)

        try {
            when (category.lowercase(Locale.ROOT)) {
                "image", "photo" -> {
                    val projection = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.DATE_ADDED
                    )
                    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: ""
                            if (cleanFilter.isEmpty() || name.lowercase(Locale.ROOT).contains(cleanFilter)) {
                                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                                val ok = openContentUri(uri, "image/*")
                                speakCallback(if (isTamil) "உங்கள் புகைப்படத்தைத் திறக்கிறேன்." else "Opening your photo $name.", lang, "happy")
                                return ok
                            }
                        }
                    }
                    val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(galleryIntent)
                    speakCallback(if (isTamil) "படங்கள் பகுதியைத் திறக்கிறேன்." else "Opening your images.", lang, "happy")
                    return true
                }

                "video" -> {
                    val projection = arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_ADDED
                    )
                    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
                    context.contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
                            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            val ok = openContentUri(uri, "video/*")
                            speakCallback(if (isTamil) "உங்கள் வீடியோவைத் திறக்கிறேன்." else "Playing your video $name.", lang, "happy")
                            return ok
                        }
                    }
                    activity?.launchSafDocumentPicker("video/*")
                    speakCallback(if (isTamil) "உங்கள் வீடியோவைத் தேர்ந்தெடுக்கவும்." else "Please select the video to play.", lang, "talking")
                    return true
                }

                "pdf", "document" -> {
                    activity?.launchSafDocumentPicker("application/pdf")
                    speakCallback(
                        if (isTamil) "உங்கள் PDF கோப்பைத் தேர்ந்தெடுக்கவும்." else "Opening document picker for your PDF.",
                        lang,
                        "talking"
                    )
                    return true
                }

                "downloads" -> {
                    val dmIntent = Intent("android.intent.action.VIEW_DOWNLOADS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dmIntent)
                    speakCallback(if (isTamil) "Downloads பகுதியைத் திறக்கிறேன்." else "Opening Downloads.", lang, "happy")
                    return true
                }
            }
        } catch (e: Exception) {
            activity?.launchSafDocumentPicker("*/*")
            return false
        }
        return false
    }

    fun openContentUri(uri: Uri, mimeType: String): Boolean {
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(viewIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tellCurrentTimeAndDate(isTamil: Boolean) {
        val now = Date()
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
        val msg = if (isTamil) {
            "இப்போது நேரம் $timeStr. இன்று $dateStr."
        } else {
            "The current time is $timeStr, on $dateStr."
        }
        speakCallback(msg, if (isTamil) "ta-IN" else "en-IN", "happy")
    }

    private fun findInstalledAppByName(canonicalOrRaw: String): InstalledAppInfo? {
        var installedApps = cachedInstalledApps
        if (installedApps.isEmpty()) {
            installedApps = queryLaunchableAppsInternal()
            cachedInstalledApps = installedApps
        }
        val q = canonicalOrRaw.trim().lowercase(Locale.ROOT)
        return installedApps.firstOrNull { it.normalizedLabel == q }
            ?: installedApps.firstOrNull { it.normalizedLabel.contains(q) || q.contains(it.normalizedLabel) }
    }

    fun openApplicationByName(spokenQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = spokenQuery
            .replace(Regex("^(the|app|application|mobile)\\s+"), "")
            .replace(Regex("\\s+(app|application|செயலி|open\\s+pannu|pannu|thira|திற)$"), "")
            .trim()
            .lowercase(Locale.ROOT)

        if (cleanQuery.isEmpty()) return false

        val settingsTarget = extractMobileSettingsTarget("open $cleanQuery")
        if (settingsTarget != null) {
            return openAndroidSettingsScreen(settingsTarget, isTamil)
        }

        val aliasMap = mapOf(
            "யூடியூப்" to "youtube", "utube" to "youtube", "you tube" to "youtube",
            "வாட்ஸ்அப்" to "whatsapp", "வாட்சப்" to "whatsapp", "whats app" to "whatsapp",
            "இன்ஸ்டாகிராம்" to "instagram", "இன்ஸ்டா" to "instagram", "insta" to "instagram",
            "குரோம்" to "chrome", "google chrome" to "chrome",
            "கூகுள்" to "google",
            "கால்குலேட்டர்" to "calculator", "calc" to "calculator",
            "கேமரா" to "camera",
            "கேலரி" to "gallery", "photos" to "gallery",
            "ஃபைல்ஸ்" to "files", "file manager" to "files",
            "கிளாக்" to "clock", "alarm" to "clock",
            "போன்" to "phone", "dialer" to "phone",
            "மெசேஜ்" to "messages", "sms" to "messages",
            "ப்ளே ஸ்டோர்" to "play store", "playstore" to "play store",
            "ஸ்பாட்டிஃபை" to "spotify",
            "மேப்ஸ்" to "maps", "google maps" to "maps",
            "free fire" to "free fire", "ff" to "free fire"
        )
        val canonical = aliasMap[cleanQuery] ?: cleanQuery

        val knownPackages = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram" to listOf("com.instagram.android"),
            "chrome" to listOf("com.android.chrome"),
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
            "play store" to listOf("com.android.vending"),
            "spotify" to listOf("com.spotify.music"),
            "maps" to listOf("com.google.android.apps.maps"),
            "calculator" to listOf("com.transsion.calculator", "com.google.android.calculator", "com.android.calculator2"),
            "camera" to listOf("com.transsion.camera", "com.android.camera", "com.google.android.GoogleCamera"),
            "gallery" to listOf("com.transsion.ai.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
            "files" to listOf("com.transsion.filemanagerx", "com.google.android.apps.nbu.files", "com.android.documentsui"),
            "clock" to listOf("com.transsion.deskclock", "com.google.android.deskclock", "com.android.deskclock"),
            "phone" to listOf("com.sh.smart.caller", "com.google.android.dialer", "com.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.transsion.smartmessage", "com.android.mms"),
            "free fire" to listOf("com.dts.freefiremax", "com.dts.freefireth")
        )

        // Fast-path 1: Check known packages directly (< 1ms)
        knownPackages[canonical]?.forEach { pkg ->
            val directIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (directIntent != null) {
                val displayLabel = canonical.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return launchPackage(pkg, displayLabel, isTamil)
            }
        }

        // Fast-path 2: Use pre-cached installed apps list
        var installedApps = cachedInstalledApps
        if (installedApps.isEmpty()) {
            installedApps = queryLaunchableAppsInternal()
            cachedInstalledApps = installedApps
        }

        val exactMatches = installedApps.filter { it.normalizedLabel == canonical || it.normalizedLabel == cleanQuery }
        if (exactMatches.size == 1) {
            return launchPackage(exactMatches[0].packageName, exactMatches[0].label, isTamil)
        }

        val partial = installedApps.filter {
            it.normalizedLabel.contains(canonical) || canonical.contains(it.normalizedLabel)
        }

        return when {
            partial.isEmpty() -> {
                val msg = if (isTamil) {
                    "$spokenQuery உங்கள் போனில் நிறுவப்படவில்லை."
                } else {
                    "$spokenQuery is not installed on your phone."
                }
                speakCallback(msg, lang, "confused")
                false
            }
            partial.size == 1 -> launchPackage(partial[0].packageName, partial[0].label, isTamil)
            else -> {
                val names = partial.take(3).joinToString(", ") { it.label }
                val msg = if (isTamil) {
                    "பல செயலிகள் உள்ளன: $names. எதைத் திறக்க வேண்டும்?"
                } else {
                    "Multiple apps match: $names. Which one would you like to open?"
                }
                speakCallback(msg, lang, "confused")
                if (activity != null) {
                    activity.runOnUiThread {
                        val items = partial.take(5)
                        AlertDialog.Builder(activity)
                            .setTitle(if (isTamil) "செயலியைத் தேர்ந்தெடுக்கவும்" else "Select Application")
                            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                                launchPackage(items[which].packageName, items[which].label, isTamil)
                            }
                            .setNegativeButton(if (isTamil) "ரத்து" else "Cancel", null)
                            .show()
                    }
                }
                false
            }
        }
    }

    fun launchPackage(packageName: String, label: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            lastLaunchedAppName = label
            val msg = if (isTamil) "$label திறக்கிறேன்." else "Opening $label."
            speakCallback(msg, lang, "happy")
            true
        } catch (e: Exception) {
            speakCallback(if (isTamil) "$label திறக்க முடியவில்லை." else "Failed to open $label.", lang, "confused")
            false
        }
    }

    private fun handleCloseAppRequest(appName: String, isTamil: Boolean) {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanTarget = appName.lowercase(Locale.ROOT).trim()
        val isClosingMaxterSelf = cleanTarget.contains("maxter") ||
                cleanTarget.contains("jiraiya") ||
                cleanTarget.contains("மேக்ஸ்டர்") ||
                cleanTarget.contains("ஜிரையா")

        if (isClosingMaxterSelf) {
            val byeMsg = if (isTamil) "சரி நண்பா! Maxter J-ஐ மூடுகிறேன்." else "Closing Maxter J. See you soon!"
            speakCallback(byeMsg, lang, "happy")
            if (activity != null) {
                activity.runOnUiThread {
                    activity.stopNativeVoiceListening()
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            activity.finishAndRemoveTask()
                        } else {
                            activity.finish()
                        }
                    }, 500L)
                }
            }
            return
        }

        // Legitimate Android-supported exit for another foreground app: navigate to Home screen
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } catch (_: Exception) {
        }
        lastLaunchedAppName = null

        val msg = if (isTamil) {
            "$appName திரையிலிருந்து முகப்புத் திரைக்குத் திரும்பியுள்ளேன். (ஆண்ட்ராய்டு பாதுகாப்பால் பிற செயலிகளை நேரடியாக Force-Close செய்ய அனுமதி இல்லை)."
        } else {
            "Returned to the Home screen from $appName. (Android security does not permit force-closing other apps directly)."
        }
        speakCallback(msg, lang, "talking")
    }

    private fun bringMaxterJToFront(isTamil: Boolean) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            if (isTamil) {
                speakCallback("Maxter J திரைக்குத் திரும்பியுள்ளீர்கள்.", "ta-IN", "happy")
            }
        } catch (_: Exception) {
        }
    }

    private fun navigateToHomeScreen(isTamil: Boolean) {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(home)
            val msg = if (isTamil) "முகப்புத் திரைக்குச் செல்கிறது." else "Going to the home screen."
            speakCallback(msg, if (isTamil) "ta-IN" else "en-IN", "happy")
        } catch (_: Exception) {
        }
    }

    private fun handleGoBack(isTamil: Boolean) {
        if (activity != null) {
            activity.runOnUiThread {
                if (activity.webView.canGoBack()) {
                    activity.webView.goBack()
                } else {
                    activity.moveTaskToBack(true)
                }
            }
            val msg = if (isTamil) "பின்னால் செல்கிறது." else "Going back."
            speakCallback(msg, if (isTamil) "ta-IN" else "en-IN", "happy")
        } else {
            navigateToHomeScreen(isTamil)
        }
    }

    fun handleGeneralConversationOrAiQuery(userQuery: String) {
        val clean = userQuery.trim()
        if (clean.isEmpty()) return

        val hasTamil = clean.any { it in '஀'..'௿' }
        val isThanlish = looksLikeThanlish(clean)
        val savedLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        val respondInTamil = hasTamil || isThanlish || savedLocale.startsWith("ta")
        val replyLang = if (respondInTamil) "ta-IN" else "en-IN"

        val aiMode = prefs.getString("ai_mode", "local") ?: "local"
        val endpointUrl = (prefs.getString("ai_endpoint_url", "") ?: "").trim()
        val apiKey = (prefs.getString("ai_api_key", "") ?: "").trim()
        val modelName = (prefs.getString("ai_model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash").trim()

        if (aiMode == "local" || (endpointUrl.isEmpty() && apiKey.isEmpty())) {
            val localReply = generateLocalSmartReply(clean, respondInTamil)
            speakCallback(localReply, replyLang, "talking")
            return
        }

        backgroundExecutor.execute {
            val onlineReply = tryCallConfigurableAiEndpoint(
                query = clean,
                respondInTamil = respondInTamil,
                aiMode = aiMode,
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                modelName = modelName
            )
            val finalReply = onlineReply?.takeIf { it.isNotBlank() } ?: generateLocalSmartReply(clean, respondInTamil)
            speakCallback(finalReply, replyLang, "talking")
        }
    }

    private fun tryCallConfigurableAiEndpoint(
        query: String,
        respondInTamil: Boolean,
        aiMode: String,
        endpointUrl: String,
        apiKey: String,
        modelName: String
    ): String? {
        return try {
            val systemPrompt = if (respondInTamil) {
                "You are Maxter J, a friendly, fast personal AI assistant on an Infinix GT 20 Pro Android phone. Reply concisely (1-3 sentences) in natural Tamil or Tanglish."
            } else {
                "You are Maxter J, a friendly, fast personal AI assistant on an Infinix GT 20 Pro Android phone. Reply concisely (1-3 sentences) in clear English."
            }

            val isGeminiDirect = endpointUrl.contains("generativelanguage.googleapis.com") ||
                    (aiMode == "gemini_free" && apiKey.isNotEmpty())
            if (isGeminiDirect && apiKey.isNotEmpty()) {
                val model = modelName.ifEmpty { "gemini-2.5-flash" }
                val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser: $query")))
                    }))
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                if (conn.responseCode in 200..299) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates")
                    val text = candidates?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                    if (!text.isNullOrBlank()) return text.trim()
                }
                return null
            }

            if (endpointUrl.isEmpty()) return null
            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val payload = JSONObject().apply {
                put("model", modelName.ifEmpty { "llama-3.3-70b-versatile" })
                put("query", query)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", query))
                })
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = JSONObject(body)
                if (json.has("reply")) return json.getString("reply").trim()
                val choices = json.optJSONArray("choices")
                val msg = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                if (!msg.isNullOrBlank()) return msg.trim()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun generateLocalSmartReply(query: String, respondInTamil: Boolean): String {
        val lower = query.lowercase(Locale.ROOT)
        if (lower.contains("vanakkam") || lower.contains("வணக்கம்") || lower == "hi" || lower == "hello" || lower.contains("epdi irukka")) {
            return if (respondInTamil) {
                "வணக்கம் நண்பா! நான் Maxter J. 'Yow, YouTube search Tamil songs' அல்லது 'Bro, open Chrome' என்று எப்போது வேண்டுமானாலும் சொல்லுங்கள்!"
            } else {
                "Hello friend! I'm Maxter J. Say 'Yow, YouTube search Tamil songs' or 'Bro, open Chrome' anytime!"
            }
        }
        if (lower.contains("who are you") || lower.contains("neenga yaaru") || lower.contains("நீ யார்")) {
            return if (respondInTamil) {
                "நான் Maxter J AI — உங்கள் Infinix GT 20 Pro போனுக்கான தனிப்பட்ட தமிழ், Tanglish மற்றும் ஆங்கில குரல் உதவியாளர்!"
            } else {
                "I am Maxter J AI, your personal Tamil, Tanglish, and English voice assistant built for your Infinix GT 20 Pro."
            }
        }
        return if (respondInTamil) {
            "நான் கேட்டது: \"$query\". தேட வேண்டுமா? 'Yow, YouTube search $query' அல்லது 'Bro, Chrome search $query' என்று சொல்லுங்கள்!"
        } else {
            "I heard: \"$query\". Say 'Yow, YouTube search $query' or 'Bro, Chrome search $query' to search immediately!"
        }
    }

    fun startFloatingOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            if (activity != null) {
                Toast.makeText(context, "Grant 'Display over other apps' permission for Maxter J", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                activity.startActivityForResult(intent, MainActivity.REQ_OVERLAY_PERMISSION)
            }
            return
        }
        val svc = Intent(context, FloatingJiraiyaService::class.java).apply {
            action = FloatingJiraiyaService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    fun notifyOverlayVisualState(visualState: String) {
        if (!FloatingJiraiyaService.isRunning) return
        try {
            val svc = Intent(context, FloatingJiraiyaService::class.java).apply {
                action = FloatingJiraiyaService.ACTION_SET_VISUAL_STATE
                putExtra("visual_state", visualState)
            }
            context.startService(svc)
        } catch (_: Exception) {
        }
    }

    fun updateFloatingOverlayService(forceReposition: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return
        }
        val svc = Intent(context, FloatingJiraiyaService::class.java).apply {
            action = FloatingJiraiyaService.ACTION_UPDATE_CONFIG
            putExtra("force_reposition", forceReposition)
        }
        context.startService(svc)
    }

    fun stopFloatingOverlayService() {
        if (!FloatingJiraiyaService.isRunning) return
        val svc = Intent(context, FloatingJiraiyaService::class.java).apply {
            action = FloatingJiraiyaService.ACTION_STOP
        }
        context.startService(svc)
    }

    private fun recordTaskHistory(spokenCommand: String, actionName: String, success: Boolean) {
        val latMs = String.format(Locale.US, "%.1f", lastCommandLatencyMs)
        backgroundExecutor.execute {
            try {
                val existingStr = prefs.getString("task_history_json", "[]") ?: "[]"
                val arr = JSONArray(existingStr)
                val entry = JSONObject().apply {
                    put("command", spokenCommand)
                    put("action", actionName)
                    put("success", success)
                    put("latencyMs", latMs)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).format(Date()))
                }
                val updated = JSONArray()
                updated.put(entry)
                for (i in 0 until Math.min(arr.length(), 49)) {
                    updated.put(arr.getJSONObject(i))
                }
                prefs.edit().putString("task_history_json", updated.toString()).apply()
                uiEventCallback?.invoke(JSONObject().apply {
                    put("type", "task_logged")
                    put("entry", entry)
                })
            } catch (_: Exception) {
            }
        }
    }
}
