package org.snowballmc.snowball.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.config.annotations.TransferConfig;
import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.BooleanConfigValidator;
import org.snowballmc.snowball.config.api.impl.ConfigValidatorImpl.IntConfigValidator;
import org.snowballmc.snowball.util.LeavesUpdateHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GlobalConfigFilesTestSuite {
    private static final String WEB = "settings.misc.config-web";
    private static final String LANGUAGE = "settings.misc.server-lang";
    private static final String SERVER_NAME = "settings.misc.server-mod-name";
    private static final String SOURCE = "settings.misc.auto-update.download-source";
    private static final String BARREL = "settings.modify.large-barrel";
    private static final String RAIL_LIMIT = "settings.performance.rail-power-limit";
    private static final Set<String> SNOWBALL_KEYS = Set.of(
        "settings.modify.fakeplayer.fakeplayer-inventory-gui-buttons",
        "settings.modify.allow-grindstone-overstacking",
        "settings.modify.curse-enchanted-book-stack-size",
        BARREL,
        "settings.modify.large-ender-chest",
        "settings.performance.path-navigation-timeout-fix",
        "settings.performance.structure-layout-optimizer",
        "settings.performance.structure-layout-deduplicate-template-pool",
        "settings.performance.bellows-nbt-optimizations",
        "settings.performance.bellows-selector-optimizations",
        "settings.performance.bellows-skip-server-culling",
        "settings.performance.ferrite-blockstate-dedup",
        "settings.performance.ferrite-component-patch-shrink",
        "settings.performance.optimize-powered-rail",
        RAIL_LIMIT,
        "settings.performance.explosion-engine",
        "settings.performance.explosion-engine-cluster-radius",
        "settings.performance.explosion-engine-max-primed",
        "settings.performance.modernfix-cache-strongholds",
        "settings.performance.modernfix-dfu",
        "settings.performance.modernfix-dedup-wall-shapes",
        "settings.performance.modernfix-attribute-supplier-dedup",
        "settings.performance.modernfix-dedup-climate-parameters",
        "settings.performance.cerulean-skip-ticks",
        "settings.performance.cerulean-ignore-emptied-stacks",
        "settings.performance.cerulean-ignore-decreased-stacks",
        "settings.performance.cerulean-init-last-slots",
        "settings.fix.spear-charge-contact-cooldown",
        "settings.fix.spear-charge-dragon-hit-part",
        "settings.performance.skip-generic-game-event-without-listeners",
        "settings.performance.skip-block-physics-event-without-listeners",
        "settings.performance.optimize-chunk-packet-construction",
        "settings.performance.skip-inventory-change-without-listeners",
        "settings.performance.optimize-mob-spawning",
        "settings.performance.biome-spy",
        "settings.fix.fix-skeleton-horse-trap-goal",
        "settings.fix.propagate-block-destruction-cancellation",
        "settings.async.async-mob-spawning.enabled",
        "settings.async.async-pathfinding.enabled",
        "settings.async.async-pathfinding.max-threads",
        "settings.async.async-pathfinding.keepalive",
        "settings.async.async-pathfinding.queue-size",
        "settings.async.async-pathfinding.reject-policy"
    );

    @TempDir
    Path directory;

    @Test
    void generatesExactlyTheSplitKeysAndTheirComments() throws Exception {
        GlobalConfigCreator.create(directory.resolve("config"));
        GlobalConfigFiles files = load();
        Set<String> snowballPaths = files.snowball().getKeys(true).stream()
            .filter(path -> !files.snowball().isConfigurationSection(path)).collect(Collectors.toSet());
        assertEquals(SNOWBALL_KEYS, snowballPaths);
        for (String path : snowballPaths) {
            assertFalse(files.leaves().contains(path), path);
            assertFalse(files.snowball().getComments(path).isEmpty(), path);
        }
        assertEquals(LeavesConfig.CURRENT_CONFIG_VERSION, files.leaves().getInt("config-version"));
        assertEquals("zh_cn", files.leaves().getString(LANGUAGE));
        assertEquals("SnowBall", files.leaves().getString(SERVER_NAME));
        assertEquals(LeavesUpdateHelper.DOWNLOAD_REPOSITORY, files.leaves().getString(SOURCE));
        assertFalse(files.leaves().contains(WEB));
        assertTrue(files.leaves().contains("settings.modify.snowball-and-egg-can-knockback-player"));
        assertTrue(files.snowball().getBoolean("settings.performance.skip-generic-game-event-without-listeners"));
        assertFalse(files.snowball().getBoolean("settings.async.async-mob-spawning.enabled"));
        assertFalse(files.snowball().getBoolean("settings.performance.biome-spy"));
        String pathfinding = "settings.async.async-pathfinding.";
        assertFalse(files.snowball().getBoolean(pathfinding + "enabled"));
        assertEquals(0, files.snowball().getInt(pathfinding + "max-threads"));
        assertEquals(60, files.snowball().getInt(pathfinding + "keepalive"));
        assertEquals(0, files.snowball().getInt(pathfinding + "queue-size"));
        assertEquals("AUTO", files.snowball().getString(pathfinding + "reject-policy"));
    }

    @Test
    void relocatesLegacyFileAndMigratesValuesAndCommentsWithoutOverwritingSnowBall() throws Exception {
        Path old = directory.resolve("leaves.yml");
        String original = """
            # 保留文件头

            settings:
              # 容器设置
              modify:
                # 木桶配置说明
                large-barrel: true # 木桶自定义注释
              performance:
                # 铁轨配置说明
                rail-power-limit: 13
              misc:
                config-web:
                  enable: true
                server-lang: en_us
                server-mod-name: My Server
                auto-update:
                  # 指定自动更新下载源。 application GitHub Releases rainyun 雨云提供的镜像下载源
                  # 保留用户注释
                  download-source: cloud
            """;
        Files.writeString(old, original);
        Path leaves = configPath(GlobalConfigFiles.LEAVES_FILE);
        assertEquals(leaves, GlobalConfigFiles.prepareLeavesFile(leaves, old, leaves));
        assertFalse(Files.exists(old));
        assertEquals(original, Files.readString(leaves));
        Files.writeString(configPath(GlobalConfigFiles.SNOWBALL_FILE), """
            settings:
              performance:
                # 新文件铁轨说明
                rail-power-limit: 17
            """);

        GlobalConfigFiles first = load();
        first.migrate(List.of(BARREL, RAIL_LIMIT));
        first.save();
        GlobalConfigFiles files = load();
        assertTrue(files.snowball().getBoolean(BARREL));
        assertEquals(17, files.snowball().getInt(RAIL_LIMIT));
        assertEquals(List.of("木桶配置说明"), files.snowball().getComments(BARREL));
        assertEquals(List.of("木桶自定义注释"), files.snowball().getInlineComments(BARREL));
        assertEquals(List.of("容器设置"), files.snowball().getComments("settings.modify"));
        assertEquals(List.of("新文件铁轨说明", "铁轨配置说明"), files.snowball().getComments(RAIL_LIMIT));
        assertEquals(List.of("保留文件头"), files.leaves().options().getHeader());
        assertEquals(List.of("保留用户注释"), files.leaves().getComments(SOURCE));
        assertEquals(LeavesUpdateHelper.DOWNLOAD_REPOSITORY, files.leaves().getString(SOURCE));
        assertEquals("en_us", files.leaves().getString(LANGUAGE));
        assertEquals("My Server", files.leaves().getString(SERVER_NAME));
        assertFalse(files.leaves().contains(BARREL));
        assertFalse(files.leaves().contains(RAIL_LIMIT));
        assertFalse(files.leaves().contains(WEB));
        files.migrate(List.of(BARREL, RAIL_LIMIT));
        files.save();
        try (var backups = Files.newDirectoryStream(leaves.getParent(), "leaves.yml.migration-*.bak")) {
            List<Path> paths = java.util.stream.StreamSupport.stream(backups.spliterator(), false).toList();
            assertEquals(1, paths.size());
            assertEquals(original, Files.readString(paths.getFirst()));
        }
    }

    @Test
    void mergesTwoLegacyLocationsAndLeavesExplicitPathUntouched() throws Exception {
        Path old = directory.resolve("leaves.yml");
        Files.writeString(old, "settings:\n  modify:\n    large-barrel: true\n  misc:\n    server-lang: en_us\n");
        Path leaves = configPath(GlobalConfigFiles.LEAVES_FILE);
        Files.createDirectories(leaves.getParent());
        Files.writeString(leaves, "settings:\n  misc:\n    server-lang: zh_cn\n");
        Path explicit = directory.resolve("explicit.yml");
        assertEquals(explicit, GlobalConfigFiles.prepareLeavesFile(explicit, old, leaves));
        assertTrue(Files.exists(old));
        GlobalConfigFiles.prepareLeavesFile(leaves, old, leaves);
        assertFalse(Files.exists(old));
        assertTrue(load().leaves().getBoolean(BARREL));
        assertEquals("zh_cn", load().leaves().getString(LANGUAGE));
    }

    @Test
    void rejectsInvalidSnowBallYamlWithoutChangingLeaves() throws Exception {
        GlobalConfigCreator.create(directory.resolve("config"));
        Path leaves = configPath(GlobalConfigFiles.LEAVES_FILE);
        Path snowball = configPath(GlobalConfigFiles.SNOWBALL_FILE);
        String original = Files.readString(leaves);
        Files.writeString(snowball, "settings: [\n");
        assertThrows(InvalidConfigurationException.class, this::load);
        assertEquals(original, Files.readString(leaves));
        assertEquals("settings: [\n", Files.readString(snowball));
    }

    @Test
    void reloadKeepsLockedRuntimeValueAndCommandWritesOnlyTheOwningFile() throws Exception {
        Settings fixture = new Settings();
        Path leavesPath = configPath(GlobalConfigFiles.LEAVES_FILE);
        Path snowballPath = configPath(GlobalConfigFiles.SNOWBALL_FILE);
        Files.createDirectories(leavesPath.getParent());
        Files.writeString(leavesPath, """
            settings:
              modify:
                large-barrel: true
                old-limit: 12 # 旧键的注释
              misc:
                server-mod-name: Initial
            """);
        GlobalConfigFiles files = load();
        GlobalConfigManager.load(files, fixtureEntries(fixture, files), false);
        assertTrue(fixture.barrel);
        assertEquals(12, fixture.limit);
        assertEquals(List.of("旧键的注释"), load().snowball().getInlineComments(RAIL_LIMIT));
        assertFalse(load().leaves().contains("settings.modify.old-limit"));
        assertEquals("Initial", fixture.name);
        assertEquals(Set.of("rail-power-limit"), GlobalConfigManager.getVerifiedConfigSubPaths("performance.rail"));
        assertTrue(GlobalConfigManager.getVerifiedConfigSubPaths("modify.").contains("large-barrel"));

        GlobalConfigFiles changed = load();
        changed.leaves().set(SERVER_NAME, "Reloaded");
        changed.snowball().set(BARREL, false);
        changed.snowball().set(RAIL_LIMIT, 19);
        changed.save();
        GlobalConfigFiles reloaded = load();
        GlobalConfigManager.load(reloaded, fixtureEntries(fixture, reloaded), true);
        assertFalse(fixture.barrel);
        assertEquals("Reloaded", fixture.name);
        assertEquals(12, fixture.limit);
        assertEquals("12(19 after restart)", GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit").getString());

        String leavesBefore = Files.readString(leavesPath);
        GlobalConfigManager.getVerifiedConfig("modify.large-barrel").set("true");
        assertTrue(fixture.barrel);
        assertTrue(load().snowball().getBoolean(BARREL));
        assertEquals(leavesBefore, Files.readString(leavesPath));
        VerifiedConfig locked = GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit");
        assertTrue(locked.lock());
        assertDoesNotThrow(() -> locked.set("22"));
        assertEquals(22, load().snowball().getInt(RAIL_LIMIT));
        assertEquals(12, fixture.limit);
        assertEquals("12(22 after restart)", locked.getString());
        String snowballBefore = Files.readString(snowballPath);
        GlobalConfigManager.getVerifiedConfig("misc.server-mod-name").set("Command");
        assertEquals("Command", fixture.name);
        assertEquals("Command", load().leaves().getString(SERVER_NAME));
        assertEquals(snowballBefore, Files.readString(snowballPath));
    }

    @Test
    void commandPreservesUnrelatedDiskEditsAndRejectsNonBooleanInput() throws Exception {
        Settings fixture = new Settings();
        GlobalConfigFiles files = load();
        GlobalConfigManager.load(files, fixtureEntries(fixture, files), false);
        Files.writeString(files.snowballPath(), """
            # Custom header

            settings:
              modify:
                # Custom barrel comment
                large-barrel: false # Custom inline comment
              performance:
                rail-power-limit: 19
              manual-section:
                keep: hand-edited
            """);
        String leavesBefore = Files.readString(files.leavesPath());
        String snowballBefore = Files.readString(files.snowballPath());
        VerifiedConfig barrel = GlobalConfigManager.getVerifiedConfig("modify.large-barrel");
        for (String invalid : List.of("tru", "yes", "no", "0", "", " true ")) {
            assertThrows(IllegalArgumentException.class, () -> barrel.set(invalid), invalid);
        }
        assertEquals(snowballBefore, Files.readString(files.snowballPath()));
        assertFalse(fixture.barrel);

        barrel.set("TrUe");
        GlobalConfigFiles saved = load();
        assertTrue(fixture.barrel);
        assertEquals("true", barrel.getString());
        assertEquals("8", GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit").getString());
        assertTrue(saved.snowball().getBoolean(BARREL));
        assertEquals(19, saved.snowball().getInt(RAIL_LIMIT));
        assertEquals("hand-edited", saved.snowball().getString("settings.manual-section.keep"));
        assertEquals(List.of("Custom header"), saved.snowball().options().getHeader());
        assertEquals(List.of("Custom barrel comment"), saved.snowball().getComments(BARREL));
        assertEquals(List.of("Custom inline comment"), saved.snowball().getInlineComments(BARREL));
        assertEquals(leavesBefore, Files.readString(files.leavesPath()));
        barrel.set("FaLsE");
        assertFalse(fixture.barrel);
        assertFalse(load().snowball().getBoolean(BARREL));
    }

    @Test
    void reloadMigratesLockedLegacyAliasAndBacksUpItsOriginalBytes() throws Exception {
        Settings fixture = new Settings();
        GlobalConfigFiles initial = load();
        GlobalConfigManager.load(initial, fixtureEntries(fixture, initial), false);
        assertEquals(8, fixture.limit);
        assertEquals(1, fixture.lockedApplications);
        String legacy = "settings:\n  modify:\n    old-limit: 14 # Old comment\n";
        Files.writeString(initial.leavesPath(), legacy);
        Files.delete(initial.snowballPath());
        GlobalConfigFiles changed = load();
        GlobalConfigManager.load(changed, fixtureEntries(fixture, changed), true);

        GlobalConfigFiles migrated = load();
        assertFalse(migrated.leaves().contains("settings.modify.old-limit"));
        assertEquals(14, migrated.snowball().getInt(RAIL_LIMIT));
        assertEquals(List.of("Old comment"), migrated.snowball().getInlineComments(RAIL_LIMIT));
        assertEquals(8, fixture.limit);
        assertEquals(1, fixture.lockedApplications);
        VerifiedConfig locked = GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit");
        assertEquals("8(14 after restart)", locked.getString());
        locked.set("16");
        assertEquals(8, fixture.limit);
        assertEquals(1, fixture.lockedApplications);
        assertEquals("8(16 after restart)", locked.getString());
        try (var backups = Files.list(initial.leavesPath().getParent())) {
            List<Path> matches = backups.filter(path -> path.getFileName().toString().matches("leaves\\.yml\\.migration-.+\\.bak")).toList();
            assertEquals(1, matches.size());
            assertEquals(legacy, Files.readString(matches.getFirst()));
        }
    }

    @Test
    void failedWritesDoNotPublishRuntimeValuesCallbacksOrQuerySnapshots() throws Exception {
        Settings fixture = new Settings();
        GlobalConfigFiles files = load();
        GlobalConfigManager.load(files, fixtureEntries(fixture, files), false);
        VerifiedConfig barrel = GlobalConfigManager.getVerifiedConfig("modify.large-barrel");
        VerifiedConfig locked = GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit");
        locked.set("19");
        assertEquals(1, fixture.barrelApplications);
        assertEquals(1, fixture.lockedApplications);

        // Stage valid values, then obstruct the second file at save time.
        GlobalConfigFiles candidates = load();
        candidates.snowball().set(BARREL, true);
        candidates.snowball().set(RAIL_LIMIT, 25);
        candidates.leaves().set(SERVER_NAME, "Not published");
        Files.delete(candidates.leavesPath());
        Files.createDirectory(candidates.leavesPath());
        Files.writeString(candidates.leavesPath().resolve("block-replacement"), "keep");
        assertThrows(IOException.class, () -> GlobalConfigManager.load(candidates, fixtureEntries(fixture, candidates), true));
        assertSame(barrel, GlobalConfigManager.getVerifiedConfig("modify.large-barrel"));
        assertSame(locked, GlobalConfigManager.getVerifiedConfig("performance.rail-power-limit"));
        assertEquals("false", barrel.getString());
        assertEquals("8(19 after restart)", locked.getString());
        assertEquals("SnowBall", fixture.name);
        assertFalse(fixture.barrel);
        assertEquals(8, fixture.limit);
        assertEquals(1, fixture.barrelApplications);
        assertEquals(1, fixture.lockedApplications);

        // A malformed owning file must not be overwritten or change the published snapshot.
        String invalidYaml = "settings: [\n";
        Files.writeString(files.snowballPath(), invalidYaml);
        assertThrows(IllegalArgumentException.class, () -> barrel.set("true"));
        assertThrows(IllegalArgumentException.class, () -> locked.set("22"));
        assertEquals(invalidYaml, Files.readString(files.snowballPath()));
        assertEquals("false", barrel.getString());
        assertEquals("8(19 after restart)", locked.getString());
        assertEquals(1, fixture.barrelApplications);
    }

    @Test
    void realConfigAppliesDependentRuntimeFieldOnlyAfterSave() throws Exception {
        GlobalConfigFiles files = load();
        VerifiedConfig forceVoidTrade = GlobalConfigManager.entries(files).stream()
            .filter(entry -> entry.path().equals("settings.modify.force-void-trade"))
            .findFirst().orElseThrow();
        boolean originalForce = LeavesConfig.modify.forceVoidTrade;
        boolean originalVoidTrade = LeavesConfig.modify.oldMC.voidTrade;
        try {
            LeavesConfig.modify.forceVoidTrade = false;
            LeavesConfig.modify.oldMC.voidTrade = false;
            Files.createDirectories(files.leavesPath().getParent());
            Files.writeString(files.leavesPath(), "settings: [\n");
            assertThrows(IllegalArgumentException.class, () -> forceVoidTrade.set("true"));
            assertFalse(LeavesConfig.modify.forceVoidTrade);
            assertFalse(LeavesConfig.modify.oldMC.voidTrade);
            assertFalse(files.leaves().contains(forceVoidTrade.path()));

            Files.writeString(files.leavesPath(), "settings: {}\n");
            forceVoidTrade.set("true");
            assertTrue(LeavesConfig.modify.forceVoidTrade);
            assertTrue(LeavesConfig.modify.oldMC.voidTrade);
            assertTrue(load().leaves().getBoolean(forceVoidTrade.path()));
        } finally {
            LeavesConfig.modify.forceVoidTrade = originalForce;
            LeavesConfig.modify.oldMC.voidTrade = originalVoidTrade;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<VerifiedConfig> fixtureEntries(Settings fixture, GlobalConfigFiles files) {
        return java.util.Arrays.stream(Settings.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(GlobalConfig.class))
            .map(field -> {
                field.setAccessible(true);
                VerifiedConfig config = VerifiedConfig.build(field.getAnnotation(GlobalConfig.class), field, fixture, "settings.", files);
                ConfigValidator<?> validator = switch (field.getName()) {
                    case "barrel" -> new BooleanConfigValidator() {
                        @Override
                        public void onApplied(Boolean old, Boolean value) {
                            fixture.barrelApplications++;
                        }
                    };
                    case "limit" -> new IntConfigValidator() {
                        @Override
                        public void onApplied(Integer old, Integer value) {
                            fixture.lockedApplications++;
                        }
                    };
                    default -> config.validator();
                };
                return new VerifiedConfig((ConfigValidator<? super Object>) validator,
                    config.lock(), field, fixture, config.path(), files, config.snowball());
            }).toList();
    }

    private static final class Settings {
        int barrelApplications;
        int lockedApplications;

        @GlobalConfig(value = "modify.large-barrel", snowball = true)
        boolean barrel;

        @GlobalConfig(value = "performance.rail-power-limit", lock = true, snowball = true)
        @TransferConfig("modify.old-limit")
        int limit = 8;

        @GlobalConfig("misc.server-mod-name")
        String name = "SnowBall";
    }

    private Path configPath(String name) {
        return directory.resolve("config").resolve(name);
    }

    private GlobalConfigFiles load() throws IOException, InvalidConfigurationException {
        return GlobalConfigFiles.load(configPath(GlobalConfigFiles.LEAVES_FILE), configPath(GlobalConfigFiles.SNOWBALL_FILE));
    }
}
