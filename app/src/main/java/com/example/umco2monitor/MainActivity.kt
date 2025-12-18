package com.example.umco2monitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.lifecycle.viewmodel.compose.viewModel
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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = bleConnectionState) {
            is BleConnectionState.Disconnected -> DisconnectedScreen(onScanClicked = { viewModel.startScan() })
            is BleConnectionState.Scanning -> ScanningScreen(devices = state.discoveredDevices, onDeviceClicked = { viewModel.connectToDevice(it) })
            is BleConnectionState.Connected -> ConnectedScreen(co2Value = co2Value, onDisconnectClicked = { viewModel.disconnect() })
            is BleConnectionState.Error -> {
                // Check if the error is due to permissions being denied
                val isPermissionError = "permission" in state.message.lowercase()

                if (isPermissionError) {
                    ErrorScreen(
                        message = state.message,
                        onRerequestClicked = { activity.reRequestPermissions() },
                        onSettingsClicked = { activity.openAppSettings() }
                    )
                } else {
                    ErrorScreen(
                        message = state.message,
                        onRerequestClicked = { viewModel.startScan() }
                    )
                }
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
 */
@Composable
fun ScanningScreen(devices: List<DiscoveredDevice>, onDeviceClicked: (DiscoveredDevice) -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Scanning...", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        if (devices.isEmpty()) {
            Text("No devices found yet...")
        } else {
            LazyColumn {
                items(devices) { device ->
                    DeviceListItem(device = device, onClick = { onDeviceClicked(device) })
                }
            }
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
 * @param onDisconnectClicked The action to perform when the "Disconnect" button is clicked.
 */
@Composable
fun ConnectedScreen(co2Value: UShort?, onDisconnectClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("CO2 Reading", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = co2Value?.toString() ?: "--",
            style = MaterialTheme.typography.displayLarge
        )
        Text("ppm")
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDisconnectClicked) {
            Text("Disconnect")
        }
    }
}

/**
 * This screen is displayed when an error occurs.
 * @param message The error message to display.
 * @param buttonText The text to display on the action button.
 * @param onButtonClicked The action to perform when the button is clicked.
 */
@Composable
fun ErrorScreen(message: String, onRerequestClicked: (() -> Unit)? = null, onSettingsClicked: (() -> Unit)? = null) {
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
                    Text("Rerequest Permissions")
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
