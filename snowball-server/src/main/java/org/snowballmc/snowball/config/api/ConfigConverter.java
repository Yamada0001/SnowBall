package org.snowballmc.snowball.config.api;

public interface ConfigConverter<E> {
    @SuppressWarnings("unchecked")
    default E loadConvert(Object value) throws IllegalArgumentException {
        try {
            return (E) value;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(e);
        }
    }

    default Object saveConvert(E value) {
        return value;
    }
}
