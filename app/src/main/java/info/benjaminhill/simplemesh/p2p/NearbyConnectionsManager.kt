@file:OptIn(ExperimentalSerializationApi::class)

package info.benjaminhill.simplemesh.p2p

import android.app.Application
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
import info.benjaminhill.simplemesh.strategy.RandomConnectionStrategy
import info.benjaminhill.simplemesh.util.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the lifecycle of Google Nearby Connections.
 *
 * Responsibilities:
 * 1.  **Hardware Interface:** Wraps the [ConnectionsClient] to handle permissions, startup, and shutdown.
 * 2.  **Event Dispatch:** Receives callbacks (Lifecycle, Payload, Discovery) and routes them to:
 *     - [DevicesRegistry] for state updates.
 *     - [RandomConnectionStrategy] for connection logic.
 * 3.  **Data Transport:** Handles the PING/PONG heartbeat and broadcasting of application data.
 */
class NearbyConnectionsManager(
    private val application: Application,
    private val externalScope: CoroutineScope
) {
    private val packetRouter = PacketRouter(externalScope)

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(
            application
        )
    }

    // Strategy is responsible for deciding WHO to connect to.
    private val connectionStrategy: RandomConnectionStrategy by lazy {
        RandomConnectionStrategy(
            connectionsClient,
            externalScope,
            connectionLifecycleCallback,
            payloadCallback,
        )
    }

    init {
        externalScope.launch {
            var previousDevices = emptySet<EndpointId>()
            DevicesRegistry.devices.collect { currentDeviceMap ->
                val currentDevices = currentDeviceMap.keys
                val removedDevices = previousDevices - currentDevices

                removedDevices.forEach { removedId ->

                    Timber.tag(TAG)
                        .d("Device removed from registry. Ensuring disconnect: $removedId")

                    connectionsClient.disconnectFromEndpoint(removedId.value)

                }



                if (removedDevices.isNotEmpty() && isDiscovering) {

                    Timber.tag(TAG)

                        .d("Device(s) removed: $removedDevices. Restarting discovery to flush cache.")

                    stopDiscovery()

                    startDiscovery()

                }


                // Dynamic Discovery: Save radio resources by stopping scan when full.

                val activeCount = currentDeviceMap.values.count {

                    it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING

                }



                if (activeCount >= MAX_CONNECTIONS) {

                    if (isDiscovering) {

                        Timber.tag(TAG)
                            .d("Max connections reached ($activeCount/$MAX_CONNECTIONS). Pausing discovery.")

                        stopDiscovery()

                    }

                } else {

                    if (!isDiscovering) {

                        Timber.tag(TAG)
                            .d("Slots available ($activeCount/$MAX_CONNECTIONS). Resuming discovery.")

                        startDiscovery()

                    }

                }



                previousDevices = currentDevices

            }

        }

    }

    companion object {
        const val MAX_CONNECTIONS = 3
        val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
        private const val MAX_PAYLOAD_SIZE = 32 * 1024 // 32KB
        private const val TAG = "P2P_MANAGER"
    }

    // --- Callbacks ---

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointIdStr: String, connectionInfo: ConnectionInfo) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).d("onConnectionInitiated: $endpointId (${connectionInfo.endpointName})")
            externalScope.launch {
                DevicesRegistry.updateDeviceStatus(
                    endpointId,
                    externalScope,
                    ConnectionPhase.CONNECTING
                )
            }
            connectionStrategy.onConnectionInitiated(endpointId, connectionInfo)
        }

        override fun onConnectionResult(endpointIdStr: String, result: ConnectionResolution) {
            val endpointId = EndpointId(endpointIdStr)
            val device = DevicesRegistry.getLatestDeviceState(endpointId)

            if (device == null) {
                Timber.tag(TAG).w("onConnectionResult for unknown device: $endpointId")
                return
            }

            val newPhase = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Timber.tag(TAG).i("Connected to ${device.name} ($endpointId)")
                    DevicesRegistry.resetRetryCount(device.name)
                    ConnectionPhase.CONNECTED
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Timber.tag(TAG).w("Connection rejected by ${device.name} ($endpointId)")
                    DevicesRegistry.incrementRetryCount(device.name)
                    ConnectionPhase.REJECTED
                }

                else -> {
                    Timber.tag(TAG)
                        .e("Connection error to ${device.name} ($endpointId): ${result.status.statusMessage}")
                    DevicesRegistry.incrementRetryCount(device.name)
                    ConnectionPhase.ERROR
                }
            }

            externalScope.launch {
                DevicesRegistry.updateDeviceStatus(endpointId, externalScope, newPhase)
            }

            if (newPhase == ConnectionPhase.CONNECTED) {
                // Start the heartbeat immediately
                connectedSendPing(endpointId)
            }
        }

        override fun onDisconnected(endpointIdStr: String) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).i("Disconnected from $endpointId")
            externalScope.launch {
                DevicesRegistry.updateDeviceStatus(
                    endpointId,
                    externalScope,
                    ConnectionPhase.DISCONNECTED
                )
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointIdStr: String, payload: Payload) {
            val endpointId = EndpointId(endpointIdStr)
            // Any data received means the connection is alive. Refresh timeout.
            externalScope.launch {
                DevicesRegistry.updateDeviceStatus(
                    endpointId,
                    externalScope,
                    ConnectionPhase.CONNECTED
                )
            }

            if (payload.type != Payload.Type.BYTES) return

            val data = payload.asBytes() ?: return

            // 1. Handle Protocol Messages (PING/PONG)
            if (data.contentEquals(PING)) {
                Timber.tag(TAG).v("RX PING <- $endpointId")
                sendPayload(endpointId, PONG)
                return
            }
            if (data.contentEquals(PONG)) {
                Timber.tag(TAG).v("RX PONG <- $endpointId")
                connectedSendPing(endpointId, 30.seconds)
                return
            }

            // 2. Handle Mesh Packets
            when (val result = packetRouter.handleIncoming(data)) {
                is PacketRouter.RouteResult.Delivered -> {
                    Timber.tag(TAG)
                        .d("RX MeshPacket ${result.packet.id} (TTL=${result.packet.ttl}) <- $endpointId")
                    // Application logic using result.packet.payload would go here

                    if (result.forwardBytes != null) {
                        broadcastInternal(result.forwardBytes, exclude = endpointId)
                    }
                }

                PacketRouter.RouteResult.Duplicate -> {
                    Timber.tag(TAG).v("Dropped duplicate packet from $endpointId")
                }

                is PacketRouter.RouteResult.Malformed -> {
                    Timber.tag(TAG).w(result.error, "Dropped malformed packet from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional: Add progress tracking here if sending large files
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointIdStr: String, info: DiscoveredEndpointInfo) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).d("Found: ${info.endpointName} ($endpointId)")

            externalScope.launch {
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = ConnectionPhase.DISCOVERED,
                    newName = EndpointName(info.endpointName)
                )
                DevicesRegistry.addPotentialPeer(endpointId)
            }
        }

        override fun onEndpointLost(endpointIdStr: String) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).d("Lost: $endpointId")
            externalScope.launch {
                DevicesRegistry.remove(endpointId)
            }
        }
    }

    // --- Public API ---
    private var isDiscovering = false
    private var isAdvertising = false

    fun startAdvertising() {
        if (isAdvertising) {
            Timber.tag(TAG).d("Advertising already in progress.")
            return
        }
        Timber.tag(TAG).d("Starting Advertising...")
        isAdvertising = true
        connectionsClient.startAdvertising(
            DeviceIdentifier.get(application),
            application.packageName,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnFailureListener { e ->
            isAdvertising = false
            Timber.tag(TAG).e(e, "startAdvertising failed")
        }
    }

    fun startDiscovery() {
        // Ensure strategy is running
        connectionStrategy.start()
        if (isDiscovering) {
            Timber.tag(TAG).d("Discovery already in progress.")
            return
        }
        Timber.tag(TAG).d("Starting Discovery...")
        isDiscovering = true
        connectionsClient.startDiscovery(
            application.packageName,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnFailureListener { e ->
            isDiscovering = false
            Timber.tag(TAG).e(e, "startDiscovery failed")
        }
    }

    @Suppress("unused")
    fun stopAdvertising() {
        Timber.tag(TAG).d("Stopping Advertising")
        isAdvertising = false
        connectionsClient.stopAdvertising()
    }

    fun stopDiscovery() {
        Timber.tag(TAG).d("Stopping Discovery")
        isDiscovering = false
        connectionsClient.stopDiscovery()
    }

    fun stopAll() {
        Timber.tag(TAG).i("Stopping All P2P Operations")
        connectionStrategy.stop()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        stopDiscovery()
    }

    fun broadcast(data: ByteArray, exclude: EndpointId? = null) {
        val packetBytes = packetRouter.createBroadcast(data)
        broadcastInternal(packetBytes, exclude)
    }

    private fun broadcastInternal(data: ByteArray, exclude: EndpointId? = null) {
        if (data.size > MAX_PAYLOAD_SIZE) {
            Timber.tag(TAG).e("Payload too large (${data.size} > $MAX_PAYLOAD_SIZE). Dropping.")
            return
        }

        val targets = DevicesRegistry.devices.value.values
            .filter { it.phase == ConnectionPhase.CONNECTED && it.endpointId != exclude }
            .map { it.endpointId.value }

        if (targets.isNotEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(data))
                .addOnFailureListener { e -> Timber.tag(TAG).w(e, "Broadcast failed") }
        }
    }

    // --- Helpers ---

    private fun connectedSendPing(endpointId: EndpointId, delay: Duration = 15.seconds) {
        externalScope.launch {
            // Only send if we are still connected
            DevicesRegistry.getLatestDeviceState(endpointId) ?: return@launch

            // Update state to refresh timeout logic
            DevicesRegistry.updateDeviceStatus(endpointId, externalScope, ConnectionPhase.CONNECTED)

            delay(delay)
            // Double-check before sending
            if (DevicesRegistry.getLatestDeviceState(endpointId)?.phase == ConnectionPhase.CONNECTED) {
                sendPayload(endpointId, PING)
            }
        }
    }

    private fun sendPayload(endpointId: EndpointId, bytes: ByteArray) {
        connectionsClient.sendPayload(endpointId.value, Payload.fromBytes(bytes))
            .addOnFailureListener { e ->
                Timber.tag(TAG).w(e, "Failed to send payload to $endpointId")
            }
    }
}
