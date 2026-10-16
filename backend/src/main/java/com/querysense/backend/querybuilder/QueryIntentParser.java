package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Parses a natural-language question into a QueryIntent. */
@Component
public class QueryIntentParser {

    static final String JOIN_ORIGIN = "declared foreign key";

    private static final Pattern TIME_WORDS = Pattern.compile(
            "\\b(?:last|this|next|past|previous)\\s+(?:month|week|year|day|quarter|decade)\\b"
                    + "|\\b(?:today|yesterday|tomorrow|recently|lately)\\b"
                    + "|\\b(?:in|during|before|after|since|by|until|till)\\s+"
                    + "(?:january|february|march|april|may|june|july|august"
                    + "|september|october|november|december)\\b"
                    + "|\\b(?:before|after|since|until)\\s+\\d{4}\\b"
                    + "|\\b(?:date range|last \\d+ days)\\b");

    private static final Map<String, QueryIntent.Op> COMPARISON_OPS = Map.ofEntries(
            Map.entry("more than", QueryIntent.Op.GT), Map.entry("greater than", QueryIntent.Op.GT),
            Map.entry("larger than", QueryIntent.Op.GT), Map.entry("higher than", QueryIntent.Op.GT),
            Map.entry("above", QueryIntent.Op.GT), Map.entry("over", QueryIntent.Op.GT),
            Map.entry("less than", QueryIntent.Op.LT), Map.entry("fewer than", QueryIntent.Op.LT),
            Map.entry("lower than", QueryIntent.Op.LT), Map.entry("smaller than", QueryIntent.Op.LT),
            Map.entry("below", QueryIntent.Op.LT), Map.entry("under", QueryIntent.Op.LT),
            Map.entry("at least", QueryIntent.Op.GTE), Map.entry("no less than", QueryIntent.Op.GTE),
            Map.entry("at most", QueryIntent.Op.LTE), Map.entry("no more than", QueryIntent.Op.LTE),
            Map.entry("up to", QueryIntent.Op.LTE));

    private static final Pattern COMPARISON = Pattern.compile(
            "\\b(?:is\\s+)?(" + alternation(COMPARISON_OPS.keySet()) + ")\\s+(.+)$");

    private static final Pattern BETWEEN = Pattern.compile("\\b(?:is\\s+)?between\\s+(.+)$");

    private static String alternation(Collection<String> words) {
        return words.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
    }

    private static final Pattern PER_GROUP = Pattern.compile(
            "\\b(?:per|for each|in each|by each|for every|in every|grouped by|group by)\\s+([a-z_ ]+?)\\b");

    private static final Pattern PREPOSITION = Pattern.compile(
            "\\b(?:from|in|at(?!\\s+(?:least|most)\\b)|located in|living in|based in|residing in)\\s+(?=[a-z])");

    private static final Pattern WITHOUT = Pattern.compile(
            "\\b(?:without|with no|who have no|that have no|having no|with zero)\\s+(?:any\\s+)?");

    private static final Pattern NEVER = Pattern.compile("\\bnever\\s+([a-z]+?)(?:ed|d)\\b");

    private static final Pattern DISTINCT_PHRASE = Pattern.compile(
            "\\b(?:distinct|unique|different)\\s+([a-z_]+(?:\\s+[a-z_]+)?)(?:\\s+(?:of|in|from|among|for)\\b|$)");

    private static final Pattern WHICH_PHRASE = Pattern.compile(
            "^(?:which|what)\\s+([a-z_]+(?:\\s+[a-z_]+)?)\\s+(?:do|does|did|are|have|has)\\b");

    private static final String AGGREGATE_WORDS = "average|avg|mean|sum|maximum|max|highest|minimum|min|lowest|count";

    private static final Pattern AGGREGATES = Pattern.compile(
            "\\b((?:" + AGGREGATE_WORDS + ")(?:\\s*(?:,|and)\\s*(?:" + AGGREGATE_WORDS + "))+)\\s+"
                    + "(?:of\\s+)?(?:the\\s+)?([a-z_ ]+?)\\b");

    private static final Adjectives ADJECTIVES = Adjectives.builtIn();

    private static final Pattern TOP_BY = Pattern.compile(
            "\\b(top|bottom)\\b.*?\\bby\\s+(?:the\\s+)?([a-z_]+(?:\\s+[a-z_]+)?)");

    private static final Pattern RANK_N = Pattern.compile(
            "\\b(\\S+)\\s+(highest|lowest|greatest)\\s+([a-z_]+(?:\\s+[a-z_]+)?)"
                    + "|\\b(highest|lowest|greatest)\\s+(\\S+)\\s+([a-z_]+(?:\\s+[a-z_]+)?)");

    private static final Pattern ADJECTIVE = Pattern.compile(
            "\\b(?:(?!(?:most|least)\\b)(\\S+)\\s+)?(?:(most|least)\\s+)?(" + alternation(ADJECTIVES.entries().keySet()) + ")\\b(?:\\s+(\\S+))?");

    private record Ranking(QueryIntent.ColumnRef column, QueryIntent.Direction direction, Integer limit) {}

    private static final Pattern EXCLUDING = Pattern.compile(
            "\\b(?:other than|excluding|except|not in|not from)\\s+(?=[a-z])");

    private static final Pattern NOT_VALUE = Pattern.compile("\\b(?:is\\s+)?not\\s+(?=[a-z])");

    private static final int LONGEST_VALUE_WORDS = 4;

    private static final Pattern LEADING_DELETE = Pattern.compile(
            "^(?:please )?(?:delete|remove)\\b");

    private static final Pattern STANDALONE_DELETE = Pattern.compile(
            "\\b(?:delete|remove)\\b");

    private static final Pattern LIMIT = Pattern.compile("\\b(?:top|first|limit|bottom)\\s+(.+)$");

    private static final Pattern ORDER_BY = Pattern.compile(
            "\\b(?:order(?:ed)?\\s+by|sort(?:ed)?\\s+by)\\s+([a-z_]+(?:\\s+(?!desc|asc)[a-z_]+)?)"
                    + "(\\s+desc(?:ending)?|\\s+asc(?:ending)?)?");

    private static final Pattern ONLY_JUST = Pattern.compile(
            "(?:only|just)\\s+(?:the\\s+)?([a-z][a-z0-9 ,]*?)(?:\\s+of\\s+|\\s+from\\s+|\\s+for\\s+|$)");

    private static final Pattern OF_PHRASE = Pattern.compile("^(.*?)\\s+of\\s+");

    private static final Pattern AGGREGATE_PHRASE = Pattern.compile(
            "\\b(average|avg|mean|sum|total(?!\\s+number)|maximum|max|highest|minimum|min|lowest)\\s+"
                    + "(?:of\\s+)?(?:the\\s+)?([a-z_ ]+?)\\b");

    private static final Pattern WITH_VALUE = Pattern.compile("\\b(?:with|whose|where)\\s+(?=[a-z0-9])");

    private static final Pattern VALUE_START = Pattern.compile("\\G\\s+(?:(?:is|equals|=)\\s+)?(?=[a-z0-9_])");

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private static final Pattern BARE_BY = Pattern.compile("(?<!sorted |ordered |order |sort |grouped |group )\\bby\\s+(?:the\\s+)?([a-z_]+)");

    private static final Set<String> FILLER_WORDS = Set.of(
            "please", "can", "could", "you", "i", "we", "want", "need", "would", "like", "see", "do", "does", "did",
            "record", "records", "row", "rows", "entry", "entries", "detail", "details", "info", "information",
            "data", "everything", "those", "these", "ones", "one", "whose", "where", "named", "called", "with",
            "without", "never", "zero", "having", "whom", "how", "many", "much", "together", "both", "also",
            "along", "related", "among", "other", "than", "only", "just", "first", "last", "latest", "newest",
            "oldest", "earliest", "recent", "most", "least", "highest", "lowest", "greatest", "bottom", "distinct",
            "unique", "different", "placed", "made", "own", "owns", "bought", "purchased", "ordered", "table",
            "tables", "collection", "collections", "document", "documents", "query", "up", "equals", "us", "my",
            "our", "in", "living", "located", "based", "residing", "live", "lives", "that", "they");

    private static final Pattern CONJOINED_NOUN = Pattern.compile("\\b(?:and|plus)\\s+([a-z_]+)\\b");

    private static final Map<String, String> AGGREGATE_FUNCTIONS = Map.ofEntries(
            Map.entry("average", "AVG"), Map.entry("avg", "AVG"), Map.entry("mean", "AVG"),
            Map.entry("sum", "SUM"), Map.entry("total", "SUM"),
            Map.entry("maximum", "MAX"), Map.entry("max", "MAX"), Map.entry("highest", "MAX"),
            Map.entry("minimum", "MIN"), Map.entry("min", "MIN"), Map.entry("lowest", "MIN"));

    private static final Pattern TOTAL_OF = Pattern.compile("\\btotal\\s+([a-z_]+)");

    private static final Map<String, Map.Entry<String, QueryIntent.Op>> COMPARATIVES = Map.of(
            "cheaper", Map.entry("cheapest", QueryIntent.Op.LT),
            "pricier", Map.entry("priciest", QueryIntent.Op.GT),
            "costlier", Map.entry("costly", QueryIntent.Op.GT));

    private static final Pattern COMPARATIVE = Pattern.compile(
            "\\b(?:(more|less)\\s+([a-z]+)|(cheaper|pricier|costlier))\\s+than\\s+(.+)$");

    private static final Set<String> VALUE_STOPWORDS = Set.of(
            "the", "a", "an", "all", "any", "each", "every", "this", "that",
            "their", "there", "them", "it", "which", "who", "what", "order",
            "ascending", "descending", "asc", "desc", "total", "and", "or");

    private static final Set<String> PROJECTION_FILLER_WORDS = Set.of(
            "show", "get", "list", "fetch", "find", "select", "display",
            "retrieve", "give", "me", "the", "a", "an", "all");

    private static final Set<String> COUNT_WORDS = Set.of(
            "count", "how many", "total number", "number of");

    private static final Set<String> NON_COLUMN_WORDS = Set.of(
            "in", "at", "from", "with", "to", "for", "on", "of", "by", "as",
            "is", "are", "was", "were", "be", "been", "has", "have", "had",
            "and", "or", "not", "no", "than", "more", "less", "fewer",
            "above", "below", "over", "under", "between", "per", "each",
            "the", "a", "an", "all", "any", "every", "this", "that",
            "their", "there", "them", "it", "its", "who", "which", "what",
            "sorted", "ordered", "grouped", "group", "order", "sort",
            "ascending", "descending", "asc", "desc", "limit", "top",
            "show", "get", "list", "give", "me", "display", "find", "except", "excluding");

    private final SchemaBinder binder;
    private final JoinPathResolver joins;
    private final DialectRegistry<SchemaMetadata> metadataByDialect;
    private final DialectRegistry<QueryEmitter> emitters;
    private final RulesClock clock;
    private final ColumnPhrase phrases;

    public QueryIntentParser(SchemaBinder binder, JoinPathResolver joins,
                             List<SchemaMetadata> metadata, List<QueryEmitter> emitters, RulesClock clock) {
        this.binder = binder;
        this.joins = joins;
        this.metadataByDialect = new DialectRegistry<>(metadata, "schema metadata");
        this.emitters = new DialectRegistry<>(emitters, "query emitter");
        this.clock = clock;
        this.phrases = new ColumnPhrase(binder);
    }

    static Set<String> instructionWords() {
        Set<String> words = new HashSet<>();
        COUNT_WORDS.forEach(w -> words.addAll(List.of(w.split(" "))));
        words.addAll(AGGREGATE_FUNCTIONS.keySet());
        words.addAll(PROJECTION_FILLER_WORDS);
        words.addAll(NON_COLUMN_WORDS);
        words.addAll(VALUE_STOPWORDS);
        words.addAll(List.of("top", "first", "limit", "only", "just", "number", "total", "delete", "remove"));
        return Set.copyOf(words);
    }

    public RuleBuildResult parse(String rawInput, Dialect dialect) {
        List<RuleTrace.Binding> bindings = new ArrayList<>();
        List<Interpretation> interpretations = new ArrayList<>();
        try {
            return parse(rawInput, dialect, bindings, interpretations);
        } catch (DeclineException e) {
            return RuleBuildResult.declined(e.getMessage(), bindings);
        }
    }

    private RuleBuildResult parse(String rawInput, Dialect dialect, List<RuleTrace.Binding> bindings,
                                  List<Interpretation> interpretations) {
        String normalized = SchemaBinder.normalize(rawInput).replaceAll("\\s*\\bplease\\b", "").trim();
        Optional<DateReader.DateRange> date = DateReader.read(normalized, clock.today());
        String text = date.map(r -> normalized.substring(0, r.start()) + " ".repeat(r.end() - r.start())
                + normalized.substring(r.end())).orElse(normalized);
        SchemaMetadata metadata = metadataByDialect.get(dialect);

        Matcher timeWords = TIME_WORDS.matcher(normalized);
        if (date.isEmpty() && timeWords.find()) {
            return RuleBuildResult.declined(
                    "The question mentions a time period (\"" + timeWords.group() + "\") this engine can't read as "
                            + "dates, so it went to the language model instead.",
                    bindings);
        }

        SchemaLexicon.TableMatches tableMatches = binder.tableMatches(text, dialect);
        if (tableMatches.ambiguity().isPresent()) {
            SchemaLexicon.Ambiguity ambiguity = tableMatches.ambiguity().get();
            throw new AmbiguousWordException(ambiguityMessage(ambiguity.phrase(), ambiguity.candidates()));
        }
        List<KnownSchema.Table> tables = tableMatches.hits().stream().map(SchemaLexicon.TableHit::table).toList();
        tableMatches.hits().forEach(hit -> note(hit.binding().phrase(), hit.table().name(), hit.binding().kind(), interpretations));
        if (tables.isEmpty()) {
            return RuleBuildResult.declined(
                    "Nothing in the question matches a table in the schema that is currently active.",
                    bindings);
        }
        if (tables.size() > JoinPathResolver.MAX_TABLES) {
            return RuleBuildResult.declined(
                    "The question names " + tables.size() + " tables. This engine joins four at most.",
                    bindings);
        }
        tables.forEach(t -> bindings.add(new RuleTrace.Binding(t.name(), "TABLE", t.name())));

        Matcher conjoined = CONJOINED_NOUN.matcher(text);
        while (conjoined.find()) {
            String word = conjoined.group(1);
            boolean known = binder.tableNamed(word, dialect).isPresent()
                    || binder.isTableWord(word, dialect)
                    || NON_COLUMN_WORDS.contains(word)
                    || VALUE_STOPWORDS.contains(word)
                    || tables.stream().anyMatch(t -> binder.bindColumn(t, word).isPresent())
                    || tables.stream().anyMatch(t -> !columnsHolding(t, word, metadata).isEmpty());
            if (!known && word.endsWith("s")) {
                return RuleBuildResult.declined(
                        "\"" + word + "\" isn't a table in the active schema, so this engine can't tell "
                                + "what the question joins " + tables.get(0).name() + " to.",
                        bindings);
            }
        }

        boolean anti = false;
        Matcher without = WITHOUT.matcher(text);
        Matcher never = NEVER.matcher(text);
        if (without.find()) {
            if (tables.size() != 2) {
                return RuleBuildResult.declined(
                        "The question asks for " + tables.get(0).name() + " without something, but doesn't name one "
                                + "other table to check against.",
                        bindings);
            }
            anti = true;
        } else if (never.find()) {
            List<SchemaLexicon.TableHit> stem = binder.tableMatches(never.group(1), dialect).hits();
            if (stem.size() == 1 && !stem.get(0).table().equals(tables.get(0))) {
                if (tables.size() == 1) {
                    tables = List.of(tables.get(0), stem.get(0).table());
                    bindings.add(new RuleTrace.Binding(never.group(), "TABLE", stem.get(0).table().name()));
                }
                anti = true;
            }
        }

        KnownSchema.Table subject = tables.get(0);
        String subjectPhrase = tableMatches.hits().get(0).binding().phrase();
        boolean singular = !subjectPhrase.endsWith("s");
        KnownSchema.Table related = tables.size() >= 2 ? tables.get(tables.size() - 1) : null;

        QueryIntent.JoinSpec joinSpec = null;
        List<QueryIntent.JoinSpec> furtherJoins = new ArrayList<>();
        List<RuleTrace.JoinEdge> joinEdges = new ArrayList<>();
        List<KnownSchema.Table> chainTables = new ArrayList<>(List.of(subject));
        if (related != null) {
            List<KnownSchema.ForeignKey> edges;
            if (anti) {
                JoinPathResolver.JoinResolution direct = joins.resolve(subject.name(), related.name(), dialect);
                edges = direct instanceof JoinPathResolver.Resolved resolved ? List.of(resolved.edge()) : null;
                if (edges == null) {
                    return declineFor(direct, subject, related, bindings);
                }
            } else {
                JoinPathResolver.ChainResolution chain = joins.resolveChain(subject.name(),
                        tables.subList(1, tables.size()).stream().map(KnownSchema.Table::name).toList(), dialect);
                if (!(chain instanceof JoinPathResolver.Chain found)) {
                    return declineFor(chain, subject, tables, bindings);
                }
                edges = found.edges();
            }
            for (KnownSchema.ForeignKey edge : edges) {
                boolean fromKnown = chainTables.stream().anyMatch(t -> t.name().equalsIgnoreCase(edge.fromTable()));
                String knownTable = fromKnown ? edge.fromTable() : edge.toTable();
                String knownColumn = fromKnown ? edge.fromColumn() : edge.toColumn();
                String newTable = fromKnown ? edge.toTable() : edge.fromTable();
                String newColumn = fromKnown ? edge.toColumn() : edge.fromColumn();
                QueryIntent.JoinSpec spec = new QueryIntent.JoinSpec(newTable,
                        new QueryIntent.ColumnRef(knownTable, knownColumn), new QueryIntent.ColumnRef(newTable, newColumn),
                        anti ? QueryIntent.JoinKind.ANTI : QueryIntent.JoinKind.INNER);
                if (joinSpec == null) {
                    joinSpec = spec;
                } else {
                    furtherJoins.add(spec);
                }
                joinEdges.add(new RuleTrace.JoinEdge(edge.fromTable(), edge.fromColumn(), edge.toTable(),
                        edge.toColumn(), JOIN_ORIGIN));
                binder.tableNamed(newTable, dialect).ifPresent(chainTables::add);
                if (tables.stream().noneMatch(t -> t.name().equalsIgnoreCase(newTable))) {
                    bindings.add(new RuleTrace.Binding(newTable, "THROUGH", newTable));
                }
            }
        }
        RuleTrace.JoinEdge joinEdge = joinEdges.isEmpty() ? null : joinEdges.get(0);

        boolean joined = joinSpec != null;
        QueryIntent.Verb verb = LEADING_DELETE.matcher(text).find()
                ? QueryIntent.Verb.DELETE : QueryIntent.Verb.SELECT;

        Matcher strayDeleteVerb = STANDALONE_DELETE.matcher(text);
        if (verb == QueryIntent.Verb.SELECT && strayDeleteVerb.find()) {
            return RuleBuildResult.declined(
                    "The question uses \"" + strayDeleteVerb.group() + "\" without opening with it, "
                            + "so this engine cannot tell whether it asks to remove rows or to "
                            + "show them.",
                    bindings);
        }

        if (verb == QueryIntent.Verb.DELETE && joined) {
            return RuleBuildResult.declined(
                    "Deleting across two related tables is beyond what this engine will build.",
                    bindings);
        }

        List<QueryIntent.SelectItem> selectItems = new ArrayList<>();
        List<QueryIntent.Predicate> where = new ArrayList<>();
        List<int[]> conditionSpans = new ArrayList<>();
        List<QueryIntent.Predicate> having = new ArrayList<>();
        List<int[]> consumed = new ArrayList<>();

        if (date.isPresent()) {
            DateReader.DateRange range = date.get();
            if (range.from() != null && range.until() != null && !range.from().isBefore(range.until())) {
                throw new DeclineException("The date range \"" + range.phrase() + "\" ends before it starts, so this "
                        + "engine won't build a query that can never match.");
            }
            DateChoice choice = dateColumn(normalized, chainTables, joined, range.phrase());
            QueryIntent.ColumnRef dateRef = choice.ref();
            if (choice.namingWord() != null) {
                Matcher naming = Pattern.compile("\\b" + Pattern.quote(choice.namingWord()) + "\\b").matcher(text);
                if (naming.find()) {
                    consumed.add(new int[]{naming.start(), naming.end()});
                }
            }
            if (range.from() != null) {
                where.add(new QueryIntent.Predicate(dateRef, QueryIntent.Op.GTE,
                        List.of(QueryIntent.Literal.date(range.from(), clock.zone()))));
            }
            if (range.until() != null) {
                where.add(new QueryIntent.Predicate(dateRef, QueryIntent.Op.LT,
                        List.of(QueryIntent.Literal.date(range.until(), clock.zone()))));
            }
            String owner = dateRef.table() == null ? subject.name() : dateRef.table();
            bindings.add(new RuleTrace.Binding(range.phrase(), "DATE_RANGE", owner + "." + dateRef.column()
                    + (range.from() == null ? "" : " from " + range.from())
                    + (range.until() == null ? "" : " up to " + range.until())));
            consumed.add(new int[]{range.start(), range.end()});
            conditionSpans.add(new int[]{range.start(), range.end()});
        }

        Matcher totalOf = TOTAL_OF.matcher(text);
        boolean countRequested = COUNT_WORDS.stream().anyMatch(text::contains)
                || (totalOf.find() && !binder.tableMatches(totalOf.group(1), dialect).hits().isEmpty());

        Optional<String> distinctColumn = distinctColumn(text, subject, dialect, interpretations);
        boolean distinct = distinctColumn.isPresent();
        Optional<String> strongPhrase = distinct || countRequested ? Optional.empty() : extractStrongProjectionPhrase(text);
        List<String> projected = List.of();
        boolean projectionRequested = false;
        if (strongPhrase.isPresent()) {
            int recorded = interpretations.size();
            List<String> resolved;
            try {
                resolved = resolveChunks(strongPhrase.get(), subject, interpretations);
            } catch (AmbiguousWordException e) {
                if (namesTable(strongPhrase.get(), dialect)) {
                    resolved = null;
                } else {
                    throw e;
                }
            }
            if (resolved == null && namesTable(strongPhrase.get(), dialect)) {
                interpretations.subList(recorded, interpretations.size()).clear();
            }
            if (resolved != null) {
                projected = resolved;
                projectionRequested = true;
                projected.forEach(c -> bindings.add(
                        new RuleTrace.Binding(c, "COLUMN", subject.name() + "." + c)));
            } else if (!namesTable(strongPhrase.get(), dialect)) {
                return RuleBuildResult.declined(
                        "The question asks for specific fields, but at least one of them is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
        }

        if (distinct) {
            projected = List.of(distinctColumn.get());
            projectionRequested = true;
            bindings.add(new RuleTrace.Binding(distinctColumn.get(), "DISTINCT",
                    subject.name() + "." + distinctColumn.get()));
        }

        if (!projectionRequested && !countRequested && !AGGREGATE_PHRASE.matcher(text).find()
                && !AGGREGATES.matcher(text).find()) {
            Optional<String> tail = extractTrailingColumnPhrase(text, subject, dialect)
                    .filter(t -> !insideAny(text.lastIndexOf(t), consumed));
            if (tail.isPresent()) {
                List<String> resolved = resolveChunks(tail.get(), subject, interpretations);
                if (resolved == null) {
                    return RuleBuildResult.declined(
                            "The question looks like it asks for \"" + tail.get() + "\" from "
                                    + subject.name() + ", but that does not match exactly one of "
                                    + "its columns.",
                            bindings);
                }
                projected = resolved;
                projectionRequested = true;
                projected.forEach(c -> bindings.add(
                        new RuleTrace.Binding(tail.get(), "COLUMN", subject.name() + "." + c)));
            }
        }

        Optional<Ranking> ranked = projectionRequested ? Optional.empty()
                : ranking(text, normalized, subject, chainTables, joined, singular, interpretations);

        List<QueryIntent.Aggregate> aggregates = new ArrayList<>();

        if (!projectionRequested && ranked.isEmpty()) {
            Matcher many = AGGREGATES.matcher(text);
            Matcher agg = AGGREGATE_PHRASE.matcher(text);
            if (many.find()) {
                Optional<String> col = column(subject, many.group(2), interpretations);
                if (col.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question asks for " + many.group(1) + " of \"" + many.group(2)
                                    + "\", which is not a column of " + subject.name() + ".",
                            bindings);
                }
                List<String> rendered = new ArrayList<>();
                for (String word : many.group(1).split("\\s*(?:,|and)\\s*")) {
                    QueryIntent.Aggregate aggregate = "count".equals(word)
                            ? new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null)
                            : new QueryIntent.Aggregate(QueryIntent.Fn.valueOf(AGGREGATE_FUNCTIONS.get(word)),
                                    qualify(col.get(), subject, joined));
                    aggregates.add(aggregate);
                    rendered.add(aggregate.column() == null ? "COUNT(*)"
                            : aggregate.fn().name() + "(" + subject.name() + "." + col.get() + ")");
                }
                bindings.add(new RuleTrace.Binding(many.group(1) + " " + many.group(2), "AGGREGATE",
                        String.join(", ", rendered)));
            } else if (agg.find() && !("total".equals(agg.group(1)) && countRequested)) {
                String fn = AGGREGATE_FUNCTIONS.get(agg.group(1));
                Optional<String> col = column(subject, agg.group(2), interpretations);
                if (col.isEmpty()) {
                    col = followingColumn(text, agg.start(2), subject, interpretations);
                }
                if (col.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question asks for the " + agg.group(1) + " of \"" + agg.group(2)
                                    + "\", which is not a column of " + subject.name() + ".",
                            bindings);
                }
                aggregates.add(new QueryIntent.Aggregate(QueryIntent.Fn.valueOf(fn), qualify(col.get(), subject, joined)));
                bindings.add(new RuleTrace.Binding(agg.group(2), "AGGREGATE",
                        fn + "(" + subject.name() + "." + col.get() + ")"));
            }
        }

        String groupColumn = null;
        String groupPhrase = null;
        Matcher per = PER_GROUP.matcher(text);
        if (per.find()) {
            groupPhrase = per.group(1).trim();
            Optional<String> col = column(subject, per.group(1), interpretations);
            if (col.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for a breakdown by \"" + per.group(1).trim()
                                + "\", which is not a column of " + subject.name() + ".",
                        bindings);
            }
            groupColumn = col.get();
            bindings.add(new RuleTrace.Binding(per.group(1).trim(), "GROUPING",
                    subject.name() + "." + groupColumn));
        }

        Matcher between = BETWEEN.matcher(text);
        if (between.find() && adjacentWordBefore(text, between.start()).isPresent()) {
            String rest = between.group(1);
            Optional<ColumnPhrase.Reading> rangeRead = phrases.before(text, between.start(), List.of(subject));
            String rangePhrase = rangeRead.map(ColumnPhrase.Reading::phrase)
                    .orElse(wordBefore(text, between.start()).map(w -> text.substring(w[0], w[1])).orElse(""));
            Optional<NumberReader.Reading> low = NumberReader.leading(rest);
            String after = low.map(r -> rest.substring(r.phrase().length()).trim()).orElse("");
            Optional<NumberReader.Reading> high = after.startsWith("and ")
                    ? NumberReader.leading(after.substring(4))
                    : Optional.empty();
            if (low.isEmpty() || high.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for " + rangePhrase + " between \"" + rest
                                + "\", but this engine can only read a range between two numbers.",
                        bindings);
            }
            if (rangeRead.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for a range of \"" + rangePhrase + "\", which is not a column of "
                                + subject.name() + ".",
                        bindings);
            }
            QueryIntent.ColumnRef rangeRef = use(rangeRead.get(), joined, interpretations);
            QueryIntent.Literal from = QueryIntent.Literal.number(low.get().value());
            QueryIntent.Literal to = QueryIntent.Literal.number(high.get().value());
            where.add(new QueryIntent.Predicate(rangeRef, QueryIntent.Op.BETWEEN, List.of(from, to)));
            conditionSpans.add(new int[]{rangeRead.get().start(), between.start(1)
                    + rest.indexOf(high.get().phrase(), low.get().phrase().length()) + high.get().phrase().length()});
            String lhs = joined ? subject.name() + "." + rangeRead.get().column() : rangeRead.get().column();
            bindings.add(new RuleTrace.Binding(
                    rangePhrase + " between " + low.get().phrase() + " and " + high.get().phrase(),
                    "COMPARISON", lhs + " BETWEEN " + from.text() + " AND " + to.text()));
        }

        Matcher comparison = COMPARISON.matcher(text);
        String countTargetColumn = null;
        int searchFrom = 0;
        while (searchFrom < text.length() && comparison.find(searchFrom)) {
            if (insideAny(comparison.start(), conditionSpans)) {
                searchFrom = comparison.end(1);
                continue;
            }
            Optional<int[]> previous = adjacentWordBefore(text, comparison.start());
            if (previous.isEmpty()) {
                searchFrom = comparison.end(1);
                continue;
            }
            QueryIntent.Op op = COMPARISON_OPS.get(comparison.group(1));
            String operator = op.sql();
            String rest = comparison.group(2);
            Optional<NumberReader.Reading> reading = NumberReader.leading(rest);
            if (reading.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question compares against \"" + rest.split("\\s+")[0]
                                + "\", which is not a number this engine can read.",
                        bindings);
            }
            QueryIntent.Literal number = QueryIntent.Literal.number(reading.get().value());
            Optional<ColumnPhrase.Reading> lhsRead = phrases.before(text, comparison.start(), chainTables);
            String lhsPhrase = lhsRead.map(ColumnPhrase.Reading::phrase)
                    .orElse(text.substring(previous.get()[0], previous.get()[1]));
            int start = lhsRead.map(ColumnPhrase.Reading::start).orElse(previous.get()[0]);
            int[] span = {start, comparison.start(2) + reading.get().phrase().length()};

            if (lhsRead.isPresent()) {
                QueryIntent.ColumnRef lhsRef = use(lhsRead.get(), joined, interpretations);
                String lhs = SqlEmitter.column(lhsRef);
                where.add(new QueryIntent.Predicate(lhsRef, op, List.of(number)));
                conditionSpans.add(span);
                bindings.add(new RuleTrace.Binding(
                        lhsPhrase + " " + comparison.group(1) + " " + reading.get().phrase(),
                        "COMPARISON", lhs + " " + operator + " " + number.text()));
            } else if (joined) {
                if (countTargetColumn != null) {
                    return RuleBuildResult.declined(
                            "The question compares two counts, which this engine won't combine.", bindings);
                }
                countTargetColumn = binder.primaryKey(related);
                String rendered = "COUNT(" + related.name() + "." + countTargetColumn + ")";
                having.add(new QueryIntent.Predicate(
                        new QueryIntent.Aggregate(QueryIntent.Fn.COUNT,
                                new QueryIntent.ColumnRef(related.name(), countTargetColumn)),
                        op, List.of(number)));
                conditionSpans.add(span);
                bindings.add(new RuleTrace.Binding(
                        comparison.group(1) + " " + reading.get().phrase() + " " + related.name(),
                        "COMPARISON", rendered + " " + operator + " " + number.text()));
            } else {
                return RuleBuildResult.declined(
                        "The question compares \"" + lhsPhrase + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            searchFrom = span[1];
        }

        Matcher comparative = COMPARATIVE.matcher(text);
        if (comparative.find() && !insideAny(comparative.start(), conditionSpans)) {
            String adjective = comparative.group(3) != null
                    ? COMPARATIVES.get(comparative.group(3)).getKey() : comparative.group(2);
            Adjectives.Entry entry = ADJECTIVES.entries().get(adjective);
            if (entry != null && "column".equals(entry.type())) {
                QueryIntent.Op op = comparative.group(3) != null ? COMPARATIVES.get(comparative.group(3)).getValue()
                        : ("more".equals(comparative.group(1)) == (entry.direction() == QueryIntent.Direction.DESC))
                                ? QueryIntent.Op.GT : QueryIntent.Op.LT;
                Optional<String> col = entry.words().stream()
                        .map(w -> binder.bindColumn(subject, w)).flatMap(Optional::stream).findFirst();
                Optional<NumberReader.Reading> reading = NumberReader.leading(comparative.group(4));
                if (col.isEmpty() || reading.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question compares \"" + comparative.group().trim() + "\", but " + subject.name()
                                    + " has no column or number this engine can compare it with.",
                            bindings);
                }
                QueryIntent.Literal number = QueryIntent.Literal.number(reading.get().value());
                where.add(new QueryIntent.Predicate(qualify(col.get(), subject, joined), op, List.of(number)));
                conditionSpans.add(new int[]{comparative.start(), comparative.start(4) + reading.get().phrase().length()});
                bindings.add(new RuleTrace.Binding(comparative.group().trim(), "COMPARISON",
                        subject.name() + "." + col.get() + " " + op.sql() + " " + number.text()));
            }
        }

        if (anti && !having.isEmpty()) {
            return RuleBuildResult.declined(
                    "The question asks for " + subject.name() + " without " + related.name()
                            + " and also counts them, which this engine won't combine.",
                    bindings);
        }

        if (groupColumn != null && aggregates.isEmpty() && !countRequested && countTargetColumn == null) {
            return RuleBuildResult.declined(
                    "The question asks for a breakdown by \"" + groupPhrase + "\" but not for a total, "
                            + "average or count of anything, so there is nothing to group.",
                    bindings);
        }

        Matcher excluding = EXCLUDING.matcher(text);
        if (excluding.find()) {
            Optional<ValueList> list = readValues(text, excluding.end(), chainTables, joined, null, metadata, dialect,
                    interpretations);
            if (list.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question excludes something, but no value of " + subject.name() + " follows.",
                        bindings);
            }
            where.add(new QueryIntent.Predicate(valueRef(list.get(), joined),
                    list.get().typed().size() == 1 ? QueryIntent.Op.NE : QueryIntent.Op.NOT_IN,
                    List.copyOf(list.get().literals())));
            bindings.add(new RuleTrace.Binding("not " + String.join(" and ", list.get().typed()), "FILTER",
                    list.get().table() + "." + list.get().column()));
            consumed.add(new int[]{excluding.start(), list.get().end()});
            conditionSpans.add(new int[]{excluding.start(), list.get().end()});
        } else {
            Matcher notValue = NOT_VALUE.matcher(text);
            if (notValue.find() && adjacentWordBefore(text, notValue.start()).isPresent()) {
                Optional<ColumnPhrase.Reading> notRead = phrases.beforeExact(text, notValue.start(), subject);
                if (notRead.isPresent()) {
                    String notColumn = notRead.get().column();
                    Optional<ValueList> list = readValues(text, notValue.end(), chainTables, joined, notColumn,
                            metadata, dialect, interpretations);
                    if (list.isEmpty()) {
                        return RuleBuildResult.declined(
                                "The question says " + notRead.get().phrase() + " is not something, but no stored value "
                                        + "of " + subject.name() + "." + notColumn + " follows.",
                                bindings);
                    }
                    where.add(new QueryIntent.Predicate(qualify(notColumn, subject, joined),
                            list.get().typed().size() == 1 ? QueryIntent.Op.NE : QueryIntent.Op.NOT_IN,
                            List.copyOf(list.get().literals())));
                    bindings.add(new RuleTrace.Binding(notRead.get().phrase() + " not "
                            + String.join(" and ", list.get().typed()), "FILTER",
                            subject.name() + "." + notColumn));
                    consumed.add(new int[]{notRead.get().start(), list.get().end()});
                    conditionSpans.add(new int[]{notRead.get().start(), list.get().end()});
                }
            }
        }

        Matcher preposition = PREPOSITION.matcher(text);
        while (preposition.find()) {
            if (insideAny(preposition.start(), consumed)) {
                continue;
            }
            Optional<ValueList> list = readValues(text, preposition.end(), chainTables, joined, null, metadata, dialect,
                    interpretations);
            Matcher tableWord = Pattern.compile("([a-z_]+)\\s+(?=[a-z])").matcher(text);
            if (list.isEmpty() && joined && tableWord.find(preposition.end()) && tableWord.start() == preposition.end()
                    && binder.tableMatches(tableWord.group(1), dialect).hits().stream()
                            .anyMatch(h -> chainTables.contains(h.table()))) {
                list = readValues(text, tableWord.end(), chainTables, joined, null, metadata, dialect, interpretations);
            }
            if (list.isEmpty()) {
                continue;
            }
            where.add(new QueryIntent.Predicate(valueRef(list.get(), joined),
                    list.get().typed().size() == 1 ? QueryIntent.Op.EQ : QueryIntent.Op.IN,
                    List.copyOf(list.get().literals())));
            bindings.add(new RuleTrace.Binding(String.join(" and ", list.get().typed()), "FILTER",
                    list.get().table() + "." + list.get().column()));
            consumed.add(new int[]{preposition.start(), list.get().end()});
            conditionSpans.add(new int[]{preposition.start(), list.get().end()});
            break;
        }

        Matcher with = WITH_VALUE.matcher(text);
        int[] withFirst = null;
        int withSecondEnd = -1;
        while (with.find()) {
            List<int[]> following = wordsFrom(text, with.end(), 2);
            if (following.size() == 2 && following.get(0)[0] == with.end()) {
                withFirst = following.get(0);
                withSecondEnd = following.get(1)[1];
                break;
            }
        }
        if (withFirst != null) {
            String first = text.substring(withFirst[0], withFirst[1]);
            int withStart = with.start();
            int matchEnd = withSecondEnd;
            if (!insideAny(withFirst[0], conditionSpans) && !insideAny(withStart, conditionSpans)
                    && !VALUE_STOPWORDS.contains(first) && !NON_COLUMN_WORDS.contains(first)
                    && conditionSpans.stream().noneMatch(s -> s[0] >= withStart && s[0] < matchEnd)) {
                Optional<ColumnPhrase.Reading> withRead = phrases.after(text, withFirst[0], chainTables);
                if (withRead.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question filters on \"" + first + "\", which is not a column of "
                                    + subject.name() + ".",
                            bindings);
                }
                Matcher valueStart = VALUE_START.matcher(text);
                if (valueStart.find(withRead.get().end())) {
                    QueryIntent.ColumnRef withRef = use(withRead.get(), joined, interpretations);
                    KnownSchema.Table withTable = withRead.get().table();
                    int valueAt = valueStart.end();
                    String value;
                    Optional<NumberReader.Reading> number = NumberReader.leading(text.substring(valueAt));
                    QueryIntent.Literal literalValue;
                    int valueEnd;
                    if (number.isPresent()) {
                        literalValue = QueryIntent.Literal.number(number.get().value());
                        value = number.get().phrase();
                        valueEnd = valueAt + value.length();
                    } else {
                        List<String> run = valueWords(text.substring(valueAt), chainTables, dialect);
                        Optional<String> stored = Optional.empty();
                        int n = run.size();
                        for (; n >= 1 && stored.isEmpty(); n--) {
                            stored = storedValueOf(withTable, withRead.get().column(), String.join(" ", run.subList(0, n)), metadata);
                        }
                        if (stored.isPresent()) {
                            run = run.subList(0, n + 1);
                        }
                        value = String.join(" ", run);
                        if (stored.isEmpty() && dialect == Dialect.MONGODB) {
                            return RuleBuildResult.declined(
                                    "Couldn't confirm how \"" + value + "\" is stored in " + withTable.name() + "."
                                            + withRead.get().column() + ", and MongoDB matches text exactly, so this "
                                            + "engine won't guess its capitalisation.",
                                    bindings);
                        }
                        literalValue = QueryIntent.Literal.text(value, stored.orElse(value));
                        valueEnd = text.indexOf(value, valueAt) + value.length();
                    }
                    where.add(new QueryIntent.Predicate(withRef, QueryIntent.Op.EQ, List.of(literalValue)));
                    conditionSpans.add(new int[]{withStart, valueEnd});
                    bindings.add(new RuleTrace.Binding(withRead.get().phrase() + " " + value, "FILTER",
                            withTable.name() + "." + withRead.get().column()));
                }
            }
        }

        boolean anyOf = false;
        if (conditionSpans.size() >= 2) {
            List<int[]> ordered = new ArrayList<>(conditionSpans);
            ordered.sort(Comparator.comparingInt(s -> s[0]));
            boolean or = false;
            boolean and = false;
            for (int k = 0; k + 1 < ordered.size(); k++) {
                String gap = text.substring(Math.min(ordered.get(k)[1], ordered.get(k + 1)[0]), ordered.get(k + 1)[0]);
                or |= gap.matches(".*\\bor\\b.*");
                and |= gap.matches(".*\\band\\b.*");
            }
            if (or && and) {
                return RuleBuildResult.declined(
                        "The question joins its conditions with both \"and\" and \"or\", so this engine can't tell "
                                + "how they group.",
                        bindings);
            }
            anyOf = or;
        }
        if (anyOf && joinSpec != null && joinSpec.kind() == QueryIntent.JoinKind.ANTI) {
            return RuleBuildResult.declined(
                    "The question combines \"or\" with \"without\", which this engine won't build.",
                    bindings);
        }

        boolean joinedFilter = joined && !anti
                && where.stream().anyMatch(p -> p.lhs() instanceof QueryIntent.ColumnRef r
                        && r.table() != null && !r.table().equals(subject.name()));

        if (groupColumn != null) {
            selectItems.add(new QueryIntent.SelectItem(qualify(groupColumn, subject, joined)));
        }
        if (!aggregates.isEmpty()) {
            aggregates.forEach(a -> selectItems.add(new QueryIntent.SelectItem(a)));
        } else if (countTargetColumn != null) {
            for (String col : IdentifyingColumns.of(subject, binder, metadata)) {
                selectItems.add(0, new QueryIntent.SelectItem(
                        new QueryIntent.ColumnRef(subject.name(), col)));
            }
            selectItems.add(new QueryIntent.SelectItem(new QueryIntent.Aggregate(
                    QueryIntent.Fn.COUNT, new QueryIntent.ColumnRef(related.name(), countTargetColumn))));
        } else if (countRequested) {
            selectItems.add(new QueryIntent.SelectItem(joinedFilter
                    ? new QueryIntent.Aggregate(QueryIntent.Fn.COUNT,
                            new QueryIntent.ColumnRef(subject.name(), binder.primaryKey(subject)))
                    : new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null)));
        } else if (!projected.isEmpty()) {
            projected.forEach(c -> selectItems.add(
                    new QueryIntent.SelectItem(qualify(c, subject, joined))));
        } else if (verb == QueryIntent.Verb.SELECT && selectItems.isEmpty()) {
            selectItems.add(new QueryIntent.SelectItem(anti || joinedFilter
                    ? new QueryIntent.TableStar(subject.name())
                    : QueryIntent.Star.INSTANCE));
        }

        if (verb == QueryIntent.Verb.DELETE) {
            selectItems.clear();
            if (where.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks to delete rows without saying which ones.", bindings);
            }
        }

        for (KnownSchema.Table table : tables) {
            List<String> live = metadata.getColumnNames(table.name());
            if (live.isEmpty()) {
                continue;
            }
            for (String bound : boundColumnsOf(table, selectItems, where, having, groupColumn)) {
                boolean present = live.stream().anyMatch(c -> c.equalsIgnoreCase(bound));
                if (!present) {
                    return RuleBuildResult.declined(
                            "The column \"" + bound + "\" is described by the active schema but is "
                                    + "not in the database right now.",
                            bindings);
                }
            }
        }

        List<QueryIntent.OrderItem> orderBy = new ArrayList<>();
        Matcher order = ORDER_BY.matcher(text);
        String orderPhrase = order.find() ? order.group(1) : null;
        Optional<QueryIntent.ColumnRef> orderRef = orderPhrase == null ? Optional.empty()
                : chainColumn(orderPhrase, chainTables, joined, interpretations);
        if (orderRef.isEmpty() && orderPhrase != null && orderPhrase.contains(" ")) {
            orderPhrase = orderPhrase.substring(0, orderPhrase.indexOf(' '));
            orderRef = chainColumn(orderPhrase, chainTables, joined, interpretations);
        }
        if (orderPhrase != null && (orderRef.isPresent() || binder.tableMatches(orderPhrase, dialect).hits().isEmpty())) {
            if (orderRef.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks to sort by \"" + orderPhrase + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            QueryIntent.Direction direction = order.group(2) != null && order.group(2).contains("desc")
                    ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC;
            orderBy.add(new QueryIntent.OrderItem(orderRef.get(), direction));
            consumed.add(new int[]{order.start(), order.start(1) + orderPhrase.length()});
        }
        if (orderBy.isEmpty() && ranked.isPresent()) {
            orderBy.add(new QueryIntent.OrderItem(ranked.get().column(), ranked.get().direction()));
        }

        Integer limit = null;
        Matcher limitMatcher = LIMIT.matcher(text);
        if (limitMatcher.find()) {
            Optional<NumberReader.Reading> reading = NumberReader.leading(limitMatcher.group(1));
            if (reading.isPresent()) {
                BigDecimal value = reading.get().value();
                if (!reading.get().integral() || value.signum() <= 0
                        || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    return RuleBuildResult.declined(
                            "The question asks for the top \"" + reading.get().phrase() + "\", which is not a "
                                    + "whole number of rows this engine can limit to.",
                            bindings);
                }
                limit = value.intValueExact();
            }
        }
        if (limit == null) {
            Matcher counted = Pattern.compile("\\b(\\S+)\\s+" + Pattern.quote(subjectPhrase) + "\\b").matcher(text);
            if (counted.find() && !insideAny(counted.start(), conditionSpans)) {
                Optional<NumberReader.Reading> reading = NumberReader.leading(counted.group(1));
                if (reading.isPresent() && reading.get().integral() && reading.get().value().signum() > 0
                        && reading.get().value().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
                    limit = reading.get().value().intValueExact();
                }
            }
        }
        if (limit == null && ranked.isPresent()) {
            limit = ranked.get().limit();
        }
        if (ranked.isPresent()) {
            bindings.add(new RuleTrace.Binding("ranking", "RANKING",
                    SqlEmitter.column(ranked.get().column()) + " " + ranked.get().direction()));
        }

        List<int[]> read = new ArrayList<>(consumed);
        read.addAll(conditionSpans);
        Optional<String> unread = unreadWord(text, read, chainTables, bindings, interpretations, metadata, dialect);
        if (unread.isPresent()) {
            return RuleBuildResult.declined(
                    "The question uses \"" + unread.get() + "\", which isn't a table, column or stored value of the "
                            + "active schema, so this engine can't be sure it reads the whole question.",
                    bindings);
        }

        Matcher bareBy = BARE_BY.matcher(text);
        if (orderBy.isEmpty() && groupColumn == null && bareBy.find() && !TOP_BY.matcher(text).find()
                && binder.tableMatches(bareBy.group(1), dialect).hits().isEmpty()) {
            return RuleBuildResult.declined(
                    "The question says \"" + bareBy.group().trim() + "\", which could mean sort or group, so this "
                            + "engine won't guess.",
                    bindings);
        }

        QueryIntent intent = new QueryIntent(verb, subject.name(), joinSpec,
                List.copyOf(selectItems), List.copyOf(where), List.copyOf(having),
                List.copyOf(orderBy), limit, distinct || joinedFilter, anyOf, List.copyOf(furtherJoins));

        String sql;
        try {
            sql = emitters.get(dialect).emit(intent);
        } catch (UnexpressibleIntentException e) {
            return RuleBuildResult.declined(e.getMessage(), bindings);
        }
        RuleTrace trace = new RuleTrace(
                RuleTrace.BUILT,
                shapeOf(intent),
                List.copyOf(bindings),
                joinEdge,
                checksFor(joined, joinEdges.size()),
                null,
                List.copyOf(joinEdges));
        return RuleBuildResult.built(sql, trace, interpretations);
    }

    private QueryIntent.ColumnRef use(ColumnPhrase.Reading reading, boolean joined, List<Interpretation> interpretations) {
        note(reading.phrase(), reading.table().name() + "." + reading.column(), reading.kind(), interpretations);
        return qualify(reading.column(), reading.table(), joined);
    }

    private Optional<QueryIntent.ColumnRef> chainColumn(String phrase, List<KnownSchema.Table> chainTables,
                                                        boolean joined, List<Interpretation> interpretations) {
        KnownSchema.Table subject = chainTables.get(0);
        Optional<String> own = column(subject, phrase, interpretations);
        if (own.isPresent() || !joined) {
            return own.map(c -> qualify(c, subject, joined));
        }
        List<QueryIntent.ColumnRef> found = new ArrayList<>();
        List<SchemaLexicon.Binding> bindingsFound = new ArrayList<>();
        for (KnownSchema.Table table : chainTables.subList(1, chainTables.size())) {
            if (binder.columnMatch(table, phrase) instanceof SchemaLexicon.Bound bound) {
                found.add(new QueryIntent.ColumnRef(table.name(), bound.binding().item()));
                bindingsFound.add(bound.binding());
            }
        }
        if (found.size() > 1) {
            throw new AmbiguousWordException(ambiguityMessage(phrase,
                    found.stream().map(r -> r.table() + "." + r.column()).toList()));
        }
        if (found.size() == 1) {
            note(bindingsFound.get(0).phrase(), found.get(0).table() + "." + found.get(0).column(),
                    bindingsFound.get(0).kind(), interpretations);
            return Optional.of(found.get(0));
        }
        return Optional.empty();
    }

    private record DateChoice(QueryIntent.ColumnRef ref, String namingWord) {}

    private DateChoice dateColumn(String text, List<KnownSchema.Table> chainTables, boolean joined, String phrase) {
        List<QueryIntent.ColumnRef> named = new ArrayList<>();
        List<String> namingWords = new ArrayList<>();
        List<QueryIntent.ColumnRef> all = new ArrayList<>();
        Set<String> words = new HashSet<>(Arrays.asList(text.split("[^a-z0-9_]+")));
        for (KnownSchema.Table table : chainTables) {
            for (String column : table.columns()) {
                if (!SchemaBinder.isDateType(binder.fieldType(table, column))) {
                    continue;
                }
                QueryIntent.ColumnRef ref = new QueryIntent.ColumnRef(joined ? table.name() : null, column);
                all.add(ref);
                String spaced = NameWords.phrase(column);
                String stem = spaced.replaceAll(" ?\\b(?:date|at|on)$", "");
                String naming = !stem.isEmpty() && words.contains(stem + "ed") ? stem + "ed"
                        : !stem.isEmpty() && words.contains(stem + "d") ? stem + "d"
                        : !stem.isEmpty() && words.contains(stem) ? stem
                        : text.contains(spaced) ? spaced : null;
                if (naming != null) {
                    named.add(ref);
                    namingWords.add(naming);
                }
            }
        }
        List<QueryIntent.ColumnRef> pick = !named.isEmpty() ? named
                : all.stream().filter(r -> r.table() == null || r.table().equals(chainTables.get(0).name())).toList();
        if (pick.isEmpty()) {
            pick = all;
        }
        if (pick.isEmpty()) {
            throw new DeclineException("No table in the question has a date column, so this engine can't place \""
                    + phrase + "\".");
        }
        if (pick.size() > 1) {
            String tableName = chainTables.get(0).name();
            throw new DeclineException("\"" + phrase + "\" could be checked against " + listWith("or", pick.stream()
                    .map(r -> (r.table() == null ? tableName : r.table()) + "." + r.column()).toList())
                    + "; name one in the question.");
        }
        return new DateChoice(pick.get(0), named.size() == 1 ? namingWords.get(0) : null);
    }

    private Optional<String> column(KnownSchema.Table table, String phrase, List<Interpretation> interpretations) {
        SchemaLexicon.ColumnMatch match = binder.columnMatch(table, phrase);
        if (match instanceof SchemaLexicon.Ambiguous ambiguous) {
            throw new AmbiguousWordException(ambiguityMessage(ambiguous.ambiguity().phrase(),
                    ambiguous.ambiguity().candidates().stream().map(c -> table.name() + "." + c).toList()));
        }
        if (match instanceof SchemaLexicon.Bound bound) {
            note(bound.binding().phrase(), table.name() + "." + bound.binding().item(), bound.binding().kind(), interpretations);
            return Optional.of(bound.binding().item());
        }
        return Optional.empty();
    }

    private static void note(String phrase, String item, SchemaLexicon.MatchKind kind, List<Interpretation> interpretations) {
        if (kind == SchemaLexicon.MatchKind.SCHEMA_ALIAS || kind == SchemaLexicon.MatchKind.SYNONYM
                || kind == SchemaLexicon.MatchKind.TYPO) {
            Interpretation interpretation = new Interpretation(phrase, item, kind);
            if (!interpretations.contains(interpretation)) {
                interpretations.add(interpretation);
            }
        }
    }

    static String ambiguityMessage(String phrase, List<String> candidates) {
        return "\"" + phrase + "\" could mean " + listWith("or", candidates) + ", so this engine won't pick one.";
    }

    static String listWith(String conjunction, List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " " + conjunction + " " + items.get(items.size() - 1);
    }

    private RuleBuildResult declineFor(JoinPathResolver.JoinResolution resolution, KnownSchema.Table subject,
                                       KnownSchema.Table related, List<RuleTrace.Binding> bindings) {
        if (resolution instanceof JoinPathResolver.Ambiguous ambiguous) {
            String columns = ambiguous.edges().stream().map(KnownSchema.ForeignKey::fromColumn)
                    .reduce((a, b) -> a + " and " + b).orElse("");
            return RuleBuildResult.declined("There is more than one way to connect " + subject.name() + " and "
                    + related.name() + " (" + columns + "), and the question does not say which one it means.", bindings);
        }
        return RuleBuildResult.declined("The schema declares no relationship between " + subject.name() + " and "
                + related.name() + ", so there is no reliable way to connect them.", bindings);
    }

    private RuleBuildResult declineFor(JoinPathResolver.ChainResolution chain, KnownSchema.Table subject,
                                       List<KnownSchema.Table> tables, List<RuleTrace.Binding> bindings) {
        if (chain instanceof JoinPathResolver.AmbiguousChain ambiguous) {
            if (ambiguous.paths().stream().allMatch(p -> p.size() == 1)) {
                String columns = ambiguous.paths().stream().map(p -> p.get(0).fromColumn())
                        .reduce((a, b) -> a + " and " + b).orElse("");
                return RuleBuildResult.declined("There is more than one way to connect " + subject.name() + " and "
                        + ambiguous.table() + " (" + columns + "), and the question does not say which one it means.",
                        bindings);
            }
            String ways = ambiguous.paths().stream().map(p -> "through " + p.stream()
                            .flatMap(e -> Stream.of(e.fromTable(), e.toTable()))
                            .filter(t -> !t.equalsIgnoreCase(subject.name()) && !t.equalsIgnoreCase(ambiguous.table()))
                            .distinct().collect(Collectors.joining(" and ")))
                    .collect(Collectors.joining(", or "));
            return RuleBuildResult.declined("There are two equally short ways to connect " + subject.name() + " and "
                    + ambiguous.table() + " (" + ways + "), and the question doesn't say which one it means.", bindings);
        }
        if (chain instanceof JoinPathResolver.TooFar tooFar) {
            return RuleBuildResult.declined("Connecting these tables needs " + tooFar.tables()
                    + " tables. This engine joins four at most.", bindings);
        }
        String missing = ((JoinPathResolver.NoChain) chain).table();
        return tables.size() == 2
                ? RuleBuildResult.declined("The schema declares no relationship between " + subject.name() + " and "
                        + missing + ", so there is no reliable way to connect them.", bindings)
                : RuleBuildResult.declined("The schema declares no chain of relationships linking " + subject.name()
                        + " and " + missing + " within four tables.", bindings);
    }

    private QueryIntent.ColumnRef qualify(String column, KnownSchema.Table table, boolean joined) {
        return new QueryIntent.ColumnRef(joined ? table.name() : null, column);
    }

    private record ValueMatch(String column, String stored) {}

    private record ValueRun(String typed, String column, String stored, int words, boolean typo) {}

    private List<ValueMatch> columnsHolding(KnownSchema.Table table, String value, SchemaMetadata metadata) {
        List<ValueMatch> matches = new ArrayList<>();
        for (String column : table.columns()) {
            storedValueOf(table, column, value, metadata).ifPresent(stored -> matches.add(new ValueMatch(column, stored)));
        }
        return matches;
    }

    private ValueRun valueRun(KnownSchema.Table table, String[] words, int from, SchemaMetadata metadata,
                             String onlyColumn) {
        for (int n = Math.min(LONGEST_VALUE_WORDS, words.length - from); n >= 1; n--) {
            String phrase = String.join(" ", Arrays.copyOfRange(words, from, from + n));
            List<ValueMatch> matches = columnsHolding(table, phrase, metadata).stream()
                    .filter(m -> onlyColumn == null || m.column().equals(onlyColumn)).toList();
            if (matches.size() > 1) {
                List<String> columns = matches.stream().map(m -> table.name() + "." + m.column()).toList();
                throw new AmbiguousWordException("\"" + matches.get(0).stored() + "\" is stored in "
                        + (columns.size() == 2 ? "both " : "") + listWith("and", columns)
                        + ", so this engine can't tell which one the question means.");
            }
            if (matches.size() == 1) {
                return new ValueRun(phrase, matches.get(0).column(), matches.get(0).stored(), n, false);
            }
        }
        String word = words[from];
        if (word.length() < Edits.MIN_TYPO_LENGTH) {
            return null;
        }
        List<ValueMatch> near = new ArrayList<>();
        for (String column : table.columns()) {
            if (onlyColumn != null && !column.equals(onlyColumn)) {
                continue;
            }
            metadata.getDistinctValues(table.name(), column).stream()
                    .filter(v -> v != null && Edits.withinOne(word, v.toLowerCase(Locale.ROOT)))
                    .distinct()
                    .forEach(v -> near.add(new ValueMatch(column, v)));
        }
        if (near.size() > 1) {
            throw new AmbiguousWordException(ambiguityMessage(word,
                    near.stream().map(m -> table.name() + "." + m.column() + " = " + m.stored()).toList()));
        }
        return near.size() == 1 ? new ValueRun(word, near.get(0).column(), near.get(0).stored(), 1, true) : null;
    }

    private record ValueList(String table, String column, List<String> typed, List<QueryIntent.Literal> literals, int end) {}

    private Optional<ValueList> readValues(String text, int from, List<KnownSchema.Table> chainTables, boolean joined,
                                           String onlyColumn, SchemaMetadata metadata, Dialect dialect,
                                           List<Interpretation> interpretations) {
        KnownSchema.Table subject = chainTables.get(0);
        KnownSchema.Table valueTable = null;
        String rest = text.substring(from).split("[^a-z\\s]", 2)[0];
        List<String> words = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        Matcher word = Pattern.compile("[a-z]+").matcher(rest);
        while (word.find()) {
            words.add(word.group());
            ends.add(from + word.end());
        }
        String[] array = words.toArray(new String[0]);

        List<String> typed = new ArrayList<>();
        List<QueryIntent.Literal> literals = new ArrayList<>();
        String valueColumn = null;
        int end = from;
        int i = 0;
        while (i < array.length) {
            String first = array[i];
            if (VALUE_STOPWORDS.contains(first)
                    || binder.tableNamed(first, dialect).isPresent()
                    || chainTables.stream().anyMatch(t -> binder.bindColumn(t, first).isPresent())) {
                break;
            }
            KnownSchema.Table runTable = valueTable == null ? subject : valueTable;
            ValueRun run = valueRun(runTable, array, i, metadata, onlyColumn);
            if (run == null && valueTable == null && joined && onlyColumn == null) {
                List<KnownSchema.Table> holding = new ArrayList<>();
                List<ValueRun> runs = new ArrayList<>();
                for (KnownSchema.Table other : chainTables.subList(1, chainTables.size())) {
                    ValueRun found = valueRun(other, array, i, metadata, null);
                    if (found != null) {
                        holding.add(other);
                        runs.add(found);
                    }
                }
                if (holding.size() > 1) {
                    throw new DeclineException("\"" + array[i] + "\" is stored in both "
                            + listWith("and", holding.stream().map(t -> t.name() + "." + runs.get(holding.indexOf(t)).column()).toList())
                            + ", so this engine can't tell which one the question means.");
                }
                if (holding.size() == 1) {
                    runTable = holding.get(0);
                    run = runs.get(0);
                }
            }
            if (run == null) {
                if (!typed.isEmpty()) {
                    break;
                }
                throw new DeclineException("No column of " + subject.name() + " contains the value \"" + first
                        + "\", so this engine cannot tell which field the question means.");
            }
            if (valueColumn != null && !valueColumn.equals(run.column())) {
                throw new DeclineException("\"" + typed.get(0) + "\" and \"" + run.typed() + "\" are values of "
                        + "different columns (" + valueColumn + " and " + run.column() + "), so this engine "
                        + "cannot tell which one the question filters on.");
            }
            valueColumn = run.column();
            valueTable = runTable;
            typed.add(run.typed());
            if (run.typo()) {
                literals.add(QueryIntent.Literal.text(run.stored(), run.stored()));
                note(run.typed(), runTable.name() + "." + run.column() + " = " + run.stored(),
                        SchemaLexicon.MatchKind.TYPO, interpretations);
            } else {
                literals.add(QueryIntent.Literal.text(run.typed(), run.stored()));
            }
            i += run.words();
            end = ends.get(i - 1);
            if (i < array.length && ("and".equals(array[i]) || "or".equals(array[i]))) {
                i++;
                continue;
            }
            break;
        }
        return typed.isEmpty() ? Optional.empty()
                : Optional.of(new ValueList(valueTable.name(), valueColumn, typed, literals, end));
    }

    private List<String> valueWords(String rest, List<KnownSchema.Table> chainTables, Dialect dialect) {
        List<String> run = new ArrayList<>();
        for (String word : rest.trim().split("\\s+")) {
            if (run.size() == LONGEST_VALUE_WORDS || !word.matches("[a-z0-9_]+")
                    || (!run.isEmpty() && (NON_COLUMN_WORDS.contains(word) || VALUE_STOPWORDS.contains(word)
                    || binder.tableNamed(word, dialect).isPresent()
                    || chainTables.stream().anyMatch(t -> binder.bindColumn(t, word).isPresent())))) {
                break;
            }
            run.add(word);
        }
        return run;
    }

    private Optional<String> unreadWord(String text, List<int[]> spans, List<KnownSchema.Table> chainTables,
                                        List<RuleTrace.Binding> bindings, List<Interpretation> interpretations,
                                        SchemaMetadata metadata, Dialect dialect) {
        Set<String> known = instructionWords();
        Set<String> bound = new HashSet<>();
        bindings.forEach(b -> bound.addAll(Arrays.asList((b.phrase() + " " + b.resolvedTo()).split("[^a-z0-9]+"))));
        interpretations.forEach(i -> bound.addAll(Arrays.asList(i.phrase().split("[^a-z0-9]+"))));
        Matcher word = Pattern.compile("[a-z0-9_]+").matcher(text);
        while (word.find()) {
            String w = word.group();
            if (insideAny(word.start(), spans) || known.contains(w) || FILLER_WORDS.contains(w) || bound.contains(w)
                    || COMPARISON_OPS.keySet().stream().anyMatch(op -> op.matches("(?:.* )?" + Pattern.quote(w) + "(?: .*)?"))
                    || ADJECTIVES.entries().containsKey(w) || w.matches("\\d+") || NumberReader.leading(w).isPresent()
                    || !binder.tableMatches(w, dialect).hits().isEmpty()
                    || chainTables.stream().anyMatch(t -> binder.columnMatch(t, w) instanceof SchemaLexicon.Bound)) {
                continue;
            }
            return Optional.of(w);
        }
        return Optional.empty();
    }

    private static QueryIntent.ColumnRef valueRef(ValueList list, boolean joined) {
        return new QueryIntent.ColumnRef(joined ? list.table() : null, list.column());
    }

    private static Optional<int[]> wordBefore(String text, int to) {
        Matcher m = WORD.matcher(text);
        int[] last = null;
        while (m.find() && m.end() <= to) {
            last = new int[]{m.start(), m.end()};
        }
        return Optional.ofNullable(last);
    }

    private static Optional<int[]> adjacentWordBefore(String text, int to) {
        return wordBefore(text, to).filter(w -> w[1] < to && text.substring(w[1], to).isBlank());
    }

    private static List<int[]> wordsFrom(String text, int from, int max) {
        List<int[]> found = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (found.size() < max && m.find(found.isEmpty() ? from : found.get(found.size() - 1)[1])) {
            found.add(new int[]{m.start(), m.end()});
        }
        return found;
    }

    private static boolean insideAny(int position, List<int[]> spans) {
        return spans.stream().anyMatch(s -> position >= s[0] && position < s[1]);
    }

    private Optional<String> storedValueOf(KnownSchema.Table table, String column, String value,
                                           SchemaMetadata metadata) {
        return metadata.getDistinctValues(table.name(), column).stream()
                .filter(v -> v != null && v.equalsIgnoreCase(value))
                .findFirst();
    }

    private List<String> boundColumnsOf(KnownSchema.Table table,
                                        List<QueryIntent.SelectItem> selectItems,
                                        List<QueryIntent.Predicate> where,
                                        List<QueryIntent.Predicate> having,
                                        String groupColumn) {
        List<String> names = new ArrayList<>();
        for (QueryIntent.SelectItem item : selectItems) {
            columnOf(table, item.operand()).ifPresent(names::add);
        }
        for (QueryIntent.Predicate predicate : where) {
            columnOf(table, predicate.lhs()).ifPresent(names::add);
        }
        for (QueryIntent.Predicate predicate : having) {
            columnOf(table, predicate.lhs()).ifPresent(names::add);
        }
        if (groupColumn != null) {
            names.add(groupColumn);
        }
        return names;
    }

    private Optional<String> columnOf(KnownSchema.Table table, QueryIntent.Operand operand) {
        QueryIntent.ColumnRef ref;
        if (operand instanceof QueryIntent.ColumnRef direct) {
            ref = direct;
        } else if (operand instanceof QueryIntent.Aggregate agg && agg.column() != null) {
            ref = agg.column();
        } else {
            return Optional.empty();
        }
        if (ref.table() != null && !ref.table().equals(table.name())) {
            return Optional.empty();
        }
        boolean known = table.columns().stream().anyMatch(c -> c.equalsIgnoreCase(ref.column()));
        return known ? Optional.of(ref.column()) : Optional.empty();
    }

    private Optional<String> followingColumn(String text, int from, KnownSchema.Table subject,
                                             List<Interpretation> interpretations) {
        List<String> words = new ArrayList<>();
        for (String word : text.substring(from).trim().split("\\s+")) {
            if (words.size() == 3 || NON_COLUMN_WORDS.contains(word)) {
                break;
            }
            words.add(word);
        }
        for (int i = words.size() - 1; i >= 1; i--) {
            Optional<String> col = column(subject, words.get(i), interpretations);
            if (col.isPresent()) {
                return col;
            }
        }
        return Optional.empty();
    }

    private Optional<String> distinctColumn(String text, KnownSchema.Table subject, Dialect dialect,
                                            List<Interpretation> interpretations) {
        Matcher distinct = DISTINCT_PHRASE.matcher(text);
        String phrase = null;
        if (distinct.find()) {
            phrase = distinct.group(1);
        } else {
            Matcher which = WHICH_PHRASE.matcher(text);
            if (which.find() && binder.tableMatches(which.group(1), dialect).hits().isEmpty()) {
                phrase = which.group(1);
            }
        }
        if (phrase == null) {
            return Optional.empty();
        }
        Optional<String> bound = column(subject, phrase, interpretations);
        if (bound.isEmpty() && phrase.contains(" ")) {
            bound = column(subject, phrase.substring(0, phrase.indexOf(' ')), interpretations);
        }
        if (bound.isEmpty() && phrase.contains(" ")) {
            bound = column(subject, phrase.substring(phrase.lastIndexOf(' ') + 1), interpretations);
        }
        return bound;
    }

    private Optional<Ranking> ranking(String text, String normalized, KnownSchema.Table subject,
                                      List<KnownSchema.Table> chainTables, boolean joined, boolean singular,
                                      List<Interpretation> interpretations) {
        Matcher top = TOP_BY.matcher(text);
        if (top.find()) {
            Optional<String> col = column(subject, top.group(2), interpretations);
            if (col.isEmpty() && top.group(2).contains(" ")) {
                col = column(subject, top.group(2).substring(0, top.group(2).indexOf(' ')), interpretations);
            }
            if (col.isEmpty()) {
                throw new DeclineException("The question ranks by \"" + top.group(2) + "\", which is not a column of "
                        + subject.name() + ".");
            }
            QueryIntent.Direction direction = "top".equals(top.group(1)) ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC;
            return Optional.of(new Ranking(qualify(col.get(), subject, joined), direction, singular ? 1 : null));
        }

        Matcher rankN = RANK_N.matcher(text);
        if (rankN.find()) {
            String count = rankN.group(1) != null ? rankN.group(1) : rankN.group(5);
            String word = rankN.group(2) != null ? rankN.group(2) : rankN.group(4);
            String phrase = rankN.group(3) != null ? rankN.group(3) : rankN.group(6);
            Optional<NumberReader.Reading> n = NumberReader.leading(count);
            if (n.isPresent() && n.get().integral() && n.get().phrase().equals(count)) {
                Optional<String> col = column(subject, phrase, interpretations);
                if (col.isEmpty() && phrase.contains(" ")) {
                    col = column(subject, phrase.substring(0, phrase.indexOf(' ')), interpretations);
                }
                if (col.isPresent()) {
                    QueryIntent.Direction direction = "lowest".equals(word) ? QueryIntent.Direction.ASC : QueryIntent.Direction.DESC;
                    return Optional.of(new Ranking(qualify(col.get(), subject, joined), direction,
                            n.get().value().intValueExact()));
                }
            }
        }

        Matcher adjective = ADJECTIVE.matcher(text);
        while (adjective.find()) {
            Adjectives.Entry entry = ADJECTIVES.entries().get(adjective.group(3));
            if (entry.needsMostOrLeast() && adjective.group(2) == null) {
                continue;
            }
            if ("date".equals(entry.type())) {
                QueryIntent.ColumnRef dateRef = dateColumn(normalized, chainTables, joined, adjective.group(3)).ref();
                QueryIntent.Direction direction = "least".equals(adjective.group(2))
                        ? (entry.direction() == QueryIntent.Direction.ASC ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC)
                        : entry.direction();
                Integer limit = null;
                for (String candidate : new String[]{adjective.group(1), adjective.group(4)}) {
                    Optional<NumberReader.Reading> n = candidate == null ? Optional.empty() : NumberReader.leading(candidate);
                    if (n.isPresent() && n.get().integral() && n.get().phrase().equals(candidate)) {
                        limit = n.get().value().intValueExact();
                    }
                }
                return Optional.of(new Ranking(dateRef, direction, limit != null ? limit : (singular ? 1 : null)));
            }
            if (!"column".equals(entry.type())) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String word : entry.words()) {
                if (binder.columnMatch(subject, word) instanceof SchemaLexicon.Bound bound
                        && !columns.contains(bound.binding().item())) {
                    columns.add(bound.binding().item());
                }
            }
            if (columns.isEmpty()) {
                throw new DeclineException("\"" + adjective.group(3) + "\" doesn't match a column of " + subject.name() + ".");
            }
            if (columns.size() > 1) {
                throw new DeclineException(ambiguityMessage(adjective.group(3),
                        columns.stream().map(c -> subject.name() + "." + c).toList()));
            }
            note(adjective.group(3), subject.name() + "." + columns.get(0), SchemaLexicon.MatchKind.SYNONYM, interpretations);
            QueryIntent.Direction direction = "least".equals(adjective.group(2))
                    ? (entry.direction() == QueryIntent.Direction.ASC ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC)
                    : entry.direction();
            Integer limit = null;
            for (String candidate : new String[]{adjective.group(1), adjective.group(4)}) {
                Optional<NumberReader.Reading> n = candidate == null ? Optional.empty() : NumberReader.leading(candidate);
                if (n.isPresent() && n.get().integral() && n.get().phrase().equals(candidate)) {
                    limit = n.get().value().intValueExact();
                }
            }
            return Optional.of(new Ranking(qualify(columns.get(0), subject, joined), direction,
                    limit != null ? limit : (singular ? 1 : null)));
        }
        return Optional.empty();
    }

    private Optional<String> extractTrailingColumnPhrase(String text, KnownSchema.Table table,
                                                         Dialect dialect) {
        List<List<String>> owners = SchemaLexicon.ownerWords(table);
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        String names = owners.stream().map(w -> Pattern.quote(String.join(" ", w))).collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("\\b(?:" + names + ")\\s+([a-z0-9 ]+)$");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String phrase = matcher.group(1).trim();
        if (phrase.isEmpty()) {
            return Optional.empty();
        }

        String[] words = phrase.split("\\s+");
        if (words.length > 5) {
            return Optional.empty();
        }
        for (String word : words) {
            if ((NON_COLUMN_WORDS.contains(word) && !"and".equals(word)) || binder.tableNamed(word, dialect).isPresent()) {
                return Optional.empty();
            }
        }
        return Optional.of(phrase);
    }

    private Optional<String> extractStrongProjectionPhrase(String text) {
        Matcher onlyJust = ONLY_JUST.matcher(text);
        if (onlyJust.find()) {
            return Optional.of(onlyJust.group(1));
        }
        Matcher of = OF_PHRASE.matcher(text);
        if (of.find() && !AGGREGATE_PHRASE.matcher(text).find()) {
            return Optional.of(of.group(1));
        }
        return Optional.empty();
    }

    private boolean namesTable(String phrase, Dialect dialect) {
        String words = String.join(" ", Arrays.stream(phrase.trim().split("\\s+"))
                .filter(w -> !PROJECTION_FILLER_WORDS.contains(w))
                .toList());
        return !words.isEmpty() && !binder.tableMatches(words, dialect).hits().isEmpty();
    }

    private List<String> resolveChunks(String rawPhrase, KnownSchema.Table table, List<Interpretation> interpretations) {
        List<String> resolved = new ArrayList<>();
        for (String chunk : rawPhrase.split("\\s*(?:,|\\band\\b)\\s*")) {
            List<String> words = new ArrayList<>();
            for (String word : chunk.trim().split("\\s+")) {
                if (!word.isBlank() && !PROJECTION_FILLER_WORDS.contains(word)) {
                    words.add(word);
                }
            }
            if (words.isEmpty()) {
                continue;
            }
            Optional<String> match = column(table, String.join(" ", words), interpretations);
            if (match.isEmpty() && words.size() > 1) {
                match = column(table, words.get(words.size() - 1), interpretations);
            }
            if (match.isEmpty()) {
                return null;
            }
            resolved.add(match.get());
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private String shapeOf(QueryIntent intent) {
        StringBuilder shape = new StringBuilder(intent.verb().name());
        if (intent.join() != null) {
            shape.append(" + JOIN");
        }
        if (intent.join() != null && intent.join().kind() == QueryIntent.JoinKind.ANTI) {
            shape.append(" (without)");
        }
        if (intent.hasAggregate()
                && intent.selectItems().stream().anyMatch(i -> !i.aggregated())) {
            shape.append(" + GROUP BY");
        }
        if (!intent.having().isEmpty()) {
            shape.append(" + HAVING");
        }
        return shape.toString();
    }

    private List<String> checksFor(boolean joined, int edges) {
        List<String> checks = new ArrayList<>();
        checks.add("Every field named in the question matched a column in the active schema");
        if (joined && edges <= 1) {
            checks.add("Exactly one declared foreign key relates these tables");
        } else if (joined) {
            checks.add("Exactly one shortest chain of declared foreign keys relates these tables");
        }
        return List.copyOf(checks);
    }
}
