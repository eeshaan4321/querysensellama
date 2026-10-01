package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;

import java.util.List;
import java.util.Optional;

/** A table or collection declared in an uploaded schema. */
public record ParsedTable(
        String name,
        List<ParsedColumn> columns,
        List<KnownSchema.ForeignKey> foreignKeys,
        List<String> embeddedArrays,
        Document validator) {

    public ParsedTable(String name, List<ParsedColumn> columns, List<KnownSchema.ForeignKey> foreignKeys) {
        this(name, columns, foreignKeys, List.of(), null);
    }

    public Optional<ParsedColumn> column(String columnName) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
    }

    public List<String> columnNames() {
        return columns.stream().map(ParsedColumn::name).toList();
    }

    public KnownSchema.Table toKnownTable() {
        return new KnownSchema.Table(name, columnNames(), embeddedArrays);
    }
}
