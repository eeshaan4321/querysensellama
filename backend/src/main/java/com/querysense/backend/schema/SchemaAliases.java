package com.querysense.backend.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extra names a schema declares for its tables and columns, read from "aka:" in comments and descriptions. */
public final class SchemaAliases {

    public record TableAliases(List<String> table, Map<String, List<String>> columns) {
        public static final TableAliases NONE = new TableAliases(List.of(), Map.of());

        public List<String> column(String name) {
            return columns.getOrDefault(name, List.of());
        }

        public boolean isEmpty() {
            return table.isEmpty() && columns.isEmpty();
        }
    }

    public record Cleaned(Map<String, TableAliases> aliases, List<String> warnings) {}

    private static final Pattern AKA = Pattern.compile("(?i)\\baka:\\s*([^;]*)");

    private SchemaAliases() {}

    public static List<String> fromComment(String text) {
        if (text == null) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && (trimmed.startsWith("'") || trimmed.startsWith("\""))
                && trimmed.endsWith(trimmed.substring(0, 1))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Matcher m = AKA.matcher(trimmed);
        if (!m.find()) {
            return List.of();
        }
        return Arrays.stream(m.group(1).split(","))
                .map(s -> normalize(s.replaceAll("[.\\s]+$", "")))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public static String normalize(String alias) {
        return alias == null ? "" : alias.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", " ").trim();
    }

    public static TableAliases merge(TableAliases a, TableAliases b) {
        Set<String> table = new LinkedHashSet<>(a.table());
        table.addAll(b.table());
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (TableAliases source : List.of(a, b)) {
            source.columns().forEach((column, names) -> {
                Set<String> merged = new LinkedHashSet<>(columns.getOrDefault(column, List.of()));
                merged.addAll(names);
                columns.put(column, List.copyOf(merged));
            });
        }
        return new TableAliases(List.copyOf(table), Map.copyOf(columns));
    }

    public static String describe(String name, List<String> aliases) {
        return aliases.isEmpty() ? name : name + " (also called: " + String.join(", ", aliases) + ")";
    }

    public static Cleaned clean(List<KnownSchema.Table> tables, Map<String, TableAliases> declared) {
        List<String> warnings = new ArrayList<>();
        Set<String> tableNames = new LinkedHashSet<>();
        Map<String, KnownSchema.Table> byName = new LinkedHashMap<>();
        for (KnownSchema.Table table : tables) {
            tableNames.add(normalize(table.name()));
            tableNames.add(normalize(table.singularName()));
            byName.put(table.name(), table);
        }
        declared.keySet().stream().filter(t -> !byName.containsKey(t)).sorted().forEach(t -> warnings.add(
                "Aliases were declared for \"" + t + "\", which isn't a table in this schema, so they were dropped."));

        Map<String, List<String>> owners = new HashMap<>();
        declared.forEach((table, aliases) -> {
            if (byName.containsKey(table)) {
                aliases.table().forEach(a -> owners.computeIfAbsent(a, k -> new ArrayList<>()).add(table));
            }
        });

        Map<String, TableAliases> kept = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (KnownSchema.Table table : tables) {
            TableAliases aliases = declared.getOrDefault(table.name(), TableAliases.NONE);
            List<String> keptTable = new ArrayList<>();
            for (String alias : aliases.table()) {
                if (tableNames.contains(alias)) {
                    warnings.add("The alias \"" + alias + "\" declared for " + table.name()
                            + " is already the name of a table, so it was dropped.");
                } else if (owners.get(alias).size() > 1) {
                    if (reported.add(alias)) {
                        warnings.add("The alias \"" + alias + "\" was declared for more than one table ("
                                + String.join(" and ", owners.get(alias)) + "), so none of them keeps it.");
                    }
                } else {
                    keptTable.add(alias);
                }
            }
            Map<String, List<String>> keptColumns = keptColumns(table, aliases, warnings);
            if (!keptTable.isEmpty() || !keptColumns.isEmpty()) {
                kept.put(table.name(), new TableAliases(List.copyOf(keptTable), Map.copyOf(keptColumns)));
            }
        }
        return new Cleaned(Map.copyOf(kept), List.copyOf(warnings));
    }

    private static Map<String, List<String>> keptColumns(KnownSchema.Table table, TableAliases aliases,
                                                         List<String> warnings) {
        Set<String> columnNames = new LinkedHashSet<>();
        table.columns().forEach(c -> columnNames.add(normalize(c.replaceAll("([a-z0-9])([A-Z])", "$1_$2"))));
        Map<String, List<String>> owners = new HashMap<>();
        aliases.columns().forEach((column, names) ->
                names.forEach(n -> owners.computeIfAbsent(n, k -> new ArrayList<>()).add(column)));

        Map<String, List<String>> kept = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : aliases.columns().entrySet()) {
            String column = entry.getKey();
            if (!table.columns().contains(column)) {
                warnings.add("Aliases were declared for " + table.name() + "." + column
                        + ", which isn't a column of that table, so they were dropped.");
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (String alias : entry.getValue()) {
                if (columnNames.contains(alias)) {
                    warnings.add("The alias \"" + alias + "\" declared for " + table.name() + "." + column
                            + " is already the name of a column of " + table.name() + ", so it was dropped.");
                } else if (owners.get(alias).size() > 1) {
                    if (reported.add(alias)) {
                        warnings.add("The alias \"" + alias + "\" was declared for more than one column of "
                                + table.name() + ", so none of them keeps it.");
                    }
                } else {
                    keep.add(alias);
                }
            }
            if (!keep.isEmpty()) {
                kept.put(column, List.copyOf(keep));
            }
        }
        return kept;
    }
}
