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

    companion object {
        private val PING = "PING".toByteArray()
        private val PONG = "PONG".toByteArray()
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

        // a connection attempt succeeds or fails.
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val status = when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> ConnectionPhase.CONNECTED
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> ConnectionPhase.REJECTED
                else -> ConnectionPhase.ERROR
            }
            Timber.tag("P2P_MESH").d("onConnectionResult: endpointId=$endpointId, status=$status")
            if (status == ConnectionPhase.CONNECTED) {
                connectedSendPing(endpointId)
            } else {
                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = status
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            Timber.tag("P2P_MESH").d("onDisconnected: endpointId=$endpointId")
            DevicesRegistry.updateDeviceStatus(
                endpointId = endpointId,
                externalScope = externalScope,
                newPhase = ConnectionPhase.DISCONNECTED
            )
        }
    }


    // Handles receiving data (payloads) from other devices.

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
                    connectionsClient.requestConnection(
                        "localName",
                        endpointId,
                        connectionLifecycleCallback
                    )
                }

                override fun onEndpointLost(endpointId: String) {
                    Timber.tag("P2P_MESH").d("onEndpointLost: endpointId=$endpointId")
                    DevicesRegistry.remove(endpointId)
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
}
