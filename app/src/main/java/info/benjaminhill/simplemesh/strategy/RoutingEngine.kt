package info.benjaminhill.simplemesh.strategy

import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import info.benjaminhill.simplemesh.p2p.TransportFrame
import info.benjaminhill.simplemesh.p2p.TransportFrameType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import timber.log.Timber

class RoutingEngine(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    private val myDeviceName: EndpointName,
) {
    private val seenMessageIds = mutableSetOf<String>()

    @OptIn(ExperimentalSerializationApi::class)
    fun handlePayload(routedMessage: RoutedMessage) {
        if (seenMessageIds.contains(routedMessage.messageId)) {
            return // Already seen this message
        }
        seenMessageIds.add(routedMessage.messageId)

        if (routedMessage.destEndpointId == myDeviceName.value) {
            // This is for me, process it.
            Timber.tag("P2P_MESH")
                .d("RoutingEngine: Received direct message with messageId: ${routedMessage.messageId}")
            // ... (processing logic would go here)
            return // Don't forward
        }

        if (routedMessage.destEndpointId == "BROADCAST") {
            // Process the data
            Timber.tag("P2P_MESH")
                .d("RoutingEngine: Received broadcast with messageId: ${routedMessage.messageId}")
            // ... (processing logic would go here)
        }

        // Forward the payload if TTL is greater than 0
        if (routedMessage.ttl > 0) {
            routedMessage.ttl--
            val payload = Cbor.encodeToByteArray(RoutedMessage.serializer(), routedMessage)
            val transportFrame = TransportFrame(TransportFrameType.ROUTED_MESSAGE, payload)
            val bytes = Cbor.encodeToByteArray(TransportFrame.serializer(), transportFrame)
            nearbyConnectionsManager.broadcast(bytes)
        }
    }
}
