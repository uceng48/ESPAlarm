package com.example.esp32alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.welie.blessed.BluetoothPeripheral
import com.example.esp32alarm.model.DeviceItem

class MainActivity : AppCompatActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var preferences: PreferencesHelper
    private lateinit var notificationHelper: NotificationHelper

    private lateinit var tvStatus: TextView
    private lateinit var tvRssi: TextView
    private lateinit var tvAlarmState: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DeviceAdapter
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnAlarmOn: Button
    private lateinit var btnAlarmOff: Button
    private lateinit var btnReadRssi: Button
    private lateinit var etCommand: EditText
    private lateinit var btnSend: Button
    private lateinit var btnSettings: Button
    private lateinit var tvLog: TextView

    private var deviceList = mutableListOf<DeviceItem>()
    private var selectedDevice: DeviceItem? = null
    private var peripheralMap = mutableMapOf<String, BluetoothPeripheral>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = PreferencesHelper(this)
        notificationHelper = NotificationHelper(this)
        LogHelper.init(this)
        LogHelper.log("App started")

        initViews()
        requestPermissions()
        initBleManager()
        loadDeviceList()

        startService(Intent(this, BleAlarmService::class.java))
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvRssi = findViewById(R.id.tvRssi)
        tvAlarmState = findViewById(R.id.tvAlarmState)
        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnAlarmOn = findViewById(R.id.btnAlarmOn)
        btnAlarmOff = findViewById(R.id.btnAlarmOff)
        btnReadRssi = findViewById(R.id.btnReadRssi)
        etCommand = findViewById(R.id.etCommand)
        btnSend = findViewById(R.id.btnSend)
        btnSettings = findViewById(R.id.btnSettings)
        tvLog = findViewById(R.id.tvLog)

        adapter = DeviceAdapter(deviceList) { device ->
            selectedDevice = device
            val peripheral = peripheralMap[device.address]
            if (peripheral != null) {
                bleManager.connect(peripheral)
            } else {
                Toast.makeText(this, "Perangkat tidak tersedia, scan ulang", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.setOnClickListener {
            deviceList.clear()
            peripheralMap.clear()
            bleManager.startScan()
            Toast.makeText(this, "Memindai...", Toast.LENGTH_SHORT).show()
        }

        btnConnect.setOnClickListener {
            selectedDevice?.let {
                val peripheral = peripheralMap[it.address]
                if (peripheral != null) bleManager.connect(peripheral)
                else Toast.makeText(this, "Pilih perangkat dari daftar", Toast.LENGTH_SHORT).show()
            }
        }

        btnDisconnect.setOnClickListener {
            bleManager.disconnect()
            updateUI(false)
        }

        btnAlarmOn.setOnClickListener {
            bleManager.sendMessage("BUZZER_ON")
        }

        btnAlarmOff.setOnClickListener {
            bleManager.sendMessage("BUZZER_OFF")
            bleManager.stopAlarm()
        }

        btnReadRssi.setOnClickListener {
            bleManager.readRssi()
        }

        btnSend.setOnClickListener {
            val cmd = etCommand.text.toString()
            if (cmd.isNotEmpty()) {
                bleManager.sendMessage(cmd)
                etCommand.text.clear()
            }
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 100)
        } else {
            requestIgnoreBatteryOptimization()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Semua izin diperlukan", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                requestIgnoreBatteryOptimization()
            }
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Mohon nonaktifkan optimasi baterai untuk aplikasi ini", Toast.LENGTH_LONG).show()
        }
    }

    private fun initBleManager() {
        bleManager = BleManager(this, preferences, notificationHelper)

        bleManager.onDeviceDiscovered = { peripheral, rssi ->
            runOnUiThread {
                val name = peripheral.name ?: "Unknown"
                val address = peripheral.address
                val existing = deviceList.find { it.address == address }
                if (existing == null) {
                    val device = DeviceItem(name, address, rssi, false, false)
                    deviceList.add(device)
                    peripheralMap[address] = peripheral
                    adapter.updateList(deviceList)
                } else {
                    existing.rssi = rssi
                    adapter.updateList(deviceList)
                }
            }
        }

        bleManager.onConnectionStateChanged = { connected, address ->
            runOnUiThread {
                updateUI(connected)
                if (connected && address != null) {
                    deviceList.forEach {
                        it.isConnected = it.address == address
                    }
                    adapter.updateList(deviceList)
                    preferences.setLastMac(address)
                    Toast.makeText(this, "✅ Terhubung ke $address", Toast.LENGTH_SHORT).show()
                    bleManager.stopScan()
                } else if (!connected && address != null) {
                    deviceList.forEach {
                        if (it.address == address) it.isConnected = false
                    }
                    adapter.updateList(deviceList)
                }
            }
        }

        bleManager.onMessageReceived = { message ->
            runOnUiThread {
                tvLog.append("📨 $message\n")
            }
        }

        bleManager.onRssiUpdate = { rssi ->
            runOnUiThread {
                tvRssi.text = "RSSI: $rssi dBm"
            }
        }

        bleManager.onAlarmStateChanged = { active ->
            runOnUiThread {
                tvAlarmState.text = if (active) "🔔 ALARM AKTIF" else "🔕 Alarm nonaktif"
                tvAlarmState.setTextColor(if (active) 0xFFFF0000.toInt() else 0xFF000000.toInt())
            }
        }

        val lastMac = preferences.getLastMac()
        if (lastMac != null && preferences.getAutoReconnect()) {
            bleManager.startScan()
        }
    }

    private fun updateUI(connected: Boolean) {
        tvStatus.text = if (connected) "🟢 Terhubung" else "🔴 Tidak terhubung"
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnAlarmOn.isEnabled = connected
        btnAlarmOff.isEnabled = connected
        btnSend.isEnabled = connected
        etCommand.isEnabled = connected
        btnReadRssi.isEnabled = connected
    }

    private fun loadDeviceList() {
        val saved = preferences.getDeviceList()
        deviceList.clear()
        deviceList.addAll(saved)
        adapter.updateList(deviceList)
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etDuration = dialogView.findViewById<EditText>(R.id.etAlarmDuration)
        val spSound = dialogView.findViewById<Spinner>(R.id.spAlarmSound)
        val cbVibrate = dialogView.findViewById<CheckBox>(R.id.cbVibrate)
        val cbAutoReconnect = dialogView.findViewById<CheckBox>(R.id.cbAutoReconnect)

        etDuration.setText(preferences.getAlarmDuration().toString())
        val soundOptions = arrayOf("siren", "beep")
        spSound.setSelection(if (preferences.getAlarmSound() == "siren") 0 else 1)
        cbVibrate.isChecked = preferences.getAlarmVibrate()
        cbAutoReconnect.isChecked = preferences.getAutoReconnect()

        AlertDialog.Builder(this)
            .setTitle("Pengaturan Alarm")
            .setView(dialogView)
            .setPositiveButton("Simpan") { _, _ ->
                val duration = etDuration.text.toString().toIntOrNull() ?: 10
                preferences.setAlarmDuration(duration)
                preferences.setAlarmSound(soundOptions[spSound.selectedItemPosition])
                preferences.setAlarmVibrate(cbVibrate.isChecked)
                preferences.setAutoReconnect(cbAutoReconnect.isChecked)
                Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
        LogHelper.log("App destroyed")
    }
}
