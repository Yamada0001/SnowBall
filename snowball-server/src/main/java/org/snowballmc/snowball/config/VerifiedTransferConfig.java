package org.snowballmc.snowball.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.snowballmc.snowball.LeavesLogger;
import org.snowballmc.snowball.config.annotations.TransferConfig;
import org.snowballmc.snowball.config.api.ConfigTransformer;
import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.AutoConfigTransformer;

import java.lang.reflect.Field;

public record VerifiedTransferConfig(ConfigTransformer<? super Object, ? super Object> transformer, Field field, Object upstreamField, String path) {

    public boolean run(YamlConfiguration source, YamlConfiguration target, String destination, ConfigValidator<? super Object> validator) {
        if (transformer == null || !source.contains(path, true)) {
            return false;
        }
        try {
            Object value = transformer.transform(transformer.loadConvert(source.get(path)));
            var comments = source.getComments(path);
            var inlineComments = source.getInlineComments(path);
            source.set(path, null);
            target.set(destination, validator.saveConvert(value));
            target.setComments(destination, comments);
            target.setInlineComments(destination, inlineComments);
            return true;
        } catch (IllegalArgumentException | ClassCastException e) {
            LeavesLogger.LOGGER.warn("Failure to load legacy configuration {}", path, e);
        } catch (ConfigTransformer.StopTransformException ignored) {
        }
        return false;
    }

    @Contract("_, _, _ -> new")
    public static @NotNull VerifiedTransferConfig build(@NotNull TransferConfig config, @NotNull Field field, @Nullable Object upstreamField) {
        String path = "settings." + config.value();

        ConfigTransformer<? super Object, ? super Object> transformer = null;
        try {
            transformer = createTransformer(config.transformer(), field);
        } catch (Exception e) {
            LeavesLogger.LOGGER.warn("Failure to load leaves config transformer for {}", path, e);
        }

        return new VerifiedTransferConfig(transformer, field, upstreamField, path);
    }

    @SuppressWarnings("unchecked")
    private static ConfigTransformer<? super Object, ? super Object> createTransformer(@NotNull Class<? extends ConfigTransformer<?, ?>> clazz, Field field) throws Exception {
        if (clazz.equals(AutoConfigTransformer.class)) {
            return (ConfigTransformer<? super Object, ? super Object>) AutoConfigTransformer.createValidator(field);
        } else {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ConfigTransformer<? super Object, ? super Object>) constructor.newInstance();
        }
    }
}
