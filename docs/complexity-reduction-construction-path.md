# Complexity Reduction Construction Path

目标是先把 kinetic_assembly 的运行时复杂度压回一个可维护的主干，再继续做 PhysX geometry、GPU、soft body 等扩展。施工分两个阶段：

1. PhysX 与 Java 的物理交互层收口。
2. Assembly mixin 逻辑删减压缩。

两个阶段不能反过来做。Assembly mixin 现在之所以膨胀，是因为它们被迫自己读取物理状态、判断 pose、维护 support/tracking、补偿 packet/render 边界。第一阶段先提供稳定、便宜、只读的 runtime state；第二阶段再把 mixin 压成 vanilla hook adapter。

## Phase 1: PhysX/Java Interaction Layer

### 1.1 建立轻量物理状态 API

现状热路径常用 `MechanicsBodySnapshot`，而 snapshot 会读 pose、linear velocity、angular velocity。第一步先增加 pose-only API，后续再替换成批量 JNI read。

目标：

- `MechanicsWorld.pose(id)` 只读 body pose；
- `MechanicsBodySnapshot` 退回 debug、commands、persistence、status；
- assembly tracking/collision/aero/selection 不再调用 full snapshot。

验收：

```text
rg "world\\.snapshot" src/main/java/com/firedoge/kineticassembly/minecraft/assembly
```

结果只允许出现在保存、命令、显式 debug 或 body rebuild 路径里，不能出现在 collision target、tracking tick、entity move 热路径。

### 1.2 统一 tick 后状态刷新

PhysX step 完成后，Java 侧统一刷新 body state cache。热路径只消费 cache，不主动跨 JNI。

目标结构：

```text
ServerPhysicsRuntime.tick
  -> scene.advance(fixedStep)
  -> read active body states once
  -> publish MechanicsBodyState cache
  -> refresh AssemblyRuntimeState
```

过渡实现可以先逐 body 读 pose；最终实现必须是一 tick 一次 batch。

### 1.3 JNI 批量读取

Native 增加批量接口：

```text
readBodyStates(worldHandle, bodyHandles[], out[])
```

第一版可只输出 pose：

```text
body = [x, y, z, qx, qy, qz, qw]
```

第二版再扩展 full state：

```text
body = [pose7, linearVelocity3, angularVelocity3]
```

验收：

- N 个 assembly 每 tick 只有 1 次 body-state JNI read；
- collision、render、aero、selection 内没有 native pose read；
- profiling 能显示 batch body 数、batch read 耗时、cache 命中情况。

### 1.4 固定步长只保留一个权威

当前 server 路径实际由 `ServerPhysicsRuntime.SceneState` 做 accumulator。`PhysicsTicker` 是另一套 accumulator，native `create_world` 接收 fixed step 但不使用。

短期目标：

- server 只以 `SceneState` 为 fixed-step 权威；
- native `step_world` 只模拟传入的 fixed step；
- `PhysicsTicker` 标记为非 server 路径或删除候选。

长期二选一：

- Java authoritative：native 不保存 fixed step/maxSubSteps；
- native authoritative：native 保存 accumulator，Java 只传 wall-clock delta。

不能两套同时存在。

### 1.5 显式 mass / inertia / COM

当前 compound body 由大量 box shape 构成，并靠 PhysX 自动 `updateMassAndInertia`。这会让 body origin、COM、plot origin 的关系隐式化。

目标：

- Java 侧计算 mass、center of mass、inertia tensor；
- mechanics definition 携带 mass properties；
- PhysX native 显式 set mass、mass-space inertia、center-of-mass local pose；
- `bodyToPlotOrigin` 只由 runtime state 维护，rebuild/split 不允许隐式漂移。

### 1.6 PhysX geometry 改造放在本阶段末尾

只有在 Java 状态读取和 cache 收口后，才开始选择：

- `PxCustomGeometry` + native voxel chunk/octree；
- dirty section mesh/convex/heightfield cooking + rebuild budget。

否则 geometry 改造会被 Java 热路径反复 snapshot、loop blocks、重建 target 抵消。

## Phase 2: Assembly Mixin Compression

### 2.1 建立 AssemblyRuntimeState

统一运行态包含：

```text
id
bodyId / bodyHandle
poseFrame
localAggregateBounds
worldBounds
sweptBounds
collisionCache
localSpatialIndex
shapeEpoch
poseEpoch
renderEpoch
bodyToPlotOrigin
```

`PhysicsAssembly` 保留持久数据；`ClientTrackedAssembly` 和 server container 只持有或引用 runtime state，不能各自维护一套派生状态。

### 2.2 建立 service 边界

Mixin 不再承载业务逻辑，只转发到 service：

```text
AssemblyPoseSyncService
AssemblyCollisionService
AssemblyEntityMotor
AssemblyPacketCoordinateBridge
AssemblyRenderBridge
AssemblyPathingBridge
AssemblyLifecycleBridge
```

验收：

- mixin 方法通常只有 5 到 15 行；
- mixin 不拥有长期状态；
- 新 bug 修复优先改 service，不再往 mixin 条件树里补分支。

### 2.3 优先压 collision/tracking mixin

第一批处理：

- entity move/collision；
- living travel/jump/fall effects；
- server player carry；
- getOnPos/getInBlockState。

目标：

- support/tracking 只由 `AssemblyEntityMotor` 决定；
- collision query/solve 只由 `AssemblyCollisionService` 决定；
- mixin 只负责进入 vanilla hook 和写回结果。

### 2.4 再压 packet mixin

所有 plot/world packet 转换集中到 `AssemblyPacketCoordinateBridge`。

目标：

- player move packet；
- entity move/teleport packet；
- add-entity packet；
- client correction。

Mixin 不直接查 `collisionTargets`，不直接做 pose transform。

### 2.5 再压 render mixin

所有 render pose、light probe、contained/tracked entity render position 从 `AssemblyRenderBridge` 读取。

目标：

- pose 变化只改 transform；
- render mesh lifetime 不受 pose lifetime 影响；
- render mixin 不判断 support/tracking 策略。

### 2.6 最后处理长尾 mixin

最后处理 pathfinding、riding、projectile、attached entity、storage/chunk lifecycle。每类先做 inventory：

```text
hook point
owned state
owned logic
target service
keep / merge / delete
```

只删除已经变成纯转发或被 service 覆盖的 mixin。

## Immediate Cut

第一刀从 Phase 1.1 开始：

1. 新增 pose-only mechanics API。
2. `AssemblyTrackingSystem` 改用 pose-only API。
3. `ServerAssemblyContainer.collisionTargets` 改用 pose-only API。
4. `ServerPhysicsRuntime` 在 physics step 后刷新 mechanics state cache。
5. `MechanicsWorld.pose(id)` 和 `MechanicsWorld.snapshot(id)` 优先读取 cache。
6. `PhysicsWorld.readBodyStates(...)` 提供 Java fallback，PhysX backend 使用 JNI batch。
7. native `read_body_states(world, bodies[], out[])` 一次输出 pose、linear velocity、angular velocity。
8. `AssemblyRuntimeState` 开始接管 server runtime 派生状态。
9. pose 更新写入 runtime state，world/swept bounds 由 cached local aggregate AABB transform 得到。
10. `ServerAssemblyContainer.collisionTargets` 使用 runtime cached swept bounds 和 collision blocks。
11. server tick 在 entity bridge/tracking 前统一刷新 runtime states。
12. holding chunk 和 entity-inside 扫描读取 runtime bounds。
13. selection/pick 使用 pose-only transform，只在命中候选时读取 full body snapshot。
14. `collisionTargets` 增加 chunk-level runtime spatial index，index 未建立时保留全量 fallback。
15. 保留 full snapshot API 给 persistence/debug/commands/rebuild/aero。
16. 后续把 client tracked assembly、packet/render/pathing 长尾继续接到 runtime state。
