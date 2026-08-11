package com.example.esp32alarm

import android.app.Service
import android.content.Intent
import android.os.IBinder

class BleAlarmService : Service() {

    private lateinit var bleManager: BleManager

    override fun onCreate() {
        super.onCreate()
        // Menggunakan 1 parameter (this)
        bleManager = BleManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
    }
}
