package info.benjaminhill.simplemesh

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    val permissionsToRequest =
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            // TODO: Handle permission grant/denial
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
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (icon, color) = when (device.status) {
                    ConnectionStatus.DISCOVERY_FAILED -> Icons.Default.Warning to Color.Red
                    ConnectionStatus.DISCOVERED -> Icons.Default.Info to Color.Blue
                    ConnectionStatus.CONNECTING -> Icons.Default.Info to Color.Gray
                    ConnectionStatus.CONNECTED -> Icons.Default.CheckCircle to Color.Green
                    ConnectionStatus.REJECTED -> Icons.Default.Close to Color.Red
                    ConnectionStatus.ERROR -> Icons.Default.Warning to Color.Red
                    ConnectionStatus.DISCONNECTED -> Icons.Default.Close to Color.Gray
                }
                Icon(imageVector = icon, contentDescription = null, tint = color)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = device.name)
                    Text(text = device.status.toString(), color = color)
                }
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
