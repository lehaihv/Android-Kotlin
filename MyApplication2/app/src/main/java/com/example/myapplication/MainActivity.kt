package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Bluetooth adapter with backward compatibility
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothManager?.adapter
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var buttonScan: Button
    private lateinit var buttonConnect: Button
    private lateinit var listViewDevices: ListView

    private val foundDevices = mutableListOf<BluetoothDevice>()
    private lateinit var adapter: ArrayAdapter<String>
    private var selectedDevice: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (!foundDevices.any { d -> d.address == device.address }) {
                            foundDevices.add(device)
                            adapter.add("${device.name ?: "Unknown"} - ${device.address}")
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(this@MainActivity, "Scan finished", Toast.LENGTH_SHORT).show()
                    buttonScan.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonScan = findViewById(R.id.buttonScan)
        buttonConnect = findViewById(R.id.buttonConnect)
        listViewDevices = findViewById(R.id.listViewDevices)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice)
        listViewDevices.adapter = adapter
        listViewDevices.choiceMode = ListView.CHOICE_MODE_SINGLE

        listViewDevices.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = foundDevices[position]
        }

        buttonScan.setOnClickListener { startBluetoothScan() }
        buttonConnect.setOnClickListener { connectToSelectedDevice() }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun startBluetoothScan() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // Clear previous results
        foundDevices.clear()
        adapter.clear()
        selectedDevice = null

        try {
            // Cancel previous discovery if any
            if (bluetoothAdapter!!.isDiscovering) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothAdapter!!.cancelDiscovery()
                    } else {
                        Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
                        return
                    }
                } else {
                    bluetoothAdapter!!.cancelDiscovery()
                }
            }

            // Start discovery
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothAdapter!!.startDiscovery()
                } else {
                    Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                bluetoothAdapter!!.startDiscovery()
            }

            buttonScan.isEnabled = false
            Toast.makeText(this, "Scanning started...", Toast.LENGTH_SHORT).show()
        } catch (se: SecurityException) {
            Toast.makeText(this, "Permission error: ${se.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToSelectedDevice() {
        if (selectedDevice == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasConnectPermission()) {
            requestRequiredPermissions()
            return
        }

        selectedDevice?.let { device ->
            Toast.makeText(this, "Connecting to ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            // TODO: Implement actual connection logic here
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted. You can scan now.", Toast.LENGTH_SHORT).show()
                buttonScan.isEnabled = true
            } else {
                Toast.makeText(this, "Permissions denied. Cannot scan Bluetooth devices.", Toast.LENGTH_LONG).show()
                buttonScan.isEnabled = false
            }
        }
    }
}
