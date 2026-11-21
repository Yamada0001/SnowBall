package org.snowballmc.snowball.config.annotations;

import org.snowballmc.snowball.config.api.ConfigValidator;
import org.snowballmc.snowball.config.api.impl.AutoConfigValidator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GlobalConfig {
    String value();

    boolean lock() default false;

    boolean snowball() default false;

    /**
     * 写入 leaves.yml 的注释，按数组顺序逐行输出在该配置项上方。
     *
     * <p>留空表示不写注释。</p>
     */
    String[] comment() default {};

    Class<? extends ConfigValidator<?>> validator() default AutoConfigValidator.class;
}
