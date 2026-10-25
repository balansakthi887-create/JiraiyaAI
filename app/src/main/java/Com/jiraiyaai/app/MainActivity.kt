package com.jiraiyaai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity — Complete Personal AI Assistant Upgrade for Maxter J
 * Target Device: Infinix GT 20 Pro (Infinix X6871, XOS 15.1.2, Android 15)
 *
 * Capabilities:
 * 1. Preserves `file:///android_asset/index.html` and original character (`images/21574.png`)
 * 2. Natural English, Tamil (`ta-IN`), and Thanlish voice command engine with configurable wake phrase
 * 3. Google Search by voice (extracts clean query, opens encoded Google Search URL / intent)
 * 4. Installed App Discovery & Launching (`PackageManager`) + honest App Closing / Back / Home navigation
 * 5. Authorized File, Photo, Video, Audio & PDF opening (`MediaStore` + Storage Access Framework)
 * 6. Voice-controlled Device Volume (`AudioManager`: Media, Alarm, Notification, Call)
 * 7. Voice-controlled Screen Brightness (In-app window + system-wide `Settings.System.SCREEN_BRIGHTNESS`)
 * 8. Hardware Rear Torch / Flashlight Control (`CameraManager.setTorchMode`)
 * 9. Privacy-Focused Biometric Voice & Face Enrollment / Verification state & local profile storage
 * 10. Automatic Task System with persistent Task History log (`SharedPreferences` JSON)
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    lateinit var webView: WebView
    lateinit var prefs: SharedPreferences
    lateinit var commandController: MaxterVoiceCommandController

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        const val PREFS_NAME = "JiraiyaOverlayPrefs"
        const val REQ_PERMISSIONS = 1001
        const val REQ_OVERLAY_PERMISSION = 1002
        const val REQ_WRITE_SETTINGS = 1003
        const val REQ_SAF_OPEN_DOCUMENT = 1004
        const val REQ_WEBVIEW_FILE_CHOOSER = 1005
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        commandController = MaxterVoiceCommandController(
            context = this,
            activity = this,
            prefs = prefs,
            speakCallback = { msg, lang, expr -> speakWithAnimation(msg, lang, expr) },
            uiEventCallback = { eventJson -> notifyWebView(eventJson) }
        )

        requestInitialRuntimePermissions()

        val rootLayout = FrameLayout(this)

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
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // Grant WebRTC camera & microphone access inside WebView for Voice & Face Enrollment
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
     * Starts native Android SpeechRecognizer in English (`en-IN`) or Tamil (`ta-IN`)
     * and notifies the UI so the character displays the Listening animation.
     */
    fun startNativeVoiceListening(preferredLocale: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSIONS)
            speakWithAnimation("Microphone permission is required for voice commands.", "en-IN", "confused")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakWithAnimation("Speech recognition is not available on this device.", "en-IN", "confused")
            return
        }

        val localeToUse = preferredLocale ?: (prefs.getString("voice_locale", "ta-IN") ?: "ta-IN")

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "listening")
                        })
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListening = false
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "thinking")
                        })
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        notifyWebView(JSONObject().apply {
                            put("type", "state")
                            put("state", "idle")
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                        if (spokenText.isNotEmpty()) {
                            notifyWebView(JSONObject().apply {
                                put("type", "transcript")
                                put("text", spokenText)
                            })
                            commandController.processVoiceCommand(spokenText, isFromVerifiedSession = false)
                        } else {
                            notifyWebView(JSONObject().apply {
                                put("type", "state")
                                put("state", "idle")
                            })
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeToUse)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            isListening = false
            speakWithAnimation("Unable to start microphone listening.", "en-IN", "confused")
        }
    }

    fun stopNativeVoiceListening() {
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
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "talking")
                    })
                }
                override fun onDone(utteranceId: String?) {
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
                }
                override fun onError(utteranceId: String?) {
                    notifyWebView(JSONObject().apply {
                        put("type", "state")
                        put("state", "idle")
                    })
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
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "maxter_utt_${System.currentTimeMillis()}")
            }
        }
    }

    fun stopSpeakingImmediate() {
        runOnUiThread {
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
        })
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            syncOverlayStateToWebView()
        }
    }

    private fun notifyWebView(payload: JSONObject) {
        runOnUiThread {
            val escaped = payload.toString()
            webView.evaluateJavascript(
                "window.onMaxterNativeEvent && window.onMaxterNativeEvent($escaped);",
                null
            )
        }
    }

    /**
     * Non-destructively hooks any legacy `index.html` Web Speech recognition so spoken
     * commands always trigger the full native `MaxterVoiceCommandController`.
     */
    private fun injectNonDestructiveAssistantBridge() {
        val js = """
            (function() {
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
        fun startVoiceListening(locale: String) {
            runOnUiThread { startNativeVoiceListening(locale.ifBlank { null }) }
        }

        @JavascriptInterface
        fun stopVoiceListening() {
            runOnUiThread { stopNativeVoiceListening() }
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
            return commandController.performGoogleSearch(query, isTa)
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
                if (obj.has("wake_phrase")) ed.putString("wake_phrase", obj.getString("wake_phrase"))
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
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        // Turn off hardware flashlight on destroy if left on unexpectedly
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
 * MaxterVoiceCommandController — Handles all 14 Automated Tasks in English, Tamil, and Thanlish:
 * 1. Google Search
 * 2. Open Installed Application
 * 3. Find & Open Authorized File / PDF / Document
 * 4. Play Selected Video
 * 5. Open Selected Image / Latest Photo
 * 6. Control Device Volume (Media, Alarm, Notification, Call)
 * 7. Control Screen Brightness (Window & System-Wide)
 * 8. Hardware Flashlight / Torch ON & OFF
 * 9. Tell Current Time & Date
 * 10. Start / Stop Speech Playback
 * 11. Change Assistant Language (Tamil / English / Thanlish)
 * 12. Open Character Customization / Settings
 * 13. Open Supported Android Settings Pages (Wi-Fi, Bluetooth, Display, Sound, Main Settings)
 * 14. Explain Restricted Actions & Perform Supported Back / Home Navigation
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

    companion object {
        @Volatile
        var lastLaunchedAppName: String? = null

        @Volatile
        var isTorchOn: Boolean = false
    }

    fun processVoiceCommand(rawInput: String, isFromVerifiedSession: Boolean = false): Boolean {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return false

        val hasTamilChars = raw.any { it in '஀'..'௿' }
        val savedLocale = prefs.getString("voice_locale", "ta-IN") ?: "ta-IN"
        val isThanlish = looksLikeThanlish(raw)
        val respondInTamil = hasTamilChars || isThanlish || savedLocale.startsWith("ta")
        val replyLang = if (respondInTamil) "ta-IN" else "en-IN"

        // Strip configurable wake phrase ("Hey Maxter", "Maxter J", "Jiraiya", "Pervy Sage")
        val customWake = (prefs.getString("wake_phrase", "hey maxter") ?: "hey maxter").lowercase(Locale.ROOT).trim()
        var cleaned = raw.lowercase(Locale.ROOT)
        if (customWake.isNotEmpty() && cleaned.startsWith(customWake)) {
            cleaned = cleaned.removePrefix(customWake).trimStart(',', ' ', '.', '!')
        }
        cleaned = cleaned.replace(
            Regex("^(hey\\s+|hi\\s+|hello\\s+|ok\\s+|okay\\s+)?(maxter\\s*j|maxter|master\\s*j|jiraiya|pervy\\s*sage|ஜிரையா|மேக்ஸ்டர்\\s*ஜே|மேக்ஸ்டர்)[,\\s!]*"),
            ""
        ).trim()

        // 0. STOP SPEAKING (Always allowed immediately)
        if (cleaned == "stop" || cleaned.contains("stop speaking") || cleaned.contains("be quiet") ||
            cleaned.contains("silence") || cleaned.contains("நிறுத்து") || cleaned.contains("பேசாதே") ||
            cleaned.contains("stop pannu")
        ) {
            activity?.stopSpeakingImmediate()
            recordTaskHistory(raw, "Stop Speaking", true)
            return true
        }

        // Check Biometric Voice / Face Authorization Gate if enabled in Settings
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
            val ok = setHardwareFlashlight(true, respondInTamil)
            recordTaskHistory(raw, "Flashlight ON", ok)
            return true
        }
        if (isFlashlightOffCommand(cleaned)) {
            val ok = setHardwareFlashlight(false, respondInTamil)
            recordTaskHistory(raw, "Flashlight OFF", ok)
            return true
        }

        // 2. VOLUME CONTROL ("Volume up", "Set media volume to 50 percent", "Volume konjam increase pannu")
        val volCmd = parseVolumeCommand(cleaned)
        if (volCmd != null) {
            val (stream, action, pct) = volCmd
            val ok = controlDeviceVolume(stream, action, pct, respondInTamil)
            recordTaskHistory(raw, "Volume ($stream: $action)", ok)
            return true
        }

        // 3. BRIGHTNESS CONTROL ("Brightness up", "Set brightness to 60 percent", "Brightness kammi pannu")
        val brightPct = parseBrightnessCommand(cleaned)
        if (brightPct != null) {
            val ok = setScreenBrightness(brightPct, systemWide = true, isTamil = respondInTamil)
            recordTaskHistory(raw, "Set Brightness ($brightPct%)", ok)
            return true
        }

        // 4. TIME & DATE ("Maxter, enna time?", "What time is it?", "இப்போது மணி என்ன?")
        if (isTimeOrDateCommand(cleaned)) {
            tellCurrentTimeAndDate(respondInTamil)
            recordTaskHistory(raw, "Tell Time & Date", true)
            return true
        }

        // 5. LANGUAGE SWITCHING ("Speak in Tamil", "Switch to English", "தமிழில் பேசு")
        val newLang = parseLanguageSwitchCommand(cleaned)
        if (newLang != null) {
            prefs.edit().putString("voice_locale", newLang).apply()
            val msg = if (newLang.startsWith("ta")) {
                "சரி! இனி நான் தமிழில் பேசுகிறேன்."
            } else {
                "Sure! I will now respond in English."
            }
            speakCallback(msg, newLang, "happy")
            recordTaskHistory(raw, "Switch Language ($newLang)", true)
            return true
        }

        // 6. CHARACTER CUSTOMIZATION / SETTINGS ("Customize character", "Open character settings")
        if (cleaned.contains("customize character") || cleaned.contains("character setting") ||
            cleaned.contains("character customization") || cleaned.contains("கேரக்டர் செட்டிங்ஸ்")
        ) {
            uiEventCallback?.invoke(JSONObject().apply {
                put("type", "open_modal")
                put("modal", "settings_character")
            })
            val msg = if (respondInTamil) {
                "கேரக்டர் தனிப்பயனாக்க அமைப்புகளைத் திறக்கிறேன்."
            } else {
                "Opening Character Customization settings."
            }
            speakCallback(msg, replyLang, "happy")
            recordTaskHistory(raw, "Open Character Customization", true)
            return true
        }

        // 7. GOOGLE SEARCH BY VOICE ("Search Google for...", "Google-la Free Fire sensitivity search pannu")
        val searchPair = extractGoogleSearchQuery(cleaned)
        if (searchPair != null) {
            val (isSearchIntent, query) = searchPair
            if (isSearchIntent) {
                if (query.isBlank()) {
                    val ask = if (respondInTamil) {
                        "கூகுளில் என்ன தேட வேண்டும் என்று சொல்லுங்கள்?"
                    } else {
                        "What would you like me to search for on Google?"
                    }
                    speakCallback(ask, replyLang, "confused")
                    recordTaskHistory(raw, "Google Search (Prompted for Query)", true)
                    return true
                }
                val ok = performGoogleSearch(query, respondInTamil)
                recordTaskHistory(raw, "Google Search: $query", ok)
                return true
            }
        }

        // 8. OPEN FILES, PHOTOS, VIDEOS, PDF, DOWNLOADS ("Open my latest photo", "Indha video open pannu", "Open my PDF")
        val fileReq = parseFileOpenCommand(cleaned)
        if (fileReq != null) {
            val (category, nameFilter) = fileReq
            val ok = findAndOpenAuthorizedFile(category, nameFilter, respondInTamil)
            recordTaskHistory(raw, "Open File ($category)", ok)
            return true
        }

        // 9. NAVIGATION & APP CLOSING ("Close YouTube", "Indha app close pannu", "Go back", "Go home")
        if (isReturnToMaxterCommand(cleaned)) {
            bringMaxterJToFront(respondInTamil)
            recordTaskHistory(raw, "Return to Maxter J", true)
            return true
        }
        if (isGoHomeCommand(cleaned)) {
            navigateToHomeScreen(respondInTamil)
            recordTaskHistory(raw, "Go to Home Screen", true)
            return true
        }
        if (isGoBackCommand(cleaned)) {
            handleGoBack(respondInTamil)
            recordTaskHistory(raw, "Navigate Back", true)
            return true
        }
        val closeTarget = extractCloseAppTarget(cleaned)
        if (closeTarget != null) {
            handleCloseAppRequest(closeTarget, respondInTamil)
            recordTaskHistory(raw, "Close App ($closeTarget)", true)
            return true
        }

        // 10. OPEN INSTALLED APPS & SYSTEM SETTINGS ("Open YouTube", "YouTube open pannu", "Open Wi-Fi settings")
        val openTarget = extractOpenAppTarget(cleaned)
        if (openTarget != null) {
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
            "google-la", "google la", "youtube-la", "podu", "anai", "aakku"
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
            "torch off pannu", "light off pannu", "light anai",
            "லைட்டை ஆஃப் பண்ணு", "லைட்டை ஆப் பண்ணு", "லைட் ஆஃப்", "டார்ச் ஆஃப்", "விளக்கை அணை"
        )
        return patterns.any { cmd.contains(it) }
    }

    /**
     * Parses volume commands:
     * Returns Triple(streamName, action, percent) where action is "up", "down", "mute", "max", or "set"
     */
    private fun parseVolumeCommand(cmd: String): Triple<String, String, Int>? {
        val hasVolKeyword = cmd.contains("volume") || cmd.contains("sound") ||
                cmd.contains("audio") || cmd.contains("வால்யூம்") || cmd.contains("சத்தம்") ||
                cmd.contains("mute")
        if (!hasVolKeyword) return null

        val stream = when {
            cmd.contains("alarm") || cmd.contains("அலாரம்") -> "alarm"
            cmd.contains("notification") || cmd.contains("ring") || cmd.contains("ரிங்டோன்") -> "notification"
            cmd.contains("call") || cmd.contains("voice call") -> "call"
            else -> prefs.getString("default_volume_stream", "media") ?: "media"
        }

        // Check explicit percentage ("set media volume to 50 percent", "volume 70")
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

    /**
     * Parses brightness commands and returns target brightness percentage (5..100).
     */
    private fun parseBrightnessCommand(cmd: String): Int? {
        if (!cmd.contains("brightness") && !cmd.contains("வெளிச்சம்") && !cmd.contains("பிரைட்னஸ்")) {
            return null
        }
        val currentPct = prefs.getInt("current_brightness_pct", 65)

        val pctMatch = Regex("(\\d{1,3})\\s*(?:%|percent|சதவீதம்)?").find(cmd)
        if (pctMatch != null) {
            return (pctMatch.groupValues[1].toIntOrNull() ?: 60).coerceIn(10, 100)
        }
        if (cmd.contains("full") || cmd.contains("max") || cmd.contains("முழு")) {
            return 100
        }
        if (cmd.contains("up") || cmd.contains("increase") || cmd.contains("adhigam") ||
            cmd.contains("கூட்டு") || cmd.contains("அதிகரி")
        ) {
            return (currentPct + 20).coerceIn(10, 100)
        }
        if (cmd.contains("down") || cmd.contains("decrease") || cmd.contains("kammi") ||
            cmd.contains("low") || cmd.contains("குறை") || cmd.contains("கம்மி")
        ) {
            // Prevent accidental changes that make the screen unreadable (minimum 12%)
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
     * Extracts clean Google search query from English, Tamil, or Thanlish voice commands:
     * Examples:
     * - "Search Google for the latest Android updates" -> "the latest Android updates"
     * - "Free Fire headshot tips search pannu" -> "Free Fire headshot tips"
     * - "Google-la Infinix GT 20 Pro gaming settings search pannu" -> "Infinix GT 20 Pro gaming settings"
     */
    private fun extractGoogleSearchQuery(cmd: String): Pair<Boolean, String>? {
        if (cmd == "search" || cmd == "google search" || cmd == "தேடு" || cmd == "search pannu") {
            return Pair(true, "")
        }

        // Thanlish / Tamil suffix pattern: "[ Google-la ] <query> search pannu / தேடு"
        val suffixRegex = Regex("^(?:google-la|google\\s+la|in\\s+google|கூகுளில்|கூகுள்ல)?\\s*(.+?)\\s+(?:google\\s+search\\s+pannu|search\\s+pannu|தேடு|தேடுங்கள்|search\\s+in\\s+google)$")
        val suffixMatch = suffixRegex.find(cmd)
        if (suffixMatch != null) {
            val q = suffixMatch.groupValues[1]
                .replace(Regex("^(?:for|about)\\s+"), "")
                .trim()
            return Pair(true, q)
        }

        // Prefix pattern: "search google for <query>" or "search for <query>" or "google search <query>"
        val prefixRegex = Regex("^(?:search\\s+google\\s+for|google\\s+search\\s+for|search\\s+on\\s+google\\s+for|search\\s+for|google\\s+search|search\\s+google|search)\\s+(.+)$")
        val prefixMatch = prefixRegex.find(cmd)
        if (prefixMatch != null) {
            val q = prefixMatch.groupValues[1]
                .replace(Regex("\\s+(?:on\\s+google|in\\s+google|search\\s+pannu)$"), "")
                .trim()
            return Pair(true, q)
        }

        return null
    }

    /**
     * Parses file / media opening commands:
     * - "Open my latest photo" / "Open the photo named birthday"
     * - "Open my images"
     * - "Open the video I downloaded" / "Indha video open pannu" / "Play my video"
     * - "Open my PDF" / "Open my last downloaded file" / "Downloads folder open pannu"
     */
    private fun parseFileOpenCommand(cmd: String): Pair<String, String>? {
        // Photo named X
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
        if (cmd.contains("indha app close") || cmd.contains("close this app") || cmd == "close") {
            return lastLaunchedAppName ?: "current app"
        }
        val en = Regex("^(?:close|exit|quit)\\s+(.+?)(?:\\s+pannu)?$").find(cmd)
        if (en != null) return en.groupValues[1].trim()

        val ta = Regex("^(.+?)\\s+(?:close\\s+pannu|மூடு|க்ளோஸ் பண்ணு)$").find(cmd)
        if (ta != null) return ta.groupValues[1].trim()
        return null
    }

    private fun extractOpenAppTarget(cmd: String): String? {
        val en = Regex("^(?:open|launch|start)\\s+(.+?)(?:\\s+pannu)?$").find(cmd)
        if (en != null) return en.groupValues[1].trim()

        val ta = Regex("^(.+?)\\s+(?:open\\s+pannu|திற|ஓபன் பண்ணு)$").find(cmd)
        if (ta != null) return ta.groupValues[1].trim()
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
                if (isTamil) "சரி! Flashlight-ஐ ஆன் செய்கிறேன்." else "Flashlight is on."
            } else {
                if (isTamil) "சரி! Flashlight-ஐ அணைக்கிறேன்." else "Flashlight is off."
            }
            speakCallback(msg, lang, "happy")
            true
        } catch (e: SecurityException) {
            val msg = if (isTamil) "கேமரா அனுமதி மறுக்கப்பட்டுள்ளது." else "Camera permission is required to control the flashlight."
            speakCallback(msg, lang, "confused")
            false
        } catch (e: Exception) {
            val msg = if (isTamil) "பிளாஷ்லைட்டை இப்போது இயக்க முடியவில்லை." else "Unable to control flashlight right now."
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
            val curVol = audioManager.getStreamVolume(streamType)

            when (action) {
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    val msg = if (isTamil) "சரி! மீடியா வால்யூமை அதிகரிக்கிறேன்." else "Increased $streamName volume."
                    speakCallback(msg, lang, "happy")
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    val msg = if (isTamil) "சரி! வால்யூமை குறைக்கிறேன்." else "Decreased $streamName volume."
                    speakCallback(msg, lang, "happy")
                }
                "mute" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        audioManager.setStreamVolume(streamType, 0, AudioManager.FLAG_SHOW_UI)
                    }
                    val msg = if (isTamil) "சரி! ஆடியோ மியூட் செய்யப்பட்டது." else "Muted $streamName volume."
                    speakCallback(msg, lang, "happy")
                }
                else -> {
                    val targetIndex = Math.round((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
                    val msg = if (isTamil) {
                        "சரி! வால்யூம் $percent சதவீதமாக மாற்றப்பட்டது."
                    } else {
                        "Set $streamName volume to $percent percent."
                    }
                    speakCallback(msg, lang, "happy")
                }
            }
            true
        } catch (sec: SecurityException) {
            val msg = if (isTamil) {
                "அமைதி (Do Not Disturb) பயன்முறையில் வால்யூமை மாற்ற அனுமதி இல்லை."
            } else {
                "Cannot change that volume stream while Do Not Disturb mode is active."
            }
            speakCallback(msg, lang, "confused")
            false
        } catch (e: Exception) {
            false
        }
    }

    fun setScreenBrightness(percent: Int, systemWide: Boolean, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val safePercent = percent.coerceIn(12, 100) // Prevent unreadable dark screen
        prefs.edit().putInt("current_brightness_pct", safePercent).apply()

        // 1. Always apply in-app window brightness immediately when MainActivity is active
        activity?.runOnUiThread {
            val lp = activity.window.attributes
            lp.screenBrightness = safePercent / 100f
            activity.window.attributes = lp
        }

        // 2. Check if system-wide WRITE_SETTINGS is granted
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
                    val msg = if (isTamil) {
                        "சரி! திரை வெளிச்சம் $safePercent சதவீதமாக மாற்றப்பட்டது."
                    } else {
                        "Screen brightness set to $safePercent percent."
                    }
                    speakCallback(msg, lang, "happy")
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                val msg = if (isTamil) {
                    "செயலிக்குள் வெளிச்சம் $safePercent% ஆக மாற்றப்பட்டது. போன் முழுவதும் மாற்ற 'Modify system settings' அனுமதியை வழங்கவும்."
                } else {
                    "In-app brightness set to $safePercent%. To change system-wide brightness, please grant Modify System Settings permission."
                }
                speakCallback(msg, lang, "talking")
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

        val msg = if (isTamil) "திரை வெளிச்சம் $safePercent% ஆக மாற்றப்பட்டது." else "Brightness set to $safePercent percent."
        speakCallback(msg, lang, "happy")
        return true
    }

    fun performGoogleSearch(rawQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.isEmpty()) return false

        return try {
            val encoded = Uri.encode(cleanQuery)
            val searchUri = Uri.parse("https://www.google.com/search?q=$encoded")
            val viewIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
            val msg = if (isTamil) {
                "சரி! Google-ல் $cleanQuery தேடுகிறேன்."
            } else {
                "Searching Google for $cleanQuery."
            }
            speakCallback(msg, lang, "happy")
            true
        } catch (e: Exception) {
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
                                val msg = if (isTamil) "உங்கள் புகைப்படத்தைத் திறக்கிறேன்." else "Opening your photo $name."
                                speakCallback(msg, lang, "happy")
                                return openContentUri(uri, "image/*")
                            }
                        }
                    }
                    // Fallback to Gallery / Photos picker if no matching photo or permission needed
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
                            val msg = if (isTamil) "உங்கள் வீடியோவைத் திறக்கிறேன்." else "Playing your video $name."
                            speakCallback(msg, lang, "happy")
                            return openContentUri(uri, "video/*")
                        }
                    }
                    activity?.launchSafDocumentPicker("video/*")
                    speakCallback(if (isTamil) "உங்கள் வீடியோவைத் தேர்ந்தெடுக்கவும்." else "Please select the video to play.", lang, "talking")
                    return true
                }

                "pdf", "document" -> {
                    activity?.launchSafDocumentPicker("application/pdf")
                    val msg = if (isTamil) {
                        "பாதுகாப்பான கோப்புத் தேர்வியைத் திறக்கிறேன். உங்கள் PDF கோப்பைத் தேர்ந்தெடுக்கவும்."
                    } else {
                        "Opening Android document picker so you can select your PDF file."
                    }
                    speakCallback(msg, lang, "talking")
                    return true
                }

                "downloads" -> {
                    val dmIntent = Intent(DownloadManager_ACTION_VIEW_DOWNLOADS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dmIntent)
                    val msg = if (isTamil) "சரி! Downloads பகுதியைத் திறக்கிறேன்." else "Opening your Downloads folder."
                    speakCallback(msg, lang, "happy")
                    return true
                }
            }
        } catch (e: Exception) {
            activity?.launchSafDocumentPicker("*/*")
            return false
        }
        return false
    }

    private val DownloadManager_ACTION_VIEW_DOWNLOADS = "android.intent.action.VIEW_DOWNLOADS"

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

    fun openApplicationByName(spokenQuery: String, isTamil: Boolean): Boolean {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        val cleanQuery = spokenQuery
            .replace(Regex("^(the|app|application)\\s+"), "")
            .replace(Regex("\\s+(app|application|செயலி|pannu)$"), "")
            .trim()
            .lowercase(Locale.ROOT)

        if (cleanQuery.isEmpty()) return false

        // Supported Android Settings pages
        if (cleanQuery.contains("wifi") || cleanQuery.contains("wi-fi")) {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speakCallback(if (isTamil) "வைஃபை அமைப்புகளைத் திறக்கிறேன்." else "Opening Wi-Fi settings.", lang, "happy")
            return true
        }
        if (cleanQuery.contains("bluetooth")) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speakCallback(if (isTamil) "புளூடூத் அமைப்புகளைத் திறக்கிறேன்." else "Opening Bluetooth settings.", lang, "happy")
            return true
        }
        if (cleanQuery == "settings" || cleanQuery == "செட்டிங்ஸ்" || cleanQuery == "அமைப்புகள்") {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            speakCallback(if (isTamil) "செட்டிங்ஸ் திறக்கப்படுகிறது." else "Opening Android Settings.", lang, "happy")
            return true
        }

        val aliasMap = mapOf(
            "யூடியூப்" to "youtube", "utube" to "youtube", "you tube" to "youtube",
            "வாட்ஸ்அப்" to "whatsapp", "வாட்சப்" to "whatsapp", "whats app" to "whatsapp",
            "இன்ஸ்டாகிராம்" to "instagram", "இன்ஸ்டா" to "instagram", "insta" to "instagram",
            "குரோம்" to "chrome", "google chrome" to "chrome",
            "கூகுள்" to "google",
            "கால்குலேட்டர்" to "calculator", "calc" to "calculator"
        )
        val canonical = aliasMap[cleanQuery] ?: cleanQuery

        val knownPackages = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram" to listOf("com.instagram.android"),
            "chrome" to listOf("com.android.chrome"),
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
            "calculator" to listOf("com.transsion.calculator", "com.google.android.calculator", "com.android.calculator2")
        )

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

        val installedApps = resolveList.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            InstalledAppInfo(label, label.lowercase(Locale.ROOT), pkg)
        }.distinctBy { it.packageName }

        val exactMatches = installedApps.filter { it.normalizedLabel == canonical || it.normalizedLabel == cleanQuery }
        if (exactMatches.size == 1) {
            return launchPackage(exactMatches[0].packageName, exactMatches[0].label, isTamil)
        }

        knownPackages[canonical]?.forEach { pkg ->
            val match = installedApps.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
            if (match != null) {
                return launchPackage(match.packageName, match.label, isTamil)
            }
        }

        val partial = installedApps.filter {
            it.normalizedLabel.contains(canonical) || canonical.contains(it.normalizedLabel)
        }

        return when {
            partial.isEmpty() -> {
                val msg = if (isTamil) {
                    "அந்த செயலி உங்கள் போனில் நிறுவப்படவில்லை."
                } else {
                    "That application is not installed on your phone."
                }
                speakCallback(msg, lang, "confused")
                false
            }
            partial.size == 1 -> launchPackage(partial[0].packageName, partial[0].label, isTamil)
            else -> {
                val names = partial.take(3).joinToString(", ") { it.label }
                val msg = if (isTamil) {
                    "ஒன்றுக்கும் மேற்பட்ட செயலிகள் உள்ளன: $names. எதைத் திறக்க வேண்டும்?"
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
            val msg = if (isTamil) "சரி! $label திறக்கிறேன்." else "Opening $label."
            speakCallback(msg, lang, "happy")
            true
        } catch (e: Exception) {
            speakCallback(if (isTamil) "$label திறக்க முடியவில்லை." else "Failed to open $label.", lang, "confused")
            false
        }
    }

    private fun handleCloseAppRequest(appName: String, isTamil: Boolean) {
        val lang = if (isTamil) "ta-IN" else "en-IN"
        bringMaxterJToFront(isTamil = false)
        val msg = if (isTamil) {
            "ஆண்ட்ராய்டு பாதுகாப்பு விதிகளின்படி மற்ற செயலிகளை நேரடியாகக் கட்டாயமாக மூட முடியாது. நான் உங்களை மீண்டும் Maxter J திரைக்கு அழைத்து வந்துள்ளேன்."
        } else {
            "Android does not allow ordinary apps to force-close $appName directly, so I have returned you to Maxter J."
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