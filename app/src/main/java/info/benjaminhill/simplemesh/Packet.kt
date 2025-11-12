package info.benjaminhill.simplemesh

sealed class Packet {
    data class GossipPacket(val data: ByteArray) : Packet()
    data class MeshPacket(val meshPayload: MeshPayload) : Packet()
}
