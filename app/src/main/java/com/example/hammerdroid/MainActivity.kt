package com.example.hammerdroid

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
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val session by lazy {
        ViewModelProvider(this)[GtSessionViewModel::class.java]
    }
    private var receiverRegistered = false

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            session.permissionGranted = granted
            if (granted) refreshDevices() else session.append("Bluetooth permission denied.")
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBluetoothState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GtFlash)
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF22B8FF),
                    secondary = Color(0xFF42E8FF),
                    background = Color(0xFF050A10),
                    surface = Color(0xFF101923)
                )
            ) {
                Surface(Modifier.fillMaxSize()) {
                    GtFlashScreen(
                        session = session,
                        onRefresh = ::refreshDevices,
                        onRequestPermission = ::ensurePermission,
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    )
                }
            }
        }
        ensurePermission()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(stateReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshBluetoothState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun ensurePermission() {
        if (
            Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            session.permissionGranted = false
            permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            session.permissionGranted = true
            refreshDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetoothState() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val permitted = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = adapter != null && permitted &&
            runCatching { adapter.isEnabled }.getOrDefault(false)
        session.updateBluetoothState(adapter != null, enabled, permitted)
        if (enabled) refreshDevices()
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            session.updateBluetoothState(false, false, session.permissionGranted)
            return
        }
        if (!session.permissionGranted) return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            session.updateBluetoothState(true, false, true)
            return
        }
        val devices = runCatching {
            adapter.bondedDevices
                .filter { (it.name ?: "").startsWith("OBDX", ignoreCase = true) }
                .sortedBy { it.name }
        }.getOrElse {
            session.append("Could not read paired Bluetooth devices.")
            emptyList()
        }
        session.updateDevices(devices)
    }
}

@Composable
private fun GtFlashScreen(
    session: GtSessionViewModel,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val pageScroll = rememberScrollState()
    val logScroll = rememberScrollState()
    LaunchedEffect(session.logText) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp)
            .verticalScroll(pageScroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text("OBDX Pro GT • Bluetooth • PCM identification")

        when {
            !session.bluetoothAvailable ->
                GuidanceCard("Bluetooth is not available on this Android device.")
            !session.permissionGranted -> {
                GuidanceCard("Bluetooth permission is required to list and connect to the adapter.")
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Bluetooth permission")
                }
            }
            !session.bluetoothEnabled -> {
                GuidanceCard("Bluetooth is off. Turn it on, pair the GT, then return and refresh.")
                Button(onClick = onOpenBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Bluetooth settings")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Paired adapter", style = MaterialTheme.typography.labelLarge)
                Text(session.deviceLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (session.connected) "Connected" else "Disconnected",
                    color = if (session.connected) {
                        Color(0xFF42E8A8)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = session::previousDevice,
                        enabled = session.devices.size > 1 && !session.busy && !session.connected,
                        modifier = Modifier.weight(1f)
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = session::nextDevice,
                        enabled = session.devices.size > 1 && !session.busy && !session.connected,
                        modifier = Modifier.weight(1f)
                    ) { Text("Next") }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = session.bluetoothEnabled &&
                            session.permissionGranted && !session.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Refresh") }
                }
                if (session.connected) {
                    OutlinedButton(
                        onClick = session::resetAdapter,
                        enabled = !session.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reset adapter") }
                    Button(
                        onClick = session::disconnect,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = session::connect,
                        enabled = session.canConnect,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (session.busy) "Connecting…" else "Connect GT") }
                }
            }
        }

        Button(
            onClick = session::identify,
            enabled = session.connected && !session.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (session.busy) "Working…" else "Identify PCM") }

        if (session.busy) {
            OutlinedButton(
                onClick = session::cancelOperation,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel operation") }
        }

        session.identity?.let { pcm ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("PCM identity", style = MaterialTheme.typography.titleMedium)
                    Text("VIN: ${if (session.showVin) pcm.vin else session.maskedVin}")
                    Text("OS ID: ${pcm.osId}")
                    OutlinedButton(onClick = session::toggleVin) {
                        Text(if (session.showVin) "Hide VIN" else "Show VIN")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Activity", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = session::clearLog,
                        enabled = session.logText.isNotBlank()
                    ) { Text("Clear") }
                }
                Text(
                    session.logText.ifBlank { "No activity yet." },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(logScroll),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun GuidanceCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            Modifier.padding(14.dp),
            color = Color(0xFFFFC857)
        )
    }
}

class GtSessionViewModel : ViewModel() {
    private val transport = BluetoothSppTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: ObdxGtClient? = null
    private var operation: Job? = null
    private var connectedAddress: String? = null
    private var entries = listOf(
        "Pair the OBDX Pro GT in Android Bluetooth settings, then tap Refresh."
    )

    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set
    var selectedIndex by mutableIntStateOf(0)
        private set
    var logText by mutableStateOf(entries.joinToString("\n"))
        private set
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var bluetoothAvailable by mutableStateOf(true)
        private set
    var bluetoothEnabled by mutableStateOf(false)
        private set
    var permissionGranted by mutableStateOf(false)
    var identity by mutableStateOf<PcmIdentity?>(null)
        private set
    var showVin by mutableStateOf(false)
        private set

    val canConnect: Boolean
        get() = bluetoothAvailable && bluetoothEnabled && permissionGranted &&
            devices.isNotEmpty() && !busy

    val deviceLabel: String
        @SuppressLint("MissingPermission")
        get() = devices.getOrNull(selectedIndex)?.let { device ->
            val position = if (devices.size > 1) {
                " (${selectedIndex + 1}/${devices.size})"
            } else {
                ""
            }
            "${device.name ?: "OBDX adapter"}$position"
        } ?: "No paired OBDX Pro GT found"

    val maskedVin: String
        get() = identity?.vin?.let { "•••••••••••••${it.takeLast(4)}" } ?: ""

    fun updateBluetoothState(
        available: Boolean,
        enabled: Boolean,
        permitted: Boolean
    ) {
        val wasEnabled = bluetoothEnabled
        bluetoothAvailable = available
        bluetoothEnabled = enabled
        permissionGranted = permitted
        if (wasEnabled && !enabled) disconnect("Bluetooth turned off.")
    }

    @SuppressLint("MissingPermission")
    fun updateDevices(newDevices: List<BluetoothDevice>) {
        val selectedAddress = devices.getOrNull(selectedIndex)?.address
        devices = newDevices
        selectedIndex = newDevices.indexOfFirst { it.address == selectedAddress }
            .takeIf { it >= 0 } ?: 0
        if (connected && newDevices.none { it.address == connectedAddress }) {
            disconnect("Connected adapter is no longer paired.")
        }
        append(
            if (newDevices.isEmpty()) {
                "No paired OBDX adapter found."
            } else {
                "Found ${newDevices.size} paired OBDX adapter(s)."
            }
        )
    }

    fun previousDevice() {
        if (devices.isNotEmpty() && !connected) {
            selectedIndex = (selectedIndex - 1 + devices.size) % devices.size
        }
    }

    fun nextDevice() {
        if (devices.isNotEmpty() && !connected) {
            selectedIndex = (selectedIndex + 1) % devices.size
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        val device = devices.getOrNull(selectedIndex) ?: return
        operation?.cancel()
        operation = scope.launch {
            busy = true
            identity = null
            showVin = false
            try {
                append("Connecting to ${device.name ?: "OBDX adapter"}…")
                transport.connect(device)
                val connectedClient = ObdxGtClient(transport)
                val info = connectedClient.initialize(::append)
                client = connectedClient
                connected = true
                connectedAddress = device.address
                append("${info.name} connected.")
                append("Firmware ${info.firmware} • Hardware ${info.hardware}")
                append(
                    "Vehicle voltage %.2f V".format(
                        Locale.US,
                        info.voltage
                    )
                )
                if (info.voltage < 12.0) {
                    append("WARNING: voltage is too low for programming.")
                }
            } catch (_: CancellationException) {
                transport.close()
            } catch (error: Throwable) {
                connected = false
                connectedAddress = null
                client = null
                transport.close()
                append("Connection failed: ${error.message ?: "unknown error"}")
            } finally {
                busy = false
            }
        }
    }

    fun identify() {
        if (!connected) return
        operation?.cancel()
        operation = scope.launch {
            busy = true
            try {
                val connectedClient = client ?: error("GT session is not initialized")
                check(connectedClient.checkConnection()) {
                    "Adapter connection-health check failed"
                }
                identity = connectedClient.identifyPcm(::append)
                showVin = false
                append("PCM identified. VIN is hidden in the identity card.")
            } catch (_: CancellationException) {
                // Cancellation is reported by cancelOperation.
            } catch (error: Throwable) {
                append("Identification failed: ${error.message ?: "unknown error"}")
            } finally {
                busy = false
            }
        }
    }

    fun resetAdapter() {
        val connectedClient = client ?: return
        operation?.cancel()
        operation = scope.launch {
            busy = true
            try {
                connectedClient.resetAdapter()
                append("Adapter reset. Reconnect before another PCM request.")
            } catch (_: CancellationException) {
                append("Adapter reset cancelled.")
            } catch (error: Throwable) {
                append("Adapter reset failed: ${error.message ?: "unknown error"}")
            } finally {
                transport.close()
                client = null
                connected = false
                connectedAddress = null
                busy = false
            }
        }
    }

    fun cancelOperation() {
        operation?.cancel()
        operation = null
        transport.close()
        client = null
        connected = false
        connectedAddress = null
        busy = false
        append("Operation cancelled; adapter disconnected to clear partial data.")
    }

    fun disconnect() = disconnect("Disconnected.")

    private fun disconnect(message: String) {
        operation?.cancel()
        operation = null
        transport.close()
        client = null
        connected = false
        connectedAddress = null
        busy = false
        identity = null
        showVin = false
        append(message)
    }

    fun toggleVin() {
        if (identity != null) showVin = !showVin
    }

    fun clearLog() {
        entries = emptyList()
        logText = ""
    }

    fun append(message: String) {
        val safeLines = message.lineSequence()
            .map(::redactSensitiveData)
            .filter { it.isNotBlank() }
            .toList()
        entries = (entries + safeLines).takeLast(MAX_LOG_LINES)
        logText = entries.joinToString("\n")
    }

    private fun redactSensitiveData(message: String): String = message
        .replace(MAC_ADDRESS, "••:••:••:••:••:••")
        .replace(VIN, "•••••••••••••[VIN]")

    override fun onCleared() {
        operation?.cancel()
        transport.close()
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_LOG_LINES = 50
        val MAC_ADDRESS = Regex("(?i)(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
        val VIN = Regex("(?i)\\b[A-HJ-NPR-Z0-9]{17}\\b")
    }
}
