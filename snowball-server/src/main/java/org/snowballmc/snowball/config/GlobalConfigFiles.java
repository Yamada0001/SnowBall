package org.snowballmc.snowball.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.util.LeavesUpdateHelper;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public record GlobalConfigFiles(Path leavesPath, Path snowballPath, YamlConfiguration leaves, YamlConfiguration snowball) {
    public static final String LEAVES_FILE = "leaves.yml";
    public static final String SNOWBALL_FILE = "SnowBall.yml";
    private static final String REMOVED_WEB_PATH = "settings.misc.config-web";
    private static final String VERSION_COMMENT = "SnowBall 基础配置文件版本号。请勿修改！";
    private static final String UPDATE_SOURCE = "settings.misc.auto-update.download-source";
    private static final String OLD_SOURCE_COMMENT = "指定自动更新下载源。 application GitHub Releases rainyun 雨云提供的镜像下载源";

    public static GlobalConfigFiles load(Path leavesPath, Path snowballPath) throws IOException, InvalidConfigurationException {
        if (leavesPath.toAbsolutePath().normalize().equals(snowballPath.toAbsolutePath().normalize())) {
            throw new IOException("Leaves and SnowBall configurations must be separate files");
        }
        YamlConfiguration leaves = read(leavesPath, LeavesConfig.CONFIG_HEADER);
        YamlConfiguration snowball = read(snowballPath, "Configuration file for SnowBall.");
        return withVersion(leavesPath, snowballPath, leaves, snowball);
    }

    static GlobalConfigFiles empty(Path leavesPath, Path snowballPath) {
        return withVersion(leavesPath, snowballPath, newYaml(LeavesConfig.CONFIG_HEADER), newYaml("Configuration file for SnowBall."));
    }

    private static GlobalConfigFiles withVersion(Path leavesPath, Path snowballPath, YamlConfiguration leaves, YamlConfiguration snowball) {
        leaves.set("config-version", LeavesConfig.CURRENT_CONFIG_VERSION);
        addDefaultComments(leaves, "config-version", List.of(VERSION_COMMENT));
        return new GlobalConfigFiles(leavesPath, snowballPath, leaves, snowball);
    }

    private static YamlConfiguration newYaml(String header) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.options().setHeader(List.of(header));
        return yaml;
    }

    public static YamlConfiguration read(Path path, String header) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = newYaml(header);
        Reader reader;
        try {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException ignored) {
            return yaml;
        }
        try (reader) {
            yaml.load(reader);
        }
        return yaml;
    }

    /** Also used before the region-format migration, which runs before server initialization. */
    public static File prepareLeavesFile(File requested) throws IOException, InvalidConfigurationException {
        return prepareLeavesFile(requested.toPath(), Path.of(LEAVES_FILE), Path.of("config", LEAVES_FILE)).toFile();
    }

    static Path prepareLeavesFile(Path requested, Path legacy, Path destination) throws IOException, InvalidConfigurationException {
        if (!requested.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize()) || !Files.exists(legacy)) {
            return requested;
        }
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (!Files.exists(destination)) {
            // Relocate the original bytes unchanged; the subsequent split backs up this file before rewriting it.
            Files.move(legacy, destination);
        } else {
            YamlConfiguration target = read(destination, LeavesConfig.CONFIG_HEADER);
            YamlConfiguration source = read(legacy, LeavesConfig.CONFIG_HEADER);
            mergeMissing(target, source, "");
            target.options().setHeader(mergeComments(target.options().getHeader(), source.options().getHeader()));
            target.options().setFooter(mergeComments(target.options().getFooter(), source.options().getFooter()));
            saveAtomically(destination, target);
            // Keep the old file as a backup, but stop treating it as a migration source on every boot.
            backUp(legacy);
            Files.delete(legacy);
        }
        return requested;
    }

    private static void mergeMissing(YamlConfiguration target, YamlConfiguration source, String prefix) {
        ConfigurationSection section = prefix.isEmpty() ? source : source.getConfigurationSection(prefix);
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (source.isConfigurationSection(path)) {
                if (!target.contains(path, true)) {
                    target.createSection(path);
                }
                if (!target.isConfigurationSection(path)) {
                    continue;
                }
                mergeMissing(target, source, path);
            } else {
                if (!target.contains(path, true)) {
                    target.set(path, source.get(path));
                }
                if (target.isConfigurationSection(path)) {
                    continue;
                }
            }
            copyComments(source, target, path);
        }
    }

    public YamlConfiguration owner(boolean snowballKey) {
        return snowballKey ? snowball : leaves;
    }

    void saveKey(boolean snowballKey, String path, Object value, GlobalConfig config) throws IOException, InvalidConfigurationException {
        Path file = snowballKey ? snowballPath : leavesPath;
        YamlConfiguration current = read(file, snowballKey ? "Configuration file for SnowBall." : LeavesConfig.CONFIG_HEADER);
        for (int separator = path.indexOf('.'); separator >= 0; separator = path.indexOf('.', separator + 1)) {
            String parent = path.substring(0, separator);
            if (current.contains(parent, true) && !current.isConfigurationSection(parent)) {
                throw new InvalidConfigurationException("Cannot update " + path + ": " + parent + " is not a section");
            }
        }
        current.set(path, value);
        addDefaultComments(current, path, config);
        saveAtomically(file, current);

        // Unrelated on-disk edits remain pending until reload; publish only the committed key.
        owner(snowballKey).set(path, value);
    }

    public void migrate(List<String> snowballKeys) throws IOException {
        migrate(snowballKeys, List.of());
    }

    void migrate(List<String> snowballKeys, List<String> transferPaths) throws IOException {
        boolean migrating = leaves.contains(REMOVED_WEB_PATH, true)
            || (leaves.contains(UPDATE_SOURCE, true) && !LeavesUpdateHelper.DOWNLOAD_REPOSITORY.equals(leaves.get(UPDATE_SOURCE)))
            || leaves.getComments(UPDATE_SOURCE).contains(OLD_SOURCE_COMMENT)
            || snowballKeys.stream().anyMatch(path -> leaves.contains(path, true))
            // A legacy alias can be removed by either a transfer or pruning.
            || transferPaths.stream().anyMatch(path -> leaves.contains(path, true));
        if (migrating && !Files.notExists(leavesPath)) {
            backUp(leavesPath);
        }
        for (String path : snowballKeys) {
            moveSnowballKey(path);
        }
        leaves.set(REMOVED_WEB_PATH, null);
        snowball.set(REMOVED_WEB_PATH, null);
        if (leaves.contains(UPDATE_SOURCE, true)) {
            // Normalize the retired sources on reload as well (the setting is startup-only).
            leaves.set(UPDATE_SOURCE, LeavesUpdateHelper.DOWNLOAD_REPOSITORY);
            List<String> comments = new ArrayList<>(leaves.getComments(UPDATE_SOURCE));
            if (comments.removeIf(OLD_SOURCE_COMMENT::equals)) {
                leaves.setComments(UPDATE_SOURCE, comments);
            }
        }
    }

    private void moveSnowballKey(String path) {
        if (!leaves.contains(path, true)) {
            return;
        }
        if (!snowball.contains(path, true)) {
            snowball.set(path, leaves.get(path));
        }
        copyComments(leaves, snowball, path);
        leaves.set(path, null);

        // Copy shared headings; drop headings from the old file only when the section is empty.
        for (int separator = path.lastIndexOf('.'); separator >= 0; separator = path.lastIndexOf('.', separator - 1)) {
            String parent = path.substring(0, separator);
            copyComments(leaves, snowball, parent);
            ConfigurationSection section = leaves.getConfigurationSection(parent);
            if (section != null && section.getKeys(false).isEmpty()) {
                leaves.set(parent, null);
            }
        }
    }

    static void addDefaultComments(YamlConfiguration yaml, String path, GlobalConfig config) {
        addDefaultComments(yaml, path, List.of(config.comment()));
    }

    private static void addDefaultComments(YamlConfiguration yaml, String path, List<String> comments) {
        if (!comments.isEmpty() && yaml.getComments(path).isEmpty()) {
            yaml.setComments(path, comments);
        }
    }

    private static void copyComments(YamlConfiguration source, YamlConfiguration target, String path) {
        target.setComments(path, mergeComments(target.getComments(path), source.getComments(path)));
        target.setInlineComments(path, mergeComments(target.getInlineComments(path), source.getInlineComments(path)));
    }

    private static List<String> mergeComments(List<String> preferred, List<String> incoming) {
        if (preferred.isEmpty()) {
            return incoming;
        }
        if (incoming.isEmpty() || preferred.equals(incoming)) {
            return preferred;
        }
        List<String> comments = new ArrayList<>(preferred);
        for (String line : incoming) {
            if (!preferred.contains(line)) {
                comments.add(line);
            }
        }
        return comments;
    }

    public void save() throws IOException {
        // Until SnowBall has been persisted, do not remove the keys from the old on-disk file.
        save(true);
        save(false);
    }

    public void save(boolean snowballKey) throws IOException {
        saveAtomically(snowballKey ? snowballPath : leavesPath, owner(snowballKey));
    }

    private static void backUp(Path source) throws IOException {
        Path backup = Files.createTempFile(source.toAbsolutePath().getParent(), source.getFileName() + ".migration-", ".bak");
        try {
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static void saveAtomically(Path path, YamlConfiguration yaml) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
