package com.example.esp32alarm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogHelper {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(dir, "esp_alarm_log.txt")
    }

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message\n"
        logFile?.appendText(line)
        android.util.Log.d("ESPAlarm", message)
    }
}