package info.benjaminhill.simplemesh

import android.app.Activity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** 
 * Core class for finding, connecting to, and communicating with other devices.
 */
class NearbyConnectionsManager(
    // The main screen, required for NFC and other UI-related connection tasks.
    private val activity: Activity,
    // Coroutine scope from the ViewModel to tie background jobs to the ViewModel's lifecycle.
    private val externalScope: CoroutineScope
) {

    // The main object from Google's Nearby Connections library.
    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(activity)
    }

    companion object {
        private val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
    }

    // Internal, mutable list of devices. Entire map gets cloned every time there is an update.
    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())

    // External, read-only list of devices for the UI.
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    // Handles events like new connection requests, results, and disconnections.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        // another device wants to connect to us
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.tag("P2P_MESH").d("onConnectionInitiated: endpointId=$endpointId")
            // Automatically accept all connections
            updateDeviceStatus(endpointId, ConnectionState.CONNECTING)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        // a connection attempt succeeds or fails.
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionState.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionState.REJECTED
                else -> ConnectionState.ERROR
            }
            Timber.tag("P2P_MESH").d("onConnectionResult: endpointId=$endpointId, status=$status")
            updateDeviceStatus(endpointId, status)
        }

        override fun onDisconnected(endpointId: String) {
            Timber.tag("P2P_MESH").d("onDisconnected: endpointId=$endpointId")
            updateDeviceStatus(endpointId, ConnectionState.DISCONNECTED)
        }
    }

    // Handles finding and losing other devices on the network.
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        // a new device is discovered nearby.
        override fun onEndpointFound(
            endpointId: String,
            discoveredEndpointInfo: DiscoveredEndpointInfo
        ) {
            Timber.tag("P2P_MESH").d("onEndpointFound: endpointId=$endpointId")
            updateDeviceStatus(
                endpointId,
                ConnectionState.DISCOVERED,
                discoveredEndpointInfo.endpointName
            )
            connectionsClient.requestConnection("phone", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            Timber.tag("P2P_MESH").d("onEndpointLost: endpointId=$endpointId")
            _devices.value -= endpointId
        }
    }

    // Handles receiving data (payloads) from other devices.
    private val payloadCallback = object : PayloadCallback() {
        // Triggered when we get a PING or a PONG.
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
                            // Reset the timeout by creating a new heartbeat
                            updateDeviceStatus(endpointId, ConnectionState.CONNECTED)
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

    // Periodically sends a PING to a device to ensure it's still connected.
    private fun startHeartbeat(endpointId: String) = externalScope.launch {
        while (true) {
            delay(15_000)
            Timber.tag("P2P_MESH").d("Sending PING to $endpointId")
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(PING))
            delay(15_000)
            Timber.tag("P2P_MESH").w("No PONG from $endpointId, assuming disconnected.")
            updateDeviceStatus(endpointId, ConnectionState.ERROR)
        }
    }

    fun startAdvertising() {
        Timber.tag("P2P_MESH").d("startAdvertising")
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            "phone",
            activity.packageName,
            connectionLifecycleCallback,
            advertisingOptions
        )
    }

    fun startDiscovery() {
        Timber.tag("P2P_MESH").d("startDiscovery")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            activity.packageName,
            endpointDiscoveryCallback,
            discoveryOptions
        )
        // Add a timeout for the discovery
        externalScope.launch {
            delay(30_000)
            if (_devices.value.none { it.value.status == ConnectionState.DISCOVERED }) {
                Timber.tag("P2P_MESH").w("Discovery timed out, no devices found.")
                _devices.value += "discovery_failed" to DeviceState(
                    endpointId = "discovery_failed",
                    name = "Discovery Failed",
                    status = ConnectionState.DISCOVERY_FAILED
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

    private fun updateDeviceStatus(
        endpointId: String,
        status: ConnectionState,
        name: String? = null
    ) {
        val existingDevice = _devices.value[endpointId]
        val newName = name ?: existingDevice?.name ?: "Unknown"

        // Cancel any existing job
        existingDevice?.stateJob?.cancel()

        val stateJob: Job? = status.createJob(
            scope = externalScope,
            endpointId = endpointId,
            removeDevice = { _devices.value -= endpointId },
            startHeartbeat = { startHeartbeat(endpointId) },
            getCurrentStatus = { _devices.value[endpointId]?.status }
        )

        _devices.value += endpointId to DeviceState(endpointId, newName, status, stateJob)
    }
}
