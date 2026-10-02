# Rule Engine Multi-Table Joins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A question may name up to four tables. The rule engine follows the shortest chain of declared foreign keys linking them, puts filters, values and columns on any table in the chain, and builds it in MySQL and MongoDB with every edge shown in the trace.

**Architecture:**
- `JoinPathResolver.resolveChain` runs a breadth-first search over the foreign-key graph.
- `QueryIntent` keeps its first `join` and gains `furtherJoins`; `joins()` returns them all.
- `SqlEmitter` writes one `JOIN` per edge. `MongoEmitter` writes a `$lookup`/`$unwind` per edge, with each joined filter's `$match` placed right after its collection arrives, plus an each-subject-once tail.
- The parser binds columns and values across the chain, and `RuleTrace` lists every edge.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, Mockito; React 19, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-02-rule-engine-multi-table-joins-design.md`

**Precondition:** sub-projects 1 and 2 are complete. This plan quotes the parser as they leave it:
- `column(...)`, `note(...)`, `ambiguityMessage(...)`;
- `readValues(...)`, `ValueList`, `consumed`, `conditionSpans`;
- `DeclineException`, `TableStar`, the `distinct` and `anyOf` flags, `JoinKind.ANTI`.

## Global Constraints

- Every question that builds today builds byte-for-byte the same, two-table joins included. The one exception is spec §7: a comparison on a column found only in the joined table now filters it, instead of counting.
- At most four tables in a chain. Two equally short chains decline.
- Anti-joins (sub-project 2) keep needing one direct key.
- Both dialects, always.
- Source comments: a one-line class summary at most.
- Backend tests: `cd backend && ./mvnw -q test -Dtest=<Class>`. Frontend: `cd frontend && npx vitest run <path>`.
- Do not start the app. Commits end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **A two-table join must render exactly as today**, both `JOIN … ON` sides. Pinned in Task 2 (`twoTableUnchanged`) and Task 4 (`twoTableQuestionUnchanged`).
2. **"orders of customers" must not be read as a column projection.** Pinned in Task 4 (`ofTableIsNotProjection`).
3. **"ordered by customers" must not become a sort.** Pinned in Task 4 (`orderedByTableIsNotSort`).
4. **A count with filters on a joined table counts each subject once.** Pinned in Task 2 (`distinctSubjectCount`) and Task 4.
5. **An OR group touching a joined collection is applied after all lookups in MongoDB.** Pinned in Task 2 (`orAfterLookups`).

## Departures from the spec, on purpose

1. `QueryIntent` keeps its `join` component and gains `furtherJoins`, rather than replacing `join` with a list. A constructor overloaded on `JoinSpec` against `List<JoinSpec>` would make every existing `null` argument ambiguous.
2. No table-qualified column reading (spec §5, as revised).

---

### Task 0: Reconcile

- [ ] **Step 1:** Read `QueryIntentParser.java`, `QueryIntent.java`, `SqlEmitter.java`, `MongoEmitter.java`, `JoinPathResolver.java` and `RuleTrace.java` as sub-projects 1 and 2 left them. Confirm the names quoted in this plan; record a `Ruling:` for any difference.
- [ ] **Step 2:** `cd backend && ./mvnw -q test` is green.

---

### Task 1: `JoinPathResolver.resolveChain`

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/JoinPathResolver.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/JoinChainTest.java` (new)

**Interfaces:**
- Produces:
  - `sealed interface ChainResolution permits Chain, NoChain, AmbiguousChain, TooFar`
  - `record Chain(List<KnownSchema.ForeignKey> edges)`
  - `record NoChain(String table)`
  - `record AmbiguousChain(String table, List<List<KnownSchema.ForeignKey>> paths)`
  - `record TooFar(int tables)`
  - `ChainResolution resolveChain(String subject, List<String> others, Dialect dialect)`
  - `static final int MAX_TABLES = 4`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinChainTest {

    private final JoinPathResolver resolver = new JoinPathResolver(new ActiveSchemaProvider());

    private static List<String> tables(JoinPathResolver.ChainResolution resolution) {
        return ((JoinPathResolver.Chain) resolution).edges().stream()
                .map(e -> e.fromTable() + "." + e.fromColumn() + "->" + e.toTable())
                .toList();
    }

    @Test
    @DisplayName("a direct key is a one-edge chain, as today")
    void direct() {
        assertThat(tables(resolver.resolveChain("customers", List.of("orders"), Dialect.MYSQL)))
                .containsExactly("orders.customer_id->customers");
    }

    @Test
    @DisplayName("two and three hops, ordered outward from the subject")
    void hops() {
        assertThat(tables(resolver.resolveChain("employees", List.of("customers"), Dialect.MYSQL)))
                .containsExactly("orders.employee_id->employees", "orders.customer_id->customers");
        assertThat(tables(resolver.resolveChain("customers", List.of("products"), Dialect.MYSQL)))
                .containsExactly("orders.customer_id->customers", "order_items.order_id->orders",
                        "order_items.product_id->products");
    }

    @Test
    @DisplayName("no chain within three edges, and too many tables, are refused")
    void refused() {
        assertThat(resolver.resolveChain("departments", List.of("products"), Dialect.MYSQL))
                .isInstanceOf(JoinPathResolver.NoChain.class);
        assertThat(resolver.resolveChain("orders", List.of("categories", "departments"), Dialect.MYSQL))
                .isInstanceOf(JoinPathResolver.TooFar.class);
    }

    @Test
    @DisplayName("two equally short chains are ambiguous; self-references are ignored")
    void ambiguous() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u", "querysense_uploads", List.of(
                new KnownSchema.Table("orders", List.of("id", "billing_address_id", "shipping_address_id")),
                new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(new KnownSchema.ForeignKey("orders", "billing_address_id", "addresses", "id"),
                        new KnownSchema.ForeignKey("orders", "shipping_address_id", "addresses", "id")),
                Map.of(), Instant.now()));
        JoinPathResolver custom = new JoinPathResolver(schemas);

        assertThat(custom.resolveChain("orders", List.of("addresses"), Dialect.MYSQL))
                .isInstanceOfSatisfying(JoinPathResolver.AmbiguousChain.class, a -> assertThat(a.paths()).hasSize(2));
        assertThat(tables(resolver.resolveChain("employees", List.of("departments"), Dialect.MYSQL)))
                .containsExactly("employees.department_id->departments");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=JoinChainTest`
Expected: compilation failure — `resolveChain` doesn't exist.

- [ ] **Step 3: Implement**

Add to `JoinPathResolver.java`, alongside the existing `resolve`, which stays:

```java
    public static final int MAX_TABLES = 4;
    private static final int MAX_EDGES = MAX_TABLES - 1;

    public sealed interface ChainResolution permits Chain, NoChain, AmbiguousChain, TooFar {}

    public record Chain(List<KnownSchema.ForeignKey> edges) implements ChainResolution {}

    public record NoChain(String table) implements ChainResolution {}

    public record AmbiguousChain(String table, List<List<KnownSchema.ForeignKey>> paths) implements ChainResolution {}

    public record TooFar(int tables) implements ChainResolution {}

    public ChainResolution resolveChain(String subject, List<String> others, Dialect dialect) {
        List<KnownSchema.ForeignKey> graph = activeSchema.foreignKeys(dialect).stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .toList();
        List<KnownSchema.ForeignKey> union = new java.util.ArrayList<>();
        for (String target : others) {
            List<List<KnownSchema.ForeignKey>> shortest = shortestPaths(subject, target, graph);
            if (shortest.isEmpty()) {
                return new NoChain(target);
            }
            if (shortest.size() > 1) {
                return new AmbiguousChain(target, shortest);
            }
            shortest.get(0).stream().filter(e -> !union.contains(e)).forEach(union::add);
        }
        java.util.Set<String> tables = new java.util.LinkedHashSet<>();
        tables.add(subject.toLowerCase(java.util.Locale.ROOT));
        union.forEach(e -> {
            tables.add(e.fromTable().toLowerCase(java.util.Locale.ROOT));
            tables.add(e.toTable().toLowerCase(java.util.Locale.ROOT));
        });
        if (tables.size() > MAX_TABLES) {
            return new TooFar(tables.size());
        }
        return new Chain(outward(subject, union));
    }

    private record Walk(String at, List<KnownSchema.ForeignKey> path) {}

    private static List<List<KnownSchema.ForeignKey>> shortestPaths(String from, String to,
                                                                    List<KnownSchema.ForeignKey> graph) {
        List<Walk> frontier = List.of(new Walk(from, List.of()));
        for (int depth = 1; depth <= MAX_EDGES; depth++) {
            List<Walk> next = new java.util.ArrayList<>();
            List<List<KnownSchema.ForeignKey>> found = new java.util.ArrayList<>();
            for (Walk walk : frontier) {
                for (KnownSchema.ForeignKey edge : graph) {
                    String other = edge.fromTable().equalsIgnoreCase(walk.at()) ? edge.toTable()
                            : edge.toTable().equalsIgnoreCase(walk.at()) ? edge.fromTable() : null;
                    if (other == null || visits(walk, other, from)) {
                        continue;
                    }
                    List<KnownSchema.ForeignKey> path = new java.util.ArrayList<>(walk.path());
                    path.add(edge);
                    if (other.equalsIgnoreCase(to)) {
                        found.add(List.copyOf(path));
                    } else {
                        next.add(new Walk(other, path));
                    }
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
            frontier = next;
        }
        return List.of();
    }

    private static boolean visits(Walk walk, String table, String start) {
        if (table.equalsIgnoreCase(start)) {
            return true;
        }
        return walk.path().stream().anyMatch(e -> e.fromTable().equalsIgnoreCase(table) || e.toTable().equalsIgnoreCase(table));
    }

    private static List<KnownSchema.ForeignKey> outward(String subject, List<KnownSchema.ForeignKey> union) {
        java.util.Set<String> known = new java.util.HashSet<>();
        known.add(subject.toLowerCase(java.util.Locale.ROOT));
        List<KnownSchema.ForeignKey> remaining = new java.util.ArrayList<>(union);
        List<KnownSchema.ForeignKey> ordered = new java.util.ArrayList<>();
        while (!remaining.isEmpty()) {
            KnownSchema.ForeignKey next = remaining.stream()
                    .filter(e -> known.contains(e.fromTable().toLowerCase(java.util.Locale.ROOT))
                            || known.contains(e.toTable().toLowerCase(java.util.Locale.ROOT)))
                    .findFirst()
                    .orElseThrow();
            remaining.remove(next);
            ordered.add(next);
            known.add(next.fromTable().toLowerCase(java.util.Locale.ROOT));
            known.add(next.toTable().toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(ordered);
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='JoinChainTest,JoinPathResolverTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): the shortest chain of declared foreign keys through up to four tables

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Several joins in `QueryIntent`, `RuleTrace` and both emitters

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java`, `SqlEmitter.java`, `MongoEmitter.java`, `backend/src/main/java/com/querysense/backend/dto/RuleTrace.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/ChainEmitterTest.java` (new)

**Interfaces:**
- Produces:
  - `QueryIntent`'s 11th component `List<JoinSpec> furtherJoins`; `List<JoinSpec> joins()`; the existing 8- and 10-argument constructors pass `List.of()`
  - `RuleTrace`'s 7th component `List<JoinEdge> joinEdges`; the 6-argument constructor derives it from `joinEdge`
  - in `MongoEmitter`, filters on joined collections become `$match` after their `$unwind`, and the refusal is removed

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainEmitterTest {

    private final SqlEmitter sql = new SqlEmitter();
    private final MongoEmitter mongo = new MongoEmitter(new ActiveSchemaProvider());

    private static QueryIntent.ColumnRef ref(String table, String column) {
        return new QueryIntent.ColumnRef(table, column);
    }

    private static final QueryIntent.JoinSpec TO_ORDER_ITEMS = new QueryIntent.JoinSpec("order_items",
            ref("products", "id"), ref("order_items", "product_id"));
    private static final QueryIntent.JoinSpec TO_ORDERS = new QueryIntent.JoinSpec("orders",
            ref("order_items", "order_id"), ref("orders", "id"));
    private static final QueryIntent.JoinSpec TO_CUSTOMERS = new QueryIntent.JoinSpec("customers",
            ref("orders", "customer_id"), ref("customers", "id"));

    private static QueryIntent chain(List<QueryIntent.SelectItem> select, List<QueryIntent.Predicate> where,
                                     boolean distinct, boolean anyOf) {
        return new QueryIntent(QueryIntent.Verb.SELECT, "products", TO_ORDER_ITEMS, select, where, List.of(),
                List.of(), null, distinct, anyOf, List.of(TO_ORDERS, TO_CUSTOMERS));
    }

    private static final QueryIntent.Predicate IN_PUNE = new QueryIntent.Predicate(ref("customers", "city"),
            QueryIntent.Op.EQ, List.of(QueryIntent.Literal.text("pune", "Pune")));

    @Test
    @DisplayName("a three-hop chain with a filter on the last table, each product once")
    void chainWithJoinedFilter() {
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(new QueryIntent.TableStar("products"))),
                List.of(IN_PUNE), true, false);

        assertThat(sql.emit(intent)).isEqualTo("SELECT DISTINCT products.* FROM products "
                + "JOIN order_items ON products.id = order_items.product_id "
                + "JOIN orders ON order_items.order_id = orders.id "
                + "JOIN customers ON orders.customer_id = customers.id WHERE customers.city = 'pune';");
        assertThat(mongo.emit(intent)).isEqualTo("db.products.aggregate(["
                + "{\"$lookup\": {\"from\": \"order_items\", \"localField\": \"id\", \"foreignField\": \"product_id\", \"as\": \"order_items\"}}, "
                + "{\"$unwind\": \"$order_items\"}, "
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"order_items.order_id\", \"foreignField\": \"id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$lookup\": {\"from\": \"customers\", \"localField\": \"orders.customer_id\", \"foreignField\": \"id\", \"as\": \"customers\"}}, "
                + "{\"$unwind\": \"$customers\"}, "
                + "{\"$match\": {\"customers.city\": \"Pune\"}}, "
                + "{\"$group\": {\"_id\": \"$_id\", \"doc\": {\"$first\": \"$$ROOT\"}}}, "
                + "{\"$replaceRoot\": {\"newRoot\": \"$doc\"}}, "
                + "{\"$project\": {\"order_items\": 0, \"orders\": 0, \"customers\": 0}}])");
    }

    @Test
    @DisplayName("distinctSubjectCount: a count with joined filters counts each subject once")
    void distinctSubjectCount() {
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(
                new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, ref("products", "id")))), List.of(IN_PUNE), true, false);

        assertThat(sql.emit(intent)).startsWith("SELECT COUNT(DISTINCT products.id) FROM products JOIN order_items");
        assertThat(mongo.emit(intent)).endsWith("{\"$project\": {\"order_items\": 0, \"orders\": 0, \"customers\": 0}}, "
                + "{\"$count\": \"count\"}])");
    }

    @Test
    @DisplayName("orAfterLookups: an OR touching a joined collection is matched after the last lookup")
    void orAfterLookups() {
        QueryIntent.Predicate cheap = new QueryIntent.Predicate(ref("products", "price"), QueryIntent.Op.LT,
                List.of(QueryIntent.Literal.number(new java.math.BigDecimal("10"))));
        QueryIntent intent = chain(List.of(new QueryIntent.SelectItem(new QueryIntent.TableStar("products"))),
                List.of(cheap, IN_PUNE), true, true);

        assertThat(mongo.emit(intent)).contains("{\"$unwind\": \"$customers\"}, "
                + "{\"$match\": {\"$or\": [{\"price\": {\"$lt\": 10}}, {\"customers.city\": \"Pune\"}]}}");
    }

    @Test
    @DisplayName("twoTableUnchanged: one join renders exactly as before")
    void twoTableUnchanged() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new QueryIntent.JoinSpec("orders", ref("customers", "id"), ref("orders", "customer_id")),
                List.of(new QueryIntent.SelectItem(QueryIntent.Star.INSTANCE)), List.of(), List.of(), List.of(), null);

        assertThat(sql.emit(intent)).isEqualTo("SELECT * FROM customers JOIN orders ON customers.id = orders.customer_id;");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ChainEmitterTest`
Expected: compilation failure — there is no 11-argument constructor.

- [ ] **Step 3: Implement**

`QueryIntent.java`:
- Add the component `List<JoinSpec> furtherJoins` after `anyOf`.
- Keep the 8-argument constructor and add a 10-argument one, both delegating with `List.of()`:

```java
    public QueryIntent(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems,
                       List<Predicate> where, List<Predicate> having, List<OrderItem> orderBy, Integer limit,
                       boolean distinct, boolean anyOf) {
        this(verb, fromTable, join, selectItems, where, having, orderBy, limit, distinct, anyOf, List.of());
    }

    public List<JoinSpec> joins() {
        List<JoinSpec> all = new java.util.ArrayList<>();
        if (join != null) {
            all.add(join);
        }
        all.addAll(furtherJoins);
        return List.copyOf(all);
    }
```

`RuleTrace.java`: add the component `List<JoinEdge> joinEdges`, and:

```java
    public RuleTrace(String outcome, String shape, List<Binding> bindings, JoinEdge joinEdge,
                     List<String> checksPassed, String declineReason) {
        this(outcome, shape, bindings, joinEdge, checksPassed, declineReason,
                joinEdge == null ? List.of() : List.of(joinEdge));
    }
```

`SqlEmitter.java`:
1. The select head becomes:

```java
            boolean countOnce = intent.distinct() && intent.selectItems().size() == 1
                    && intent.selectItems().get(0).operand() instanceof QueryIntent.Aggregate agg
                    && agg.fn() == QueryIntent.Fn.COUNT && agg.column() != null;
            sql.append(intent.distinct() && !countOnce ? "SELECT DISTINCT " : "SELECT ")
                    .append(countOnce
                            ? "COUNT(DISTINCT " + column(((QueryIntent.Aggregate) intent.selectItems().get(0).operand()).column()) + ")"
                            : intent.selectItems().stream().map(item -> operand(item.operand())).collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
```

2. Replace the single-join append with a loop over `intent.joins()`. Each join appends `(anti ? " LEFT JOIN " : " JOIN ") + table + " ON " + column(left) + " = " + column(right)`, where `anti` is true only for an `ANTI` join.
3. The `IS NULL` condition still uses `intent.join()`.
4. The `GROUP BY` is skipped when `countOnce`.

`MongoEmitter.java`:
1. In `requireFilterable`, delete the `if (ref.table() != null && !ref.table().equals(intent.fromTable())) { throw … }` block.

2. In `emit`, replace `Document filter = filter(intent, intent.where());` with:

```java
        boolean orTouchesJoins = intent.anyOf() && intent.where().stream().anyMatch(p -> isJoined(intent, p));
        List<QueryIntent.Predicate> basePredicates = orTouchesJoins ? List.of()
                : intent.where().stream().filter(p -> !isJoined(intent, p)).toList();
        Document filter = filter(intent, basePredicates);
```

   and change the fast-path condition's `intent.join() == null` to `intent.joins().isEmpty()`.

3. Add:

```java
    private static boolean isJoined(QueryIntent intent, QueryIntent.Predicate p) {
        QueryIntent.ColumnRef ref = (QueryIntent.ColumnRef) p.lhs();
        return ref.table() != null && !ref.table().equals(intent.fromTable());
    }
```

4. In `pipeline`, replace the single `if (intent.join() != null) { … }` block with:

```java
        boolean orTouchesJoins = intent.anyOf() && intent.where().stream().anyMatch(p -> isJoined(intent, p));
        for (QueryIntent.JoinSpec join : intent.joins()) {
            stages.add(new Document("$lookup", new Document("from", join.table())
                    .append("localField", field(intent, join.left()))
                    .append("foreignField", join.right().column())
                    .append("as", join.table())));
            if (join.kind() == QueryIntent.JoinKind.ANTI) {
                stages.add(new Document("$match", new Document(join.table(), new Document("$size", 0))));
                stages.add(new Document("$project", new Document(join.table(), 0)));
                continue;
            }
            stages.add(new Document("$unwind", "$" + join.table()));
            if (!orTouchesJoins) {
                List<QueryIntent.Predicate> here = intent.where().stream()
                        .filter(p -> join.table().equals(((QueryIntent.ColumnRef) p.lhs()).table()))
                        .toList();
                if (!here.isEmpty()) {
                    stages.add(new Document("$match", matchDocument(here, p -> field(intent, (QueryIntent.ColumnRef) p.lhs()))));
                }
            }
        }
        if (orTouchesJoins) {
            stages.add(new Document("$match", new Document("$or", intent.where().stream()
                    .map(p -> new Document(field(intent, (QueryIntent.ColumnRef) p.lhs()), condition(p)))
                    .toList())));
        }
```

5. In the `intent.distinct()` branch from sub-project 2, add before its `$group`:

```java
            boolean wholeSubject = intent.selectItems().stream().noneMatch(i -> i.operand() instanceof QueryIntent.ColumnRef);
            if (wholeSubject) {
                stages.add(new Document("$group", new Document("_id", "$_id").append("doc", new Document("$first", "$$ROOT"))));
                stages.add(new Document("$replaceRoot", new Document("newRoot", "$doc")));
                Document drop = new Document();
                intent.joins().forEach(j -> drop.append(j.table(), 0));
                stages.add(new Document("$project", drop));
                if (intent.hasAggregate()) {
                    stages.add(new Document("$count", "count"));
                }
                return stages;
            }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='ChainEmitterTest,ShapeEmitterTest,SqlEmitterTest,MongoEmitterTest,QueryIntentParserTest,DialectParityTest,RuleTraceWordingTest'`
Expected: PASS. If a `MongoEmitterTest` case asserted the removed refusal ("would apply that filter before…"), change it to assert the `$match` placed after the `$unwind`, and write a `Ruling:` line. This is the spec's intended change.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): several joins per query, joined filters after their lookup, each subject once

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: The parser builds chains

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/MultiTableJoinTest.java` (new)

**Interfaces:**
- Consumes: `resolveChain` (Task 1); `furtherJoins` and `joinEdges` (Task 2).
- Produces:
  - `List<KnownSchema.Table> chainTables` (subject first, then the rest in edge order)
  - `KnownSchema.Table related`, the last table named
  - the intent built with `furtherJoins`, and the trace with `joinEdges`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
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

/** Chains of up to four tables on the built-in demo schemas. */
class MultiTableJoinTest {

    private ActiveSchemaProvider schemas;
    private QueryIntentParser parser;

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune"));
        return m;
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    RuleBuildResult mysql(String question) {
        return parser.parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("a three-table chain through an unnamed table, with every edge in the trace")
    void threeTables() {
        RuleBuildResult result = mysql("employees and customers");

        assertThat(result.sql()).isEqualTo("SELECT * FROM employees JOIN orders ON employees.id = orders.employee_id "
                + "JOIN customers ON orders.customer_id = customers.id;");
        assertThat(result.trace().joinEdges()).hasSize(2);
    }

    @Test
    @DisplayName("a four-table chain")
    void fourTables() {
        assertThat(mysql("customers and products").sql()).isEqualTo("SELECT * FROM customers "
                + "JOIN orders ON customers.id = orders.customer_id "
                + "JOIN order_items ON orders.id = order_items.order_id "
                + "JOIN products ON order_items.product_id = products.id;");
    }

    @Test
    @DisplayName("too many tables, and no chain within four, decline")
    void refused() {
        assertThat(mysql("orders, categories and departments").trace().declineReason()).contains("four");
        assertThat(mysql("departments and products").trace().declineReason()).contains("departments").contains("products");
    }

    @Test
    @DisplayName("twoTableQuestionUnchanged: today's two-table output, word for word")
    void twoTableQuestionUnchanged() {
        assertThat(mysql("which customers placed more than three orders").sql()).isEqualTo(
                "SELECT customers.id, customers.name FROM customers JOIN orders ON customers.id = orders.customer_id "
                        + "GROUP BY customers.id, customers.name HAVING COUNT(orders.id) > 3;");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=MultiTableJoinTest`
Expected: `threeTables`, `fourTables` and `refused` fail, because three tables decline and two unlinked tables decline with today's message. `twoTableQuestionUnchanged` passes.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Replace the `tables.size() > 2` check with:

```java
        if (tables.size() > JoinPathResolver.MAX_TABLES) {
            return RuleBuildResult.declined(
                    "The question names " + tables.size() + " tables. This engine joins four at most.",
                    bindings);
        }
```

2. `KnownSchema.Table related = tables.size() >= 2 ? tables.get(tables.size() - 1) : null;`

3. Replace the join-resolution block (`if (related != null) { JoinPathResolver.JoinResolution resolution = … }`) with:

```java
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
```

4. Add the decline helpers. The two-table messages are today's, word for word:

```java
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
                            .flatMap(e -> java.util.stream.Stream.of(e.fromTable(), e.toTable()))
                            .filter(t -> !t.equalsIgnoreCase(subject.name()) && !t.equalsIgnoreCase(ambiguous.table()))
                            .distinct().collect(java.util.stream.Collectors.joining(" and ")))
                    .collect(java.util.stream.Collectors.joining(", or "));
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
```

5. `boolean joined = joinSpec != null;` is unchanged.

6. Build the intent with `List.copyOf(furtherJoins)` as its 11th argument.

7. Build the trace with the 7-argument `RuleTrace(RuleTrace.BUILT, shapeOf(intent), List.copyOf(bindings), joinEdge, checksFor(joined, joinEdges.size()), null, List.copyOf(joinEdges))`.

8. `checksFor(boolean joined, int edges)`:
   - one edge adds today's check, word for word;
   - more than one adds `"Exactly one shortest chain of declared foreign keys relates these tables"`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='MultiTableJoinTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,QuestionShapesTest,VocabularyTest,RuleTraceWordingTest,MongoRuleEngineTest,JoinPathResolverTest'`
Expected: PASS.
- If `DeclineBoundaryTest`'s "orders, customers and products together" or "customers and suppliers" now builds, those questions moved from decline to build. Decide each:
  - if the spec makes it buildable, move it out of the must-decline list and write a `Ruling:` line;
  - in the demo there is no `customers`–`suppliers` chain within four tables, so that one keeps declining.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): the parser joins up to four tables along the shortest declared chain

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Columns and values across the chain

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `MultiTableJoinTest.java`

**Interfaces:**
- Produces:
  - `Optional<QueryIntent.ColumnRef> chainColumn(String phrase, List<KnownSchema.Table> chainTables, boolean joined, List<Interpretation> interpretations)`
  - `ValueList` gains `String table`
  - the each-subject-once selection

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    @DisplayName("a value found on a joined table filters it, and each subject row comes once")
    void valueOnJoinedTable() {
        assertThat(mysql("orders of customers in mumbai").sql()).isEqualTo(
                "SELECT DISTINCT orders.* FROM orders JOIN customers ON orders.customer_id = customers.id "
                        + "WHERE customers.city = 'mumbai';");
        assertThat(mysql("how many orders of customers in mumbai").sql()).isEqualTo(
                "SELECT COUNT(DISTINCT orders.id) FROM orders JOIN customers ON orders.customer_id = customers.id "
                        + "WHERE customers.city = 'mumbai';");
    }

    @Test
    @DisplayName("orderedByTableIsNotSort: products ordered by customers in pune is a four-table chain")
    void orderedByTableIsNotSort() {
        assertThat(mysql("products ordered by customers in pune").sql()).isEqualTo(
                "SELECT DISTINCT products.* FROM products JOIN order_items ON products.id = order_items.product_id "
                        + "JOIN orders ON order_items.order_id = orders.id "
                        + "JOIN customers ON orders.customer_id = customers.id WHERE customers.city = 'pune';");
    }

    @Test
    @DisplayName("ofTableIsNotProjection: 'orders of customers' is not a request for an 'orders' column")
    void ofTableIsNotProjection() {
        assertThat(mysql("orders of customers in mumbai").built()).isTrue();
    }

    @Test
    @DisplayName("a sort column found only on a joined table sorts by it")
    void sortOnJoinedTable() {
        assertThat(mysql("orders and customers sorted by city").sql()).isEqualTo(
                "SELECT * FROM orders JOIN customers ON orders.customer_id = customers.id ORDER BY customers.city ASC;");
    }

    @Test
    @DisplayName("a column fitting two chain tables declines, naming both")
    void ambiguousColumn() {
        assertThat(mysql("orders and categories sorted by name").trace().declineReason())
                .contains("products.name").contains("categories.name");
    }

    @Test
    @DisplayName("spec §7: a comparison on a joined table's column filters it instead of counting")
    void comparisonOnJoinedColumn() {
        assertThat(mysql("orders with payments amount over 100").sql()).isEqualTo(
                "SELECT DISTINCT orders.* FROM orders JOIN payments ON orders.id = payments.order_id "
                        + "WHERE payments.amount > 100;");
    }

    @Test
    @DisplayName("MongoDB: the joined filter follows its lookup, each order once")
    void mongoJoinedFilter() {
        assertThat(parser.parse("orders of customers in mumbai", Dialect.MONGODB).sql()).isEqualTo(
                "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": \"customer_id\", "
                        + "\"foreignField\": \"_id\", \"as\": \"customers\"}}, {\"$unwind\": \"$customers\"}, "
                        + "{\"$match\": {\"customers.city\": \"Mumbai\"}}, "
                        + "{\"$group\": {\"_id\": \"$_id\", \"doc\": {\"$first\": \"$$ROOT\"}}}, "
                        + "{\"$replaceRoot\": {\"newRoot\": \"$doc\"}}, {\"$project\": {\"customers\": 0}}])");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=MultiTableJoinTest`
Expected: the new tests fail. The value isn't searched on joined tables, "of customers" is read as a projection, "ordered by customers" as a sort, and the comparison counts.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Add the helper:

```java
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
```

   When the subject itself has the column, the result is `qualify(column, subject, joined)`, exactly as today.

2. **Ambiguity across the chain:**
   - A phrase the subject has binds the subject, as today, even if another chain table also has it.
   - Only a phrase the subject lacks, found on two other chain tables, declines.
   - In `ambiguousColumn`, `orders` has no `name`, while `products` and `categories` on the chain `orders → order_items → products → categories` both do.

3. Use `chainColumn` at the order-by, comparison and `with` sites. These sites previously called `column(subject, …)` and then `qualify(…, subject, joined)`; they now take the returned `ColumnRef` directly:
   - **Order by:** `Optional<QueryIntent.ColumnRef> orderRef = chainColumn(order.group(1), chainTables, joined, interpretations);`, and the `OrderItem` uses `orderRef.get()`.
   - **Comparison:** `Optional<QueryIntent.ColumnRef> lhsRef = chainColumn(comparison.group(1), chainTables, joined, interpretations);`. If present, the `WHERE` predicate uses `lhsRef.get()` and the binding's `lhs` is `SqlEmitter.column(lhsRef.get())`. Otherwise the `HAVING`-count path runs as today.
   - **`with`:** `Optional<QueryIntent.ColumnRef> withRef = chainColumn(with.group(1), chainTables, joined, interpretations);`. The stored-value lookup uses the table of `withRef.get()` (`binder.tableNamed(withRef.get().table() == null ? subject.name() : withRef.get().table(), dialect).orElse(subject)`).
   - **Aggregate, grouping and projection** stay on the subject, as today.

4. **Values across the chain:**
   - `ValueList` gains `String table` as its first component.
   - In `readValues`, after `ValueRun run = valueRun(subject, array, i, metadata, onlyColumn);`, if `run == null && joined && onlyColumn == null`, try each other chain table in order, `valueRun(table, array, i, metadata, null)`, and keep the single table that matches. Two matches throw `new DeclineException("\"" + array[i] + "\" is stored in both " + … + ", so this engine can't tell which one the question means.")`.
   - Record the matched table name in `ValueList.table`.
   - `readValues` takes `chainTables` and `joined` in place of `related`; the `isColumnOfEither` probe becomes `chainTables.stream().anyMatch(t -> binder.bindColumn(t, first).isPresent())`.
   - At every `readValues` call site, qualify with the found table: `new QueryIntent.ColumnRef(joined ? list.get().table() : null, list.get().column())`.

5. In `extractStrongProjectionPhrase` (both the `ONLY_JUST` and `OF_PHRASE` branches), return `Optional.empty()` when the phrase, after filler words are removed, binds a table: `!binder.tableMatches(phrase, dialect).hits().isEmpty()`. The method gains a `Dialect dialect` parameter, and its one caller passes it.

6. In the order-by block, skip the match when its target binds a table: `if (order.find() && binder.tableMatches(order.group(1), dialect).hits().isEmpty()) { … }`.

7. **Each subject once:** after all conditions are read, and before the select chain:

```java
        boolean joinedFilter = joined && !anti
                && where.stream().anyMatch(p -> p.lhs() instanceof QueryIntent.ColumnRef r
                        && r.table() != null && !r.table().equals(subject.name()));
```

   In the select chain:
   - the bare-count branch becomes `selectItems.add(new QueryIntent.SelectItem(joinedFilter ? new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, new QueryIntent.ColumnRef(subject.name(), subject.columns().get(0))) : new QueryIntent.Aggregate(QueryIntent.Fn.COUNT, null)));`;
   - the star branch adds `TableStar(subject.name())` when `joinedFilter`;
   - pass `distinct || joinedFilter` as the intent's `distinct` argument.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='MultiTableJoinTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,QuestionShapesTest,VocabularyTest,MongoRuleEngineTest,RuleTraceWordingTest'`
Expected: PASS. If an existing test asserted the §7 `HAVING`-count reading for a joined-table column, update it and write a `Ruling:` line.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): columns and values bind across the chain; joined filters return each subject once

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Every edge in the trace panel; parity; docs

**Files:**
- Modify: `frontend/src/components/analysis/RuleTracePanel.jsx`, `frontend/src/components/analysis/RuleTracePanel.test.jsx`, `backend/src/test/java/com/querysense/backend/querybuilder/DialectParityTest.java`, `RULES.md`, `PROJECT.md`

- [ ] **Step 1: Write the failing frontend test**

Add to `RuleTracePanel.test.jsx`:

```jsx
  it('lists every edge of a multi-table chain', () => {
    const edge = (fromTable, fromColumn, toTable) => ({ fromTable, fromColumn, toTable, toColumn: 'id', origin: 'declared foreign key' });
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        trace={{
          outcome: 'BUILT', shape: 'SELECT + JOIN', bindings: [], checksPassed: [], declineReason: null,
          joinEdge: edge('orders', 'customer_id', 'customers'),
          joinEdges: [edge('orders', 'customer_id', 'customers'), edge('order_items', 'order_id', 'orders')],
        }}
      />
    );

    expect(screen.getByText('order_items.order_id')).toBeInTheDocument();
    expect(screen.getByText('orders.customer_id')).toBeInTheDocument();
  });
```

Run: `cd frontend && npx vitest run src/components/analysis/RuleTracePanel.test.jsx`
Expected: FAIL. Only `joinEdge` is shown.

- [ ] **Step 2: Implement**

In `RuleTracePanel.jsx`, replace the `{trace.joinEdge && ( <p className="rule-trace__join"> … </p> )}` block with:

```jsx
          {(trace.joinEdges?.length ? trace.joinEdges : trace.joinEdge ? [trace.joinEdge] : []).map((edge, i) => (
            <p className="rule-trace__join" key={i}>
              <span className="rule-trace__join-edge">
                <code>{edge.fromTable}.{edge.fromColumn}</code>
                <span aria-hidden="true"> → </span>
                <code>{edge.toTable}.{edge.toColumn}</code>
              </span>
              <span className="rule-trace__origin">
                read from your schema&rsquo;s {edge.origin}, not inferred
              </span>
            </p>
          ))}
```

Run: `cd frontend && npx vitest run && npm run lint`
Expected: PASS. The existing single-edge test still passes.

- [ ] **Step 3: Parity**

Add to `DialectParityTest`'s `@ValueSource`:

```java
            "employees and customers",
            "customers and products",
            "orders of customers in mumbai",
            "how many orders of customers in mumbai",
            "orders and customers sorted by city",
```

Run: `cd backend && ./mvnw -q test -Dtest=DialectParityTest`
Expected: PASS.

- [ ] **Step 4: Full checks**

Run: `cd backend && ./mvnw test`, then `cd frontend && npm test && npm run lint && npm run build`.
Expected: all green.

- [ ] **Step 5: Docs**

- **`RULES.md`:**
  - rewrite E4 (up to four tables) and E6 (the shortest chain; ambiguity; through-tables);
  - add the §7 note to E13;
  - add the declines "no chain within four tables", "too many tables in the chain", "two equally short chains" and "a column or value on two chain tables";
  - update the counts.
- **`PROJECT.md`:** add `### Session of <date> — rule engine joins across up to four tables (sub-project 3 of 4)`, with the task/commit table, the departures, the §7 change, the test counts and "not run in the app". In the §5 roadmap, mark sub-project 3 built and sub-project 4 next.

- [ ] **Step 6: Commit**

```bash
git add backend/src frontend/src RULES.md PROJECT.md
git commit -m "docs: record rule engine joins across up to four tables; the trace shows every edge

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
