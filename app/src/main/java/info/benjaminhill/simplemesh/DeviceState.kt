package info.benjaminhill.simplemesh


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

data class DeviceState(
    val endpointId: String,
    val name: String,
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