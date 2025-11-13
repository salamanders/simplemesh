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
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
    private var routingEngine: RoutingEngine? = null

    private var manageConnectionsJob: Job? = null
    private var connectionRotationJob: Job? = null

    fun setGossipManager(gossipManager: GossipManager) {
        this.gossipManager = gossipManager
    }

    fun setRoutingEngine(routingEngine: RoutingEngine) {
        this.routingEngine = routingEngine
    }

    companion object {
        private val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
        private const val MAX_CONNECTIONS = 4
        private const val MAX_PAYLOAD_SIZE = 32 * 1024 // 32KB
    }


    // Handles events like new connection requests, results, and disconnections.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        // another device wants to connect to us
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Timber.tag("P2P_MESH").d("onConnectionInitiated: endpointId=$endpointId")

            val connectedPeers =
                DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED }
            if (connectedPeers.size >= MAX_CONNECTIONS) {
                val networkGraph = DevicesRegistry.networkGraph.value
                val myNeighbors =
                    networkGraph[DeviceIdentifier.get(activity)]?.toList() ?: emptyList()

                var disconnectedPeer = false
                // Find a pair of my neighbors who are also neighbors with each other
                for (i in myNeighbors.indices) {
                    for (j in i + 1 until myNeighbors.size) {
                        val neighbor1 = myNeighbors[i]
                        val neighbor2 = myNeighbors[j]
                        if (networkGraph[neighbor1]?.contains(neighbor2) == true) {
                            val peerToDisconnect =
                                listOf(neighbor1, neighbor2).random() // Pick one to disconnect
                            val endpointToDisconnect =
                                DevicesRegistry.devices.value.values.find { it.name == peerToDisconnect }?.endpointId
                            if (endpointToDisconnect != null) {
                                Timber.tag("P2P_MESH").i("onConnectionInitiated: At connection limit, but found redundant peer. Disconnecting from $peerToDisconnect to make room for $endpointId")
                                connectionsClient.disconnectFromEndpoint(endpointToDisconnect)
                                disconnectedPeer = true
                                break // Exit inner loop
                            } else {
                                Timber.tag("P2P_MESH").w("onConnectionInitiated: Wanted to disconnect from redundant peer $peerToDisconnect, but could not find endpointId.")
                            }
                        }
                    }
                    if (disconnectedPeer) break // Exit outer loop
                }

                if (!disconnectedPeer) {
                    Timber.tag("P2P_MESH")
                        .i("onConnectionInitiated: Rejecting connection from $endpointId, at capacity and no redundant peers found.")
                    connectionsClient.rejectConnection(endpointId)
                    return
                }
            }

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
                                    when (val packet = Packet.fromByteArray(data)) {
                                        is Packet.GossipPacket -> gossipManager?.handlePayload(
                                            packet.data
                                        )

                                        is Packet.MeshPacket -> routingEngine?.handlePayload(
                                            packet.meshPayload
                                        )
                                    }
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
            val newPhase = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionPhase.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionPhase.REJECTED
                else -> ConnectionPhase.ERROR
            }
            Timber.tag("P2P_MESH")
                .d("onConnectionResult: endpointId=$endpointId, status=$newPhase")

            DevicesRegistry.getLatestDeviceState(endpointId)?.let { device ->
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = newPhase
                )

                if (newPhase == ConnectionPhase.CONNECTED) {
                    val localDeviceName = DeviceIdentifier.get(activity)
                    val connectedNeighbors = DevicesRegistry.devices.value.values
                        .filter { it.phase == ConnectionPhase.CONNECTED }
                        .map { it.name }
                        .toSet()
                    DevicesRegistry.updateLocalDeviceInGraph(localDeviceName, connectedNeighbors)
                    connectedSendPing(endpointId)
                } else if (result.status.statusCode == ConnectionsStatusCodes.STATUS_ERROR) {
                    reconnectWithBackoff(device.name)
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
                    .filter { it.phase == ConnectionPhase.CONNECTED && it.endpointId != endpointId }
                    .map { it.name }
                    .toSet()
                DevicesRegistry.updateLocalDeviceInGraph(localDeviceName, connectedNeighbors)
                DevicesRegistry.removeDeviceFromGraph(device.name) // Remove from global graph
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
        start()
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
        stop()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun start() {
        if (manageConnectionsJob?.isActive != true) {
            manageConnectionsJob = manageConnections()
        }
        if (connectionRotationJob?.isActive != true) {
            connectionRotationJob = connectionRotation()
        }
    }

    private fun stop() {
        manageConnectionsJob?.cancel()
        connectionRotationJob?.cancel()
    }

    private fun connectionRotation() = externalScope.launch {
        while (true) {
            delay(5.minutes.inWholeMilliseconds + (1..60).random().seconds.inWholeMilliseconds)

            val networkGraph = DevicesRegistry.networkGraph.value
            val myName = DeviceIdentifier.get(activity)
            val myNeighbors = networkGraph[myName] ?: emptySet()

            if (myNeighbors.size >= MAX_CONNECTIONS) {
                // Find leaf nodes (neighbors with only one connection, which is to us)
                val leafNodes = myNeighbors.filter { neighbor ->
                    (networkGraph[neighbor]?.size ?: 0) == 1
                }

                if (leafNodes.isNotEmpty()) {
                    val nodeToDisconnect = leafNodes.random()
                    val endpointToDisconnect =
                        DevicesRegistry.devices.value.values.find { it.name == nodeToDisconnect }?.endpointId
                    if (endpointToDisconnect != null) {
                        Timber.tag("P2P_MESH")
                            .i("Connection Rotation: Disconnecting from leaf node $nodeToDisconnect to find new peers.")
                        connectionsClient.disconnectFromEndpoint(endpointToDisconnect)
                    }
                }
            }
        }
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
        if (data.size > MAX_PAYLOAD_SIZE) {
            Timber.tag("P2P_MESH").e("broadcast: Payload size ${data.size} exceeds MAX_PAYLOAD_SIZE ${MAX_PAYLOAD_SIZE}. Dropping payload.")
            return
        }
        val connectedDevices =
            DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED }
        connectedDevices.forEach { device ->
            connectionsClient.sendPayload(device.endpointId, Payload.fromBytes(data))
        }
    }

    private fun reconnectWithBackoff(name: String) = externalScope.launch {
        // If we have enough connections, don't force a restart of discovery.
        val connectedCount =
            DevicesRegistry.devices.value.values.count { it.phase == ConnectionPhase.CONNECTED }
        if (connectedCount >= 2) {
            Timber.tag("P2P_MESH")
                .i("reconnectWithBackoff: $connectedCount devices are connected, aborting reconnection attempt for $name.")
            return@launch
        }

        val retryCount = DevicesRegistry.getRetryCount(name)
        if (retryCount >= 4) {
            Timber.tag("P2P_MESH")
                .w("reconnectWithBackoff: Device $name reached max retries, giving up.")
            DevicesRegistry.devices.value.values.find { it.name == name }
                ?.let { DevicesRegistry.remove(it.endpointId) }
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
