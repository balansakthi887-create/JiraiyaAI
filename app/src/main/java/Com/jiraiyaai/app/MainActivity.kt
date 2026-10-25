package com.jiraiyaai.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import org.json.JSONObject
import java.net.URLEncoder

class MaxterVoiceCommandController(
    private val context: Context,
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val speakCallback: (String, String, String) -> Unit,
    private val uiEventCallback: (JSONObject) -> Unit
) {

    fun processVoiceCommand(rawInput: String, isFromVerifiedSession: Boolean) {
        var cleaned = rawInput.trim().lowercase()

        // 1. Wake word cleanup (Fixed Regex)
        cleaned = cleaned.replace(
            Regex("""^(hey\s+|hi\s+|hello\s+|ok\s+|okay\s+)?(maxter|jiraiya)[\s,:]*""", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        if (cleaned.isEmpty()) return

        // 2. Volume percentage (Fixed Regex)
        val volMatch = Regex("""(\d{1,3})\s*(?:%|percent|சதவீதம்)?""").find(cleaned)
        if (cleaned.contains("volume") || cleaned.contains("sound") || cleaned.contains("சத்தம்")) {
            val pct = volMatch?.groupValues?.get(1)?.toIntOrNull()
            if (pct != null) {
                setVolumePercentage(pct)
                return
            }
        }

        // 3. Brightness percentage (Fixed Regex)
        val brightMatch = Regex("""(\d{1,3})\s*(?:%|percent|சதவீதம்)?""").find(cleaned)
        if (cleaned.contains("brightness") || cleaned.contains("வெளிச்சம்")) {
            val pct = brightMatch?.groupValues?.get(1)?.toIntOrNull()
            if (pct != null) {
                setBrightnessPercentage(pct)
                return
            }
        }

        // Torch / Flashlight
        if (cleaned.contains("torch on") || cleaned.contains("flashlight on") || cleaned.contains("லைட் போடு")) {
            setTorch(true)
            speakCallback("Torch turned on", "en-IN", "happy")
            return
        }
        if (cleaned.contains("torch off") || cleaned.contains("flashlight off") || cleaned.contains("லைட் ஆப்")) {
            setTorch(false)
            speakCallback("Torch turned off", "en-IN", "neutral")
            return
        }

        // 4. Google Search — Fixed Regexes
        if (cleaned.startsWith("search") || cleaned.startsWith("google") || cleaned.contains("தேடு")) {
            var query = cleaned
            val suffixRegex = Regex("""(?:\s+google[- ]?la|\s+in\s+google|\s+google\s+search)$""", RegexOption.IGNORE_CASE)
            val prefixRegex = Regex("""^(?:search\s+google\s+for|google\s+search\s+for|search\s+for|google|search)\s*""", RegexOption.IGNORE_CASE)

            query = query.replace(prefixRegex, "")
                         .replace(suffixRegex, "")
                         .replace(Regex("""^(?:for|about)\s+"""), "")
                         .replace(Regex("""\s+(?:on\s+google|in\s+google|search\s+pannu)$"""), "")
                         .trim()

            if (query.isNotEmpty()) {
                performGoogleSearch(query)
                return
            }
        }

        // 5. Photo / Media retrieval (Fixed Regex)
        val namedPhotoRegex = Regex("""(?:photo|image|picture)\s+named\s+(.+)""", RegexOption.IGNORE_CASE)
        val photoMatch = namedPhotoRegex.find(cleaned)
        if (photoMatch != null) {
            val imageName = photoMatch.groupValues[1].trim()
            findAndOpenImage(imageName)
            return
        }

        // 6. Close-app commands (Fixed Regexes)
        val closeEnRegex = Regex("""^(?:close|exit|quit)\s+(.+?)(?:\s+pannu)?$""", RegexOption.IGNORE_CASE)
        val closeTaRegex = Regex("""^(.+?)\s+(?:close\s+pannu|மூடு|க்ளோஸ்\s+பண்ணு)$""", RegexOption.IGNORE_CASE)
        val closeTarget = closeEnRegex.find(cleaned)?.groupValues?.get(1)
            ?: closeTaRegex.find(cleaned)?.groupValues?.get(1)

        if (!closeTarget.isNullOrBlank()) {
            val appTarget = cleanAppName(closeTarget)
            activity.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            speakCallback("Minimizing $appTarget", "en-IN", "neutral")
            return
        }

        // 7. Open-app commands (Fixed Regexes)
        val openEnRegex = Regex("""^(?:open|launch|start)\s+(.+?)(?:\s+pannu)?$""", RegexOption.IGNORE_CASE)
        val openTaRegex = Regex("""^(.+?)\s+(?:open\s+pannu|திற|ஓபன்\s+பண்ணு)$""", RegexOption.IGNORE_CASE)
        val openTarget = openEnRegex.find(cleaned)?.groupValues?.get(1)
            ?: openTaRegex.find(cleaned)?.groupValues?.get(1)

        if (!openTarget.isNullOrBlank()) {
            val appTarget = cleanAppName(openTarget)
            launchAppByName(appTarget)
            return
        }

        speakCallback("Processing request: $cleaned", "en-IN", "thinking")
    }

    // 8. App name cleanup (Fixed Regex)
    private fun cleanAppName(name: String): String {
        return name.replace(Regex("""^(the\s+app|application)\s+""", RegexOption.IGNORE_CASE), "")
                   .replace(Regex("""\s+(app|application|செயலி|pannu)$""", RegexOption.IGNORE_CASE), "")
                   .trim()
    }

    private fun setVolumePercentage(percent: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((percent.coerceIn(0, 100) / 100.0f) * maxVol).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        speakCallback("Volume set to $percent percent", "en-IN", "neutral")
    }

    private fun setBrightnessPercentage(percent: Int) {
        val bounded = percent.coerceIn(0, 100)
        activity.runOnUiThread {
            val lp = activity.window.attributes
            lp.screenBrightness = bounded / 100.0f
            activity.window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
            val sysValue = (bounded * 255) / 100
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, sysValue)
        }
        speakCallback("Brightness adjusted to $bounded percent", "en-IN", "neutral")
    }

    private fun setTorch(state: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: "0"
            cameraManager.setTorchMode(cameraId, state)
        } catch (_: Exception) {}
    }

    private fun performGoogleSearch(query: String) {
        speakCallback("Searching Google for $query", "en-IN", "talking")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private fun launchAppByName(query: String) {
        val pm = context.packageManager
        val launchIntent = pm.getInstalledApplications(PackageManager.GET_META_DATA).firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            label == query || label.contains(query)
        }?.let { pm.getLaunchIntentForPackage(it.packageName) }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            speakCallback("Opening $query", "en-IN", "happy")
        } else {
            speakCallback("Could not find app $query", "en-IN", "confused")
        }
    }

    private fun findAndOpenImage(imageName: String) {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$imageName%")

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                openContentUri(contentUri, "image/*")
                speakCallback("Opening $imageName", "en-IN", "happy")
                return
            }
        }
        speakCallback("Photo not found", "en-IN", "confused")
    }

    fun openContentUri(uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
