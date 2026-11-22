package org.snowballmc.snowball.experimental;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.snowballmc.snowball.LeavesLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Independent, restart-only configuration. Workers see only a published immutable snapshot. */
public final class ExperimentalConfig {
    public static final String FILE = "Experimental-SB.yml";
    private static final String WORLD = "async.parallel-world-ticking.";
    private static final String SENSORS = "async.parallel-sensors.";
    private static final String CULLING = "network.raytrace-entity-culling.";
    private static final String AUTO = "async.auto-thread-allocation";
    private static final String GURU = "async.guru-mode-enable";
    private static final String AFFINITY = "async.guru-mode.parallel-sensors-affinity";
    private static final int MAX_WORKERS = 256;
    private static final Map<String, Object> DEFAULTS = defaults();
    private static volatile Settings current = parse(new YamlConfiguration(), Runtime.getRuntime().availableProcessors(), false, ignored -> {});
    private static Settings diskSettings = current;
    private static Map<String, Object> diskValues = DEFAULTS;

    private ExperimentalConfig() {}

    private static Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(AUTO, false);
        result.put(GURU, false);
        result.put(AFFINITY, List.of());
        result.put(WORLD + "enable", false);
        result.put(WORLD + "thread-count", 4);
        result.put(WORLD + "log-container-creation-stacktraces", false);
        result.put(WORLD + "disable-hard-throw", false);
        result.put(WORLD + "use-per-world-tps-bar", true);
        result.put(WORLD + "show-tps-of-server-instead-of-world", true);
        result.put(SENSORS + "enable", false);
        result.put(SENSORS + "max-threads", 0);
        result.put(CULLING + "enabled", false);
        result.put(CULLING + "threads", 0);
        result.put(CULLING + "check-interval-ms", 50);
        result.put(CULLING + "max-trace-distance", 64);
        result.put(CULLING + "force-visible-radius", 4.0);
        result.put(CULLING + "visibility-timeout-ms", 1000L);
        result.put(CULLING + "aabb-expansion", 0.5);
        result.put(CULLING + "bounding-box-limit", 20);
        result.put(CULLING + "cull-players", true);
        result.put(CULLING + "skip-marker-armor-stands", true);
        result.put(CULLING + "skipped-entity-types", List.of());
        return Collections.unmodifiableMap(result);
    }

    public record World(boolean requested, boolean enabled, int threadCount, boolean logContainerCreationStacktraces,
                        boolean usePerWorldTpsBar, boolean showTpsOfServerInsteadOfWorld) {}
    public record Sensors(boolean enabled, int maxThreads, List<Integer> affinity) {}
    public record Culling(boolean enabled, int threads, int checkIntervalMs, int maxTraceDistance,
                          double forceVisibleRadius, long visibilityTimeoutMs, double aabbExpansion,
                          int boundingBoxLimit, boolean cullPlayers, boolean skipMarkerArmorStands,
                          List<String> skippedEntityTypes) {}
    public record Settings(World world, Sensors sensors, Culling culling) {}

    public static Settings current() {
        return current;
    }

    public static synchronized void load(Path path, boolean reload) {
        try {
            createIfMissing(path);
            YamlConfiguration yaml = read(path);
            Settings parsed = parse(yaml, Runtime.getRuntime().availableProcessors(), Boolean.getBoolean("divinemc.async.guru"),
                ExperimentalConfig::knownEntityType, message -> LeavesLogger.LOGGER.warn("{}: {}", path, message));
            Map<String, Object> values = values(yaml);
            diskValues = values;
            diskSettings = parsed;
            if (reload) {
                if (!parsed.equals(current)) {
                    LeavesLogger.LOGGER.info("Changes to {} take effect after restart", path);
                }
                return;
            }
            current = parsed;
            if (parsed.world().enabled()) {
                LeavesLogger.LOGGER.warn("{}: experimental parallel world ticking requested. Only eligible worlds use workers; all other ticks remain serial.", path);
            }
            LeavesLogger.LOGGER.info("{}: world ticking requested={}, config accepted={}, configured workers={}; sensors requested={}, enabled={}, threads={}; culling requested={}, enabled={}, threads={}", path,
                values.get(WORLD + "enable"), parsed.world().enabled(), parsed.world().threadCount(),
                values.get(SENSORS + "enable"), parsed.sensors().enabled(), parsed.sensors().maxThreads(),
                values.get(CULLING + "enabled"), parsed.culling().enabled(), parsed.culling().threads());
        } catch (IOException | InvalidConfigurationException e) {
            LeavesLogger.LOGGER.warn("Cannot read {}; {}: {}", path, reload ? "keeping startup settings" : "experimental features remain disabled", e.getMessage());
            if (!reload) {
                diskValues = DEFAULTS;
                current = parse(new YamlConfiguration(), Runtime.getRuntime().availableProcessors(), false, ignored -> {});
                diskSettings = current;
            }
        }
    }

    public static void createIfMissing(Path path) throws IOException {
        if (Files.exists(path)) return;
        try (InputStream stream = ExperimentalConfig.class.getResourceAsStream("/snowball-config/" + FILE)) {
            if (stream == null) throw new IOException("Missing packaged template " + FILE);
            writeAtomically(path, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static YamlConfiguration read(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        return yaml;
    }

    private static Map<String, Object> values(YamlConfiguration yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        DEFAULTS.forEach((key, fallback) -> result.put(key, yaml.get(key, fallback)));
        return Collections.unmodifiableMap(result);
    }

    public static java.util.Set<String> keys() {
        return DEFAULTS.keySet();
    }

    public static List<String> valueSuggestions(String key) {
        return DEFAULTS.get(key) instanceof Boolean ? List.of("true", "false") : List.of();
    }

    public static synchronized String describe(String key) {
        if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("Unknown experimental setting: " + key);
        Object active = effectiveValue(current, key);
        Object next = effectiveValue(diskSettings, key);
        Object saved = diskValues.get(key);
        String result = String.valueOf(active);
        if (!java.util.Objects.equals(active, next)) result += " (" + next + " after restart)";
        if (!sameValue(next, saved)) result += " [saved: " + saved + "]";
        return result;
    }

    private static boolean sameValue(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b) {
            try {
                return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
            } catch (NumberFormatException ignored) {
                // A non-finite raw YAML value should still be shown separately.
            }
        }
        return java.util.Objects.equals(first, second);
    }

    private static Object effectiveValue(Settings settings, String key) {
        return switch (key) {
            case AUTO, GURU, WORLD + "disable-hard-throw" -> false; // unsupported or reserved
            case AFFINITY -> settings.sensors().affinity();
            case WORLD + "enable" -> settings.world().enabled();
            case WORLD + "thread-count" -> settings.world().threadCount();
            case WORLD + "log-container-creation-stacktraces" -> settings.world().logContainerCreationStacktraces();
            case WORLD + "use-per-world-tps-bar" -> settings.world().usePerWorldTpsBar();
            case WORLD + "show-tps-of-server-instead-of-world" -> settings.world().showTpsOfServerInsteadOfWorld();
            case SENSORS + "enable" -> settings.sensors().enabled();
            case SENSORS + "max-threads" -> settings.sensors().maxThreads();
            case CULLING + "enabled" -> settings.culling().enabled();
            case CULLING + "threads" -> settings.culling().threads();
            case CULLING + "check-interval-ms" -> settings.culling().checkIntervalMs();
            case CULLING + "max-trace-distance" -> settings.culling().maxTraceDistance();
            case CULLING + "force-visible-radius" -> settings.culling().forceVisibleRadius();
            case CULLING + "visibility-timeout-ms" -> settings.culling().visibilityTimeoutMs();
            case CULLING + "aabb-expansion" -> settings.culling().aabbExpansion();
            case CULLING + "bounding-box-limit" -> settings.culling().boundingBoxLimit();
            case CULLING + "cull-players" -> settings.culling().cullPlayers();
            case CULLING + "skip-marker-armor-stands" -> settings.culling().skipMarkerArmorStands();
            case CULLING + "skipped-entity-types" -> settings.culling().skippedEntityTypes();
            default -> throw new IllegalArgumentException("Unknown experimental setting: " + key);
        };
    }

    public static synchronized void saveSetting(Path path, String key, String input) throws IOException, InvalidConfigurationException {
        Object fallback = DEFAULTS.get(key);
        if (fallback == null) throw new IllegalArgumentException("Unknown experimental setting: " + key);
        createIfMissing(path);
        YamlConfiguration yaml = read(path);
        YamlConfiguration value = new YamlConfiguration();
        // List input uses YAML flow syntax; unwrap only one value and reject sibling/section injection.
        if (input.length() > 4096 || input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Expected one short YAML value for " + key);
        }
        value.loadFromString("value: " + input);
        if (!value.getKeys(false).equals(java.util.Set.of("value")) || !value.contains("value")) {
            throw new IllegalArgumentException("Expected one YAML value for " + key);
        }
        Object candidate = value.get("value");
        List<String> violations = new java.util.ArrayList<>();
        YamlConfiguration probe = new YamlConfiguration();
        probe.set(key, candidate);
        parse(probe, Runtime.getRuntime().availableProcessors(), Boolean.getBoolean("divinemc.async.guru"), violations::add);
        if (!violations.isEmpty()) throw new IllegalArgumentException(String.join("; ", violations));
        List<String> comments = yaml.getComments(key);
        List<String> inline = yaml.getInlineComments(key);
        yaml.set(key, candidate);
        yaml.setComments(key, comments);
        yaml.setInlineComments(key, inline);
        Settings parsed = parse(yaml, Runtime.getRuntime().availableProcessors(), Boolean.getBoolean("divinemc.async.guru"),
            ExperimentalConfig::knownEntityType, message -> LeavesLogger.LOGGER.warn("{}: {}", path, message));
        writeAtomically(path, yaml.saveToString());
        diskValues = values(yaml);
        diskSettings = parsed;
    }

    private static boolean knownEntityType(String id) {
        Identifier key = Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key);
    }

    private static void writeAtomically(Path path, String contents) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".experimental-sb-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Settings parse(YamlConfiguration yaml, int processors, boolean guruProperty, Consumer<String> warn) {
        return parse(yaml, processors, guruProperty, id -> true, warn);
    }

    static Settings parse(YamlConfiguration yaml, int processors, boolean guruProperty, Predicate<String> knownEntity, Consumer<String> warn) {
        Reader world = new Reader(yaml, warn);
        boolean worldEnabled = world.bool(WORLD + "enable");
        int worldThreads = world.integer(WORLD + "thread-count", 1, MAX_WORKERS);
        boolean log = world.bool(WORLD + "log-container-creation-stacktraces");
        boolean tps = world.bool(WORLD + "use-per-world-tps-bar");
        boolean globalTps = world.bool(WORLD + "show-tps-of-server-instead-of-world");
        if (world.bool(AUTO)) world.invalid(AUTO, "requires regionized chunk ticking, which is not part of this port");
        if (world.bool(WORLD + "disable-hard-throw")) world.invalid(WORLD + "disable-hard-throw", "disabling world ownership assertions is not supported");

        Reader sensing = new Reader(yaml, warn);
        boolean sensorEnabled = sensing.bool(SENSORS + "enable");
        int sensorThreads = sensing.integer(SENSORS + "max-threads", Integer.MIN_VALUE, MAX_WORKERS);
        boolean guru = sensing.bool(GURU);
        TreeSet<Integer> cores = new TreeSet<>();
        for (Object core : sensing.list(AFFINITY)) {
            if (core instanceof Integer number && number >= 0) cores.add(number);
            else sensing.invalid(AFFINITY, "expected nonnegative CPU indices");
        }
        if (!cores.isEmpty()) {
            if (guru && guruProperty) sensing.invalid(AFFINITY, "CPU affinity is not supported by this port");
            else warn.accept(AFFINITY + ": ignored without both " + GURU + " and -Ddivinemc.async.guru=true");
        }
        sensorThreads = sensorThreads > 0 ? sensorThreads : sensorThreads == 0 ? Math.max(1, processors / 4) : Math.max(1, processors + sensorThreads);

        Reader ray = new Reader(yaml, warn);
        boolean cull = ray.bool(CULLING + "enabled");
        int cullThreads = ray.integer(CULLING + "threads", Integer.MIN_VALUE, MAX_WORKERS);
        int interval = Math.max(10, ray.integer(CULLING + "check-interval-ms", 0, Integer.MAX_VALUE));
        int distance = Math.clamp(ray.integer(CULLING + "max-trace-distance", Integer.MIN_VALUE, Integer.MAX_VALUE), 16, 160);
        double radius = ray.decimal(CULLING + "force-visible-radius");
        long visible = ray.duration(CULLING + "visibility-timeout-ms");
        double expansion = ray.decimal(CULLING + "aabb-expansion");
        int box = ray.integer(CULLING + "bounding-box-limit", 1, Integer.MAX_VALUE);
        boolean players = ray.bool(CULLING + "cull-players");
        boolean markers = ray.bool(CULLING + "skip-marker-armor-stands");
        TreeSet<String> skipped = new TreeSet<>();
        for (Object entry : ray.list(CULLING + "skipped-entity-types")) {
            if (!(entry instanceof String string)) {
                ray.invalid(CULLING + "skipped-entity-types", "expected entity identifier strings");
                continue;
            }
            String id = string.toLowerCase(Locale.ROOT);
            if (!id.contains(":")) id = "minecraft:" + id;
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                ray.invalid(CULLING + "skipped-entity-types", "invalid entity identifier: " + string);
            } else if (!knownEntity.test(id)) {
                warn.accept(CULLING + "skipped-entity-types: unknown entity type " + id + " (ignored)");
            } else {
                skipped.add(id);
            }
        }
        return new Settings(new World(worldEnabled, worldEnabled && world.valid, worldThreads, log, tps, globalTps),
            new Sensors(sensorEnabled && sensing.valid, Math.min(MAX_WORKERS, sensorThreads), List.of()),
            new Culling(cull && ray.valid, cullThreads > 0 ? cullThreads : Math.clamp(processors / 8, 1, 4), interval,
                distance, radius, visible, expansion, box, players, markers, List.copyOf(skipped)));
    }

    private static final class Reader {
        private final YamlConfiguration yaml;
        private final Consumer<String> warn;
        private boolean valid = true;

        Reader(YamlConfiguration yaml, Consumer<String> warn) { this.yaml = yaml; this.warn = warn; }

        Object get(String key) {
            for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
                String parent = key.substring(0, dot);
                if (yaml.contains(parent) && !yaml.isConfigurationSection(parent)) {
                    invalid(parent, "expected a mapping");
                    return DEFAULTS.get(key);
                }
            }
            return yaml.get(key, DEFAULTS.get(key));
        }

        void invalid(String path, String reason) { valid = false; warn.accept(path + ": " + reason); }

        boolean bool(String key) {
            if (get(key) instanceof Boolean result) return result;
            invalid(key, "expected a boolean");
            return (boolean) DEFAULTS.get(key);
        }

        int integer(String key, int min, int max) {
            if (get(key) instanceof Integer n && n >= min && n <= max) return n;
            invalid(key, "expected an integer in [" + min + ", " + max + "]");
            return (int) DEFAULTS.get(key);
        }

        long duration(String key) {
            Object value = get(key);
            if ((value instanceof Long || value instanceof Integer) && ((Number) value).longValue() >= 0) return ((Number) value).longValue();
            invalid(key, "expected a nonnegative 64-bit integer");
            return (long) DEFAULTS.get(key);
        }

        double decimal(String key) {
            Object value = get(key);
            if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0.0) return n.doubleValue();
            invalid(key, "expected a finite nonnegative number");
            return (double) DEFAULTS.get(key);
        }

        List<?> list(String key) {
            if (get(key) instanceof List<?> result) return result;
            invalid(key, "expected a list");
            return List.of();
        }
    }
}
