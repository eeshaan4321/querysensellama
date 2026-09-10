package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SchemaBinder
 *
 * Resolves nouns in a question to real tables and columns of the ACTIVE
 * schema. SchemaLookup stays where it is — OllamaService still uses it for
 * prompt grounding — but the rule engine binds through this class instead,
 * because the two jobs have different correctness requirements. Grounding a
 * prompt too broadly costs nothing; binding a query to the wrong table
 * returns a confident wrong answer.
 *
 * WHY MOST-SPECIFIC RATHER THAN FIRST-FOUND (PROJECT.md §5, order_items):
 * the old matcher returned the first table whose name or singular appeared
 * as a whole word. "show all order items" matched `orders` on the word
 * "order" sitting inside the phrase "order items", and `order_items` could
 * never match at all, because an underscored identifier does not appear in
 * prose. Declaration order was not the cause — `order_items` was tested
 * first and still lost.
 *
 * Two fixes, both here:
 *   1. normalize underscores and whitespace to the same form on BOTH sides
 *      of the comparison, so "order items" and "order_items" are one token;
 *   2. drop any match whose span is CONTAINED inside a longer match's span,
 *      so `orders` cannot win on a word sitting inside `order items`.
 *
 * WHY AMBIGUITY RETURNS EMPTY RATHER THAN A BEST GUESS:
 * this is the Bug 7 rule, kept. A caller that receives nothing declines to
 * the LLM, which is always an acceptable outcome. A caller that receives a
 * guess emits SQL labelled RULE_ENGINE at 0.95 with nothing on screen to
 * suggest doubt, which is the one output the project's auditability claim
 * cannot survive.
 */
@Component
public class SchemaBinder {

    private final ActiveSchemaProvider activeSchema;

    public SchemaBinder(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    /** Lowercase, and collapse underscores and runs of whitespace to one space. */
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

    /**
     * Which tables of the active schema this question names.
     *
     * @param normalizedText must already have been through {@link #normalize}.
     * @return one entry per distinct table, longest match winning over any
     *         match nested inside it. Empty means nothing bound.
     */
    public List<KnownSchema.Table> bindTables(String normalizedText) {
        List<Span> spans = new ArrayList<>();
        for (KnownSchema.Table table : activeSchema.tables()) {
            longestSpanFor(normalizedText, table).ifPresent(spans::add);
        }

        List<KnownSchema.Table> kept = new ArrayList<>();
        for (Span span : spans) {
            boolean swallowed = spans.stream().anyMatch(span::containedIn);
            if (!swallowed) {
                kept.add(span.table());
            }
        }
        return List.copyOf(kept);
    }

    // Plural before singular: the plural is the longer form, so when both
    // match, the plural's span is the one recorded and the singular's
    // contained span never competes with another table's.
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

    /**
     * Resolve one word or compound token to a real column of the given
     * table: exact match, then singular, then an UNAMBIGUOUS suffix match
     * ("order date" -> order_date, but "id" only when exactly one column
     * ends that way).
     *
     * Returns empty rather than picking among several candidates — see the
     * class doc on why a guess is worse than a deferral here.
     */
    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        String token = normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        String underscored = token.replace(' ', '_');

        for (String col : table.columns()) {
            if (col.equalsIgnoreCase(token) || col.equalsIgnoreCase(underscored)) {
                return Optional.of(col);
            }
        }

        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1)
                : underscored;
        for (String col : table.columns()) {
            if (col.equalsIgnoreCase(singular)) {
                return Optional.of(col);
            }
        }

        List<String> suffixMatches = table.columns().stream()
                .filter(col -> col.toLowerCase(Locale.ROOT).endsWith("_" + singular)
                        || col.toLowerCase(Locale.ROOT).endsWith("_" + underscored))
                .distinct()
                .toList();
        return suffixMatches.size() == 1 ? Optional.of(suffixMatches.get(0)) : Optional.empty();
    }

    /** The active schema's tables, for callers that need to scan columns. */
    public List<KnownSchema.Table> tables() {
        return activeSchema.tables();
    }

    /** Exact table lookup by name, for resolving an already-bound name back to its columns. */
    public Optional<KnownSchema.Table> tableNamed(String name) {
        return activeSchema.findTable(name);
    }
}
