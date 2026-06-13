# Assembly Mainline

这份文档解释 kinetic_assembly 的 assembly 系统应该如何工作，以及我们为什么要按这条主线推进。

目标读者是不熟悉 Minecraft 原版代码的人。这里不假设你已经知道 `Entity.move`、chunk light、client packet interpolation 等细节，而是先说明我们需要接触哪些原版边界，以及每个边界应该由哪个 kinetic_assembly 子系统负责。

## One Sentence

Assembly 是一个静态 plot，被一个稳定的 pose frame 投影到主世界；玩家和实体仍然生活在 vanilla world 坐标里，但可以通过明确的 tracking/support 状态跟随某个 assembly。

```text
plot/local blocks
        |
        | AssemblyPoseFrame
        v
visible moving structure in world

entity world position
        |
        | EntityAssemblyState
        v
supported / carried by one assembly
```

## Problem We Are Solving

Minecraft 原版假设世界是一个静态整数方块网格：

- 方块碰撞在 world 坐标里。
- `onGround` 来自静态方块碰撞。
- 玩家移动包是 world 坐标。
- chunk render mesh 通常只在方块或光照变化时重建。
- 光照和 block entity 都假设方块在固定 chunk 里。

Assembly 打破了这个假设，因为方块数据静态存放在 plot chunk 里，但可见位置由物理 pose 决定。

因此我们的工作不是让 vanilla “自然理解移动方块”，而是在每个边界做明确翻译：

```text
plot <-> local <-> world
```

## Coordinate Spaces

### World Coordinate

玩家、普通实体、camera、vanilla movement packet 使用的主世界坐标。

示例：

```text
player position = (264.8, -59.0, 276.2)
```

### Plot Coordinate

Assembly 方块实际存放在 vanilla chunk 里的坐标。Vanilla chunk、block state、block entity、light engine 看到的是 plot coordinate。

这个坐标是静态的。Assembly 移动时，plot 里的方块不会真的移动。

### Body Local Coordinate

物理和碰撞使用的 assembly 局部坐标。它和 PhysX body pose 一起决定方块在 world 中的可见位置。

```text
world = pose.localToWorld(bodyLocal)
bodyLocal = pose.worldToLocal(world)
```

## Core Ownership Rules

这些规则比具体实现更重要。后续 bug 修复必须服从它们。

### Raw PhysX Pose Has One Owner

Raw PhysX pose 只能进入 `AssemblyPoseService`。

`AssemblyPoseService` 负责：

- 读取 raw physics pose。
- 过滤静止抖动。
- 生成 `AssemblyPoseFrame(previous, current, epoch)`。
- 保证 gameplay 看到的是稳定 pose，而不是 raw PhysX jitter。

其他系统不应该直接使用 raw PhysX pose。

### Tracking State Has One Owner

玩家和实体的 assembly tracking/support 状态只能由 `AssemblyEntityMotor` 决定。

它负责：

- 站在哪个 assembly 上。
- 什么时候继承 assembly 位移。
- 什么时候跳跃断开 support。
- 多个 assembly 同时接触时保留哪个 support。
- 什么时候清理 stale tracking id。
- sneak 时如何裁剪水平移动。

Mixin 只能把 vanilla hook 转发给 motor，不能自己决定 tracking state。

### Mesh Lifetime Is Not Pose Lifetime

Render mesh 是 plot 几何缓存。Pose 是 transform。

```text
block/light changed -> rebuild mesh
pose changed        -> update transform only
```

Pose update 不应该 invalidate render mesh。否则大量移动 assembly 会导致 VBO 反复重建，FPS 会急剧下降。

### Render Pose Is Not Gameplay Pose

Gameplay 使用 authoritative `AssemblyPoseFrame`。

Render 使用客户端 snapshot interpolation 后的 render pose。

这两个不应该混在一起：

```text
gameplay pose: collision, support, network authority
render pose: smooth visual presentation
```

### Packet Coordinate Space Has One Owner

玩家移动包和实体同步包的 local/world 转换只能由 network protocol 层处理。

普通站立 support 不应该随便切换成 plot-space movement packet。只有明确处于 `PACKET_LOCAL` 或 plot-contained 状态时，才使用 plot/local packet path。

## Main Systems

### AssemblyPoseService

职责：

- raw PhysX pose -> stable `AssemblyPoseFrame`
- close-enough filtering
- epoch management
- world/swept bounds update input

禁止：

- 直接处理玩家。
- 直接重建 render mesh。
- 直接发网络包。

### AssemblyPlotStorage

职责：

- 管理 reserved plot chunks。
- 存储 block state、block entity、section data。
- 处理 block mutation。
- 提供 plot coordinate 查询。

禁止：

- 关心 assembly 可见 world pose。
- 直接处理玩家 support。

### AssemblyRenderSystem

职责：

- 按 render layer 构建 plot mesh。
- 缓存 VBO。
- 每帧用 render pose 施加 transform。
- 渲染 block entity、selection outline、breaking overlay。

关键取舍：

```text
pose 变化只改矩阵，不重建 mesh。
```

### AssemblyEntityMotor

职责：

- entity tracking/support state
- carry motion
- jump break
- support reacquire cooldown
- multi-assembly hysteresis
- support anchor jitter filtering
- future sneak edge guard

它不应该做：

- render
- light
- raw PhysX stepping
- chunk storage

### AssemblyCollisionService

职责：

- assembly local collision query
- SAT/OBB collision
- support probe
- step-up probe
- scaffolding/slab/stair shape policy

它输出 collision result，但不应该拥有长期 tracking state。长期 state 属于 `AssemblyEntityMotor`。

### AssemblyNetworkSync

职责：

- server -> client pose snapshots
- client snapshot buffer
- render-time interpolation
- player movement packet local/world conversion
- tracked entity packet local/world conversion

关键取舍：

```text
直接使用最新 pose packet 做 partialTick lerp 不够。
需要 snapshot buffer + interpolation delay。
```

### AssemblyLightingBridge

职责：

- plot light
- world/environment light
- mesh/light dirty propagation

关键取舍：

```text
light changed 可以 dirty mesh。
pose changed 默认不能 dirty mesh。
```

## Vanilla Boundary Map

不需要一次读懂整个 Minecraft。只需要理解这些接触面。

### Entity Movement

主要 vanilla 边界：

- `Entity.move`
- `LivingEntity.travel`
- `LivingEntity.jumpFromGround`
- `Entity.getOnPos`
- `Entity.getInBlockState`

我们在这里处理：

- assembly collision
- support state
- carry
- jump break
- sneak edge guard
- fall/sprint particles

### Player Networking

主要 vanilla 边界：

- `LocalPlayer.sendPosition`
- `ServerGamePacketListenerImpl.handleMovePlayer`
- `ServerboundMovePlayerPacket`

我们在这里处理：

- world packet
- plot/local packet
- server rewrite back to world
- moved-wrongly policy

### Entity Networking

主要 vanilla 边界：

- `ServerEntity.sendChanges`
- `ClientPacketListener.handleMoveEntity`
- `ClientPacketListener.handleTeleportEntity`
- `Entity.recreateFromPacket`

我们在这里处理：

- tracked entity local packet
- plot-contained entity packet
- client plot lerp
- add-entity ordering races

### Rendering

主要 vanilla 边界：

- level render stage
- block render dispatcher
- block entity render dispatcher
- selection outline
- breaking overlay

我们在这里处理：

- plot mesh build
- root pose transform
- render interpolation
- culling

### Chunk And Light

主要 vanilla 边界：

- chunk load/unload packets
- plot chunk holder
- light engine updates
- section dirty notifications

我们在这里处理：

- plot chunk ownership
- light dirty
- render mesh dirty
- client chunk availability

## Current Symptoms And Architectural Meaning

### Many Assemblies Drop FPS

Likely architectural cause:

```text
pose update invalidates render mesh
```

Correct direction:

```text
separate pose transform from mesh lifetime
add frustum culling and dirty queues
```

### Sneak Freezes Player

Likely architectural cause:

```text
support/carry state is being used as edge protection
```

Correct direction:

```text
implement assembly-local sneak edge guard
only clamp horizontal motion
do not freeze position
```

### Client Movement Looks Discontinuous

Likely architectural cause:

```text
client renders latest pose packet directly
no network snapshot buffer
```

Correct direction:

```text
server sends timed snapshots
client renders from delayed interpolation buffer
```

## Non-Goals For The Current Cut

These are important, but not part of the immediate mainline:

- full vehicle/pilot custom orientation
- pathfinding parity
- projectile parity
- every block entity behavior
- perfect lighting
- Sodium/Iris optimized backend
- complete Create/Aeronautics-level compatibility
- arbitrary entity ecosystem support

Trying to solve these before the mainline is stable will make the code grow sideways again.

## Mainline Work Order

### Phase 1: Render Boundary

Goal:

```text
pose update does not rebuild mesh
```

Work:

- split render mesh dirty reasons from pose update;
- keep VBOs alive across pose changes;
- add culling before drawing each assembly;
- avoid immediate rebuild paths where possible;
- treat translucent sorting as a separate render problem.

Validation:

- 1 assembly moving: FPS stable.
- 40 assemblies static: FPS acceptable.
- 40 assemblies moving: no mesh rebuild storm.

### Phase 2: Network Snapshot Interpolation

Goal:

```text
client render pose is smooth even with irregular packet timing
```

Work:

- add server snapshot sequence/time;
- client buffers snapshots per assembly;
- render uses delayed interpolation time;
- gameplay pose and render pose become separate reads.

Validation:

- moving assembly appears continuous at high FPS;
- low packet jitter does not produce frame-by-frame snapping;
- server correction does not teleport visual pose unless state actually jumps.

### Phase 3: Sneak Edge Guard

Goal:

```text
shift prevents walking off a assembly edge without freezing the player
```

Work:

- detect `shiftKeyDown && SUPPORTED`;
- transform candidate feet support into assembly local frame;
- back off X/Z motion like vanilla edge protection;
- do not mutate support anchor except through normal motor update.

Validation:

- player can sneak-walk on assembly;
- player stops at edge;
- releasing shift resumes normal movement;
- jump is not blocked.

### Phase 4: Entity Motor Cleanup

Goal:

```text
all long-lived entity/assembly state is owned by AssemblyEntityMotor
```

Work:

- keep mixins as adapters;
- move remaining inherited velocity policy into motor or a motor-adjacent effects service;
- add assertions that no other code writes tracking state directly.

Validation:

- `trackingAssemblyId` changes are traceable through motor;
- support loss and support switch are explainable from logs.

### Phase 5: Lighting Boundary

Goal:

```text
assembly geometry update does not turn black, and pose update does not rebuild all lighted meshes unnecessarily
```

Work:

- separate plot light dirty from pose dirty;
- define how world/environment light is sampled;
- decide when light requires mesh rebuild versus shader/uniform update.

## Rules For Future Changes

Before changing code for a bug, answer these questions:

1. Which subsystem owns this state?
2. Is this a gameplay pose issue or a render pose issue?
3. Is this plot coordinate, body-local coordinate, or world coordinate?
4. Is this a vanilla boundary problem?
5. Is the fix preserving the mainline, or adding another exception?

If the answer is unclear, do not patch yet.

## Red Flags

These usually mean the code is drifting away from the mainline:

- A mixin contains complex business logic.
- A pose update rebuilds mesh.
- Raw PhysX pose is used outside `AssemblyPoseService`.
- Player support state is written outside `AssemblyEntityMotor`.
- A packet handler does ad hoc local/world math.
- A render path updates gameplay state.
- A collision function stores long-lived tracking state.
- A bug fix depends on one specific log line instead of an invariant.

## Minimal Test Matrix

Every mainline change should be checked against these cases:

### Render

- 1 static assembly
- 1 moving assembly
- 40 static assemblies
- 40 moving assemblies
- block update inside assembly
- light update near assembly

### Player

- stand still on flat assembly block
- walk on flat assembly block
- jump on assembly block
- sneak on assembly block
- walk to edge while sneaking
- fall near assembly edge
- stand between two close assemblies

### Network

- integrated server
- dedicated server
- high FPS client
- low FPS client
- moving assembly with irregular packet arrival
- server correction while tracking

### Coordinates

- plot-contained entity
- supported entity in world
- packet-local state
- removed assembly projection fallback

## Glossary

### Assembly

A moving block structure represented as static plot data plus a world pose.

### Plot

Reserved vanilla chunk area where assembly blocks are stored.

### Body Local

Local coordinate system used by physics and collision.

### Pose Frame

Stable previous/current pose pair used by gameplay.

### Render Pose

Interpolated pose used only for visual rendering.

### Tracking

The entity is associated with a assembly.

### Supported

The entity is standing on a assembly and should inherit its movement.

### Packet Local

The entity/player packet is intentionally expressed in plot/local coordinates.

### Carry

Motion inherited from the assembly pose changing between frames.

### Sneak Edge Guard

Assembly-local version of vanilla sneak edge protection.

## Current Direction

The project should not continue as "patch whatever broke last".

The direction is:

```text
1. establish subsystem ownership
2. separate gameplay pose from render pose
3. separate mesh lifetime from pose lifetime
4. centralize entity support in AssemblyEntityMotor
5. add proper client snapshot interpolation
6. only then expand compatibility
```

This is the mainline. Changes that do not fit this line should be deferred or isolated behind a feature flag.
