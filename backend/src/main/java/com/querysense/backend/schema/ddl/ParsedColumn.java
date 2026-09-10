package com.querysense.backend.schema.ddl;

import java.util.List;
import java.util.Locale;

/**
 * One column as declared in uploaded DDL.
 *
 * `type` is the bare data type as written ("VARCHAR", "INT", "DECIMAL") and
 * `typeArgs` its parenthesised arguments ("255", or "10","2" for
 * DECIMAL(10,2), or every literal of an ENUM). SchemaSeeder reads both to
 * decide what a plausible value looks like, and SchemaProvisioner reads
 * them to re-emit the column.
 */
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

    /** First type argument as an int — the length of a VARCHAR, the precision of a DECIMAL. */
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
