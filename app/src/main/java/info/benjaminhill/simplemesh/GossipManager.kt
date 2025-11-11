package info.benjaminhill.simplemesh

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

class GossipManager(
    private val externalScope: CoroutineScope,
    private val nearbyConnectionsManager: NearbyConnectionsManager
) {
    private val gson = Gson()

    fun start() {
        externalScope.launch {
            while (true) {
                gossip()
                delay(30.seconds)
            }
        }
    }

    private fun gossip() {
        val networkGraph = DevicesRegistry.networkGraph.value
        if (networkGraph.isNotEmpty()) {
            val json = gson.toJson(networkGraph)
            nearbyConnectionsManager.broadcast(json.toByteArray())
        }
    }

    fun handlePayload(payload: ByteArray) {
        val type = object : TypeToken<Map<String, Set<String>>>() {}.type
        val receivedGraph = gson.fromJson<Map<String, Set<String>>>(String(payload), type)
        if (receivedGraph != null) {
            mergeGraphs(receivedGraph)
        }
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
            Timber.tag("P22P_MESH").d("Gossip: Merged network graph. New size: ${currentGraph.size}")
            DevicesRegistry.updateNetworkGraph(currentGraph)
        }
    }
}
