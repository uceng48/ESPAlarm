package com.example.esp32alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BleAlarmService : Service() {

    private lateinit var bleManager: BleManager
    private lateinit var preferences: PreferencesHelper
    private lateinit var notificationHelper: NotificationHelper

    override fun onCreate() {
        super.onCreate()

        // Inisialisasi helper
        preferences = PreferencesHelper(this)
        notificationHelper = NotificationHelper(this)

        // Buat BleManager yang akan berjalan di background
        bleManager = BleManager(this, preferences, notificationHelper)

        // Jika ada MAC terakhir, mulai scan agar auto-reconnect berjalan
        val lastMac = preferences.getLastMac()
        if (lastMac != null && preferences.getAutoReconnect()) {
            bleManager.startScan()
        }

        // Jalankan sebagai Foreground Service (agar tidak dihentikan sistem)
        startForeground(NOTIFICATION_ID, createNotification())

        LogHelper.log("BleAlarmService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: jika service mati, sistem akan mencoba menghidupkan ulang
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
        LogHelper.log("BleAlarmService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────
    // Notifikasi untuk Foreground Service
    // ──────────────────────────────────────────────
    private fun createNotification(): Notification {
        val channelId = "esp_alarm_service_channel"
        val channelName = "ESP32 Alarm Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ESP32 Alarm")
            .setContentText("Memantau koneksi...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
    }
}