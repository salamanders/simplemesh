package info.benjaminhill.simplemesh.p2p

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MeshPacketTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `serialization and deserialization is correct`() {
        val id = UUID.randomUUID().toString()
        val payload = ByteArray(1024) { i -> (i % 256).toByte() }
        val original = MeshPacket(id, 10, payload)

        val bytes = Cbor.encodeToByteArray(original)
        val decoded = Cbor.decodeFromByteArray<MeshPacket>(bytes)

        assertEquals(original, decoded)
        assertEquals(id, decoded.id)
        assertEquals(10, decoded.ttl)
        assertTrue(decoded.payload.contentEquals(payload))
    }
}
