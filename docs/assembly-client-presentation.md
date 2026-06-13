# Assembly Client Presentation

The current assembly prototype now has a plot-chunk client presentation slice.
The server owns block storage, physics body identity, player queries, and
command-gated mutation; the client receives metadata payloads plus vanilla chunk
packets for reserved plot chunks. `BlockDisplay` remains available only as an
optional debug proxy.

The assembly presentation target is a plot-chunk model, not only a custom moving
mesh. The player-facing result should feel like Minecraft blocks in a moving
local chunk space:

- the player should be able to select a assembly block and see the normal block
  outline;
- assembly blocks should be rendered like blocks, not like ordinary entities;
- assembly blocks should participate in Minecraft-style lighting instead of
  looking like isolated display proxies;
- interaction routing should treat the selected target as a block inside a
  moving chunk/section store;
- the client and server should eventually route chunk, light, raycast, and block
  update queries through a plot-chunk bridge.

## Current BlockDisplay Limits

`BlockDisplay` is useful for prototypes because it is easy to spawn and follows
the physics body pose. It is not the final representation:

- selection outline is not tied into vanilla block hit rendering;
- block lighting is entity/display lighting, not section lighting;
- model culling, ambient occlusion, breaking overlay, particles, and block entity
  rendering do not behave like normal chunk content;
- one entity per block is not the right performance model for large assemblies.

The current `BlockDisplay` path should remain a debug/prototype adapter.

## Current Plot Chunk Slice

The active client route is plot chunk based, not mirror/patch based:

- the server sends `ClientboundStartTrackingAssemblyPayload`,
  vanilla `ClientboundLevelChunkWithLightPacket` packets for plot chunks, then
  `ClientboundFinalizeAssemblyPayload`;
- client chunk-cache mixins intercept reserved plot chunk coordinates and store
  those vanilla chunks in `ClientAssemblyContainer`;
- root pose updates use `ClientboundAssemblyTransformPayload`;
- `AssemblyPlotRenderer` draws finalized plot chunks from their `LevelChunk`
  sections with vanilla block model rendering, host-world light/tint sampling,
  fluid rendering, and block entity rendering;
- `ClientAssemblySelection` raycasts loaded client plot chunks from the player
  view and draws a transformed outline from the same root pose as rendering;
- `/kinetic_assembly_client assembly_status` reports client tracking, loaded plot chunks,
  last rendered block/block-entity counts, and the current plot selection.

This is still a direct immediate renderer. The next presentation step is
replacing the immediate per-frame rebuild with render-section compile/cache
integration.

## Plot Chunk Target

The final target is a plot chunk route:

- each assembly owns one or more reserved plot chunks in the host level;
- assembly blocks live in chunk/section-like storage, not per-block entities;
- vanilla-like chunk queries can be redirected into the assembly plot when the
  queried coordinates are inside that reserved plot;
- the assembly root PhysX body maps between plot-local block coordinates and
  host-world coordinates;
- render sections, light queries, block outlines, breaking overlays, particles,
  and selected block interactions use plot-local coordinates plus the root
  transform.

This does not mean the first code slice must inject fully-populated vanilla
chunks into every path. It does mean the data model should be plot-compatible
from the start: local section positions, global plot chunk coordinates, dirty
section tracking, chunk-like block state access, and an explicit local-to-world
transform.

## Target Layers

The target should separate four layers:

- Server plot storage: authoritative chunk/section-like block state, dirty
  sections, block entity ownership, and plot allocation.
- Physics root: one or more PhysX bodies/colliders that move the plot-local
  contents.
- Client plot chunks: client-side vanilla `LevelChunk` instances and root
  transform for interpolation, selection, and rendering.
- Vanilla bridge: focused mixins for chunk cache routing, raycast, outline,
  render section, light query, and later block interaction input.

## Selection Outline

The existing `/kinetic_assembly assembly pick` proves the server-side query semantics. The
client-side presentation needs an equivalent selected target:

```text
world-space ray -> assembly transform -> section-local block hit
```

Once a selected assembly block exists on the client, a render hook can draw the
normal block outline using the transformed local block AABB. This is separate
from mutation. The outline should work before vanilla left-click/right-click
input is redirected.

Likely mixin/hook area:

- client hit result calculation or selected target replacement;
- block outline rendering;
- breaking progress overlay once break routing exists.

Current M24.2 status: the client computes the selected assembly block from the
loaded plot chunks and the player's view ray. The outline is drawn from the same
plot chunk/root pose path as the block renderer. This is still not a full
vanilla hit-result replacement; left-click/right-click routing and vanilla
breaking overlays remain later bridge work.

## Lighting

Assembly lighting should be derived from Minecraft world lighting, not from
entity display lighting.

The first approximation can sample host-world light at transformed block
positions. A later implementation can maintain a section-local light cache and
update it when the assembly moves, rotates, or its block states change.

Important constraints:

- light lookup must be bounded per frame/tick;
- lighting should be cached for stable assemblies;
- dirty blocks should invalidate only affected local positions;
- full vanilla light propagation inside moving sections can wait until ordinary
  block rendering and selection are stable.

## Recommended Implementation Order

1. Keep `BlockDisplay` as an optional debug visual.
2. Add a plot-compatible assembly store: reserved plot id, plot chunk
   coordinates, section-local block state access, and dirty section tracking.
3. Add client plot chunk tracking for block states and root transforms.
4. Add focused mixins for selected block raycast and transformed block outline.
5. Render plot-local sections through a chunk-section-like renderer path, keeping
   `BlockDisplay` only for debug.
6. Add bounded lighting lookup/cache for plot-local blocks.
7. Route vanilla break/place/use input into the plot bridge after selection,
   rendering, and lighting are stable.
