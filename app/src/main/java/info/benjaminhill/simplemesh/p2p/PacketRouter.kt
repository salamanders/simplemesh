package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalSerializationApi::class)
class PacketRouter(
    scope: CoroutineScope,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    // Maps Packet ID -> Timestamp (ms)
    private val seenPackets = ConcurrentHashMap<String, Long>()

    init {
        // Cleanup job to remove old packets
        scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                val now = timeProvider()
                val oldSize = seenPackets.size

                // Remove entries older than TTL
                val iterator = seenPackets.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value > CACHE_TTL_MS) {
                        iterator.remove()
                    }
                }

                if (oldSize != seenPackets.size) {
                    Timber.tag(TAG).v("Cleaned up packet cache: $oldSize -> ${seenPackets.size}")
                }
            }
        }
    }

    fun createBroadcast(payload: ByteArray): ByteArray {
        val packetId = UUID.randomUUID().toString()
        val packet = MeshPacket(
            id = packetId,
            ttl = DEFAULT_TTL,
            payload = payload
        )
        markAsSeen(packetId)
        return Cbor.encodeToByteArray(packet)
    }

    fun handleIncoming(data: ByteArray): RouteResult {
        return try {
            val packet = Cbor.decodeFromByteArray<MeshPacket>(data)

            if (hasSeen(packet.id)) {
                return RouteResult.Duplicate
            }
            markAsSeen(packet.id)

            val forwardBytes = if (packet.ttl > 0) {
                val forwardedPacket = packet.copy(ttl = packet.ttl - 1)
                Cbor.encodeToByteArray(forwardedPacket)
            } else {
                null
            }

            RouteResult.Delivered(packet, forwardBytes)
        } catch (e: Exception) {
            RouteResult.Malformed(e)
        }
    }

    private fun markAsSeen(id: String) {
        seenPackets[id] = timeProvider()
    }

    private fun hasSeen(id: String): Boolean {
        return seenPackets.containsKey(id)
    }

    sealed interface RouteResult {
        data class Delivered(val packet: MeshPacket, val forwardBytes: ByteArray?) : RouteResult
        data object Duplicate : RouteResult
        data class Malformed(val error: Throwable) : RouteResult
    }

    companion object {
        private const val TAG = "P2P_ROUTER"
        private const val DEFAULT_TTL = 5
        private val CACHE_TTL_MS = 10.minutes.inWholeMilliseconds
        private val CLEANUP_INTERVAL = 1.minutes
    }
}
