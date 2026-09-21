package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

/** The tables parsed from an uploaded file, with any skipped statements. */
public record ParsedSchema(List<ParsedTable> tables, List<String> warnings) {

    public Optional<ParsedTable> table(String name) {
        return tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<KnownSchema.ForeignKey> allForeignKeys() {
        return tables.stream().flatMap(t -> t.foreignKeys().stream()).toList();
    }

    public List<KnownSchema.Table> toKnownTables() {
        return tables.stream().map(ParsedTable::toKnownTable).toList();
    }
}
