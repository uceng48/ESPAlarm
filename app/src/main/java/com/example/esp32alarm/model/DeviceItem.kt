package com.example.esp32alarm.model

data class DeviceItem(
    val name: String?,
    val address: String,
    var rssi: Int = 0,
    var isConnected: Boolean = false,
    var isFavorite: Boolean = false
)
