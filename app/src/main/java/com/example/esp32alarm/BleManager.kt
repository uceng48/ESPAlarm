package com.example.esp32alarm

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.UUID

class BleManager(
    private val context: Context,
    private val preferences: PreferencesHelper,
    private val notificationHelper: NotificationHelper
) {
    companion object {
        val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID_TX = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CHARACTERISTIC_UUID_RX = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
        private const val HEARTBEAT_TIMEOUT = 10000L
    }

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothLeScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private var gatt: BluetoothGatt? = null
    private var isConnected = false
    private var alarmActive = false
    private var mediaPlayer: MediaPlayer? = null
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String?) -> Unit)? = null
    var onDeviceDiscovered: ((BluetoothDevice, Int) -> Unit)? = null
    var onRssiUpdate: ((Int) -> Unit)? = null
    var onAlarmStateChanged: ((Boolean) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name
            if (name?.contains("ESP32C3") == true) {
                onDeviceDiscovered?.invoke(device, result.rssi)
                val lastMac = preferences.getLastMac()
                if (lastMac != null && device.address == lastMac && !isConnected) {
                    LogHelper.log("Auto-connecting to $name")
                    connect(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            LogHelper.log("Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                alarmActive = false
                stopAlarm()
                preferences.setLastMac(gatt.device.address)
                reconnectAttempts = 0
                heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
                onConnectionStateChanged?.invoke(true, gatt.device.address)
                gatt.discoverServices()
                LogHelper.log("Connected to ${gatt.device.name}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                onConnectionStateChanged?.invoke(false, gatt.device.address)
                LogHelper.log("Disconnected: ${gatt.device.name}")
                handleDisconnect(gatt.device)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                service?.let {
                    val characteristic = it.getCharacteristic(CHARACTERISTIC_UUID_TX)
                    characteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                        LogHelper.log("Services discovered, notifications enabled")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val message = String(characteristic.value)
            LogHelper.log("Received: $message")
            when {
                message == "HEARTBEAT" -> {
                    heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
                    heartbeatHandler.postDelayed(heartbeatTimeoutRunnable, HEARTBEAT_TIMEOUT)
                }
                message.startsWith("ALARM_") -> {
                    if (message == "ALARM_ACTIVATED") startAlarm()
                    else stopAlarm()
                }
                else -> onMessageReceived?.invoke(message)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            LogHelper.log("Characteristic write status: $status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            onRssiUpdate?.invoke(rssi)
        }
    }

    private val heartbeatTimeoutRunnable = Runnable {
        LogHelper.log("Heartbeat timeout, disconnecting")
        disconnect()
        handleDisconnect(gatt?.device)
    }

    private fun handleDisconnect(device: BluetoothDevice?) {
        if (alarmActive) return
        alarmActive = true
        startAlarm()
        if (preferences.getAutoReconnect() && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            LogHelper.log("Reconnect attempt $reconnectAttempts")
            startScan()
        }
    }

    // ─── Public methods ───
    fun startScan() {
        bluetoothLeScanner?.let { scanner ->
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(scanFilter), settings, scanCallback)
            LogHelper.log("Scan started with filter")
        }
    }

    fun stopScan() {
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        stopAlarm()
        heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
    }

    fun sendMessage(message: String) {
        gatt?.let { gattInstance ->
            val service = gattInstance.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID_RX)
            characteristic?.let {
                it.value = message.toByteArray()
                it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gattInstance.writeCharacteristic(it)
                LogHelper.log("Sent: $message")
            }
        }
    }

    fun readRssi() {
        gatt?.readRemoteRssi()
    }

    fun isConnected(): Boolean = isConnected
    fun getConnectedDeviceAddress(): String? = gatt?.device?.address

    // ─── Alarm ───
    private fun startAlarm() {
        if (alarmActive) return
        alarmActive = true
        onAlarmStateChanged?.invoke(true)

        notificationHelper.showAlarmNotification("⚠️ Alarm ESP32", "Koneksi terputus! Segera cek perangkat.")

        val soundType = preferences.getAlarmSound()
        if (soundType == "siren") {
            try {
                if (mediaPlayer == null) {
                    mediaPlayer = MediaPlayer.create(context, R.raw.alarm_siren)
                    mediaPlayer?.isLooping = true
                }
                mediaPlayer?.start()
            } catch (e: Exception) {
                playBeepTone()
            }
        } else {
            playBeepTone()
        }

        if (preferences.getAlarmVibrate()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500, 500), 0))
            } else {
                vibrator.vibrate(longArrayOf(0, 500, 500, 500), 0)
            }
        }

        val duration = preferences.getAlarmDuration()
        if (duration > 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (alarmActive) stopAlarm()
            }, duration * 1000L)
        }
    }

    private fun playBeepTone() {
        val toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
        Thread {
            while (alarmActive) {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                Thread.sleep(600)
            }
            toneGenerator.release()
        }.start()
    }

    fun stopAlarm() {
        if (!alarmActive) return
        alarmActive = false
        onAlarmStateChanged?.invoke(false)
        mediaPlayer?.stop()
        mediaPlayer?.reset()
        vibrator.cancel()
        notificationHelper.cancelAlarmNotification()
    }

    fun isAlarmActive(): Boolean = alarmActive

    fun cleanup() {
        disconnect()
        mediaPlayer?.release()
        heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
        LogHelper.log("Cleaned up")
    }
}
