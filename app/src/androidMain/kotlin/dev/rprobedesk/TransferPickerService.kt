package dev.rprobedesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Android can close a cached app's TCP sockets while DocumentsUI is in front of it. */
class TransferPickerService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = "device-file-transfer"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,tr("设备文件传输"), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(uiMessage("RProbeDesk 文件传输"))
            .setContentText(uiMessage("正在选择文件或保存位置"))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf(startId) }
}
