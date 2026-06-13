# Terrain Collider Batching

M9 reduces terrain collider count while keeping the same full-block collision
model.

## Current Behavior

Chunk terrain still enters PhysX through the M8 build queue. The build step now:

1. Scans the chunk's full-block collision occupancy.
2. Runs a greedy 3D merge over adjacent solid cells.
3. Creates one static PhysX box for each merged solid region.
4. Tracks those boxes as chunk-owned terrain colliders.

The generated box covers the exact union of full block cells in that merged
region:

```text
center = merged region center
half extents = merged region dimensions / 2
```

This replaces the previous one-body-per-exposed-block path.

## Why This Comes Before Shape Fidelity

The previous debug output showed thousands of terrain static objects for a small
9-chunk test area. Slabs, stairs, and arbitrary voxel shapes would multiply that
cost if the engine kept creating one native object per block-sized piece.

Batching full blocks first gives the runtime a better baseline:

- fewer PhysX static bodies;
- lower scene object count;
- cheaper chunk unload and rebuild;
- clearer diagnostics now that partial shapes are imported separately.

## Dirty Rebuilds

When a known terrain chunk becomes dirty, the next build pass removes every
merged collider owned by that chunk and replaces them with a fresh greedy batch.
Individual block updates no longer try to remove a single collider immediately,
because a collider may represent a multi-block region.

## Diagnostics

`/kinetic_assembly physics_status` keeps the same terrain fields:

- `terrainColliders`: merged static terrain boxes, not block count.
- `terrainChunks`: chunks that currently own terrain collider sets.
- `lastTerrainAdded`: merged static boxes created by the last build pass.
- `lastTerrainBuildMs`: elapsed time for the last terrain build pass.

After M9, `terrainColliders` should be read as "number of batched PhysX terrain
objects." It should be much lower than the number of full blocks in the same
chunks.

## Limitations

- Only full-block collision is batched.
- Partial voxel shapes are imported separately and are not batched across blocks.
- The greedy merge is local to one chunk.
- Cross-chunk collider merging is intentionally deferred.
- Static boxes are still represented as separate PhysX bodies; a future mesh or
  compound representation may be more efficient.
