package org.snowballmc.snowball.config.api;

import java.util.List;

public interface ConfigValidator<E> extends ConfigConverter<E> {
    E stringConvert(String value) throws IllegalArgumentException;

    /** Validate a candidate without changing live state. The old value is null during file loads. */
    default void verify(E old, E value) throws IllegalArgumentException {
    }

    /** Apply runtime side effects only after persistence and publication. Not called for pending restart values. */
    default void onApplied(E old, E value) {
    }

    default List<String> valueSuggest() {
        return List.of("<value>");
    }

    default void runAfterLoader(E value, boolean reload) {
    }
}
