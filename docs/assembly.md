# Assembly Prototype

For the current architecture spine and decision rules, read
`docs/assembly-mainline.md`.

Detached block assemblies now use a plot ownership model for the first
server/client slice. It is still transitional, but the active route is reserved
plot chunks plus a physics root transform rather than mirror/patch block
snapshots.

A assembly is now the gameplay owner for a detached world fragment:

- `AssemblyId` is the stable gameplay id.
- `PhysicsAssembly` owns the reserved plot metadata, section-local block store,
  source bounds, mechanics body id, and visual proxy bindings.
- `AssemblyBlock` stores original source position, assembly-local position,
  block state, collision bounds, and visual offset.
- `AssemblySectionStorage` stores the assembly as one 16x16x16 section with
  section-local lookup, mutation, and dirty block tracking.
- `AssemblyManager` assembles blocks from the Minecraft world, creates the
  aggregate mechanics body, owns visual sync, and removes/discards assemblies.
- `ServerAssemblyContainer` owns `PlotChunkHolder` instances for reserved plot
  chunks and sends vanilla chunk packets to tracking clients.
- `ClientAssemblyContainer` stores client-side plot `LevelChunk` instances and
  root transform metadata for rendering.

The mechanics body is still the physics representation, but gameplay systems
should address the object through the assembly id.

## Commands

```text
/kinetic_assembly assembly assemble_block <pos> [mass] [debugProxy]
/kinetic_assembly assembly assemble_box <from> <to> [mass] [debugProxy]
/kinetic_assembly assembly list [limit]
/kinetic_assembly assembly pick [maxDistance]
/kinetic_assembly assembly break [maxDistance]
/kinetic_assembly assembly impulse <idPrefix> <x> <y> <z>
/kinetic_assembly assembly torque_impulse <idPrefix> <x> <y> <z>
/kinetic_assembly assembly remove <idPrefix>
/kinetic_assembly assembly status
```

Legacy block commands still work, but now forward to the assembly manager:

```text
/kinetic_assembly block detach <pos> [mass] [debugProxy]
/kinetic_assembly block detach_box <from> <to> [mass] [debugProxy]
/kinetic_assembly block list [limit]
/kinetic_assembly block remove <idPrefix>
```

`idPrefix` matches either the assembly id or its mechanics body id. Prefer
`/kinetic_assembly assembly impulse` for assemblies; `/kinetic_assembly mechanics impulse` only searches
raw mechanics body ids.

## Current Limits

- Physics is still one aggregate AABB body, not a true compound collider.
- `BlockDisplay` visuals are optional debug proxies, not the main presentation.
- Client rendering caches non-translucent plot chunk geometry per assembly and
  render layer; translucent blocks and block entities still use immediate
  rendering paths, and render-section integration is not done.
- Vanilla hit-result replacement, breaking overlay, and full interaction input
  routing are not implemented yet.
- Block entities and normal entities are not captured yet.
- Restore remains a legacy/debug path, not the main lifecycle direction.
- Persistence across world reload is not implemented yet.

## M23 Direction

M23.2 starts treating the assembly as a chunk/section-local gameplay store driven
by the host world's tick. The final target is a reserved plot chunk route, where
assembly block state, rendering, lighting, and interaction can be bridged through
Minecraft-like chunk semantics. Player physics also moves under PhysX ownership
later so players, assemblies, and terrain participate in the same authority model.

The current storage layer provides:

- section-local block lookup through `AssemblySectionStorage`;
- reserved plot metadata for the future plot chunk bridge;
- block mutation APIs that mark dirty local positions;
- dirty position inspection and bounded draining for later collider rebuilds;
- player view ray queries through `/kinetic_assembly assembly pick`;
- command-gated local block removal through `/kinetic_assembly assembly break`;
- compatibility with existing `assembly` commands and visual proxies.
- client presentation from reserved plot chunks using vanilla block chunk
  packets, root transform metadata, direct block/fluid rendering, and block
  entity rendering;
- client plot-chunk selection outline from the same root transform used for
  rendering.

The detailed interaction contract is in `docs/assembly-interaction-contract.md`.
The read-only query bridge is described in `docs/assembly-query-bridge.md`.
Command-gated interaction routing is described in
`docs/assembly-interactions.md`.
The client-side plot-chunk presentation target is described in
`docs/assembly-client-presentation.md`.
