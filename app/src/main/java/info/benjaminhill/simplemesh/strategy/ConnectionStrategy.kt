package info.benjaminhill.simplemesh.strategy

import android.app.Activity
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import info.benjaminhill.simplemesh.p2p.ConnectionPhase
import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.EndpointId
import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.util.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConnectionStrategy(
    private val activity: Activity,
    private val connectionsClient: ConnectionsClient,
    private val externalScope: CoroutineScope,
    private val connectionLifecycleCallback: ConnectionLifecycleCallback,
) {
    private var manageConnectionsJob: Job? = null
    private var connectionRotationJob: Job? = null

    companion object {
        private const val MAX_CONNECTIONS = 4
    }

    fun start() {
        if (manageConnectionsJob?.isActive != true) {
            manageConnectionsJob = manageConnections()
        }
        /*if (connectionRotationJob?.isActive != true) {
            connectionRotationJob = connectionRotation()
        }*/
    }

    fun stop() {
        manageConnectionsJob?.cancel()
        connectionRotationJob?.cancel()
    }

    /**
     * When at capacity, try to disconnect from a redundant peer to make room for a new connection.
     * @return true if a peer was disconnected
     */
    fun tryDisconnectRedundantPeer(newEndpointId: EndpointId): Boolean {
        val networkGraph = DevicesRegistry.networkGraph.value
        val myName = EndpointName(DeviceIdentifier.get(activity))
        val myNeighbors = networkGraph[myName]?.toList() ?: emptyList()

        myNeighbors.indices.forEach { i ->
            (i + 1 until myNeighbors.size).forEach { j ->
                val neighbor1 = myNeighbors[i]
                val neighbor2 = myNeighbors[j]

                if (networkGraph[neighbor1]?.contains(neighbor2) == true) {
                    listOf(neighbor1, neighbor2).random().let { peerToDisconnect ->
                        DevicesRegistry.devices.value.values.find { it.name == peerToDisconnect }?.endpointId?.let { endpointToDisconnect ->
                            Timber.tag("P2P_MESH")
                                .i("tryDisconnectRedundantPeer: At connection limit, but found redundant peer. Disconnecting from $peerToDisconnect to make room for $newEndpointId")
                            connectionsClient.disconnectFromEndpoint(endpointToDisconnect.value)
                            return true
                        } ?: Timber.tag("P2P_MESH")
                            .w("tryDisconnectRedundantPeer: Wanted to disconnect from redundant peer $peerToDisconnect, but could not find endpointId.")
                    }
                }
            }
        }
        return false
    }


    private fun connectionRotation() = externalScope.launch {
        while (true) {
            delay(5.minutes + (1..60).random().seconds)

            val networkGraph = DevicesRegistry.networkGraph.value
            val myName = EndpointName(DeviceIdentifier.get(activity))
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
                        connectionsClient.disconnectFromEndpoint(endpointToDisconnect.value)
                    }
                }
            }
        }
    }

    private fun manageConnections() = externalScope.launch {
        while (true) {
            val connectedOrConnectingEndpointIds =
                DevicesRegistry.devices.value.values.filter {
                    it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING
                }.map { it.endpointId }.toSet()

            if (connectedOrConnectingEndpointIds.size < MAX_CONNECTIONS) {
                val availablePeers = DevicesRegistry.potentialPeers.value.filter {
                    !connectedOrConnectingEndpointIds.contains(it)
                }

                val peerToConnect = availablePeers.firstOrNull()

                if (peerToConnect != null) {
                    Timber.tag("P2P_MESH")
                        .d("manageConnections: Attempting to connect to peer: $peerToConnect")
                    connectionsClient.requestConnection(
                        DeviceIdentifier.get(activity),
                        peerToConnect.value,
                        connectionLifecycleCallback
                    )
                }
            }
            delay(5.seconds)
        }
    }
}