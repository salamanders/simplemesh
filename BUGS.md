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
