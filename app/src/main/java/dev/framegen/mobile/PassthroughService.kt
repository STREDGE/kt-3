package dev.framegen.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Exists for one reason: a window added by an accessibility service is "trusted", so Android 12+
 * lets touches pass through it at full opacity. The service asks for no events and reads nothing.
 */
class PassthroughService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PassthroughService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
