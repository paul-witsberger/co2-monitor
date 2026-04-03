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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Main activity of the application. This is the entry point of the app.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: SensorViewModel by viewModels { SensorViewModelFactory(application) }

    private val requestMultiplePermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions: Map<String, Boolean> ->
            val fineLocationDenied: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) false else permissions[Manifest.permission.ACCESS_FINE_LOCATION] == false
            val bluetoothConnectDenied: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions[Manifest.permission.BLUETOOTH_CONNECT] == false else false
            val bluetoothScanDenied: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions[Manifest.permission.BLUETOOTH_SCAN] == false else false

            if (fineLocationDenied || bluetoothConnectDenied || bluetoothScanDenied) {
                Toast.makeText(this, "Bluetooth and location permissions are required.", Toast.LENGTH_LONG).show()
                viewModel.onPermissionsDenied()
            }
        }

    /**
     * Re-requests required permissions if they were denied.
     */
    fun reRequestPermissions() {
        val permissionsToRequest: MutableList<String> = mutableListOf()
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

    /**
     * Opens the application settings to allow the user to manually grant permissions.
     */
    fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialPermissions: MutableList<String> = mutableListOf()
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
                ) { innerPadding: PaddingValues ->
                    MainScreen(viewModel, this, Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Main Compose screen displaying either disconnected, scanning, connecting, connected, or error states.
 *
 * @param viewModel The view model.
 * @param activity The main activity context.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun MainScreen(viewModel: SensorViewModel, activity: MainActivity, modifier: Modifier = Modifier) {
    val bleConnectionState: BleConnectionState by viewModel.bleConnectionState.collectAsState()

    LaunchedEffect(bleConnectionState) {
        if (bleConnectionState is BleConnectionState.Connected) {
            val intent: Intent = Intent(activity, MeasurementService::class.java)
            activity.startService(intent)
        } else {
            val intent: Intent = Intent(activity, MeasurementService::class.java)
            activity.stopService(intent)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state: BleConnectionState = bleConnectionState) {
            is BleConnectionState.Disconnected -> DisconnectedScreen(onScanClicked = { viewModel.startScan() })
            is BleConnectionState.Scanning -> ScanningScreen(
                devices = state.discoveredDevices,
                onDeviceClicked = { device: DiscoveredDevice -> viewModel.connectToDevice(device) },
                onStopScanClicked = { viewModel.stopScan() }
            )
            is BleConnectionState.Connecting -> ConnectingScreen(deviceName = state.deviceName)
            is BleConnectionState.Connected -> ConnectedScreen(viewModel)
            is BleConnectionState.Error -> {
                val isPermissionError: Boolean = state.message.contains("permission", ignoreCase = true)
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

/**
 * Screen displayed when successfully connected to a sensor device.
 *
 * @param viewModel The view model.
 */
@Composable
fun ConnectedScreen(viewModel: SensorViewModel) {
    val selectedTab: Int by viewModel.selectedTab.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { viewModel.setSelectedTab(0) }, text = { Text("Live") })
            Tab(selected = selectedTab == 1, onClick = { viewModel.setSelectedTab(1) }, text = { Text("History") })
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) LiveView(viewModel) else HistoryView(viewModel)
        }
    }
}

/**
 * Displays live sensor readings.
 *
 * @param viewModel The view model.
 */
@Composable
fun LiveView(viewModel: SensorViewModel) {
    val co2Value: UShort? by viewModel.co2Value.collectAsState()
    val temperatureValue: Float? by viewModel.temperatureValue.collectAsState()
    val humidityValue: Float? by viewModel.humidityValue.collectAsState()

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

/**
 * Card representing a single sensor reading.
 *
 * @param label The name of the reading.
 * @param value The value string to display.
 * @param unit The unit of the reading.
 * @param color The accent color for the reading text.
 */
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

/**
 * Displays the historical data chart.
 *
 * @param viewModel The view model.
 */
@Composable
fun HistoryView(viewModel: SensorViewModel) {
    val history: List<SensorData> by viewModel.history.collectAsState()
    val settings: HistorySettings by viewModel.historySettings.collectAsState()

    val timeRanges: List<Pair<String, Duration>> = listOf(
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
            SensorPlot(data = history, settings = settings)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Time Range", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            timeRanges.forEach { (label: String, duration: Duration) ->
                FilterChip(
                    selected = settings.timeRange == duration,
                    onClick = { viewModel.updateHistorySettings(timeRange = duration) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

/**
 * Plots sensor history data onto a canvas.
 *
 * @param data A list of historical sensor data points.
 * @param settings Current plot settings.
 */
@Composable
fun SensorPlot(data: List<SensorData>, settings: HistorySettings) {
    val maize: Color = colorResource(id = R.color.michigan_maize)

    var scale: Float by remember { mutableFloatStateOf(1f) }
    var timeShift: Duration by remember { mutableStateOf(Duration.ZERO) }

    LaunchedEffect(settings.timeRange) {
        scale = 1f
        timeShift = Duration.ZERO
    }

    val paddingLeftDp: androidx.compose.ui.unit.Dp = 55.dp
    val paddingRightDp: androidx.compose.ui.unit.Dp = 55.dp
    val paddingTopDp: androidx.compose.ui.unit.Dp = 10.dp
    val paddingBottomDp: androidx.compose.ui.unit.Dp = 40.dp

    val axisConfigs: List<AxisConfig> = listOf(
        AxisConfig("CO2", maize, settings.showCo2, { it.co2Value.toFloat() }),
        AxisConfig("Humidity", Color.Cyan, settings.showHumidity, { it.humidityValue }),
        AxisConfig("Temperature", Color.Red, settings.showTemperature, { it.temperatureValue })
    )

    var isLeftOccupied: Boolean = false
    axisConfigs.filter { it.isVisible }.forEach { config: AxisConfig ->
        if (!isLeftOccupied) { config.position = AxisPosition.LEFT; isLeftOccupied = true }
        else { config.position = AxisPosition.RIGHT }
    }

    Canvas(modifier = Modifier
        .fillMaxSize()
        .pointerInput(settings) {
            detectTransformGestures { centroid: Offset, pan: Offset, zoom: Float, _: Float ->
                val paddingLeftPx: Float = paddingLeftDp.toPx()
                val paddingRightPx: Float = paddingRightDp.toPx()
                val plotWidth: Float = size.width - paddingLeftPx - paddingRightPx
                if (plotWidth <= 0f) return@detectTransformGestures

                val oldScale: Float = scale
                val oldVisibleDuration: Duration = settings.timeRange / oldScale.toDouble()

                val newScale: Float = (scale * zoom).coerceIn(0.1f, 20f)
                scale = newScale
                val newVisibleDuration: Duration = settings.timeRange / newScale.toDouble()

                val panInDuration: Duration = oldVisibleDuration * (pan.x / plotWidth).toDouble()

                val durationChangeFromZoom: Duration = oldVisibleDuration - newVisibleDuration
                val zoomShift: Duration = durationChangeFromZoom * (1.0 - (centroid.x - paddingLeftPx) / plotWidth).coerceIn(0.0, 1.0)

                timeShift += panInDuration + zoomShift
                timeShift = timeShift.coerceAtLeast(Duration.ZERO)
            }
        }
    ) {
        val paddingLeftPx: Float = paddingLeftDp.toPx()
        val paddingRightPx: Float = paddingRightDp.toPx()
        val paddingTopPx: Float = paddingTopDp.toPx()
        val paddingBottomPx: Float = paddingBottomDp.toPx()
        val plotWidth: Float = size.width - paddingLeftPx - paddingRightPx

        val now: Instant = Clock.System.now()
        val visibleDuration: Duration = settings.timeRange / scale.toDouble()
        val endTime: Instant = now - timeShift
        val startTime: Instant = endTime - visibleDuration

        val startMs: Long = startTime.toEpochMilliseconds()
        val endMs: Long = endTime.toEpochMilliseconds()
        val durationMs: Double = visibleDuration.inWholeMilliseconds.toDouble()

        val dynamicRanges: Map<AxisConfig, AxisRange> = axisConfigs.filter { it.isVisible }.associateWith { config: AxisConfig ->
            val valuesInView: List<Float> = data
                .filter { it.timestamp in startTime..endTime }
                .map(config.valueSelector)

            val minVal: Float = valuesInView.minOrNull() ?: 0f
            val maxVal: Float = valuesInView.maxOrNull() ?: (if (config.name == "CO2") 1000f else 100f)

            val spread: Float = (maxVal - minVal).coerceAtLeast(if (config.name == "CO2") 100f else 5f)
            val yMin: Float = minVal - spread * 0.1f
            val yMax: Float = maxVal + spread * 0.1f

            AxisRange(yMin, yMax, getNiceRange(yMin.toDouble(), yMax.toDouble()))
        }

        fun getX(timestamp: Long): Float {
            val progress: Double = (timestamp - startMs) / durationMs
            return (paddingLeftPx + progress * plotWidth).toFloat()
        }

        val gridLineCount: Int = 4

        dynamicRanges.forEach { (axis: AxisConfig, range: AxisRange) ->
            drawYAxis(axis, range, gridLineCount, paddingLeftPx, paddingRightPx, paddingTopPx, paddingBottomPx)
        }

        clipRect(left = paddingLeftPx, top = paddingTopPx, right = size.width - paddingRightPx, bottom = size.height - paddingBottomPx) {
            dynamicRanges.entries.firstOrNull()?.let { entry: Map.Entry<AxisConfig, AxisRange> ->
                drawYGridlines(entry.value, gridLineCount, paddingLeftPx, paddingRightPx, paddingTopPx, paddingBottomPx)
            }

            val visibleData: List<SensorData> = data.filter { it.timestamp in startTime..endTime }
            val maxDrawPoints: Int = 1000
            val step: Int = (visibleData.size / maxDrawPoints).coerceAtLeast(1)
            val sampledDataForDrawing: List<SensorData> = if (step > 1) {
                visibleData.filterIndexed { index: Int, _: SensorData -> index % step == 0 }
            } else {
                visibleData
            }

            dynamicRanges.entries.reversed().forEach { (axis: AxisConfig, range: AxisRange) ->
                drawSeries(
                    data = sampledDataForDrawing,
                    valueSelector = axis.valueSelector,
                    minVal = range.yMin,
                    maxVal = range.yMax,
                    color = axis.color,
                    getX = ::getX,
                    maxGapMs = 60000L,
                    paddingTop = paddingTopPx,
                    paddingBottom = paddingBottomPx
                )
            }
        }

        val xLabelPaint: android.graphics.Paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val interval: Duration = getNiceTimeInterval(visibleDuration, gridLineCount)
        val firstTickMs: Long = ceil(startMs / interval.inWholeMilliseconds.toDouble()).toLong() * interval.inWholeMilliseconds

        val labelWidthEstimate: Float = 75.dp.toPx()
        val pixelsPerTick: Double = (interval.inWholeMilliseconds.toDouble() / durationMs) * plotWidth
        val step: Int = ceil(labelWidthEstimate / pixelsPerTick).toInt().coerceAtLeast(1)

        var count: Int = 0
        var labelMs: Long = firstTickMs
        while (labelMs <= endMs) {
            if (count % step == 0) {
                val x: Float = getX(labelMs)
                if (x in (paddingLeftPx - 2f)..(size.width - paddingRightPx + 2f)) {
                    drawContext.canvas.nativeCanvas.drawText(
                        formatTimestamp(labelMs, visibleDuration),
                        x,
                        size.height - paddingBottomPx + 22.dp.toPx(),
                        xLabelPaint
                    )
                }
            }
            labelMs += interval.inWholeMilliseconds
            count++
        }
    }
}

internal fun getNiceTimeInterval(duration: Duration, targetTicks: Int = 4): Duration {
    val niceDurations: List<Duration> = listOf(
        1.seconds, 2.seconds, 5.seconds, 10.seconds, 15.seconds, 30.seconds,
        1.minutes, 2.minutes, 5.minutes, 10.minutes, 15.minutes, 30.minutes,
        1.hours, 2.hours, 3.hours, 4.hours, 6.hours, 12.hours,
        1.days, 2.days, 7.days
    )

    val targetInterval: Duration = duration / targetTicks.toDouble()

    return niceDurations.firstOrNull { it >= targetInterval } ?: niceDurations.last()
}

private fun DrawScope.drawYGridlines(range: AxisRange, gridLines: Int, pL: Float, pR: Float, pT: Float, pB: Float) {
    val plotHeight: Float = size.height - pT - pB
    val (niceMin: Double, niceMax: Double) = range.niceRange

    for (i in 0..gridLines) {
        val value: Double = niceMin + i * (niceMax - niceMin) / gridLines
        val y: Float = size.height - pB - ((value - range.yMin) / (range.yMax - range.yMin) * plotHeight).toFloat()
        if (y < pT - 1f || y > size.height - pB + 1f) continue
        if (i > 0) {
            drawLine(color = Color.Gray, start = Offset(pL, y), end = Offset(size.width - pR, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
        }
    }
}

private fun DrawScope.drawYAxis(axis: AxisConfig, range: AxisRange, gridLines: Int, pL: Float, pR: Float, pT: Float, pB: Float) {
    val plotHeight: Float = size.height - pT - pB
    val yLabelPaint: android.graphics.Paint = Paint().asFrameworkPaint().apply { isAntiAlias = true; color = axis.color.toArgb(); textSize = 16.sp.toPx() }
    val (niceMin: Double, niceMax: Double) = range.niceRange

    for (i in 0..gridLines) {
        val value: Double = niceMin + i * (niceMax - niceMin) / gridLines
        val y: Float = size.height - pB - ((value - range.yMin) / (range.yMax - range.yMin) * plotHeight).toFloat()
        if (y < pT - 1f || y > size.height - pB + 1f) continue

        val label: String = if (axis.name == "CO2") "${value.toInt()}" else "%.1f".format(value)
        val labelX: Float = if (axis.position == AxisPosition.LEFT) pL - yLabelPaint.measureText(label) - 4.dp.toPx()
        else size.width - pR + 4.dp.toPx()
        drawContext.canvas.nativeCanvas.drawText(label, labelX, y + yLabelPaint.textSize / 2, yLabelPaint)
    }
}

private fun formatTimestamp(timestamp: Long, range: Duration): String {
    val format: String = when {
        range > 2.days -> "MM/dd"
        range > 1.hours -> "HH:mm"
        else -> "HH:mm:ss"
    }
    return SimpleDateFormat(format, Locale.getDefault()).format(Date(timestamp))
}

/**
 * Calculates a "nice" range for the axis that starts and ends on clean multiples of a tick size.
 *
 * @param min The minimum data value.
 * @param max The maximum data value.
 * @param ticks The target number of intervals (default 4).
 * @return A pair representing the nice min and nice max.
 */
internal fun getNiceRange(min: Double, max: Double, ticks: Int = 4): Pair<Double, Double> {
    if (min == max) return Pair(min - 1.0, max + 1.0)

    val range: Double = max - min
    val unroundedTickSize: Double = range / ticks

    val x: Double = ceil(log10(unroundedTickSize) - 1.0)
    val pow10x: Double = 10.0.pow(x)
    val niceTick: Double = ceil(unroundedTickSize / pow10x) * pow10x

    val epsilon: Double = 1e-10
    val niceMin: Double = floor((min / niceTick) + epsilon) * niceTick
    val niceMax: Double = ceil((max / niceTick) - epsilon) * niceTick

    return Pair(niceMin, niceMax)
}

/**
 * Draws the series of data points as a continuous line on the canvas.
 *
 * @param data The points to draw.
 * @param valueSelector Selects the property to draw.
 * @param minVal Minimum y value to scale by.
 * @param maxVal Maximum y value to scale by.
 * @param color Color of the line.
 * @param getX Function returning the x position on canvas.
 * @param maxGapMs The max permitted time gap between points before line is broken.
 * @param paddingTop Top canvas padding.
 * @param paddingBottom Bottom canvas padding.
 */
fun DrawScope.drawSeries(
    data: List<SensorData>,
    valueSelector: (SensorData) -> Float,
    minVal: Float,
    maxVal: Float,
    color: Color,
    getX: (Long) -> Float,
    maxGapMs: Long,
    paddingTop: Float,
    paddingBottom: Float
) {
    val path: Path = Path()
    var lastTimestamp: Long? = null
    val plotHeight: Float = size.height - paddingTop - paddingBottom

    data.forEach { sensorData: SensorData ->
        val currentTimestamp: Long = sensorData.timestamp.toEpochMilliseconds()
        val x: Float = getX(currentTimestamp)
        
        val rawY: Float = valueSelector(sensorData)
        val normalizedY: Float = if (maxVal == minVal) 0.5f else (rawY - minVal) / (maxVal - minVal)
        val y: Float = size.height - paddingBottom - (normalizedY.coerceIn(0f, 1f) * plotHeight)

        val isFirstPoint: Boolean = lastTimestamp == null
        if (isFirstPoint) {
            path.moveTo(x, y)
        } else {
            val gap: Long = currentTimestamp - lastTimestamp
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

/**
 * Screen showing disconnected state.
 *
 * @param onScanClicked Callback when scan button is clicked.
 */
@Composable
fun DisconnectedScreen(onScanClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Ready to scan for your sensor.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onScanClicked) { Text("Scan for Devices") }
    }
}

/**
 * Screen showing scanning state.
 *
 * @param devices List of discovered devices.
 * @param onDeviceClicked Callback when a device is clicked.
 * @param onStopScanClicked Callback when stop scan button is clicked.
 */
@Composable
fun ScanningScreen(devices: List<DiscoveredDevice>, onDeviceClicked: (DiscoveredDevice) -> Unit, onStopScanClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scanning...", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (devices.isEmpty()) Text("No devices found yet...", modifier = Modifier.align(Alignment.Center))
            else LazyColumn { items(devices) { device: DiscoveredDevice -> DeviceListItem(device) { onDeviceClicked(device) } } }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStopScanClicked, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Stop Scan") }
    }
}

/**
 * Item representing a single discovered BLE device.
 *
 * @param device The discovered device.
 * @param onClick Callback when the device is clicked.
 */
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

/**
 * Screen showing the connecting state.
 *
 * @param deviceName Name of the device currently being connected to.
 */
@Composable
fun ConnectingScreen(deviceName: String?) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Connecting to ${deviceName ?: "Device"}...", style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Screen showing error messages.
 *
 * @param message The error message to display.
 * @param onRerequestClicked Callback when primary button is clicked.
 * @param onSettingsClicked Callback to go to settings, if null button is hidden.
 * @param firstButtonText Text to display on the primary button.
 */
@Composable
fun ErrorScreen(message: String, onRerequestClicked: () -> Unit, onSettingsClicked: (() -> Unit)?, firstButtonText: String) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("An Error Occurred", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, modifier = Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRerequestClicked) { Text(firstButtonText) }
        onSettingsClicked?.let { callback: () -> Unit -> Spacer(modifier = Modifier.height(8.dp)); Button(onClick = callback) { Text("Go to Settings") } }
    }
}
