# Rule Engine Question Shapes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The rule engine builds, in MySQL and MongoDB:
- ranking and superlatives;
- inclusive and between ranges;
- not-equal and exclusion;
- "without" anti-joins;
- DISTINCT;
- OR across conditions;
- several totals per question.

**Architecture:**
- `QueryIntent` gains `NE`, `NOT_IN` and `BETWEEN`; a `TableStar` operand; an `ANTI` join kind; and `distinct` and `anyOf` flags. Both emitters render each one.
- `QueryIntentParser` gains one small reader per shape and records the text span of every condition, so connectors between conditions decide AND versus OR.
- Value reading (E14) moves into one reusable method that exclusion shares.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, Mockito, Jackson.

**Spec:** `docs/superpowers/specs/2026-10-02-rule-engine-question-shapes-design.md`

**Precondition:** sub-project 1 (`docs/superpowers/plans/2026-10-02-rule-engine-vocabulary.md`) is complete. This plan uses what it produced:
- `SchemaLexicon`, `Interpretation` and `AmbiguousWordException`;
- `column(table, phrase, interpretations)`, `note(...)`, `ambiguityMessage(...)`, `listWith(...)`;
- `valueRun(...)`, `columnsHolding(...)`, `NumberReader`, `Literal.number(BigDecimal)`;
- the `PREPOSITION` value loop.

Code excerpts below quote the parser as that plan leaves it.

## Global Constraints

- Every question that builds today builds byte-for-byte the same, at the same confidence. The only exceptions are the two silent drops in spec §3.8: a second comparison, and a `with` filter beside another filter.
- Every new decline is a full sentence naming what it couldn't choose between or read.
- Both dialects, always. Every shape goes through `QueryIntent` and both emitters.
- An adjective binding counts as a `SYNONYM` interpretation (confidence 0.85).
- "The highest/lowest X" with no number keeps building `MAX`/`MIN` as today.
- Source comments: a one-line class summary at most.
- Backend tests: `cd backend && ./mvnw -q test -Dtest=<Class>`, and `./mvnw test` for all.
- Do not start the app. Commits end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **"between" next to a number containing "and"** (`price between two hundred and fifty and 500`): the low end must read 250, not 200. Pinned in Task 2 (`betweenWithAndInsideNumber`).
2. **"not in" must not also become an equality** (`customers not in pune`): exactly one `!=` predicate. Pinned in Task 3 (`notInIsOnePredicate`).
3. **"which <table> …" stays a normal question** (`which customers are in mumbai`): no DISTINCT. Pinned in Task 5 (`whichTableIsNotDistinct`).
4. **A comparison phrase after `with`** (`products with price at least 500`): one predicate, not a stray `with` filter. Pinned in Task 6 (`withInsideComparisonIsNotAFilter`).
5. **Today's MAX path** (`highest price of products`) must still build `MAX(price)` once ranking exists. Pinned in Task 8 (`highestWithoutNumberIsStillMax`).

## Departures from the spec, on purpose

1. The anti-join adds its `IS NULL` condition inside `SqlEmitter`, not as a parser predicate. MongoDB expresses the same thing structurally (`$size: 0`), so the predicate would have meant nothing there.
2. Parser declines raised inside helper methods use a new `DeclineException`, which `AmbiguousWordException` extends. The parser's single catch turns either into a decline.

---

### Task 0: Reconcile with the code as sub-project 1 left it

- [ ] **Step 1:** Read `QueryIntentParser.java`, `QueryIntent.java`, `SqlEmitter.java`, `MongoEmitter.java` and `RuleBuildResult.java`. Confirm that the names this plan quotes exist:
  - `column(...)`, `note(...)`, `ambiguityMessage(...)`, `listWith(...)`;
  - `valueRun(...)`, `columnsHolding(...)`, `PREPOSITION`;
  - `Interpretation`, `AmbiguousWordException`.

  Where a name differs, use the real one and write a `Ruling:` line in the ledger.
- [ ] **Step 2:** Run `cd backend && ./mvnw -q test`. It must be green before Task 1.

---

### Task 1: `QueryIntent` and both emitters learn the new shapes

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java`, `SqlEmitter.java`, `MongoEmitter.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/ShapeEmitterTest.java` (new)

**Interfaces:**
- Produces:
  - `QueryIntent.Op.NE`, `NOT_IN` and `BETWEEN`
  - `record QueryIntent.TableStar(String table) implements Operand`
  - `enum QueryIntent.JoinKind { INNER, ANTI }`
  - `JoinSpec(String table, ColumnRef left, ColumnRef right, JoinKind kind)`, with a 3-argument constructor meaning `INNER`
  - `QueryIntent`'s components `boolean distinct` and `boolean anyOf`, with the 8-argument constructor kept

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeEmitterTest {

    private final SqlEmitter sql = new SqlEmitter();
    private final MongoEmitter mongo = new MongoEmitter(new ActiveSchemaProvider());

    private static QueryIntent.ColumnRef col(String column) {
        return new QueryIntent.ColumnRef(null, column);
    }

    private static QueryIntent.SelectItem item(QueryIntent.Operand operand) {
        return new QueryIntent.SelectItem(operand);
    }

    private static QueryIntent intent(String table, QueryIntent.JoinSpec join, List<QueryIntent.SelectItem> select,
                                      List<QueryIntent.Predicate> where, boolean distinct, boolean anyOf) {
        return new QueryIntent(QueryIntent.Verb.SELECT, table, join, select, where, List.of(), List.of(), null,
                distinct, anyOf);
    }

    private static final List<QueryIntent.SelectItem> ALL = List.of(item(QueryIntent.Star.INSTANCE));

    @Test
    @DisplayName("not-equal and not-in")
    void notEqual() {
        QueryIntent ne = intent("orders", null, ALL, List.of(new QueryIntent.Predicate(col("status"),
                QueryIntent.Op.NE, List.of(QueryIntent.Literal.text("shipped", "SHIPPED")))), false, false);
        assertThat(sql.emit(ne)).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
        assertThat(mongo.emit(ne)).isEqualTo("db.orders.find({\"status\": {\"$ne\": \"SHIPPED\"}})");

        QueryIntent notIn = intent("customers", null, ALL, List.of(new QueryIntent.Predicate(col("city"),
                QueryIntent.Op.NOT_IN, List.of(QueryIntent.Literal.text("pune", "Pune"),
                QueryIntent.Literal.text("delhi", "Delhi")))), false, false);
        assertThat(sql.emit(notIn)).isEqualTo("SELECT * FROM customers WHERE city NOT IN ('pune', 'delhi');");
        assertThat(mongo.emit(notIn)).isEqualTo("db.customers.find({\"city\": {\"$nin\": [\"Pune\", \"Delhi\"]}})");
    }

    @Test
    @DisplayName("between")
    void between() {
        QueryIntent between = intent("products", null, ALL, List.of(new QueryIntent.Predicate(col("price"),
                QueryIntent.Op.BETWEEN, List.of(QueryIntent.Literal.number(new BigDecimal("100")),
                QueryIntent.Literal.number(new BigDecimal("500"))))), false, false);
        assertThat(sql.emit(between)).isEqualTo("SELECT * FROM products WHERE price BETWEEN 100 AND 500;");
        assertThat(mongo.emit(between)).isEqualTo("db.products.find({\"price\": {\"$gte\": 100, \"$lte\": 500}})");
    }

    @Test
    @DisplayName("anyOf ORs the conditions")
    void anyOf() {
        QueryIntent or = intent("customers", null, ALL, List.of(
                new QueryIntent.Predicate(col("city"), QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("mumbai", "Mumbai"))),
                new QueryIntent.Predicate(col("name"), QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("asha", "Asha")))),
                false, true);
        assertThat(sql.emit(or)).isEqualTo("SELECT * FROM customers WHERE (city = 'mumbai' OR name = 'asha');");
        assertThat(mongo.emit(or)).isEqualTo("db.customers.find({\"$or\": [{\"city\": \"Mumbai\"}, {\"name\": \"Asha\"}]})");
    }

    @Test
    @DisplayName("distinct")
    void distinct() {
        QueryIntent distinct = intent("customers", null, List.of(item(col("city"))), List.of(), true, false);
        assertThat(sql.emit(distinct)).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(mongo.emit(distinct)).isEqualTo("db.customers.aggregate([{\"$group\": {\"_id\": \"$city\"}}])");
    }

    @Test
    @DisplayName("anti-join, as rows and as a count")
    void antiJoin() {
        QueryIntent.JoinSpec anti = new QueryIntent.JoinSpec("orders",
                new QueryIntent.ColumnRef("customers", "id"), new QueryIntent.ColumnRef("orders", "customer_id"),
                QueryIntent.JoinKind.ANTI);

        QueryIntent rows = intent("customers", anti, List.of(item(new QueryIntent.TableStar("customers"))), List.of(), false, false);
        assertThat(sql.emit(rows)).isEqualTo("SELECT customers.* FROM customers LEFT JOIN orders "
                + "ON customers.id = orders.customer_id WHERE orders.customer_id IS NULL;");
        assertThat(mongo.emit(rows)).isEqualTo("db.customers.aggregate([{\"$lookup\": {\"from\": \"orders\", "
                + "\"localField\": \"id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$match\": {\"orders\": {\"$size\": 0}}}, {\"$project\": {\"orders\": 0}}])");

        QueryIntent count = intent("customers", anti,
                List.of(item(new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null))), List.of(), false, false);
        assertThat(sql.emit(count)).isEqualTo("SELECT COUNT(*) FROM customers LEFT JOIN orders "
                + "ON customers.id = orders.customer_id WHERE orders.customer_id IS NULL;");
        assertThat(mongo.emit(count)).endsWith("{\"$project\": {\"orders\": 0}}, {\"$count\": \"count\"}])");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ShapeEmitterTest`
Expected: compilation failure — the new ops, `TableStar`, `JoinKind` and the 10-argument constructor don't exist.

- [ ] **Step 3: Implement**

`QueryIntent.java`:
- The record becomes `QueryIntent(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems, List<Predicate> where, List<Predicate> having, List<OrderItem> orderBy, Integer limit, boolean distinct, boolean anyOf)`.
- Add:

```java
    public QueryIntent(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems,
                       List<Predicate> where, List<Predicate> having, List<OrderItem> orderBy, Integer limit) {
        this(verb, fromTable, join, selectItems, where, having, orderBy, limit, false, false);
    }
```

- `Op` becomes `EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<="), IN("IN"), NE("!="), NOT_IN("NOT IN"), BETWEEN("BETWEEN");`.
- `public sealed interface Operand permits ColumnRef, Aggregate, Star, TableStar {}`
- `public record TableStar(String table) implements Operand {}`
- `public enum JoinKind { INNER, ANTI }`
- `JoinSpec`:

```java
    public record JoinSpec(String table, ColumnRef left, ColumnRef right, JoinKind kind) {
        public JoinSpec(String table, ColumnRef left, ColumnRef right) {
            this(table, left, right, JoinKind.INNER);
        }
    }
```

`SqlEmitter.java` — replace `emit` and `predicate`, add `render`, and add the `TableStar` case to `operand`:

```java
    @Override
    public String emit(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            sql.append("DELETE FROM ").append(intent.fromTable());
        } else {
            sql.append(intent.distinct() ? "SELECT DISTINCT " : "SELECT ")
                    .append(intent.selectItems().stream()
                            .map(item -> operand(item.operand()))
                            .collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
        }

        boolean anti = intent.join() != null && intent.join().kind() == QueryIntent.JoinKind.ANTI;
        if (intent.join() != null) {
            sql.append(anti ? " LEFT JOIN " : " JOIN ").append(intent.join().table())
                    .append(" ON ").append(column(intent.join().left()))
                    .append(" = ").append(column(intent.join().right()));
        }

        List<String> conditions = new ArrayList<>();
        if (!intent.where().isEmpty()) {
            conditions.add(intent.anyOf() && intent.where().size() > 1
                    ? "(" + render(intent.where(), " OR ") + ")"
                    : render(intent.where(), " AND "));
        }
        if (anti) {
            conditions.add(column(intent.join().right()) + " IS NULL");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        if (intent.hasAggregate()) {
            List<String> grouped = intent.selectItems().stream()
                    .filter(item -> !item.aggregated())
                    .map(item -> operand(item.operand()))
                    .toList();
            if (!grouped.isEmpty()) {
                sql.append(" GROUP BY ").append(String.join(", ", grouped));
            }
        }

        if (!intent.having().isEmpty()) {
            sql.append(" HAVING ").append(render(intent.having(), " AND "));
        }

        if (!intent.orderBy().isEmpty()) {
            sql.append(" ORDER BY ").append(intent.orderBy().stream()
                    .map(o -> column(o.column()) + " " + o.direction().name())
                    .collect(Collectors.joining(", ")));
        }

        if (intent.limit() != null) {
            sql.append(" LIMIT ").append(intent.limit());
        }

        return sql.append(";").toString();
    }

    private String render(List<QueryIntent.Predicate> predicates, String joiner) {
        return predicates.stream().map(this::predicate).collect(Collectors.joining(joiner));
    }

    private String predicate(QueryIntent.Predicate p) {
        String lhs = operand(p.lhs());
        return switch (p.op()) {
            case IN -> lhs + " IN " + p.values().stream()
                    .map(v -> "'" + v.text() + "'")
                    .collect(Collectors.joining(", ", "(", ")"));
            case NOT_IN -> lhs + " NOT IN " + p.values().stream()
                    .map(SqlEmitter::literal)
                    .collect(Collectors.joining(", ", "(", ")"));
            case BETWEEN -> lhs + " BETWEEN " + literal(p.values().get(0)) + " AND " + literal(p.values().get(1));
            default -> lhs + " " + p.op().sql() + " " + literal(p.values().get(0));
        };
    }
```

- Delete the old `renderAll`.
- In `operand`, add `case QueryIntent.TableStar star -> star.table() + ".*";`.
- Add `import java.util.ArrayList;`.

`MongoEmitter.java`:
- In `emit`, the fast-path condition becomes `if (intent.join() == null && unwinds.isEmpty() && intent.having().isEmpty() && !intent.distinct()) {`.
- In `pipeline`, replace the join block with:

```java
        if (intent.join() != null) {
            QueryIntent.JoinSpec join = intent.join();
            stages.add(new Document("$lookup", new Document("from", join.table())
                    .append("localField", field(intent, join.left()))
                    .append("foreignField", join.right().column())
                    .append("as", join.table())));
            if (join.kind() == QueryIntent.JoinKind.ANTI) {
                stages.add(new Document("$match", new Document(join.table(), new Document("$size", 0))));
                stages.add(new Document("$project", new Document(join.table(), 0)));
            } else {
                stages.add(new Document("$unwind", "$" + join.table()));
            }
        }
```

- Immediately after the `unwinds.forEach(...)` line, add:

```java
        if (intent.distinct()) {
            List<QueryIntent.ColumnRef> keys = groupKeys(intent);
            stages.add(new Document("$group", new Document("_id", groupId(intent, keys))));
            if (!intent.orderBy().isEmpty()) {
                Document sort = new Document();
                for (QueryIntent.OrderItem o : intent.orderBy()) {
                    sort.append(groupedPath(intent, keys, o.column()), direction(o));
                }
                stages.add(new Document("$sort", sort));
            }
            if (intent.limit() != null) {
                stages.add(new Document("$limit", intent.limit()));
            }
            return stages;
        }
```

- Replace `filter` with:

```java
    private Document filter(QueryIntent intent, List<QueryIntent.Predicate> predicates) {
        for (QueryIntent.Predicate p : predicates) {
            requireFilterable(intent, (QueryIntent.ColumnRef) p.lhs());
        }
        if (intent.anyOf() && predicates.size() > 1) {
            List<Document> branches = predicates.stream()
                    .map(p -> new Document(field(intent, (QueryIntent.ColumnRef) p.lhs()), condition(p)))
                    .toList();
            return new Document("$or", branches);
        }
        return matchDocument(predicates, p -> field(intent, (QueryIntent.ColumnRef) p.lhs()));
    }
```

- In `condition`, add `case NE -> new Document("$ne", values.get(0));`, `case NOT_IN -> new Document("$nin", values);` and `case BETWEEN -> new Document("$gte", values.get(0)).append("$lte", values.get(1));`.
- In `embeddedArraysUsed`, add `case QueryIntent.TableStar t -> null;`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='ShapeEmitterTest,SqlEmitterTest,MongoEmitterTest,DialectParityTest,QueryIntentParserTest'`
Expected: PASS. `ShapeEmitterTest` 5/5, and the existing emitter and parser tests are unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): QueryIntent gains not-equal, not-in, between, distinct, OR and anti-join, in both emitters

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Inclusive bounds and `between`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/DeclineException.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/AmbiguousWordException.java`, `QueryIntentParser.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/QuestionShapesTest.java` (new)

**Interfaces:**
- Produces:
  - `class DeclineException extends RuntimeException`; `AmbiguousWordException` now extends it, and the parser's entry point catches `DeclineException`
  - `COMPARISON_OPS`
  - the `BETWEEN` reader

- [ ] **Step 1: Write the failing test**

`QuestionShapesTest.java`, which later tasks extend:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The shapes of sub-project 2 on a schema that declares no aliases. */
class QuestionShapesTest {

    static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city", "email")),
            new KnownSchema.Table("orders", List.of("id", "customer_id", "amount", "status")),
            new KnownSchema.Table("products", List.of("id", "name", "price")),
            new KnownSchema.Table("payments", List.of("id", "order_id", "amount", "method")),
            new KnownSchema.Table("listings", List.of("id", "price", "cost")));

    static final List<KnownSchema.ForeignKey> KEYS = List.of(
            new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id"),
            new KnownSchema.ForeignKey("payments", "order_id", "orders", "id"));

    private QueryIntentParser parser;

    static SchemaSnapshot upload(Dialect dialect) {
        return new SchemaSnapshot("upload-s", "shop", "querysense_uploads", TABLES, KEYS, Map.of(),
                Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of());
    }

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(m.getDistinctValues(eq("customers"), eq("name"))).thenReturn(List.of("Asha", "Ravi"));
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("SHIPPED", "PENDING"));
        return m;
    }

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(upload(Dialect.MYSQL));
        schemas.publish(upload(Dialect.MONGODB));
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    String sql(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        return result.sql();
    }

    String declineReason(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).isFalse();
        return result.trace().declineReason();
    }

    @Test
    @DisplayName("inclusive bounds")
    void inclusiveBounds() {
        assertThat(sql("products with price at least 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
        assertThat(sql("products with price no less than 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
        assertThat(sql("products with price at most 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("products with price no more than 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("products with price up to 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("which customers placed at least three orders")).isEqualTo(
                "SELECT customers.id, customers.name FROM customers JOIN orders ON customers.id = orders.customer_id "
                        + "GROUP BY customers.id, customers.name HAVING COUNT(orders.id) >= 3;");
    }

    @Test
    @DisplayName("between")
    void between() {
        assertThat(sql("products with price between 100 and 500"))
                .isEqualTo("SELECT * FROM products WHERE price BETWEEN 100 AND 500;");
    }

    @Test
    @DisplayName("betweenWithAndInsideNumber: the low end is read whole before the separating 'and'")
    void betweenWithAndInsideNumber() {
        assertThat(sql("products with price between two hundred and fifty and 500"))
                .isEqualTo("SELECT * FROM products WHERE price BETWEEN 250 AND 500;");
    }

    @Test
    @DisplayName("a between whose ends aren't numbers declines")
    void betweenNonNumbers() {
        assertThat(declineReason("products with price between cheap and dear")).contains("two numbers");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: `inclusiveBounds` and `between` fail, since `at least` and `between` aren't read. `betweenNonNumbers` may pass or fail.

- [ ] **Step 3: Implement**

`DeclineException.java`:

```java
package com.querysense.backend.querybuilder;

/** A reason the rule engine declines, raised from inside a helper and turned into a decline by the parser. */
class DeclineException extends RuntimeException {

    DeclineException(String message) {
        super(message);
    }
}
```

`AmbiguousWordException.java`: change `extends RuntimeException` to `extends DeclineException`.

In `QueryIntentParser.java`:

1. In the public `parse`, change `catch (AmbiguousWordException e)` to `catch (DeclineException e)`.

2. Replace `COMPARISON` and `COMPARISON_GREATER` with:

```java
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
            "\\b([a-z_]+)\\s+(?:is\\s+)?(" + alternation(COMPARISON_OPS.keySet()) + ")\\s+(.+)$");

    private static final Pattern BETWEEN = Pattern.compile("\\b([a-z_]+)\\s+(?:is\\s+)?between\\s+(.+)$");

    private static String alternation(java.util.Collection<String> words) {
        return words.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
    }
```

   `COMPARISON_OPS` and `alternation` must be declared before `COMPARISON`. Static initialisers run in declaration order.

3. In the comparison branch, replace the two lines that derive `operator` and `op` from `COMPARISON_GREATER` with:

```java
            QueryIntent.Op op = COMPARISON_OPS.get(comparison.group(2));
            String operator = op.sql();
```

4. Immediately before `Matcher comparison = COMPARISON.matcher(text);`, add:

```java
        Matcher between = BETWEEN.matcher(text);
        if (between.find()) {
            String rest = between.group(2);
            Optional<NumberReader.Reading> low = NumberReader.leading(rest);
            String after = low.map(r -> rest.substring(r.phrase().length()).trim()).orElse("");
            Optional<NumberReader.Reading> high = after.startsWith("and ")
                    ? NumberReader.leading(after.substring(4))
                    : Optional.empty();
            if (low.isEmpty() || high.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for " + between.group(1) + " between \"" + rest
                                + "\", but this engine can only read a range between two numbers.",
                        bindings);
            }
            Optional<String> rangeColumn = column(subject, between.group(1), interpretations);
            if (rangeColumn.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for a range of \"" + between.group(1) + "\", which is not a column of "
                                + subject.name() + ".",
                        bindings);
            }
            QueryIntent.Literal from = QueryIntent.Literal.number(low.get().value());
            QueryIntent.Literal to = QueryIntent.Literal.number(high.get().value());
            where.add(new QueryIntent.Predicate(qualify(rangeColumn.get(), subject, joined),
                    QueryIntent.Op.BETWEEN, List.of(from, to)));
            String lhs = joined ? subject.name() + "." + rangeColumn.get() : rangeColumn.get();
            bindings.add(new RuleTrace.Binding(
                    between.group(1) + " between " + low.get().phrase() + " and " + high.get().phrase(),
                    "COMPARISON", lhs + " BETWEEN " + from.text() + " AND " + to.text()));
        }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,VocabularyTest,RuleTraceWordingTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): at least, at most, up to and between

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Value reading as one method; not-equal and exclusion

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Consumes: `valueRun` and `columnsHolding` (sub-project 1); `DeclineException` (Task 2).
- Produces:
  - `record ValueList(String column, List<String> typed, List<QueryIntent.Literal> literals, int end)`
  - `Optional<ValueList> readValues(String text, int from, KnownSchema.Table subject, KnownSchema.Table related, String onlyColumn, SchemaMetadata metadata, Dialect dialect, List<Interpretation> interpretations)`
  - a `List<int[]> consumed` of spans, which the E14 loop skips

- [ ] **Step 1: Write the failing tests**

Add to `QuestionShapesTest`:

```java
    @Test
    @DisplayName("not-equal on a named column")
    void notEqualColumn() {
        assertThat(sql("orders with status not shipped")).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
        assertThat(sql("orders with status is not shipped")).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
    }

    @Test
    @DisplayName("exclusion finds its column from the data")
    void exclusion() {
        assertThat(sql("customers other than pune")).isEqualTo("SELECT * FROM customers WHERE city != 'pune';");
        assertThat(sql("customers excluding pune and delhi"))
                .isEqualTo("SELECT * FROM customers WHERE city NOT IN ('pune', 'delhi');");
        assertThat(sql("customers except mumbai")).isEqualTo("SELECT * FROM customers WHERE city != 'mumbai';");
    }

    @Test
    @DisplayName("notInIsOnePredicate: 'not in pune' is one not-equal, never also an equality")
    void notInIsOnePredicate() {
        assertThat(sql("customers not in pune")).isEqualTo("SELECT * FROM customers WHERE city != 'pune';");
    }

    @Test
    @DisplayName("an exclusion whose value is nowhere declines")
    void exclusionUnknownValue() {
        assertThat(declineReason("customers excluding atlantis")).contains("\"atlantis\"");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: the four new tests fail. `not in pune` currently builds `city = 'pune'`.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Add the patterns:

```java
    private static final Pattern EXCLUDING = Pattern.compile(
            "\\b(?:other than|excluding|except|not in|not from)\\s+(?=[a-z])");

    private static final Pattern NOT_VALUE = Pattern.compile("\\b([a-z_]+)\\s+(?:is\\s+)?not\\s+(?=[a-z])");
```

2. `valueRun` gains a final parameter `String onlyColumn`. Inside it, filter by that column when it is set:
   - the exact-run search: `List<ValueMatch> matches = columnsHolding(table, phrase, metadata).stream().filter(m -> onlyColumn == null || m.column().equals(onlyColumn)).toList();`
   - the typo search: `for (String column : table.columns())` gets `if (onlyColumn != null && !column.equals(onlyColumn)) { continue; }` as its first line.

3. Add:

```java
    private record ValueList(String column, List<String> typed, List<QueryIntent.Literal> literals, int end) {}

    private Optional<ValueList> readValues(String text, int from, KnownSchema.Table subject, KnownSchema.Table related,
                                           String onlyColumn, SchemaMetadata metadata, Dialect dialect,
                                           List<Interpretation> interpretations) {
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
                    || isColumnOfEither(first, subject, related)) {
                break;
            }
            ValueRun run = valueRun(subject, array, i, metadata, onlyColumn);
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
            typed.add(run.typed());
            if (run.typo()) {
                literals.add(QueryIntent.Literal.text(run.stored(), run.stored()));
                note(run.typed(), subject.name() + "." + run.column() + " = " + run.stored(),
                        SchemaLexicon.MatchKind.TYPO, interpretations);
            } else {
                literals.add(QueryIntent.Literal.text(run.typed(), run.stored()));
            }
            i += run.words();
            end = ends.get(i - 1);
            if (i < array.length && "and".equals(array[i])) {
                i++;
                continue;
            }
            break;
        }
        return typed.isEmpty() ? Optional.empty() : Optional.of(new ValueList(valueColumn, typed, literals, end));
    }

    private static boolean insideAny(int position, List<int[]> spans) {
        return spans.stream().anyMatch(s -> position >= s[0] && position < s[1]);
    }
```

4. Replace the whole E14 `while (preposition.find()) { … }` block with:

```java
        List<int[]> consumed = new ArrayList<>();

        Matcher excluding = EXCLUDING.matcher(text);
        if (excluding.find()) {
            Optional<ValueList> list = readValues(text, excluding.end(), subject, related, null, metadata, dialect,
                    interpretations);
            if (list.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question excludes something, but no value of " + subject.name() + " follows.",
                        bindings);
            }
            where.add(new QueryIntent.Predicate(qualify(list.get().column(), subject, joined),
                    list.get().typed().size() == 1 ? QueryIntent.Op.NE : QueryIntent.Op.NOT_IN,
                    List.copyOf(list.get().literals())));
            bindings.add(new RuleTrace.Binding("not " + String.join(" and ", list.get().typed()), "FILTER",
                    subject.name() + "." + list.get().column()));
            consumed.add(new int[]{excluding.start(), list.get().end()});
        } else {
            Matcher notValue = NOT_VALUE.matcher(text);
            if (notValue.find()) {
                Optional<String> notColumn = binder.bindColumn(subject, notValue.group(1));
                if (notColumn.isPresent()) {
                    Optional<ValueList> list = readValues(text, notValue.end(), subject, related, notColumn.get(),
                            metadata, dialect, interpretations);
                    if (list.isEmpty()) {
                        return RuleBuildResult.declined(
                                "The question says " + notValue.group(1) + " is not something, but no stored value "
                                        + "of " + subject.name() + "." + notColumn.get() + " follows.",
                                bindings);
                    }
                    where.add(new QueryIntent.Predicate(qualify(notColumn.get(), subject, joined),
                            list.get().typed().size() == 1 ? QueryIntent.Op.NE : QueryIntent.Op.NOT_IN,
                            List.copyOf(list.get().literals())));
                    bindings.add(new RuleTrace.Binding(notValue.group(1) + " not "
                            + String.join(" and ", list.get().typed()), "FILTER",
                            subject.name() + "." + notColumn.get()));
                    consumed.add(new int[]{notValue.start(), list.get().end()});
                }
            }
        }

        Matcher preposition = PREPOSITION.matcher(text);
        while (preposition.find()) {
            if (insideAny(preposition.start(), consumed)) {
                continue;
            }
            Optional<ValueList> list = readValues(text, preposition.end(), subject, related, null, metadata, dialect,
                    interpretations);
            if (list.isEmpty()) {
                continue;
            }
            where.add(new QueryIntent.Predicate(qualify(list.get().column(), subject, joined),
                    list.get().typed().size() == 1 ? QueryIntent.Op.EQ : QueryIntent.Op.IN,
                    List.copyOf(list.get().literals())));
            bindings.add(new RuleTrace.Binding(String.join(" and ", list.get().typed()), "FILTER",
                    subject.name() + "." + list.get().column()));
            consumed.add(new int[]{preposition.start(), list.get().end()});
            break;
        }
```

5. The `with` branch's guard `if (where.isEmpty() && with.find() …` is unchanged here; Task 6 changes it.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,VocabularyTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,RuleTraceWordingTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): not-equal and exclusion, through one shared value reader

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Without / never (anti-join)

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Consumes: `JoinKind.ANTI` and `TableStar` (Task 1).
- Produces: anti-join intents for `without`, `with no`, `who have no`, `that have no`, `having no`, `with zero` and `never <verb>ed`.

- [ ] **Step 1: Write the failing tests**

Add to `QuestionShapesTest`:

```java
    @Test
    @DisplayName("without: rows with no related row")
    void without() {
        String expected = "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                + "WHERE orders.customer_id IS NULL;";
        assertThat(sql("customers without orders")).isEqualTo(expected);
        assertThat(sql("customers with no orders")).isEqualTo(expected);
        assertThat(sql("customers who have no orders")).isEqualTo(expected);
    }

    @Test
    @DisplayName("without, counted and filtered")
    void withoutCountedAndFiltered() {
        assertThat(sql("how many customers without orders")).isEqualTo(
                "SELECT COUNT(*) FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.customer_id IS NULL;");
        assertThat(sql("customers in pune without orders")).isEqualTo(
                "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE customers.city = 'pune' AND orders.customer_id IS NULL;");
    }

    @Test
    @DisplayName("never <verb>ed reads the verb's stem as a table; with no direct key it declines")
    void never() {
        assertThat(sql("customers who never ordered")).isEqualTo(
                "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.customer_id IS NULL;");
        assertThat(declineReason("products never ordered")).contains("no relationship");
    }

    @Test
    @DisplayName("an anti-join with no linking key declines")
    void withoutNoKey() {
        assertThat(declineReason("products without payments")).contains("no relationship");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: the four new tests fail.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Add the patterns:

```java
    private static final Pattern WITHOUT = Pattern.compile(
            "\\b(?:without|with no|who have no|that have no|having no|with zero)\\s+(?:any\\s+)?");

    private static final Pattern NEVER = Pattern.compile("\\bnever\\s+([a-z]+?)(?:ed|d)\\b");
```

2. Directly after the tables-size checks, and before `KnownSchema.Table subject = tables.get(0);`, add:

```java
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
```

   `tables` must be a non-final local for this. If it is declared `List<KnownSchema.Table> tables = …`, that already works.

3. Where the `JoinSpec` is built (`joinSpec = joinSpecFor(edge, subject, related);`), follow it with:

```java
            if (anti) {
                joinSpec = new QueryIntent.JoinSpec(joinSpec.table(), joinSpec.left(), joinSpec.right(),
                        QueryIntent.JoinKind.ANTI);
            }
```

4. After the comparison block, add:

```java
        if (anti && !having.isEmpty()) {
            return RuleBuildResult.declined(
                    "The question asks for " + subject.name() + " without " + related.name()
                            + " and also counts them, which this engine won't combine.",
                    bindings);
        }
```

5. In the select-building chain, change the final branch to:

```java
        } else if (verb == QueryIntent.Verb.SELECT && selectItems.isEmpty()) {
            selectItems.add(new QueryIntent.SelectItem(anti
                    ? new QueryIntent.TableStar(subject.name())
                    : QueryIntent.Star.INSTANCE));
        }
```

6. In `shapeOf`, after the `+ JOIN` line, add `if (intent.join() != null && intent.join().kind() == QueryIntent.JoinKind.ANTI) { shape.append(" (without)"); }`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,VocabularyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): without and never build an anti-join through one declared key

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: DISTINCT

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Produces: `distinct` set on the intent for `distinct/unique/different <column> of|in|from|among|for` and `which|what <column> do|does|did|are|have|has`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    @DisplayName("distinct values of a column")
    void distinct() {
        assertThat(sql("distinct cities of customers")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("unique cities of customers")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("which cities do customers live in")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("what statuses do orders have")).isEqualTo("SELECT DISTINCT status FROM orders;");
    }

    @Test
    @DisplayName("whichTableIsNotDistinct: 'which customers are in mumbai' reads as today")
    void whichTableIsNotDistinct() {
        assertThat(sql("which customers are in mumbai")).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: `distinct` fails; `whichTableIsNotDistinct` passes.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Add the patterns:

```java
    private static final Pattern DISTINCT_PHRASE = Pattern.compile(
            "\\b(?:distinct|unique|different)\\s+([a-z_]+(?:\\s+[a-z_]+)?)\\s+(?:of|in|from|among|for)\\b");

    private static final Pattern WHICH_PHRASE = Pattern.compile(
            "^(?:which|what)\\s+([a-z_]+(?:\\s+[a-z_]+)?)\\s+(?:do|does|did|are|have|has)\\b");
```

2. Add the helper:

```java
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
        return bound;
    }
```

3. Immediately before `Optional<String> strongPhrase = extractStrongProjectionPhrase(text);`, add:

```java
        Optional<String> distinctColumn = distinctColumn(text, subject, dialect, interpretations);
        boolean distinct = distinctColumn.isPresent();
```

   Change the strong-phrase line to `Optional<String> strongPhrase = distinct ? Optional.empty() : extractStrongProjectionPhrase(text);`.

   After the strong-phrase block, add:

```java
        if (distinct) {
            projected = List.of(distinctColumn.get());
            projectionRequested = true;
            bindings.add(new RuleTrace.Binding(distinctColumn.get(), "DISTINCT",
                    subject.name() + "." + distinctColumn.get()));
        }
```

4. Construct the intent with the 10-argument constructor: `new QueryIntent(verb, subject.name(), joinSpec, List.copyOf(selectItems), List.copyOf(where), List.copyOf(having), List.copyOf(orderBy), limit, distinct, false)`. Task 6 replaces the last `false`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,VocabularyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): distinct, unique and which-X-do questions select distinct values

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: OR across conditions, every comparison, and the `with` filter beside others

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Consumes: `consumed` and `readValues` (Task 3).
- Produces: a `List<int[]> conditionSpans`, recorded for every condition; `anyOf` on the intent; `or` as a value separator.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    @DisplayName("or inside a value list is IN; or between conditions is OR")
    void or() {
        assertThat(sql("customers in mumbai or pune")).isEqualTo("SELECT * FROM customers WHERE city IN ('mumbai', 'pune');");
        assertThat(sql("customers in mumbai or with name asha"))
                .isEqualTo("SELECT * FROM customers WHERE (city = 'mumbai' OR name = 'asha');");
        assertThat(sql("products with price below 100 or price above 900"))
                .isEqualTo("SELECT * FROM products WHERE (price < 100 OR price > 900);");
    }

    @Test
    @DisplayName("every condition is used: a second comparison and a with-filter beside another filter (spec §3.8)")
    void silentDropsFixed() {
        assertThat(sql("products with price above 100 and price below 900"))
                .isEqualTo("SELECT * FROM products WHERE price > 100 AND price < 900;");
        assertThat(sql("customers in mumbai with name asha"))
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai' AND name = 'asha';");
    }

    @Test
    @DisplayName("withInsideComparisonIsNotAFilter: 'with price at least 500' is one predicate")
    void withInsideComparisonIsNotAFilter() {
        assertThat(sql("products with price at least 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
    }

    @Test
    @DisplayName("and mixed with or between conditions declines; or with an anti-join declines")
    void mixedDeclines() {
        assertThat(declineReason("products with price below 100 or price above 900 and price below 950"))
                .contains("both \"and\" and \"or\"");
        assertThat(declineReason("customers in pune or with name asha without orders")).contains("without");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: `or`, `silentDropsFixed` and `mixedDeclines` fail.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. In `readValues`, change `if (i < array.length && "and".equals(array[i])) {` to `if (i < array.length && ("and".equals(array[i]) || "or".equals(array[i]))) {`.

2. Declare `List<int[]> conditionSpans = new ArrayList<>();` next to `where`.

3. **Between:** after its `where.add`, add `conditionSpans.add(new int[]{between.start(), between.start(2) + rest.indexOf(high.get().phrase(), low.get().phrase().length()) + high.get().phrase().length()});`.

4. **Comparison:** replace `if (comparison.find()) { … }` with a loop that reads every comparison:

```java
        int searchFrom = 0;
        while (searchFrom < text.length() && comparison.find(searchFrom)) {
            if (insideAny(comparison.start(), conditionSpans)) {
                searchFrom = comparison.end(2);
                continue;
            }
            // existing body of the comparison block, unchanged, with these additions:
            //   after a WHERE predicate is added:
            //     conditionSpans.add(new int[]{comparison.start(), comparison.start(3) + reading.get().phrase().length()});
            //   after a HAVING predicate is added: the same conditionSpans.add(...)
            searchFrom = comparison.start(3) + reading.get().phrase().length();
        }
```

   Write the body out in full: the existing comparison code moves inside the loop, with the two `conditionSpans.add(...)` lines placed as described. A decline inside the loop still returns.
   - **The count path:** `countTargetColumn` is set by the first comparison that takes it. A second comparison taking the count path declines: `"The question compares two counts, which this engine won't combine."`

5. **Exclusion and E14:** each `consumed.add(new int[]{…})` from Task 3 is followed by the same `conditionSpans.add(new int[]{…})`.

6. **The `with` filter:** replace its guard `if (where.isEmpty() && with.find() && …` with:

```java
        Matcher with = WITH_VALUE.matcher(text);
        if (with.find() && !insideAny(with.start(1), conditionSpans) && !insideAny(with.start(), conditionSpans)
                && !VALUE_STOPWORDS.contains(with.group(1)) && !NON_COLUMN_WORDS.contains(with.group(1))
                && conditionSpans.stream().noneMatch(s -> s[0] >= with.start() && s[0] < with.end())) {
```

   After its `where.add`, add `conditionSpans.add(new int[]{with.start(), number.isPresent() ? with.start(2) + number.get().phrase().length() : with.end()});`.

7. After all conditions are read, and before the select is built:

```java
        boolean anyOf = false;
        if (where.size() >= 2) {
            List<int[]> ordered = new ArrayList<>(conditionSpans);
            ordered.sort(java.util.Comparator.comparingInt(s -> s[0]));
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
```

8. Pass `anyOf` as the intent's last argument.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,VocabularyTest,RuleTraceWordingTest,MongoRuleEngineTest'`
Expected: PASS. If an existing test asserted one of the §3.8 silent drops, update it to the new output and write a `Ruling:` line naming the test.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): OR across conditions, every comparison read, and with-filters beside other filters

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Several totals in one question

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Produces: `List<QueryIntent.Aggregate> aggregates`, replacing `aggregateFunction` and `aggregateColumn`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    @DisplayName("several totals share one column; count means COUNT(*)")
    void severalTotals() {
        assertThat(sql("average and highest price of products")).isEqualTo("SELECT AVG(price), MAX(price) FROM products;");
        assertThat(sql("count and average amount of payments per method"))
                .isEqualTo("SELECT method, COUNT(*), AVG(amount) FROM payments GROUP BY method;");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest#severalTotals`
Expected: FAIL — both decline today.

- [ ] **Step 3: Implement**

1. Add the pattern:

```java
    private static final String AGGREGATE_WORDS = "average|avg|mean|sum|maximum|max|highest|minimum|min|lowest|count";

    private static final Pattern AGGREGATES = Pattern.compile(
            "\\b((?:" + AGGREGATE_WORDS + ")(?:\\s*(?:,|and)\\s*(?:" + AGGREGATE_WORDS + "))+)\\s+"
                    + "(?:of\\s+)?(?:the\\s+)?([a-z_ ]+?)\\b");
```

2. Replace the `aggregateFunction` and `aggregateColumn` declarations and their block with:

```java
        List<QueryIntent.Aggregate> aggregates = new ArrayList<>();

        if (!projectionRequested) {
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
            } else if (agg.find()) {
                String fn = AGGREGATE_FUNCTIONS.get(agg.group(1));
                Optional<String> col = column(subject, agg.group(2), interpretations);
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
```

3. In the nothing-to-group check, replace `aggregateFunction == null` with `aggregates.isEmpty()`.

4. In the select chain, replace the `if (aggregateFunction != null) { … }` branch with `if (!aggregates.isEmpty()) { aggregates.forEach(a -> selectItems.add(new QueryIntent.SelectItem(a))); }`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,VocabularyTest,MongoRuleEngineTest'`
Expected: PASS. Single-aggregate output is unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): several totals of one column in one question

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Ranking and superlatives

**Files:**
- Create: `backend/src/main/resources/querybuilder/adjectives.json`, `backend/src/main/java/com/querysense/backend/querybuilder/Adjectives.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `QuestionShapesTest.java`

**Interfaces:**
- Produces:
  - `Adjectives.builtIn()`
  - `record Adjectives.Entry(List<String> words, QueryIntent.Direction direction, boolean needsMostOrLeast, String type)`, where `type` is `"column"` here and sub-project 4 adds `"date"`
  - `Map<String, Entry> Adjectives.entries()`
  - `record Ranking(QueryIntent.ColumnRef column, QueryIntent.Direction direction, Integer limit)`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    @DisplayName("top and bottom by a column")
    void topBottom() {
        assertThat(sql("top 5 products by price")).isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 5;");
        assertThat(sql("bottom 3 products by price")).isEqualTo("SELECT * FROM products ORDER BY price ASC LIMIT 3;");
        assertThat(sql("top product by price")).isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 1;");
    }

    @Test
    @DisplayName("the N highest or lowest")
    void nHighest() {
        assertThat(sql("products with the 3 highest prices")).isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 3;");
        assertThat(sql("the 2 lowest prices of products")).isEqualTo("SELECT * FROM products ORDER BY price ASC LIMIT 2;");
    }

    @Test
    @DisplayName("superlative adjectives, with most and least")
    void adjectives() {
        assertThat(sql("cheapest product")).isEqualTo("SELECT * FROM products ORDER BY price ASC LIMIT 1;");
        assertThat(sql("3 most expensive products")).isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 3;");
        assertThat(sql("least expensive products")).isEqualTo("SELECT * FROM products ORDER BY price ASC;");
        assertThat(sql("largest orders")).isEqualTo("SELECT * FROM orders ORDER BY amount DESC;");
    }

    @Test
    @DisplayName("an adjective fitting two columns, or none, declines")
    void adjectiveDeclines() {
        assertThat(declineReason("cheapest listing")).contains("listings.price").contains("listings.cost");
        assertThat(declineReason("cheapest customer")).contains("cheapest");
    }

    @Test
    @DisplayName("highestWithoutNumberIsStillMax: today's MAX reading is unchanged")
    void highestWithoutNumberIsStillMax() {
        assertThat(sql("highest price of products")).isEqualTo("SELECT MAX(price) FROM products;");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=QuestionShapesTest`
Expected: `topBottom`, `nHighest`, `adjectives` and `adjectiveDeclines` fail. `highestWithoutNumberIsStillMax` passes.

- [ ] **Step 3: Implement**

`adjectives.json`:

```json
{
  "cheapest":   {"words": ["price", "cost"], "direction": "ASC",  "needsMostOrLeast": false, "type": "column"},
  "priciest":   {"words": ["price", "cost"], "direction": "DESC", "needsMostOrLeast": false, "type": "column"},
  "expensive":  {"words": ["price", "cost"], "direction": "DESC", "needsMostOrLeast": true,  "type": "column"},
  "costly":     {"words": ["price", "cost"], "direction": "DESC", "needsMostOrLeast": true,  "type": "column"},
  "largest":    {"words": ["amount", "size", "quantity", "total"], "direction": "DESC", "needsMostOrLeast": false, "type": "column"},
  "biggest":    {"words": ["amount", "size", "quantity", "total"], "direction": "DESC", "needsMostOrLeast": false, "type": "column"},
  "smallest":   {"words": ["amount", "size", "quantity", "total"], "direction": "ASC",  "needsMostOrLeast": false, "type": "column"},
  "heaviest":   {"words": ["weight"], "direction": "DESC", "needsMostOrLeast": false, "type": "column"},
  "lightest":   {"words": ["weight"], "direction": "ASC",  "needsMostOrLeast": false, "type": "column"},
  "longest":    {"words": ["length", "duration"], "direction": "DESC", "needsMostOrLeast": false, "type": "column"},
  "shortest":   {"words": ["length", "duration"], "direction": "ASC",  "needsMostOrLeast": false, "type": "column"}
}
```

`Adjectives.java`:

```java
package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The reviewed adjectives a ranking question may use, each with the column words it reaches and a direction. */
public final class Adjectives {

    public record Entry(List<String> words, QueryIntent.Direction direction, boolean needsMostOrLeast, String type) {}

    private static final String RESOURCE = "/querybuilder/adjectives.json";

    private final Map<String, Entry> entries;

    public Adjectives(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static Adjectives builtIn() {
        try (InputStream in = Adjectives.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            Map<String, Entry> read = new ObjectMapper().readValue(in, new TypeReference<LinkedHashMap<String, Entry>>() {});
            return new Adjectives(read);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    public Map<String, Entry> entries() {
        return entries;
    }
}
```

In `QueryIntentParser.java`:

1. Add the fields and patterns:

```java
    private static final Adjectives ADJECTIVES = Adjectives.builtIn();

    private static final Pattern TOP_BY = Pattern.compile(
            "\\b(top|bottom)\\b.*?\\bby\\s+(?:the\\s+)?([a-z_]+(?:\\s+[a-z_]+)?)");

    private static final Pattern RANK_N = Pattern.compile(
            "\\b(\\S+)\\s+(highest|lowest|greatest)\\s+([a-z_]+(?:\\s+[a-z_]+)?)"
                    + "|\\b(highest|lowest|greatest)\\s+(\\S+)\\s+([a-z_]+(?:\\s+[a-z_]+)?)");

    private static final Pattern ADJECTIVE = Pattern.compile(
            "\\b(?:(\\S+)\\s+)?(?:(most|least)\\s+)?(" + alternation(ADJECTIVES.entries().keySet()) + ")\\b(?:\\s+(\\S+))?");

    private record Ranking(QueryIntent.ColumnRef column, QueryIntent.Direction direction, Integer limit) {}
```

2. Change `LIMIT` to `Pattern.compile("\\b(?:top|first|limit|bottom)\\s+(.+)$")`.

3. Add the helper:

```java
    private Optional<Ranking> ranking(String text, KnownSchema.Table subject, boolean joined, boolean singular,
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
            if (!"column".equals(entry.type()) || (entry.needsMostOrLeast() && adjective.group(2) == null)) {
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
```

4. Work out `singular` right after the subject is chosen:

```java
        String subjectPhrase = tableMatches.hits().get(0).binding().phrase();
        boolean singular = !subjectPhrase.endsWith("s");
```

5. Immediately before the `List<QueryIntent.Aggregate> aggregates` block from Task 7, add:

```java
        Optional<Ranking> ranked = projectionRequested ? Optional.empty()
                : ranking(text, subject, joined, singular, interpretations);
```

   Then guard the aggregate block with `if (!projectionRequested && ranked.isEmpty()) {` in place of `if (!projectionRequested) {`.

6. Where `orderBy` is built:
   - after the existing `ORDER_BY` block, add `if (orderBy.isEmpty() && ranked.isPresent()) { orderBy.add(new QueryIntent.OrderItem(ranked.get().column(), ranked.get().direction())); }`;
   - after the limit block, add `if (limit == null && ranked.isPresent()) { limit = ranked.get().limit(); }`;
   - when ranked, add a binding: `bindings.add(new RuleTrace.Binding("ranking", "RANKING", SqlEmitter.column(ranked.get().column()) + " " + ranked.get().direction()));`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QuestionShapesTest,QueryIntentParserTest,DialectParityTest,VocabularyTest,DeclineBoundaryTest,MongoRuleEngineTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): top/bottom by, the N highest/lowest, and superlative adjectives rank and limit

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Parity, full checks and the record

**Files:**
- Modify: `backend/src/test/java/com/querysense/backend/querybuilder/DialectParityTest.java`, `RULES.md`, `PROJECT.md`

- [ ] **Step 1: Parity cases**

Add to `DialectParityTest`'s `@ValueSource`:

```java
            "top 5 products by price",
            "cheapest product",
            "products with price at least 500",
            "products with price between 100 and 500",
            "orders with status not shipped",
            "customers excluding pune and delhi",
            "customers without orders",
            "how many customers without orders",
            "distinct cities of customers",
            "customers in mumbai or pune",
            "average and highest price of products",
```

Run: `cd backend && ./mvnw -q test -Dtest=DialectParityTest`
Expected: PASS. A question that builds in one dialect only is a defect in Tasks 1–8; fix it there.

- [ ] **Step 2: Full checks**

Run: `cd backend && ./mvnw test`, then `cd frontend && npm test && npm run lint`.
Expected: all green.

- [ ] **Step 3: `RULES.md`**

1. Add rules E24–E30, each with what it does, why, when it declines, and an example, in the file's existing style:
   - E24 Ranking;
   - E25 Inclusive bounds and between;
   - E26 Not-equal and exclusion;
   - E27 Without (anti-join);
   - E28 Distinct;
   - E29 OR across conditions;
   - E30 Several totals.
2. Add the §3.8 intended changes under E13 and E15.
3. Update the rule-engine count, the decline table and the "How many rules" table.

- [ ] **Step 4: `PROJECT.md`**

- Add `### Session of <date> — rule engine question shapes (sub-project 2 of 4)`, with the task/commit table, the departures, the §3.8 changes, the test counts, and "not run in the app".
- In §5's roadmap table, mark sub-project 2 built and sub-project 3 next.

- [ ] **Step 5: Commit**

```bash
git add backend/src RULES.md PROJECT.md
git commit -m "docs: record rule engine question shapes — ranking, ranges, exclusion, anti-join, distinct, OR, totals

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
