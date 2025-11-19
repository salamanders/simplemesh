package info.benjaminhill.simplemesh.strategy

import android.app.Activity
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.PayloadCallback
import info.benjaminhill.simplemesh.p2p.ConnectionPhase
import info.benjaminhill.simplemesh.p2p.DevicesRegistry
import info.benjaminhill.simplemesh.p2p.EndpointId
import info.benjaminhill.simplemesh.p2p.EndpointName
import info.benjaminhill.simplemesh.util.DeviceIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class RingConnectionStrategy(
    private val activity: Activity,
    private val connectionsClient: ConnectionsClient,
    private val externalScope: CoroutineScope,
    private val connectionLifecycleCallback: ConnectionLifecycleCallback,
    private val payloadCallback: PayloadCallback,
    private val startDiscovery: () -> Unit,
    private val stopDiscovery: () -> Unit,
) {
    private var strategyJob: Job? = null
    private val myName: EndpointName by lazy { EndpointName(DeviceIdentifier.get(activity)) }
    private val stabilityFlow = MutableStateFlow(false)

    companion object {
        private const val MAX_CONNECTIONS = 4
        private const val STABILITY_DURATION_MINUTES = 1
    }

    fun start() {
        if (strategyJob?.isActive != true) {
            strategyJob = externalScope.launch {
                launch { manageRing() }
                launch { manageDiscovery() }
            }
        }
    }

    fun stop() {
        strategyJob?.cancel()
    }

    @OptIn(FlowPreview::class)
    private suspend fun manageDiscovery() {
        stabilityFlow
            .debounce(STABILITY_DURATION_MINUTES.minutes)
            .collectLatest { isStable ->
                if (isStable) {
                    Timber.tag("P2P_MESH")
                        .i("Network is stable, but keeping discovery active to prevent partitioning.")
                    // TODO: We may someday want to convert to a Duty Cycle to save battery.
                    startDiscovery()
                } else {
                    Timber.tag("P2P_MESH").i("Network is unstable, starting discovery.")
                    startDiscovery()
                }
            }
    }

    private fun manageRing() = externalScope.launch {
        // Continually observe the list of all known devices (potential and connected)
        DevicesRegistry.devices.collectLatest { allDevices ->
            val connectedPeers = allDevices.values.filter { it.phase == ConnectionPhase.CONNECTED }
            val potentialPeers = DevicesRegistry.potentialPeers.value.mapNotNull { allDevices[it] }
            val allPeerNames: List<EndpointName> =
                (potentialPeers.map { it.name } + connectedPeers.map { it.name } + myName).distinct()

            if (allPeerNames.size < 2) {
                Timber.tag("P2P_MESH").d("allPeerNames.size < 2, waiting for more peers.")
                stabilityFlow.value = false
                return@collectLatest
            }

            val ring: List<EndpointName> = allPeerNames.sortedBy { it.value }
            val myIndex = ring.indexOf(myName)

            // Desired connections
            val successor = ring[(myIndex + 1) % ring.size]
            val predecessor = ring[(myIndex - 1 + ring.size) % ring.size]
            val opposite = getOpposite(myIndex, ring)

            val desiredPeers = listOfNotNull(successor, opposite).toSet()

            // Connect to desired peers if not already connected
            desiredPeers.forEach { peerName ->
                connectToPeer(peerName)
            }

            // Prune unwanted connections
            pruneConnections(setOf(successor, predecessor, opposite))

            // Update stability status
            val hasSuccessor = connectedPeers.any { it.name == successor }
            val hasPredecessor = connectedPeers.any { it.name == predecessor }
            val hasOpposite = opposite == null || connectedPeers.any { it.name == opposite }
            stabilityFlow.value =
                hasSuccessor && hasPredecessor && hasOpposite && connectedPeers.size >= 2
        }
    }

    private fun getOpposite(myIndex: Int, ring: List<EndpointName>): EndpointName? {
        if (ring.size <= 3) return null // No opposite in a small ring

        var oppositeIndex = (myIndex + ring.size / 2) % ring.size

        // Avoid connecting to neighbors or neighbors-of-neighbors
        while (isPeerTooClose(myIndex, oppositeIndex, ring.size)) {
            oppositeIndex = (oppositeIndex + 1) % ring.size
            if (oppositeIndex == myIndex) {
                Timber.tag("P2P_MESH")
                    .d("No valid opposite peer found in a ring of size ${ring.size}")
                return null
            }
        }
        return ring[oppositeIndex]
    }

    private fun connectToPeer(peerName: EndpointName) {
        val isAlreadyConnected =
            DevicesRegistry.devices.value.values.any { it.name == peerName && it.phase == ConnectionPhase.CONNECTED }
        val isAlreadyConnecting =
            DevicesRegistry.devices.value.values.any { it.name == peerName && it.phase == ConnectionPhase.CONNECTING }

        if (isAlreadyConnected || isAlreadyConnecting) {
            return
        }

        val targetDevice = DevicesRegistry.devices.value.values.find { it.name == peerName }
        if (targetDevice != null) {
            externalScope.launch {
                val retryCount = DevicesRegistry.getRetryCount(peerName)
                if (retryCount > 0) {
                    // Exponential backoff: 2s, 4s, 8s, ... capped at ~1 minute
                    val backoffMillis = (2.0.pow(retryCount.coerceAtMost(6) - 1) * 2000).toLong()
                    val jitterMillis = Random.nextLong(0, 2000)
                    val totalDelay = backoffMillis + jitterMillis
                    Timber.tag("P2P_MESH")
                        .i("Backing off connection to ${peerName.value} for ${totalDelay}ms (retry #${retryCount})")
                    delay(totalDelay)
                }

                // After delay, re-check if we still need to connect
                val stillNeedsConnection =
                    DevicesRegistry.devices.value.values.none {
                        it.name == peerName && it.phase in listOf(
                            ConnectionPhase.CONNECTED,
                            ConnectionPhase.CONNECTING
                        )
                    }

                if (stillNeedsConnection) {
                    val currentTargetDevice =
                        DevicesRegistry.devices.value.values.find { it.name == peerName }
                    if (currentTargetDevice != null) {
                        Timber.tag("P2P_MESH")
                            .i("Attempting to connect to peer: ${peerName.value}")
                        connectionsClient.requestConnection(
                            myName.value,
                            currentTargetDevice.endpointId.value,
                            connectionLifecycleCallback
                        )
                    }
                } else {
                    Timber.tag("P2P_MESH")
                        .d("Connection to ${peerName.value} no longer needed after backoff.")
                }
            }
        }
    }

    fun onConnectionInitiated(endpointId: EndpointId, connectionInfo: ConnectionInfo) {
        val incomingName = EndpointName(connectionInfo.endpointName)
        val allPeerNames =
            (DevicesRegistry.devices.value.values.map { it.name } + myName).distinct()
        val ring: List<EndpointName> = allPeerNames.sortedBy { it.value }
        val myIndex = ring.indexOf(myName)

        val successor = ring[(myIndex + 1) % ring.size]
        val predecessor = ring[(myIndex - 1 + ring.size) % ring.size]

        // Accept if it's an immediate neighbor
        if (incomingName == successor || incomingName == predecessor) {
            Timber.tag("P2P_MESH")
                .i("Accepting connection from immediate neighbor: ${incomingName.value}")
            connectionsClient.acceptConnection(endpointId.value, payloadCallback)
        } else {
            // Or if we have capacity for a diagonal connection
            if (DevicesRegistry.devices.value.values.count { it.phase == ConnectionPhase.CONNECTED } < MAX_CONNECTIONS) {
                Timber.tag("P2P_MESH")
                    .i("Accepting connection from non-neighbor: ${incomingName.value}")
                connectionsClient.acceptConnection(endpointId.value, payloadCallback)
            } else {
                Timber.tag("P2P_MESH")
                    .w("Rejecting connection from ${incomingName.value}, no capacity.")
                connectionsClient.rejectConnection(endpointId.value)
            }
        }
    }

    fun onConnectionResult(isSuccess: Boolean) {
        if (isSuccess) {
            stabilityFlow.value = false // Re-evaluate stability
        }
    }

    fun onDisconnected() {
        stabilityFlow.value = false // Re-evaluate stability
    }

    private fun pruneConnections(desiredPeers: Set<EndpointName?>) {
        val connectedDevices =
            DevicesRegistry.devices.value.values.filter { it.phase == ConnectionPhase.CONNECTED }

        // Identify connections we explicitly want to keep (Successor, Predecessor, Opposite)
        val importantConnections = connectedDevices.filter { it.name in desiredPeers }

        // Identify "Spare Tires" (Random connections or old neighbors)
        val spareConnections = connectedDevices.filter { it.name !in desiredPeers }

        // Check which desired peers are NOT currently connected or connecting.
        // We need to ensure we have 'slots' available for these specific missing peers.
        val missingDesiredCount = desiredPeers.filterNotNull().count { name ->
            DevicesRegistry.devices.value.values.none {
                it.name == name && (it.phase == ConnectionPhase.CONNECTED || it.phase == ConnectionPhase.CONNECTING)
            }
        }

        // We want to keep as many spares as possible, but we MUST reserve space for the missing desired peers
        // if we are hitting the MAX_CONNECTIONS cap.
        // Available slots for spares = (Max Capacity) - (Slots taken by Important) - (Slots reserved for Missing Important)
        val slotsUsedByImportant = importantConnections.size
        val maxSparesAllowed = MAX_CONNECTIONS - slotsUsedByImportant - missingDesiredCount

        if (spareConnections.size > maxSparesAllowed) {
            val numToPrune = spareConnections.size - maxSparesAllowed.coerceAtLeast(0)
            // Prune the extras to make room
            spareConnections.take(numToPrune).forEach { deviceToDisconnect ->
                Timber.tag("P2P_MESH")
                    .i("Pruning spare connection to ${deviceToDisconnect.name.value} to make room for desired peer (Spare limit exceeded).")
                connectionsClient.disconnectFromEndpoint(deviceToDisconnect.endpointId.value)
            }
        } else {
            Timber.tag("P2P_MESH")
                .d("Keeping ${spareConnections.size} spare connections. (Allowed: $maxSparesAllowed)")
        }
    }

    private fun isPeerTooClose(myIndex: Int, peerIndex: Int, ringSize: Int): Boolean {
        if (peerIndex == -1) return true
        val distance = getRingDistance(myIndex, peerIndex, ringSize)
        return distance <= 2
    }

    private fun getRingDistance(index1: Int, index2: Int, ringSize: Int): Int {
        if (index1 == -1 || index2 == -1) return Int.MAX_VALUE
        val forwardDistance = (index2 - index1 + ringSize) % ringSize
        val backwardDistance = (index1 - index2 + ringSize) % ringSize
        return minOf(forwardDistance, backwardDistance)
    }
}
