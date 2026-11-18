package com.mojtaba.simcallrecorder.recording.shizuku

import android.annotation.SuppressLint

/**
 * Shizuku instantiates a UserService with the app's Context. On some Android builds that leaves an
 * ActivityThread application object associated with the app package while the process UID is 2000.
 * AudioRecord attribution can then become the invalid pair (shell UID, app package), which
 * AudioFlinger may reject. Clear only that process-local initial-application reference so framework
 * attribution resolves the actual shell/root identity. If Android changes this internal field or
 * access is denied, return false and refuse capture rather than guessing an attribution identity.
 *
 * This is an isolated compatibility boundary: no general hidden-API access, shell command execution,
 * or arbitrary attribution source can be supplied by the app.
 */
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
internal object ShizukuShellAttribution {
    fun detachFromApplication(): Boolean = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
            ?: return true // A bare app_process host already has no application object.

        val initialApplication = activityThreadClass
            .getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }

        initialApplication.set(activityThread, null)
        initialApplication.get(activityThread) == null
    }.getOrDefault(false)
}
