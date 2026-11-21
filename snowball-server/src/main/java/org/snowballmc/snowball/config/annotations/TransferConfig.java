package org.snowballmc.snowball.config.annotations;

import org.snowballmc.snowball.config.api.ConfigTransformer;
import org.snowballmc.snowball.config.api.impl.AutoConfigTransformer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(TransferConfig.Array.class)
public @interface TransferConfig {
    String value();

    Class<? extends ConfigTransformer<?, ?>> transformer() default AutoConfigTransformer.class;

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Array {
        TransferConfig[] value();
    }
}
