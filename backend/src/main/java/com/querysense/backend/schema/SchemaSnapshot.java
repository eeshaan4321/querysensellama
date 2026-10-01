package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A complete schema for one dialect: its tables, foreign keys, indexes and database. */
public record SchemaSnapshot(
        String id,
        String label,
        String catalog,
        List<KnownSchema.Table> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        Map<String, Integer> rowCounts,
        Instant loadedAt,
        Dialect dialect,
        List<IndexSpec> indexes,
        Map<String, Map<String, String>> fieldTypes,
        Map<String, Map<String, List<String>>> enums) {

    public static final String BUILT_IN_ID = "builtin";

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, Dialect.MYSQL, List.of(), Map.of(), Map.of());
    }

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, Map.of(), Map.of());
    }

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes,
                          Map<String, Map<String, String>> fieldTypes) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, fieldTypes, Map.of());
    }

    public Map<String, List<String>> enumsFor(String tableName) {
        return enums.getOrDefault(tableName, Map.of());
    }

    public Map<String, String> fieldTypesFor(String tableName) {
        return fieldTypes.getOrDefault(tableName, Map.of());
    }

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
