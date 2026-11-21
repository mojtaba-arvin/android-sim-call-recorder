package com.mojtaba.simcallrecorder.recording

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional scenario detector. It stores only the package name/UID of an ongoing CATEGORY_CALL
 * notification; it never reads notification title/text/actions or attempts to capture notification
 * sounds. A notification is a hint that a VoIP call may be active, not proof of audio routing.
 */
internal class VoipCallNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.isOngoing && sbn.notification.category == Notification.CATEGORY_CALL) {
            val uid = runCatching {
                packageManager.getApplicationInfo(sbn.packageName, 0).uid
            }.getOrNull() ?: return
            VoipCallNotificationRegistry.update(sbn.packageName, uid)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) VoipCallNotificationRegistry.clear(sbn.packageName)
    }
}

internal object VoipCallNotificationRegistry {
    @Volatile var packageName: String? = null
        private set
    @Volatile var packageUid: Int? = null
        private set
    @Volatile var lastSeenAtMillis: Long? = null
        private set

    @Synchronized
    fun update(name: String, uid: Int) {
        packageName = name
        packageUid = uid
        lastSeenAtMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun clear(name: String) {
        if (packageName == name) {
            packageName = null
            packageUid = null
            lastSeenAtMillis = null
        }
    }
}
