package info.benjaminhill.simplemesh.strategy

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RoutedMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceEndpointId: String,
    val destEndpointId: String, // "BROADCAST" for all
    var ttl: Int = 10,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RoutedMessage

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
