package info.benjaminhill.simplemesh.strategy

import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.p2p.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
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
            val gossipPacket = Packet.GossipPacket(networkGraph)
            val bytes = Cbor.encodeToByteArray(Packet.Companion.serializer(), gossipPacket)
            nearbyConnectionsManager.broadcast(bytes)
        }
    }

    fun handlePayload(payload: Map<String, Set<String>>) {
        mergeGraphs(payload)
    }

    private fun mergeGraphs(receivedGraph: Map<String, Set<String>>) {
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
}