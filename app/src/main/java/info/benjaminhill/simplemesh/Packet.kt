package info.benjaminhill.simplemesh

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

@Serializable
sealed class Packet {
    @Serializable
    data class GossipPacket(val data: Map<String, Set<String>>) : Packet()

    @Serializable
    data class MeshPacket(val meshPayload: MeshPayload) : Packet()

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        fun fromByteArray(byteArray: ByteArray): Packet =
            Cbor.decodeFromByteArray(serializer(), byteArray)

        // Custom serializer for Instant. Keep in case we use later.
        @SuppressWarnings("unused")
        object InstantSerializer : KSerializer<Instant> {
            override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

            override fun serialize(encoder: Encoder, value: Instant) {
                encoder.encodeLong(value.toEpochMilli())
            }

            override fun deserialize(decoder: Decoder): Instant {
                return Instant.ofEpochMilli(decoder.decodeLong())
            }
        }
    }
}
