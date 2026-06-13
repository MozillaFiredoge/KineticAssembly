# Assembly Query Bridge

M23.4.1 adds the first read-only query bridge from vanilla player view space
into moving assembly block space.

The bridge answers one question directly: which block inside a physics assembly
is the player looking at? M23.4.2 builds on the same query path for explicit
command-gated block removal.

## Command

```text
/kinetic_assembly assembly pick [maxDistance]
```

The command requires a player command source. It uses the player's eye position
and look direction, transforms the ray into each assembly body's local space,
tests against captured block collision bounds, and reports the closest hit.

The output includes:

- assembly id;
- mechanics body id;
- section-local block position;
- original source block position;
- block id;
- world hit position;
- body-local hit position;
- hit distance.

## Transform Model

`AssemblyTransform` maps between host-world coordinates and assembly body-local
coordinates using the mechanics body pose:

```text
world position = body position + body rotation * body-local position
body-local position = inverse(body rotation) * (world position - body position)
```

For M23.4.1, each captured block is queried as an AABB in body-local space. The
AABB origin comes from `AssemblyBlock.visualLocalOrigin`, which is already the
coordinate used by the current `BlockDisplay` visual proxy sync. This keeps pick
results aligned with the visible prototype.

## Current Limits

- `/kinetic_assembly assembly pick` is read-only. `/kinetic_assembly assembly break` mutates storage
  through the same query path.
- The block test uses each block collision shape's bounding box, not the full
  voxel shape.
- Place/use routing is not implemented yet.
- The current physics collider is still the aggregate mechanics body, so query
  precision can be better than solver collision precision until M23.5.
