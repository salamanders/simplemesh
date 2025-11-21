package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class PacketRouterTest {

    private val testScope = TestScope()

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `createBroadcast wraps data and marks as seen`() = testScope.runTest {
        val router = PacketRouter(backgroundScope)
        val payload = "Hello".toByteArray()

        val broadcastBytes = router.createBroadcast(payload)
        val packet = Cbor.decodeFromByteArray<MeshPacket>(broadcastBytes)

        assertEquals(5, packet.ttl)
        assertTrue(packet.payload.contentEquals(payload))

        // Should drop if we receive our own packet back
        val result = router.handleIncoming(broadcastBytes)
        assertTrue(result is PacketRouter.RouteResult.Duplicate)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `handleIncoming processes new packet and decrements TTL`() = testScope.runTest {
        val router = PacketRouter(backgroundScope)
        val payload = "Test".toByteArray()
        val packet = MeshPacket(UUID.randomUUID().toString(), ttl = 5, payload = payload)
        val packetBytes = Cbor.encodeToByteArray(packet)

        val result = router.handleIncoming(packetBytes)

        assertTrue(result is PacketRouter.RouteResult.Delivered)
        val delivered = result as PacketRouter.RouteResult.Delivered
        assertEquals(packet.id, delivered.packet.id)
        assertNotNull(delivered.forwardBytes)

        // Check forwarded packet has TTL - 1
        val forwardedPacket = Cbor.decodeFromByteArray<MeshPacket>(delivered.forwardBytes!!)
        assertEquals(4, forwardedPacket.ttl)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `handleIncoming drops duplicates`() = testScope.runTest {
        val router = PacketRouter(backgroundScope)
        val payload = "Repeat".toByteArray()
        val packet = MeshPacket(UUID.randomUUID().toString(), ttl = 5, payload = payload)
        val packetBytes = Cbor.encodeToByteArray(packet)

        // First time
        assertTrue(router.handleIncoming(packetBytes) is PacketRouter.RouteResult.Delivered)

        // Second time
        assertTrue(router.handleIncoming(packetBytes) is PacketRouter.RouteResult.Duplicate)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `handleIncoming does not forward if TTL is 0`() = testScope.runTest {
        val router = PacketRouter(backgroundScope)
        val payload = "Dead".toByteArray()
        val packet = MeshPacket(UUID.randomUUID().toString(), ttl = 0, payload = payload)
        val packetBytes = Cbor.encodeToByteArray(packet)

        val result = router.handleIncoming(packetBytes)

        assertTrue(result is PacketRouter.RouteResult.Delivered)
        val delivered = result as PacketRouter.RouteResult.Delivered
        assertNull(delivered.forwardBytes)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `cleanup removes old entries`() = testScope.runTest {
        // Use a fake time that we can advance manually
        var currentTime = 0L

        val router = PacketRouter(backgroundScope, timeProvider = { currentTime })
        val payload = "Old".toByteArray()
        val packetId = UUID.randomUUID().toString()
        val packet = MeshPacket(packetId, ttl = 5, payload = payload)
        val packetBytes = Cbor.encodeToByteArray(packet)

        // Mark as seen at time 0
        router.handleIncoming(packetBytes)

        // Verify it is seen (duplicate drop)
        assertTrue(router.handleIncoming(packetBytes) is PacketRouter.RouteResult.Duplicate)

        // Advance "wall clock" time
        currentTime += 12.minutes.inWholeMilliseconds

        // Advance coroutine time so the delay() finishes
        advanceTimeBy(12.minutes)

        // Should be forgotten now
        assertTrue(router.handleIncoming(packetBytes) is PacketRouter.RouteResult.Delivered)
    }
}
