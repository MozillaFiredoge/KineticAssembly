# Render Proxy

M11 replaces the temporary ArmorStand debug proxy with a `BlockDisplay`.

## Current Behavior

`/kinetic_assembly spawn_box` creates:

- one PhysX dynamic box body;
- one scaled `Display.BlockDisplay` visual proxy;
- terrain chunk build work around the spawn point.

The PhysX body remains authoritative. The display entity is only a visual proxy
and does not participate in vanilla physics.

## Display Transform

The proxy uses a lime stained glass block state and a display transformation
derived from the PhysX pose and box half extents.

The block display model is naturally in local block coordinates from `0..1`.
The runtime treats the PhysX pose position as the box center, so it computes a
local translation that moves the rendered cube's center onto the PhysX body
center while applying the PhysX quaternion rotation.

```text
PhysX pose -> BlockDisplay position + transformation
```

This lets the proxy show full 3D rotation, including roll, and match the dynamic
box size exposed by `/kinetic_assembly spawn_box [size] [mass]`.

## Lifecycle

`ServerPhysicsRuntime` owns the binding:

- if the PhysX object disappears, the proxy is discarded;
- if the proxy disappears, a new `BlockDisplay` is spawned at the current PhysX
  pose;
- `/kinetic_assembly clear` discards proxies and closes current-level physics objects;
- server shutdown discards remaining proxies before scene cleanup.

## Diagnostics

`/kinetic_assembly physics_status` includes:

- `boundEntities`: currently bound visual proxies.
- `proxyRecreated`: total proxy recreations since the runtime was last cleared.
- `lastProxyRecreated`: proxies recreated by the latest sync pass.

For normal testing, `boundEntities` should match the number of active dynamic
debug bodies and `lastProxyRecreated` should usually be `0`.

## Limitations

- The proxy is still a debug visualization, not a gameplay entity.
- The proxy block state is fixed at lime stained glass.
- Only uniform box sizes are exposed through commands right now.
- The display transform is sent through vanilla display entity data; custom
  rendering may be needed later for richer physics objects.
