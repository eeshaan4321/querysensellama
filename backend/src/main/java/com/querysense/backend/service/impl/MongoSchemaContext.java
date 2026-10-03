package com.querysense.backend.service.impl;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Describes the active MongoDB schema to the language model: collections, typed field paths, enums, embedded arrays and references. */
final class MongoSchemaContext {

    private static final String INDENT = "            ";

    private MongoSchemaContext() {
    }

    static String describe(SchemaSnapshot snapshot, List<KnownSchema.Table> mentioned) {
        List<KnownSchema.Table> tables = mentioned.isEmpty() ? snapshot.tables() : mentioned;
        if (tables.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n" + INDENT + "Real MongoDB schema — use ONLY these collections "
                + "and fields; never invent a collection or field name:\n");
        for (KnownSchema.Table table : tables) {
            Map<String, String> types = snapshot.fieldTypesFor(table.name());
            Map<String, List<String>> enums = snapshot.enumsFor(table.name());
            String fields = table.columns().stream()
                    .map(column -> SchemaAliases.describe(describeField(column, types.get(column), enums.get(column)), snapshot.aliasesFor(table.name()).column(column)))
                    .collect(Collectors.joining(", "));
            out.append(INDENT).append("- ").append(SchemaAliases.describe(table.name(), snapshot.aliasesFor(table.name()).table())).append(": ").append(fields);
            if (!table.embeddedArrays().isEmpty()) {
                out.append("; embedded array").append(table.embeddedArrays().size() == 1 ? "" : "s").append(": ")
                        .append(String.join(", ", table.embeddedArrays()));
            }
            out.append("\n");
        }
        Set<String> names = tables.stream().map(KnownSchema.Table::name).collect(Collectors.toSet());
        for (KnownSchema.ForeignKey reference : snapshot.foreignKeys()) {
            if (names.contains(reference.fromTable()) && names.contains(reference.toTable())) {
                out.append(INDENT).append("- Reference: ").append(reference.fromTable()).append(".")
                        .append(reference.fromColumn()).append(" → ").append(reference.toTable()).append(".")
                        .append(reference.toColumn()).append(" — use $lookup with localField \"")
                        .append(reference.fromColumn()).append("\" and foreignField \"")
                        .append(reference.toColumn()).append("\" to combine them.\n");
            }
        }
        return out.toString();
    }

    private static String describeField(String path, String type, List<String> values) {
        if (type == null) {
            return path;
        }
        if (values == null || values.isEmpty()) {
            return path + " (" + type + ")";
        }
        return path + " (" + type + ", one of "
                + values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ")) + ")";
    }
}
