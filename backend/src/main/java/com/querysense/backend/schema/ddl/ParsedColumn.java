package com.querysense.backend.schema.ddl;

import java.util.List;
import java.util.Locale;

/** A column declared in uploaded DDL. */
public record ParsedColumn(
        String name,
        String type,
        List<String> typeArgs,
        boolean notNull,
        boolean autoIncrement,
        boolean unique,
        boolean primaryKey) {

    public String normalisedType() {
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }

    public int firstArgOr(int fallback) {
        if (typeArgs.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(typeArgs.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
