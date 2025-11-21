# Known Bugs & Issues

## Critical Issues

### startDiscovery failed (Status 8002)

**Symptom:** `logcat` shows `ApiException: 8002: STATUS_ALREADY_DISCOVERING`.
**Cause:** `startDiscovery` is called when the client is already discovering.
**Status:** Needs a flag or state check to prevent redundant calls.

### Activity Lifecycle Leak

**Symptom:** P2P operations stop when screen rotates.
**Cause:** `NearbyConnectionsManager` uses `lifecycleScope` which dies on rotation.
**Status:** High priority fix scheduled (see `GEMINI.md`).

### Packet Cache Memory Leak

**Symptom:** `seenPackets` set grows indefinitely.
**Cause:** `ConcurrentHashMap` never clears old packet IDs.
**Status:** High priority fix scheduled (needs TTL cleanup).

### Discovered Endpoints Never Connect (Major Logic Flaw)

**Symptom:** Devices are discovered but the application never attempts to initiate a connection,
resulting in them timing out in the `DISCOVERED` state.
**Cause:** In `RandomConnectionStrategy.manageConnectionsLoop()`, `attemptToConnect()` was
incorrectly called with `allDevices.keys` as `excludeIds`. This caused all known devices, including
`DISCOVERED` ones, to be excluded from connection attempts.
**Status:** Fixed.
**Fix Description:** Modified `RandomConnectionStrategy.manageConnectionsLoop()` to pass only the
EndpointIds of devices that are currently `CONNECTED` or `CONNECTING` to `attemptToConnect()` as
`excludeIds`. This ensures that `DISCOVERED` devices are now correctly considered as potential
candidates for new connections.

### Persistent Reconnection Loops and "Zombie" Connections

**Symptom:** Devices enter an infinite loop of attempting to connect, failing with
`STATUS_ALREADY_CONNECTED_TO_ENDPOINT (8003)`, timing out, being removed, rediscovered, and
retrying. This leads to connection flickering and instability. Additionally, connections that
timeout at the application layer (`CONNECTED` -> `ERROR` -> `null`) are not explicitly disconnected
at the Nearby Connections API layer, creating "zombie" connections that consume resources but are
not managed by the app.
**Cause:**

1. When a connection attempt fails with `STATUS_ALREADY_CONNECTED_TO_ENDPOINT (8003)`, the
   `RandomConnectionStrategy` currently treats this as a generic failure, leading to `ERROR` state
   and subsequent removal/retry cycles.
2. When a device is removed from `DevicesRegistry` (either due to timeout or `onEndpointLost`), the
   `NearbyConnectionsManager` does not explicitly call `connectionsClient.disconnectFromEndpoint()`.
   This leaves the underlying GMS connection active, leading to the 8003 errors and resource
   consumption.
   **Status:** Fixed.
   **Fix Description:**
1. In `NearbyConnectionsManager.kt`, modified the `init` block to explicitly call
   `connectionsClient.disconnectFromEndpoint(removedId.value)` for any `EndpointId` that is removed
   from `DevicesRegistry.devices`. This ensures that internal app state changes are reflected at the
   GMS layer.
2. In `RandomConnectionStrategy.kt`, modified the `addOnFailureListener` for
   `connectionsClient.requestConnection()` to specifically handle
   `STATUS_ALREADY_CONNECTED_TO_ENDPOINT (8003)`. If this status code is received, the device's
   state is immediately updated to `ConnectionPhase.CONNECTED`, treating the existing connection as
   valid and preventing further retry loops for that peer.

### Documentation Inconsistency (Flow Docs vs. Actual Code)

**Symptom:** The "Example Flows" (and now "Detailed Network State Flow & Lifecycle") sections in
`GEMINI.md` describe the intended behavior for node connection and state transitions, but this
behavior is not accurately reflected in the current code implementation.
**Cause:** The documentation is idealized, describing *what should happen*, rather than reflecting
the current implementation details. For example, the flow describes a 'Selection' step where `NodeB`
is picked, but the `RandomConnectionStrategy` code prevents `DISCOVERED` nodes from being picked due
to an incorrect `excludeIds` parameter.
**Status:** Acknowledged. The documentation serves as a target specification. The code needs to be
updated to match the documentation's intent.

## General Bugs

### Didn't connect in time

**Symptom:** Devices are found but time out in `DISCOVERED` state without connecting.
**Logs:** `Timeout: ... spent >30s in DISCOVERED. Moving to ERROR.`

### DevicesRegistry Race Condition

**Symptom:** Devices transition from `CONNECTED` to `ERROR` exactly 30 seconds after connection
initiation, even though the connection is healthy. This creates a "flickering" effect where nodes
disconnect and reconnect periodically. Double logs indicate multiple timeout jobs firing
simultaneously.
**Cause:** `DevicesRegistry.updateDeviceStatus` is not thread-safe. The "Check-Then-Act" sequence (
reading current state, cancelling job, writing new state) allows race conditions where multiple
threads create overlapping `DeviceState` objects. If an "orphan" job isn't cancelled, it will
eventually fire and kill the connection, even if the current state is valid.
**Status:** Fixed.
**Fix Description:**

1. Introduced a `Mutex` in `DevicesRegistry` to synchronize all state transitions.
2. The `updateDeviceStatus` and `remove` methods are now suspend functions guarded by
   `mutex.withLock`.
3. This ensures that reading the old state, cancelling its job, and installing the new state is an
   atomic operation.

### Recursive Discovery Start Loop

**Description:** `NearbyConnectionsManager` calls `strategy.start()`, which calls back to
`NearbyConnectionsManager.startDiscovery()`, causing potential loops and race conditions.

### Connection Attempt Race Condition

**Description:** `RandomConnectionStrategy` may attempt to connect to the same peer twice if the
first attempt is delayed.

---

# Bug Report Template

**Description**
A clear and concise description of what the bug is.

**To Reproduce**
Steps to reproduce the behavior:

1. Go to '...'
2. Click on '...'
3. Scroll down to '...'
4. See error

**Expected behavior**
A clear and concise description of what you expected to happen.

**Logs**
Paste relevant logcat output here. Use `deploy_all.sh` to capture logs from multiple devices if
possible.

**Screenshots**
If applicable, add screenshots to help explain your problem.

**Environment**

- Device: [e.g. Pixel 6]
- OS: [e.g. Android 13]