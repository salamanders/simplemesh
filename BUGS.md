# Suspected Bug: Connection Instability and Churn

The primary bug is a cascading failure of connections, resulting in high churn and network
instability. This is caused by an overly aggressive reconnection strategy in the
`reconnectWithBackoff` function.

## Evidence

The logcats from all four devices show a clear pattern of connection instability.

1. **Connection Churn:** Devices are frequently connecting and disconnecting. This is evident from
   the repeated `onConnectionInitiated`, `onConnectionResult`, `onDisconnected`, and
   `reconnectWithBackoff` messages in the logs.
2. **Reconnect Storms:** When a device disconnects, the `reconnectWithBackoff` function is called.
   This function calls `stopAll()`, which tears down all existing connections, stops advertising,
   and stops discovery. This is a very heavy-handed approach to reconnecting to a single lost peer.
   It's likely causing a cascading failure where all connections are dropped, and then all devices
   try to reconnect at once, leading to the observed churn.
3. **Discovery Timeouts:** Devices are failing to connect after being discovered. The log message
   `Device ... stuck in phase DISCOVERED for more than 30s, moving to auto next phase ERROR`
   indicates that devices are found but the connection is not established within the 30-second
   timeout. This is likely a symptom of the network being overwhelmed with connection requests.
4. **Connection Timeouts:** Even when a connection is established, it's not stable. The message
   `Device ... stuck in phase CONNECTED for more than 1m, moving to auto next phase ERROR` shows
   that the PING/PONG heartbeat is failing, causing the connection to be considered dead. This is
   also likely a symptom of the network instability.

## Reliable Code

The following parts of the code appear to be reliable:

* **`ConnectionPhase` enum and `DeviceState` timeout mechanism:** This system correctly identifies
  when devices are stuck in a particular state and triggers the appropriate timeout actions. The
  issue is not with the detection of the problem, but with the response to it.
* **PING/PONG heartbeat:** The heartbeat mechanism is functioning correctly to detect dead
  connections.

## Proposed Fix and Analysis

The proposed fix is to make the `reconnectWithBackoff` function less disruptive by replacing the
call to `stopAll()` with more granular calls to `connectionsClient.stopDiscovery()` and
`connectionsClient.stopAdvertising()`.

### Arguments For

* **Reduces Network Disruption:** This change will prevent the mass disconnection of all peers when
  a single peer is lost. This will allow the device to maintain its healthy connections while it
  attempts to find new or lost peers.
* **Addresses Cascading Failure:** This surgical approach directly addresses the cascading failure
  mode observed in the logs. By not tearing down all connections, it prevents the "reconnect storm"
  that is overwhelming the network.
* **Logical Next Step:** The current strategy is clearly not working. A less disruptive approach is
  a logical and low-risk next step in debugging the issue.

### Arguments Against

* **Insufficient Reset:** It's possible that simply restarting discovery and advertising is not
  enough to clear an error state in the Nearby Connections API. The `stopAll()` might be necessary
  to perform a "hard reset" of the `ConnectionsClient`. If the underlying issue is a state
  corruption within the `ConnectionsClient`, then not calling `stopAll()` might leave the client in
  a broken state, and the reconnection attempts will still fail.
* **Masking a Deeper Issue:** It's possible that the connection churn is a symptom of a deeper
  issue, such as a race condition or a logic error in the `ConnectionStrategy`. While making the
  reconnection logic less aggressive will likely improve stability, it might not address the root
  cause of the initial disconnections.

## Root Cause of Initial Disconnections

Further analysis of the logs and code has revealed that the `HealingService` is the primary
trigger for the initial disconnections.

The `HealingService` is intended to perform "global healing" by periodically restarting discovery
to find and merge with other network partitions. However, its implementation is overly aggressive.

```kotlin
// HealingService.kt
class HealingService(
    private val nearbyConnectionsManager: NearbyConnectionsManager,
    private val externalScope: CoroutineScope
) {
    fun start() {
        externalScope.launch {
            while (true) {
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
```

The service calls `nearbyConnectionsManager.stopAll()` every 5 minutes and 15 seconds. This call
tears down all active connections, intentionally causing a network-wide disconnection event.

This "global healing" mechanism is the root cause of the initial disconnections. The subsequent
"reconnect storms" are a direct result of each device's `reconnectWithBackoff` function
attempting to recover from this intentional disconnection, also by calling `stopAll()`.

This creates a vicious cycle:
1. `HealingService` on one device calls `stopAll()`, disconnecting all its peers.
2. The disconnected peers detect the disconnection and trigger their own `reconnectWithBackoff`.
3. Each of these peers then calls `stopAll()`, causing further disconnections and network
   instability.

The combination of the `HealingService`'s aggressive global healing and the `reconnectWithBackoff`
function's aggressive local healing is the core reason for the observed connection churn.

## Secondary Causes of Instability

Beyond the primary issues in `HealingService` and `reconnectWithBackoff`, the
`ConnectionStrategy.kt` file contains logic that contributes to connection churn, especially in a
small network.

1. **`connectionRotation`:** This feature is designed to prevent network partitions by periodically
   disconnecting from "leaf nodes" (peers with only one connection) to encourage the formation of a
   more resilient topology. It runs every 5 minutes. In a small network of 4-5 devices, the
   topology is less likely to have significant partitions, and this proactive disconnection adds
   unnecessary instability.

2. **`manageConnections`:** The logic for selecting a new peer to connect to is too simplistic. It
   can attempt to connect to peers that are already in the process of connecting (`CONNECTING`
   phase) or have very recently disconnected. This leads to redundant connection attempts and
   contributes to network noise.

## Heartbeat Race Condition

Even after addressing the aggressive reconnection logic, the logs show that devices are still
timing out while in the `CONNECTED` phase. This is due to a race condition in the heartbeat
mechanism.

### Evidence

The logs show a recurring pattern where a device times out and moves to the `ERROR` state, only to
receive a `PING` or `PONG` immediately after.

```
11-16 11:37:04.410 W P2P_MESH: Device I8RO stuck in phase CONNECTED for more than 1m, moving to auto next phase ERROR
11-16 11:37:04.478 D P2P_MESH: Received PING from I8RO
```

This indicates that the timeout in `DeviceState.startAutoTimeout` is firing just before the
heartbeat message is processed.

### Root Cause

The root cause of this race condition is that the timeout for a device is not being reset when a
`PING` is received from that device.

The current logic in `NearbyConnectionsManager.onPayloadReceived` is as follows:
- When a `PONG` is received, it calls `connectedSendPing`, which updates the device's state to
  `CONNECTED` and resets the timeout.
- When a `PING` is received, it only sends a `PONG` back. It **does not** reset the timeout for the
  device that sent the `PING`.

This means that if a device is busy or the network is congested, the `PONG` might be delayed, and
the sending device will time out, even though the connection is still alive.

### Proposed Fix

The fix is to update the device's state to `CONNECTED` when a `PING` is received. This will reset
the timeout and prevent the race condition.

The `onPayloadReceived` function in `NearbyConnectionsManager.kt` should be modified to call
`DevicesRegistry.updateDeviceStatus` when a `PING` is received.
