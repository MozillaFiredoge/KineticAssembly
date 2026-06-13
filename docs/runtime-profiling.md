# Runtime Profiling

M16 adds coarse per-tick profiling to `/kinetic_assembly physics_status`.

## Fields

Timing fields are from the last server physics tick:

```text
lastTickMs
lastQueueActiveMs
lastTerrainProcessMs
lastStepPhaseMs
lastSyncEntitiesMs
syncObjectLookupMs
syncEntityLookupMs
syncRecreateMs
syncPoseReadMs
syncApplyMs
lastStepMs
```

- `lastTickMs`: total time spent inside `ServerPhysicsRuntime.tick`.
- `lastQueueActiveMs`: time spent scanning active physics objects and queueing
  nearby terrain chunks.
- `lastTerrainProcessMs`: wall-clock time spent processing the terrain build
  queue this tick.
- `lastStepPhaseMs`: total wall-clock time spent stepping all live scenes.
- `lastSyncEntitiesMs`: time spent syncing bound Minecraft debug entities.
- `syncObjectLookupMs`: object map lookup cost inside proxy sync.
- `syncEntityLookupMs`: Minecraft entity UUID lookup cost inside proxy sync.
- `syncRecreateMs`: cost of recreating missing `BlockDisplay` proxies.
- `syncPoseReadMs`: cost of reading physics poses for synced proxies.
- `syncApplyMs`: cost of applying proxy position/transformation updates.
  Unchanged position-only proxies are skipped before calling Minecraft entity
  setters.
- `lastStepMs`: maximum native scene step time among live scenes. This is the
  old metric and is narrower than `lastStepPhaseMs`.

Count fields help explain where Java/JNI work comes from:

```text
activeSnapshots
activeDynamics
activeTerrainQueued
activeTerrainSkippedHeight
activeTerrainScanLimit
syncedEntities
entityPoseSyncs
entitySyncLimit
syncRemoved
syncMissingEntities
proxySyncTransform
```

- `activeSnapshots`: number of dynamic object candidates available for
  active-object terrain queueing.
- `activeDynamics`: number of live dynamic boxes scanned this tick after the
  active terrain scan budget.
- `activeTerrainQueued`: terrain chunks newly queued by the active object scan.
- `activeTerrainSkippedHeight`: scanned dynamic boxes skipped because they are
  outside the Minecraft build height plus `activeTerrainVerticalMargin`.
- `activeTerrainScanLimit`: maximum dynamic boxes scanned per tick for active
  terrain queueing. This is controlled by `activeTerrainMaxScansPerTick`.
- `syncedEntities`: bound debug proxy entries processed this tick.
- `entityPoseSyncs`: proxy pose updates actually written to Minecraft
  entities. This can be lower than `syncedEntities` when resting proxies did
  not move.
- `entitySyncLimit`: maximum debug proxy pose updates allowed per tick. This is
  controlled by `debugProxyMaxSyncsPerTick`.
- `syncRemoved`: stale proxy bindings removed this tick.
- `syncMissingEntities`: missing or removed proxy entities encountered this
  tick. These usually trigger proxy recreation.
- `proxySyncTransform`: whether each proxy sync writes display transformation
  data. This is controlled by `debugProxySyncTransform`.

## Reading The Numbers

If `lastStepMs` is low but `lastTickMs` is high, the bottleneck is outside the
native PhysX step.

`activeSnapshots` is the active terrain candidate count. `activeDynamics` is
the number scanned this tick, capped by `activeTerrainScanLimit`; terrain
colliders are not scanned for active-object terrain queueing.

If `activeTerrainSkippedHeight` is high, many dynamic bodies have fallen far
outside the Minecraft build height and no longer request terrain colliders.

If `lastSyncEntitiesMs` and `entityPoseSyncs` are high, the visible
`BlockDisplay` debug proxies are dominating the server tick. Proxy pose sync is
round-robin limited by `entitySyncLimit`, currently 256 by default. Use
`/kinetic_assembly spawn_stress_grid` for physics-only pressure tests.

Proxy setup still uses NBT when a `BlockDisplay` is first created, but normal
per-tick pose updates avoid `Entity#load`. By default, per-tick sync only
updates position and skips `setPos` when the proxy is already at the target
position; set `debugProxySyncTransform=true` if rotation visualization is more
important than server tick cost.

If `lastSyncEntitiesMs` is high while `entityPoseSyncs` is low, inspect the
sync subfields. High `syncRecreateMs` or `syncMissingEntities` means proxies
are being recreated instead of merely updated. High `syncApplyMs` points at
Minecraft entity data/tracker/network update cost.

If `lastStepPhaseMs` tracks `lastStepMs`, the native solver step itself is the
main cost. Compare CPU and GPU only after the Java/Minecraft overhead is under
control.
