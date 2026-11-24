package org.handtop.desktop.notify

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.handtop.desktop.service.NotificationsService

/**
 * The phone's current notifications as HandTop shows them (taskbar + notification center).
 * Filled by [NotificationsService] while the user has granted notification access; main
 * thread only. Nothing is stored beyond what Android currently shows.
 */
object Notifications {

    class Item(
        val key: String,
        val packageName: String,
        val appName: CharSequence,
        val postTime: Long,
        val title: CharSequence?,
        val text: CharSequence?,
        val smallIcon: Icon?,
        val color: Int,
        val clearable: Boolean,
        val contentIntent: PendingIntent?,
        val autoCancel: Boolean,
    )

    /** Newest first. */
    var items: List<Item> = emptyList()
        private set

    private var service: NotificationListenerService? = null
    private val listeners = ArrayList<() -> Unit>()
    private val appNames = HashMap<String, CharSequence>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun component(context: Context) = ComponentName(context, NotificationsService::class.java)

    /** Android binds the listener itself; after an app update it sometimes needs a nudge. */
    fun ensureConnected(context: Context) {
        if (service != null || !isAccessGranted(context)) return
        try {
            NotificationListenerService.requestRebind(component(context))
        } catch (_: RuntimeException) {
        }
    }

    /** Android's page where the user allows HandTop to read notifications. */
    fun accessSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component(context).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    // ---- from the service ----

    internal fun connected(service: NotificationListenerService) {
        this.service = service
    }

    internal fun disconnected(service: NotificationListenerService) {
        if (this.service !== service) return
        this.service = null
        items = emptyList()
        notifyChanged()
    }

    internal fun update(context: Context, active: Array<StatusBarNotification>) {
        // A group summary repeats its children: keep it only when it stands alone.
        val groupsWithChildren = active.filter { it.isGroup && !it.isSummary() }.map { it.groupKey }.toSet()
        items = active.asSequence()
            .filter { it.packageName != context.packageName } // our own "sessions running" note
            .filter { !(it.isSummary() && it.groupKey in groupsWithChildren) }
            .mapNotNull { it.toItem(context) }
            .sortedByDescending { it.postTime }
            .toList()
        notifyChanged()
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    private fun StatusBarNotification.isSummary() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun StatusBarNotification.toItem(context: Context): Item? {
        val n = notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        return Item(
            key = key,
            packageName = packageName,
            appName = appName(context, this),
            postTime = postTime,
            title = title,
            text = text,
            smallIcon = n.smallIcon,
            color = n.color,
            clearable = isClearable,
            contentIntent = n.contentIntent,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    private fun appName(context: Context, sbn: StatusBarNotification): CharSequence {
        // System notifications may name another app ("android.substName").
        sbn.notification.extras.getCharSequence("android.substName")?.let { return it }
        return appNames.getOrPut(sbn.packageName) {
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0))
            } catch (_: PackageManager.NameNotFoundException) {
                sbn.packageName
            }
        }
    }

    // ---- actions ----

    fun dismiss(item: Item) {
        try {
            service?.cancelNotification(item.key)
        } catch (_: RuntimeException) {
        }
    }

    fun clearAll() {
        try {
            service?.cancelAllNotifications()
        } catch (_: RuntimeException) {
        }
    }

    /** Opens what the notification points to, on the same screen as HandTop (phone or DeX). */
    fun open(activity: Activity, item: Item) {
        val intent = item.contentIntent ?: return
        val options = ActivityOptions.makeBasic()
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        display?.let { options.launchDisplayId = it.displayId }
        // Android 14+: a PendingIntent only starts an activity with the sender's say-so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        try {
            intent.send(activity, 0, null, null, null, null, options.toBundle())
        } catch (_: PendingIntent.CanceledException) {
        }
        if (item.autoCancel) dismiss(item)
    }
}
