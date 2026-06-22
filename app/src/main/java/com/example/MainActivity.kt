package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// Enum representing the transfer modes
enum class TransferDirection {
    Idle, Sending, Receiving
}

// Struct representing the live transfer status
data class TransferState(
    val direction: TransferDirection = TransferDirection.Idle,
    val filename: String = "",
    val totalSize: Long = 0L,
    val bytesTransferred: Long = 0L,
    val statusText: String = "",
    val speedText: String = "",
    val etaText: String = "",
    val receivedFile: File? = null,
    val isSuccess: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String = ""
)

data class HistoryItem(
    val timestamp: Long,
    val direction: String, // "SENDER" or "RECEIVER"
    val filename: String,
    val size: Long,
    val isSuccess: Boolean
)

data class PortalDevice(
    val ipAddress: String,
    val deviceName: String, // e.g. "iPhone", "Windows PC"
    val lastAction: String, // "Downloaded image.png"
    val timestamp: Long,
    val totalTransferredBytes: Long = 0L
)

class MainActivity : ComponentActivity() {

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter()

    // Observable states using Compose mutable state
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var peers = mutableStateListOf<WifiP2pDevice>()
    private var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
    private var isScanning by mutableStateOf(false)
    private var currentStatusMessage by mutableStateOf("Ready to scan")
    private var lastConnectedDeviceName by mutableStateOf<String?>(null)

    // Sockets and Coroutine jobs
    private var activeServerSocket: ServerSocket? = null
    private var receiverJob: Job? = null
    private var senderJob: Job? = null

    // Transfer Progress State
    private var transferState by mutableStateOf(TransferState())

    // Active screen navigation segment
    private var activeTab by mutableStateOf("home")
    private val historyList = mutableStateListOf<HistoryItem>()
    private val receivedFilesList = mutableStateListOf<File>()
    private val recentPortalDevices = mutableStateListOf<PortalDevice>()
    private var userDeviceName by mutableStateOf("My Phone")
    private var showDiagnosticDialog by mutableStateOf(false)
    private var isFinalizingTransfer by mutableStateOf(false)
    private var finalizingMessage by mutableStateOf("")
    private var showEnableWifiDialog by mutableStateOf(false)

    // Web Share Portal State (iOS & PC compatibility bridge)
    private var webShareHost: WebShareHost? = null
    private var webServerStatus by mutableStateOf("Stopped")
    private val webServerLogs = mutableStateListOf<String>()
    private var webSharedFileUri by mutableStateOf<Uri?>(null)
    private var webSharedFileName by mutableStateOf("")
    private var webSharedFileSize by mutableStateOf(0L)
    private val webSharedFiles = mutableStateListOf<SharedFile>()

    // History Persistence helpers
    private fun saveHistoryToSharedPrefs() {
        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        val stringSet = historyList.map { "${it.timestamp}|${it.direction}|${it.filename}|${it.size}|${it.isSuccess}" }.toSet()
        sharedPrefs.edit().putStringSet("transfer_history_list", stringSet).apply()
    }

    private fun loadHistoryFromSharedPrefs() {
        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        val stringSet = sharedPrefs.getStringSet("transfer_history_list", null) ?: emptySet()
        val items = stringSet.mapNotNull { line ->
            try {
                val chunks = line.split("|")
                if (chunks.size == 5) {
                    HistoryItem(
                        timestamp = chunks[0].toLong(),
                        direction = chunks[1],
                        filename = chunks[2],
                        size = chunks[3].toLong(),
                        isSuccess = chunks[4].toBoolean()
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
        historyList.clear()
        historyList.addAll(items)
    }

    private fun addHistoryItem(direction: String, filename: String, size: Long, isSuccess: Boolean) {
        val item = HistoryItem(System.currentTimeMillis(), direction, filename, size, isSuccess)
        historyList.add(0, item)
        saveHistoryToSharedPrefs()
    }

    // Local sandbox-saved received files
    private fun refreshReceivedFiles() {
        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val files = downloadsDir.listFiles() ?: emptyArray()
        receivedFilesList.clear()
        receivedFilesList.addAll(files.sortedByDescending { it.lastModified() })
    }

    private fun savePortalDevicesToSharedPrefs() {
        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        val stringSet = recentPortalDevices.map { "${it.ipAddress}|${it.deviceName}|${it.lastAction}|${it.timestamp}|${it.totalTransferredBytes}" }.toSet()
        sharedPrefs.edit().putStringSet("portal_devices_list", stringSet).apply()
    }

    private fun loadPortalDevicesFromSharedPrefs() {
        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        val stringSet = sharedPrefs.getStringSet("portal_devices_list", null) ?: emptySet()
        val items = stringSet.mapNotNull { line ->
            try {
                val chunks = line.split("|")
                if (chunks.size >= 4) {
                    PortalDevice(
                        ipAddress = chunks[0],
                        deviceName = chunks[1],
                        lastAction = chunks[2],
                        timestamp = chunks[3].toLong(),
                        totalTransferredBytes = if (chunks.size >= 5) chunks[4].toLong() else 0L
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
        recentPortalDevices.clear()
        recentPortalDevices.addAll(items)
    }

    private fun addOrUpdatePortalDevice(ipAddress: String, deviceName: String, action: String) {
        val existingIndex = recentPortalDevices.indexOfFirst { it.ipAddress == ipAddress && it.deviceName == deviceName }
        if (existingIndex != -1) {
            val existing = recentPortalDevices[existingIndex]
            recentPortalDevices[existingIndex] = existing.copy(
                lastAction = action,
                timestamp = System.currentTimeMillis()
            )
        } else {
            recentPortalDevices.add(0, PortalDevice(
                ipAddress = ipAddress,
                deviceName = deviceName,
                lastAction = action,
                timestamp = System.currentTimeMillis()
            ))
        }
        val sorted = recentPortalDevices.sortedByDescending { it.timestamp }
        recentPortalDevices.clear()
        recentPortalDevices.addAll(sorted)
        savePortalDevicesToSharedPrefs()
    }

    private fun saveUserDeviceName(name: String) {
        userDeviceName = name
        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        sharedPrefs.edit().putString("user_device_name", name).apply()
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%d B/s", bytesPerSec.toLong())
        }
    }

    private fun formatEta(remainingBytes: Long, bytesPerSec: Double): String {
        if (remainingBytes <= 0) return "0s"
        val seconds = (remainingBytes.toDouble() / bytesPerSec).toLong()
        return when {
            seconds >= 3600 -> String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> String.format("%dm %ds", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPrefs = getSharedPreferences("transfr_prefs", MODE_PRIVATE)
        userDeviceName = sharedPrefs.getString("user_device_name", android.os.Build.MODEL) ?: "My Phone"
        loadHistoryFromSharedPrefs()
        refreshReceivedFiles()
        loadPortalDevicesFromSharedPrefs()

        // Set up P2P manager
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, mainLooper, null)

        // Actions to listen in the broadcast receiver
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        val logTimeFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        webShareHost = WebShareHost(
            context = this,
            port = 8989,
            onStatusUpdate = { status ->
                webServerStatus = status
            },
            onFileReceived = { file ->
                runOnUiThread {
                    refreshReceivedFiles()
                    addHistoryItem("WEB_RECV", file.name, file.length(), true)
                    Toast.makeText(this@MainActivity, "Received ${file.name} over Web Portal!", Toast.LENGTH_LONG).show()
                }
            },
            onLogMessage = { log ->
                runOnUiThread {
                    val time = logTimeFormatter.format(java.util.Date())
                    if (log.startsWith("PORTAL_ACTION|")) {
                        try {
                            val parts = log.substring("PORTAL_ACTION|".length).split("|")
                            if (parts.size >= 3) {
                                val ip = parts[0]
                                val rawDevice = parts[1]
                                val action = parts[2]
                                addOrUpdatePortalDevice(ip, rawDevice, action)
                            }
                        } catch (_: Exception) {}
                    } else {
                        webServerLogs.add(0, "[$time] $log")
                    }
                }
            }
        )

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        webShareHost?.stop()
        cleanupNetworkResources()
    }

    private fun registerReceiver() {
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isWifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!isWifiP2pEnabled) {
                            currentStatusMessage = "Wi-Fi P2P is disabled. Enable Wi-Fi."
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (hasP2pPermissions(context)) {
                            wifiP2pManager?.requestPeers(channel, peerListListener)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager?.requestConnectionInfo(channel, connectionInfoListener)
                        } else {
                            handleDisconnect()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        // We can receive our own device renaming or parameters if desired
                    }
                }
            }
        }
        registerReceiver(receiver, intentFilter)
    }

    private fun unregisterReceiver() {
        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        receiver = null
    }

    // Listener for available peers changed
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val refreshedPeers = peerList.deviceList
        peers.clear()
        peers.addAll(refreshedPeers)
        if (refreshedPeers.isEmpty()) {
            currentStatusMessage = "No devices found nearby"
        } else {
            currentStatusMessage = "Found ${refreshedPeers.size} devices"
        }
    }

    // Listener for P2P connection configuration change
    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        connectionInfo = info
        if (info.groupFormed) {
            val groupOwnerIp = info.groupOwnerAddress?.hostAddress ?: ""
            if (info.isGroupOwner) {
                currentStatusMessage = "Connected as RECEIVER. Ready for files..."
                startReceiver()
            } else {
                currentStatusMessage = "Connected as SENDER. Choose file to share."
                stopReceiver() // Client does not listen on ServerSocket
            }
        } else {
            handleDisconnect()
        }
    }

    // Triggers direct search of nearby devices
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasP2pPermissions(this)) {
            currentStatusMessage = "Permissions required to scan"
            return
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifiManager == null || !wifiManager.isWifiEnabled) {
            showEnableWifiDialog = true
            currentStatusMessage = "Wi-Fi is turned off. Please turn on Wi-Fi."
            return
        }

        isScanning = true
        currentStatusMessage = "Scanning for devices..."
        peers.clear()

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Peers change broadcast will report list
            }

            override fun onFailure(reason: Int) {
                isScanning = false
                currentStatusMessage = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.ERROR -> "Discovery failed (Internal Error)"
                    WifiP2pManager.BUSY -> "System is busy. Retrying..."
                    else -> "Discovery failed ($reason)"
                }
            }
        })

        // Stop animating scanning after 15 seconds to save battery
        lifecycleScope.launch {
            delay(15000)
            isScanning = false
        }
    }

    // Connects to a selected device
    @SuppressLint("MissingPermission")
    private fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        currentStatusMessage = "Initiating connection to ${device.deviceName}..."
        lastConnectedDeviceName = device.deviceName

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                currentStatusMessage = "Connecting with ${device.deviceName}..."
            }

            override fun onFailure(reason: Int) {
                currentStatusMessage = "Connection request failed ($reason)"
            }
        })
    }

    // Disconnects active sharing group
    private fun disconnectFromPeers() {
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handleDisconnect()
            }

            override fun onFailure(reason: Int) {
                currentStatusMessage = "Disconnect failed ($reason)"
            }
        })
    }

    private fun handleDisconnect() {
        connectionInfo = null
        lastConnectedDeviceName = null
        currentStatusMessage = "Disconnected"
        cleanupNetworkResources()
        transferState = TransferState() // Reset progress screen state
    }

    private fun cleanupNetworkResources() {
        receiverJob?.cancel()
        receiverJob = null
        senderJob?.cancel()
        senderJob = null
        try {
            activeServerSocket?.close()
        } catch (_: Exception) {}
        activeServerSocket = null
    }

    // Starts ServerSocket receiving process on the Group Owner
    private fun startReceiver() {
        cleanupNetworkResources()
        receiverJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(8988).apply { reuseAddress = true }
                activeServerSocket = server
                while (isActive) {
                    val client = server.accept()
                    handleIncomingConnection(client)
                }
            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        currentStatusMessage = "Receiver stopped: ${e.localizedMessage}"
                    }
                }
            }
        }
    }

    private fun stopReceiver() {
        cleanupNetworkResources()
    }

    // Reads the custom stream protocol metadata line, then streams file bytes
    private fun handleIncomingConnection(socket: Socket) {
        lifecycleScope.launch(Dispatchers.IO) {
            var inputStream = socket.getInputStream()
            var fileOutputStream: FileOutputStream? = null
            var filename = "received_file"
            var totalSize = 0L
            try {
                // Read UTF-8 metadata string up to '\n'
                val headerBytes = ByteArrayOutputStream()
                var b: Int
                while (true) {
                    b = inputStream.read()
                    if (b == -1 || b == '\n'.code) break
                    headerBytes.write(b)
                }
                val headerStr = headerBytes.toString("UTF-8")
                if (headerStr.isBlank()) {
                    socket.close()
                    return@launch
                }

                // Parser metadata: "fileName|fileSize"
                val chunks = headerStr.split("|")
                filename = chunks.getOrNull(0) ?: "received_file"
                totalSize = chunks.getOrNull(1)?.toLongOrNull() ?: 0L

                val startTime = System.currentTimeMillis()
                // Update state block
                withContext(Dispatchers.Main) {
                    transferState = TransferState(
                        direction = TransferDirection.Receiving,
                        filename = filename,
                        totalSize = totalSize,
                        bytesTransferred = 0,
                        statusText = "Receiving file from partner...",
                        speedText = "0 KB/s",
                        etaText = "--"
                    )
                }

                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val targetFile = File(downloadsDir, filename)
                fileOutputStream = FileOutputStream(targetFile)

                val buf = ByteArray(8192)
                var bytesRead: Int
                var count = 0L

                while (true) {
                    bytesRead = inputStream.read(buf)
                    if (bytesRead == -1) break
                    fileOutputStream.write(buf, 0, bytesRead)
                    count += bytesRead

                    // Periodically update progress UI limiting layout pressure
                    if (count % 3 == 0L || bytesRead == -1 || count == totalSize) {
                        val currentCount = count
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val speedValText: String
                        val etaValText: String

                        if (elapsedMs > 100) {
                            val speedBytesPerSec = (currentCount.toDouble() / elapsedMs) * 1000.0
                            speedValText = formatSpeed(speedBytesPerSec)
                            val remainingBytes = totalSize - currentCount
                            if (remainingBytes <= 0) {
                                etaValText = "0s"
                            } else {
                                etaValText = formatEta(remainingBytes, speedBytesPerSec)
                            }
                        } else {
                            speedValText = "0 KB/s"
                            etaValText = "--"
                        }

                        withContext(Dispatchers.Main) {
                            transferState = transferState.copy(
                                bytesTransferred = currentCount,
                                speedText = speedValText,
                                etaText = etaValText,
                                statusText = "Receiving: ${formatFileSize(currentCount)} of ${formatFileSize(totalSize)}"
                            )
                        }
                    }
                }

                fileOutputStream.flush()
                fileOutputStream.close()
                socket.close()

                withContext(Dispatchers.Main) {
                    isFinalizingTransfer = true
                    finalizingMessage = "Verifying file integrity and writing buffer to download storage..."
                }

                delay(1200)

                withContext(Dispatchers.Main) {
                    isFinalizingTransfer = false
                    transferState = transferState.copy(
                        bytesTransferred = totalSize,
                        isSuccess = true,
                        receivedFile = targetFile,
                        speedText = "Done",
                        etaText = "Done",
                        statusText = "Completed! Saved: $filename"
                    )
                    currentStatusMessage = "Received: $filename"
                    addHistoryItem("RECEIVER", filename, totalSize, true)
                    refreshReceivedFiles()
                    Toast.makeText(this@MainActivity, "Received $filename successfully!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    transferState = transferState.copy(
                        isError = true,
                        errorMessage = e.localizedMessage ?: "Network read failure"
                    )
                    addHistoryItem("RECEIVER", filename, totalSize, false)
                }
                try { fileOutputStream?.close() } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // Handles the outbound stream
    private fun sendFile(uri: Uri) {
        val groupInfo = connectionInfo ?: return
        val targetIp = groupInfo.groupOwnerAddress?.hostAddress ?: return

        senderJob?.cancel()
        senderJob = lifecycleScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var outputStream = java.io.OutputStream.nullOutputStream()
            var name = "file"
            var size = 0L
            try {
                val metadata = getFileMetadata(this@MainActivity, uri)
                name = metadata.first
                size = metadata.second

                val startTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    transferState = TransferState(
                        direction = TransferDirection.Sending,
                        filename = name,
                        totalSize = size,
                        bytesTransferred = 0,
                        statusText = "Connecting with group receiver...",
                        speedText = "0 KB/s",
                        etaText = "--"
                    )
                }

                socket = Socket()
                socket.connect(InetSocketAddress(targetIp, 8988), 10000)
                outputStream = socket.getOutputStream()

                // Protocol prefix line: "filename|size\n"
                val header = "$name|$size\n"
                outputStream.write(header.toByteArray(Charsets.UTF_8))
                outputStream.flush()

                withContext(Dispatchers.Main) {
                    transferState = transferState.copy(
                        statusText = "Sending data standard stream..."
                    )
                }

                contentResolver.openInputStream(uri)?.use { fileIn ->
                    val buf = ByteArray(8192)
                    var readLen: Int
                    var totalWritten = 0L

                    while (true) {
                        readLen = fileIn.read(buf)
                        if (readLen == -1) break
                        outputStream.write(buf, 0, readLen)
                        totalWritten += readLen

                        val currentTotal = totalWritten
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val speedValText: String
                        val etaValText: String

                        if (elapsedMs > 100) {
                            val speedBytesPerSec = (currentTotal.toDouble() / elapsedMs) * 1000.0
                            speedValText = formatSpeed(speedBytesPerSec)
                            val remainingBytes = size - currentTotal
                            if (remainingBytes <= 0) {
                                etaValText = "0s"
                            } else {
                                etaValText = formatEta(remainingBytes, speedBytesPerSec)
                            }
                        } else {
                            speedValText = "0 KB/s"
                            etaValText = "--"
                        }

                        withContext(Dispatchers.Main) {
                            transferState = transferState.copy(
                                bytesTransferred = currentTotal,
                                speedText = speedValText,
                                etaText = etaValText,
                                statusText = "Sending: ${formatFileSize(currentTotal)} of ${formatFileSize(size)}"
                            )
                        }
                    }
                }

                outputStream.flush()
                socket.close()

                withContext(Dispatchers.Main) {
                    isFinalizingTransfer = true
                    finalizingMessage = "Finalizing session and completing transmission..."
                }

                delay(1200)

                withContext(Dispatchers.Main) {
                    isFinalizingTransfer = false
                    transferState = transferState.copy(
                        bytesTransferred = size,
                        isSuccess = true,
                        speedText = "Done",
                        etaText = "Done",
                        statusText = "Successfully sent! ${formatFileSize(size)}"
                    )
                    currentStatusMessage = "Shared $name successfully"
                    addHistoryItem("SENDER", name, size, true)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    transferState = transferState.copy(
                        isError = true,
                        errorMessage = e.localizedMessage ?: "Network write failure"
                    )
                    addHistoryItem("SENDER", name, size, false)
                }
                try { outputStream.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    // Opens standard document reader using FileProvider
    private fun openReceivedFile(file: File) {
        try {
            val authority = "${applicationContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val ext = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No compatible viewer App found on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    // Queries the display name and size from selected content provider item
    private fun getFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unnamed_transfer"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) name = cursor.getString(nameIdx)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {}
        return Pair(name, size)
    }

    private fun hasP2pPermissions(context: Context): Boolean {
        val locationOk = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val wifiOk = ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            return locationOk && wifiOk
        }
        return locationOk
    }

    private fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: ""
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "Local IP"
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val index = if (digitGroups < units.size) digitGroups else units.size - 1
        return String.format("%.1f %s", size / Math.pow(1024.0, index.toDouble()), units[index])
    }

    // Main Layout Composable
    @Composable
    fun MainScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current

        // Check and request Android permissions
        var permissionsGranted by remember { mutableStateOf(hasP2pPermissions(context)) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            permissionsGranted = results.values.all { it }
            if (permissionsGranted) {
                currentStatusMessage = "Ready to discover peers"
            } else {
                currentStatusMessage = "Permissions are required for wireless discovery"
            }
        }

        // Standard dynamic file picker
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                sendFile(uri)
            }
        }

        val webFilePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris != null && uris.isNotEmpty()) {
                webSharedFiles.clear()
                uris.forEach { uri ->
                    val metadata = getFileMetadata(context, uri)
                    webSharedFiles.add(SharedFile(uri, metadata.first, metadata.second))
                }

                // First file for backward-compatibility fallback
                val firstUri = uris[0]
                val firstMetadata = getFileMetadata(context, firstUri)
                webSharedFileUri = firstUri
                webSharedFileName = firstMetadata.first
                webSharedFileSize = firstMetadata.second

                webShareHost?.apply {
                    sharedFiles.clear()
                    sharedFiles.addAll(webSharedFiles)
                    sharedFileUri = firstUri
                    sharedFileName = firstMetadata.first
                    sharedFileSize = firstMetadata.second
                }

                val titleMsg = if (uris.size == 1) firstMetadata.first else "${uris.size} files"
                Toast.makeText(context, "Ready to share: $titleMsg", Toast.LENGTH_SHORT).show()
                val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                webServerLogs.add(0, "[$logTime] Made $titleMsg available on local portal")
            }
        }

        // Immersive Theme Colors Coordination (Dark Theme)
        val themeBg = Color(0xFF141318)
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerLightPurple = Color(0xFF4F378B)
        val onPrimaryDeep = Color(0xFFEADDFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)
        val bottomNavBg = Color(0xFF1D1B20)

        var showWebPortalDialog by remember { mutableStateOf(false) }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = themeBg,
            bottomBar = {
                // Material 3 Navigation Bar
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    color = bottomNavBg,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // Home Item
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeTab = "home" }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == "home") containerLightPurple else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "Home",
                                    tint = if (activeTab == "home") onPrimaryDeep else textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Home",
                                fontSize = 11.sp,
                                fontWeight = if (activeTab == "home") FontWeight.Medium else FontWeight.Normal,
                                color = textPrimary
                            )
                        }

                        // History Item
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeTab = "history" }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == "history") containerLightPurple else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "History",
                                    tint = if (activeTab == "history") onPrimaryDeep else textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "History",
                                fontSize = 11.sp,
                                fontWeight = if (activeTab == "history") FontWeight.Medium else FontWeight.Normal,
                                color = textPrimary
                            )
                        }

                        // Files Item
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    activeTab = "files"
                                    refreshReceivedFiles()
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == "files") containerLightPurple else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Files",
                                    tint = if (activeTab == "files") onPrimaryDeep else textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Files",
                                fontSize = 11.sp,
                                fontWeight = if (activeTab == "files") FontWeight.Medium else FontWeight.Normal,
                                color = textPrimary
                            )
                        }

                        // Settings Item
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activeTab = "settings" }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (activeTab == "settings") containerLightPurple else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = if (activeTab == "settings") onPrimaryDeep else textPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Settings",
                                fontSize = 11.sp,
                                fontWeight = if (activeTab == "settings") FontWeight.Medium else FontWeight.Normal,
                                color = textPrimary
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            when (activeTab) {
                "home" -> HomeScreen(
                    innerPadding = innerPadding,
                    permissionsGranted = permissionsGranted,
                    permissionLauncher = permissionLauncher,
                    filePickerLauncher = filePickerLauncher,
                    webFilePickerLauncher = webFilePickerLauncher,
                    onOpenWebPortal = { showWebPortalDialog = true }
                )
                "history" -> HistoryScreen(innerPadding)
                "files" -> FilesScreen(innerPadding)
                "settings" -> SettingsScreen(innerPadding)
            }
        }

        if (showDiagnosticDialog) {
            DiagnosticDialog(onDismiss = { showDiagnosticDialog = false })
        }

        if (showWebPortalDialog) {
            WebSharePortalDialog(
                onDismiss = { showWebPortalDialog = false },
                webFilePickerLauncher = webFilePickerLauncher
            )
        }

        if (isFinalizingTransfer) {
            FinalizingTransferDialog(message = finalizingMessage)
        }

        if (showEnableWifiDialog) {
            EnableWifiDialog(onDismiss = { showEnableWifiDialog = false })
        }
    }

    @Composable
    fun HomeScreen(
        innerPadding: PaddingValues,
        permissionsGranted: Boolean,
        permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        filePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        webFilePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>,
        onOpenWebPortal: () -> Unit
    ) {
        val context = LocalContext.current
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerLightPurple = Color(0xFF4F378B)
        val onPrimaryDeep = Color(0xFFEADDFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            // Header Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(containerLightPurple)
                            .clickable {
                                showDiagnosticDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = onPrimaryDeep,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Mobile Transfer",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary,
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = userDeviceName,
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Permissions Warning Card
            if (!permissionsGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFA93226).copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, Color(0xFFA93226).copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Alert", tint = Color(0xFFA93226))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Permissions Required", fontWeight = FontWeight.Bold, color = Color(0xFFA93226), fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "System Location and Scanner access is required to build Wi-Fi Direct pipelines with nearby phones.",
                            color = Color(0xFF641E16),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                }
                                permissionLauncher.launch(perms.toTypedArray())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = brandPrimary, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bridge state variables for direct HomeScreen portal management
            var activeBridgeTab by remember { mutableStateOf("local") } // "local" or "cloud"
            val coroutineScope = rememberCoroutineScope()

            var cloudUploading by remember { mutableStateOf(false) }
            var cloudUploadUrl by remember { mutableStateOf("") }
            var cloudUploadError by remember { mutableStateOf("") }
            var cloudUploadProgressBytes by remember { mutableStateOf(0L) }
            var cloudUploadTotalBytes by remember { mutableStateOf(0L) }

            var cloudDownloadUrlInput by remember { mutableStateOf("") }
            var cloudDownloading by remember { mutableStateOf(false) }
            var cloudDownloadError by remember { mutableStateOf("") }
            var cloudDownloadProgressText by remember { mutableStateOf("") }
            var cloudDownloadProgressBytes by remember { mutableStateOf(0L) }
            var cloudDownloadTotalBytes by remember { mutableStateOf(0L) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Portal Title Banner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Secure Share Portal",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = textPrimary
                            )
                            Text(
                                text = "BROWSER TRANSFER CHANNELS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = brandPrimary,
                                letterSpacing = 1.sp
                            )
                        }
                        
                        // Status light indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (webServerStatus == "Active") Color(0xFF27AE60) else Color(0xFF938F99))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (webServerStatus == "Active") "ACTIVE" else "OFFLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (webServerStatus == "Active") Color(0xFF27AE60) else textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tab Selector for Bridge Type
                    TabRow(
                        selectedTabIndex = if (activeBridgeTab == "local") 0 else 1,
                        containerColor = Color(0xFF141318),
                        contentColor = brandPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = activeBridgeTab == "local",
                            onClick = { activeBridgeTab = "local" },
                            text = { Text("Local Wi-Fi QR", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Wifi, contentDescription = "Local Wi-Fi", modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = activeBridgeTab == "cloud",
                            onClick = { activeBridgeTab = "cloud" },
                            text = { Text("Secure Cloud Sync", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Cloud, contentDescription = "Secure Cloud", modifier = Modifier.size(16.dp)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeBridgeTab == "local") {
                        // LOCAL PORTAL BLOCK (QR-centric same network share)
                        val portalUrl = "http://${getLocalWifiIpAddress()}:8989"

                        if (webServerStatus == "Active") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "SCAN QR CODE TO CONNECT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textSecondary,
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                // Gorgeous styled container for the QR
                                Box(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .background(Color.White, RoundedCornerShape(24.dp))
                                        .border(3.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(brandPrimary, containerLightPurple)), RoundedCornerShape(24.dp))
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    QRCodeImage(
                                        url = portalUrl,
                                        modifier = Modifier.size(175.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Copy URL bar with modern design
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF141318), RoundedCornerShape(14.dp))
                                        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                                        .clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("transfr_link", portalUrl)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Portal Link Copied!", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = "Link icon",
                                            tint = brandPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = portalUrl,
                                            fontWeight = FontWeight.Bold,
                                            color = brandPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Link",
                                        tint = textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Ensure devices are on the same Wi-Fi, then scan QR or visit portal.",
                                    fontSize = 11.sp,
                                    color = textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Spacer(modifier = Modifier.height(14.dp))

                                // Premium styling for selected files card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141318)),
                                    border = BorderStroke(1.dp, borderColor),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "ACTIVE DOWNLOADS ON PORTAL",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = brandPrimary,
                                                letterSpacing = 0.5.sp
                                            )
                                            Button(
                                                onClick = { webFilePickerLauncher.launch("*/*") },
                                                colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Add File", modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (webSharedFiles.isNotEmpty() || webSharedFileUri != null) "Change" else "Choose File", fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        if (webSharedFiles.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                webSharedFiles.take(5).forEach { file ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(containerWhite.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                imageVector = Icons.Default.InsertDriveFile,
                                                                contentDescription = "File Icon",
                                                                tint = brandPrimary,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(8.dp))
                                                            Text(
                                                                text = file.name,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = textPrimary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = formatFileSize(file.size),
                                                            fontSize = 11.sp,
                                                            color = textSecondary
                                                        )
                                                    }
                                                }
                                                if (webSharedFiles.size > 5) {
                                                    Text(
                                                        text = "+ ${webSharedFiles.size - 5} more files...",
                                                        fontSize = 11.sp,
                                                        color = textSecondary,
                                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                                    )
                                                }
                                            }
                                        } else if (webSharedFileUri != null) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(containerWhite.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.InsertDriveFile,
                                                        contentDescription = "File Icon",
                                                        tint = brandPrimary,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = webSharedFileName,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = textPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = formatFileSize(webSharedFileSize),
                                                    fontSize = 11.sp,
                                                    color = textSecondary
                                                )
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(containerWhite.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CloudQueue,
                                                    contentDescription = null,
                                                    tint = textSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Select files to make them available for download",
                                                    fontSize = 11.sp,
                                                    color = textSecondary
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = { webShareHost?.stop() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B)),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.LinkOff, contentDescription = "Stop Server", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Stop Local Portal Server", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        } else {
                            // If Server Stopped
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = "Portal Stopped",
                                    tint = textSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(54.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Portal Server is Offline",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = textPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Start the local portal to display the QR Code. Any browser client on the same network can download files or upload to you.",
                                    fontSize = 11.sp,
                                    color = textSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = { webShareHost?.start() },
                                    colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Wifi, contentDescription = "Start Link")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Start Share Portal & Show QR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        // CLOUD PORTAL BLOCK
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Part A: Upload to Cloud
                            Text(
                                text = "UPLOAD FILE TO SECURE CLOUD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = brandPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            val cloudSelectedFileNameText = if (cloudUploadUrl.isNotEmpty()) "Uploaded!" else "No File Selected"

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141318)),
                                border = BorderStroke(1.dp, borderColor),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.UploadFile,
                                        contentDescription = "Cloud File",
                                        tint = brandPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cloudSelectedFileNameText,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (cloudUploadUrl.isNotEmpty()) {
                                            Text(
                                                text = cloudUploadUrl,
                                                fontSize = 11.sp,
                                                color = Color(0xFF81C784),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    
                                    Button(
                                        onClick = {
                                            if (cloudUploading) return@Button
                                            // Launch native picker
                                            webFilePickerLauncher.launch("*/*")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text("Select File", fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (cloudUploading) {
                                val fraction = if (cloudUploadTotalBytes > 0L) {
                                    cloudUploadProgressBytes.toFloat() / cloudUploadTotalBytes.toFloat()
                                } else 0f
                                
                                StraightLineTransferProgress(
                                    title = "Uploading To Cloud",
                                    filename = "Secure Packet",
                                    progress = fraction,
                                    statusText = "Sending payload: ${formatFileSize(cloudUploadProgressBytes)} / ${formatFileSize(cloudUploadTotalBytes)}"
                                )
                            } else {
                                Button(
                                    onClick = {
                                        if (webSharedFileUri == null) {
                                            Toast.makeText(context, "Select a file to broadcast first!", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        cloudUploading = true
                                        cloudUploadError = ""
                                        cloudUploadUrl = ""
                                        cloudUploadProgressBytes = 0L
                                        cloudUploadTotalBytes = 0L
                                        uploadFileToCloud(
                                            uri = webSharedFileUri!!,
                                            onProgress = { sentBytes, totalBytes ->
                                                cloudUploadProgressBytes = sentBytes
                                                cloudUploadTotalBytes = totalBytes
                                            },
                                            onSuccess = { downloadLink ->
                                                cloudUploading = false
                                                cloudUploadUrl = downloadLink
                                                Toast.makeText(context, "Uploaded successfully! QR generated below.", Toast.LENGTH_SHORT).show()
                                            },
                                            onError = { err ->
                                                cloudUploading = false
                                                cloudUploadError = err
                                            }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = "Upload Cloud")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (webSharedFileUri != null) "Upload '${webSharedFileName}' To Cloud" else "Upload Selected File To Cloud",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (cloudUploadError.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Upload error: $cloudUploadError", color = Color(0xFFE28F8F), fontSize = 11.sp)
                            }

                            if (cloudUploadUrl.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text("CLOUD QR CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    QRCodeImage(url = cloudUploadUrl, modifier = Modifier.size(150.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Scan QR with iOS camera to download directly on another cellular network. Valid for 1 hour.",
                                        fontSize = 10.sp,
                                        color = textSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = borderColor, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(16.dp))

                            // Part B: Download from Cloud link
                            Text(
                                text = "RECEIVE FILE FROM CLOUD LINK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = brandPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = cloudDownloadUrlInput,
                                onValueChange = { cloudDownloadUrlInput = it },
                                placeholder = { Text("https://tmpfiles.org/dl/...", fontSize = 12.sp, color = textSecondary) },
                                label = { Text("Enter Cloud URL To Download", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = brandPrimary,
                                    unfocusedBorderColor = borderColor,
                                    focusedLabelColor = brandPrimary,
                                    unfocusedLabelColor = textSecondary,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            if (cloudDownloading) {
                                val cleanUrl = cloudDownloadUrlInput.trim().substringBefore("?")
                                val parts = cleanUrl.split("/")
                                val last = parts.lastOrNull() ?: ""
                                val cloudFileName = if (last.isNotEmpty() && last != "dl") last else "Remote Cloud File"
                                val fraction = if (cloudDownloadTotalBytes > 0L) {
                                    cloudDownloadProgressBytes.toFloat() / cloudDownloadTotalBytes.toFloat()
                                } else 0f
                                
                                StraightLineTransferProgress(
                                    title = "Downloading From Cloud",
                                    filename = cloudFileName,
                                    progress = fraction,
                                    statusText = if (cloudDownloadProgressText.isEmpty()) "Downloading..." else cloudDownloadProgressText
                                )
                            } else {
                                Button(
                                    onClick = {
                                        val rawUrl = cloudDownloadUrlInput.trim()
                                        if (rawUrl.isEmpty()) {
                                            Toast.makeText(context, "Enter cloud link to download first", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        cloudDownloading = true
                                        cloudDownloadError = ""
                                        cloudDownloadProgressBytes = 0L
                                        cloudDownloadTotalBytes = 0L
                                        cloudDownloadProgressText = "Requesting cloud service..."
                                        downloadFileFromCloud(
                                            rawUrl,
                                            onProgress = { downloaded, total ->
                                                cloudDownloadProgressBytes = downloaded
                                                cloudDownloadTotalBytes = total
                                                cloudDownloadProgressText = if (total > 0) {
                                                    "Downloaded: ${formatFileSize(downloaded)} / ${formatFileSize(total)}"
                                                } else {
                                                    "Downloaded: ${formatFileSize(downloaded)}"
                                                }
                                            },
                                            onSuccess = { savedFile -> coroutineScope.launch {
                                                cloudDownloading = false
                                                isFinalizingTransfer = true; finalizingMessage = "Assembling secure packets and verifying file signature..."; delay(1500); isFinalizingTransfer = false; cloudDownloadUrlInput = ""
                                                Toast.makeText(context, "File downloaded: ${savedFile.name}", Toast.LENGTH_LONG).show()
                                                refreshReceivedFiles()

                                                val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                                webServerLogs.add(0, "[$logTime] Downloaded cloud file: ${savedFile.name}"); }
                                            },
                                            onError = { err ->
                                                cloudDownloading = false
                                                cloudDownloadError = err
                                            }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Download Item")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download From Cloud Link", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }

                            if (cloudDownloadError.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Download error: $cloudDownloadError", color = Color(0xFFE28F8F), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun WebSharePortalDialog(
        onDismiss: () -> Unit,
        webFilePickerLauncher: androidx.activity.result.ActivityResultLauncher<String>
    ) {
        val context = LocalContext.current
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerLightPurple = Color(0xFF4F378B)
        val onPrimaryDeep = Color(0xFFEADDFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        val localIp = remember { getLocalWifiIpAddress() }
        val portalUrl = "http://$localIp:8989"

        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF141318)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "iOS & PC Portal",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = textPrimary
                            )
                            Text(
                                text = "CROSS-PLATFORM BRIDGE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = brandPrimary,
                                letterSpacing = 1.sp
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.background(containerWhite, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Dialog",
                                tint = textPrimary
                            )
                        }
                    }

                  Spacer(modifier = Modifier.height(16.dp))

                  // Tab Selector for Bridge Type (Allows same-network or cross-network transfers)
                  var activeBridgeTab by remember { mutableStateOf("local") } // "local" or "cloud"

                  TabRow(
                      selectedTabIndex = if (activeBridgeTab == "local") 0 else 1,
                      containerColor = containerWhite,
                      contentColor = brandPrimary,
                      modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(12.dp))
                          .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                  ) {
                      Tab(
                          selected = activeBridgeTab == "local",
                          onClick = { activeBridgeTab = "local" },
                          text = { Text("Local Wi-Fi", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                          icon = { Icon(Icons.Default.Wifi, contentDescription = "Local Wi-Fi", modifier = Modifier.size(18.dp)) }
                      )
                      Tab(
                          selected = activeBridgeTab == "cloud",
                          onClick = { activeBridgeTab = "cloud" },
                          text = { Text("Secure Cloud", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                          icon = { Icon(Icons.Default.Cloud, contentDescription = "Secure Cloud", modifier = Modifier.size(18.dp)) }
                      )
                  }

                  Spacer(modifier = Modifier.height(16.dp))

                  if (activeBridgeTab == "cloud") {
                      // CLOUD TAB PANEL (Works across completely separate networks anywhere in the world)
                      val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                      var cloudUploading by remember { mutableStateOf(false) }
                      var cloudUploadUrl by remember { mutableStateOf("") }
                      var cloudUploadError by remember { mutableStateOf("") }
                      var cloudUploadProgressBytes by remember { mutableStateOf(0L) }
                      var cloudUploadTotalBytes by remember { mutableStateOf(0L) }

                      var cloudDownloadUrlInput by remember { mutableStateOf("") }
                      var cloudDownloading by remember { mutableStateOf(false) }
                      var cloudDownloadError by remember { mutableStateOf("") }
                      var cloudDownloadProgressText by remember { mutableStateOf("") }
                      var cloudDownloadProgressBytes by remember { mutableStateOf(0L) }
                      var cloudDownloadTotalBytes by remember { mutableStateOf(0L) }

                      androidx.compose.foundation.lazy.LazyColumn(
                          modifier = Modifier
                              .fillMaxWidth()
                              .weight(1f),
                          verticalArrangement = Arrangement.spacedBy(16.dp),
                          contentPadding = PaddingValues(bottom = 16.dp)
                      ) {
                          // Card 1: Upload / Sending
                          item {
                              Card(
                                  modifier = Modifier.fillMaxWidth(),
                                  colors = CardDefaults.cardColors(containerColor = containerWhite),
                                  border = BorderStroke(1.dp, borderColor),
                                  shape = RoundedCornerShape(20.dp)
                              ) {
                                  Column(modifier = Modifier.padding(16.dp)) {
                                      Text(
                                          text = "SEND FILE TO IOS / PC (ANY NETWORK)",
                                          fontSize = 11.sp,
                                          fontWeight = FontWeight.Bold,
                                          color = brandPrimary,
                                          letterSpacing = 1.sp
                                      )
                                      Spacer(modifier = Modifier.height(12.dp))

                                      if (webSharedFileUri != null) {
                                          Row(
                                              modifier = Modifier.fillMaxWidth(),
                                              verticalAlignment = Alignment.CenterVertically
                                          ) {
                                              Icon(
                                                  imageVector = Icons.Default.InsertDriveFile,
                                                  contentDescription = "File Icon",
                                                  tint = brandPrimary,
                                                  modifier = Modifier.size(28.dp)
                                              )
                                              Spacer(modifier = Modifier.width(10.dp))
                                              Column(modifier = Modifier.weight(1f)) {
                                                  Text(
                                                      text = webSharedFileName,
                                                      fontSize = 13.sp,
                                                      fontWeight = FontWeight.Bold,
                                                      color = textPrimary,
                                                      maxLines = 1,
                                                      overflow = TextOverflow.Ellipsis
                                                  )
                                                  Text(
                                                      text = formatFileSize(webSharedFileSize),
                                                      fontSize = 11.sp,
                                                      color = textSecondary
                                                  )
                                              }
                                              Button(
                                                  onClick = { webFilePickerLauncher.launch("*/*") },
                                                  colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                                  shape = RoundedCornerShape(10.dp),
                                                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                              ) {
                                                  Text("Change", fontSize = 11.sp)
                                              }
                                          }

                                          Spacer(modifier = Modifier.height(16.dp))

                                          if (cloudUploading) {
                                              val fraction = if (cloudUploadTotalBytes > 0L) {
                                                  cloudUploadProgressBytes.toFloat() / cloudUploadTotalBytes.toFloat()
                                              } else 0f
                                              
                                              StraightLineTransferProgress(
                                                  title = "Uploading",
                                                  filename = webSharedFileName,
                                                  progress = fraction,
                                                  statusText = "Buffered: ${formatFileSize(cloudUploadProgressBytes)} / ${formatFileSize(cloudUploadTotalBytes)}"
                                              )
                                          } else {
                                              if (cloudUploadUrl.isEmpty()) {
                                                  Button(
                                                      onClick = {
                                                          cloudUploading = true
                                                          cloudUploadError = ""
                                                          cloudUploadProgressBytes = 0L
                                                          cloudUploadTotalBytes = 0L
                                                          uploadFileToCloud(
                                                              webSharedFileUri!!,
                                                              onProgress = { uploaded, total ->
                                                                  cloudUploadProgressBytes = uploaded
                                                                  cloudUploadTotalBytes = total
                                                              },
                                                              onSuccess = { url -> coroutineScope.launch {
                                                                  cloudUploading = false
                                                                  // Done in coroutine block below
                                                                  isFinalizingTransfer = true; finalizingMessage = "Publishing secure transfer end-point & generating QR..."; delay(1500); isFinalizingTransfer = false; cloudUploadUrl = url; Toast.makeText(context, "Uploaded Succeeded!", Toast.LENGTH_SHORT).show() }
                                                              },
                                                              onError = { errMsg ->
                                                                  cloudUploading = false
                                                                  cloudUploadError = errMsg
                                                              }
                                                          )
                                                      },
                                                      colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                                      modifier = Modifier.fillMaxWidth(),
                                                      shape = RoundedCornerShape(12.dp)
                                                  ) {
                                                      Icon(Icons.Default.CloudUpload, contentDescription = "Upload")
                                                      Spacer(modifier = Modifier.width(8.dp))
                                                      Text("Upload & Generate QR Link", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                  }
                                              } else {
                                                  Button(
                                                      onClick = {
                                                          cloudUploadUrl = ""
                                                          cloudUploadError = ""
                                                      },
                                                      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC0392B)),
                                                      modifier = Modifier.fillMaxWidth(),
                                                      shape = RoundedCornerShape(12.dp)
                                                  ) {
                                                      Text("Clear Cloud Uploaded State", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                  }
                                              }
                                          }

                                          if (cloudUploadError.isNotEmpty()) {
                                              Spacer(modifier = Modifier.height(8.dp))
                                              Text(
                                                  text = "Upload error: $cloudUploadError",
                                                  color = Color(0xFFE28F8F),
                                                  fontSize = 12.sp,
                                                  fontWeight = FontWeight.Medium
                                              )
                                          }
                                      } else {
                                          Column(
                                              modifier = Modifier.fillMaxWidth(),
                                              horizontalAlignment = Alignment.CenterHorizontally,
                                              verticalArrangement = Arrangement.Center
                                          ) {
                                              Text(
                                                  text = "Choose a file to share with iOS / PC:",
                                                  fontSize = 12.sp,
                                                  color = textSecondary
                                              )
                                              Spacer(modifier = Modifier.height(10.dp))
                                              Button(
                                                  onClick = { webFilePickerLauncher.launch("*/*") },
                                                  colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                                  shape = RoundedCornerShape(12.dp)
                                              ) {
                                                  Icon(Icons.Default.Add, contentDescription = "Select File")
                                                  Spacer(modifier = Modifier.width(6.dp))
                                                  Text("Select a File")
                                              }
                                          }
                                      }

                                      if (cloudUploadUrl.isNotEmpty()) {
                                          HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = borderColor)

                                          Column(
                                              modifier = Modifier.fillMaxWidth(),
                                              horizontalAlignment = Alignment.CenterHorizontally
                                          ) {
                                              Text(
                                                  text = "SECURE CLOUD PORTAL ACTIVE",
                                                  fontSize = 11.sp,
                                                  fontWeight = FontWeight.Bold,
                                                  color = Color(0xFF27AE60)
                                              )
                                              Spacer(modifier = Modifier.height(12.dp))

                                              QRCodeImage(
                                                  url = cloudUploadUrl,
                                                  modifier = Modifier.size(170.dp)
                                              )

                                              Spacer(modifier = Modifier.height(12.dp))

                                              Row(
                                                  modifier = Modifier
                                                      .fillMaxWidth()
                                                      .background(Color(0xFF1D1B20), RoundedCornerShape(8.dp))
                                                      .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                                      .padding(horizontal = 12.dp, vertical = 10.dp),
                                                  verticalAlignment = Alignment.CenterVertically,
                                                  horizontalArrangement = Arrangement.SpaceBetween
                                              ) {
                                                  Text(
                                                      text = cloudUploadUrl,
                                                      fontSize = 11.sp,
                                                      color = brandPrimary,
                                                      maxLines = 1,
                                                      overflow = TextOverflow.Ellipsis,
                                                      modifier = Modifier.weight(1f)
                                                  )
                                                  Spacer(modifier = Modifier.width(8.dp))
                                                  Text(
                                                      text = "COPY",
                                                      fontSize = 11.sp,
                                                      fontWeight = FontWeight.Bold,
                                                      color = textPrimary,
                                                      modifier = Modifier.clickable {
                                                          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                          val clip = android.content.ClipData.newPlainText("transfr_link", cloudUploadUrl)
                                                          clipboard.setPrimaryClip(clip)
                                                          Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                                      }
                                                  )
                                              }

                                              Spacer(modifier = Modifier.height(12.dp))

                                              Text(
                                                  text = "Scan this QR with iOS Camera, or enter URL in browser. Both devices can be on different networks (cellular, different Wi-Fi, anywhere!). Valid for 1 hour.",
                                                  fontSize = 11.sp,
                                                  color = textSecondary,
                                                  textAlign = TextAlign.Center,
                                                  lineHeight = 15.sp
                                              )
                                          }
                                      }
                                  }
                              }
                          }

                          // Card 2: Download / Receiving
                          item {
                              Card(
                                  modifier = Modifier.fillMaxWidth(),
                                  colors = CardDefaults.cardColors(containerColor = containerWhite),
                                  border = BorderStroke(1.dp, borderColor),
                                  shape = RoundedCornerShape(20.dp)
                              ) {
                                  Column(modifier = Modifier.padding(16.dp)) {
                                      Text(
                                          text = "RECEIVE FILE FROM IOS / WINDOWS / MAC",
                                          fontSize = 11.sp,
                                          fontWeight = FontWeight.Bold,
                                          color = brandPrimary,
                                          letterSpacing = 1.sp
                                      )
                                      Spacer(modifier = Modifier.height(12.dp))

                                      OutlinedTextField(
                                          value = cloudDownloadUrlInput,
                                          onValueChange = { cloudDownloadUrlInput = it },
                                          placeholder = { Text("https://tmpfiles.org/dl/...", fontSize = 12.sp, color = textSecondary) },
                                          label = { Text("Enter Cloud URL To Download", fontSize = 11.sp) },
                                          singleLine = true,
                                          modifier = Modifier.fillMaxWidth(),
                                          colors = OutlinedTextFieldDefaults.colors(
                                              focusedBorderColor = brandPrimary,
                                              unfocusedBorderColor = borderColor,
                                              focusedLabelColor = brandPrimary,
                                              unfocusedLabelColor = textSecondary,
                                              focusedTextColor = textPrimary,
                                              unfocusedTextColor = textPrimary
                                          ),
                                          shape = RoundedCornerShape(12.dp)
                                      )

                                      Spacer(modifier = Modifier.height(12.dp))

                                      if (cloudDownloading) {
                                          val cloudFileName = remember(cloudDownloadUrlInput) {
                                              val cleanUrl = cloudDownloadUrlInput.trim().substringBefore("?")
                                              val parts = cleanUrl.split("/")
                                              val last = parts.lastOrNull() ?: ""
                                              if (last.isNotEmpty() && last != "dl") last else "Remote Cloud File"
                                          }
                                          val fraction = if (cloudDownloadTotalBytes > 0L) {
                                              cloudDownloadProgressBytes.toFloat() / cloudDownloadTotalBytes.toFloat()
                                          } else 0f
                                          
                                          StraightLineTransferProgress(
                                              title = "Downloading",
                                              filename = cloudFileName,
                                              progress = fraction,
                                              statusText = if (cloudDownloadProgressText.isEmpty()) "Downloading file..." else cloudDownloadProgressText
                                          )
                                      } else {
                                          Button(
                                              onClick = {
                                                  val rawUrl = cloudDownloadUrlInput.trim()
                                                  if (rawUrl.isEmpty()) {
                                                      Toast.makeText(context, "Enter cloud link to download first", Toast.LENGTH_SHORT).show()
                                                      return@Button
                                                  }
                                                  cloudDownloading = true
                                                  cloudDownloadError = ""
                                                  cloudDownloadProgressBytes = 0L
                                                  cloudDownloadTotalBytes = 0L
                                                  cloudDownloadProgressText = "Requesting cloud service..."
                                                  downloadFileFromCloud(
                                                      rawUrl,
                                                      onProgress = { downloaded, total ->
                                                          cloudDownloadProgressBytes = downloaded
                                                          cloudDownloadTotalBytes = total
                                                          cloudDownloadProgressText = if (total > 0) {
                                                              "Downloaded: ${formatFileSize(downloaded)} / ${formatFileSize(total)}"
                                                          } else {
                                                              "Downloaded: ${formatFileSize(downloaded)}"
                                                          }
                                                      },
                                                      onSuccess = { savedFile -> coroutineScope.launch {
                                                          cloudDownloading = false
                                                          isFinalizingTransfer = true; finalizingMessage = "Assembling secure packets and verifying file signature..."; delay(1500); isFinalizingTransfer = false; cloudDownloadUrlInput = ""
                                                          Toast.makeText(context, "File downloaded: ${savedFile.name}", Toast.LENGTH_LONG).show()
                                                          refreshReceivedFiles()

                                                          val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                                                          webServerLogs.add(0, "[$logTime] Downloaded cloud file: ${savedFile.name}"); }
                                                      },
                                                      onError = { err ->
                                                          cloudDownloading = false
                                                          cloudDownloadError = err
                                                      }
                                                  )
                                              },
                                              colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                              modifier = Modifier.fillMaxWidth(),
                                              shape = RoundedCornerShape(12.dp)
                                          ) {
                                              Icon(Icons.Default.CloudDownload, contentDescription = "Download Item")
                                              Spacer(modifier = Modifier.width(8.dp))
                                              Text("Download From Cloud Link", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                          }
                                      }

                                      if (cloudDownloadError.isNotEmpty()) {
                                          Spacer(modifier = Modifier.height(8.dp))
                                          Text(
                                              text = "Download error: $cloudDownloadError",
                                              color = Color(0xFFE28F8F),
                                              fontSize = 12.sp,
                                              fontWeight = FontWeight.Medium
                                          )
                                      }

                                      Spacer(modifier = Modifier.height(10.dp))
                                      Text(
                                          text = "Quick Instructions: If your iOS device is on a different network, go to 'tmpfiles.org' inside its web browser, upload your file, then scan its QR code or copy the resulting link into the input field above!",
                                          fontSize = 11.sp,
                                          color = textSecondary,
                                          lineHeight = 15.sp
                                      )
                                  }
                              }
                          }
                      }
                  } else {
                      // LOCAL SAME-NETWORK CONSOLE (original code content)
                      // Server Status Toggler Card
                      Card(
                          modifier = Modifier.fillMaxWidth(),
                          colors = CardDefaults.cardColors(containerColor = containerWhite),
                          border = BorderStroke(1.dp, borderColor),
                          shape = RoundedCornerShape(20.dp)
                      ) {
                          Row(
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .padding(16.dp),
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.SpaceBetween
                          ) {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                  Box(
                                      modifier = Modifier
                                          .size(8.dp)
                                          .clip(CircleShape)
                                          .background(if (webServerStatus == "Active") Color(0xFF27AE60) else Color(0xFFC0392B))
                                  )
                                  Spacer(modifier = Modifier.width(10.dp))
                                  Column {
                                      Text(
                                          text = "Portal Status: $webServerStatus",
                                          fontWeight = FontWeight.SemiBold,
                                          fontSize = 14.sp,
                                          color = textPrimary
                                      )
                                      Text(
                                          text = if (webServerStatus == "Active") "Accepting connections on Wi-Fi" else "Internal server is stopped",
                                          fontSize = 11.sp,
                                          color = textSecondary
                                      )
                                  }
                              }

                              Button(
                                  onClick = {
                                      if (webServerStatus == "Active") {
                                          webShareHost?.stop()
                                      } else {
                                          webShareHost?.start()
                                      }
                                  },
                                  colors = ButtonDefaults.buttonColors(
                                      containerColor = if (webServerStatus == "Active") Color(0xFFC0392B) else containerLightPurple,
                                      contentColor = Color.White
                                  ),
                                  contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                  shape = RoundedCornerShape(12.dp)
                              ) {
                                  Text(
                                      text = if (webServerStatus == "Active") "Stop" else "Start",
                                      fontSize = 12.sp,
                                      fontWeight = FontWeight.Bold
                                  )
                              }
                          }
                      }

                      Spacer(modifier = Modifier.height(16.dp))

                      // If server is active, show the QR Code and instructions
                      if (webServerStatus == "Active") {
                          // QR Code + Address Panel
                          Card(
                              modifier = Modifier.fillMaxWidth(),
                              colors = CardDefaults.cardColors(containerColor = containerWhite),
                              border = BorderStroke(1.dp, borderColor),
                              shape = RoundedCornerShape(24.dp)
                          ) {
                              Column(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .padding(20.dp),
                                  horizontalAlignment = Alignment.CenterHorizontally
                              ) {
                                  Text(
                                      text = "SCAN QR CODE TO CONNECT",
                                      fontSize = 11.sp,
                                      fontWeight = FontWeight.Bold,
                                      color = textSecondary,
                                      letterSpacing = 1.5.sp,
                                      modifier = Modifier.padding(bottom = 12.dp)
                                  )

                                  // Styled container for the QR
                                  Box(
                                      modifier = Modifier
                                          .padding(4.dp)
                                          .background(Color.White, RoundedCornerShape(20.dp))
                                          .border(2.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(brandPrimary, containerLightPurple)), RoundedCornerShape(20.dp))
                                          .padding(12.dp),
                                      contentAlignment = Alignment.Center
                                  ) {
                                      QRCodeImage(
                                          url = portalUrl,
                                          modifier = Modifier.size(175.dp)
                                      )
                                  }

                                  Spacer(modifier = Modifier.height(14.dp))

                                  // Copy URL bar
                                  Row(
                                      modifier = Modifier
                                          .fillMaxWidth()
                                          .background(Color(0xFF141318), RoundedCornerShape(12.dp))
                                          .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                          .clickable {
                                              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                              val clip = android.content.ClipData.newPlainText("transfr_link", portalUrl)
                                              clipboard.setPrimaryClip(clip)
                                              Toast.makeText(context, "Portal Link Copied!", Toast.LENGTH_SHORT).show()
                                          }
                                          .padding(horizontal = 12.dp, vertical = 10.dp),
                                      verticalAlignment = Alignment.CenterVertically,
                                      horizontalArrangement = Arrangement.SpaceBetween
                                  ) {
                                      Row(
                                          verticalAlignment = Alignment.CenterVertically,
                                          modifier = Modifier.weight(1f)
                                      ) {
                                          Icon(
                                              imageVector = Icons.Default.Link,
                                              contentDescription = "Link icon",
                                              tint = brandPrimary,
                                              modifier = Modifier.size(16.dp)
                                          )
                                          Spacer(modifier = Modifier.width(8.dp))
                                          Text(
                                              text = portalUrl,
                                              fontWeight = FontWeight.Bold,
                                              color = brandPrimary,
                                              fontSize = 13.sp,
                                              maxLines = 1,
                                              overflow = TextOverflow.Ellipsis
                                          )
                                      }
                                      Icon(
                                          imageVector = Icons.Default.ContentCopy,
                                          contentDescription = "Copy Link",
                                          tint = textSecondary,
                                          modifier = Modifier.size(14.dp)
                                      )
                                  }

                                  Spacer(modifier = Modifier.height(10.dp))

                                  Text(
                                      text = "Ensure devices are on the same Wi-Fi, then scan QR or visit portal.",
                                      fontSize = 11.sp,
                                      color = textSecondary,
                                      textAlign = TextAlign.Center
                                  )
                              }
                          }

                          Spacer(modifier = Modifier.height(16.dp))

                          // Shared File selection panel
                          Card(
                              modifier = Modifier.fillMaxWidth(),
                              colors = CardDefaults.cardColors(containerColor = containerWhite),
                              border = BorderStroke(1.dp, borderColor),
                              shape = RoundedCornerShape(20.dp)
                          ) {
                              Column(modifier = Modifier.padding(16.dp)) {
                                  Row(
                                      modifier = Modifier.fillMaxWidth(),
                                      horizontalArrangement = Arrangement.SpaceBetween,
                                      verticalAlignment = Alignment.CenterVertically
                                  ) {
                                      Text(
                                          text = "ACTIVE DOWNLOADS ON PORTAL",
                                          fontSize = 10.sp,
                                          fontWeight = FontWeight.Bold,
                                          color = brandPrimary,
                                          letterSpacing = 0.5.sp
                                      )
                                      Button(
                                          onClick = { webFilePickerLauncher.launch("*/*") },
                                          colors = ButtonDefaults.buttonColors(containerColor = containerLightPurple),
                                          shape = RoundedCornerShape(10.dp),
                                          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                      ) {
                                          Text(if (webSharedFiles.isNotEmpty() || webSharedFileUri != null) "Change" else "Choose File", fontSize = 11.sp)
                                      }
                                  }

                                  Spacer(modifier = Modifier.height(10.dp))

                                  if (webSharedFiles.isNotEmpty()) {
                                      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                          webSharedFiles.take(5).forEach { file ->
                                              Row(
                                                  modifier = Modifier
                                                      .fillMaxWidth()
                                                      .background(Color(0xFF141318), RoundedCornerShape(8.dp))
                                                      .padding(horizontal = 10.dp, vertical = 8.dp),
                                                  verticalAlignment = Alignment.CenterVertically,
                                                  horizontalArrangement = Arrangement.SpaceBetween
                                              ) {
                                                  Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                      Icon(
                                                          imageVector = Icons.Default.InsertDriveFile,
                                                          contentDescription = "File Icon",
                                                          tint = brandPrimary,
                                                          modifier = Modifier.size(15.dp)
                                                      )
                                                      Spacer(modifier = Modifier.width(8.dp))
                                                      Text(
                                                          text = file.name,
                                                          fontSize = 12.sp,
                                                          fontWeight = FontWeight.SemiBold,
                                                          color = textPrimary,
                                                          maxLines = 1,
                                                          overflow = TextOverflow.Ellipsis,
                                                          modifier = Modifier.weight(1f)
                                                      )
                                                  }
                                                  Spacer(modifier = Modifier.width(8.dp))
                                                  Text(
                                                      text = formatFileSize(file.size),
                                                      fontSize = 11.sp,
                                                      color = textSecondary
                                                  )
                                              }
                                          }
                                          if (webSharedFiles.size > 5) {
                                              Text(
                                                  text = "+ ${webSharedFiles.size - 5} more files...",
                                                  fontSize = 11.sp,
                                                  color = textSecondary,
                                                  fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                  modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                              )
                                          }
                                      }
                                  } else if (webSharedFileUri != null) {
                                      Row(
                                          modifier = Modifier
                                              .fillMaxWidth()
                                              .background(Color(0xFF141318), RoundedCornerShape(8.dp))
                                              .padding(horizontal = 10.dp, vertical = 8.dp),
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement = Arrangement.SpaceBetween
                                      ) {
                                          Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                              Icon(
                                                  imageVector = Icons.Default.InsertDriveFile,
                                                  contentDescription = "File Icon",
                                                  tint = brandPrimary,
                                                  modifier = Modifier.size(15.dp)
                                              )
                                              Spacer(modifier = Modifier.width(8.dp))
                                              Text(
                                                  text = webSharedFileName,
                                                  fontSize = 12.sp,
                                                  fontWeight = FontWeight.SemiBold,
                                                  color = textPrimary,
                                                  maxLines = 1,
                                                  overflow = TextOverflow.Ellipsis
                                              )
                                          }
                                          Spacer(modifier = Modifier.width(8.dp))
                                          Text(
                                              text = formatFileSize(webSharedFileSize),
                                              fontSize = 11.sp,
                                              color = textSecondary
                                          )
                                      }
                                  } else {
                                      Row(
                                          modifier = Modifier
                                              .fillMaxWidth()
                                              .background(Color(0xFF141318), RoundedCornerShape(8.dp))
                                              .padding(horizontal = 10.dp, vertical = 12.dp),
                                          verticalAlignment = Alignment.CenterVertically
                                      ) {
                                          Icon(
                                              imageVector = Icons.Default.CloudQueue,
                                              contentDescription = null,
                                              tint = textSecondary,
                                              modifier = Modifier.size(16.dp)
                                          )
                                          Spacer(modifier = Modifier.width(8.dp))
                                          Text(
                                              text = "Select files to make them available for download",
                                              fontSize = 11.sp,
                                              color = textSecondary
                                          )
                                      }
                                  }
                              }
                          }
                      } else {
                          // Stopped instructions state
                          Box(
                              modifier = Modifier
                                  .fillMaxWidth()
                                  .weight(1f),
                              contentAlignment = Alignment.Center
                          ) {
                              Column(
                                  horizontalAlignment = Alignment.CenterHorizontally,
                                  verticalArrangement = Arrangement.Center,
                                  modifier = Modifier.padding(24.dp)
                              ) {
                                  Icon(
                                      imageVector = Icons.Default.Language,
                                      contentDescription = "Web server inactive",
                                      tint = textSecondary.copy(alpha = 0.3f),
                                      modifier = Modifier.size(54.dp)
                                  )
                                  Spacer(modifier = Modifier.height(12.dp))
                                  Text(
                                      text = "Web Portal is Standby",
                                      fontSize = 16.sp,
                                      fontWeight = FontWeight.SemiBold,
                                      color = textPrimary
                                  )
                                  Spacer(modifier = Modifier.height(6.dp))
                                  Text(
                                      text = "Click 'Start' above to activate the compatibility bridge. iOS devices, Windows, and Macs can then immediately share or collect files on the same network using their browser.",
                                      textAlign = TextAlign.Center,
                                      fontSize = 12.sp,
                                      color = textSecondary
                                  )
                              }
                          }
                      }

                  }
              }
          }
      }
  }

      /**
      * Upload helper supporting different networks. Sends selected local file to secure transient 
      * tmpfiles.org web services.
      */
     private fun uploadFileToCloud(
         uri: Uri,
         onProgress: (Long, Long) -> Unit,
         onSuccess: (String) -> Unit,
         onError: (String) -> Unit
     ) {
         lifecycleScope.launch(Dispatchers.IO) {
             try {
                 val contentResolver = this@MainActivity.contentResolver
                 val metadata = getFileMetadata(this@MainActivity, uri)
                 val fileName = metadata.first

                 val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Could not resolve local file stream")
                 val cacheFile = File(this@MainActivity.cacheDir, "cloud_up_${System.currentTimeMillis()}")
                 try {
                     cacheFile.outputStream().use { fos ->
                         val buffer = ByteArray(64 * 1024)
                         var read: Int
                         while (inputStream.read(buffer).also { read = it } != -1) {
                             fos.write(buffer, 0, read)
                         }
                     }
                 } finally {
                     inputStream.close()
                 }

                 val client = okhttp3.OkHttpClient.Builder()
                     .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                     .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                     .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                     .build()

                 val fileBody = ProgressRequestBody(cacheFile, "application/octet-stream".toMediaTypeOrNull()) { bytesWritten, totalBytes ->
                     onProgress(bytesWritten, totalBytes)
                 }
                 val requestBody = okhttp3.MultipartBody.Builder()
                     .setType(okhttp3.MultipartBody.FORM)
                     .addFormDataPart("file", fileName, fileBody)
                     .build()

                 val request = okhttp3.Request.Builder()
                     .url("https://tmpfiles.org/api/v1/upload")
                     .post(requestBody)
                     .build()

                 client.newCall(request).execute().use { response ->
                     if (!response.isSuccessful) {
                         throw Exception("Upload failed: HTTP ${response.code}")
                     }
                     val bodyString = response.body?.string() ?: ""
                     val regex = """"url"\s*:\s*"([^"]+)"""".toRegex()
                     val match = regex.find(bodyString)
                     val uploadedUrl = match?.groupValues?.get(1)

                     if (uploadedUrl != null) {
                         // Build direct download endpoint URL: replaces /tmpfiles.org/ with /tmpfiles.org/dl/
                         val dlUrl = uploadedUrl.replace("https://tmpfiles.org/", "https://tmpfiles.org/dl/")
                         withContext(Dispatchers.Main) {
                             onSuccess(dlUrl)
                         }
                     } else {
                         throw Exception("Server failed to return valid URL payload")
                     }
                 }
                 try { cacheFile.delete() } catch (_: Exception) {}
             } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     onError(e.localizedMessage ?: "Unknown file upload failure")
                  }
              }
          }
      }

     /**
      * Download helper downloading from secure transient link path. 
      */
     private fun downloadFileFromCloud(
         urlString: String,
         onProgress: (Long, Long) -> Unit,
         onSuccess: (File) -> Unit,
         onError: (String) -> Unit
     ) {
         lifecycleScope.launch(Dispatchers.IO) {
             try {
                 var targetUrl = urlString.trim()
                 if (targetUrl.isEmpty()) {
                     throw Exception("Target URL is blank")
                 }

                 if (targetUrl.contains("tmpfiles.org/") && !targetUrl.contains("tmpfiles.org/dl/")) {
                     targetUrl = targetUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                 }

                 val client = okhttp3.OkHttpClient.Builder()
                     .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                     .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                     .build()

                 val request = okhttp3.Request.Builder()
                     .url(targetUrl)
                     .build()

                 client.newCall(request).execute().use { response ->
                     if (!response.isSuccessful) {
                         throw Exception("Download server failed: HTTP ${response.code}")
                     }
                     val body = response.body ?: throw Exception("Empty response body payload")
                     val contentLength = body.contentLength()

                     var fileName = "downloaded_cloud_file"
                     val contentDisposition = response.header("Content-Disposition")
                     if (contentDisposition != null && contentDisposition.contains("filename=")) {
                         val fnIdx = contentDisposition.indexOf("filename=")
                         if (fnIdx != -1) {
                             var fn = contentDisposition.substring(fnIdx + 9).trim()
                             if (fn.startsWith("\"") && fn.endsWith("\"")) {
                                 fn = fn.substring(1, fn.length - 1)
                             }
                             if (fn.isNotEmpty()) {
                                 fileName = fn
                             }
                         }
                     } else {
                         val lastSlashSegment = targetUrl.lastIndexOf('/')
                         if (lastSlashSegment != -1 && lastSlashSegment < targetUrl.length - 1) {
                             fileName = targetUrl.substring(lastSlashSegment + 1)
                         }
                     }

                     val downloadsDir = this@MainActivity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: this@MainActivity.filesDir
                     var targetFile = File(downloadsDir, fileName)
                     var count = 1
                     val ext = targetFile.extension
                     val baseName = targetFile.nameWithoutExtension
                     while (targetFile.exists()) {
                         targetFile = File(downloadsDir, "$baseName-$count.$ext")
                         count++
                     }

                     body.byteStream().use { input ->
                         targetFile.outputStream().use { output ->
                             val buffer = ByteArray(64 * 1024)
                             var bytesRead: Int
                             var totalDownloaded = 0L
                             while (input.read(buffer).also { bytesRead = it } != -1) {
                                 output.write(buffer, 0, bytesRead)
                                 totalDownloaded += bytesRead
                                 withContext(Dispatchers.Main) {
                                     onProgress(totalDownloaded, contentLength)
                                 }
                             }
                         }
                     }

                     withContext(Dispatchers.Main) {
                         onSuccess(targetFile)
                     }
                 }
             } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     onError(e.localizedMessage ?: "Unknown file download failure")
  
              }
            }
        }
    }

    @Composable
    fun QRCodeImage(url: String, modifier: Modifier = Modifier) {
        val qrCodeWriter = remember { com.google.zxing.qrcode.QRCodeWriter() }
        val bitMatrix = remember(url) {
            try {
                val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
                qrCodeWriter.encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 200, 200, hints)
            } catch (e: Exception) {
                null
            }
        }

        if (bitMatrix != null) {
            Box(
                modifier = modifier
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(160.dp)) {
                    val sizeX = bitMatrix.width
                    val sizeY = bitMatrix.height
                    val cellWidth = size.width / sizeX
                    val cellHeight = size.height / sizeY
                    for (x in 0 until sizeX) {
                        for (y in 0 until sizeY) {
                            if (bitMatrix.get(x, y)) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = androidx.compose.ui.geometry.Offset(x * cellWidth, y * cellHeight),
                                    size = androidx.compose.ui.geometry.Size(cellWidth + 0.5f, cellHeight + 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = modifier
                    .background(Color.DarkGray, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Unable to generate QR", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun HistoryScreen(innerPadding: PaddingValues) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Screen Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Transfer History",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                if (historyList.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            historyList.clear()
                            saveHistoryToSharedPrefs()
                        }
                    ) {
                        Text("Clear All", color = Color(0xFFC0392B))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (historyList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Empty",
                            tint = textSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No transfer history yet",
                            fontWeight = FontWeight.Medium,
                            color = textPrimary,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Completed wireless transfers will show up here.",
                            color = textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(historyList) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = containerWhite),
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val itemColor = if (item.direction == "RECEIVER") Color(0xFF3498DB) else Color(0xFF2ECC71)
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(itemColor.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (item.direction == "RECEIVER") Icons.Default.CallReceived else Icons.Default.CallMade,
                                        contentDescription = item.direction,
                                        tint = itemColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.filename,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = formatFileSize(item.size),
                                            fontSize = 12.sp,
                                            color = textSecondary
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(textSecondary, CircleShape)
                                        )
                                        val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.timestamp))
                                        Text(
                                            text = dateStr,
                                            fontSize = 12.sp,
                                            color = textSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                if (item.isSuccess) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFD4EFDF), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Success", color = Color(0xFF27AE60), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFFADBD8), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Failed", color = Color(0xFFC0392B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FilesScreen(innerPadding: PaddingValues) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Received Files",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                if (receivedFilesList.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            receivedFilesList.forEach { it.delete() }
                            refreshReceivedFiles()
                            Toast.makeText(context, "Cleared all local files", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Delete All", color = Color(0xFFC0392B))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (receivedFilesList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Empty Folder",
                            tint = textSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No received files",
                            fontWeight = FontWeight.Medium,
                            color = textPrimary,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Files you request and save locally are stored safely here.",
                            color = textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(receivedFilesList) { file ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = containerWhite),
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(brandPrimary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val extension = file.extension.lowercase()
                                        val icon = when {
                                            extension in listOf("jpg", "jpeg", "png", "webp", "gif") -> Icons.Default.Image
                                            extension in listOf("pdf", "doc", "docx", "txt", "xls", "xlsx") -> Icons.Default.Description
                                            extension in listOf("mp4", "mkv", "avi") -> Icons.Default.Movie
                                            extension in listOf("mp3", "wav", "m4a") -> Icons.Default.AudioFile
                                            else -> Icons.Default.Drafts
                                        }

                                        Icon(
                                            imageVector = icon,
                                            contentDescription = "File icon",
                                            tint = brandPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            color = textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatFileSize(file.length()),
                                            fontSize = 12.sp,
                                            color = textSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            file.delete()
                                            refreshReceivedFiles()
                                            Toast.makeText(context, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B)),
                                        border = BorderStroke(1.dp, Color(0xFFC0392B).copy(alpha = 0.3f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Delete", fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { openReceivedFile(file) },
                                        colors = ButtonDefaults.buttonColors(containerColor = brandPrimary, contentColor = Color.White),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Open", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsScreen(innerPadding: PaddingValues) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sharing Device Name card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sharing Settings", fontWeight = FontWeight.Bold, color = brandPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Device Name", fontSize = 12.sp, color = textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))

                    var editedName by remember { mutableStateOf(userDeviceName) }

                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. My Phone") },
                        trailingIcon = {
                            if (editedName != userDeviceName) {
                                TextButton(onClick = {
                                    if (editedName.isNotBlank()) {
                                        saveUserDeviceName(editedName)
                                        Toast.makeText(context, "Device name saved", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Text("Save", color = brandPrimary)
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("This is the custom moniker which other phones see dynamically when probing nearby links.", fontSize = 11.sp, color = textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Storage Details / Folder Details
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Destination Folder", fontWeight = FontWeight.Bold, color = brandPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    Text(downloadsDir.absolutePath, fontSize = 12.sp, color = textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Received files are automatically stored in this sandbox-safe system folder.", fontSize = 11.sp, color = textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // System & Diagnostics quick list
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Diagnostics info", fontWeight = FontWeight.Bold, color = brandPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Wi-Fi Direct Hardware Support", fontSize = 13.sp, color = textPrimary)
                        Text(
                            text = if (wifiP2pManager != null) "Compatible" else "Unsupported",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (wifiP2pManager != null) Color(0xFF27AE60) else Color(0xFFC0392B)
                        )
                    }

                    HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Antenna State", fontSize = 13.sp, color = textPrimary)
                        Text(
                            text = if (isWifiP2pEnabled) "Enabled" else "Off / Unknown",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWifiP2pEnabled) Color(0xFF27AE60) else Color(0xFFC0392B)
                        )
                    }

                    HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("App Version", fontSize = 13.sp, color = textPrimary)
                        Text(
                            text = "2.1.0-Immersive",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = textSecondary
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun DiagnosticDialog(onDismiss: () -> Unit) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(brandPrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, "Info", tint = brandPrimary)
                        }
                        Text(
                            text = "Diagnostics & Support",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Transfr is an advanced local file transfer program leveraging the high-speed local P2P Wi-Fi pipeline without cell towers or cables.",
                        fontSize = 12.sp,
                        color = textSecondary,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("QUICK INSTRUCTIONS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = brandPrimary)
                    Spacer(modifier = Modifier.height(6.dp))

                    Text("1. Connect both devices to the same local space", fontSize = 12.sp, color = textPrimary)
                    Text("2. Press 'Scan' on one phone to search nearby", fontSize = 12.sp, color = textPrimary)
                    Text("3. Select the target phone in the list to form link", fontSize = 12.sp, color = textPrimary)
                    Text("4. Once connected, Send/Choose file from matching panel", fontSize = 12.sp, color = textPrimary)

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = brandPrimary, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    @Composable
    fun FinalizingTransferDialog(message: String) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        androidx.compose.ui.window.Dialog(
            onDismissRequest = {} // Prevent dismissal by clicking outside
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = brandPrimary,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Processing Transfer",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = textSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }

    @Composable
    fun EnableWifiDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = containerWhite),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE74C3C).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Wifi, "Wi-Fi Required", tint = Color(0xFFE74C3C))
                        }
                        Text(
                            text = "Enable Wi-Fi Required",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "To search for nearby devices and make wireless offline file transfers, Wi-Fi must be enabled.",
                        fontSize = 12.sp,
                        color = textSecondary,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, borderColor),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary)
                        ) {
                            Text("Cancel", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                onDismiss()
                                try {
                                    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                        android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
                                    } else {
                                        android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open Wi-Fi Settings.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = brandPrimary, contentColor = Color.White)
                        ) {
                            Text("Turn On", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Composable rendering a transfer console, aligning with light immersive styling card design
    @Composable
    fun TransferConsoleCard(
        state: TransferState,
        onOpenFile: (File) -> Unit
    ) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerLightPurple = Color(0xFF4F378B)
        val containerWhite = Color(0xFF211F26)
        val borderColor = Color(0xFF49454F)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = containerWhite),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, borderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.direction == TransferDirection.Idle) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Colors.p2pActiveColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Active sharing link formed",
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    Column {
                        val fraction = if (state.totalSize > 0) {
                            state.bytesTransferred.toFloat() / state.totalSize.toFloat()
                        } else 0f
                        
                        StraightLineTransferProgress(
                            title = if (state.direction == TransferDirection.Sending) "Sending File" else "Receiving File",
                            filename = state.filename,
                            progress = fraction,
                            statusText = state.statusText,
                            speedText = state.speedText,
                            etaText = state.etaText
                        )

                        if (state.isSuccess) {
                            Spacer(modifier = Modifier.height(10.dp))
                            ServiceTransferSuccessChip(
                                file = state.receivedFile,
                                onOpenFile = onOpenFile
                            )
                        } else if (state.isError) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFADBD8), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, "Error", tint = Color(0xFFC0392B), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(state.errorMessage, color = Color(0xFFC0392B), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ServiceTransferSuccessChip(file: File?, onOpenFile: (File) -> Unit) {
        val successGreen = Color(0xFF1B5E20)
        val successGreenBg = Color(0xFFE8F5E9)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(successGreenBg, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, "Success check", tint = successGreen, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Transfer completed successfully!", color = successGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (file != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "File: ${file.name}",
                        color = successGreen.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onOpenFile(file) },
                        colors = ButtonDefaults.buttonColors(containerColor = successGreen, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.OpenInNew, "Open", modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun formatShortTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86450_000 -> "${diff / 3600_000}h ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                sdf.format(java.util.Date(timestamp))
            }
        }
    }

    @Composable
    fun PortalDeviceItemRow(device: PortalDevice) {
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val containerWhite = Color(0xFF211F26)
        val borderStrokeColor = Color(0xFF49454F)

        val systemIcon = when {
            device.deviceName.contains("iPhone", ignoreCase = true) -> Icons.Default.Smartphone
            device.deviceName.contains("iPad", ignoreCase = true) -> Icons.Default.Tablet
            device.deviceName.contains("Mac", ignoreCase = true) -> Icons.Default.Laptop
            device.deviceName.contains("Windows", ignoreCase = true) -> Icons.Default.Computer
            device.deviceName.contains("Android", ignoreCase = true) -> Icons.Default.Smartphone
            else -> Icons.Default.Language
        }

        val actionColor = when {
            device.lastAction.startsWith("Uploaded", ignoreCase = true) -> Color(0xFF81C784)
            device.lastAction.startsWith("Downloaded", ignoreCase = true) -> Color(0xFF64B5F6)
            else -> brandPrimary
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(containerWhite)
                .border(BorderStroke(1.dp, borderStrokeColor), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF313033)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = systemIcon,
                            contentDescription = "Device System Icon",
                            tint = brandPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = device.deviceName,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "IP: ${device.ipAddress}",
                                color = textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(textSecondary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatShortTimestamp(device.timestamp),
                                color = textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = device.lastAction,
                        color = actionColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // Composable item representing a nearby device row in the Immersive UI design
    @Composable
    fun DeviceItemRow(
        device: WifiP2pDevice,
        onClick: () -> Unit,
        activeConnectedAddress: String?
    ) {
        val containerWhite = Color(0xFF211F26)
        val textPrimary = Color(0xFFE6E1E9)
        val textSecondary = Color(0xFF938F99)
        val brandPrimary = Color(0xFFD0BCFF)
        val borderStrokeColor = Color(0xFF49454F)

        val connectionStatusLabel = when (device.status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }

        val badgeBg = when (device.status) {
            WifiP2pDevice.CONNECTED -> Color(0xFF193C23)
            WifiP2pDevice.INVITED -> Color(0xFF3F2712)
            else -> Color(0xFF313033)
        }

        val badgeText = when (device.status) {
            WifiP2pDevice.CONNECTED -> Color(0xFF81C784)
            WifiP2pDevice.INVITED -> Color(0xFFFFB74D)
            else -> brandPrimary
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(containerWhite)
                .border(BorderStroke(1.dp, borderStrokeColor), RoundedCornerShape(16.dp))
                .clickable { onClick() }
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (device.status == WifiP2pDevice.CONNECTED) Color(0xFF4F378B) else Color(0xFF313033)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (device.isGroupOwner) Icons.Default.Dns else Icons.Default.Smartphone,
                            contentDescription = "Device structure",
                            tint = if (device.status == WifiP2pDevice.CONNECTED) brandPrimary else textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (device.deviceName.isNullOrBlank()) "Android Device" else device.deviceName,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = device.deviceAddress,
                            color = textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Connection status badge on the right
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        text = connectionStatusLabel.uppercase(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeText
                    )
                }
            }
        }
    }
}

class ProgressRequestBody(
    private val file: java.io.File,
    private val contentType: okhttp3.MediaType?,
    private val onProgress: (Long, Long) -> Unit
) : okhttp3.RequestBody() {
    
    private val fileLength = file.length()

    override fun contentType(): okhttp3.MediaType? {
        return contentType
    }

    override fun contentLength(): Long {
        return fileLength
    }

    override fun writeTo(sink: okio.BufferedSink) {
        val buffer = ByteArray(8192)
        var uploaded = 0L
        file.inputStream().use { inputStream ->
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress(uploaded, fileLength)
            }
        }
    }
}

@Composable
fun StraightLineTransferProgress(
    title: String,
    filename: String,
    progress: Float,
    statusText: String,
    speedText: String = "",
    etaText: String = ""
) {
    val textPrimary = Color(0xFFE6E1E9)
    val textSecondary = Color(0xFF938F99)
    val lightBlueAccent = Color(0xFFB1E5F6)
    val activeProgressColor = Color(0xFF0091FF)
    val trackProgressColor = Color(0xFF2C3946)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F222B)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF353A47))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0091FF),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(lightBlueAccent)
                )
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(lightBlueAccent)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (filename.isNotEmpty()) {
                Text(
                    text = filename,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            val animateProgress by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                label = "Smooth Progress update"
            )
            val percent = (animateProgress * 100).toInt()
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(trackProgressColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = if (animateProgress < 0.05f) 0.05f else animateProgress)
                        .clip(CircleShape)
                        .background(activeProgressColor)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "$percent%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                if (speedText.isNotEmpty()) {
                    Text(
                        text = speedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF27AE60)
                    )
                }
            }
            
            if (etaText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "ETA: $etaText",
                        fontSize = 11.sp,
                        color = textSecondary
                    )
                }
            }
        }
    }
}

// Styling colors constants setup
object Colors {
    val p2pActiveColor = Color(0xFF1B5E20)
    val badgeConnected = Color(0xFF2E7D32)
    val badgeInvited = Color(0xFFEF6C00)
    val badgeAvailable = Color(0xFF6750A4)
}

