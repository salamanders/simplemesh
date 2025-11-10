package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun failIfStillInSameState(
    duration: Duration,
    scope: CoroutineScope,
    endpointId: String,
    state: ConnectionState,
    getCurrentStatus: () -> ConnectionState?,
    removeDevice: () -> Unit
): Job = scope.launch {
    delay(duration)
    if (getCurrentStatus() == state) {
        Timber.tag("P2P_MESH")
            .w("Device $endpointId stuck in state $state for more than $duration, removing.")
        removeDevice()
    }
}

private typealias JobProducer = (
    scope: CoroutineScope,
    endpointId: String,
    removeDevice: () -> Unit,
    startHeartbeat: () -> Job,
    getCurrentStatus: () -> ConnectionState?
) -> Job?

enum class ConnectionState(
    private val jobProducer: JobProducer
) {
    DISCOVERY_FAILED({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, DISCOVERY_FAILED, getCurrentStatus, removeDevice)
    }),
    DISCOVERED({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, DISCOVERED, getCurrentStatus, removeDevice)
    }),
    CONNECTING({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, CONNECTING, getCurrentStatus, removeDevice)
    }),
    CONNECTED({ _, _, _, startHeartbeat, _ ->
        startHeartbeat()
    }),
    DISCONNECTED({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, DISCONNECTED, getCurrentStatus, removeDevice)
    }),
    REJECTED({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, REJECTED, getCurrentStatus, removeDevice)
    }),
    ERROR({ scope, endpointId, removeDevice, _, getCurrentStatus ->
        failIfStillInSameState(30.seconds, scope, endpointId, ERROR, getCurrentStatus, removeDevice)
    });

    /**
     * Creates a new Job appropriate for this status.
     * The job will be managed by the DeviceState.
     */
    fun createJob(
        scope: CoroutineScope,
        endpointId: String,
        removeDevice: () -> Unit,
        startHeartbeat: () -> Job,
        getCurrentStatus: () -> ConnectionState?
    ): Job? = jobProducer(scope, endpointId, removeDevice, startHeartbeat, getCurrentStatus)
}
