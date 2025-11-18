package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.Serializable

@Serializable
data class TransportFrame(
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TransportFrame

        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        return payload.contentHashCode()
    }
}
