package info.benjaminhill.simplemesh

import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.p2p.Packet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import timber.log.Timber
import java.util.UUID

@Serializable
data class MeshPayload(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceEndpointId: String,
    val destEndpointId: String, // "BROADCAST" for all
    var ttl: Int = 10,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPayload

        if (messageId != other.messageId) return false
        if (sourceEndpointId != other.sourceEndpointId) return false
        if (destEndpointId != other.destEndpointId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ttl
        result = 31 * result + messageId.hashCode()
        result = 31 * result + sourceEndpointId.hashCode()
        result = 31 * result + destEndpointId.hashCode()
        return result
    }
}

class RoutingEngine(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    private val myDeviceName: String,
) {
    private val seenMessageIds = mutableSetOf<String>()

    @OptIn(ExperimentalSerializationApi::class)
    fun handlePayload(meshPayload: MeshPayload) {
        if (seenMessageIds.contains(meshPayload.messageId)) {
            return // Already seen this message
        }
        seenMessageIds.add(meshPayload.messageId)

        if (meshPayload.destEndpointId == myDeviceName) {
            // This is for me, process it.
            Timber.tag("P2P_MESH")
                .d("RoutingEngine: Received direct message with messageId: ${meshPayload.messageId}")
            // ... (processing logic would go here)
            return // Don't forward
        }

        if (meshPayload.destEndpointId == "BROADCAST") {
            // Process the data
            Timber.tag("P2P_MESH")
                .d("RoutingEngine: Received broadcast with messageId: ${meshPayload.messageId}")
            // ... (processing logic would go here)
        }

        // Forward the payload if TTL is greater than 0
        if (meshPayload.ttl > 0) {
            meshPayload.ttl--
            val meshPacket = Packet.MeshPacket(meshPayload)
            val bytes = Cbor.encodeToByteArray(Packet.serializer(), meshPacket)
            nearbyConnectionsManager.broadcast(bytes)
        }
    }
}
