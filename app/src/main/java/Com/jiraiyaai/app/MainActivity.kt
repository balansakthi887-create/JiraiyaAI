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
 * MainActivity — Complete Upgrade & Bug Fix Edition for Maxter J AI
 * Target Device: Infinix GT 20 Pro (Infinix X6871, XOS 15.1.2, Android 15, compileSdk 35 / targetSdk 35)
 *
 * Key Upgrades Implemented:
 * 1. Permanent Top Status Bar & Bottom Navigation Bar Overlap Fix (`WindowInsets.Type.systemBars() | displayCutout() | ime()`)
 *    without double padding.
 * 2. Automatic Wake-Word Detection (`Yoww`, `Yow`, `Dai`, `Dei`, `Bro`, `Master`, `யோவ்`, `டேய்`, `ப்ரோ`, `மாஸ்டர்`)
 *    with single reused `SpeechRecognizer`, cooldown/backoff, TTS self-trigger prevention, and Battery-Saving Mode.
 * 3. Tamil, Thanglish, and English Natural Voice Understanding.
 * 4. Fast Google Search in Chrome (`com.android.chrome` with tab reuse), Google Search, and YouTube Search.
 * 5. Direct Mobile Settings Intents (Main, Wi-Fi, Bluetooth, Sound, Display, App Info) with `resolveActivity` checks.
 * 6. 3x Faster App Discovery & Command Execution (in-memory `cachedInstalledApps`, action-first before TTS, fast silence endpointer).
 * 7. Fixed App Close Command (`close Maxter J` vs `close YouTube` / `close Chrome` / `indha app close pannu`).
 * 8. Configurable Free-Tier AI API Client (Local Assistant / Cloudflare Worker Proxy / Gemini or Groq Free Tier with 8s timeout & offline fallback).
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
    private var isActivityResumed = false

    @Volatile
    private var manualMicTriggered = false

    @Volatile
    private var activeWakeWindowUntil = 0L

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

        // Permanent Fix for Android 15 (targetSdk 35) Top Status Bar, Display Cutout & Bottom Navigation Bar Overlap
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
                scheduleWakeWordLoopIfEnabled(600L)
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

    /**
     * Configures dark status bar and navigation bar colors and cutout handling for Infinix GT 20 Pro.
     */
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

    /**
     * Permanently prevents top status bar, punch-hole camera cutout, and bottom navigation bar overlap
     * on Android 6 through Android 15 (`targetSdk 35`).
     */
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
     * Checks whether continuous wake-word detection (`Yoww`, `Dai`, `Bro`, `Master`) should run right now.
     * Respects user toggle, microphone permission, TTS playback state, and Battery-Saving Mode.
     */
    private fun shouldRunContinuousWakeLoop(): Boolean {
        if (!isActivityResumed) return false
        if (!prefs.getBoolean("wake_word_enabled", true)) return false
        if (isSpeaking || isListening) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // Battery-Saving Mode check (default ON): pause continuous wake-word listening if screen is off or battery < 15%
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

    fun scheduleWakeWordLoopIfEnabled(delayMs: Long = 500L) {
        mainHandler.removeCallbacks(wakeRestartRunnable)
        if (!prefs.getBoolean("wake_word_enabled", true)) return
        mainHandler.postDelayed(wakeRestartRunnable, delayMs)
    }

    /**
     * Creates and reuses a SINGLE SpeechRecognizer instance on the main thread (never creating multiple instances).
     */
    private fun ensureSingleSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    val isWakeActive = SystemClock.uptimeMillis() < activeWakeWindowUntil
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", if (manualMicTriggered || isWakeActive) "listening" else "wake_standby")
                    })
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    if (manualMicTriggered || SystemClock.uptimeMillis() < activeWakeWindowUntil) {
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "thinking")
                        })
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    manualMicTriggered = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    // Backoff before restarting continuous wake-word listening
                    val backoff = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        900L
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
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val wasManual = manualMicTriggered
                    manualMicTriggered = false

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim().orEmpty()

                    if (spokenText.isEmpty()) {
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "idle")
                        })
                        scheduleWakeWordLoopIfEnabled(600L)
                        return
                    }

                    val now = SystemClock.uptimeMillis()
                    val inWakeWindow = now < activeWakeWindowUntil
                    val wakeEnabled = prefs.getBoolean("wake_word_enabled", true)
                    val wakeInspection = commandController.inspectWakeWord(spokenText)

                    if (wasManual || inWakeWindow || !wakeEnabled || wakeInspection.hasWakeWord) {
                        val commandToRun = if (wakeInspection.hasWakeWord) {
                            wakeInspection.strippedCommand
                        } else {
                            spokenText
                        }

                        // Case 1: User spoke ONLY the wake word ("Yoww", "Dai", "Bro", "Master")
                        if (commandToRun.isBlank()) {
                            activeWakeWindowUntil = SystemClock.uptimeMillis() + 8500L
                            notifyWebView(JSONObject().apply {
                                put("type", "wake_triggered")
                                put("wakeWord", spokenText)
                            })
                            // Immediately listen for the follow-up command without speaking over the user
                            scheduleWakeWordLoopIfEnabled(180L)
                            return
                        }

                        // Case 2: User spoke wake word + command OR spoke inside active wake/manual session
                        activeWakeWindowUntil = 0L
                        notifyWebView(JSONObject().apply {
                            put("type", "transcript")
                            put("text", spokenText)
                        })
                        val handled = commandController.processVoiceCommand(commandToRun, isFromVerifiedSession = wasManual)
                        if (!handled) {
                            commandController.handleGeneralConversationOrAiQuery(commandToRun)
                        }
                        scheduleWakeWordLoopIfEnabled(800L)
                    } else {
                        // Spoken phrase did not contain a wake word while in wake-word standby mode
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "idle")
                        })
                        scheduleWakeWordLoopIfEnabled(500L)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * Starts native Android SpeechRecognizer in English (`en-IN`) or Tamil (`ta-IN`)
     * using the single reused SpeechRecognizer instance and shortened silence endpointer extras.
     */
    fun startNativeVoiceListening(preferredLocale: String? = null, isManualTap: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            if (isManualTap) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
                speakWithAnimation("Microphone permission is required for voice commands.", "en-IN", "confused")
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
                scheduleWakeWordLoopIfEnabled(900L)
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

            val recognizerIntent = (prebuiltRecognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
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
                    // Pause microphone listening while TTS speaks so Maxter J never triggers on its own voice
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
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    scheduleWakeWordLoopIfEnabled(450L)
                }

                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                    scheduleWakeWordLoopIfEnabled(600L)
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
        isActivityResumed = true
        // Reset lastLaunchedAppName when the user returns to Maxter J in foreground
        MaxterVoiceCommandController.lastLaunchedAppName = null
        if (::webView.isInitialized) {
            syncOverlayStateToWebView()
        }
        scheduleWakeWordLoopIfEnabled(500L)
    }

    override fun onPause() {
        isActivityResumed = false
        mainHandler.removeCallbacks(wakeRestartRunnable)
        if (isListening && !manualMicTriggered) {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            isListening = false
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

    /**
     * Marks `window.__androidInsetsApplied = true` (preventing double status-bar padding in WebView)
     * and non-destructively bridges Web Speech recognition into `MaxterVoiceCommandController`.
     */
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

    /**
     * Secure, validated JavaScript Bridge exposing personal assistant & hardware capabilities
     * to `index.html` (`window.AndroidVoice` and `window.MaxterNative`).
     */
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
                // Handled inside Maxter J navigation stack (Category -> Settings -> Menu -> Home)
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
 * MaxterVoiceCommandController — High-Speed Voice NLU & Device Controller in English, Tamil, and Thanglish
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
        private var lastExecutedCommandText: String = ""

        @Volatile
        private var lastExecutedCommandTime: Long = 0L

        private val backgroundExecutor = Executors.newCachedThreadPool()
    }

    /**
     * Pre-loads and caches launchable Android applications on a background thread
     * so voice app opening executes in < 2ms instead of blocking on PackageManager.
     */
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
     * Inspects a spoken transcript for built-in or custom wake words:
     * "Yoww", "Yow", "Yo", "Dai", "Dei", "Bro", "Master", "Maxter", "Maxter J", "Jiraiya", "Perusu",
     * "யோவ்", "டேய்", "ப்ரோ", "மாஸ்டர்", "ஜிரையா", "மேக்ஸ்டர்".
     */
    fun inspectWakeWord(rawInput: String): WakeWordResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return WakeWordResult(false, "")

        val lower = trimmed.lowercase(Locale.ROOT)
        val customWakeRaw = (prefs.getString("wake_phrase", "yoww, dai, bro, master") ?: "yoww, dai, bro, master")
        val customTokens = customWakeRaw.split(",", ";")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        for (token in customTokens) {
            if (lower == token) return WakeWordResult(true, "")
            if (lower.startsWith("$token ") || lower.startsWith("$token,") || lower.startsWith("$token!")) {
                val rem = trimmed.substring(token.length).trimStart(',', ' ', '.', '!', '-')
                return WakeWordResult(true, rem)
            }
        }

        val wakeRegex = Regex(
            "^(?:hey\\s+|hi\\s+|hello\\s+|ok\\s+|okay\\s+)?(yow+w*|yo|dai|dei|bro|master\\s*j?|maxter\\s*j?|jiraiya|pervy\\s*sage|perusu|யோவ்|டேய்|ப்ரோ|மாஸ்டர்|ஜிரையா|மேக்ஸ்டர்\\s*ஜே|மேக்ஸ்டர்)(?:$|[,\\s!.:-]+(.*)$)",
            RegexOption.IGNORE_CASE
        )
        val match = wakeRegex.find(trimmed)
        if (match != null) {
            val remainder = match.groupValues.getOrNull(2)?.trim().orEmpty()
            return WakeWordResult(true, remainder)
        }

        return WakeWordResult(false, trimmed)
    }

    fun processVoiceCommand(rawInput: String, isFromVerifiedSession: Boolean = false): Boolean {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return false

        // Deduplication guard (prevents duplicate execution within 650ms)
        val now = SystemClock.uptimeMillis()
        val normDedup = raw.lowercase(Locale.ROOT)
        if (normDedup == lastExecutedCommandText && (now - lastExecutedCommandTime) < 650L) {
            return true
        }

        val hasTamilChars = raw.any { it in '஀'..'௿' }
        val savedLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        val isThanlish = looksLikeThanlish(raw)
        val respondInTamil = hasTamilChars || isThanlish || savedLocale.startsWith("ta")
        val replyLang = if (respondInTamil) "ta-IN" else "en-IN"

        // Strip wake word if present at the beginning of the command
        val wakeInspect = inspectWakeWord(raw)
        var cleaned = (if (wakeInspect.hasWakeWord && wakeInspect.strippedCommand.isNotEmpty()) {
            wakeInspect.strippedCommand
        } else {
            raw
        }).lowercase(Locale.ROOT).trim()

        // Strip polite conversational fillers at edges ("konjam", "please", "ippo", "udane")
        cleaned = cleaned
            .replace(Regex("^(?:please|kindly|ippo|udane|seri)\\s+"), "")
            .replace(Regex("\\s+(?:please|ippo|udane)$"), "")
            .trim()

        // 0. STOP SPEAKING (Always allowed immediately)
        if (cleaned == "stop" || cleaned.contains("stop speaking") || cleaned.contains("be quiet") ||
            cleaned.contains("silence") || cleaned.contains("நிறுத்து") || cleaned.contains("பேசாதே") ||
            cleaned.contains("stop pannu")
        ) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            activity?.stopSpeakingImmediate()
            recordTaskHistory(raw, "Stop Speaking", true)
            return true
        }

        // Biometric Voice / Face Authorization Gate if enabled in Settings
        val needVoice = prefs.getBoolean("require_voice_auth", false)
        val needFace = prefs.getBoolean("require_face_auth", false)
        val sessionVerified = prefs.getBoolean("session_verified", true)
        if ((needVoice || needFace) && !sessionVerified && !isFromVerifiedSession) {
            val missingMsg = if (respondInTamil) {
                "பாதுகாப்பு பூட்டு இயக்கத்தில் உள்ளது. கட்டளையை இயக்க உங்கள் குரல் அல்லது முகத்தைச் சரிபார்க்கவும்."
            } else {
                "Biometric security lock is active. Please verify your enrolled voice or face before running commands."
            }
            speakCallback(missingMsg, replyLang, "confused")
            recordTaskHistory(raw, "Blocked by Biometric Lock", false)
            return true
        }

        // 1. FLASHLIGHT / TORCH
        if (isFlashlightOnCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = setHardwareFlashlight(true, respondInTamil)
            recordTaskHistory(raw, "Flashlight ON", ok)
            return true
        }
        if (isFlashlightOffCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = setHardwareFlashlight(false, respondInTamil)
            recordTaskHistory(raw, "Flashlight OFF", ok)
            return true
        }

        // 2. VOLUME CONTROL ("Volume up", "Yow volume konjam increase pannu", "Set media volume to 50 percent")
        val volCmd = parseVolumeCommand(cleaned)
        if (volCmd != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val (stream, action, pct) = volCmd
            val ok = controlDeviceVolume(stream, action, pct, respondInTamil)
            recordTaskHistory(raw, "Volume ($stream: $action)", ok)
            return true
        }

        // 3. BRIGHTNESS CONTROL ("Brightness up", "Bro brightness kammi pannu", "Set brightness to 60 percent")
        val brightPct = parseBrightnessCommand(cleaned)
        if (brightPct != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = setScreenBrightness(brightPct, systemWide = true, isTamil = respondInTamil)
            recordTaskHistory(raw, "Set Brightness ($brightPct%)", ok)
            return true
        }

        // 4. TIME & DATE
        if (isTimeOrDateCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            tellCurrentTimeAndDate(respondInTamil)
            recordTaskHistory(raw, "Tell Time & Date", true)
            return true
        }

        // 5. LANGUAGE SWITCHING
        val newLang = parseLanguageSwitchCommand(cleaned)
        if (newLang != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            prefs.edit().putString("voice_locale", newLang).apply()
            val msg = if (newLang.startsWith("ta")) "சரி! இனி நான் தமிழில் பேசுகிறேன்." else "Sure! I will now respond in English."
            speakCallback(msg, newLang, "happy")
            recordTaskHistory(raw, "Switch Language ($newLang)", true)
            return true
        }

        // 6. OVERLAY VOICE CONTROLS ("Start overlay", "Stop overlay", "Hide overlay", "Show character overlay")
        if (cleaned.contains("start overlay") || cleaned.contains("show overlay") || cleaned.contains("enable overlay") ||
            cleaned.contains("overlay on pannu")
        ) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
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
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            prefs.edit().putBoolean("overlay_enabled", false).apply()
            stopFloatingOverlayService()
            activity?.syncOverlayStateToWebView()
            speakCallback(if (respondInTamil) "Maxter J ஓவர்லே நிறுத்தப்பட்டது." else "Floating character overlay stopped.", replyLang, "happy")
            recordTaskHistory(raw, "Stop Character Overlay", true)
            return true
        }

        // 7. CHARACTER CUSTOMIZATION / IN-APP SETTINGS
        if (cleaned.contains("customize character") || cleaned.contains("character setting") ||
            cleaned.contains("character customization") || cleaned.contains("கேரக்டர் செட்டிங்ஸ்")
        ) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
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

        // 8. YOUTUBE SEARCH BY VOICE ("YouTube la ... search pannu", "Search YouTube for ...")
        val ytQuery = extractYouTubeSearchQuery(cleaned)
        if (ytQuery != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = performYouTubeSearch(ytQuery, respondInTamil)
            recordTaskHistory(raw, "YouTube Search: $ytQuery", ok)
            return true
        }

        // 9. FAST GOOGLE SEARCH IN CHROME / GOOGLE ("Yoww Chrome la Free Fire sensitivity search pannu", "Search in Chrome for AI tools")
        val searchTriple = extractChromeOrGoogleSearchQuery(cleaned)
        if (searchTriple != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val (isSearchIntent, preferChrome, query) = searchTriple
            if (isSearchIntent) {
                if (query.isBlank()) {
                    val ask = if (respondInTamil) {
                        "குரோமில் என்ன தேட வேண்டும் என்று சொல்லுங்கள்?"
                    } else {
                        "What would you like me to search for?"
                    }
                    speakCallback(ask, replyLang, "confused")
                    recordTaskHistory(raw, "Search (Prompted for Query)", true)
                    return true
                }
                val ok = performGoogleOrChromeSearch(query, preferChrome = preferChrome, isTamil = respondInTamil)
                recordTaskHistory(raw, if (preferChrome) "Chrome Search: $query" else "Google Search: $query", ok)
                return true
            }
        }

        // 10. OPEN FILES, PHOTOS, VIDEOS, PDF, DOWNLOADS
        val fileReq = parseFileOpenCommand(cleaned)
        if (fileReq != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val (category, nameFilter) = fileReq
            val ok = findAndOpenAuthorizedFile(category, nameFilter, respondInTamil)
            recordTaskHistory(raw, "Open File ($category)", ok)
            return true
        }

        // 11. NAVIGATION & APP CLOSING ("Close YouTube", "Close Chrome", "Close settings", "Close Maxter J", "Dei indha app close pannu")
        if (isReturnToMaxterCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            bringMaxterJToFront(respondInTamil)
            recordTaskHistory(raw, "Return to Maxter J", true)
            return true
        }
        if (isGoHomeCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            navigateToHomeScreen(respondInTamil)
            recordTaskHistory(raw, "Go to Home Screen", true)
            return true
        }
        if (isGoBackCommand(cleaned)) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            handleGoBack(respondInTamil)
            recordTaskHistory(raw, "Navigate Back", true)
            return true
        }
        val closeTarget = extractCloseAppTarget(cleaned)
        if (closeTarget != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            handleCloseAppRequest(closeTarget, respondInTamil)
            recordTaskHistory(raw, "Close App ($closeTarget)", true)
            return true
        }

        // 12. MOBILE SETTINGS BY VOICE ("Open settings", "Open mobile settings", "Dai settings open pannu", "Open Wi-Fi / Bluetooth / Sound / Display / App settings")
        val settingsTarget = extractMobileSettingsTarget(cleaned)
        if (settingsTarget != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = openAndroidSettingsScreen(settingsTarget, respondInTamil)
            recordTaskHistory(raw, "Open Settings ($settingsTarget)", ok)
            return true
        }

        // 13. OPEN INSTALLED APPS FAST ("Open YouTube", "Dai YouTube open pannu", "Open Chrome", "Open WhatsApp")
        val openTarget = extractOpenAppTarget(cleaned)
        if (openTarget != null) {
            lastExecutedCommandText = normDedup
            lastExecutedCommandTime = now
            val ok = openApplicationByName(openTarget, respondInTamil)
            recordTaskHistory(raw, "Open App: $openTarget", ok)
            return true
        }

        return false
    }

    private fun looksLikeThanlish(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val markers = listOf(
            "pannu", "panna", "konjam", "kammi", "athigam", "adhigam", "thira",
            "moodu", "enna", "enga", "indha", "andha", "epdi", "eppadi", "sollu",
            "google-la", "google la", "chrome-la", "chrome la", "youtube-la", "youtube la",
            "podu", "anai", "aakku", "yow", "yoww", "dai", "dei"
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
        // Do not confuse "open sound settings" with volume adjustment
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
        return cmd.contains("what time") || cmd.contains("current time") || cmd.contains("enna time") ||
                cmd.contains("time enna") || cmd.contains("மணி என்ன") || cmd.contains("நேரம் என்ன") ||
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

    /**
     * Extracts YouTube search query if the user specifically asked to search on YouTube:
     * - "YouTube la Tamil songs search pannu" -> "Tamil songs"
     * - "Search YouTube for Kotlin tutorials" -> "Kotlin tutorials"
     */
    private fun extractYouTubeSearchQuery(cmd: String): String? {
        val suffix = Regex("^(?:youtube-la|youtube\\s+la|in\\s+youtube|யூடியூபில்|யூடியூப்ல)\\s+(.+?)\\s+(?:search\\s+pannu|தேடு|தேடுங்கள்|search|play\\s+pannu)$").find(cmd)
        if (suffix != null) {
            return suffix.groupValues[1].trim()
        }
        val prefix = Regex("^(?:search\\s+youtube\\s+for|youtube\\s+search\\s+for|search\\s+on\\s+youtube\\s+for|search\\s+in\\s+youtube\\s+for)\\s+(.+)$").find(cmd)
        if (prefix != null) {
            return prefix.groupValues[1].trim()
        }
        return null
    }

    /**
     * Extracts Chrome or Google search query from English, Tamil, or Thanglish voice commands:
     * Returns Triple(isSearchIntent, preferChrome, cleanQuery).
     * Examples:
     * - "Chrome la Free Fire sensitivity search pannu" -> Triple(true, true, "Free Fire sensitivity")
     * - "Search in Chrome for AI tools" -> Triple(true, true, "AI tools")
     * - "Google la Infinix GT 20 Pro gaming settings search pannu" -> Triple(true, true, "Infinix GT 20 Pro gaming settings")
     * - "Search Python tutorial" -> Triple(true, true, "Python tutorial")
     */
    private fun extractChromeOrGoogleSearchQuery(cmd: String): Triple<Boolean, Boolean, String>? {
        if (cmd == "search" || cmd == "google search" || cmd == "chrome search" || cmd == "தேடு" || cmd == "search pannu") {
            return Triple(true, true, "")
        }

        val mentionsChrome = cmd.contains("chrome") || cmd.contains("குரோம்")

        // 1. Thanglish / Tamil location + query + suffix:
        // e.g. "chrome la free fire sensitivity search pannu", "google-la tamil songs search pannu", "<query> chrome la search pannu"
        val locPrefixRegex = Regex("^(?:chrome-la|chrome\\s+la|in\\s+chrome|on\\s+chrome|குரோமில்|குரோம்ல|google-la|google\\s+la|in\\s+google|on\\s+google|கூகுளில்|கூகுள்ல)\\s+(.+?)\\s+(?:chrome\\s+search\\s+pannu|google\\s+search\\s+pannu|search\\s+pannu|தேடு|தேடுங்கள்|search)$")
        val locPrefixMatch = locPrefixRegex.find(cmd)
        if (locPrefixMatch != null) {
            val q = locPrefixMatch.groupValues[1]
                .replace(Regex("^(?:for|about)\\s+"), "")
                .trim()
            return Triple(true, true, q)
        }

        // 2. Query + location + suffix: e.g. "free fire tips chrome la search pannu"
        val locMidRegex = Regex("^(.+?)\\s+(?:chrome-la|chrome\\s+la|in\\s+chrome|google-la|google\\s+la|in\\s+google|கூகுளில்|குரோமில்)\\s+(?:search\\s+pannu|தேடு|தேடுங்கள்|search)$")
        val locMidMatch = locMidRegex.find(cmd)
        if (locMidMatch != null) {
            val q = locMidMatch.groupValues[1].trim()
            return Triple(true, true, q)
        }

        // 3. Generic suffix: "<query> search pannu" / "<query> தேடு"
        val suffixRegex = Regex("^(.+?)\\s+(?:google\\s+search\\s+pannu|chrome\\s+search\\s+pannu|search\\s+pannu|தேடு|தேடுங்கள்|search\\s+in\\s+google|search\\s+in\\s+chrome)$")
        val suffixMatch = suffixRegex.find(cmd)
        if (suffixMatch != null) {
            val q = suffixMatch.groupValues[1]
                .replace(Regex("^(?:chrome-la|chrome\\s+la|google-la|google\\s+la|for|about)\\s+"), "")
                .trim()
            return Triple(true, true, q)
        }

        // 4. English prefix pattern: "search in chrome for <query>", "search google for <query>", "search <query>"
        val prefixRegex = Regex("^(?:search\\s+in\\s+chrome\\s+for|search\\s+on\\s+chrome\\s+for|chrome\\s+search\\s+for|search\\s+chrome\\s+for|search\\s+google\\s+for|google\\s+search\\s+for|search\\s+on\\s+google\\s+for|search\\s+in\\s+google\\s+for|search\\s+for|google\\s+search|chrome\\s+search|search\\s+google|search\\s+chrome|search)\\s+(.+)$")
        val prefixMatch = prefixRegex.find(cmd)
        if (prefixMatch != null) {
            val q = prefixMatch.groupValues[1]
                .replace(Regex("\\s+(?:on\\s+google|in\\s+google|on\\s+chrome|in\\s+chrome|search\\s+pannu)$"), "")
                .trim()
            return Triple(true, mentionsChrome || true, q)
        }

        return null
    }

    /**
     * Extracts mobile settings target screen if the command asks to open Android / phone settings.
     */
    private fun extractMobileSettingsTarget(cmd: String): String? {
        if (cmd.contains("character setting")) return null // Handled by in-app character settings
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
        return cmd == "go back" || cmd == "back" || cmd.contains("previous screen") ||
                cmd.contains("பின்னாடி போ") || cmd.contains("பின்னால் போ")
    }

    private fun extractCloseAppTarget(cmd: String): String? {
        if (cmd.contains("indha app close") || cmd.contains("indha app-a close") ||
            cmd.contains("close this app") || cmd == "close app" || cmd == "இந்த செயலியை மூடு"
        ) {
            // If an external app was launched in this session or we are running from the overlay, target that app;
            // otherwise if inside Maxter J foreground with no external app launched, target Maxter J.
            return lastLaunchedAppName ?: (if (activity != null) "maxter j" else "current external app")
        }
        val en = Regex("^(?:close|exit|quit)\\s+(.+?)(?:\\s+pannu)?$").find(cmd)
        if (en != null) return en.groupValues[1].trim()

        val ta = Regex("^(.+?)\\s+(?:close\\s+pannu|மூடு|க்ளோஸ் பண்ணு|exit\\s+pannu)$").find(cmd)
        if (ta != null) return ta.groupValues[1].trim()
        return null
    }

    private fun extractOpenAppTarget(cmd: String): String? {
        val en = Regex("^(?:open|launch|start)\\s+(.+?)(?:\\s+pannu)?$").find(cmd)
        if (en != null) return en.groupValues[1].trim()

        val ta = Regex("^(.+?)\\s+(?:open\\s+pannu|திற|ஓபன் பண்ணு|launch\\s+pannu)$").find(cmd)
        if (ta != null) return ta.groupValues[1].trim()
        return null
    }

    // =============================================================================================
    // HARDWARE & ANDROID EXECUTION METHODS (ACTION-FIRST FOR 3X FASTER RESPONSE)
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

            // Execute hardware torch state change FIRST before TTS
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

    /**
     * Opens Google Search directly in Google Chrome (`com.android.chrome`) with tab reuse
     * (`Browser.EXTRA_APPLICATION_ID`) so repeated voice searches are instant and don't clutter tabs.
     * Falls back cleanly to the default browser if Chrome is not installed.
     */
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

    /**
     * Opens YouTube search directly in the YouTube app (`com.google.android.youtube`),
     * falling back to YouTube web results in Chrome/Browser if the app is not installed.
     */
    fun performYouTubeSearch(rawQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isEmpty()) return false

        val pm = context.packageManager
        return try {
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (ytIntent.resolveActivity(pm) != null) {
                context.startActivity(ytIntent)
                lastLaunchedAppName = "YouTube"
            } else {
                val webUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lastLaunchedAppName = "YouTube"
            }
            speakCallback(
                if (isTamil) "YouTube-ல் $cleanQuery தேடுகிறேன்." else "Searching YouTube for $cleanQuery.",
                lang,
                "happy"
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens supported Android System Settings screens safely after verifying `resolveActivity`.
     */
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
            else -> Triple(Intent(Settings.ACTION_SETTINGS), "Mobile settings", "மொபைல் செட்டிங்ஸ்")
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

    /**
     * Opens installed apps ~3x faster using the pre-warmed `cachedInstalledApps` list
     * and launches the intent BEFORE starting TTS.
     */
    fun openApplicationByName(spokenQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = spokenQuery
            .replace(Regex("^(the|app|application|mobile)\\s+"), "")
            .replace(Regex("\\s+(app|application|செயலி|pannu)$"), "")
            .trim()
            .lowercase(Locale.ROOT)

        if (cleanQuery.isEmpty()) return false

        // Route settings requests directly
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
            "free fire" to "free fire", "ff" to "free fire"
        )
        val canonical = aliasMap[cleanQuery] ?: cleanQuery

        val knownPackages = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram" to listOf("com.instagram.android"),
            "chrome" to listOf("com.android.chrome"),
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
            "calculator" to listOf("com.transsion.calculator", "com.google.android.calculator", "com.android.calculator2"),
            "camera" to listOf("com.transsion.camera", "com.android.camera", "com.google.android.GoogleCamera"),
            "gallery" to listOf("com.transsion.ai.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
            "files" to listOf("com.transsion.filemanagerx", "com.google.android.apps.nbu.files", "com.android.documentsui"),
            "clock" to listOf("com.transsion.deskclock", "com.google.android.deskclock", "com.android.deskclock"),
            "phone" to listOf("com.sh.smart.caller", "com.google.android.dialer", "com.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.transsion.smartmessage", "com.android.mms"),
            "play store" to listOf("com.android.vending"),
            "free fire" to listOf("com.dts.freefiremax", "com.dts.freefireth")
        )

        // Fast-path 1: Check known packages directly via PackageManager.getLaunchIntentForPackage (< 1ms)
        knownPackages[canonical]?.forEach { pkg ->
            val directIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (directIntent != null) {
                val displayLabel = canonical.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return launchPackage(pkg, displayLabel, isTamil)
            }
        }

        // Fast-path 2: Use pre-cached installed apps list (refresh synchronously only if empty)
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
            // Action-first: Launch activity BEFORE speaking confirmation for instant response
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

    /**
     * Properly handles both closing Maxter J itself and exiting/closing other apps:
     * - Case A: Target is Maxter J ("close Maxter J", "exit Maxter J", or "close this app" when Maxter J is in front)
     *   -> Stops TTS/listening cleanly and calls `activity.finishAndRemoveTask()`.
     * - Case B: Target is another app ("close YouTube", "close Chrome", "close settings", "indha app close pannu" over another app)
     *   -> Navigates to the Android Home screen (`ACTION_MAIN` + `CATEGORY_HOME`) so the foreground app closes to background
     *      without ever crashing or closing Maxter J, and speaks a concise, honest explanation.
     */
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
                    }, 550L)
                }
            }
            return
        }

        // Target is another app (e.g. YouTube, Chrome, Settings, WhatsApp)
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
            "$appName திரையிலிருந்து வெளியேறி முகப்புத் திரைக்குச் சென்றுவிட்டேன். (ஆண்ட்ராய்டு பாதுகாப்பால் பிற செயலிகளை நேரடியாக Force-Stop செய்ய முடியாது)."
        } else {
            "Exited $appName to the Home screen. (Android security restricts ordinary apps from force-killing other apps directly)."
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
            bringMaxterJToFront(isTamil)
        }
    }

    /**
     * Handles general questions, coding help, translation, and casual Tamil/Thanglish/English conversation.
     * Uses the user-configured Free-Tier AI API (Cloudflare Worker Proxy or Google Gemini / Groq / OpenRouter)
     * with an 8-second timeout and automatic fallback to the built-in Local Offline Assistant.
     */
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

        // Run network request on background thread with 8s timeout; fallback to local reply on any error
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
                "You are Maxter J, a friendly, fast personal AI assistant on an Infinix GT 20 Pro Android phone. Reply concisely (1-3 sentences) in natural Tamil or Thanglish."
            } else {
                "You are Maxter J, a friendly, fast personal AI assistant on an Infinix GT 20 Pro Android phone. Reply concisely (1-3 sentences) in clear English."
            }

            // Option A: Google Gemini REST API (`generativelanguage.googleapis.com`)
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

            // Option B: Cloudflare Worker Proxy OR OpenAI-Compatible Free Tier (Groq / OpenRouter)
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
                "வணக்கம் நண்பா! நான் Maxter J. சூப்பரா இருக்கேன்! Chrome தேடல், YouTube, செட்டிங்ஸ், பிளாஷ்லைட் என எதை வேண்டுமானாலும் கேளுங்கள்."
            } else {
                "Hello friend! I'm Maxter J, ready to help. Ask me to search Chrome, open apps, or control your phone!"
            }
        }
        if (lower.contains("who are you") || lower.contains("neenga yaaru") || lower.contains("நீ யார்")) {
            return if (respondInTamil) {
                "நான் Maxter J AI — உங்கள் Infinix GT 20 Pro போனுக்கான தனிப்பட்ட தமிழ், Thanglish மற்றும் ஆங்கில குரல் உதவியாளர்!"
            } else {
                "I am Maxter J AI, your personal Tamil, Thanglish, and English voice assistant built for your Infinix GT 20 Pro."
            }
        }
        return if (respondInTamil) {
            "நான் கேட்டது: \"$query\". இதை Chrome-ல் தேட வேண்டுமா? 'Yoww Chrome la $query search pannu' என்று சொல்லுங்கள்!"
        } else {
            "I heard: \"$query\". Say 'Yoww search in Chrome for $query' to look it up on the web immediately!"
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
        backgroundExecutor.execute {
            try {
                val existingStr = prefs.getString("task_history_json", "[]") ?: "[]"
                val arr = JSONArray(existingStr)
                val entry = JSONObject().apply {
                    put("command", spokenCommand)
                    put("action", actionName)
                    put("success", success)
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
