package com.example.esp32alarm

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.content.Context
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import com.welie.blessed.*
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

    private lateinit var centralManager: BluetoothCentralManager
    private var peripheral: BluetoothPeripheral? = null
    private var isConnected = false
    private var alarmActive = false
    private var mediaPlayer: MediaPlayer? = null
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String?) -> Unit)? = null
    var onDeviceDiscovered: ((BluetoothPeripheral, Int) -> Unit)? = null
    var onRssiUpdate: ((Int) -> Unit)? = null
    var onAlarmStateChanged: ((Boolean) -> Unit)? = null

    // ─── Callback ───
    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            val name = peripheral.name
            if (name?.contains("ESP32C3") == true) {
                onDeviceDiscovered?.invoke(peripheral, scanResult.rssi)
                val lastMac = preferences.getLastMac()
                if (lastMac != null && peripheral.address == lastMac && !isConnected) {
                    LogHelper.log("Auto-connecting to $name")
                    connect(peripheral)
                }
            }
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            isConnected = true
            alarmActive = false
            stopAlarm()
            preferences.setLastMac(peripheral.address)
            reconnectAttempts = 0
            heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
            onConnectionStateChanged?.invoke(true, peripheral.address)
            peripheral.discoverServices()
            LogHelper.log("Connected to ${peripheral.name}")
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            isConnected = false
            onConnectionStateChanged?.invoke(false, peripheral.address)
            LogHelper.log("Connection failed: $status")
            handleDisconnect(peripheral)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            isConnected = false
            onConnectionStateChanged?.invoke(false, peripheral.address)
            LogHelper.log("Disconnected: ${peripheral.name}")
            handleDisconnect(peripheral)
        }

        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            val service = peripheral.getService(SERVICE_UUID)
            service?.let {
                peripheral.setNotify(it.getCharacteristic(CHARACTERISTIC_UUID_TX), true)
                LogHelper.log("Services discovered, notifications enabled")
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothCharacteristic,
            status: GattStatus
        ) {
            val message = String(value)
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

        override fun onRssiRead(peripheral: BluetoothPeripheral, rssi: Int, status: GattStatus) {
            onRssiUpdate?.invoke(rssi)
        }
    }

    init {
        val handlerThread = HandlerThread("BLE")
        handlerThread.start()
        centralManager = BluetoothCentralManager(context, centralManagerCallback, handlerThread)
        LogHelper.log("BleManager initialized")
    }

    private val heartbeatTimeoutRunnable = Runnable {
        LogHelper.log("Heartbeat timeout, disconnecting")
        disconnect()
        handleDisconnect(peripheral)
    }

    private fun handleDisconnect(peripheral: BluetoothPeripheral?) {
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
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        centralManager.startScan(listOf(scanFilter), null)
        LogHelper.log("Scan started with filter")
    }

    fun stopScan() {
        centralManager.stopScan()
    }

    fun connect(peripheral: BluetoothPeripheral) {
        this.peripheral = peripheral
        centralManager.connectPeripheral(peripheral)
    }

    fun disconnect() {
        peripheral?.let { centralManager.cancelConnection(it) }
        isConnected = false
        stopAlarm()
        heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
    }

    fun sendMessage(message: String) {
        if (isConnected && peripheral != null) {
            val service = peripheral?.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID_RX)
            characteristic?.let {
                peripheral?.writeCharacteristic(
                    it,
                    message.toByteArray(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                LogHelper.log("Sent: $message")
            }
        }
    }

    fun readRssi() {
        peripheral?.readRssi()
    }

    fun isConnected(): Boolean = isConnected
    fun getConnectedDeviceAddress(): String? = peripheral?.address

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
        centralManager.close()
        mediaPlayer?.release()
        heartbeatHandler.removeCallbacks(heartbeatTimeoutRunnable)
        LogHelper.log("Cleaned up")
    }
}
