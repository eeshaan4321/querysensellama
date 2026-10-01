package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The tables parsed from an uploaded file, with their indexes and any skipped statements. */
public record ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes) {

    public ParsedSchema(List<ParsedTable> tables, List<String> warnings) {
        this(tables, warnings, List.of());
    }

    public Optional<ParsedTable> table(String name) {
        return tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<KnownSchema.ForeignKey> allForeignKeys() {
        return tables.stream().flatMap(t -> t.foreignKeys().stream()).toList();
    }

    public List<KnownSchema.Table> toKnownTables() {
        return tables.stream().map(ParsedTable::toKnownTable).toList();
    }

    public List<IndexSpec> indexesOf(String table) {
        return indexes.stream().filter(i -> i.table().equalsIgnoreCase(table)).toList();
    }

    public Map<String, Map<String, String>> fieldTypes() {
        Map<String, Map<String, String>> byTable = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            Map<String, String> types = new LinkedHashMap<>();
            table.columns().forEach(c -> types.put(c.name(), c.type()));
            byTable.put(table.name(), types);
        }
        return byTable;
    }
}
