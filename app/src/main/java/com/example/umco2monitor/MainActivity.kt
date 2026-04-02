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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.umco2monitor.ui.theme.UMCO2MonitorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
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
        PrimaryTabRow (selectedTabIndex = selectedTab) {
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

    val timeRanges = listOf(
        "1H" to 1.hours,
        "6H" to 6.hours,
        "24H" to 24.hours,
        "7D" to 7.days
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Sensor History", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.DarkGray.copy(alpha = 0.8f))
                // Future pan/zoom can be implemented using the pointerInput modifier
                .pointerInput(Unit) {
                    // detectTransformGestures { _, pan, zoom, _ -> ... }
                }
        ) {
            SensorPlot(data = history, settings = settings)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Time Range Selection
        Text("Time Range", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            timeRanges.forEach { (label, duration) ->
                FilterChip(
                    selected = settings.timeRange == duration,
                    onClick = { viewModel.updateHistorySettings(timeRange = duration) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Visible Sensors Selection
        Text("Visible Sensors", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterChip(selected = settings.showCo2, onClick = { viewModel.updateHistorySettings(showCo2 = !settings.showCo2) }, label = { Text("CO2") })
            FilterChip(selected = settings.showTemperature, onClick = { viewModel.updateHistorySettings(showTemperature = !settings.showTemperature) }, label = { Text("Temp") })
            FilterChip(selected = settings.showHumidity, onClick = { viewModel.updateHistorySettings(showHumidity = !settings.showHumidity) }, label = { Text("Humid") })
        }
    }
}

@Composable
fun SensorPlot(data: List<SensorData>, settings: HistorySettings) {
    val maize = colorResource(id = R.color.michigan_maize)
    val textPaint = Paint().asFrameworkPaint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = 12.sp.value
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val padding = 20.dp.toPx() // Padding for labels
        if (data.isEmpty()) return@Canvas

        val now = Clock.System.now()
        val maxTime = now.toEpochMilliseconds()
        val minTime = (now - settings.timeRange).toEpochMilliseconds()
        val timeRangeWidth = (maxTime - minTime).toFloat()

        fun getX(timestamp: Long) = padding + (timestamp - minTime) / timeRangeWidth * (size.width - 2 * padding)

        // Find min/max for CO2 to set the primary Y-axis
        val co2Values = if (settings.showCo2) data.map { it.co2Value.toFloat() } else emptyList()
        val minCo2 = co2Values.minOrNull() ?: 400f
        val maxCo2 = co2Values.maxOrNull() ?: 2000f
        val co2Spread = (maxCo2 - minCo2).coerceAtLeast(100f)
        val yMinCo2 = minCo2 - co2Spread * 0.1f
        val yMaxCo2 = maxCo2 + co2Spread * 0.1f

        // Draw Y-axis grid lines and labels for CO2
        val gridLines = 5
        val co2NiceRange = getNiceRange(yMinCo2.toDouble(), yMaxCo2.toDouble(), gridLines)
        for (i in 0..gridLines) {
            val value = co2NiceRange.first + i * (co2NiceRange.second - co2NiceRange.first) / gridLines
            val y = size.height - padding - ((value - yMinCo2) / (yMaxCo2 - yMinCo2) * (size.height - 2 * padding)).toFloat()
            drawLine(
                color = Color.Gray,
                start = Offset(padding, y),
                end = Offset(size.width - padding, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${value.toInt()}",
                0f,
                y + textPaint.textSize / 2,
                textPaint
            )
        }
        
        // Draw X-axis tick marks and labels
        val xGridLines = 4
        for (i in 0..xGridLines) {
            val timestamp = minTime + i * timeRangeWidth.toLong() / xGridLines
            val x = getX(timestamp)
            drawLine(
                color = Color.Gray,
                start = Offset(x, size.height - padding),
                end = Offset(x, size.height - padding + 5.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatTimestamp(timestamp, settings.timeRange),
                x - textPaint.measureText(formatTimestamp(timestamp, settings.timeRange)) / 2,
                size.height,
                textPaint
            )
        }

        val maxGapMs = maxOf(20000f, timeRangeWidth / 300f * 3f).toLong()

        if (settings.showCo2) {
            drawSeries(data, { it.co2Value.toFloat() }, yMinCo2, yMaxCo2, maize, ::getX, maxGapMs, padding)
        }
        
        if (settings.showTemperature) {
            val tempValues = data.map { it.temperatureValue }
            val minTemp = tempValues.minOrNull() ?: 60f
            val maxTemp = tempValues.maxOrNull() ?: 100f
            val tempSpread = (maxTemp - minTemp).coerceAtLeast(5f)
            drawSeries(data, { it.temperatureValue }, minTemp - tempSpread * 0.1f, maxTemp + tempSpread * 0.1f, Color.Red, ::getX, maxGapMs, padding)
        }

        if (settings.showHumidity) {
            val humidValues = data.map { it.humidityValue }
            val minHumid = humidValues.minOrNull() ?: 0f
            val maxHumid = humidValues.maxOrNull() ?: 100f
            val humidSpread = (maxHumid - minHumid).coerceAtLeast(5f)
            drawSeries(data, { it.humidityValue }, minHumid - humidSpread * 0.1f, maxHumid + humidSpread * 0.1f, Color.Cyan, ::getX, maxGapMs, padding)
        }
    }
}

private fun formatTimestamp(timestamp: Long, range: Duration): String {
    val format = if (range > 2.days) "MM/dd" else "HH:mm"
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))
}

private fun getNiceRange(min: Double, max: Double, ticks: Int): Pair<Double, Double> {
    val range = max - min
    if (range == 0.0) return Pair(min - 1, max + 1)
    val tickSpacing = range / ticks
    val magnitude = 10.0.pow(floor(log10(tickSpacing)))
    val residual = tickSpacing / magnitude
    
    val niceTick = when {
        residual > 5 -> 10 * magnitude
        residual > 2 -> 5 * magnitude
        else -> 2 * magnitude
    }
    
    val niceMin = floor(min / niceTick) * niceTick
    val niceMax = kotlin.math.ceil(max / niceTick) * niceTick
    return Pair(niceMin, niceMax)
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    data: List<SensorData>, 
    valueSelector: (SensorData) -> Float, 
    minVal: Float, 
    maxVal: Float, 
    color: Color, 
    getX: (Long) -> Float,
    maxGapMs: Long,
    padding: Float
) {
    val path = Path()
    var lastTimestamp: Long? = null

    data.forEachIndexed { index, sensorData ->
        val currentTimestamp = sensorData.timestamp.toEpochMilliseconds()
        val x = getX(currentTimestamp)
        
        if (x < padding) return@forEachIndexed

        val rawY = valueSelector(sensorData)
        val normalizedY = if (maxVal == minVal) 0.5f else (rawY - minVal) / (maxVal - minVal)
        val y = (size.height - padding) - (normalizedY.coerceIn(0f, 1f) * (size.height - 2 * padding))

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            val gap = currentTimestamp - (lastTimestamp ?: currentTimestamp)
            if (gap > maxGapMs) {
                path.moveTo(x, y) 
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
