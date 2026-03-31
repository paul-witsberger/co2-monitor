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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.umco2monitor.ui.theme.UMCO2MonitorTheme

/**
 * Main activity of the application. This is the entry point of the app.
 */
class MainActivity : ComponentActivity() {
    /*
    The ViewModel for this activity. This stores the current state of the app. "by viewModels"
    delegates the creation of the ViewModel to the Android framework so that the viewModel persists
    even after the activity is destroyed.
    */
    private val viewModel: SensorViewModel by viewModels { SensorViewModelFactory(application) }

    // Handles the result of the runtime permission request dialog.
    private val requestMultiplePermissions: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if any of the permissions were denied
            val fineLocationDenied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                false
            } else {
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == false
            }
            // BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions did not exist before Android S
            val bluetoothConnectDenied = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == false
            } else {
                false
            }
            val bluetoothScanDenied = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions[Manifest.permission.BLUETOOTH_SCAN] == false
            } else {
                false
            }

            // If a necessary permission was denied, inform the user
            if (fineLocationDenied || bluetoothConnectDenied || bluetoothScanDenied) {
                // Show a dialog to explain why the permission is needed
                Toast.makeText(
                    this,
                    "Bluetooth and location permissions are required to scan for sensors. Please try again and accept the permissions for this app to function.",
                    Toast.LENGTH_LONG
                ).show()
                // Update the app state to reflect the permission decision
                viewModel.onPermissionsDenied()
            }
        }

    // Relaunches the permission request dialog in case the user has denied the necessary permissions
    fun reRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestMultiplePermissions.launch(permissions)
    }

    // Opens the application's details screen in the system settings to allow the user to manually
    // enable the necessary permissions
    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    /**
     * The entry point for the activity. This function sets up the UI, requests necessary
     * Bluetooth permissions, and initializes the ViewModel.
     * @param savedInstanceState If the activity is being re-initialized after previously being shut
     * down then this Bundle contains the data it most recently supplied in
     * onSaveInstanceState(Bundle).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for convenience - can remove this after development
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestMultiplePermissions.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            requestMultiplePermissions.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }

        // Sets up the UI
        setContent {
            UMCO2MonitorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = colorResource(id = R.color.michigan_blue)
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        activity = this@MainActivity,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

/**
 * The main container Composable that acts as a router for the app's UI.
 * It observes the [BleConnectionState] and displays the appropriate screen
 * (e.g., [DisconnectedScreen], [ScanningScreen], etc.) based on the current state.
 *
 * @param viewModel The app's central ViewModel, providing state and event handlers.
 * @param activity The activity that the UI is displayed in.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun MainScreen(viewModel: SensorViewModel, activity: MainActivity, modifier: Modifier = Modifier) {
    val bleConnectionState by viewModel.bleConnectionState.collectAsState()
    val co2Value by viewModel.co2Value.collectAsState()
    val temperatureValue by viewModel.temperatureValue.collectAsState()
    val humidityValue by viewModel.humidityValue.collectAsState()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = bleConnectionState) {
            is BleConnectionState.Disconnected -> DisconnectedScreen(onScanClicked = { viewModel.startScan() })
            is BleConnectionState.Scanning -> ScanningScreen(
                devices = state.discoveredDevices,
                onDeviceClicked = { viewModel.connectToDevice(it) },
                onStopScanClicked = { viewModel.stopScan() }
            )
            is BleConnectionState.Connecting -> ConnectingScreen(deviceName = state.deviceName)
            is BleConnectionState.Connected -> ConnectedScreen(
                co2Value = co2Value,
                temperatureValue = temperatureValue,
                humidityValue = humidityValue,
                onDisconnectClicked = { viewModel.disconnect() }
            )
            is BleConnectionState.Error -> {
                val isPermissionError = state.message.contains("permission", ignoreCase = true)
                ErrorScreen(
                    message = state.message,
                    onRerequestClicked = {
                        if (isPermissionError) activity.reRequestPermissions()
                        else viewModel.startScan()
                    },
                    onSettingsClicked = if (isPermissionError) { { activity.openAppSettings() } } else null,
                    firstButtonText = if (isPermissionError) "Rerequest Permissions" else "Restart Scan"
                )
            }
        }
    }
}

/**
 * This screen is displayed when the app is not connected to a BLE device.
 * @param onScanClicked The action to perform when the "Scan for Devices" button is clicked.
 */
@Composable
fun DisconnectedScreen(onScanClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ready to scan for your sensor.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onScanClicked) {
            Text("Scan for Devices")
        }
    }
}

/**
 * This screen is displayed when the app is scanning for BLE devices.
 * @param devices The list of discovered devices.
 * @param onDeviceClicked The action to perform when a device is clicked.
 * @param onStopScanClicked The action to perform when the "Stop Scan" button is clicked.
 */
@Composable
fun ScanningScreen(devices: List<DiscoveredDevice>, onDeviceClicked: (DiscoveredDevice) -> Unit, onStopScanClicked: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Show "Scanning..." at the top of the screen
        Text("Scanning...",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(16.dp))
        // Either show the list of devices or a message if no devices have been found yet
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (devices.isEmpty()) {
                Text("No devices found yet...", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn {
                    items(devices) { device ->
                        DeviceListItem(device = device, onClick = { onDeviceClicked(device) })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Allow the user to stop scanning
        Button(
            onClick = onStopScanClicked,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("Stop Scan")
        }
    }
}

/**
 * This is a list item that represents a discovered device.
 * @param device The discovered device.
 * @param onClick The action to perform when the device is clicked.
 */
@Composable
fun DeviceListItem(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name ?: "Unknown Device", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = device.address)
        }
    }
}

/**
 * This screen is displayed when the app is connected to a BLE device.
 * @param co2Value The current CO2 value.
 * @param temperatureValue The current temperature value.
 * @param humidityValue The current humidity value.
 * @param onDisconnectClicked The action to perform when the "Disconnect" button is clicked.
 */
@Composable
fun ConnectedScreen(    co2Value: UShort?,
                        temperatureValue: Float?,
                        humidityValue: Float?,
                        onDisconnectClicked: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- CO2 SECTION (Hero/Emphasis) ---
        Text("CO2 CONCENTRATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(
            text = co2Value?.toString() ?: "--",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text("ppm", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(48.dp))

        // --- SECONDARY DATA SECTION (Smaller, side-by-side) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Temperature
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TEMPERATURE", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = temperatureValue?.let { "%.1f".format(it) } ?: "--",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text("°F", style = MaterialTheme.typography.bodySmall)
            }

            // Humidity
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HUMIDITY", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = humidityValue?.let { "%.1f".format(it) } ?: "--",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text("%", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        Button(onClick = onDisconnectClicked) {
            Text("Disconnect")
        }
    }
}

/**
 * This screen is displayed when the app is connecting to a BLE device.
 * @param deviceName The name of the device being connected to.
 */
@Composable
fun ConnectingScreen(deviceName: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connecting to ${deviceName ?: "Device"}...",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "This should take 5-10 seconds...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// TODO Make sure the appropriate permissions are set before allowing the transition to the scanning screen
/**
 * This screen is displayed when an error occurs.
 * @param message The error message to display.
 * @param onRerequestClicked The action to perform when the "Rerequest Permissions" button is clicked.
 * @param onSettingsClicked The action to perform when the "Go to Settings" button is clicked.
 * @param firstButtonText The text to display on the first button. Defaults to "Rerequest Permissions", but for other errors it can be something else.
 */
@Composable
fun ErrorScreen(
    message: String,
    onRerequestClicked: (() -> Unit)? = null,
    onSettingsClicked: (() -> Unit)? = null,
    firstButtonText: String = "Rerequest Permissions") {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("An Error Occurred", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, modifier = Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            onRerequestClicked?.let { onClick ->
                Button(onClick = onClick) {
                    Text(firstButtonText)
                }
            }
            onSettingsClicked?.let { onClick ->
                Button(onClick = onClick) {
                    Text("Go to Settings")
                }
            }
        }
    }
}
