package info.benjaminhill.simplemesh

import com.google.gson.Gson
import timber.log.Timber
import java.util.UUID

data class MeshPayload(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceEndpointId: String,
    val destEndpointId: String, // "BROADCAST" for all
    var ttl: Int = 10,
    val data: ByteArray
)

class RoutingEngine(
    private val nearbyConnectionsManager: NearbyConnectionsManager
) {
    private val seenMessageIds = mutableSetOf<String>()
    private val gson = Gson()

    fun handlePayload(payload: ByteArray) {
        val meshPayload = gson.fromJson(String(payload), MeshPayload::class.java)

        if (seenMessageIds.contains(meshPayload.messageId)) {
            return // Already seen this message
        }
        seenMessageIds.add(meshPayload.messageId)

        if (meshPayload.destEndpointId == "BROADCAST") {
            // Process the data
            Timber.tag("P2P_MESH")
                .d("RoutingEngine: Received broadcast with messageId: ${meshPayload.messageId}")
            // ...

            // Forward the payload if TTL is greater than 0
            if (meshPayload.ttl > 0) {
                meshPayload.ttl--
                val meshPacket = Packet.MeshPacket(meshPayload)
                val json = gson.toJson(meshPacket)
                nearbyConnectionsManager.broadcast(json.toByteArray())
            }
        }
    }
}
