package com.example.esp32alarm

import android.content.Context
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.WriteType

class BleManager(private val context: Context) {

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: android.bluetooth.le.ScanResult) {
            central.connectPeripheral(peripheral, peripheralCallback)
        }
    }

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            // Service discovery dilakukan otomatis oleh Blessed
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            // Proses data karakteristik yang diterima
        }

        override fun onReadRemoteRssi(
            peripheral: BluetoothPeripheral,
            rssi: Int,
            status: GattStatus
        ) {
            // Proses pembacaan RSSI
        }
    }

    private val central = BluetoothCentralManager(
        context,
        centralCallback,
        Handler(Looper.getMainLooper())
    )

    fun startScanning() {
        central.scanForPeripherals()
    }

    fun writeData(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        peripheral.writeCharacteristic(characteristic, data, WriteType.WITH_RESPONSE)
    }

    fun readRssi(peripheral: BluetoothPeripheral) {
        peripheral.readRemoteRssi()
    }
}
