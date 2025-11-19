package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Immutable representation of a remote device's state.
 *
 * @param endpointId The ephemeral ID assigned by Nearby Connections.
 * @param name The persistent human-readable name (from advertising).
 * @param phase Current connection status.
 */
data class DeviceState(
    val endpointId: EndpointId,
    val name: EndpointName,
    val phase: ConnectionPhase,
) {
    // A handle to the job that will trigger the timeout for this specific phase instance.
    // Transients are excluded from serialization/hashCode/equals.
    @Transient
    lateinit var followUpAction: Job

    /**
     * Schedules a timeout for the current phase.
     * If the device is still in this exact phase when the timer expires, it transitions to the next phase.
     */
    fun startAutoTimeout(externalScope: CoroutineScope) {
        followUpAction = externalScope.launch {
            delay(phase.timeout)
            
            // Re-check truth: Has the device changed state while we were sleeping?
            val latestState = DevicesRegistry.getLatestDeviceState(endpointId)
            if (latestState?.phase == phase) {
                val nextPhase = phase.phaseOnTimeout()

                Timber.tag("P2P_STATE").w(
                    "Timeout: ${name.value} ($endpointId) spent >${phase.timeout} in $phase. Moving to $nextPhase."
                )

                DevicesRegistry.updateDeviceStatus(
                    endpointId = endpointId,
                    externalScope = externalScope,
                    newPhase = nextPhase,
                )
            }
        }
    }
    
    override fun toString() = "${name.value} ($endpointId): $phase"
}