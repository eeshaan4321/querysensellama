package com.querysense.backend.dialect;

import java.util.Locale;

/** The database dialects QuerySense generates and analyses queries for. */
public enum Dialect {
    MYSQL("MySQL"),
    MONGODB("MongoDB");

    private final String label;

    Dialect(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Dialect from(String databaseType) {
        if (databaseType != null) {
            String wanted = databaseType.trim().toUpperCase(Locale.ROOT);
            for (Dialect d : values()) {
                if (d.name().equals(wanted)) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported databaseType '" + databaseType
                + "'. Use MySQL or MongoDB.");
    }
}
