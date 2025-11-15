package info.benjaminhill.simplemesh.strategy

import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Manages the healing of the mesh network.
 *
 * This service is responsible for "global" healing, which is the process of detecting and
 * repairing network partitions. It does this by periodically restarting the discovery process,
 * which allows the device to find and connect to other partitions that may have formed.
 *
 * "Local" healing, which is the process of reconnecting to lost neighbors, is handled by the
 * `manageConnections` loop in `NearbyConnectionsManager`.
 */
class HealingService(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    private val externalScope: CoroutineScope
) {

    fun start() {
        externalScope.launch {
            while (true) {
                // Global healing: periodically rediscover to find partitions
                Timber.tag("P2P_MESH").d("HealingService: Starting periodic discovery.")
                nearbyConnectionsManager.startDiscovery()
                delay(15.seconds)
                nearbyConnectionsManager.stopAll() // To avoid continuous battery drain
                nearbyConnectionsManager.startAdvertising() // Restart advertising
                delay(5.minutes) // 5 minutes
            }
        }
    }
}