# 优化模组移植技术书

> 目标端：Leaves（Paper 26.3 分支，`mcVersion=26.3`，`paperRef=0727243c`）
> 源端：19 个 Fabric 优化模组（本地克隆于 `需要移植的MOD/`）
> 方法：**Bridge 桥接 + Patch 补丁**
> 版本：v1.0

---

## 0. 结论摘要

0. **每个移植的功能都必须在 `leaves.yml` 中可独立开关与配置——这是硬性验收条件，无例外。** 不允许出现「合入即生效、无法关闭」的功能。详见 §3 闸门 G6 与 §7 配置规范。
1. **技术上可行，且已有官方先例**：Leaves 已用同一套方法移植过 Lithium（`0126`、`0127`）与 Pufferfish/Gale 系列。本技术书不是发明新方法，而是把既有先例固化为可复用的工程规范。
2. **不需要 Mixin**：Leaves 的 Mixin 是**插件级可选能力**（`-Dleavesclip.enable.mixin=true`，核心自身不含任何 `*.mixins.json`）。核心优化一律走源码 Patch，避免运行时字节码注入带来的可维护性与兼容性风险。
3. **不需要 Yarn→Mojmap 映射转换**：实测 19 个仓库中，凡目标 MC ≥ 26.x 的均已直接使用 Mojang 官方名（`accessWidener v1 official`、`loom-no-remap`、`gradle.properties` 无 `mappings` 行）。**这是本次移植最大的成本削减项**。
4. **许可证不构成阻塞**：Leaves 继承 Paper 的 **GPL-3.0**。GPL-3.0 源可合入；LGPL-3.0 允许按 GPL-3.0 再发布（LGPL-3.0 §3）；MIT/Apache-2.0 可合入（需保留声明）。例外有二：**Cerulean** 的实际许可是自定义的「Toni's MMC License」（禁止 relicense、附商业分成条款），与 GPL-3.0 不兼容；**InfChatHistory** 的 LICENSE 与元数据互相矛盾。两者均否决。
5. **Paper ≠ Folia，不能拿 Folia 当否决理由**：Leaves 的基座是 Paper（单主线程 tick 全部维度），不是 Folia（区域化多线程）。因此 **worldthreader 的「按维度并行 tick」在 Paper 上是可行的增量能力**，与 Folia 只是「两种互斥的线程模型方案」，并非冲突项。同理，任何以「Folia 已经这么做了」为由的否决都不成立，只有以「Paper 现有代码结构不允许」为由的否决才成立。
6. **19 个模组的最终分布（v1.5 实测，每个模组只计一次）**：**已移植 8 个**、**上游已覆盖 3 个**、**技术否决 5 个**、**超大工程 3 个**。合计 19。逐项裁决见 §4.7。

**一句话路线**：T0 的 `mcm-rabbit-pathfinding-fix` 已于 v1.2 端到端跑通（补丁 `0139`，配置键 `path-navigation-timeout-fix`），Bridge+Patch 流水线与补丁工作流均已验证；后续按 §5.0 的四条硬性约定推进 §11 里程碑（worldthreader 与 Oushii 是投入最大、收益最高的两项）。

---

## 1. 技术底座

### 1.1 目标端：Leaves 的补丁体系（实测）

Leaves 使用 paperweight 的 **fork 机制**，而非简单地堆叠 diff：

```
snowball-server/build.gradle.kts.patch     ← 改写 paper-server 的构建脚本
snowball-server/paper-patches/features/    ← 16 个补丁，作用于 paper-server
snowball-server/minecraft-patches/features/← 138 个补丁，作用于 net.minecraft
snowball-api/paper-patches/                ← API 侧补丁
```

关键装配片段（`build.gradle.kts.patch`）：

```kotlin
val leaves = forks.register("leaves") {
    upstream.patchDir("paperServer") {
        upstreamPath = "paper-server"
        excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
        patchesDir = rootDirectory.dir("snowball-server/paper-patches")
        outputDir = rootDirectory.dir("paper-server")
    }
}
activeFork = leaves
```

落点约定：

| 层 | 物理位置 | 职责 |
|---|---|---|
| Patch 层 | `minecraft-patches/features/NNNN-*.patch` | 只在 NMS 里**插入调用点**，不写业务逻辑 |
| Bridge 层 | `snowball-server/src/main/java/org/snowballmc/snowball/<mod>/` | 移植过来的模组核心逻辑，**纯 Java** |
| Config 层 | `LeavesConfig.PerformanceConfig` | 每个优化一个 `@GlobalConfig` 布尔开关 |
| API 层 | `snowball-api/src/main/java/org/snowballmc/snowball/` | 仅当需要暴露给插件开发者时使用 |

现有 Bridge 先例（Lithium 移植的产物）：

```
org/snowballmc/snowball/lithium/
├── api/inventory/            LithiumInventory, LithiumDefaultedList,
│                             LithiumCooldownReceivingInventory,
│                             LithiumTransferConditionInventory
└── common/
    ├── block/entity/         SleepingBlockEntity, InventoryChangeTracker,
    │                         SetChangedHandlingBlockEntity, …
    ├── hopper/               HopperCachingState, InventoryHelper,
    │                         LithiumDoubleInventory, …
    └── world/chunk/          LithiumHashPalette, …
```

补丁头部的**署名格式**（`0126` 实例，必须沿用）：

```
Subject: [PATCH] Lithium Sleeping Block Entity

This patch is Powered by Lithium(https://github.com/CaffeineMC/lithium)

This patch is based on the following mixins:
* "net/caffeinemc/mods/lithium/mixin/world/block_entity_ticking/sleeping/*.java"
* "net/caffeinemc/mods/lithium/mixin/block/hopper/*.java"
By: 2No2Name <2No2Name@web.de>
As part of: Lithium (https://github.com/CaffeineMC/lithium)
Licensed under: LGPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.html)
```

补丁体内的行内标记统一用 `// Leaves - <主题>` / `// Leaves start - <主题>` … `// Leaves end - <主题>`，便于上游 rebase 时定位与冲突解析。

### 1.2 源端：19 个模组的事实盘点

| # | 模组 | 许可 | MC | Mixin 条数 | 平台耦合 | 侧别 |
|---|---|---|---|---|---|---|
| 1 | alternate-current | MIT | 26.3 | 5 | Fabric entrypoint | 服务端 |
| 2 | Oushii | GPL-3.0 | 26.3/26.4 | 4（1 客户端） | accessWidener `oushii.ct` | 服务端+少量客户端 |
| 3 | FerriteCore | MIT | [26.1,27) | 13（5 配置/4 插件） | Fabric `MappingResolver` | 双端（服务端有效） |
| 4 | krypton | LGPL-3.0 | 26.3 | 10 | `velocity-native` 原生库 + AW | 双端（服务端为主） |
| 5 | PacketFixer | MIT | 26.3 | 19 | fabric-api `PayloadHelper` | 服务端网络 |
| 6 | ScalableLux | LGPL-3.0-only | 26.3 | 16（3 客户端） | FlowSched 子模块（**未检出**） | 双端（服务端为主） |
| 7 | Ksyxis | MIT | 版本无关 | 7（1 客户端） | 自研多映射名字符串 | 服务端 |
| 8 | ModernFix---mVUS | LGPL-3.0-or-later | 26.3 | 120（58 客户端） | MixinExtras + ASM + AW | 双端 |
| 9 | StructureLayoutOptimizer | MIT | 26.3 | 6 | `resourcefulconfig` 硬依赖 | 纯服务端 |
| 10 | AsyncLogger | LGPL-3.0-only | 26.3（Stonecutter 13 节点） | 2（**注入体全注释，实际 0**） | 类加载器手术 + 6 种 loader 后端 | 双端 |
| 11 | Async | GPL-3.0 | 26.3 | 92 | fabric-api events + MixinSquared | 纯服务端 |
| 12 | RailOptimization | GPL-3.0 | 26.1（声明 26.1–26.3） | 5（另有 4 空壳） | fabric-api events | 纯服务端 |
| 13 | Jasione | LGPL-3.0-only | 26.1（无 26.3 节点） | **0** | Mixin coprocessor / NeoForge ClassProcessor | 双端（JVM 层） |
| 14 | mcm-rabbit-pathfinding-fix | Apache-2.0 | 26.3 | 8（1 客户端） | classTweaker 6 条 | 服务端为主 |
| 15 | worldthreader | MIT | 26.3 | 71 | fabric-api gamerule/lifecycle（未在 depends 声明） | 服务端 |
| 16 | Cerulean | **Toni's MMC License（非 GPL，与 GPL-3.0 不兼容）** | 26.3 | 5 | Manifold 预处理器 + AW | 服务端逻辑 |
| 17 | InfChatHistory | CC0（元数据写 MIT，**冲突**） | 1.21.4 | 1 | 无 | **纯客户端** |
| 18 | Ferrite | MIT | 26.3 | 71（66+5） | **Rust cdylib + JNI** + 自建 Rayon 池 | 服务端为主 |
| 19 | Bellows | MIT | 26.3 | 39 | `lib/Moonrise-Fabric-1.1.0.jar` 本地 jar + classTweaker | 纯服务端 |

---

## 2. Bridge + Patch 架构

### 2.1 分层模型

```
┌─────────────────────────────────────────────────────────────┐
│  插件层（可选）  snowball-api 暴露的稳定接口                    │
├─────────────────────────────────────────────────────────────┤
│  Config 层   LeavesConfig.PerformanceConfig                  │
│              @GlobalConfig("xxx") boolean xxx                │
├─────────────────────────────────────────────────────────────┤
│  Bridge 层   org.snowballmc.snowball.<mod>.common / .api         │
│              ← 模组核心逻辑的 Java 移植，无 loader 依赖        │
├─────────────────────────────────────────────────────────────┤
│  Patch 层    minecraft-patches/features/NNNN-*.patch         │
│              ← 只在 NMS 插入 if (config) Bridge.xxx(...)      │
├─────────────────────────────────────────────────────────────┤
│  NMS 层      net.minecraft（Mojang 官方名，26.3）             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Bridge 三定律（评审的硬性判据）

**定律 1：Bridge 不得 import 任何加载器/平台类型。**
禁止出现 `net.fabricmc.*`、`net.neoforged.*`、`org.spongepowered.asm.*`。出现即判不通过，退回重构。

> 反例：FerriteCore 的 `PlatformHooks` 依赖 Fabric `MappingResolver` 反射求字段名；krypton 的初始化器依赖 `velocity.mod.json`。这类耦合必须在 Bridge 层被**替换为直接引用或内联常量**，而不是搬进来。

**定律 2：Patch 不写业务逻辑。**
Patch 只允许三种形态：

```java
// 形态 A：配置门控的调用转发
if (org.snowballmc.snowball.LeavesConfig.performance.<key>) {
    org.snowballmc.snowball.<mod>.Hook.onXxx(this, ...);   // Leaves - <主题>
}

// 形态 B：可见性调整（替代 accessWidener）
-public final class Foo {
+public final class Foo {          // Leaves - <主题>：包内可见 → 公开
     private final Barrier bar;   // 字段同理

// 形态 C：duck 接口（让 Bridge 反查 NMS 私有状态）
-public class LevelChunk {
+public class LevelChunk implements org.snowballmc.snowball.<mod>.api.ExtendedLevelChunk {
```

**定律 3：一功能一开关，且必须在 `leaves.yml` 中可独立配置。**
关于 `leaves.yml` 的可配置性，评审时逐项检查：

1. 该功能在 `LeavesConfig.PerformanceConfig` 中有**独立**的 `@GlobalConfig` 字段，**不允许**多个模组共用一个开关，也**不允许**把模组内的多个子功能塞进一个布尔。
2. 默认值与「未移植」等价：纯性能等价变换默认 `true`；语义变更默认 `false`。
3. 支持关闭：关闭后行为与原版 Paper 逐字节一致（不只是「效果变弱」）。
4. 支持热重载或明确声明需重启：
   - 无副作用的功能 → 普通字段，随 `GlobalConfigManager.reload()` 热生效。
   - 涉及线程池/线程模型的 → `@GlobalConfig(value = "...", lock = true)`，声明「需重启生效」，并给出日志提示。
5. 字段必须有注释说明「与原版的差异」；语义变更类还要写进配置注释与启动日志。
6. 若功能有非布尔参数（如 `rail-power-limit`、`parallelism`），必须配 `ConfigValidator` 做范围校验，越界时回落默认值并告警。

> 这条是**硬性验收条件**，不是建议。理由：Leaves 是面向服主的分支，任何无法关闭的优化都会在出问题时变成事故；且 `leaves.yml` 是服主唯一的期望管理界面。

### 2.3 为什么不用 Mixin

| 维度 | Mixin | Patch |
|---|---|---|
| 上游 rebase | 注入点漂移时静默失效或运行期崩溃 | 编译期失败，立刻暴露 |
| 调试 | 堆栈指向生成代码 | 源码级 |
| 与 Paper 自身补丁共存 | 两者改同一行时行为不确定 | 明确合并冲突 |
| 用户可预期性 | 需要 mixin 配置/依赖 | 无额外运行时 |

社区生态里，Leaves 把 Mixin 留给**插件**（`MixinConfiguration` 可声明 `packageName`/`mixins`/`accessWidener`），核心不用。

### 2.4 命名与目录规范

```
org.snowballmc.snowball.<modslug>/
├── api/            对外稳定接口（仅当有跨模块/插件需求时创建）
│   └── <Domain>Access.java
├── common/         核心逻辑，按模组原包结构平移
│   └── <原包路径>/*.java
└── Hook.java       唯一入口：Patch 只调用本类的静态方法
```

- `<modslug>`：小写无连字符（`alternatecurrent`、`rabbitpathfix`、`bellows`）。
- 配置键：`performance.<kebab-case>`，与现有风格一致（`faster-chunk-serialization`）。
- 补丁编号：接续现有最大号（当前 `0138`），新补丁从 `0139` 起，同一模组的补丁编号连续。
- 一个模组**原则上只产出一个补丁文件**；超过 1500 行时按子系统拆分并加 `-1/-2` 后缀。

---

## 3. 准入判定：五道闸门

任何模组必须先过闸，再排期。任一闸门失败即降级或否决。

### G1 许可证闸门

| 源许可 | Leaves（GPL-3.0）可否合入 | 附加义务 |
|---|---|---|
| GPL-3.0 | ✅ 直接合入 | 署名 + 保留许可声明 |
| LGPL-3.0 / -only / -or-later | ✅ 可按 GPL-3.0 再发布（LGPL-3.0 §3） | Bridge 中派生文件加 LGPL 头 |
| MIT | ✅ | 保留 copyright + 许可正文 |
| Apache-2.0 | ✅ | 保留 copyright + **NOTICE** |
| 许可缺失/自相矛盾 | ⚠️ 阻塞 | 需上游补正文或书面授权 |

### G2 重复闸门：Leaves 已有能力

已内置（**不要重复移植**）：

| 能力 | 依据 |
|---|---|
| Pufferfish 全套 4 项 | 补丁 `0017` `0018` `0020` `0021` |
| Gale 区组 tick/分配套件 10 项 | 补丁 `0053`–`0063` `0102` |
| Lithium 方块实体休眠 / 装备追踪 | 补丁 `0126` `0127`（默认关，`lock` 保护） |
| Lithium 漏斗 & 物品栏优化（部分） | `0086` `0087` `0126` |
| Lithium AI/POI 优化（部分） | `0019` `0055` `0093` |
| 区块序列化加速 | 补丁 `0054`（源自 Leaf） |
| 封包大小限制放开 | `paper-patches/0008-Disable-packet-limit` |
| 杂项实体分配/移动优化 | `0016` `0020` `0021` `0058` `0062` `0102` `0129` |

**未覆盖（价值洼地）**：区块系统并发（C2ME 类）、光照引擎（Starlight 类）、红石线、TNT 爆炸、世界生成拼图、命令/NBT/选择器、铁轨。

> ⚠️ **前置核实项**：Paper 上游自 1.20.x 起是否已内置 Starlight 式光照引擎，本地无法从 patch 目录断定（`paperServer` 源码未随仓库落地）。**ScalableLux 排期前必须先用 `./gradlew applyPatches` 展开 `paper-server` 后核实 `ThreadedLevelLightEngine` 是否被上游改写**；若已内置，ScalableLux 直接否决。

### G3 平台耦合闸门

按耦合强度分级，决定改造工作量：

| 耦合类型 | 例子 | 改造动作 |
|---|---|---|
| 无（纯算法） | Ksyxis、RailOptimization、alternate-current | 直接平移 |
| fabric-api 事件 | Async、RailOptimization、worldthreader、Cerulean | 换成 Paper 对应事件或直接内联到 hook |
| accessWidener / classTweaker | Oushii、Bellows、ModernFix、ScalableLux | 改为 Patch 形态 B 直接调可见性 |
| 加载器生命周期 | FerriteCore（MappingResolver）、AsyncLogger（ClassLoader 手术）、Jasione（Mixin coprocessor） | 卸载平台层，或整体否决 |
| 原生库 | krypton（velocity-native）、Ferrite（Rust cdylib） | 需评估打包/加载路径 |

### G4 语义闸门

标记"是否改变原版可观察行为"。改变者一律 `default = false` 且在文档/配置注释中写明差异：

- alternate-current：红石更新顺序变为非位置相关，**破坏 locational 红石机器**
- Ksyxis：出生点常驻区块消失，`spawnChunkRadius` 对外报 0
- RailOptimization：`powerLimit`（默认 8，可 1–64）改变信号传播范围
- Oushii：爆炸走自研引擎而非 `Level#explode`，爆炸事件监听者收不到
- Cerulean：跳过 advancement 触发器，**会漏触发进度**
- worldthreader：玩家换维度改为重建 `ServerPlayer`，破坏 vanilla 边缘行为

### G5 可维护性闸门

- `@Overwrite` 数量：> 0 需组长评审；> 3 直接否决（krypton 有 4 处，见 §4.3）。
- 是否依赖未检出的子模块（ScalableLux 的 FlowSched）。
- 是否能在 Paper 上游 rebase 时以「补丁冲突」而非「运行期 NPE」的形式暴露问题。

### G6 配置闸门（硬性，不可豁免）

**每个移植的功能都必须能在 `leaves.yml` 里单独开启/关闭与配置。** 评审时按清单核验：

| # | 检查项 | 判定 |
|---|---|---|
| C1 | `PerformanceConfig` 中存在**专属**的 `@GlobalConfig` 字段（非复用其他模组的键） | 缺则驳回 |
| C2 | 该模组的每个原子子功能都有各自的键（如 ModernFix 的 8 个采纳项 = 8 个键） | 合并则驳回 |
| C3 | 默认值符合 §7.1（等价变换 `true` / 语义变更 `false`） | 错则驳回 |
| C4 | 关闭后与未移植的 Paper 行为完全一致 | 不完全一致则驳回 |
| C5 | 线程池/线程模型类功能标注 `lock = true` 并在文档说明需重启 | 缺则驳回 |
| C6 | 非布尔参数（并行度、上限值、阈值）有 `ConfigValidator` 范围校验 | 缺则驳回 |
| C7 | 字段有注释说明与原版差异；语义变更项有启动日志提示 | 缺则驳回 |
| C8 | `./gradlew :snowball-server:createLeavesConfig` 生成的 `leaves.yml` 中键名与默认值正确，旧配置可平滑升级 | 失败则驳回 |

> **一票否决**：任何"合入即生效且无法关闭"的功能，无论性能收益多大，一律不进主干。

---

## 4. 分级与逐项移植方案

### 4.0 分级总览

| 级别 | 含义 | 模组 |
|---|---|---|
| **已移植** | 补丁 + Bridge + 配置键，三重验证通过 | mcm-rabbit-pathfinding-fix、StructureLayoutOptimizer、Bellows、RailOptimization、FerriteCore(2 模块)、Oushii、ModernFix(4 模块)、**Cerulean** |
| **上游已覆盖** | Paper/Moonrise 已实现，移植即负收益 | alternate-current、ScalableLux、PacketFixer（注：FerriteCore ②③⑤ 与 Bellows blockforceload 属**部分**覆盖，对应模组整体计入「已移植」） |
| **技术否决** | 注入点消失 / 载体不存在 / 依赖原生库 | Ksyxis、InfChatHistory、Jasione、AsyncLogger、krypton |
| **超大工程** | 工程量属独立立项 | worldthreader、Async、Ferrite |

工作量单位：人日（含自测，不含跨模块联调）。

---

### 4.1 T0 梯队

#### 4.1.1 Ksyxis — ❌ 否决（上游已移除被优化对象）

- **许可**：MIT ｜ **Mixin**：7（服务端 6 + 客户端 1）｜ **原判**：T0 首选
- **实测否决理由（v1.2 改判）**：Ksyxis 的全部价值在于"移除出生点常驻区块"。而 **MC 1.21.9 起原版已删除出生点常驻区块**，26.3 上：

  | Ksyxis 原注入点 | 26.3 现状 |
  |---|---|
  | `ServerLevel.setDefaultSpawnPos(BlockPos, float)` | **方法已不存在**，被 `setRespawnData(LevelData.RespawnData)` 取代 |
  | `MinecraftServer.prepareLevels(ChunkProgressListener)` | **已不存在** |
  | `Level.isSpawnChunk(int, int)` | **已不存在** |
  | `spawnChunkRadius` 游戏规则 | 被 [GameRuleRegistryFix.java](snowball-server/src/minecraft/java/net/minecraft/util/datafix/fixes/GameRuleRegistryFix.java#L41) 显式 `remove` |
  | 出生点 ticket 循环 | 在 [ServerLevel.java](snowball-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java#L2662-L2677) 中**被整段注释**，留有 `// TODO: 1.21.9 removed spawn chunk, should we keep this?` |
  | `PrepareSpawnTask$Preparing` / `$Ready` | 存在，但只剩**登录期临时**的 `PLAYER_SPAWN` 半径 3 |

- **结论**：没有可移植的东西。唯一残留的 `PLAYER_SPAWN` 半径 3 若缩到 0，会破坏玩家落地。**不移植，不新建补丁。**
- **方法论教训**：T0 排序不能只看"模组简单、许可证干净"，必须先做**上游存活核实**。26.x 是版本断层，多个"优化模组"所优化的原版缺陷已被 Mojang 自己修掉。

#### 4.1.2 mcm-rabbit-pathfinding-fix — ✅ 已移植（缩减为 1/2 范围）

- **许可**：Apache-2.0 ｜ **Mixin**：8（服务端 7 + 客户端 1）｜ **MC**：26.3
- **范围裁决（v1.2）**：模组自身含两半，26.3 只剩一半有效：

  | 模组子项 | 26.3 是否仍需要 | 依据 |
  |---|---|---|
  | `PathNavigation#doStuckDetection` 节点超时 | ✅ **仍需要** | 原版在速度为 0 时仍写 `: 0.0`，且超时判定带 `timeoutLimit > 0.0` 前置条件 → 永不超时 |
  | `resetStuckTimeout` 的 `timeoutLimit` 归零 | ✅ **仍需要** | 原版仍是 `this.timeoutLimit = 0.0;` |
  | 卡死距离判定（`effectiveSpeed` 钳位） | ❌ 上游已改 | 26.3 已是 `speed >= 1.0F ? speed : speed * speed` |
  | `Rabbit` 跳跃力 / MoveControl / RaidGardenGoal | ⏸ 暂缓 | MC-150224 相关修复 26.3 已包含，需逐条复核后再决定 |
  | `RabbitModel`（客户端） | ❌ 跳过 | 服务端无渲染 |

- **已落地**：[snowball-server/minecraft-patches/features/0139-Fix-path-navigation-never-timing-out-when-mob-speed-is-zero.patch](snowball-server/minecraft-patches/features/0139-Fix-path-navigation-never-timing-out-when-mob-speed-is-zero.patch)
  - **Bridge**：`org.snowballmc.snowball.pathfix.PathTimeoutFix`（纯计算，无 NMS 依赖）
    - `DEFAULT_NODE_TIMEOUT = 200.0`（零速兜底）
    - `MAX_NODE_TIMEOUT = 4000.0`（近零速时 `dist/speed` 爆炸的封顶）
  - **Patch 形态 A**（配置门控转发），两处：`doStuckDetection` 的 `timeoutLimit` 计算、`resetStuckTimeout`
  - **配置**：`performance.path-navigation-timeout-fix`，默认 `true`（bugfix 类）
- **验证**：`applyAllPatches` ✅ ｜ `compileJava` ✅ ｜ `createLeavesConfig` 生成 `run/leaves.yml` 中 `path-navigation-timeout-fix: true` ✅
- **工作量**：1 人日

#### 4.1.3 StructureLayoutOptimizer — 拼图结构布局

- **许可**：MIT ｜ **Mixin**：6（5 类 + 1 Accessor）｜ **MC**：26.3 ｜ 纯服务端 worldgen
- **机制**：以 `BoxOctree` 替换 `JigsawPlacement.lambda$addPieces$2` 里 `Shapes.join` 的全顶点相交；配套 `TrojanVoxelShape`、`PalettedStructureBlockInfoList`。
- **Bridge**：`org.snowballmc.snowball.structurelayout/`
  - `BoxOctree`、`TrojanVoxelShape`、`PalettedStructureBlockInfoList`、`ConcurrentReferenceHashMap`、`TrojanArrayList`
  - `PlatformService` 接口在模组里有 `ServiceLoader` 抽象，移植时可**直接内联**为静态方法，删掉服务发现层
- **Patch**：`0141-Optimize-jigsaw-structure-layout.patch`
  - `JigsawPlacement`（`@WrapOperation` 处 → 直接改写调用）
  - `JigsawPlacement$Placer#tryPlacingChildren`（6 处 `@Redirect`/`@ModifyExpressionValue` → 源码级替换）
  - `SinglePoolElement`、`StructureTemplatePool`（Accessor → 可见性调整或 duck 接口）
  - `StructureTemplate#placeInWorld`、`StructureTemplate$Palette#<init>`
- **配置**：`performance.optimize-structure-layout`（默认 `false`）
  - 另设 `performance.structure-layout-dedup-shuffled-pool`（默认 `false`，会改变结构布局并破坏种子一致性——模组自身注释已警告）
- **工作量**：4–5 人日

#### 4.1.4 Bellows — 命令 / NBT / 选择器优化

- **许可**：MIT ｜ **Mixin**：39 ｜ **MC**：26.3 ｜ 纯服务端
- **机制**：三块
  1. `selector`：`@e` 选择器结果缓存 + `EntityLookup` 适配（8 条）
  2. `nbt`：数据包/实体 NBT 局部读写与序列化剪枝（16 + 5 + 2 条）
  3. `blockforceload`：修 MC-168596（`execute if block` 导致的永久强加载）（5 条）
- **Bridge**：`org.snowballmc.snowball.bellows/`
  - `selector/`（选择器缓存、`LevelEntityGetterAdapter` 适配）
  - `nbt/`（局部化 NBT 读写器）
- **Patch**：`0142-Command-NBT-selector-optimizations.patch`
  - 去掉 `selector.moonrise` 那 1 条与 `lib/Moonrise-Fabric-1.1.0.jar` 依赖：**Paper/Leaves 本身即使用 Moonrise 上游**，直接在 Bridge 里对齐 Moonrise 的 `EntityLookup` 即可，不需要 vendored jar。
  - `blockforceload` 部分独立成 `0143-Fix-MC-168596-block-force-load.patch`（这是 bugfix，与其他优化解耦）。
- **配置**：
  - `performance.bellows-selector-cache`（默认 `false`）
  - `performance.bellows-nbt-localized`（默认 `false`）
  - `performance.fix-block-force-load`（默认 `true`，bugfix）
- **风险**：`@e` 缓存语义在 26.3 选择器重写后是否仍成立，需专门回归；模组自身 `BlockDataAccessor` mixin 有 TODO 未修（`Bellows.java:42-45`），该子项不要搬。
- **工作量**：6–8 人日

---

### 4.2 T1 梯队

#### 4.2.1 alternate-current — 非位置相关红石线

- **许可**：MIT ｜ **Mixin**：5 ｜ **MC**：26.3 ｜ 服务端
- **收益**：README 声称红石线 MSPT 贡献降至 1/20；红石更新顺序确定化（修 MC-11193）。
- **机制**：抛弃原版递归更新，改为「建网 → 找外部供电源 → 从源扩散」；每根线每个 tick 最多 setBlock 一次；block update 从 42 降到 24，shape update 从 22 降到 6。
- **Bridge**：`org.snowballmc.snowball.alternatecurrent/wire/`
  - `WireHandler`、`WireNode`、`Node`、`WireConnection`、`WireConnectionManager`、`UpdateOrder`、`PriorityQueue`、`SimpleQueue`、`LevelHelper`、`Config`
  - 模组自身有 `Profiler`/`ACProfiler`/`ProfilerResults` 与 `/alternatecurrent` 命令 —— **命令与 profiler 建议不搬**（Leaves 已有 spark）
- **Patch**：`0144-Alternate-current-redstone.patch`
  - `RedstoneWireBlockMixin` → 改写 `RedstoneWireBlock#update` / `getConnection` 相关方法
  - `ServerLevelMixin` + `IServerLevel` 接口 → duck 接口（形态 C）
  - `ExperimentalRedstoneUtilsMixin`、`MinecraftServerMixin`、`CommandsMixin` → 按需
- **配置**：`performance.alternate-current`（**默认 `false`**）
- **风险**：**语义强变更**——破坏依赖更新顺序的 locational 红石机器。必须默认关闭 + 配置注释明示 + 启动日志提示。
- **工作量**：5–6 人日

#### 4.2.2 Oushii — 爆炸引擎

- **许可**：GPL-3.0（可合入）｜ **Mixin**：4（`PrimedTnt`、`Entity`、`ServerLevelTick`、`ClientLevel`）｜ **MC**：26.3/26.4
- **机制**：把原版 1352 条射线采样替换为**方块网格上的 blast field**：每方块最多访问一次，按"离中心更近的邻居留下的能量 − 自身抗爆值"传播，被挡住则在后方形成阴影。同一 tick 相邻爆炸合并；TNT 穿过空区块段时跳过碰撞检测。
- **Bridge**：`org.snowballmc.snowball.oushii/`
  - `ExplosionEngine`（能量场求解）、`BlastField`、`BlockResistanceCache`（按 blockstate 缓存一次）、`ChunkSectionReader`
- **Patch**：`0145-Oushii-explosion-engine.patch`
  - `PrimedTnt#tick`（只接管原版 `PrimedTnt`，不碰其子类——这是模组刻意的兼容设计，必须保留）
  - `ServerLevel#tick`（同 tick 爆炸合并）
  - `Entity`（TNT 飞行跳过碰撞）
  - `ClientLevel` 部分**跳过**（服务端无渲染）
- **配置**：`performance.explosion-engine`（默认 `false`）
- **风险**：
  - 爆炸不再走 `Level#explode` → **插件监听的爆炸事件收不到**。需在 Bridge 里补发对应的 Bukkit `EntityExplodeEvent`/`BlockExplodeEvent`，否则是插件兼容性事故。
  - `shouldBlockExplode` / `dropFromExplosion` / `wasExploded` 三个钩子必须逐一对齐，否则掉落物与连锁 TNT 行为会变。
- **工作量**：7–9 人日（含事件补发与行为对拍）

#### 4.2.3 FerriteCore — 只取 2/5 模块

- **许可**：MIT ｜ **Mixin**：13（4 个 `IMixinConfigPlugin`）｜ **MC**：[26.1, 27)
- **模组内含 5 个模块**，逐个裁决：

| 模块 | 内容 | 裁决 | 理由 |
|---|---|---|---|
| ① blockstate 缓存去重 | `BlockStateBase$Cache` 的 collisionShape + `faceSturdy` 用 `Object2ObjectOpenCustomHashMap` 全局去重；`ArrayVoxelShape` internals 替换 | ✅ **移植** | 纯内存收益，与 Leaves 无重叠 |
| ② 邻居表压缩 | `@Overwrite StateHolder#initializeNeighbors`，`S[][]` → 自研 `FastMap` | ⚠️ **条件移植** | `@Overwrite` 且有 `ThreadLocal`，与任何改方块状态的补丁硬冲突；需组长评审 |
| ③ 属性表压缩 | `propertyValues` 置 null，改查 FastMap | ✅ 移植 | 低风险 |
| ④ 数据组件空 map 单例 | `PatchedDataComponentMap` | ✅ 移植 | 低风险 |
| ⑤ 线程检测器 byte 化 | `PalettedContainer` 的 `ThreadingDetector` | ✅ 移植 | 低风险 |
| — 模型/资源加载 | **本分支不存在** | — | 无需处理 |

- **Bridge**：`org.snowballmc.snowball.ferritecore/`
  - `BlockStateCacheImpl`、`FastMap`、`ThreadingDetectorInternals`
  - **删掉** `PlatformHooks` 对 Fabric `MappingResolver` 的依赖（`FerriteCore/PlatformHooks.java:8-20`），字段名在 Leaves 里是编译期已知的
- **Patch**：`0146-FerriteCore-blockstate-memory.patch`
- **配置**：
  - `performance.ferrite-blockstate-dedup`（默认 `false`）
  - `performance.ferrite-compact-neighbor-table`（默认 `false`，标注 `@Overwrite` 风险）
- **风险**：全局静态去重表**永不释放**；与 Lithium（已移植 `0126`）同改方块状态时可能互相踩踏，模组本身在检测到 Lithium 时会自动关掉 threaddetec 模块——移植时这个探测逻辑要改为直接对齐 Leaves 的 `0126` 补丁。
- **工作量**：5–6 人日

#### 4.2.4 RailOptimization — 铁轨动力传播

- **许可**：GPL-3.0 ｜ **Mixin**：5 有效（另有 4 个空壳）｜ **MC**：26.1（声明支持 26.1–26.3）
- **机制**：用自研 `RailLogic`/`RailSignalSearcher`/`RailPath`/`RailLaneUpdater`（约 2000 行）替换 `PoweredRailBlock#updateState` 的 BFS 搜索与递归更新；`RailSearchCache` 为开放寻址 long→int 缓存，容量随 `railPowerLimit`（上限 1024）。
- **Bridge**：`org.snowballmc.snowball.railoptimization/`（整个 `RailLogic` 家族平移）
- **Patch**：`0142-Optimize-powered-rail-updates.patch`（已落地）
  - `PoweredRailBlock#updateState`（`@WrapMethod` → 前置调用 `RailLogic.tryCustomUpdateState`，false 则回落原版）
  - `BaseRailBlock#neighborChanged` 的 `shouldBeRemoved`
  - `Level` 实现 `LevelEpochAccess`（duck 接口，缓存按 epoch 失效）
  - `LevelChunk#setBlockState` HEAD（缓存失效）
  - `BlockState` 实现 `RailStateAccess`
- **配置**：`performance.optimize-powered-rail`（默认 `false`）+ `performance.rail-power-limit`（默认 `8`，范围 1–64）
- **优势**：模组自带约 250 个 gametest 场景，**可直接改造为 Leaves 的回归基准**，这是 19 个模组里唯一自带完整行为测试套件的。
- **风险**：`powerLimit` 改变红石/信号语义；`railShape` 序数表与 `shouldBeRemoved` 描述符随 NMS 版本漂移。
- **工作量**：6–7 人日

#### 4.2.5 Cerulean — 进度触发器节流

- **许可**：⚠️ **真实许可为自定义的「Toni's MMC License」，已获原作者移植授权**。仓库根的 `LICENSE.md` 在提交 `d854107` 被删除，从 `836ec62` 可恢复正文：要求 fork 变现时 50% 收益归原作者、禁止转载到不付费站点、明确禁止 relicense、All other rights reserved；`gradle.properties` 里的 `mod.license=GPLv3` 属不实声明。该许可自带「Exceptions / changes may be made to the above terms upon request」条款，**已由原作者书面同意移植**，补丁头记录了授权事实，版权仍归原作者。
- **已落地**：补丁 `0147-Cerulean-inventory-change-trigger-throttle.patch`
- **Mixin**：5 ｜ **MC**：26.3 ｜ 服务端逻辑
- **机制**：`InventoryChangeTrigger#trigger` 里的三条短路——采样节流（每玩家每物品 5 次变动判定一次）、堆变空、堆变小；外加 `AbstractContainerMenu#addSlot` 用容器现有物品初始化 `lastSlots`，避免开箱刷屏。
- **未移植的 3 项**：多谓词短路、堆叠阈值门、`count` 前置比较。它们依赖 `@Local` 捕获一个在重度补丁方法内部构造的列表，以及一个资源重载监听器，做成源码补丁太脆。
- **配置**：`cerulean-skip-ticks`、`cerulean-ignore-emptied-stacks`、`cerulean-ignore-decreased-stacks`、`cerulean-init-last-slots`，**全部默认 `false`**（前三项会跳过原本执行的条件判定）。
- **Bridge**：`org.snowballmc.snowball.cerulean/`（`StackSizeThresholdManager`、`ThrottleState`）
- **Patch**：`0148-Throttle-inventory-change-trigger.patch`
- **配置**：`performance.throttle-advancement-trigger`（默认 `false`）
- **注意**：
  - Leaves 已有 `0063 skip-cloning-advancement-criteria`（`performance.skip-cloning-advancement-criteria`），需与之对齐语义，避免双重节流。
  - 模组的 `skipTicks` 用普通 `HashMap`，**Folia 下不安全**；Leaves 若不是区域化线程则无碍，但应在 Bridge 中改为 `ConcurrentHashMap` 以防未来迁移。
- **风险**：**会漏触发进度**（语义变更），必须默认关闭。
- **工作量**：3–4 人日（许可解决后）

---

### 4.3 T2 梯队（只取子集）

#### 4.3.1 ModernFix — 从 120 条 mixin 中挑 6–8 项

模组按目录即选项，可直接按目录裁决：

| 子模块 | 内容 | 裁决 |
|---|---|---|
| `bugfix/chunk_deadlock` | `ServerChunkCache`/`ChunkMap` 死锁修复 | ⚠️ 先查 Paper 上游是否已修 |
| `perf/compact_PalettedContainer` | PalettedContainer 紧凑化 | ✅ 移植 |
| `perf/cache_strongholds` | 要塞位置缓存 | ✅ 移植 |
| `perf/dedup_multi_objs` / `registry` | 注册表/对象去重 | ✅ 移植（与 FerriteCore ④ 二选一，避免重复） |
| `perf/dfu` | DataFixer 延迟/清理 | ✅ 移植（服务端启动收益） |
| `perf/dedicated_reload_executor` | 资源重载专用线程池 | ⚠️ 需评估线程池归属 |
| `perf/dynamic_resources` | 模型延迟加载 | ❌ 纯客户端 |
| `perf/encoder_cache_leak` | 编码器缓存泄漏 | ⚠️ 主要客户端 |
| 其余 `client` 58 条 | 渲染/UI/纹理/字体 | ❌ 全部跳过 |

- **落地方式**：每采纳一项产出一个独立小补丁（`0149-`…），便于单独回滚。
- **Config 键**：`performance.modernfix-<子项>`。
- **工作量**：每项 1–2 人日，全集 8–12 人日。

#### 4.3.2 krypton — 只取压缩/加密，避开 `@Overwrite`

- **许可**：LGPL-3.0
- **10 条 mixin** 逐条裁决：

| 目标 | 内容 | 裁决 |
|---|---|---|
| `Connection`（加密） | `VelocityCipher` 替换 vanilla `CipherDecoder/Encoder` | ✅ 有价值，但需确认 Paper 是否已有 AES 加速 |
| `Connection`（压缩） | `VelocityCompressor`（libdeflate）替换 `CompressionDecoder/Encoder` | ✅ 移植 |
| `Connection`（prepender） | `@Overwrite createFrameEncoder` | ❌ G5 否决（`@Overwrite`） |
| `Varint21FrameDecoder` | `@Overwrite decode` + 修 nullping | ❌ G5 否决（`@Overwrite`），**若 CVE 相关则单独做安全补丁** |
| `ServerLoginPacketListenerImpl` | 在 `setEncryptionKey` 后重注入 | ✅ |
| `LegacyQueryHandler` | 非 active 时清理 buffer（安全修复） | ✅ 独立安全补丁 |
| `VarInt`/`Utf8String` | 微优化 | ⚠️ 收益极低，跳过 |
| `ServerEntity` | `Collections.emptyList()` → `ImmutableList.of()` | ⚠️ 跳过（Paper 可能已优化） |
| `SharedConstants` | `ResourceLeakDetector` 调试 | ✅ 一行 |

- **原生库问题**：模组 shade 了 `com.velocitypowered:velocity-native:3.4.0-SNAPSHOT`（4 平台 .so/.dll）。移植需在 `leafsclip` 打包路径里增加 native 解包逻辑，或在 Config 关闭时**不加载 native**（纯 Java 回退）。
- **明确不做**：不替换 Netty `EventLoopGroup`、不替换 allocator（模组本身也没做，只把 `io.netty.allocator.maxOrder` 设为 9）。
- **与 Paper 的冲突面**：Paper 已重写网络 pipeline 的 handler 命名与结构（`compress`/`decompress`/`decrypt`/`encrypt`/`splitter`/`prepender`），krypton 的 `@Inject` 点依赖这些名字。
- **工作量**：5–7 人日

#### 4.3.3 PacketFixer — 大部分与 `0008-Disable-packet-limit` 重复

- **19 条 mixin** 几乎全是 `@ModifyConstant` 数值放宽（包体上限、varint、NBT、字符串、keepAlive 超时、登录超时...）。
- **裁决**：Leaves 已有 `paper-patches/0008-Disable-packet-limit`。**只取未覆盖的少量项**：
  - `ClientboundLevelChunkPacketData`（2097152 上限）
  - `NbtAccounter`
  - `ServerCommonPacketListenerImpl` keepAlive / `ServerLoginPacketListenerImpl` 超时（若配置需要）
- **不要取**：`Config.allSizesUnlimited = true` 的默认值（等于关掉抗 DoS 上限）。
- ⚠️ 该模组的 `PayloadHelperMixin` 混入 **fabric-api 内部类** `net.fabricmc.fabric.impl.networking.payload.PayloadHelper`，移植时删除。
- **工作量**：1–2 人日

---

### 4.4 T3 梯队（只搬思路 / 暂缓）

#### 4.4.1 ScalableLux — 先核实，再决定

- **阻塞项**：① `FlowSched/` 子模块**未检出**（空目录），`settings.gradle` 对其实施 `includeBuild` + `substitute`，当前**无法构建**；② 需先核实 Paper 上游是否已内置 Starlight 式光照引擎（§3 G2）。
- **若核实为「Paper 未内置」**：Bridge 需平移约 4.7k 行（`StarLightEngine` 1590、`StarLightInterface` 997、`SkyStarLightEngine` 705、`SWMRNibbleArray` 447）+ 33 行 accessWidener 转 Patch 形态 B + 自建 `ExecutorManager`（并行度 `max(1, cores/3)`）。工作量 **20–30 人日**，且必须从 `github.com/RelativityMC/FlowSched` 补齐子模块。
- **若核实为「Paper 已内置」**：**否决**，转而只借鉴其 `SWMRNibbleArray` 的并发写读取思路。
- 存档兼容：不改磁盘格式（存盘时 `SWMRNibbleArray` → vanilla `DataLayer`），读盘失败 fallback 重算光照——这一点设计良好，值得借鉴。

#### 4.4.2 AsyncLogger — 不移植，改用 log4j2 原生配置

- **模组真正的注入点不是 mixin**（2 条注入体全被注释，26.x 上实际生效 0 条），而是 preLaunch 阶段**把 core 类 define 进系统 ClassLoader** + 替换 `LogManager` 工厂为 `Log4jContextFactory(BasicAsyncLoggerContextSelector)`。
- **结论**：这套 classloader 手术在 Paper 上既无必要也无载体。等价效果只需在 `log4j2.xml` 配 `AsyncLogger`/`AsyncRoot` 并设系统属性：
  - `log4j2.asyncLoggerRingBufferSize`（默认 16384）
  - `log4j2.asyncQueueFullPolicy`、`log4j2.discardThreshold`
- **工作量**：0.5 人日（配置 + 压测），远低于移植。

#### 4.4.3 Async — 只借鉴（与 worldthreader 不同，它是同维度内并行）

> **注意区分**：`Async` 是**同一维度内**把实体 tick 拆成并行批次（改锁契约），`worldthreader` 是**维度之间**并行（维度内仍单线程顺序）。前者动的是并发语义，后者动的是线程归属。所以 worldthreader 可移植（§4.6.1），而 Async 不移植。

- **GPL-3.0 许可无障碍**，但 92 个 `@Mixin` 重定义了实体 tick 的线程模型与锁契约；自研并发容器（`Long2ObjectConcurrentHashMap` 等）、`SynchronisePlugin` 加载期改写方法为 `synchronized`、`shouldTickSynchronously()` 白名单。
- **不移植的原因（与 Folia 无关）**：它要把 `Entity`/`EntityTickList`/`PalettedContainer`/`LevelTicks` 等一大批数据结构整体换成并发版本，并与 Lithium/VMP/C2ME 显式对齐或互斥；任一处签名或语义偏移都变成难复现的并发 bug。这是「重写锁契约」，不是「加个特性」。
- **建议**：**不移植**。可借鉴两点：① `@AsyncCompatible` 风格的"哪些实体可异步"白名单设计；② 其 `IteratorSafeOrderedReferenceSet` 等并发容器实现——后者若 worldthreader（§4.6.1）在计分板/实体查找表上需要并发容器，可直接复用这些实现。
- **与 worldthreader 的关系**：二者**不可同时开启**（一个改维度内并发，一个改维度间线程）。若先做 worldthreader，Async 永久搁置。

#### 4.4.4 （已移至 §4.6「T1★ 重头戏」）worldthreader

见 §4.6.1。

#### 4.4.5 Jasione — 载体在 Paper 上不存在

- 0 个 `@Mixin`。它挂在 **Mixin 自身的 coprocessor 链**（反射写 `MixinProcessor.coprocessors`）、NeoForge `ClassProcessor`、Forge `ILaunchPluginService` 三条路径上，对**所有类**做 ASM 数据流分析，把 `Enum#values()` 的数组克隆替换为运行期生成的缓存类。
- **在 Paper 上机制不可搬运**（无 Mixin 处理器链）。但它解决的问题在 Paper 上可以用更轻的方式达成：**对热路径枚举直接写源码级 `static final` 缓存数组**，不需要全类 ASM 分析。
- **建议**：不移植机制，只做一次「热路径 `values()` 调用点普查」（spark 采样 + grep），对 top-N 处手工缓存。工作量 1 人日。

#### 4.4.6 Ferrite — Rust 内核不可替代

- **Java + Rust 混合**：Rust 侧 4 个 crate 约 14.3k 行（cramming、实体物理、红石 BFS、density、aquifer、terrain、perlin/simplex、surface rule、nav cache、climate、Rayon 池初始化），经 **JNI** 暴露为 `RustBridge` 的 native 方法；Java 侧 30.6k 行含 71 条 mixin。
- **硬伤**：Rust 侧**自建并显式定容 Rayon 池**，与 Leaves 的线程模型直接冲突；JNI 符号名与 `RustBridge` 方法签名强绑定；26.3 已重写 density function，模组自身有 6 条 mixin 被 `build.gradle` 排除编译。
- **建议**：**不整体移植**。可单独评估三个自包含子项：① 红石算法（与 alternate-current 同源，二选一）；② `nav cache`；③ `cramming`（实体挤压）。若要做裸 JNI，需先解决 native 打包与 Leaves 的 `leavesclip` 加载路径问题。
- **工作量**：单子项 8–15 人日，整体 60+ 人日，不建议。

---

### 4.5 T4 梯队（否决）

#### 4.5.1 InfChatHistory

- **纯客户端**（`"environment": "client"`），唯一 mixin 把 `ChatComponent` 的历史上限常量 100 → 2147483646。**服务端无对应逻辑**，Leaves 无法承载。
- 附带问题：`LICENSE` 为 CC0 1.0，而 `fabric.mod.json` 声明 MIT——**自相矛盾**；且引入了 `fabric-language-kotlin` 依赖但仓库内 0 个 `.kt` 文件。
- **裁决**：否决（若要此功能，应由客户端模组实现，或作为 Leaves 客户端配套单独开发）。

---

### 4.6 T1★ 重头戏

#### 4.6.1 worldthreader — 维度级并行 tick

- **许可**：MIT ｜ **Mixin**：71（26 个不同 NMS 类）｜ **MC**：26.3 ｜ 纯服务端 ｜ **复杂度：高** ｜ **工作量：20–28 人日**

**先纠正一个判断前提。** 本文档 v1.0 曾以「与 Folia 互斥」为由将 worldthreader 列为不移植，这是错误的推理：**Leaves 的基座是 Paper，不是 Folia**。Paper 是单主线程 tick 全部维度；worldthreader 做的正是「把维度级 tick 拆到各自线程」，因此在 Paper 上它是**可行的增量能力**，而非冲突项。把它与 Folia 并置只是说明二者是互斥的两种线程模型方案——你只能选一个，但不代表不能选 worldthreader。

| 维度 | worldthreader | Folia |
|---|---|---|
| 切分粒度 | 世界（维度） | 区块区域（region） |
| 并发度 | 维度间并行，维度内单线程顺序 | 区域内并行，跨区异步 |
| 语义 | 尽力保住 vanilla 单 tick 顺序 | 重新定义语义 |
| API | 无（只用 gamerule 开关） | RegionScheduler / EntityScheduler |
| 与 Paper 基座 | **可直接作为增量特性** | 需要换基座 |
| 与 Leaves | **可选特性** | 不适用 |

**机制（必须完整理解后再动手）**

1. **一维度一线程**：`WorldThreadingManager` 构造时对 `server.getAllLevels()` 每个 `ServerLevel` 起独立 `Thread`（`runWorldThread`）。
2. **主线程脱离世界循环**：`@Redirect` 掉 `MinecraftServer#tickChildren` 里对 `getAllLevels()` 的调用，返回 `Collections.emptyList()`。
3. **两层 Phaser 栅栏**：`tickBarrier`（tick 边界对齐全部维度）+ `withinTickBarrier`（tick 内阶段对齐）。阶段序列：`WORLD_TICK → RECEIVE_TELEPORTS → TICK_AFTER_TELEPORT →（RECOVER_FAILED_TELEPORTS）`。
4. **独占世界访问**：`AtomicInteger threadsRequestingExclusiveWorldAccess` + `Semaphore yieldingLevels/reacquireLevels` + `AtomicReference<Thread> threadWithExclusiveWorldAccess`。所有跨维度共享写（`/time`、`/weather`、`/gamerule`、`/worldborder`、whitelist/ban/op、schedule、计分板、`DimensionDataStorage`）都必须先取得独占权。
5. **线程归属转移**：`ThreadOwnedObject` + `ThreadHelper.swapOnMultithreadTickStart/End`，在 tick 前把 `ServerLevel`/`ServerChunkCache` 的「所属线程」换成世界线程，tick 后换回。
6. **语义取舍**：`Entity#level` 视为 effectively final；换维度的实体「本 tick 消失、tick 末落地」；玩家换维度改为**重建 `ServerPlayer` 实例**（instance_swap）。

**Bridge 设计**

```
org.snowballmc.snowball.worldthreader/
├── api/
│   ├── ThreadOwnedObject.java          NMS 侧 duck 接口（leaves$getOwnerThread / swapOwner）
│   └── ExclusiveWorldAccess.java       独占访问令牌
├── common/thread/
│   ├── WorldThreadingManager.java      维度线程生命周期、两层 Phaser
│   ├── ThreadHelper.java               线程归属交换
│   └── WorldThreaderTickPhase.java     阶段枚举
├── common/ServerWorldTicking.java      单维度 tick 主循环
├── common/scoreboard/                  线程安全计分板（替代原 vanilla 实现）
└── Hook.java                           唯一入口
```

关键改造点：

- **删除 fabric-api 依赖**：模组用了 `GameRuleBuilder`（`fabric-game-rule-api-v1`）与 `ServerLevelEvents.LOAD/UNLOAD`（`fabric-lifecycle-events-v1`），且**未在 `fabric.mod.json` 的 `depends` 里声明**——移植时改为 Leaves 自身的 gamerule 注册与 `ServerLevel` 生命周期 hook。
- **`WorldThreaderMixinPlugin` 是空壳**（`shouldApplyMixin` 恒 true），无需搬运条件逻辑；真正的开关在本技术书要求的配置项上。
- **计分板线程安全化**（12 条 mixin）：`Scoreboard`/`ServerScoreboard`/`Score`/`Objective`/`PlayerScores`/`PlayerTeam`/`ScoreboardSaveData`/`ScoreboardCommand` 等，建议整体替换为 Bridge 中的并发实现，而不是逐方法打补丁。

**Patch 规划**（71 条 mixin 按类簇合并，不要一 mixin 一补丁）

| 补丁 | 覆盖 | 条数 |
|---|---|---|
| `01XX-Worldthreader-core.patch` | `MinecraftServer`/`ServerLevel`/`ServerChunkCache`/`Level` 的 tick 与线程归属 | 约 8 |
| `01XX-Worldthreader-teleport.patch` | 跨维度传送簇：`Entity`/`ServerPlayer`/`ItemEntity`/`FallingBlockEntity`/`PrimedTnt`/`ThrownEnderpearl`/`Projectile`/`NetherPortalBlock`/`EndPortalBlock`/`PlayerList`/`ServerGamePacketListenerImpl`/`LivingEntity` | 19 |
| `01XX-Worldthreader-compat.patch` | 线程安全兼容：`EntitySelector`/`Sensor`/红石类/`ServerTickRateManager`/命令类/`DedicatedServer` | 20 |
| `01XX-Worldthreader-scoreboard.patch` | 计分板线程安全（12 条） | 12 |
| `01XX-Worldthreader-vanilla-fixes.patch` | `EntityReference`/`EntityLookup`/`PersistentEntitySectionManager`/`NeutralMob`/`Leashable` | 7 |

**配置（G6 逐项落实）**

```java
@GlobalConfig(value = "worldthreader", lock = true)
public boolean worldthreader = false;          // 需重启：线程模型不可热切换

@GlobalConfig(value = "worldthreader-parallelism", lock = true, validator = WorldthreaderParallelismValidator.class)
public int worldthreaderParallelism = 0;       // 0 = 维度数（每个维度一线程）

@GlobalConfig("worldthreader-extra-entity-tick-after-teleport")
public boolean worldthreaderExtraEntityTickAfterTeleport = false;  // 对应原 gamerule

@GlobalConfig("worldthreader-debug")
public boolean worldthreaderDebug = false;
```

- 原模组用 gamerule（`worldthreader:active` 等）做开关，移植后**必须改为 `leaves.yml` 配置键**，`lock = true`（重启生效），并在 `ConfigValidator.runAfterLoader` 中做线程池创建/销毁的副作用管理。
- **默认 `false`**：这是语义影响最大的一个特性（改变 tick 线程归属），绝不能默认开。

**风险与缓解**

| 风险 | 说明 | 缓解 |
|---|---|---|
| 插件单线程假设 | 大量插件（含 Bukkit 事件监听）隐含「都在主线程」 | 服务器启动时若检测到 worldthreader 开启，输出显著警告；文档明确列出不兼容类别 |
| 跨维度共享状态 | `/time`、`/weather`、`/gamerule`、计分板、`DimensionDataStorage` 并发写 | 必须完整保留「独占世界访问」信号量机制，不得简化 |
| 玩家换维度 | 模组用 example_swap 重建 `ServerPlayer`，破坏 vanilla 边缘行为 | 保留该设计但写入配置文档；相关插件（如跨服传送）需回归 |
| MSPT 语义 | 每 tick 等待最慢维度，TPS 波动更明显 | 文档化；考虑加「慢维度告警」日志 |
| 与 Async 冲突 | Async 也改实体 tick 线程模型 | 两者**不可同时开启**，配置校验器里做互斥检查 |
| `Entity#level` effectively final | 与部分实体类插件冲突 | 文档化 |

**为什么值得做**：这是 19 个模组里**唯一能显著降低主线程 MSPT 上限**的方案（其余都是单点优化）。多维度服务器（主世界 + 下界 + 末地 + 若干维度世界）收益最大。

**为什么放在 T1★ 而不是 T1**：工作量与风险都是 T1 里最高的，需单独立项、单独里程碑、单独回归套件，不适合夹在其他 T1 项里顺手做。

---

### 4.7 最终裁决总表（v1.3）

下表是 19 个模组在真实代码库上跑完后的最终归属，每一项都有行号级证据。

#### 已移植（8 个补丁 / 7 个模组）

| 补丁 | 模组 | Bridge | 配置键 | 验证 |
|---|---|---|---|---|
| `0139` | mcm-rabbit-pathfinding-fix | `pathfix`（1 类） | `path-navigation-timeout-fix` | ✅✅✅ |
| `0140` | StructureLayoutOptimizer | `structurelayout`（7 类） | `structure-layout-optimizer`、`-deduplicate-template-pool` | ✅✅✅ |
| `0141` | Bellows | `bellows`（6 类） | `bellows-nbt-optimizations`、`-selector-optimizations`、`-skip-server-culling` | ✅✅✅ |
| `0142` | RailOptimization | `railoptimization`（13 类） | `optimize-powered-rail`、`rail-power-limit` | ✅✅✅ |
| `0143` | FerriteCore（2/5 模块） | `ferritecore`（3 类） | `ferrite-blockstate-dedup`、`ferrite-component-patch-shrink` | ✅✅✅ |
| `0144` | Oushii | `oushii`（7 类） | `explosion-engine`、`-cluster-radius`、`-max-primed` | ✅✅✅ |
| `0145` | ModernFix（3 模块） | `modernfix.bridge`（4 类） | 5 个 `modernfix-*` | ✅✅✅ |
| `0146` | ModernFix（要塞缓存） | 同上 | `modernfix-cache-strongholds` | ✅✅✅ |
| `0147` | Cerulean（经原作者授权） | `cerulean`（2 类） | 4 个 `cerulean-*` | ✅✅✅ |

#### 上游已覆盖（移植即负收益，整体计入本类的 3 个模组）

| 模组 | 证据 |
|---|---|
| **alternate-current** | Paper 已内置：`alternate/current/wire/` 9 类 2246 行在 NMS 树内；`WorldConfiguration` 有 `redstone-implementation: VANILLA/EIGENCRAFT/ALTERNATE_CURRENT`；`RedstoneWireBlock` 有 4 处 AC 分支 |
| **ScalableLux** | Paper 经 Moonrise 已内置 Starlight：`ca/spottedleaf/moonrise/patches/starlight/light/{StarLight,SkyStarLight,BlockStarLight}Engine`；`ThreadedLevelLightEngine implements StarLightLightingProvider` |
| **FerriteCore ②③⑤**（*部分*，模组整体计入「已移植」） | ②`StateHolder` 已置 `neighbors = null`；③`propertyKeys/propertyValues = null`（`// Paper end - optimise blockstate property access`）；⑤`PalettedContainer.threadingDetector` 已注释为 `// Paper - unused` |
| **PacketFixer** | 与 `paper-patches/0008-Disable-packet-limit.patch` 大面积重复，仅剩 3 项未覆盖 |
| **Bellows blockforceload**（*部分*，模组整体计入「已移植」） | MC-168596 上游已修：Moonrise 在 `finally` 释放票证，模组的 `removeTicket(UNKNOWN)` 在 26.3 匹配不上任何票证 |

#### 技术否决

| 模组 | 原因 |
|---|---|
| **Ksyxis** | MC 1.21.9+ 删除出生点常驻区块；`setDefaultSpawnPos`/`prepareLevels`/`isSpawnChunk` 全部消失，`spawnChunkRadius` 被 `GameRuleRegistryFix` 移除 |
| **InfChatHistory** | 纯客户端（`environment: client`），服务端无对应逻辑 |
| **Jasione** | 挂在 Mixin coprocessor 链上，Paper 无此载体 |
| **AsyncLogger** | 2 条 mixin 注入体全被注释，实际生效 0 条；等价效果用 log4j2 配置即可 |
| **krypton** | 压缩/加密必须 `velocity-native` 原生库；`Varint21FrameDecoder` 已被 Paper 取代（且更严格）；其余为 `@Overwrite` 微优化 |
| **ModernFix attribute_supplier_dedup** | `AttributeSupplier` 已由 `DefaultAttributes` 按实体类型各建一份（唯一构造点 `Builder#build()`），跨类型去重收益近 0，且共享 `AttributeInstance` 有状态污染风险 |

#### 超大工程（需独立立项）

| 模组 | 状态 |
|---|---|
| **worldthreader** | 71 条 mixin，维度级并行 tick，见 §4.6.1 |
| **Async** | 92 条 mixin，重定义实体 tick 的锁契约，与 worldthreader 互斥 |
| **Ferrite** | Rust cdylib + JNI + 自建 Rayon 池，约 14.3k 行 Rust |

---

## 5. 标准作业流程（SOP）

### 5.0 补丁工作流（实测踩坑记录，务必先读）

本节是 v1.2 在真实环境中跑通后补写的。**Leaves 的补丁系统与文档描述有出入，以下为实测结论。**

| 事实 | 说明 |
|---|---|
| `applyAllPatches` 是唯一入口 | `applyPatches` **不存在**（任务名歧义），正确的是 `applyAllPatches` |
| 补丁回写任务 | `rebuildMinecraftFeaturePatches`（minecraft 侧）/ `rebuildAllServerPatches` |
| `snowball-server/` **不是** git 仓库 | `docs/CONTRIBUTING_cn.md` 说它"不是正常的 git 仓库"，实测连 `.git` 都没有。`git -C snowball-server log` 会向上找到根仓库，产生误导 |
| 真正的补丁仓库在缓存里 | `snowball-server/.gradle/caches/paperweight/taskCache/runLeavesSetup`（每个提交 = 一个补丁） |
| **`rebuildMinecraftFeaturePatches` 不幂等** | 实测在无实质改动时会重写 49 个补丁的 `index` 行与行号偏移（189 增 189 删，语义零变化）。**不要**用它的输出当提交内容 |
| **推荐做法：手写补丁文件** | 直接写入 `snowball-server/minecraft-patches/features/NNNN-<Title>.patch`，再用 `applyAllPatches` 验证；不要依赖 rebuild 生成 |

**补丁文件四条硬性约定（缺一即失败）**

1. **`From:` 必须是 `Name <email>` 完整形式。**
   写 `From: LeavesMC` 会导致 paperweight 内部的 `git am` 报
   `fatal: empty ident name (for <>) not allowed`，而报错被吞成一句
   `PaperweightException: Failed to apply patches`，极难定位。
2. **文件名必须与 `Subject:` 严格一致**：`0138-BLinear-region-format.patch` ↔ `[PATCH] BLinear region format`（连字符↔空格，逐词对应）。
3. **`index` 行用 40 位完整 blob 哈希。** 用 `git diff --full-index` 生成；`git diff` 默认的 7 位缩写不被接受。
4. **编号接续，不得跳号**，当前最大 `0138`，新补丁从 `0139` 起。

**生成补丁体的正确姿势**

```bash
# 在补丁仓库里生成权威 diff（避免手写 hunk 出错）
T2="snowball-server/.gradle/caches/paperweight/taskCache/runLeavesSetup"
cp snowball-server/src/minecraft/java/<path>.java "$T2/<path>.java"
git -C "$T2" diff --full-index -- "<path>.java" > /tmp/body.diff
git -C "$T2" checkout -- "<path>.java"     # 立刻还原，别留脏
```

然后把 `/tmp/body.diff` 拼到补丁头之后。

**验证闭环（每次必跑）**

```bash
./gradlew applyAllPatches                        # 补丁能否被 git am 接受
./gradlew :snowball-server:compileJava             # 代码能否编译
./gradlew :snowball-server:createLeavesConfig      # leaves.yml 键能否生成
```

> 注意：`applyAllPatches` 失败时**不会**打印 git 的原始报错。要定位根因，直接在
> `runLeavesSetup` 里手工跑一次 `git am --3way <patch>` 看真实 stderr，比加 `--info`/`--debug` 有效得多。

### 5.1 八步流程

每个模组严格走 8 步，每步有可验收产物。

| 步 | 动作 | 产物 | 关卡 |
|---|---|---|---|
| S1 | **过闸**：G1–G5 逐条判定并记录 | `移植评估表`（附在 PR 描述） | 组长签核 |
| S2 | **映射核对**：确认模组用的是 Mojmap；若不是，先机械转换 | 转换清单 / 「无需转换」结论 | 编译通过 |
| S3 | **剥离平台层**：删除所有 loader 入口、fabric-api 调用、`ServiceLoader`、config 库；把 accessWidener 转成 Patch 形态 B | Bridge 骨架（可编译、无 `net.fabricmc.*`） | `grep -r "net.fabricmc"` 为空 |
| S4 | **平移核心逻辑** 到 `org.snowballmc.snowball.<slug>/common/` | 可编译的 Bridge | 单测（若有）通过 |
| S5 | **写 Hook 入口**：把模组的每个 mixin 注入点翻译成 `Hook` 的静态方法 | `Hook.java` | 评审通过 |
| S6 | **落 Patch**：在 NMS 对应位置插入配置门控调用 | `NNNN-*.patch` | `./gradlew applyPatches` 通过 |
| S7 | **加配置（强制，见 §3 G6）**：`PerformanceConfig` 里为每个原子子功能加独立 `@GlobalConfig` 字段；语义变更写 Javadoc；线程模型类加 `lock = true`；带参数的加 `ConfigValidator` | 配置键 + 副作用 validator + `createLeavesConfig` 能生成 | **G6 的 C1–C8 全部通过** |
| S8 | **回归**：行为对拍 + 性能基准 + 上游 rebase 演练 | 测试报告 | 见 §8 |

**每个 PR 必须包含**：补丁头部的 `Powered by` 署名块、Bridge 文件的许可头（GPL-3.0 或派生源许可）、配置键的语义说明、以及 S8 的测试结论。

---

## 6. 关键工艺细节

### 6.1 Mixin 注解 → Patch 手法对照

| Mixin 手法 | Patch 落法 | 注意 |
|---|---|---|
| `@Inject(HEAD/RETURN/INVOKE)` | 直接在方法源码插入调用/`if` | 用 `// Leaves - <主题>` 标记 |
| `@ModifyConstant` | 把常量改为读取配置/字段 | 最省事的一类，优先 |
| `@ModifyVariable` | 局部变量赋值处插入 | 注意作用域重叠 |
| `@Redirect` | 把被重定向的调用替换为 Bridge 调用 | 需保持返回值/异常语义 |
| `@WrapOperation` / `@WrapMethod`（MixinExtras） | 手动构造 `if (Bridge.tryXxx(...)) return;` 前置分支 | 原逻辑必须保留为 fallback |
| `@ModifyExpressionValue` | 把表达式抽出为局部变量后包裹 | |
| `@Overwrite` | **禁止**。改写为「前置 hook + 原逻辑保留」 | 见 §6.4 |
| `@Accessor` / `@Invoker` | Patch 形态 B 调可见性，或 duck 接口 | |
| `accessWidener` / `classTweaker` | Patch 形态 B 直接改 `private`→`public` | 逐条核对，不要漏 |

### 6.2 duck 接口（形态 C）的用法与边界

```java
// snowball-server/src/main/java/org/snowballmc/snowball/<slug>/api/ExtendedLevelChunk.java
public interface ExtendedLevelChunk {
    long leaves$getEpoch();
}
```

Patch 侧只需 `implements` + 加字段与方法。**边界**：duck 接口方法名统一以 `leaves$` 前缀，避免与 Paper/其他 fork 的 duck 接口撞名（Paper 用 `paper$`，Leaves 现有代码也用 `lithium$` 之类的模组前缀）。

### 6.3 线程池归属

若模组自带线程池（ScalableLux 的 `ExecutorManager`、ModernFix 的 reload executor、Ferrite 的 Rayon）：

1. **不得**在 Bridge 静态初始化时创建。
2. 必须绑定 Leaves 的生命周期（服务器启动创建 / 停止 shutdown），且 `setDaemon(true)`。
3. 线程命名必须带前缀（如 `Leaves-<slug>-<n>`），便于 spark/线程转储识别。
4. 并行度默认不得取 `availableProcessors()`（与其他池争抢），按模组原值或 `max(1, cores/N)`。

### 6.4 `@Overwrite` 禁区

Paper 的补丁密度极高，`@Overwrite` 等价于「抢走整个方法」。规则：

- 一律转换为「前置 hook + 原逻辑保留」。
- 若原方法确实无法保留（如 krypton 的 `createFrameEncoder`），则该子项**放弃**，除非有安全理由并单独评审。
- 例外通道：`FerriteCore` 的 `StateHolder#initializeNeighbors` 属结构性重写，可申请例外，但必须在配置默认关闭 + 文档标注冲突风险。

### 6.5 原生库处理

- **krypton**（velocity-native）与 **Ferrite**（Rust cdylib）都需要从 jar 解包 native 并 `System.load`。
- Leaves 的打包链路是 `leavesclip`（`org.leavesmc:leavesclip:3.0.10`），需确认其是否支持 native 资源透传。**若不支持，必须提供纯 Java 回退路径**，并在 native 加载失败时自动降级 + 日志警告，而不是抛异常。
- 二进制体积与跨平台（win/linux/macos × x64/aarch64）是长期维护负担，建议原生路径**只在收益明确时启用**。

---

## 7. 配置与开关规范

> **本文档的硬性要求：每一个移植进来的功能，都必须能在 `leaves.yml` 里被服主单独配置——独立开关、独立参数、独立默认值。不允许捆绑、不允许隐式生效。**

### 7.1 机制事实（已核实，据此写代码）

`leaves.yml` 的能力由三件东西构成，写配置前必须先理解：

| 组件 | 位置 | 作用 |
|---|---|---|
| `@GlobalConfig(value, lock, validator)` | `config/annotations/GlobalConfig.java` | 声明一个配置项。`value` = yml 键名；`lock = true` = 该键热重载时被跳过（`initConfig` 里 `if (loaded && globalConfig.lock()) return;`），即**重启生效**；`validator` = 取值校验器 |
| `@GlobalConfigCategory(value)` | 同类 | 声明一个配置分组（如 `performance`），自动递归展开其字段 |
| `GlobalConfigManager.reload()` | `config/GlobalConfigManager.java:43` | 重新读取全部配置；对**非 lock** 项调用 `validator.runAfterLoader(value, true)` |

因此"可配置"落地为：

- **纯开关** → 普通 `boolean` 字段；
- **需要重启才安全的开关** → `lock = true`；
- **凡是有运行期副作用的功能（线程池创建/销毁、缓存清空、native 加载/卸载）→ 必须实现 `ConfigValidator.runAfterLoader(V value, boolean reload)`**，否则热重载后会出现"配置改了但行为没变"或"线程池泄漏"。

### 7.2 命名与默认值

```java
@GlobalConfigCategory("performance")
public static class PerformanceConfig {

    // ---- 纯性能等价变换：默认开 ----
    @GlobalConfig("fix-block-force-load")
    public boolean fixBlockForceLoad = true;

    // ---- 语义变更：默认关，必须注释差异 ----
    /**
     * 使用非位置相关（non-locational）红石线实现。
     * 与原版差异：红石更新顺序不再依赖位置，依赖顺序的 locational 机器会失效。
     */
    @GlobalConfig("alternate-current")
    public boolean alternateCurrent = false;

    // ---- 带参数的：必须校验范围 ----
    @GlobalConfig(value = "rail-power-limit", validator = RailPowerLimitValidator.class)
    public int railPowerLimit = 8;              // 原版 8，范围 1–64

    // ---- 线程模型类：重启生效 ----
    @GlobalConfig(value = "worldthreader", lock = true)
    public boolean worldthreader = false;

    @GlobalConfig(value = "worldthreader-parallelism", lock = true,
                  validator = WorldthreaderParallelismValidator.class)
    public int worldthreaderParallelism = 0;    // 0 = 每维度一线程
}
```

规则：

1. **纯性能等价变换** → 默认 `true`（与现有 `faster-chunk-serialization` 一致）。
2. **语义变更** → 默认 `false`，字段 Javadoc 写明「与原版差异」。
3. **bugfix** → 默认 `true`。
4. **线程模型 / native 库 / 不可热切换的** → `lock = true`。
5. **一功能一键**：一个模组内的多个原子子功能必须是多个键（如 ModernFix 采纳的 8 项 = 8 个键），不允许打包成一个 `modernfix = true`。
6. **键名**：`kebab-case`，不加模组名前缀除非易混淆（`alternate-current` 而非 `ac`）。
7. **互斥检查**：明确互斥的功能（`worldthreader` ↔ `async`）在各自 validator 里做互斥校验，冲突时启动失败并给出可读错误，而不是静默取其一。

### 7.3 键名与分组总表（移植后 `leaves.yml` 应长这样）

```yaml
performance:
  # 已存在（本次不改）
  faster-chunk-serialization: true
  sleeping-block-entity: false

  # T0
  keep-spawn-chunks: true                    # Ksyxis：false 才启用优化
  rabbit-pathfinding-fix: true
  optimize-structure-layout: false
  structure-layout-dedup-shuffled-pool: false
  bellows-selector-cache: false
  bellows-nbt-localized: false
  fix-block-force-load: true

  # T1
  alternate-current: false
  explosion-engine: false                    # Oushii
  ferrite-blockstate-dedup: false
  ferrite-compact-neighbor-table: false
  optimize-powered-rail: false
  rail-power-limit: 8
  throttle-advancement-trigger: false

  # T1★
  worldthreader: false                       # lock
  worldthreader-parallelism: 0               # lock
  worldthreader-extra-entity-tick-after-teleport: false
  worldthreader-debug: false

  # T2（逐项）
  modernfix-compact-paletted-container: false
  modernfix-cache-strongholds: false
  modernfix-registry-dedup: false
  modernfix-dfu-cleanup: false
  krypton-native-compression: false
  krypton-native-encryption: false
  packetfixer-chunk-packet-limit: false
```

### 7.4 一个正确实现副作用的范例

```java
public class WorldthreaderValidator extends BooleanConfigValidator {
    @Override
    public void runAfterLoader(Boolean value, boolean reload) {
        if (reload && value) {
            // 该键已 lock，正常不会走到这里；防御性判断
            LeavesLogger.LOGGER.warn("worldthreader 需要重启才能生效");
            return;
        }
        if (value) {
            WorldThreadingManager.start();
        } else {
            WorldThreadingManager.stop();
        }
    }
}
```

### 7.5 配置生成的验证

新增 `@GlobalConfig` 字段后必须执行：

```bash
./gradlew :snowball-server:createLeavesConfig
```

逐项确认：

1. 新键出现在 `leaves.yml` 的正确分组下，键名与默认值正确；
2. 旧 `leaves.yml`（缺新键）能平滑升级——`GlobalConfigManager` 会自动补默认值并 `save()` 回写；
3. 删除字段后 `clearRemovedConfig()` 能从 yml 中清理陈旧键；
4. 非法取值被 validator 拦下并回落默认值（手工改一个越界值验证）。

---

## 8. 兼容性与回归测试

### 8.1 三级回归

| 级别 | 内容 | 通过标准 |
|---|---|---|
| L1 编译与装配 | `./gradlew applyPatches` + `./gradlew build` | 无冲突、无警告新增 |
| L2 行为对拍 | 开关 ON/OFF 两态下跑同一场景，比较**可观察输出** | 差异项与配置文档一致，无意外差异 |
| L3 性能基准 | spark 采样 + 固定场景压测 | 目标指标改善，无其他指标回退 |

### 8.2 必须覆盖的场景矩阵

- 红石：locational 机器（若移植 alternate-current，需专门验证「破坏性」是有意为之）
- TNT：单发、链式、水下、抗爆方块、modded TNT 子类（Oushii）
- 世界生成：固定种子结构布局一致性（StructureLayoutOptimizer 的 dedup 开关必须验证「布局会变」）
- 命令/NBT：大规模 `@e` 选择器、`data merge`、函数包（Bellows）
- 铁轨：可直接复用 RailOptimization 自带的约 250 个 gametest 场景
- 出生点：无人时刷怪塔/红石是否停摆（Ksyxis）
- **worldthreader 专项**（§4.6.1，需独立套件）：
  - 多维度并发：主世界 + 下界 + 末地 + N 个自定义维度同时有实体活动
  - 跨维度传送：玩家/物品/生物/掉落方块/TNT/末影珍珠穿门，验证「本 tick 消失、tick 末落地」
  - 跨维度共享写：`/time set`、`/weather`、`/gamerule`、`/worldborder`、whitelist/ban/op、schedule、计分板命令在并发 tick 下的正确性
  - 计分板：多玩家并发改分、队伍变更、`ScoreboardSaveData` 落盘一致性
  - 插件回归：选取使用主线程假设的常见插件（经济、领地、区块操作）做冒烟
  - 关掉后行为完全回到 Paper 基线（G6 C4）

### 8.3 配置回归（对应 G6）

每个新功能必须做一遍「开关对拍」：

| 步骤 | 动作 | 判据 |
|---|---|---|
| 1 | `leaves.yml` 中功能键保持默认 | 行为 = 未移植的 Paper |
| 2 | 改为开启并重启（`lock` 项）或热重载（非 `lock` 项） | 功能生效，无异常日志 |
| 3 | 再改回默认 | 功能完全失效，无残留线程/缓存/内存增长（用 spark 或 jcmd 观察） |
| 4 | 写入非法值（越界整数、错误类型） | validator 拦下并回落默认值 + 告警日志 |
| 5 | 删除该键 | `clearRemovedConfig()` 清理陈旧键，或补回默认值 |

### 8.4 上游 rebase 演练

每次 Paper 上游升级后，必须演练一遍全部新增补丁的 rebase。**判据**：冲突只应出现在 Patch 层，Bridge 层不应因 NMS 变化而需要改签名；若 Bridge 被迫改动，说明 duck 接口/内联常量泄露了 NMS 细节，应重构。

---

## 9. 许可证合规

Leaves 继承 Paper 的 **GPL-3.0**（`LICENSE.md`）。合规要求：

| 源许可 | 处置 | 必做 |
|---|---|---|
| GPL-3.0（Async、RailOptimization、Cerulean、Oushii） | 直接合入，整体仍为 GPL-3.0 | 补丁头署名 + 文件许可头 |
| LGPL-3.0 / -only / -or-later（krypton、ScalableLux、ModernFix、AsyncLogger、Jasione） | 依 LGPL-3.0 §3 按 GPL-3.0 再发布 | 派生文件加 LGPL 头，保留原作者与出处 |
| MIT（多数） | 合入 | 保留 copyright + LICENSE 正文（可汇总进 `licenses/`） |
| Apache-2.0（mcm-rabbit-pathfinding-fix） | 合入 | 保留 copyright + **NOTICE** |
| 缺失/矛盾（Cerulean、InfChatHistory） | **阻塞** | 上游补正文或书面授权 |

**署名模板**（写入每个补丁头部，沿用 `0126` 的格式）：

```
Subject: [PATCH] <优化主题>

This patch is Powered by <模组名>(<仓库 URL>)

This patch is based on the following mixins:
* "<原 mixin 路径>"
By: <原作者>
As part of: <模组名> (<仓库 URL>)
Licensed under: <SPDX> (<许可 URL>)
```

---

## 10. 风险登记册

| ID | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 语义变更类优化被误默认开启 | 玩家机器失效、进度漏触发 | G4 强制默认 `false` + 配置注释 + 启动日志提示 |
| R2 | `@Overwrite` 与 Paper 补丁抢方法 | 上游 rebase 后静默行为偏移 | §6.4 禁区，超出即否决 |
| R3 | 全局静态去重表内存泄漏 | 长时间运行 OOM | FerriteCore 类模块加容量上限与监控指标 |
| R4 | 原生库打包/加载失败 | 启动崩溃 | 强制纯 Java 回退 + 降级日志 |
| R5 | ScalableLux 与 Paper 内置光照重复 | 白做工 20–30 人日 | §3 G2 前置核实 |
| R6 | Oushii 绕过 `Level#explode` 导致插件收不到事件 | 插件生态事故 | Bridge 内补发 Bukkit 事件（必做项） |
| R7 | 线程模型类移植（Async / worldthreader）与插件单线程假设冲突 | 数据竞争、崩溃、插件行为异常 | worldthreader 纳入 T1★ 单独立项并强制 `lock=true` + 默认关 + 与 Async 互斥校验；Async 不移植 |
| R7b | worldthreader 的跨维度共享写（`/time`、`/weather`、`/gamerule`、计分板、`DimensionDataStorage`）未加独占访问 | 静默数据损坏 | 必须完整保留原模组的「独占世界访问」信号量，评审时专项检查，不得简化 |
| R11 | 新增功能无法在 `leaves.yml` 单独关闭（违反 G6） | 出问题时无法止损，服主无法回退 | G6 一票否决；PR 模板内置 C1–C8 检查清单 |
| R8 | 补丁数量膨胀导致 rebase 成本线性上升 | 维护负担 | 一模组一补丁；子项独立编号便于回滚 |
| R9 | 许可证缺失（Cerulean） | 法律风险 | G1 阻塞，先解决再排期 |
| R10 | 26.1→26.3 API 漂移（FerriteCore、RailOptimization、Jasione） | 编译失败 | 先做版本抬升作为独立前置任务 |

---

## 11. 里程碑

| 阶段 | 内容 | 产出 | 工期 |
|---|---|---|---|
| **M0 立规范** | 落地本文档、建立 `org.snowballmc.snowball.<slug>` 骨架、确定 PR 模板与评审清单（含 G6 的 C1–C8） | 规范 + 样板目录 | 2 人日 |
| **M1 打样板** | Ksyxis → mcm-rabbit-pathfinding-fix → StructureLayoutOptimizer | 3 个补丁 + 3 组配置键，验证 SOP 全链路 | 5–8 人日 |
| **M2 攻坚一中** | alternate-current、Bellows、RailOptimization | 3 个大补丁 + 铁轨回归套件 | 18–21 人日 |
| **M3 攻坚二中** | Oushii、FerriteCore（2/5 模块）、Cerulean（许可先行） | 3 个补丁 + 爆炸事件补发 | 15–19 人日 |
| **M4 子集收割** | ModernFix 精选 6–8 项、krypton 压缩/加密、PacketFixer 剩余项 | 8–10 个小补丁（每项独立配置键） | 14–21 人日 |
| **M5 worldthreader 立项** | 维度级并行 tick：Bridge 骨架 → core patch → 传送簇 → 计分板 → 兼容簇 → 插件回归 | 5 个补丁 + 独立回归套件 + `lock` 配置组 | 20–28 人日 |
| **M6 前置调研** | 核实 Paper 光照现状；决定 ScalableLux 去留；Jasione 的 `values()` 普查；AsyncLogger 改用 log4j2 配置 | 调研报告 + 2 个小改动 | 4–6 人日 |

**总量估算**：约 **78–105 人日**（不含 ScalableLux 的 20–30 人日与 Ferrite 的 60+ 人日，两者均属「需单独立项」）。

**排序理由**：M1 用最低风险的三个模组把 Bridge+Patch 流水线跑通并沉淀 PR 模板（**含 G6 配置规范的落地样板**）；M2 拿收益高且自含测试套件的三个；M3 处理需要「行为对拍」的重活；M4 是零散收割；M5 是唯一的架构级改造，放在流水线与评审标准成熟之后启动，避免一边建规范一边啃最难的骨头；M6 全是「先搞清楚再动手」的调研，可与 M4/M5 并行。

---

## 12. 附录：待核实清单

以下事项在动工前必须澄清，否则相关排期无效：

1. **Paper 上游是否已内置 Starlight 式光照引擎** → 决定 ScalableLux 去留。动作：`./gradlew applyPatches` 后检查 `paper-server/src/minecraft/java/net/minecraft/server/level/ThreadedLevelLightEngine.java` 是否被改写。
2. **Paper 上游是否已修 chunk deadlock** → 决定 ModernFix `bugfix/chunk_deadlock` 是否重复。动作：检查 `ServerChunkCache`/`ChunkMap` 是否有死锁相关补丁注释。
3. **leavesclip 是否支持 native 资源透传** → 决定 krypton 压缩/加密与 Ferrite 子项的可行性。
4. **Cerulean 的许可证正文** → 需上游补齐或书面授权。
5. **`performance.cache-climb-check`、`reduce-chuck-load-and-lookup`、`optimize-noise-generation` 三个配置键无补丁引用** → 是孤立占位还是实现遗漏，需确认后再决定是否复用这些键。
6. **worldthreader 前置**：确认 Leaves 现有代码中没有「所有世界必须在主线程 tick」的硬假设（例如某些插件 API 或 `MinecraftServer` 的同步点假设），以及 `MinecraftServer#tickChildren` 是否已被其他 Leaves 补丁改写（有则需合并冲突）。动作：`grep -rn "tickChildren" snowball-server/` 并检查 `paperServer` 展开后的源码。

---

## 13. 变更记录

| 版本 | 变更 |
|---|---|
| v1.5 | **Cerulean 改判为已移植**：原作者书面授权移植，补丁 `0147` 落地（采样节流 / 堆变空 / 堆变小 / lastSlots 初始化），三重验证通过；原「许可不兼容」结论作废，改为「真实许可为 Toni's MMC License，经授权使用」。合计：已移植 8 / 上游已覆盖 3 / 技术否决 5 / 超大工程 3 |
| v1.4 | **Cerulean 从「阻塞」改判为「技术否决」**：通过 git 历史恢复出其真实许可「Toni's MMC License」（`836ec62:LICENSE.md`，1112 字节），确认与 GPL-3.0 不兼容，且仓库元数据 `mod.license=GPLv3` 为虚假声明。原「缺 LICENSE 正文，等上游补齐」的判断作废 |
| v1.3 | ① 文档与代码对齐：新增 §4.7 最终裁决总表，19 个模组逐项定性（已移植 7 / 上游已覆盖 3 / 技术否决 5 / 阻塞 4）；② 记录 8 个补丁（`0139`–`0146`）与对应 Bridge、配置键、验证状态；③ 补全 krypton / ScalableLux / alternate-current / ModernFix 子集的否决证据（均为行号级） |
| v1.2 | ① **Ksyxis 改判为否决**：MC 1.21.9+ 上游已删除出生点常驻区块，原注入点全部消失（§4.1.1）；② **`mcm-rabbit-pathfinding-fix` 端到端移植完成**：补丁 `0139` + `PathTimeoutFix` Bridge + `path-navigation-timeout-fix` 配置，三项验证全通过（§4.1.2）；③ 新增 §5.0 补丁工作流实测踩坑（`git am` 需完整 ident、文件名须对应 Subject、`index` 需 40 位哈希、rebuild 不幂等） |
| v1.1 | ① 纠正 worldthreader 判定：Paper ≠ Folia，改为可移植并升为 T1★ 单独立项（新增 §4.6.1、里程碑 M5、风险 R7/R7b）；② 将「每个功能必须可在 `leaves.yml` 单独配置」提升为硬性要求：新增结论第 0 条、Bridge 定律 3 细化、G6 配置闸门（C1–C8）、SOP S7 强化、§7 全量重写（含实测机制与键名总表）、§8.3 配置回归、风险 R11 |
| v1.0 | 初版：19 个模组的事实盘点、Bridge+Patch 架构、五道闸门、T0–T4 分级、SOP、工艺细节、许可证、风险、里程碑 |
