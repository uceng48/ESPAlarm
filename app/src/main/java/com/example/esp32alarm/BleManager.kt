package com.example.esp32alarm

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import java.util.UUID

class BleManager(private val context: Context) {

    val SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")

    var connectedPeripheral: BluetoothPeripheral? = null
        private set

    var onDeviceDiscovered: ((peripheral: BluetoothPeripheral, scanResult: ScanResult) -> Unit)? = null
    var onConnectionStateChanged: ((peripheral: BluetoothPeripheral, isConnected: Boolean) -> Unit)? = null
    var onMessageReceived: ((message: String) -> Unit)? = null
    var onRssiUpdate: ((rssi: Int) -> Unit)? = null
    var onAlarmStateChanged: ((isAlarmOn: Boolean) -> Unit)? = null

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            onDeviceDiscovered?.invoke(peripheral, scanResult)
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            connectedPeripheral = peripheral
            onConnectionStateChanged?.invoke(peripheral, true)
        }

        // HciStatus digunakan di sini
        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            if (connectedPeripheral == peripheral) {
                connectedPeripheral = null
            }
            onConnectionStateChanged?.invoke(peripheral, false)
        }
    }

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            val characteristic = peripheral.getCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID)
            if (characteristic != null) {
                peripheral.setNotify(characteristic, true)
            }
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                val message = String(value, Charsets.UTF_8)
                onMessageReceived?.invoke(message)

                if (message.contains("ALARM_ON", ignoreCase = true)) {
                    onAlarmStateChanged?.invoke(true)
                } else if (message.contains("ALARM_OFF", ignoreCase = true)) {
                    onAlarmStateChanged?.invoke(false)
                }
            }
        }

        override fun onReadRemoteRssi(
            peripheral: BluetoothPeripheral,
            rssi: Int,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                onRssiUpdate?.invoke(rssi)
            }
        }
    }

    private val central = BluetoothCentralManager(
        context,
        centralCallback,
        Handler(Looper.getMainLooper())
    )

    fun startScan() {
        central.scanForPeripherals()
    }

    fun stopScan() {
        central.stopScan()
    }

    fun connect(peripheral: BluetoothPeripheral) {
        central.connectPeripheral(peripheral, peripheralCallback)
    }

    fun connect(address: String) {
        val peripheral = central.getPeripheral(address)
        connect(peripheral)
    }

    fun disconnect() {
        connectedPeripheral?.let {
            central.cancelConnection(it)
        }
    }

    fun sendMessage(message: String) {
        connectedPeripheral?.let { peripheral ->
            val characteristic = peripheral.getCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID)
            if (characteristic != null) {
                peripheral.writeCharacteristic(
                    characteristic,
                    message.toByteArray(Charsets.UTF_8),
                    WriteType.WITH_RESPONSE
                )
            }
        }
    }

    fun stopAlarm() {
        sendMessage("STOP_ALARM")
    }

    fun readRssi(peripheral: BluetoothPeripheral? = connectedPeripheral) {
        val target = peripheral ?: connectedPeripheral
        target?.readRemoteRssi()
    }

    fun cleanup() {
        stopScan()
        disconnect()
    }
}
