# Assembly Milestones 2

目标不是复刻任何外部实现，而是逐步把 kinetic_assembly 的 assembly 接进 Minecraft 已有的 chunk、tick、交互和物理同步入口。物理几何、断裂检测、terrain obstacle、VoxelShape 重构继续沿用 kinetic_assembly 现有架构。

## M1: Plot Chunk 活化

目标：让原版认为 assembly 的 plot chunk 是 loaded/ticking chunk。

验收：
- 红石线、中继器、观察者、按钮、拉杆在 assembly 内能正常 tick。
- `ServerChunkCache` / `ChunkMap` 查询能拿到 plot chunk。
- plot chunk 创建、重建、移除时不会残留 tick container 或 chunk holder。

当前状态：已有第一版实现。`shouldTickBlocksAt`、`ServerChunkCache.isPositionTicking`、`ServerLevel.isPositionTickingWithEntitiesLoaded` 都会放行 plot chunk，避免 scheduled tick 因 entity manager 未确认 loaded 而不执行；assembly 初次装载后会对 plot 内方块补一次邻居更新，并在后续数个 server tick 内继续做短暂自举更新；自举更新会同时模拟输入侧每个邻居变化和输出侧邻居通知；中继器/比较器会在自举期补一个短 block tick，并直接执行一次 vanilla tick 判断，用于启动红石 loop。需要游戏内验证红石 scheduled tick。

## M2: Scheduled Tick 迁移

目标：捕获 assembly 时，把源世界里已经排队的 block/fluid ticks 搬到 plot chunk。

验收：
- 正在延迟中的中继器、比较器、水/岩浆更新在 assembly 化后不丢 tick。
- 捕获前已排队的 tick 能映射到 plot 坐标继续执行。
- 移除或还原 assembly 时不会留下错误 tick。

当前状态：已有第一版初次 assemble 迁移，使用 vanilla `LevelTicks.copyArea` / `clearArea` 把源区域的 block/fluid scheduled ticks 移到 plot 坐标；plot chunk 重建时会保留旧 tick 队列，restore 时会把 plot tick 迁回源坐标，assembly 删除时会清理 plot tick。需要游戏内验证延迟红石和流体更新。

## M3: 实体感知映射

目标：让 plot chunk 里的方块能感知真实世界坐标里的玩家/实体。

验收：
- 玩家站在移动 assembly 上能触发压力板。
- 绊线、探测铁轨等依赖实体查询的方块具备同类基础能力。
- 只在 plot chunk 查询实体时做投影，避免影响普通世界查询。

当前状态：已有第一版 `entityInside` 投影 tick 和 plot AABB 实体查询投影，需要游戏内验证压力板、绊线等具体方块。

## M4: 方块状态与物理同步查漏

目标：plot chunk 中方块变化后，物理体、断裂结构、terrain obstacle、显示 chunk 保持一致。

验收：
- 破坏、放置、红石改变方块状态后，物理体与 plot chunk 方块一致。
- 无碰撞方块变化不会破坏 assembly 物理体。
- assembly 拆分、移除、还原时不会留下旧物理或旧显示状态。

当前状态：开始补一致性边界。物理 body 重建改为 replacement body 和 plot chunk 重建都成功后再替换旧 body；body id 变化后会刷新 debug visual，并同时按旧 body pose 与新 body pose 扫描清理 orphan visual；破坏路径不再提前局部删除 visual，而是由 rebuild/remove 统一清理和重建；debug visual 启用状态改为显式记录，实体会写入 assembly 专属 tag，清理时会额外扫描并删除 orphan visual，并兼容清理旧 custom name visual；客户端收到 plot chunk 全量包时会替换整个 `LevelChunk` 对象；plot chunk rebuild 的 full chunk resync 改为 tick 末尾合并发送，避免同一 vanilla/redstone 更新调用栈里的后续 block update 覆盖最终状态；包含 `MOVING_PISTON` 的临时 chunk rebuild 不发送 full chunk resync，也跳过普通单块变化广播，避免客户端通过普通方块包收到无法还原 BlockEntity 的 moving piston；assembly 拆分时会把原 plot chunk 中对应方块的 block/fluid scheduled ticks 迁到 child plot；活塞 block event 广播会按物理位置计算可见范围，活塞 base/head/moving piston 已加入依赖删除。需要游戏内验证破坏、放置、拆分、restore/remove、活塞以及 visual 是否残留。

补充查漏：assembly 方块碰撞会从 plot/物理 body 投影成真实世界里的临时 `VoxelShape`，追加到 server/client 两侧 `CollisionGetter#getBlockCollisions`，让玩家和普通实体能先按 vanilla 移动管线站在 assembly 上。当前是 AABB 投影近似，能覆盖基础站立/阻挡；快速移动平台、旋转平台的速度继承和精确 OBB 碰撞仍属于后续查漏。

## M5: BlockEntity 兼容

目标：常见 block entity 在 assembly 内能稳定保存状态、tick、重建后不丢关键数据。

验收：
- 箱子、告示牌、漏斗、比较器相关方块至少不明显错乱。
- plot chunk 重建后 block entity ticker 不重复、不丢失。
- block entity NBT 与运行时状态的同步策略明确。

当前状态：开始覆盖发射器/投掷器这类 block entity 交互。plot 坐标中通过 `addFreshEntity` 生成的物品、投射物会在加入世界前映射到 assembly 物理位置，并旋转初速度方向；assembly 破坏路径现在会区分 vanilla plot 破坏和 assembly 自身破坏，前者补容器库存掉落，后者补完整方块掉落与容器库存掉落，掉落实体继续通过 plot->world 投影落到物理位置；捕获物理化源方块时会先保存 block entity NBT，再从源 chunk 移除 block entity 后置空方块，避免箱子、投掷器、发射器在 assembly 化前按 vanilla 破坏掉出库存；plot 内 block entity 调用 `setChanged()` 时会把最新 NBT 同步回 assembly 块记录，避免运行时库存/文本状态在后续 rebuild 或 split 时回退到捕获时旧状态；plot chunk rebuild 前会从旧 plot chunk 刷新最新 block entity NBT，并为新 plot chunk 创建新的 block entity 实例，让旧 chunk/ticker 正常卸载旧实例，避免同一个 block entity 实例跨 rebuild 被旧 chunk 标记 removed 后再复用；实体查询投影已覆盖返回 `List` 和写入 output list 两类 `getEntities` 入口，让漏斗吸物品、比较器读物品展示框以及类似 block entity 查询路径更稳定；物品展示框这类附着实体会记录 plot 锚点并投影到物理世界，原版支撑检查回读 plot 支撑方块，避免因为世界坐标没有支撑方块而自动掉落；展示框旋转时的比较器邻居通知会回写到 plot 锚点，避免只通知物理世界坐标；restore 回源前会从 plot chunk 或 assembly 缓存抓取最新 block entity NBT，并在 source 坐标重建 block entity，避免箱子、投掷器、发射器这类运行时状态回源后变空。需要游戏内验证箱子、投掷器、发射器的物理化捕获不掉库存、破坏掉落、运行中改库存后 rebuild/split 不丢状态、漏斗吸物品/传容器、比较器读容器/物品展示框，以及发射器/投掷器的库存变化、发射实体位置和速度。

补充查漏：同一 plot TNT 在同一 server tick 内重复 prime 会被去重，避免比较器/邻居更新链路生成双 TNT 实体；客户端侧也会把 assembly plot block 的交互距离映射到物理位置，避免告示牌编辑界面因为客户端距离判定瞬间关闭。液体放置仍未支持，暂时延期。

## M6: 生命周期与清理

目标：assembly 创建、重建、移除、服务器关闭时资源清理一致。

验收：
- 没有残留 plot chunk、tick container、block entity ticker、物理 body、客户端假 chunk。
- 服务器关闭、维度卸载、assembly 删除路径都能安全释放资源。
- 如果暂不做完整持久化，运行时清理边界必须稳定。

当前状态：开始第一轮运行时清理边界。assembly 移除、forget、body 丢失、拆分失败/原体替换、无碰撞重建、服务器关闭等路径会统一做移除准备：标记 removing，主动丢弃该 assembly 登记的附着实体（例如物品展示框），并清空 block entity 缓存；服务器关闭时还会按 level 额外扫描清理未归属到具体 assembly 的附着实体登记。需要游戏内验证 remove/clear/server stop 后没有展示框实体、plot chunk、ticker、visual 残留。

## M7: Assembly 持久化保存

目标：服务器保存/重进世界后，运行中的 assembly 能以原来的方块、BlockEntity、姿态、速度和 plot 映射恢复。

验收：
- 退出并重进世界后，assembly 仍然存在，方块状态、容器/告示牌等 BlockEntity NBT 不丢。
- 恢复后的 assembly 继续拥有 PhysX body、plot chunk、客户端追踪和基础交互。
- 保存会保留 body pose、linear velocity、mass 和稳定 AssemblyId；恢复时重新创建 body，并推进 plot allocator，避免新 assembly 复用旧 plot slot。
- assembly 被删除/forget 后，下一次保存会清空持久化记录。

当前状态：开始最小实现。每个 `ServerLevel` 通过 `SavedData` 保存 assembly 记录；server tick 首次恢复，之后每 100 tick 捕获一次运行时快照并在有变化时落盘，vanilla level save 前会刷新一次，server stopping 前强制 capture 并 flush。关闭清理阶段会抑制后续 save capture，避免 runtime container 被清空后把持久化文件覆盖成空记录。保存内容包括 AssemblyId、bounds、plot、pose、linear velocity、mass、方块状态、BlockEntity NBT、local collision boxes、visual local origin，以及 plot chunk 中的 block/fluid scheduled tick 队列。恢复前会等待目标附近 chunk 可检查，并同步预热附近 terrain collider，避免 assembly 先进入物理场景后在 terrain rebuild 前被重力拉走；恢复 plot chunk 后会把保存的 `SavedTick` 按当前 gameTime 重新 schedule 回 `LevelTicks`。

## M8: 兼容测试集

目标：建立固定测试场景，用来反复验证 assembly 行为。

验收：
- 覆盖红石钟、压力板门、绊线、活塞、无碰撞方块、block entity、拆分、移除、保存/重进世界。
- 每次改动后能快速复现核心场景。

## 建议顺序

优先顺序：M1 -> M2 -> M3 -> M4 -> M5 -> M6 -> M7。

M8 从 M1 后开始积累，不等到最后再补。
