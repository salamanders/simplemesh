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
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    // Cache to prevent re-broadcasting seen packets. Maps Packet ID -> Timestamp (or Boolean)
    // We can use a size-limited cache or just clear it periodically.
    // For simplicity, a ConcurrentHashMap with manual size check or a simple LRU would work.
    // Let's use a simple set for now, knowing it might grow (a real app needs cleanup).
    private val seenPackets = ConcurrentHashMap.newKeySet<String>()

    private val connectionsClient: ConnectionsClient by lazy { Nearby.getConnectionsClient(application) }

    // Strategy is responsible for deciding WHO to connect to.
    private val connectionStrategy: RandomConnectionStrategy by lazy {
        RandomConnectionStrategy(
            connectionsClient,
            externalScope,
            connectionLifecycleCallback,
            payloadCallback,
        )
    }

    companion object {
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
            DevicesRegistry.updateDeviceStatus(endpointId, externalScope, ConnectionPhase.CONNECTING)
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

            DevicesRegistry.updateDeviceStatus(endpointId, externalScope, newPhase)

            if (newPhase == ConnectionPhase.CONNECTED) {
                // Start the heartbeat immediately
                connectedSendPing(endpointId)
            }
        }

        override fun onDisconnected(endpointIdStr: String) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).i("Disconnected from $endpointId")
            DevicesRegistry.updateDeviceStatus(
                endpointId,
                externalScope,
                ConnectionPhase.DISCONNECTED
            )
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointIdStr: String, payload: Payload) {
            val endpointId = EndpointId(endpointIdStr)
            if (payload.type != Payload.Type.BYTES) return

            val data = payload.asBytes() ?: return

            // 1. Handle Protocol Messages (PING/PONG)
            if (data.contentEquals(PING)) {
                Timber.tag(TAG).v("RX PING <- $endpointId")
                DevicesRegistry.updateDeviceStatus(
                    endpointId,
                    externalScope,
                    ConnectionPhase.CONNECTED
                )
                sendPayload(endpointId, PONG)
                return
            }
            if (data.contentEquals(PONG)) {
                Timber.tag(TAG).v("RX PONG <- $endpointId")
                connectedSendPing(endpointId, 30.seconds)
                return
            }

            // 2. Handle Mesh Packets
            try {
                val packet = Cbor.decodeFromByteArray<MeshPacket>(data)

                if (seenPackets.contains(packet.id)) {
                    // Already seen, drop to prevent loops
                    return
                }
                seenPackets.add(packet.id)

                // Process payload (Application logic would go here)
                Timber.tag(TAG).d("RX MeshPacket ${packet.id} (TTL=${packet.ttl}) <- $endpointId")

                // Forward if TTL > 0
                if (packet.ttl > 0) {
                    val forwardedPacket = packet.copy(ttl = packet.ttl - 1)
                    val forwardedBytes = Cbor.encodeToByteArray(forwardedPacket)
                    broadcastInternal(forwardedBytes, exclude = endpointId)
                }

            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to decode packet from $endpointId")
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

            DevicesRegistry.updateDeviceStatus(
                endpointId = endpointId,
                externalScope = externalScope,
                newPhase = ConnectionPhase.DISCOVERED,
                newName = EndpointName(info.endpointName)
            )
            DevicesRegistry.addPotentialPeer(endpointId)
        }

        override fun onEndpointLost(endpointIdStr: String) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag(TAG).d("Lost: $endpointId")
            DevicesRegistry.remove(endpointId)
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
        // Wrap app data in a MeshPacket with a fresh ID and TTL
        val packetId = UUID.randomUUID().toString()
        val packet = MeshPacket(
            id = packetId,
            ttl = 5, // Allow 5 hops
            payload = data
        )
        // Mark as seen by self so we don't echo it if it comes back
        seenPackets.add(packetId)

        val packetBytes = Cbor.encodeToByteArray(packet)
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
        // Only send if we are still connected
        val device = DevicesRegistry.getLatestDeviceState(endpointId) ?: return

        // Update state to refresh timeout logic
        DevicesRegistry.updateDeviceStatus(endpointId, externalScope, ConnectionPhase.CONNECTED)

        externalScope.launch {
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
