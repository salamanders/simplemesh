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
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import info.benjaminhill.simplemesh.p2p.ConnectionPhase

import info.benjaminhill.simplemesh.p2p.DeviceState
import info.benjaminhill.simplemesh.p2p.EndpointId
import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.ui.theme.SimpleMeshTheme
import info.benjaminhill.simplemesh.util.DeviceIdentifier

class MainActivity : ComponentActivity() {

    // Holds the state for the UI, surviving screen rotations.
    private val viewModel: MainViewModel by viewModels {
        // This factory is needed to pass the connections manager to the ViewModel's constructor.
        MainViewModelFactory(
            (application as SimpleMeshApplication).nearbyConnectionsManager,
            EndpointName(DeviceIdentifier.get(this))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleMeshTheme {
                RequestPermissions {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        DeviceList(
                            devices = viewModel.devices.collectAsState().value,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
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
fun RequestPermissions(content: @Composable () -> Unit) {
    val permissionsToRequest =
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

    val allPermissionsGranted = remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        allPermissionsGranted.value = permissions.all { it.value }
    }
    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
    }

    if (allPermissionsGranted.value) {
        content()
    } else {
        Text("All permissions are required for the app to function.")
    }
}

@Composable
fun DeviceList(devices: Map<EndpointId, DeviceState>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(devices.values.toList()) { device ->
            DeviceRow(device)
        }
    }
}

@Composable
fun DeviceRow(device: DeviceState) {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = device.phase.icon,
            contentDescription = "Connection phase: ${device.phase}",
            tint = device.phase.color
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = "${device.name.value} (${device.endpointId.value})")
            Text(text = device.phase.toString(), color = device.phase.color)
        }
    }
}

// For displaying a sample device list in the Android Studio preview pane.
@Preview(showBackground = true)
@Composable
fun DeviceListPreview() {
    SimpleMeshTheme {
        DeviceList(
            devices = mapOf(
                EndpointId("preview1") to DeviceState(
                    EndpointId("endpoint1"),
                    EndpointName("Device 1"),
                    ConnectionPhase.CONNECTED
                ),
                EndpointId("preview2") to DeviceState(
                    EndpointId("endpoint2"),
                    EndpointName("Device 2"),
                    ConnectionPhase.DISCOVERED
                )
            )
        )
    }
}
