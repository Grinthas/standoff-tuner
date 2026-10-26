package com.grinthas.standofftuner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Следит за игрой и возвращает настройки экрана, когда она закрывается.
 * Уведомление с кнопкой «Вернуть» позволяет откатить всё вручную.
 */
class WatchService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var tweaks: Tweaks
    private var seenGame = false
    private var missing = 0
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tweaks = Tweaks(this)
        thread = HandlerThread("watch").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent?.action == ACTION_RESTORE) {
            handler.post { finish() }
            return START_NOT_STICKY
        }
        seenGame = false
        missing = 0
        startedAt = System.currentTimeMillis()
        handler.removeCallbacksAndMessages(null)
        handler.post(::check)
        return START_NOT_STICKY
    }

    private fun check() {
        val running = runCatching { tweaks.gameRunning() }.getOrDefault(false)
        if (running) {
            seenGame = true
            missing = 0
        } else if (seenGame) {
            missing++
        }
        val neverStarted = !seenGame && System.currentTimeMillis() - startedAt > 90_000
        if (missing >= 2 || neverStarted) finish() else handler.postDelayed(::check, 3_000)
    }

    private fun finish() {
        runCatching { tweaks.restore() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Слежение за игрой", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val restore = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Standoff Tuner активен")
            .setContentText("Настройки вернутся после выхода из игры")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify), "Вернуть сейчас", restore).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        const val ACTION_RESTORE = "com.grinthas.standofftuner.RESTORE"
        private const val CHANNEL = "watch"
        private const val ID = 1
    }
}
