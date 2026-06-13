# Assembly Interaction Contract

M23 promotes assemblies from detached visual assemblies into a gameplay/tick
boundary. Two requirements are now architectural constraints:

1. A player that participates in this physics world is represented by a PhysX
   object. PhysX owns the player's physical pose and Minecraft synchronizes the
   player from the PhysX result.
2. A assembly behaves like a chunk section inside the host world. The host
   world's server tick is also the assembly tick; assemblies do not run an
   independent scheduler.

## Terms

- Host level: the `ServerLevel` that owns the assembly.
- Assembly plot: a reserved plot chunk/section block store using plot-local and
  section-local coordinates. M23 starts with one 16x16x16 section and leaves
  multi-section assemblies for later.
- Assembly root: the PhysX body/root transform that maps assembly-local
  coordinates into host-world coordinates.
- Player body: the PhysX actor bound to a Minecraft player UUID.
- World bridge: the adapter layer that translates Minecraft queries and events
  between host-world coordinates and assembly-local coordinates.

## Tick Contract

Assemblies are driven by the normal server tick of their host level:

1. Collect player inputs and Minecraft interaction intents.
2. Apply queued assembly block changes and neighbor events.
3. Rebuild or update dirty assembly colliders within the tick budget.
4. Step the PhysX scene once from the host world tick.
5. Synchronize PhysX-owned player poses and assembly visual/debug proxies.
6. Dispatch deferred block/entity side effects with bounded work.

Terrain queues, collider rebuild queues, and visual synchronization can be
budgeted across ticks, but they still belong to the host world's tick order.

## Player Physics Ownership

The first player prototype should treat the PhysX player body as authoritative
for position, velocity, and collision response. Minecraft input should become
intent applied to the player body, such as impulses, target velocity constraints,
or controller forces. Minecraft should not be the owner of the final player
position while the PhysX body is active.

The likely initial shape is a capsule rather than a block AABB. The first slice
should stay narrow:

- single-player or single-test-player ownership;
- ground movement only;
- no mounts, swimming, elytra, portals, or special vanilla movement modes;
- post-step synchronization from PhysX body pose to Minecraft player pose;
- clear fallback when native physics is unavailable.

Vanilla behavior that depends on player movement, such as fall damage,
knockback, step height, ladders, fluids, and network interpolation, must be
reintroduced explicitly after the ownership rule is proven.

## Assembly As Plot Chunk

The final assembly storage model should be a plot chunk model. A assembly is not
just a rendered moving mesh; it is a reserved chunk/section-like block space in
the host level with a PhysX root transform. The first implementation can remain
small, but its identifiers and storage shape should be compatible with this
target:

- reserved plot id and plot chunk coordinates;
- chunk/section-local block positions;
- chunk-like block state lookup and mutation;
- collision shape queries in local coordinates;
- dirty section and dirty shape tracking;
- bounded block tick and neighbor update queues;
- a local-to-world transform supplied by the assembly root.

The safe implementation path is still staged: build the plot-compatible
storage/query API first, then route selected Minecraft operations into it. The
important constraint is that the staging path must converge on chunk cache,
render section, lighting, raycast, and block interaction routing rather than a
parallel non-Minecraft visual model.

## Interaction Boundary

Cross-boundary interaction should be explicit and budgeted:

- Player to assembly: raycasts and block interactions are transformed from
  world coordinates into assembly-local coordinates, then routed to assembly
  storage.
- Assembly to world: the assembly root collides with host terrain through PhysX,
  while block events that affect the host level pass through the world bridge.
- World entity to assembly: physical collision should be handled by PhysX first;
  gameplay interactions should start with a whitelist.
- Assembly block entity behavior: delayed until ordinary block state queries,
  neighbor updates, and player interaction routing are stable.

BlockDisplay visuals remain debug/render proxies. They are not the authority for
assembly block state, collision, or tick behavior.

## Compatibility And Performance

Mixins should be introduced only where the bridge needs Minecraft behavior that
cannot be reached through public APIs. The likely seams are player movement
application, collision/raycast queries, and block interaction routing.

M23 should avoid per-block entity ownership for normal assembly content. The
authoritative data is the section store plus dirty collider state. Debug visuals
can remain optional and should keep their own synchronization budget.

## Client Presentation Boundary

The server-side assembly store is not enough to support the intended
moving-assembly behavior. A final assembly must also have a client presentation
layer that behaves like plot chunk content:

- selected assembly blocks need a transformed block outline;
- rendered blocks need Minecraft-style model lighting instead of entity/display
  lighting;
- breaking overlays, particles, and later block entity rendering should target
  plot-local positions;
- `BlockDisplay` visuals are only a debug/prototype adapter.

The implementation should first create a client plot mirror for assembly block
states, then add focused mixins for hit outline, render section integration,
lighting, breaking overlay, and vanilla input routing.

## M23 Slices

- M23.1: lock this interaction contract and update the milestone plan.
- M23.2: add a section-local assembly storage API with dirty block tracking.
- M23.3: add a PhysX-owned player body prototype and post-step player pose sync.
  Implemented as a command-gated dynamic-box prototype; full character
  controller behavior remains later work.
- M23.4: add a vanilla player bridge through a PhysX player proxy. This becomes
  the normal-gameplay path; M23.3 remains an experimental PhysX-owned agent
  mode.
- M23.4.1: add a read-only player view ray query into assembly-local block
  space.
- M23.4.2: add command-gated assembly-local block removal through the query
  bridge.
- M23.4.3: document the client presentation target: plot-chunk
  routing, transformed selection outline, render sections, and Minecraft-style
  lighting.
- M23.4.x: route player use/place intents into assembly-local coordinates for a
  small whitelist.
- M24: implement the plot-chunk assembly foundation before depending on vanilla
  input mixins for normal gameplay.
- M23.5: rebuild dirty assembly colliders from section storage, still with
  conservative shape scope.
- M23.6: add bounded cross-boundary event dispatch and diagnostics.
