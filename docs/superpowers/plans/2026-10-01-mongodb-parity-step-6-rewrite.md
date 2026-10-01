# MongoDB Parity, Step 6 — Rewrite and Compare — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/query/compare` works for MongoDB the way it works for MySQL. A `MongoQueryRewriter` applies RW1–RW4, each of which returns identical documents or declines. Both versions are planned with the real `explain`. The documents-examined delta and the plan change are reported with a reason per rule. For both dialects, an accepted rewrite is written into `QueryHistory.optimizedQuery`.

**Architecture:** A `Rewriter` per dialect (`QueryRewriter` for MySQL, unchanged; `MongoQueryRewriter` new), picked through `DialectRegistry`. `MongoQueryRewriter` parses with `MongoShellParser`, rewrites the `MongoQuery`, and writes it back as mongosh text with a new `MongoShellWriter`. `QueryComparisonServiceImpl` routes by dialect, words its summary per dialect, and hands an accepted rewrite to a new `OptimizedQueryRecorder`.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, MongoDB Java sync driver 5.5.2, JUnit 5, AssertJ, Mockito. The local server is MongoDB 8.3.11 Community on `localhost:27017`.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`: §3.1 (`Rewriter`), §7, §10, §12 step 6.

## Global Constraints

- Every rewrite rule returns identical documents or declines. A rewrite that returns different documents is a different question, not a faster answer.
- QuerySense recommends and never executes. Compare runs `explain` only (step 5's analyzer). The rewritten query is shown and stored in history, never run.
- MySQL behaviour and every MySQL-facing string stay byte-identical. `QueryRewriter` and the MySQL compare summary don't change.
- `POST /api/query/compare`'s response shape is unchanged. The request gains one optional field, `userInput`.
- Source comments: a one-line class summary only (`/** ... */` above the class). No other comments.
- Backend tests: `cd backend && ./mvnw test`. For named classes use `-Dtest=A,B` (a comma).
- `@Tag("mongo")` tests skip when `localhost:27017` is closed, use a throwaway database, and drop it.
- Do not start the app. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **RW4 must not move a `$match` past an `$unwind` of the field it filters.** `$unwind items` then `$match items.quantity` filters elements; moved first, it would filter whole orders and return different documents. Pinned in Task 4 (`matchOnUnwoundFieldStays`).
2. **RW4 must not move a `$match` on a `$lookup`'s `as` field**, which doesn't exist before the lookup. Pinned in Task 4 (`matchOnJoinedFieldStays`).
3. **RW2 must not collapse an `$or` that spans two fields, or holds an operator, a regex or an embedded document.** Pinned in Task 2 (`orAcrossFieldsDeclines`, `orWithOperatorsDeclines`).
4. **RW3 must not drop a sort unless an exact match on a unique field caps the result at one document.** A non-unique field, or a range on a unique field, keeps the sort. Pinned in Task 3 (`sortKeptWithoutUniqueEquality`).
5. **A rewrite MongoDB rejects is withheld and never written into history.** Pinned in Task 5 (`rejectedRewriteIsWithheldAndNotRecorded`).

## Departures from the spec, on purpose

- **RW1 applies to `find` only.** MongoDB's aggregation already works out which fields a pipeline needs and fetches only those, so a projection prepended to a pipeline saves nothing.
- **RW1's "identical documents" rests on the schema being complete.** QuerySense's databases are provisioned and seeded from the declared schema, so their documents hold only declared fields. `RULES.md` states this.
- **RW3 applies to `find` only, and reads unique indexes from the active schema snapshot** (the declared indexes, as step 5's advisor does), not from a live `listIndexes()`.
- **History matching falls back to the compared query text.** The compare request gains an optional `userInput`. When it is absent (the current frontend sends none until step 8), the most recent history row with the same generated query, database type and schema label is updated. The match is made in Java over the last 50 rows, because `generatedQuery` is a `TEXT` column that H2 can't compare in SQL.
- **MongoDB comparisons are measured, not estimated.** `explain` at `executionStats` runs the plan, so `estimate` is `false` for MongoDB and the summary says "measured".
- **`MongoShellWriter` writes mongosh's own syntax** (`ObjectId("…")`, `ISODate("…")`, `NumberLong("…")`), so types survive the round trip. `MongoEmitter` keeps its relaxed JSON.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../mongo/MongoShellWriter.java` (new) | `MongoQuery` → mongosh text that `MongoShellParser` reads back to the same query |
| `backend/.../optimization/Rewriter.java` (new) | Per-dialect rewrite interface |
| `backend/.../optimization/QueryRewriter.java` (modify) | Implements `Rewriter` as MySQL; behaviour unchanged |
| `backend/.../optimization/MongoQueryRewriter.java` (new) | RW1–RW4 for MongoDB |
| `backend/.../service/impl/OptimizedQueryRecorder.java` (new) | Writes an accepted rewrite into the matching history row |
| `backend/.../repository/QueryHistoryRepository.java` (modify) | Two lookups for the recorder |
| `backend/.../dto/CompareRequest.java` (modify) | Optional `userInput` |
| `backend/.../service/QueryComparisonService.java`, `service/impl/QueryComparisonServiceImpl.java`, `controller/QueryComparisonController.java` (modify) | Compare per dialect, record the rewrite |

`backend/...` = `backend/src/main/java/com/querysense/backend`. Tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: `MongoShellWriter`

**Files:**
- Create: `backend/.../mongo/MongoShellWriter.java`
- Test: `backend/src/test/java/com/querysense/backend/mongo/MongoShellWriterTest.java`

**Interfaces:**
- Consumes: `MongoQuery`, `MongoShellParser` (existing).
- Produces: `public final class MongoShellWriter { public static String write(MongoQuery query); }`. The output looks like:
  - `db.<c>.find(<filter>[, <projection>])[.sort(<sort>)][.limit(<n>)]`
  - `db.<c>.aggregate([<stage>, …])`
  - `db.<c>.countDocuments(<filter>)`
  - `db.<c>.deleteMany(<filter>)`

  The JSON is `JsonMode.SHELL`. For every query `q` that `MongoShellParser` produces, `parse(write(q))` equals `q`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.mongo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoShellWriterTest {

    private final MongoShellParser parser = new MongoShellParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "db.customers.find({\"city\": \"Mumbai\"})",
            "db.customers.find({}, {\"name\": 1, \"_id\": 0}).sort({\"name\": -1}).limit(5)",
            "db.customers.find()",
            "db.orders.find({\"customer_id\": ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\"), \"order_date\": {\"$gte\": ISODate(\"2026-01-01T00:00:00Z\")}})",
            "db.orders.find({\"n\": NumberLong(\"5\"), \"p\": NumberDecimal(\"9.99\"), \"r\": /^mum/i})",
            "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])",
            "db.orders.aggregate([])",
            "db.orders.countDocuments({\"status\": \"PAID\"})",
            "db.orders.deleteMany({\"status\": \"CANCELLED\"})"
    })
    @DisplayName("what the parser reads, the writer writes back to the same query")
    void roundTrips(String mongosh) {
        MongoQuery parsed = parser.parse(mongosh);

        assertThat(parser.parse(MongoShellWriter.write(parsed))).isEqualTo(parsed);
    }

    @Test
    @DisplayName("plain queries read like the rule engine's own output")
    void plainShape() {
        assertThat(MongoShellWriter.write(parser.parse("db.customers.find({\"city\": \"Mumbai\"})")))
                .isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
        assertThat(MongoShellWriter.write(parser.parse(
                "db.products.find({}).sort({\"price\": -1}).limit(5)")))
                .isEqualTo("db.products.find({}).sort({\"price\": -1}).limit(5)");
        assertThat(MongoShellWriter.write(parser.parse("db.orders.countDocuments({})")))
                .isEqualTo("db.orders.countDocuments({})");
    }

    @Test
    @DisplayName("an ObjectId is written in mongosh syntax")
    void objectIdInShellSyntax() {
        assertThat(MongoShellWriter.write(parser.parse(
                "db.orders.find({\"_id\": ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\")})")))
                .contains("ObjectId(\"5f1d7f1b2c3a4b5c6d7e8f90\")");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoShellWriterTest`
Expected: compilation failure (`MongoShellWriter` does not exist).

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.mongo;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.List;
import java.util.stream.Collectors;

/** Writes a MongoQuery back as mongosh text that MongoShellParser reads to the same query. */
public final class MongoShellWriter {

    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.SHELL).build();

    private MongoShellWriter() {
    }

    public static String write(MongoQuery query) {
        String target = "db." + query.collection();
        Document filter = query.filter() == null ? new Document() : query.filter();
        return switch (query.operation()) {
            case FIND -> {
                StringBuilder out = new StringBuilder(target).append(".find(").append(json(filter));
                if (query.projection() != null) {
                    out.append(", ").append(json(query.projection()));
                }
                out.append(")");
                if (query.sort() != null) {
                    out.append(".sort(").append(json(query.sort())).append(")");
                }
                if (query.limit() != null) {
                    out.append(".limit(").append(query.limit()).append(")");
                }
                yield out.toString();
            }
            case AGGREGATE -> target + ".aggregate("
                    + (query.pipeline() == null ? List.<Document>of() : query.pipeline()).stream()
                            .map(MongoShellWriter::json)
                            .collect(Collectors.joining(", ", "[", "]"))
                    + ")";
            case COUNT -> target + ".countDocuments(" + json(filter) + ")";
            case DELETE -> target + ".deleteMany(" + json(filter) + ")";
        };
    }

    private static String json(Document document) {
        return document.toJson(JSON);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoShellWriterTest`
Expected: PASS, 11 tests (9 parameterised and 2 plain).

If a parameterised case fails because the parser reads one of the literals differently than expected (for example, `/^mum/i`), print `parser.parse(...)` for that literal and fix the fixture string so it is valid mongosh. The assertion `parse(write(q)) == q` stays as it is.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): write a parsed MongoDB query back as mongosh text, types intact

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `Rewriter`, and `MongoQueryRewriter` with RW2

**Files:**
- Create: `backend/.../optimization/Rewriter.java`, `backend/.../optimization/MongoQueryRewriter.java`
- Modify: `backend/.../optimization/QueryRewriter.java` (implements `Rewriter`)
- Test: `backend/src/test/java/com/querysense/backend/optimization/MongoQueryRewriterTest.java`

**Interfaces:**
- Consumes: `MongoShellWriter` (Task 1), `MongoShellParser`, `ActiveSchemaProvider`, `QueryRewrite`, `RewriteChange` (existing).
- Produces:
  - `public interface Rewriter extends DialectAware { QueryRewrite rewrite(String query); }`
  - `QueryRewriter implements Rewriter`. `dialect()` returns `MYSQL`, and `rewrite(String sql)` returns `rewrite(sql, "MySQL")`. The 2-argument method is unchanged.
  - `@Component public class MongoQueryRewriter implements Rewriter` with `MongoQueryRewriter(ActiveSchemaProvider, MongoShellParser)`. Its `dialect()` returns `MONGODB`.
    - It returns `QueryRewrite.unchanged(text)` for null or unparseable text, and for a collection not in the active Mongo schema.
    - Rules apply in the order RW1, RW2, RW3, RW4. This task adds RW2; Tasks 3 and 4 add the rest.
    - A trailing `;` on the input is kept on the output.
  - The `QueryRewrite.sql()` component carries the mongosh text for MongoDB.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoQueryRewriterTest {

    private final ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
    private final MongoShellParser parser = new MongoShellParser();
    private final MongoQueryRewriter rewriter = new MongoQueryRewriter(activeSchema, parser);

    private QueryRewrite rewrite(String mongosh) {
        return rewriter.rewrite(mongosh);
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(rewriter.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("RW2: an $or of equalities on one field becomes $in, and the other conditions stay")
    void orBecomesIn() {
        QueryRewrite result = rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}], "
                + "\"name\": \"Asha\"}, {\"name\": 1})");

        assertThat(result.changed()).isTrue();
        assertThat(result.changes()).extracting(RewriteChange::ruleId).containsExactly("RW2");
        assertThat(parser.parse(result.sql()).filter()).isEqualTo(parser.parse(
                "db.customers.find({\"city\": {\"$in\": [\"Pune\", \"Delhi\"]}, \"name\": \"Asha\"})").filter());
        assertThat(result.changes().get(0).explanation()).contains("'city'").contains("identical");
    }

    @Test
    @DisplayName("RW2 accepts {$eq: v} branches and rewrites every $match in a pipeline")
    void eqBranchesAndPipelines() {
        QueryRewrite result = rewrite("db.orders.aggregate([{\"$match\": {\"$or\": [{\"status\": {\"$eq\": \"NEW\"}}, "
                + "{\"status\": \"PAID\"}]}}, {\"$group\": {\"_id\": \"$status\"}}])");

        assertThat(result.sql()).contains("\"$in\"").doesNotContain("\"$or\"");
        assertThat(parser.parse(result.sql()).pipeline()).hasSize(2);
    }

    @Test
    @DisplayName("RW2 declines an $or across two fields")
    void orAcrossFieldsDeclines() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"name\": \"Asha\"}]}, {\"name\": 1})")
                .changed()).isFalse();
    }

    @Test
    @DisplayName("RW2 declines operators, regexes, embedded documents and a single branch")
    void orWithOperatorsDeclines() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"$ne\": \"Pune\"}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"$regex\": \"pu\"}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"a\": 1}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Delhi\"}]}, {\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}], "
                + "\"city\": \"Agra\"}, {\"name\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("unparseable text, an unknown collection and nothing to do all come back unchanged")
    void unchangedCases() {
        assertThat(rewrite("db.customers.find({city: )").sql()).isEqualTo("db.customers.find({city: )");
        assertThat(rewrite("db.clients.find({\"$or\": [{\"a\": 1}, {\"a\": 2}]})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"city\": \"Pune\"}, {\"name\": 1})").changed()).isFalse();
        assertThat(rewriter.rewrite(null).changed()).isFalse();
    }

    @Test
    @DisplayName("a trailing semicolon survives the rewrite")
    void semicolonKept() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]}, {\"name\": 1});").sql())
                .endsWith(";");
    }

    @Test
    @DisplayName("the MySQL rewriter is a Rewriter for MySQL and behaves as before")
    void mysqlRewriter() {
        QueryRewriter mysql = new QueryRewriter(activeSchema);

        assertThat(mysql.dialect()).isEqualTo(Dialect.MYSQL);
        String sql = "SELECT * FROM customers WHERE city = 'a' OR city = 'b';";
        assertThat(mysql.rewrite(sql)).isEqualTo(mysql.rewrite(sql, "MySQL"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`Rewriter.java`:

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dialect.DialectAware;

/** Rewrites one dialect's query into an equivalent, cheaper one, or returns it unchanged. */
public interface Rewriter extends DialectAware {
    QueryRewrite rewrite(String query);
}
```

`QueryRewriter`: change the declaration to `public class QueryRewriter implements Rewriter`, and add:

```java
    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public QueryRewrite rewrite(String sql) {
        return rewrite(sql, "MySQL");
    }
```

`MongoQueryRewriter.java`:

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.mongo.MongoShellWriter;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Rewrites a MongoDB query into one MongoDB can serve more cheaply that returns the same documents; a rule that cannot guarantee that declines. */
@Component
public class MongoQueryRewriter implements Rewriter {

    private final ActiveSchemaProvider activeSchema;
    private final MongoShellParser parser;

    public MongoQueryRewriter(ActiveSchemaProvider activeSchema, MongoShellParser parser) {
        this.activeSchema = activeSchema;
        this.parser = parser;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public QueryRewrite rewrite(String text) {
        if (text == null) {
            return QueryRewrite.unchanged(null);
        }
        MongoQuery query;
        try {
            query = parser.parse(text);
        } catch (MongoShellParseException e) {
            return QueryRewrite.unchanged(text);
        }
        Optional<KnownSchema.Table> table = activeSchema.tables(Dialect.MONGODB).stream()
                .filter(t -> t.name().equals(query.collection()))
                .findFirst();
        if (table.isEmpty()) {
            return QueryRewrite.unchanged(text);
        }

        List<RewriteChange> changes = new ArrayList<>();
        MongoQuery rewritten = collapseOrChains(query, changes);
        if (changes.isEmpty()) {
            return QueryRewrite.unchanged(text);
        }
        String out = MongoShellWriter.write(rewritten);
        return new QueryRewrite(text.strip().endsWith(";") ? out + ";" : out, List.copyOf(changes));
    }

    private MongoQuery collapseOrChains(MongoQuery query, List<RewriteChange> changes) {
        if (query.operation() == MongoQuery.Operation.AGGREGATE) {
            if (query.pipeline() == null) {
                return query;
            }
            List<Document> stages = new ArrayList<>();
            boolean changed = false;
            for (Document stage : query.pipeline()) {
                if (stage.get("$match") instanceof Document match) {
                    Document collapsed = collapse(match, changes);
                    if (collapsed != null) {
                        stages.add(new Document("$match", collapsed));
                        changed = true;
                        continue;
                    }
                }
                stages.add(stage);
            }
            return changed ? withPipeline(query, stages) : query;
        }
        Document collapsed = collapse(query.filter(), changes);
        return collapsed == null ? query : withFilter(query, collapsed);
    }

    private static Document collapse(Document filter, List<RewriteChange> changes) {
        if (filter == null || !(filter.get("$or") instanceof List<?> clauses) || clauses.size() < 2) {
            return null;
        }
        String field = null;
        List<Object> values = new ArrayList<>();
        for (Object clause : clauses) {
            if (!(clause instanceof Document branch) || branch.size() != 1) {
                return null;
            }
            Map.Entry<String, Object> only = branch.entrySet().iterator().next();
            if (only.getKey().startsWith("$") || (field != null && !field.equals(only.getKey()))) {
                return null;
            }
            field = only.getKey();
            Object value = only.getValue();
            if (value instanceof Document operator) {
                if (operator.size() != 1 || !operator.containsKey("$eq") || !plain(operator.get("$eq"))) {
                    return null;
                }
                value = operator.get("$eq");
            } else if (!plain(value)) {
                return null;
            }
            values.add(value);
        }
        if (filter.containsKey(field)) {
            return null;
        }
        Document out = new Document();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if ("$or".equals(entry.getKey())) {
                out.append(field, new Document("$in", values));
            } else {
                out.append(entry.getKey(), entry.getValue());
            }
        }
        changes.add(new RewriteChange("RW2",
                "Collapsed " + values.size() + " $or'd equality tests on '" + field + "' into a single $in. "
                        + "MongoDB plans $in as one index scan over several values, where an $or plans each "
                        + "branch separately. The documents returned are identical."));
        return out;
    }

    static boolean plain(Object value) {
        return !(value instanceof Document) && !(value instanceof List)
                && !(value instanceof Pattern) && !(value instanceof BsonRegularExpression);
    }

    static MongoQuery withFilter(MongoQuery q, Document filter) {
        return new MongoQuery(q.collection(), q.operation(), filter, q.projection(), q.sort(), q.limit(), q.pipeline());
    }

    static MongoQuery withPipeline(MongoQuery q, List<Document> pipeline) {
        return new MongoQuery(q.collection(), q.operation(), q.filter(), q.projection(), q.sort(), q.limit(),
                List.copyOf(pipeline));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest,QueryRewriterTest`
Expected: PASS. `QueryRewriterTest` is unchanged and still green.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rewrite): a Rewriter per dialect; MongoDB's RW2 collapses an \$or of equalities into \$in

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: RW1 and RW3 for MongoDB

**Files:**
- Modify: `backend/.../optimization/MongoQueryRewriter.java`
- Test: add to `MongoQueryRewriterTest.java`

**Interfaces:**
- Consumes: `MongoQueryFields.referencedPaths` (step 5), `SchemaSnapshot.indexes()`, `IndexSpec`.
- Produces:
  - **RW1:** a `find` with no projection gets one naming the collection's declared top-level fields, in declaration order, without `_id` (which MongoDB returns by default). It declines when the filter or sort references a field whose top-level name isn't declared.
  - **RW3:** a `find` with a non-empty sort, whose filter has a plain equality (or `$eq`) on `_id` or on the sole key of a unique index of that collection in the active snapshot, loses its sort.

- [ ] **Step 1: Write the failing tests**

Add to `MongoQueryRewriterTest`. The import `java.util.List` and the `org.bson.Document` import are needed.

```java
    @Test
    @DisplayName("RW1: a find without a projection names the declared top-level fields, _id left to the default")
    void projectionNamesFields() {
        QueryRewrite result = rewrite("db.orders.find({\"status\": \"PAID\"})");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).containsExactly("RW1");
        assertThat(parser.parse(result.sql()).projection()).isEqualTo(new org.bson.Document("customer_id", 1)
                .append("employee_id", 1).append("order_date", 1).append("status", 1).append("items", 1));
        assertThat(result.changes().get(0).explanation()).contains("5 fields").contains("'orders'");
    }

    @Test
    @DisplayName("RW1 declines when a projection exists, when the query is not a find, or when it names an undeclared field")
    void projectionDeclines() {
        assertThat(rewrite("db.orders.find({\"status\": \"PAID\"}, {\"status\": 1})").changed()).isFalse();
        assertThat(rewrite("db.orders.countDocuments({\"status\": \"PAID\"})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({\"coupon\": \"X\"})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({}).sort({\"coupon\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("RW3: an exact match on a unique field caps the result at one document, so the sort goes")
    void sortDroppedOnUniqueEquality() {
        QueryRewrite byEmail = rewrite("db.customers.find({\"email\": \"a@b.c\"}, {\"name\": 1}).sort({\"name\": 1})");
        QueryRewrite byId = rewrite("db.customers.find({\"_id\": {\"$eq\": 7}}, {\"name\": 1}).sort({\"name\": -1}).limit(3)");

        assertThat(byEmail.changes()).extracting(RewriteChange::ruleId).containsExactly("RW3");
        assertThat(parser.parse(byEmail.sql()).sort()).isNull();
        assertThat(byEmail.changes().get(0).explanation()).contains("'email'").contains("unique");
        assertThat(parser.parse(byId.sql()).sort()).isNull();
        assertThat(parser.parse(byId.sql()).limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("RW3 keeps the sort for a non-unique field, a range on a unique field, or a unique index elsewhere")
    void sortKeptWithoutUniqueEquality() {
        assertThat(rewrite("db.customers.find({\"city\": \"Pune\"}, {\"name\": 1}).sort({\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"email\": {\"$gte\": \"a\"}}, {\"name\": 1}).sort({\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({\"email\": \"a@b.c\"}, {\"status\": 1}).sort({\"status\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("RW1 and RW3 fire together, reported in rule order")
    void rulesInOrder() {
        assertThat(rewrite("db.customers.find({\"email\": \"a@b.c\"}).sort({\"name\": 1})").changes())
                .extracting(RewriteChange::ruleId).containsExactly("RW1", "RW3");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest`
Expected: the five new tests fail, because neither rule exists yet. The Task 2 tests keep passing.

- [ ] **Step 3: Implement**

In `MongoQueryRewriter`:
- Add the imports `com.querysense.backend.analysis.MongoQueryFields`, `com.querysense.backend.schema.IndexSpec`, `java.util.HashSet`, `java.util.LinkedHashSet` and `java.util.Set`.
- In `rewrite`, replace the line `MongoQuery rewritten = collapseOrChains(query, changes);` with:

```java
        MongoQuery rewritten = nameTheFields(query, table.get(), changes);
        rewritten = collapseOrChains(rewritten, changes);
        rewritten = dropSortOnUniqueMatch(rewritten, changes);
```

Add:

```java
    private MongoQuery nameTheFields(MongoQuery query, KnownSchema.Table table, List<RewriteChange> changes) {
        if (query.operation() != MongoQuery.Operation.FIND || query.projection() != null) {
            return query;
        }
        List<String> roots = topLevelFields(table);
        if (roots.isEmpty()) {
            return query;
        }
        Set<String> declared = new HashSet<>(roots);
        declared.add("_id");
        for (String path : MongoQueryFields.referencedPaths(query)) {
            if (!declared.contains(path.split("\\.")[0])) {
                return query;
            }
        }
        Document projection = new Document();
        roots.forEach(root -> projection.append(root, 1));
        changes.add(new RewriteChange("RW1",
                "Named the " + roots.size() + " fields of '" + table.name() + "' in a projection instead of "
                        + "returning whole documents, so an index holding those fields could answer the query "
                        + "without fetching each document. The collection's schema declares no other fields, so "
                        + "the documents returned are identical."));
        return new MongoQuery(query.collection(), query.operation(), query.filter(), projection, query.sort(),
                query.limit(), query.pipeline());
    }

    private MongoQuery dropSortOnUniqueMatch(MongoQuery query, List<RewriteChange> changes) {
        if (query.operation() != MongoQuery.Operation.FIND || query.sort() == null || query.sort().isEmpty()
                || query.filter() == null) {
            return query;
        }
        Set<String> unique = uniqueFields(query.collection());
        for (Map.Entry<String, Object> entry : query.filter().entrySet()) {
            if (unique.contains(entry.getKey()) && equality(entry.getValue())) {
                changes.add(new RewriteChange("RW3",
                        "Dropped .sort() because the filter matches '" + entry.getKey() + "' exactly and a "
                                + "unique index on it allows at most one document. Sorting one document cannot "
                                + "change the result; it only cost an in-memory sort."));
                return new MongoQuery(query.collection(), query.operation(), query.filter(), query.projection(),
                        null, query.limit(), query.pipeline());
            }
        }
        return query;
    }

    private Set<String> uniqueFields(String collection) {
        Set<String> unique = new HashSet<>(Set.of("_id"));
        for (IndexSpec index : activeSchema.active(Dialect.MONGODB).indexes()) {
            if (index.unique() && index.keys().size() == 1 && index.table().equals(collection)) {
                unique.add(index.keys().get(0).column());
            }
        }
        return unique;
    }

    private static boolean equality(Object value) {
        if (value instanceof Document operator) {
            return operator.size() == 1 && operator.containsKey("$eq") && plain(operator.get("$eq"));
        }
        return plain(value);
    }

    static List<String> topLevelFields(KnownSchema.Table table) {
        Set<String> roots = new LinkedHashSet<>();
        for (String column : table.columns()) {
            String root = column.split("\\.")[0];
            if (!"_id".equals(root)) {
                roots.add(root);
            }
        }
        return List.copyOf(roots);
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest`
Expected: PASS. Every Task 2 test has a projection, so RW1 never fires in them.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rewrite): MongoDB RW1 names the declared fields, RW3 drops a sort a unique match makes pointless

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: RW4 — a `$match` ahead of `$lookup` and `$unwind`

**Files:**
- Modify: `backend/.../optimization/MongoQueryRewriter.java`
- Test: add to `MongoQueryRewriterTest.java`

**Interfaces:**
- Produces: **RW4.** In a pipeline, a `$match` directly preceded by a run of `$lookup` and/or `$unwind` stages moves to before that run, but only when:
  - every field it references has a top-level name that is a declared field of the base collection, or `_id`;
  - none of those names is a `$lookup`'s `as`, an `$unwind`'s path, or an `$unwind`'s `includeArrayIndex` within the run;
  - it uses no top-level operator other than `$and`, `$or` and `$nor`.

  It applies to every such `$match`.

- [ ] **Step 1: Write the failing tests**

```java
    private static final String LOOKUP = "{\"$lookup\": {\"from\": \"customers\", \"localField\": \"customer_id\", "
            + "\"foreignField\": \"_id\", \"as\": \"customer\"}}";

    private java.util.List<String> stageNames(String mongosh) {
        return parser.parse(mongosh).pipeline().stream().map(s -> s.keySet().iterator().next()).toList();
    }

    @Test
    @DisplayName("RW4: a $match on base fields moves ahead of the $lookup before it")
    void matchMovesAheadOfLookup() {
        QueryRewrite result = rewrite("db.orders.aggregate([" + LOOKUP + ", {\"$match\": {\"status\": \"PAID\"}}])");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).containsExactly("RW4");
        assertThat(stageNames(result.sql())).containsExactly("$match", "$lookup");
        assertThat(result.changes().get(0).explanation()).contains("status").contains("'orders'");
    }

    @Test
    @DisplayName("RW4 moves past a whole run of $lookup and $unwind, keeping their order")
    void matchMovesPastARun() {
        QueryRewrite result = rewrite("db.orders.aggregate([" + LOOKUP + ", {\"$unwind\": \"$customer\"}, "
                + "{\"$match\": {\"$and\": [{\"status\": \"PAID\"}, {\"employee_id\": {\"$exists\": true}}]}}])");

        assertThat(stageNames(result.sql())).containsExactly("$match", "$lookup", "$unwind");
    }

    @Test
    @DisplayName("RW4 keeps a $match on the field an $unwind just unwound")
    void matchOnUnwoundFieldStays() {
        assertThat(rewrite("db.orders.aggregate([{\"$unwind\": \"$items\"}, "
                + "{\"$match\": {\"items.quantity\": {\"$gt\": 2}}}])").changed()).isFalse();
    }

    @Test
    @DisplayName("RW4 keeps a $match on the field a $lookup adds")
    void matchOnJoinedFieldStays() {
        assertThat(rewrite("db.orders.aggregate([" + LOOKUP + ", {\"$match\": {\"customer.city\": \"Pune\"}}])")
                .changed()).isFalse();
    }

    @Test
    @DisplayName("RW4 keeps a $match with $expr, one already first, and one after a reshaping stage")
    void otherMatchesStay() {
        assertThat(rewrite("db.orders.aggregate([" + LOOKUP + ", {\"$match\": {\"$expr\": {\"$gt\": [\"$a\", 1]}}}])")
                .changed()).isFalse();
        assertThat(rewrite("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, " + LOOKUP + "])").changed()).isFalse();
        assertThat(rewrite("db.orders.aggregate([{\"$group\": {\"_id\": \"$status\"}}, {\"$match\": {\"_id\": \"PAID\"}}])")
                .changed()).isFalse();
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest`
Expected: `matchMovesAheadOfLookup` and `matchMovesPastARun` fail. The three "stays" tests already pass, because nothing moves yet. Each of them must still pass after Step 3.

- [ ] **Step 3: Implement**

In `rewrite`, after `rewritten = dropSortOnUniqueMatch(rewritten, changes);`, add `rewritten = matchBeforeJoin(rewritten, table.get(), changes);`. Then add:

```java
    private MongoQuery matchBeforeJoin(MongoQuery query, KnownSchema.Table table, List<RewriteChange> changes) {
        if (query.operation() != MongoQuery.Operation.AGGREGATE || query.pipeline() == null) {
            return query;
        }
        Set<String> base = new HashSet<>(topLevelFields(table));
        base.add("_id");
        List<Document> stages = new ArrayList<>(query.pipeline());
        boolean moved = false;
        for (int i = 1; i < stages.size(); i++) {
            if (!(stages.get(i).get("$match") instanceof Document match)) {
                continue;
            }
            int start = i;
            Set<String> introduced = new HashSet<>();
            while (start > 0 && joinStage(stages.get(start - 1))) {
                introduced.addAll(introducedFields(stages.get(start - 1)));
                start--;
            }
            if (start == i) {
                continue;
            }
            Set<String> roots = new LinkedHashSet<>();
            if (!matchRoots(match, roots) || roots.isEmpty() || !base.containsAll(roots)
                    || roots.stream().anyMatch(introduced::contains)) {
                continue;
            }
            stages.add(start, stages.remove(i));
            changes.add(new RewriteChange("RW4",
                    "Moved the $match on " + String.join(", ", roots) + " ahead of the " + (i - start)
                            + " $lookup/$unwind stage(s) before it. It reads only fields of '" + table.name()
                            + "' that those stages do not change, so it keeps the same documents — but it "
                            + "filters them before the join, so fewer documents are joined."));
            moved = true;
        }
        return moved ? withPipeline(query, stages) : query;
    }

    private static boolean joinStage(Document stage) {
        return stage.containsKey("$lookup") || stage.containsKey("$unwind");
    }

    private static Set<String> introducedFields(Document stage) {
        Set<String> fields = new HashSet<>();
        if (stage.get("$lookup") instanceof Document lookup && lookup.get("as") instanceof String as) {
            fields.add(as.split("\\.")[0]);
        }
        Object unwind = stage.get("$unwind");
        Object path = unwind instanceof Document d ? d.get("path") : unwind;
        if (path instanceof String p) {
            fields.add(p.replaceFirst("^\\$", "").split("\\.")[0]);
        }
        if (unwind instanceof Document d && d.get("includeArrayIndex") instanceof String index) {
            fields.add(index.split("\\.")[0]);
        }
        return fields;
    }

    private static boolean matchRoots(Document match, Set<String> roots) {
        for (Map.Entry<String, Object> entry : match.entrySet()) {
            String key = entry.getKey();
            if (Set.of("$and", "$or", "$nor").contains(key) && entry.getValue() instanceof List<?> clauses) {
                for (Object clause : clauses) {
                    if (!(clause instanceof Document d) || !matchRoots(d, roots)) {
                        return false;
                    }
                }
            } else if (key.startsWith("$")) {
                return false;
            } else {
                roots.add(key.split("\\.")[0]);
            }
        }
        return true;
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryRewriterTest`
Expected: PASS, every test.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rewrite): MongoDB RW4 moves a \$match on base fields ahead of \$lookup and \$unwind

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Compare per dialect, and the rewrite recorded in history

**Files:**
- Create: `backend/.../service/impl/OptimizedQueryRecorder.java`
- Modify: `backend/.../repository/QueryHistoryRepository.java`
- Modify: `backend/.../dto/CompareRequest.java`
- Modify: `backend/.../service/QueryComparisonService.java`, `backend/.../service/impl/QueryComparisonServiceImpl.java`, `backend/.../controller/QueryComparisonController.java`
- Modify test: `backend/src/test/java/com/querysense/backend/service/impl/QueryComparisonServiceImplTest.java` (setup, and `mongoIsDeclined` replaced)
- Test: `backend/src/test/java/com/querysense/backend/service/impl/OptimizedQueryRecorderTest.java`

**Interfaces:**
- Consumes: `Rewriter` (Task 2), `MongoQueryRewriter` (Tasks 2–4), `QueryAnalysisService` (step 5), `ActiveSchemaProvider`, `QueryHistory`.
- Produces:
  - `QueryHistoryRepository`:
    - `Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(String userInput, String databaseType, String schemaLabel)`
    - `List<QueryHistory> findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(String databaseType, String schemaLabel)`
  - `@Component public class OptimizedQueryRecorder`, with `OptimizedQueryRecorder(QueryHistoryRepository, ActiveSchemaProvider)` and `public boolean record(Dialect dialect, String userInput, String originalQuery, String optimizedQuery)`.
    - With a non-blank `userInput`, it updates the newest row with that input (case ignored), the dialect's label as database type, and the active schema's label.
    - Otherwise it updates the newest of the last 50 such rows whose `generatedQuery` equals `originalQuery`, compared after trimming.
    - It returns whether a row was updated.
  - `CompareRequest` gains an optional `String userInput` with its getter and setter.
  - `QueryComparisonService` gains `CompareResponse compare(String query, String databaseType, String userInput)`. The 2-argument version means `userInput = null`.
  - `QueryComparisonServiceImpl(List<Rewriter>, QueryAnalysisService, OptimizedQueryRecorder)`.
    - It resolves `Dialect.from(databaseType)`, so an unknown type is a 400.
    - MySQL keeps every summary string.
    - MongoDB sets `estimate = false`, reports documents examined, names the plan change, and words a rejected rewrite as "MongoDB's explain rejected it".
    - An accepted rewrite (rewritten, and the after-analysis did not fail) is recorded, for both dialects.

- [ ] **Step 1: Write the failing tests**

`OptimizedQueryRecorderTest.java`:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OptimizedQueryRecorderTest {

    private static final String MONGO_LABEL = KnownMongoSchema.builtIn().label();

    private QueryHistoryRepository repository;
    private OptimizedQueryRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(QueryHistoryRepository.class);
        recorder = new OptimizedQueryRecorder(repository, new ActiveSchemaProvider());
    }

    private static QueryHistory row(String generated) {
        QueryHistory history = new QueryHistory();
        history.setGeneratedQuery(generated);
        return history;
    }

    @Test
    @DisplayName("with the user's question, the newest matching row for this database and schema gets the rewrite")
    void byUserInput() {
        QueryHistory row = row("db.customers.find({})");
        when(repository.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                "customers in pune", "MongoDB", MONGO_LABEL)).thenReturn(Optional.of(row));

        boolean recorded = recorder.record(Dialect.MONGODB, "  customers in pune ", "db.customers.find({})", "REWRITTEN");

        assertThat(recorded).isTrue();
        assertThat(row.getOptimizedQuery()).isEqualTo("REWRITTEN");
        verify(repository).save(row);
    }

    @Test
    @DisplayName("without the question, the newest row whose generated query matches gets the rewrite")
    void byGeneratedQuery() {
        QueryHistory older = row("db.customers.find({})");
        QueryHistory other = row("db.orders.find({})");
        when(repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc("MongoDB", MONGO_LABEL))
                .thenReturn(List.of(other, older));

        assertThat(recorder.record(Dialect.MONGODB, " ", " db.customers.find({}) ", "REWRITTEN")).isTrue();

        assertThat(older.getOptimizedQuery()).isEqualTo("REWRITTEN");
        assertThat(other.getOptimizedQuery()).isNull();
        verify(repository).save(older);
    }

    @Test
    @DisplayName("no matching row means nothing is written")
    void noRow() {
        when(repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(any(), any()))
                .thenReturn(List.of());

        assertThat(recorder.record(Dialect.MYSQL, null, "SELECT 1", "SELECT 2")).isFalse();
        verify(repository, never()).save(any());
    }
}
```

In `QueryComparisonServiceImplTest`:
- Add the imports `com.querysense.backend.mongo.MongoShellParser`, `com.querysense.backend.optimization.MongoQueryRewriter` and `java.util.List`, plus `static org.assertj.core.api.Assertions.assertThatThrownBy` and `static org.mockito.ArgumentMatchers.any`.
- Add a field `private OptimizedQueryRecorder recorder;`.
- In `setUp`, replace the service line with:

```java
        recorder = mock(OptimizedQueryRecorder.class);
        service = new QueryComparisonServiceImpl(List.of(new QueryRewriter(activeSchema),
                new MongoQueryRewriter(new ActiveSchemaProvider(), new MongoShellParser())), analysis, recorder);
```

- Replace `mongoIsDeclined` with:

```java
    private QueryAnalysisResult mongoResult(long docs, String accessType, boolean failed) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setEstimatedRows(docs);
        result.setAccessType(accessType);
        result.setExplainFailed(failed);
        return result;
    }

    private static final String MONGO_OR = "db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]}, {\"name\": 1})";

    @Test
    @DisplayName("MongoDB is compared on real plans: documents examined, the plan change, measured not estimated")
    void mongoIsCompared() {
        when(analysis.analyze(eq(MONGO_OR), eq("MongoDB"))).thenReturn(mongoResult(2000, "COLLSCAN", false));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q.contains("$in")), eq("MongoDB")))
                .thenReturn(mongoResult(180, "IXSCAN", false));

        CompareResponse response = service.compare(MONGO_OR, "MongoDB", "customers in pune or delhi");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).contains("\"$in\"");
        assertThat(response.getChanges()).extracting(c -> c.ruleId()).containsExactly("RW2");
        assertThat(response.getEstimatedRowsDelta()).isEqualTo(-1820L);
        assertThat(response.getQueryCostDelta()).isNull();
        assertThat(response.isEstimate()).isFalse();
        assertThat(response.getSummary())
                .contains("MongoDB examined 1820 fewer documents")
                .contains("from COLLSCAN to IXSCAN")
                .doesNotContain("EXPLAIN estimates");
        verify(recorder).record(Dialect.MONGODB, "customers in pune or delhi", MONGO_OR, response.getOptimizedQuery());
    }

    @Test
    @DisplayName("a MongoDB rewrite that explain rejects is withheld, and not written into history")
    void rejectedRewriteIsWithheldAndNotRecorded() {
        when(analysis.analyze(eq(MONGO_OR), eq("MongoDB"))).thenReturn(mongoResult(2000, "COLLSCAN", false));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q.contains("$in")), eq("MongoDB")))
                .thenReturn(mongoResult(0, null, true));

        CompareResponse response = service.compare(MONGO_OR, "MongoDB");

        assertThat(response.isRewritten()).isFalse();
        assertThat(response.getOptimizedQuery()).isEqualTo(MONGO_OR);
        assertThat(response.getSummary()).contains("MongoDB's explain rejected it");
        verify(recorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an accepted MySQL rewrite is recorded too, and an unchanged query is not")
    void mysqlRecording() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(303L, 62.0));

        CompareResponse response = service.compare(original, "MySQL", "mumbai or pune customers");
        service.compare("SELECT COUNT(*) FROM customers;", "MySQL", "how many customers");

        verify(recorder).record(Dialect.MYSQL, "mumbai or pune customers", original, response.getOptimizedQuery());
        verify(recorder, never()).record(any(), eq("how many customers"), any(), any());
    }

    @Test
    @DisplayName("an unknown database type is refused, which the API returns as a 400")
    void unknownTypeRefused() {
        assertThatThrownBy(() -> service.compare("SELECT 1", "Oracle"))
                .isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=QueryComparisonServiceImplTest,OptimizedQueryRecorderTest`
Expected: compilation failure (`OptimizedQueryRecorder`, the 3-argument constructor and `compare(…, userInput)` don't exist).

- [ ] **Step 3: Implement**

`QueryHistoryRepository`: add `import java.util.List;` and:

```java
    Optional<QueryHistory> findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
            String userInput, String databaseType, String schemaLabel);

    List<QueryHistory> findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
            String databaseType, String schemaLabel);
```

`OptimizedQueryRecorder.java`:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Writes an accepted rewrite into the history row of the query it improves. */
@Component
public class OptimizedQueryRecorder {

    private final QueryHistoryRepository repository;
    private final ActiveSchemaProvider activeSchema;

    public OptimizedQueryRecorder(QueryHistoryRepository repository, ActiveSchemaProvider activeSchema) {
        this.repository = repository;
        this.activeSchema = activeSchema;
    }

    public boolean record(Dialect dialect, String userInput, String originalQuery, String optimizedQuery) {
        String schemaLabel = activeSchema.active(dialect).label();
        Optional<QueryHistory> row;
        if (userInput != null && !userInput.isBlank()) {
            row = repository.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                    userInput.trim(), dialect.label(), schemaLabel);
        } else {
            String original = originalQuery == null ? "" : originalQuery.trim();
            row = repository.findTop50ByDatabaseTypeIgnoreCaseAndSchemaLabelOrderByCreatedAtDescIdDesc(
                            dialect.label(), schemaLabel).stream()
                    .filter(h -> h.getGeneratedQuery() != null && h.getGeneratedQuery().trim().equals(original))
                    .findFirst();
        }
        row.ifPresent(history -> {
            history.setOptimizedQuery(optimizedQuery);
            repository.save(history);
        });
        return row.isPresent();
    }
}
```

`CompareRequest`: add `private String userInput;` with `public String getUserInput() { return userInput; }` and `public void setUserInput(String userInput) { this.userInput = userInput; }`.

`QueryComparisonService`:

```java
public interface QueryComparisonService {
    default CompareResponse compare(String query, String databaseType) {
        return compare(query, databaseType, null);
    }

    CompareResponse compare(String query, String databaseType, String userInput);
}
```

`QueryComparisonController.compare`: `return queryComparisonService.compare(request.getQuery(), request.getDatabaseType(), request.getUserInput());`

`QueryComparisonServiceImpl`, replacing the class body above `deltaOf`. The two `deltaOf` methods and MySQL's `summarise` stay exactly as they are; MySQL's summary is still `summarise(response)`.

```java
    private final DialectRegistry<Rewriter> rewriters;
    private final QueryAnalysisService queryAnalysisService;
    private final OptimizedQueryRecorder recorder;

    public QueryComparisonServiceImpl(List<Rewriter> rewriters,
                                      QueryAnalysisService queryAnalysisService,
                                      OptimizedQueryRecorder recorder) {
        this.rewriters = new DialectRegistry<>(rewriters, "query rewriter");
        this.queryAnalysisService = queryAnalysisService;
        this.recorder = recorder;
    }

    @Override
    public CompareResponse compare(String query, String databaseType, String userInput) {
        Dialect dialect = Dialect.from(databaseType);
        boolean mongo = dialect == Dialect.MONGODB;
        CompareResponse response = new CompareResponse();
        response.setOriginalQuery(query);
        response.setOptimizedQuery(query);
        response.setEstimate(!mongo);

        QueryRewrite rewrite = rewriters.get(dialect).rewrite(query);

        QueryAnalysisResult before = queryAnalysisService.analyze(query, databaseType);
        response.setOriginalAnalysis(before);

        if (!rewrite.changed()) {
            response.setSummary("No rewrite rule applied to this query, "
                    + "so there is nothing to compare it against.");
            return response;
        }

        QueryAnalysisResult after = queryAnalysisService.analyze(rewrite.sql(), databaseType);

        if (after.isExplainFailed()) {
            response.setSummary(mongo
                    ? "A rewrite was produced but could not be checked — MongoDB's explain rejected it, so it is "
                            + "withheld rather than offered as an improvement. This usually means the active "
                            + "schema and the live collections disagree about a field."
                    : "A rewrite was produced but could not be checked — EXPLAIN rejected it, "
                            + "so it is withheld rather than offered as an improvement. "
                            + "This usually means the active schema and the live tables disagree about a column.");
            return response;
        }

        response.setOptimizedQuery(rewrite.sql());
        response.setRewritten(true);
        response.setChanges(rewrite.changes());
        response.setOptimizedAnalysis(after);
        response.setEstimatedRowsDelta(deltaOf(before.getEstimatedRows(), after.getEstimatedRows()));
        response.setQueryCostDelta(deltaOf(before.getQueryCost(), after.getQueryCost()));
        response.setSummary(mongo ? summariseMongo(response, before, after) : summarise(response));

        recorder.record(dialect, userInput, query, rewrite.sql());
        return response;
    }

    private String summariseMongo(CompareResponse response, QueryAnalysisResult before, QueryAnalysisResult after) {
        int ruleCount = response.getChanges().size();
        String opening = ruleCount + (ruleCount == 1 ? " rewrite rule applied. " : " rewrite rules applied. ");
        String planChange = before.getAccessType() != null && after.getAccessType() != null
                && !before.getAccessType().equals(after.getAccessType())
                ? " The plan changed from " + before.getAccessType() + " to " + after.getAccessType() + "."
                : "";

        Long delta = response.getEstimatedRowsDelta();
        if (delta == null) {
            return opening + "MongoDB reported no documents-examined figure for one of the two plans, "
                    + "so the two cannot be compared on documents." + planChange;
        }
        if (delta < 0) {
            return opening + "MongoDB examined " + Math.abs(delta) + " fewer documents under the rewrite, "
                    + "measured by running both plans with explain." + planChange;
        }
        if (delta > 0) {
            return opening + "MongoDB examined " + delta + " more documents under the rewrite — the rewrite did "
                    + "not help this query, measured by running both plans with explain." + planChange;
        }
        return opening + "MongoDB examined the same number of documents either way, so the rewrite changes "
                + "the shape of the query without changing what it reads." + planChange;
    }
```

Remove the old `"MySQL".equalsIgnoreCase` early return and its "Comparison is SQL-only for now" message. Imports: `com.querysense.backend.dialect.Dialect`, `com.querysense.backend.dialect.DialectRegistry`, `com.querysense.backend.optimization.Rewriter` and `java.util.List`; drop the unused `QueryRewriter` import. The class comment line stays.

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green.
- The existing MySQL compare tests pass unchanged.
- `BackendApplicationTests` starts the context, which validates the two derived repository queries and finds one `Rewriter` per dialect.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(compare): compare MongoDB on real plans, and record an accepted rewrite in history for both dialects

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Integration test against the real `mongod`

**Files:**
- Test: `backend/src/test/java/com/querysense/backend/optimization/MongoCompareIntegrationTest.java`

**Interfaces:**
- Consumes: everything above, plus step 5's analyzer chain.
- Produces: an `@Tag("mongo")` test in `querysense_it_compare_<nanoTime>`, dropped afterwards. It skips when the port is closed.

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.optimization;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.querysense.backend.analysis.MongoExplainAnalyzer;
import com.querysense.backend.analysis.MongoExplainExecutor;
import com.querysense.backend.analysis.MongoExplainParser;
import com.querysense.backend.analysis.MongoPreflightValidator;
import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.analysis.VerdictService;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
import com.querysense.backend.service.impl.OptimizedQueryRecorder;
import com.querysense.backend.service.impl.QueryAnalysisServiceImpl;
import com.querysense.backend.service.impl.QueryComparisonServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

@Tag("mongo")
class MongoCompareIntegrationTest {

    private static final int ROWS = 2000;
    private static final String DATABASE = "querysense_it_compare_" + System.nanoTime();

    private static MongoClient client;
    private static QueryComparisonServiceImpl compare;

    private record NoMySql() implements PlanAnalyzer {
        @Override
        public Dialect dialect() {
            return Dialect.MYSQL;
        }

        @Override
        public QueryAnalysisResult analyze(String query) {
            throw new UnsupportedOperationException();
        }
    }

    private static boolean mongoListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    static void provisionAndSeed() throws IOException {
        assumeTrue(mongoListening(), "no mongod on localhost:27017");
        client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000");
        ParsedSchema demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
        new MongoProvisioner(client, DATABASE).provision(demo);
        new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        activeSchema.publish(new SchemaSnapshot("it", "it", DATABASE, demo.toKnownTables(), demo.allForeignKeys(),
                Map.of(), Instant.now(), Dialect.MONGODB, demo.indexes(), demo.fieldTypes()));
        MongoShellParser parser = new MongoShellParser();
        MongoExplainAnalyzer analyzer = new MongoExplainAnalyzer(parser, new MongoPreflightValidator(activeSchema),
                new MongoExplainExecutor(client, activeSchema), new MongoExplainParser(), new VerdictService(1000L));
        compare = new QueryComparisonServiceImpl(
                List.of(new QueryRewriter(activeSchema), new MongoQueryRewriter(activeSchema, parser)),
                new QueryAnalysisServiceImpl(List.of(analyzer, new NoMySql())),
                mock(OptimizedQueryRecorder.class));
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("RW3 on a real server: the sort after a unique match disappears from the plan")
    void sortDropped() {
        CompareResponse response = compare.compare(
                "db.customers.find({\"email\": \"nobody@example.com\"}, {\"name\": 1}).sort({\"name\": 1})", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getChanges()).extracting(RewriteChange::ruleId).containsExactly("RW3");
        assertThat(response.getOriginalAnalysis().isUsesFilesort()).isTrue();
        assertThat(response.getOptimizedAnalysis().isUsesFilesort()).isFalse();
        assertThat(response.isEstimate()).isFalse();
    }

    @Test
    @DisplayName("RW4 on a real server: the $match moves ahead of the $lookup, and MongoDB examines no more documents")
    void matchAheadOfLookup() {
        CompareResponse response = compare.compare("db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"customer\"}}, "
                + "{\"$match\": {\"status\": \"PAID\"}}])", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).startsWith("db.orders.aggregate([{\"$match\"");
        assertThat(response.getEstimatedRowsDelta()).isNotNull().isLessThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("RW2 on a real server: $or becomes $in and both plans are read")
    void orToIn() {
        CompareResponse response = compare.compare("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, "
                + "{\"city\": \"Delhi\"}]}, {\"name\": 1})", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).contains("\"$in\"");
        assertThat(response.getEstimatedRowsDelta()).isNotNull();
        assertThat(response.getSummary()).startsWith("1 rewrite rule applied. MongoDB examined");
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=MongoCompareIntegrationTest`
Expected with `mongod` up: PASS, 3 tests, not skipped.

A failure is a finding about production code. One possible cause: `sortDropped`'s original plan shows no `SORT` on 8.3 because the planner answered the sort another way. If so, print the original analysis's `planTree` and record a ruling. Only then may that assertion be replaced, and only by `getChanges()` containing RW3. Never weaken the RW4 or RW2 assertions.

- [ ] **Step 3: Confirm nothing was left behind, then run the suite and commit**

List the databases with the scratchpad `ListDbs.java` program, as in step 5's Task 11. Expected: no `querysense_it_compare_` entry.

Run: `cd backend && ./mvnw test`
Expected: all green.

```bash
git add backend/src/test
git commit -m "test(mongo): compare MongoDB rewrites on real explain plans, skipped when no mongod is running

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Record it in `RULES.md` and `PROJECT.md`

**Files:**
- Modify: `RULES.md`, `PROJECT.md`

- [ ] **Step 1: `RULES.md` section 10**
  - **Opening paragraph:** replace "All three are MySQL-only…" with: MySQL has RW1–RW3, MongoDB has RW1–RW4 (2026-10-01), and anything unparseable, or a collection or table not in the active schema, comes back unchanged.
  - **RW1–RW3:** add a "MongoDB" bullet to each, with the Task 2–3 wording and one example each. RW1 says the identical-documents guarantee rests on the declared schema, from which QuerySense's databases are provisioned and seeded.
  - **RW4:** add it, with what it moves, the three conditions under which it declines, and the `$unwind items` / `items.quantity` example of why.
  - **Compare:** say compare runs on both dialects, MongoDB's figures are measured (`estimate: false`), and an accepted rewrite is written into `QueryHistory.optimizedQuery` (matched by question, else by generated query).
  - **"How many rules" table:** the Query rewrite row becomes 4 rules, RW1–RW4 (RW4 MongoDB only), with `optimization/MongoQueryRewriter` added. Update both totals by one: 64 IDs, 60 firing.

- [ ] **Step 2: `PROJECT.md`**
  - **New session entry:** add `### Session of <execution date> — MongoDB parity, step 6 (rewrite and compare)` after the step 5 session. It contains:
    - the plan path;
    - a table of Tasks 1–6 with commits;
    - the six departures above, one line each;
    - what Task 6 verified against the real `mongod`, and what is unit-tested only.
  - **"Next:" line:** in the steps 1–3 entry, it becomes: steps 1–6 are complete, and step 7 (LLM grounding for MongoDB, and the Mongo path of `GeneratedQueryValidator`) is next.
  - **Live checklist:** add these steps to the new entry.
    1. `curl -X POST localhost:8080/api/query/compare -H "Content-Type: application/json" -d '{"query": "db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]}, {\"name\": 1})", "databaseType": "MongoDB"}'` returns `rewritten: true`, an `$in`, `estimate: false`, and a summary naming documents examined.
    2. Generate `customers in pune` with MongoDB in the Workbench. Then compare its generated query with `userInput` set to the same question. The History screen's row for it now has an optimized query.
    3. A MySQL compare returns the same summary text as before, with `estimate: true`.

- [ ] **Step 3: Commit**

```bash
git add RULES.md PROJECT.md
git commit -m "docs: record MongoDB parity step 6 — MongoDB rewrite rules, compare, optimizedQuery in history

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
