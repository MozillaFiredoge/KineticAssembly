# Debug Object Management

M13 adds lightweight management commands for dynamic debug boxes.

## Commands

```text
/kinetic_assembly list_boxes
/kinetic_assembly list_boxes <limit>
/kinetic_assembly remove_nearest
/kinetic_assembly remove_nearest <maxDistance>
/kinetic_assembly remove_box <idPrefix>
```

`list_boxes` reports active dynamic boxes in the current level, sorted by
distance from the command source. Each row includes the full object id, pose
position, cached linear velocity, and distance.

`remove_nearest` removes the nearest dynamic debug box within the requested
range. The default range is 32 blocks.

`remove_box` removes a dynamic debug box by object id prefix. Ambiguous prefixes
are rejected so accidental deletion does not remove the wrong object.

## Lifecycle

Removing a debug box does both pieces of cleanup owned by
`ServerPhysicsRuntime`:

- closes the dynamic PhysX object through the scene;
- discards the bound `BlockDisplay` render proxy, if it still exists.

Terrain colliders are not affected. They remain chunk-owned and continue to be
released by chunk unload, dirty rebuild, `/kinetic_assembly clear`, or runtime shutdown.

## Why This Matters

The earlier debug workflow only had global cleanup through `/kinetic_assembly clear`.
Once multiple boxes can be spawned with different sizes, masses, and velocities,
testers need to remove individual objects without rebuilding all terrain
colliders in the dimension.

## Current Limits

- Object ids are volatile runtime ids and are not persisted.
- `remove_box` accepts id prefixes, not entity UUIDs.
- Listing is intentionally compact and command-text based; there is no visual
  selection overlay yet.
