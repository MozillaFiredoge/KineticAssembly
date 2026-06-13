# Entity Synchronization

M4 introduced a minimal visible entity binding to prove that Minecraft objects
can be driven by PhysX simulation. M11 replaces the original ArmorStand proxy
with a `BlockDisplay` render proxy.

## Current Behavior

`/kinetic_assembly spawn_box` creates:

- queued chunk terrain collision around the spawn point;
- a dynamic PhysX box two blocks above the command source;
- a visible `BlockDisplay` debug proxy bound to that dynamic box.

On each `ServerTickEvent.Post`, `ServerPhysicsRuntime` steps the physics scenes
and then synchronizes bound entities:

```text
PhysicsObject pose -> BlockDisplay position + transformation
```

The proxy has no vanilla physics and zero vanilla velocity so PhysX remains the
single source of motion. Its display scale follows the spawned dynamic box size.
If the `BlockDisplay` is missing on a later tick, the runtime recreates it at
the current PhysX pose instead of destroying the physics object; this keeps the
simulation state authoritative during debug proxy failures.

## Cleanup

- `/kinetic_assembly clear` removes bound debug entities in the current level and closes the
  associated physics objects.
- `ServerStoppingEvent` discards remaining bound entities before scene cleanup.
- `ServerStoppedEvent` performs a final runtime cleanup fallback.

## Limitations

- The visible entity is a debug `BlockDisplay`, not a gameplay entity.
- Rotation is synchronized through the display transformation.
- Only full-block terrain collision is converted.
- Entity synchronization is server-side only; client rendering is whatever
  vanilla entity tracking provides.

The next refinement after this is adding configurable dynamic body size/mass or
moving toward a custom gameplay entity.
