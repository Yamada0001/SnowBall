package org.snowballmc.snowball.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.LeavesLogger;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.AutoConfigValidator;

import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Objects;

public record VerifiedConfig(ConfigValidator<? super Object> validator, boolean lock, Field field, Object upstreamField, String path, GlobalConfigFiles files, boolean snowball) {

    YamlConfiguration configuration() {
        return files.owner(snowball);
    }

    public void set(String stringValue) throws IllegalArgumentException {
        synchronized (GlobalConfigManager.class) {
            Object value;
            try {
                value = checkedValue(validator.stringConvert(stringValue));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("value parse error: " + e.getMessage(), e);
            }

            Object old = get();
            validator.verify(old, value);
            try {
                files.saveKey(snowball, path, validator.saveConvert(value), field.getAnnotation(GlobalConfig.class));
            } catch (IOException | InvalidConfigurationException e) {
                LeavesLogger.LOGGER.error("Unable to save configuration {}", path, e);
                throw new IllegalArgumentException("Unable to save " + path, e);
            }
            if (!lock) {
                applyValue(value);
                validator.onApplied(old, value);
            }
        }
    }

    Object checkedValue(Object value) {
        Class<?> type = field.getType();
        if (type.isPrimitive() ? !MethodType.methodType(type).wrap().returnType().isInstance(value)
            : value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("expected " + type.getSimpleName());
        }
        return value;
    }

    void applyValue(Object value) {
        try {
            field.set(upstreamField, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot apply " + path, e);
        }
    }

    public Object get() {
        synchronized (GlobalConfigManager.class) {
            try {
                return field.get(upstreamField);
            } catch (IllegalAccessException e) {
                LeavesLogger.LOGGER.error("Failure to get {} value", path, e);
                return "<VALUE ERROR>";
            }
        }
    }

    public String getString() {
        synchronized (GlobalConfigManager.class) {
            Object value = get();
            Object savedValue = configuration().get(path);
            try {
                if (lock && savedValue != null) {
                    savedValue = validator.loadConvert(savedValue);
                    if (!Objects.equals(savedValue, value)) {
                        return value + "(" + savedValue + " after restart)";
                    }
                }
            } catch (IllegalArgumentException | ClassCastException e) {
                LeavesLogger.LOGGER.error("Failure to get {} value", path, e);
            }
            return String.valueOf(value);
        }
    }

    @NotNull
    @Contract("_, _, _, _, _ -> new")
    public static VerifiedConfig build(@NotNull GlobalConfig config, @NotNull Field field, @Nullable Object upstreamField, @NotNull String upstreamPath, GlobalConfigFiles files) {
        String path = upstreamPath + config.value();

        ConfigValidator<? super Object> validator;
        try {
            validator = createValidator(config.validator(), field);
        } catch (Exception e) {
            LeavesLogger.LOGGER.error("Failure to load leaves config {}", path, e);
            throw new RuntimeException();
        }

        return new VerifiedConfig(validator, config.lock(), field, upstreamField, path, files, config.snowball());
    }

    @SuppressWarnings("unchecked")
    private static ConfigValidator<? super Object> createValidator(@NotNull Class<? extends ConfigValidator<?>> clazz, Field field) throws Exception {
        if (clazz.equals(AutoConfigValidator.class)) {
            return (ConfigValidator<? super Object>) AutoConfigValidator.createValidator(field);
        } else {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ConfigValidator<? super Object>) constructor.newInstance();
        }
    }
}
