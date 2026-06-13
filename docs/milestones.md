# KineticAssembly Milestones

This roadmap starts with a narrow end-to-end slice before expanding into full
Minecraft collision integration.

## M1: Native Minimal Loop

Goal: prove the native PhysX bridge works without Minecraft.

- Create a PhysX world.
- Create a ground plane.
- Create a dynamic box.
- Step the simulation for a fixed number of ticks.
- Read the body pose back into Java.

Likely JNI surface:

```text
nativeCreateBoxShape
nativeCreateStaticPlane
nativeCreateStaticBody
nativeCreateDynamicBody
nativeGetBodyPose
nativeSetBodyPose
nativeSetLinearVelocity
nativeDestroyBody
```

Acceptance: a Java-side test or command can step a box until it falls and rests
on a plane.

Status: implemented with `./gradlew nativeSmokeTest`; the smoke test creates a
static plane and a dynamic box, steps 240 frames, and verifies the box settles
near `y=0.5`.

## M2: Java API Lifecycle

Goal: make the Java physics API usable and explicit about ownership.

- Wire `api/` interfaces to the PhysX backend implementation.
- Define handle ownership for worlds, shapes, materials, and bodies.
- Make every `close()` implementation idempotent.
- Decide whether shapes are shared or owned by bodies.
- Ensure failed native creation paths release partially-created resources.

Acceptance: Java code can create and destroy worlds, shapes, and bodies without
leaking handles or double-freeing native objects.

Status: implemented for the current PhysX world/body/shape scope. Lifecycle
rules are documented in `docs/lifecycle.md` and covered by
`./gradlew nativeSmokeTest`.

## M2.5: Scene/Object Layer

Goal: add a lightweight middle layer between Minecraft and the physics backend,
with a clear scene/object boundary before adopting a full plot-grid system.

- Introduce a server-side physics scene per Minecraft level.
- Track physics objects by stable IDs instead of exposing native handles to
  Minecraft integration code.
- Keep pose synchronization, body lifecycle, and debug metadata outside the raw
  PhysX backend.
- Keep this layer smaller than a full assembly model until moving block structures
  require isolated chunk grids.
- Reserve full assembly-style concepts for later moving block assemblies,
  serialization, network interpolation, and client rendering.

Acceptance: Minecraft-facing code can create, tick, query, and remove physics
objects through a scene/object API without touching `PhysXBody` or native
handles directly.

Status: implemented as a lightweight `minecraft/scene` package. Design notes are
in `docs/scene-layer.md` and behavior is covered by `./gradlew nativeSmokeTest`.

## M3: Minecraft Server Tick Integration

Goal: run PhysX from the server tick without client rendering or block collision.

- Create one `PhysicsWorld` per `ServerLevel`.
- Step each world from server tick using a fixed timestep.
- Add basic commands:
  - `/kinetic_assembly spawn_box`
  - `/kinetic_assembly physics_status`
  - `/kinetic_assembly clear`

Acceptance: a command creates a physics body, server ticks step it, and logs or
command output show its pose changing.

Status: implemented for server-side scenes and commands. `ServerTickEvent.Post`
steps the runtime, `ServerStoppedEvent` closes all scenes, and
`RegisterCommandsEvent` registers:

```text
/kinetic_assembly spawn_box
/kinetic_assembly physics_status
/kinetic_assembly clear
```

Run `tools/native/build-linux.sh` before `./gradlew runServer` so the dev run can
load `build/native/linux-x86_64/libkinetic_assembly_native.so`.

## M4: Entity Synchronization

Goal: make a visible Minecraft entity follow a PhysX body.

- Pick a simple test entity, such as an armor stand or a custom debug entity.
- Attach a PhysX dynamic body to the entity.
- Drive entity position from the PhysX pose on the server.
- Keep networking and rendering minimal.

Acceptance: a visible entity moves according to PhysX simulation.

Status: initially implemented with a visible `ArmorStand` debug entity, then
superseded by M11's `BlockDisplay` render proxy. `/kinetic_assembly spawn_box` creates a
dynamic PhysX box and binds a visible Minecraft proxy to its pose; server ticks
update the proxy after physics simulation. Design notes are in
`docs/entity-sync.md`.

## M5: Static Block Collision

Goal: introduce Minecraft world collision in a controlled way.

- Start with simple solid block AABBs.
- Build static collision when chunks load.
- Release static collision when chunks unload.
- Avoid rebuilding chunk collision every tick.
- Later evaluate batched meshes for dense terrain.

Acceptance: a dynamic test body collides with nearby solid blocks.

Status: superseded by M8's chunk-owned build queue. The M5 shape model remains:
full-block collision is represented as static PhysX box colliders grouped by
chunk and released on `ChunkEvent.Unload`. Details and limitations are in
`docs/block-collision.md`.

## M6: Debugging And Diagnostics

Goal: make runtime behavior inspectable.

- Report world count.
- Report body count.
- Report native linked status.
- Report step time.
- Report active chunk collision count.
- Add debug rendering after the simulation path is stable.

Acceptance: commands or debug output can quickly identify missing native links,
leaks, duplicate worlds, and tick cost.

Status: implemented through `/kinetic_assembly physics_status`. It reports native link
status, scene count, object count, terrain collider count, terrain chunk count,
terrain queue state, bound debug entity count, last physics step time, last
terrain build time, and a sample dynamic object pose. Details are in
`docs/diagnostics.md`.

## M7: Native Packaging

Goal: package the native bridge with the mod.

- Copy `build/native/linux-x86_64/libkinetic_assembly_native.so` into
  `src/main/resources/native/linux-x86_64/`.
- Add Gradle tasks for native build and copy.
- Keep Linux working first.
- Add Windows and macOS only after the Linux loop is stable.

Acceptance: the mod jar contains the Linux native library and can load it through
`NativeLibraryLoader`.

Status: implemented for Linux. `buildNativeLinux` runs
`tools/native/build-linux.sh`, `copyNativeLinux` copies
`libkinetic_assembly_native.so` into generated resources under
`native/linux-x86_64/`, and `processResources` depends on that copy task when
`-Pkinetic_assembly.bundleNativeLinux=true` is set. Details are in
`docs/native-packaging.md`.

## M8: Chunk Collision Pipeline

Goal: build and maintain static terrain collision through a chunk-owned queue,
instead of directly sampling blocks around `/kinetic_assembly spawn_box`.

- Add a terrain collision build queue.
- Schedule chunk collision builds from active dynamic/debug physics objects.
- Build static full-block colliders per chunk on the server tick.
- Track chunk build state: queued, built, dirty, unloaded.
- Rebuild dirty chunks after block updates, with throttling.
- Release chunk-owned colliders when chunks unload.
- Keep the current full-block AABB shape model; leave partial voxel shapes for a
  later milestone.

Acceptance: dynamic debug bodies collide with already-built terrain chunks,
`/kinetic_assembly physics_status` reports queued/built/dirty terrain chunks, chunk unload
releases its static objects, and repeated spawn/clear/chunk travel does not grow
object or terrain counts unexpectedly.

Status: initial pipeline implemented. Server ticks queue chunks around active
dynamic debug objects, `/kinetic_assembly spawn_box` queues the spawn area, and the runtime
builds at most one terrain chunk per tick before stepping PhysX. Block updates
mark known chunks dirty for queued rebuild, while chunk unload clears queued
state and releases chunk-owned colliders. Details are in
`docs/chunk-collision-pipeline.md`.

## M9: Terrain Collider Batching

Goal: reduce static terrain body count before adding more block shape fidelity.

- Keep the M8 chunk build queue.
- Keep the current full-block collision model.
- Scan chunk full-block occupancy.
- Greedily merge adjacent full blocks into larger axis-aligned box colliders.
- Track each merged box as a chunk-owned static terrain collider.
- Rebuild a dirty chunk by replacing its previous merged colliders.

Acceptance: `/kinetic_assembly physics_status` should show far fewer
`terrainColliders` than the old one-body-per-block terrain path, while dynamic
debug boxes still collide with solid full-block terrain.

Status: implemented as full-block greedy AABB batching in
`ServerPhysicsRuntime`. The runtime now creates one static PhysX box per merged
solid region instead of one static PhysX box per exposed full block. Details are
in `docs/terrain-batching.md`.

## M10: Partial Block Collision Shapes

Goal: bring common non-full Minecraft collision shapes into PhysX without
abandoning the M9 full-block batching path.

- Keep full blocks on the greedy chunk batching path.
- Read non-full block `VoxelShape` collision boxes from Minecraft.
- Convert each non-empty shape box into a chunk-owned static PhysX box.
- Rebuild partial shapes through the existing dirty chunk queue.
- Add diagnostics for partial shape collider count per build pass.

Acceptance: slabs, stairs, fences, panes, and similar blocks contribute terrain
collision through `/kinetic_assembly physics_status`, and dynamic debug boxes can collide
with those shape boxes after the chunk is built.

Status: implemented as `VoxelShape.forAllBoxes` import for non-full blocks.
`lastTerrainPartial` reports how many partial-shape colliders were created by
the last terrain build pass. Details are in
`docs/partial-block-collision.md`.

## M11: Real Physics Debug Entity / Render Proxy

Goal: replace the ArmorStand debug proxy with a visible box-like render proxy
that matches the PhysX dynamic body pose more accurately.

- Use a `BlockDisplay` as the visible debug proxy.
- Render a 1x1x1 cube-like block instead of an ArmorStand.
- Synchronize position and full quaternion rotation from PhysX pose.
- Keep PhysX body authoritative.
- Keep proxy lifecycle owned by `ServerPhysicsRuntime`.
- Recreate missing proxies without deleting the PhysX body.
- Report proxy recreation diagnostics in `/kinetic_assembly physics_status`.

Acceptance: `/kinetic_assembly spawn_box` creates a visible cube-like object, the visual
proxy follows PhysX position and rotation, missing proxies are recreated, and
`physics_status` keeps reporting `boundEntities=1` with a dynamic sample pose.

Status: implemented with `Display.BlockDisplay` using a lime stained glass block
state and a server-authored display transformation. `proxyRecreated` and
`lastProxyRecreated` report debug proxy recovery. Details are in
`docs/render-proxy.md`.

## M12: Dynamic Body Controls

Goal: make dynamic PhysX bodies easier to create and manipulate for collision
testing.

- Add optional size and mass arguments to `/kinetic_assembly spawn_box`.
- Scale the `BlockDisplay` render proxy to match the spawned PhysX box size.
- Add a command to set linear velocity on the nearest dynamic debug box.
- Report active dynamic box count in `/kinetic_assembly physics_status`.
- Include sample linear velocity in status output.

Acceptance: testers can spawn boxes with different sizes and masses, apply a
velocity to the nearest box, and observe dynamic body motion through the
`BlockDisplay` proxy while PhysX remains authoritative.

Status: implemented. `/kinetic_assembly spawn_box [size] [mass]` creates a uniform dynamic
box with matching proxy scale, and `/kinetic_assembly set_velocity <x> <y> <z>` sets the
linear velocity of the nearest dynamic box within 32 blocks. Details are in
`docs/dynamic-body-controls.md`.

## M13: Debug Object Management

Goal: make active dynamic debug bodies manageable without clearing the whole
dimension.

- Add a command to list dynamic debug boxes in the current level.
- Sort listed boxes by distance from the command source.
- Add a command to remove the nearest dynamic debug box.
- Add a command to remove a dynamic debug box by object id prefix.
- When removing a dynamic box, close the PhysX object and discard its
  `BlockDisplay` proxy.
- Keep terrain colliders untouched during individual debug object removal.

Acceptance: testers can spawn several debug boxes, list them, remove one by
nearest distance or id prefix, and observe `physics_status` decrement
`dynamicBoxes`, `objects`, and `boundEntities` without dropping cached terrain
colliders.

Status: implemented. `/kinetic_assembly list_boxes [limit]`, `/kinetic_assembly remove_nearest
[maxDistance]`, and `/kinetic_assembly remove_box <idPrefix>` manage dynamic debug boxes
inside the current level. Details are in `docs/debug-object-management.md`.

## M14: Cross-Platform Native Build Entry Points

Goal: make the JNI bridge CMake project portable enough that Windows and macOS
native builds do not require rewriting `CMakeLists.txt`.

- Detect the PhysX platform binary directory from the CMake host platform.
- Keep `PHYSX_LIB_DIR` as an explicit override for unusual local installs.
- Add platform-specific PhysX install hints for Linux, Windows, and macOS.
- Link platform system libraries through CMake platform branches instead of
  hardcoding Linux `dl` and `pthread`.
- Keep Gradle native packaging opt-in and Linux-only until other platforms are
  validated.

Acceptance: the existing Linux native smoke test still passes, Windows/macOS
helpers can invoke the same CMake project with platform overrides, and normal
`./gradlew build` remains Java-only.

Status: implemented at the build-entry level. Linux was verified locally through
`./gradlew nativeSmokeTest`; Windows and macOS CMake paths are prepared but
still require local platform validation before jar packaging tasks are added.
Details are in `docs/native-packaging.md`.

## M15: GPU Dynamics Stress Path

Goal: make PhysX GPU rigid body dynamics testable under many-body load without
Minecraft render proxies dominating the result.

- Add an opt-in config flag for requesting `PxSceneFlag::eENABLE_GPU_DYNAMICS`
  on newly created PhysX scenes.
- Create one shared CUDA context manager when the local PhysX build and driver
  support GPU dynamics.
- Use GPU broadphase for GPU scenes, and fall back to CPU scenes if GPU setup
  fails.
- Create physics scenes lazily instead of pre-creating one scene for every
  loaded dimension during server tick.
- Report both requested GPU dynamics and actually enabled GPU scene count in
  `/kinetic_assembly physics_status`.
- Add a physics-only stress-grid command that creates dynamic boxes without
  `BlockDisplay` proxies.
- Copy `PhysXGpu` runtime artifacts into native output when CMake can find
  them.

Acceptance: testers can enable `enableGpuDynamics`, restart the world, confirm
`gpuRequested=true` and `gpuScenes>0` after creating a physics scene, then run
`/kinetic_assembly spawn_stress_grid <countX> <countY> <countZ> [spacing] [size] [mass]`
to compare CPU and GPU dynamics behavior. CPU fallback remains usable and is
visible as `gpuRequested=true, gpuScenes=0`.

Status: implemented. Details are in `docs/gpu-dynamics.md`.

## M16: Runtime Profiling

Goal: make server-side physics tick costs visible before optimizing the wrong
layer.

- Measure total time spent inside `ServerPhysicsRuntime.tick`.
- Split the tick into active-object terrain queueing, terrain queue processing,
  scene stepping, and debug entity sync.
- Keep the existing native scene step metric for comparison.
- Count active-object snapshots, dynamic bodies found during the active scan,
  terrain chunks queued, synced debug entities, and entity pose syncs.
- Report the metrics through `/kinetic_assembly physics_status`.

Acceptance: a tester can distinguish native PhysX step cost from Java/JNI
snapshot readback, terrain queueing, and Minecraft debug entity sync cost.

Status: implemented. Details are in `docs/runtime-profiling.md`.

## M17: Many-Body Runtime Stress Pass

Goal: validate many dynamic PhysX bodies under Minecraft server tick load, while
separating solver cost from terrain queueing and debug proxy synchronization.

- Use physics-only stress grids to test solver throughput without Minecraft
  entity overhead.
- Use visible `BlockDisplay` proxies to measure the cost of debug visualization
  separately from the native PhysX step.
- Round-robin debug proxy sync and skip unchanged position-only proxy writes.
- Budget active-object terrain scans instead of reading every dynamic body pose
  every tick.
- Skip active terrain queueing for bodies far outside Minecraft build height.
- Report active terrain scan budget, height skips, proxy sync counts, and sync
  sub-timings in `/kinetic_assembly physics_status`.

Acceptance: thousands of dynamic boxes can be kept in one server scene, with
`lastSyncEntitiesMs` reduced below the native solver step cost and
`lastQueueActiveMs` bounded by `activeTerrainMaxScansPerTick`. The status log
should make the remaining bottleneck obvious.

Status: passed for the current debug/stress scope. A representative run with
4,444 dynamic boxes reported roughly `lastTickMs=11.961`,
`lastStepPhaseMs=9.450`, `lastQueueActiveMs=1.919`, and
`lastSyncEntitiesMs=0.579`, showing that the main remaining cost is the PhysX
solver step rather than Minecraft proxy synchronization. This does not imply
that real gameplay should use thousands of visible Minecraft entities.

## M18: Mechanics API And Coupling Boundary

Goal: move from debug bodies and stress tests toward a public mechanics
substrate that aerodynamics, electromagnetics, vehicles, gameplay systems, and
future RL tooling can build on.

- Add a stable `KineticAssembly.api()` entry point.
- Expose a per-`ServerLevel` `MechanicsWorld` instead of native handles or debug
  scene internals.
- Separate mechanics-owned gameplay bodies from debug/stress bodies.
- Support the first rigid body surface: create dynamic boxes, list snapshots,
  read one snapshot, set linear velocity, apply linear impulse, and remove a
  body.
- Keep `BlockDisplay` proxies out of the mechanics body model.
- Add sandbox commands under `/kinetic_assembly mechanics` to exercise the public API.
- Add optional show/hide debug proxy commands as a separate visualization
  adapter.
- Reserve force/torque APIs, contact events, compound bodies, soft bodies,
  vehicle assemblies, and deterministic RL reset for later slices.

Acceptance: another server-side system can enter through `KineticAssembly.api()`,
create a gameplay mechanics body in a level, apply an impulse, inspect its
snapshot, and remove it without touching PhysX native handles or debug proxy
state.

Status: M18.1 and M18.2 are implemented. The public API skeleton is available
through `KineticAssembly.api()`, `/kinetic_assembly mechanics` exercises create/list/impulse/remove
without using debug body commands, and show/hide debug proxies are optional
adapters. Details are in `docs/mechanics-api.md`. The next slice should add a
tiny gameplay object prototype on top of this API instead of binding gameplay
directly to debug bodies.

## M19: Default CCD Safety Baseline

Goal: reduce high-speed tunneling before gameplay objects, vehicles, and future
RL actions depend on the mechanics layer.

- Enable `PxSceneFlag::eENABLE_CCD` for every PhysX scene.
- Add `PxPairFlag::eDETECT_CCD_CONTACT` to normal contact pairs through the
  native simulation filter shader.
- Enable `PxRigidBodyFlag::eENABLE_CCD` on backend-created dynamic bodies.
- Keep CCD on by default without adding a user-facing config or command.
- Add a native smoke check for a fast dynamic box colliding with a thin static
  wall in a single 20 TPS step.

Acceptance: native smoke tests verify that a high-speed dynamic box does not
tunnel through a thin static wall, and normal trigger behavior remains separate
from CCD contact pairs.

Status: implemented. Details are in `docs/ccd.md`.

## M20: Detached Block Gameplay Prototype

Goal: prove that Minecraft world content can detach into a mechanics-owned body
without coupling gameplay directly to debug/stress bodies.

- Add `/kinetic_assembly block detach <pos> [mass] [debugProxy]`.
- Replace the selected block with air and refresh terrain collision around the
  changed block.
- Create a mechanics dynamic box from the block collision-shape bounding box.
- Use the original block state as the optional `BlockDisplay` visualization.
- Track detached block metadata separately from mechanics body ownership.
- Add list, restore, and remove commands for the prototype lifecycle.

Acceptance: a solid block can be detached into a mechanics body, collide with
terrain as a dynamic object, receive mechanics impulses through the existing
mechanics commands, and either be restored to its original position or removed.

Status: prototype implemented. Details are in
`docs/detached-block-prototype.md`.

## M21: Small Block Assembly Prototype

Goal: move from one detached block to a small Minecraft block structure while
still avoiding full compound-body scope.

- Add `/kinetic_assembly block detach_box <from> <to> [mass] [debugProxy]`.
- Collect a bounded set of non-air blocks with collision shapes.
- Remove those blocks from the world and refresh terrain collision around the
  edited positions.
- Represent the assembly with one aggregate mechanics dynamic box.
- Save every original block state and relative position for restore.
- Visualize the assembly with one `BlockDisplay` per original block, all
  following the same mechanics body pose.

Acceptance: a small block range can detach into one mechanics body, move as a
single rigid assembly, list through `/kinetic_assembly block list`, and restore/remove
through the same detached-block lifecycle as M20.

Status: prototype implemented. Details are in
`docs/detached-block-prototype.md`.

## M22.1: Assembly Ownership Model

Goal: replace the temporary detached-block ownership model with a assembly
boundary before adding richer assembly behavior.

- Add `AssemblyId`, `PhysicsAssembly`, `AssemblyBlock`, `AssemblyBounds`, and
  `AssemblySnapshot`.
- Move block capture, mechanics body creation, visual proxy ownership, and
  remove/discard lifecycle into `AssemblyManager`.
- Treat the assembly id as the gameplay handle while keeping the mechanics body
  id as a physics implementation detail.
- Add `/kinetic_assembly assembly assemble_block <pos> [mass] [debugProxy]`.
- Add `/kinetic_assembly assembly assemble_box <from> <to> [mass] [debugProxy]`.
- Add `/kinetic_assembly assembly list [limit]`, `/kinetic_assembly assembly remove <idPrefix>`,
  and `/kinetic_assembly assembly status`.
- Keep `/kinetic_assembly block ...` commands as compatibility wrappers over the assembly
  manager.
- Leave restore as a legacy/debug path instead of the primary lifecycle.

Acceptance: a small block range can be captured into a named assembly, moved by
its mechanics body, listed and removed by assembly id, while old block commands
continue to work through the same manager.

Status: implemented. Details are in `docs/assembly.md`.

## M23.1: Assembly Interaction Contract

Goal: make the next assembly work compatible with a plot-chunk model
and PhysX-owned player movement before adding more gameplay behavior.

- Treat the player as a PhysX object when participating in this physics world.
  PhysX owns the physical player pose; Minecraft synchronizes from PhysX after
  the physics step.
- Treat a assembly as a reserved plot chunk/section block store inside the host
  world. The host world's server tick is the assembly tick.
- Define plot id, plot chunk coordinates, section-local block storage,
  local-to-world transforms, dirty shape tracking, and bounded tick queues before
  adding deeper mixins.
- Route player interaction through a world bridge that can transform raycasts,
  use, break, and place operations between host-world and assembly-local
  coordinates.
- Keep `BlockDisplay` entities as optional debug/render proxies only.
- Delay block entities, normal entity capture, and broad chunk-cache injection
  until the plot storage/query/tick contract is stable.

Acceptance: the project has a written interaction contract that makes player
physics ownership, assembly tick ownership, cross-boundary interaction, and the
next implementation slices explicit.

Status: implemented. Details are in `docs/assembly-interaction-contract.md`.

## M23.2: Section-Local Assembly Storage

Goal: move assembly internals from a captured block list to a section-local
storage API that can later become plot chunk storage before routing player
interaction or rebuilding colliders from assembly mutations.

- Add a `AssemblySectionStorage` with one 16x16x16 local section as the first
  plot-compatible storage slice.
- Store blocks by section-local coordinates while preserving source positions
  for legacy/debug lifecycle paths.
- Expose block lookup, block state lookup, collision bounds lookup, and source
  coordinate conversion.
- Mark local positions dirty on block mutation, removal, and explicit dirty
  requests.
- Support bounded dirty-position draining so future collider rebuild work can be
  budgeted over host-world ticks.
- Keep existing assembly assemble/list/remove/impulse behavior compatible.

Acceptance: an assembled assembly owns section-local storage, existing commands
still work, `/kinetic_assembly assembly status` reports dirty block count, and dirty
positions can be drained later by collider rebuild code.

Status: implemented. Details are in `docs/assembly.md`.

## M23.3: PhysX-Owned Player Body Prototype

Goal: create the first explicit player-to-PhysX binding so player pose can be
owned by PhysX instead of Minecraft while the prototype is active.

- Add a `PLAYER` mechanics body role.
- Add `PlayerPhysicsManager` to bind one server player to one mechanics body.
- Create a standing-player dynamic box with half extents `(0.30, 0.90, 0.30)`.
- Step PhysX through the existing `ServerPhysicsRuntime` tick, then synchronize
  the player feet position from the PhysX body center.
- Disable vanilla gravity while the binding is active, clear vanilla delta
  movement, and reset fall distance during sync.
- Add `/kinetic_assembly player_physics enable [mass] [debugProxy]`.
- Add `/kinetic_assembly player_physics disable`, `status`, and `impulse <x> <y> <z>`.
- Clean up player bindings on `/kinetic_assembly clear`, dimension changes, missing bodies,
  and server shutdown.

Acceptance: a command-source player can be explicitly bound to a PhysX mechanics
body, receive impulses through the player-physics command surface, and have
their server position synchronized from the PhysX body after each physics step.

Status: implemented. Details are in `docs/player-physics.md`.

## M23.4: Vanilla Player Bridge

Goal: keep normal Minecraft players vanilla-authoritative while exposing their
position to PhysX through a player proxy body.

- Add a `PLAYER_PROXY` mechanics body role.
- Add `PlayerProxyManager` to bind one server player to one proxy mechanics
  body.
- Add `MechanicsWorld.setPose` so bridge code can update proxy bodies without
  depending on debug object internals.
- Sync player proxy pose and approximate player velocity before each
  `ServerPhysicsRuntime` step.
- Keep M23.3 `PLAYER` bodies and M23.4 `PLAYER_PROXY` bodies mutually exclusive
  for the same player.
- Add `/kinetic_assembly player_proxy enable [mass] [debugProxy]`.
- Add `/kinetic_assembly player_proxy disable` and `status`.
- Clean up player proxies on `/kinetic_assembly clear`, dimension changes, missing players,
  and server shutdown.

Acceptance: a command-source player can keep normal vanilla movement while a
PhysX `PLAYER_PROXY` body follows them before each physics step, allowing other
PhysX bodies to observe a player-shaped body without PhysX owning the player's
final Minecraft pose.

Status: implemented. Details are in `docs/player-bridge.md`.

## M23.4.1: Assembly Query Bridge

Goal: let a vanilla player identify which block inside a moving assembly they
are looking at before implementing break/place/use routing.

- Add `AssemblyTransform` for world-to-assembly and assembly-to-world coordinate
  conversion from the mechanics body pose.
- Add `AssemblyPickResult` as the read-only query result model.
- Add `AssemblyManager.pickBlock` to raycast from a world-space origin/direction
  into active assemblies in the current level.
- Test captured block collision bounds in assembly body-local space.
- Add `/kinetic_assembly assembly pick [maxDistance]`.
- Report assembly id, body id, section-local block position, source position,
  block state, hit points, and distance.
- Keep the phase read-only: no break/place/use, no storage mutation, and no
  collider rebuild.

Acceptance: after assembling and moving a assembly, a player can look at its
visible block proxy and run `/kinetic_assembly assembly pick` to get the matching assembly
id and section-local block position.

Status: implemented. Details are in `docs/assembly-query-bridge.md`.

## M23.4.2: Command-Gated Assembly Break

Goal: prove that an explicit player action can mutate assembly-local storage
through the query bridge before adding vanilla input hooks or placement.

- Add `AssemblyBreakResult` as the mutation result model.
- Add `AssemblyManager.breakPickedBlock` to reuse the player view ray query,
  remove the selected local block from section storage, and mark it dirty.
- Discard the matching `BlockDisplay` visual for the removed block.
- Remove the whole assembly mechanics body if the final block is broken.
- Add `/kinetic_assembly assembly break [maxDistance]`.
- Keep the phase command-gated: no vanilla left-click/right-click mixins, no
  placement, no use routing, and no collider rebuild yet.

Acceptance: after assembling a visual assembly, a player can look at one of its
blocks and run `/kinetic_assembly assembly break` to remove that block from assembly
storage and debug visuals while dirty state records the changed local position.

Status: implemented. Details are in `docs/assembly-interactions.md`.

## M23.4.3: Assembly Plot Chunk Presentation Contract

Goal: capture the client-side requirements before replacing
`BlockDisplay` debug visuals or adding vanilla input mixins.

- Treat `BlockDisplay` visuals as a temporary debug/prototype adapter.
- Define the final target as a plot chunk route, not only a custom
  moving mesh.
- Require server and client assembly storage to be compatible with reserved plot
  chunk coordinates, section-local blocks, dirty sections, and root transforms.
- Require transformed block selection outlines for assembly-local hits.
- Require Minecraft-style block rendering and lighting through a plot/chunk
  bridge, starting with bounded host-world light sampling at transformed block
  positions.
- Keep breaking overlays, particles, block entity rendering, and full vanilla
  input routing as later implementation steps.
- Avoid treating assemblies as ordinary entities for final rendering.

Acceptance: the project has a written client presentation contract that
separates plot storage, physics root, client plot mirror, outline rendering,
render section integration, and lighting behavior.

Status: documented. Details are in `docs/assembly-client-presentation.md`.

## M24: Plot Chunk Assembly Foundation

Goal: move the assembly target from debug visuals toward a plot chunk
route, where assembly content is represented as reserved chunk/section-local
block data with a PhysX root transform.

- Add a plot-compatible assembly model: plot id, plot chunk coordinates,
  section-local blocks, dirty sections, and root transform.
- Add a client plot mirror for assembly block states and root transforms.
- Add focused mixin registration for the first plot bridge hooks.
- Draw transformed block outlines for selected plot-local assembly blocks.
- Render assembly blocks through a chunk-section-like path instead of one entity
  per block.
- Add bounded lighting lookup/cache for transformed plot-local block positions.
- Keep `BlockDisplay` available as an optional debug visual.
- Only after this layer is stable, route vanilla break/place/use input through
  focused mixins.

Status: in progress. The current code slice adds reserved plot metadata,
server-side `PlotChunkHolder` ownership, server/client chunk-cache routing, and
client tracking based on metadata payloads plus vanilla plot chunk packets.
Client presentation now draws finalized plot chunks directly from client
`LevelChunk` sections with vanilla block model rendering, host-world light/tint
sampling, fluids, block entity rendering, and plot-chunk local selection
outlines. Full render-section compile/cache integration remains a
later M24 slice.

## M24.2: Client Plot State And Selection Outline

Goal: give the client enough plot-local assembly state to compute the selected
assembly block without asking the server every frame.

- Register NeoForge play payloads for server-to-client assembly tracking
  metadata and root pose updates.
- Sync assembly id, plot coordinates, root pose, plot-origin transform, and plot
  chunk positions to clients in the same dimension.
- Send block contents through vanilla `ClientboundLevelChunkWithLightPacket`
  packets for reserved plot chunks.
- Maintain client-side plot chunk tracking keyed by Minecraft dimension id.
- Reuse the same world-ray to assembly-local raycast semantics as the
  server-side `/kinetic_assembly assembly pick` bridge.
- Track the current client selected assembly target each client tick.
- Draw a transformed collision outline for the selected plot-local block while
  keeping `BlockDisplay` as the current optional visual proxy.

Acceptance: after assembling a visual assembly, the client receives tracking
metadata and plot chunks, can identify loaded plot chunks locally, and renders
the plot block content at the physics body pose.

Status: in progress. Tracking metadata payloads establish client assemblies,
vanilla chunk packets populate client plot chunks, transform payloads update
root pose, `AssemblyPlotRenderer` draws loaded plot content, and
`ClientAssemblySelection` draws a transformed outline for the current plot
target. Vanilla hit-result replacement and breaking overlays remain later bridge
work.

## First Target

The recommended first vertical slice is:

```text
/kinetic_assembly spawn_box creates a PhysX dynamic box and makes a visible Minecraft test
entity follow its pose as it falls.
```

This validates native linking, JNI, Java API lifecycle, server ticking, and
entity synchronization while keeping the scope small.
