package com.querysense.backend.schema;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Table lookup helpers shared by the schema sources. */
public final class SchemaLookup {

    private SchemaLookup() {}

    public static Optional<KnownSchema.Table> findTable(List<KnownSchema.Table> tables, String name) {
        if (tables == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(lower) || t.singularName().equalsIgnoreCase(lower))
                .findFirst();
    }

    public static List<KnownSchema.Table> detectTablesMentioned(List<KnownSchema.Table> tables, String text) {
        if (tables == null || text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return tables.stream().filter(t -> mentions(lower, t)).toList();
    }

    public static boolean mentions(String lowercaseText, KnownSchema.Table table) {
        return matchesWholeWord(lowercaseText, table.name())
                || matchesWholeWord(lowercaseText, table.singularName());
    }

    private static boolean matchesWholeWord(String text, String word) {
        return text.matches(".*\\b" + Pattern.quote(word) + "\\b.*");
    }
}
