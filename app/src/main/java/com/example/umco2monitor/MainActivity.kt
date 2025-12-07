package com.example.umco2monitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.umco2monitor.ui.theme.UMCO2MonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            requestMultiplePermissions.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }

        enableEdgeToEdge()
        setContent {
            val viewModel: SensorViewModel = viewModel(
                factory = SensorViewModelFactory(application)
            )

            UMCO2MonitorTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = colorResource(id = R.color.michigan_blue)
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private val requestMultiplePermissions: ActivityResultLauncher<Array<String>?> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            // You can handle individual permission results here if needed
        }
    }
}

@Composable
fun MainScreen(viewModel: SensorViewModel, modifier: Modifier = Modifier) {
    val bleConnectionState: BleConnectionState by viewModel.bleConnectionState.collectAsState()
    val co2Value: Int? by viewModel.co2Value.collectAsState()

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val state: BleConnectionState = bleConnectionState) {
            is BleConnectionState.Disconnected -> {
                Text(
                    text = "Ready to scan for BLE devices.",
                    color = colorResource(id = R.color.michigan_maize),
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { viewModel.startScan() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Scan for Devices")
                }
            }
            is BleConnectionState.Scanning -> {
                Text(
                    text = "Scanning for devices...",
                    color = colorResource(id = R.color.michigan_maize),
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { viewModel.stopScan() },
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
                ) {
                    Text("Stop Scan")
                }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.discoveredDevices) { device ->
                        DiscoveredDeviceItem(device = device) {
                            viewModel.connectToDevice(device)
                        }
                    }
                }
            }
            is BleConnectionState.Connected -> {
                Text(
                    text = "Connected to ${state.device.name ?: "Unknown Device"}",
                    color = colorResource(id = R.color.michigan_maize),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "CO2: ${co2Value ?: "--"} ppm",
                    color = colorResource(id = R.color.white),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Button(onClick = { viewModel.disconnect() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Disconnect")
                }
            }
            is BleConnectionState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(text = "Scan Failed", style = MaterialTheme.typography.titleLarge)
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.startScan() }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceItem(
    device: DiscoveredDevice,
    onConnect: () -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable { onConnect() }
        .padding(16.dp)
    ) {
        Text(
            text = device.name ?: "Unknown Device",
            color = colorResource(id = R.color.white)
        )
        Text(
            text = device.address,
            color = colorResource(id = R.color.white)
        )
    }
}
