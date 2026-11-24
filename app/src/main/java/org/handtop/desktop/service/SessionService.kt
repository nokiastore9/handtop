package org.handtop.desktop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import org.handtop.desktop.DesktopActivity
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.linux.GpuDrivers
import org.handtop.desktop.linux.LinuxInstaller
import org.handtop.desktop.term.TermSessions

/**
 * Foreground service that keeps the app process — and with it the shell and Linux
 * processes — alive while terminals are open or Linux is being installed.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        if (!isNeeded()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DesktopActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            LinuxInstaller.isRunning -> getString(R.string.notif_installing)
            GpuDrivers.isRunning -> getString(R.string.notif_gpu_installing)
            else -> getString(R.string.notif_sessions, TermSessions.hosts.size)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SessionService"

        private fun isNeeded() =
            TermSessions.hosts.isNotEmpty() || LinuxInstaller.isRunning || GpuDrivers.isRunning ||
                LinuxApps.running.isNotEmpty()

        /** Start, refresh or stop the service to match the current sessions / installs. */
        fun update(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            try {
                if (isNeeded()) context.startForegroundService(intent) else context.stopService(intent)
            } catch (e: IllegalStateException) {
                // Starting a foreground service from the background is refused (Android 12+);
                // the next change made while HandTop is in front will start it.
                Log.w(TAG, "Cannot update foreground service now", e)
            }
        }
    }
}
