package info.benjaminhill.simplemesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NearbyConnectionsManager(private val context: Context) {

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Automatically accept all connections
            updateDeviceStatus(endpointId, ConnectionStatus.CONNECTING)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionStatus.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionStatus.REJECTED
                else -> ConnectionStatus.ERROR
            }
            updateDeviceStatus(endpointId, status)
        }

        override fun onDisconnected(endpointId: String) {
            updateDeviceStatus(endpointId, ConnectionStatus.DISCONNECTED)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
            _devices.value += endpointId to DeviceState(
                endpointId = endpointId,
                name = discoveredEndpointInfo.endpointName,
                status = ConnectionStatus.DISCOVERED
            )
            connectionsClient.requestConnection("phone", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            _devices.value -= endpointId
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Not used in this implementation
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not used in this implementation
        }
    }

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            "phone",
            context.packageName,
            connectionLifecycleCallback,
            advertisingOptions
        )
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(context.packageName, endpointDiscoveryCallback, discoveryOptions)
    }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun updateDeviceStatus(endpointId: String, status: ConnectionStatus) {
        val currentDevice = _devices.value[endpointId] ?: return
        _devices.value = _devices.value + (endpointId to currentDevice.copy(status = status))
    }
}

data class DeviceState(
    val endpointId: String,
    val name: String,
    val status: ConnectionStatus
)

enum class ConnectionStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    REJECTED,
    ERROR
}
