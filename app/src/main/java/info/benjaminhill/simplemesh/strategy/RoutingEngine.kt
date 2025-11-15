package info.benjaminhill.simplemesh.strategy

import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.p2p.Packet
import info.benjaminhill.simplemesh.p2p.PacketType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import timber.log.Timber

@Serializable
data class MeshPacket(val meshPayload: MeshPayload)

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
            val meshPacket = MeshPacket(meshPayload)
            val payload = Cbor.encodeToByteArray(MeshPacket.serializer(), meshPacket)
            val packet = Packet(PacketType.MESH, payload)
            val bytes = Cbor.encodeToByteArray(Packet.serializer(), packet)
            nearbyConnectionsManager.broadcast(bytes)
        }
    }
}
