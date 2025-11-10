package info.benjaminhill.simplemesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

enum class ConnectionStatus {
    DISCOVERY_FAILED,
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    REJECTED,
    ERROR;

    /**
     * Creates a new Job appropriate for this status.
     * The job will be managed by the DeviceState.
     */
    fun createJob(
        scope: CoroutineScope,
        endpointId: String,
        removeDevice: () -> Unit,
        startHeartbeat: () -> Job,
        getCurrentStatus: () -> ConnectionStatus?
    ): Job? = when (this) {
        DISCOVERED, CONNECTING -> {
            scope.launch {
                delay(30_000)
                if (getCurrentStatus() == this@ConnectionStatus) {
                    Timber.tag("P2P_MESH")
                        .w("Device $endpointId stuck in state $this, removing.")
                    removeDevice()
                }
            }
        }
        CONNECTED -> {
            startHeartbeat()
        }
        ERROR, REJECTED, DISCONNECTED -> {
            scope.launch {
                delay(30_000)
                if (getCurrentStatus() == this@ConnectionStatus) {
                    Timber.tag("P2P_MESH")
                        .w("Device $endpointId in state $this timed out, removing.")
                    removeDevice()
                }
            }
        }
        DISCOVERY_FAILED -> null
    }
}
