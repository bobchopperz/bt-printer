package com.example.btprinter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.btprinter.ui.theme.BtPrinterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BtPrinterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PrinterApp()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun PrinterApp() {
    val context = LocalContext.current
    var textToPrint by remember { mutableStateOf("Hello Printer!") }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var statusMessage by remember { mutableStateOf("Ready") }
    val scope = rememberCoroutineScope()

    // Permissions handling
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            statusMessage = "Izin diberikan. Memuat perangkat..."
            pairedDevices = getPairedDevices(context)
        } else {
            statusMessage = "Izin Bluetooth diperlukan."
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Bluetooth Printer App", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = textToPrint,
            onValueChange = { textToPrint = it },
            label = { Text("Teks untuk dicetak") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Status: $statusMessage")
        
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            pairedDevices = getPairedDevices(context)
        }) {
            Text("Refresh Perangkat")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Pilih Perangkat:", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(pairedDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            selectedDevice = device
                            statusMessage = "Perangkat dipilih: ${device.name}"
                        },
                    colors = if (selectedDevice == device) 
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) 
                    else CardDefaults.cardColors()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge)
                        Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Button(
            onClick = {
                if (selectedDevice != null) {
                    scope.launch {
                        statusMessage = "Mencetak..."
                        val success = printText(context, selectedDevice!!, textToPrint)
                        statusMessage = if (success) "Cetak Berhasil!" else "Cetak Gagal"
                    }
                } else {
                    Toast.makeText(context, "Pilih perangkat dulu", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedDevice != null
        ) {
            Text("CETAK")
        }
    }
}

fun getPairedDevices(context: Context): List<BluetoothDevice> {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = bluetoothManager.adapter
    
    if (adapter == null || !adapter.isEnabled) {
        Toast.makeText(context, "Bluetooth tidak aktif", Toast.LENGTH_SHORT).show()
        return emptyList()
    }

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Permission check handled in UI, but safety check here
        return emptyList()
    }

    return adapter.bondedDevices.toList()
}

suspend fun printText(context: Context, device: BluetoothDevice, text: String): Boolean {
    return withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                 Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return@withContext false
            }

            // Standard UUID for SPP (Serial Port Profile)
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket.connect()

            val outputStream = socket.outputStream
            // Print text
            outputStream.write((text + "\n").toByteArray())
            
            // Add blank lines for spacing before cutting (increased as requested)
            outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A))

            // ESC/POS command for full paper cut
            val cutCommand = byteArrayOf(0x1D, 0x56, 0x00)
            outputStream.write(cutCommand)

            outputStream.flush() // Ensure all data is sent

            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        } finally {
            try {
                socket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}