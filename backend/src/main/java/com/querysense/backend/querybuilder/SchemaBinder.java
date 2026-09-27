package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches words in a question to tables and columns of the active schema. */
@Component
public class SchemaBinder {

    private final ActiveSchemaProvider activeSchema;

    public SchemaBinder(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", " ").trim();
    }

    private record Span(int start, int end, KnownSchema.Table table) {
        boolean containedIn(Span other) {
            boolean identical = start == other.start && end == other.end;
            return !identical && start >= other.start && end <= other.end;
        }
    }

    public List<KnownSchema.Table> bindTables(String normalizedText, Dialect dialect) {
        List<Span> spans = new ArrayList<>();
        for (KnownSchema.Table table : activeSchema.tables(dialect)) {
            longestSpanFor(normalizedText, table).ifPresent(spans::add);
        }

        spans.sort(Comparator.comparingInt(Span::start));

        List<KnownSchema.Table> kept = new ArrayList<>();
        for (Span span : spans) {
            boolean swallowed = spans.stream().anyMatch(span::containedIn);
            if (!swallowed) {
                kept.add(span.table());
            }
        }
        return List.copyOf(kept);
    }

    private Optional<Span> longestSpanFor(String text, KnownSchema.Table table) {
        return firstMatch(text, normalize(table.name()), table)
                .or(() -> firstMatch(text, normalize(table.singularName()), table));
    }

    private Optional<Span> firstMatch(String text, String term, KnownSchema.Table table) {
        if (term.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(text);
        return m.find() ? Optional.of(new Span(m.start(), m.end(), table)) : Optional.empty();
    }

    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        String token = normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        String underscored = token.replace(' ', '_');
        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1)
                : underscored;

        for (String candidate : List.of(underscored, singular)) {
            for (String col : table.columns()) {
                String snake = snakeCase(col);
                if (col.equalsIgnoreCase(candidate) || snake.equals(candidate)
                        || snake.equals("_" + candidate)) {
                    return Optional.of(col);
                }
            }
        }

        List<String> suffixMatches = table.columns().stream()
                .filter(col -> {
                    String snake = snakeCase(col);
                    return snake.endsWith("_" + singular) || snake.endsWith("_" + underscored)
                            || snake.endsWith("." + singular) || snake.endsWith("." + underscored);
                })
                .distinct()
                .toList();
        return suffixMatches.size() == 1 ? Optional.of(suffixMatches.get(0)) : Optional.empty();
    }

    static String snakeCase(String column) {
        return column.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public List<KnownSchema.Table> tables(Dialect dialect) {
        return activeSchema.tables(dialect);
    }

    public Optional<KnownSchema.Table> tableNamed(String name, Dialect dialect) {
        return activeSchema.findTable(dialect, name);
    }
}
