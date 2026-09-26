package com.querysense.backend.dialect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One implementation per dialect, checked complete when built. */
public final class DialectRegistry<T extends DialectAware> {

    private final Map<Dialect, T> byDialect = new EnumMap<>(Dialect.class);

    public DialectRegistry(List<T> implementations, String role) {
        for (T impl : implementations) {
            if (byDialect.putIfAbsent(impl.dialect(), impl) != null) {
                throw new IllegalStateException("Two " + role + " implementations for " + impl.dialect());
            }
        }
        for (Dialect d : Dialect.values()) {
            if (!byDialect.containsKey(d)) {
                throw new IllegalStateException("No " + role + " implementation for " + d);
            }
        }
    }

    public T get(Dialect dialect) {
        return byDialect.get(dialect);
    }
}
