package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a natural-language question into a QueryIntent. */
@Component
public class QueryIntentParser {

    static final String JOIN_ORIGIN = "declared foreign key";

    private static final Pattern TEMPORAL = Pattern.compile(
            "\\b(?:last|this|next|past)\\s+(?:month|week|year|day|quarter|decade)\\b"
                    + "|\\b(?:today|yesterday|tomorrow|recently)\\b"
                    + "|\\b(?:in|during|before|after|since|by)\\s+"
                    + "(?:january|february|march|april|may|june|july|august"
                    + "|september|october|november|december)\\b"
                    + "|\\b(?:before|after|since|until)\\s+\\d{4}\\b"
                    + "|\\b(?:date range|last \\d+ days)\\b");

    private static final Pattern COMPARISON = Pattern.compile(
            "\\b([a-z_]+)\\s+(?:is\\s+)?"
                    + "(more than|greater than|larger than|higher than"
                    + "|less than|fewer than|lower than|smaller than"
                    + "|above|over|below|under)\\s+([a-z0-9_]+)\\b");

    private static final Pattern PER_GROUP = Pattern.compile(
            "\\b(?:per|for each|grouped by|group by)\\s+([a-z_ ]+?)\\b");

    private static final Pattern PREPOSITION_VALUE = Pattern.compile(
            "\\b(?:from|in|at|located in|living in|based in|residing in)\\s+([a-z]+(?:\\s+and\\s+[a-z]+)*)\\b");

    private static final Pattern LEADING_DELETE = Pattern.compile(
            "^(?:please )?(?:delete|remove)\\b");

    private static final Pattern STANDALONE_DELETE = Pattern.compile(
            "\\b(?:delete|remove)\\b");

    private static final Pattern LIMIT = Pattern.compile("\\b(?:top|first|limit)\\s+(\\d+)\\b");

    private static final Pattern ORDER_BY = Pattern.compile(
            "\\b(?:order(?:ed)?\\s+by|sort(?:ed)?\\s+by)\\s+([a-z_]+)"
                    + "(\\s+desc(?:ending)?|\\s+asc(?:ending)?)?");

    private static final Pattern ONLY_JUST = Pattern.compile(
            "(?:only|just)\\s+(?:the\\s+)?([a-z][a-z ,]*?)(?:\\s+of\\s+|\\s+from\\s+|\\s+for\\s+|$)");

    private static final Pattern OF_PHRASE = Pattern.compile("^(.*?)\\s+of\\s+");

    private static final Pattern AGGREGATE_PHRASE = Pattern.compile(
            "\\b(average|avg|mean|sum|maximum|max|highest|minimum|min|lowest)\\s+"
                    + "(?:of\\s+)?(?:the\\s+)?([a-z_ ]+?)\\b");

    private static final Pattern WITH_VALUE = Pattern.compile("\\bwith\\s+([a-z_]+)\\s+([a-z0-9_]+)\\b");

    private static final Pattern CONJOINED_NOUN = Pattern.compile("\\b(?:and|plus)\\s+([a-z_]+)\\b");

    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10);

    private static final Map<String, String> AGGREGATE_FUNCTIONS = Map.of(
            "average", "AVG", "avg", "AVG", "mean", "AVG",
            "sum", "SUM",
            "maximum", "MAX", "max", "MAX", "highest", "MAX",
            "minimum", "MIN", "min", "MIN", "lowest", "MIN");

    private static final Set<String> COMPARISON_GREATER = Set.of(
            "more than", "greater than", "larger than", "higher than", "above", "over");

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
            "show", "get", "list", "give", "me", "display", "find");

    private final SchemaBinder binder;
    private final JoinPathResolver joins;
    private final DialectRegistry<SchemaMetadata> metadataByDialect;
    private final DialectRegistry<QueryEmitter> emitters;

    public QueryIntentParser(SchemaBinder binder, JoinPathResolver joins,
                             List<SchemaMetadata> metadata, List<QueryEmitter> emitters) {
        this.binder = binder;
        this.joins = joins;
        this.metadataByDialect = new DialectRegistry<>(metadata, "schema metadata");
        this.emitters = new DialectRegistry<>(emitters, "query emitter");
    }

    public RuleBuildResult parse(String rawInput, Dialect dialect) {
        String text = SchemaBinder.normalize(rawInput);
        List<RuleTrace.Binding> bindings = new ArrayList<>();
        SchemaMetadata metadata = metadataByDialect.get(dialect);

        if (TEMPORAL.matcher(text).find()) {
            return RuleBuildResult.declined(
                    "The question asks about a period of time. This engine does not build date "
                            + "filters, so the question went to the language model instead.",
                    bindings);
        }

        List<KnownSchema.Table> tables = binder.bindTables(text, dialect);
        if (tables.isEmpty()) {
            return RuleBuildResult.declined(
                    "Nothing in the question matches a table in the schema that is currently active.",
                    bindings);
        }
        if (tables.size() > 2) {
            return RuleBuildResult.declined(
                    "The question names " + tables.size() + " tables. This engine joins two at most.",
                    bindings);
        }
        tables.forEach(t -> bindings.add(new RuleTrace.Binding(t.name(), "TABLE", t.name())));

        Matcher conjoined = CONJOINED_NOUN.matcher(text);
        while (conjoined.find()) {
            String word = conjoined.group(1);
            boolean known = binder.tableNamed(word, dialect).isPresent()
                    || NON_COLUMN_WORDS.contains(word)
                    || VALUE_STOPWORDS.contains(word)
                    || tables.stream().anyMatch(t -> binder.bindColumn(t, word).isPresent())
                    || tables.stream().anyMatch(t -> columnHolding(t, word, metadata).isPresent());
            if (!known && word.endsWith("s")) {
                return RuleBuildResult.declined(
                        "\"" + word + "\" isn't a table in the active schema, so this engine can't tell "
                                + "what the question joins " + tables.get(0).name() + " to.",
                        bindings);
            }
        }

        KnownSchema.Table subject = tables.get(0);
        KnownSchema.Table related = tables.size() == 2 ? tables.get(1) : null;

        QueryIntent.JoinSpec joinSpec = null;
        RuleTrace.JoinEdge joinEdge = null;
        if (related != null) {
            JoinPathResolver.JoinResolution resolution = joins.resolve(subject.name(), related.name(), dialect);
            if (resolution instanceof JoinPathResolver.None) {
                return RuleBuildResult.declined(
                        "The schema declares no relationship between " + subject.name() + " and "
                                + related.name() + ", so there is no reliable way to connect them.",
                        bindings);
            }
            if (resolution instanceof JoinPathResolver.Ambiguous ambiguous) {
                String columns = ambiguous.edges().stream()
                        .map(KnownSchema.ForeignKey::fromColumn)
                        .reduce((a, b) -> a + " and " + b)
                        .orElse("");
                return RuleBuildResult.declined(
                        "There is more than one way to connect " + subject.name() + " and "
                                + related.name() + " (" + columns + "), and the question does not "
                                + "say which one it means.",
                        bindings);
            }
            KnownSchema.ForeignKey edge = ((JoinPathResolver.Resolved) resolution).edge();
            joinSpec = joinSpecFor(edge, subject, related);
            joinEdge = new RuleTrace.JoinEdge(edge.fromTable(), edge.fromColumn(),
                    edge.toTable(), edge.toColumn(), JOIN_ORIGIN);
        }

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
        List<QueryIntent.Predicate> having = new ArrayList<>();

        boolean countRequested = COUNT_WORDS.stream().anyMatch(text::contains);

        Optional<String> strongPhrase = extractStrongProjectionPhrase(text);
        List<String> projected = List.of();
        boolean projectionRequested = strongPhrase.isPresent();
        if (projectionRequested) {
            List<String> resolved = resolveChunks(strongPhrase.get(), subject);
            if (resolved == null) {
                return RuleBuildResult.declined(
                        "The question asks for specific fields, but at least one of them is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            projected = resolved;
            projected.forEach(c -> bindings.add(
                    new RuleTrace.Binding(c, "COLUMN", subject.name() + "." + c)));
        }

        if (!projectionRequested && !countRequested) {
            Optional<String> tail = extractTrailingColumnPhrase(text, subject, dialect);
            if (tail.isPresent()) {
                List<String> resolved = resolveChunks(tail.get(), subject);
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

        String aggregateFunction = null;
        String aggregateColumn = null;

        if (!projectionRequested) {
            Matcher agg = AGGREGATE_PHRASE.matcher(text);
            if (agg.find()) {
                aggregateFunction = AGGREGATE_FUNCTIONS.get(agg.group(1));
                Optional<String> col = binder.bindColumn(subject, agg.group(2));
                if (col.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question asks for the " + agg.group(1) + " of \"" + agg.group(2)
                                    + "\", which is not a column of " + subject.name() + ".",
                            bindings);
                }
                aggregateColumn = col.get();
                bindings.add(new RuleTrace.Binding(agg.group(2), "AGGREGATE",
                        aggregateFunction + "(" + subject.name() + "." + aggregateColumn + ")"));
            }
        }

        String groupColumn = null;
        String groupPhrase = null;
        Matcher per = PER_GROUP.matcher(text);
        if (per.find()) {
            groupPhrase = per.group(1).trim();
            Optional<String> col = binder.bindColumn(subject, per.group(1));
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

        Matcher comparison = COMPARISON.matcher(text);
        String countTargetColumn = null;
        if (comparison.find()) {
            String operator = COMPARISON_GREATER.contains(comparison.group(2)) ? ">" : "<";
            QueryIntent.Op op = COMPARISON_GREATER.contains(comparison.group(2))
                    ? QueryIntent.Op.GT : QueryIntent.Op.LT;
            Optional<Integer> literal = numberFrom(comparison.group(3));
            if (literal.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question compares against \"" + comparison.group(3)
                                + "\", which is not a number this engine can read.",
                        bindings);
            }

            Optional<String> lhsColumn = binder.bindColumn(subject, comparison.group(1));
            if (lhsColumn.isPresent()) {
                String lhs = joined ? subject.name() + "." + lhsColumn.get() : lhsColumn.get();
                where.add(new QueryIntent.Predicate(qualify(lhsColumn.get(), subject, joined), op,
                        List.of(QueryIntent.Literal.number(literal.get()))));
                bindings.add(new RuleTrace.Binding(
                        comparison.group(1) + " " + comparison.group(2) + " " + comparison.group(3),
                        "COMPARISON", lhs + " " + operator + " " + literal.get()));
            } else if (joined) {
                countTargetColumn = related.columns().get(0);
                String rendered = "COUNT(" + related.name() + "." + countTargetColumn + ")";
                having.add(new QueryIntent.Predicate(
                        new QueryIntent.Aggregate(QueryIntent.Fn.COUNT,
                                new QueryIntent.ColumnRef(related.name(), countTargetColumn)),
                        op, List.of(QueryIntent.Literal.number(literal.get()))));
                bindings.add(new RuleTrace.Binding(
                        comparison.group(2) + " " + comparison.group(3) + " " + related.name(),
                        "COMPARISON", rendered + " " + operator + " " + literal.get()));
            } else {
                return RuleBuildResult.declined(
                        "The question compares \"" + comparison.group(1) + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
        }

        if (groupColumn != null && aggregateFunction == null && !countRequested && countTargetColumn == null) {
            return RuleBuildResult.declined(
                    "The question asks for a breakdown by \"" + groupPhrase + "\" but not for a total, "
                            + "average or count of anything, so there is nothing to group.",
                    bindings);
        }

        Matcher preposition = PREPOSITION_VALUE.matcher(text);
        while (preposition.find()) {
            List<String> values = new ArrayList<>();
            List<String> storedValues = new ArrayList<>();
            String valueColumn = null;

            for (String candidate : preposition.group(1).split("\s+and\s+")) {
                if (VALUE_STOPWORDS.contains(candidate)
                        || binder.tableNamed(candidate, dialect).isPresent()
                        || isColumnOfEither(candidate, subject, related)) {
                    break;
                }
                Optional<ValueMatch> match = columnHolding(subject, candidate, metadata);
                if (match.isEmpty()) {
                    if (!values.isEmpty()) {
                        break;
                    }
                    return RuleBuildResult.declined(
                            "No column of " + subject.name() + " contains the value \"" + candidate
                                    + "\", so this engine cannot tell which field the question means.",
                            bindings);
                }
                String column = match.get().column();
                if (valueColumn != null && !valueColumn.equals(column)) {
                    return RuleBuildResult.declined(
                            "\"" + values.get(0) + "\" and \"" + candidate + "\" are values of different "
                                    + "columns (" + valueColumn + " and " + column + "), so this "
                                    + "engine cannot tell which one the question filters on.",
                            bindings);
                }
                valueColumn = column;
                values.add(candidate);
                storedValues.add(match.get().stored());
            }

            if (values.isEmpty()) {
                continue;
            }

            List<QueryIntent.Literal> literals = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                literals.add(QueryIntent.Literal.text(values.get(i), storedValues.get(i)));
            }
            where.add(new QueryIntent.Predicate(qualify(valueColumn, subject, joined),
                    values.size() == 1 ? QueryIntent.Op.EQ : QueryIntent.Op.IN, List.copyOf(literals)));
            bindings.add(new RuleTrace.Binding(String.join(" and ", values), "FILTER",
                    subject.name() + "." + valueColumn));
            break;
        }

        Matcher with = WITH_VALUE.matcher(text);
        if (where.isEmpty() && with.find() && !VALUE_STOPWORDS.contains(with.group(1))
                && !NON_COLUMN_WORDS.contains(with.group(1))) {
            Optional<String> column = binder.bindColumn(subject, with.group(1));
            if (column.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question filters on \"" + with.group(1) + "\", which is not a column of "
                                + subject.name() + ".",
                        bindings);
            }
            String value = with.group(2);
            Optional<Integer> number = numberFrom(value);
            QueryIntent.Literal literalValue;
            if (number.isPresent()) {
                literalValue = new QueryIntent.Literal(value, true, number.get());
            } else {
                Optional<String> stored = storedValueOf(subject, column.get(), value, metadata);
                if (stored.isEmpty() && dialect == Dialect.MONGODB) {
                    return RuleBuildResult.declined(
                            "Couldn't confirm how \"" + value + "\" is stored in " + subject.name() + "."
                                    + column.get() + ", and MongoDB matches text exactly, so this "
                                    + "engine won't guess its capitalisation.",
                            bindings);
                }
                literalValue = QueryIntent.Literal.text(value, stored.orElse(value));
            }
            where.add(new QueryIntent.Predicate(qualify(column.get(), subject, joined),
                    QueryIntent.Op.EQ, List.of(literalValue)));
            bindings.add(new RuleTrace.Binding(with.group(1) + " " + value, "FILTER",
                    subject.name() + "." + column.get()));
        }

        if (groupColumn != null) {
            selectItems.add(new QueryIntent.SelectItem(qualify(groupColumn, subject, joined)));
        }
        if (aggregateFunction != null) {
            selectItems.add(new QueryIntent.SelectItem(new QueryIntent.Aggregate(
                    QueryIntent.Fn.valueOf(aggregateFunction),
                    qualify(aggregateColumn, subject, joined))));
        } else if (countTargetColumn != null) {
            for (String col : identifyingColumns(subject)) {
                selectItems.add(0, new QueryIntent.SelectItem(
                        new QueryIntent.ColumnRef(subject.name(), col)));
            }
            selectItems.add(new QueryIntent.SelectItem(new QueryIntent.Aggregate(
                    QueryIntent.Fn.COUNT, new QueryIntent.ColumnRef(related.name(), countTargetColumn))));
        } else if (countRequested) {
            selectItems.add(new QueryIntent.SelectItem(new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null)));
        } else if (!projected.isEmpty()) {
            projected.forEach(c -> selectItems.add(
                    new QueryIntent.SelectItem(qualify(c, subject, joined))));
        } else if (verb == QueryIntent.Verb.SELECT && selectItems.isEmpty()) {
            selectItems.add(new QueryIntent.SelectItem(QueryIntent.Star.INSTANCE));
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
        if (order.find()) {
            Optional<String> column = binder.bindColumn(subject, order.group(1));
            if (column.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks to sort by \"" + order.group(1) + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            QueryIntent.Direction direction = order.group(2) != null && order.group(2).contains("desc")
                    ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC;
            orderBy.add(new QueryIntent.OrderItem(qualify(column.get(), subject, joined), direction));
        }

        Integer limit = null;
        Matcher limitMatcher = LIMIT.matcher(text);
        if (limitMatcher.find()) {
            limit = Integer.parseInt(limitMatcher.group(1));
        }

        QueryIntent intent = new QueryIntent(verb, subject.name(), joinSpec,
                List.copyOf(selectItems), List.copyOf(where), List.copyOf(having),
                List.copyOf(orderBy), limit);

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
                checksFor(joined),
                null);
        return RuleBuildResult.built(sql, trace);
    }

    private QueryIntent.JoinSpec joinSpecFor(KnownSchema.ForeignKey edge,
                                             KnownSchema.Table subject,
                                             KnownSchema.Table related) {
        boolean subjectIsTarget = edge.toTable().equalsIgnoreCase(subject.name());
        String subjectColumn = subjectIsTarget ? edge.toColumn() : edge.fromColumn();
        String relatedColumn = subjectIsTarget ? edge.fromColumn() : edge.toColumn();
        return new QueryIntent.JoinSpec(
                related.name(),
                new QueryIntent.ColumnRef(subject.name(), subjectColumn),
                new QueryIntent.ColumnRef(related.name(), relatedColumn));
    }

    private QueryIntent.ColumnRef qualify(String column, KnownSchema.Table table, boolean joined) {
        return new QueryIntent.ColumnRef(joined ? table.name() : null, column);
    }

    private List<String> identifyingColumns(KnownSchema.Table table) {
        List<String> chosen = new ArrayList<>();
        if (!table.columns().isEmpty()) {
            chosen.add(table.columns().get(0));
        }
        table.columns().stream()
                .filter(c -> c.equalsIgnoreCase("name") || c.equalsIgnoreCase("title")
                        || c.equalsIgnoreCase("label"))
                .findFirst()
                .ifPresent(chosen::add);
        return chosen;
    }

    private boolean isColumnOfEither(String token, KnownSchema.Table a, KnownSchema.Table b) {
        return binder.bindColumn(a, token).isPresent()
                || (b != null && binder.bindColumn(b, token).isPresent());
    }

    private record ValueMatch(String column, String stored) {}

    private Optional<ValueMatch> columnHolding(KnownSchema.Table table, String value,
                                              SchemaMetadata metadata) {
        for (String column : table.columns()) {
            Optional<String> stored = storedValueOf(table, column, value, metadata);
            if (stored.isPresent()) {
                return Optional.of(new ValueMatch(column, stored.get()));
            }
        }
        return Optional.empty();
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

    private Optional<Integer> numberFrom(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (NUMBER_WORDS.containsKey(lower)) {
            return Optional.of(NUMBER_WORDS.get(lower));
        }
        try {
            return Optional.of(Integer.parseInt(lower));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<String> extractTrailingColumnPhrase(String text, KnownSchema.Table table,
                                                         Dialect dialect) {
        Pattern pattern = Pattern.compile(
                "\\b(?:" + Pattern.quote(SchemaBinder.normalize(table.name()))
                        + "|" + Pattern.quote(SchemaBinder.normalize(table.singularName()))
                        + ")\\s+([a-z ]+)$");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String phrase = matcher.group(1).trim();
        if (phrase.isEmpty()) {
            return Optional.empty();
        }

        String[] words = phrase.split("\\s+");
        if (words.length > 3) {
            return Optional.empty();
        }
        for (String word : words) {
            if (NON_COLUMN_WORDS.contains(word) || binder.tableNamed(word, dialect).isPresent()) {
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

    private List<String> resolveChunks(String rawPhrase, KnownSchema.Table table) {
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
            Optional<String> match = binder.bindColumn(table, String.join(" ", words));
            if (match.isEmpty() && words.size() > 1) {
                match = binder.bindColumn(table, words.get(words.size() - 1));
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
        if (intent.hasAggregate()
                && intent.selectItems().stream().anyMatch(i -> !i.aggregated())) {
            shape.append(" + GROUP BY");
        }
        if (!intent.having().isEmpty()) {
            shape.append(" + HAVING");
        }
        return shape.toString();
    }

    private List<String> checksFor(boolean joined) {
        List<String> checks = new ArrayList<>();
        checks.add("Every field named in the question matched a column in the active schema");
        if (joined) {
            checks.add("Exactly one declared foreign key relates these tables");
        }
        return List.copyOf(checks);
    }
}
