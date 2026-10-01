# MongoDB Parity, Step 5 — Real Plan Analysis — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A MongoDB query is analysed the way a MySQL query is: it is checked against the active Mongo schema, planned by the real `mongod` with `explain` at `executionStats`, mapped onto the same `QueryAnalysisResult`, judged by the same verdict rules in MongoDB's words, and advised on by Mongo variants of the domain, resource and schema-advisor rules, with the fix as a ready `createIndex` in `suggestedChange`.

**Architecture:** A `PlanAnalyzer` per dialect, picked through `DialectRegistry`. `MySqlPlanAnalyzer` is today's SQL path moved unchanged. `MongoExplainAnalyzer` parses with `MongoShellParser`, runs `MongoPreflightValidator`, reads real indexes and `explain` output through `MongoExplainExecutor`, and maps that output with `MongoExplainParser`. The verdict, rules and advisor stay single code paths and switch wording or keys per dialect. `IndexAdvisor` splits the schema advisor into a MySQL half (moved unchanged) and a new Mongo half. Explain fixtures are captured from the local `mongod` and checked in.

**Tech Stack:** Java 21, Spring Boot 3.5, MongoDB Java sync driver 5.5.2 (the `MongoClient` bean is already autoconfigured), JUnit 5, AssertJ, Mockito. The local server is MongoDB 8.3.11 Community on `localhost:27017`.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`: §3.1 (`PlanAnalyzer`, `IndexAdvisor`), §6.1–6.5, §10 (fixtures and integration), §11 (errors), §12 step 5.

## Global Constraints

- QuerySense recommends and never executes. The only command sent for a user's query is `explain`, plus `listIndexes`. A pipeline containing `$out` or `$merge` is refused before anything reaches MongoDB. Advisor output is shown and never applied.
- Explain verbosity is pinned to `executionStats`, for `find`, `aggregate`, `count` and `delete`, against `activeSchema.catalog(Dialect.MONGODB)`.
- If MongoDB can't be reached, the response is a 503 naming MongoDB (`MongoUnavailableException`, already mapped). It is never a 500.
- MySQL behaviour and every MySQL-facing string stay byte-identical. Existing tests change only where this plan lists the change.
- Source comments: a one-line class summary only (`/** ... */` above the class). No other comments.
- Backend tests: `cd backend && ./mvnw test`. For named classes use `-Dtest=A,B` (a comma, never `+`).
- `@Tag("mongo")` tests run when `localhost:27017` is open and skip when it is closed. They use throwaway databases and drop them afterwards. They never touch `querysense` or `querysense_uploads`.
- Do not start the app. The user runs the live checks.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **`explain` on `deleteMany` must not delete.** Pinned in Task 1 (`deleteIsWrappedInExplain`: the command's only top-level keys are `explain` and `verbosity`) and in Task 11 (`deleteExplainDeletesNothing` counts the documents afterwards).
2. **A pipeline with `$out` or `$merge` must never reach MongoDB.** Pinned in Task 3 (`writeStagesAreRefused`) and Task 5 (`writePipelineNeverReachesMongo`, which verifies the executor is never called).
3. **A collection declared in the schema but missing from `mongod`** (the built-in demo before its first seeding) must be an `UNKNOWN` verdict naming the collection, not "Looks efficient". Pinned in Task 2 (`missingCollectionFails`) and Task 5 (`missingCollectionIsUnknown`).
4. **`mongod` stops mid-analysis.** The result is a 503 naming MongoDB, from both `listIndexes` and `explain`. Pinned in Task 4 (`unreachableExplainIs503`, `unreachableIndexesIs503`) and Task 5 (`unreachableMongoPropagates`).
5. **COUNT, DELETE and `$group` return fewer documents than they read without discarding any.** They must not be graded "discards N%". Pinned in Task 2 (`countReportsMatchesNotZero`, `pushedDownGroupClaimsNoDiscard`) and Task 7 (`mongoScanWithoutDiscardFigureIsNotCritical`).

## Departures from the spec, on purpose

- **The fixture "script" is a JUnit class**, `MongoExplainFixtureCapture`, run on demand with `-Dquerysense.capture-fixtures=true`. `mongosh` isn't installed here, and the class builds its commands with the production `MongoExplainCommands`, so the fixtures are exactly what the analyzer sends. The fixtures themselves sit under `backend/src/test/resources/mongo/` as the spec says.
- **Per-collection figures exist only where MongoDB reports them.** The classic engine (`explainVersion` "1") reports `docsExamined` and `nReturned` per stage. Pipelines pushed into the slot-based engine (`explainVersion` "2") report totals only. There `filtered` stays null, so the verdict reports a scan without claiming any waste.
- **TX6 and RS4 fold into TX1 and RS3 for MongoDB.** `explain` now reports the collection scan those two rules approximated from the filter text. M1 survives as the `listIndexes`-backed signal that names the field, and it warns on its own only when the plan did *not* already scan. `RULES.md` records the folds and new totals; full renumbering stays with step 9.
- **`suggestedChange` carries index statements only.** The `"rewrite"` value arrives with `MongoQueryRewriter` in step 6.
- **W2 has no MongoDB variant.** Every Mongo access stage either scans the collection or uses an index, so "no index and not a scan" can't occur.
- **Preflight stops checking field paths at the first stage that reshapes documents** (`$group`, `$project`, `$lookup`, `$unwind`, …). Fields after it are computed names that the schema can't know about. `$lookup`'s own `from`, `localField` and `foreignField` are still checked.
- **A collection that doesn't exist in `mongod`** is reported as V1 (`UNKNOWN`), with a hint to start the backend with `mongod` running. The spec is silent on this case.

## Deferred to later plans

- `MongoQueryRewriter`, Mongo compare, and `optimizedQuery` in history: step 6.
- The Analysis screen using `ExplainPlanView` for Mongo, the "no cost figure" note, deleting `MongoFindingsList`, and the health endpoint's `mongo` field: step 8. Every response field added here is additive, so the current frontend keeps working.
- Renumbering `RULES.md`: step 9.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../analysis/MongoExplainCommands.java` (new) | `MongoQuery` → the `explain` command document |
| `backend/.../analysis/MongoShapeGuard.java` (new) | Stage names and stats keys the parser understands |
| `backend/.../analysis/MongoExplainParser.java` (new) | `explain` output → `QueryAnalysisResult` fields, plan tree, W1/W3/W4 warnings |
| `backend/.../analysis/MongoQueryFields.java` (new) | Field paths out of a `MongoQuery`: all of them for preflight, classified for the advisor |
| `backend/.../dto/MongoQueryShape.java` (new) | Equality, sort, range and regex fields; ESR key order |
| `backend/.../analysis/MongoPreflightValidator.java` (new) | Collection and field paths exist in the active Mongo schema; no write stages |
| `backend/.../analysis/MongoExplainExecutor.java` (new) | Runs `explain` and `listIndexes` on the active Mongo database; 503 on outage |
| `backend/.../analysis/PlanAnalyzer.java` (new) | Per-dialect analysis interface |
| `backend/.../analysis/MySqlPlanAnalyzer.java` (new) | Today's SQL analysis path, moved unchanged |
| `backend/.../analysis/MongoExplainAnalyzer.java` (new) | The Mongo path, plus M1 and M2 |
| `backend/.../analysis/MongoSimpleAnalyzer.java` (delete) | Replaced |
| `backend/.../service/impl/QueryAnalysisServiceImpl.java` (modify) | Routes by dialect |
| `backend/.../dto/QueryAnalysisResult.java` (modify) | `executionMillis`, `mongoShape` (not serialised) |
| `backend/.../analysis/VerdictService.java` (modify) | MongoDB wording |
| `backend/.../optimization/IndexStatements.java` (new) | `CREATE INDEX` / `createIndex` text per dialect |
| `backend/.../dto/OptimizationSuggestion.java` (modify) | `suggestedChange` |
| `backend/.../optimization/TransactionalOptimizationRules.java`, `ResourceConsciousService.java` (modify) | `suggestedChange`, Mongo variants |
| `backend/.../advisor/IndexAdvisor.java`, `MySqlIndexAdvisor.java`, `MongoIndexAdvisor.java` (new) | The advisor's two halves |
| `backend/.../service/impl/SchemaAdvisorServiceImpl.java` (modify) | Routes by dialect |
| `backend/src/test/resources/mongo/explain/*.json`, `mongo/listIndexes/demo.json` (new, captured) | Real `explain` and `listIndexes` output |

`backend/...` = `backend/src/main/java/com/querysense/backend`. Tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: `MongoExplainCommands`, and fixtures captured from the local `mongod`

**Files:**
- Create: `backend/.../analysis/MongoExplainCommands.java`
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoExplainCommandsTest.java`
- Create: `backend/src/test/java/com/querysense/backend/analysis/MongoExplainFixtureCapture.java`
- Create (by running the capture): `backend/src/test/resources/mongo/explain/*.json`, `backend/src/test/resources/mongo/listIndexes/demo.json`
- Create: `backend/src/test/java/com/querysense/backend/analysis/MongoFixtures.java` (test helper)

**Interfaces:**
- Consumes: `MongoShellParser`, `MongoQuery` (existing); `JsonSchemaUploadParser`, `MongoProvisioner`, `MongoSeeder` (step 4).
- Produces:
  - `public final class MongoExplainCommands { public static final String VERBOSITY = "executionStats"; public static Document explain(MongoQuery query); }`
  - Fixture files named `<name>.json`, each `{"query": "<mongosh text>", "explain": {<raw explain output>}}`. The names are `find-collscan`, `find-express-ixscan`, `find-fetch-ixscan`, `find-sort`, `find-idhack`, `find-multikey`, `find-missing-collection`, `agg-group`, `agg-lookup-sort`, `agg-facet`, `count` and `delete`.
  - `mongo/listIndexes/demo.json`: `{"<collection>": [<listIndexes documents>], ...}` for the eight demo collections.
  - Test helper `MongoFixtures`: `record Captured(MongoQuery query, Document explain)`, `static Captured explain(String name)`, `static List<Document> indexes(String collection)`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoExplainCommandsTest {

    private final MongoShellParser parser = new MongoShellParser();

    private Document explain(String mongosh) {
        return MongoExplainCommands.explain(parser.parse(mongosh));
    }

    @Test
    @DisplayName("find carries its filter, projection, sort and limit, at executionStats")
    void find() {
        Document command = explain(
                "db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1}).sort({\"name\": -1}).limit(5)");

        assertThat(command.getString("verbosity")).isEqualTo("executionStats");
        Document find = command.get("explain", Document.class);
        assertThat(find.getString("find")).isEqualTo("customers");
        assertThat(find.get("filter")).isEqualTo(new Document("city", "Mumbai"));
        assertThat(find.get("projection")).isEqualTo(new Document("name", 1));
        assertThat(find.get("sort")).isEqualTo(new Document("name", -1));
        assertThat(find.get("limit")).isEqualTo(5);
    }

    @Test
    @DisplayName("a bare find sends an empty filter and nothing else")
    void bareFind() {
        Document find = explain("db.customers.find()").get("explain", Document.class);

        assertThat(find.keySet()).containsExactly("find", "filter");
        assertThat(find.get("filter")).isEqualTo(new Document());
    }

    @Test
    @DisplayName("aggregate sends its pipeline and an empty cursor")
    void aggregate() {
        Document aggregate = explain("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}])")
                .get("explain", Document.class);

        assertThat(aggregate.getString("aggregate")).isEqualTo("orders");
        assertThat(aggregate.getList("pipeline", Document.class))
                .containsExactly(new Document("$match", new Document("status", "PAID")));
        assertThat(aggregate.get("cursor")).isEqualTo(new Document());
    }

    @Test
    @DisplayName("countDocuments becomes the count command with its query")
    void count() {
        Document count = explain("db.orders.countDocuments({\"status\": \"PAID\"})").get("explain", Document.class);

        assertThat(count).isEqualTo(new Document("count", "orders").append("query", new Document("status", "PAID")));
    }

    @Test
    @DisplayName("deleteMany is wrapped in explain, so it is planned and never run")
    void deleteIsWrappedInExplain() {
        Document command = explain("db.orders.deleteMany({\"status\": \"CANCELLED\"})");

        assertThat(command.keySet()).containsExactly("explain", "verbosity");
        Document delete = command.get("explain", Document.class);
        assertThat(delete.getString("delete")).isEqualTo("orders");
        assertThat(delete.getList("deletes", Document.class)).containsExactly(
                new Document("q", new Document("status", "CANCELLED")).append("limit", 0));
    }

    @Test
    @DisplayName("every operation is covered")
    void everyOperation() {
        assertThat(MongoQuery.Operation.values()).hasSize(4);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainCommandsTest`
Expected: compilation failure (`MongoExplainCommands` does not exist).

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import org.bson.Document;

import java.util.List;

/** Builds the explain command, pinned to executionStats, for a parsed MongoDB query. */
public final class MongoExplainCommands {

    public static final String VERBOSITY = "executionStats";

    private MongoExplainCommands() {
    }

    public static Document explain(MongoQuery query) {
        return new Document("explain", command(query)).append("verbosity", VERBOSITY);
    }

    private static Document command(MongoQuery query) {
        Document filter = query.filter() == null ? new Document() : query.filter();
        return switch (query.operation()) {
            case FIND -> {
                Document find = new Document("find", query.collection()).append("filter", filter);
                if (query.projection() != null) {
                    find.append("projection", query.projection());
                }
                if (query.sort() != null) {
                    find.append("sort", query.sort());
                }
                if (query.limit() != null) {
                    find.append("limit", query.limit());
                }
                yield find;
            }
            case AGGREGATE -> new Document("aggregate", query.collection())
                    .append("pipeline", query.pipeline() == null ? List.of() : query.pipeline())
                    .append("cursor", new Document());
            case COUNT -> new Document("count", query.collection()).append("query", filter);
            case DELETE -> new Document("delete", query.collection())
                    .append("deletes", List.of(new Document("q", filter).append("limit", 0)));
        };
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainCommandsTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the capture class**

`MongoExplainFixtureCapture` deliberately doesn't end in `Test`, so a plain `./mvnw test` never picks it up.

```java
package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Captures explain and listIndexes output from the local mongod into test fixtures; run on demand only. */
@Tag("mongo")
class MongoExplainFixtureCapture {

    static final int ROWS = 2000;
    private static final String DATABASE = "querysense_fixtures";
    private static final Path EXPLAIN_DIR = Path.of("src/test/resources/mongo/explain");
    private static final Path INDEX_FILE = Path.of("src/test/resources/mongo/listIndexes/demo.json");

    static final Map<String, String> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("find-collscan", "db.customers.find({\"city\": \"Mumbai\"})");
        QUERIES.put("find-express-ixscan", "db.customers.find({\"email\": \"nobody@example.com\"})");
        QUERIES.put("find-fetch-ixscan", "db.customers.find({\"email\": {\"$gte\": \"m\"}})");
        QUERIES.put("find-sort", "db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})");
        QUERIES.put("find-idhack", "db.customers.find({\"_id\": ObjectId(\"000000000000000000000000\")})");
        QUERIES.put("find-multikey",
                "db.orders.find({\"items.product_id\": ObjectId(\"000000000000000000000000\")})");
        QUERIES.put("find-missing-collection", "db.nosuch.find({\"a\": 1})");
        QUERIES.put("agg-group", "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])");
        QUERIES.put("agg-lookup-sort", "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"customer\"}}, "
                + "{\"$sort\": {\"order_date\": -1}}])");
        QUERIES.put("agg-facet", "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$facet\": {\"n\": [{\"$count\": \"n\"}]}}])");
        QUERIES.put("count", "db.orders.countDocuments({\"status\": \"PAID\"})");
        QUERIES.put("delete", "db.orders.deleteMany({\"status\": \"CANCELLED\"})");
    }

    private static boolean mongoListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void capture() throws IOException {
        assumeTrue(Boolean.getBoolean("querysense.capture-fixtures"),
                "run with -Dquerysense.capture-fixtures=true to recapture");
        assumeTrue(mongoListening(), "no mongod on localhost:27017");

        JsonWriterSettings pretty = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build();
        MongoShellParser parser = new MongoShellParser();
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000")) {
            MongoDatabase db = client.getDatabase(DATABASE);
            db.drop();
            ParsedSchema demo = new JsonSchemaUploadParser().parse(
                    new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
            new MongoProvisioner(client, DATABASE).provision(demo);
            new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);

            Files.createDirectories(EXPLAIN_DIR);
            for (Map.Entry<String, String> query : QUERIES.entrySet()) {
                Document explain = db.runCommand(MongoExplainCommands.explain(parser.parse(query.getValue())));
                Document fixture = new Document("query", query.getValue()).append("explain", explain);
                Files.writeString(EXPLAIN_DIR.resolve(query.getKey() + ".json"), fixture.toJson(pretty));
            }

            Document indexes = new Document();
            for (KnownSchema.Table table : KnownMongoSchema.TABLES) {
                indexes.append(table.name(), db.getCollection(table.name()).listIndexes().into(new ArrayList<>()));
            }
            Files.createDirectories(INDEX_FILE.getParent());
            Files.writeString(INDEX_FILE, indexes.toJson(pretty));

            db.drop();
        }
    }
}
```

- [ ] **Step 6: Capture the fixtures**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainFixtureCapture -Dquerysense.capture-fixtures=true`
Expected: 1 test, passed (not skipped). `ls src/test/resources/mongo/explain` lists the 12 files above. `src/test/resources/mongo/listIndexes/demo.json` exists, and its `customers` entry includes `email_1` with `"unique": true`.

If the test reports *skipped*, the property didn't reach the forked JVM. Re-run with `-DargLine=-Dquerysense.capture-fixtures=true` and record the ruling. Don't hand-write fixtures.

- [ ] **Step 7: Write the fixture helper**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads explain and listIndexes output captured from a real mongod by MongoExplainFixtureCapture. */
final class MongoFixtures {

    record Captured(MongoQuery query, Document explain) {
    }

    private MongoFixtures() {
    }

    static Captured explain(String name) {
        Document file = Document.parse(read("mongo/explain/" + name + ".json"));
        return new Captured(new MongoShellParser().parse(file.getString("query")),
                file.get("explain", Document.class));
    }

    static List<Document> indexes(String collection) {
        return Document.parse(read("mongo/listIndexes/demo.json")).getList(collection, Document.class);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green. The capture class does not run.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "test(mongo): explain commands at executionStats, and explain/listIndexes fixtures captured from mongod 8.3

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `MongoExplainParser` and `MongoShapeGuard`

**Files:**
- Create: `backend/.../analysis/MongoShapeGuard.java`, `backend/.../analysis/MongoExplainParser.java`
- Modify: `backend/.../dto/QueryAnalysisResult.java` (adds `executionMillis`)
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoExplainParserTest.java`

**Interfaces:**
- Consumes: `MongoFixtures` (Task 1), `PlanNode`, `TableAnalysis`, `QueryAnalysisResult` (existing).
- Produces:
  - `QueryAnalysisResult`: `Long getExecutionMillis()` and `void setExecutionMillis(Long)`.
  - `final class MongoShapeGuard` (package-private): `SCAN_STAGES`, `INDEX_STAGES`, `KNOWN_STAGES` (`Set<String>`), `REQUIRED_STATS` (`List<String>`), and `static boolean known(String stage)`.
  - `@Component public class MongoExplainParser { public void read(Document explain, MongoQuery query, QueryAnalysisResult result); }`. It fills `tables`, `planTree`, `hasFullTableScan`, `usesIndex`, `keyUsed`, `accessType`, `usesFilesort`, `usesTemporaryTable`, `estimatedRows`, `estimatedRowsReturned`, `filtered` and `executionMillis`, plus warnings W1, W3 and W4. `queryCost` is left null. On an unreadable shape, or a collection missing from `mongod`, it sets `explainFailed` with a warning.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dto.PlanNode;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoExplainParserTest {

    private static final long ROWS = MongoExplainFixtureCapture.ROWS;

    private final MongoExplainParser parser = new MongoExplainParser();

    private QueryAnalysisResult read(String fixture) {
        MongoFixtures.Captured captured = MongoFixtures.explain(fixture);
        QueryAnalysisResult result = new QueryAnalysisResult();
        parser.read(captured.explain(), captured.query(), result);
        return result;
    }

    private static TableAnalysis table(QueryAnalysisResult result, String name) {
        return result.getTables().stream().filter(t -> name.equals(t.getTableName())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a collection scan maps to a full scan with documents examined, returned and the share kept")
    void findCollscan() {
        QueryAnalysisResult result = read("find-collscan");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isUsesIndex()).isFalse();
        assertThat(result.getAccessType()).isEqualTo("COLLSCAN");
        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getEstimatedRowsReturned()).isBetween(1L, ROWS - 1);
        assertThat(result.getQueryCost()).isNull();
        assertThat(result.getExecutionMillis()).isNotNull();
        TableAnalysis customers = table(result, "customers");
        assertThat(customers.isFullTableScan()).isTrue();
        assertThat(customers.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(customers.getFiltered())
                .isEqualTo(100.0 * result.getEstimatedRowsReturned() / ROWS);
        assertThat(result.getPlanTree().kind()).isEqualTo("COLLSCAN");
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("Collection scan").contains("customers"));
    }

    @Test
    @DisplayName("the express index path on a unique field is index use, with its index named")
    void findExpressIxscan() {
        QueryAnalysisResult result = read("find-express-ixscan");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.isHasFullTableScan()).isFalse();
        assertThat(result.getKeyUsed()).isEqualTo("email_1");
        assertThat(result.getAccessType()).isEqualTo("EXPRESS_IXSCAN");
        assertThat(result.getWarnings()).noneSatisfy(w -> assertThat(w).contains("Collection scan"));
    }

    @Test
    @DisplayName("an index scan under FETCH takes its document counts from the FETCH")
    void findFetchIxscan() {
        QueryAnalysisResult result = read("find-fetch-ixscan");

        TableAnalysis customers = table(result, "customers");
        assertThat(customers.getAccessType()).isEqualTo("IXSCAN");
        assertThat(customers.getKeyUsed()).isEqualTo("email_1");
        assertThat(customers.getEstimatedRows()).isPositive();
        assertThat(result.getPlanTree().kind()).isEqualTo("FETCH");
        assertThat(result.getPlanTree().children()).extracting(PlanNode::kind).containsExactly("IXSCAN");
    }

    @Test
    @DisplayName("a blocking SORT is an in-memory sort")
    void findSort() {
        QueryAnalysisResult result = read("find-sort");

        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(result.getPlanTree().kind()).isEqualTo("SORT");
        assertThat(result.getPlanTree().usesFilesort()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("In-memory sort"));
    }

    @Test
    @DisplayName("an _id lookup names the _id_ index even when the stage carries no index name")
    void findIdhack() {
        QueryAnalysisResult result = read("find-idhack");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.getKeyUsed()).isEqualTo("_id_");
    }

    @Test
    @DisplayName("a multikey index on an embedded array path is index use")
    void findMultikey() {
        assertThat(read("find-multikey").getKeyUsed()).isEqualTo("items.product_id_1");
    }

    @Test
    @DisplayName("a collection missing from mongod fails the analysis, naming the collection")
    void missingCollectionFails() {
        MongoFixtures.Captured captured = MongoFixtures.explain("find-missing-collection");
        MongoQuery declared = new MongoShellParser().parse("db.customers.find({\"a\": 1})");
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(captured.explain(), declared, result);

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement()
                .satisfies(w -> assertThat(w).contains("'customers'").contains("does not exist"));
    }

    @Test
    @DisplayName("a pushed-down $group reads its classic tree, claims no discard, and is not a spill")
    void pushedDownGroupClaimsNoDiscard() {
        QueryAnalysisResult result = read("agg-group");

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isUsesTemporaryTable()).isFalse();
        TableAnalysis orders = table(result, "orders");
        assertThat(orders.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(orders.getFiltered()).isNull();
        assertThat(result.getFiltered()).isNull();
        assertThat(result.getPlanTree().kind()).isEqualTo("GROUP");
    }

    @Test
    @DisplayName("a $lookup reports the joined collection and its index, and the sort above it")
    void lookupAndSort() {
        QueryAnalysisResult result = read("agg-lookup-sort");

        assertThat(table(result, "orders").isFullTableScan()).isTrue();
        TableAnalysis customers = table(result, "customers");
        assertThat(customers.isUsesIndex()).isTrue();
        assertThat(customers.getKeyUsed()).isEqualTo("_id_");
        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(result.getEstimatedRows()).isEqualTo(2 * ROWS);
    }

    @Test
    @DisplayName("a pipeline MongoDB did not push down reads its $cursor, and later stages sit above it in the tree")
    void cursorPipeline() {
        QueryAnalysisResult result = read("agg-facet");

        assertThat(table(result, "orders").getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getPlanTree().kind()).isEqualTo("$facet");
        assertThat(result.getPlanTree().children()).extracting(PlanNode::kind).containsExactly("COLLSCAN");
    }

    @Test
    @DisplayName("count reports the matching documents, not count's own zero")
    void countReportsMatchesNotZero() {
        QueryAnalysisResult result = read("count");

        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getEstimatedRowsReturned()).isBetween(1L, ROWS - 1);
        assertThat(table(result, "orders").getFiltered()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("delete is read from its explain, like count")
    void deleteIsRead() {
        QueryAnalysisResult result = read("delete");

        assertThat(result.getPlanTree().kind()).isEqualTo("BATCHED_DELETE");
        assertThat(result.getEstimatedRowsReturned()).isPositive();
    }

    @Test
    @DisplayName("every captured fixture uses only stages the guard knows")
    void fixturesHaveNoUnknownStages() {
        for (String name : MongoExplainFixtureCapture.QUERIES.keySet()) {
            if (name.equals("find-missing-collection")) {
                continue;
            }
            assertThat(read(name).getWarnings()).as(name)
                    .noneSatisfy(w -> assertThat(w).contains("does not read"));
        }
    }

    private static MongoQuery find() {
        return new MongoShellParser().parse("db.customers.find({\"city\": \"Mumbai\"})");
    }

    private static Document stats(Document executionStages) {
        return new Document("nReturned", 5).append("totalDocsExamined", 100)
                .append("executionTimeMillis", 1).append("executionStages", executionStages);
    }

    @Test
    @DisplayName("an unknown stage warns, and the stages it wraps are still read")
    void unknownStageWarns() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers")
                .append("docsExamined", 100).append("nReturned", 5);
        Document explain = new Document("queryPlanner", new Document("winningPlan", new Document("stage", "MYSTERY")
                .append("inputStage", collscan)))
                .append("executionStats", stats(new Document("stage", "MYSTERY").append("inputStage", collscan)));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("MYSTERY").contains("does not read"));
    }

    @Test
    @DisplayName("a group that spilled to disk anywhere in the execution stats is a temporary-table signal")
    void spilledGroup() {
        Document explain = new Document("queryPlanner", new Document("winningPlan", new Document("queryPlan",
                new Document("stage", "GROUP").append("inputStage",
                        new Document("stage", "COLLSCAN").append("nss", "db.customers")))))
                .append("executionStats", stats(new Document("stage", "project").append("inputStage",
                        new Document("stage", "group").append("usedDisk", true))));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.isUsesTemporaryTable()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("spilled to disk"));
    }

    @Test
    @DisplayName("an explain result without a winning plan is unreadable, not empty")
    void unreadableShape() {
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(new Document("ok", 1.0), find(), result);

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement().satisfies(w -> assertThat(w).contains("shape"));
    }

    @Test
    @DisplayName("a missing statistic is named and its figure left out")
    void missingStatistic() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers");
        Document explain = new Document("queryPlanner", new Document("winningPlan", collscan))
                .append("executionStats", new Document("nReturned", 5).append("executionTimeMillis", 1)
                        .append("executionStages", collscan));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(result.getEstimatedRows()).isNull();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("totalDocsExamined"));
    }

    @Test
    @DisplayName("an index a rejected plan would have used is reported as a possible key on the scanned collection")
    void rejectedPlanIndexIsPossibleKey() {
        Document collscan = new Document("stage", "COLLSCAN").append("nss", "db.customers")
                .append("docsExamined", 100).append("nReturned", 5);
        Document explain = new Document("queryPlanner", new Document("winningPlan", collscan)
                .append("rejectedPlans", List.of(new Document("stage", "FETCH").append("inputStage",
                        new Document("stage", "IXSCAN").append("indexName", "city_1")))))
                .append("executionStats", stats(collscan));
        QueryAnalysisResult result = new QueryAnalysisResult();

        parser.read(explain, find(), result);

        assertThat(table(result, "customers").getPossibleKeys()).isEqualTo("city_1");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainParserTest`
Expected: compilation failure (`MongoExplainParser`, `getExecutionMillis` missing).

- [ ] **Step 3: Implement**

`QueryAnalysisResult`: add a field after `queryCost`:

```java
    private Long executionMillis;
```

and, after `setQueryCost`:

```java
    public Long getExecutionMillis() { return executionMillis; }
    public void setExecutionMillis(Long executionMillis) { this.executionMillis = executionMillis; }
```

`MongoShapeGuard.java`:

```java
package com.querysense.backend.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The explain stages and statistics MongoExplainParser understands; anything else is reported, not guessed at. */
final class MongoShapeGuard {

    static final Set<String> SCAN_STAGES = Set.of("COLLSCAN");

    static final Set<String> INDEX_STAGES = Set.of(
            "IXSCAN", "EXPRESS_IXSCAN", "EXPRESS_IDHACK", "IDHACK", "COUNT_SCAN", "DISTINCT_SCAN",
            "CLUSTERED_IXSCAN", "EXPRESS_CLUSTERED_IXSCAN");

    static final Set<String> KNOWN_STAGES;

    static final List<String> REQUIRED_STATS = List.of("nReturned", "totalDocsExamined", "executionTimeMillis");

    static {
        Set<String> known = new HashSet<>(SCAN_STAGES);
        known.addAll(INDEX_STAGES);
        known.addAll(Set.of(
                "FETCH", "SORT", "SORT_KEY_GENERATOR", "SORT_MERGE", "PROJECTION_SIMPLE", "PROJECTION_DEFAULT",
                "PROJECTION_COVERED", "LIMIT", "SKIP", "OR", "AND_HASH", "AND_SORTED", "COUNT", "BATCHED_DELETE",
                "DELETE", "GROUP", "EQ_LOOKUP", "EQ_LOOKUP_UNWIND", "UNWIND", "SHARDING_FILTER", "EOF", "SUBPLAN",
                "CACHED_PLAN", "MATCH", "REPLACE_ROOT", "TEXT_MATCH", "TEXT_OR", "GEO_NEAR_2D",
                "GEO_NEAR_2DSPHERE"));
        KNOWN_STAGES = Set.copyOf(known);
    }

    private MongoShapeGuard() {
    }

    static boolean known(String stage) {
        return KNOWN_STAGES.contains(stage);
    }
}
```

`MongoExplainParser.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dto.PlanNode;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import com.querysense.backend.mongo.MongoQuery;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maps MongoDB explain output, at executionStats verbosity, onto a QueryAnalysisResult. */
@Component
public class MongoExplainParser {

    private static final String UNREADABLE = "MongoDB returned an explain result in a shape QuerySense does not "
            + "read, so no plan figures are shown. The query itself was not run.";

    private static final class Walk {
        final List<TableAnalysis> tables = new ArrayList<>();
        final Set<String> unknownStages = new LinkedHashSet<>();
        boolean withStats;
        boolean sorts;
        boolean lookup;
        String collection;
    }

    public void read(Document explain, MongoQuery query, QueryAnalysisResult result) {
        Document source = explain;
        List<Document> laterStages = List.of();
        if (explain.get("stages") instanceof List<?> stages && !stages.isEmpty()) {
            source = stages.get(0) instanceof Document first && first.get("$cursor") instanceof Document cursor
                    ? cursor
                    : null;
            laterStages = stages.subList(1, stages.size()).stream()
                    .filter(Document.class::isInstance).map(Document.class::cast).toList();
        }
        Document planner = documentAt(source, "queryPlanner");
        Document stats = documentAt(source, "executionStats");
        Document winning = documentAt(planner, "winningPlan");
        if (winning == null || stats == null) {
            fail(result);
            return;
        }

        Document queryPlan = documentAt(winning, "queryPlan");
        Document planRoot = queryPlan != null ? queryPlan : winning;
        if ("EOF".equals(stageOf(planRoot)) && "nonExistentNamespace".equals(planRoot.get("type"))) {
            result.addWarning("The collection '" + query.collection() + "' does not exist in MongoDB yet, so "
                    + "there is no plan to read. If this is the built-in demo, start the backend with mongod "
                    + "running so it can be seeded, then analyze the query again.");
            result.setExplainFailed(true);
            return;
        }

        Walk walk = new Walk();
        walk.collection = query.collection();
        Document executionStages = documentAt(stats, "executionStages");
        walk.withStats = queryPlan == null && executionStages != null;
        Document root = walk.withStats ? executionStages : planRoot;
        if (stageOf(root) == null) {
            fail(result);
            return;
        }

        PlanNode tree = node(root, null, walk);
        for (Document stage : laterStages) {
            String name = stage.keySet().stream().filter(k -> k.startsWith("$")).findFirst().orElse("?");
            boolean sorts = "$sort".equals(name);
            walk.sorts |= sorts;
            tree = new PlanNode(name, null, null, null, null, null, sorts, spilledHere(stage), List.of(tree));
        }

        markPossibleKeys(planner, walk);
        if (!walk.withStats && !walk.lookup
                && walk.tables.stream().map(TableAnalysis::getTableName).distinct().count() == 1) {
            walk.tables.forEach(t -> t.setEstimatedRows(number(stats.get("totalDocsExamined"))));
        }

        summarise(result, query, stats, walk, tree, spilledAnywhere(stats) || spilledAnywhere(laterStages));
    }

    private PlanNode node(Document stage, Document parent, Walk walk) {
        String name = stageOf(stage);
        if (name != null && !MongoShapeGuard.known(name)) {
            walk.unknownStages.add(name);
        }
        List<PlanNode> children = new ArrayList<>();
        for (Document child : childrenOf(stage)) {
            children.add(node(child, stage, walk));
        }

        String index = indexOf(stage, name);
        String collection = collectionOf(stage, walk);
        if (MongoShapeGuard.SCAN_STAGES.contains(name)) {
            walk.tables.add(table(collection, name, null, true,
                    number(stage.get("docsExamined")), number(stage.get("nReturned")), walk.withStats));
        } else if (MongoShapeGuard.INDEX_STAGES.contains(name)) {
            boolean fetched = parent != null && "FETCH".equals(stageOf(parent));
            Document counted = fetched ? parent : stage;
            walk.tables.add(table(collection, name, index, false,
                    number(counted.get("docsExamined")), number(counted.get("nReturned")), walk.withStats));
        } else if ("EQ_LOOKUP".equals(name) || "EQ_LOOKUP_UNWIND".equals(name)) {
            walk.lookup = true;
            String foreign = shortName(stage.get("foreignCollection"));
            walk.tables.add(table(foreign, name, index, index == null, null, null, false));
        }

        boolean sorts = "SORT".equals(name);
        walk.sorts |= sorts;
        return new PlanNode(name, index != null ? index : collection, name, index,
                number(stage.get("docsExamined")), null, sorts, spilledHere(stage), children);
    }

    private static TableAnalysis table(String collection, String stage, String index, boolean scan,
                                       Long examined, Long returned, boolean withStats) {
        TableAnalysis table = new TableAnalysis();
        table.setTableName(collection);
        table.setAccessType(stage);
        table.setKeyUsed(index);
        table.setFullTableScan(scan);
        table.setUsesIndex(index != null);
        if (withStats) {
            table.setEstimatedRows(examined);
            if (examined != null && examined > 0 && returned != null) {
                table.setFiltered(100.0 * returned / examined);
            }
        }
        return table;
    }

    private void summarise(QueryAnalysisResult result, MongoQuery query, Document stats, Walk walk,
                           PlanNode tree, boolean spilled) {
        List<TableAnalysis> tables = walk.tables;
        result.setTables(tables);
        result.setPlanTree(tree);
        result.setQueryCost(null);
        result.setHasFullTableScan(tables.stream().anyMatch(TableAnalysis::isFullTableScan));
        result.setUsesIndex(tables.stream().anyMatch(TableAnalysis::isUsesIndex));
        result.setKeyUsed(tables.stream().map(TableAnalysis::getKeyUsed).filter(Objects::nonNull)
                .findFirst().orElse(null));
        result.setAccessType(result.isHasFullTableScan()
                ? "COLLSCAN"
                : tables.stream().filter(TableAnalysis::isUsesIndex).map(TableAnalysis::getAccessType)
                        .findFirst().orElse(tree.kind()));
        result.setUsesFilesort(walk.sorts);
        result.setUsesTemporaryTable(spilled);
        result.setEstimatedRows(number(stats.get("totalDocsExamined")));
        result.setExecutionMillis(number(stats.get("executionTimeMillis")));

        boolean countsOnly = query.operation() == MongoQuery.Operation.COUNT
                || query.operation() == MongoQuery.Operation.DELETE;
        result.setEstimatedRowsReturned(countsOnly
                ? tables.stream().map(TableAnalysis::getEstimatedRowsReturned).filter(Objects::nonNull)
                        .findFirst().orElse(null)
                : number(stats.get("nReturned")));
        result.setFiltered(tables.stream().filter(t -> t.getEstimatedRows() != null)
                .max(Comparator.comparing(TableAnalysis::getEstimatedRows))
                .map(TableAnalysis::getFiltered).orElse(null));

        for (String key : MongoShapeGuard.REQUIRED_STATS) {
            if (!stats.containsKey(key)) {
                result.addWarning("MongoDB's explain output did not include " + key
                        + ", so the figure it supplies is not shown.");
            }
        }
        if (!walk.unknownStages.isEmpty()) {
            result.addWarning("MongoDB's plan includes a stage QuerySense does not read ("
                    + String.join(", ", walk.unknownStages) + "). Figures that depend on it are left out "
                    + "rather than guessed.");
        }
        addWarnings(result, tables, query.collection());
    }

    private static void addWarnings(QueryAnalysisResult result, List<TableAnalysis> tables, String collection) {
        Set<String> emitted = new LinkedHashSet<>();
        for (TableAnalysis table : tables) {
            String name = table.getTableName();
            if (table.isFullTableScan() && emitted.add(name)) {
                result.addWarning("Collection scan detected on '" + name + "'. MongoDB read every document in "
                        + "this collection. If the query is slow, check whether an index on the filtered fields "
                        + "could reduce the documents examined.");
            }
        }
        if (result.isUsesTemporaryTable()) {
            result.addWarning("The grouping stage on '" + collection + "' spilled to disk. It needed more memory "
                    + "than MongoDB allows a single stage, so part of the work was written out and read back.");
        }
        if (result.isUsesFilesort()) {
            result.addWarning("In-memory sort used for '" + collection + "'. If the query is slow, check whether "
                    + "an index matching the sort fields could let MongoDB return documents already in order.");
        }
    }

    private static void markPossibleKeys(Document planner, Walk walk) {
        Set<String> rejectedIndexes = new LinkedHashSet<>();
        indexNames(planner.get("rejectedPlans"), rejectedIndexes);
        if (rejectedIndexes.isEmpty()) {
            return;
        }
        String keys = String.join(", ", rejectedIndexes);
        walk.tables.stream().filter(TableAnalysis::isFullTableScan).forEach(t -> t.setPossibleKeys(keys));
    }

    private static void indexNames(Object node, Set<String> out) {
        if (node instanceof Document document) {
            if (document.get("indexName") instanceof String name) {
                out.add(name);
            }
            document.values().forEach(v -> indexNames(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> indexNames(v, out));
        }
    }

    private static List<Document> childrenOf(Document stage) {
        List<Document> children = new ArrayList<>();
        for (String key : List.of("inputStage", "outerStage", "innerStage")) {
            if (stage.get(key) instanceof Document child) {
                children.add(child);
            }
        }
        if (stage.get("inputStages") instanceof List<?> list) {
            list.stream().filter(Document.class::isInstance).map(Document.class::cast).forEach(children::add);
        }
        return children;
    }

    private static String indexOf(Document stage, String name) {
        if (stage.get("indexName") instanceof String index) {
            return index;
        }
        return name != null && name.endsWith("IDHACK") ? "_id_" : null;
    }

    private static String collectionOf(Document stage, Walk walk) {
        String name = shortName(stage.get("nss"));
        return name != null ? name : walk.collection;
    }

    private static String shortName(Object namespace) {
        if (!(namespace instanceof String nss)) {
            return null;
        }
        int dot = nss.indexOf('.');
        return dot < 0 ? nss : nss.substring(dot + 1);
    }

    private static boolean spilledHere(Document stage) {
        boolean group = "GROUP".equalsIgnoreCase(stageOf(stage)) || stage.containsKey("$group");
        return group && Boolean.TRUE.equals(stage.get("usedDisk"));
    }

    private static boolean spilledAnywhere(Object node) {
        if (node instanceof Document document) {
            return spilledHere(document) || document.values().stream().anyMatch(MongoExplainParser::spilledAnywhere);
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(MongoExplainParser::spilledAnywhere);
        }
        return false;
    }

    private static String stageOf(Document stage) {
        return stage != null && stage.get("stage") instanceof String name ? name : null;
    }

    private static Document documentAt(Document parent, String key) {
        return parent != null && parent.get(key) instanceof Document child ? child : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static void fail(QueryAnalysisResult result) {
        result.addWarning(UNREADABLE);
        result.setExplainFailed(true);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainParserTest`
Expected: PASS, 18 tests. If `fixturesHaveNoUnknownStages` names a stage, the captured 8.3 output uses a stage the guard doesn't list. Add that exact name to `KNOWN_STAGES` only if it is a classic-engine stage name (upper case), and record a ruling. Never loosen the test.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): read explain output at executionStats onto the analysis result, with a shape guard

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `MongoQueryFields`, `MongoQueryShape` and `MongoPreflightValidator`

**Files:**
- Create: `backend/.../dto/MongoQueryShape.java`, `backend/.../analysis/MongoQueryFields.java`, `backend/.../analysis/MongoPreflightValidator.java`
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoQueryFieldsTest.java`, `MongoPreflightValidatorTest.java`

**Interfaces:**
- Consumes: `MongoQuery`, `MongoShellParser`, `ActiveSchemaProvider`, `KnownSchema.Table`, `SchemaSnapshot.fieldTypesFor`, `IndexSpec.KeyPart` (existing).
- Produces:
  - `public record MongoQueryShape(String collection, List<String> equalityFields, List<IndexSpec.KeyPart> sortKeys, List<String> rangeFields, List<String> unanchoredRegexFields, boolean groupsWithoutIndexedMatch)`, with `List<IndexSpec.KeyPart> esrKeys()` (equality, then sort, then range, each field once) and `List<IndexSpec.KeyPart> sortIndexKeys()` (equality, then sort).
  - `public final class MongoQueryFields` with:
    - `record Lookup(String from, String localField, String foreignField, boolean fromBase)`
    - `static boolean writesOutput(MongoQuery)`
    - `static List<String> referencedPaths(MongoQuery)`
    - `static List<Lookup> lookups(MongoQuery)`
    - `static MongoQueryShape shape(MongoQuery, Set<String> leadingIndexFields)`
  - `@Component public class MongoPreflightValidator { public MongoPreflightValidator(ActiveSchemaProvider); public Optional<String> validate(MongoQuery query); }`

- [ ] **Step 1: Write the failing tests**

`MongoQueryFieldsTest.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MongoQueryFieldsTest {

    private final MongoShellParser parser = new MongoShellParser();

    private MongoQueryShape shape(String mongosh, String... leading) {
        return MongoQueryFields.shape(parser.parse(mongosh), Set.of(leading));
    }

    @Test
    @DisplayName("filter fields split into equality, range and unanchored regex, and the sort keeps direction")
    void classifiesFilterAndSort() {
        MongoQueryShape shape = shape("db.payments.find({\"method\": \"UPI\", \"amount\": {\"$gte\": 10}, "
                + "\"order_id\": {\"$in\": [1, 2]}, \"note\": {\"$regex\": \"pi\"}}).sort({\"payment_date\": -1})");

        assertThat(shape.collection()).isEqualTo("payments");
        assertThat(shape.equalityFields()).containsExactly("method", "order_id");
        assertThat(shape.rangeFields()).containsExactly("amount");
        assertThat(shape.unanchoredRegexFields()).containsExactly("note");
        assertThat(shape.sortKeys()).containsExactly(new IndexSpec.KeyPart("payment_date", -1));
    }

    @Test
    @DisplayName("ESR orders the index keys equality, sort, range, each field once")
    void esrOrder() {
        MongoQueryShape shape = shape("db.payments.find({\"amount\": {\"$gt\": 5}, \"method\": \"UPI\"})"
                + ".sort({\"payment_date\": -1, \"method\": 1})");

        assertThat(shape.esrKeys()).containsExactly(
                new IndexSpec.KeyPart("method", 1),
                new IndexSpec.KeyPart("payment_date", -1),
                new IndexSpec.KeyPart("amount", 1));
        assertThat(shape.sortIndexKeys()).containsExactly(
                new IndexSpec.KeyPart("method", 1),
                new IndexSpec.KeyPart("payment_date", -1));
    }

    @Test
    @DisplayName("an anchored regex is a range, and $and is flattened while $or is left out")
    void anchoredRegexAndLogic() {
        MongoQueryShape shape = shape("db.customers.find({\"name\": {\"$regex\": \"^Ra\"}, "
                + "\"$and\": [{\"city\": \"Pune\"}], \"$or\": [{\"phone\": \"1\"}]})");

        assertThat(shape.rangeFields()).containsExactly("name");
        assertThat(shape.equalityFields()).containsExactly("city");
        assertThat(shape.unanchoredRegexFields()).isEmpty();
    }

    @Test
    @DisplayName("an aggregate's leading $match and $sort are its filter and sort; later stages are not")
    void aggregateLeadingStages() {
        MongoQueryShape shape = shape("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$sort\": {\"order_date\": -1}}, {\"$group\": {\"_id\": \"$customer_id\"}}, "
                + "{\"$match\": {\"_id\": 1}}])");

        assertThat(shape.equalityFields()).containsExactly("status");
        assertThat(shape.sortKeys()).containsExactly(new IndexSpec.KeyPart("order_date", -1));
    }

    @Test
    @DisplayName("a $group with no $match on an indexed field before it is flagged; with one it is not")
    void groupWithoutIndexedMatch() {
        String unfiltered = "db.orders.aggregate([{\"$group\": {\"_id\": \"$status\"}}])";
        String unindexed = "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$customer_id\"}}])";
        String indexed = "db.orders.aggregate([{\"$match\": {\"customer_id\": 1}}, "
                + "{\"$group\": {\"_id\": \"$status\"}}])";

        assertThat(shape(unfiltered, "customer_id").groupsWithoutIndexedMatch()).isTrue();
        assertThat(shape(unindexed, "customer_id").groupsWithoutIndexedMatch()).isTrue();
        assertThat(shape(indexed, "customer_id").groupsWithoutIndexedMatch()).isFalse();
        assertThat(shape("db.orders.find({})").groupsWithoutIndexedMatch()).isFalse();
    }

    @Test
    @DisplayName("referenced paths cover filter, projection inclusions, sort, $elemMatch and $or")
    void referencedPathsOfFind() {
        assertThat(MongoQueryFields.referencedPaths(parser.parse(
                "db.orders.find({\"$or\": [{\"status\": \"NEW\"}], \"items\": {\"$elemMatch\": {\"quantity\": 2}}}, "
                        + "{\"customer_id\": 1, \"employee_id\": 0}).sort({\"order_date\": 1})")))
                .containsExactly("status", "items", "items.quantity", "customer_id", "order_date");
    }

    @Test
    @DisplayName("pipeline paths stop after the first stage that reshapes documents")
    void referencedPathsOfPipeline() {
        assertThat(MongoQueryFields.referencedPaths(parser.parse(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$sort\": {\"order_date\": 1}}, "
                        + "{\"$group\": {\"_id\": \"$customer_id\", \"total\": {\"$sum\": \"$items.quantity\"}}}, "
                        + "{\"$match\": {\"total\": {\"$gt\": 2}}}])")))
                .containsExactly("status", "order_date", "customer_id", "items.quantity");
    }

    @Test
    @DisplayName("lookups name their collections and fields, and whether they read the base documents")
    void lookups() {
        assertThat(MongoQueryFields.lookups(parser.parse(
                "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": \"customer_id\", "
                        + "\"foreignField\": \"_id\", \"as\": \"c\"}}, {\"$unwind\": \"$c\"}, "
                        + "{\"$lookup\": {\"from\": \"employees\", \"localField\": \"c.x\", "
                        + "\"foreignField\": \"_id\", \"as\": \"e\"}}])")))
                .containsExactly(
                        new MongoQueryFields.Lookup("customers", "customer_id", "_id", true),
                        new MongoQueryFields.Lookup("employees", "c.x", "_id", false));
    }

    @Test
    @DisplayName("$out and $merge are writes")
    void writes() {
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.aggregate([{\"$out\": \"copy\"}])"))).isTrue();
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.aggregate([{\"$merge\": \"copy\"}])"))).isTrue();
        assertThat(MongoQueryFields.writesOutput(parser.parse("db.orders.find({})"))).isFalse();
    }
}
```

`MongoPreflightValidatorTest.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MongoPreflightValidatorTest {

    private final ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
    private final MongoPreflightValidator validator = new MongoPreflightValidator(activeSchema);
    private final MongoShellParser parser = new MongoShellParser();

    private Optional<String> check(String mongosh) {
        return validator.validate(parser.parse(mongosh));
    }

    @Test
    @DisplayName("a query on declared fields passes")
    void declaredPasses() {
        assertThat(check("db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1}).sort({\"name\": 1})")).isEmpty();
        assertThat(check("db.orders.find({\"items.quantity\": {\"$gt\": 2}})")).isEmpty();
        assertThat(check("db.orders.find({\"items.0.quantity\": 2})")).isEmpty();
        assertThat(check("db.orders.find({\"items\": {\"$size\": 2}})")).isEmpty();
        assertThat(check("db.customers.find({\"_id\": 1})")).isEmpty();
    }

    @Test
    @DisplayName("an unknown collection is refused, in the wording of SQL's P2")
    void unknownCollection() {
        assertThat(check("db.users.find({})")).hasValueSatisfying(m -> assertThat(m)
                .isEqualTo("Collection 'users' is not in the active MongoDB schema — this query references "
                        + "a collection that isn't part of the real schema."));
    }

    @Test
    @DisplayName("collection names are matched exactly, never by singular form")
    void exactCollectionName() {
        assertThat(check("db.customer.find({})")).isPresent();
    }

    @Test
    @DisplayName("an unknown field in the filter, projection or sort is refused, naming it")
    void unknownFields() {
        assertThat(check("db.customers.find({\"age\": 30})")).hasValueSatisfying(m -> assertThat(m)
                .isEqualTo("Field 'age' does not exist on 'customers' — this query references a field that "
                        + "isn't part of the real schema."));
        assertThat(check("db.customers.find({}, {\"age\": 1})")).isPresent();
        assertThat(check("db.customers.find({}).sort({\"age\": 1})")).isPresent();
        assertThat(check("db.customers.find({\"$or\": [{\"age\": 1}]})")).isPresent();
        assertThat(check("db.orders.find({\"items\": {\"$elemMatch\": {\"bogus\": 1}}})"))
                .hasValueSatisfying(m -> assertThat(m).contains("'items.bogus'"));
    }

    @Test
    @DisplayName("pipeline fields are checked up to the first reshaping stage, including $group's references")
    void pipelineFields() {
        assertThat(check("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$status\", \"n\": {\"$sum\": 1}}}, {\"$match\": {\"n\": {\"$gt\": 2}}}])"))
                .isEmpty();
        assertThat(check("db.orders.aggregate([{\"$group\": {\"_id\": \"$nope\"}}])"))
                .hasValueSatisfying(m -> assertThat(m).contains("'nope'"));
    }

    @Test
    @DisplayName("a $lookup's collection, local field and foreign field are all checked")
    void lookupChecked() {
        String valid = "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": "
                + "\"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])";
        assertThat(check(valid)).isEmpty();
        assertThat(check(valid.replace("\"customers\"", "\"clients\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'clients'"));
        assertThat(check(valid.replace("\"customer_id\"", "\"client_id\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'client_id'").contains("'orders'"));
        assertThat(check(valid.replace("\"foreignField\": \"_id\"", "\"foreignField\": \"code\""))).hasValueSatisfying(m ->
                assertThat(m).contains("'code'").contains("'customers'"));
    }

    @Test
    @DisplayName("$out and $merge are refused before anything reaches MongoDB")
    void writeStagesAreRefused() {
        assertThat(check("db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$out\": \"copy\"}])"))
                .hasValueSatisfying(m -> assertThat(m).contains("never runs a write"));
        assertThat(check("db.orders.aggregate([{\"$merge\": {\"into\": \"copy\"}}])")).isPresent();
    }

    @Test
    @DisplayName("a path below a field declared as an untyped object is accepted")
    void belowAnObjectField() {
        activeSchema.publish(new SchemaSnapshot("u", "logs.json", "querysense_uploads",
                List.of(new KnownSchema.Table("logs", List.of("_id", "meta"))), List.of(), Map.of(),
                Instant.now(), Dialect.MONGODB, List.of(),
                Map.of("logs", Map.of("_id", "objectId", "meta", "object"))));

        assertThat(check("db.logs.find({\"meta.source\": \"web\"})")).isEmpty();
        assertThat(check("db.customers.find({})")).isPresent();
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryFieldsTest,MongoPreflightValidatorTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`MongoQueryShape.java`:

```java
package com.querysense.backend.dto;

import com.querysense.backend.schema.IndexSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The fields a MongoDB query matches exactly, sorts on and ranges over, for the index advisor. */
public record MongoQueryShape(
        String collection,
        List<String> equalityFields,
        List<IndexSpec.KeyPart> sortKeys,
        List<String> rangeFields,
        List<String> unanchoredRegexFields,
        boolean groupsWithoutIndexedMatch) {

    public List<IndexSpec.KeyPart> esrKeys() {
        return keys(true);
    }

    public List<IndexSpec.KeyPart> sortIndexKeys() {
        return keys(false);
    }

    private List<IndexSpec.KeyPart> keys(boolean withRange) {
        Map<String, IndexSpec.KeyPart> ordered = new LinkedHashMap<>();
        equalityFields.forEach(f -> ordered.putIfAbsent(f, new IndexSpec.KeyPart(f, 1)));
        sortKeys.forEach(k -> ordered.putIfAbsent(k.column(), k));
        if (withRange) {
            rangeFields.forEach(f -> ordered.putIfAbsent(f, new IndexSpec.KeyPart(f, 1)));
        }
        return List.copyOf(ordered.values());
    }
}
```

`MongoQueryFields.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.IndexSpec;
import org.bson.BsonRegularExpression;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads field paths out of a parsed MongoDB query: every one for preflight, and the leading filter and sort, classified, for the advisor. */
public final class MongoQueryFields {

    public record Lookup(String from, String localField, String foreignField, boolean fromBase) {
    }

    private static final Set<String> SHAPE_PRESERVING = Set.of("$match", "$sort", "$limit", "$skip");
    private static final Set<String> EXPRESSION_STAGES = Set.of(
            "$group", "$project", "$addFields", "$set", "$bucket", "$sortByCount");
    private static final Set<String> WRITE_STAGES = Set.of("$out", "$merge");
    private static final Set<String> LOGICAL = Set.of("$and", "$or", "$nor");
    private static final Set<String> RANGE_OPERATORS = Set.of(
            "$gt", "$gte", "$lt", "$lte", "$ne", "$nin", "$exists", "$type", "$not");

    private MongoQueryFields() {
    }

    public static boolean writesOutput(MongoQuery query) {
        return pipelineOf(query).stream().anyMatch(stage -> WRITE_STAGES.contains(stageName(stage)));
    }

    public static List<String> referencedPaths(MongoQuery query) {
        Set<String> paths = new LinkedHashSet<>();
        if (query.operation() != MongoQuery.Operation.AGGREGATE) {
            filterPaths(query.filter(), "", paths);
            if (query.projection() != null) {
                query.projection().forEach((key, value) -> {
                    if (included(value)) {
                        paths.add(key);
                    }
                });
            }
            if (query.sort() != null) {
                paths.addAll(query.sort().keySet());
            }
            return List.copyOf(paths);
        }
        for (Document stage : pipelineOf(query)) {
            String name = stageName(stage);
            Object body = stage.get(name);
            if ("$match".equals(name)) {
                filterPaths(asDocument(body), "", paths);
            } else if ("$sort".equals(name)) {
                paths.addAll(asDocument(body).keySet());
            } else if (!"$limit".equals(name) && !"$skip".equals(name)) {
                if (EXPRESSION_STAGES.contains(name)) {
                    expressionPaths(body, paths);
                    if ("$project".equals(name)) {
                        asDocument(body).forEach((key, value) -> {
                            if (included(value)) {
                                paths.add(key);
                            }
                        });
                    }
                } else if ("$unwind".equals(name)) {
                    expressionPaths(body instanceof Document d ? d.get("path") : body, paths);
                }
                break;
            }
        }
        return List.copyOf(paths);
    }

    public static List<Lookup> lookups(MongoQuery query) {
        List<Lookup> out = new ArrayList<>();
        boolean fromBase = true;
        for (Document stage : pipelineOf(query)) {
            String name = stageName(stage);
            if ("$lookup".equals(name) && stage.get(name) instanceof Document body) {
                out.add(new Lookup(text(body.get("from")), text(body.get("localField")),
                        text(body.get("foreignField")), fromBase));
            }
            if (!SHAPE_PRESERVING.contains(name)) {
                fromBase = false;
            }
        }
        return out;
    }

    public static MongoQueryShape shape(MongoQuery query, Set<String> leadingIndexFields) {
        List<Document> filters = new ArrayList<>();
        Document sort = null;
        boolean groupsWithoutIndexedMatch = false;
        if (query.operation() == MongoQuery.Operation.AGGREGATE) {
            boolean leading = true;
            boolean indexedMatch = false;
            boolean seenGroup = false;
            for (Document stage : pipelineOf(query)) {
                String name = stageName(stage);
                if ("$group".equals(name) && !seenGroup) {
                    seenGroup = true;
                    groupsWithoutIndexedMatch = !indexedMatch;
                }
                if ("$match".equals(name)) {
                    Document match = asDocument(stage.get(name));
                    if (!seenGroup && match.keySet().stream().anyMatch(leadingIndexFields::contains)) {
                        indexedMatch = true;
                    }
                    if (leading) {
                        filters.add(match);
                    }
                } else if ("$sort".equals(name)) {
                    if (leading && sort == null) {
                        sort = asDocument(stage.get(name));
                    }
                } else if (!SHAPE_PRESERVING.contains(name)) {
                    leading = false;
                }
            }
        } else {
            if (query.filter() != null) {
                filters.add(query.filter());
            }
            sort = query.sort();
        }

        Set<String> equality = new LinkedHashSet<>();
        Set<String> range = new LinkedHashSet<>();
        Set<String> regex = new LinkedHashSet<>();
        filters.forEach(f -> classify(f, equality, range, regex));
        List<IndexSpec.KeyPart> sortKeys = new ArrayList<>();
        if (sort != null) {
            for (Map.Entry<String, Object> entry : sort.entrySet()) {
                if (entry.getValue() instanceof Number direction) {
                    sortKeys.add(new IndexSpec.KeyPart(entry.getKey(), direction.intValue() < 0 ? -1 : 1));
                }
            }
        }
        return new MongoQueryShape(query.collection(), List.copyOf(equality), List.copyOf(sortKeys),
                List.copyOf(range), List.copyOf(regex), groupsWithoutIndexedMatch);
    }

    private static void classify(Document filter, Set<String> equality, Set<String> range, Set<String> regex) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("$and".equals(key) && value instanceof List<?> clauses) {
                clauses.stream().filter(Document.class::isInstance).map(Document.class::cast)
                        .forEach(c -> classify(c, equality, range, regex));
                continue;
            }
            if (key.startsWith("$")) {
                continue;
            }
            if (value instanceof Pattern || value instanceof BsonRegularExpression) {
                (anchored(value) ? range : regex).add(key);
            } else if (value instanceof Document ops && ops.keySet().stream().anyMatch(k -> k.startsWith("$"))) {
                if (ops.containsKey("$regex")) {
                    (anchored(ops.get("$regex")) ? range : regex).add(key);
                } else if (ops.containsKey("$eq") || ops.containsKey("$in")) {
                    equality.add(key);
                } else if (ops.keySet().stream().anyMatch(RANGE_OPERATORS::contains)) {
                    range.add(key);
                }
            } else {
                equality.add(key);
            }
        }
    }

    private static boolean anchored(Object regex) {
        String pattern = regex instanceof Pattern p ? p.pattern()
                : regex instanceof BsonRegularExpression r ? r.getPattern()
                : String.valueOf(regex);
        return pattern.startsWith("^") || pattern.startsWith("\\A");
    }

    private static void filterPaths(Document filter, String prefix, Set<String> paths) {
        if (filter == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            if (LOGICAL.contains(key) && entry.getValue() instanceof List<?> clauses) {
                clauses.stream().filter(Document.class::isInstance).map(Document.class::cast)
                        .forEach(c -> filterPaths(c, prefix, paths));
            } else if (!key.startsWith("$")) {
                String path = prefix + key;
                paths.add(path);
                if (entry.getValue() instanceof Document ops && ops.get("$elemMatch") instanceof Document inner) {
                    filterPaths(inner, path + ".", paths);
                }
            }
        }
    }

    private static void expressionPaths(Object value, Set<String> paths) {
        if (value instanceof String s && s.startsWith("$") && !s.startsWith("$$")) {
            paths.add(s.substring(1));
        } else if (value instanceof Document document) {
            document.values().forEach(v -> expressionPaths(v, paths));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> expressionPaths(v, paths));
        }
    }

    private static boolean included(Object value) {
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.TRUE.equals(value);
    }

    private static List<Document> pipelineOf(MongoQuery query) {
        return query.operation() == MongoQuery.Operation.AGGREGATE && query.pipeline() != null
                ? query.pipeline()
                : List.of();
    }

    private static String stageName(Document stage) {
        return stage.keySet().stream().findFirst().orElse("");
    }

    private static Document asDocument(Object value) {
        return value instanceof Document document ? document : new Document();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
```

`MongoPreflightValidator.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Catches unknown collections, unknown field paths and write stages before a MongoDB query reaches explain. */
@Component
public class MongoPreflightValidator {

    private final ActiveSchemaProvider activeSchema;

    public MongoPreflightValidator(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public Optional<String> validate(MongoQuery query) {
        if (MongoQueryFields.writesOutput(query)) {
            return Optional.of("This pipeline contains $out or $merge, which write documents. QuerySense plans "
                    + "queries and never runs a write, so it was not sent to MongoDB.");
        }
        Optional<KnownSchema.Table> base = collection(query.collection());
        if (base.isEmpty()) {
            return Optional.of(unknownCollection(query.collection()));
        }
        for (String path : MongoQueryFields.referencedPaths(query)) {
            if (!declares(base.get(), path)) {
                return Optional.of(unknownField(path, query.collection()));
            }
        }
        for (MongoQueryFields.Lookup lookup : MongoQueryFields.lookups(query)) {
            if (lookup.from() == null) {
                continue;
            }
            Optional<KnownSchema.Table> from = collection(lookup.from());
            if (from.isEmpty()) {
                return Optional.of(unknownCollection(lookup.from()));
            }
            if (lookup.fromBase() && lookup.localField() != null && !declares(base.get(), lookup.localField())) {
                return Optional.of(unknownField(lookup.localField(), query.collection()));
            }
            if (lookup.foreignField() != null && !declares(from.get(), lookup.foreignField())) {
                return Optional.of(unknownField(lookup.foreignField(), lookup.from()));
            }
        }
        return Optional.empty();
    }

    private Optional<KnownSchema.Table> collection(String name) {
        return activeSchema.tables(Dialect.MONGODB).stream().filter(t -> t.name().equals(name)).findFirst();
    }

    private boolean declares(KnownSchema.Table table, String rawPath) {
        String path = Arrays.stream(rawPath.split("\\."))
                .filter(s -> !s.matches("\\d+") && !s.startsWith("$"))
                .collect(Collectors.joining("."));
        if (path.isEmpty() || "_id".equals(path) || path.startsWith("_id.")) {
            return true;
        }
        if (table.columns().contains(path) || table.embeddedArrays().contains(path)) {
            return true;
        }
        String prefix = path + ".";
        if (table.columns().stream().anyMatch(c -> c.startsWith(prefix))) {
            return true;
        }
        Map<String, String> types = activeSchema.active(Dialect.MONGODB).fieldTypesFor(table.name());
        return table.columns().stream().anyMatch(c -> path.startsWith(c + ".")
                && ("object".equals(types.get(c)) || "any".equals(types.get(c))));
    }

    private static String unknownCollection(String collection) {
        return "Collection '" + collection + "' is not in the active MongoDB schema — this query references "
                + "a collection that isn't part of the real schema.";
    }

    private static String unknownField(String path, String collection) {
        return "Field '" + path + "' does not exist on '" + collection + "' — this query references a field "
                + "that isn't part of the real schema.";
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoQueryFieldsTest,MongoPreflightValidatorTest`
Expected: PASS, 9 and 8 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): preflight a MongoDB query against the active schema, and read its filter shape

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `MongoExplainExecutor`

**Files:**
- Create: `backend/.../analysis/MongoExplainExecutor.java`
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoExplainExecutorTest.java`

**Interfaces:**
- Consumes: `MongoExplainCommands` (Task 1), `ActiveSchemaProvider`, `MongoUnavailableException` (existing).
- Produces: `@Component public class MongoExplainExecutor` with `MongoExplainExecutor(MongoClient, ActiveSchemaProvider)`, plus:
  - `public Document explain(MongoQuery query)`.
  - `public List<Document> indexes(String collection)`, which returns an empty list when the collection doesn't exist.
  - On timeout or socket failure, both throw `MongoUnavailableException`. A `MongoCommandException` other than "namespace not found" propagates.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoExplainExecutorTest {

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private ListIndexesIterable<Document> listIndexes;
    private ActiveSchemaProvider activeSchema;
    private MongoExplainExecutor executor;
    private final MongoQuery query = new MongoShellParser().parse("db.customers.find({\"city\": \"Mumbai\"})");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        listIndexes = mock(ListIndexesIterable.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        when(collection.listIndexes()).thenReturn(listIndexes);
        activeSchema = new ActiveSchemaProvider();
        executor = new MongoExplainExecutor(client, activeSchema);
    }

    private static MongoCommandException commandError(int code) {
        return new MongoCommandException(new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(code)).append("errmsg", new BsonString("boom")), new ServerAddress());
    }

    @Test
    @DisplayName("explain runs the pinned command on the active Mongo schema's database")
    void explainRunsOnActiveDatabase() {
        Document plan = new Document("ok", 1.0);
        when(database.runCommand(any(Bson.class))).thenReturn(plan);

        assertThat(executor.explain(query)).isSameAs(plan);

        verify(client).getDatabase("querysense");
        ArgumentCaptor<Bson> command = ArgumentCaptor.forClass(Bson.class);
        verify(database).runCommand(command.capture());
        assertThat(command.getValue()).isEqualTo(MongoExplainCommands.explain(query));
    }

    @Test
    @DisplayName("after a Mongo upload, explain goes to the upload database")
    void followsTheActiveSchema() {
        activeSchema.publish(new SchemaSnapshot("u", "x.json", "querysense_uploads", List.of(), List.of(),
                Map.of(), Instant.now(), Dialect.MONGODB, List.of()));
        when(database.runCommand(any(Bson.class))).thenReturn(new Document());

        executor.explain(query);

        verify(client).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("an unreachable server during explain is a 503 naming MongoDB")
    void unreachableExplainIs503() {
        when(database.runCommand(any(Bson.class))).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThatThrownBy(() -> executor.explain(query))
                .isInstanceOf(MongoUnavailableException.class)
                .hasMessageContaining("MongoDB")
                .hasMessageContaining("not run");
    }

    @Test
    @DisplayName("an explain error from the server propagates as itself")
    void commandErrorPropagates() {
        when(database.runCommand(any(Bson.class))).thenThrow(commandError(2));

        assertThatThrownBy(() -> executor.explain(query)).isInstanceOf(MongoCommandException.class);
    }

    @Test
    @DisplayName("indexes come from listIndexes on the named collection")
    @SuppressWarnings("unchecked")
    void indexesAreListed() {
        Document emailIndex = new Document("key", new Document("email", 1)).append("name", "email_1");
        when(listIndexes.into(any())).thenAnswer(inv -> {
            Collection<Document> target = inv.getArgument(0);
            target.add(emailIndex);
            return target;
        });

        assertThat(executor.indexes("customers")).containsExactly(emailIndex);
        verify(database).getCollection("customers");
    }

    @Test
    @DisplayName("a collection missing from the database has no indexes")
    void missingCollectionHasNoIndexes() {
        when(listIndexes.into(any())).thenThrow(commandError(26));

        assertThat(executor.indexes("customers")).isEmpty();
    }

    @Test
    @DisplayName("an unreachable server during listIndexes is a 503 too")
    void unreachableIndexesIs503() {
        when(listIndexes.into(any())).thenThrow(new MongoSocketOpenException("refused", new ServerAddress()));

        assertThatThrownBy(() -> executor.indexes("customers")).isInstanceOf(MongoUnavailableException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainExecutorTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Runs explain and listIndexes against the active MongoDB schema's database; it never runs the query itself. */
@Component
public class MongoExplainExecutor {

    private static final int NAMESPACE_NOT_FOUND = 26;

    private final MongoClient client;
    private final ActiveSchemaProvider activeSchema;

    public MongoExplainExecutor(MongoClient client, ActiveSchemaProvider activeSchema) {
        this.client = client;
        this.activeSchema = activeSchema;
    }

    public Document explain(MongoQuery query) {
        try {
            return database().runCommand(MongoExplainCommands.explain(query));
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw unavailable(e);
        }
    }

    public List<Document> indexes(String collection) {
        try {
            return database().getCollection(collection).listIndexes().into(new ArrayList<>());
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_NOT_FOUND) {
                return List.of();
            }
            throw e;
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw unavailable(e);
        }
    }

    private MongoDatabase database() {
        return client.getDatabase(activeSchema.catalog(Dialect.MONGODB));
    }

    private static MongoUnavailableException unavailable(RuntimeException e) {
        return new MongoUnavailableException("MongoDB is not reachable, so this query's plan could not be read. "
                + "Check that mongod is running at the address in spring.data.mongodb.uri. The query itself "
                + "was not run.", e);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainExecutorTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): run explain and listIndexes on the active Mongo database, 503 when it is down

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `MongoExplainAnalyzer`, with M1 and M2 on real indexes

**Files:**
- Create: `backend/.../analysis/PlanAnalyzer.java`, `backend/.../analysis/MongoExplainAnalyzer.java`
- Modify: `backend/.../dto/QueryAnalysisResult.java` (adds `mongoShape`)
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoExplainAnalyzerTest.java`

**Interfaces:**
- Consumes: Tasks 1–4; `VerdictService` (existing); `MongoShellParser`.
- Produces:
  - `public interface PlanAnalyzer extends DialectAware { QueryAnalysisResult analyze(String query); }`
  - `@Component public class MongoExplainAnalyzer implements PlanAnalyzer` with `MongoExplainAnalyzer(MongoShellParser, MongoPreflightValidator, MongoExplainExecutor, MongoExplainParser, VerdictService)`. Its `dialect()` returns `MONGODB`. It always applies the verdict.
  - `QueryAnalysisResult`: `MongoQueryShape getMongoShape()` (annotated `@JsonIgnore`) and `void setMongoShape(MongoQueryShape)`.
  - M1: `hasUnindexedFilter` is true when an equality or range field is the leading key of no index. `filteredColumn` is the first such field, else the first equality field, else the first range field. `orderByColumn` is the first sort key. M1 warns only when the plan did not scan the collection.
  - M2: `hasUnanchoredRegex` when the shape has unanchored regex fields. The warning text is unchanged from today.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoExplainAnalyzerTest {

    private MongoExplainExecutor executor;
    private MongoExplainAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        executor = mock(MongoExplainExecutor.class);
        when(executor.indexes(anyString())).thenAnswer(inv -> MongoFixtures.indexes(inv.getArgument(0)));
        analyzer = new MongoExplainAnalyzer(new MongoShellParser(),
                new MongoPreflightValidator(new ActiveSchemaProvider()), executor, new MongoExplainParser(),
                new VerdictService(1000L));
    }

    private QueryAnalysisResult analyze(String mongosh, String fixture) {
        when(executor.explain(any(MongoQuery.class))).thenReturn(MongoFixtures.explain(fixture).explain());
        return analyzer.analyze(mongosh);
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(analyzer.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("a scanned, unindexed filter: plan figures, the field named, a verdict, and no second warning")
    void collscanEndToEnd() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-collscan");

        assertThat(result.getDatabaseType()).isEqualTo("MongoDB");
        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.getQueryType()).isEqualTo("FIND");
        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isHasUnindexedFilter()).isTrue();
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getMongoShape().equalityFields()).containsExactly("city");
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.CRITICAL);
        assertThat(result.getWarnings()).noneSatisfy(w -> assertThat(w).contains("no index starts with"));
    }

    @Test
    @DisplayName("an indexed lookup is not an unindexed filter")
    void indexedLookup() {
        QueryAnalysisResult result = analyze("db.customers.find({\"email\": \"nobody@example.com\"})",
                "find-express-ixscan");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.isHasUnindexedFilter()).isFalse();
        assertThat(result.getFilteredColumn()).isEqualTo("email");
    }

    @Test
    @DisplayName("M1 warns about an unindexed field when the plan used a different index")
    void unindexedFieldBesideAnIndex() {
        QueryAnalysisResult result = analyze("db.customers.find({\"email\": \"x\", \"city\": \"Mumbai\"})",
                "find-express-ixscan");

        assertThat(result.isHasUnindexedFilter()).isTrue();
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w)
                .contains("'city'").contains("no index starts with"));
    }

    @Test
    @DisplayName("M2 flags an unanchored regex with today's wording")
    void unanchoredRegex() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": {\"$regex\": \"mum\"}})", "find-collscan");

        assertThat(result.isHasUnanchoredRegex()).isTrue();
        assertThat(result.getWarnings()).contains("Unanchored $regex pattern detected — "
                + "prefix with '^' to allow index usage, if an index exists on this field.");
    }

    @Test
    @DisplayName("the sort field is the order-by column")
    void sortField() {
        assertThat(analyze("db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})", "find-sort")
                .getOrderByColumn()).isEqualTo("name");
    }

    @Test
    @DisplayName("count and delete keep their operation as the query type")
    void operationTypes() {
        assertThat(analyze("db.orders.countDocuments({\"status\": \"PAID\"})", "count").getQueryType())
                .isEqualTo("COUNT");
        assertThat(analyze("db.orders.deleteMany({\"status\": \"CANCELLED\"})", "delete").getQueryType())
                .isEqualTo("DELETE");
    }

    @Test
    @DisplayName("unreadable text fails with the parser's reason, names the collection, and never reaches MongoDB")
    void unparseable() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({city: )");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.getWarnings()).singleElement()
                .satisfies(w -> assertThat(w).startsWith("This MongoDB query could not be read"));
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a preflight refusal fails the analysis before MongoDB is asked anything")
    void preflightRefusal() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"age\": 30})");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement().satisfies(w -> assertThat(w).contains("'age'"));
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a pipeline that writes never reaches MongoDB")
    void writePipelineNeverReachesMongo() {
        QueryAnalysisResult result = analyzer.analyze("db.orders.aggregate([{\"$out\": \"copy\"}])");

        assertThat(result.isExplainFailed()).isTrue();
        verify(executor, never()).explain(any());
        verify(executor, never()).indexes(anyString());
    }

    @Test
    @DisplayName("a collection declared but missing from mongod is UNKNOWN, naming it")
    void missingCollectionIsUnknown() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-missing-collection");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("'customers'"));
    }

    @Test
    @DisplayName("a server error while planning fails the analysis with MongoDB's message")
    void commandError() {
        when(executor.explain(any(MongoQuery.class))).thenThrow(new MongoCommandException(
                new BsonDocument("ok", new BsonDouble(0)).append("code", new BsonInt32(2))
                        .append("errmsg", new BsonString("unknown operator: $bogus")), new ServerAddress()));

        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w)
                .isEqualTo("MongoDB could not plan this query: unknown operator: $bogus"));
    }

    @Test
    @DisplayName("an unreachable MongoDB propagates as a 503")
    void unreachableMongoPropagates() {
        when(executor.indexes(anyString())).thenThrow(new MongoUnavailableException("MongoDB is not reachable",
                new MongoTimeoutException("Timed out")));

        assertThatThrownBy(() -> analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})"))
                .isInstanceOf(MongoUnavailableException.class);
    }

    @Test
    @DisplayName("the analysis result never serialises the shape")
    void shapeIsNotSerialised() throws Exception {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-collscan");

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);

        assertThat(json).doesNotContain("mongoShape").contains("executionMillis");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainAnalyzerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`QueryAnalysisResult`: add `import com.fasterxml.jackson.annotation.JsonIgnore;` and the field

```java
    private MongoQueryShape mongoShape;
```

with

```java
    @JsonIgnore
    public MongoQueryShape getMongoShape() { return mongoShape; }
    public void setMongoShape(MongoQueryShape mongoShape) { this.mongoShape = mongoShape; }
```

`PlanAnalyzer.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.dto.QueryAnalysisResult;

/** Analyses one dialect's query into plan figures, warnings and a verdict. */
public interface PlanAnalyzer extends DialectAware {
    QueryAnalysisResult analyze(String query);
}
```

`MongoExplainAnalyzer.java`:

```java
package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Analyses a MongoDB query: parse, preflight, the real indexes and explain plan, then the verdict. */
@Component
public class MongoExplainAnalyzer implements PlanAnalyzer {

    private static final Pattern COLLECTION_PATTERN = Pattern.compile("\\bdb\\s*\\.\\s*(\\w+)\\s*\\.");

    private final MongoShellParser shellParser;
    private final MongoPreflightValidator preflight;
    private final MongoExplainExecutor executor;
    private final MongoExplainParser explainParser;
    private final VerdictService verdictService;

    public MongoExplainAnalyzer(MongoShellParser shellParser,
                                MongoPreflightValidator preflight,
                                MongoExplainExecutor executor,
                                MongoExplainParser explainParser,
                                VerdictService verdictService) {
        this.shellParser = shellParser;
        this.preflight = preflight;
        this.executor = executor;
        this.explainParser = explainParser;
        this.verdictService = verdictService;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public QueryAnalysisResult analyze(String text) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType(Dialect.MONGODB.label());
        result.setQueryType("FIND");
        result.setTableName(collectionFrom(text));

        MongoQuery query;
        try {
            query = shellParser.parse(text);
        } catch (MongoShellParseException e) {
            return failed(result, "This MongoDB query could not be read: " + e.getMessage());
        }
        result.setTableName(query.collection());
        result.setQueryType(query.operation().name());

        Optional<String> refused = preflight.validate(query);
        if (refused.isPresent()) {
            return failed(result, refused.get());
        }

        try {
            Set<String> leading = leadingKeys(executor.indexes(query.collection()));
            MongoQueryShape shape = MongoQueryFields.shape(query, leading);
            result.setMongoShape(shape);
            flagFilter(shape, leading, result);
            explainParser.read(executor.explain(query), query, result);
        } catch (MongoCommandException e) {
            return failed(result, "MongoDB could not plan this query: " + e.getErrorMessage());
        }

        if (result.isHasUnindexedFilter() && !result.isHasFullTableScan() && !result.isExplainFailed()) {
            result.addWarning("Filtering on '" + result.getFilteredColumn() + "', which no index starts with on '"
                    + query.collection() + "'. MongoDB filters it after reading each document.");
        }
        verdictService.apply(result);
        return result;
    }

    private static void flagFilter(MongoQueryShape shape, Set<String> leading, QueryAnalysisResult result) {
        List<String> filtered = Stream.concat(shape.equalityFields().stream(), shape.rangeFields().stream()).toList();
        Optional<String> unindexed = filtered.stream().filter(f -> !leading.contains(f)).findFirst();
        result.setHasUnindexedFilter(unindexed.isPresent());
        result.setFilteredColumn(unindexed.orElse(filtered.isEmpty() ? null : filtered.get(0)));
        result.setOrderByColumn(shape.sortKeys().isEmpty() ? null : shape.sortKeys().get(0).column());
        if (!shape.unanchoredRegexFields().isEmpty()) {
            result.setHasUnanchoredRegex(true);
            result.addWarning("Unanchored $regex pattern detected — "
                    + "prefix with '^' to allow index usage, if an index exists on this field.");
        }
    }

    private static Set<String> leadingKeys(List<Document> indexes) {
        return indexes.stream()
                .map(i -> i.get("key") instanceof Document key ? key.keySet().stream().findFirst().orElse(null) : null)
                .filter(k -> k != null)
                .collect(Collectors.toSet());
    }

    private QueryAnalysisResult failed(QueryAnalysisResult result, String reason) {
        result.addWarning(reason);
        result.setExplainFailed(true);
        verdictService.apply(result);
        return result;
    }

    private static String collectionFrom(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = COLLECTION_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoExplainAnalyzerTest`
Expected: PASS, 14 tests. `MongoSimpleAnalyzer` still exists and is still wired in. Task 6 swaps it out.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): MongoExplainAnalyzer — preflight, real indexes and explain, with M1 and M2 on the parsed query

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Analysis routes by dialect; `MongoSimpleAnalyzer` goes

**Files:**
- Create: `backend/.../analysis/MySqlPlanAnalyzer.java`
- Modify: `backend/.../service/impl/QueryAnalysisServiceImpl.java`
- Delete: `backend/.../analysis/MongoSimpleAnalyzer.java`, `backend/src/test/java/com/querysense/backend/analysis/MongoSimpleAnalyzerTest.java`, `backend/src/test/java/com/querysense/backend/analysis/MongoCollectionNameTest.java`
- Modify test: `backend/src/test/java/com/querysense/backend/service/impl/SchemaAdvisorServiceImplTest.java` (`lookupOnlyPipelineGetsNoIndex` and its imports)
- Test: `backend/src/test/java/com/querysense/backend/analysis/MySqlPlanAnalyzerTest.java`, `backend/src/test/java/com/querysense/backend/service/impl/QueryAnalysisServiceImplTest.java`

**Interfaces:**
- Consumes: `PlanAnalyzer`, `MongoExplainAnalyzer` (Task 5), `DialectRegistry`.
- Produces:
  - `@Component public class MySqlPlanAnalyzer implements PlanAnalyzer` with `MySqlPlanAnalyzer(SqlStructureParser, MySqlExplainAnalyzer, SqlPreflightValidator, VerdictService)`. Its body is today's SQL branch of `QueryAnalysisServiceImpl.analyze`, unchanged.
  - `QueryAnalysisServiceImpl(List<PlanAnalyzer>)`. `analyze(query, databaseType)` resolves `Dialect.from(databaseType)`, so an unknown type is an `IllegalArgumentException`, which is a 400.

- [ ] **Step 1: Write the failing tests**

`MySqlPlanAnalyzerTest.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MySqlPlanAnalyzerTest {

    private MySqlExplainAnalyzer explainAnalyzer;
    private SqlPreflightValidator preflight;
    private MySqlPlanAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        explainAnalyzer = mock(MySqlExplainAnalyzer.class);
        preflight = mock(SqlPreflightValidator.class);
        when(preflight.validate(anyString(), anyString())).thenReturn(Optional.empty());
        analyzer = new MySqlPlanAnalyzer(new SqlStructureParser(), explainAnalyzer, preflight, new VerdictService(1000L));
    }

    @Test
    @DisplayName("serves MySQL")
    void servesMySql() {
        assertThat(analyzer.dialect()).isEqualTo(Dialect.MYSQL);
    }

    @Test
    @DisplayName("a statement that is not a SELECT returns its structure with no verdict and no EXPLAIN")
    void nonSelectIsStructuralOnly() {
        QueryAnalysisResult result = analyzer.analyze("DELETE FROM customers WHERE id = 1");

        assertThat(result.getVerdict()).isNull();
        verifyNoInteractions(explainAnalyzer);
    }

    @Test
    @DisplayName("a preflight refusal is a failed analysis with an UNKNOWN verdict")
    void preflightRefusal() {
        when(preflight.validate(anyString(), anyString())).thenReturn(Optional.of("Column 'age' does not exist"));

        QueryAnalysisResult result = analyzer.analyze("SELECT age FROM customers");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).contains("Column 'age' does not exist");
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        verifyNoInteractions(explainAnalyzer);
    }

    @Test
    @DisplayName("a SELECT is explained, and the structural columns are merged in")
    void selectIsExplained() {
        QueryAnalysisResult explained = new QueryAnalysisResult();
        explained.setDatabaseType("MySQL");
        when(explainAnalyzer.analyze(anyString(), anyString())).thenReturn(explained);

        QueryAnalysisResult result = analyzer.analyze("SELECT * FROM customers WHERE city = 'Pune' ORDER BY name");

        assertThat(result).isSameAs(explained);
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getOrderByColumn()).isEqualTo("name");
        assertThat(result.getVerdict()).isNotNull();
    }
}
```

`QueryAnalysisServiceImplTest.java`:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryAnalysisServiceImplTest {

    private record Stub(Dialect dialect, String label) implements PlanAnalyzer {
        @Override
        public QueryAnalysisResult analyze(String query) {
            QueryAnalysisResult result = new QueryAnalysisResult();
            result.setDatabaseType(label);
            result.setTableName(query);
            return result;
        }
    }

    private final QueryAnalysisServiceImpl service = new QueryAnalysisServiceImpl(List.of(
            new Stub(Dialect.MYSQL, "mysql"), new Stub(Dialect.MONGODB, "mongo")));

    @Test
    @DisplayName("each database type reaches its own analyzer, whatever its case")
    void routesByDialect() {
        assertThat(service.analyze("q1", "MySQL").getDatabaseType()).isEqualTo("mysql");
        assertThat(service.analyze("q2", "MongoDB").getDatabaseType()).isEqualTo("mongo");
        assertThat(service.analyze("q3", "mongodb").getTableName()).isEqualTo("q3");
    }

    @Test
    @DisplayName("an unknown database type is refused, which the API returns as a 400")
    void unknownTypeRefused() {
        assertThatThrownBy(() -> service.analyze("q", "Oracle")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a dialect with no analyzer fails at startup")
    void missingDialectFailsFast() {
        assertThatThrownBy(() -> new QueryAnalysisServiceImpl(List.of(new Stub(Dialect.MYSQL, "mysql"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MySqlPlanAnalyzerTest,QueryAnalysisServiceImplTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`MySqlPlanAnalyzer.java`: the body of `analyze` and `mergeResults` is moved from `QueryAnalysisServiceImpl` character for character, without the MongoDB branch:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Analyses a MySQL query: its structure, the preflight checks, then EXPLAIN and the verdict. */
@Component
public class MySqlPlanAnalyzer implements PlanAnalyzer {

    private final SqlStructureParser sqlStructureParser;
    private final MySqlExplainAnalyzer mySqlExplainAnalyzer;
    private final SqlPreflightValidator sqlPreflightValidator;
    private final VerdictService verdictService;

    public MySqlPlanAnalyzer(SqlStructureParser sqlStructureParser,
                             MySqlExplainAnalyzer mySqlExplainAnalyzer,
                             SqlPreflightValidator sqlPreflightValidator,
                             VerdictService verdictService) {
        this.sqlStructureParser = sqlStructureParser;
        this.mySqlExplainAnalyzer = mySqlExplainAnalyzer;
        this.sqlPreflightValidator = sqlPreflightValidator;
        this.verdictService = verdictService;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery) {
        QueryAnalysisResult structuralResult = sqlStructureParser.parse(sqlOrMongoQuery);

        if (!"SELECT".equals(structuralResult.getQueryType())
                || structuralResult.getTableName() == null) {
            return structuralResult;
        }

        Optional<String> preflightFailure =
                sqlPreflightValidator.validate(sqlOrMongoQuery, structuralResult.getTableName());
        if (preflightFailure.isPresent()) {
            structuralResult.addWarning(preflightFailure.get());
            structuralResult.setExplainFailed(true);
            verdictService.apply(structuralResult);
            return structuralResult;
        }

        QueryAnalysisResult explainResult = mySqlExplainAnalyzer.analyze(
                sqlOrMongoQuery, structuralResult.getTableName());

        QueryAnalysisResult merged = mergeResults(structuralResult, explainResult);

        verdictService.apply(merged);
        return merged;
    }

    private QueryAnalysisResult mergeResults(QueryAnalysisResult structural,
                                             QueryAnalysisResult explain) {
        explain.getWarnings().addAll(0, structural.getWarnings());
        explain.setFilteredColumn(structural.getFilteredColumn());
        explain.setOrderByColumn(structural.getOrderByColumn());
        return explain;
    }
}
```

`QueryAnalysisServiceImpl.java`, replacing the whole file:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.service.QueryAnalysisService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Hands a query to the plan analyzer of its database type. */
@Service
public class QueryAnalysisServiceImpl implements QueryAnalysisService {

    private final DialectRegistry<PlanAnalyzer> analyzers;

    public QueryAnalysisServiceImpl(List<PlanAnalyzer> analyzers) {
        this.analyzers = new DialectRegistry<>(analyzers, "plan analyzer");
    }

    @Override
    public QueryAnalysisResult analyze(String sqlOrMongoQuery, String databaseType) {
        return analyzers.get(Dialect.from(databaseType)).analyze(sqlOrMongoQuery);
    }
}
```

Delete `MongoSimpleAnalyzer.java`, `MongoSimpleAnalyzerTest.java` and `MongoCollectionNameTest.java`. Their behaviours are covered by `MongoExplainAnalyzerTest` (`unparseable` names the collection from the text; `operationTypes`; `unanchoredRegex`) and `MongoQueryFieldsTest`.

In `SchemaAdvisorServiceImplTest`, replace `lookupOnlyPipelineGetsNoIndex` with the version below. Then remove the imports of `MongoSimpleAnalyzer` and `MongoShellParser`.

```java
    @Test
    @DisplayName("a rule-engine $lookup pipeline with no filter gets no createIndex recommendation")
    void lookupOnlyPipelineGetsNoIndex() {
        QueryAnalysisResult analysed = base();
        analysed.setDatabaseType("MongoDB");
        analysed.setTableName("customers");
        analysed.setQueryType("AGGREGATE");

        assertThat(advisor.recommend(analysed)).isEmpty();
    }
```

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green. That includes `BackendApplicationTests`, whose context must find exactly one `PlanAnalyzer` per dialect.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(analysis): route analysis by dialect; MongoDB gets a real explain, MongoSimpleAnalyzer is retired

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: The verdict speaks MongoDB

**Files:**
- Modify: `backend/.../analysis/VerdictService.java`
- Modify test: `backend/src/test/java/com/querysense/backend/analysis/VerdictServiceTest.java` (add tests only)

**Interfaces:**
- Consumes: `QueryAnalysisResult.getDatabaseType()`, `Dialect.from`.
- Produces: `evaluate(result)` picks its words from the result's dialect. A null or blank `databaseType` means MySQL. The MySQL sentences are unchanged, character for character. V1–V9 keep their order and conditions.

- [ ] **Step 1: Write the failing tests**

Add to `VerdictServiceTest`:

```java
    private TableAnalysis mongoTable(String name, String stage, Long docs, Double filtered,
                                     String possibleKeys, String keyUsed) {
        TableAnalysis t = new TableAnalysis();
        t.setTableName(name);
        t.setAccessType(stage);
        t.setEstimatedRows(docs);
        t.setFiltered(filtered);
        t.setPossibleKeys(possibleKeys);
        t.setKeyUsed(keyUsed);
        t.setFullTableScan("COLLSCAN".equals(stage));
        t.setUsesIndex(keyUsed != null);
        return t;
    }

    private QueryAnalysisResult mongoResultWith(TableAnalysis... tables) {
        QueryAnalysisResult result = resultWith(tables);
        result.setDatabaseType("MongoDB");
        result.setQueryType("FIND");
        return result;
    }

    @Test
    @DisplayName("MySQL wording is unchanged, character for character")
    void mysqlWordingUnchanged() {
        QueryAnalysisResult failed = resultWith();
        failed.setExplainFailed(true);
        assertThat(service.evaluate(failed).getHeadline())
                .isEqualTo("MySQL couldn't produce an execution plan for this query.");

        assertThat(service.evaluate(resultWith(table("orders", "ALL", 50_000L, 10.0, null, null))).getHeadline())
                .isEqualTo("Reads the whole orders table — about 50,000 rows — then discards 90% of what it read.");

        QueryAnalysisResult sorted = resultWith(table("orders", "range", 5_000L, 30.0, null, "idx_status"));
        sorted.setUsesFilesort(true);
        AnalysisVerdict sortVerdict = service.evaluate(sorted);
        assertThat(sortVerdict.getHeadline()).isEqualTo("MySQL has to sort roughly 5,000 rows after reading them.");
        assertThat(sortVerdict.getDetail()).isEqualTo("No index supplied the rows in the order this query needs, "
                + "so the ordering or grouping work happens as a separate step.");

        AnalysisVerdict indexed = service.evaluate(resultWith(table("customers", "ref", 12L, 100.0, "idx_city", "idx_city")));
        assertThat(indexed.getHeadline()).isEqualTo("Uses the idx_city index to find rows instead of reading the whole table.");
        assertThat(indexed.getDetail()).isEqualTo("About 12 rows are expected to be read.");

        AnalysisVerdict ignored = service.evaluate(resultWith(table("orders", "index", 500L, 30.0, "idx_status", null)));
        assertThat(ignored.getDetail()).isEqualTo("MySQL only uses an index when it estimates that doing so is "
                + "cheaper. On a small table, or for a query that matches most rows, reading the table directly "
                + "genuinely is cheaper.");
    }

    @Test
    @DisplayName("MongoDB verdicts speak of collections, documents and MongoDB")
    void mongoWording() {
        QueryAnalysisResult failed = mongoResultWith();
        failed.setExplainFailed(true);
        assertThat(service.evaluate(failed).getHeadline())
                .isEqualTo("MongoDB couldn't produce an execution plan for this query.");

        AnalysisVerdict wasteful = service.evaluate(mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 10.0, null, null)));
        assertThat(wasteful.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(wasteful.getHeadline()).isEqualTo(
                "Reads the whole customers collection — about 2,000 documents — then discards 90% of what it read.");
        assertThat(wasteful.getDetail()).isEqualTo("MongoDB reported no index it could have used for this collection.");

        AnalysisVerdict indexed = service.evaluate(mongoResultWith(mongoTable("customers", "EXPRESS_IXSCAN", 12L, 100.0, null, "email_1")));
        assertThat(indexed.getHeadline())
                .isEqualTo("Uses the email_1 index to find documents instead of reading the whole collection.");
        assertThat(indexed.getDetail()).isEqualTo("About 12 documents were examined.");

        AnalysisVerdict ignored = service.evaluate(mongoResultWith(mongoTable("orders", "FETCH", 500L, 30.0, "status_1", null)));
        assertThat(ignored.getHeadline()).isEqualTo("An index on orders was available, but MongoDB chose not to use it.");
    }

    @Test
    @DisplayName("MongoDB's in-memory sort and spilled group read as such")
    void mongoSortAndGroup() {
        QueryAnalysisResult sorted = mongoResultWith(mongoTable("customers", "IXSCAN", 5_000L, 30.0, null, "city_1"));
        sorted.setUsesFilesort(true);
        assertThat(service.evaluate(sorted).getHeadline())
                .isEqualTo("MongoDB has to sort roughly 5,000 documents in memory after reading them.");

        QueryAnalysisResult grouped = mongoResultWith(mongoTable("orders", "IXSCAN", 5_000L, 30.0, null, "status_1"));
        grouped.setUsesTemporaryTable(true);
        AnalysisVerdict groupVerdict = service.evaluate(grouped);
        assertThat(groupVerdict.getHeadline())
                .isEqualTo("MongoDB has to group roughly 5,000 documents, and the grouping spilled to disk.");
        assertThat(groupVerdict.getDetail()).isEqualTo("No index supplied the documents in the order this query "
                + "needs, so the sorting or grouping work happens as a separate step.");
    }

    @Test
    @DisplayName("a Mongo scan with no discard figure (count, delete, a pushed-down group) is not graded as waste")
    void mongoScanWithoutDiscardFigureIsNotCritical() {
        AnalysisVerdict verdict = service.evaluate(mongoResultWith(mongoTable("orders", "COLLSCAN", 2_000L, null, null, null)));

        assertThat(verdict.getSeverity()).isEqualTo(Severity.WARNING);
        assertThat(verdict.getHeadline()).isEqualTo("Reads the whole orders collection — about 2,000 documents.");
    }

    @Test
    @DisplayName("no MongoDB verdict says table, rows or MySQL")
    void mongoNeverSaysSqlWords() {
        QueryAnalysisResult sorted = mongoResultWith(mongoTable("customers", "IXSCAN", 5_000L, 30.0, null, "city_1"));
        sorted.setUsesFilesort(true);
        List<QueryAnalysisResult> cases = List.of(
                mongoResultWith(),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 10.0, null, null)),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 2_000L, 95.0, null, null)),
                mongoResultWith(mongoTable("customers", "COLLSCAN", 80L, 10.0, null, null)),
                mongoResultWith(mongoTable("orders", "FETCH", 500L, 30.0, "status_1", null)),
                mongoResultWith(mongoTable("customers", "IXSCAN", 12L, 100.0, null, "email_1")),
                sorted);

        for (QueryAnalysisResult result : cases) {
            AnalysisVerdict verdict = service.evaluate(result);
            String words = (verdict.getHeadline() + " " + (verdict.getDetail() == null ? "" : verdict.getDetail()))
                    .toLowerCase();
            assertThat(words).as(verdict.getHeadline())
                    .doesNotContain(" table").doesNotContain(" rows").doesNotContain("mysql");
        }
    }
```

- [ ] **Step 2: Run to verify the new Mongo tests fail**

Run: `cd backend && ./mvnw test -Dtest=VerdictServiceTest`
Expected: `mysqlWordingUnchanged` passes, because it pins today's text. The four Mongo tests fail with MySQL wording such as "MySQL couldn't produce…" or "orders table".

- [ ] **Step 3: Implement**

In `VerdictService`, add `import com.querysense.backend.dialect.Dialect;` and the record below inside the class:

```java
    private record Words(String engine, String table, String rows, boolean mongo) {
        static final Words MYSQL = new Words("MySQL", "table", "rows", false);
        static final Words MONGODB = new Words("MongoDB", "collection", "documents", true);
    }

    private static Words wordsFor(QueryAnalysisResult result) {
        String type = result.getDatabaseType();
        return type != null && !type.isBlank() && Dialect.from(type) == Dialect.MONGODB ? Words.MONGODB : Words.MYSQL;
    }
```

Replace `evaluate`, `secondaryDetail` and `displayNameOf` with:

```java
    public AnalysisVerdict evaluate(QueryAnalysisResult result) {
        Words w = wordsFor(result);
        if (result.isExplainFailed()) {
            return new AnalysisVerdict(Severity.UNKNOWN, "Couldn't analyze",
                    w.engine() + " couldn't produce an execution plan for this query.",
                    "The specific reason is listed with the warnings below. No metrics are shown, "
                            + "because none were measured — an empty plan is not a clean one.");
        }

        List<TableAnalysis> tables = result.getTables();
        if (tables == null || tables.isEmpty()) {
            return new AnalysisVerdict(Severity.INFO, "Nothing to read",
                    w.engine() + " can answer this query without reading any " + w.table() + ".",
                    "The optimiser resolved it from the query itself, so there is no work to reduce.");
        }

        TableAnalysis worstScan = worstFullScan(tables);
        TableAnalysis ignoredIndex = tableWithIgnoredIndex(tables);

        if (worstScan != null && isLarge(worstScan)) {
            Double filtered = worstScan.getFiltered();
            String name = displayNameOf(worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());

            if (filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT) {
                return new AnalysisVerdict(Severity.CRITICAL, "Needs attention",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows()
                                + " — then discards " + formatPercent(100.0 - filtered) + " of what it read.",
                        secondaryDetail(ignoredIndex, worstScan, w));
            }

            if (filtered == null) {
                return new AnalysisVerdict(Severity.WARNING, "Could be better",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows() + ".",
                        secondaryDetail(ignoredIndex, worstScan, w));
            }
        }

        if (ignoredIndex != null) {
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    "An index on " + displayNameOf(ignoredIndex) + " was available, but " + w.engine()
                            + " chose not to use it.",
                    w.engine() + " only uses an index when it estimates that doing so is cheaper. On a small "
                            + w.table() + ", or for a query that matches most " + w.rows() + ", reading the "
                            + w.table() + " directly genuinely is cheaper.");
        }

        if ((result.isUsesFilesort() || result.isUsesTemporaryTable())
                && isLargeRowCount(result.getEstimatedRows())) {
            String what = result.isUsesFilesort() ? "sort" : (w.mongo() ? "group" : "assemble");
            String tail = !w.mongo() ? " after reading them."
                    : result.isUsesFilesort() ? " in memory after reading them."
                    : ", and the grouping spilled to disk.";
            return new AnalysisVerdict(Severity.WARNING, "Could be better",
                    w.engine() + " has to " + what + " roughly " + formatCount(result.getEstimatedRows())
                            + " " + w.rows() + tail,
                    "No index supplied the " + w.rows() + " in the order this query needs, so the "
                            + (w.mongo() ? "sorting" : "ordering") + " or grouping work happens as a separate step.");
        }

        if (worstScan != null) {
            String name = displayNameOf(worstScan);
            String rows = formatCount(worstScan.getEstimatedRows());
            Double filtered = worstScan.getFiltered();

            if (isLarge(worstScan)) {
                return new AnalysisVerdict(Severity.INFO, "Looks reasonable",
                        "Reads the whole " + name + " " + w.table() + " — about " + rows + " " + w.rows()
                                + " — and returns most of them.",
                        (w.mongo() ? "A collection scan" : "A full scan") + " is the efficient plan when a query "
                                + "genuinely needs this much of the " + w.table() + ", so there is little an "
                                + "index could save here.");
            }

            return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                    "Reads all " + rows + " " + w.rows() + " of " + name + ", which is normal at this size.",
                    filtered != null && filtered < MAJORITY_DISCARDED_BELOW_PERCENT
                            ? "Most of those " + w.rows() + " are discarded, but the " + w.table()
                                    + " is small enough that the cost is negligible."
                            : null);
        }

        String key = result.getKeyUsed();
        return new AnalysisVerdict(Severity.INFO, "Looks efficient",
                key != null
                        ? "Uses the " + key + " index to find " + w.rows() + " instead of reading the whole "
                                + w.table() + "."
                        : w.engine() + " reaches the " + w.rows() + " it needs without scanning a whole "
                                + w.table() + ".",
                result.getEstimatedRows() != null
                        ? "About " + formatCount(result.getEstimatedRows()) + " " + w.rows()
                                + (w.mongo() ? " were examined." : " are expected to be read.")
                        : null);
    }
```

```java
    private String secondaryDetail(TableAnalysis ignoredIndex, TableAnalysis scanned, Words w) {
        if (ignoredIndex != null) {
            return w.engine() + (w.mongo() ? " considered " : " listed ") + ignoredIndex.getPossibleKeys() + " on "
                    + displayNameOf(ignoredIndex)
                    + " as usable for this query and then chose not to use it.";
        }
        if (scanned.getPossibleKeys() == null || scanned.getPossibleKeys().isBlank()) {
            return w.engine() + " reported no index it could have used for this " + w.table() + ".";
        }
        return null;
    }
```

`displayNameOf` is unchanged.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=VerdictServiceTest`
Expected: PASS. The existing tests and the five new ones are all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(verdict): MongoDB verdicts speak of collections, documents and in-memory sorts

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: `suggestedChange`, and `IndexStatements` for both dialects

**Files:**
- Create: `backend/.../optimization/IndexStatements.java`
- Modify: `backend/.../dto/OptimizationSuggestion.java`
- Modify: `backend/.../optimization/TransactionalOptimizationRules.java` (TX1, TX2 for MySQL)
- Modify: `backend/.../optimization/ResourceConsciousService.java` (RS1 and RS3 for MySQL; `adjustSeverity` keeps the change)
- Test: `backend/src/test/java/com/querysense/backend/optimization/IndexStatementsTest.java`, `SuggestedChangeTest.java`

**Interfaces:**
- Consumes: `Dialect`, `IndexSpec.KeyPart`.
- Produces:
  - `public final class IndexStatements` with `static String create(Dialect, String table, List<IndexSpec.KeyPart> keys)` and `static String create(Dialect, String table, String column)`. Either returns `null` when the table or any column is null.
    - MySQL produces `CREATE INDEX idx_<table>_<c1>_<c2> ON <table>(<c1>, <c2>);` and ignores direction, as the advisor does today.
    - MongoDB produces `db.<collection>.createIndex({ "<f1>": 1, "<f2>": -1 });`.
  - `OptimizationSuggestion`:
    - a field `suggestedChange` with getter and setter;
    - a 4-argument constructor `(message, severity, source, suggestedChange)`, where the 3-argument constructor means `null`;
    - factories `domain(message, severity, suggestedChange)` and `resource(message, severity, suggestedChange)`;
    - `OptimizationSuggestion withSeverity(SuggestionSeverity)`, which keeps the source and the change.
  - Every MySQL message is unchanged. Only the change is new.

- [ ] **Step 1: Write the failing tests**

`IndexStatementsTest.java`:

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexStatementsTest {

    @Test
    @DisplayName("MySQL statements match the advisor's existing CREATE INDEX format")
    void mysql() {
        assertThat(IndexStatements.create(Dialect.MYSQL, "orders", "status"))
                .isEqualTo("CREATE INDEX idx_orders_status ON orders(status);");
        assertThat(IndexStatements.create(Dialect.MYSQL, "orders", List.of(
                new IndexSpec.KeyPart("status", 1), new IndexSpec.KeyPart("order_date", -1))))
                .isEqualTo("CREATE INDEX idx_orders_status_order_date ON orders(status, order_date);");
    }

    @Test
    @DisplayName("MongoDB statements are createIndex with each key's direction")
    void mongo() {
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", "status"))
                .isEqualTo("db.orders.createIndex({ \"status\": 1 });");
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", List.of(
                new IndexSpec.KeyPart("status", 1), new IndexSpec.KeyPart("order_date", -1))))
                .isEqualTo("db.orders.createIndex({ \"status\": 1, \"order_date\": -1 });");
    }

    @Test
    @DisplayName("an unknown table or column gives no statement rather than a broken one")
    void unknownGivesNull() {
        assertThat(IndexStatements.create(Dialect.MYSQL, null, "status")).isNull();
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", (String) null)).isNull();
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", List.of())).isNull();
    }
}
```

`SuggestedChangeTest.java`:

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestedChangeTest {

    private final TransactionalOptimizationRules rules = new TransactionalOptimizationRules();
    private final ResourceConsciousService resources = new ResourceConsciousService();

    private static QueryAnalysisResult mysql() {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MySQL");
        result.setTableName("orders");
        result.setEstimatedRows(6_000L);
        return result;
    }

    @Test
    @DisplayName("TX1 carries the index on the filtered column, and its message is unchanged")
    void tx1() {
        QueryAnalysisResult result = mysql();
        result.setHasFullTableScan(true);
        result.setFilteredColumn("status");

        OptimizationSuggestion tx1 = rules.evaluate(result).get(0);

        assertThat(tx1.getSuggestedChange()).isEqualTo("CREATE INDEX idx_orders_status ON orders(status);");
        assertThat(tx1.getMessage()).isEqualTo("Full table scan on 'orders' in a transactional query. This query "
                + "path is expected to run frequently — a missing index here means this scan repeats on every "
                + "call. Add an index on the lookup column(s) immediately.");
    }

    @Test
    @DisplayName("TX1 without a known filtered column suggests no statement")
    void tx1WithoutColumn() {
        QueryAnalysisResult result = mysql();
        result.setHasFullTableScan(true);

        assertThat(rules.evaluate(result).get(0).getSuggestedChange()).isNull();
    }

    @Test
    @DisplayName("TX2 carries the index on the sort column")
    void tx2() {
        QueryAnalysisResult result = mysql();
        result.setUsesFilesort(true);
        result.setUsesIndex(true);
        result.setKeyUsed("idx_status");
        result.setOrderByColumn("order_date");

        assertThat(rules.evaluate(result)).anySatisfy(s -> assertThat(s.getSuggestedChange())
                .isEqualTo("CREATE INDEX idx_orders_order_date ON orders(order_date);"));
    }

    @Test
    @DisplayName("TX3 has no index fix, so no statement")
    void tx3() {
        QueryAnalysisResult result = mysql();
        result.setUsesTemporaryTable(true);
        result.setUsesIndex(true);
        result.setKeyUsed("idx_status");

        assertThat(rules.evaluate(result)).allSatisfy(s -> assertThat(s.getSuggestedChange()).isNull());
    }

    @Test
    @DisplayName("RS1 and RS3 carry the sort and filter indexes")
    void resourceRules() {
        QueryAnalysisResult result = mysql();
        result.setUsesFilesort(true);
        result.setOrderByColumn("order_date");
        result.setHasFullTableScan(true);
        result.setFilteredColumn("status");

        List<OptimizationSuggestion> low = resources.evaluate(ResourceLevel.LOW, result);

        assertThat(low).extracting(OptimizationSuggestion::getSuggestedChange).containsExactly(
                "CREATE INDEX idx_orders_order_date ON orders(order_date);",
                "CREATE INDEX idx_orders_status ON orders(status);");
    }

    @Test
    @DisplayName("shifting severity keeps the suggested change and the source")
    void severityShiftKeepsChange() {
        OptimizationSuggestion original = OptimizationSuggestion.domain("m", SuggestionSeverity.WARNING, "CREATE INDEX x");

        OptimizationSuggestion shifted = resources.adjustSeverity(ResourceLevel.LOW, List.of(original)).get(0);

        assertThat(shifted.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
        assertThat(shifted.getSuggestedChange()).isEqualTo("CREATE INDEX x");
        assertThat(shifted.getSource()).isEqualTo(OptimizationSuggestion.Source.DOMAIN);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=IndexStatementsTest,SuggestedChangeTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`IndexStatements.java`:

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** The index statement a suggestion shows, written for its dialect; QuerySense never runs it. */
public final class IndexStatements {

    private IndexStatements() {
    }

    public static String create(Dialect dialect, String table, String column) {
        return column == null ? null : create(dialect, table, List.of(new IndexSpec.KeyPart(column, 1)));
    }

    public static String create(Dialect dialect, String table, List<IndexSpec.KeyPart> keys) {
        if (table == null || keys == null || keys.isEmpty()
                || keys.stream().map(IndexSpec.KeyPart::column).anyMatch(Objects::isNull)) {
            return null;
        }
        if (dialect == Dialect.MONGODB) {
            return "db." + table + ".createIndex({ " + keys.stream()
                    .map(k -> "\"" + k.column() + "\": " + k.direction())
                    .collect(Collectors.joining(", ")) + " });";
        }
        List<String> columns = keys.stream().map(IndexSpec.KeyPart::column).toList();
        return "CREATE INDEX idx_" + table + "_" + String.join("_", columns)
                + " ON " + table + "(" + String.join(", ", columns) + ");";
    }
}
```

`OptimizationSuggestion.java`: add the field `private String suggestedChange;`. Then:

```java
    public OptimizationSuggestion(String message, SuggestionSeverity severity, Source source) {
        this(message, severity, source, null);
    }

    public OptimizationSuggestion(String message, SuggestionSeverity severity, Source source, String suggestedChange) {
        this.message = message;
        this.severity = severity;
        this.source = source;
        this.suggestedChange = suggestedChange;
    }

    public static OptimizationSuggestion domain(String message, SuggestionSeverity severity, String suggestedChange) {
        return new OptimizationSuggestion(message, severity, Source.DOMAIN, suggestedChange);
    }

    public static OptimizationSuggestion resource(String message, SuggestionSeverity severity, String suggestedChange) {
        return new OptimizationSuggestion(message, severity, Source.RESOURCE, suggestedChange);
    }

    public OptimizationSuggestion withSeverity(SuggestionSeverity newSeverity) {
        return new OptimizationSuggestion(message, newSeverity, source, suggestedChange);
    }

    public String getSuggestedChange() { return suggestedChange; }
    public void setSuggestedChange(String suggestedChange) { this.suggestedChange = suggestedChange; }
```

The existing 3-argument constructor is replaced by the delegating one above. The existing 2-argument `domain` and `resource` factories stay.

`TransactionalOptimizationRules.evaluateMySql`: add `import com.querysense.backend.dialect.Dialect;`. TX1 becomes `OptimizationSuggestion.domain("<unchanged message>", severity, IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getFilteredColumn()))`. TX2 becomes `OptimizationSuggestion.domain("<unchanged message>", SuggestionSeverity.CRITICAL, IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getOrderByColumn()))`. TX3, TX4 and TX5 are untouched.

`ResourceConsciousService.evaluateMySql`: add the same import. Define `String sortIndex = IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getOrderByColumn());` and pass it as the third argument of all three RS1 `resource(...)` calls. RS3 passes `IndexStatements.create(Dialect.MYSQL, result.getTableName(), result.getFilteredColumn())`. RS2 is untouched. In `adjustSeverity`, replace `new OptimizationSuggestion(suggestion.getMessage(), newSeverity, suggestion.getSource())` with `suggestion.withSeverity(newSeverity)`.

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(optimization): suggestions carry the index statement that fixes them (sub-project C)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: MongoDB variants of the TX and RS rules

**Files:**
- Modify: `backend/.../optimization/TransactionalOptimizationRules.java` (`evaluateMongo` replaced)
- Modify: `backend/.../optimization/ResourceConsciousService.java` (`evaluateMongo` replaced)
- Test: `backend/src/test/java/com/querysense/backend/optimization/MongoOptimizationRulesTest.java`

**Interfaces:**
- Consumes:
  - From Task 5: the flags `MongoExplainAnalyzer` sets, and `getMongoShape()`.
  - From Task 3: `esrKeys()` and `sortIndexKeys()`.
  - From Task 8: `IndexStatements` and the 3-argument factories.
- Produces:
  - TX1 to TX5 and TX7, and RS1 to RS3, for MongoDB. TX6 and RS4 are no longer emitted: they fold into TX1 and RS3.
  - Index fixes use the shape's ESR keys when a shape is present, else the single filtered or sort field.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.optimization;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.OptimizationSuggestion;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.enums.ResourceLevel;
import com.querysense.backend.enums.SuggestionSeverity;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoOptimizationRulesTest {

    private final TransactionalOptimizationRules rules = new TransactionalOptimizationRules();
    private final ResourceConsciousService resources = new ResourceConsciousService();

    private static QueryAnalysisResult mongo(long docs) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName("customers");
        result.setEstimatedRows(docs);
        result.setMongoShape(new MongoQueryShape("customers", List.of("city"),
                List.of(new IndexSpec.KeyPart("name", 1)), List.of(), List.of(), false));
        return result;
    }

    private static QueryAnalysisResult collscan(long docs) {
        QueryAnalysisResult result = mongo(docs);
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        result.setHasUnindexedFilter(true);
        result.setFilteredColumn("city");
        return result;
    }

    @Test
    @DisplayName("TX1: a collection scan above 100 documents is CRITICAL, fixed by the ESR index, and TX6 does not repeat it")
    void tx1() {
        List<OptimizationSuggestion> suggestions = rules.evaluate(collscan(2_000));

        assertThat(suggestions).singleElement().satisfies(s -> {
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
            assertThat(s.getMessage()).startsWith("Collection scan on 'customers'");
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
    }

    @Test
    @DisplayName("TX1 on a small collection is a WARNING")
    void tx1Small() {
        assertThat(rules.evaluate(collscan(50)).get(0).getSeverity()).isEqualTo(SuggestionSeverity.WARNING);
    }

    @Test
    @DisplayName("TX2: an in-memory sort is CRITICAL, fixed by equality then sort fields")
    void tx2() {
        QueryAnalysisResult result = mongo(2_000);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setUsesFilesort(true);
        result.setOrderByColumn("name");

        assertThat(rules.evaluate(result)).anySatisfy(s -> {
            assertThat(s.getMessage()).contains("sorts documents in memory");
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.CRITICAL);
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
    }

    @Test
    @DisplayName("TX3: a group that spilled to disk is CRITICAL, with no index statement")
    void tx3() {
        QueryAnalysisResult result = mongo(2_000);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setUsesTemporaryTable(true);

        assertThat(rules.evaluate(result)).anySatisfy(s -> {
            assertThat(s.getMessage()).contains("spilled to disk");
            assertThat(s.getSuggestedChange()).isNull();
        });
    }

    @Test
    @DisplayName("TX5: a single-document express lookup is the ideal shape")
    void tx5() {
        QueryAnalysisResult result = mongo(1);
        result.setUsesIndex(true);
        result.setKeyUsed("email_1");
        result.setAccessType("EXPRESS_IXSCAN");

        assertThat(rules.evaluate(result)).singleElement().satisfies(s -> {
            assertThat(s.getSeverity()).isEqualTo(SuggestionSeverity.INFO);
            assertThat(s.getMessage()).contains("email_1").contains("ideal shape");
        });
    }

    @Test
    @DisplayName("TX7: an unanchored regex stays CRITICAL with today's message")
    void tx7() {
        QueryAnalysisResult result = mongo(10);
        result.setUsesIndex(true);
        result.setKeyUsed("city_1");
        result.setAccessType("IXSCAN");
        result.setHasUnanchoredRegex(true);

        assertThat(rules.evaluate(result)).anySatisfy(s -> assertThat(s.getMessage())
                .startsWith("Unanchored regex filter detected in a transactional query."));
    }

    @Test
    @DisplayName("no MongoDB suggestion speaks of tables or filesorts")
    void mongoWording() {
        QueryAnalysisResult result = collscan(6_000);
        result.setUsesFilesort(true);
        result.setUsesTemporaryTable(true);

        List<OptimizationSuggestion> all = new java.util.ArrayList<>(rules.evaluate(result));
        for (ResourceLevel level : ResourceLevel.values()) {
            all.addAll(resources.evaluate(level, result));
        }

        assertThat(all).isNotEmpty().allSatisfy(s -> assertThat(s.getMessage().toLowerCase())
                .doesNotContain("table").doesNotContain("filesort"));
    }

    @Test
    @DisplayName("RS3 on MongoDB: a big collection scan under low memory, with the index; RS4 no longer repeats it")
    void rs3() {
        List<OptimizationSuggestion> low = resources.evaluate(ResourceLevel.LOW, collscan(6_000));

        assertThat(low).singleElement().satisfies(s -> {
            assertThat(s.getMessage()).contains("collection scan");
            assertThat(s.getSuggestedChange()).isEqualTo("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
        });
        assertThat(resources.evaluate(ResourceLevel.MEDIUM, collscan(6_000))).isEmpty();
    }

    @Test
    @DisplayName("RS1 and RS2 on MongoDB follow the resource level")
    void rs1AndRs2() {
        QueryAnalysisResult result = mongo(6_000);
        result.setUsesFilesort(true);
        result.setUsesTemporaryTable(true);

        assertThat(resources.evaluate(ResourceLevel.LOW, result)).extracting(OptimizationSuggestion::getSeverity)
                .containsExactly(SuggestionSeverity.CRITICAL, SuggestionSeverity.CRITICAL);
        assertThat(resources.evaluate(ResourceLevel.HIGH, result)).extracting(OptimizationSuggestion::getSeverity)
                .containsExactly(SuggestionSeverity.INFO, SuggestionSeverity.INFO);
    }

    @Test
    @DisplayName("without a shape, the index falls back to the filtered field")
    void fallbackWithoutShape() {
        QueryAnalysisResult result = collscan(2_000);
        result.setMongoShape(null);

        assertThat(rules.evaluate(result).get(0).getSuggestedChange())
                .isEqualTo("db.customers.createIndex({ \"city\": 1 });");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoOptimizationRulesTest`
Expected: failures. Today's Mongo rules read `hasUnindexedFilter`, emit TX6 text, and set no `suggestedChange`.

- [ ] **Step 3: Implement**

In `TransactionalOptimizationRules`, replace `evaluateMongo` with the code below and add the imports `java.util.Set`, `com.querysense.backend.dialect.Dialect` and `com.querysense.backend.dto.MongoQueryShape`:

```java
    private static final Set<String> SINGLE_DOCUMENT_LOOKUPS = Set.of("EXPRESS_IXSCAN", "EXPRESS_IDHACK", "IDHACK");

    private void evaluateMongo(QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        boolean documentsExceedThreshold = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_CRITICAL_THRESHOLD;

        if (result.isHasFullTableScan()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Collection scan on '" + result.getTableName() + "' in a transactional query. This query "
                            + "path is expected to run frequently — a missing index here means this scan repeats "
                            + "on every call. Add an index on the filtered field(s) immediately.",
                    documentsExceedThreshold ? SuggestionSeverity.CRITICAL : SuggestionSeverity.WARNING,
                    mongoIndex(result, true)));
        }

        if (result.isUsesFilesort()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query sorts documents in memory. Transactional queries should be simple key lookups — an "
                            + "in-memory sort here suggests this query is doing more than it should on a hot "
                            + "path. Consider an index covering the sort order, or reconsider whether this "
                            + "belongs in the transactional path at all.",
                    SuggestionSeverity.CRITICAL,
                    mongoIndex(result, false)));
        }

        if (result.isUsesTemporaryTable()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query groups enough documents that the grouping spilled to disk. Grouping is unusual for a "
                            + "transactional query — this logic may be better suited to a batch or analytical "
                            + "path rather than running on every transactional call.",
                    SuggestionSeverity.CRITICAL));
        }

        if (!result.isUsesIndex() && result.getKeyUsed() == null && !result.isHasFullTableScan()
                && result.getAccessType() != null) {
            suggestions.add(OptimizationSuggestion.domain(
                    "No index used for this lookup on '" + result.getTableName() + "'. Transactional queries "
                            + "should resolve via an index (ideally _id or a unique index) for consistent, "
                            + "low latency.",
                    SuggestionSeverity.WARNING));
        }

        if (SINGLE_DOCUMENT_LOOKUPS.contains(result.getAccessType()) && result.getKeyUsed() != null) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Query resolves via index '" + result.getKeyUsed() + "' as a single-document lookup ("
                            + result.getAccessType() + ") — this is the ideal shape for a transactional lookup.",
                    SuggestionSeverity.INFO));
        }

        if (result.isHasUnanchoredRegex()) {
            suggestions.add(OptimizationSuggestion.domain(
                    "Unanchored regex filter detected in a transactional query. This forces a collection scan on "
                            + "every call — reconsider whether regex matching belongs in this code path, or "
                            + "anchor the pattern if a prefix match is sufficient.",
                    SuggestionSeverity.CRITICAL
            ));
        }
    }

    static String mongoIndex(QueryAnalysisResult result, boolean forScan) {
        MongoQueryShape shape = result.getMongoShape();
        if (shape != null) {
            return IndexStatements.create(Dialect.MONGODB, result.getTableName(),
                    forScan ? shape.esrKeys() : shape.sortIndexKeys());
        }
        return IndexStatements.create(Dialect.MONGODB, result.getTableName(),
                forScan ? result.getFilteredColumn() : result.getOrderByColumn());
    }
```

In `ResourceConsciousService`, replace `evaluateMongo` with the code below. `mongoIndex` is package-private on `TransactionalOptimizationRules` in the same package.

```java
    private void evaluateMongo(ResourceLevel level, QueryAnalysisResult result, List<OptimizationSuggestion> suggestions) {

        boolean documentsAreSignificant = result.getEstimatedRows() != null
                && result.getEstimatedRows() > ROW_COUNT_MEMORY_CONCERN_THRESHOLD;

        if (result.isUsesFilesort() && documentsAreSignificant) {
            String sortIndex = TransactionalOptimizationRules.mongoIndex(result, false);
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this in-memory sort covers a significant number of "
                                + "documents — it is likely to exceed MongoDB's sort memory limit and spill to "
                                + "disk, or fail. Avoiding the sort (via an index) matters more under these "
                                + "constraints than it would otherwise.",
                        SuggestionSeverity.CRITICAL, sortIndex));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "In-memory sort over a significant number of documents. At medium available memory "
                                + "this is usually fine, but worth monitoring if memory pressure increases.",
                        SuggestionSeverity.WARNING, sortIndex));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "In-memory sort detected, but available memory is high — this is unlikely to spill "
                                + "to disk or cause a noticeable slowdown.",
                        SuggestionSeverity.INFO, sortIndex));
            }
        }

        if (result.isUsesTemporaryTable()) {
            switch (level) {
                case LOW -> suggestions.add(OptimizationSuggestion.resource(
                        "Available memory is low and this grouping already spilled to disk — under memory "
                                + "pressure the spill grows, adding significant latency.",
                        SuggestionSeverity.CRITICAL));
                case MEDIUM -> suggestions.add(OptimizationSuggestion.resource(
                        "The grouping spilled to disk. At medium available memory this is usually fine, but "
                                + "keep an eye on it if document counts grow.",
                        SuggestionSeverity.WARNING));
                case HIGH -> suggestions.add(OptimizationSuggestion.resource(
                        "The grouping spilled to disk even though available memory is high — it is unlikely "
                                + "to be the bottleneck, but its memory limit is being reached.",
                        SuggestionSeverity.INFO));
            }
        }

        if (result.isHasFullTableScan() && documentsAreSignificant && level == ResourceLevel.LOW) {
            suggestions.add(OptimizationSuggestion.resource(
                    "Full collection scan over a significant number of documents while available memory is "
                            + "low — this pushes other data out of MongoDB's cache and can slow down other "
                            + "concurrent queries, not just this one.",
                    SuggestionSeverity.WARNING,
                    TransactionalOptimizationRules.mongoIndex(result, true)));
        }
    }
```

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(optimization): MongoDB variants of the transactional and resource rules, reading the real plan

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: The advisor's two halves, and MongoDB's SA1–SA3

**Files:**
- Create: `backend/.../advisor/IndexAdvisor.java`, `backend/.../advisor/MySqlIndexAdvisor.java`, `backend/.../advisor/MongoIndexAdvisor.java`
- Modify: `backend/.../service/impl/SchemaAdvisorServiceImpl.java`
- Modify test: `backend/src/test/java/com/querysense/backend/service/impl/SchemaAdvisorServiceImplTest.java`
- Test: `backend/src/test/java/com/querysense/backend/advisor/MongoIndexAdvisorTest.java`

**Interfaces:**
- Consumes: `MongoQueryShape` (Task 3), `getMongoShape()` (Task 5), `IndexStatements` (Task 8), `SchemaRecommendation` and `DialectRegistry` (existing).
- Produces:
  - `public interface IndexAdvisor extends DialectAware { List<SchemaRecommendation> recommend(QueryAnalysisResult result); }`
  - `@Component MySqlIndexAdvisor`: today's `addMySqlRecommendations`, `reconcileIndexes`, `rowsPhrase`, `tableLabel` and `safeName`, moved without a character changed.
  - `@Component MongoIndexAdvisor`, with:
    - **regex**: an unanchored regex gives a STRUCTURAL anchor-or-text-index recommendation;
    - **SA1**: COLLSCAN with no index in use gives one INDEX with the ESR keys, which also covers the sort;
    - **SA2**: an in-memory sort, when SA1 didn't fire, gives an INDEX on equality then sort;
    - **SA3**: `groupsWithoutIndexedMatch` gives a STRUCTURAL recommendation.
    - It recommends nothing when the shape is null.
  - `SchemaAdvisorServiceImpl(List<IndexAdvisor>)`, which routes by `databaseType`. Null or blank means MySQL.

- [ ] **Step 1: Write the failing tests**

`MongoIndexAdvisorTest.java`:

```java
package com.querysense.backend.advisor;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MongoIndexAdvisorTest {

    private final MongoIndexAdvisor advisor = new MongoIndexAdvisor();

    private static QueryAnalysisResult result(MongoQueryShape shape) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setTableName(shape == null ? "orders" : shape.collection());
        result.setEstimatedRows(12_000L);
        result.setMongoShape(shape);
        return result;
    }

    private static MongoQueryShape shape(List<String> eq, List<IndexSpec.KeyPart> sort, List<String> range,
                                         List<String> regex, boolean group) {
        return new MongoQueryShape("orders", eq, sort, range, regex, group);
    }

    private static QueryAnalysisResult scanned(MongoQueryShape shape) {
        QueryAnalysisResult result = result(shape);
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        return result;
    }

    @Test
    @DisplayName("SA1: a scan on one field recommends createIndex on it")
    void sa1Single() {
        List<SchemaRecommendation> recommendations =
                advisor.recommend(scanned(shape(List.of("status"), List.of(), List.of(), List.of(), false)));

        assertThat(recommendations).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("INDEX");
            assertThat(r.getTargetTable()).isEqualTo("orders");
            assertThat(r.getTargetColumn()).isEqualTo("status");
            assertThat(r.getSuggestedAction()).isEqualTo("db.orders.createIndex({ \"status\": 1 });");
            assertThat(r.getSummary()).contains("12,000");
        });
    }

    @Test
    @DisplayName("SA1 with a sort and a range collapses into one compound index, equality → sort → range")
    void sa1Esr() {
        List<SchemaRecommendation> recommendations = advisor.recommend(scanned(shape(List.of("status"),
                List.of(new IndexSpec.KeyPart("order_date", -1)), List.of("employee_id"), List.of(), false)));

        assertThat(recommendations).singleElement().satisfies(r -> assertThat(r.getSuggestedAction())
                .isEqualTo("db.orders.createIndex({ \"status\": 1, \"order_date\": -1, \"employee_id\": 1 });"));
    }

    @Test
    @DisplayName("SA2: an in-memory sort with an index in use recommends equality then sort fields")
    void sa2() {
        QueryAnalysisResult result = result(shape(List.of("customer_id"),
                List.of(new IndexSpec.KeyPart("order_date", -1)), List.of(), List.of(), false));
        result.setUsesIndex(true);
        result.setUsesFilesort(true);

        assertThat(advisor.recommend(result)).singleElement().satisfies(r -> assertThat(r.getSuggestedAction())
                .isEqualTo("db.orders.createIndex({ \"customer_id\": 1, \"order_date\": -1 });"));
    }

    @Test
    @DisplayName("SA3: a $group with no indexed $match before it is flagged for review")
    void sa3() {
        QueryAnalysisResult result = result(shape(List.of(), List.of(), List.of(), List.of(), true));
        result.setUsesIndex(true);

        assertThat(advisor.recommend(result)).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("STRUCTURAL");
            assertThat(r.getSuggestedAction()).doesNotContain("createIndex({ \"");
        });
    }

    @Test
    @DisplayName("an unanchored regex is structural advice naming a text index on its field, not an ordinary index")
    void regex() {
        List<SchemaRecommendation> recommendations =
                advisor.recommend(scanned(shape(List.of(), List.of(), List.of(), List.of("note"), false)));

        assertThat(recommendations).singleElement().satisfies(r -> {
            assertThat(r.getType()).isEqualTo("STRUCTURAL");
            assertThat(r.getSuggestedAction()).contains("db.orders.createIndex({ \"note\": \"text\" })");
        });
    }

    @Test
    @DisplayName("nothing to index, an index already in use, or no shape: no recommendation")
    void quietCases() {
        assertThat(advisor.recommend(scanned(shape(List.of(), List.of(), List.of(), List.of(), false)))).isEmpty();
        QueryAnalysisResult indexed = result(shape(List.of("status"), List.of(), List.of(), List.of(), false));
        indexed.setUsesIndex(true);
        assertThat(advisor.recommend(indexed)).isEmpty();
        QueryAnalysisResult failed = result(null);
        failed.setHasFullTableScan(true);
        assertThat(advisor.recommend(failed)).isEmpty();
    }

    @Test
    @DisplayName("summaries are plain language: no plan jargon")
    void plainSummaries() {
        List<QueryAnalysisResult> cases = List.of(
                scanned(shape(List.of("status"), List.of(new IndexSpec.KeyPart("order_date", -1)), List.of(), List.of(), false)),
                scanned(shape(List.of(), List.of(), List.of(), List.of("note"), false)),
                result(shape(List.of(), List.of(), List.of(), List.of(), true)));

        for (QueryAnalysisResult result : cases) {
            for (SchemaRecommendation r : advisor.recommend(result)) {
                assertThat(r.getSummary().toLowerCase(Locale.ROOT))
                        .doesNotContain("collscan").doesNotContain("explain").doesNotContain("$regex")
                        .doesNotContain("$group").doesNotContain("filesort");
                assertThat(r.getReason()).isNotBlank().isNotEqualTo(r.getSummary());
            }
        }
    }
}
```

In `SchemaAdvisorServiceImplTest`:
- Add the imports `com.querysense.backend.advisor.MongoIndexAdvisor`, `com.querysense.backend.advisor.MySqlIndexAdvisor`, `com.querysense.backend.dto.MongoQueryShape` and `com.querysense.backend.schema.IndexSpec`.
- `setUp` becomes `advisor = new SchemaAdvisorServiceImpl(List.of(new MySqlIndexAdvisor(), new MongoIndexAdvisor()));`.
- Replace `recommendsMongoIndex` with:

```java
    @Test
    @DisplayName("a scanned Mongo filter recommends createIndex on the field")
    void recommendsMongoIndex() {
        List<SchemaRecommendation> recommendations = advisor.recommend(mongoScan());

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getSuggestedAction()).contains("createIndex");
    }
```

- Replace `recommendsAnchoringRegex` with:

```java
    @Test
    @DisplayName("an unanchored Mongo regex is flagged as structural")
    void recommendsAnchoringRegex() {
        List<SchemaRecommendation> recommendations = advisor.recommend(mongoRegex());

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getType()).isEqualTo("STRUCTURAL");
    }
```

- In `everyRuleFiring`, replace the `mongoFilter` and `mongoRegex` blocks with `return List.of(fullScan(), filesort, temp, mongoScan(), mongoRegex());`, and add these helpers next to `base()`:

```java
    private QueryAnalysisResult mongoScan() {
        QueryAnalysisResult result = base();
        result.setDatabaseType("MongoDB");
        result.setHasFullTableScan(true);
        result.setAccessType("COLLSCAN");
        result.setMongoShape(new MongoQueryShape("user_details", List.of("status"), List.of(), List.of(), List.of(), false));
        return result;
    }

    private QueryAnalysisResult mongoRegex() {
        QueryAnalysisResult result = base();
        result.setDatabaseType("MongoDB");
        result.setUsesIndex(true);
        result.setHasUnanchoredRegex(true);
        result.setMongoShape(new MongoQueryShape("user_details", List.of(), List.of(), List.of(), List.of("status"), false));
        return result;
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MongoIndexAdvisorTest,SchemaAdvisorServiceImplTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`IndexAdvisor.java`:

```java
package com.querysense.backend.advisor;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;

import java.util.List;

/** Schema recommendations for one dialect, read from a query analysis. */
public interface IndexAdvisor extends DialectAware {
    List<SchemaRecommendation> recommend(QueryAnalysisResult result);
}
```

`MySqlIndexAdvisor.java`: a `@Component` implementing `IndexAdvisor`. `dialect()` returns `Dialect.MYSQL`. `recommend` creates the list, calls `addMySqlRecommendations(result, recommendations)` and returns the list. The private methods `addMySqlRecommendations`, `reconcileIndexes`, `rowsPhrase`, `tableLabel` and `safeName` are moved from `SchemaAdvisorServiceImpl` without a character changed. The class comment is `/** Schema recommendations for MySQL: indexes for full scans and filesorts, and grouping flagged for review. */`. It imports `java.util.ArrayList`, `java.util.List`, `java.util.Locale`, `Dialect`, `QueryAnalysisResult`, `SchemaRecommendation` and `org.springframework.stereotype.Component`.

`MongoIndexAdvisor.java`:

```java
package com.querysense.backend.advisor;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.optimization.IndexStatements;
import com.querysense.backend.schema.IndexSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Schema recommendations for MongoDB: equality → sort → range compound indexes, regex and grouping advice. */
@Component
public class MongoIndexAdvisor implements IndexAdvisor {

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        List<SchemaRecommendation> recommendations = new ArrayList<>();
        MongoQueryShape shape = result.getMongoShape();
        if (shape == null) {
            return recommendations;
        }
        String collection = result.getTableName() != null ? result.getTableName() : shape.collection();

        if (!shape.unanchoredRegexFields().isEmpty()) {
            String field = shape.unanchoredRegexFields().get(0);
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    collection,
                    field,
                    "The search pattern can match anywhere inside the text. The database can only take a "
                            + "shortcut when a pattern is pinned to the start of the value, so here it reads "
                            + "every document whether an index exists or not.",
                    "Unanchored $regex on '" + field + "' — MongoDB cannot use an index for a regex that isn't "
                            + "left-anchored, regardless of whether an index exists on the field.",
                    "Rewrite the regex with a leading '^' if a prefix match is intended, or create a text index "
                            + "(db." + collection + ".createIndex({ \"" + field + "\": \"text\" })) if full "
                            + "substring search is required."));
        }

        boolean scanFixed = false;
        if (result.isHasFullTableScan() && !result.isUsesIndex() && !shape.esrKeys().isEmpty()) {
            List<IndexSpec.KeyPart> keys = shape.esrKeys();
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    columns(keys),
                    "To answer this, the database is reading " + documentsPhrase(result) + " of " + collection
                            + " and checking each one, because no index covers " + columns(keys) + ". An index "
                            + "works like the index at the back of a book: it lets the database jump straight to "
                            + "the matching documents instead of reading everything."
                            + (keys.size() > 1
                                    ? " The fields are ordered exact matches first, then the sort, then any "
                                            + "range — the order the database can make the most use of."
                                    : ""),
                    "Collection scan (COLLSCAN) with no index in use on '" + collection + "', reported by "
                            + "MongoDB's explain. Compound key order follows equality → sort → range, so one "
                            + "index serves the filter and the sort together.",
                    IndexStatements.create(Dialect.MONGODB, collection, keys)));
            scanFixed = true;
        }

        if (!scanFixed && result.isUsesFilesort() && !shape.sortKeys().isEmpty()) {
            List<IndexSpec.KeyPart> keys = shape.sortIndexKeys();
            String sortFields = columns(shape.sortKeys());
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    columns(keys),
                    "The database can find the documents, but then has to put them in order in memory, "
                            + "because no index keeps " + sortFields + " already sorted. On a big collection that "
                            + "ordering step is usually the slow part. An index that starts with the exact-match "
                            + "fields and then the sort fields lets it return them already in order. Re-check "
                            + "after applying rather than assuming it worked.",
                    "Blocking SORT stage in the winning plan for " + sortFields + " — an index on the equality "
                            + "fields followed by the sort fields lets MongoDB walk the index in order instead.",
                    IndexStatements.create(Dialect.MONGODB, collection, keys)));
        }

        if (shape.groupsWithoutIndexedMatch()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    collection,
                    null,
                    "The database is combining documents into groups without first narrowing them down "
                            + "through an index, so every document is read to build the groups. Filtering on an "
                            + "indexed field before the grouping step usually helps. There is no single command "
                            + "that reliably fixes this, so it is flagged for a person to look at.",
                    "$group stage with no $match on an indexed field before it — every document in '"
                            + collection + "' feeds the group.",
                    "Add a $match on an indexed field before the $group in '" + collection
                            + "', or index the field the existing $match filters on."));
        }

        return recommendations;
    }

    private static String columns(List<IndexSpec.KeyPart> keys) {
        return keys.stream().map(IndexSpec.KeyPart::column).collect(Collectors.joining(", "));
    }

    private static String documentsPhrase(QueryAnalysisResult result) {
        Long documents = result.getEstimatedRows();
        if (documents == null || documents <= 0) {
            return "every document";
        }
        return "all " + String.format(Locale.UK, "%,d", documents) + " documents";
    }
}
```

`SchemaAdvisorServiceImpl.java`, replacing the whole file:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.advisor.IndexAdvisor;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dialect.DialectRegistry;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.SchemaAdvisorService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Schema recommendations derived from analysis signals, by the advisor of the query's dialect. */
@Service
public class SchemaAdvisorServiceImpl implements SchemaAdvisorService {

    private final DialectRegistry<IndexAdvisor> advisors;

    public SchemaAdvisorServiceImpl(List<IndexAdvisor> advisors) {
        this.advisors = new DialectRegistry<>(advisors, "index advisor");
    }

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        String type = result.getDatabaseType();
        Dialect dialect = type == null || type.isBlank() ? Dialect.MYSQL : Dialect.from(type);
        return advisors.get(dialect).recommend(result);
    }
}
```

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green. That includes every MySQL test in `SchemaAdvisorServiceImplTest`, unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(advisor): split the advisor by dialect; MongoDB gets ESR compound indexes, regex and grouping advice

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Integration test against the real `mongod`

**Files:**
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoAnalysisIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 2–5, 7, 10; `MongoProvisioner` and `MongoSeeder` (step 4).
- Produces: a `@Tag("mongo")` test. It skips when port 27017 is closed. It works in `querysense_it_analysis_<nanoTime>` and drops that database afterwards.

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.querysense.backend.advisor.MongoIndexAdvisor;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
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

@Tag("mongo")
class MongoAnalysisIntegrationTest {

    private static final int ROWS = 2000;
    private static final String DATABASE = "querysense_it_analysis_" + System.nanoTime();

    private static MongoClient client;
    private static MongoExplainAnalyzer analyzer;
    private static final MongoIndexAdvisor advisor = new MongoIndexAdvisor();

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
        analyzer = new MongoExplainAnalyzer(new MongoShellParser(), new MongoPreflightValidator(activeSchema),
                new MongoExplainExecutor(client, activeSchema), new MongoExplainParser(), new VerdictService(1000L));
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("an unindexed filter: a real collection scan, a CRITICAL verdict, and createIndex on the field")
    void unindexedFilter() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})");

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.CRITICAL);
        assertThat(result.getVerdict().getHeadline()).contains("customers collection").contains("documents");
        assertThat(advisor.recommend(result)).extracting(SchemaRecommendation::getSuggestedAction)
                .containsExactly("db.customers.createIndex({ \"city\": 1 });");
    }

    @Test
    @DisplayName("a unique-index lookup uses the index, and the advisor stays quiet")
    void indexedLookup() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"email\": \"nobody@example.com\"})");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.getKeyUsed()).isEqualTo("email_1");
        assertThat(advisor.recommend(result)).isEmpty();
    }

    @Test
    @DisplayName("a scan with a sort collapses into one compound index")
    void scanAndSort() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})");

        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(advisor.recommend(result)).extracting(SchemaRecommendation::getSuggestedAction)
                .containsExactly("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
    }

    @Test
    @DisplayName("a $group with no filter is flagged for review")
    void ungroupedFilter() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.orders.aggregate([{\"$group\": {\"_id\": \"$status\", \"n\": {\"$sum\": 1}}}])");

        assertThat(advisor.recommend(result)).anySatisfy(r -> assertThat(r.getType()).isEqualTo("STRUCTURAL"));
    }

    @Test
    @DisplayName("explaining deleteMany deletes nothing")
    void deleteExplainDeletesNothing() {
        QueryAnalysisResult result = analyzer.analyze("db.orders.deleteMany({\"status\": \"CANCELLED\"})");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(client.getDatabase(DATABASE).getCollection("orders").countDocuments()).isEqualTo(ROWS);
    }

    @Test
    @DisplayName("every plan from the real server is read without an unknown-stage warning")
    void noUnknownStages() {
        for (String query : List.of(
                "db.customers.find({\"city\": \"Mumbai\"})",
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$lookup\": {\"from\": \"customers\", "
                        + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])",
                "db.orders.countDocuments({\"status\": \"PAID\"})")) {
            assertThat(analyzer.analyze(query).getWarnings()).as(query)
                    .noneSatisfy(w -> assertThat(w).contains("does not read"));
        }
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=MongoAnalysisIntegrationTest`
Expected with `mongod` up: PASS, 6 tests, not skipped. With nothing listening: all skipped.

A failure against the real server is a real finding. Fix production code, re-run the unit tests of the task that owns it, and never loosen an assertion.

- [ ] **Step 3: Confirm nothing was left behind**

`mongosh` isn't installed, so list the databases through the driver. Save this to the session scratchpad as `ListDbs.java`, not in the repo:

```java
import com.mongodb.client.MongoClients;

public class ListDbs {
    public static void main(String[] args) {
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000")) {
            client.listDatabaseNames().forEach(System.out::println);
        }
    }
}
```

Run: `cd backend && ./mvnw -q dependency:build-classpath -Dmdep.outputFile=<scratchpad>/cp.txt`, then `java -cp "$(cat <scratchpad>/cp.txt)" <scratchpad>/ListDbs.java`
Expected: no `querysense_it_analysis_` and no `querysense_fixtures` entry.

- [ ] **Step 4: Run the whole suite, then commit**

Run: `cd backend && ./mvnw test`
Expected: all green.

```bash
git add backend/src/test
git commit -m "test(mongo): analyze, judge and advise on real explain plans from mongod, skipped when none is running

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Record it in `RULES.md` and `PROJECT.md`

**Files:**
- Modify: `RULES.md`, `PROJECT.md`

- [ ] **Step 1: `RULES.md`**
  - **Header:** add a line saying the file was read again on 2026-10-01, when MongoDB analysis became a real `explain()` reader (step 5).
  - **"How many rules" table:**
    - P1–P2 gains a MongoDB P2 variant.
    - W1, W3 and W4 gain MongoDB variants in `MongoExplainParser`; W2 has none.
    - M1–M2 now live in `MongoExplainAnalyzer`.
    - TX6 and RS4 are marked "folded into TX1 / RS3 for MongoDB (2026-10-01)".
    - SA4 and SA5 are marked "folded into SA1 for MongoDB".
    - The total is restated as 63 IDs, of which 59 still fire. Each folded ID's line keeps its number and says where it went; renumbering is step 9.
  - **Sections 3, 4 and 5:**
    - Under P2, add the MongoDB wording and the `$out`/`$merge` refusal.
    - Under W1, W3 and W4, add the MongoDB wording.
    - Replace section 5's scope note: these are now real-index checks, and M1 warns only when the plan didn't scan.
  - **Section 6:** say V1–V9 run unchanged on MongoDB's mapped fields, list the vocabulary (rows→documents, table→collection, sort→"in memory", temporary→"grouping spilled to disk"), and note that MongoDB has no cost figure.
  - **Sections 7 and 8:** give each TX and RS rule its MongoDB trigger, and describe `suggestedChange`.
  - **Section 9:**
    - SA1 for MongoDB is the ESR compound index, and absorbs the sort.
    - SA2 for MongoDB is equality then sort.
    - SA3 for MongoDB is a `$group` with no indexed `$match` before it.
    - The regex advice is SA1's regex case.
    - Reconciliation for MongoDB happens in SA1 itself.

- [ ] **Step 2: `PROJECT.md`**
  - **New session entry:** add `### Session of <execution date> — MongoDB parity, step 5 (analysis)` after the step 4 session. It contains:
    - the plan path;
    - a table of Tasks 1–11 with one line each and its commit from `git log`;
    - the seven departures above, one line each;
    - what Task 11 verified against the real `mongod` (passed or skipped), and what is unit-tested only.
  - **"Next:" line:** in the steps 1–3 entry, update it to say steps 1–5 are complete and step 6 (`MongoQueryRewriter`, Mongo compare, `optimizedQuery` in history) is next.
  - **Live checklist:** add these steps to the new entry.
    1. Workbench with MongoDB: generate `customers in mumbai`, then open Analysis. The verdict reads "Reads the whole customers collection — about 12,000 documents — then discards …". The warnings include "Collection scan detected on 'customers'". The current Mongo findings view still renders; step 8 replaces it.
    2. Schema advisor on the same query shows `db.customers.createIndex({ "city": 1 });`.
    3. `POST /api/query/analyze` with `{"query": "db.orders.aggregate([{\"$out\": \"x\"}])", "databaseType": "MongoDB", "resourceLevel": "MEDIUM"}` returns an `UNKNOWN` verdict whose warning says QuerySense never runs a write. No `x` collection appears in `querysense`.
    4. With `mongod` stopped, the same analyze call on a `find` returns 503 naming MongoDB within a few seconds.
    5. A MySQL analysis is unchanged: same verdict text, same suggestions, plus a new `suggestedChange` on TX1, TX2, RS1 and RS3 when a column is known.

- [ ] **Step 3: Commit**

```bash
git add RULES.md PROJECT.md
git commit -m "docs: record MongoDB parity step 5 — real explain analysis, Mongo rule variants, suggestedChange

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
