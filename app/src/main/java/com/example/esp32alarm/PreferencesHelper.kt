package com.example.esp32alarm

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.esp32alarm.model.DeviceItem

class PreferencesHelper(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("esp_alarm_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_LAST_MAC = "last_mac"
        private const val KEY_DEVICE_LIST = "device_list"
        private const val KEY_ALARM_DURATION = "alarm_duration"
        private const val KEY_ALARM_SOUND = "alarm_sound"
        private const val KEY_ALARM_VIBRATE = "alarm_vibrate"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    }

    fun getLastMac(): String? = prefs.getString(KEY_LAST_MAC, null)
    fun setLastMac(mac: String?) = prefs.edit().putString(KEY_LAST_MAC, mac).apply()

    fun getDeviceList(): MutableList<DeviceItem> {
        val json = prefs.getString(KEY_DEVICE_LIST, "[]")
        val type = object : TypeToken<MutableList<DeviceItem>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    fun saveDeviceList(list: List<DeviceItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_DEVICE_LIST, json).apply()
    }

    fun addOrUpdateDevice(device: DeviceItem) {
        val list = getDeviceList().toMutableList()
        val index = list.indexOfFirst { it.address == device.address }
        if (index >= 0) list[index] = device
        else list.add(device)
        saveDeviceList(list)
    }

    fun getAlarmDuration(): Int = prefs.getInt(KEY_ALARM_DURATION, 10)
    fun setAlarmDuration(seconds: Int) = prefs.edit().putInt(KEY_ALARM_DURATION, seconds).apply()

    fun getAlarmSound(): String = prefs.getString(KEY_ALARM_SOUND, "siren") ?: "siren"
    fun setAlarmSound(sound: String) = prefs.edit().putString(KEY_ALARM_SOUND, sound).apply()

    fun getAlarmVibrate(): Boolean = prefs.getBoolean(KEY_ALARM_VIBRATE, true)
    fun setAlarmVibrate(enabled: Boolean) = prefs.edit().putBoolean(KEY_ALARM_VIBRATE, enabled).apply()

    fun getAutoReconnect(): Boolean = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    fun setAutoReconnect(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
}