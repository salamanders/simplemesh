package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@Serializable
data class Packet(
    val type: PacketType,
    val payload: ByteArray
) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromByteArray(byteArray: ByteArray): Packet =
            Cbor.decodeFromByteArray(serializer(), byteArray)
    }
}

enum class PacketType {
    GOSSIP,
    MESH
}
