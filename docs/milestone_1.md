# KineticAssembly Milestone Track 1

This file replaces `docs/milestones.md` for the active plot-assembly track.
Older milestones remain useful as project history, but new work should be
recorded here.

## M24: Plot Chunk Assembly Foundation

Goal: move assemblies from debug `BlockDisplay` entities toward a plot chunk
route where block data, selection, rendering, lighting, and interaction all flow
through a plot-local bridge driven by a PhysX root transform.

### M24.1: Reserved Plot Metadata

Status: implemented.

- Allocate a stable plot id for each assembled assembly.
- Store reserved plot chunk coordinates, section Y, and chunk span.
- Expose plot metadata through assembly snapshots and command output.
- Register an empty mixin config so focused plot bridge hooks can be added
  without changing mod metadata later.

Acceptance: assembled assemblies report a plot coordinate such as
`plot-0@chunk=1000000,1000000,sectionY=4,span=1`.

### M24.2: Client Plot Mirror And Selection Outline

Status: implemented.

- Sync authoritative assembly snapshots to clients in the same dimension.
- Maintain a client mirror keyed by dimension id.
- Compute selected assembly block client-side by transforming the player view
  ray into each assembly's body-local space.
- Draw a transformed blue outline for the selected assembly block.

Known limitation: the outline can visibly lag or drift from the `BlockDisplay`
debug proxy because they currently use separate sync and interpolation paths.
M24.5 temporarily added a small visual pose delay to reduce the mismatch; M25.1
supersedes that by rendering assembly blocks and outlines from the same client
plot mirror.

### M24.3: Vanilla Attack Routing Prototype

Status: implemented.

- Intercept the client attack keymapping only when a client assembly selection
  exists.
- Cancel vanilla attack processing for that click so normal blocks and entities
  are not affected by the assembly action.
- Send a serverbound assembly action payload with the client-selected assembly
  id and local block position as a hint.
- Treat the client hint as non-authoritative. The server recomputes the player
  eye ray against authoritative assemblies and only breaks the block when the
  server-selected assembly id and local position match the client hint exactly.
- Keep the action narrow: left-click break only, no right-click use/place, no
  breaking progress overlay, and no block entity routing.

Acceptance: after assembling a visual assembly, a player can point at a
blue-outlined assembly block and left-click to remove that block from the
server-side assembly storage and its debug visual.

### M24.4: Whole Assembly Lifecycle Consolidation

Status: implemented.

- Treat `assemble_box` as one physicalize-region operation: capture source
  blocks, remove them from the host world, create one mechanics body, allocate a
  reserved plot, and register one authoritative `PhysicsAssembly`.
- Add explicit lifecycle state:
  - `CAPTURED`: block data and mechanics body have been created, but the manager
    has not activated the assembly yet.
  - `ACTIVE`: normal tick/sync/query state.
  - `DIRTY`: assembly-local block storage has changed and downstream collider,
    renderer, or delta-sync work may need to catch up.
  - `REMOVING`: the assembly is being torn down and should not be treated as a
    stable gameplay target.
- Move reserved plot allocation into `AssemblyPlotAllocator`.
- Move capture/physicalize rollback logic into `AssemblyAssembler`.
- Move `BlockDisplay` rendering into `AssemblyDebugVisuals` and explicitly
  treat it as debug/prototype visual state, not the final plot renderer.
- Include lifecycle state in snapshots and client sync entries.
- Keep existing commands, blue outline selection, and left-click break behavior
  compatible.

Acceptance: the current user-facing behavior remains intact, but the code path
now reflects the intended whole-assembly model instead of growing all logic
inside `AssemblyManager`.

## Recommended Next Slices

### M24.5: Pose Sync And Block Delta Sync

Status: implemented.

- Split the current full snapshot sync into:
  - a frequent pose sync for root body transforms and lifecycle state;
  - an infrequent full snapshot for resync;
  - block delta sync for assembly-local mutations.
- Keep selection using the client mirror state. M25 removed the old visual pose
  delay because assembly blocks and outlines now render from the same mirror.
- Preserve server-authoritative action validation.

Acceptance: moving assemblies keep client-side selection/outline updated through
lightweight pose packets, block break actions update the mirror through block
delta packets, and full snapshot sync is no longer the normal every-tick pose
transport.

### M24.6: New-Client Snapshot Bootstrap

Status: implemented.

- Send a full assembly snapshot when a player joins, respawns, or changes
  dimension.
- Keep periodic full sync as a fallback resync path, not the primary transport.
- Ensure empty levels clear stale client mirrors.
- On dimension change, send an empty snapshot for the old dimension before
  bootstrapping the new dimension.

### M24.7: Interaction Reliability And Feedback

Status: implemented through M26.1 and M26.2.

- Add a short server-to-client result payload for accepted/rejected assembly
  actions.
- Surface a minimal debug counter for input-routed breaks, rejected actions, and
  stale client hints.
- Add transient client outline feedback for pending/accepted/rejected actions.

### M24.8: Breaking Overlay Prototype

Status: implemented as M26.3.

- Track client-side breaking progress for the selected assembly target.
- Render a transformed breaking overlay at the selected plot-local block bounds.
- Keep actual mutation server-authoritative.

### M24.9: Right-Click Routing Prototype

Status: implemented as M27.1 for routing/validation only.

- Route use/place only after left-click break behavior is stable.
- Start with simple use forwarding for non-block-entity blocks.
- Defer inventories, block entities, redstone, and neighbor update routing until
  the plot bridge has clearer ownership rules.

### M25: Plot Renderer And Lighting

Goal: replace `BlockDisplay` debug visuals with a client plot-section render
path. Render blocks, selection outlines, and later breaking overlays from the
same client mirror pose so they cannot disagree visually.

### M25.1: Mirror-Driven Block Renderer

Status: implemented.

- Render assembly blocks on the client directly from `ClientAssemblyMirror`.
- Use vanilla `BlockRenderDispatcher#renderSingleBlock` for the first prototype
  instead of building a custom chunk mesh cache immediately.
- Render the blue selection outline from the same renderer and same mirror pose.
- Refresh client selection during the render pass so the outline does not keep a
  target pose captured on the previous client tick.
- Use the current mirror pose directly now that `BlockDisplay` is no longer the
  primary visual path.
- Sample host-world light at each transformed block origin as a temporary
  bounded lighting approximation.
- Make `block detach` and `assembly assemble_*` default to `debugProxy=false`;
  explicit `debugProxy=true` still keeps the old `BlockDisplay` debug path
  available for comparison.

Acceptance: newly assembled assemblies render without `BlockDisplay` debug
proxies, and selected-block outlines are drawn from the same pose source as the
visible blocks.

### M25.2: Renderer Cost Control

Status: implemented.

- Add basic frustum and 192-block distance culling before rendering assembly
  blocks.
- Compute a transformed world AABB per assembly from its mirror block bounds.
- Track rendered assembly/block counts plus distance/frustum/empty cull counts.
- Expose the latest client-side renderer counters through:

```text
/kinetic_assembly_client assembly_renderer_status
```

- Keep a conservative immediate `renderSingleBlock` path before introducing
  mesh caching.

### M25.3: Plot Mesh Cache

Status: implemented as a conservative CPU-side render cache.

- Cache each client's renderable assembly block list and local bounds per
  assembly.
- Keep pose sync separate from cache rebuilds: moving/rotating a assembly only
  changes the root transform used at draw time.
- Rebuild the cache when the mirrored block list changes through full snapshot
  sync or block delta sync.
- Remove cache entries when the client mirror clears a level or removes a
  assembly.
- Report cache size and last-frame rebuild count through:

```text
/kinetic_assembly_client assembly_renderer_status
```

Note: this is the first plot-cache layer. It avoids repeated per-frame block
filtering and bounds aggregation, but it intentionally keeps vanilla
`renderSingleBlock` drawing. A real baked/VBO mesh cache should come after the
rendering contract is stable.

### M25.4: Lighting Bridge

Status: implemented as a bounded host-world light cache.

- Replace direct per-frame light queries with a client-side cache capped at
  4096 samples per level.
- Keep samples for up to 20 game ticks before refreshing, so moving assemblies
  can reuse nearby host-world lighting without holding stale values
  indefinitely.
- Define the prototype sampling rule: each render block samples light at the
  center of its body-local collision bounds after applying the current assembly
  root transform into host-world coordinates.
- Clear light cache entries when the client mirror clears a level.
- Report light cache counters through:

```text
/kinetic_assembly_client assembly_renderer_status
```

Current scope: this is a bounded sampling bridge, not a full plot light engine.
Full chunk-cache/light-engine mixins should remain opt-in until the renderer and
interaction contract are stable.

### M26: Interaction Reliability

Goal: make assembly input routing observable and robust before expanding into
breaking overlays, right-click use/place routing, block entities, and redstone.

### M26.1: Server Action Result Feedback

Status: implemented.

- Add a clientbound assembly action result payload for accepted/rejected
  actions.
- Return explicit reject reasons for spectator players, dimension mismatch,
  server pick mismatch, and server-side errors.
- Keep break mutation server-authoritative: the client hint is still validated
  against the server's current eye ray before accepting.
- Track client-side sent/accepted/rejected counters plus the latest result.
- Expose the counters through:

```text
/kinetic_assembly_client assembly_action_status
```

Current scope: this is diagnostic feedback only. It does not yet show chat
messages, breaking progress, or retry/cooldown UI.

### M26.2: Client Action Feedback Overlay

Status: implemented.

- Track the latest client action hint target and transient feedback state.
- Color the selected assembly outline based on action state:
  - blue: normal selection;
  - yellow: action sent and awaiting server result;
  - green: accepted result flash;
  - red: rejected result flash.
- Keep feedback local and non-authoritative. The server result still comes from
  M26.1's action result payload.
- Extend `/kinetic_assembly_client assembly_action_status` with the latest client hint and
  transient feedback state.

Current scope: this is a lightweight visual acknowledgement. It is not yet the
vanilla-style cracking/breaking progress overlay.

### M26.3: Breaking Overlay Prototype

Status: implemented.

- Track a local selected-target breaking progress while the attack key is held.
- Render a transformed orange progress overlay at the selected assembly block's
  body-local bounds using the same assembly pose as the block renderer and
  outline.
- Reset or decay the progress when the target changes or the attack key is
  released.
- Keep mutation server-authoritative. The overlay does not decide whether a
  block is actually removed.

Current scope: this is an overlay prototype built from transformed line boxes.
It is intentionally not yet Minecraft's vanilla cracking texture pipeline.

### M27: Right-Click Routing

Goal: route non-breaking assembly interactions through the same client hint and
server revalidation contract before introducing block entities, inventories, or
redstone.

### M27.1: Use Block Routing Skeleton

Status: implemented.

- Add `USE_BLOCK` to the assembly action payload.
- Send `USE_BLOCK` when the client right-clicks while targeting a assembly
  block.
- Server-side handling re-picks the current eye ray and only accepts when the
  target id and local position match the client hint.
- Return the same action result payload used by break actions.
- Do not yet call block `use`, open inventories, place blocks, dispatch
  neighbor updates, or route redstone.

Current scope: this is a routing/validation skeleton only. It should be treated
as a contract test before gameplay behavior is added.

### M28: Vanilla Plot Bridge

Goal: pivot the plot-assembly path toward a plot-backed model: blocks live at
reserved plot coordinates, and vanilla Minecraft code sees those coordinates as
normal blocks wherever possible. Custom mirror rendering and action routing stay
as prototype fallbacks until the vanilla bridge owns the full path.

### M28.1: Vanilla Pick, Block Query, And Highlight Bridge

Status: implemented.

- Keep the existing PhysX body, assembly storage, client mirror, and plot
  metadata.
- Treat the reserved plot block position as the canonical vanilla-facing block
  coordinate for assembly blocks.
- Add client/server `Level#getBlockState` and `Level#getFluidState` mixins so
  plot block coordinates can resolve to assembly block data without backing
  chunks yet.
- Return AIR for empty positions inside an allocated plot so vanilla shape and
  neighbor queries do not fall through into real far-away chunks.
- Add a client `Entity#pick` mixin so the vanilla crosshair hit result becomes a
  `BlockHitResult` against the plot block position when a assembly block is
  closer than the normal world hit.
- Add a client `LevelRenderer#renderHitOutline` redirect that transforms
  vanilla block highlight rendering from plot-local coordinates through the
  current assembly root pose.
- Let `ClientAssemblySelection` prefer the vanilla `minecraft.hitResult` when
  it targets a plot block, falling back to the older mirror raycast only when
  the vanilla path has no assembly hit.

Current scope: this intentionally does not remove the prototype mirror block
renderer, action feedback outline, breaking overlay, or custom action packets.
The purpose is to move the selection/highlight read path into Minecraft's normal
query flow first, then migrate chunk rendering and interaction mutation onto the
same plot bridge.

### M28.2: Vanilla Break And Simple Use Bridge

Status: implemented.

- Stop intercepting attack/use key presses on the client for assembly targets;
  vanilla `Minecraft` input now sees the plot `BlockHitResult` from M28.1 and
  sends normal block action/use packets.
- Remove the old client-to-server `AssemblyActionPayload` registration, the
  action feedback status command, and the custom client selection/break overlay
  path. Runtime interaction is now forced through vanilla packets plus mixins.
- Extend `/kinetic_assembly assembly status` with `vanillaBreakActions`,
  `vanillaUseActions`, accepted/rejected counters, and `plotBlockWrites` so the
  active bridge path can be verified in game.
- Add a server `ServerPlayerGameMode#handleBlockBreakAction` mixin for plot
  blocks. It validates that the player is still near the transformed plot block
  before removing a block from `AssemblyManager`.
- Add a server `ServerGamePacketListenerImpl#handleUseItemOn` mixin for plot
  blocks. It bypasses vanilla's distance check against far-away reserved plot
  coordinates, then forwards simple right-click use through
  `ServerPlayerGameMode#useItemOn` after transformed-center reach validation.
- Replace server-side re-raycast validation with a transformed-center reach
  check. Vanilla packets already identify the plot block, and the extra raycast
  was too sensitive to client/server PhysX pose skew.
- Add client/server `Level#setBlock` bridges for existing plot blocks so simple
  vanilla state mutations such as lever/button toggles can write back into the
  assembly section instead of touching the reserved far-away chunk.
- Add client/server plot sound remapping for `Level#playSound(Player, BlockPos,
  ...)`, so simple block-use sounds play at the transformed physical block
  location.
- Suppress vanilla neighbor updates at plot positions for now. This avoids
  accidental reserved-chunk traversal before redstone/neighborhood semantics
  have an explicit plot bridge.
- Add client effect remapping for vanilla hit particles, hit sounds, break
  sounds, break particles, and simple plot-space particles emitted by vanilla
  block client code.
- Keep block placement into empty plot cells, block entities, inventories,
  drops, redstone neighbor semantics, and real chunk render/light ownership out
  of this step.

Acceptance: breaking a selected assembly block uses vanilla attack packets and
produces visible block hit/break particles and sounds at the rendered physical
position. Simple existing-block state changes can be attempted through the
vanilla use path, while complex block entity/redstone behavior remains a later
phase.

### M28.3: Compound Collider And Vanilla Destroy Alignment

Status: implemented.

- Replace the original single aggregate PhysX box for assembled assemblies with a
  dynamic compound box body: each captured block collision box becomes one
  body-local PhysX box shape attached to the same `PxRigidDynamic`.
- Keep the physics object type as `DYNAMIC_BOX` at the scene layer for now so
  existing terrain queueing, status counts, and mechanics commands continue to
  see assemblies as active dynamic bodies.
- Add a mechanics-level `DYNAMIC_COMPOUND_BOX` body type for snapshots/debug
  output so compound assembly bodies can be distinguished from simple debug
  boxes.
- Rebuild the assembly mechanics body after plot block removal or replacement.
  The rebuild preserves the previous root pose and linear velocity, swaps the
  `bodyId`, then removes the old body.
- Route server-side plot block breaking through vanilla `Level#destroyBlock`
  instead of directly calling `AssemblyManager#breakPlotBlock`. Vanilla now owns
  destroy effects, drops, game events, and the final `setBlock` mutation; the
  plot `Level#setBlock` bridge writes that mutation back into assembly storage.
- Redirect server `levelEvent` calls for plot blocks to the block's transformed
  physical world position, so vanilla break effects are emitted near the moving
  assembly rather than at the far-away reserved plot coordinate.
- Bridge `Player#canInteractWithBlock` for plot blocks on both server and
  client. Vanilla container `stillValid` checks now measure distance to the
  transformed physical block position instead of the reserved plot coordinate,
  which keeps simple menus such as the crafting table from closing immediately.

Current scope: this fixes the physical topology root cause for non-solid
multi-block assemblies and moves break mutation back onto a vanilla path. It does
not yet solve the renderer seam problem; the client still draws each block with
the immediate `renderSingleBlock` path. General block entities, inventories,
redstone, and robust complex-topology gameplay semantics remain later
plot-bridge work.

Acceptance: assembled multi-block assemblies collide as a compound of their
individual collision boxes, block edits update the PhysX collider, breaking a
plot block uses vanilla destroy side effects while still mutating assembly
storage instead of the reserved chunk, and simple vanilla menus no longer fail
their distance check just because their canonical position is a reserved plot
coordinate.

### M28.4: VoxelShape Box Decomposition And Merge

Status: implemented.

- Store each assembly block's decomposed collision boxes from
  `VoxelShape#toAabbs()` in addition to the aggregate `localCollisionBounds`.
- Keep `localCollisionBounds` as the compatibility/visual aggregate used by
  outline, lighting samples, and existing renderer code.
- Generate PhysX compound colliders from the decomposed body-local boxes rather
  than from one aggregate box per block.
- Add a conservative aligned-AABB greedy merge before PhysX body creation.
  Boxes with matching intervals on two axes and touching/overlapping intervals
  on the third axis are merged into one larger cuboid.
- Cap merged compound collision boxes at 512 per assembly body.
- Sync per-block collision box lists to clients and bump the payload protocol
  version to avoid old-client packet mismatch.
- Use decomposed boxes for server and client assembly block picking, while
  still using aggregate bounds for coarse visual feedback.

Current scope: this improves collision fidelity and reduces shape count where
adjacent boxes can be merged. It intentionally does not split disconnected
assembly parts into separate rigid bodies yet.

Next fracture step: after any block mutation, compute connected components over
the remaining collision graph. If the graph has more than one component, keep
one component on the existing assembly id/body and spawn additional assemblies
for the detached components, each with its own body, plot metadata, client sync,
and inherited velocity/pose.

### M28.5: Assembly-Local Batched Block Rendering

Status: implemented.

- Replace the primary assembly block draw path from isolated
  `BlockRenderDispatcher#renderSingleBlock` calls to vanilla
  `BlockRenderDispatcher#renderBatched`.
- Add a client-side `BlockAndTintGetter` view over the mirrored assembly block
  list. The view resolves block states and fluids by assembly-local
  `BlockPos`, so vanilla block rendering can see adjacent assembly blocks.
- Render model blocks with `checkSides=true`, allowing vanilla
  `Block#shouldRenderFace` to cull internal faces between adjacent assembly
  blocks.
- Keep non-model render shapes on the old `renderSingleBlock` fallback path.
- Keep existing transformed root pose, light sampling, render distance culling,
  and render-cache bookkeeping.

Current scope: this is a logical section-render bridge, not a persistent baked
chunk VBO yet. It should remove the most visible "separate block entity" feel
and internal-face artifacts, but a full chunk-section mesh cache is
still the later target for performance and exact vanilla chunk rendering
semantics.
