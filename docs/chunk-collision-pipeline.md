# Chunk Collision Pipeline

M8 moves terrain collision from spawn-local block sampling to a chunk-owned build
queue.

## Current Behavior

The runtime queues terrain chunks from two sources:

- Active dynamic debug objects: every server tick queues a small chunk radius
  around each dynamic physics object.
- `/kinetic_assembly spawn_box`: queues the chunk containing the debug body plus the
  surrounding radius.

The queue is processed before PhysX stepping. The current throttle is:

```text
max terrain chunk builds per tick: 1
```

This keeps chunk scanning visible in diagnostics and avoids accidentally doing a
large terrain import in one tick.

## Chunk Build

Each built chunk is owned by the current `ServerPhysicsScene` for that dimension.
The current implementation still uses the M5 shape model, but M9 batches nearby
full blocks before creating PhysX objects, and M10 imports non-full
`VoxelShape` boxes separately:

```text
one static PhysX box per merged full-block region
one static PhysX box per partial shape box
```

The batch is local to one chunk. Cross-chunk merging is intentionally deferred
until the terrain pipeline has more runtime testing.

## State Model

Terrain chunks move through these runtime states:

- `PENDING`: queued for first build.
- `BUILT`: the chunk has been scanned and owns zero or more static colliders.
- `DIRTY`: the chunk was previously known and has been queued for rebuild after
  a block update.
- unloaded chunks are removed from the queue and state map.

Block updates do not rebuild collision immediately. They mark already-known
chunks dirty, and the server tick queue rebuilds them with the same throttle as
new chunks. Block break still removes the exact stale collider immediately
before queueing the chunk rebuild.

## Diagnostics

`/kinetic_assembly physics_status` reports the terrain pipeline with:

- `terrainColliders`: batched static terrain boxes currently cached.
- `terrainChunks`: chunks that currently own collider sets.
- `terrainQueued`: chunks waiting in the build queue.
- `terrainBuilt`: chunks whose latest state is built.
- `terrainDirty`: known chunks waiting for rebuild.
- `lastTerrainChunks`: terrain chunks built by the last build pass.
- `lastTerrainAdded`: batched static colliders created by the last build pass.
- `lastTerrainPartial`: partial-shape colliders created by the last build pass.
- `lastTerrainBuildMs`: elapsed time for the last build pass.

These fields are the main acceptance signal for M8. During testing, repeated
spawn, clear, chunk travel, and chunk unload should not cause terrain or object
counts to grow without bound.

## Limitations

- Full blocks are batched, but partial shapes are not batched across blocks.
- Fluids are ignored unless their block state exposes a collision shape.
- Cross-chunk neighbor checks are intentionally conservative in this pass.
- The build queue is driven by active physics/debug objects, not by eager
  chunk-load construction.

The next terrain milestone should focus on entity-specific collision context,
partial-shape batching, or a lower-level compound/mesh representation for the
batched boxes.
