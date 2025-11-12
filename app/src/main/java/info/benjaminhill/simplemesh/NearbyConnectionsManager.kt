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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    private var gossipManager: GossipManager? = null

    fun setGossipManager(gossipManager: GossipManager) {
        this.gossipManager = gossipManager
    }

    companion object {
        private val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
        private const val MAX_CONNECTIONS = 4
    }


    // Handles events like new connection requests, results, and disconnections.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        // another device wants to connect to us
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.tag("P2P_MESH").d("onConnectionInitiated: endpointId=$endpointId")
            // Automatically accept all connections
            DevicesRegistry.updateDeviceStatus(
                endpointId = endpointId,
                externalScope = externalScope,
                newPhase = ConnectionPhase.CONNECTING
            )
            connectionsClient.acceptConnection(endpointId, object : PayloadCallback() {
                // Triggered when we get a PING or a PONG.
                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    when (payload.type) {
                        Payload.Type.BYTES -> {
                            val data = payload.asBytes()!!
                            when {
                                data.contentEquals(PING) -> {
                                    Timber.tag("P2P_MESH").d("Received PING from $endpointId")
                                    connectionsClient.sendPayload(
                                        endpointId,
                                        Payload.fromBytes(PONG)
                                    )
                                }

                                data.contentEquals(PONG) -> {
                                    Timber.tag("P2P_MESH").d("Received PONG from $endpointId")
                                    connectedSendPing(endpointId, 30.seconds)
                                }

                                else -> {
                                    gossipManager?.handlePayload(data)
                                }
                            }
                        }

                        else -> {
                            // Ignore other payload types
                        }
                    }
                }

                override fun onPayloadTransferUpdate(
                    endpointId: String,
                    update: PayloadTransferUpdate
                ) {
                    // Not used in this implementation
                }
            })
        }

        // Resets the connection phase every time, and sends a ping in a few seconds
        fun connectedSendPing(endpointId: String, delay: Duration = 15.seconds) {
            val device = DevicesRegistry.getLatestDeviceState(endpointId)
            if (device != null) {
                DevicesRegistry.resetRetryCount(device.name)
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = ConnectionPhase.CONNECTED
                )
                externalScope.launch {
                    delay(delay)
                    connectionsClient.sendPayload(endpointId, Payload.fromBytes(PING))
                }
            }
        }

        // a connection attempt succeeds or fails.
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionPhase.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionPhase.REJECTED
                else -> ConnectionPhase.ERROR
            }
            Timber.tag("P2P_MESH").d("onConnectionResult: endpointId=$endpointId, status=$status")
            if (status == ConnectionPhase.CONNECTED) {
                val localDeviceName = DeviceIdentifier.get(activity)
                val connectedNeighbors = DevicesRegistry.devices.value.values
                    .filter { it.phase == ConnectionPhase.CONNECTED }
                    .map { it.name }
                    .toSet()
                DevicesRegistry.updateLocalDeviceInGraph(localDeviceName, connectedNeighbors)
                connectedSendPing(endpointId)
            } else {
                val device = DevicesRegistry.getLatestDeviceState(endpointId)
                if (device != null) {
                    DevicesRegistry.updateDeviceStatus(
                        endpointId = endpointId,
                        externalScope = externalScope,
                        newPhase = status
                    )
                    // STATUS_ERROR is a transient error, so we should retry.
                    if (result.status.statusCode == ConnectionsStatusCodes.STATUS_ERROR) {
                        reconnectWithBackoff(device.name)
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.tag("P2P_MESH").d("onDisconnected: endpointId=$endpointId")
            val device = DevicesRegistry.getLatestDeviceState(endpointId)
            if (device != null) {
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = ConnectionPhase.DISCONNECTED
                )
                val localDeviceName = DeviceIdentifier.get(activity)
                val connectedNeighbors = DevicesRegistry.devices.value.values
                    .filter { it.phase == ConnectionPhase.CONNECTED }
                    .map { it.name }
                    .toSet()
                DevicesRegistry.updateLocalDeviceInGraph(localDeviceName, connectedNeighbors)
                reconnectWithBackoff(device.name)
            }
        }
    }


    // Handles receiving data (payloads) from other devices.

    fun startAdvertising() {
        Timber.tag("P2P_MESH").d("startAdvertising")
        val advertisingOptions =
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            DeviceIdentifier.get(activity),
            activity.packageName,
            connectionLifecycleCallback,
            advertisingOptions
        )
    }

    fun startDiscovery() {
        manageConnections()
        Timber.tag("P2P_MESH").d("startDiscovery")
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            activity.packageName,
            object : EndpointDiscoveryCallback() {
                // a new device is discovered nearby.
                override fun onEndpointFound(
                    endpointId: String,
                    discoveredEndpointInfo: DiscoveredEndpointInfo
                ) {
                    Timber.tag("P2P_MESH").d("onEndpointFound: endpointId=$endpointId")
                    DevicesRegistry.updateDeviceStatus(
                        endpointId = endpointId,
                        externalScope = externalScope,
                        newPhase = ConnectionPhase.DISCOVERED,
                        newName = discoveredEndpointInfo.endpointName,
                    )
                    // Do not immediately connect, just add to the list of potential peers
                    // A separate job will decide who to connect to.
                    DevicesRegistry.addPotentialPeer(endpointId)
                }

                override fun onEndpointLost(endpointId: String) {
                    Timber.tag("P2P_MESH").d("onEndpointLost: endpointId=$endpointId")
                    val device = DevicesRegistry.getLatestDeviceState(endpointId)
                    if (device != null) {
                        DevicesRegistry.resetRetryCount(device.name)
                        DevicesRegistry.remove(endpointId)
                    }
                }
            },
            discoveryOptions
        )
    }

    fun stopAll() {
        Timber.tag("P2P_MESH").d("stopAll")
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun manageConnections() = externalScope.launch {
        while (true) {
            val connectedDevices =
                DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED }
            val potentialPeers = DevicesRegistry.potentialPeers.value
            if (connectedDevices.size < MAX_CONNECTIONS) {
                val networkGraph = DevicesRegistry.networkGraph.value
                val allKnownDevices = networkGraph.keys
                val unconnectedPeers = potentialPeers.filter {
                    val device = DevicesRegistry.getLatestDeviceState(it)
                    device != null && !allKnownDevices.contains(device.name)
                }

                val peerToConnect = unconnectedPeers.firstOrNull()

                if (peerToConnect != null) {
                    Timber.tag("P2P_MESH")
                        .d("manageConnections: Attempting to connect to an unconnected peer: $peerToConnect")
                    connectionsClient.requestConnection(
                        DeviceIdentifier.get(activity),
                        peerToConnect,
                        connectionLifecycleCallback
                    )
                } else {
                    val randomPeer = potentialPeers.firstOrNull {
                        !DevicesRegistry.devices.value.containsKey(it)
                    }
                    if (randomPeer != null) {
                        Timber.tag("P2P_MESH")
                            .d("manageConnections: Attempting to connect to a random peer: $randomPeer")
                        connectionsClient.requestConnection(
                            DeviceIdentifier.get(activity),
                            randomPeer,
                            connectionLifecycleCallback
                        )
                    }
                }
            }
            delay(5.seconds)
        }
    }

    fun broadcast(data: ByteArray) {
        val connectedDevices =
            DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED }
        connectedDevices.forEach { device ->
            connectionsClient.sendPayload(device.endpointId, Payload.fromBytes(data))
        }
    }

    private fun reconnectWithBackoff(name: String) {
        externalScope.launch {
            // If we are connected to other devices, don't try to reconnect.
            // Let the normal discovery process find the device again.
            val otherConnectedDevices = DevicesRegistry.devices.value.values.any {
                it.phase == ConnectionPhase.CONNECTED
            }
            if (otherConnectedDevices) {
                Timber.tag("P2P_MESH")
                    .i("reconnectWithBackoff: Other devices are connected, aborting reconnection attempt for $name.")
                return@launch
            }

            val retryCount = DevicesRegistry.getRetryCount(name)
            if (retryCount >= 4) {
                Timber.tag("P2P_MESH")
                    .w("reconnectWithBackoff: Device $name reached max retries, giving up.")
                DevicesRegistry.devices.value.values.find { it.name == name }?.let {
                    DevicesRegistry.remove(it.endpointId)
                }
                return@launch
            }

            val delayDuration = (5 * (1 shl retryCount)).seconds
            val jitter = (0..1000).random()
            Timber.tag("P2P_MESH")
                .i("reconnectWithBackoff: Attempting to reconnect to $name in $delayDuration (retry #${retryCount + 1}) with ${jitter}ms jitter")
            delay(delayDuration.inWholeMilliseconds + jitter)

            DevicesRegistry.incrementRetryCount(name)

            stopAll()
            startAdvertising()
            startDiscovery()
        }
    }
}
