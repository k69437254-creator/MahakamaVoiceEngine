package com.aram.voiceengine.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder

class IshtarPlaybackService : Service() {

    private var player: MediaPlayer? = null
    private var currentUri: String? = null
    private var prepared = false

    companion object {
        const val ACTION_PLAY = "com.aram.voiceengine.app.action.PLAY"
        const val ACTION_TOGGLE = "com.aram.voiceengine.app.action.TOGGLE"
        const val ACTION_STOP = "com.aram.voiceengine.app.action.STOP"
        const val EXTRA_URI = "com.aram.voiceengine.app.extra.URI"
        private const val CHANNEL_ID = "ishtar_playback"
        private const val NOTIFICATION_ID = 8417
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val value = intent.getStringExtra(EXTRA_URI)
                if (!value.isNullOrBlank()) startPlayback(value)
                else stopPlayback()
            }
            ACTION_TOGGLE -> togglePlayback()
            ACTION_STOP -> stopPlayback()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(value: String) {
        stopPlayerOnly()
        currentUri = value
        prepared = false
        startForeground(NOTIFICATION_ID, buildNotification("يتم تجهيز الملف الصوتي", false))

        try {
            val next = MediaPlayer()
            player = next
            next.setWakeMode(applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            next.setOnPreparedListener { media ->
                prepared = true
                media.start()
                updateNotification()
            }
            next.setOnCompletionListener { stopPlayback() }
            next.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            next.setDataSource(this, Uri.parse(value))
            next.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun togglePlayback() {
        val active = player ?: return
        if (!prepared) return
        try {
            if (active.isPlaying) active.pause() else active.start()
            updateNotification()
        } catch (_: IllegalStateException) {
            stopPlayback()
        }
    }

    private fun stopPlayerOnly() {
        val active = player
        player = null
        prepared = false
        if (active != null) {
            try { if (active.isPlaying) active.stop() } catch (_: Exception) { }
            try { active.reset() } catch (_: Exception) { }
            try { active.release() } catch (_: Exception) { }
        }
    }

    private fun stopPlayback() {
        stopPlayerOnly()
        currentUri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(
            if (player?.isPlaying == true) "يعمل الصوت في الخلفية" else "التشغيل متوقف مؤقتًا",
            player?.isPlaying == true
        ))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تشغيل عشتار الصوتي",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار دائم للتحكم بالتشغيل الصوتي في الخلفية"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun servicePending(action: String): PendingIntent {
        val intent = Intent(this, IshtarPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(message: String, playing: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val contentIntent = PendingIntent.getActivity(
                this,
                8418,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(contentIntent)
        }

        val toggleIcon = if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        val toggleTitle = if (playing) "إيقاف مؤقت" else "استئناف"

        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ISHTAR · عشتار")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, toggleIcon),
                toggleTitle,
                servicePending(ACTION_TOGGLE)
            ).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "إيقاف",
                servicePending(ACTION_STOP)
            ).build())

        return builder.build()
    }

    override fun onDestroy() {
        stopPlayerOnly()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
