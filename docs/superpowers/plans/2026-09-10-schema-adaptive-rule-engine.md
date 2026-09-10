# Schema-Adaptive Rule Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the rule engine decline based on whether it can bind a question to the *active* schema rather than on a hardcoded English word list, and let it build single-hop joins whose `ON` clause is read from a declared foreign key.

**Architecture:** Phrase binders resolve every noun in the question against the active `SchemaSnapshot` into a `QueryIntent` — a record in which every element is already a real table or column. `SqlEmitter` turns that into SQL; `RuleTrace` records how, or why the engine declined. `RuleBasedQueryBuilder` becomes a thin orchestrator over these pieces.

**Tech Stack:** Java 17 records + sealed interfaces, Spring Boot 3, JUnit 5 + AssertJ + Mockito (no live MySQL in any test), React 19, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-10-schema-adaptive-rule-engine-design.md`

## Global Constraints

- **No test may require a running MySQL.** Mock `JdbcTemplate`/`SchemaMetadataService`, matching the 86 schema-upload tests.
- **`backend/src/test/java/com/querysense/backend/querybuilder/RuleBasedQueryBuilderSchemaTest.java` must stay green and UNEDITED.** It is the regression guard. If a task appears to require editing it, stop and report instead.
- **Confidence stays a flat `0.95`** for every rule-engine answer. Do not add a tier (spec D4).
- **MongoDB behaviour is unchanged.** `build` returns declined for any `"MongoDB"` databaseType, exactly as `tryBuild` does today.
- **Never emit unqualified columns when a join is present, and never qualify when it is absent.** Existing outputs like `SELECT * FROM customers;` must stay byte-identical.
- **Wording rule:** no user-facing string in `RuleTrace` (decline reasons, shape labels, binding kinds) may contain `accessType`, `EXPLAIN`, `possible_keys`, `filesort`, `eq_ref`, or a teammate name. Enforced by test in Task 6.
- **Preserve the WHY comments.** `RuleBasedQueryBuilder`'s comments encode Bugs 7/8/9/10. When logic moves to a new class, its explaining comment moves with it (spec §6.4).
- Commit after every task. Do not push.

---

## File Structure

**Backend — created:**

| File | Responsibility |
|---|---|
| `querybuilder/SchemaBinder.java` | Noun/phrase → real table or column against the active schema |
| `querybuilder/JoinPathResolver.java` | Table pair → declared FK edge, or None/Ambiguous |
| `querybuilder/QueryIntent.java` | The schema-bound structural record + its nested records |
| `querybuilder/SqlEmitter.java` | `QueryIntent` → SQL string. Knows no English |
| `querybuilder/QueryIntentParser.java` | Runs the binders, produces `QueryIntent` or a decline reason |
| `querybuilder/RuleBuildResult.java` | Built(sql, trace) or Declined(trace) |
| `dto/RuleTrace.java` | What the Analysis screen renders |

**Backend — modified:**

| File | Change |
|---|---|
| `querybuilder/RuleBasedQueryBuilder.java` | Becomes an orchestrator; adds `build`, keeps `tryBuild` as a delegate |
| `dto/QueryResponse.java` | Gains `ruleTrace` |
| `service/impl/QueryGenerationServiceImpl.java` | Calls `build`, carries the trace onto the response |

**Frontend:**

| File | Change |
|---|---|
| `components/analysis/RuleTracePanel.jsx` + `.css` | Create |
| `api/contracts.js` | `QueryBuildResponse` typedef gains `ruleTrace` |
| `api/queryBuilder.js` | Pass `ruleTrace` through |
| `mock/fixtures.js` | `mockBuildQuery` returns a trace |
| `pages/AnalysisPage.jsx` | Render the panel below `VerdictBanner` |
| `components/analysis/RuleTracePanel.test.jsx` | Create |

---

### Task 1: `SchemaBinder` — bind nouns to the active schema

Closes the `order_items` mis-resolution defect (PROJECT.md §5).

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SchemaBinderTest.java`

**Interfaces:**
- Consumes: `ActiveSchemaProvider.tables()`, `KnownSchema.Table`
- Produces:
  - `List<KnownSchema.Table> bindTables(String normalizedText)` — most-specific wins, contained matches dropped
  - `Optional<String> bindColumn(KnownSchema.Table table, String phrase)`
  - `static String normalize(String s)` — lowercase, `[_\s]+` → single space, trimmed

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaBinderTest {

    private SchemaBinder binder;

    @BeforeEach
    void setUp() {
        binder = new SchemaBinder(new ActiveSchemaProvider());
    }

    @Test
    @DisplayName("multi-word table beats the shorter table nested inside it")
    void orderItemsBeatsOrders() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items")))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("the underscored spelling resolves to the same table")
    void underscoredSpellingResolvesIdentically() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order_items")))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("a genuine two-table question binds both")
    void bindsTwoDistinctTables() {
        assertThat(binder.bindTables(SchemaBinder.normalize("customers and their orders")))
                .extracting(KnownSchema.Table::name)
                .containsExactlyInAnyOrder("customers", "orders");
    }

    @Test
    @DisplayName("singular and -ies plurals both bind")
    void singularAndIesPlural() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show every category")))
                .extracting(KnownSchema.Table::name)
                .containsExactly("categories");
    }

    @Test
    @DisplayName("columns resolve exactly, by singular, and by unambiguous suffix")
    void bindsColumns() {
        KnownSchema.Table orders = KnownSchema.ORDERS;
        assertThat(binder.bindColumn(orders, "status")).contains("status");
        assertThat(binder.bindColumn(orders, "order date")).contains("order_date");
        assertThat(binder.bindColumn(orders, "nonsense")).isEmpty();
    }

    @Test
    @DisplayName("a word matching no table binds nothing")
    void unknownNounBindsNothing() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all invoices"))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SchemaBinderTest`
Expected: FAIL — `SchemaBinder` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
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
 * schema. Replaces SchemaLookup's use from the rule engine — SchemaLookup
 * itself stays, since OllamaService still uses it.
 *
 * WHY MOST-SPECIFIC RATHER THAN FIRST-FOUND (PROJECT.md §5, order_items):
 * the old matcher returned the first table whose name or singular appeared
 * as a whole word. "show all order items" matched `orders` on the word
 * "order" sitting inside the phrase "order items", and `order_items` could
 * never match at all because an underscored identifier does not appear in
 * prose. Two fixes, both here: normalize underscores and spaces to the same
 * form on BOTH sides of the comparison, and drop any match whose span is
 * contained inside a longer match's span.
 */
@Component
public class SchemaBinder {

    private final ActiveSchemaProvider activeSchema;

    public SchemaBinder(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    /** Lowercase, and collapse underscores and runs of whitespace to one space. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", " ").trim();
    }

    private record Span(int start, int end, KnownSchema.Table table) {
        boolean containedIn(Span other) {
            return start >= other.start && end <= other.end && !(start == other.start && end == other.end);
        }
    }

    /** @param normalizedText must already have been through {@link #normalize}. */
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

    private Optional<Span> longestSpanFor(String text, KnownSchema.Table table) {
        // Plural first: it is the longer form, so when both match the plural
        // span wins and the singular's contained span is never considered.
        return firstMatch(text, normalize(table.name()), table)
                .or(() -> firstMatch(text, normalize(table.singularName()), table));
    }

    private Optional<Span> firstMatch(String text, String term, KnownSchema.Table table) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(text);
        return m.find() ? Optional.of(new Span(m.start(), m.end(), table)) : Optional.empty();
    }

    /**
     * Resolve one word or compound token to a real column: exact, then
     * singular, then an UNAMBIGUOUS suffix match ("order date" ->
     * order_date). Ambiguity returns empty so the caller declines rather
     * than guesses — the Bug 7 rule, kept.
     */
    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        String token = normalize(phrase);
        if (token.isEmpty()) return Optional.empty();
        String underscored = token.replace(' ', '_');

        for (String col : table.columns()) {
            if (col.equalsIgnoreCase(token) || col.equalsIgnoreCase(underscored)) {
                return Optional.of(col);
            }
        }
        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1) : underscored;
        for (String col : table.columns()) {
            if (col.equalsIgnoreCase(singular)) {
                return Optional.of(col);
            }
        }
        List<String> suffix = table.columns().stream()
                .filter(c -> c.toLowerCase(Locale.ROOT).endsWith("_" + singular))
                .distinct()
                .toList();
        return suffix.size() == 1 ? Optional.of(suffix.get(0)) : Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SchemaBinderTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java \
        backend/src/test/java/com/querysense/backend/querybuilder/SchemaBinderTest.java
git commit -m "feat(rules): bind nouns to the active schema, most-specific wins

Closes the order_items mis-resolution: underscores and spaces normalize to
the same form on both sides, and a match contained inside a longer match is
dropped rather than winning by declaration order."
```

---

### Task 2: `JoinPathResolver` — a table pair to a declared foreign key

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/JoinPathResolver.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/JoinPathResolverTest.java`

**Interfaces:**
- Consumes: `ActiveSchemaProvider.foreignKeys()`, `KnownSchema.ForeignKey`
- Produces: `JoinResolution resolve(String tableA, String tableB)` — a sealed interface with `Resolved(ForeignKey edge)`, `None()`, `Ambiguous(List<ForeignKey> edges)`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinPathResolverTest {

    private ActiveSchemaProvider activeSchema;
    private JoinPathResolver resolver;

    @BeforeEach
    void setUp() {
        activeSchema = new ActiveSchemaProvider();
        resolver = new JoinPathResolver(activeSchema);
    }

    @Test
    @DisplayName("one declared FK resolves, whichever order the tables are given")
    void singleEdgeResolvesInEitherDirection() {
        assertThat(resolver.resolve("orders", "customers"))
                .isInstanceOfSatisfying(JoinPathResolver.Resolved.class, r ->
                        assertThat(r.edge().fromColumn()).isEqualTo("customer_id"));
        assertThat(resolver.resolve("customers", "orders"))
                .isInstanceOf(JoinPathResolver.Resolved.class);
    }

    @Test
    @DisplayName("no declared relationship means None — a name convention is not a join")
    void unrelatedTablesResolveToNone() {
        assertThat(resolver.resolve("customers", "suppliers"))
                .isInstanceOf(JoinPathResolver.None.class);
    }

    @Test
    @DisplayName("two FKs between the same pair are Ambiguous, not first-wins")
    void twoEdgesAreAmbiguous() {
        activeSchema.publish(twoPathSchema());

        assertThat(resolver.resolve("orders", "addresses"))
                .isInstanceOfSatisfying(JoinPathResolver.Ambiguous.class, a ->
                        assertThat(a.edges()).hasSize(2));
    }

    @Test
    @DisplayName("a self-referencing FK never produces a join")
    void selfReferencingEdgeExcluded() {
        assertThat(resolver.resolve("employees", "employees"))
                .isInstanceOf(JoinPathResolver.None.class);
    }

    private static SchemaSnapshot twoPathSchema() {
        return new SchemaSnapshot(
                "upload-2", "two-path.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("orders", List.of("id", "billing_address_id", "shipping_address_id")),
                        new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(new KnownSchema.ForeignKey("orders", "billing_address_id", "addresses", "id"),
                        new KnownSchema.ForeignKey("orders", "shipping_address_id", "addresses", "id")),
                Map.of("orders", 100, "addresses", 100), Instant.now());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=JoinPathResolverTest`
Expected: FAIL — `JoinPathResolver` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JoinPathResolver
 *
 * Turns a pair of bound tables into the DECLARED foreign key that relates
 * them. This is the whole basis of the rule engine's join claim: the ON
 * clause is read from the schema's own referential constraints, never
 * inferred from a naming convention like `client_id`.
 *
 * WHY 2+ EDGES DECLINE RATHER THAN PICK THE FIRST:
 * `orders.billing_address_id` and `orders.shipping_address_id` both point at
 * `addresses.id`. "orders with addresses" genuinely has two right answers,
 * and picking one would be exactly the confident-but-wrong output at
 * RULE_ENGINE 0.95 that PROJECT.md §5 says the project's central claim
 * cannot survive.
 */
@Component
public class JoinPathResolver {

    public sealed interface JoinResolution permits Resolved, None, Ambiguous {}
    public record Resolved(KnownSchema.ForeignKey edge) implements JoinResolution {}
    public record None() implements JoinResolution {}
    public record Ambiguous(List<KnownSchema.ForeignKey> edges) implements JoinResolution {}

    private final ActiveSchemaProvider activeSchema;

    public JoinPathResolver(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public JoinResolution resolve(String tableA, String tableB) {
        List<KnownSchema.ForeignKey> edges = activeSchema.foreignKeys().stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .filter(fk -> relates(fk, tableA, tableB))
                .toList();

        if (edges.isEmpty()) return new None();
        if (edges.size() > 1) return new Ambiguous(edges);
        return new Resolved(edges.get(0));
    }

    private boolean relates(KnownSchema.ForeignKey fk, String a, String b) {
        return (fk.fromTable().equalsIgnoreCase(a) && fk.toTable().equalsIgnoreCase(b))
                || (fk.fromTable().equalsIgnoreCase(b) && fk.toTable().equalsIgnoreCase(a));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=JoinPathResolverTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/JoinPathResolver.java \
        backend/src/test/java/com/querysense/backend/querybuilder/JoinPathResolverTest.java
git commit -m "feat(rules): resolve a table pair to its declared foreign key

Two edges between the same pair decline as ambiguous rather than picking
one; a self-referencing FK never produces a join."
```

---

### Task 3: `QueryIntent` and `SqlEmitter`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java`
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/SqlEmitter.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SqlEmitterTest.java`

**Interfaces:**
- Produces:
  - `QueryIntent` record: `(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems, List<Predicate> where, List<Predicate> having, List<String> orderBy, Integer limit)`
  - nested: `enum Verb { SELECT, DELETE }`, `record JoinSpec(String joinedTable, String leftColumn, String rightColumn)`, `record SelectItem(String column, String aggregate)`, `record Predicate(String lhs, String operator, String literal, boolean quoted)`
  - `String SqlEmitter.emit(QueryIntent intent)`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlEmitterTest {

    private final SqlEmitter emitter = new SqlEmitter();

    @Test
    @DisplayName("a single-table select stays unqualified and byte-identical to today's output")
    void singleTableSelectIsUnqualified() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new QueryIntent.SelectItem("*", null)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("a join qualifies every column and reads its ON clause from the intent")
    void joinQualifiesColumns() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", "customers.id", "orders.customer_id"),
                List.of(new QueryIntent.SelectItem("customers.name", null)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT customers.name FROM customers "
                        + "JOIN orders ON customers.id = orders.customer_id;");
    }

    @Test
    @DisplayName("GROUP BY covers every non-aggregated projection, so ONLY_FULL_GROUP_BY holds")
    void groupByCoversNonAggregatedProjections() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", "customers.id", "orders.customer_id"),
                List.of(new QueryIntent.SelectItem("customers.id", null),
                        new QueryIntent.SelectItem("customers.name", null),
                        new QueryIntent.SelectItem("orders.id", "COUNT")),
                List.of(),
                List.of(new QueryIntent.Predicate("COUNT(orders.id)", ">", "3", false)),
                List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT customers.id, customers.name, COUNT(orders.id) FROM customers "
                        + "JOIN orders ON customers.id = orders.customer_id "
                        + "GROUP BY customers.id, customers.name "
                        + "HAVING COUNT(orders.id) > 3;");
    }

    @Test
    @DisplayName("no aggregate means no GROUP BY clause at all")
    void noAggregateMeansNoGroupBy() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new QueryIntent.SelectItem("name", null)),
                List.of(new QueryIntent.Predicate("city", "=", "mumbai", true)),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT name FROM customers WHERE city = 'mumbai';")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("order by and limit are appended in SQL order")
    void orderByThenLimit() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "products", null,
                List.of(new QueryIntent.SelectItem("*", null)),
                List.of(), List.of(), List.of("price DESC"), 5);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 5;");
    }

    @Test
    @DisplayName("DELETE emits its WHERE and never a projection")
    void deleteEmitsWhere() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.DELETE, "customers", null,
                List.of(),
                List.of(new QueryIntent.Predicate("city", "=", "pune", true)),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("DELETE FROM customers WHERE city = 'pune';");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SqlEmitterTest`
Expected: FAIL — `QueryIntent` and `SqlEmitter` do not exist.

- [ ] **Step 3: Write minimal implementation**

`QueryIntent.java`:

```java
package com.querysense.backend.querybuilder;

import java.util.List;

/**
 * QueryIntent
 *
 * One question, fully resolved against the ACTIVE schema. Every table and
 * column named here has already been bound to something real — nothing in
 * this record is a raw string from the user.
 *
 * That is what makes "the engine declines correctly" a structural property
 * rather than a word list someone has to keep extending: if a phrase could
 * not be bound, no intent exists to emit, and the caller declines.
 */
public record QueryIntent(
        Verb verb,
        String fromTable,
        JoinSpec join,                 // nullable — single-table query
        List<SelectItem> selectItems,  // empty for DELETE
        List<Predicate> where,
        List<Predicate> having,
        List<String> orderBy,
        Integer limit) {               // nullable

    public enum Verb { SELECT, DELETE }

    /** Column names are already qualified by the parser when a join is present. */
    public record JoinSpec(String joinedTable, String leftColumn, String rightColumn) {}

    /** @param aggregate null for a plain column, else COUNT/SUM/AVG/MIN/MAX. */
    public record SelectItem(String column, String aggregate) {
        public boolean aggregated() { return aggregate != null; }
        public String render() { return aggregated() ? aggregate + "(" + column + ")" : column; }
    }

    /** @param quoted whether the literal needs quoting — decided at bind time, not by guessing. */
    public record Predicate(String lhs, String operator, String literal, boolean quoted) {
        public String render() {
            return lhs + " " + operator + " " + (quoted ? "'" + literal + "'" : literal);
        }
    }

    public boolean hasAggregate() {
        return selectItems.stream().anyMatch(SelectItem::aggregated);
    }
}
```

`SqlEmitter.java`:

```java
package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * SqlEmitter
 *
 * QueryIntent to SQL. Deterministic, and knows no English whatsoever — every
 * decision it makes is structural. Kept separate from the binders so the
 * emitted text can be asserted exactly, without a sentence anywhere in the
 * test.
 *
 * WHY GROUP BY IS DERIVED RATHER THAN BOUND:
 * MySQL runs ONLY_FULL_GROUP_BY by default. Grouping by exactly the
 * non-aggregated projections is always valid under it, whereas grouping by
 * the primary key alone relies on MySQL's functional-dependency detection —
 * true today, and a silent failure on any server configured otherwise.
 * SqlPreflightValidator exists partly because that rule was broken before.
 */
@Component
public class SqlEmitter {

    public String emit(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            sql.append("DELETE FROM ").append(intent.fromTable());
        } else {
            sql.append("SELECT ")
               .append(intent.selectItems().stream()
                       .map(QueryIntent.SelectItem::render)
                       .collect(Collectors.joining(", ")))
               .append(" FROM ").append(intent.fromTable());
        }

        if (intent.join() != null) {
            sql.append(" JOIN ").append(intent.join().joinedTable())
               .append(" ON ").append(intent.join().leftColumn())
               .append(" = ").append(intent.join().rightColumn());
        }

        if (!intent.where().isEmpty()) {
            sql.append(" WHERE ").append(renderAll(intent.where()));
        }

        if (intent.hasAggregate()) {
            List<String> grouped = intent.selectItems().stream()
                    .filter(item -> !item.aggregated())
                    .map(QueryIntent.SelectItem::column)
                    .toList();
            if (!grouped.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", grouped));
            }
        }

        if (!intent.having().isEmpty()) {
            sql.append(" HAVING ").append(renderAll(intent.having()));
        }

        if (!intent.orderBy().isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", intent.orderBy()));
        }

        if (intent.limit() != null) {
            sql.append(" LIMIT ").append(intent.limit());
        }

        return sql.append(";").toString();
    }

    private String renderAll(List<QueryIntent.Predicate> predicates) {
        return predicates.stream()
                .map(QueryIntent.Predicate::render)
                .collect(Collectors.joining(" AND "));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SqlEmitterTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java \
        backend/src/main/java/com/querysense/backend/querybuilder/SqlEmitter.java \
        backend/src/test/java/com/querysense/backend/querybuilder/SqlEmitterTest.java
git commit -m "feat(rules): schema-bound QueryIntent and a deterministic SQL emitter

GROUP BY is derived from the non-aggregated projections, which is
ONLY_FULL_GROUP_BY-safe by construction rather than by relying on MySQL's
functional-dependency detection."
```

---

### Task 4: `RuleTrace` and `RuleBuildResult`

Pure data, written before the parser so the parser has somewhere to record its reasoning.

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/dto/RuleTrace.java`
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/RuleBuildResult.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/RuleBuildResultTest.java`

**Interfaces:**
- Produces:
  - `RuleTrace(String outcome, String shape, List<Binding> bindings, JoinEdge joinEdge, List<String> checksPassed, String declineReason)`
  - `RuleTrace.Binding(String phrase, String kind, String resolvedTo)`
  - `RuleTrace.JoinEdge(String fromTable, String fromColumn, String toTable, String toColumn, String origin)`
  - `RuleBuildResult.built(String sql, RuleTrace trace)`, `RuleBuildResult.declined(String reason, List<Binding> partial)`, `.built()`, `.sql()`, `.trace()`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBuildResultTest {

    @Test
    @DisplayName("a built result carries its SQL and a BUILT trace")
    void builtCarriesSqlAndTrace() {
        RuleTrace trace = new RuleTrace("BUILT", "SELECT", List.of(), null, List.of(), null);
        RuleBuildResult result = RuleBuildResult.built("SELECT * FROM customers;", trace);

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(result.trace().outcome()).isEqualTo("BUILT");
    }

    @Test
    @DisplayName("a declined result has no SQL and keeps the reason plus whatever bound")
    void declinedKeepsReasonAndPartialBindings() {
        RuleBuildResult result = RuleBuildResult.declined(
                "The question mentions a date range, which this engine does not build.",
                List.of(new RuleTrace.Binding("orders", "TABLE", "orders")));

        assertThat(result.built()).isFalse();
        assertThat(result.sql()).isNull();
        assertThat(result.trace().outcome()).isEqualTo("DECLINED");
        assertThat(result.trace().declineReason()).contains("date range");
        assertThat(result.trace().bindings()).hasSize(1);
    }

    @Test
    @DisplayName("toOptional bridges to the legacy tryBuild signature")
    void toOptionalBridgesLegacyCallers() {
        assertThat(RuleBuildResult.declined("no", List.of()).toOptional()).isEmpty();
        assertThat(RuleBuildResult.built("SELECT 1;", null).toOptional()).contains("SELECT 1;");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=RuleBuildResultTest`
Expected: FAIL — `RuleTrace` and `RuleBuildResult` do not exist.

- [ ] **Step 3: Write minimal implementation**

`RuleTrace.java`:

```java
package com.querysense.backend.dto;

import java.util.List;

/**
 * RuleTrace
 *
 * How the rule engine built a query — or why it declined and handed the
 * question to the next layer.
 *
 * WHY DECLINES ARE TRACED TOO:
 * `handledBy` records WHICH layer answered but never why the previous one
 * passed. PROJECT.md §2 claims routing is "auditable rather than asserted";
 * without a decline reason that claim holds only for the half of the
 * decision that succeeded. The reasons already exist as logic in the
 * parser — this stops discarding them.
 *
 * WHY `origin` ON JoinEdge:
 * It is the field that makes the project's join claim checkable rather than
 * asserted. "declared foreign key" means a reader can open their own DDL and
 * confirm the ON clause was read, not guessed.
 *
 * WORDING RULE: every string here is user-facing and must stay free of
 * EXPLAIN vocabulary — the same discipline
 * SchemaAdvisorServiceImplTest.jargonFreeSummaries enforces, and for the
 * same reason: copy drifts back toward the vocabulary of the code around it.
 */
public record RuleTrace(
        String outcome,            // "BUILT" | "DECLINED"
        String shape,              // e.g. "SELECT + JOIN + GROUP BY + HAVING"; null when declined
        List<Binding> bindings,
        JoinEdge joinEdge,         // nullable
        List<String> checksPassed,
        String declineReason) {    // null when built

    public record Binding(String phrase, String kind, String resolvedTo) {}

    public record JoinEdge(String fromTable, String fromColumn,
                           String toTable, String toColumn, String origin) {}
}
```

`RuleBuildResult.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;

import java.util.List;
import java.util.Optional;

/**
 * RuleBuildResult
 *
 * What the rule engine returns: either SQL with the trace that produced it,
 * or a decline with the reason.
 *
 * WHY THIS EXISTS RATHER THAN A CHANGED tryBuild SIGNATURE:
 * RuleBasedQueryBuilderSchemaTest asserts on tryBuild's Optional<String> and
 * is the regression guard for schema adaptivity — the one file a refactor of
 * this area must not touch. So `build` is added returning this type, and
 * `tryBuild` stays as a delegate over toOptional().
 */
public record RuleBuildResult(String sql, RuleTrace trace) {

    public static RuleBuildResult built(String sql, RuleTrace trace) {
        return new RuleBuildResult(sql, trace);
    }

    public static RuleBuildResult declined(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace("DECLINED", null, partialBindings, null, List.of(), reason));
    }

    public boolean built() {
        return sql != null;
    }

    public Optional<String> toOptional() {
        return Optional.ofNullable(sql);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=RuleBuildResultTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/dto/RuleTrace.java \
        backend/src/main/java/com/querysense/backend/querybuilder/RuleBuildResult.java \
        backend/src/test/java/com/querysense/backend/querybuilder/RuleBuildResultTest.java
git commit -m "feat(rules): RuleTrace and RuleBuildResult carry both outcomes

A decline now keeps its reason instead of evaporating into Optional.empty(),
which is what makes the routing boundary auditable rather than asserted."
```

---

### Task 5: `QueryIntentParser` — the binders and the decline boundary

The largest task. It replaces `hasAnyComplexity` entirely.

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/QueryIntentParserTest.java`

**Interfaces:**
- Consumes: `SchemaBinder`, `JoinPathResolver`, `SchemaMetadataService.getDistinctValues(String, String)`
- Produces: `RuleBuildResult parse(String rawInput)` — bindings recorded on both paths

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryIntentParserTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(metadata.getDistinctValues(eq("customers"), eq("city")))
                .thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        parser = new QueryIntentParser(
                new SchemaBinder(activeSchema),
                new JoinPathResolver(activeSchema),
                metadata);
    }

    @Test
    @DisplayName("the sample prompt that was LLM-only now builds a join with HAVING")
    void buildsJoinWithHaving() {
        RuleBuildResult result = parser.parse("Which customers placed more than three orders");

        assertThat(result.built()).isTrue();
        assertThat(result.sql())
                .contains("JOIN orders ON customers.id = orders.customer_id")
                .contains("GROUP BY")
                .contains("HAVING COUNT(orders.id) > 3");
        assertThat(result.trace().joinEdge().origin()).isEqualTo("declared foreign key");
    }

    @Test
    @DisplayName("a question word alone no longer forces a decline")
    void questionWordIsNotComplexity() {
        RuleBuildResult result = parser.parse("which customers are in mumbai");

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("the filter column is found in the data, not hardcoded to city")
    void filterColumnComesFromTheData() {
        RuleBuildResult result = parser.parse("customers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.trace().bindings())
                .anySatisfy(b -> assertThat(b.resolvedTo()).isEqualTo("customers.city"));
    }

    @Test
    @DisplayName("a value present in no column of the bound table declines")
    void ungroundedValueDeclines() {
        RuleBuildResult result = parser.parse("customers in atlantis");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isNotBlank();
    }

    @Test
    @DisplayName("per <column> becomes a real GROUP BY instead of being dropped")
    void perColumnGroups() {
        RuleBuildResult result = parser.parse("how many orders are there per status");

        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("GROUP BY status").contains("COUNT(");
    }

    @Test
    @DisplayName("a temporal expression declines, but the word 'may' alone does not")
    void temporalDeclinesButModalMayDoesNot() {
        assertThat(parser.parse("orders from last month").built()).isFalse();
        assertThat(parser.parse("show all customers").built()).isTrue();
    }

    @Test
    @DisplayName("two tables with no declared relationship decline")
    void unrelatedPairDeclines() {
        RuleBuildResult result = parser.parse("customers and suppliers");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("relationship");
    }

    @Test
    @DisplayName("the built-in sample prompts are unchanged")
    void builtInSamplePromptsUnchanged() {
        assertThat(parser.parse("Show all customers").sql()).isEqualTo("SELECT * FROM customers;");
        assertThat(parser.parse("Show only the name and email of customers").sql())
                .isEqualTo("SELECT name, email FROM customers;");
        assertThat(parser.parse("How many orders are there").sql())
                .isEqualTo("SELECT COUNT(*) FROM orders;");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=QueryIntentParserTest`
Expected: FAIL — `QueryIntentParser` does not exist.

- [ ] **Step 3: Write minimal implementation**

Implement `QueryIntentParser` with this structure. Each private method is one binder; each returns either a bound fragment or triggers a decline through `RuleBuildResult.declined`.

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QueryIntentParser
 *
 * Replaces RuleBasedQueryBuilder.hasAnyComplexity. The old gate asked "does
 * this text contain a scary word?" and declined on a hardcoded English list
 * — `who`, `which`, `more than`, month names. None of those has anything to
 * do with the schema, so uploading a schema could not change what the engine
 * would claim.
 *
 * This asks instead: can every noun be bound to something real in the ACTIVE
 * schema? Unbound or ambiguous means decline. That makes the decline
 * decision depend on the uploaded schema, which is the whole point.
 *
 * NOTE ON THE ONE SURVIVING WORD LIST (temporal): date arithmetic has no
 * correct rule-based answer here, so temporal EXPRESSIONS still decline. It
 * is a binder that declines, not a veto on tokens — the old month regex
 * contained `may`, so "customers who may have churned" was declined for a
 * modal verb.
 */
@Component
public class QueryIntentParser {

    private static final String JOIN_ORIGIN = "declared foreign key";

    private static final Pattern TEMPORAL = Pattern.compile(
            "\\b(last|this|next)\\s+(month|week|year|day|quarter)\\b"
            + "|\\b(today|yesterday|tomorrow)\\b"
            + "|\\bin\\s+(january|february|march|april|may|june|july|august"
            + "|september|october|november|december)\\b"
            + "|\\b(before|after|since)\\s+\\d{4}\\b");

    private static final Pattern COMPARISON = Pattern.compile(
            "\\b(more|greater|higher|larger)\\s+than\\s+(\\w+)"
            + "|\\b(less|fewer|lower|smaller)\\s+than\\s+(\\w+)"
            + "|\\b(above|over)\\s+(\\w+)|\\b(below|under)\\s+(\\w+)");

    private static final Pattern PER_GROUP = Pattern.compile("\\b(?:per|for each|by)\\s+([a-z_ ]+?)\\b");

    private static final Pattern PREPOSITION_VALUE = Pattern.compile(
            "\\b(?:from|in|at|located in|living in|based in|residing in)\\s+([a-z]+)\\b");

    private static final Pattern LIMIT = Pattern.compile("\\b(?:top|first|limit)\\s+(\\d+)\\b");

    private static final Pattern ORDER_BY = Pattern.compile(
            "\\b(?:order(?:ed)?\\s+by|sort(?:ed)?\\s+by)\\s+([a-z_ ]+?)"
            + "(\\s+desc(?:ending)?|\\s+asc(?:ending)?)?\\b");

    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9, "ten", 10);

    private static final Map<String, String> AGGREGATE_WORDS = Map.of(
            "average", "AVG", "avg", "AVG", "mean", "AVG",
            "sum", "SUM", "total", "SUM",
            "maximum", "MAX", "max", "MAX", "highest", "MAX",
            "minimum", "MIN");

    private final SchemaBinder binder;
    private final JoinPathResolver joins;
    private final SchemaMetadataService metadata;

    public QueryIntentParser(SchemaBinder binder, JoinPathResolver joins,
                             SchemaMetadataService metadata) {
        this.binder = binder;
        this.joins = joins;
        this.metadata = metadata;
    }

    public RuleBuildResult parse(String rawInput) {
        String text = SchemaBinder.normalize(rawInput);
        List<RuleTrace.Binding> bindings = new ArrayList<>();

        if (TEMPORAL.matcher(text).find()) {
            return RuleBuildResult.declined(
                    "The question asks about a time period. This engine does not build date "
                            + "filters, so the question went to the language model instead.",
                    bindings);
        }

        List<KnownSchema.Table> tables = binder.bindTables(text);
        if (tables.isEmpty()) {
            return RuleBuildResult.declined(
                    "No table in the current schema matches anything in the question.", bindings);
        }
        if (tables.size() > 2) {
            return RuleBuildResult.declined(
                    "The question names " + tables.size() + " tables. This engine builds a join "
                            + "between two at most.", bindings);
        }
        tables.forEach(t -> bindings.add(
                new RuleTrace.Binding(t.name(), "TABLE", t.name())));

        // ... continue: resolve join (Task 2), projection, aggregate,
        // grouping, comparison, value-grounded filter, order, limit.
        // Each unbindable phrase returns RuleBuildResult.declined with the
        // reason, carrying `bindings` so the trace shows how far it got.
        throw new UnsupportedOperationException("completed during implementation");
    }
}
```

Complete the method body so every test in Step 1 passes. The required behaviour, in order:

1. **Temporal check** (above) — decline.
2. **Bind tables** — 0 declines, >2 declines, 2 goes to the join resolver.
3. **Join** — `joins.resolve(a, b)`; `None` declines with a reason containing the word `relationship`; `Ambiguous` declines naming both edges; `Resolved` produces a `JoinSpec` and a `RuleTrace.JoinEdge` with `origin = "declared foreign key"`.
4. **Verb** — `delete`/`remove` present ⇒ `DELETE` (and a DELETE with no `where` declines, preserving today's safety rule); otherwise `SELECT`.
5. **Aggregate** — `count`/`how many`/`number of` ⇒ `COUNT` over the joined table's `id` when a join exists, else `COUNT(*)`. `AGGREGATE_WORDS` ⇒ that function over a bound column; an aggregate word whose column will not bind declines.
6. **Grouping** — `PER_GROUP` capture bound via `binder.bindColumn`; unbindable declines (this is the dropped-`per gender` defect). A grouping forces the grouped column into `selectItems` as a non-aggregated item.
7. **Comparison** — `COMPARISON` capture; digits or `NUMBER_WORDS` give the literal. If an aggregate is present the predicate goes to `having` with `lhs` = the rendered aggregate; otherwise to `where` with `lhs` = a bound column.
8. **Value-grounded filter** — `PREPOSITION_VALUE` capture; for each string column of the FROM table, `metadata.getDistinctValues(table, column)` and take the column whose values contain the capture case-insensitively. Found ⇒ `Predicate(column, "=", value, true)` and a binding of `table.column`. Not found ⇒ decline (this replaces `isSafeLocationCondition`).
9. **Projection** — reuse the existing `only/just X` and `X of Y` strong-signal rules from `RuleBasedQueryBuilder.detectColumns`, resolving through `binder.bindColumn`. Unresolvable strong phrase declines. Nothing requested ⇒ `SelectItem("*", null)` when there is no aggregate and no grouping.
10. **Live-drift check** (spec §3.4) — for each table in the intent, call `metadata.getColumnNames(table)`. When that list is **non-empty**, every column bound for that table must appear in it; if one does not, decline. An **empty** list means "unknown" and must degrade to allowing rather than blocking — the pattern every analyzer in that package already follows, and the reason `SchemaMetadataService`'s own Javadoc tells callers to treat empty as unknown.
11. **Order/limit** — `ORDER_BY` and `LIMIT`; an unbindable order column declines.
12. **Qualification** — when a join exists, qualify every column as `table.column`; otherwise leave bare.
13. **Shape label** — join `"SELECT + JOIN"`, plus `" + GROUP BY"` and `" + HAVING"` as those clauses appear.
14. **checksPassed** — add `"Every column resolved against the live schema"` and, when a join exists, `"Exactly one foreign key relates these tables"`.

Move the WHY comments for Bugs 7/8/9/10 from `RuleBasedQueryBuilder` onto the corresponding steps here.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=QueryIntentParserTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java \
        backend/src/test/java/com/querysense/backend/querybuilder/QueryIntentParserTest.java
git commit -m "feat(rules): bind questions to the active schema instead of blacklisting words

Replaces hasAnyComplexity. Question words, comparisons and aggregates no
longer force a decline; unbound or ambiguous nouns do. The filter column is
found in the data rather than hardcoded to city."
```

---

### Task 6: Rewire `RuleBasedQueryBuilder` and pin the decline boundary

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/RuleBasedQueryBuilder.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DeclineBoundaryTest.java` (create)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/RuleTraceWordingTest.java` (create)

**Interfaces:**
- Consumes: `QueryIntentParser.parse`, `SqlEmitter.emit`
- Produces: `RuleBuildResult build(String input, String databaseType)`; `Optional<String> tryBuild(String, String)` retained as a delegate

- [ ] **Step 1: Write the failing tests**

`DeclineBoundaryTest.java` — the guard on over-claiming:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The line the rule engine must not cross.
 *
 * Every other test in this package widens what the engine claims. This one
 * holds the boundary, and it guards the single failure PROJECT.md §5 says
 * the project's central claim cannot survive: a wrong answer returned as
 * RULE_ENGINE at confidence 0.95. A deferral to the LLM is always an
 * acceptable outcome; a confident wrong answer never is.
 */
class DeclineBoundaryTest {

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        SchemaBinder binder = new SchemaBinder(activeSchema);
        builder = new RuleBasedQueryBuilder(
                new QueryIntentParser(binder, new JoinPathResolver(activeSchema), metadata),
                new SqlEmitter());
    }

    @ParameterizedTest
    @DisplayName("these must always decline to the LLM")
    @ValueSource(strings = {
            "orders from last month",
            "customers who joined in January",
            "show me everything interesting",
            "list the top 5 buyers by total spend",
            "customers and suppliers",
            "orders, customers and products together",
            "delete all customers",
            "average of the flurb of customers",
            "how many customers are there per flurb",
    })
    void mustDecline(String input) {
        assertThat(builder.build(input, "MySQL").built())
                .as("input: %s", input)
                .isFalse();
    }

    @ParameterizedTest
    @DisplayName("MongoDB always declines, regardless of how simple the question is")
    @ValueSource(strings = {"Show all customers", "how many orders are there"})
    void mongoAlwaysDeclines(String input) {
        assertThat(builder.build(input, "MongoDB").built()).isFalse();
    }
}
```

`RuleTraceWordingTest.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the COPY, not the logic — the same guard
 * SchemaAdvisorServiceImplTest.jargonFreeSummaries and VerdictServiceTest
 * both carry, and for the reason recorded there: prose drifts back toward
 * the vocabulary of the code around it.
 */
class RuleTraceWordingTest {

    private static final List<String> BANNED = List.of(
            "accesstype", "explain", "possible_keys", "filesort", "eq_ref",
            "anant", "vedant", "eeshaan");

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(
                new QueryIntentParser(new SchemaBinder(activeSchema),
                        new JoinPathResolver(activeSchema), metadata),
                new SqlEmitter());
    }

    @Test
    @DisplayName("no decline reason leaks database vocabulary or a teammate name")
    void declineReasonsAreJargonFree() {
        List<String> inputs = List.of(
                "orders from last month", "customers and suppliers",
                "show me everything interesting", "how many customers are there per flurb");

        for (String input : inputs) {
            String reason = builder.build(input, "MySQL").trace().declineReason();
            assertThat(reason).as("input: %s", input).isNotBlank();
            String lower = reason.toLowerCase(Locale.ROOT);
            for (String banned : BANNED) {
                assertThat(lower).as("input: %s", input).doesNotContain(banned);
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='DeclineBoundaryTest+RuleTraceWordingTest'`
Expected: FAIL — `RuleBasedQueryBuilder` has no two-arg constructor and no `build` method.

- [ ] **Step 3: Rewrite `RuleBasedQueryBuilder` as an orchestrator**

```java
package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * RuleBasedQueryBuilder
 *
 * The rule engine's entry point. It now orchestrates rather than parses:
 * QueryIntentParser binds the question to the ACTIVE schema, SqlEmitter
 * turns the bound intent into SQL.
 *
 * WHAT CHANGED AND WHY (see docs/superpowers/specs/2026-09-10-schema-adaptive-rule-engine-design.md):
 * the old flow detected a table, checked the input against a hardcoded
 * English blacklist, and emitted. The blacklist could not follow an
 * uploaded schema, so uploading one changed the engine's vocabulary but
 * never what it was willing to claim. Binding failure is the decline signal
 * now.
 *
 * MongoDB still declines here, before any binding: every emitter below
 * produces SQL syntax, and claiming a Mongo request would return
 * syntactically valid but wrong-dialect output. Unchanged from the previous
 * implementation, and it stays until a real Mongo builder exists.
 */
@Component
public class RuleBasedQueryBuilder {

    private final QueryIntentParser parser;
    private final SqlEmitter emitter;

    public RuleBasedQueryBuilder(QueryIntentParser parser, SqlEmitter emitter) {
        this.parser = parser;
        this.emitter = emitter;
    }

    public RuleBuildResult build(String input, String databaseType) {
        if (input == null || input.isBlank()) {
            return RuleBuildResult.declined("The question was empty.", List.of());
        }
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return RuleBuildResult.declined(
                    "This engine builds SQL only. MongoDB questions go to the language model.",
                    List.of());
        }
        return parser.parse(input);
    }

    /**
     * Retained deliberately: RuleBasedQueryBuilderSchemaTest asserts on this
     * signature and is the regression guard for schema adaptivity. It must
     * keep passing unedited.
     */
    public Optional<String> tryBuild(String input, String databaseType) {
        return build(input, databaseType).toOptional();
    }
}
```

`QueryIntentParser.parse` must return `RuleBuildResult` already carrying the emitted SQL, so inject `SqlEmitter` into the parser and call it there. Update the parser's constructor and `QueryIntentParserTest` setup accordingly.

- [ ] **Step 4: Run the whole backend suite**

Run: `cd backend && ./mvnw test`
Expected: PASS, including `RuleBasedQueryBuilderSchemaTest` **unedited**. If that file fails, the emitter is qualifying or spacing something differently — fix the emitter, not the test.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/ \
        backend/src/test/java/com/querysense/backend/querybuilder/
git commit -m "refactor(rules): RuleBasedQueryBuilder orchestrates, binders decide

Adds build() returning RuleBuildResult; tryBuild stays as a delegate so the
schema regression test keeps passing unedited. DeclineBoundaryTest pins the
line the engine must not cross."
```

---

### Task 7: Carry the trace onto the API response

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/dto/QueryResponse.java`
- Modify: `backend/src/main/java/com/querysense/backend/service/impl/QueryGenerationServiceImpl.java`
- Test: `backend/src/test/java/com/querysense/backend/service/impl/QueryGenerationServiceImplTest.java` (create)

**Interfaces:**
- Consumes: `RuleBuildResult`, `RuleTrace`
- Produces: `QueryResponse(String generatedQuery, String targetDb, String handledBy, Double confidence, RuleTrace ruleTrace)`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.dto.RuleTrace;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryGenerationServiceImplTest {

    private RuleBasedQueryBuilder rules;
    private QueryHistoryRepository history;
    private LlmFallbackService llm;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        rules = mock(RuleBasedQueryBuilder.class);
        history = mock(QueryHistoryRepository.class);
        llm = mock(LlmFallbackService.class);
        GeneratedQueryValidator validator = mock(GeneratedQueryValidator.class);
        when(validator.looksIncomplete(anyString(), anyString(), anyString())).thenReturn(false);
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(history.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new QueryGenerationServiceImpl(rules, history, llm, validator);
    }

    @Test
    @DisplayName("a rule-built query carries its trace and stays at confidence 0.95")
    void ruleBuiltCarriesTrace() {
        RuleTrace trace = new RuleTrace("BUILT", "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace));

        QueryResponse response = service.generateQuery("Show all customers", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("RULE_ENGINE");
        assertThat(response.getConfidence()).isEqualTo(0.95);
        assertThat(response.getRuleTrace().outcome()).isEqualTo("BUILT");
    }

    @Test
    @DisplayName("an LLM answer carries the DECLINED trace explaining why the rules passed")
    void llmAnswerCarriesDeclineReason() {
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("The question asks about a time period.", List.of()));
        when(llm.generateQuery(anyString(), anyString())).thenReturn("SELECT 1;");

        QueryResponse response = service.generateQuery("orders from last month", "MySQL");

        assertThat(response.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(response.getRuleTrace().outcome()).isEqualTo("DECLINED");
        assertThat(response.getRuleTrace().declineReason()).contains("time period");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=QueryGenerationServiceImplTest`
Expected: FAIL — `QueryResponse` has no `getRuleTrace`, and the service calls `tryBuild` not `build`.

- [ ] **Step 3: Write minimal implementation**

In `QueryResponse.java`, add the field after `confidence`:

```java
    private Double confidence;

    /**
     * How the rule engine built this query, or why it declined and passed it
     * on. Null only for responses that never went through the rule engine.
     * Rendered on the Analysis screen; see RuleTrace.
     */
    private RuleTrace ruleTrace;
```

In `QueryGenerationServiceImpl.generateQuery`, replace the Layer 1 block and thread `trace` through:

```java
        RuleTrace trace;
        RuleBuildResult ruleResult = ruleBasedQueryBuilder.build(input, databaseType);

        if (ruleResult.built()) {
            generatedSql = ruleResult.sql();
            source = SOURCE_RULE_ENGINE;
            confidence = CONFIDENCE_RULE_ENGINE;
            trace = ruleResult.trace();
        } else {
            // The rule engine's reason for passing travels with whatever
            // answers instead — without it, handledBy records WHICH layer
            // answered but never why the previous one declined.
            trace = ruleResult.trace();
            ... existing cache / LLM branches unchanged ...
        }

        saveToHistory(input, generatedSql, databaseType, source);
        return new QueryResponse(generatedSql, databaseType, source, confidence, trace);
```

`QueryHistory` is **not** changed — the trace is not persisted (spec §7: history stores no run context, and that stays out of scope).

- [ ] **Step 4: Run the whole backend suite**

Run: `cd backend && ./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/dto/QueryResponse.java \
        backend/src/main/java/com/querysense/backend/service/impl/QueryGenerationServiceImpl.java \
        backend/src/test/java/com/querysense/backend/service/impl/QueryGenerationServiceImplTest.java
git commit -m "feat(api): return the rule trace on /api/query/generate

An LLM- or cache-answered query now carries the rule engine's reason for
declining, so the routing decision is auditable and not just its outcome."
```

---

### Task 8: Frontend contract, client and fixtures

**Files:**
- Modify: `frontend/src/api/contracts.js:111-117`
- Modify: `frontend/src/api/queryBuilder.js`
- Modify: `frontend/src/mock/fixtures.js:43-56`

**Interfaces:**
- Produces: `build.ruleTrace` on the object `WorkbenchContext` stores

- [ ] **Step 1: Extend the typedef**

In `contracts.js`, add to `QueryBuildResponse`:

```js
 * @property {number} confidence      fixed per-source heuristic, not a computed score — see backend QueryResponse.java
 * @property {RuleTrace|null} ruleTrace  how the rule engine built this, or why it declined; null when the query did not come from the rule engine path
 */

/**
 * @typedef {Object} RuleTrace
 * @property {'BUILT'|'DECLINED'} outcome
 * @property {string|null} shape         e.g. "SELECT + JOIN + GROUP BY + HAVING"
 * @property {Array<{phrase: string, kind: string, resolvedTo: string}>} bindings
 * @property {{fromTable: string, fromColumn: string, toTable: string, toColumn: string, origin: string}|null} joinEdge
 * @property {string[]} checksPassed
 * @property {string|null} declineReason
 */
```

- [ ] **Step 2: Pass it through the client**

In `queryBuilder.js`, add one line to the returned object:

```js
  return {
    generatedQuery: data.generatedQuery,
    targetDb,
    handledBy: data.handledBy,
    confidence: data.confidence,
    ruleTrace: data.ruleTrace ?? null,
  };
```

- [ ] **Step 3: Give mock mode a trace**

In `fixtures.js`, replace the return of `mockBuildQuery`:

```js
  const ruleTrace = handledBy === 'RULE_ENGINE'
    ? {
        outcome: 'BUILT',
        shape: 'SELECT',
        bindings: [
          { phrase: table, kind: 'TABLE', resolvedTo: table },
          { phrase: column, kind: 'FILTER', resolvedTo: `${table}.${column}` },
        ],
        joinEdge: null,
        checksPassed: ['Every column resolved against the live schema'],
        declineReason: null,
      }
    : {
        outcome: 'DECLINED',
        shape: null,
        bindings: [{ phrase: table, kind: 'TABLE', resolvedTo: table }],
        joinEdge: null,
        checksPassed: [],
        declineReason: 'The question asks about a time period. This engine does not build date filters, so the question went to the language model instead.',
      };

  return { generatedQuery, targetDb, handledBy, confidence, ruleTrace };
```

- [ ] **Step 4: Verify the build is clean**

Run: `cd frontend && npm run build && npm run lint`
Expected: build clean; lint at 4 pre-existing warnings, no new ones.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/contracts.js frontend/src/api/queryBuilder.js frontend/src/mock/fixtures.js
git commit -m "feat(ui): carry ruleTrace through the API layer and mock fixtures

No ENDPOINT_STATUS entry: that map is keyed by endpoint and queryBuilder is
already REAL."
```

---

### Task 9: `RuleTracePanel` on the Analysis screen

**Files:**
- Create: `frontend/src/components/analysis/RuleTracePanel.jsx`
- Create: `frontend/src/components/analysis/RuleTracePanel.css`
- Create: `frontend/src/components/analysis/RuleTracePanel.test.jsx`
- Modify: `frontend/src/pages/AnalysisPage.jsx`

**Interfaces:**
- Consumes: `build.ruleTrace` from `WorkbenchContext`
- Produces: `<RuleTracePanel trace={build.ruleTrace} handledBy={build.handledBy} />`

- [ ] **Step 1: Write the failing test**

```jsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { RuleTracePanel } from './RuleTracePanel';

describe('RuleTracePanel', () => {
  it('renders nothing when there is no trace', () => {
    const { container } = render(<RuleTracePanel trace={null} handledBy="CACHE" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('names the foreign key a join was read from', () => {
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        trace={{
          outcome: 'BUILT',
          shape: 'SELECT + JOIN + GROUP BY + HAVING',
          bindings: [{ phrase: 'customers', kind: 'TABLE', resolvedTo: 'customers' }],
          joinEdge: {
            fromTable: 'orders', fromColumn: 'customer_id',
            toTable: 'customers', toColumn: 'id',
            origin: 'declared foreign key',
          },
          checksPassed: ['Exactly one foreign key relates these tables'],
          declineReason: null,
        }}
      />
    );
    expect(screen.getByText(/orders\.customer_id/)).toBeInTheDocument();
    expect(screen.getByText(/declared foreign key/i)).toBeInTheDocument();
  });

  it('shows the decline reason and which layer answered instead', () => {
    render(
      <RuleTracePanel
        handledBy="OLLAMA"
        trace={{
          outcome: 'DECLINED',
          shape: null,
          bindings: [],
          joinEdge: null,
          checksPassed: [],
          declineReason: 'The question asks about a time period.',
        }}
      />
    );
    expect(screen.getByText(/time period/)).toBeInTheDocument();
    expect(screen.getByText(/language model/i)).toBeInTheDocument();
  });
});
```

Add the test dependencies if absent:

```bash
cd frontend && npm install -D @testing-library/react @testing-library/jest-dom jsdom
```

and set `test: { environment: 'jsdom', setupFiles: './src/setupTests.js', globals: true }` in `vite.config.js`, with `src/setupTests.js` containing `import '@testing-library/jest-dom';`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- RuleTracePanel`
Expected: FAIL — cannot resolve `./RuleTracePanel`.

- [ ] **Step 3: Write the component**

```jsx
import './RuleTracePanel.css';

/**
 * How the rule engine built this query — or why it declined and handed the
 * question on.
 *
 * WHY THIS IS ON THE ANALYSIS SCREEN AND NOT THE WORKBENCH:
 * the Workbench answers "is this the right query?". This answers "why is it
 * shaped this way?", which is the question a reader already has once they
 * are looking at the plan. It arrives via WorkbenchContext's `build`, so
 * /api/query/analyze is untouched and no extra request is made.
 *
 * WHY DECLINES RENDER TOO:
 * `handledBy` says WHICH layer answered, never why the previous one passed.
 * Showing the decline reason is what makes the routing decision auditable
 * rather than asserted.
 */

const LAYER_NAME = {
  OLLAMA: 'the local language model',
  CACHE: 'a cached answer from an earlier identical question',
  RULE_ENGINE: 'the rule engine',
};

export function RuleTracePanel({ trace, handledBy }) {
  // Absent means there is no traced generation behind this analysis — a
  // history replay, or a query that did not come from the Workbench. It does
  // NOT mean the rules declined: that is outcome === 'DECLINED', and renders.
  if (!trace) return null;

  const declined = trace.outcome === 'DECLINED';

  return (
    <section className={`rule-trace rule-trace--${declined ? 'declined' : 'built'}`}
             aria-label="How this query was built">
      <header className="rule-trace__head">
        <span className="rule-trace__badge">
          {declined ? 'Rule engine declined' : 'Built by the rule engine'}
        </span>
        {!declined && trace.shape && <span className="rule-trace__shape">{trace.shape}</span>}
      </header>

      {declined ? (
        <>
          <p className="rule-trace__reason">{trace.declineReason}</p>
          <p className="rule-trace__handoff">
            Answered instead by {LAYER_NAME[handledBy] || 'another layer'}.
          </p>
        </>
      ) : (
        <>
          {trace.joinEdge && (
            <p className="rule-trace__join">
              Joined on{' '}
              <code>
                {trace.joinEdge.fromTable}.{trace.joinEdge.fromColumn}
              </code>{' '}
              →{' '}
              <code>
                {trace.joinEdge.toTable}.{trace.joinEdge.toColumn}
              </code>
              <span className="rule-trace__origin">
                read from your schema&rsquo;s {trace.joinEdge.origin}, not inferred
              </span>
            </p>
          )}

          {trace.bindings?.length > 0 && (
            <dl className="rule-trace__bindings">
              {trace.bindings.map((b, i) => (
                <div className="rule-trace__binding" key={i}>
                  <dt>&ldquo;{b.phrase}&rdquo;</dt>
                  <dd>
                    <span className="rule-trace__kind">{b.kind}</span>
                    <code>{b.resolvedTo}</code>
                  </dd>
                </div>
              ))}
            </dl>
          )}

          {trace.checksPassed?.length > 0 && (
            <ul className="rule-trace__checks">
              {trace.checksPassed.map((c, i) => (
                <li key={i}>{c}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}
```

`RuleTracePanel.css` — follow `VerdictBanner.css`'s token usage: `--surface`, `--border`, `--ink-secondary`, `--text-sm`, `--space-*`, and `--data` (the project's purple) for the built state's accent, `--llm-tint` (amber) for the declined state, matching how the app already colours rule-engine versus LLM provenance.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- RuleTracePanel`
Expected: PASS, 3 tests.

- [ ] **Step 5: Wire it into `AnalysisPage`**

Add the import beside the other analysis components, and render it directly below `<VerdictBanner />` inside the `{analysis && (...)}` block:

```jsx
          <VerdictBanner verdict={analysis.verdict} />

          {/*
            Below the verdict, above the metrics: the verdict says whether
            the query is any good, this says why it is shaped the way it is,
            and everything below is the evidence.
          */}
          <RuleTracePanel trace={build.ruleTrace} handledBy={build.handledBy} />
```

- [ ] **Step 6: Verify build, lint and the full frontend suite**

Run: `cd frontend && npm test && npm run build && npm run lint`
Expected: all tests pass; build clean; lint shows no new warnings beyond the 4 pre-existing.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/analysis/RuleTracePanel.jsx \
        frontend/src/components/analysis/RuleTracePanel.css \
        frontend/src/components/analysis/RuleTracePanel.test.jsx \
        frontend/src/pages/AnalysisPage.jsx frontend/vite.config.js \
        frontend/src/setupTests.js frontend/package.json frontend/package-lock.json
git commit -m "feat(ui): show how the rule engine built a query, or why it declined

Renders below the verdict on the Analysis screen from WorkbenchContext's
build, so /api/query/analyze is untouched and no extra request is made."
```

---

### Task 10: Documentation

**Files:**
- Modify: `PROJECT.md`
- Modify: `backend/CLAUDE.md`

- [ ] **Step 1: Record the session in PROJECT.md §7**

Add a session entry following the established format. It must state:

- what changed, and the three causes from spec §1 that made an upload not change routing;
- that the `order_items` defect (§5) and the dropped-`per <column>` defect (§8) are now closed, and update those sections to say so rather than leaving them reading as open;
- the two defects found while designing and now fixed — the hardcoded `city` filter column and the inert known-value safety net — which no section recorded before;
- test counts actually observed, not estimated;
- **explicitly, under "Not verified":** that nothing here was run against a live backend or in a browser this session.

- [ ] **Step 2: Update §2's routing table**

The line *"It declines — and hands to the LLM — anything containing a join, an aggregate…, a comparison…, a question word…"* is now false. Replace it with the binding-based rule and the surviving temporal decline.

- [ ] **Step 3: Update the sample-prompt table**

*"Which customers placed more than three orders"* moves from `LLM → cache on a repeat` to the rule engine, with the emitted SQL.

- [ ] **Step 4: Update `backend/CLAUDE.md` §2**

Its "rule-based engine handles the majority of queries" claim now has joins behind it. Add a short subsection noting the FK-derived join and pointing at the spec.

- [ ] **Step 5: Commit**

```bash
git add PROJECT.md backend/CLAUDE.md
git commit -m "docs: record the schema-adaptive rule engine and what it closed"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1.1 blacklist is not schema-aware | 5 |
| §1.2 `tableCount > 1` auto-decline | 2, 5 |
| §1.3 hardcoded `city` / inert safety net | 5 (step 8) |
| §3.2 class breakdown | 1, 2, 3, 5, 6 |
| §3.3 value net inverted | 5 (step 8) |
| §3.4 live-drift via `getColumnNames` | 1 (`bindColumn` resolves against the snapshot; the live check rides on `SchemaMetadataService` in Task 5) |
| §4.1 join resolution | 2 |
| §4.2 worked example | 5 (`buildsJoinWithHaving`) |
| §4.3 decline boundary | 6 (`DeclineBoundaryTest`) |
| §4.4 temporal binder | 5, 6 |
| §5.1 trace shape | 4 |
| §5.2 declines traced | 4, 7 |
| §5.3 frontend | 8, 9 |
| §6 testing | every task |
| §6.4 comment preservation | 5, 6 |

**Gap found and closed:** spec §3.4 specifies a live `getColumnNames` drift check at bind time. Task 1 binds against the snapshot only. Task 5 step 8 already calls `SchemaMetadataService`; the live column check belongs alongside it. **Add to Task 5 step 3, behaviour item 9:** after projection binding, confirm each resolved column appears in `metadata.getColumnNames(table)` when that list is non-empty, and decline if not — an empty list means "unknown", which must degrade to allowing rather than blocking, the pattern every analyzer in that package already follows.

**Placeholder scan:** Task 5 step 3 deliberately leaves the parser body as a numbered behavioural specification with a throwing stub rather than 300 lines of inline code. Every branch, pattern, and decline reason it must produce is enumerated, and the eight tests in step 1 define it exactly. This is the one place in the plan where the test is the specification; noted here so a reader does not mistake it for an omission.

**Type consistency:** `RuleBuildResult.built()`/`sql()`/`trace()`, `RuleTrace.outcome()`/`declineReason()`/`joinEdge()`, `QueryIntent.SelectItem.render()`, `SchemaBinder.normalize()`/`bindTables()`/`bindColumn()`, `JoinPathResolver.Resolved.edge()` — used consistently across Tasks 1-9. `QueryIntentParser`'s constructor gains `SqlEmitter` in Task 6; Task 5's test setup must be updated in that same task, and Task 6 step 3 says so.
