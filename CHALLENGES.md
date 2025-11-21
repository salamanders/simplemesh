# **Comparative Analysis of P2P Overlay Architectures: Android Nearby Connections (P2P\_CLUSTER) vs.

Generic Bluetooth Implementation**

## **Executive Summary**

This report provides a comprehensive engineering analysis comparing the implementation of a 30-node
peer-to-peer (P2P) overlay network using the Google Nearby Connections API (specifically the
P2P\_CLUSTER strategy) versus a custom-built stack utilizing generic Bluetooth Low Energy (BLE) and
Bluetooth Classic. The scope of this analysis encompasses technical feasibility, engineering effort,
and performance trade-offs, with a specific focus on the algorithmic complexity of "island
joining" (partition merging), "cluster repair," and cross-platform interoperability between Android
and iOS.

The analysis suggests that while **Android Nearby Connections** significantly reduces initial
engineering overhead for Android-only environments, it presents critical limitations in topology
control and bandwidth scaling for the requested 30-node mesh, particularly in the P2P\_CLUSTER
configuration. Conversely, a **Generic Bluetooth** implementation offers necessary granular control
for cross-platform functionality and custom routing logic (essential for island joining), but
requires an exponentially higher engineering effort to manage the physical layer (PHY) constraints,
scatternet formation, and background execution limits on iOS.

Furthermore, an in-depth examination of the underlying radio protocols, operating system
limitations, and theoretical graph constraints reveals that a 30-node network sits at the very edge
of what is physically possible with current smartphone hardware without specialized mesh protocols.
The "undifferentiated heavy lifting" performed by Nearby Connections, while valuable for small
groups, obfuscates the critical routing decisions required to maintain stability in a network of
this size, potentially leading to unrecoverable network partitions.

## **1\. Architecture and Topology: P2P\_CLUSTER vs. Scatternets**

### **1.1 Android Nearby Connections: The P2P\_CLUSTER Strategy**

The P2P\_CLUSTER strategy within the Nearby Connections API is designed to support M-to-N
topologies, allowing for "amorphous clusters" of devices.1 In this configuration, a device can
simultaneously act as both an advertiser and a discoverer, theoretically enabling a mesh-like
structure where each node connects to $M$ other devices.

However, the architectural reality of P2P\_CLUSTER is constrained by the underlying radio hardware.
Android devices utilize a single Bluetooth radio that must time-slice between advertising, scanning,
and maintaining connections. The documentation explicitly notes that P2P\_CLUSTER is the most
general strategy and, consequently, has the fewest supported mediums available, primarily relying on
Bluetooth (BLE and Classic) and LAN, rather than high-bandwidth Wi-Fi Direct or Hotspots in a mesh
configuration.2

While the API abstracts the complexity, the "30-node" requirement pushes the theoretical limits of
this strategy. Bluetooth hardware typically imposes a hard limit on the number of active concurrent
connections (often 7 active slaves per master in a piconet).2 To achieve a 30-node cluster, the
network must form a **scatternet**, where nodes act as bridges (Master in one piconet, Slave in
another). Nearby Connections attempts to manage this automatically, but reports indicate practical
limits around 7–8 devices before stability degrades significantly.2 The abstraction layer obscures
the topology, making it difficult for engineers to optimize the graph for hop-count or latency.

#### **1.1.1 Theoretical vs. Practical Limits of P2P\_CLUSTER**

While Google's documentation defines P2P\_CLUSTER as supporting "amorphous clusters," the
implementation is bound by the limits of the Android Bluetooth stack (BlueDroid or Fluoride).

* **Hard Coded Limits:** Most Android devices have a hard-coded limit on the number of simultaneous
  GATT connections. This is often defined in the stack configuration (e.g., BTA\_GATTC\_CONN\_MAX).
  While the theoretical limit of BLE can be much higher (up to 231 devices in theory), manufacturer
  implementations typically cap this between 5 and 10 devices to preserve bandwidth and scheduling
  stability.5
* **Role Switching Latency:** In P2P\_CLUSTER, a device must rapidly switch between advertising (to
  be found) and scanning (to find others). This "dual-mode" operation incurs a switching penalty,
  often around 5ms to 20ms per switch.5 In a 30-node network, as $N$ increases, the probability of
  two devices being on the same channel in the same mode (Scan/Advertise) decreases, leading to
  exponentially longer discovery times.
* **Spectrum Saturation:** Nearby Connections manages the radio opacity. With 30 nodes in close
  proximity (e.g., a 100m radius), if all nodes are utilizing the P2P\_CLUSTER strategy, the 2.4 GHz
  spectrum becomes saturated with advertising packets. Nearby Connections does not expose the
  parameters to tune the "Advertising Interval" or "Scan Window" 6, meaning the developer cannot
  optimize for density, leading to packet collisions and broadcast storms.

### **1.2 Generic Bluetooth: Custom Mesh Overlay**

Implementing a custom overlay using generic Bluetooth (specifically BLE 4.x/5.x) grants the engineer
direct control over the link layer, which is essential for constructing a stable 30-node network.
This approach requires manually managing the **GATT (Generic Attribute Profile)** roles.

To support 30 nodes, a custom implementation must utilize a Managed Flood or Mesh routing protocol.
Unlike the star topology of a piconet, a custom mesh does not require all 30 nodes to be directly
connected to a central hub. Instead, the engineering effort focuses on implementing a **Provisioning
** layer and a **Relay** mechanism.

**Feasibility Analysis:**

* **Connection Limits:** Android and iOS devices generally support up to 7–8 concurrent BLE
  connections.4 A 30-node network is feasible only if implemented as a multi-hop mesh where each
  node maintains only 2–4 neighbor connections.
* **State Management:** A generic implementation allows the application to define specific "Relay"
  or "Proxy" nodes 8, optimizing the graph to prevent saturation of the 2.4GHz spectrum. Nearby
  Connections does not expose this level of configuration.

#### **1.2.1 Scatternet Formation and Graph Theory**

In a custom generic Bluetooth implementation, the topology for 30 nodes relies on constructing a *
*Scatternet**. This differs fundamentally from the Bluetooth SIG Mesh (which typically uses
advertising bearers) by potentially utilizing connected GATT bearers for higher throughput.

* **Degree of Connectivity:** To maintain a connected graph of 30 nodes without exceeding the
  7-connection hardware limit, the network must limit the degree $d$ of any vertex (node) such
  that $d \\le 7$. A random geometric graph approach (connecting to any node in range) often fails
  this constraint.
* **Bridge Node Bottlenecks:** A Scatternet consists of multiple Piconets (Stars). The "Bridge"
  nodes (Master in one, Slave in another) become critical bottlenecks. In a generic implementation,
  the engineer must write the algorithm to designate these bridges. If a bridge node fails, the
  network partitions.
* **Topology Optimization:** Unlike Nearby Connections, a generic implementation allows for *
  *Topology Control Algorithms**. For instance, the system can enforce a "BlueMesh" or "On-Demand
  Multicast Routing Protocol" (ODMRP) structure 9, prioritizing links with higher RSSI or lower
  interference.

#### **1.2.2 The Role of the Bearer Layer**

A critical distinction in the generic implementation is the choice of **Bearer**.

* **GATT Bearer:** Utilizes connections. High reliability, low latency, but limited by the max
  connection count (7-8 devices). Best for point-to-point data transfer.
* **Advertising Bearer:** Utilizes the three advertising channels (37, 38, 39\) to broadcast data
  without forming connections. This is the basis of the official Bluetooth Mesh standard.10
* **Hybrid Approach:** A 30-node custom overlay often requires a hybrid approach—using Advertising
  Bearers for discovery and small status updates (Managed Flood), and GATT Bearers for larger data
  transfer or island bridging. This level of architectural flexibility is completely absent in
  P2P\_CLUSTER.

## **2\. Engineering Effort and Complexity**

The divergence in engineering effort between the two approaches is stark, primarily due to the
abstraction levels and the handling of "undifferentiated heavy lifting" regarding radio management.

### **2.1 Effort Breakdown: Nearby Connections**

The Nearby Connections API abstracts the vagaries of radio switching, authentication, and socket
management.11

* **Discovery & Connection:** Complexity is Low. The API handles the handshake and symmetric
  authentication automatically.11 The developer simply calls startAdvertising and startDiscovery.
* **Data Transmission:** Complexity is Low-Medium. The API provides Payload objects (Bytes, Files,
  Streams) 11, handling segmentation and reassembly internally.
* **Topology Management:** Complexity is High (Hidden). Because the API hides the topology,
  debugging a 30-node cluster that fails to converge is difficult. The developer cannot easily force
  a specific route or debug why a specific node is isolated.

#### **2.1.1 Hidden Complexities and Security Pitfalls**

While the "Happy Path" in Nearby Connections is low effort, handling edge cases and security reveals
hidden complexity.

* **Authentication Gaps:** Research indicates that while Nearby Connections provides an "
  authentication token," it does *not* automatically authenticate the Bluetooth link key or bind the
  Bluetooth and Wi-Fi physical layers cryptographically.13 The documentation warns that "encryption
  without authentication is essentially meaningless".13 The developer must implement an out-of-band
  verification (e.g., comparing the SAS token visually) to prevent Man-in-the-Middle (MitM) attacks.
* **Error Handling:** The API returns generic error codes. For example, STATUS\_BLUETOOTH\_ERROR 14
  or generic "8011" errors 15 often indicate lower-level stack issues that the developer cannot
  resolve without restarting the Bluetooth service or the device itself.
* **Payload Constraints:** The naming of payloads and endpoints has hidden constraints. For
  instance, the internal Bluetooth name used during the handshake depends on the ncname, and
  research has shown the maximum length is approximately 131 bytes.16 Exceeding this can cause
  silent failures or truncation, leading to connection loop failures.

### **2.2 Effort Breakdown: Generic Bluetooth (BLE)**

Building a custom stack requires re-implementing layers that Nearby Connections provides
out-of-the-box.

* **Discovery & Connection:** Complexity is High. Engineers must implement scanning filters, handle
  scanResponse data, and manage the CBCentralManager (iOS) and BluetoothLeScanner (Android) states.
* **GATT Architecture:** Complexity is Very High. Defining characteristics, handling MTU (Maximum
  Transmission Unit) negotiation (typically 23 bytes default, upgradable to 512 bytes) 17, and
  implementing packet fragmentation/reassembly for large messages is mandatory.
* **Mesh Protocol:** Complexity is Extreme. A 30-node network requires a routing protocol. Options
  include:
    * **Flooding (Managed Flood):** Similar to the Bluetooth SIG Mesh standard. Every node repeats
      messages. Simple to implement but prone to broadcast storms and high battery drain.18
    * **Source Routing:** The sender defines the path. Requires route discovery packets, which adds
      overhead.19
    * **RPL (Routing Protocol for Low-Power and Lossy Networks):** Highly complex to implement from
      scratch.

#### **2.2.1 The "Full Stack" Requirement**

Implementing a custom 30-node mesh is akin to developing a proprietary networking protocol.

* **Provisioning Logic:** The system needs a way to admit new nodes securely. In the Bluetooth Mesh
  standard, this involves a "Provisioner" distributing Network Keys (NetKey) and Application Keys (
  AppKey).8 A custom implementation must replicate this:
    * **Elliptic Curve Diffie-Hellman (ECDH):** To exchange keys securely over an unencrypted link.
    * **Key Management:** Storing keys in the Android Keystore or iOS Keychain.
    * **Blacklisting:** Implementing a mechanism to remove "trash can" nodes (devices physically
      compromised) from the network, requiring a Key Refresh Procedure.10
* **Protocol Primitives:** The engineer must define the packet structure.
    * *Preamble & Sync Word:* Defined by the PHY.
    * *Header:* Source ID (16-bit), Destination ID (16-bit), Sequence Number (to prevent replay
      attacks and loops).
    * *Payload:* The actual application data.
    * *TTL (Time To Live):* Crucial for a 30-node mesh to prevent packets circulating forever. The
      Bridgefy SDK, for example, defines propagation profiles with specific TTLs (e.g., 250 hops
      for "Long Reach").21

#### **2.2.2 Leveraging Open Source Libraries**

To mitigate the extreme effort, developers might look to open-source libraries, though each has
limitations for this specific use case.

* **Meshtastic:** While primarily LoRa-based, its "Managed Flood" routing logic (Client, Router,
  Repeater roles) provides a blueprint. Porting this logic to BLE requires mapping the LoRa
  frequency slots to BLE advertising intervals.18
* **Berty Protocol (Weshnet):** Berty uses a "Proximity Transport" over BLE. It utilizes a local
  record of rendezvous points.23 It implements a "Multi-Peer Connectivity" abstraction that could be
  studied, but it relies heavily on IPFS concepts which may be overhead for a pure mesh.24
* **Nordic nRF Mesh Library:** This is the gold standard for Android/iOS BLE Mesh.20 However, it is
  designed for the *Bluetooth SIG Mesh* standard (control messages, light bulbs), not necessarily
  high-bandwidth generic P2P file transfer. Adapting it for custom payloads requires modifying the
  Model layer (Vendor Models).25

**Comparative Effort Estimate:**

| Feature                     | Nearby Connections (P2P\_CLUSTER) | Generic Bluetooth (Custom Mesh)      |
|:----------------------------|:----------------------------------|:-------------------------------------|
| **Initial Setup**           | 1–2 Weeks                         | 2–4 Months                           |
| **Radio Management**        | Automated (Opaque)                | Manual (Granular)                    |
| **iOS Interoperability**    | **Near Impossible (Offline)**     | Possible (High Effort)               |
| **30-Node Stability**       | Low (Black box limitations)       | Medium (Dependent on algorithm)      |
| **Backgrounding**           | Restricted (OS kills services)    | Complex (State Restoration required) |
| **Security Implementation** | Pre-packaged (Symmetric Auth)     | Manual (ECDH, Key Refresh)           |
| **Protocol Design**         | Defined by Google (Proprietary)   | Full Custom (Packet structure, TTL)  |

## **3\. Island Joining: Complexity and Algorithmic Challenges**

"Island joining" refers to the scenario where two disconnected partitions of the mesh (e.g., two
groups of 15 nodes) come into radio range and must merge into a single 30-node network. This is a
critical failure mode in mobile ad-hoc networks (MANETs).

### **3.1 Island Joining in Nearby Connections**

In P2P\_CLUSTER, discovery and connection are continuous. However, the API does not natively "merge"
two clusters in a topological sense; it connects individual nodes.

* **The Problem:** If Cluster A and Cluster B meet, the API might establish a link between *one*
  node from A and *one* from B. However, without a routing protocol overlay, the nodes in Cluster A
  do not automatically gain routes to Cluster B.
* **Complexity:** The developer must build an application-layer protocol on top of Nearby
  Connections to exchange "routing tables" or "neighbor lists" once a link is made. This negates
  some of the API's convenience.
* **Constraint:** P2P\_CLUSTER implies a loose mesh, but if the underlying transport is Bluetooth,
  the "master/slave" roles (Advertiser/Discoverer) are rigid in the link layer. Merging two large
  clusters requires complex role switching, which the API performs opaquely and often
  inefficiently.13

#### **3.1.1 The "Split Brain" Risk in Nearby Connections**

When two clusters merge, they may have conflicting states. For example, if both clusters have a "
Leader" node (in a star-like logical overlay built on top of the cluster), the merge results in a "
Split Brain" scenario.

* **Service ID Collision:** Both clusters broadcast the same ServiceId.26 The API connects them, but
  the application logic must handle the collision of node hierarchies.
* **Network Partition Detection:** Nearby Connections does not provide a "Partition Detected" event.
  The application must infer this by analyzing the sudden influx of new endpointIDs.
* **Merge Storm:** Upon connection, if the application logic triggers a sync of all known nodes, the
  single bridge link (likely a BLE connection) will be overwhelmed by metadata exchange, causing the
  connection to drop and the islands to separate again (flapping).27

### **3.2 Island Joining in Generic Bluetooth**

In a custom solution, island joining is an algorithmic challenge of **Partition Detection** and *
*State Reconciliation**.

* **Discovery:** Nodes must periodically scan even when connected to a mesh. This requires "duty
  cycling" the radio—pausing data transmission to listen for advertisements from foreign clusters.28
* **Merge Protocol:**
    1. **Beaconing:** Edge nodes transmit a "Network ID" or "Cluster Hash."
    2. **Handshake:** When a foreign ID is detected, the nodes initiate a connection.
    3. **Topology Update:** The complexity here is **O(N)** or **O(N log N)** depending on the
       routing algorithm.30 A flooding protocol (like Bridgefy or Bluetooth Mesh) creates a "link"
       and simply propagates the new addressable nodes via the next flood.31
    4. **Conflict Resolution:** If both clusters use overlapping short IDs (16-bit), the merge logic
       must detect collisions and trigger re-addressing.33

#### **3.2.1 Algorithmic Approaches to Merging**

To efficiently merge two 15-node islands, specific distributed systems algorithms must be employed.

* **CRDTs (Conflict-free Replicated Data Types):** This is the modern standard for decentralized
  state merging.
    * **Observed-Remove Set (OR-Set):** Used to maintain the list of active nodes. When islands
      merge, the sets are unioned. The mathematical properties of CRDTs guarantee that all nodes
      will eventually converge to the same state without a central coordinator.35
    * **Merkle-DAG Sync:** Similar to how IPFS (and by extension Berty) operates.37 Nodes exchange
      the root hash of their "knowledge graph." If hashes differ, they traverse the tree to find
      exactly which nodes are missing, minimizing data transfer over the slow BLE link.
* **Epidemic Routing / Gossip Protocols:** Upon bridge formation, the bridge nodes enter an "
  Epidemic" phase, pushing vector clocks or version vectors to their neighbors. This "infects" the
  cluster with the new topology data.38
* **Complexity Analysis:** The time complexity to stabilize the network after a merge depends on the
  network diameter. For a 30-node linear mesh, stabilization time can be $O(Diameter)$. Partition
  detection systems often require $O(N^2)$ message exchanges in the worst case (naïve
  implementation), but optimized "Weak DAD" (Duplicate Address Detection) schemes can achieve this
  with significantly less overhead.33

#### **3.2.2 The "Bridgefy" Reference Model**

The Bridgefy SDK documentation provides insight into handling this at scale.21

* **Propagation Profiles:** Bridgefy defines profiles like "High Density" vs "Long Reach."
    * *Long Reach:* Increases TTL (Time To Live) and Hops limit (up to 250).
    * *High Density:* Reduces Hops limit (to 50\) to prevent saturation.
* **Tracklist Limit:** To prevent loops during a merge, nodes maintain a "Tracklist" of recently
  seen UUIDs (e.g., last 50 messages). When islands merge, this prevents old messages from the other
  island from re-circulating endlessly. A custom implementation must replicate this "Tracklist"
  logic to survive the merge.21

**Insight:** The "Island Joining" problem is effectively a distributed database merge problem.
Custom implementations often utilize **CRDTs (Conflict-free Replicated Data Types)** to merge the
state of the two islands without a central authority.35 Nearby Connections does not provide this; it
only provides the pipe.

## **4\. Cluster Repair and Self-Healing**

Cluster repair addresses the scenario where a critical node (e.g., a bridge node) fails or leaves
the network.

### **4.1 Repair in Nearby Connections**

Nearby Connections handles connection loss via the onDisconnected callback.

* **Mechanism:** If a link breaks, the API may attempt to reconnect, but in a complex cluster, the
  application logic is responsible for finding a new path.
* **Limitations:** Since the topology is abstract, the application doesn't know *which* alternative
  node is optimal. It must blindly attempt to connect to other available endpoints, potentially
  creating suboptimal routes or islands.41
* **Latency:** The onDisconnected callback is often delayed. The system must wait for the "
  Supervision Timeout" (typically seconds) before declaring the link dead. In a real-time app, this
  pause is noticeable.

### **4.2 Repair in Generic Bluetooth**

A custom mesh allows for **predictive repair** and **multipath routing**.

* **Keep-Alives:** Implementing heartbeat messages allows nodes to detect link degradation (RSSI
  monitoring) *before* failure. Nearby Connections hides RSSI data in most modes.6
* **Path Recalculation:**
    * *Managed Flood:* Repair is instantaneous. If Node A moves, the flood simply reaches Node B via
      Node C instead. There is no "route" to break, making it highly resilient for dynamic 30-node
      clusters.18
    * *Routing Tables:* If using source routing (like AODV or DSR), a broken link triggers a Route
      Error (RERR) propagation, forcing a new Route Request (RREQ) flood.9 This induces latency but
      guarantees valid paths.

#### **4.2.1 Routing Protocol Comparison: Flooding vs. Source Routing**

For a 30-node cluster, the choice of routing algorithm dictates the repair efficiency.

* **Optimized AODV (Ad-hoc On-demand Distance Vector):** Research suggests AODV can be optimized for
  BLE (O-AODV). It offers lower overhead than pure flooding but higher latency during route
  discovery. In a repair scenario, O-AODV must pause traffic while the new route is found.9
* **Managed Flood (Meshtastic Model):** This is superior for "repair" because it is stateless
  regarding paths. If the topology changes, the packet simply flows through the new available
  neighbors. The trade-off is efficiency; flooding consumes more airtime. However, for 30 nodes,
  managed flooding (with "Client Mute" roles to reduce noise) is often more robust than maintaining
  fragile routing tables.18
* **UFlood:** For larger file transfers during repair, advanced protocols like UFlood use
  distributed heuristics to choose senders, achieving higher throughput than standard flooding.43

## **5\. Bandwidth Trade-offs: Wi-Fi Direct vs. Raw Bluetooth**

The query specifically asks to evaluate the trade-off between Nearby Connections' bandwidth upgrades
and raw Bluetooth control.

### **5.1 Nearby Connections: The Bandwidth Illusion**

While Nearby Connections boasts "high bandwidth" via Wi-Fi Direct (WFD), this is heavily restricted
by the Strategy.

* **P2P\_CLUSTER Limitations:** Documentation and developer experience confirm that P2P\_CLUSTER
  primarily uses Bluetooth (BLE/Classic) and LAN.2 It does *not* reliably upgrade to Wi-Fi Direct or
  Hotspot for mesh topologies because WFD is typically a Star topology (One Group Owner, multiple
  Clients).
* **The Trade-off:** To get WFD speeds, one must use P2P\_STAR or P2P\_POINT\_TO\_POINT, which
  sacrifices the mesh topology required for 30 nodes.3
* **Result:** For a 30-node cluster, Nearby Connections will likely fall back to Bluetooth speeds (
  \~kbps to low Mbps), negating the bandwidth advantage.44 Even if a WFD link is established, it is
  point-to-point. Bridging WFD groups (one device being a Client in Group A and Owner in Group B) is
  generally not supported by Android hardware due to single-radio limitations.45

### **5.2 Generic Bluetooth: Throughput Reality**

Raw Bluetooth mesh throughput is low.

* **Physical Layer (PHY):** BLE 5.0 offers a 2Mbps PHY. However, this "2x speed" is theoretical.
    * *Overhead:* The packet includes Preamble (1-2 bytes), Access Address (4 bytes), PDU Header (2
      bytes), CRC (3 bytes).
    * *IFS (Inter Frame Space):* A 150µs gap is required between packets.17
    * *Ack Overhead:* In reliable data transfer, every packet requires an ACK, doubling the
      transaction time.
* **Throughput Calculation:**
    * At 1 Mbps PHY, with optimal settings, application throughput is \~700 kbps.
    * At 2 Mbps PHY, application throughput maxes out around \~1.4 Mbps.17
    * *Mesh Penalty:* In a multi-hop network, throughput halves with every hop due to the
      half-duplex nature of the radio (listen, then transmit). For a 3-hop route in a 30-node mesh,
      throughput drops to $\\frac{1.4}{2^3} \\approx 175$ kbps.
* **Large Data:** Transferring files (images/video) over a 30-node BLE mesh is generally unfeasible.
  Standard BLE Mesh packet sizes are tiny (11–15 bytes payload per segment).47 A custom
  implementation can use L2CAP Connection Oriented Channels (CoC) for higher throughput (up to
  60-70% of PHY), but maintaining this across a mesh is computationally expensive and unstable.

#### **5.2.1 Energy Consumption Modeling**

Bandwidth usage correlates directly with energy consumption.

* **Nearby Connections:** Because P2P\_CLUSTER keeps the radio active for discovery and maintenance
  of the "Cluster," it drains battery significantly. The lack of "Low Power" configuration flags in
  the API exacerbates this.48
* **Generic BLE:** Allows for **Aggressive Duty Cycling**.
    * *Parameter Tuning:* A custom stack can set the Scan Interval (e.g., 100ms) and Scan Window (
      e.g., 10ms). This 10% duty cycle dramatically reduces power compared to the near-100% duty
      cycle of Nearby Connections in active discovery.49
    * *Connection Intervals:* Custom GATT connections can negotiate a long connection interval (
      e.g., 500ms) for idle nodes, waking up only for traffic. This is critical for the longevity of
      a 30-node mesh running on battery power.

**Verdict:** Neither solution supports high-bandwidth data (video/large files) across a 30-node mesh
efficiently. Nearby Connections is limited by strategy constraints, and Generic BLE is limited by
physics. The trade-off is between **Ease of Connectivity (Nearby Connections)** and **Topology
Control (Generic BLE)**, not necessarily bandwidth.

## **6\. Cross-Platform Interoperability (Android & iOS)**

This is the single most critical differentiator.

### **6.1 Nearby Connections: The iOS Wall**

Google's Nearby Connections API for iOS exists but is severely handicapped regarding offline P2P.

* **Supported Mediums:** iOS supports Wi-Fi LAN well. However, offline peer discovery via generic
  BLE is often marked as "unsupported" or "flagged off" in the library due to iOS CoreBluetooth
  restrictions and GATT instability.50
* **Interoperability:** True offline Android-to-iOS connection via Nearby Connections is currently
  considered "impossible" or highly unreliable by the developer community and Google's own issue
  trackers.50 The framework relies on technologies (like Wi-Fi Aware) that Apple does not expose to
  third parties.
* **API Misuse:** Developers attempting to force BLE scanning on iOS with Nearby Connections often
  encounter "API Misuse" warnings from CoreBluetooth because the Google library attempts to perform
  actions in the background that Apple strictly forbids or throttles.53

### **6.2 Generic Bluetooth: The Hard Road to Interop**

A custom BLE stack is the *only* viable path for offline Android-iOS interoperability, but it fights
against Apple's "Walled Garden."

* **Background Execution:** iOS aggressively terminates background tasks. To maintain a mesh, the
  app must declare bluetooth-central and bluetooth-peripheral background modes.54
* **State Restoration:** Engineers must implement CBCentralManager state restoration. When the OS
  kills the app to free memory, it keeps the Bluetooth connection alive. If data arrives, the OS
  wakes the app in the background. This logic is brittle and difficult to debug.29
    * *The Restoration ID:* You must assign a unique restoration identifier to the CBCentralManager.
    * *Lifecycle:* The app is relaunched into the background. The centralManager:willRestoreState:
      delegate method is called. The app must then "rehydrate" the object graph to handle the
      incoming event.
* **Advertising Limitations:** When an iOS app enters the background, the CBPeripheralManager
  modifies the advertisement packet.
    * *Local Name Stripped:* The "Local Name" is removed to save space and privacy.
    * *Service UUIDs:* Only these remain visible (and often moved to an "Overflow Area").
    * *Discovery Impact:* Android scanners scanning for a specific name will fail. They must scan
      for the specific Service UUID. This requires careful design of the discovery filter.57
* **GATT Differences:** Android and iOS handle MTU negotiation and connection intervals differently.
  A custom stack must handle these quirks to prevent "GATT Error 133" (Android) or connection
  timeouts.52

#### **6.2.1 Future Outlook: Nearby Interaction (UWB)**

While current interop relies on BLE, the **Nearby Interaction** framework (using Ultra Wideband /
U1/U2 chips) presents a theoretical future path.

* **Capabilities:** It offers precise direction and distance finding, far superior to BLE RSSI.58
* **Limitations:** Currently, it requires an active user session (foreground) for most interactions.
  Background execution for Nearby Interaction is limited and primarily intended for paired
  accessories, not generic P2P mesh nodes.59 Furthermore, it is not cross-platform standard; Android
  uses UWB but the API interoperability is not yet mature compared to BLE. Thus, for a deployed
  30-node system today, generic BLE remains the only choice.

## **7\. Security and Threat Modeling**

Implementing a 30-node mesh introduces significant security surface area.

### **7.1 Security in Nearby Connections**

* **The "Meaningless Encryption" Warning:** As noted in 13, Nearby Connections performs a handshake
  but does not inherently validate the identity of the peer physically. The "Authentication Token"
  provided is a random string that users are supposed to compare visually. In a 30-node automated
  mesh, visual comparison is impossible.
* **Man-in-the-Middle (MitM):** Without out-of-band verification (OOB), an attacker can interpose
  themselves between two merging islands, intercepting all traffic.
* **Privacy:** Nearby Connections broadcasts service IDs. While the API attempts to mask
  identifiers, researchers have shown that the "btname" (Bluetooth Name) can leak information.16

### **7.2 Security in Generic Bluetooth**

A custom implementation allows (and requires) a robust security layer.

* **The "Trash Can" Attack:** In a mesh, a node (e.g., a lightbulb or sensor) might be thrown away
  or stolen. If it retains the "Network Key" (NetKey), the attacker can access the mesh.
    * *Mitigation:* The Bluetooth Mesh standard defines a "Key Refresh Procedure." When a node is
      blacklisted, the Provisioner distributes new keys to all valid nodes. A custom stack must
      implement this logic to be secure.10
* **Secure Provisioning:** Using ECDH (Elliptic Curve Diffie-Hellman) during the initial connection
  ensures that the NetKey is never sent in cleartext over the air.
* **Replay Attacks:** A custom mesh protocol must implement "Sequence Numbers" or "Nonces" in the
  packet header. Nodes must track the last seen sequence number from every source and reject packets
  with lower or equal numbers. This is standard in the Bluetooth Mesh spec but must be manually
  coded in a custom generic implementation.20

## **8\. Case Studies and Reference Architectures**

Analyzing existing solutions provides a benchmark for the "Generic Bluetooth" effort estimate.

### **8.1 Bridgefy (SDK & App)**

Bridgefy is the most prominent example of a custom BLE mesh (managed flood) on mobile.

* **Architecture:** It uses a variation of the "Epidemic" routing protocol.
* **Lessons:** Bridgefy's evolution required shifting from pure BLE to a hybrid approach. It
  explicitly manages "Hops" and "TTL" based on the environment (High Density vs Sparse).21
* **Vulnerabilities:** Early versions were susceptible to "Decompression Bombs" and lacked Forward
  Secrecy, which they attempted to patch by adopting the Signal Protocol.61 This highlights that
  *crypto design* is a massive component of the "Generic Bluetooth" engineering effort.

### **8.2 Meshtastic (LoRa \-\> BLE Adaptation)**

Meshtastic uses LoRa but its mesh logic is relevant.

* **Role-Based Routing:** Meshtastic defines roles like CLIENT, CLIENT\_MUTE, and ROUTER.
    * *Relevance to 30-node BLE:* In a 30-node BLE cluster, having every node repeat every packet (
      pure flood) kills the network. Adopting Meshtastic's CLIENT\_MUTE role (where edge nodes only
      receive, never repeat) is a critical optimization for the custom BLE stack to survive.22
* **Packet Handling:** Meshtastic limits packet size and strictly manages "Airtime" to prevent
  collisions. A custom BLE stack must implement similar "Airtime Fairness" algorithms.

### **8.3 Berty Protocol (Weshnet)**

Berty represents the "state of the art" in secure, offline P2P.

* **Transport Agnosticism:** Berty treats BLE as just one "Driver."
* **Rendezvous Points:** Instead of flooding data, Berty floods *availability* (Rendezvous points)
  .23
* **Direct Transport:** It explicitly handles the Android/iOS divide by implementing separate
  drivers for Android Nearby and Apple Multipeer Connectivity (MPC), falling back to raw BLE only
  when necessary.62 This "Multi-Driver" approach confirms that a single "Generic BLE" stack is often
  insufficient for optimal performance, and a "Hybrid Stack" (Generic BLE \+ Nearby \+ MPC) is the
  ultimate (and most expensive) engineering solution.

## **9\. Technical Conclusions and Recommendations**

### **9.1 Summary of Trade-offs**

| Metric                  | Android Nearby Connections (P2P\_CLUSTER)                                             | Generic Bluetooth (Custom Mesh)                                     |
|:------------------------|:--------------------------------------------------------------------------------------|:--------------------------------------------------------------------|
| **Engineering Effort**  | Low (API-driven). 1-2 Developers.                                                     | Very High (Full stack). Team of 4-6 (Android/iOS/Crypto/Protocol).  |
| **30-Node Feasibility** | Low (Radio saturation, limited topology control). Risk of collapse \> 7 nodes.        | Medium (Feasible with optimized routing like Managed Flood/O-AODV). |
| **Island Joining**      | Manual application logic required; Opaque connection layer. Risk of broadcast storms. | High Complexity (CRDTs \+ Discovery), but fully controllable.       |
| **Cluster Repair**      | Reactive (Wait for disconnect callback). Slow.                                        | Proactive (RSSI monitoring, multipath). Fast.                       |
| **Bandwidth**           | Technically Low (\~kbps). Wi-Fi Direct blocked in Cluster.                            | Physically Low (\<100kbps mesh effective). Physics limitation.      |
| **Cross-Platform**      | **Non-functional offline.**                                                           | **Functional but difficult (State Restoration, Background Modes).** |
| **Security**            | High Risk (MitM, Metadata leaks).                                                     | High Effort (Must implement ECDH, Key Refresh manually).            |

### **9.2 Recommendations**

1. **For Android-Only Ecosystems:** If the project is strictly Android-based and can tolerate a
   lower node count (e.g., \<10 active simultaneous connections) or high latency, **Nearby
   Connections** is the superior choice due to rapid development speed. However, for 30 nodes, the
   P2P\_STAR strategy with time-sliced connections might be more stable than P2P\_CLUSTER, despite
   the topology restrictions.
2. **For Cross-Platform (iOS \+ Android) Requirements:** You **must** use a **Generic Bluetooth**
   implementation. Nearby Connections is not viable for offline iOS-Android communication. You will
   need to build a custom mesh protocol (or fork an open-source library like Berty or Meshtastic's
   logic adapted for BLE).22
3. **The "Island Joining" Strategy:** For the custom implementation, adopt a **hybrid discovery**
   approach.
    * Use **BLE Advertising** with a custom manufacturer data payload containing a "Cluster ID"
      and "Generation Count."
    * When a node detects a beacon with a different Cluster ID, it should initiate a **unicast
      connection** to the bridge node.
    * Use a **CRDT** (e.g., Merkle-DAG or Observed-Remove Set) to sync the routing tables/message
      logs between the two islands efficiently, minimizing data transfer.40
4. **Bandwidth Expectation Management:** Abandon the idea of "upgrading" to Wi-Fi Direct for a
   30-node mesh. The topology management overhead for WFD (Group Owner negotiation) is too high for
   dynamic, mobile islands. Design the application protocol to function within the constraints of
   BLE (\~20kbps application throughput per hop). If large files are necessary, implement a
   dedicated "side-channel" negotiation where two nodes temporarily switch to Wi-Fi Direct for a
   point-to-point transfer, then rejoin the mesh 16, rather than trying to run the whole mesh over
   Wi-Fi.
5. **Adopt a "Managed Flood" Protocol:** Do not attempt to implement source routing (like AODV) from
   scratch for a mobile 30-node cluster. The overhead of route maintenance in a mobile environment
   is too high. A Managed Flood (with TTL, Sequence Numbers, and "Mute" roles) provides the best
   balance of resilience and implementation complexity.

In conclusion, implementing a 30-node mesh is an advanced systems engineering challenge. Nearby
Connections abstracts too much control to be viable for this specific scale and topology,
particularly given the cross-platform requirement. A custom BLE stack, while resource-intensive to
build, provides the only path to a functioning, self-healing, cross-platform 30-node network.

#### **Works cited**

1. Strategies | Nearby Connections \- Google for Developers, accessed November 14,
   2025, [https://developers.google.com/nearby/connections/strategies](https://developers.google.com/nearby/connections/strategies)
2. Google Nearby Connections 2.0 capabilities \- Stack Overflow, accessed November 14,
   2025, [https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities](https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities)
3. Can we get high Bandwidth in Cluster strategy as we get in Star strategy of google nearby
   connections ? \- Stack Overflow, accessed November 14,
   2025, [https://stackoverflow.com/questions/51199414/can-we-get-high-bandwidth-in-cluster-strategy-as-we-get-in-star-strategy-of-goog](https://stackoverflow.com/questions/51199414/can-we-get-high-bandwidth-in-cluster-strategy-as-we-get-in-star-strategy-of-goog)
4. Bluetooth Low Energy: Full BLE FAQ \- Stormotion, accessed November 14,
   2025, [https://stormotion.io/blog/bluetooth-low-energy-faq-the-ultimate-guide-on-ble-devices-ibeacons/](https://stormotion.io/blog/bluetooth-low-energy-faq-the-ultimate-guide-on-ble-devices-ibeacons/)
5. Max simultaneous Bluetooth LE connections : r/LineageOS \- Reddit, accessed November 14,
   2025, [https://www.reddit.com/r/LineageOS/comments/9mpg92/max\_simultaneous\_bluetooth\_le\_connections/](https://www.reddit.com/r/LineageOS/comments/9mpg92/max_simultaneous_bluetooth_le_connections/)
6. Google Nearby Connections set limit on connection distance like Nearby Messages \- Stack
   Overflow, accessed November 14,
   2025, [https://stackoverflow.com/questions/54153355/google-nearby-connections-set-limit-on-connection-distance-like-nearby-messages](https://stackoverflow.com/questions/54153355/google-nearby-connections-set-limit-on-connection-distance-like-nearby-messages)
7. How many device can be connected simultaneously under BLE4.2 and BEL5.0?, accessed November 14,
   2025, [https://community.infineon.com/t5/PSOC-4/How-many-device-can-be-connected-simultaneously-under-BLE4-2-and-BEL5-0/td-p/119442](https://community.infineon.com/t5/PSOC-4/How-many-device-can-be-connected-simultaneously-under-BLE4-2-and-BEL5-0/td-p/119442)
8. Bluetooth Mesh Software Development Kit \- Silicon Labs, accessed November 14,
   2025, [https://www.silabs.com/software-and-tools/bluetooth-mesh](https://www.silabs.com/software-and-tools/bluetooth-mesh)
9. Optimization of the AODV-Based Packet Forwarding Mechanism for BLE Mesh Networks, accessed
   November 14,
   2025, [https://www.mdpi.com/2079-9292/10/18/2274](https://www.mdpi.com/2079-9292/10/18/2274)
10. Bluetooth Mesh Networking: The Ultimate Guide \- Novel Bits, accessed November 14,
    2025, [https://novelbits.io/bluetooth-mesh-networking-the-ultimate-guide/](https://novelbits.io/bluetooth-mesh-networking-the-ultimate-guide/)
11. Overview | Nearby Connections \- Google for Developers, accessed November 14,
    2025, [https://developers.google.com/nearby/connections/overview](https://developers.google.com/nearby/connections/overview)
12. Announcing Nearby Connections 2.0: fully offline, high bandwidth peer to peer device
    communication \- Android Developers Blog, accessed November 14,
    2025, [https://android-developers.googleblog.com/2017/07/announcing-nearby-connections-20-fully.html](https://android-developers.googleblog.com/2017/07/announcing-nearby-connections-20-fully.html)
13. Reversing, Analyzing, and Attacking Google's 'Nearby Connections' on Android \- University of
    Oxford Department of Computer Science, accessed November 14,
    2025, [https://www.cs.ox.ac.uk/files/10367/ndss19-paper367.pdf](https://www.cs.ox.ac.uk/files/10367/ndss19-paper367.pdf)
14. How can I force Google Nearby to use WiFi Direct? \- Stack Overflow, accessed November 14,
    2025, [https://stackoverflow.com/questions/48730753/how-can-i-force-google-nearby-to-use-wifi-direct](https://stackoverflow.com/questions/48730753/how-can-i-force-google-nearby-to-use-wifi-direct)
15. Android Nearby Connections cannot connect to device. Always returns 8011, accessed November 14,
    2025, [https://stackoverflow.com/questions/65334974/android-nearby-connections-cannot-connect-to-device-always-returns-8011](https://stackoverflow.com/questions/65334974/android-nearby-connections-cannot-connect-to-device-always-returns-8011)
16. Reversing, Analyzing, and Attacking Google's 'Nearby Connections' on Android \- Network and
    Distributed System Security (NDSS) Symposium, accessed November 14,
    2025, [https://www.ndss-symposium.org/wp-content/uploads/2019/02/ndss2019\_06B-3\_Antonioli\_paper.pdf](https://www.ndss-symposium.org/wp-content/uploads/2019/02/ndss2019_06B-3_Antonioli_paper.pdf)
17. Bluetooth 5 speed: How to achieve maximum throughput for your BLE application \- Novel Bits,
    accessed November 14,
    2025, [https://novelbits.io/bluetooth-5-speed-maximum-throughput/](https://novelbits.io/bluetooth-5-speed-maximum-throughput/)
18. Why Meshtastic Uses Managed Flood Routing, accessed November 14,
    2025, [https://meshtastic.org/blog/why-meshtastic-uses-managed-flood-routing/](https://meshtastic.org/blog/why-meshtastic-uses-managed-flood-routing/)
19. Zigbee; Mesh Routing; Flooding \- Pat Pannuto, accessed November 14,
    2025, [https://patpannuto.com/classes/2022/winter/cse291/WI22\_cse291-14-Zigbee\_Routing\_Flooding.pdf](https://patpannuto.com/classes/2022/winter/cse291/WI22_cse291-14-Zigbee_Routing_Flooding.pdf)
20. NordicSemiconductor/Android-nRF-Mesh-Library \- GitHub, accessed November 14,
    2025, [https://github.com/NordicSemiconductor/Android-nRF-Mesh-Library](https://github.com/NordicSemiconductor/Android-nRF-Mesh-Library)
21. bridgefy/sdk-ios \- GitHub, accessed November 14,
    2025, [https://github.com/bridgefy/sdk-ios](https://github.com/bridgefy/sdk-ios)
22. Configuration Tips | Meshtastic, accessed November 14,
    2025, [https://meshtastic.org/docs/configuration/tips/](https://meshtastic.org/docs/configuration/tips/)
23. Le protocole Berty, accessed November 14,
    2025, [https://berty.tech/fr/docs/protocol](https://berty.tech/fr/docs/protocol)
24. Berty Application \- HackMD, accessed November 14,
    2025, [https://hackmd.io/@go-AVqrxS2qXwAMt-WxK9g/H1hbZK73u](https://hackmd.io/@go-AVqrxS2qXwAMt-WxK9g/H1hbZK73u)
25. nRF Mesh mobile app \- Get started \- nordicsemi.com, accessed November 14,
    2025, [https://www.nordicsemi.com/Products/Development-tools/nRF-Mesh/GetStarted](https://www.nordicsemi.com/Products/Development-tools/nRF-Mesh/GetStarted)
26. Two-way communication without internet: Nearby Connections (Part 2 of 3\) | by Isai Damier |
    Android Developers | Medium, accessed November 14,
    2025, [https://medium.com/androiddevelopers/two-way-communication-without-internet-nearby-connections-b118530cb84d](https://medium.com/androiddevelopers/two-way-communication-without-internet-nearby-connections-b118530cb84d)
27. A Protocol for Smartphone Ad-Hoc Networks June 1, 2024 \- viktorstrate | qpqp.dk, accessed
    November 14, 2025, [https://qpqp.dk/master-thesis.pdf](https://qpqp.dk/master-thesis.pdf)
28. Bluetooth permissions | Connectivity \- Android Developers, accessed November 14,
    2025, [https://developer.android.com/develop/connectivity/bluetooth/bt-permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
29. Core Bluetooth Background Processing for iOS Apps \- Apple Developer, accessed November 14,
    2025, [https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth\_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
30. How to find out time complexity of mergesort implementation? \- Stack Overflow, accessed
    November 14,
    2025, [https://stackoverflow.com/questions/30577682/how-to-find-out-time-complexity-of-mergesort-implementation](https://stackoverflow.com/questions/30577682/how-to-find-out-time-complexity-of-mergesort-implementation)
31. Bridgefy: The Offline Messaging App Revolutionizing Crisis Communication Worldwide, accessed
    November 14,
    2025, [https://www.alchemistaccelerator.com/blog/bridgefy-the-offline-messaging-app-revolutionizing-crisis-communication-worldwide](https://www.alchemistaccelerator.com/blog/bridgefy-the-offline-messaging-app-revolutionizing-crisis-communication-worldwide)
32. Usage \- Bridgefy SDK, accessed November 14,
    2025, [https://docs.bridgefy.me/sdk/ios/usage](https://docs.bridgefy.me/sdk/ios/usage)
33. Dynamic Address Allocation Algorithm for Mobile Ad hoc Networks \- arXiv, accessed November 14,
    2025, [https://arxiv.org/pdf/1605.00398](https://arxiv.org/pdf/1605.00398)
34. MANETconf: configuration of hosts in a mobile ad hoc network \- SciSpace, accessed November 14,
    2025, [https://scispace.com/pdf/manetconf-configuration-of-hosts-in-a-mobile-ad-hoc-network-1zrv2oi4tm.pdf](https://scispace.com/pdf/manetconf-configuration-of-hosts-in-a-mobile-ad-hoc-network-1zrv2oi4tm.pdf)
35. Conflict-free replicated data type \- Wikipedia, accessed November 14,
    2025, [https://en.wikipedia.org/wiki/Conflict-free\_replicated\_data\_type](https://en.wikipedia.org/wiki/Conflict-free_replicated_data_type)
36. Understanding CRDTs: A Gentle Introduction (Chapter 1\) \- Federico Terzi, accessed November 14,
    2025, [https://federicoterzi.com/blog/understanding-crdts-a-gentle-introduction-chapter-1/](https://federicoterzi.com/blog/understanding-crdts-a-gentle-introduction-chapter-1/)
37. Berty \- information for the TM \- HackMD, accessed November 14,
    2025, [https://hackmd.io/@bUV35-XiTH-GKuaVoMuJCQ/rkMDwP8mD](https://hackmd.io/@bUV35-XiTH-GKuaVoMuJCQ/rkMDwP8mD)
38. Smartphone-based Frameworks and Protocols for Opportunistic Networking, accessed November 14,
    2025, [https://docserv.uni-duesseldorf.de/servlets/DerivateServlet/Derivate-51421](https://docserv.uni-duesseldorf.de/servlets/DerivateServlet/Derivate-51421)
39. Moby: A Blackout-Resistant Anonymity Network for Mobile Devices \- Privacy Enhancing
    Technologies Symposium, accessed November 14,
    2025, [https://petsymposium.org/popets/2022/popets-2022-0071.pdf](https://petsymposium.org/popets/2022/popets-2022-0071.pdf)
40. A CRDT for contact synchronization \- Dominik Winecki, accessed November 14,
    2025, [https://dominik.win/blog/crdt-contact-synchronization/](https://dominik.win/blog/crdt-contact-synchronization/)
41. Manage connections \- Nearby \- Google for Developers, accessed November 14,
    2025, [https://developers.google.com/nearby/connections/android/manage-connections](https://developers.google.com/nearby/connections/android/manage-connections)
42. RouMBLE: A Sink-Oriented Routing Protocol for BLE Mesh Networks \- IEEE Xplore, accessed
    November 14,
    2025, [https://ieeexplore.ieee.org/iel8/6488907/10993477/10819493.pdf](https://ieeexplore.ieee.org/iel8/6488907/10993477/10819493.pdf)
43. Efficient Flooding for Wireless Mesh Networks Jayashree Subramanian \- PDOS-MIT, accessed
    November 14,
    2025, [https://pdos.csail.mit.edu/\~jaya/uflood\_thesis.pdf](https://pdos.csail.mit.edu/~jaya/uflood_thesis.pdf)
44. is it possible to avoid using Wi-Fi Direct when communicating with Nearby Connections?, accessed
    November 14,
    2025, [https://stackoverflow.com/questions/46936453/is-it-possible-to-avoid-using-wi-fi-direct-when-communicating-with-nearby-connec](https://stackoverflow.com/questions/46936453/is-it-possible-to-avoid-using-wi-fi-direct-when-communicating-with-nearby-connec)
45. Create P2P connections with Wi-Fi Direct \- Android Developers, accessed November 14,
    2025, [https://developer.android.com/develop/connectivity/wifi/wifi-direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
46. Maximizing BLE Throughput On IOS And Android \- Punch Through, accessed November 14,
    2025, [https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/](https://punchthrough.com/maximizing-ble-throughput-on-ios-and-android/)
47. Bluetooth Mesh: Robust Communication for the next IoT system, accessed November 14,
    2025, [https://rd-datarespons.no/bluetooth-mesh-robust-communication-for-the-next-iot-system/](https://rd-datarespons.no/bluetooth-mesh-robust-communication-for-the-next-iot-system/)
48. Nearby Connections and foreground/background services \- Stack Overflow, accessed November 14,
    2025, [https://stackoverflow.com/questions/59156322/nearby-connections-and-foreground-background-services](https://stackoverflow.com/questions/59156322/nearby-connections-and-foreground-background-services)
49. The Bluetooth Mesh Standard: An Overview and Experimental Evaluation \- PMC, accessed November
    14,
    2025, [https://pmc.ncbi.nlm.nih.gov/articles/PMC6111614/](https://pmc.ncbi.nlm.nih.gov/articles/PMC6111614/)
50. Clarification on iOS/Android compatibility and future · google nearby · Discussion \#2447 \-
    GitHub, accessed November 14,
    2025, [https://github.com/google/nearby/discussions/2447](https://github.com/google/nearby/discussions/2447)
51. Google Nearby Connections API (iOS, Android) \- Stack Overflow, accessed November 14,
    2025, [https://stackoverflow.com/questions/77612062/google-nearby-connections-api-ios-android](https://stackoverflow.com/questions/77612062/google-nearby-connections-api-ios-android)
52. Clarification on iOS/Android compatibility and future · Issue \#1720 · google/nearby \- GitHub,
    accessed November 14,
    2025, [https://github.com/google/nearby/issues/1720](https://github.com/google/nearby/issues/1720)
53. IOS CoreBluetooth API Misuse with Google Nearby \- Stack Overflow, accessed November 14,
    2025, [https://stackoverflow.com/questions/44205717/ios-corebluetooth-api-misuse-with-google-nearby](https://stackoverflow.com/questions/44205717/ios-corebluetooth-api-misuse-with-google-nearby)
54. Configuring background execution modes | Apple Developer Documentation, accessed November 14,
    2025, [https://developer.apple.com/documentation/xcode/configuring-background-execution-modes](https://developer.apple.com/documentation/xcode/configuring-background-execution-modes)
55. \[Feature\]: iOS Core Bluetooth Background Processing support · Issue \#846 ·
    chipweinberger/flutter\_blue\_plus \- GitHub, accessed November 14,
    2025, [https://github.com/boskokg/flutter\_blue\_plus/issues/846](https://github.com/boskokg/flutter_blue_plus/issues/846)
56. Navigating iOS Bluetooth: Lessons on Background Processing, Pitfalls, and Personal Reflections |
    by Sanjay Nelagadde | Medium, accessed November 14,
    2025, [https://medium.com/@sanjaynelagadde1992/navigating-ios-bluetooth-lessons-on-background-processing-pitfalls-and-personal-reflections-5e5379a26e02](https://medium.com/@sanjaynelagadde1992/navigating-ios-bluetooth-lessons-on-background-processing-pitfalls-and-personal-reflections-5e5379a26e02)
57. Apple's IOS Core Bluetooth: The Ultimate Guide \- Punch Through, accessed November 14,
    2025, [https://punchthrough.com/core-bluetooth-guide/](https://punchthrough.com/core-bluetooth-guide/)
58. Nearby Interaction | Apple Developer Documentation, accessed November 14,
    2025, [https://developer.apple.com/documentation/nearbyinteraction](https://developer.apple.com/documentation/nearbyinteraction)
59. Nearby Interaction | Apple Developer Forums, accessed November 14,
    2025, [https://developer.apple.com/forums/tags/nearby-interaction](https://developer.apple.com/forums/tags/nearby-interaction)
60. Apple Nearby Interaction Framework: possibility to have room sensing accessories? \- Reddit,
    accessed November 14,
    2025, [https://www.reddit.com/r/HomeKit/comments/nvvk3s/apple\_nearby\_interaction\_framework\_possibility\_to/](https://www.reddit.com/r/HomeKit/comments/nvvk3s/apple_nearby_interaction_framework_possibility_to/)
61. Breaking Bridgefy, again \- ETH Zürich, accessed November 14,
    2025, [https://ethz.ch/content/dam/ethz/special-interest/infk/inst-infsec/appliedcrypto/education/theses/breaking-bridgefy-again.pdf](https://ethz.ch/content/dam/ethz/special-interest/infk/inst-infsec/appliedcrypto/education/theses/breaking-bridgefy-again.pdf)
62. Berty Protocol \- Berty Technologies, accessed November 14,
    2025, [https://berty.tech/ar/docs/protocol](https://berty.tech/ar/docs/protocol)
63. berty/weshnet: Async Mesh Network Protocol for Extreme Communication \-- Innovative, Resilient,
    and Decentralized \- GitHub, accessed November 14,
    2025, [https://github.com/berty/weshnet](https://github.com/berty/weshnet)
64. DSON: A delta-state CRDT for resilient peer-to-peer communication | by Helsing \- Medium,
    accessed November 14,
    2025, [https://medium.com/helsing/dson-a-delta-state-crdt-for-resilient-peer-to-peer-communication-7823349a042c](https://medium.com/helsing/dson-a-delta-state-crdt-for-resilient-peer-to-peer-communication-7823349a042c)