package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

/** A table declared in uploaded DDL. */
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

    public KnownSchema.Table toKnownTable() {
        return new KnownSchema.Table(name, columnNames());
    }
}
