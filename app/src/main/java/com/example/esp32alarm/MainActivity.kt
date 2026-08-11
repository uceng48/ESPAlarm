package com.example.esp32alarm

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.esp32alarm.databinding.ActivityMainBinding
import com.welie.blessed.BluetoothPeripheral

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private var selectedPeripheral: BluetoothPeripheral? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inisialisasi BleManager dengan 1 parameter (context)
        bleManager = BleManager(this)

        setupBleCallbacks()
        setupUIListeners()
        checkPermissions()
    }

    private fun setupBleCallbacks() {
        bleManager.onDeviceDiscovered = { peripheral: BluetoothPeripheral, scanResult: ScanResult ->
            runOnUiThread {
                // Contoh: Menyimpan perangkat pertama atau mencatat log
                if (selectedPeripheral == null) {
                    selectedPeripheral = peripheral
                    binding.tvStatus?.text = "Ditemukan: ${peripheral.name ?: peripheral.address}"
                }
            }
        }

        bleManager.onConnectionStateChanged = { peripheral: BluetoothPeripheral, isConnected: Boolean ->
            runOnUiThread {
                if (isConnected) {
                    binding.tvStatus?.text = "Terhubung ke: ${peripheral.name ?: peripheral.address}"
                    binding.btnConnect?.text = "Disconnect"
                } else {
                    binding.tvStatus?.text = "Terputus"
                    binding.btnConnect?.text = "Connect"
                }
            }
        }

        bleManager.onMessageReceived = { message: String ->
            runOnUiThread {
                binding.tvMessage?.text = "Pesan: $message"
            }
        }

        bleManager.onRssiUpdate = { rssi: Int ->
            runOnUiThread {
                binding.tvRssi?.text = "RSSI: $rssi dBm"
            }
        }

        bleManager.onAlarmStateChanged = { isAlarmOn: Boolean ->
            runOnUiThread {
                if (isAlarmOn) {
                    binding.tvAlarmStatus?.text = "ALARM AKTIF!"
                } else {
                    binding.tvAlarmStatus?.text = "ALARM MATI"
                }
            }
        }
    }

    private fun setupUIListeners() {
        binding.btnScan?.setOnClickListener {
            binding.tvStatus?.text = "Mencari perangkat..."
            bleManager.startScan()
        }

        binding.btnStopScan?.setOnClickListener {
            bleManager.stopScan()
            binding.tvStatus?.text = "Scan dihentikan"
        }

        binding.btnConnect?.setOnClickListener {
            selectedPeripheral?.let { peripheral ->
                if (bleManager.connectedPeripheral == null) {
                    bleManager.connect(peripheral)
                } else {
                    bleManager.disconnect()
                }
            } ?: run {
                Toast.makeText(this, "Pilih/cari perangkat terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStopAlarm?.setOnClickListener {
            bleManager.stopAlarm()
        }

        binding.btnReadRssi?.setOnClickListener {
            bleManager.readRssi()
        }

        binding.btnSendCustom?.setOnClickListener {
            val message = binding.etMessage?.text.toString()
            if (message.isNotEmpty()) {
                bleManager.sendMessage(message)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
    }
}
