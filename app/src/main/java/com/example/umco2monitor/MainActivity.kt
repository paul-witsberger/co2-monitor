package com.example.umco2monitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.umco2monitor.ui.theme.UMCO2MonitorTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Main activity of the application. This is the entry point of the app.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: SensorViewModel by viewModels { SensorViewModelFactory(application) }

    private val requestMultiplePermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) false else permissions[Manifest.permission.ACCESS_FINE_LOCATION] == false
            val bluetoothConnectDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions[Manifest.permission.BLUETOOTH_CONNECT] == false else false
            val bluetoothScanDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions[Manifest.permission.BLUETOOTH_SCAN] == false else false

            if (fineLocationDenied || bluetoothConnectDenied || bluetoothScanDenied) {
                Toast.makeText(this, "Bluetooth and location permissions are required.", Toast.LENGTH_LONG).show()
                viewModel.onPermissionsDenied()
            }
        }

    fun reRequestPermissions() {
        // Create a mutable list to hold permissions
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
    }

    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions at the start
        val initialPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            initialPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            initialPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            initialPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            initialPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestMultiplePermissions.launch(initialPermissions.toTypedArray())

        setContent {
            UMCO2MonitorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = colorResource(id = R.color.michigan_blue)
                ) { innerPadding ->
                    MainScreen(viewModel, this, Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: SensorViewModel, activity: MainActivity, modifier: Modifier = Modifier) {
    val bleConnectionState by viewModel.bleConnectionState.collectAsState()

    LaunchedEffect(bleConnectionState) {
        if (bleConnectionState is BleConnectionState.Connected) {
            Intent(activity, MeasurementService::class.java).also { intent ->
                activity.startService(intent)
            }
        } else {
            Intent(activity, MeasurementService::class.java).also { intent ->
                activity.stopService(intent)
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = bleConnectionState) {
            is BleConnectionState.Disconnected -> DisconnectedScreen(onScanClicked = { viewModel.startScan() })
            is BleConnectionState.Scanning -> ScanningScreen(
                devices = state.discoveredDevices,
                onDeviceClicked = { viewModel.connectToDevice(it) },
                onStopScanClicked = { viewModel.stopScan() }
            )
            is BleConnectionState.Connecting -> ConnectingScreen(deviceName = state.deviceName)
            is BleConnectionState.Connected -> ConnectedScreen(viewModel)
            is BleConnectionState.Error -> {
                val isPermissionError = state.message.contains("permission", ignoreCase = true)
                ErrorScreen(
                    message = state.message,
                    onRerequestClicked = { if (isPermissionError) activity.reRequestPermissions() else viewModel.startScan() },
                    onSettingsClicked = if (isPermissionError) { { activity.openAppSettings() } } else null,
                    firstButtonText = if (isPermissionError) "Rerequest Permissions" else "Restart Scan"
                )
            }
        }
    }
}

@Composable
fun ConnectedScreen(viewModel: SensorViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow (selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { viewModel.setSelectedTab(0) }, text = { Text("Live") })
            Tab(selected = selectedTab == 1, onClick = { viewModel.setSelectedTab(1) }, text = { Text("History") })
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) LiveView(viewModel) else HistoryView(viewModel)
        }
    }
}

@Composable
fun LiveView(viewModel: SensorViewModel) {
    val co2Value by viewModel.co2Value.collectAsState()
    val temperatureValue by viewModel.temperatureValue.collectAsState()
    val humidityValue by viewModel.humidityValue.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Current Readings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        ReadingCard(label = "CO2", value = co2Value?.toString() ?: "--", unit = "ppm", color = colorResource(id = R.color.michigan_maize))
        ReadingCard(label = "Temperature", value = temperatureValue?.let { "%.1f".format(it) } ?: "--", unit = "°F", color = Color.Red)
        ReadingCard(label = "Humidity", value = humidityValue?.let { "%.1f".format(it) } ?: "--", unit = "%", color = Color.Cyan)

        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
    }
}

@Composable
fun ReadingCard(label: String, value: String, unit: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = color)
                Text(text = "$value $unit", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HistoryView(viewModel: SensorViewModel) {
    val history by viewModel.history.collectAsState()
    val settings by viewModel.historySettings.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sensor History", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.DarkGray).padding(8.dp)) {
            SensorPlot(data = history, settings = settings)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Visible Sensors", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterChip(selected = settings.showCo2, onClick = { viewModel.updateHistorySettings(showCo2 = !settings.showCo2) }, label = { Text("CO2") })
            FilterChip(selected = settings.showTemperature, onClick = { viewModel.updateHistorySettings(showTemperature = !settings.showTemperature) }, label = { Text("Temp") })
            FilterChip(selected = settings.showHumidity, onClick = { viewModel.updateHistorySettings(showHumidity = !settings.showHumidity) }, label = { Text("Humid") })
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Time Range: ${settings.timeRangeHours} hours", style = MaterialTheme.typography.labelLarge)
        Slider(value = settings.timeRangeHours.toFloat(), onValueChange = { viewModel.updateHistorySettings(timeRangeHours = it.toInt()) }, valueRange = 1f..24f, steps = 23)
    }
}

@Composable
fun SensorPlot(data: List<SensorData>, settings: HistorySettings) {
    val maize = colorResource(id = R.color.michigan_maize)
    
    // Calculate real-time window bounds
    val now = Clock.System.now()
    val maxTime = now.toEpochMilliseconds()
    val minTime = (now - settings.timeRangeHours.hours).toEpochMilliseconds()
    val timeRangeWidth = (maxTime - minTime).toFloat()

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (data.isEmpty()) return@Canvas

        fun getX(timestamp: Long) = (timestamp - minTime) / timeRangeWidth * size.width

        // Gap threshold logic:
        // Expected interval if 300 points are spread across the time window.
        val expectedInterval = timeRangeWidth / 300f
        // Threshold: 3x the expected interval, or at least 20 seconds.
        val maxGapMs = maxOf(20000f, expectedInterval * 3f).toLong()

        // Independent dynamic scaling for each sensor with buffer
        if (settings.showCo2) {
            val values = data.map { it.co2Value.toFloat() }
            val min = values.minOrNull() ?: 400f
            val max = values.maxOrNull() ?: 2000f
            val spread = (max - min).coerceAtLeast(100f)
            drawSeries(data, { it.co2Value.toFloat() }, min - (spread * 0.1f), max + (spread * 0.1f), maize, ::getX, maxGapMs)
        }
        
        if (settings.showTemperature) {
            val values = data.map { it.temperatureValue }
            val min = values.minOrNull() ?: 60f
            val max = values.maxOrNull() ?: 100f
            val spread = (max - min).coerceAtLeast(5f)
            drawSeries(data, { it.temperatureValue }, min - (spread * 0.1f), max + (spread * 0.1f), Color.Red, ::getX, maxGapMs)
        }

        if (settings.showHumidity) {
            val values = data.map { it.humidityValue }
            val min = values.minOrNull() ?: 0f
            val max = values.maxOrNull() ?: 100f
            val spread = (max - min).coerceAtLeast(5f)
            drawSeries(data, { it.humidityValue }, min - (spread * 0.1f), max + (spread * 0.1f), Color.Cyan, ::getX, maxGapMs)
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    data: List<SensorData>, 
    valueSelector: (SensorData) -> Float, 
    minVal: Float, 
    maxVal: Float, 
    color: Color, 
    getX: (Long) -> Float,
    maxGapMs: Long
) {
    val path = Path()
    var lastTimestamp: Long? = null

    data.forEachIndexed { index, sensorData ->
        val currentTimestamp = sensorData.timestamp.toEpochMilliseconds()
        val x = getX(currentTimestamp)
        
        // Skip points that are off-screen to the left (older than the window)
        if (x < 0) return@forEachIndexed

        val rawY = valueSelector(sensorData)
        val normalizedY = if (maxVal == minVal) 0.5f else (rawY - minVal) / (maxVal - minVal)
        val y = size.height - (normalizedY.coerceIn(0f, 1f) * size.height)

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            val gap = currentTimestamp - (lastTimestamp ?: currentTimestamp)
            if (gap > maxGapMs) {
                path.moveTo(x, y) // Discontinuity: skip drawing a line
            } else {
                path.lineTo(x, y)
            }
        }
        lastTimestamp = currentTimestamp
    }
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
}

@Composable
fun DisconnectedScreen(onScanClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Ready to scan for your sensor.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onScanClicked) { Text("Scan for Devices") }
    }
}

@Composable
fun ScanningScreen(devices: List<DiscoveredDevice>, onDeviceClicked: (DiscoveredDevice) -> Unit, onStopScanClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scanning...", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (devices.isEmpty()) Text("No devices found yet...", modifier = Modifier.align(Alignment.Center))
            else LazyColumn { items(devices) { DeviceListItem(it) { onDeviceClicked(it) } } }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStopScanClicked, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Stop Scan") }
    }
}

@Composable
fun DeviceListItem(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name ?: "Unknown Device", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = device.address)
        }
    }
}

@Composable
fun ConnectingScreen(deviceName: String?) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Connecting to ${deviceName ?: "Device"}...", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ErrorScreen(message: String, onRerequestClicked: () -> Unit, onSettingsClicked: (() -> Unit)?, firstButtonText: String) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("An Error Occurred", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, modifier = Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRerequestClicked) { Text(firstButtonText) }
        onSettingsClicked?.let { Spacer(modifier = Modifier.height(8.dp)); Button(onClick = it) { Text("Go to Settings") } }
    }
}
