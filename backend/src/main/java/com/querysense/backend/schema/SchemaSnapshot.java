package com.querysense.backend.schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SchemaSnapshot
 *
 * One complete description of the schema QuerySense is currently working
 * against: either the built-in nine-table demo schema, or a schema the user
 * uploaded. Immutable, so swapping schemas is a single reference assignment
 * and no consumer can observe a half-changed one.
 *
 * WHY catalog IS NULL FOR THE BUILT-IN SCHEMA:
 * The built-in schema lives in whatever database spring.datasource.url
 * points at, and that URL reads ${DB_HOST}/${DB_PORT} so it varies per
 * machine. Null means "the connection already points at the right place",
 * which lets ExplainExecutor skip catalog switching entirely rather than
 * compare against a hardcoded name that could drift. That skip is what
 * makes "EXPLAIN is unchanged on the built-in path" a structural fact
 * rather than a string comparison anyone could later get wrong.
 */
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

    /**
     * Rows seeded into a table, or 0 when unknown. The built-in schema
     * reports 0 for everything: nothing measures the live demo tables at
     * startup, and a guess here would be worse than an absence.
     */
    public int rowCountFor(String tableName) {
        return rowCounts.getOrDefault(tableName, 0);
    }
}
