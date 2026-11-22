# EtheriumMC 三项实验功能移植战略书

**进度：配置已接入；实体遮挡与两类感知器的距离排序已实现。整世界 tick 仍串行，目前只交付计时和前置修复。下文保留原始目标与验收标准。**

## 1. 版本与证据

- SnowBall：本地 `dev/26.3`，`304621d`，固定 Paper 26.3 #257。正式开工前记录实际目标提交，重新检查三项在最新目标上的冲突；不因调研擅自更新工作分支。
- EtheriumMC：`Study-MCMCMC/EtheriumMC`，`EtheriumMC/26.3`，`8b670c2b6494556e6666373d440e4b3ac137ac42`。发行标签 `EtheriumMC/26.3` 解引用为 `b8e3141fca6c2b6db543549ad0553a21d029cc5d`；已核对核心 0047 / 0073 / 0074 和 `AsyncWorldTicking.java` 在这两个提交之间没有差异。GitHub Releases 列出的资产为 `EtheriumMC-loader-26_3-v7.jar`，SHA-256 为 `addb194d689023a457d027e6ad18f0c5ec7aea11f7dc619e7dcc453eccbbc5bc`。这里只核对了发布元数据，未下载或运行 JAR，也未提取实际生成的配置。
- 字段来源：[DivineMC 固定提交的 DivineConfig.java](https://github.com/BX-Team/DivineMC/blob/c2a3e127d98c9d0886635e609bcba74fafb0fd60/divinemc-server/src/main/java/org/bxteam/divinemc/config/DivineConfig.java)，配套类为 `ConfigCategory`、`EntityCullingManager`、`PlayerCullTracker`、`ParallelSensorTicker`。它是核对用的上游提交，不是已证明的 EtheriumMC 发布构建锁定提交。
- 世界并行存在 **两条不同的代码线**。EtheriumMC `0047-SparklyPaper-Parallel-world-ticking.patch` 直接接入 tick 循环，使用 `DivineConfig.AsyncCategory`；配套 CraftBukkit 补丁为 `0014`、`0016`，统计显示涉及 Purpur 层 `0005`。另有 Fish 衍生 `AsyncWorldTicking.java`、`0031`、`0033` 和 Pufferfish 层 `0002`；后者是 Flare 接线，不是调度入口。该 clone 中没有查到 `AsyncWorldTicking.tickWorlds()` 的调用方，也没有匹配 `FishConfig.ASYNC.WORLD_TICKING` 的配置声明。可对照 [Fish 固定提交的 FishConfig.java](https://github.com/Biquaternions/Fish/blob/6184435de11413e49b7f304083554b98e7e21aa5/fish-server/src/main/java/me/biquaternions/fish/FishConfig.java)：`async.world-ticking.enabled: false`、`threads: 8`；源码的 fallback 对非正值返回至少 1 的半核数，但 `validate()` 读的是 transient `threads`，不是 YAML 绑定的 `softThreads`，不能据此保证某个发行版 `threads: 0` 生效。`LOG_ASYNC_ACCESSES` 只有调用证据，未确认发行 YAML 的键名或默认值。**首选可追踪到 tick 循环的 DivineMC/SparklyPaper 线，不把两种方案拼接，也不把 Fish 键伪装成已验证的别名。**
- 来源许可：EtheriumMC 有 `PATCHES-LICENSE.txt`（GPLv3 文本）；这不替代逐补丁、逐辅助类核对。开工时记录原作者、上游 SHA、适用许可证与 NOTICE；Raytrace 还需沿源码核对 tr7zw EntityCulling 的衍生许可。来源不明确的代码先不复制；不以完整引入 DivineMC、Purpur、Fish、Flare 或 Folia 解决缺口。

## 2. `Experimental-SB.yml` 的配置契约

本次交付的 [配置样稿](../config/Experimental-SB.yml) 有 22 个叶键，沿用可核对的上游 YAML 层级，不加 `settings.` 包装。三功能主体为 **6 + 11 + 2** 个键，另含 3 个相关共享控制项。默认值是上游声明中的 YAML 初始值，不把 CPU 解析结果写回文件。注释采用中文并覆盖原配置的单位、自动值、豁免、性能与兼容性代价，不声称是上游原文件的逐字拷贝。

- **世界并行（`async.parallel-world-ticking`）**：`enable`、`thread-count`、`log-container-creation-stacktraces`、`disable-hard-throw`、`use-per-world-tps-bar`、`show-tps-of-server-instead-of-world`。分别验证启停、最大同时运行数、诊断日志、线程检查及 TPS 显示选择，不接受无效果的开关。`async.auto-thread-allocation` 在 DivineMC 开启时还会启用 Regionized Chunk Ticking；少于 6 核关闭自动分配，6–8/9–11/12–15/16+ 核分别给世界并行 2/3/4/5 个许可。该附带功能不在本次范围，SnowBall 第一版需明确拒绝 `true` 并禁用世界并行。`disable-hard-throw: true` 同样应拒绝并记录原因，不能关闭线程归属校验。这是有意偏离，不算运行语义 1:1。若要求保留两项原语义，必须作为独立范围再评估，而非悄悄扩大本次移植。
- **实体遮挡（`network.raytrace-entity-culling`）**：11 个键见样稿。线程为整数，`threads <= 0` 取 `clamp(floor(CPU/8),1,4)`；`check-interval-ms` 为整数、执行下限 10；`max-trace-distance` 为整数、执行夹限 16–160；`visibility-timeout-ms` 为长整数；半径/包围盒扩张为小数；`bounding-box-limit` 整数；类型豁免为字符串列表。非法非有限小数、不可表示整数及危险的负值应报出具体路径，禁用对应功能或回退到上次通过校验的配置，不能静默把未知方块视作遮挡。原注释中的「marker armor stand」实际检查的是隐形盔甲架，应测试这一差异。
- **并行感知（`async.parallel-sensors`）**：`enable: false` 和整数 `max-threads: 0`。正数直用，零为 `max(floor(CPU/4),1)`，负数为 `max(CPU+值,1)`；以源码为准。`async.guru-mode-enable` 与 `async.guru-mode.parallel-sensors-affinity` 配合 JVM 属性 `divinemc.async.guru`，空列表不绑定。若亲和性依赖的平台能力未移植，非空列表必须明确告警、不得静默冒充有效；实测平台与绑定行为是宣称这部分等价的门槛。

### 与 SnowBall 配置系统接合

1. 在 `GlobalConfigFiles` / `GlobalConfigManager` / `VerifiedConfig` 引入第三种配置归属，优先用明确的文件归属枚举替代继续堆叠布尔参数。现有 `leaves.yml`、`SnowBall.yml` 的键、注释和迁移优先级不变。把 `GlobalConfigCreator`、启动、重载、命令只写所属文件等入口全部纳入测试。
2. 只给实验文件支持这一份根层级，避免为了剥离 `settings.` 拆坏旧配置；校验路径、类型、默认值和是否需重启。第一次临时文件写入后原子替换，之后保留管理员设置/注释，不覆盖其他文件。无文件或无效文件明确记录，三项保持关闭或保留上次有效配置；不改存档、不写伪造的生效值。
3. 第一版所有这三项运行配置需重启，尤其池大小、队列生命周期、亲和性和世界所有权。启动日志分别列出请求值、解析后线程数、真正启用情况和回退原因。通过 `GlobalConfigFilesTestSuite` 加覆盖：三文件互斥、完整键集合、逐项类型/默认、重复生成、非法 YAML、冷热重载、配置命令和用户注释保留。
4. 严格 1:1 的证据门槛：从指定发行包获取可信摘要或作者确认的构建来源；在隔离目录生成默认配置，与样稿逐路径比较键、默认值、类型和边界行为；记录当前两条 PWT 线哪条真正生效。未拿到发行配置前，只能说「与已核对的源码对照」，不能宣称「与 EtheriumMC 发行版一模一样」。

## 3. 移植顺序和验证

| 阶段 | 实施边界 | 合并条件 |
| --- | --- | --- |
| 0：配置与记录 | 第三配置文件、许可与来源表、诊断和默认关闭；不启动任何工作线程。 | 缺文件、坏配置、旧配置迁移与生成样稿均有回归测试。禁用时无工作线程、无发包和调度差异。 |
| 1：Raytrace Entity Culling | 从 EtheriumMC `0074` 和 DivineMC `culling`/实际射线辅助类分离。接 `ChunkMap.TrackedEntity`、`ServerPlayer.doTick`、方块变更失效、定位栏。仅用稳定快照做射线；发包与增删追踪只在所有者线程进行。不要把相邻 `0006-Raytrace-AntiXray-SDK-integration.patch` 当成依赖自动导入；在确认辅助类的真实依赖后只引必要部分。 | 空气、玻璃、半砖、门、区块边界、刚拆墙、移动观察者、传送/退服、实体 ID 复用、高延迟、Bukkit `canSee` 和定位栏测试；未加载或快照过期一律不因本功能隐藏，同时绝不绕过既有隐藏权限。记录遮挡误判、出现延迟、出站字节和每观察者内存。 |
| 2：Parallel Sensor Phase | 以 `0073` 的 `ServerLevel` / `Sensor` 接线为参考，只逐个启用审计过的传感器。所有者检查 EAR/冻结、是否到期；工作线程只读冻结输入或快照，提交记忆仍按所有者线程和原顺序；隔离 `TargetingConditions` 的可变状态。保留 Paper 可配置扫描周期；不能在拥有者未冻结时把活动世界暴露给工作线程。 | 村民、成年检测、掠食者、猪灵、监守者、可见性、POI 与冻结实体的开/关差分。保证同一传感器每轮不重跑、不漏跑，线程异常和队列满时同步回退。重点排查其他异步寻路、刷怪计数和同时启用世界并行时的所有权。 |
| 3：Parallel World Ticking | 最后在独立实验分支。世界各自单一 owner、并发限制、轮次屏障；明确主线程保留的 Bukkit 全局队列、跨世界传送/重生、计分板、数据包重载、存档卸载、自动保存与停服。不要照搬全局漏斗事件标志、可变缓存或通过放宽所有线程断言来处理插件。Fish 的阻塞调度只作对照，先避免主线程等待世界工作线程、工作线程再等待主线程的环。 | 1/2/3 世界、线程数 1/2/4、双向跨维度传送、玩家加入退出、红石和传送门、假人和录像、插件事件与主线程契约、保存/卸载/关服、异常注入及 watchdog。必须无死锁、无重复 tick、无跨线程写入。一次世界 tick 已部分执行时不得直接重跑该 tick；用明确停用/重启方式回滚。 |

组合验证：先各功能单开，再两两组合，最后三项同开；有冲突时不直接放开组合。使用世界副本和代表性插件场景记录每维度 MSPT 的 p50/p95/p99、线程排队、GC、发包与内存；结果与同一基线串行组对照，不预设性能收益。工程验收用 `applyAllPatches`、API/服务端测试、`createLeavesclipJar --rerun` 与隔离启动验证。补丁按配置、culling、sensor、world 分组保存，每组可单独关闭。

## 4. 整世界并行的解阻顺序（Paper #257 实测源码）

已落地：[0160](../snowball-server/minecraft-patches/features/0160-Scope-hopper-event-and-update-state.patch) 将 `InventoryMoveItemEvent` 的世界配置和每次传输的短路状态移出静态字段，并只抑制目标容器的重复更新通知；[4 个回归用例](../snowball-server/src/test/java/net/minecraft/world/level/block/entity/HopperTransferStateTestSuite.java)验证不同世界配置、嵌套传输、双箱选侧和异常恢复。这只解除一个共享状态问题，不代表建立了世界线程归属。

1. **事件：先定义安全边界，再派发。** [PaperEventManager.callEvent](../paper-server/src/main/java/io/papermc/paper/plugin/manager/PaperEventManager.java#L35-L60) 在读监听器前就检查同步事件的主线程契约；而且无监听器也不能普遍跳过，本项目在此处有假人的世界生命周期处理。保持此断言，先用受控场景追踪会触发同步事件的 tick 阶段与取消/修改结果。只有能在不持有世界锁、源/目标均可让渡的边界暂停或移交的调用才能由主线程处理；原调用返回前仍须获得取消/修改结果。若主线程在轮次屏障中直接 `join`，让 worker 再同步排队到主线程会形成等待环。首个验收用例是监听器在世界 A 发起同步读写世界 B；不能安全处理的阶段保持串行。
2. **区块等待：按世界审计执行器与完成队列。** [ServerChunkCache 构造时绑定当前线程](../snowball-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java#L208)；[MainThreadExecutor.getRunningThread/pollTask](../snowball-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java#L889-L908) 返回这个线程并驱动 Moonrise 任务。[syncLoad](../snowball-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java#L110-L136) 按 TickThread 判定泵队列后等待 future。只改线程判定或只改执行器线程都不足以处理同步加载；必须证明每个 future 的完成路径在等待期间还能运行，且不会借机执行其他世界的普通任务。测试区块冷加载、缓存命中、异常和关闭中的等待，校验 `pushChunkWait` / `popChunkWait` 配对。文档中提到的所有跨线程归属更改仍未实现。
3. **跨维度迁移：对两边都确认静止，再执行。** [Entity.teleportCrossDimension](../snowball-server/src/minecraft/java/net/minecraft/world/entity/Entity.java#L4370-L4398) 同一调用处理乘客、移除源实体、在目标世界加入新实体；[ServerPlayer.teleport](../snowball-server/src/minecraft/java/net/minecraft/server/level/ServerPlayer.java#L1602-L1642) 还允许事件改变目标并涉及玩家网络状态。第一阶段只能在协调器持有双方安全点时执行，不能持着世界 A 的锁等待世界 B。验收包括双向同时传送、监听器取消/改世界、乘客、目标卸载和连接关闭，禁止重复加入或先发成功包再失败回退。夜跳还要覆盖 [ServerLevel 的全局时钟分支](../snowball-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java#L790-L835)。
4. **保存、卸载、停服：先阻止新轮次并排空再写。** [MinecraftServer.saveAllChunks/saveEverything](../snowball-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java#L932-L976) 涉及全局计分板、玩家与各世界保存；[CraftServer.unloadWorld](../paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java#L1314-L1352) 的迭代检查目前是注释，不能当屏障；[stopServer](../snowball-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java#L1010-L1105) 可能由另一线程调用。验收顺序为拒绝新工作、处理已经承诺的移交、等待并确认 writer 停止，然后关闭世界、区域文件与数据存储。`stopLock` 只保护重复停服，不是世界屏障；不得持锁等待还依赖该锁的任务。包含 `/save-all flush`、卸载取消、watchdog 停服、重启后区块/实体数据比对。

[双世界安全点原型](../snowball-server/src/test/java/org/snowballmc/snowball/experimental/world/WorldTickSafePointTestSuite.java) 已通过 4 项测试：两世界重叠执行、单 worker 顺序执行、失败轮次不提交回调或重跑、停服后拒绝新轮次并保存。原型只存在于测试源码，不处理真实 Bukkit 事件、区块或存档。下一步在独立实验分支逐项接入可证明安全的调用；**不得把模拟成功当成整世界并行验收**。当前启动路径继续强制串行，[0159](../snowball-server/minecraft-patches/features/0159-Record-serial-world-tick-durations.patch) 的 JFR 事件只测基线。
