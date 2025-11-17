package info.benjaminhill.simplemesh.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Represents the state of a remote device.
 *
 * This data class encapsulates the state of a device, including its `endpointId`, `name`, and
 * `phase` (connection status). It also includes a mechanism to automatically transition to a new
 * phase if a device remains in a specific phase for too long. This is implemented using a
 * `CoroutineScope` and a `delay`.
 */
data class DeviceState(
    val endpointId: EndpointId,
    val name: EndpointName,
    val phase: ConnectionPhase,
) {
    override fun toString() = "DeviceState(endpointId='$endpointId', name='$name', phase=$phase)"

    @Transient
    lateinit var followUpAction: Job

    fun startAutoTimeout(externalScope: CoroutineScope) {
        followUpAction = externalScope.launch {
            delay(phase.timeout)
            if (DevicesRegistry.getLatestDeviceState(endpointId)?.phase == phase) {
                val nextPhase = phase.phaseOnTimeout()

                Timber.tag("P2P_MESH")
                    .w("Device $endpointId stuck in phase $phase for more than ${phase.timeout}, moving to auto next phase $nextPhase")

                if (nextPhase != null) {
                    DevicesRegistry.updateDeviceStatus(
                        endpointId = endpointId,
                        externalScope = externalScope,
                        newPhase = nextPhase,
                    )
                } else {
                    DevicesRegistry.remove(endpointId)
                }
            }
        }
    }
}