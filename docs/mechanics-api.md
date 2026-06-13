# Mechanics API

M18 starts a public mechanics layer for other simulation mods.

The entry point is:

```java
MechanicsApi mechanics = KineticAssembly.api();
MechanicsWorld world = mechanics.world(serverLevel);
```

This layer is intentionally above the raw PhysX backend and above the current
debug `BlockDisplay` proxy path. External systems should treat PhysX scenes,
native handles, and debug proxies as implementation details.

Assembly is the gameplay-facing mechanics layer. Dependent mods should use this
API for movable structures and physical systems instead of depending on
`minecraft.assembly`, native bridge, backend, render, network, or mixin classes.

The compile-time API artifact is described in `docs/api-artifacts.md`.

## Current Surface

The first API slice supports server-side rigid dynamic boxes:

```java
MechanicsBodySnapshot body = world.createDynamicBox(
        MechanicsBoxDefinition.gameplayDynamicBox(
                new PhysicsPose(position, PhysicsQuaternion.IDENTITY),
                new PhysicsVector(0.5D, 0.5D, 0.5D),
                1.0F
        )
);

world.applyLinearImpulse(body.id(), new PhysicsVector(0.0D, 2.0D, 0.0D));
world.applyAngularImpulse(body.id(), new PhysicsVector(0.0D, 1.0D, 0.0D));
Optional<MechanicsBodySnapshot> latest = world.snapshot(body.id());
```

Available operations:

- create a gameplay dynamic box;
- create a gameplay dynamic box with a caller-supplied stable body id;
- create a fixed joint between two mechanics bodies;
- create a distance joint between two mechanics bodies;
- list mechanics-owned body snapshots in one `ServerLevel`;
- list mechanics-owned joint snapshots in one `ServerLevel`;
- read one body snapshot by stable id;
- set linear velocity;
- set angular velocity;
- apply linear and angular impulses;
- remove the body;
- remove a joint.

## Sandbox Commands

M18.1 adds command coverage for this public API surface:

```text
/kinetic_assembly mechanics spawn_box [size] [mass] [debugProxy]
/kinetic_assembly mechanics list [limit]
/kinetic_assembly mechanics impulse <idPrefix> <x> <y> <z>
/kinetic_assembly mechanics torque_impulse <idPrefix> <x> <y> <z>
/kinetic_assembly mechanics remove <idPrefix>
/kinetic_assembly mechanics show <idPrefix>
/kinetic_assembly mechanics hide <idPrefix>
/kinetic_assembly mechanics joint_fixed <firstBodyPrefix> <secondBodyPrefix>
/kinetic_assembly mechanics joint_fixed <firstBodyPrefix> <secondBodyPrefix> <anchorX> <anchorY> <anchorZ>
/kinetic_assembly mechanics joint_revolute <firstBodyPrefix> <secondBodyPrefix> <anchorX> <anchorY> <anchorZ> <axisX> <axisY> <axisZ>
/kinetic_assembly mechanics joint_prismatic <firstBodyPrefix> <secondBodyPrefix> <anchorX> <anchorY> <anchorZ> <axisX> <axisY> <axisZ>
/kinetic_assembly mechanics joint_distance <firstBodyPrefix> <secondBodyPrefix> <minDistance> <maxDistance>
/kinetic_assembly mechanics joint_distance <firstBodyPrefix> <secondBodyPrefix> <minDistance> <maxDistance> <stiffness> <damping>
/kinetic_assembly mechanics joint_distance_anchors <firstBodyPrefix> <secondBodyPrefix> <firstAnchorX> <firstAnchorY> <firstAnchorZ> <secondAnchorX> <secondAnchorY> <secondAnchorZ> <minDistance> <maxDistance>
/kinetic_assembly mechanics joint_distance_anchors <firstBodyPrefix> <secondBodyPrefix> <firstAnchorX> <firstAnchorY> <firstAnchorZ> <secondAnchorX> <secondAnchorY> <secondAnchorZ> <minDistance> <maxDistance> <stiffness> <damping>
/kinetic_assembly mechanics joints [limit]
/kinetic_assembly mechanics joint_remove <jointPrefix>
```

The spawn/list/impulse/remove path is an API smoke test: it enters through
`KineticAssembly.api()` and works with `MechanicsWorld`, not debug body commands.

## Ownership

Bodies created through the mechanics API are tracked separately from debug and
stress bodies. They do not automatically create `BlockDisplay` render proxies.
Rendering, gameplay conversion, and lifecycle policy should be layered on top of
this API.

Callers that need joints to survive a save/load cycle should persist their
logical body ids and recreate mechanics bodies with the stable-id overloads:

```java
MechanicsBodyId bodyId = new MechanicsBodyId(savedUuid);
MechanicsBodySnapshot body = world.createDynamicBox(bodyId, definition);
```

`MechanicsBodySnapshot` reports:

- stable `MechanicsBodyId`;
- Minecraft level key;
- body type and role;
- PhysX pose;
- linear velocity as tracked by the current backend wrapper;
- angular velocity as tracked by the current backend wrapper;
- half extents and mass;
- closed state.

## Debug Proxy Adapter

M18.2 adds an optional debug proxy adapter for mechanics bodies:

- `spawn_box ... true` creates the body and immediately shows a debug proxy.
- `show <idPrefix>` attaches a `BlockDisplay` debug proxy to an existing
  mechanics body.
- `hide <idPrefix>` removes the proxy without removing the mechanics body.
- `remove <idPrefix>` removes the body and also discards any bound debug proxy.

The proxy is intentionally not part of the body model. It is a visualization
adapter backed by the same round-robin sync path as other debug proxies.

M20 extends the adapter internally so gameplay prototypes can render a mechanics
body with a specific block state. Detached blocks use this path to show the
original Minecraft block instead of the generic debug material.

## Block Density

Detached blocks and assembled assemblies default to density-derived mass when no
explicit command mass is supplied. The built-in defaults live in:

```text
src/main/resources/kinetic_assembly/default_block_density.json
```

On server start, KineticAssembly loads those defaults and creates a user-editable copy
if missing:

```text
config/kinetic_assembly/block_density.json
```

The first density format is intentionally small:

```json
{
  "version": 1,
  "mass_scale": 0.05,
  "default_density": 1000.0,
  "blocks": {
    "minecraft:stone": 2400.0
  },
  "tags": {
    "minecraft:logs": 700.0
  }
}
```

Resolution order is exact block id, then block tag, then `default_density`.
Actual mass is:

```text
collision volume * density * mass_scale
```

For example, with `mass_scale=0.05`, a full stone block at density `2400.0`
becomes `120 kg`. Commands that still pass a `mass` argument use that value as
an override; command output marks masses as `(auto)` or `(override)`.

Assembled assemblies also derive body-local center of mass and diagonal inertia
from the per-block collision boxes and resolved densities. Explicit command
mass overrides preserve that density distribution and scale the total mass and
inertia to the requested value.

## Joint Persistence

KineticAssembly persists mechanics joint definitions in level `SavedData`. The stored
definition is physical, not semantic: joint id, joint type, two mechanics body
ids, `collideConnected`, and body-local frames or anchors.

On load, KineticAssembly attempts to recreate saved joints after assembly restore and
before the physics step. If either referenced body is not present yet, the joint
record is kept and restore is retried on later ticks. If both bodies are present
but the joint was removed or can no longer be recreated, the record is dropped
on the next capture.

KineticAssembly does not persist arbitrary external mod assemblies. External mods still
own their gameplay object identity and must recreate their bodies with stable
`MechanicsBodyId` values if they want persisted joints to reconnect. Built-in
assemblies now store their mechanics body id so joints between restored assemblies
can be reattached.

## Coupling Direction

Aerodynamics, electromagnetics, vehicle logic, and RL experiments should couple
through this mechanics layer instead of through native handles:

- Aerodynamics can sample wind and call `applyLinearImpulse` and
  `applyAngularImpulse`.
- Electromagnetics can translate field force/torque probes into mechanics
  impulses or forces.
- Vehicle code can own higher-level assemblies while KineticAssembly owns rigid body
  stepping and collision.
- RL tooling can snapshot state, apply actions, and reset bodies without
  depending on debug entities.

## Current Limits

- The first API slice only exposes dynamic boxes.
- `applyLinearImpulse` and `applyAngularImpulse` are native PhysX impulse calls
  for the PhysX backend. They require a dynamic body and fail for static bodies.
- Fixed joints currently preserve the two bodies' relative pose at creation time.
  The mechanics API also exposes a world-frame/world-anchor creation path that
  converts the frame to each body's local joint frame.
- Revolute joints use a world frame whose local X axis is the hinge axis. The
  mechanics API also exposes a world-anchor/world-axis creation path. Limits and
  motors are future extensions.
- Prismatic joints use a world frame whose local X axis is the slide axis. The
  mechanics API also exposes a world-anchor/world-axis creation path. Translation
  limits and drives are future extensions.
- Distance joints currently connect the two body origins. They expose min/max
  distance, optional spring stiffness, and optional damping. The mechanics API
  also exposes a world-anchor creation path with one anchor point on each body.
- Joint creation overloads default to `collideConnected=false`. Other mods can
  pass `collideConnected=true` through the mechanics API when the two connected
  bodies should still collide with each other.
- Snapshots are server-side and per-level.
- Soft body attachments, spherical/D6 joints, joint limits/motors beyond
  distance constraints, sleep state, contact events, and deterministic episode
  reset are future milestones.
