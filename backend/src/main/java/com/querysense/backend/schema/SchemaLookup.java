package com.querysense.backend.schema;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SchemaLookup
 *
 * WHY THIS CLASS EXISTS:
 * KnownSchema owned both the built-in schema's DATA (its nine tables) and
 * the LOGIC for matching a table name against free text. The schema-upload
 * feature needs that logic to run against an uploaded schema too, so the
 * logic is lifted here and KnownSchema's static methods now delegate to it.
 * Behaviour is deliberately unchanged — SchemaLookupTest pins it.
 *
 * WHY Pattern.quote:
 * KnownSchema's original matcher interpolated the table name straight into
 * a regex. Every built-in name is [a-z_] so that was safe. Uploaded names
 * are not guaranteed to be, and an unquoted '.' would match any character.
 * SchemaUploadValidator rejects such names anyway; this is the second lock
 * on the same door, and it changes nothing for the built-in names.
 *
 * SCOPE: this is best-effort word-boundary matching, not entity linking.
 * PROJECT.md's "rule engine resolves multi-word tables to the wrong table"
 * defect is a case it gets wrong, and lifting the logic here deliberately
 * does NOT fix it — that would be a behaviour change smuggled into a
 * refactor. It stays open, and stays documented.
 */
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

    /**
     * Which of the given tables are named (plural or singular) in a piece of
     * free text — used both to detect the table a rule-engine request
     * targets, and to decide what to include in OllamaService's
     * schema-grounding prompt.
     */
    public static List<KnownSchema.Table> detectTablesMentioned(List<KnownSchema.Table> tables, String text) {
        if (tables == null || text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return tables.stream().filter(t -> mentions(lower, t)).toList();
    }

    /** @param lowercaseText caller must have lowercased it already. */
    public static boolean mentions(String lowercaseText, KnownSchema.Table table) {
        return matchesWholeWord(lowercaseText, table.name())
                || matchesWholeWord(lowercaseText, table.singularName());
    }

    private static boolean matchesWholeWord(String text, String word) {
        return text.matches(".*\\b" + Pattern.quote(word) + "\\b.*");
    }
}
