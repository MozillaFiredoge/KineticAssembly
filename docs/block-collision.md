# Static Block Collision

The current terrain implementation converts Minecraft full-block collision into
static PhysX boxes through the M8 chunk build queue.

## Current Behavior

M8 replaces the direct spawn-local sampling path with a chunk-owned terrain
build queue. When `/kinetic_assembly spawn_box` runs, `ServerPhysicsRuntime` queues chunks
around the command source:

```text
chunk radius: 1
max chunk builds per tick: 1
```

On server tick, queued chunks are scanned before PhysX stepping. Full blocks in
the chunk are greedily merged into larger static PhysX boxes:

```text
center = merged box center
half extents = merged box dimensions / 2
```

The dynamic test box then collides with these chunk-owned static block colliders
instead of an older debug plane or a one-off local sample.

Non-full blocks are also converted when their Minecraft collision
`VoxelShape` exposes one or more boxes. Each shape box becomes a chunk-owned
static PhysX box.

## Chunk-Aware Caching

Built terrain colliders are tracked per scene and grouped by chunk position.
Re-running `/kinetic_assembly spawn_box` nearby reuses built chunk state and queues only
chunks that are not already built or dirty.

When a cached chunk unloads, `ChunkEvent.Unload` releases every static collider
tracked for that chunk.

`ChunkEvent.Load` is intentionally not used for immediate construction. NeoForge
notes that this event may fire before a chunk is promoted to FULL, and accessing
level data there can cause chunk loading deadlocks. The current safe path is to
queue terrain work from active physics/debug objects, build from the server
tick, and release colliders on unload.

## Block Updates

For already-known chunks, the runtime marks chunk collision dirty from these
events:

- `BlockEvent.NeighborNotifyEvent`
- `BlockEvent.BreakEvent`
- `BlockEvent.EntityPlaceEvent`
- `BlockEvent.FluidPlaceBlockEvent`

Dirty chunks are rebuilt through the same throttled server tick queue. Because a
single terrain collider may now represent multiple blocks, block break no longer
tries to remove one collider immediately. This keeps cached collision closer to
the Minecraft world after simple block changes, but it is still a best-effort
path; full correctness for every block state mutation will need a lower-level
hook or mixin later.

`/kinetic_assembly clear` removes the current level's physics objects and resets the block
collision cache for that level.

`/kinetic_assembly physics_status` reports `terrainColliders`, `terrainChunks`,
`terrainQueued`, `terrainBuilt`, `terrainDirty`, `lastTerrainChunks`,
`lastTerrainAdded`, `lastTerrainPartial`, and `lastTerrainBuildMs` so batched
collider count, partial-shape count, queue state, rebuild state, and chunk build
cost are visible while testing.

## Limitations

- Only full-block collision is converted.
- Partial voxel shapes are imported as their Minecraft shape boxes, but they are
  not batched across blocks.
- Fluids are ignored unless their block state exposes a collision shape.
- Collision is built by active physics/debug objects, not eager chunk-load
  handling.
- Block updates are best-effort for already-cached chunks.

The next refinement is improving partial block shape fidelity or replacing
separate batched static bodies with a compound or mesh representation.
