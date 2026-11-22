package org.snowballmc.snowball;

import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemorySection;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.bot.BotList;
import org.snowballmc.snowball.bot.ServerBot;
import org.snowballmc.snowball.command.bot.BotCommand;
import org.snowballmc.snowball.command.leaves.LeavesCommand;
import org.snowballmc.snowball.config.GlobalConfigFiles;
import org.snowballmc.snowball.config.GlobalConfigManager;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.config.annotations.GlobalConfigCategory;
import org.snowballmc.snowball.config.annotations.TransferConfig;
import org.snowballmc.snowball.config.api.ConfigTransformer;
import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.BooleanConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.DoubleConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.EnumConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.IntConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.ListConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.LongConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.StringConfigValidator;
import org.snowballmc.snowball.profile.LeavesMinecraftSessionService;
import org.snowballmc.snowball.protocol.CarpetServerProtocol.CarpetRule;
import org.snowballmc.snowball.protocol.CarpetServerProtocol.CarpetRules;
import org.snowballmc.snowball.protocol.PcaSyncProtocol;
import org.snowballmc.snowball.protocol.bladeren.BladerenProtocol.LeavesFeature;
import org.snowballmc.snowball.protocol.bladeren.BladerenProtocol.LeavesFeatureSet;
import org.snowballmc.snowball.protocol.rei.REIServerProtocol;
import org.snowballmc.snowball.protocol.servux.logger.DataLogger;
import org.snowballmc.snowball.protocol.syncmatica.SyncmaticaProtocol;
import org.snowballmc.snowball.region.blinear.data.BufferedLinearRegionFileFlusher;
import org.snowballmc.snowball.region.blinear.enums.EnumRegionFormat;
import org.snowballmc.snowball.util.LeavesUpdateHelper;
import org.snowballmc.snowball.util.MathUtils;
import org.snowballmc.snowball.util.McTechnicalModeHelper;
import org.snowballmc.snowball.util.ServerI18nUtil;
import org.snowballmc.snowball.util.VillagerInfiniteDiscountHelper;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
public final class LeavesConfig {

    public static final String CONFIG_HEADER = "SnowBall base configuration (leaves.yml).";
    public static final int CURRENT_CONFIG_VERSION = 6;

    private static GlobalConfigFiles configFiles;

    public static void init(final @NotNull File file) {
        try {
            File leavesFile = GlobalConfigFiles.prepareLeavesFile(file);
            synchronized (GlobalConfigManager.class) {
                GlobalConfigFiles loaded = GlobalConfigFiles.load(leavesFile.toPath(), Path.of("config", GlobalConfigFiles.SNOWBALL_FILE));
                GlobalConfigManager.init(loaded);
                configFiles = loaded;
            }
            org.snowballmc.snowball.experimental.ExperimentalConfig.load(Path.of("config", org.snowballmc.snowball.experimental.ExperimentalConfig.FILE), false);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Unable to load Leaves and SnowBall configuration files", e);
        }

        LeavesCommand.INSTANCE.register();
    }

    public static void reload() {
        try {
            synchronized (GlobalConfigManager.class) {
                GlobalConfigFiles updated = GlobalConfigFiles.load(configFiles.leavesPath(), configFiles.snowballPath());
                GlobalConfigManager.reload(updated);
                configFiles = updated;
            }
            org.snowballmc.snowball.experimental.ExperimentalConfig.load(Path.of("config", org.snowballmc.snowball.experimental.ExperimentalConfig.FILE), true);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Unable to reload Leaves and SnowBall configuration files", e);
        }
    }

    public static ModifyConfig modify = new ModifyConfig();

    @GlobalConfigCategory("modify")
    public static class ModifyConfig {

        public FakeplayerConfig fakeplayer = new FakeplayerConfig();

        @GlobalConfigCategory("fakeplayer")
        public static class FakeplayerConfig {

            @TransferConfig("fakeplayer.enable")
            @GlobalConfig(value = "enable", validator = FakeplayerValidator.class, comment = {
                "是否开启假人相关功能。"
            })
            public boolean enable = true;

            private static class FakeplayerValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    if (!value.equals(old)) {
                        if (value) {
                            BotCommand.INSTANCE.register();
                        } else {
                            BotCommand.INSTANCE.unregister();
                            BotList.INSTANCE.removeAll();
                        }
                    }
                }
            }

            @TransferConfig("fakeplayer.unable-fakeplayer-names")
            @GlobalConfig(value = "unable-fakeplayer-names", comment = {
                "禁止假人使用的名称列表。"
            })
            public List<String> unableNames = List.of("player-name");

            @GlobalConfig(value = "limit", comment = {
                "指定假人的最大数量。"
            })
            public int limit = 10;

            @GlobalConfig(value = "prefix", comment = {
                "指定假人名称前缀。需要符合 Minecraft 用户名规范。"
            })
            public String prefix = "";

            @GlobalConfig(value = "suffix", comment = {
                "指定假人名称后缀。需要符合 Minecraft 用户名规范。"
            })
            public String suffix = "";

            @GlobalConfig(value = "regen-amount", validator = RegenAmountValidator.class, comment = {
                "假人每游戏刻恢复的生命值。"
            })
            public double regenAmount = 0.0;

            private static class RegenAmountValidator extends DoubleConfigValidator {
                @Override
                public void verify(Double old, Double value) throws IllegalArgumentException {
                    if (value < 0.0) {
                        throw new IllegalArgumentException("regen-amount need >= 0.0");
                    }
                }
            }

            @GlobalConfig(value = "resident-fakeplayer", comment = {
                "是否在服务器关闭时保存假人并在下次开启服务器时恢复。"
            })
            public boolean canResident = false;

            @GlobalConfig(value = "open-fakeplayer-inventory", comment = {
                "是否允许玩家按下使用键打开假人背包。"
            })
            public boolean canOpenInventory = false;

            @GlobalConfig(value = "fakeplayer-inventory-gui-buttons", snowball = true, comment = {
                "打开假人背包后，在界面中额外显示可点击的功能按钮。槽位 0 为停止假人当前动作，槽位 5 为交换主副手，",
                "槽位 8 为丢弃快捷栏当前选中的物品。关闭时这些位置只是占位符，点击无效。"
            })
            public boolean inventoryGuiButtons = false;

            @GlobalConfig(value = "use-action", validator = CanUseConfigValidator.class, comment = {
                "是否允许假人使用动作。"
            })
            public boolean canUseAction = true;

            private static class CanUseConfigValidator extends BotSubcommandValidator {
                private CanUseConfigValidator() {
                    super("use");
                }
            }

            @GlobalConfig(value = "modify-config", validator = CanModifyConfigValidator.class, comment = {
                "是否允许修改假人配置。"
            })
            public boolean canModifyConfig = false;

            private static class CanModifyConfigValidator extends BotSubcommandValidator {
                private CanModifyConfigValidator() {
                    super("config");
                }
            }

            @GlobalConfig(value = "manual-save-and-load", validator = CanManualSaveAndLoadValidator.class, comment = {
                "是否允许手动保存和加载假人"
            })
            public boolean canManualSaveAndLoad = false;

            private static class CanManualSaveAndLoadValidator extends BotSubcommandValidator {
                private CanManualSaveAndLoadValidator() {
                    super("save", "load");
                }
            }

            private static class BotSubcommandValidator extends BooleanConfigValidator {
                private final List<String> subcommands;

                private BotSubcommandValidator(String... subcommand) {
                    this.subcommands = List.of(subcommand);
                }

                @Override
                public void onApplied(Boolean old, Boolean value) {
                    if (old != null && !old.equals(value)) {
                        Bukkit.getOnlinePlayers().stream()
                            .filter(sender -> subcommands.stream().allMatch(subcommand -> BotCommand.hasPermission(sender, subcommand)))
                            .forEach(org.bukkit.entity.Player::updateCommands);
                    }
                }
            }

            @GlobalConfig(value = "cache-skin", lock = true, comment = {
                "是否缓存假人皮肤以加快假人二次创建的速度，缓存只会在重启服务器后失效。"
            })
            public boolean useSkinCache = false;

            public InGameConfig inGame = new InGameConfig();

            @GlobalConfigCategory("in-game")
            public static class InGameConfig {

                @TransferConfig("modify.fakeplayer.always-send-data")
                @GlobalConfig(value = "always-send-data", comment = {
                    "是否一直向与假人处于同一维度的玩家发送该假人的数据。这将跳过追踪距离的限制。"
                })
                public boolean canSendDataAlways = true;

                @TransferConfig("modify.fakeplayer.skip-sleep-check")
                @GlobalConfig(value = "skip-sleep-check", comment = {
                    "是否不将假人计入跳过夜晚所需睡眠的人数。"
                })
                public boolean canSkipSleep = false;

                @TransferConfig("modify.fakeplayer.spawn-phantom")
                @GlobalConfig(value = "spawn-phantom", comment = {
                    "是否允许假人生成幻翼。"
                })
                public boolean canSpawnPhantom = false;

                @TransferConfig("modify.fakeplayer.tick-type")
                @GlobalConfig(value = "tick-type", comment = {
                    "假人的默认实体刻类型。"
                })
                public ServerBot.TickType tickType = ServerBot.TickType.NETWORK;

                @GlobalConfig(value = "simulation-distance", validator = BotSimulationDistanceValidator.class, comment = {
                    "假人的模拟距离，这将影响区块更新和生物生成"
                })
                private int simulationDistance = -1;

                public int getSimulationDistance(ServerBot bot) {
                    return this.simulationDistance == -1 ? bot.getBukkitEntity().getSimulationDistance() : this.simulationDistance;
                }

                public static class BotSimulationDistanceValidator extends IntConfigValidator {
                    @Override
                    public void verify(Integer old, Integer value) throws IllegalArgumentException {
                        if ((value < 2 && value != -1) || value > 32) {
                            throw new IllegalArgumentException("simulation-distance must be a number between 2 and 32, got: " + value);
                        }
                    }
                }

                @GlobalConfig(value = "enable-locator-bar", comment = {
                    "是否将假人添加到玩家的定位栏中。"
                })
                public boolean enableLocatorBar = false;
            }
        }

        public MinecraftOLDConfig oldMC = new MinecraftOLDConfig();

        @GlobalConfigCategory("minecraft-old")
        public static class MinecraftOLDConfig {

            public BlockUpdaterConfig updater = new BlockUpdaterConfig();

            @GlobalConfigCategory("block-updater")
            public static class BlockUpdaterConfig {
                @TransferConfig("modify.instant-block-updater-reintroduced")
                @TransferConfig("modify.minecraft-old.instant-block-updater-reintroduced")
                @GlobalConfig(value = "instant-block-updater-reintroduced", lock = true, comment = {
                    "是否重新引入 1.19 版本前的瞬时方块更新机制。 TIP 有关此功能更详细的说明，请参阅 【我的世界】瞬时科技相关介绍。"
                })
                public boolean instantBlockUpdaterReintroduced = false;

                @TransferConfig("modify.minecraft-old.cce-update-suppression")
                @GlobalConfig(value = "cce-update-suppression", comment = {
                    "是否允许利用 ClassCastException 进行更新抑制。 TIP 有关此功能更详细的说明，",
                    "请参阅 关于强转抑制器的笔记。"
                })
                public boolean cceUpdateSuppression = false;

                @GlobalConfig(value = "sound-update-suppression", comment = {
                    "是否允许利用 IllegalArgumentException 进行更新抑制。 TIP 有关此功能更详细的说明，",
                    "请参阅 【MC】新的更新抑制器：声音抑制 [1.19~1.21]【Igna778】。"
                })
                public boolean soundUpdateSuppression = false;

                @TransferConfig("modify.redstone-wire-dont-connect-if-on-trapdoor")
                @TransferConfig("modify.minecraft-old.redstone-wire-dont-connect-if-on-trapdoor")
                @TransferConfig("modify.minecraft-old.block-updater.redstone-wire-dont-connect-if-on-trapdoor")
                @GlobalConfig(value = "redstone-ignore-upwards-update", comment = {
                    "是否重新引入 1.20 版本前的机制：红石粉不会连接四周开启的活板门上的红石粉。 1.20.2 版本前的机制：红石粉、",
                    "红石中继器和红石比较器收到来自下侧的状态更新时不检查附着。"
                })
                public boolean redstoneIgnoreUpwardsUpdate = false;

                @TransferConfig("modify.minecraft-old.old-block-entity-behaviour")
                @TransferConfig("modify.minecraft-old.block-updater.old-block-entity-behaviour")
                @GlobalConfig(value = "old-block-remove-behaviour", comment = {
                    "是否重新引入 1.21 版本前的方块实体相关行为。 这将允许方块被破坏后保留其方块实体，进行方块实体替换等操作。",
                    " WARNING 此功能并不完全与 1.21 前相同，事实上，开启后几乎所有方块实体都可以被保留。故此功能较为危险，请谨慎开启。",
                    " TIP 有关此功能更详细的说明，请参阅 【JKM】方块实体替换 [1.17.1]。"
                })
                public boolean oldBlockRemoveBehaviour = false;
            }

            @TransferConfig("shears-in-dispenser-can-zero-amount")
            @TransferConfig("modify.shears-in-dispenser-can-zero-amount")
            @GlobalConfig(value = "shears-in-dispenser-can-zero-amount", comment = {
                "是否重新引入 18w44a 版本前的机制：发射器可以将剪刀使用至负耐久度。"
            })
            public boolean shearsInDispenserCanZeroAmount = false;

            @SuppressWarnings("unused")
            @GlobalConfig(value = "villager-infinite-discounts", validator = VillagerInfiniteDiscountsValidator.class, comment = {
                "是否重新引入 1.20.2 版本前的机制：村民被多次感染并治愈时，所提供的折扣翻倍。"
            })
            private boolean villagerInfiniteDiscounts = false;

            private static class VillagerInfiniteDiscountsValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    VillagerInfiniteDiscountHelper.doVillagerInfiniteDiscount(value);
                }
            }

            @GlobalConfig(value = "copper-bulb-1gt-delay", comment = {
                "是否重新引入 23w46a 版本前的机制：铜灯在红石信号变化时延迟 1 游戏刻之后切换状态。"
            })
            public boolean copperBulb1gt = false;

            @GlobalConfig(value = "crafter-1gt-delay", comment = {
                "是否重新引入 23w42a 版本前的机制：合成器输出物品的频率为 1 游戏刻。"
            })
            public boolean crafter1gt = false;

            @TransferConfig("modify.zero-tick-plants")
            @GlobalConfig(value = "zero-tick-plants", comment = {
                "是否重新引入 1.5 版本前的 零刻作物催熟技术。"
            })
            public boolean zeroTickPlants = false;

            @TransferConfig("modify.minecraft-old.loot-world-random")
            @GlobalConfig(value = "rng-fishing", lock = true, validator = RNGFishingValidator.class, comment = {
                "是否重新引入 1.20 版本前的机制：利用 RNG 操作战利品表。这将允许破解钓鱼随机发生器。"
            })
            public boolean rngFishing = false;

            private static class RNGFishingValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    LeavesFeatureSet.register(LeavesFeature.of("rng_fishing", value));
                }
            }

            @GlobalConfig(value = "allow-entity-portal-with-passenger", comment = {
                "是否允许带有乘客的实体穿过传送门。关闭后，实体穿过传送门的逻辑将恢复至 1.21 版本前。"
            })
            public boolean allowEntityPortalWithPassenger = true;

            @GlobalConfig(value = "disable-gateway-portal-entity-ticking", comment = {
                "是否禁用 1.21 版本引入的机制：实体穿过折跃门时，折跃门方块可以加载区块。"
            })
            public boolean disableGatewayPortalEntityTicking = false;

            @GlobalConfig(value = "disable-LivingEntity-ai-step-alive-check", comment = {
                "是否禁用 LivingEntity 中 Aistep 的存活检查。 TIP 有关此功能更详细的说明，",
                "请参阅 [MC]鹦鹉复制！活体克隆技术大突破！。"
            })
            public boolean disableLivingEntityAiStepAliveCheck = false;

            @GlobalConfig(value = "spawn-invulnerable-time", comment = {
                "是否重新引入 1.21.4 版本前的机制：允许玩家在进入游戏后的几秒内免疫伤害。"
            })
            public boolean spawnInvulnerableTime = false;

            @GlobalConfig(value = "old-hopper-suck-in-behavior", comment = {
                "是否重新引入 1.20.5 版本前的机制：漏斗上方有碰撞箱完整的方块时，仍然会检测物品实体。",
                " WARNING 开启后可能会降低性能。"
            })
            public boolean oldHopperSuckInBehavior = false;

            @GlobalConfig(value = "old-zombie-piglin-drop", comment = {
                "是否重新引入 1.21.5 版本前的机制：处于发怒状态的僵尸猪灵被任何因素杀死都会掉落经验和稀有掉落物。"
            })
            public boolean oldZombiePiglinDrop = false;

            @TransferConfig(value = "modify.minecraft-old.revert-raid-changes", transformer = RaidConfigTransformer.class)
            @GlobalConfig(value = "old-raid-behavior", comment = {
                "是否重新引入 1.21 版本前的袭击机制。这将使大部分袭击塔恢复运作。"
            })
            public boolean oldRaidBehavior = false;

            public static class RaidConfigTransformer implements ConfigTransformer<MemorySection, Boolean> {
                @Override
                public Boolean transform(@NotNull MemorySection raidConfig) {
                    return raidConfig.getBoolean("allow-bad-omen-trigger-raid")
                        || raidConfig.getBoolean("give-bad-omen-when-kill-patrol-leader")
                        || raidConfig.getBoolean("skip-height-check")
                        || raidConfig.getBoolean("use-old-find-spawn-position");
                }
            }

            @GlobalConfig(value = "old-zombie-reinforcement", comment = {
                "是否重新引入 1.21.2 版本前的机制：当僵尸村民、尸壳、溺尸或僵尸猪灵被伤害时，它们生成普通僵尸增援。"
            })
            public boolean oldZombieReinforcement = false;

            @GlobalConfig(value = "allow-anvil-destroy-item-entities", comment = {
                "是否重新引入 1.20 版本前的机制：下落的铁砧能摧毁地上的物品实体。"
            })
            public boolean allowAnvilDestroyItemEntities = false;

            public TripwireConfig tripwire = new TripwireConfig();

            @GlobalConfigCategory("tripwire-and-hook-behavior")
            public static class TripwireConfig {
                @TransferConfig("modify.minecraft-old.string-tripwire-hook-duplicate")
                @GlobalConfig(value = "string-tripwire-hook-duplicate", comment = {
                    "是否重新引入 1.21.2 版本前的常亮绊线和绊线复制技术。 TIP 有关此功能更详细的说明，",
                    "请参阅 这玩意儿啾竟怎么刷的？刷线机原理探讨 - 含手动刷线机和【百万】无人刷线机。"
                })
                public boolean stringTripwireHookDuplicate = false;

                @GlobalConfig(value = "tripwire-behavior", comment = {
                    "指定末地黑曜石平台与常亮绊线之间的互动行为。 VANILLA_21 使用 1.21 版本后的机制：常亮绊线无法阻止平台生成，",
                    "平台会使被其替代的方块掉落。 VANILLA_20 使用 1.21 版本前的机制：常亮绊线可以阻止平台生成，",
                    "被平台替代的方块不会掉落。 MIXED 使用复合机制：常亮绊线可以阻止平台生成，平台会使被其替代的方块掉落。",
                    " TIP 有关此功能更详细的说明，请参阅 【MC】1.13-1.19.3+使用常亮绊线删除黑曜石平台【enbyd】。"
                })
                public TripwireBehavior tripwireBehavior = TripwireBehavior.VANILLA_21;

                public enum TripwireBehavior {
                    VANILLA_20, VANILLA_21, MIXED
                }
            }

            @GlobalConfig(value = "void-trade", validator = VoidTradeValidator.class, comment = {
                "是否重新引入 1.21.4 版本前的机制：村民离开交互范围时，村民交易窗口不关闭。"
            })
            public boolean voidTrade = false;

            private static class VoidTradeValidator extends BooleanConfigValidator {
                @Override
                public void verify(Boolean old, Boolean value) throws IllegalArgumentException {
                    if (!value && old != null && LeavesConfig.modify.forceVoidTrade) {
                        throw new IllegalArgumentException("force-void-trade is enable, void-trade always need true");
                    }
                }
            }

            @GlobalConfig(value = "disable-item-damage-check", comment = {
                "禁用 物品修复 的耐久度检查。"
            })
            public boolean disableItemDamageCheck = false;

            @GlobalConfig(value = "old-throwable-projectile-tick-order", comment = {
                "是否恢复 1.21.4 前的投掷物残留刻复制行为。 TIP 有关残留刻的更详细的说明，请参阅 【MC】这也能复制？！"
            })
            public boolean oldThrowableProjectileTickOrder = false;

            @GlobalConfig(value = "keep-leash-connect-when-use-firework", comment = {
                "拴绳不会在使用烟花火箭加速时断开。"
            })
            public boolean keepLeashConnectWhenUseFirework = false;

            @GlobalConfig(value = "tnt-wet-explosion-no-item-damage", comment = {
                "是否恢复 1.21.2-1.21.5 中 TNT 接触到水但爆炸中心不处于水中时，可以破坏方块但不会炸毁掉落物实体的特性。"
            })
            public boolean tntWetExplosionNoItemDamage = false;

            @GlobalConfig(value = "old-projectile-explosion-behavior", comment = {
                "是否恢复 1.21.4 对箭矢蓄能的修复。"
            })
            public boolean oldProjectileExplosionBehavior = false;

            @GlobalConfig(value = "ender-dragon-part-can-use-end-portal", comment = {
                "是否恢复 1.21 前末影龙部件可穿过末地门的特性。"
            })
            public boolean enderDragonPartCanUseEndPortal = false;

            @GlobalConfig(value = "old-minecart-motion-behavior", comment = {
                "还原旧版矿车的运动计算方式（加速度与摩擦系数），用于复现旧版本矿车行为。"
            })
            public boolean oldMinecartMotionBehavior = false;

            @GlobalConfig(value = "allow-inf-nan-motion-values", comment = {
                "允许实体的 motion 被设为无限大或 NaN。原版会拒绝这类值，开启后不再过滤，供特殊装置使用。"
            })
            public boolean allowInfNanMotionValues = true;
        }

        public ElytraAeronauticsConfig elytraAeronautics = new ElytraAeronauticsConfig();

        @GlobalConfigCategory("elytra-aeronautics")
        public static class ElytraAeronauticsConfig {
            @GlobalConfig(value = "no-chunk-load", comment = {
                "是否在玩家使用鞘翅以指定的高度与速度飞行时不加载区块。"
            })
            public boolean enableNoChunkLoad = false;

            @GlobalConfig(value = "no-chunk-height", comment = {
                "玩家进入不加载区块状态需要的飞行高度。"
            })
            public double noChunkHeight = 500.0D;

            @GlobalConfig(value = "no-chunk-speed", comment = {
                "玩家进入不加载区块状态需要的飞行速度。"
            })
            public double noChunkSpeed = -1.0D;

            @GlobalConfig(value = "message", comment = {
                "是否在玩家进入不加载区块状态时显示提示。"
            })
            public boolean doSendMessages = true;

            @GlobalConfig(value = "message-start", comment = {
                "在玩家进入不加载区块状态时显示的提示。"
            })
            public String startMessage = "Flight enter cruise mode";

            @GlobalConfig(value = "message-end", comment = {
                "在玩家脱离不加载区块状态时显示的提示。"
            })
            public String endMessage = "Flight exit cruise mode";
        }

        @TransferConfig("redstone-shears-wrench")
        @GlobalConfig(value = "redstone-shears-wrench", comment = {
            "是否允许玩家使用剪刀调整部分红石元件方块朝向，并且不会产生更新。"
        })
        public boolean redstoneShearsWrench = false;

        @TransferConfig("budding-amethyst-can-push-by-piston")
        @TransferConfig("modify.budding-amethyst-can-push-by-piston")
        @GlobalConfig(value = "movable-budding-amethyst", validator = MovableBuddingAmethystValidator.class, comment = {
            "是否允许活塞推动紫水晶母岩。"
        })
        public boolean movableBuddingAmethyst = false;

        private static class MovableBuddingAmethystValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                CarpetRules.register(CarpetRule.of("carpet", "movableAmethyst", value));
            }
        }

        @TransferConfig("spectator-dont-get-advancement")
        @GlobalConfig(value = "spectator-dont-get-advancement", comment = {
            "是否阻止旁观者模式下的玩家获取进度。"
        })
        public boolean spectatorDontGetAdvancement = false;

        @TransferConfig("stick-change-armorstand-arm-status")
        @GlobalConfig(value = "stick-change-armorstand-arm-status", comment = {
            "是否允许玩家在潜行状态下手持木棍对盔甲架按下使用键切换盔甲架手臂的显示状态。"
        })
        public boolean stickChangeArmorStandArmStatus = true;

        @TransferConfig("snowball-and-egg-can-knockback-player")
        @GlobalConfig(value = "snowball-and-egg-can-knockback-player", comment = {
            "是否允许鸡蛋和雪球击退玩家。"
        })
        public boolean snowballAndEggCanKnockback = true;

        @GlobalConfig(value = "flatten-triangular-distribution", comment = {
            "是否将随机发生器改为平均分布。这将使边缘事件更容易发生。"
        })
        public boolean flattenTriangularDistribution = false;

        @GlobalConfig(value = "player-operation-limiter", comment = {
            "是否限制玩家的动作执行速度。开启后每个游戏刻只能破坏 1 个方块或放置 2 个方块。"
        })
        public boolean playerOperationLimiter = false;

        @GlobalConfig(value = "renewable-elytra", validator = RenewableElytraValidator.class, comment = {
            "当潜影贝击杀幻翼时掉落破损的鞘翅的概率。设置为 -1.0 以禁用此功能。"
        })
        public double renewableElytra = -1.0F;

        private static class RenewableElytraValidator extends DoubleConfigValidator {
            @Override
            public void verify(Double old, Double value) throws IllegalArgumentException {
                if (value > 1.0) {
                    throw new IllegalArgumentException("renewable-elytra need <= 1.0f");
                }
            }
        }

        public ShulkerBoxConfig shulkerBox = new ShulkerBoxConfig();

        @GlobalConfigCategory("shulker-box")
        public static class ShulkerBoxConfig {

            @TransferConfig("modify.stackable-shulker-boxes")
            @GlobalConfig(value = "stackable-shulker-boxes", validator = StackableShulkerValidator.class, comment = {
                "设置潜影盒的最大堆叠数量。 false：不堆叠 2-99：指定最大堆叠数量 TIP 此功能不会影响物品本身的数据。",
                "堆叠的潜影盒将在漏斗中分离，理论上不会影响红石机器正常工作。"
            })
            public int stackableShulkerBoxes = 1;

            private static class StackableShulkerValidator implements ConfigValidator<Integer> {

                @Override
                public Integer loadConvert(Object value) throws IllegalArgumentException {
                    return switch (value) {
                        case String stringValue -> stringValue.equals("true") ? 2
                            : !MathUtils.isNumeric(stringValue) ? 1
                            : Integer.parseInt(stringValue);
                        case Integer integerValue -> integerValue;
                        case Boolean boolValue -> boolValue ? 2 : 1;
                        case null, default -> throw new IllegalArgumentException("stackable-shulker-boxes need string or integer or boolean");
                    };
                }

                @Override
                public Object saveConvert(Integer value) {
                    return value == 1 ? false
                        : value == 2 ? true
                        : value;
                }

                @Override
                public Integer stringConvert(String value) throws IllegalArgumentException {
                    return loadConvert(value);
                }

                @Override
                public void verify(Integer old, Integer value) throws IllegalArgumentException {
                    if (value < 1 || value > 99) {
                        throw new IllegalArgumentException("stackable-shulker-boxes need >= 1 and <= 99");
                    }
                }

                @Override
                public List<String> valueSuggest() {
                    return List.of("true", "false", "64", "32");
                }
            }

            @GlobalConfig(value = "same-nbt-stackable", comment = {
                "使相同数据 (NBT) 的潜影盒能进行堆叠，而不是仅堆叠空盒子。"
            })
            public boolean sameNbtStackable = false;
        }

        @GlobalConfig(value = "allow-grindstone-overstacking", snowball = true, comment = {
            "允许砂轮处理与产出超过常规堆叠数量的物品。开启后砂轮要求输入数量不超过 1 的限制被放开，合并时按两边实际数量相加，",
            "上限取该物品的堆叠上限（包含 shulker-box、",
            "curse-enchanted-book-stack-size 等超堆叠设定）。关闭时行为与原版完全一致。"
        })
        public boolean allowGrindstoneOverstacking = false;

        @GlobalConfig(value = "curse-enchanted-book-stack-size", snowball = true, validator = CurseEnchantedBookValidator.class, comment = {
            "带诅咒附魔的附魔书的最大堆叠数量。设为 1 或 false 表示不启用。仅当物品为附魔书且至少带一个诅咒附魔时生效；",
            "附魔与其它组件完全相同的两本才会叠在一起。取值范围 1-99。"
        })
        public int curseEnchantedBookStackSize = 1;

        private static class CurseEnchantedBookValidator implements ConfigValidator<Integer> {

            @Override
            public Integer loadConvert(Object value) throws IllegalArgumentException {
                return switch (value) {
                    case String stringValue -> !MathUtils.isNumeric(stringValue) ? 1 : Integer.parseInt(stringValue);
                    case Integer integerValue -> integerValue;
                    case Boolean boolValue -> boolValue ? 64 : 1;
                    case null, default -> throw new IllegalArgumentException("curse-enchanted-book-stack-size need string or integer or boolean");
                };
            }

            @Override
            public Object saveConvert(Integer value) {
                return value == 1 ? false : value;
            }

            @Override
            public Integer stringConvert(String value) throws IllegalArgumentException {
                return loadConvert(value);
            }

            @Override
            public void verify(Integer old, Integer value) throws IllegalArgumentException {
                if (value < 1 || value > 99) {
                    throw new IllegalArgumentException("curse-enchanted-book-stack-size need >= 1 and <= 99");
                }
            }

            @Override
            public List<String> valueSuggest() {
                return List.of("false", "64", "16");
            }
        }

        @GlobalConfig(value = "force-void-trade", validator = ForceVoidTradeValidator.class, comment = {
            "是否允许玩家强制进行虚空交易。开启后，将重新引入 1.21.4 版本前的机制：村民离开交互范围时，村民交易窗口不关闭。",
            "同时玩家在通过折跃门后，无需等待区块卸载，即可进行虚空交易。"
        })
        public boolean forceVoidTrade = false;

        private static class ForceVoidTradeValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                if (value) {
                    LeavesConfig.modify.oldMC.voidTrade = true;
                }
            }
        }

        @GlobalConfig(value = "mc-technical-survival-mode", lock = true, validator = McTechnicalModeValidator.class, comment = {
            "是否开启生电模式。 开启后部分上游修改将被禁用，以下配置项将被强制覆盖为下方的值并忽略原始配置文件中的值。",
            " paper-global.yml： yamlunsupported-settings: allow-piston-duplication: true allow-headless-pistons: true allow-permanent-block-break-exploits: true allow-unsafe-end-portal-teleportation: true skip-tripwire-hook-placement-validation: true packet-limiter: all-packets: max-packet-rate: 5000.0 overrides: {} item-validation: resolve-selectors-in-books: true scoreboards: save-empty-scoreboard-teams: true paper-world.yml（对所有维度）： yamlcollisions: allow-player-cramming-damage: true entities: spawning: count-all-mobs-for-spawning: tru behavior: stuck-entity-poi-retry-delay: disabled unsupported-settings: fix-invulnerable-end-crystal-exploit: false hopper: cooldown-when-full: false spigot.yml（world-settings下，",
            "对所有维度）： yamlmax-tnt-per-tick: 2000"
        })
        public boolean mcTechnicalMode = true;

        private static class McTechnicalModeValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                if (value) {
                    McTechnicalModeHelper.doMcTechnicalMode();
                }
            }
        }

        @GlobalConfig(value = "return-nether-portal-fix", comment = {
            "是否修复玩家从下界返回主世界时传送门位置异常的问题。",
            "其功能类似于 NetherPortalFix WARNING 这可能会导致原有的下界传送门链接不可用。"
        })
        public boolean netherPortalFix = false;

        @GlobalConfig(value = "use-vanilla-random", lock = true, validator = UseVanillaRandomValidator.class, comment = {
            "是否使用原版随机数。开启后将略微降低性能，但允许玩家破解随机发生器。"
        })
        public boolean useVanillaRandom = false;

        private static class UseVanillaRandomValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                LeavesFeatureSet.register(LeavesFeature.of("use_vanilla_random", value));
            }
        }

        @GlobalConfig(value = "fix-update-suppression-crash", comment = {
            "是否阻止由于更新抑制导致的崩溃。"
        })
        public boolean updateSuppressionCrashFix = true;

        @GlobalConfig(value = "fix-stuck-zombified-piglin-anger-target", comment = {
            "修复僵尸猪灵的愤怒目标卡死、无法正常清除的问题。"
        })
        public boolean fixStuckZombifiedPiglinAngerTarget = false;

        @GlobalConfig(value = "bedrock-break-list", lock = true, comment = {
            "是否开启破基岩榜。此功能的行为与 FZBBL 一致。"
        })
        public boolean bedrockBreakList = false;

        @GlobalConfig(value = "disable-distance-check-for-use-item", validator = DisableDistanceCheckForUseItemValidator.class, comment = {
            "是否禁用使用物品时的距离检测。"
        })
        public boolean disableDistanceCheckForUseItem = false;

        private static class DisableDistanceCheckForUseItemValidator extends BooleanConfigValidator {
            @Override
            public void verify(Boolean old, Boolean value) throws IllegalArgumentException {
                if (!value && old != null && LeavesConfig.protocol.alternativeBlockPlacement != ProtocolConfig.AlternativePlaceType.NONE) {
                    throw new IllegalArgumentException("alternative-block-placement is enable, disable-distance-check-for-use-item always need true");
                }
            }
        }

        @GlobalConfig(value = "no-feather-falling-trample", comment = {
            "是否允许玩家在穿戴拥有摔落缓冲魔咒的靴子时掉落在耕地上不会导致其退化为泥土。"
        })
        public boolean noFeatherFallingTrample = false;

        @GlobalConfig(value = "shared-villager-discounts", comment = {
            "是否允许所有玩家共享同一村民治愈折扣。"
        })
        public boolean sharedVillagerDiscounts = false;

        @GlobalConfig(value = "disable-check-out-of-order-command", comment = {
            "是否禁用聊天信息顺序检查。此问题于使用 Litematica 的一键放置功能时常会发生。"
        })
        public boolean disableCheckOutOfOrderCommand = false;

        @GlobalConfig(value = "despawn-enderman-with-block", comment = {
            "是否允许手持方块的末影人自然消失。"
        })
        public boolean despawnEndermanWithBlock = false;

        @GlobalConfig(value = "creative-no-clip", validator = CreativeNoClipValidator.class, comment = {
            "是否允许玩家在创造模式下飞行时禁用碰撞箱。",
            " TIP 此功能需要启用 settings.protocol.leaves-carpet-support。",
            " 且需要客户端安装 Carpet Mod。"
        })
        public boolean creativeNoClip = false;

        private static class CreativeNoClipValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                CarpetRules.register(CarpetRule.of("carpet", "creativeNoClip", value));
            }
        }

        @GlobalConfig(value = "shave-snow-layers", comment = {
            "是否允许对雪使用锹时减少雪层并掉落雪球。 TIP 尽管八层雪与雪块十分相似，但此功能仅对八层雪生效。"
        })
        public boolean shaveSnowLayers = true;

        @GlobalConfig(value = "disable-packet-limit", comment = {
            "是否禁用发包限制。"
        })
        public boolean disablePacketLimit = false;

        @GlobalConfig(value = "lava-riptide", validator = LavaRiptideValidator.class, comment = {
            "是否允许玩家在熔岩中使用激流。 TIP 此功能需要启用 settings.protocol.bladeren.protocol。",
            " 且需要客户端安装 Bladeren Mod。"
        })
        public boolean lavaRiptide = false;

        private static class LavaRiptideValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                LeavesFeatureSet.register(LeavesFeature.of("lava_riptide", value));
            }
        }

        @GlobalConfig(value = "no-block-update-command", validator = NoBlockUpdateValidator.class, comment = {
            "是否启用 /leaves blockupdate 命令，此命令让玩家可以控制服务器内全局方块更新是否发生。"
        })
        public boolean noBlockUpdateCommand = false;

        private static class NoBlockUpdateValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                if (old != null && !old.equals(value)) {
                    Bukkit.getOnlinePlayers().stream()
                        .filter(sender -> LeavesCommand.hasPermission(sender, "blockupdate"))
                        .forEach(org.bukkit.entity.Player::updateCommands);
                }
            }
        }

        @GlobalConfig(value = "no-tnt-place-update", comment = {
            "是否在放置 TNT 时不进行方块更新。"
        })
        public boolean noTNTPlaceUpdate = false;

        @GlobalConfig(value = "container-passthrough", comment = {
            "是否允许玩家在使用 告示牌/物品展示框 时可以直接打开后方的容器。启用后将会改变原有操作逻辑。 对于告示牌，",
            "只有在手中无物品且潜行时才可编辑文字。 对于物品展示框，只有在潜行时才可正常旋转物品。"
        })
        public boolean containerPassthrough = false;

        @GlobalConfig(value = "avoid-anvil-too-expensive", validator = AnvilNotExpensiveValidator.class, comment = {
            "是否允许铁砧修复花费可以高于 40 并且不会因为过于昂贵无法使用。 TIP 要正确显示真实的经验消耗等级，",
            "需要启用 settings.protocol.leaves-carpet-support，",
            " 且需要客户端安装 Plusls Carpet Addition。"
        })
        public boolean avoidAnvilTooExpensive = false;

        private static class AnvilNotExpensiveValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                CarpetRules.register(CarpetRule.of("pca", "avoidAnvilTooExpensive", value));
            }
        }

        @GlobalConfig(value = "bow-infinity-fix", comment = {
            "是否允许生存模式下的玩家在物品栏中无箭矢时仍可使用拥有无限魔咒的弓。"
        })
        public boolean bowInfinityFix = false;

        public HopperCounterConfig hopperCounter = new HopperCounterConfig();

        @GlobalConfigCategory("hopper-counter")
        public static class HopperCounterConfig {
            @TransferConfig(value = "modify.hopper-counter", transformer = HopperCounterTransfer.class)
            @TransferConfig("modify.counter.enable")
            @GlobalConfig(value = "enable", validator = HopperCounterValidator.class, comment = {
                "是否启用漏斗计数器。"
            })
            public boolean enable = false;

            private static class HopperCounterValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    if (old != null && !old.equals(value)) {
                        Bukkit.getOnlinePlayers().stream()
                            .filter(sender -> LeavesCommand.hasPermission(sender, "counter"))
                            .forEach(org.bukkit.entity.Player::updateCommands);
                    }
                }
            }

            @TransferConfig("modify.counter.unlimited-speed")
            @GlobalConfig(value = "unlimited-speed", comment = {
                "是否解除漏斗计数器的速度限制，这将提升测试高效率机器的准确性。"
            })
            public boolean unlimitedSpeed = false;

            private static class HopperCounterTransfer implements ConfigTransformer<Object, Boolean> {
                @Override
                public Boolean transform(Object object) throws StopTransformException {
                    if (object instanceof Boolean) {
                        return (Boolean) object;
                    } else {
                        throw new StopTransformException();
                    }
                }
            }
        }

        @GlobalConfig(value = "spider-jockeys-drop-gapples", validator = JockeysDropGAppleValidator.class, comment = {
            "当蜘蛛骑士死亡时掉落附魔金苹果的概率。设置为 -1.0 以禁用此功能。"
        })
        public double spiderJockeysDropGapples = -1.0;

        private static class JockeysDropGAppleValidator extends DoubleConfigValidator {
            @Override
            public void verify(Double old, Double value) throws IllegalArgumentException {
                if (value > 1.0) {
                    throw new IllegalArgumentException("spider-jockeys-drop-gapples need <= 1.0f");
                }
            }
        }

        @GlobalConfig(value = "renewable-deepslate", comment = {
            "是否使得在主世界 Y &lt; 0 的区域，熔岩流在水平方向或上方触碰水源或水流时将会变为深板岩圆石而不是普通圆石，",
            "在非水平方向上将变成深板岩而不是石头。"
        })
        public boolean renewableDeepslate = false;

        @GlobalConfig(value = "renewable-sponges", comment = {
            "是否允许守卫者在被闪电束击中后转化为远古守卫者。"
        })
        public boolean renewableSponges = false;

        @GlobalConfig(value = "renewable-coral", validator = RenewableCoralValidator.class, comment = {
            "是否允许使用骨粉催熟珊瑚类植物。 FALSE 不允许使用骨粉催熟珊瑚类植物。 TRUE 允许使用骨粉催熟珊瑚。",
            " EXPANDED 允许使用骨粉催熟珊瑚和珊瑚扇。"
        })
        public RenewableCoralType renewableCoral = RenewableCoralType.FALSE;

        public enum RenewableCoralType {
            FALSE, TRUE, EXPANDED
        }

        private static class RenewableCoralValidator extends EnumConfigValidator<RenewableCoralType> {
            @Override
            public void onApplied(RenewableCoralType old, RenewableCoralType value) {
                CarpetRules.register(CarpetRule.of("carpet", "renewableCoral", value));
            }
        }

        @GlobalConfig(value = "disable-vault-blacklist", comment = {
            "是否禁用宝库黑名单系统。这将使得玩家可以无限次打开同一个宝库。"
        })
        public boolean disableVaultBlacklist = false;

        @SuppressWarnings("unused")
        @GlobalConfig(value = "exp-orb-absorb-mode", validator = ExpOrbModeValidator.class, comment = {
            "是否允许玩家更快地吸取经验球。 FAST 允许所有模式下的玩家快速吸取经验球。",
            " FAST_CREATIVE 仅允许创造模式玩家快速吸取经验球。 VANILLA 使用原版吸取速度，即玩家无法快速吸取经验球。"
        })
        private ExpOrbAbsorbMode expOrbAbsorbMode = ExpOrbAbsorbMode.VANILLA;

        public Predicate<ServerPlayer> fastAbsorbPredicate = player -> false;

        public enum ExpOrbAbsorbMode {
            VANILLA, FAST, FAST_CREATIVE
        }

        private static class ExpOrbModeValidator extends EnumConfigValidator<ExpOrbAbsorbMode> {
            @Override
            public void onApplied(ExpOrbAbsorbMode old, ExpOrbAbsorbMode value) {
                LeavesConfig.modify.fastAbsorbPredicate = switch (value) {
                    case FAST -> player -> true;
                    case VANILLA -> player -> false;
                    case FAST_CREATIVE -> Player::hasInfiniteMaterials;
                };
            }
        }

        @GlobalConfig(value = "follow-tick-sequence-merge", comment = {
            "让相邻的 tick 序列合并执行以减少调度开销。与原版的 tick 执行顺序可能存在细微差异。"
        })
        public boolean followTickSequenceMerge = false;

        /**
         * 大木桶：单个木桶直接是 6 排（54 格），不需要两个木桶背靠背。
         *
         * <p>容器大小在木桶实体创建时确定，漏斗与比较器读取的就是这个容器本身，
         * 因此不需要任何额外适配。</p>
         *
         * <p>注意：容器大小决定存档槽位数。开启后若有物品存在第 27 格之后，
         * 关闭此配置再加载世界时这些物品会被丢弃。</p>
         *
         * <p>移植自 Leavesaddmore 的 Large barrel（原作者 silentqaq，经授权）。</p>
         */
        @GlobalConfig(value = "large-barrel", snowball = true, comment = {
            "单个木桶直接拥有 6 行（54 格）容量，不需要两个木桶背靠背摆放。容器大小在木桶方块实体创建时确定，",
            "漏斗与比较器读取的就是这个容器本身。注意：容器大小决定存档槽位数，开启后若物品存在于第 27 格之后，",
            "关闭此配置再加载世界时这些物品会被丢弃。"
        })
        public boolean largeBarrel = false;

        /**
         * 大末影箱：每个玩家的末影箱直接是 6 排（54 格）。
         *
         * <p>注意：末影箱内容存在玩家数据里。开启后存到第 27 格之后的物品，
         * 在关闭此配置后该玩家再次加载时会丢失，属于不可逆的存档变更。</p>
         */
        @GlobalConfig(value = "large-ender-chest", snowball = true, comment = {
            "每个玩家的末影箱拥有 6 行（54 格）容量。注意：末影箱内容保存在玩家数据中，开启后存到第 27 格之后的物品，",
            "在关闭此配置后该玩家再次加载时会丢失，属于不可逆的存档变更。"
        })
        public boolean largeEnderChest = false;
    }

    public static final AsyncConfig async = new AsyncConfig();

    @GlobalConfigCategory("async")
    public static class AsyncConfig {
        public final AsyncMobSpawningConfig asyncMobSpawning = new AsyncMobSpawningConfig();
        public final AsyncPathfindingConfig asyncPathfinding = new AsyncPathfindingConfig();

        public enum PathfindTaskRejectPolicy {
            AUTO, FLUSH_ALL, CALLER_RUNS
        }

        @GlobalConfigCategory("async-mob-spawning")
        public static class AsyncMobSpawningConfig {
            @GlobalConfig(value = "enabled", snowball = true, lock = true, comment = {
                "实验性：将支持的自然刷怪计数移到后台；实体生成和插件事件仍在主线程。",
                "要求对应世界的 Paper per-player-mob-spawns 已开启；不支持的场景回退同步。默认关闭，需重启。"
            })
            public boolean enabled = false;
        }

        @GlobalConfigCategory("async-pathfinding")
        public static class AsyncPathfindingConfig {
            @GlobalConfig(value = "enabled", snowball = true, lock = true, comment = {
                "实验性：对支持的地面寻路请求使用隔离的后台搜索。",
                "未覆盖的实体、地形或已失效请求沿用同步寻路。默认关闭，需重启。"
            })
            public boolean enabled = false;

            @GlobalConfig(value = "max-threads", snowball = true, lock = true, validator = AsyncPathThreadsValidator.class, comment = {
                "最大工作线程数（0–256）；0 按可用 CPU 核数除以 4 取整，至少 1。需重启。"
            })
            public int maxThreads = 0;

            @GlobalConfig(value = "keepalive", snowball = true, lock = true, validator = AsyncPathKeepaliveValidator.class, comment = {
                "额外工作线程空闲后回收的时间（秒，0–86400）。默认 60，需重启。"
            })
            public int keepalive = 60;

            @GlobalConfig(value = "queue-size", snowball = true, lock = true, validator = AsyncPathQueueValidator.class, comment = {
                "最多等待的寻路任务数（0–65536）；0 自动按最大线程数乘 256。需重启。"
            })
            public int queueSize = 0;

            @GlobalConfig(value = "reject-policy", snowball = true, lock = true, comment = {
                "队列满时的处理策略：CALLER_RUNS 只在主线程运行新任务；FLUSH_ALL 取出队列中的任务并在主线程处理。",
                "AUTO：至少 12 个可用处理器且队列容量小于 512 时用 FLUSH_ALL，否则用 CALLER_RUNS。需重启。"
            })
            public PathfindTaskRejectPolicy rejectPolicy = PathfindTaskRejectPolicy.AUTO;

            public int resolvedMaxThreads() {
                return maxThreads == 0 ? Math.min(256, Math.max(1, Runtime.getRuntime().availableProcessors() / 4)) : maxThreads;
            }

            public int resolvedQueueSize() {
                return queueSize == 0 ? resolvedMaxThreads() * 256 : queueSize;
            }

            public PathfindTaskRejectPolicy resolvedRejectPolicy() {
                if (rejectPolicy != PathfindTaskRejectPolicy.AUTO) {
                    return rejectPolicy;
                }
                return Runtime.getRuntime().availableProcessors() >= 12 && resolvedQueueSize() < 512
                    ? PathfindTaskRejectPolicy.FLUSH_ALL : PathfindTaskRejectPolicy.CALLER_RUNS;
            }
        }

        private static class AsyncPathThreadsValidator extends IntConfigValidator {
            @Override
            public void verify(Integer old, Integer value) {
                verifyAsyncRange("max-threads", value, 256);
            }
        }

        private static class AsyncPathKeepaliveValidator extends IntConfigValidator {
            @Override
            public void verify(Integer old, Integer value) {
                verifyAsyncRange("keepalive", value, 86400);
            }
        }

        private static class AsyncPathQueueValidator extends IntConfigValidator {
            @Override
            public void verify(Integer old, Integer value) {
                verifyAsyncRange("queue-size", value, 65536);
            }
        }

        private static void verifyAsyncRange(String name, int value, int max) {
            if (value < 0 || value > max) {
                throw new IllegalArgumentException("async-pathfinding." + name + " must be between 0 and " + max);
            }
        }
    }

    public static PerformanceConfig performance = new PerformanceConfig();

    @GlobalConfigCategory("performance")
    public static class PerformanceConfig {
        @GlobalConfig(value = "skip-generic-game-event-without-listeners", snowball = true, comment = {
            "没有 Bukkit GenericGameEvent 监听器时跳过事件对象构造。原版振动、游戏事件传播不变，可热重载。"
        })
        public boolean skipGenericGameEventWithoutListeners = true;

        @GlobalConfig(value = "skip-block-physics-event-without-listeners", snowball = true, comment = {
            "仅在没有 BlockPhysicsEvent 监听器时跳过邻居更新入口的事件对象构造。方块更新照常运行，可热重载。"
        })
        public boolean skipBlockPhysicsEventWithoutListeners = true;

        @GlobalConfig(value = "optimize-chunk-packet-construction", snowball = true, comment = {
            "减少区块包构造时的临时对象和遍历成本。保留高度图拷贝、反矿透与方块实体补包，可热重载。"
        })
        public boolean optimizeChunkPacketConstruction = true;

        @GlobalConfig(value = "skip-inventory-change-without-listeners", snowball = true, comment = {
            "玩家没有待处理的背包变更进度监听时，跳过槽位扫描。不改变 Cerulean 的行为或已有进度判定，可热重载。"
        })
        public boolean skipInventoryChangeWithoutListeners = true;

        @GlobalConfig(value = "optimize-mob-spawning", snowball = true, comment = {
            "实验性：使用同步自然刷怪查询的低开销路径。与 async-mob-spawning 独立，默认关闭，可热重载。"
        })
        public boolean optimizeMobSpawning = false;

        @GlobalConfig(value = "biome-spy", snowball = true, lock = true, comment = {
            "实验性：复用多噪声生物群系查找中不随高度变化的气候采样，不改变搜索半径或顺序。默认关闭，修改后需重启。"
        })
        public boolean biomeSpy = false;

        public PerformanceRemoveConfig remove = new PerformanceRemoveConfig();

        @GlobalConfigCategory("remove")
        public static class PerformanceRemoveConfig {
            @GlobalConfig(value = "tick-guard-lambda", comment = {
                "是否在运算实体列表时不使用 Lambda。 TIP 这个功能产生的优化微乎其微。"
            })
            public boolean tickGuardLambda = true;

            @GlobalConfig(value = "damage-lambda", comment = {
                "是否在计算伤害时不使用 Lambda。 TIP 这个功能产生的优化微乎其微。"
            })
            public boolean damageLambda = true;
        }

        @GlobalConfig(value = "optimized-dragon-respawn", comment = {
            "是否优化末影龙重生，其功能等同于 Optimized Dragon Respawn"
        })
        public boolean optimizedDragonRespawn = false;

        @GlobalConfig(value = "dont-send-useless-entity-packets", comment = {
            "是否减少发送无用实体包。 WARNING 这可能会破坏利用这些无用实体包的模组。"
        })
        public boolean dontSendUselessEntityPackets = true;

        @GlobalConfig(value = "enable-suffocation-optimization", comment = {
            "是否将窒息检测频率提高为 20 游戏刻。玩家很难（但不是不可能）注意到这一变化。"
        })
        public boolean enableSuffocationOptimization = true;

        @GlobalConfig(value = "inactive-goal-selector-disable", comment = {
            "是否限制非活跃实体的目标查找器的运行。 启用此项目后，",
            "非活跃实体的目标查找器 (Goal Selector) 将会每 20 游戏刻运行一次，而不是每刻运行。"
        })
        public boolean throttleInactiveGoalSelectorTick = false;

        @GlobalConfig(value = "reduce-entity-allocations", comment = {
            "是否缓存实体的属性实例 (Attribute Instance)，这将略微减少内存分配。"
        })
        public boolean reduceEntityAllocations = true;

        @GlobalConfig(value = "cache-climb-check", comment = {
            "是否缓存攀爬检查结果。"
        })
        public boolean cacheClimbCheck = true;

        /**
         * 修复生物移动速度为 0 时路径节点永不过期的缺陷。
         *
         * <p>原版 {@code PathNavigation#doStuckDetection} 在速度为 0 时把节点超时上限置为 0，
         * 而超时判定以 {@code timeoutLimit > 0.0} 为前提，导致该分支下的寻路会无限等待下去。
         * 开启后改为使用固定的兜底超时（200 刻），并为「速度极小」的情形加上 4000 刻上限。</p>
         *
         * <p>移植自 mcm-rabbit-pathfinding-fix（Apache-2.0）的 PathNavigation 部分。</p>
         */
        @GlobalConfig(value = "path-navigation-timeout-fix", snowball = true, comment = {
            "修复生物移动速度为 0 时寻路永远不会超时的问题。"
        })
        public boolean pathNavigationTimeoutFix = true;

        // ---- 以下为第三方优化模组移植项，全部默认关闭 ----

        /**
         * 用八叉树（BoxOctree）替代拼图结构生成中 {@code Shapes.join} 的全顶点相交判定。
         *
         * <p>仅影响世界生成期的性能，不改变结构布局。</p>
         *
         * <p>移植自 StructureLayoutOptimizer（MIT）。</p>
         */
        @GlobalConfig(value = "structure-layout-optimizer", snowball = true, comment = {
            "优化拼图（jigsaw）结构的布局计算，减少生成结构时的重复运算。"
        })
        public boolean structureLayoutOptimizer = false;

        /**
         * 对拼图结构池的元素列表做去重，减少内存占用。
         *
         * <p><b>与原版差异</b>：会改变结构的随机抽取结果，破坏与给定种子的原版一致性，
         * 进而影响依赖固定结构布局的玩法与种子工具。默认关闭。</p>
         *
         * <p>移植自 StructureLayoutOptimizer（MIT）。</p>
         */
        @GlobalConfig(value = "structure-layout-deduplicate-template-pool", snowball = true, comment = {
            "对结构模板池做去重，降低结构生成相关的内存占用。"
        })
        public boolean structureLayoutDeduplicateTemplatePool = false;

        /**
         * {@code /data get} 系列命令与选择器 {@code nbt=} 谓词只序列化被请求路径覆盖的顶层键，
         * 而不是整个实体 NBT。
         *
         * <p><b>与原版差异</b>：读路径的结果与按路径查询等价，但省略了未请求的键。
         * 写路径（{@code data modify/merge/remove}、{@code execute store ... data}）仍走原版全量读写，
         * 因为 {@code Entity#load} 对缺失键是无条件重置，局部改写会破坏实体状态。</p>
         *
         * <p>移植自 Bellows（MIT）。需重启生效。</p>
         */
        @GlobalConfig(value = "bellows-nbt-optimizations", snowball = true, lock = true, comment = {
            "启用 NBT 读写路径的优化，减少临时对象分配。"
        })
        public boolean bellowsNbtOptimizations = false;

        /**
         * {@code @e} 选择器按正向 {@code type=} / {@code tag=} 约束走类型/标签倒排索引。
         *
         * <p><b>与原版差异</b>：候选集来源从「遍历维度内全部实体」改为「最小索引集合」，
         * 因此 {@code sort=arbitrary} 下的结果顺序可能与原版不同。
         * 带显式 {@code limit} 的选择器不走索引。第三方插件直接改写 {@code entityTags()}
         * 时索引会产生陈旧条目，但不会漏结果。</p>
         *
         * <p>移植自 Bellows（MIT）。需重启生效。</p>
         */
        @GlobalConfig(value = "bellows-selector-optimizations", snowball = true, lock = true, comment = {
            "优化命令选择器（selector）的解析与匹配过程。"
        })
        public boolean bellowsSelectorOptimizations = false;

        /**
         * 服务端跳过 {@code Display#updateCulling} 的剔除包围盒重算（服务端不使用剔除矩阵）。
         *
         * <p>纯算力优化，无可观测行为差异。移植自 Bellows（MIT）。需重启生效。</p>
         */
        @GlobalConfig(value = "bellows-skip-server-culling", snowball = true, lock = true, comment = {
            "跳过服务端多余的可见性剔除计算。"
        })
        public boolean bellowsSkipServerCulling = false;

        /**
         * 对 collide 判定用的 {@code VoxelShape} 与 {@code faceSturdy} 数组做全局去重，
         * 降低方块状态缓存的内存占用。
         *
         * <p>去重键为几何内容等价，不改方块行为。去重表在进程内常驻，
         * 数据包或插件反复注册新方块时只增不减。</p>
         *
         * <p>移植自 FerriteCore（MIT）。需重启生效。</p>
         */
        @GlobalConfig(value = "ferrite-blockstate-dedup", snowball = true, lock = true, comment = {
            "对方块状态（BlockState）做去重，降低其内存占用。"
        })
        public boolean ferriteBlockstateDedup = false;

        /**
         * 数据组件补丁表为空时收缩为共享空实例，减少每个物品栈的空表对象。
         *
         * <p>与 {@link #sleepingBlockEntity} 互斥：休眠方块实体开启时本项自动让位，
         * 避免额外制造 copyOnWrite 纪元导致多余唤醒。</p>
         *
         * <p>移植自 FerriteCore（MIT）。需重启生效。</p>
         */
        @GlobalConfig(value = "ferrite-component-patch-shrink", snowball = true, lock = true, comment = {
            "压缩数据组件补丁（DataComponentPatch）的存储结构，减少内存占用。"
        })
        public boolean ferriteComponentPatchShrink = false;

        /**
         * 用「建网-找源-扩散」的搜索替换动力铁轨/激活铁轨的递归供电传播，
         * 每条铁轨每次更新最多访问一次，并按方块变更纪元缓存结果。
         *
         * <p><b>与原版差异</b>：供电传播的遍历顺序与短路时机改变，
         * 依赖更新顺序的边缘红石时序可能不同。{@link #railPowerLimit} 限制单次搜索的传播范围。</p>
         *
         * <p>移植自 RailOptimization（GPL-3.0）。</p>
         */
        @GlobalConfig(value = "optimize-powered-rail", snowball = true, comment = {
            "优化动力铁轨的更新逻辑，减少不必要的方块更新。"
        })
        public boolean optimizePoweredRail = false;

        /**
         * 铁轨供电搜索的最大传播步数，范围 1–64，超出会被钳到边界。
         *
         * <p>仅在 {@link #optimizePoweredRail} 开启时生效。原版行为等价于不限步数，
         * 因此该值调得过小会让长距离铁轨失去供电。</p>
         */
        @GlobalConfig(value = "rail-power-limit", snowball = true, comment = {
            "动力铁轨单次最多推动的矿车数量上限。"
        })
        public int railPowerLimit = 8;

        /**
         * 用「方块网格 blast field」求解替换原版沿 1352 条射线采样的爆炸算法。
         *
         * <p><b>与原版差异</b>：爆炸不再走 {@code Level#explode}，坑形与能量衰减由本引擎计算。
         * 已有的 {@code EntityExplodeEvent} / {@code BlockExplodeEvent} / {@code TNTPrimeEvent} /
         * {@code BlockIgniteEvent} / {@code EntityKnockbackEvent} 由桥接层补发，
         * 因此保护类插件仍可拦截与删块。</p>
         *
         * <p>移植自 Oushii（GPL-3.0）。</p>
         */
        @GlobalConfig(value = "explosion-engine", snowball = true, comment = {
            "启用替代的爆炸计算引擎，替换原版爆炸逻辑。"
        })
        public boolean explosionEngine = false;

        /**
         * 同 tick 内相距该半径（格）以内的爆炸合并为一次求解，{@code <= 0} 关闭合并。
         * 仅在 {@link #explosionEngine} 开启时生效，上限 20。
         */
        @GlobalConfig(value = "explosion-engine-cluster-radius", snowball = true, comment = {
            "爆炸引擎将邻近方块聚类处理的半径（单位：格）。"
        })
        public double explosionEngineClusterRadius = 4.0;

        /**
         * 单次爆炸最多引燃的 TNT 实体数，防止连锁爆炸演变成实体风暴。
         * 仅在 {@link #explosionEngine} 开启时生效，范围 1–256。
         */
        @GlobalConfig(value = "explosion-engine-max-primed", snowball = true, comment = {
            "爆炸引擎单次同时处理的最大 TNT 实体数量。"
        })
        public int explosionEngineMaxPrimed = 64;

        /**
         * 缓存要塞环位置（concentric rings），避免每次查询都重算整圈采样。
         *
         * <p>缓存键包含实际生效的种子——Paper 会用服务器配置的 {@code stronghold-seed}
         * 覆盖 {@code concentricRingsSeed}，漏掉这一点会读到错误的环位。</p>
         *
         * <p>移植自 ModernFix（LGPL-3.0）。</p>
         */
        @GlobalConfig(value = "modernfix-cache-strongholds", snowball = true, comment = {
            "缓存要塞环（stronghold ring）的位置，避免重复计算。"
        })
        public boolean modernfixCacheStrongholds = false;

        /**
         * 在数据转化空闲 60 秒后清空 DataFixerUpper 的内部类型重写缓存。
         *
         * <p>纯内存优化：DFU 的几张静态缓存一旦见过某类型就永不释放，
         * 启动期预热后基本是死重量。反射失败只跳过对应表，不影响正确性。</p>
         *
         * <p>移植自 ModernFix（LGPL-3.0）。</p>
         */
        @GlobalConfig(value = "modernfix-dfu", snowball = true, comment = {
            "优化 DataFixerUpper 的加载与查询路径。"
        })
        public boolean modernfixDfu = false;

        /**
         * 围墙类方块几何体按入参去重，减少 {@code WallBlock#makeShapes} 的重复构造。
         *
         * <p>纯内存优化，不改变碰撞与形状语义。</p>
         *
         * <p>移植自 ModernFix（LGPL-3.0）。</p>
         */
        @GlobalConfig(value = "modernfix-dedup-wall-shapes", snowball = true, comment = {
            "对墙类方块（wall）的形状数据做去重。"
        })
        public boolean modernfixDedupWallShapes = false;

        /**
         * 属性表按实例去重（26.3 的键类型已漂移为 {@code Holder<Attribute>}）。
         *
         * <p>移植自 ModernFix（LGPL-3.0）。</p>
         */
        @GlobalConfig(value = "modernfix-attribute-supplier-dedup", snowball = true, comment = {
            "对属性（Attribute）供应器做去重。"
        })
        public boolean modernfixAttributeSupplierDedup = false;

        /**
         * {@code Climate.Parameter} 按 {@code (min, max)} 去重。
         *
         * <p>原版在 {@code Climate} 与 {@code OverworldBiomeBuilder} 里大量构造短生命周期实例，
         * 绝大多数入参重复。取值空间由量化坐标决定，总量有限。</p>
         *
         * <p>移植自 ModernFix（LGPL-3.0）。</p>
         */
        @GlobalConfig(value = "modernfix-dedup-climate-parameters", snowball = true, comment = {
            "对生物群系气候参数做去重。"
        })
        public boolean modernfixDedupClimateParameters = false;

        /**
         * 物品变动触发的采样节流：每个玩家每个物品每 5 次变动才判定一次进度。
         *
         * <p><b>语义变更</b>：会跳过一部分原本执行的条件判定，依赖「边持有边消耗」
         * 累积语义的自定义进度可能延迟触发。移植自 Cerulean（经原作者授权）。</p>
         */
        @GlobalConfig(value = "cerulean-skip-ticks", snowball = true, comment = {
            "跳过未发生变化的容器的 tick 处理。"
        })
        public boolean ceruleanSkipTicks = false;

        /**
         * 堆叠变空时跳过物品变动触发。
         *
         * <p><b>语义变更</b>：变空不可能满足「持有某物」类条件，但若自定义进度把
         * 「数量降到 0」当作有效条件，会被漏掉。</p>
         */
        @GlobalConfig(value = "cerulean-ignore-emptied-stacks", snowball = true, comment = {
            "忽略被清空的物品栈所触发的容器变更通知。"
        })
        public boolean ceruleanIgnoreEmptiedStacks = false;

        /**
         * 堆叠数量减少时跳过物品变动触发。
         *
         * <p><b>语义变更</b>：减少不可能跨过「至少 N 个」的下界，但消耗类进度会受影响。</p>
         */
        @GlobalConfig(value = "cerulean-ignore-decreased-stacks", snowball = true, comment = {
            "忽略数量减少的物品栈所触发的容器变更通知。"
        })
        public boolean ceruleanIgnoreDecreasedStacks = false;

        /**
         * 开箱时把容器里已有的物品直接作为「上次快照」写进 {@code lastSlots}，
         * 避免首次同步时对每个槽位都触发一次物品变动判定。
         *
         * <p>纯开销优化，不改变最终可见的进度状态。</p>
         */
        @GlobalConfig(value = "cerulean-init-last-slots", snowball = true, comment = {
            "预填充最后几个槽位的比对缓存，减少首次比较开销。"
        })
        public boolean ceruleanInitLastSlots = false;

        @GlobalConfig(value = "reduce-chuck-load-and-lookup", comment = {
            "是否减少末影人传送处理的不必要的区块查找和加载。 启用此功能后，",
            "末影人的内部随机传送和向目标附近传送将不再尝试传送至未加载的区块。 插件或外部产生的传送效果不受影响。"
        })
        public boolean reduceChuckLoadAndLookup = true;

        @GlobalConfig(value = "cache-ignite-odds", comment = {
            "是否开启对火焰蔓延检测中对方块燃烧概率的短暂缓存。"
        })
        public boolean cacheIgniteOdds = true;

        @GlobalConfig(value = "faster-chunk-serialization", comment = {
            "是否使用 Lithium 的快速区块保存序列化。"
        })
        public boolean fasterChunkSerialization = true;

        @GlobalConfig(value = "skip-secondary-POI-sensor-if-absent", comment = {
            "是否跳过寻找不存在的村民次要兴趣点。 启用此功能后，如果村民的次要工作点 (Secondary Job Site) 不存在，",
            "将直接跳过并清除村民的这部分记忆。"
        })
        public boolean skipSecondaryPOISensorIfAbsent = true;

        @GlobalConfig(value = "store-mob-counts-in-array", comment = {
            "是否在生物生成上限计算时使用数组，而不是映射 (Map)。 TIP 这个功能产生的优化微乎其微。"
        })
        public boolean storeMobCountsInArray = true;

        @GlobalConfig(value = "optimize-noise-generation", comment = {
            "是否使用 Concurrent Chunk Management Engine 的世界生成噪声优化，",
            "这有助于提高快速生成大量区块的速度。 WARNING 这是一个实验性功能。"
        })
        public boolean optimizeNoiseGeneration = false;

        @GlobalConfig(value = "optimize-sun-burn-tick", comment = {
            "是否优化实体的阳光照射检测。"
        })
        public boolean optimizeSunBurnTick = true;

        @GlobalConfig(value = "optimized-CubePointRange", comment = {
            "是否优化 CubePointRange。"
        })
        public boolean optimizedCubePointRange = true;

        @GlobalConfig(value = "check-frozen-ticks-before-landing-block", comment = {
            "是否在尝试生成冰的时候优先检查游戏刻而不是先检查方块是否为空气。"
        })
        public boolean checkFrozenTicksBeforeLandingBlock = true;

        @GlobalConfig(value = "skip-entity-move-if-movement-is-zero", comment = {
            "是否跳过完全没有移动的实体的位移计算。"
        })
        public boolean skipEntityMoveIfMovementIsZero = true;

        @GlobalConfig(value = "skip-cloning-advancement-criteria", comment = {
            "是否跳过创建进度时对准则 (Criteria) 的复制。"
        })
        public boolean skipCloningAdvancementCriteria = false;

        @GlobalConfig(value = "skip-negligible-planar-movement-multiplication", comment = {
            "是否跳过玩家移动中不必要的乘法计算。"
        })
        public boolean skipNegligiblePlanarMovementMultiplication = true;

        @GlobalConfig(value = "sleeping-block-entity", lock = true, comment = {
            "是否启用 Lithium 的方块实体休眠功能。 这将大大减小开口漏斗的性能开销，并一定程度上优化其他方块实体。"
        })
        public boolean sleepingBlockEntity = false;

        @GlobalConfig(value = "equipment-tracking", lock = true, comment = {
            "启用装备变更追踪优化。"
        })
        public boolean equipmentTracking = false;
    }

    public static ProtocolConfig protocol = new ProtocolConfig();

    @GlobalConfigCategory("protocol")
    public static class ProtocolConfig {

        @GlobalConfig(value = "strict-mode", comment = {
            "协议严格模式，对不合规的数据包做更严格的校验。"
        })
        public boolean strictMode = false;

        public BladerenConfig bladeren = new BladerenConfig();

        @GlobalConfigCategory("bladeren")
        public static class BladerenConfig {
            @GlobalConfig(value = "protocol", comment = {
                "是否开启 Bladeren 协议支持。"
            })
            public boolean enable = true;

            @GlobalConfig(value = "mspt-sync-protocol", validator = MSPTSyncValidator.class, comment = {
                "是否开启 MSPT 同步协议。"
            })
            public boolean msptSyncProtocol = false;

            private static class MSPTSyncValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    LeavesFeatureSet.register(LeavesFeature.of("mspt_sync", value));
                }
            }

            @GlobalConfig(value = "mspt-sync-tick-interval", validator = MSPTSyncIntervalValidator.class, comment = {
                "MSPT 同步频率（单位：游戏刻）。"
            })
            public int msptSyncTickInterval = 20;

            private static class MSPTSyncIntervalValidator extends IntConfigValidator {
                @Override
                public void verify(Integer old, Integer value) throws IllegalArgumentException {
                    if (value <= 0) {
                        throw new IllegalArgumentException("mspt-sync-tick-interval need > 0");
                    }
                }
            }
        }

        public SyncmaticaConfig syncmatica = new SyncmaticaConfig();

        @GlobalConfigCategory("syncmatica")
        public static class SyncmaticaConfig {
            @GlobalConfig(value = "enable", validator = SyncmaticaValidator.class, comment = {
                "是否开启 Syncmatica 协议支持。"
            })
            public boolean enable = false;

            public static class SyncmaticaValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    SyncmaticaProtocol.init(value);
                }
            }

            @GlobalConfig(value = "quota", comment = {
                "是否限制投影文件大小。"
            })
            public boolean useQuota = false;

            @GlobalConfig(value = "quota-limit", comment = {
                "投影文件体积上限（单位：字节）。"
            })
            public int quotaLimit = 40000000;
        }

        public PCAConfig pca = new PCAConfig();

        @GlobalConfigCategory("pca")
        public static class PCAConfig {
            @TransferConfig("protocol.pca-sync-protocol")
            @GlobalConfig(value = "pca-sync-protocol", validator = PcaValidator.class, comment = {
                "是否开启 Plusls Carpet Addition数据同步，其作用相当于在服务端安装了 PCA-Protocol。"
            })
            public boolean enable = false;

            public static class PcaValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    if (old != null && old != value) {
                        PcaSyncProtocol.onConfigModify(value);
                    }
                }
            }

            @TransferConfig("protocol.pca-sync-player-entity")
            @GlobalConfig(value = "pca-sync-player-entity", comment = {
                "指定哪些玩家可以同步 Plusls Carpet Addition 的数据。 NOBODY 所有玩家的数据都无法同步。",
                " BOT 只有假人的数据可以被同步到客户端。 OPS 假人的数据可以被同步到客户端，OP 可以同步所有玩家的数据。",
                " OPS_AND_SELF 玩家可以同步假人的数据，OP 可以同步所有玩家的数据，每个玩家可以同步自己的数据。",
                " EVERYONE 所有的玩家数据都可以互相同步。"
            })
            public PcaPlayerEntityType syncPlayerEntity = PcaPlayerEntityType.OPS;

            public enum PcaPlayerEntityType {
                NOBODY, BOT, OPS, OPS_AND_SELF, EVERYONE
            }
        }

        public AppleSkinConfig appleskin = new AppleSkinConfig();

        @GlobalConfigCategory("appleskin")
        public static class AppleSkinConfig {
            @TransferConfig("protocol.appleskin-protocol")
            @GlobalConfig(value = "protocol", comment = {
                "是否开启 AppleSkin 协议支持。"
            })
            public boolean enable = false;

            @GlobalConfig(value = "sync-tick-interval", comment = {
                "指定 AppleSkin 同步频率（单位：游戏刻）。"
            })
            public int syncTickInterval = 20;
        }

        public ServuxConfig servux = new ServuxConfig();

        @GlobalConfigCategory("servux")
        public static class ServuxConfig {
            @TransferConfig("protocol.servux-protocol")
            @GlobalConfig(value = "structure-protocol", comment = {
                "是否开启 Servux 的结构探测协议支持。"
            })
            public boolean structureProtocol = false;

            @GlobalConfig(value = "entity-protocol", comment = {
                "是否开启 Servux 的生物数据协议支持。"
            })
            public boolean entityProtocol = false;

            @GlobalConfig(value = "hud-metadata-protocol", comment = {
                "是否开启 Servux 的世界元数据协议支持。"
            })
            public boolean hudMetadataProtocol = false;

            @GlobalConfig(value = "hud-logger-protocol", comment = {
                "是否开启 Servux 的服务器记录器数据协议支持。"
            })
            public boolean hudLoggerProtocol = false;

            @GlobalConfig(value = "hud-enabled-loggers", comment = {
                "指定启用哪些记录器 TPS 记录服务器TPS和MSPT MOB_CAPS 记录服务器生物上限和当前数量"
            })
            public List<DataLogger.Type> hudEnabledLoggers = List.of(DataLogger.Type.TPS, DataLogger.Type.MOB_CAPS);

            @GlobalConfig(value = "hud-update-interval", comment = {
                "指定 Servux 记录器同步频率（单位：游戏刻）。"
            })
            public int hudUpdateInterval = 1;

            @GlobalConfig(value = "hud-metadata-protocol-share-seed", comment = {
                "是否在发送世界元数据时包含世界种子。"
            })
            public boolean hudMetadataShareSeed = true;

            public LitematicsConfig litematics = new LitematicsConfig();

            @GlobalConfigCategory("litematics")
            public static class LitematicsConfig {

                @TransferConfig("protocol.servux.litematics-protocol")
                @GlobalConfig(value = "enable", validator = LitematicsProtocolValidator.class, comment = {
                    "是否开启 Servux 的投影协议支持。"
                })
                public boolean enable = false;

                @GlobalConfig(value = "max-nbt-size", validator = MaxNbtSizeValidator.class, comment = {
                    "投影文件体积上限（单位：字节）。"
                })
                public long maxNbtSize = 2097152;

                public static class MaxNbtSizeValidator extends LongConfigValidator {

                    @Override
                    public void verify(Long old, Long value) throws IllegalArgumentException {
                        if (value <= 0) {
                            throw new IllegalArgumentException("Max nbt size can not be <= 0");
                        }
                    }
                }

                public static class LitematicsProtocolValidator extends BooleanConfigValidator {
                    @Override
                    public void onApplied(Boolean old, Boolean value) {
                        PluginManager pluginManager = MinecraftServer.getServer().server.getPluginManager();
                        if (value) {
                            if (pluginManager.getPermission("leaves.protocol.litematics") == null) {
                                pluginManager.addPermission(new Permission("leaves.protocol.litematics", PermissionDefault.OP));
                            }
                        } else {
                            pluginManager.removePermission("leaves.protocol.litematics");
                        }
                    }
                }
            }
        }

        @GlobalConfig(value = "bbor-protocol", comment = {
            "是否开启 BBOR 协议支持。"
        })
        public boolean bborProtocol = false;

        @GlobalConfig(value = "jade-protocol", comment = {
            "是否开启 Jade 协议支持。"
        })
        public boolean jadeProtocol = false;

        @GlobalConfig(value = "alternative-block-placement", validator = AlternativePlaceValidator.class, comment = {
            "指定精确放置协议类型。 NONE 关闭精确放置协议。 CARPET 精确放置协议版本 2。",
            " CARPET_FIX 更好的精确放置协议版本 2（需要客户端安装 MasaGadget）。",
            " LITEMATICA 精确放置协议版本 3。"
        })
        public AlternativePlaceType alternativeBlockPlacement = AlternativePlaceType.NONE;

        public enum AlternativePlaceType {
            NONE, CARPET, CARPET_FIX, LITEMATICA
        }

        private static class AlternativePlaceValidator extends EnumConfigValidator<AlternativePlaceType> {
            @Override
            public void onApplied(AlternativePlaceType old, AlternativePlaceType value) {
                if (value != AlternativePlaceType.NONE) {
                    LeavesConfig.modify.disableDistanceCheckForUseItem = true;
                }
            }
        }

        @GlobalConfig(value = "xaero-map-protocol", comment = {
            "是否开启 Xaero 的世界地图 协议支持。"
        })
        public boolean xaeroMapProtocol = false;

        @GlobalConfig(value = "xaero-map-server-id", comment = {
            "Xaero 的世界地图使用此唯一标识符确定服务器，请确保每个服务器使用唯一的标识符。 TIP 这是一个 int 范围内的随机值。",
            "如果你不清楚这个范围，请使用服务器生成的随机值。"
        })
        public int xaeroMapServerID = new Random().nextInt();

        @GlobalConfig(value = "leaves-carpet-support", comment = {
            "是否开启 Carpet 协议支持。客户端可能会感知此协议，以实现更多 Carpet 功能。",
            " TIP 开启此功能并不相当于在服务端安装了 Carpet Mod，它只会向客户端声明服务端安装了 Carpet。"
        })
        public boolean leavesCarpetSupport = false;

        @GlobalConfig(value = "rei-server-protocol", validator = ReiValidator.class, comment = {
            "是否开启 Roughly Enough Items 协议支持。"
        })
        public boolean reiServerProtocol = false;

        public static class ReiValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                if (old != value && value != null) {
                    REIServerProtocol.onConfigModify(value);
                }
            }
        }

        @GlobalConfig(value = "chat-image-protocol", comment = {
            "是否开启 ChatImage 协议支持。",
            " TIP 此功能需要启用 settings.fix.vanilla-display-name。"
        })
        public boolean chatImageProtocol = false;
    }

    public static MiscConfig mics = new MiscConfig();

    @GlobalConfigCategory("misc")
    public static class MiscConfig {

        public AsyncKeepaliveConfig asyncKeepalive = new AsyncKeepaliveConfig();

        @GlobalConfigCategory("async-keepalive")
        public static class AsyncKeepaliveConfig {

            @GlobalConfig(value = "enable", lock = true, comment = {
                "启用异步 keepalive，把连接保活检测放到独立线程，减少主线程开销。"
            })
            public boolean enable = false;

            @GlobalConfig(value = "timeout-seconds", lock = true, validator = TimeoutSecondsValidator.class, comment = {
                "异步 keepalive 的超时时间（秒），超时后断开连接。"
            })
            public int timeoutSeconds = 20;

            public long getTimeoutMillis() {
                return this.timeoutSeconds * 1000L;
            }

            private static class TimeoutSecondsValidator extends IntConfigValidator {
                @Override
                public void verify(Integer old, Integer value) throws IllegalArgumentException {
                    if (value <= 0) {
                        throw new IllegalArgumentException("timeout-seconds need > 0");
                    }
                }
            }
        }

        public AutoUpdateConfig autoUpdate = new AutoUpdateConfig();

        @GlobalConfigCategory("auto-update")
        public static class AutoUpdateConfig {
            @GlobalConfig(value = "enable", lock = true, validator = AutoUpdateValidator.class, comment = {
                "是否开启 Leaves 自动更新。 TIP 新的 Leaves 更新将在服务器重新启动后安装。"
            })
            public boolean enable = false;

            private static class AutoUpdateValidator extends BooleanConfigValidator {
                @Override
                public void runAfterLoader(Boolean value, boolean reload) {
                    if (!reload) {
                        LeavesUpdateHelper.init();
                        if (value) {
                            LeavesLogger.LOGGER.warn("Auto-Update is not completely safe. Enabling it may cause data security problems!");
                        }
                    }
                }
            }

            @GlobalConfig(value = "download-source", lock = true, validator = DownloadSourceValidator.class, comment = {
                "指定自动更新下载源。 GitHub Releases: " + LeavesUpdateHelper.DOWNLOAD_REPOSITORY
            })
            public String source = LeavesUpdateHelper.DOWNLOAD_REPOSITORY;

            public static class DownloadSourceValidator extends StringConfigValidator {
                @Override
                public void verify(String old, String value) {
                    if (!LeavesUpdateHelper.DOWNLOAD_REPOSITORY.equals(value)) {
                        throw new IllegalArgumentException("Unsupported auto-update source");
                    }
                }

                @Override
                public List<String> valueSuggest() {
                    return List.of(LeavesUpdateHelper.DOWNLOAD_REPOSITORY);
                }
            }

            @GlobalConfig(value = "allow-experimental", comment = {
                "是否允许更新到实验性版本。"
            })
            public Boolean allowExperimental = false;

            @GlobalConfig(value = "time", lock = true, comment = {
                "Leaves 自动更新的时间点（格式：HH:mm）。 TIP 此功能仅会在服务器时间到达指定时间点时检查更新，并不会重启服务器。"
            })
            public List<String> updateTime = List.of("14:00", "2:00");
        }

        public ExtraYggdrasilConfig yggdrasil = new ExtraYggdrasilConfig();

        @GlobalConfigCategory("extra-yggdrasil-service")
        public static class ExtraYggdrasilConfig {
            @GlobalConfig(value = "enable", validator = ExtraYggdrasilValidator.class, comment = {
                "是否开启额外的 Yggdrasil 验证服务器。"
            })
            public boolean enable = false;

            public static class ExtraYggdrasilValidator extends BooleanConfigValidator {
                @Override
                public void onApplied(Boolean old, Boolean value) {
                    if (value) {
                        LeavesLogger.LOGGER.warn("extra-yggdrasil-service is an unofficial support. Enabling it may cause data security problems!");
                        GlobalConfiguration.get().unsupportedSettings.performUsernameValidation = true; // always check username
                    }
                }
            }

            @GlobalConfig(value = "login-protect", comment = {
                "是否开启登录保护，开启后将不允许用户名相同而 UUID 不同的玩家加入服务器。"
            })
            public boolean loginProtect = false;

            @SuppressWarnings("unused")
            @GlobalConfig(value = "urls", lock = true, validator = ExtraYggdrasilUrlsValidator.class, comment = {
                "指定 Yggdrasil API 地址。"
            })
            private List<String> serviceList = List.of("https://url.with.authlib-injector-yggdrasil");

            public static class ExtraYggdrasilUrlsValidator extends ListConfigValidator.STRING {
                @Override
                @SuppressWarnings("deprecation") // Match the URL parser used by the session service.
                public void verify(List<String> old, List<String> value) {
                    for (String address : value) {
                        try {
                            new URL(address + "/sessionserver/session/minecraft/hasJoined");
                        } catch (MalformedURLException e) {
                            throw new IllegalArgumentException("Invalid extra Yggdrasil service URL", e);
                        }
                    }
                }

                @Override
                public void onApplied(List<String> old, List<String> value) {
                    LeavesMinecraftSessionService.initExtraYggdrasilList(value);
                }
            }
        }

        @GlobalConfig(value = "disable-method-profiler", comment = {
            "是否禁用方法检查器。这可能对服务端调试造成影响，但不影响实际生产环境。关闭后可以部分提升服务端性能。"
        })
        public boolean disableMethodProfiler = true;

        @TransferConfig("no-chat-sign")
        @GlobalConfig(value = "no-chat-sign", comment = {
            "是否禁用聊天举报。"
        })
        public boolean noChatSign = true;

        @GlobalConfig(value = "dont-respond-ping-before-start-fully", comment = {
            "在服务器完全启动前是否不响应客户端请求。"
        })
        public boolean dontRespondPingBeforeStart = true;

        @GlobalConfig(value = "server-lang", lock = true, validator = ServerLangValidator.class, comment = {
            "指定服务端的语言。 en_us 英语（美国） zh_cn 简体中文（中国）"
        })
        public String serverLang = "zh_cn";

        private static class ServerLangValidator extends StringConfigValidator {
            @Override
            public void verify(String old, String value) throws IllegalArgumentException {
                List<String> supported = new ArrayList<>(List.of("en_us"));
                if (ServerI18nUtil.finishPreload && ServerI18nUtil.tryAppendLanguages(supported) && !supported.contains(value)) {
                    throw new IllegalArgumentException("lang " + value + " not supported");
                }
            }

            @Override
            public List<String> valueSuggest() {
                List<String> supported = new ArrayList<>(List.of("en_us"));
                if (ServerI18nUtil.finishPreload) {
                    ServerI18nUtil.tryAppendLanguages(supported);
                }
                return supported;
            }
        }

        @GlobalConfig(value = "server-mod-name", comment = {
            "指定服务端名称。"
        })
        public String serverModName = "SnowBall";

        @GlobalConfig(value = "bstats-privacy-mode", comment = {
            "是否开启 bStats 隐私模式。开启后，Leaves 将停止收集服务端版本、游玩人数、Java 版本等用户信息。"
        })
        public boolean bstatsPrivacyMode = false;

        @GlobalConfig(value = "force-minecraft-command", lock = true, comment = {
            "是否令插件命令不覆盖原版指令。"
        })
        public boolean forceMinecraftCommand = false;

        @GlobalConfig(value = "leaves-packet-event", comment = {
            "是否启用 Leaves 提供的发包事件系统。这可能影响到服务器性能。"
        })
        public boolean leavesPacketEvent = false;
    }

    public static RegionConfig region = new RegionConfig();

    @GlobalConfigCategory("region")
    public static class RegionConfig {

        @GlobalConfig(value = "format", lock = true, comment = {
            "指定区域文件格式。",
            " ANVIL Anvil 文件格式（.mca） LINEAR Linear 文件格式（.linear） DANGER LINEAR 格式是一个极度危险的实验性功能，",
            "它可以极大程度压缩存档占用，但根据报告，它很有可能导致存档和玩家数据丢失等问题。 如果你一定要使用 LINEAR 格式，",
            "请备份你的存档并使用 Linear V2。"
        })
        public EnumRegionFormat format = EnumRegionFormat.ANVIL;

        public BLinearConfig blinear = new BLinearConfig();

        @GlobalConfigCategory("blinear")
        public static class BLinearConfig {

            @GlobalConfig(value = "compression-level", lock = true, validator = CompressionLevelValidator.class, comment = {
                "BLinear 区域格式使用的压缩等级，取值越大压缩越狠、CPU 开销越高。"
            })
            public int compressionLevel = 1;

            private static class CompressionLevelValidator extends IntConfigValidator {
                @Override
                public void verify(Integer old, Integer value) throws IllegalArgumentException {
                    if (value < 1 || value > 22) {
                        throw new IllegalArgumentException("blinear.compression-level need between 1 and 22");
                    }
                }
            }

            @GlobalConfig(value = "io-flush-delay-ms", lock = true, comment = {
                "BLinear 区域格式的 IO 刷盘延迟（毫秒）。"
            })
            public int ioFlushDelayMs = 3000;

            @GlobalConfig(value = "io-thread-count", lock = true, comment = {
                "BLinear 区域格式使用的 IO 线程数。"
            })
            public int ioThreadCount = 6;

            public BufferedLinearRegionFileFlusher flusher = null;

            public synchronized void startFlusher() {
                if (this.flusher == null) {
                    this.flusher = new BufferedLinearRegionFileFlusher(this.ioThreadCount, 20, this.ioFlushDelayMs);
                }
            }

            public void closeFlusherIfEnabled() {
                if (this.flusher != null) {
                    LeavesLogger.LOGGER.info("Shutting down buffered linear flusher threads for 60 seconds");
                    this.flusher.shutdown();
                }
            }
        }
    }

    public static FixConfig fix = new FixConfig();

    @GlobalConfigCategory("fix")
    public static class FixConfig {
        @GlobalConfig(value = "fix-skeleton-horse-trap-goal", snowball = true, lock = true, comment = {
            "修复骷髅马陷阱在目标迭代中移除自身的问题，并清理缓存的玩家列表。需重启。"
        })
        public boolean fixSkeletonHorseTrapGoal = true;

        @GlobalConfig(value = "propagate-block-destruction-cancellation", snowball = true, comment = {
            "末地入口、出生平台和出口门生成时，尊重插件取消的方块破坏；已为空气的位置仍可正常放块。",
            "保留 Bukkit 放置事件与方块快照回滚行为，可热重载。"
        })
        public boolean propagateBlockDestructionCancellation = false;

        @GlobalConfig(value = "vanilla-hopper", comment = {
            "开启后会使用较为激进的方案将漏斗的运行逻辑还原为原版行为。 WARNING 开启后可能会轻微降低性能。"
        })
        public boolean vanillaHopper = false;

        @GlobalConfig(value = "vanilla-display-name", validator = DisplayNameValidator.class, comment = {
            "是否将聊天中的玩家名解析为 文本组件，这会影响到玩家名改变颜色等功能。"
        })
        public boolean vanillaDisplayName = true;

        private static class DisplayNameValidator extends BooleanConfigValidator {
            @Override
            public void onApplied(Boolean old, Boolean value) {
                if (value == old) {
                    return;
                }
                for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
                    player.adventure$displayName = value ? PaperAdventure.asAdventure(player.getDisplayName()) : Component.text(player.getScoreboardName());
                }
            }
        }

        @GlobalConfig(value = "vanilla-portal-handle", comment = {
            "是否使用原版原版地狱门传送时间判定，如不启用，则实体接触传送门后被传送的时间较原版快 1 游戏刻，",
            "这将略微影响实体过门后的位置和速度，可能导致时序相当精确的跨维度机器无法使用。"
        })
        public boolean vanillaPortalHandle = true;

        @GlobalConfig(value = "collision-behavior", validator = CollisionBehaviorValidator.class, comment = {
            "指定服务器使用何种实体碰撞计算方式。 BLOCK_SHAPE_VANILLA 对于方块使用 Paper 的计算方式，",
            "其它使用原版的计算方式，兼顾性能优化和遵循原版。 PAPER 完全使用 Paper 计算方式，拥有较好的优化，",
            "但会破坏部分原版行为。 VANILLA 完全使用原版计算方式，可能会降低性能，但完全符合原版行为。"
        })
        public CollisionBehavior collisionBehavior = CollisionBehavior.PAPER;

        public enum CollisionBehavior {
            VANILLA, PAPER
        }

        private static class CollisionBehaviorValidator extends EnumConfigValidator<CollisionBehavior> {
            @Override
            public CollisionBehavior stringConvert(@NotNull String value) throws IllegalArgumentException {
                if (value.equalsIgnoreCase("BLOCK_SHAPE_VANILLA")) {
                    LeavesLogger.LOGGER.warn("Paper has updated the collision behavior to BLOCK_SHAPE_VANILLA mode, converting this to PAPER...");
                    value = "PAPER";
                }
                return super.stringConvert(value);
            }
        }

        @GlobalConfig(value = "stacked-container-destroyed-drop", comment = {
            "堆叠容器（如堆叠的潜影盒）被破坏时，把其中的内容物一并掉落。"
        })
        public boolean stackedContainerDestroyedDrop = true;

        @GlobalConfig(value = "spear-charge-contact-cooldown", snowball = true, comment = {
            "长矛冲锋仅在满足伤害、击退或下马条件时记入接触冷却；只扫到目标不再提前触发冷却。",
            "不改变原版速度门槛、接触冷却时长或实体受伤后的无敌时间。"
        })
        public boolean spearChargeContactCooldown = false;

        @GlobalConfig(value = "spear-charge-dragon-hit-part", snowball = true, comment = {
            "长矛冲锋按实际命中的末影龙部位结算伤害，仍按龙本体计算接触冷却。",
            "头部和颈部不受躯干部位减伤；保留原版阶段、伤害事件及其余部位的减伤规则。"
        })
        public boolean spearChargeDragonHitPart = false;
    }
}
