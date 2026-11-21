package com.mojtaba.simcallrecorder.recording

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * Opt-in system accessibility component used only to make the Android accessibility audio-input
 * policy available to the explicitly selected capture strategy. It does not inspect UI text,
 * clicks, passwords, notifications, or window content.
 */
internal class CallAudioAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        enabledInProcess = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        enabledInProcess = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var enabledInProcess: Boolean = false

        fun isEnabled(context: Context): Boolean {
            if (enabledInProcess) return true
            val expected = ComponentName(context, CallAudioAccessibilityService::class.java)
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(services)
            while (splitter.hasNext()) {
                if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
            }
            return false
        }
    }
}
