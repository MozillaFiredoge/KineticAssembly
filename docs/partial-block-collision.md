# Partial Block Collision

M10 imports non-full Minecraft collision shapes into the existing chunk terrain
pipeline.

## Current Behavior

Chunk builds split terrain into two paths:

- Full blocks are scanned into chunk occupancy and merged by the M9 greedy AABB
  batching pass.
- Non-full blocks use `BlockState.getCollisionShape` and
  `VoxelShape.forAllBoxes`.

Each non-empty voxel shape box becomes one static PhysX box owned by the chunk
that contains the block.

```text
center = block position + local shape box center
half extents = local shape box dimensions / 2
```

This adds basic collision for slabs, stairs, fences, panes, and other blocks
whose collision shape is represented by one or more axis-aligned boxes.

## Rebuild Behavior

Partial shape colliders are chunk-owned, not block-owned. When a known chunk is
marked dirty, the terrain pipeline removes every collider owned by that chunk
and rebuilds both:

- full-block batched colliders;
- partial-shape colliders.

This is less granular than single-block updates, but it matches the M8 chunk
ownership model and avoids trying to surgically remove one collider that may
belong to a merged terrain region.

## Diagnostics

`/kinetic_assembly physics_status` includes:

- `terrainColliders`: all chunk terrain colliders, including full-block batches
  and partial-shape boxes.
- `lastTerrainAdded`: total terrain colliders created by the last terrain build
  pass.
- `lastTerrainPartial`: partial-shape colliders created by the last terrain
  build pass.
- `lastTerrainBuildMs`: elapsed time for the last terrain build pass.

If a test area contains slabs or stairs and the containing chunk rebuilds,
`lastTerrainPartial` should be greater than zero for that build pass.

## Limitations

- Partial shapes are not batched across blocks.
- Rotated or complex blocks are only as accurate as Minecraft's axis-aligned
  collision boxes exposed through `VoxelShape.forAllBoxes`.
- Entity-specific collision context is not modeled yet; the default block
  collision shape is used.
- Cross-chunk merging remains deferred.
- Fluids are still ignored unless their block state exposes a collision shape.
