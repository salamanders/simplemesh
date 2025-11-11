# **Architecting a Large-Scale (30+ Node) Multi-Hop Mesh Network on Android's Strategy.P2P\_CLUSTER:
An Implementation and Gap Analysis**

## **Introduction: The Core Architectural Challenge: 1-Hop Primitives vs. N-Hop Ambition**

The Android Nearby Connections API, specifically with Strategy.P2P\_CLUSTER, presents a compelling
proposition: an M-to-N, "amorphous cluster" topology for flexible, mesh-like experiences.1 This
suggests an ideal foundation for a robust, 30+ device network operating offline. However, a
significant gap exists between this documented "promise" and the practical "reality" of
implementation at scale.

The central challenge is a critical, often-overlooked constraint: when Strategy.P2P\_CLUSTER is used
in a true peer-to-peer scenario without a shared Wi-Fi router, it defaults to using **only the
Bluetooth medium**.3 This reliance on mobile Bluetooth chipsets, which are optimized for low power,
imposes a severe *practical* limitation of **3 to 4 simultaneous connections per device**.3 This
limit is not theoretical; it is a hard constraint reported by API engineers, and it is further
diminished by any other active Bluetooth connections, such as smartwatches or headphones.3

Therefore, the user's objective of a 30+ node connected network is *impossible* to achieve with a "
flat" or "fully-connected" topology. Each node can only be directly connected to a small fraction of
the total network.

This limitation fundamentally reframes the problem. The task is no longer one of simple API *usage*,
but one of complex *distributed system design*. A robust 30+ node network *must* be a **multi-hop
mesh network**, and this architecture must be built entirely at the application layer, on top of the
API's 1-hop primitives.3

The Nearby Connections API, in this context, provides only the "physical layer" (radio management)
and "link layer" (1-hop peer discovery and an encrypted data socket).6 It **does not provide** the
critical "network layer" or "transport layer" for a mesh. The application is 100% responsible for
implementing:

1. **Topology Management:** Intelligently deciding *which* 3-4 peers to connect to (out of 29+
   possibilities) to form a single, healthy graph and avoid "islands".5
2. **Multi-Hop Routing:** A protocol to send messages to peers that are N-hops away, forwarding data
   through intermediate nodes.5
3. **Mesh Healing:** A distributed algorithm to detect and repair the network topology when nodes
   join, leave, or connections fail.

The following table summarizes this fundamental "gap" between the API's capabilities and the
requirements of a large-scale mesh.

**Table: Gap Analysis: Nearby Connections API Primitives vs. Robust Mesh Requirements**

| Feature             | Provided by Strategy.P2P\_CLUSTER API                                                              | Required for 30+ Node Mesh (The Application-Layer Gap)                                                                                                                                          |
|:--------------------|:---------------------------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Peer Discovery**  | onEndpointFound / onEndpointLost. Provides 1-hop "radio" visibility only.7                         | **Full Network Topology Discovery:** An application-layer protocol (e.g., gossip) for nodes to share their 1-hop neighbor lists to build a map of the entire N-hop graph.                       |
| **Connection**      | requestConnection(). Provides an encrypted 1-hop socket to a single peer.8                         | **Topology Management Algorithm:** A distributed algorithm to decide *which* 3-4 peers to connect to (out of 29+ visible) to form a single, healthy, connected graph, avoiding "islands".5      |
| **Data Transfer**   | sendPayload(). Sends a Payload (BYTES, FILE, or STREAM) to an *already-connected* 1-hop neighbor.9 | **Multi-Hop Routing Protocol:** An application-layer routing protocol (e.g., flooding with TTL, or source-routing tables) to forward payloads to N-hop-away nodes.5                             |
| **Disconnection**   | onDisconnected(String endpointId). Fired on *clean* (graceful) disconnections.8                    | **Connection Liveness Protocol:** An application-layer (e.g., PING/PONG) heartbeat to rapidly detect "zombie" or "stale" connections 10 that onDisconnected will *not* catch.                   |
| **Retry Logic**     | Provides error codes (e.g., STATUS\_RADIO\_ERROR) on connection failure.13                         | **Resilient Retry Strategy:** An implemented exponential backoff with jitter algorithm to manage connection retries without causing network storms, especially during initial mesh formation.16 |
| **Mesh Resilience** | None. A lost node is simply disconnected.8                                                         | **Mesh Healing Protocol:** An automated, distributed process for the network to detect and repair its topology in response to node churn or "graph partitions".18                               |

---

## **Part 1: The Foundational Layer: Basic State Management and Liveness**

Before attempting the advanced mesh architecture, the 1-hop connection primitives provided by the
API must be implemented with absolute robustness. A failure at this foundational layer will make any
higher-level mesh logic impossible to stabilize.

### **1.1 The Connection Lifecycle: From Advertising to STATUS\_OK**

The Nearby Connections API defines a clear, multi-phase lifecycle for establishing a 1-hop link.

Phase 1: Advertising & Discovery  
A connection requires two roles: an advertiser and a discoverer. For a P2P\_CLUSTER mesh, nodes will
typically need to perform both roles simultaneously.1 Both the startAdvertising() and
startDiscovery() calls must be configured with the exact same Strategy.P2P\_CLUSTER object; a
mismatch will prevent discovery.21  
Phase 2: Endpoint Discovery  
When a discoverer detects an advertiser, the EndpointDiscoveryCallback is triggered 7:

* **onEndpointFound(String endpointId, DiscoveredEndpointInfo info):** This is the entry point for a
  new potential connection.8 A naive implementation might immediately call requestConnection().8
  However, in a 30-node network, this leads to a connection flood. A robust implementation *must
  not* connect immediately. It should instead add the endpointId and info to a "known peers" data
  structure for later, intelligent processing by the Topology Manager (see Part 2).
* **onEndpointLost(String endpointId):** This callback fires when a previously discovered peer is no
  longer advertising or has moved out of radio range.8 This is a simple state-cleanup trigger, and
  the endpointId should be removed from the "known peers" list.

Phase 3: Connection Handshake  
Once a node decides to connect (via requestConnection()), the API initiates a symmetric handshake
managed by the ConnectionLifecycleCallback.7

* **onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo):** This method is the
  most critical part of the handshake. It is called on *both* the advertiser and the discoverer.8
  This callback serves two purposes:
    1. **Authentication:** The connectionInfo.getAuthenticationToken() provides a short string for
       verifying the peer's identity.8 In a 30+ node mesh, automatically accepting all connections (
       as seen in basic examples 8) is a severe security and stability risk. A robust implementation
       *must* use this token to perform a challenge-response or shared-secret check to ensure the
       peer is part of the mesh and not a malicious or malfunctioning device.
    2. **Acceptance/Rejection:** Both sides must *independently* call acceptConnection() or
       rejectConnection().8 The connection is only established if *both* sides accept.

Phase 4: Connection Result  
The final state of the handshake is delivered to onConnectionResult:

* **onConnectionResult(String endpointId, ConnectionResolution result):** This callback confirms the
  outcome.8 The application's state machine must react to result.getStatus().getStatusCode():
    * ConnectionsStatusCodes.STATUS\_OK: The connection is fully established and encrypted. Data can
      now be sent via sendPayload(). The endpointId is now "live" and should be added to the
      topology manager's list of active 1-hop neighbors.8
    * ConnectionsStatusCodes.STATUS\_CONNECTION\_REJECTED: One or both sides rejected the
      connection.8 This is a clean, permanent failure for this attempt.
    * ConnectionsStatusCodes.STATUS\_ERROR: The connection broke *before* it could be accepted.8
      This is a common, transient failure that should trigger the retry logic.

### **1.2 Robust Error Handling and Disconnection**

A stable mesh is built on a correct and nuanced understanding of the API's failure modes.

The Unreliability of onDisconnected  
The onDisconnected(String endpointId) callback 8 is a common source of confusion. Developers expect
it to fire for all connection failures. It does not. This callback is typically triggered only by a
graceful shutdown, such as when the remote app calls disconnectFromEndpoint() or the connection is
cleanly terminated.  
It will *not* reliably or quickly fire in common, real-world failure modes, such as:

* A remote device's battery dying.
* A remote device's operating system or application crashing.
* A user physically walking out of Bluetooth range (a "hard" disconnect).22

In these scenarios, the local device's networking stack (e.g., TCP or Bluetooth) has no immediate
signal that the peer is gone.11 It will keep the socket open, potentially for many minutes, waiting
for acknowledgments that will never arrive. This creates a **"zombie connection"**.10

The consequence for the mesh is catastrophic: the local node believes it has a valid connection,
wasting one of its 3-4 precious connection slots on a dead endpoint. As these "zombie" connections
accumulate, the node's capacity to participate in the mesh degrades, leading to network partitions.

Interpreting Critical ConnectionsStatusCodes  
Because onDisconnected is unreliable for hard failures, the application must actively monitor other
error signals from the ConnectionsStatusCodes.15

* **STATUS\_RADIO\_ERROR (8007) / STATUS\_BLUETOOTH\_ERROR (Deprecated):** This is not a transient
  error. It indicates the device's radio (Bluetooth) is in a "bad state".13 According to Google
  engineers, this can be caused by the high-stress operations of discovery and connection,
  especially on older Android versions.5 A simple retry will *not* fix this and may make it worse.
  The *only* reliable recovery is to stop all Nearby operations (stopAdvertising, stopDiscovery,
  disconnectFromAllEndpoints), wait, and potentially toggle the Bluetooth radio.5
* **STATUS\_ENDPOINT\_IO\_ERROR (8012):** This error occurs when a sendPayload() call fails.13 This
  is a *reliable signal* that the socket is broken and the connection is dead, even if
  onDisconnected has not fired. This error should be treated as a hard disconnect and trigger
  cleanup and mesh-healing logic.
* **STATUS\_ALREADY\_CONNECTED\_TO\_ENDPOINT (8003):** This is a *logic error* in the application.13
  It means requestConnection was called on an endpoint that is already connected or in a pending
  state. The application's state machine must prevent this.
* **STATUS\_ERROR (13):** This is a generic, catch-all error.14 It is most-often returned in
  onConnectionResult when something went wrong during the handshake.13 This is typically a
  *transient* failure and is the primary candidate for retry logic.

### **1.3 Implementing Resilient Connection Retry Logic**

Given the transient nature of wireless connections, a robust retry mechanism is not optional. A
naive retry loop (e.g., onFailure { requestConnection() }) will create a "connection storm," further
destabilizing the network and likely triggering STATUS\_RADIO\_ERROR.13

The required solution is **Exponential Backoff with Jitter**, a standard algorithm for distributed
systems.16

The algorithm is as follows:

1. Define a BASE\_DELAY (e.g., 500 milliseconds) and a MAX\_RETRIES (e.g., 5).
2. When a retryable failure occurs (e.g., STATUS\_ERROR on onConnectionResult), schedule a retry
   attempt.
3. The delay for retry\_attempt\_n (where n is 0-indexed) is calculated
   as: $delay \= (BASE\\\_DELAY \\times 2^n) \+ random\\\_jitter$.
4. The "jitter" (a small random value, e.g., 0-100ms) is *critical*. It prevents multiple devices
   that failed at the same time from retrying at the *exact same millisecond*, which would overload
   the network (a "thundering herd" problem).16

This backoff strategy must be *contextual*:

* **On STATUS\_ERROR:** Use exponential backoff.
* **On STATUS\_RADIO\_ERROR:** Do *not* retry. Escalate to the full radio-reset procedure.
* **On STATUS\_CONNECTION\_REJECTED:** Do *not* retry. This was a deliberate rejection.

### **1.4 Implementing the Mandatory Liveness Protocol (Heartbeats)**

As established in 1.2, onDisconnected is insufficient for detecting zombie connections. The
application *must* implement its own application-layer liveness protocol, or "heartbeat," to
actively monitor the health of its 1-hop links.12

A robust heartbeat protocol can be designed as follows:

1. **State:** For each active connection, the node maintains a lastPongReceivedTimestamp (
   initialized to System.currentTimeMillis() upon connection) and a isPongPending boolean.
2. **PING Task:** A repeating TimerTask or Handler runs every HEARTBEAT\_INTERVAL (e.g., 5 seconds).
    * It checks isPongPending. If true, it means a PONG was not received from the *previous*
      interval. This indicates a potential problem.
    * It sends a small, unique Payload.Type.BYTES message: new byte { 0x01 } (a PING).
    * It sets isPongPending \= true.
3. **PONG Response:** When a node receives a PING payload, it *immediately* replies with new byte {
   0x02 } (a PONG).
4. **PONG Reception:** When a node receives a PONG payload:
    * It updates lastPongReceivedTimestamp \= System.currentTimeMillis().
    * It sets isPongPending \= false.
5. **Monitoring Task:** A separate monitoring task runs every HEARTBEAT\_TIMEOUT (e.g., 15 seconds,
   or 3x the interval).
    * It checks if (System.currentTimeMillis() \- lastPongReceivedTimestamp) \> HEARTBEAT\_TIMEOUT.
    * If the time elapsed is greater than the timeout, the connection is declared **dead**.
6. **Action on Failure:** When the monitoring task declares a connection dead, the application
   *must* manually call disconnectFromEndpoint(dead\_endpoint\_id). This forcefully closes the
   socket, cleans up resources, and—most importantly—**frees the connection slot**. This failure is
   the primary trigger for the "Mesh Healing" protocol (see Part 2).

---

## **Part 2: The Missing Architecture: Advanced Strategies for a 30+ Node Mesh**

The foundational layer (Part 1\) provides stable 1-hop links. This section details the
application-layer distributed system that must be built *on top* of those links to create a
functional 30+ node multi-hop mesh.

### **2.1 The Central Problem: The 3-4 Connection Limit**

The 3-4 connection limit 3 is the single most important constraint and dictates the entire
architecture. A 30-node network has 435 potential 1-to-1
connections ($C(30, 2\) \= (30 \\times 29\) / 2$). A single node can only maintain 3-4 of them.

This means the primary job of a robust mesh application is not "how to connect" but "how to *not*
connect." The application's core logic must be a distributed algorithm that intelligently *rejects*
25+ potential peers and *selects* only the 3-4 that contribute to a single, healthy, unified graph.
This is the problem of **Topology Management**.

### **2.2 Topology Management: Architecting the Connection Graph**

The goal is to build and maintain a single, connected graph that includes all 30 nodes and has no "
islands" (partitions).5

Phase 1: Mitigating the "Connection Flood" (Initial Joining)  
If all 30 devices are turned on simultaneously, they will all startAdvertising and startDiscovery.
Each will see 29 peers. If all 30 attempt to connect to the first 4 they see, the result is a
network-wide "connection flood," leading to a storm of requestConnection calls, rejections, and
STATUS\_RADIO\_ERROR failures.  
A **Staggered Joining Protocol** is required to manage this initial "cold start".17 A
leader-election model is a common solution:

1. **Leader Election:** The first node to launch (or a node with the "lowest" endpointId) elects
   itself the "Root." This Root node is the *only* node that startAdvertising().
2. **Discoverers:** All other 29+ nodes *only* startDiscovery(). They *do not* advertise.
3. **Joining:** Discoverers see the single "Root" advertiser and requestConnection. The Root accepts
   them *sequentially* (not in parallel, to avoid radio errors) until its 3-4 connection slots are
   full.
4. **Delegation:** When the Root (Node L) is full, it *promotes* one of its connected children (Node
   C). It sends an application-layer message: "You are now a SUB\_LEADER. startAdvertising()."
5. **Tree Formation:** New nodes now see two advertisers (L and C). They will connect to C (the one
   with free slots). This process repeats, building an initial "spanning tree" topology 28 in an
   orderly fashion.

Phase 2: Distributed Topology Discovery (Building the Map)  
The initial spanning tree is a good start, but it's fragile (e.g., if a SUB\_LEADER fails, its
entire branch is orphaned). A more resilient, decentralized ("amorphous") topology is needed. To
achieve this, every node needs a "map" of the entire network, not just its 1-hop neighbors.  
A **Gossip Protocol** is the standard solution for this 30:

1. **Node State:** Each node (Node A) maintains its local 1-hop neighbor list (e.g., \`\`). It also
   maintains a map of the entire network graph it knows about: Map\<String, List\<String\>\>
   networkGraph.
2. **Initial State:** Node A's map starts as {"A":}.
3. **Gossip Message:** Periodically (e.g., every 30 seconds), Node A serializes its *entire*
   networkGraph map and sends it as a Payload to its 3-4 1-hop neighbors.
4. **Merge Logic:** When Node B receives Node A's map, it *merges* it with its own. It iterates
   through all entries, adding any new nodes or connections it didn't know about.
5. **Propagation:** On its next cycle, Node B gossips this *new, larger map* to *its* neighbors.
6. **Convergence:** After a few cycles of this "gossip," the complete network topology will
   propagate, and *every node in the 30-node mesh will have an identical map of the entire graph*.

This "gossiped map" is the foundational data structure that enables all other advanced features. It
is the "logic to avoid forming islands" that the Google engineer on Stack Overflow alluded to.5

Phase 3: Connection Slot Management (Using the Map)  
With a complete graph map, a node can now make intelligent decisions. When a node has a free
connection slot (e.g., its connection to B failed, or it has only 2/4 slots filled), it must not
connect to a random peer from onEndpointFound.  
Instead, it consults its **gossiped map** and runs an algorithm to answer the question: "Which
available peer, if I connect to it, would most improve the network's health?"

* It can run a Breadth-First Search (BFS) on its map to find all connected nodes.
* It can compare this to the list of "known peers" from onEndpointFound.
* If it finds a peer that is *not* in its connected graph, this peer is part of an "island."
  Connecting to it would *bridge a partition*. This becomes the highest-priority connection.
* If all peers are already in the graph, it can choose to connect to a peer that would reduce the "
  average path length" or add redundancy to a critical "bridge" node.

This is a massive leap in robustness from a basic "connect-to-first-seen" implementation.

### **2.3 The Routing Protocol: Forwarding Payloads Across Multiple Hops**

The API's sendPayload() method only works for 1-hop, directly-connected peers. It provides *no
mechanism* for sending a message to a node 5 hops away. As noted by a Google engineer, the
application *must* build its own protocol for "flooding the network for broadcasts or hoping between
nodes for directed messages".5

This requires a custom application-layer routing protocol. The first step is to define a custom
MeshPayload envelope that wraps all application data. This object is serialized (e.g., to JSON or
protobuf) and sent as the Payload.Type.BYTES.

Java

class MeshPayload {  
String messageId; // Unique ID (e.g., UUID) for this payload  
String sourceEndpointId; // Original sender  
String destEndpointId; // Final destination ("BROADCAST" or a specific ID)  
int ttl; // Time-to-Live, to prevent infinite loops  
byte data; // The actual application data  
}

With this envelope, two routing strategies can be built.

Strategy 1: Broadcast (Flooding) 5  
This is used to send a message to all 30 nodes.

1. Node A wants to broadcast. It creates a MeshPayload with a new messageId, destEndpointId \= "
   BROADCAST", and ttl \= 10 (a value larger than the expected network diameter).
2. Each node must maintain a Set\<String\> seenMessageIds (with a time-based eviction).
3. Node A adds messageId to its seenMessageIds set and sends the MeshPayload to its 3-4 1-hop
   neighbors.
4. Node B receives this payload. It checks its seenMessageIds set.
5. **If messageId is in the set:** The node has seen this message before. It **drops the payload**.
   This is the critical step that prevents infinite loops.
6. **If messageId is NOT in the set:** This is a new message. The node adds messageId to its set. It
   processes the data. It then decrements the ttl. If ttl \> 0, it **forwards the *exact same*
   MeshPayload** to its *other* 1-hop neighbors (i.e., all neighbors *except* the one it came from).

Strategy 2: Unicast (Directed "Hopping") 5  
This is used to send a message from Node A to a specific, non-adjacent Node Z.

1. **Pathfinding:** Node A wants to send to Node Z. It consults its **gossiped network map** (from
   2.2).
2. It runs a local pathfinding algorithm (e.g., BFS or Dijkstra's) on the map to find the shortest
   path of endpointIds. For example: path \= \["C", "F", "K", "Z"\].
3. **Source Routing:** Node A uses "source routing." It serializes this path *into* the
   MeshPayload (e.g., as a new List\<String\> route field).
4. Node A sends the payload to the *first hop* in the list: Node C.
5. Node C receives the payload. It sees destEndpointId is not for it. It inspects the route field.
   It "consumes" its own ID from the front of the list, leaving a new path: \["F", "K", "Z"\].
6. It forwards the modified MeshPayload to the *new* first hop: Node F.
7. This process repeats—Node F forwards to K, and K forwards to Z. When Node Z receives the payload,
   it sees the route list is empty (or contains only itself) and that destEndpointId matches its own
   ID, so it processes the message.

This source-routing approach is robust because it relies on the globally-known (gossiped) map and
does not require complex, dynamic routing tables on each node.

### **2.4 Mesh Healing: Handling Node Churn and Graph Partitions**

Node churn (devices leaving, joining, or crashing) is the *normal state* of a 30-node mobile mesh.18
The network must be self-healing.

**Trigger 1: Local Failure (Heartbeat Failure)**

* **Detection:** As described in 1.4, the application-layer heartbeat protocol detects that the
  connection to Node B is dead. The Liveness Manager calls disconnectFromEndpoint(B).
* **Healing:** Node A has now lost a neighbor and has a free connection slot.
* **Action:** The **Connection Slot Manager** (from 2.2) is activated. It consults its gossiped map,
  re-runs its connection-priority algorithm, and identifies the *next best peer* to connect to (
  e.g., a peer that bridges a partition or adds redundancy). It then initiates a requestConnection
  to this new peer, automatically "healing" the hole left by Node B.

**Trigger 2: Global Failure (Graph Partition)**

* **The Problem:** This is the most dangerous failure mode. The network physically splits into two
  15-node sub-graphs (e.g., "upstairs" and "downstairs" groups walk apart).20
* **The "Split-Brain":** Both 15-node sub-graphs are *internally* healthy. Their heartbeats work.
  Their gossip protocols work. Their routing works *within their own partition*. They have *no idea*
  the other 15 nodes exist. The local healing logic (Trigger 1\) will *never* fix this, as there are
  no "foreign" nodes to see.
* **The Solution: "Periodic Re-Discovery" Healer**
* **Algorithm:**
    1. A robust node *never* permanently stops discovery.
    2. A background "Healer Service" is implemented. Every 5 minutes (or on a randomized,
       non-deterministic timer), the node startDiscovery() for a short duration (e.g., 15 seconds).
    3. It listens to onEndpointFound for all peers within radio range.
    4. For each endpointId found, it asks a simple question: **"Is this endpointId already in my
       networkGraph map?"**
    5. **If YES:** This is a known peer from its own partition. It does nothing.
    6. **If NO:** This is a "foreign" node. It belongs to a *different, isolated partition* that has
       just come into radio range.
    7. The node *immediately* requestConnection to this foreign endpointId, treating it as a
       high-priority "bridge" connection.
    8. Once this connection is established, the two partitions are now linked by this one "bridge."
       The *next* gossip cycle (from 2.2) will flow across this bridge, merging the two 15-node maps
       into one 30-node map. The network is healed.

## **Conclusion: Bridging the Gap: Summary of Required Architectures and Final Recommendations**

The analysis confirms that while Strategy.P2P\_CLUSTER is the correct strategy for a mesh-like
topology 1, it is *not* a mesh network out-of-the-box. The API provides only the 1-hop connection
primitives, constrained by a practical 3-4 connection limit.3 The 30+ node, multi-hop mesh is a
complex distributed system that the application must build *entirely* on top of this foundation.

Simple topologies, such as the "snake-like" connection (where each node connects to at-most two
others) 5, are feasible and simpler to implement but are extremely fragile. A single node-loss
breaks the entire chain. A robust 30-node network requires a dynamic, decentralized topology managed
by the sophisticated algorithms detailed in this report.

A successful, robust implementation will require the design, implementation, and rigorous testing of
the following *six* distinct, co-dependent, application-layer managers:

1. **The Connection Manager:** Implements the core API callbacks (onConnectionInitiated,
   onConnectionResult), manages authentication, and handles retries using **exponential backoff with
   jitter**.16
2. **The Liveness Manager:** Implements the **application-layer PING/PONG heartbeat** protocol to
   detect and cull "zombie" connections, freeing scarce connection slots.12
3. **The Topology Manager:** Implements the **gossip protocol** to build and maintain a complete,
   real-time map of the 30-node network graph.31
4. **The Connection Slot Manager:** A sub-component of the Topology Manager. It implements the
   *connection-decision logic* that uses the gossiped map to *intelligently choose* which 3-4 peers
   to connect to, prioritizing graph health and actively avoiding "islands".5
5. **The Routing Engine:** Implements the MeshPayload wrapper and the logic for both **network-wide
   flooding (broadcast)** and **source-routing (unicast)** to forward data across multiple hops.5
6. **The Healing Service:** Implements the *two* distinct healing strategies: (1) **local repair** (
   triggered by heartbeat failure) and (2) **global repair** (triggered by the periodic re-discovery
   of "foreign" nodes) to fix graph partitions.20

This architecture represents a high-complexity, high-risk, but achievable engineering project. The
work involved is substantial, akin to building a custom, lightweight, mobile networking stack from
the link-layer up. If the 30+ node, multi-hop, and fully-offline requirements are all *hard
constraints*, this architecture is the necessary and viable path.

#### **Works cited**

1. Strategies | Nearby Connections \- Google for Developers, accessed November 11,
   2025, [https://developers.google.com/nearby/connections/strategies](https://developers.google.com/nearby/connections/strategies)
2. Strategy | Google Play services, accessed November 11,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy)
3. android \- Google Nearby Connections 2.0 capabilities \- Stack Overflow, accessed November 11,
   2025, [https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities](https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities)
4. max connected Endpoints with Nearby-Connections \- Stack Overflow, accessed November 11,
   2025, [https://stackoverflow.com/questions/47928656/max-connected-endpoints-with-nearby-connections](https://stackoverflow.com/questions/47928656/max-connected-endpoints-with-nearby-connections)
5. android \- Multi peer connection using Google Nearby Connection ..., accessed November 11,
   2025, [https://stackoverflow.com/questions/51177985/multi-peer-connection-using-google-nearby-connection](https://stackoverflow.com/questions/51177985/multi-peer-connection-using-google-nearby-connection)
6. Overview | Nearby Connections \- Google for Developers, accessed November 11,
   2025, [https://developers.google.com/nearby/connections/overview](https://developers.google.com/nearby/connections/overview)
7. Nearby API pre-connection phase \- InnovationM Blog, accessed November 11,
   2025, [https://www.innovationm.com/blog/nearby-api-pre-connection-phase/](https://www.innovationm.com/blog/nearby-api-pre-connection-phase/)
8. Manage connections | Nearby Connections | Google for Developers, accessed November 11,
   2025, [https://developers.google.com/nearby/connections/android/manage-connections](https://developers.google.com/nearby/connections/android/manage-connections)
9. Google Nearby connections \- Not able to transfer large bytes between 2 devices, accessed
   November 11,
   2025, [https://stackoverflow.com/questions/50548446/google-nearby-connections-not-able-to-transfer-large-bytes-between-2-devices](https://stackoverflow.com/questions/50548446/google-nearby-connections-not-able-to-transfer-large-bytes-between-2-devices)
10. How gRPC Keepalive Solved Our Zombie Connections Mystery \- Medium, accessed November 11,
    2025, [https://medium.com/freshworks-engineering-blog/how-grpc-keepalive-solved-our-zombie-connections-mystery-f4f626c8a9f2](https://medium.com/freshworks-engineering-blog/how-grpc-keepalive-solved-our-zombie-connections-mystery-f4f626c8a9f2)
11. why many libraries does not detect dead TCP connections? \- Stack Overflow, accessed November
    11,
    2025, [https://stackoverflow.com/questions/41978922/why-many-libraries-does-not-detect-dead-tcp-connections](https://stackoverflow.com/questions/41978922/why-many-libraries-does-not-detect-dead-tcp-connections)
12. Detecting Dead TCP Connections with Heartbeats and TCP Keepalives \- RabbitMQ, accessed November
    11, 2025, [https://www.rabbitmq.com/docs/heartbeats](https://www.rabbitmq.com/docs/heartbeats)
13. Error codes in Nearby Connections 2.0 \- android \- Stack Overflow, accessed November 11,
    2025, [https://stackoverflow.com/questions/46036191/error-codes-in-nearby-connections-2-0](https://stackoverflow.com/questions/46036191/error-codes-in-nearby-connections-2-0)
14. ConnectionsClient | Google Play services, accessed November 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient)
15. ConnectionsStatusCodes | Google Play services | Google for ..., accessed November 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsStatusCodes](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsStatusCodes)
16. Managing Retry Logic with Exponential Backoff | by Lince Mathew \- Medium, accessed November 11,
    2025, [https://medium.com/@linz07m/managing-retry-logic-with-exponential-backoff-44d370e38df8](https://medium.com/@linz07m/managing-retry-logic-with-exponential-backoff-44d370e38df8)
17. Best practices for large-scale IoT deployments \- Azure IoT Hub Device Provisioning Service,
    accessed November 11,
    2025, [https://learn.microsoft.com/en-us/azure/iot-dps/concepts-deploy-at-scale](https://learn.microsoft.com/en-us/azure/iot-dps/concepts-deploy-at-scale)
18. Peer-to-peer networks under churn \- ETH Zürich, accessed November 11,
    2025, [https://pub.tik.ee.ethz.ch/students/2023-HS/SA-2023-15.pdf](https://pub.tik.ee.ethz.ch/students/2023-HS/SA-2023-15.pdf)
19. Handling Churn in a DHT \- USENIX, accessed November 11,
    2025, [https://www.usenix.org/legacyurl/handling-churn-dht](https://www.usenix.org/legacyurl/handling-churn-dht)
20. The Role of Community Detection Methods in Performance Variations of Graph Mining Tasks \-
    arXiv, accessed November 11,
    2025, [https://arxiv.org/html/2509.09045v1](https://arxiv.org/html/2509.09045v1)
21. Nearby Connections for Android: Getting Started \- Kodeco, accessed November 11,
    2025, [https://www.kodeco.com/35461793-nearby-connections-for-android-getting-started](https://www.kodeco.com/35461793-nearby-connections-for-android-getting-started)
22. Nearby Connections 2.0: Successful connection, immediately followed by disconnection, accessed
    November 11,
    2025, [https://stackoverflow.com/questions/46533735/nearby-connections-2-0-successful-connection-immediately-followed-by-disconnec](https://stackoverflow.com/questions/46533735/nearby-connections-2-0-successful-connection-immediately-followed-by-disconnec)
23. How to kill windows zombie tcp connections? \- Super User, accessed November 11,
    2025, [https://superuser.com/questions/67039/how-to-kill-windows-zombie-tcp-connections](https://superuser.com/questions/67039/how-to-kill-windows-zombie-tcp-connections)
24. Exponential backoff \- Wikipedia, accessed November 11,
    2025, [https://en.wikipedia.org/wiki/Exponential\_backoff](https://en.wikipedia.org/wiki/Exponential_backoff)
25. Mastering Exponential Backoff in Distributed Systems | Better Stack Community, accessed November
    11,
    2025, [https://betterstack.com/community/guides/monitoring/exponential-backoff/](https://betterstack.com/community/guides/monitoring/exponential-backoff/)
26. Implement retries with exponential backoff \- .NET \- Microsoft Learn, accessed November 11,
    2025, [https://learn.microsoft.com/en-us/dotnet/architecture/microservices/implement-resilient-applications/implement-retries-exponential-backoff](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/implement-resilient-applications/implement-retries-exponential-backoff)
27. Short lived stale zombie connection after recovery with short NetworkRecoveryInterval, accessed
    November 11,
    2025, [https://groups.google.com/g/rabbitmq-users/c/7AZz4Nr0\_Rk](https://groups.google.com/g/rabbitmq-users/c/7AZz4Nr0_Rk)
28. The Power of Local Optimization: Approximation Algorithms for Maximum-Leaf Spanning Tree \- CMU
    Contributed Webserver, accessed November 11,
    2025, [https://www.contrib.andrew.cmu.edu/\~ravi/allerton.pdf](https://www.contrib.andrew.cmu.edu/~ravi/allerton.pdf)
29. A Minimum Spanning Tree algorithm for efficient P2P video streaming system, accessed November
    11,
    2025, [https://www.researchgate.net/publication/224128389\_A\_Minimum\_Spanning\_Tree\_algorithm\_for\_efficient\_P2P\_video\_streaming\_system](https://www.researchgate.net/publication/224128389_A_Minimum_Spanning_Tree_algorithm_for_efficient_P2P_video_streaming_system)
30. A weakly coupled adaptive gossip protocol for application level active networks \- SciSpace,
    accessed November 11,
    2025, [https://scispace.com/pdf/a-weakly-coupled-adaptive-gossip-protocol-for-application-2zoz82l07x.pdf](https://scispace.com/pdf/a-weakly-coupled-adaptive-gossip-protocol-for-application-2zoz82l07x.pdf)
31. Random Gossip Processes in Smartphone Peer-to-Peer Networks, accessed November 11,
    2025, [https://par.nsf.gov/servlets/purl/10201367](https://par.nsf.gov/servlets/purl/10201367)
32. Random Gossip Processes in Smartphone Peer-to-Peer Networks \- Georgetown University, accessed
    November 11,
    2025, [https://people.cs.georgetown.edu/\~cnewport/pubs/randomGossip.pdf](https://people.cs.georgetown.edu/~cnewport/pubs/randomGossip.pdf)
33. Gossip in a Smartphone Peer-to-Peer Network \- Georgetown University, accessed November 11,
    2025, [https://people.cs.georgetown.edu/\~cnewport/pubs/gossipmobile-full.pdf](https://people.cs.georgetown.edu/~cnewport/pubs/gossipmobile-full.pdf)
34. zssz/BerkananSDK: Bluetooth mesh messaging SDK for apps \- GitHub, accessed November 11,
    2025, [https://github.com/zssz/BerkananSDK](https://github.com/zssz/BerkananSDK)
35. java \- Google Nearby Connection: How to build up a Mesh Network ..., accessed November 11,
    2025, [https://stackoverflow.com/questions/56872930/google-nearby-connection-how-to-build-up-a-mesh-network-android-app](https://stackoverflow.com/questions/56872930/google-nearby-connection-how-to-build-up-a-mesh-network-android-app)