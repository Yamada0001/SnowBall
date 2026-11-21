package org.snowballmc.snowball.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.LeavesLogger;
import org.snowballmc.snowball.config.annotations.GlobalConfig;
import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.AutoConfigValidator;

import java.io.IOException;
import java.lang.reflect.Field;

public record VerifiedConfig(ConfigValidator<? super Object> validator, boolean lock, Field field, Object upstreamField, String path, GlobalConfigFiles files, boolean snowball) {

    YamlConfiguration configuration() {
        return files.owner(snowball);
    }

    public void set(String stringValue) throws IllegalArgumentException {
        Object value;
        try {
            value = validator.stringConvert(stringValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("value parse error: " + e.getMessage());
        }

        validator.verify(this.get(), value);

        try {
            YamlConfiguration yaml = configuration();
            Object previous = yaml.get(path);
            yaml.set(path, validator.saveConvert(value));
            try {
                files.save(snowball);
            } catch (IOException e) {
                yaml.set(path, previous);
                LeavesLogger.LOGGER.error("Unable to save configuration {}", path, e);
                throw new IllegalArgumentException("Unable to save " + path, e);
            }
            if (lock) {
                throw new IllegalArgumentException("locked, will load after restart");
            }
            field.set(upstreamField, value);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    public Object get() {
        try {
            return field.get(upstreamField);
        } catch (IllegalAccessException e) {
            LeavesLogger.LOGGER.error("Failure to get {} value", path, e);
            return "<VALUE ERROR>";
        }
    }

    public String getString() {
        Object value = this.get();

        Object savedValue = configuration().get(path);
        try {
            if (savedValue != null) {
                savedValue = validator.loadConvert(savedValue);
                if (!savedValue.equals(value)) {
                    return value.toString() + "(" + savedValue + " after restart)";
                }
            }
        } catch (IllegalArgumentException e) {
            LeavesLogger.LOGGER.error("Failure to get {} value", path, e);
        }
        return value.toString();
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
