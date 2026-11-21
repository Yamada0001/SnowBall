package org.snowballmc.snowball.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record LeavesConfigValue(@Nullable Object value) {

    public int getInt() {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        throw new ClassCastException("Value is not an integer");
    }

    public double getDouble() {
        if (value instanceof Double) {
            return (Double) value;
        }
        throw new ClassCastException("Value is not a double");
    }

    public boolean getBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new ClassCastException("Value is not a boolean");
    }

    public @NotNull String getString() {
        if (value instanceof String) {
            return (String) value;
        }
        throw new ClassCastException("Value is not a string");
    }

    /**
     * @return the wrapped value's string representation
     * @throws NullPointerException if the wrapped value is null
     */
    @Override
    public @NotNull String toString() {
        return value.toString();
    }
}
