package com.phudy.redline.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.phudy.redline.MainActivity
import com.phudy.redline.RedlineApp
import kotlinx.coroutines.*

/** Keeps downloads alive while the app is in the background, with a progress notification. */
class DownloadService : Service() {
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return START_NOT_STICKY }
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(2, n)
        if (ticker == null) ticker = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                getSystemService(android.app.NotificationManager::class.java).notify(2, build())
                delay(1000)
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val running = Downloader.tasks.value.filter { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
        val p = running.firstOrNull()?.progress ?: 0f
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, RedlineApp.CHANNEL_DL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (running.isEmpty()) "ดาวน์โหลดเสร็จแล้ว" else "กำลังดาวน์โหลด ${running.size} คลิป")
            .setContentText(running.firstOrNull()?.title ?: "บันทึกไว้ที่ Movies/REDLINE")
            .setProgress(100, (p.coerceAtLeast(0f) * 100).toInt(), p < 0)
            .setContentIntent(pi).setOngoing(running.isNotEmpty()).setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() { ticker?.cancel(); super.onDestroy() }

    companion object {
        private const val ACTION_STOP = "stop"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_STOP))
    }
}
