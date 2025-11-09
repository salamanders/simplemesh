package info.benjaminhill.simplemesh

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import info.benjaminhill.simplemesh.ui.theme.SimpleMeshTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleMeshTheme {
                RequestPermissions()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DeviceList(
                        devices = viewModel.devices.collectAsState().value,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startAdvertising()
        viewModel.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAll()
    }
}

@Composable
fun RequestPermissions() {
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            // Handle permission grant/denial
        }
    }
    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
    }
}

@Composable
fun DeviceList(devices: Map<String, DeviceState>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(devices.values.toList()) { device ->
            Column {
                Text(text = "Endpoint ID: ${device.endpointId}")
                Text(text = "Name: ${device.name}")
                Text(text = "Status: ${device.status}")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceListPreview() {
    SimpleMeshTheme {
        DeviceList(
            devices = mapOf(
                "endpoint1" to DeviceState("endpoint1", "Device 1", ConnectionStatus.CONNECTED),
                "endpoint2" to DeviceState("endpoint2", "Device 2", ConnectionStatus.DISCOVERED)
            )
        )
    }
}
