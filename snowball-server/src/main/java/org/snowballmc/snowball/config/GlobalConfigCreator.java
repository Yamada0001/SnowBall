package org.snowballmc.snowball.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.experimental.ExperimentalConfig;

import java.io.IOException;
import java.nio.file.Path;

public final class GlobalConfigCreator {
    private GlobalConfigCreator() {
    }

    public static void main(String[] args) throws IOException {
        create(Path.of("config"));
    }

    static void create(Path directory) throws IOException {
        Path leaves = directory.resolve(GlobalConfigFiles.LEAVES_FILE);
        Path snowball = directory.resolve(GlobalConfigFiles.SNOWBALL_FILE);
        // Do not pick up values from an earlier template generation.
        GlobalConfigFiles files = GlobalConfigFiles.empty(leaves, snowball);
        for (VerifiedConfig entry : GlobalConfigManager.entries(files)) {
            YamlConfiguration yaml = entry.configuration();
            yaml.set(entry.path(), entry.validator().saveConvert(entry.get()));
            GlobalConfigFiles.addDefaultComments(yaml, entry.path(), entry.field().getAnnotation(GlobalConfig.class));
        }
        files.leaves().set("settings.protocol.xaero-map-server-id", 0);
        files.save();
        ExperimentalConfig.createIfMissing(directory.resolve(ExperimentalConfig.FILE));
    }
}
