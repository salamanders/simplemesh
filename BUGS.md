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
**Symptom:** Devices are discovered but the application never attempts to initiate a connection, resulting in them timing out in the `DISCOVERED` state.
**Cause:** In `RandomConnectionStrategy.manageConnectionsLoop()`, `attemptToConnect()` is incorrectly called with `allDevices.keys` as `excludeIds`. This causes all known devices, including `DISCOVERED` ones, to be excluded from connection attempts.
**Status:** High priority fix needed in `RandomConnectionStrategy.kt`.

### Documentation Inconsistency (Flow Docs vs. Actual Code)
**Symptom:** The "Example Flows" (and now "Detailed Network State Flow & Lifecycle") sections in `GEMINI.md` describe the intended behavior for node connection and state transitions, but this behavior is not accurately reflected in the current code implementation.
**Cause:** The documentation is idealized, describing *what should happen*, rather than reflecting the current implementation details. For example, the flow describes a 'Selection' step where `NodeB` is picked, but the `RandomConnectionStrategy` code prevents `DISCOVERED` nodes from being picked due to an incorrect `excludeIds` parameter.
**Status:** Acknowledged. The documentation serves as a target specification. The code needs to be updated to match the documentation's intent.

## General Bugs

### Didn't connect in time
**Symptom:** Devices are found but time out in `DISCOVERED` state without connecting.
**Logs:** `Timeout: ... spent >30s in DISCOVERED. Moving to ERROR.`

### DevicesRegistry Race Condition
**Description:** `DevicesRegistry` updates are not atomic, potentially overwriting simultaneous state changes (e.g., Discovery + PING).

### Recursive Discovery Start Loop
**Description:** `NearbyConnectionsManager` calls `strategy.start()`, which calls back to `NearbyConnectionsManager.startDiscovery()`, causing potential loops and race conditions.

### Connection Attempt Race Condition
**Description:** `RandomConnectionStrategy` may attempt to connect to the same peer twice if the first attempt is delayed.

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
Paste relevant logcat output here. Use `deploy_all.sh` to capture logs from multiple devices if possible.

**Screenshots**
If applicable, add screenshots to help explain your problem.

**Environment**
 - Device: [e.g. Pixel 6]
 - OS: [e.g. Android 13]