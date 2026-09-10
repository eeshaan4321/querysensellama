package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RuleBasedQueryBuilder
 *
 * HOW IT WORKS:
 * The rule engine only claims queries it is 100% confident about.
 * Everything else automatically goes to the LLM fallback.
 *
 * Flow:
 * 0. If the target is MongoDB, defer to the LLM fallback immediately — every rule
 *    below only knows how to emit SQL syntax. Without this check the
 *    builder used to claim "simple-sounding" Mongo requests and return
 *    plain SQL for them, which is silently wrong rather than merely
 *    unhandled. This stays in place until a real Mongo query builder
 *    (Task B, MongoTemplate-based) exists.
 * 1. Normalize input
 * 2. Detect table — if no known table found, go to the LLM fallback
 * 3. Check for complexity signals — if any found, go to the LLM fallback
 * 4. Only then attempt to build SQL from rules
 * 5. Return Optional.empty() at any point of doubt → the LLM fallback handles it
 *
 * SCHEMA KNOWLEDGE: table/column structure comes from {@link KnownSchema},
 * the single central source both this class and OllamaService read from
 * (see KnownSchema's doc for why the previous per-class hardcoded lists
 * caused Bugs 7/8 to only get fixed for one repro sentence at a time,
 * instead of generically). Column matching below loops over each table's
 * REAL column list — it never hardcodes a subset of "the columns we expect
 * someone to ask for."
 */
@Component
public class RuleBasedQueryBuilder {

    // WHY this dependency: a static schema list can tell us COLUMN NAMES
    // exist, but not what DATA is actually in them. The safety net in
    // isSafeLocationCondition() needs real city values (e.g. "Mumbai",
    // "Pune") to detect when a request clearly references a real location
    // that the clause matcher failed to capture — see that method's doc.
    private final SchemaMetadataService schemaMetadataService;

    public RuleBasedQueryBuilder(SchemaMetadataService schemaMetadataService) {
        this.schemaMetadataService = schemaMetadataService;
    }

    // -----------------------------------------------------------------------
    // PROJECTION FILLER WORDS
    // -----------------------------------------------------------------------
    // Words that can appear inside a captured projection phrase without
    // being an attempted column reference — e.g. "show" in "show only the
    // email of...". A word here is silently ignored; a word NOT here that
    // still doesn't resolve to a real column is treated as an ambiguous,
    // unresolvable request (see resolveChunks).
    private static final Set<String> PROJECTION_FILLER_WORDS = Set.of(
            "show", "get", "list", "fetch", "find", "select", "display",
            "retrieve", "give", "me", "the", "a", "an", "all"
    );

    // -----------------------------------------------------------------------
    // MAIN ENTRY POINT
    // -----------------------------------------------------------------------

    public Optional<String> tryBuild(String input, String databaseType) {

        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        // Every builder method below emits SQL syntax only — claiming a
        // Mongo request here would return syntactically valid but wrong-
        // dialect output. Defer to the LLM fallback until a real Mongo builder exists.
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return Optional.empty();
        }

        String normalized = input.toLowerCase().trim();

        // Step 1: Detect table — anchor of every query
        // If no known table found, we can't build anything → the LLM fallback
        Optional<String> table = detectTable(normalized);
        if (table.isEmpty()) {
            return Optional.empty();
        }

        String tableName = table.get();

        // Step 2: Check complexity BEFORE attempting to build
        // If anything complex is detected → the LLM fallback handles it
        // Rule engine only claims simple, unambiguous queries
        if (isSimpleCount(normalized)) {
            return buildCountQuery(normalized, tableName);
        }

        if (isSimpleSelect(normalized)) {
            return buildSelectQuery(normalized, tableName);
        }

        if (isSimpleDelete(normalized)) {
            return buildDeleteQuery(normalized, tableName);
        }

        // INSERT always goes to the LLM fallback — needs specific values we can't infer
        // Everything else we're not confident about → the LLM fallback
        return Optional.empty();
    }


    // -----------------------------------------------------------------------
    // CONFIDENCE-GATED INTENT DETECTION
    // Each method checks BOTH intent AND absence of complexity.
    // If the query has complexity signals, we return false even if intent matches.
    // -----------------------------------------------------------------------

    private boolean isSimpleCount(String input) {
        boolean hasCountIntent = input.contains("count") ||
                input.contains("how many") ||
                input.contains("total number") ||
                input.contains("number of");
        return hasCountIntent && !hasAnyComplexity(input);
    }

    /**
     * SELECT is the rule engine's default query intent — not just for
     * inputs that literally say "show"/"get"/etc, but for bare noun-phrase
     * requests too ("customers from mumbai" implies "show me customers
     * from mumbai" just as much as "show customers from mumbai" does). The
     * previous version required one of those explicit verbs to be present
     * anywhere in the input, which meant every phrasing in the "WHERE
     * clause" bug's own test matrix that omitted a lead verb was rejected
     * before ever reaching the location matcher — a different failure mode
     * than the one that bug was ostensibly about, but the same root cause:
     * a hardcoded, incomplete list standing in for "what a simple request
     * looks like." Anything that isn't a count/delete request and has no
     * complexity signal is treated as an implicit SELECT.
     */
    private boolean isSimpleSelect(String input) {
        boolean hasDeleteIntent = input.contains("delete") || input.contains("remove");
        return !hasDeleteIntent && !hasAnyComplexity(input);
    }

    private boolean isSimpleDelete(String input) {
        boolean hasDeleteIntent = input.contains("delete") ||
                input.contains("remove");
        return hasDeleteIntent && !hasAnyComplexity(input);
    }

    /**
     * Single source of truth for complexity detection.
     *
     * WHY this approach is correct:
     * Instead of listing every possible complex case (impossible to complete),
     *      * we define what makes a query simple. Any signal of complexity = the LLM fallback.
     * The rule engine only handles what it can guarantee to get right.
     */
    private boolean hasAnyComplexity(String input) {

        // Month names — any date-specific query goes to the LLM fallback
        if (input.matches(".*(january|february|march|april|may|june|july|" +
                "august|september|october|november|december).*"))
            return true;

        // Relative time expressions
        if (input.matches(".*(last|this|next)\\s+(month|week|year|day|two|three).*"))
            return true;

        // Specific time words
        if (input.contains("today") || input.contains("yesterday") ||
                input.contains("recent") || input.contains("latest") ||
                input.contains("oldest"))
            return true;

        // Comparison expressions
        if (input.matches(".*(more|less|greater|higher|lower)\\s+than.*"))
            return true;
        if (input.matches(".*(above|below|between|at least|at most).*"))
            return true;

        // Join / relational expressions
        if (input.contains("who")  || input.contains("which") ||
                input.contains("join") || input.contains("across") ||
                input.contains("along with") || input.contains("related"))
            return true;

        // Aggregation beyond simple count
        if (input.contains("average") || input.contains("sum")     ||
                input.contains("maximum") || input.contains("minimum") ||
                input.contains("group by"))
            return true;

        // Multiple tables mentioned — needs a JOIN, beyond rule engine scope.
        // WHY singular AND plural: detectTable() already tolerates "order"
        // as well as "orders" when picking the primary table, so this check
        // has to match that tolerance — otherwise a request like "total
        // order amount for each customer in delhi" (singular "order"/
        // "customer", no plural, no other complexity keyword) would slip
        // through as if only one table were involved, and get confidently
        // mishandled by the rule engine instead of routed to the LLM fallback for the
        // join/aggregate it actually needs (Bug 9).
        long tableCount = KnownSchema.TABLES.stream()
                .filter(t -> input.matches(".*\\b" + t.name() + "\\b.*")
                        || input.matches(".*\\b" + t.singularName() + "\\b.*"))
                .count();
        if (tableCount > 1) return true;

        return false;
    }


    // -----------------------------------------------------------------------
    // TABLE DETECTION
    // -----------------------------------------------------------------------

    private Optional<String> detectTable(String input) {
        for (KnownSchema.Table table : KnownSchema.TABLES) {
            if (input.matches(".*\\b" + table.name() + "\\b.*")
                    || input.matches(".*\\b" + table.singularName() + "\\b.*")) {
                return Optional.of(table.name());
            }
        }
        return Optional.empty();
    }


    // -----------------------------------------------------------------------
    // QUERY BUILDERS
    // -----------------------------------------------------------------------

    private Optional<String> buildSelectQuery(String input, String table) {
        ProjectionResult projection = detectColumns(input, table);
        if (projection.requested() && !projection.resolved()) {
            // An explicit "only/just X" or "X of ..." phrase was present but
            // couldn't be confidently resolved against the real schema —
            // don't guess, don't silently fall back to SELECT *. See Bug 7.
            return Optional.empty();
        }
        String columnClause = projection.resolved() ? projection.columnClause() : "*";

        String sql = "SELECT " + columnClause + " FROM " + table;

        Optional<String> condition = detectCondition(input);
        if (!isSafeLocationCondition(input, condition)) {
            // A known real location value is mentioned but wasn't reflected
            // in the WHERE clause we're about to build — see Bug 8/10.
            return Optional.empty();
        }
        if (condition.isPresent()) {
            sql += " WHERE " + condition.get();
        }

        Optional<String> orderBy = detectOrderBy(input);
        if (orderBy.isPresent()) {
            sql += " ORDER BY " + orderBy.get();
        }

        Optional<Integer> limit = detectLimit(input);
        if (limit.isPresent()) {
            sql += " LIMIT " + limit.get();
        }

        return Optional.of(sql + ";");
    }

    private Optional<String> buildCountQuery(String input, String table) {
        String sql = "SELECT COUNT(*) FROM " + table;

        Optional<String> condition = detectCondition(input);
        if (!isSafeLocationCondition(input, condition)) {
            return Optional.empty();
        }
        if (condition.isPresent()) {
            sql += " WHERE " + condition.get();
        }

        return Optional.of(sql + ";");
    }

    private Optional<String> buildDeleteQuery(String input, String table) {
        // Never build DELETE without WHERE — too dangerous
        Optional<String> condition = detectCondition(input);
        if (condition.isEmpty()) {
            return Optional.empty();
        }
        if (!isSafeLocationCondition(input, condition)) {
            return Optional.empty();
        }
        return Optional.of("DELETE FROM " + table + " WHERE " + condition.get() + ";");
    }


    // -----------------------------------------------------------------------
    // CONDITION DETECTION (WHERE clause)
    // -----------------------------------------------------------------------

    // WHY no table-noun anchor (e.g. "customers from X"): the previous round
    // required the preposition to immediately follow the literal table noun,
    // which broke the moment any other word sat in between (a projected
    // column word, "that are", etc). The rule engine only ever reaches this
    // point after hasAnyComplexity() has already ruled out ambiguous
    // phrasing, so searching the whole input for one of these prepositions
    // is a bounded, acceptable generalization — and isSafeLocationCondition()
    // below is the real backstop against a wrong or missed capture.
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "\\b(?:from|located\\s+in|living\\s+in|based\\s+in|residing\\s+in|in|at)\\s+" +
                    "([a-z]+(?:\\s*,\\s*(?!and\\b|or\\b)[a-z]+)*(?:\\s*,?\\s*(?:and|or)\\s+[a-z]+)?)"
    );

    private Optional<String> detectCondition(String input) {

        // Pattern: "where <column> is/= <value>"
        Pattern wherePattern = Pattern.compile(
                "where\\s+(\\w+)\\s*(is|=|>|<|>=|<=|equals|like)\\s+([\\w@.]+)"
        );
        Matcher m = wherePattern.matcher(input);
        if (m.find()) {
            String column         = resolveColumn(m.group(1));
            String operator       = resolveOperator(m.group(2));
            String value          = m.group(3);
            String formattedValue = isNumeric(value) ? value : "'" + value + "'";
            return Optional.of(column + " " + operator + " " + formattedValue);
        }

        // Pattern: "from/in/at/living in/located in/based in/residing in <value(s)>"
        // WHY a generalized preposition group instead of a literal list per
        // phrasing: any new single- or double-word preposition can be added
        // in one place here, rather than needing its own regex/branch — the
        // exact gap that left Bug 8 only half-fixed last round. Supports
        // compound values ("mumbai and ahmedabad", "mumbai, pune, and
        // delhi") via IN(...) rather than silently keeping only the first
        // one (Bug 10).
        Matcher location = LOCATION_PATTERN.matcher(input);
        if (location.find()) {
            List<String> values = splitConjunction(location.group(1)).stream()
                    .filter(v -> !KnownSchema.tableNames().contains(v))
                    .toList();
            if (values.size() == 1) {
                return Optional.of("city = '" + values.get(0) + "'");
            }
            if (values.size() > 1) {
                String inList = values.stream()
                        .map(v -> "'" + v + "'")
                        .collect(Collectors.joining(", "));
                return Optional.of("city IN (" + inList + ")");
            }
        }

        // Pattern: "with status active"
        Pattern withPattern = Pattern.compile("with\\s+(\\w+)\\s+(\\w+)");
        Matcher withMatcher = withPattern.matcher(input);
        if (withMatcher.find()) {
            String column         = resolveColumn(withMatcher.group(1));
            String value          = withMatcher.group(2);
            String formattedValue = isNumeric(value) ? value : "'" + value + "'";
            return Optional.of(column + " = " + formattedValue);
        }

        return Optional.empty();
    }

    private List<String> splitConjunction(String raw) {
        return Arrays.stream(raw.split("\\s*(?:,|\\band\\b|\\bor\\b)\\s*"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * The general safeguard behind Bug 8/10's fix: a location-style
     * preposition list is inherently incomplete (there's always another
     * "residing in"-shaped phrasing not yet covered) and a compound value
     * list is inherently fiddlier than a single value. Rather than chase
     * every phrasing individually, this checks the result against reality —
     * if a real, known city (queried live from `customers.city`, not a
     * hardcoded guess) is mentioned in the input but doesn't appear in the
     * condition we're about to use, something was missed. Don't guess or
     * silently drop it: bail so the caller routes to the LLM fallback instead.
     *
     * A confidently wrong query is worse than an honest low-confidence one
     * — this is a general defense against the whole "WHERE clause silently
     * dropped/incomplete" bug class, not a per-phrasing patch.
     */
    private boolean isSafeLocationCondition(String input, Optional<String> condition) {
        List<String> knownCities = schemaMetadataService.getDistinctValues("customers", "city");
        String conditionLower = condition.map(String::toLowerCase).orElse("");
        for (String city : knownCities) {
            String cityLower = city.toLowerCase();
            if (input.matches(".*\\b" + Pattern.quote(cityLower) + "\\b.*")
                    && !conditionLower.contains(cityLower)) {
                return false;
            }
        }
        return true;
    }


    // -----------------------------------------------------------------------
    // COLUMN PROJECTION DETECTION ("only names of...", "email of...", "customer ids")
    // -----------------------------------------------------------------------
    // WHY this replaced the previous version (Bug 7, reopened): the prior
    // fix only recognized the literal words "name"/"names" because it
    // matched against a hand-picked alias map instead of the table's real
    // column list, and only recognized "only/just X" phrasing, not "X of Y"
    // or compound noun phrasing ("customer ids"). This version resolves
    // ANY word/phrase generically against KnownSchema's real columns for
    // whichever table was detected, and supports multiple strategies plus
    // multi-column projection.

    private record ProjectionResult(boolean requested, boolean resolved, String columnClause) {
        static ProjectionResult none() {
            return new ProjectionResult(false, false, null);
        }
        static ProjectionResult ambiguous() {
            return new ProjectionResult(true, false, null);
        }
        static ProjectionResult of(List<String> columns) {
            return new ProjectionResult(true, true, String.join(", ", columns));
        }
    }

    private static final Pattern ONLY_JUST_PATTERN = Pattern.compile(
            "(?:only|just)\\s+(?:the\\s+)?([a-z][a-z ,]*?)(?:\\s+of\\s+|\\s+from\\s+|\\s+for\\s+|$)"
    );
    private static final Pattern OF_PATTERN = Pattern.compile("^(.*?)\\s+of\\s+");

    private ProjectionResult detectColumns(String input, String tableName) {
        Optional<KnownSchema.Table> tableOpt = KnownSchema.findTable(tableName);
        if (tableOpt.isEmpty()) {
            return ProjectionResult.none();
        }
        List<String> columns = tableOpt.get().columns();

        // Strong signals: "only/just X" or "X of Y" — an explicit,
        // deliberate ask for specific columns. If present, every named
        // noun must resolve to a real column or this is ambiguous (bail to
        // the LLM fallback) rather than silently defaulting to SELECT * and dropping
        // the request.
        Optional<String> strongPhrase = extractStrongProjectionPhrase(input);
        if (strongPhrase.isPresent()) {
            List<String> resolved = resolveChunks(strongPhrase.get(), columns);
            return resolved == null ? ProjectionResult.ambiguous() : ProjectionResult.of(resolved);
        }

        // Weak/opportunistic signal: "<table noun> <column word(s)>"
        // compound, e.g. "customer ids", "order amounts". Only used when it
        // actually resolves to a real column — never triggers an ambiguous
        // bail on its own, since most sentences legitimately have some
        // other (non-column) word after the table noun ("customers living
        // in mumbai" must NOT be treated as a failed projection attempt).
        Optional<String> compoundWord = extractTableColumnCompound(input, tableOpt.get());
        if (compoundWord.isPresent()) {
            Optional<String> resolved = resolveColumnGeneric(compoundWord.get(), columns);
            if (resolved.isPresent()) {
                return ProjectionResult.of(List.of(resolved.get()));
            }
        }

        return ProjectionResult.none();
    }

    private Optional<String> extractStrongProjectionPhrase(String input) {
        Matcher onlyJust = ONLY_JUST_PATTERN.matcher(input);
        if (onlyJust.find()) {
            return Optional.of(onlyJust.group(1));
        }
        Matcher of = OF_PATTERN.matcher(input);
        if (of.find()) {
            return Optional.of(of.group(1));
        }
        return Optional.empty();
    }

    private Optional<String> extractTableColumnCompound(String input, KnownSchema.Table table) {
        Pattern pattern = Pattern.compile(
                "\\b(?:" + Pattern.quote(table.name()) + "|" + Pattern.quote(table.singularName())
                        + ")\\s+([a-z]+)\\b");
        Matcher m = pattern.matcher(input);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * Resolves a raw projection phrase (possibly multiple nouns joined by
     * "," / "and") into real column names.
     *
     * @return the resolved column list, or {@code null} if any non-filler
     *         chunk couldn't be matched to a real column — signals
     *         "ambiguous" to the caller, distinct from an empty list.
     */
    private List<String> resolveChunks(String rawPhrase, List<String> columns) {
        List<String> resolved = new ArrayList<>();
        for (String chunk : rawPhrase.split("\\s*(?:,|\\band\\b)\\s*")) {
            List<String> words = new ArrayList<>();
            for (String w : chunk.trim().split("\\s+")) {
                if (!w.isBlank() && !PROJECTION_FILLER_WORDS.contains(w)) {
                    words.add(w);
                }
            }
            if (words.isEmpty()) {
                continue; // this chunk was pure filler ("show", "the", ...) — not a failure
            }
            Optional<String> match = resolveColumnGeneric(String.join("_", words), columns);
            if (match.isEmpty() && words.size() > 1) {
                match = resolveColumnGeneric(words.get(words.size() - 1), columns);
            }
            if (match.isEmpty()) {
                return null; // a real (non-filler) noun that matches no known column
            }
            resolved.add(match.get());
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Resolves a single word/compound token to a real column name, trying:
     * exact match, singular form (plural stripped), then — for compound
     * column names like "customer_id"/"order_date" — an unambiguous suffix
     * match (e.g. "id" -> "order_id" only when exactly one column ends
     * that way).
     */
    private Optional<String> resolveColumnGeneric(String rawToken, List<String> columns) {
        String normalized = rawToken.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        for (String col : columns) {
            if (col.equalsIgnoreCase(normalized)) {
                return Optional.of(col);
            }
        }

        String singular = normalized.endsWith("s") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1) : normalized;
        for (String col : columns) {
            if (col.equalsIgnoreCase(singular)) {
                return Optional.of(col);
            }
        }

        List<String> suffixMatches = columns.stream()
                .filter(col -> col.toLowerCase().endsWith("_" + singular)
                        || col.toLowerCase().endsWith("_" + normalized))
                .distinct()
                .toList();
        if (suffixMatches.size() == 1) {
            return Optional.of(suffixMatches.get(0));
        }

        return Optional.empty();
    }


    // -----------------------------------------------------------------------
    // ORDER BY DETECTION
    // -----------------------------------------------------------------------

    private Optional<String> detectOrderBy(String input) {
        Pattern orderPattern = Pattern.compile(
                "(order(?:ed)?\\s+by|sort(?:ed)?\\s+by)\\s+(\\w+)" +
                        "(\\s+desc(?:ending)?|\\s+asc(?:ending)?)?"
        );
        Matcher m = orderPattern.matcher(input);
        if (m.find()) {
            String column    = resolveColumn(m.group(2));
            String direction = (m.group(3) != null &&
                    m.group(3).contains("desc")) ? "DESC" : "ASC";
            return Optional.of(column + " " + direction);
        }
        return Optional.empty();
    }


    // -----------------------------------------------------------------------
    // LIMIT DETECTION
    // -----------------------------------------------------------------------

    private Optional<Integer> detectLimit(String input) {
        Pattern limitPattern = Pattern.compile("(top|first|limit|only)\\s+(\\d+)");
        Matcher m = limitPattern.matcher(input);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(2)));
        }
        return Optional.empty();
    }


    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    /** Used only by wherePattern/withPattern's explicit "column word" capture — not the generic projection path. */
    private String resolveColumn(String word) {
        return word.toLowerCase();
    }

    private String resolveOperator(String op) {
        return switch (op.toLowerCase()) {
            case "is", "equals" -> "=";
            default -> op;
        };
    }

    private boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
