package com.querysense.backend.schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A complete schema: its tables, foreign keys and database. */
public record SchemaSnapshot(
        String id,
        String label,
        String catalog,
        List<KnownSchema.Table> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        Map<String, Integer> rowCounts,
        Instant loadedAt) {

    public static final String BUILT_IN_ID = "builtin";

    public boolean builtIn() {
        return BUILT_IN_ID.equals(id);
    }

    public List<String> tableNames() {
        return tables.stream().map(KnownSchema.Table::name).toList();
    }

    public int rowCountFor(String tableName) {
        return rowCounts.getOrDefault(tableName, 0);
    }
}
