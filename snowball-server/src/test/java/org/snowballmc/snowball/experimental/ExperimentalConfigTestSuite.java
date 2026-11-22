package org.snowballmc.snowball.experimental;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentalConfigTestSuite {
    @TempDir Path directory;

    @Test
    void bundledTemplateOwnsExactlyTheVerifiedSettingsAndDoesNotOverwrite() throws Exception {
        Path path = directory.resolve(ExperimentalConfig.FILE);
        ExperimentalConfig.createIfMissing(path);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path));
        assertEquals(22, ExperimentalConfig.keys().size());
        assertEquals(ExperimentalConfig.keys(), yaml.getKeys(true).stream()
            .filter(key -> !yaml.isConfigurationSection(key)).collect(Collectors.toSet()));
        var warnings = new ArrayList<String>();
        assertEquals(ExperimentalConfig.parse(new YamlConfiguration(), 16, false, warnings::add),
            ExperimentalConfig.parse(yaml, 16, false, warnings::add));
        assertTrue(warnings.isEmpty());
        String custom = Files.readString(path).replace("thread-count: 4", "thread-count: 6");
        Files.writeString(path, custom);
        ExperimentalConfig.createIfMissing(path);
        assertEquals(custom, Files.readString(path));
    }

    @Test
    void calculatesThreadsAndClampsExactlyTheDocumentedOptions() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("async.parallel-sensors.enable", true);
        yaml.set("async.parallel-sensors.max-threads", -2);
        yaml.set("network.raytrace-entity-culling.enabled", true);
        yaml.set("network.raytrace-entity-culling.check-interval-ms", 1);
        yaml.set("network.raytrace-entity-culling.max-trace-distance", 256);
        yaml.set("network.raytrace-entity-culling.skipped-entity-types", List.of("Item_Frame", "minecraft:villager"));
        var config = ExperimentalConfig.parse(yaml, 32, false, reason -> fail(reason));
        assertTrue(config.sensors().enabled());
        assertEquals(30, config.sensors().maxThreads());
        assertTrue(config.culling().enabled());
        assertEquals(4, config.culling().threads());
        assertEquals(10, config.culling().checkIntervalMs());
        assertEquals(160, config.culling().maxTraceDistance());
        assertEquals(List.of("minecraft:item_frame", "minecraft:villager"), config.culling().skippedEntityTypes());
    }

    @Test
    void refusesUnsafeValuesWithoutDisablingOtherModules() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("async.parallel-world-ticking.enable", true);
        yaml.set("async.parallel-world-ticking.disable-hard-throw", true);
        yaml.set("async.parallel-sensors.enable", true);
        yaml.set("network.raytrace-entity-culling.enabled", true);
        yaml.set("network.raytrace-entity-culling.aabb-expansion", Double.NaN);
        var warnings = new ArrayList<String>();
        var config = ExperimentalConfig.parse(yaml, 4, false, warnings::add);
        assertFalse(config.world().enabled());
        assertTrue(config.sensors().enabled());
        assertFalse(config.culling().enabled());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("disable-hard-throw")));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("aabb-expansion")));
    }

    @Test
    void unsupportedAffinityNeverClaimsBinding() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("async.parallel-sensors.enable", true);
        yaml.set("async.guru-mode-enable", true);
        yaml.set("async.guru-mode.parallel-sensors-affinity", List.of(1));
        var warnings = new ArrayList<String>();
        var config = ExperimentalConfig.parse(yaml, 4, true, warnings::add);
        assertFalse(config.sensors().enabled());
        assertTrue(config.sensors().affinity().isEmpty());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("not supported")));
    }

    @Test
    void commandWritePreservesCommentsAndReloadDoesNotApplyPendingValues() throws Exception {
        Path file = directory.resolve(ExperimentalConfig.FILE);
        try {
            ExperimentalConfig.load(file, false);
            String before = Files.readString(file);
            assertFalse(ExperimentalConfig.current().sensors().enabled());
            assertThrows(IllegalArgumentException.class, () -> ExperimentalConfig.saveSetting(file, "async.parallel-sensors.enable", "maybe"));
            assertEquals(before, Files.readString(file));
            ExperimentalConfig.saveSetting(file, "async.parallel-sensors.enable", "true");
            ExperimentalConfig.load(file, true);
            assertTrue(Files.readString(file).contains("在实体 tick 前"));
            assertFalse(ExperimentalConfig.current().sensors().enabled());
            assertEquals("false (true after restart)", ExperimentalConfig.describe("async.parallel-sensors.enable"));
            ExperimentalConfig.load(file, false);
            assertTrue(ExperimentalConfig.current().sensors().enabled());
            Files.writeString(file, "async: [\n");
            ExperimentalConfig.load(file, true);
            assertTrue(ExperimentalConfig.current().sensors().enabled());
            assertEquals("async: [\n", Files.readString(file));
        } finally {
            ExperimentalConfig.load(directory.resolve("defaults.yml"), false);
        }
    }
}
