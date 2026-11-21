package org.snowballmc.snowball.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.snowballmc.snowball.LeavesConfig;
import org.snowballmc.snowball.LeavesLogger;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.config.annotations.GlobalConfigCategory;
import org.snowballmc.snowball.config.annotations.TransferConfig;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GlobalConfigManager {
    public static final String CONFIG_START = "settings.";

    private static Map<String, VerifiedConfig> verifiedConfigs = Map.of();
    private static final Map<String, Object> defaults = new LinkedHashMap<>();

    private GlobalConfigManager() {
    }

    public static void init(GlobalConfigFiles files) throws IOException {
        load(files, false);
    }

    public static void reload(GlobalConfigFiles files) throws IOException {
        load(files, true);
    }

    private static void load(GlobalConfigFiles files, boolean reload) throws IOException {
        load(files, entries(files), reload);
    }

    static void load(GlobalConfigFiles files, List<VerifiedConfig> entries, boolean reload) throws IOException {
        files.migrate(entries.stream().filter(VerifiedConfig::snowball).map(VerifiedConfig::path).toList());
        Map<String, VerifiedConfig> verified = new LinkedHashMap<>();
        if (!reload) {
            defaults.clear();
            for (VerifiedConfig entry : entries) {
                defaults.put(entry.path(), entry.validator().saveConvert(entry.get()));
            }
        }
        for (VerifiedConfig entry : entries) {
            loadValue(entry, files.leaves(), defaults.get(entry.path()), reload);
            verified.put(entry.path().substring(CONFIG_START.length()), entry);
        }
        prune(files.leaves(), entries, false);
        prune(files.snowball(), entries, true);
        for (VerifiedConfig entry : entries) {
            if (!reload || !entry.lock()) {
                entry.validator().runAfterLoader(entry.get(), reload);
            }
        }
        files.save();
        verifiedConfigs = Collections.unmodifiableMap(verified);
    }

    private static void loadValue(VerifiedConfig entry, YamlConfiguration legacy, Object defaultValue, boolean reload) {
        String path = entry.path();
        YamlConfiguration yaml = entry.configuration();
        boolean apply = !reload || !entry.lock();
        if (apply && !yaml.contains(path, true)) {
            for (TransferConfig transfer : entry.field().getAnnotationsByType(TransferConfig.class)) {
                if (VerifiedTransferConfig.build(transfer, entry.field(), entry.upstreamField()).run(legacy, yaml, path, entry.validator())) {
                    break;
                }
            }
        }
        if (!yaml.contains(path, true)) {
            yaml.set(path, defaultValue);
        }
        if (apply) {
            try {
                Object value = entry.validator().loadConvert(yaml.get(path));
                entry.validator().verify(null, value);
                entry.field().set(entry.upstreamField(), value);
            } catch (IllegalArgumentException | ClassCastException e) {
                yaml.set(path, defaultValue);
                try {
                    entry.field().set(entry.upstreamField(), entry.validator().loadConvert(defaultValue));
                } catch (IllegalAccessException impossible) {
                    throw new IllegalStateException("Cannot reset " + path, impossible);
                }
                LeavesLogger.LOGGER.warn("{}, reset to {}", e.getMessage(), defaultValue);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot load " + path, e);
            }
        }
        GlobalConfigFiles.addDefaultComments(yaml, path, entry.field().getAnnotation(GlobalConfig.class));
    }

    static List<VerifiedConfig> entries(GlobalConfigFiles files) {
        List<VerifiedConfig> entries = new ArrayList<>();
        collectEntries(LeavesConfig.class, null, CONFIG_START, files, entries);
        return entries;
    }

    private static void collectEntries(Class<?> categoryType, Object parent, String prefix, GlobalConfigFiles files, List<VerifiedConfig> entries) {
        for (Field field : categoryType.getDeclaredFields()) {
            if (parent == null && !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            GlobalConfig config = field.getAnnotation(GlobalConfig.class);
            GlobalConfigCategory category = field.getType().getAnnotation(GlobalConfigCategory.class);
            if (config == null && category == null) {
                continue;
            }
            field.setAccessible(true);
            if (config != null) {
                entries.add(VerifiedConfig.build(config, field, parent, prefix, files));
            } else {
                try {
                    collectEntries(field.getType(), field.get(parent), prefix + category.value() + ".", files, entries);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot load configuration category " + prefix + category.value(), e);
                }
            }
        }
    }

    private static void prune(YamlConfiguration yaml, List<VerifiedConfig> entries, boolean snowball) {
        Set<String> known = new LinkedHashSet<>();
        for (VerifiedConfig entry : entries) {
            if (entry.snowball() != snowball) {
                continue;
            }
            String path = entry.path();
            known.add(path);
            while (path.contains(".")) {
                path = path.substring(0, path.lastIndexOf('.'));
                known.add(path);
            }
        }
        for (String path : yaml.getKeys(true)) {
            if (path.startsWith(CONFIG_START) && !known.contains(path)) {
                yaml.set(path, null);
            }
        }
    }

    public static VerifiedConfig getVerifiedConfig(String path) {
        return verifiedConfigs.get(path);
    }

    @NotNull
    public static Set<String> getVerifiedConfigSubPaths(@NotNull String path) {
        String prefix = path.substring(0, path.lastIndexOf('.') + 1);
        Set<String> children = new LinkedHashSet<>();
        for (String key : verifiedConfigs.keySet()) {
            if (key.startsWith(prefix)) {
                String suffix = key.substring(prefix.length());
                children.add(suffix.substring(0, suffix.indexOf('.') < 0 ? suffix.length() : suffix.indexOf('.')));
            }
        }
        return children;
    }
}
