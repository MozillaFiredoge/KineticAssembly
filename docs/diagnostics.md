# Diagnostics

M6 exposes a small runtime diagnostics snapshot through:

```text
/kinetic_assembly physics_status
```

The command reports:

- `PhysX linked`: whether the configured physics backend can load the native
  bridge.
- `scenes`: active server physics scenes.
- `objects`: total tracked physics objects across scenes.
- `dynamicBoxes`: active dynamic debug boxes.
- `terrainColliders`: cached batched static terrain colliders.
- `terrainChunks`: chunks that currently own cached terrain colliders.
- `terrainQueued`: chunks waiting in the terrain build queue.
- `terrainBuilt`: chunks whose latest known state is built.
- `terrainDirty`: known chunks waiting for rebuild after block updates.
- `boundEntities`: visible debug proxies bound to physics objects.
- `proxyRecreated`: total debug proxy recreations since the runtime was last
  cleared.
- `lastProxyRecreated`: debug proxies recreated by the latest sync pass.
- `lastStepMs`: slowest last physics step across active scenes.
- `lastTerrainChunks`: chunks built by the last terrain build pass.
- `lastTerrainAdded`: new batched terrain colliders created by the last terrain
  build pass.
- `lastTerrainPartial`: partial-shape colliders created by the last terrain
  build pass.
- `lastTerrainBuildMs`: elapsed time for the last terrain build pass.
- `sample`: one dynamic object pose and linear velocity from the current level,
  if one exists.

This is intentionally command-based for now. It is enough to identify common
integration failures while the simulation path is still server-only:

- Native link failed: `PhysX linked=false`.
- Duplicate or leaked worlds: `scenes` grows unexpectedly.
- Object leaks: `objects` does not drop after `/kinetic_assembly clear`.
- Terrain cache leaks: `terrainColliders`, `terrainChunks`, or `terrainBuilt`
  stay high after chunk unload or `/kinetic_assembly clear`.
- Stuck terrain work: `terrainQueued` or `terrainDirty` stays high while the
  server is ticking.
- Expensive chunk terrain build: `lastTerrainBuildMs` spikes.
- Expensive simulation: `lastStepMs` spikes.

Debug rendering should wait until the collision model is broader than full
blocks and there is a client-side render path worth inspecting.
