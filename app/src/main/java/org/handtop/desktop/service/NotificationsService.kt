package org.handtop.desktop.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.handtop.desktop.notify.Notifications

/**
 * Receives the phone's notifications for the taskbar and the notification center (HandTop runs
 * full screen, so Android's status bar is hidden). Enabled by the user in Android's
 * "Notification access" settings; it only mirrors what Android shows and keeps nothing.
 */
class NotificationsService : NotificationListenerService() {

    override fun onListenerConnected() {
        Notifications.connected(this)
        refresh()
    }

    override fun onListenerDisconnected() {
        Notifications.disconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null // not connected (any more)
        }
        Notifications.update(this, active ?: emptyArray())
    }
}
