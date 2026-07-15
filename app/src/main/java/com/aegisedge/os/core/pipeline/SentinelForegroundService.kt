package com.aegisedge.os.core.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.aegisedge.os.modes.OperationalMode

/**
 * Keeps the sentinel pipeline alive with the screen off — camera+microphone
 * foreground service, the Android-sanctioned way to run a 24/7 edge guardian.
 */
class SentinelForegroundService : Service() {

    companion object {
        const val EXTRA_MODE = "aegis.mode"
        private const val CHANNEL_ID = "sense_sentinel"
        private const val NOTIF_ID = 0xAE61

        var activePipeline: SensePipeline? = null
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)
            ?.let { OperationalMode.valueOf(it) } ?: OperationalMode.SCHOOL_SECURITY
        val videoUriStr = intent?.getStringExtra("video_uri")
        val videoUri = videoUriStr?.let { android.net.Uri.parse(it) }

        startInForeground(mode)

        activePipeline?.stop()
        activePipeline = SensePipeline(applicationContext, mode, videoUri).also { it.start() }
        return START_STICKY
    }

    private fun startInForeground(mode: OperationalMode) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "S.E.N.S.E. Sentinel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("S.E.N.S.E. active — ${mode.ruleSet.displayName}")
            .setContentText("All processing on-device. RAM buffer rolling; nothing stored.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        activePipeline?.stop()
        activePipeline = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
