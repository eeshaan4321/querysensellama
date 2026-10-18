package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The tables parsed from an uploaded file, with their indexes and any skipped statements. */
public record ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes,
                           Map<String, SchemaAliases.TableAliases> aliases, List<SampleInsert> samples) {

    public ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes,
                        Map<String, SchemaAliases.TableAliases> aliases) {
        this(tables, warnings, indexes, aliases, List.of());
    }

    public List<SampleInsert> samplesFor(String table) {
        return samples.stream().filter(s -> s.table().equalsIgnoreCase(table)).toList();
    }

    public ParsedSchema(List<ParsedTable> tables, List<String> warnings) {
        this(tables, warnings, List.of(), Map.of());
    }

    public ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes) {
        this(tables, warnings, indexes, Map.of());
    }

    public Map<String, String> primaryKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            List<String> key = table.columns().stream().filter(ParsedColumn::primaryKey).map(ParsedColumn::name).toList();
            if (key.size() == 1) {
                keys.put(table.name(), key.get(0));
            }
        }
        return keys;
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

    public Map<String, Map<String, List<String>>> enums() {
        Map<String, Map<String, List<String>>> byTable = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            Map<String, List<String>> found = new LinkedHashMap<>();
            if (table.validator() != null && table.validator().get("$jsonSchema") instanceof Document schema) {
                collectEnums(schema, "", found);
            }
            if (!found.isEmpty()) {
                byTable.put(table.name(), found);
            }
        }
        return byTable;
    }

    private static void collectEnums(Document schema, String prefix, Map<String, List<String>> out) {
        if (!(schema.get("properties") instanceof Document properties)) {
            return;
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Document node)) {
                continue;
            }
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (node.get("enum") instanceof List<?> values) {
                out.put(path, values.stream().map(String::valueOf).toList());
            }
            collectEnums(node, path, out);
            if (node.get("items") instanceof Document items) {
                if (items.get("enum") instanceof List<?> values) {
                    out.put(path, values.stream().map(String::valueOf).toList());
                }
                collectEnums(items, path, out);
            }
        }
    }
}
