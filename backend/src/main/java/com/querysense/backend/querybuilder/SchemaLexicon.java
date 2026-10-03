package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every name, declared alias, inflection and built-in synonym that reaches a table or column of the active schema. */
public class SchemaLexicon {

    public enum MatchKind { EXACT, INFLECTION, SCHEMA_ALIAS, SYNONYM, TYPO }

    public record Binding(String item, String phrase, MatchKind kind) {}

    public record TableHit(KnownSchema.Table table, Binding binding) {}

    public record Ambiguity(String phrase, List<String> candidates) {}

    public record TableMatches(List<TableHit> hits, Optional<Ambiguity> ambiguity) {}

    public sealed interface ColumnMatch permits Bound, Ambiguous, Unbound {}

    public record Bound(Binding binding) implements ColumnMatch {}

    public record Ambiguous(Ambiguity ambiguity) implements ColumnMatch {}

    public record Unbound() implements ColumnMatch {}

    private static final Map<String, String> IRREGULAR_PLURALS = Map.of(
            "person", "people", "child", "children", "man", "men", "woman", "women");

    private record Entry(KnownSchema.Table table, MatchKind kind) {}

    private record Index(Map<String, List<Entry>> phrases, Set<String> knownWords) {}

    private record Cached(SchemaSnapshot snapshot, Index index) {}

    private record Span(int start, int end, String phrase, List<Entry> entries) {
        boolean containedIn(Span other) {
            boolean same = start == other.start && end == other.end;
            return !same && start >= other.start && end <= other.end;
        }
    }

    private final ActiveSchemaProvider activeSchema;
    private final Synonyms synonyms;
    private final Map<Dialect, Cached> cache = new ConcurrentHashMap<>();

    public SchemaLexicon(ActiveSchemaProvider activeSchema, Synonyms synonyms) {
        this.activeSchema = activeSchema;
        this.synonyms = synonyms;
    }

    public TableMatches tables(String text, Dialect dialect) {
        Index index = index(dialect);
        List<Span> spans = new ArrayList<>();
        index.phrases().forEach((phrase, entries) -> {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(text);
            if (m.find()) {
                spans.add(new Span(m.start(), m.end(), phrase, entries));
            }
        });

        Map<KnownSchema.Table, Span> perTable = new LinkedHashMap<>();
        for (Span span : spans) {
            if (span.entries().size() != 1) {
                continue;
            }
            KnownSchema.Table table = span.entries().get(0).table();
            Span current = perTable.get(table);
            if (current == null || better(span, current)) {
                perTable.put(table, span);
            }
        }
        List<Span> candidates = new ArrayList<>(perTable.values());
        spans.stream().filter(s -> s.entries().size() > 1).forEach(candidates::add);
        candidates.sort(Comparator.comparingInt(Span::start));

        List<TableHit> hits = new ArrayList<>();
        Optional<Ambiguity> ambiguity = Optional.empty();
        for (Span span : candidates) {
            if (candidates.stream().anyMatch(span::containedIn)) {
                continue;
            }
            if (span.entries().size() > 1) {
                if (ambiguity.isEmpty()) {
                    ambiguity = Optional.of(new Ambiguity(span.phrase(),
                            span.entries().stream().map(e -> e.table().name()).toList()));
                }
                continue;
            }
            Entry entry = span.entries().get(0);
            hits.add(new TableHit(entry.table(), new Binding(entry.table().name(), span.phrase(), entry.kind())));
        }

        if (hits.isEmpty() && ambiguity.isEmpty()) {
            for (String word : text.split(" ")) {
                if (word.length() < Edits.MIN_TYPO_LENGTH || index.knownWords().contains(word)) {
                    continue;
                }
                Set<KnownSchema.Table> near = new LinkedHashSet<>();
                index.phrases().forEach((phrase, entries) -> {
                    if (!phrase.contains(" ") && Edits.withinOne(word, phrase)) {
                        entries.forEach(e -> near.add(e.table()));
                    }
                });
                if (near.size() == 1) {
                    KnownSchema.Table table = near.iterator().next();
                    hits.add(new TableHit(table, new Binding(table.name(), word, MatchKind.TYPO)));
                    break;
                }
                if (near.size() > 1) {
                    ambiguity = Optional.of(new Ambiguity(word, near.stream().map(KnownSchema.Table::name).toList()));
                    break;
                }
            }
        }
        return new TableMatches(List.copyOf(hits), ambiguity);
    }

    public boolean isTableWord(String word, Dialect dialect) {
        return index(dialect).phrases().containsKey(SchemaBinder.normalize(word));
    }

    public Optional<Binding> exactOrAlias(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        String token = SchemaBinder.normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<Binding> exact = exactColumn(table, token);
        if (exact.isPresent()) {
            return exact;
        }
        List<String> aliased = aliasedColumns(table, token, aliases);
        return aliased.size() == 1
                ? Optional.of(new Binding(aliased.get(0), token, MatchKind.SCHEMA_ALIAS))
                : Optional.empty();
    }

    public ColumnMatch column(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        String token = SchemaBinder.normalize(phrase);
        if (token.isEmpty()) {
            return new Unbound();
        }
        Optional<Binding> exact = exactColumn(table, token);
        if (exact.isPresent()) {
            return new Bound(exact.get());
        }
        ColumnMatch aliased = pick(table, token, aliasedColumns(table, token, aliases), MatchKind.SCHEMA_ALIAS);
        if (!(aliased instanceof Unbound)) {
            return aliased;
        }
        List<String> synonymous = table.columns().stream()
                .filter(c -> isSynonym(token, base(c)))
                .toList();
        ColumnMatch synonym = pick(table, token, synonymous, MatchKind.SYNONYM);
        if (!(synonym instanceof Unbound)) {
            return synonym;
        }
        if (token.contains(" ") || token.length() < Edits.MIN_TYPO_LENGTH || isKnownWord(table, token, aliases)) {
            return new Unbound();
        }
        List<String> near = table.columns().stream()
                .filter(c -> columnWords(c, aliases).stream().anyMatch(w -> Edits.withinOne(token, w)))
                .toList();
        return pick(table, token, near, MatchKind.TYPO);
    }

    private static ColumnMatch pick(KnownSchema.Table table, String token, List<String> columns, MatchKind kind) {
        List<String> distinct = columns.stream().distinct().toList();
        if (distinct.size() == 1) {
            return new Bound(new Binding(distinct.get(0), token, kind));
        }
        if (distinct.size() > 1) {
            return new Ambiguous(new Ambiguity(token, distinct));
        }
        return new Unbound();
    }

    private static Optional<Binding> exactColumn(KnownSchema.Table table, String token) {
        String underscored = token.replace(' ', '_');
        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1)
                : underscored;
        List<String> candidates = new ArrayList<>(List.of(underscored, singular));
        for (String form : forms(underscored)) {
            if (!candidates.contains(form)) {
                candidates.add(form);
            }
        }
        for (String candidate : candidates) {
            for (String col : table.columns()) {
                String snake = SchemaBinder.snakeCase(col);
                if (col.equalsIgnoreCase(candidate) || snake.equals(candidate) || snake.equals("_" + candidate)) {
                    return Optional.of(new Binding(col, token,
                            candidate.equals(underscored) ? MatchKind.EXACT : MatchKind.INFLECTION));
                }
            }
        }
        List<String> suffixMatches = table.columns().stream()
                .filter(col -> {
                    String snake = SchemaBinder.snakeCase(col);
                    return snake.endsWith("_" + singular) || snake.endsWith("_" + underscored)
                            || snake.endsWith("." + singular) || snake.endsWith("." + underscored);
                })
                .distinct()
                .toList();
        return suffixMatches.size() == 1
                ? Optional.of(new Binding(suffixMatches.get(0), token, MatchKind.INFLECTION))
                : Optional.empty();
    }

    private static List<String> aliasedColumns(KnownSchema.Table table, String token, SchemaAliases.TableAliases aliases) {
        return table.columns().stream()
                .filter(c -> aliases.column(c).stream().anyMatch(a -> phraseForms(a).contains(token)))
                .toList();
    }

    private boolean isSynonym(String token, String base) {
        if (base.contains(" ") || token.equals(base)) {
            return false;
        }
        Set<String> related = new HashSet<>(synonyms.of(base));
        related.addAll(synonyms.of(singular(base)));
        return related.contains(token) || related.contains(singular(token));
    }

    private boolean isKnownWord(KnownSchema.Table table, String token, SchemaAliases.TableAliases aliases) {
        return synonyms.allWords().contains(token)
                || table.columns().stream().anyMatch(c -> columnWords(c, aliases).contains(token));
    }

    private static Set<String> columnWords(String column, SchemaAliases.TableAliases aliases) {
        Set<String> words = new LinkedHashSet<>();
        String base = base(column);
        words.add(base);
        words.add(singular(base));
        words.addAll(aliases.column(column));
        return words;
    }

    private static String base(String column) {
        String last = column.substring(column.lastIndexOf('.') + 1);
        return SchemaBinder.normalize(SchemaBinder.snakeCase(last));
    }

    private Index index(Dialect dialect) {
        SchemaSnapshot snapshot = activeSchema.active(dialect);
        Cached cached = cache.get(dialect);
        if (cached == null || cached.snapshot() != snapshot) {
            cached = new Cached(snapshot, build(snapshot));
            cache.put(dialect, cached);
        }
        return cached.index();
    }

    private Index build(SchemaSnapshot snapshot) {
        Map<String, List<Entry>> all = new LinkedHashMap<>();
        for (KnownSchema.Table table : snapshot.tables()) {
            String name = SchemaBinder.normalize(table.name());
            String singular = SchemaBinder.normalize(table.singularName());
            put(all, name, table, MatchKind.EXACT);
            put(all, singular, table, MatchKind.INFLECTION);
            phraseForms(name).forEach(f -> put(all, f, table, MatchKind.INFLECTION));
            for (String alias : snapshot.aliasesFor(table.name()).table()) {
                phraseForms(alias).forEach(f -> put(all, f, table, MatchKind.SCHEMA_ALIAS));
            }
            if (!name.contains(" ")) {
                Set<String> related = new LinkedHashSet<>(synonyms.of(name));
                related.addAll(synonyms.of(singular));
                related.forEach(word -> forms(word).forEach(f -> put(all, f, table, MatchKind.SYNONYM)));
            }
        }
        Map<String, List<Entry>> best = new LinkedHashMap<>();
        Set<String> known = new HashSet<>(synonyms.allWords());
        all.forEach((phrase, entries) -> {
            MatchKind top = entries.stream().map(Entry::kind).min(Comparator.naturalOrder()).orElseThrow();
            Map<KnownSchema.Table, Entry> byTable = new LinkedHashMap<>();
            entries.stream().filter(e -> e.kind() == top).forEach(e -> byTable.putIfAbsent(e.table(), e));
            best.put(phrase, List.copyOf(byTable.values()));
            known.addAll(List.of(phrase.split(" ")));
        });
        return new Index(best, known);
    }

    private static void put(Map<String, List<Entry>> all, String phrase, KnownSchema.Table table, MatchKind kind) {
        if (!phrase.isEmpty()) {
            all.computeIfAbsent(phrase, k -> new ArrayList<>()).add(new Entry(table, kind));
        }
    }

    static Set<String> phraseForms(String phrase) {
        int space = phrase.lastIndexOf(' ');
        String head = space < 0 ? "" : phrase.substring(0, space + 1);
        String last = space < 0 ? phrase : phrase.substring(space + 1);
        Set<String> out = new LinkedHashSet<>();
        forms(last).forEach(f -> out.add(head + f));
        return out;
    }

    static Set<String> forms(String word) {
        Set<String> out = new LinkedHashSet<>();
        out.add(word);
        out.add(plural(word));
        out.add(singular(word));
        IRREGULAR_PLURALS.forEach((one, many) -> {
            if (word.equals(one)) {
                out.add(many);
            }
            if (word.equals(many)) {
                out.add(one);
            }
        });
        return out;
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.matches(".*(?:ses|xes|zes|ches|shes)$")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    static String plural(String word) {
        if (word.matches(".*[^aeiou]y$")) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.matches(".*(?:s|x|z|ch|sh)$")) {
            return word + "es";
        }
        return word + "s";
    }

    private static boolean better(Span candidate, Span current) {
        MatchKind a = candidate.entries().get(0).kind();
        MatchKind b = current.entries().get(0).kind();
        return a.compareTo(b) < 0 || (a == b && candidate.start() < current.start());
    }
}
