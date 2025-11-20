# Bugs

## startDiscovery failed

logcat shows repeated

```
startDiscovery failed
com.google.android.gms.common.api.ApiException: 8002: STATUS_ALREADY_DISCOVERING
```

Trace the calls to the message "startDiscovery failed" and see if any duplicates are possible, or if the app needs to keep track of a flag if it is already in discovery model
(Better if the API has a "isAlreadyDiscovering" sort of flag)

## Didn't connect in time

logcat shows

```
2025-09-29 02:32:28.411  7295-7295  P2P_MANAGER             info.benjaminhill.simplemesh         D  Found: device-KNFtJ1 (EndpointId(value=UWOY))
2025-09-29 02:32:29.166  7295-7295  P2P_MANAGER             info.benjaminhill.simplemesh         D  Found: device-wuEr4t (EndpointId(value=SIE0))
2025-09-29 02:32:32.538  7295-7368  ProfileInstaller        info.benjaminhill.simplemesh         D  Installing profile for info.benjaminhill.simplemesh
2025-09-29 02:32:32.834  7295-7295  P2P_MANAGER             info.benjaminhill.simplemesh         D  Found: device-h4MvmC (EndpointId(value=96WX))
2025-09-29 02:32:54.924  7295-7302  hill.simplemesh         info.benjaminhill.simplemesh         I  Background concurrent copying GC freed 2041KB AllocSpace bytes, 2(104KB) LOS objects, 85% free, 4109KB/28MB, paused 108us,41us total 104.707ms
2025-09-29 02:32:58.422  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-KNFtJ1 (EndpointId(value=UWOY)) spent >30s in DISCOVERED. Moving to ERROR.
2025-09-29 02:32:59.170  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-wuEr4t (EndpointId(value=SIE0)) spent >30s in DISCOVERED. Moving to ERROR.
2025-09-29 02:33:02.839  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-h4MvmC (EndpointId(value=96WX)) spent >30s in DISCOVERED. Moving to ERROR.
2025-09-29 02:33:28.430  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-KNFtJ1 (EndpointId(value=UWOY)) spent >30s in ERROR. Moving to null.
2025-09-29 02:33:28.431  7295-7295  P2P_REGISTRY            info.benjaminhill.simplemesh         D  Removing device: EndpointName(value=device-KNFtJ1) (EndpointId(value=UWOY))
2025-09-29 02:33:29.174  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-wuEr4t (EndpointId(value=SIE0)) spent >30s in ERROR. Moving to null.
2025-09-29 02:33:29.174  7295-7295  P2P_REGISTRY            info.benjaminhill.simplemesh         D  Removing device: EndpointName(value=device-wuEr4t) (EndpointId(value=SIE0))
2025-09-29 02:33:32.843  7295-7295  P2P_STATE               info.benjaminhill.simplemesh         W  Timeout: device-h4MvmC (EndpointId(value=96WX)) spent >30s in ERROR. Moving to null.
2025-09-29 02:33:32.844  7295-7295  P2P_REGISTRY            info.benjaminhill.simplemesh         D  Removing device: EndpointName(value=device-h4MvmC) (EndpointId(value=96WX))
```

It appears that it isn't connecting to the found devices within the timeout window.

## Activity Leak & Stale Context

**Description:** `NearbyConnectionsManager` retains a reference to the `Activity` passed to its constructor. This manager is held by `MainViewModel`, which outlives the `Activity` during configuration changes (e.g., screen rotation).
**Suspected Result:**
1.  **Memory Leak:** The original `Activity` is never garbage collected.
2.  **Crash/Misbehavior:** If the manager attempts to use the `Activity` context (e.g., for permissions or dialogs) after it has been destroyed, it may crash or fail silently. The `ConnectionsClient` might also become detached from the valid lifecycle.

## Broadcast Flood (No Loop Prevention)

**Description:** The `broadcast` method in `NearbyConnectionsManager` sends data to all connected peers except the sender. It does not include a unique message ID (UUID) or a Time-To-Live (TTL) counter in the payload.
**Suspected Result:** A network with a cycle (A -> B -> C -> A) will cause an infinite feedback loop of re-broadcasting the same packet, instantly flooding the network bandwidth and likely crashing the radio stack.

## DevicesRegistry Race Condition

**Description:** `DevicesRegistry` uses `_devices.value += ...` to update the state flow. This operation is not atomic: it reads the current map, creates a new one, and sets it.
**Suspected Result:** If multiple updates occur simultaneously (e.g., a PING arriving at the exact same time as a DISCOVERY event on another thread), one of the updates will be overwritten and lost. This could lead to "stuck" states where the UI or logic thinks a device is in an old phase.

## Recursive Discovery Start Loop

**Description:**
1. `NearbyConnectionsManager.startDiscovery()` calls `connectionStrategy.start()`.
2. `RandomConnectionStrategy.start()` launches a coroutine and calls its `startDiscovery` lambda.
3. The lambda calls `NearbyConnectionsManager.startDiscovery()`.
4. `NearbyConnectionsManager` then calls `connectionsClient.startDiscovery()`.
**Suspected Result:** While the strategy has an `isActive` check, the `NearbyConnectionsManager` calls `client.startDiscovery` *after* delegating to the strategy. This leads to `client.startDiscovery` being called multiple times (once via the strategy callback, once by the manager directly), contributing to the `STATUS_ALREADY_DISCOVERING` error.

## Connection Attempt Race Condition

**Description:** `RandomConnectionStrategy.attemptToConnect` selects a peer and launches a coroutine that delays (backoff) before requesting a connection. The peer's state remains `DISCOVERED` during this delay.
**Suspected Result:** If `attemptToConnect` runs again (e.g., via `manageConnectionsLoop`) before the delay finishes, it may select the *same* peer and launch a second connection attempt. Both attempts will eventually fire, potentially causing protocol errors or confusing the state machine.

## startAdvertising API Misuse

**Description:** Similar to the discovery bug, `startAdvertising()` does not appear to check if advertising is already active before calling the API.
**Suspected Result:** If `startAdvertising` is called redundantly (e.g., fast resume/pause cycles or via logic bugs), it will likely throw `ApiException: 8001: STATUS_ALREADY_ADVERTISING`, cluttering logs or interfering with legitimate restart attempts.
