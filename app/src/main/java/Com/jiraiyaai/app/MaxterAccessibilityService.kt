// File Path in Repo: app/src/main/java/com/jiraiyaai/app/MaxterAccessibilityService.kt
package com.jiraiyaai.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * MaxterAccessibilityService — Legitimate Optional Android AccessibilityService
 *
 * Used strictly when explicitly enabled by the user to execute global navigation commands:
 * - "Go back" -> GLOBAL_ACTION_BACK
 * - "Go to home" / "Close current app" -> GLOBAL_ACTION_HOME
 * - "Open recent apps" -> GLOBAL_ACTION_RECENTS
 *
 * Privacy & Security Guarantee:
 * - `canRetrieveWindowContent="false"` in `accessibility_service_config.xml`
 * - Never inspects, logs, or transmits screen text or user inputs.
 * - Never attempts deceptive force-stops or unauthorized clicks.
 */
class MaxterAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MaxterAccessibilityService? = null
            private set

        fun isServiceEnabled(): Boolean = instance != null

        fun performNavigationAction(actionType: String): Boolean {
            val svc = instance ?: return false
            return try {
                when (actionType.lowercase()) {
                    "back" -> svc.performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> svc.performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents", "recent_apps" -> svc.performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    else -> false
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MainActivity.activeInstance?.syncOverlayStateToWebView()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: Maxter J does not read or monitor screen content.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        MainActivity.activeInstance?.syncOverlayStateToWebView()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
