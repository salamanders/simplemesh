package info.benjaminhill.simplemesh.strategy

import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.p2p.TransportFrame
import info.benjaminhill.simplemesh.p2p.TransportFrameType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class GossipManager(
    private val externalScope: CoroutineScope,
    private val nearbyConnectionsManager: NearbyConnectionsManager
) {
    fun start() {
        externalScope.launch {
            while (true) {
                gossip()
                delay(30.seconds)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun gossip() {
        val networkGraph = DevicesRegistry.networkGraph.value
        if (networkGraph.isNotEmpty()) {
            val topologyGossip = TopologyGossip(networkGraph)
            val payload = Cbor.encodeToByteArray(TopologyGossip.serializer(), topologyGossip)
            val transportFrame = TransportFrame(TransportFrameType.TOPOLOGY_GOSSIP, payload)
            val bytes = Cbor.encodeToByteArray(TransportFrame.serializer(), transportFrame)
            nearbyConnectionsManager.broadcast(bytes)
        }
    }

    fun handlePayload(payload: Map<EndpointName, Set<EndpointName>>) {
        mergeGraphs(payload)
    }

    private fun mergeGraphs(receivedGraph: Map<EndpointName, Set<EndpointName>>) {
        val currentGraph = DevicesRegistry.networkGraph.value.toMutableMap()
        var changed = false
        receivedGraph.forEach { (deviceName, neighbors) ->
            val currentNeighbors = currentGraph[deviceName] ?: emptySet()
            if (!currentNeighbors.containsAll(neighbors)) {
                currentGraph[deviceName] = currentNeighbors + neighbors
                changed = true
            }
        }
        if (changed) {
            Timber.tag("P22P_MESH")
                .d("Gossip: Merged network graph. New size: ${currentGraph.size}")
            DevicesRegistry.updateNetworkGraph(currentGraph)
        }
    }

    companion object {
        @Serializable
        data class TopologyGossip(val data: Map<EndpointName, Set<EndpointName>>)
    }
}
