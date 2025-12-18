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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.btprinter.ui.theme.BtPrinterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
    
    // Printer Size State (58mm or 80mm)
    var printerSize by remember { mutableStateOf("58mm") }
    
    val scope = rememberCoroutineScope()

    // WebSocket States
    var serverUrl by remember { mutableStateOf("ws://192.168.1.5:8080") }
    var challengeCode by remember { mutableStateOf("") }
    var isConnected by remember { mutableStateOf(false) }
    var activeWebSocket by remember { mutableStateOf<WebSocket?>(null) }
    
    // OkHttpClient
    val client = remember { OkHttpClient() }

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
        Text("Bluetooth Printer Gateway", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // --- STATUS SECTION ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (statusMessage.contains("Error") || statusMessage.contains("Gagal")) 
                    MaterialTheme.colorScheme.errorContainer 
                else if (statusMessage.contains("Terhubung") || statusMessage.contains("Berhasil"))
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Status: $statusMessage",
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- SERVER SECTION ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Server Gateway", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL (ws://...)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnected
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = challengeCode,
                    onValueChange = { challengeCode = it },
                    label = { Text("Challenge Code") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isConnected,
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        if (isConnected) {
                            activeWebSocket?.close(1000, "User disconnected")
                            activeWebSocket = null
                            isConnected = false
                            statusMessage = "Disconnected from server"
                        } else {
                            if (serverUrl.isBlank()) {
                                Toast.makeText(context, "Masukkan URL Server", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            
                            if (challengeCode.isBlank()) {
                                Toast.makeText(context, "Masukkan Challenge Code", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            // Append challenge code to URL as query parameter
                            // Format: ws://server:port/?id=CHALLENGE_CODE
                            val separator = if (serverUrl.contains("?")) "&" else "?"
                            val finalUrl = "$serverUrl${separator}id=$challengeCode"
                            
                            val request = Request.Builder().url(finalUrl).build()
                            val listener = object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    scope.launch {
                                        isConnected = true
                                        statusMessage = "Terhubung! ID: $challengeCode"
                                    }
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    scope.launch {
                                        statusMessage = "Pesan masuk: $text"
                                        val device = selectedDevice
                                        if (device != null) {
                                            val success = printText(context, device, text)
                                            statusMessage = if (success) "Cetak Otomatis Berhasil ($printerSize)" else "Gagal Mencetak"
                                        } else {
                                            statusMessage = "Data diterima tapi belum ada printer dipilih!"
                                        }
                                    }
                                }

                                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                    scope.launch {
                                        isConnected = false
                                        statusMessage = "Server closing: $reason"
                                    }
                                }

                                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                    scope.launch {
                                        isConnected = false
                                        statusMessage = "Connection Error: ${t.message}"
                                    }
                                }
                            }
                            
                            try {
                                activeWebSocket = client.newWebSocket(request, listener)
                                statusMessage = "Menghubungkan..."
                            } catch (e: Exception) {
                                statusMessage = "Error: ${e.message}"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isConnected) "PUTUSKAN KONEKSI" else "SAMBUNGKAN SERVER")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- MANUAL PRINT SECTION ---
        Text("Manual Print", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textToPrint,
                onValueChange = { textToPrint = it },
                label = { Text("Tes Teks") },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (selectedDevice != null) {
                        scope.launch {
                            statusMessage = "Mencetak ($printerSize)..."
                            printText(context, selectedDevice!!, textToPrint)
                            statusMessage = "Selesai"
                        }
                    }
                },
                enabled = selectedDevice != null
            ) {
                Text("Print")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // --- DEVICE LIST SECTION ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Pilih Perangkat Bluetooth:", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { pairedDevices = getPairedDevices(context) }) {
                Text("↻", style = MaterialTheme.typography.headlineSmall)
            }
        }
        
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(pairedDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            selectedDevice = device
                            statusMessage = "Printer dipilih: ${device.name}"
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

        Spacer(modifier = Modifier.height(16.dp))

        // --- PRINTER SIZE SELECTION (BOTTOM) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Ukuran Kertas:", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = printerSize == "58mm",
                    onClick = { printerSize = "58mm" }
                )
                Text("58mm", modifier = Modifier.clickable { printerSize = "58mm" })
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = printerSize == "80mm",
                    onClick = { printerSize = "80mm" }
                )
                Text("80mm", modifier = Modifier.clickable { printerSize = "80mm" })
            }
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

            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket.connect()

            val outputStream = socket.outputStream
            outputStream.write((text + "\n").toByteArray())
            
            // Jarak sebelum potong kertas
            outputStream.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x0A, 0x0A))

            // Command Potong Kertas
            val cutCommand = byteArrayOf(0x1D, 0x56, 0x00)
            outputStream.write(cutCommand)

            outputStream.flush() 

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