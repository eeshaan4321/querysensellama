package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

/**
 * A whole uploaded file, parsed.
 *
 * `warnings` carries statements that could not be parsed and were skipped.
 * They are reported to the user rather than swallowed: a table silently
 * missing from an upload is discovered later, as a query that fails for no
 * visible reason.
 */
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
