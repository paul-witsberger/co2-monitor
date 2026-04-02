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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.times

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
        ReadingCard(label = "Humidity", value = humidityValue?.let { "%.1f".format(it) } ?: "--", unit = "%", color = Color.Cyan)
        ReadingCard(label = "Temperature", value = temperatureValue?.let { "%.1f".format(it) } ?: "--", unit = "°F", color = Color.Red)

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
        ) {
            SensorPlot(data = history, settings = settings, viewModel = viewModel)
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
            FilterChip(selected = settings.showCo2,
                onClick = { viewModel.updateHistorySettings(showCo2 = !settings.showCo2) },
                label = { Text("CO2") })
            FilterChip(selected = settings.showHumidity,
                onClick = { viewModel.updateHistorySettings(showHumidity = !settings.showHumidity) },
                label = { Text("Humid") })
            FilterChip(selected = settings.showTemperature,
                onClick = { viewModel.updateHistorySettings(showTemperature = !settings.showTemperature) },
                label = { Text("Temp") })
        }
    }
}

private enum class AxisPosition { LEFT, RIGHT }

private data class AxisRange(
    val yMin: Float,
    val yMax: Float,
    val niceRange: Pair<Double, Double>
)

private class AxisConfig(
    val name: String,
    val color: Color,
    val isVisible: Boolean,
    val valueSelector: (SensorData) -> Float,
    var position: AxisPosition = AxisPosition.LEFT
)

@Composable
fun SensorPlot(data: List<SensorData>, settings: HistorySettings, viewModel: SensorViewModel) {
    val maize = colorResource(id = R.color.michigan_maize)

    // Internal state for temporary zoom/pan
    var scale by remember { mutableFloatStateOf(1f) }
    var timeShift by remember { mutableStateOf(Duration.ZERO) }

    // RESET LOGIC: When settings.timeRange changes (user clicks 1H, 6H, etc.),
    // reset the plot to the most recent data.
    LaunchedEffect(settings.timeRange) {
        scale = 1f
        timeShift = Duration.ZERO
    }

    // Fixed Padding: Increased to 55.dp to ensure Y-axis labels stay inside the gray area
    val pL_dp = 55.dp
    val pR_dp = 55.dp
    val pT_dp = 10.dp
    val pB_dp = 40.dp

    val axisConfigs = listOf(
        AxisConfig("CO2", maize, settings.showCo2, { it.co2Value.toFloat() }),
        AxisConfig("Humidity", Color.Cyan, settings.showHumidity, { it.humidityValue }),
        AxisConfig("Temperature", Color.Red, settings.showTemperature, { it.temperatureValue })
    )

    var isLeftOccupied = false
    axisConfigs.filter { it.isVisible }.forEach { config ->
        if (!isLeftOccupied) { config.position = AxisPosition.LEFT; isLeftOccupied = true }
        else { config.position = AxisPosition.RIGHT }
    }

    Canvas(modifier = Modifier
        .fillMaxSize()
        .pointerInput(settings) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val pL = pL_dp.toPx()
                val pR = pR_dp.toPx()
                val plotWidth = size.width - pL - pR
                if (plotWidth <= 0f) return@detectTransformGestures

                // 1. Calculate the state BEFORE the gesture
                val oldScale = scale
                val oldVisibleDuration = settings.timeRange / oldScale.toDouble()

                // 2. Apply the new scale from the zoom gesture
                val newScale = (scale * zoom).coerceIn(0.1f, 20f)
                scale = newScale
                val newVisibleDuration = settings.timeRange / newScale.toDouble()

                // 3. Calculate the pan in terms of duration.
                // This calculates how much TIME the finger moved over.
                val panInDuration = oldVisibleDuration * (pan.x / plotWidth).toDouble()

                // 4. Calculate the shift caused by zooming.
                // This keeps the point under the centroid stationary.
                val durationChangeFromZoom = oldVisibleDuration - newVisibleDuration
                val zoomShift = durationChangeFromZoom * (1.0 - (centroid.x - pL) / plotWidth).coerceIn(0.0, 1.0)

                // 5. Apply the total shift.
                // Panning right (positive pan.x) pulls data from the left (past),
                // so we must INCREASE the timeShift.
                timeShift += panInDuration + zoomShift

                // 6. Constrain the shift to prevent panning into the future.
                timeShift = timeShift.coerceAtLeast(Duration.ZERO)
            }
        }
    ) {
        val pL = pL_dp.toPx()
        val pR = pR_dp.toPx()
        val pT = pT_dp.toPx()
        val pB = pB_dp.toPx()
        val plotWidth = size.width - pL - pR

        val now = Clock.System.now()
        val visibleDuration = settings.timeRange / scale.toDouble()
        val endTime = now - timeShift
        val startTime = endTime - visibleDuration

        val startMs = startTime.toEpochMilliseconds()
        val endMs = endTime.toEpochMilliseconds()
        val durationMs = visibleDuration.inWholeMilliseconds.toDouble()

        // Dynamically update the Y-Axis Ranges
        val dynamicRanges = axisConfigs.filter { it.isVisible }.associateWith { config ->
            val valuesInView = data
                .filter { it.timestamp in startTime..endTime }
                .map(config.valueSelector)

            // Provide a sensible default range if the user pans into a gap with no data.
            val minVal = valuesInView.minOrNull() ?: 0f
            val maxVal = valuesInView.maxOrNull() ?: (if (config.name == "CO2") 1000f else 100f)

            val spread = (maxVal - minVal).coerceAtLeast(if (config.name == "CO2") 100f else 5f)
            val yMin = minVal - spread * 0.1f
            val yMax = maxVal + spread * 0.1f

            AxisRange(yMin, yMax, getNiceRange(yMin.toDouble(), yMax.toDouble()))
        }

        fun getX(timestamp: Long): Float {
            val progress = (timestamp - startMs) / durationMs
            return (pL + progress * plotWidth).toFloat()
        }

        // The number of gridlines to draw
        val gridLineCount = 4

        // --- DRAWING ---
        // 1. Y-Axis Labels
        dynamicRanges.forEach { (axis, range) ->
            drawYAxis(axis, range, gridLineCount, pL, pR, pT, pB)
        }

        // 2. Clipped Data Area
        clipRect(left = pL, top = pT, right = size.width - pR, bottom = size.height - pB) {
            // Gridlines (using the first visible axis's range)
            dynamicRanges.entries.firstOrNull()?.let { (_, range) ->
                drawYGridlines(range, gridLineCount, pL, pR, pT, pB)
            }

            // Filter the full dataset to what's in view, then sample *that* for drawing.
            val visibleData = data.filter { it.timestamp in startTime..endTime }
            val maxDrawPoints = 1000 // Limit the number of points in the path for performance
            val step = (visibleData.size / maxDrawPoints).coerceAtLeast(1)
            val sampledDataForDrawing = if (step > 1) {
                visibleData.filterIndexed { index, _ -> index % step == 0 }
            } else {
                visibleData
            }

            // Lines (iterates over dynamicRanges, but draws with sampledDataForDrawing)
            dynamicRanges.entries.reversed().forEach { (axis, range) ->
                // Use the SAMPLED data for drawing the path
                drawSeries(
                    data = sampledDataForDrawing,
                    valueSelector = axis.valueSelector,
                    minVal = range.yMin,
                    maxVal = range.yMax,
                    color = axis.color,
                    getX = ::getX,
                    maxGapMs = 60000L,
                    paddingLeft = pL,
                    paddingTop = pT,
                    paddingBottom = pB
                )
            }
        }

        // 3. X-Axis Labels (With overlap prevention)
        val xLabelPaint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val interval = getNiceTimeInterval(visibleDuration, gridLineCount)
        val firstTickMs = ceil(startMs / interval.inWholeMilliseconds.toDouble()).toLong() * interval.inWholeMilliseconds

        val labelWidthEstimate = 75.dp.toPx()
        val pixelsPerTick = (interval.inWholeMilliseconds.toDouble() / durationMs) * plotWidth
        val step = ceil(labelWidthEstimate / pixelsPerTick).toInt().coerceAtLeast(1)

        var count = 0
        var labelMs = firstTickMs
        while (labelMs <= endMs) {
            if (count % step == 0) {
                val x = getX(labelMs)
                if (x in (pL - 2f)..(size.width - pR + 2f)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        formatTimestamp(labelMs, visibleDuration),
                        x,
                        size.height - pB + 22.dp.toPx(),
                        xLabelPaint
                    )
                }
            }
            labelMs += interval.inWholeMilliseconds
            count++
        }
    }
}

private fun getNiceTimeInterval(duration: Duration, targetTicks: Int = 4): Duration {
    val niceDurations = listOf(
        1.seconds, 2.seconds, 5.seconds, 10.seconds, 15.seconds, 30.seconds,
        1.minutes, 2.minutes, 5.minutes, 10.minutes, 15.minutes, 30.minutes,
        1.hours, 2.hours, 3.hours, 4.hours, 6.hours, 12.hours,
        1.days, 2.days, 7.days
    )

    val targetInterval = duration / targetTicks.toDouble()

    // Find the smallest "nice" duration that is greater than or equal to our target.
    return niceDurations.firstOrNull { it >= targetInterval } ?: niceDurations.last()
}

private fun DrawScope.drawYGridlines(range: AxisRange, gridLines: Int, pL: Float, pR: Float, pT: Float, pB: Float) {
    val plotHeight = size.height - pT - pB
    val (niceMin, niceMax) = range.niceRange // Use range from parameter
    val tickCount = ((niceMax - niceMin) / getNiceRange(niceMin, niceMax, gridLines).let { (it.second - it.first) / gridLines }).toInt().coerceAtLeast(1)

    // Use tickCount (or just iterate based on the niceTick value)
    val niceTick = (niceMax - niceMin) / gridLines // This assumes the new getNiceRange works perfectly. Let's make it robust.
    val tickStep = getNiceRange(range.yMin.toDouble(), range.yMax.toDouble(), gridLines).let { (newMin, newMax) -> (newMax - newMin) / gridLines }

    for (i in 0..gridLines) {
        val value = niceMin + i * (niceMax - niceMin) / gridLines
        val y = size.height - pB - ((value - range.yMin) / (range.yMax - range.yMin) * plotHeight).toFloat()
        if (y < pT - 1f || y > size.height - pB + 1f) continue
        if (i > 0) {
            drawLine(color = Color.Gray, start = Offset(pL, y), end = Offset(size.width - pR, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
        }
    }
}

private fun DrawScope.drawYAxis(axis: AxisConfig, range: AxisRange, gridLines: Int, pL: Float, pR: Float, pT: Float, pB: Float) {
    val plotHeight = size.height - pT - pB
    val yLabelPaint = Paint().asFrameworkPaint().apply { isAntiAlias = true; color = axis.color.toArgb(); textSize = 16.sp.toPx() }
    val (niceMin, niceMax) = range.niceRange // Use range from parameter

    for (i in 0..gridLines) {
        val value = niceMin + i * (niceMax - niceMin) / gridLines
        val y = size.height - pB - ((value - range.yMin) / (range.yMax - range.yMin) * plotHeight).toFloat()
        if (y < pT - 1f || y > size.height - pB + 1f) continue

        val label = if (axis.name == "CO2") "${value.toInt()}" else "%.1f".format(value)
        val labelX = if (axis.position == AxisPosition.LEFT) pL - yLabelPaint.measureText(label) - 4.dp.toPx()
        else size.width - pR + 4.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(label, labelX, y + yLabelPaint.textSize / 2, yLabelPaint)
    }
}

private fun formatTimestamp(timestamp: Long, range: Duration): String {
    val format = when {
        range > 2.days -> "MM/dd"
        range > 1.hours -> "HH:mm"
        else -> "HH:mm:ss"
    }
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))
}

private fun getNiceRange(min: Double, max: Double, ticks: Int = 4): Pair<Double, Double> {
    if (min == max) return Pair(min - 1, max + 1)

    val range = max - min
    val unroundedTickSize = range / ticks

    // Calculate a "nice" tick size, rounding up to a power of 10 multiplied by 1, 2, or 5
    val x = ceil(log10(unroundedTickSize) - 1)
    val pow10x = 10.0.pow(x)
    val niceTick = ceil(unroundedTickSize / pow10x) * pow10x

    val niceMin = floor(min / niceTick) * niceTick
    val niceMax = ceil(max / niceTick) * niceTick

    return Pair(niceMin, niceMax)
}

fun DrawScope.drawSeries(
    data: List<SensorData>,
    valueSelector: (SensorData) -> Float,
    minVal: Float,
    maxVal: Float,
    color: Color,
    getX: (Long) -> Float,
    maxGapMs: Long,
    paddingLeft: Float,
    paddingTop: Float,
    paddingBottom: Float
) {
    val path = Path()
    var lastTimestamp: Long? = null
    val plotHeight = size.height - paddingTop - paddingBottom

    data.forEach { sensorData ->
        val currentTimestamp = sensorData.timestamp.toEpochMilliseconds()
        val x = getX(currentTimestamp)
        
        val rawY = valueSelector(sensorData)
        val normalizedY = if (maxVal == minVal) 0.5f else (rawY - minVal) / (maxVal - minVal)
        val y = size.height - paddingBottom - (normalizedY.coerceIn(0f, 1f) * plotHeight)

        val isFirstPoint = lastTimestamp == null
        if (isFirstPoint) {
            path.moveTo(x, y)
        } else {
            val gap = currentTimestamp - (lastTimestamp ?: 0)
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
