package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

/** One CREATE TABLE, reduced to what the upload pipeline needs. */
public record ParsedTable(
        String name,
        List<ParsedColumn> columns,
        List<KnownSchema.ForeignKey> foreignKeys) {

    public Optional<ParsedColumn> column(String columnName) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
    }

    public List<String> columnNames() {
        return columns.stream().map(ParsedColumn::name).toList();
    }

    /**
     * The shape the rest of the app already understands. KnownSchema.Table
     * carries only name and column names, which is all the rule engine and
     * the LLM prompt ever needed; the richer type information stays here,
     * where provisioning and seeding use it.
     */
    public KnownSchema.Table toKnownTable() {
        return new KnownSchema.Table(name, columnNames());
    }
}
