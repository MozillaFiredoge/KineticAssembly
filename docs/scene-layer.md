# Scene/Object Layer

The scene/object layer is the lightweight boundary between Minecraft-facing code
and the raw physics backend.

This layer keeps Minecraft gameplay code from directly owning native physics
handles. It does not yet provide isolated chunk grids, moving block storage,
client interpolation, or plot allocation.

## Current Scope

- `PhysicsSceneManager` owns server physics scenes by stable scene key.
- `ServerPhysicsScene` owns one `PhysicsWorld`.
- `PhysicsObject` is the Minecraft-facing handle for simulated objects.
- `PhysicsObjectId` is stable and suitable for future entity/network mapping.
- `PhysicsObjectSnapshot` captures pose, linear velocity, angular velocity,
  type, id, and closed state.
- `RigidPhysicsObject` wraps backend `PhysicsBody` and optional owned shape
  without exposing native handles.

## Current Object Types

- `STATIC_PLANE`
- `STATIC_BLOCK`
- `DYNAMIC_BOX`

These are deliberately narrow so the first Minecraft integration can focus on
server tick, entity synchronization, and simple full-block terrain collision
before richer terrain shape handling and moving structures.

## Lifecycle

Scene-level cleanup is authoritative:

```text
object.close()
scene.close()
manager.close()
```

Closing a scene closes all remaining objects and then closes its physics world.
Removing an object by `PhysicsObjectId` closes the object and removes it from the
scene index.

## Server Integration

`ServerPhysicsRuntime` owns the active server scenes. It creates scenes for
server levels, steps them from `ServerTickEvent.Post`, and closes them when the
server stops.

The current command surface is:

```text
/kinetic_assembly spawn_box
/kinetic_assembly set_velocity
/kinetic_assembly physics_status
/kinetic_assembly clear
```

`spawn_box` creates a visible `BlockDisplay` debug proxy bound to a dynamic
physics box and queues terrain chunks around the spawn point. The proxy binding
and terrain queue are owned by `ServerPhysicsRuntime`, not by the raw scene, so
the scene layer remains independent of Minecraft entity implementation details.

`set_velocity` applies a direct linear velocity override to the nearest dynamic
debug box in the current level.

`physics_status` prints native link status, scene count, object count, terrain
collider count, terrain chunk count, bound entity count, last step time, last
terrain build time, terrain queue state, dynamic box count, and a sample dynamic
object pose/velocity from the current level.

## Why Not Full Assemblies Yet

Full assemblies become useful when the mod needs moving block assemblies, local
chunk grids, serialization of internal block storage, bounds tracking, and client
render interpolation. The current target is smaller: a visible Minecraft entity
following a PhysX dynamic body. This scene layer gives that target a clean API
without committing to the full assembly data model prematurely.
