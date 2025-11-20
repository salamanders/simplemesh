package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.Serializable

@Serializable
data class MeshPacket(
    val id: String, // UUID
    val ttl: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (id != other.id) return false
        if (ttl != other.ttl) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ttl
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
