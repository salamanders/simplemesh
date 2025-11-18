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
import info.benjaminhill.simplemesh.strategy.RingConnectionStrategy
import info.benjaminhill.simplemesh.util.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
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

    private val connectionStrategy: RingConnectionStrategy by lazy {
        RingConnectionStrategy(
            activity,
            connectionsClient,
            externalScope,
            connectionLifecycleCallback,
            payloadCallback,
            this::startDiscovery,
            this::stopDiscovery
        )
    }

    companion object {
        val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
        private const val MAX_PAYLOAD_SIZE = 32 * 1024 // 32KB
    }


    // Handles events like new connection requests, results, and disconnections.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        // another device wants to connect to us
        override fun onConnectionInitiated(endpointIdStr: String, connectionInfo: ConnectionInfo) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag("P2P_MESH").d("onConnectionInitiated: endpointId=$endpointId")
            connectionStrategy.onConnectionInitiated(endpointId, connectionInfo)
        }

        // a connection attempt succeeds or fails.
        override fun onConnectionResult(endpointIdStr: String, result: ConnectionResolution) {
            val endpointId = EndpointId(endpointIdStr)
            val device = DevicesRegistry.getLatestDeviceState(endpointId)

            if (device == null) {
                Timber.tag("P2P_MESH").w("onConnectionResult for unknown endpointId: $endpointId")
                return
            }

            val newPhase = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    DevicesRegistry.resetRetryCount(device.name)
                    ConnectionPhase.CONNECTED
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    DevicesRegistry.incrementRetryCount(device.name)
                    ConnectionPhase.REJECTED
                }

                else -> {
                    DevicesRegistry.incrementRetryCount(device.name)
                    ConnectionPhase.ERROR
                }
            }
            Timber.tag("P2P_MESH")
                .d("onConnectionResult: endpointId=$endpointId, status=$newPhase")

            DevicesRegistry.updateDeviceStatus(
                endpointId = endpointId,
                externalScope = externalScope,
                newPhase = newPhase
            )
            connectionStrategy.onConnectionResult(newPhase == ConnectionPhase.CONNECTED)
            if (newPhase == ConnectionPhase.CONNECTED) {
                connectedSendPing(endpointId)
            }
        }

        override fun onDisconnected(endpointIdStr: String) {
            val endpointId = EndpointId(endpointIdStr)
            Timber.tag("P2P_MESH").d("onDisconnected: endpointId=$endpointId")
            val device = DevicesRegistry.getLatestDeviceState(endpointId)
            if (device != null) {
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = ConnectionPhase.DISCONNECTED
                )
                connectionStrategy.onDisconnected()
            }
        }
    }

    val payloadCallback = object : PayloadCallback() {
        // Triggered when we get a PING or a PONG.
        override fun onPayloadReceived(endpointIdStr: String, payload: Payload) {
            val endpointId = EndpointId(endpointIdStr)
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = payload.asBytes()!!
                    when {
                        data.contentEquals(PING) -> {
                            Timber.tag("P2P_MESH")
                                .d("Received PING from $endpointId")
                            // Reset the timeout for this device
                            DevicesRegistry.updateDeviceStatus(
                                endpointId = endpointId,
                                externalScope = externalScope,
                                newPhase = ConnectionPhase.CONNECTED
                            )
                            connectionsClient.sendPayload(
                                endpointId.value,
                                Payload.fromBytes(PONG)
                            )
                        }

                        data.contentEquals(PONG) -> {
                            Timber.tag("P2P_MESH")
                                .d("Received PONG from $endpointId")
                            connectedSendPing(endpointId, 30.seconds)
                        }

                        else -> {
                            // Flood to all other connected devices
                            broadcast(data, exclude = endpointId)
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
    }


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
                    endpointIdStr: String,
                    discoveredEndpointInfo: DiscoveredEndpointInfo
                ) {
                    val endpointId = EndpointId(endpointIdStr)
                    Timber.tag("P2P_MESH").d("onEndpointFound: endpointId=$endpointId")
                    DevicesRegistry.updateDeviceStatus(
                        endpointId = endpointId,
                        externalScope = externalScope,
                        newPhase = ConnectionPhase.DISCOVERED,
                        newName = EndpointName(discoveredEndpointInfo.endpointName),
                    )
                    // Do not immediately connect, just add to the list of potential peers
                    // A separate job will decide who to connect to.
                    DevicesRegistry.addPotentialPeer(endpointId)
                }

                override fun onEndpointLost(endpointIdStr: String) {
                    val endpointId = EndpointId(endpointIdStr)
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

    fun stopAdvertising() {
        Timber.tag("P2P_MESH").d("stopAdvertising")
        connectionsClient.stopAdvertising()
    }

    fun stopDiscovery() {
        Timber.tag("P2P_MESH").d("stopDiscovery")
        connectionsClient.stopDiscovery()
    }

    fun stopAll() {
        Timber.tag("P2P_MESH").d("stopAll")
        connectionStrategy.stop()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    // Resets the connection phase every time, and sends a ping in a few seconds
    private fun connectedSendPing(endpointId: EndpointId, delay: Duration = 15.seconds) {
        val device = DevicesRegistry.getLatestDeviceState(endpointId)
        if (device != null) {
            DevicesRegistry.updateDeviceStatus(
                endpointId = endpointId,
                externalScope = externalScope,
                newPhase = ConnectionPhase.CONNECTED
            )
            externalScope.launch {
                delay(delay)
                connectionsClient.sendPayload(endpointId.value, Payload.fromBytes(PING))
            }
        }
    }

    fun broadcast(data: ByteArray, exclude: EndpointId? = null) {
        if (data.size > MAX_PAYLOAD_SIZE) {
            Timber.tag("P2P_MESH")
                .e("broadcast: Payload size ${data.size} exceeds MAX_PAYLOAD_SIZE ${MAX_PAYLOAD_SIZE}. Dropping payload.")
            return
        }
        val connectedDevices =
            DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED && it.endpointId != exclude }
        connectedDevices.forEach { device ->
            connectionsClient.sendPayload(device.endpointId.value, Payload.fromBytes(data))
        }
    }
}
