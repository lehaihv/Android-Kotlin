package com.example.bluetooth_data_transfer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.bluetooth_data_transfer.ui.theme.Bluetooth_data_transferTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Replace with your own unique UUID for your app's Bluetooth service
private const val MY_APP_UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB" // Example SPP UUID

class MainActivity : ComponentActivity() {

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var isScanning by mutableStateOf(false)
    private var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var receivedData by mutableStateOf("")
    private var dataToSend by mutableStateOf("")

    private var bluetoothService: BluetoothDataTransferService? = null

    // --- Permission Handling ---
    private val requestBluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                // Permissions granted, proceed with Bluetooth operations
                Log.d("BluetoothPermissions", "All required permissions granted.")
                startDiscovery()
            } else {
                // Permissions denied, handle appropriately (e.g., show a message to the user)
                Log.e("BluetoothPermissions", "Some or all permissions were denied.")
            }
        }

    private fun hasBluetoothPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION // Required for BT scanning pre-Android 12
            )
        }
        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestBluetoothPermissionLauncher.launch(requiredPermissions)
    }

    // --- Bluetooth Discovery ---
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") // Permissions are checked before starting discovery
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        // if (!discoveredDevices.contains(it) && it.name != null) { // Original
                        if (!discoveredDevices.contains(it)) { // Temporary test
                            discoveredDevices.add(it)
                            val deviceName = if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                it.name ?: "Unnamed Device"
                            } else {
                                "Name N/A (Permission Missing)"
                            }
                            Log.d("BluetoothDiscovery", "Found device: $deviceName - ${it.address}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    Log.d("BluetoothDiscovery", "Discovery finished.")
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions are checked before calling
    private fun startDiscovery() {
        if (bluetoothAdapter == null) {
            Log.e("Bluetooth", "BluetoothAdapter is null.")
            return
        }
        if (!bluetoothAdapter!!.isEnabled) {
            // Prompt user to enable Bluetooth
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // You'll need a launcher for this intent as well for modern Android
            // For simplicity, assuming it's handled or you add the launcher.
            // For now, just log and return if not enabled.
            Log.w("Bluetooth", "Bluetooth is not enabled.")
            // Consider using ActivityResultLauncher for ACTION_REQUEST_ENABLE
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT) // Old way
            return
        }

        if (hasBluetoothPermissions()) {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            discoveredDevices.clear()
            bluetoothAdapter?.startDiscovery()
            isScanning = true
            Log.d("BluetoothDiscovery", "Starting discovery...")
        } else {
            requestBluetoothPermissions()
        }
    }

    @SuppressLint("MissingPermission") // Permissions checked before
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) { // Check overall permissions first
            Log.e("BluetoothConnect", "Missing required Bluetooth permissions.")
            requestBluetoothPermissions()
            return
        }
        if (bluetoothAdapter?.isDiscovering == true) {
            // Check SCAN permission specifically for cancelDiscovery
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.cancelDiscovery()
            } else {
                Log.w("BluetoothConnect", "BLUETOOTH_SCAN permission missing, cannot cancel discovery during connect.")
                // Decide if you want to request only SCAN here or rely on the broader check above.
                // For simplicity, we'll proceed but log a warning. The ConnectThread will also try to cancel.
            }
            isScanning = false
        }
        Log.d("BluetoothConnect", "Attempting to connect to ${device.name}")

        // Initialize and start the connection in a coroutine
        bluetoothService = BluetoothDataTransferService(
            applicationContext, // Pass applicationContext
            bluetoothAdapter!!,
            onConnected = { bd ->
                connectedDevice = bd
                Log.i("BluetoothConnect", "Successfully connected to ${bd.name}")
            },
            onDisconnected = {
                connectedDevice = null
                receivedData = "Disconnected"
                bluetoothService = null // Good practice to nullify after disconnection
                Log.i("BluetoothConnect", "Disconnected.")
            },
            onDataReceived = { data ->
                receivedData = data
                Log.i("BluetoothData", "Received: $data")
            },
            onError = {errorMessage ->
                Log.e("BluetoothConnect", "Error: $errorMessage")
                receivedData = "Error: $errorMessage"
                connectedDevice = null
                bluetoothService = null // Nullify on error too
            }
        )
        bluetoothService?.connect(device)
    }

    private fun sendData(data: String) {
        if (connectedDevice != null && data.isNotEmpty()) {
            bluetoothService?.write(data.toByteArray())
            Log.d("BluetoothData", "Sent: $data")
            dataToSend = "" // Clear input after sending
        } else {
            Log.w("BluetoothData", "Not connected or data is empty, cannot send.")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register broadcast receiver for discovery
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(discoveryReceiver, filter)
        }


        setContent {
            Bluetooth_data_transferTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothScreen(
                        modifier = Modifier.padding(innerPadding),
                        discoveredDevices = discoveredDevices,
                        isScanning = isScanning,
                        connectedDeviceName = connectedDevice?.let {
                            // Check permission before accessing name
                            if (ActivityCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                it.name ?: "Unknown Device"
                            } else {
                                "Name requires permission"
                            }
                        } ?: "Not Connected",
                        receivedData = receivedData,
                        dataToSend = dataToSend,
                        onDataToSendChange = { dataToSend = it },
                        onStartScanClicked = {
                            if (hasBluetoothPermissions()) {
                                startDiscovery()
                            } else {
                                requestBluetoothPermissions()
                            }
                        },
                        onDeviceClicked = { device ->
                            connectToDevice(device)
                        },
                        onSendDataClicked = {
                            sendData(dataToSend)
                        },
                        onStopConnectionClicked = {
                            bluetoothService?.stop() // This will trigger onDisconnected callback
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(discoveryReceiver)
        bluetoothService?.stop() // Ensure service is stopped
        // Ensure Bluetooth discovery is cancelled if activity is destroyed
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }
}

// --- Composable UI ---
@SuppressLint("MissingPermission") // Permissions are checked before accessing device properties
@Composable
fun BluetoothScreen(
    modifier: Modifier = Modifier,
    discoveredDevices: List<BluetoothDevice>,
    isScanning: Boolean,
    connectedDeviceName: String,
    receivedData: String,
    dataToSend: String,
    onDataToSendChange: (String) -> Unit,
    onStartScanClicked: () -> Unit,
    onDeviceClicked: (BluetoothDevice) -> Unit,
    onSendDataClicked: () -> Unit,
    onStopConnectionClicked: () -> Unit,
    context: Context = LocalContext.current // Get context for permission checks
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = onStartScanClicked, enabled = !isScanning) {
            Text(if (isScanning) "Scanning..." else "Start Scan for Devices")
        }
        if (isScanning) {
            CircularProgressIndicator()
        }

        Text("Discovered Devices:", style = MaterialTheme.typography.titleMedium)
        if (discoveredDevices.isEmpty() && !isScanning) {
            Text("No devices found. Ensure Bluetooth is on and devices are discoverable.")
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(discoveredDevices) { device ->
                // Check for BLUETOOTH_CONNECT permission before accessing device name
                val deviceName = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name ?: "Unknown Device"
                } else {
                    "Name N/A (Permission)"
                }
                val deviceAddress = device.address
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDeviceClicked(device) }
                    .padding(8.dp)
                ) {
                    Text("Name: $deviceName")
                    Text("Address: $deviceAddress")
                }
                HorizontalDivider()
            }
        }

        HorizontalDivider(thickness = 2.dp)

        Text("Connection Status: $connectedDeviceName", style = MaterialTheme.typography.titleMedium)

        if (connectedDeviceName != "Not Connected" && connectedDeviceName != "Name requires permission" && connectedDeviceName != "Disconnected" && !connectedDeviceName.startsWith("Error:")) {
            Button(onClick = onStopConnectionClicked) {
                Text("Disconnect")
            }
            TextField(
                value = dataToSend,
                onValueChange = onDataToSendChange,
                label = { Text("Data to Send") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSendDataClicked) {
                Text("Send Data")
            }
        }
        Text("Log:", style = MaterialTheme.typography.labelLarge)
        Text(receivedData, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}

// --- Bluetooth Data Transfer Service (Simplified) ---
// This should ideally be in its own Kotlin file for better organization.
class BluetoothDataTransferService(
    private val context: Context, // Added context
    private val adapter: BluetoothAdapter,
    private val onConnected: (BluetoothDevice) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onDataReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO) // For service-level coroutines


    @Synchronized
    fun connect(device: BluetoothDevice) {
        Log.d("BTService", "Attempting to connect to device: ${device.address}")

        if (connectThread?.isAlive == true) {
            connectThread?.cancel()
            connectThread = null
        }
        connectedThread?.cancel()
        connectedThread = null

        try {
            val uuid = UUID.fromString(MY_APP_UUID_STRING)
            connectThread = ConnectThread(device, uuid)
            connectThread?.start()
        } catch (e: SecurityException) {
            Log.e("BTService", "SecurityException on connect: ${e.message}")
            // Use Dispatchers.Main if onError directly updates UI state lived in ViewModel/Activity
            CoroutineScope(Dispatchers.Main).launch { onError("Permission missing for connection.") }
        } catch (e: IllegalArgumentException) {
            Log.e("BTService", "UUID error: ${e.message}")
            CoroutineScope(Dispatchers.Main).launch { onError("Invalid Bluetooth Service UUID.") }
        }
    }

    @Synchronized
    fun stop() {
        Log.d("BTService", "Stopping Bluetooth service.")
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        // If stop is called explicitly, it implies a disconnect intention.
        // The ConnectedThread's cancel or stream error will call onDisconnected.
        // If no connection was active, or if ConnectThread was active,
        // we might want to ensure onDisconnected is called if appropriate.
        // However, usually onDisconnected is tied to an *actual* disconnection event.
        // For now, relying on thread cancellations to trigger it.
        // If the stop is user-initiated and we want immediate UI feedback:
        // CoroutineScope(Dispatchers.Main).launch { onDisconnected() } // Consider implications
    }

    fun write(bytes: ByteArray) {
        val r: ConnectedThread?
        synchronized(this) {
            if (connectedThread == null) {
                Log.e("BTService", "ConnectedThread is null, cannot write.")
                CoroutineScope(Dispatchers.Main).launch { onError("Not connected.") }
                return
            }
            r = connectedThread
        }
        r?.write(bytes) // write itself runs on ConnectedThread
    }

    @SuppressLint("MissingPermission")
    @Synchronized // Ensure thread safety when managing threads
    private fun manageConnectedSocket(device: BluetoothDevice, socket: BluetoothSocket) {
        Log.d("BTService", "Socket connected, creating ConnectedThread for ${device.name}")

        connectThread?.cancel() // Cancel the thread that completed the connection
        connectThread = null

        connectedThread?.cancel() // Cancel any existing connected thread
        connectedThread = null

        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        CoroutineScope(Dispatchers.Main).launch {
            onConnected(device)
        }
    }

    @SuppressLint("MissingPermission") // Permissions should be checked by the caller of connect()
    private inner class ConnectThread(private val mmDevice: BluetoothDevice, private val mmUuid: UUID) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                // BLUETOOTH_CONNECT permission is required to create an RFCOMM socket.
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("ConnectThread", "BLUETOOTH_CONNECT permission missing for creating socket.")
                    CoroutineScope(Dispatchers.Main).launch { onError("BLUETOOTH_CONNECT permission needed.") }
                    return@lazy null
                }
                mmDevice.createRfcommSocketToServiceRecord(mmUuid)
            } catch (e: IOException) {
                Log.e("ConnectThread", "Socket create() failed", e)
                CoroutineScope(Dispatchers.Main).launch { onError("Failed to create socket: ${e.message}") }
                null
            } catch (e: SecurityException) {
                Log.e("ConnectThread", "SecurityException on socket create(): ${e.message}")
                CoroutineScope(Dispatchers.Main).launch { onError("Permission missing for socket creation (Security).") }
                null
            }
        }

        override fun run() {
            Log.i("ConnectThread", "BEGIN mConnectThread for ${mmDevice.name ?: "Unknown Device"}")
            name = "ConnectThread"

            // Always cancel discovery because it will slow down a connection
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    if (adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                        Log.d("ConnectThread", "Discovery cancelled for connection.")
                    }
                } else {
                    Log.w("ConnectThread", "BLUETOOTH_SCAN permission missing, cannot cancel discovery.")
                    // Not calling onError here as connection might still proceed, but it's a risk.
                }

                mmSocket?.let { socket ->
                    try {
                        socket.connect() // This is a blocking call
                        Log.d("ConnectThread", "Socket connected successfully to ${mmDevice.name ?: "Unknown Device"}")
                        manageConnectedSocket(mmDevice, socket)
                    } catch (e: IOException) {
                        Log.e("ConnectThread", "Unable to connect; close the socket: ${e.message}", e)
                        try {
                            socket.close()
                        } catch (closeException: IOException) {
                            Log.e("ConnectThread", "Could not close the client socket", closeException)
                        }
                        CoroutineScope(Dispatchers.Main).launch { onError("Connection Failed: ${e.message}") }
                        return
                    } catch (e: SecurityException) {
                        Log.e("ConnectThread", "SecurityException during connect: ${e.message}")
                        CoroutineScope(Dispatchers.Main).launch { onError("Connection Permission Issue: ${e.message}") }
                        try { socket.close() } catch (ex: IOException) { Log.e("ConnectThread", "Socket close failed", ex)}
                        return
                    }
                } ?: run {
                    Log.e("ConnectThread", "mmSocket is null, cannot proceed with connection.")
                    // onError was likely called during mmSocket lazy initialization
                    return
                }
            } catch (e: SecurityException) { // Catch SecurityException for cancelDiscovery or other operations
                Log.e("ConnectThread", "SecurityException in run: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch { onError("Permission issue during connection setup.")}
                try { mmSocket?.close() } catch (ex: IOException) { Log.e("ConnectThread", "Socket close failed", ex)}
                return
            }
            Log.i("ConnectThread", "END mConnectThread for ${mmDevice.name ?: "Unknown Device"}")
        }

        fun cancel() {
            try {
                mmSocket?.close()
                Log.d("ConnectThread", "Socket closed on cancel.")
            } catch (e: IOException) {
                Log.e("ConnectThread", "Could not close the client socket on cancel", e)
            }
        }
    }


    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream? = try {
            mmSocket.inputStream
        } catch (e: IOException) {
            Log.e("ConnectedThread", "Error obtaining input stream", e)
            CoroutineScope(Dispatchers.Main).launch { onError("Error getting input stream: ${e.message}") }
            null
        }
        private val mmOutStream: OutputStream? = try {
            mmSocket.outputStream
        } catch (e: IOException) {
            Log.e("ConnectedThread", "Error obtaining output stream", e)
            CoroutineScope(Dispatchers.Main).launch { onError("Error getting output stream: ${e.message}") }
            null
        }
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            Log.i("ConnectedThread", "BEGIN mConnectedThread")
            name = "ConnectedThread"
            var numBytes: Int

            if (mmInStream == null) {
                Log.e("ConnectedThread", "Input stream is null, exiting thread.")
                CoroutineScope(Dispatchers.Main).launch { onDisconnected() }
                return
            }

            while (mmSocket.isConnected) { // Loop while socket is connected
                try {
                    numBytes = mmInStream.read(mmBuffer)
                    if (numBytes > 0) {
                        val receivedString = String(mmBuffer, 0, numBytes)
                        Log.d("ConnectedThread", "Read $numBytes bytes: $receivedString")
                        CoroutineScope(Dispatchers.Main).launch {
                            onDataReceived(receivedString)
                        }
                    } else if (numBytes == -1) { // End of stream
                        Log.d("ConnectedThread", "Input stream reached end, disconnecting.")
                        CoroutineScope(Dispatchers.Main).launch { onDisconnected() }
                        break
                    }
                } catch (e: IOException) {
                    Log.d("ConnectedThread", "Input stream was disconnected or error occurred", e)
                    CoroutineScope(Dispatchers.Main).launch { onDisconnected() }
                    break
                }
            }
            Log.i("ConnectedThread", "END mConnectedThread")
        }

        fun write(bytes: ByteArray) {
            if (mmOutStream == null) {
                Log.e("ConnectedThread", "Output stream is null, cannot write.")
                CoroutineScope(Dispatchers.Main).launch { onError("Cannot send data: Output stream unavailable.") }
                return
            }
            try {
                mmOutStream.write(bytes)
                Log.d("ConnectedThread", "Wrote ${bytes.size} bytes.")
                // Optionally, confirm write via onDataSent callback if needed by UI
            } catch (e: IOException) {
                Log.e("ConnectedThread", "Error occurred when sending data", e)
                CoroutineScope(Dispatchers.Main).launch { onError("Couldn't send data: ${e.message}") }
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
                Log.d("ConnectedThread", "Socket closed on cancel.")
            } catch (e: IOException) {
                Log.e("ConnectedThread", "Could not close the connect socket on cancel", e)
            }
            // Ensure onDisconnected is called if this cancel means the connection is truly over
            // However, the read loop should also detect this and call onDisconnected.
            // Adding it here might be redundant but safe if the loop doesn't break quickly.
            // CoroutineScope(Dispatchers.Main).launch { onDisconnected() }
        }
    }
}