package info.benjaminhill.simplemesh.strategy

import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.PayloadCallback
import info.benjaminhill.simplemesh.p2p.ConnectionPhase
import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.EndpointId
import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.p2p.NearbyConnectionsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Cockroach" Strategy: Resilience through randomness and simplicity.
 *
 * This strategy ignores topology and focuses on:
 * 1.  **Filling Slots:** Opportunistically connecting to any available peer up to `NearbyConnectionsManager.MAX_CONNECTIONS`.
 * 2.  **Constant Mixing:** Randomly dropping connections (`Island Breaker`) to ensure the network topology
 *     continually churns, preventing permanent partitions (islands) without complex routing tables.
 * 3.  **Simple Backoff:** Preventing hammering of failed nodes with a basic exponential delay.
 */
class RandomConnectionStrategy(
    private val connectionsClient: ConnectionsClient,
    private val externalScope: CoroutineScope,
    private val connectionLifecycleCallback: ConnectionLifecycleCallback,
    private val payloadCallback: PayloadCallback,
) {
    private var strategyJob: Job? = null

    // Tracks peers we are currently waiting to connect to (in backoff delay).
    // This prevents launching multiple concurrent connection attempts for the same peer.
    private val pendingConnections = ConcurrentHashMap.newKeySet<EndpointId>()

    companion object {
        // Main loop frequency. Fast enough to react, slow enough to save some battery.
        private val LOOP_INTERVAL = 5.seconds

        // Probability (0.0 - 1.0) to randomly drop a connection when full.
        // 0.1 corresponds to roughly one "churn" event per minute per device.
        private const val CHURN_PROBABILITY = 0.1
    }

    fun start() {
        if (strategyJob?.isActive == true) return

        strategyJob = externalScope.launch {
            Timber.tag("P2P_STRATEGY").i("Starting Random 'Cockroach' Strategy.")
            // Discovery is always-on to ensure we can always see new nodes or islands.
            launch { manageConnectionsLoop() }
        }
    }

    fun stop() {
        Timber.tag("P2P_STRATEGY").i("Stopping Strategy.")
        strategyJob?.cancel()
        pendingConnections.clear()
    }

    private suspend fun manageConnectionsLoop() {
        while (true) {
            // Jitter prevents all devices from making decisions in lock-step, reducing collision probability.
            delay(LOOP_INTERVAL + Random.nextLong(0, 5_000).milliseconds)

            val allDevices = DevicesRegistry.devices.value
            val busyPeers = allDevices.values.filter {
                it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING
            }

            if (busyPeers.size < NearbyConnectionsManager.MAX_CONNECTIONS) {
                // FIX: Only exclude devices that are already connected or connecting.
                // Previously, we excluded allDevices.keys, which included DISCOVERED devices.
                // This resulted in discovered devices never being connected to.
                attemptToConnect(busyPeers.map { it.endpointId }.toSet())
            } else {
                considerIslandBreaking(busyPeers.filter { it.phase == ConnectionPhase.CONNECTED })
            }
        }
    }

    private var lastNoCandidatesLogTime = 0L

    private fun attemptToConnect(excludeIds: Set<EndpointId>) {
        val potentialPeers = DevicesRegistry.potentialPeers.value
            .filter { it !in excludeIds && !pendingConnections.contains(it) }
            .mapNotNull { DevicesRegistry.getLatestDeviceState(it) } // Get full state to check name/retries
            .filter { it.phase == ConnectionPhase.DISCOVERED }

        // Simple Heuristic: Prioritize fresh peers (0 retries) to expand the mesh quickly.
        // Fallback to random shuffle to ensure we eventually retry everyone.
        val candidate = potentialPeers
            .filter { DevicesRegistry.getRetryCount(it.name) == 0 }
            .shuffled()
            .firstOrNull()
            ?: potentialPeers.shuffled().firstOrNull()

        if (candidate != null) {
            connectToPeer(candidate.name, candidate.endpointId)
        } else {
            // Log starvation occasionally (every 30s) to prove the strategy is alive but found nothing.
            val now = System.currentTimeMillis()
            if (now - lastNoCandidatesLogTime > 30_000) {
                Timber.tag("P2P_STRATEGY")
                    .d("No suitable candidates found. (Pool: ${potentialPeers.size}, Excluded: ${excludeIds.size})")
                lastNoCandidatesLogTime = now
            }
        }
    }

    private fun considerIslandBreaking(connectedPeers: List<info.benjaminhill.simplemesh.p2p.DeviceState>) {
        // "Island Breaker": Randomly disconnect a peer to force topology changes.
        // This is the "Cockroach" secret sauce: it guarantees that no static partition can exist forever.
        if (Random.nextDouble() < CHURN_PROBABILITY) {
            connectedPeers.randomOrNull()?.let { victim ->
                Timber.tag("P2P_STRATEGY")
                    .i("Island Breaker: Disconnecting ${victim.name.value} to shake up topology.")
                connectionsClient.disconnectFromEndpoint(victim.endpointId.value)
            }
        }
    }

    private fun connectToPeer(peerName: EndpointName, endpointId: EndpointId) {
        // Mark as pending immediately to prevent race conditions
        pendingConnections.add(endpointId)

        externalScope.launch {
            try {
                val retryCount = DevicesRegistry.getRetryCount(peerName)
                if (retryCount > 0) {
                    // Exponential backoff capped at ~32 seconds (2^5 * 1000)
                    val backoffMillis = (2.0.pow(retryCount.coerceAtMost(5)) * 1000).toLong()
                    delay(backoffMillis)
                }

                // Verify state hasn't changed during delay
                val currentState = DevicesRegistry.getLatestDeviceState(endpointId)?.phase
                if (currentState == ConnectionPhase.DISCOVERED) {
                    Timber.tag("P2P_STRATEGY")
                        .i("Requesting connection to ${peerName.value} ($endpointId)")

                    externalScope.launch {
                        DevicesRegistry.updateDeviceStatus(
                            endpointId,
                            externalScope,
                            ConnectionPhase.CONNECTING
                        )
                    }

                    // We use a fixed name "SimpleMesh" for the handshake because the real identity
                    // is established via the persistent DeviceIdentifier, not this ephemeral string.
                    connectionsClient.requestConnection(
                        "SimpleMesh",
                        endpointId.value,
                        connectionLifecycleCallback
                    )
                        .addOnFailureListener { e ->
                            if ((e as? com.google.android.gms.common.api.ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                                Timber.tag("P2P_STRATEGY")
                                    .w("Already connected to $endpointId. Recovering state.")
                                externalScope.launch {
                                    DevicesRegistry.updateDeviceStatus(
                                        endpointId,
                                        externalScope,
                                        ConnectionPhase.CONNECTED
                                    )
                                }
                                return@addOnFailureListener
                            }

                            Timber.tag("P2P_STRATEGY")
                                .w(e, "RequestConnection failed for $endpointId")
                            // Treat as a connection failure to trigger backoff
                            DevicesRegistry.incrementRetryCount(peerName)
                            // Fail fast: Set state to ERROR so the timeout can clean it up, rather than waiting in CONNECTING.
                            externalScope.launch {
                                DevicesRegistry.updateDeviceStatus(
                                    endpointId,
                                    externalScope,
                                    ConnectionPhase.ERROR
                                )
                            }
                        }
                }
            } finally {
                // Once the attempt has been launched (or failed), we remove from pending.
                // The device is now either CONNECTING (in Registry) or failed.
                // If it's CONNECTING, the registry state prevents duplicate attempts.
                pendingConnections.remove(endpointId)
            }
        }
    }

    // Callbacks from NearbyConnectionsManager

    fun onConnectionInitiated(endpointId: EndpointId, connectionInfo: ConnectionInfo) {
        val activeCount = DevicesRegistry.devices.value.values.count {
            it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING
        }

        if (activeCount < NearbyConnectionsManager.MAX_CONNECTIONS) {
            Timber.tag("P2P_STRATEGY")
                .i("Accepting incoming connection from ${connectionInfo.endpointName}")
            connectionsClient.acceptConnection(endpointId.value, payloadCallback)
                .addOnFailureListener { e ->
                    Timber.tag("P2P_STRATEGY").e(e, "AcceptConnection failed for $endpointId")
                }
        } else {
            Timber.tag("P2P_STRATEGY")
                .w("Rejecting ${connectionInfo.endpointName}: Capacity Reached ($activeCount/$NearbyConnectionsManager.MAX_CONNECTIONS)")
            connectionsClient.rejectConnection(endpointId.value)
        }
    }
}
