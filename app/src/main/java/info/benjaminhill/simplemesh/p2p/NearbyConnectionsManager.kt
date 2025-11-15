@file:OptIn(ExperimentalSerializationApi::class)

package info.benjaminhill.simplemesh.p2p

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
import info.benjaminhill.simplemesh.strategy.ConnectionStrategy
import info.benjaminhill.simplemesh.strategy.GossipManager
import info.benjaminhill.simplemesh.strategy.GossipPacket
import info.benjaminhill.simplemesh.strategy.MeshPacket
import info.benjaminhill.simplemesh.strategy.RoutingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Core class for finding, connecting to, and communicating with other devices.
 *
 * This class implements the Nearby Connections API and handles the following responsibilities:
 * - Advertising and discovering nearby devices.
 * - Initiating, accepting, and rejecting connections.
 * - Sending and receiving data (payloads).
 * - Managing the connection lifecycle and updating the `DevicesRegistry`.
 * - Implementing a PING/PONG heartbeat to detect and handle "zombie" connections.
 * - Reconnecting to lost devices with exponential backoff.
 * - Limiting the number of concurrent connections to avoid connection floods.
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

    private val connectionStrategy: ConnectionStrategy by lazy {
        ConnectionStrategy(activity, connectionsClient, externalScope, connectionLifecycleCallback)
    }

    private var gossipManager: GossipManager? = null
    private var routingEngine: RoutingEngine? = null

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
                if (!connectionStrategy.tryDisconnectRedundantPeer(endpointId)) {
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
                                    Timber.tag("P2P_MESH")
                                        .d("Received PING from $endpointId")
                                    connectionsClient.sendPayload(
                                        endpointId,
                                        Payload.fromBytes(PONG)
                                    )
                                }

                                data.contentEquals(PONG) -> {
                                    Timber.tag("P2P_MESH")
                                        .d("Received PONG from $endpointId")
                                    connectedSendPing(endpointId, 30.seconds)
                                }

                                else -> {
                                    val packet = Packet.fromByteArray(data)
                                    when (packet.type) {
                                        PacketType.GOSSIP -> {
                                            val gossipPacket = Cbor.decodeFromByteArray(GossipPacket.serializer(), packet.payload)
                                            gossipManager?.handlePayload(gossipPacket.data)
                                        }
                                        PacketType.MESH -> {
                                            val meshPacket = Cbor.decodeFromByteArray(MeshPacket.serializer(), packet.payload)
                                            routingEngine?.handlePayload(meshPacket.meshPayload)
                                        }
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
        connectionStrategy.start()
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
        connectionStrategy.stop()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    // Resets the connection phase every time, and sends a ping in a few seconds
    private fun connectedSendPing(endpointId: String, delay: Duration = 15.seconds) {
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

    fun broadcast(data: ByteArray) {
        if (data.size > MAX_PAYLOAD_SIZE) {
            Timber.tag("P2P_MESH")
                .e("broadcast: Payload size ${data.size} exceeds MAX_PAYLOAD_SIZE ${MAX_PAYLOAD_SIZE}. Dropping payload.")
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
