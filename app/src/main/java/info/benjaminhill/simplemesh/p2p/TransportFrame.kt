package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@Serializable
data class TransportFrame(
    val type: TransportFrameType,
    val payload: ByteArray
) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromByteArray(byteArray: ByteArray): TransportFrame =
            Cbor.decodeFromByteArray(serializer(), byteArray)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportFrame

        if (type != other.type) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

enum class TransportFrameType {
    TOPOLOGY_GOSSIP,
    ROUTED_MESSAGE
}
