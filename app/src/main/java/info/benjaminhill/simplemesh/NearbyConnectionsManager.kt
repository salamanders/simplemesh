package info.benjaminhill.simplemesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.Job

class NearbyConnectionsManager(
    private val context: Context,
    private val externalScope: CoroutineScope
) {

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }
    private val heartbeatJobs = mutableMapOf<String, Job>()
    private val connectionTimeoutJobs = mutableMapOf<String, Job>()

    companion object {
        private val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
    }

    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.tag("P2P_MESH").d("onConnectionInitiated: endpointId=$endpointId")
            // Automatically accept all connections
            updateDeviceStatus(endpointId, ConnectionStatus.CONNECTING)
            connectionsClient.acceptConnection(endpointId, payloadCallback)

            // Add a timeout for the connection
            connectionTimeoutJobs[endpointId] = externalScope.launch {
                delay(30_000)
                val currentDevice = _devices.value[endpointId]
                if (currentDevice?.status == ConnectionStatus.CONNECTING) {
                    Timber.tag("P2P_MESH").w("Connection to $endpointId timed out.")
                    updateDeviceStatus(endpointId, ConnectionStatus.ERROR)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectionTimeoutJobs.remove(endpointId)?.cancel()
            val status = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionStatus.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionStatus.REJECTED
                else -> ConnectionStatus.ERROR
            }
            Timber.tag("P2P_MESH").d("onConnectionResult: endpointId=$endpointId, status=$status")
            updateDeviceStatus(endpointId, status)

            if (status == ConnectionStatus.CONNECTED) {
                heartbeatJobs[endpointId] = startHeartbeat(endpointId)
            } else {
                heartbeatJobs.remove(endpointId)?.cancel()
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.tag("P2P_MESH").d("onDisconnected: endpointId=$endpointId")
            updateDeviceStatus(endpointId, ConnectionStatus.DISCONNECTED)
            heartbeatJobs.remove(endpointId)?.cancel()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            Timber.tag("P2P_MESH").d("onEndpointFound: endpointId=$endpointId")
            _devices.value += endpointId to DeviceState(
                endpointId = endpointId,
                name = discoveredEndpointInfo.endpointName,
                status = ConnectionStatus.DISCOVERED
            )
            connectionsClient.requestConnection("phone", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.tag("P2P_MESH").d("onEndpointLost: endpointId=$endpointId")
            _devices.value -= endpointId
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = payload.asBytes()!!
                    when {
                        data.contentEquals(PING) -> {
                            Timber.tag("P2P_MESH").d("Received PING from $endpointId")
                            connectionsClient.sendPayload(endpointId, Payload.fromBytes(PONG))
                        }
                        data.contentEquals(PONG) -> {
                            Timber.tag("P2P_MESH").d("Received PONG from $endpointId")
                            // Reset the timeout
                            heartbeatJobs[endpointId]?.cancel()
                            heartbeatJobs[endpointId] = startHeartbeat(endpointId)
                        }
                    }
                }
                else -> {
                    // Ignore other payload types
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not used in this implementation
        }
    }

    private fun startHeartbeat(endpointId: String) = externalScope.launch {
        while (true) {
            delay(15_000)
            Timber.tag("P2P_MESH").d("Sending PING to $endpointId")
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(PING))
            delay(15_000)
            Timber.tag("P2P_MESH").w("No PONG from $endpointId, assuming disconnected.")
            updateDeviceStatus(endpointId, ConnectionStatus.ERROR)
            heartbeatJobs.remove(endpointId)?.cancel()
        }
    }

    fun startAdvertising() {
        Timber.tag("P2P_MESH").d("startAdvertising")
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            "phone",
            context.packageName,
            connectionLifecycleCallback,
            advertisingOptions
        )
    }

    fun startDiscovery() {
        Timber.tag("P2P_MESH").d("startDiscovery")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            context.packageName,
            endpointDiscoveryCallback,
            discoveryOptions
        )
        // Add a timeout for the discovery
        externalScope.launch {
            delay(30_000)
            if (_devices.value.none { it.value.status == ConnectionStatus.DISCOVERED }) {
                Timber.tag("P2P_MESH").w("Discovery timed out, no devices found.")
                _devices.value += "discovery_failed" to DeviceState(
                    endpointId = "discovery_failed",
                    name = "Discovery Failed",
                    status = ConnectionStatus.DISCOVERY_FAILED
                )
            }
        }
    }

    fun stopAll() {
        Timber.tag("P2P_MESH").d("stopAll")
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun updateDeviceStatus(endpointId: String, status: ConnectionStatus) {
        val currentDevice = _devices.value[endpointId] ?: return
        _devices.value += (endpointId to currentDevice.copy(status = status))
    }
}
