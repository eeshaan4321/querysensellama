# Schema Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user upload their own DDL, create and seed those tables in a separate MySQL database, and route query generation and `EXPLAIN` analysis at them — without changing a single statement the built-in demo path issues today.

**Architecture:** `KnownSchema`'s static constants become the *built-in* `SchemaSnapshot`; a new `ActiveSchemaProvider` bean holds whichever snapshot is live and the four existing consumers read it by injection instead of statically. Uploaded DDL is parsed by JSqlParser, re-emitted as fresh `CREATE TABLE` statements into `querysense_uploads`, seeded with ~500 generated rows per table, and `ANALYZE TABLE`d so `EXPLAIN` has real statistics. `EXPLAIN` reaches the uploaded catalog through an `ExplainExecutor` that sets and restores the catalog on one pooled connection — and is a literal no-op while the built-in schema is active.

**Tech Stack:** Java 21, Spring Boot 3.5.14, JSqlParser 5.3 (already a dependency), MySQL 9.7, JUnit 5 + Mockito + AssertJ; React 19 + Vite, axios, framer-motion.

**Spec:** `docs/superpowers/specs/2026-09-10-schema-upload-design.md`

**Risk register:** `PROJECT.md` §5, "Planned feature: schema upload — risks accepted going in" (R1–R10). Tasks reference risks by number.

## Global Constraints

- **Repos:** backend is `CAPSTONE CODE/` (git repo `querysense-backend`, branch `master`); frontend is `CAPSTONE FRONTEND/` (git repo `querysense-frontend`, branch `master`). `PROJECT.md` and `docs/` sit at the parent level, inside neither repo — they are **not** committed. Commit steps below apply only within the two repos.
- **The built-in path must not change.** With no upload performed, every consumer resolves to the built-in snapshot and every SQL statement issued is byte-identical to today's. Task 3 asserts this rather than claiming it.
- **`KnownSchema`'s constants and `KnownSchemaTest` are not edited.** `TABLES`, `FOREIGN_KEYS`, `CATEGORIES`…`SUPPLIERS` keep their exact current values. Only additive methods are allowed on that class (R7).
- **No uploaded string is ever executed verbatim.** Every `CREATE TABLE` is re-emitted from the parsed model (R8).
- **Only `querysense_uploads` is ever dropped.** No code path may drop, truncate or alter anything in the connected demo database (R3).
- **Existing tests stay green:** 44 backend tests via `./mvnw test`. Frontend: `npm run build` clean, `npm run lint` at no more than its 3 pre-existing warnings.
- **All new backend tests are plain unit tests** with a mocked `JdbcTemplate`, matching `MySqlExplainAnalyzerTest`. No test requires a running MySQL.
- **Java version:** 21. Use records and `List.of()`/`Stream.toList()` in the style the codebase already uses.
- **Comment style:** this codebase documents *why*, at length, in class-level Javadoc — including what went wrong before. Match it. Do not add narration comments on obvious lines.

---

## File Structure

**Backend — new**

| File | Responsibility |
|---|---|
| `schema/SchemaSnapshot.java` | Immutable record: one schema's tables, FKs, catalog, row counts |
| `schema/SchemaLookup.java` | The name/singular matching logic, operating on any table list |
| `schema/ActiveSchemaProvider.java` | Holds the live snapshot; notifies listeners on change |
| `schema/ddl/ParsedColumn.java` · `ParsedTable.java` · `ParsedSchema.java` | Parsed-DDL model |
| `schema/ddl/SqlStatementSplitter.java` | Splits a `.sql` file into statements, quote/comment aware |
| `schema/ddl/DdlSchemaParser.java` | JSqlParser → `ParsedSchema` |
| `schema/upload/SchemaUploadValidator.java` | Rejects unsafe or unusable uploads |
| `schema/upload/SchemaProvisioner.java` | `CREATE DATABASE`, drop, re-emit `CREATE TABLE`, add FKs |
| `schema/upload/SchemaSeeder.java` | Topologically ordered row generation |
| `schema/upload/ValueGenerator.java` | Type + column-name → generated values |
| `schema/upload/SchemaUploadService.java` | Orchestrates parse → validate → provision → seed → publish |
| `analysis/ExplainExecutor.java` | Runs work in the active catalog on one connection |
| `controller/SchemaController.java` | The three endpoints |
| `dto/SchemaUploadRequest.java` · `SchemaUploadResponse.java` · `SchemaTableView.java` | Wire shapes |
| `exception/SchemaUploadException.java` | Carries a human-readable cause (R1, R2) |

**Backend — modified**

| File | Change |
|---|---|
| `schema/KnownSchema.java` | Add `builtIn()`; statics delegate to `SchemaLookup` |
| `querybuilder/RuleBasedQueryBuilder.java` | Inject `ActiveSchemaProvider` |
| `querybuilder/GeneratedQueryValidator.java` | Inject `ActiveSchemaProvider` |
| `service/impl/OllamaService.java` | Inject `ActiveSchemaProvider`; ground on all tables when none matched |
| `analysis/SchemaMetadataService.java` | Inject `ActiveSchemaProvider`; route through `ExplainExecutor`; register cache flush |
| `analysis/MySqlExplainAnalyzer.java` | Route `readPlan` through `ExplainExecutor` |
| `exception/GlobalExceptionHandler.java` | Handle `SchemaUploadException` |
| `resources/application.properties` | Upload settings |

**Frontend — new**

`api/schemaUpload.js` · `context/ActiveSchemaContext.jsx` · `pages/SchemaSourcePage.jsx` · `components/schemaSource/{SchemaDropZone,SchemaTablePreview,ActiveSchemaCard}.jsx` + CSS

**Frontend — modified**

`api/contracts.js` · `api/index.js` · `mock/fixtures.js` · `App.jsx` · `components/shell/NavRail.jsx` · `components/shell/EngineStatusBar.jsx` · `components/workbench/QueryForm.jsx` · `components/analysis/ExplainPlanView.jsx`

---

# PHASE 1 — BACKEND SEAM

## Task 1: `SchemaSnapshot`, `SchemaLookup`, `ActiveSchemaProvider`

Makes the schema swappable without editing any consumer yet. After this task the app behaves identically; nothing reads the provider.

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/SchemaLookup.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ActiveSchemaProvider.java`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/KnownSchema.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/SchemaLookupTest.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/ActiveSchemaProviderTest.java`

**Interfaces:**
- Consumes: `KnownSchema.Table`, `KnownSchema.ForeignKey` (existing records, unchanged).
- Produces:
  - `SchemaSnapshot(String id, String label, String catalog, List<Table> tables, List<ForeignKey> foreignKeys, Map<String,Integer> rowCounts, Instant loadedAt)` with `boolean builtIn()`, `List<String> tableNames()`.
  - `SchemaLookup.findTable(List<Table>, String) -> Optional<Table>`, `SchemaLookup.detectTablesMentioned(List<Table>, String) -> List<Table>`, `SchemaLookup.mentions(String lowercaseText, Table) -> boolean`.
  - `ActiveSchemaProvider` with `active()`, `isBuiltIn()`, `catalog()` (**null when built-in**), `tables()`, `foreignKeys()`, `tableNames()`, `findTable(String)`, `detectTablesMentioned(String)`, `publish(SchemaSnapshot)`, `resetToBuiltIn()`, `onSchemaChanged(Runnable)`.
  - `KnownSchema.builtIn() -> SchemaSnapshot`.

**Design note for the implementer — why `catalog()` returns `null` for built-in.** It would be easy to hardcode `"querysense"`. Don't. The database name comes from `spring.datasource.url`, which reads `${DB_HOST}`/`${DB_PORT}` env vars and can legitimately differ per machine. `null` means "whatever the connection pool already points at", which makes Task 3's no-op guarantee structural instead of a string comparison that could drift (R5).

- [ ] **Step 1: Write the failing test for `SchemaLookup`**

Create `SchemaLookupTest.java`:

```java
package com.querysense.backend.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaLookupTest
 *
 * SchemaLookup is the matching logic lifted out of KnownSchema's static
 * methods so it can run against an uploaded schema as well as the built-in
 * one. These tests pin it to the behaviour KnownSchema had before the lift,
 * because the whole point of extracting it was that nothing observable
 * changes.
 */
class SchemaLookupTest {

    private static final KnownSchema.Table CUSTOMERS =
            new KnownSchema.Table("customers", List.of("id", "name", "city"));
    private static final KnownSchema.Table ORDER_ITEMS =
            new KnownSchema.Table("order_items", List.of("id", "order_id", "quantity"));
    private static final List<KnownSchema.Table> TABLES = List.of(CUSTOMERS, ORDER_ITEMS);

    @Test
    @DisplayName("findTable matches the plural name")
    void findsByPluralName() {
        assertThat(SchemaLookup.findTable(TABLES, "customers")).contains(CUSTOMERS);
    }

    @Test
    @DisplayName("findTable matches the singular name")
    void findsBySingularName() {
        assertThat(SchemaLookup.findTable(TABLES, "customer")).contains(CUSTOMERS);
    }

    @Test
    @DisplayName("findTable is empty for null, blank and unknown names")
    void emptyForMisses() {
        assertThat(SchemaLookup.findTable(TABLES, null)).isEmpty();
        assertThat(SchemaLookup.findTable(TABLES, "   ")).isEmpty();
        assertThat(SchemaLookup.findTable(TABLES, "invoices")).isEmpty();
    }

    @Test
    @DisplayName("detectTablesMentioned finds tables named in prose")
    void detectsMentions() {
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, "show all customers in Mumbai"))
                .containsExactly(CUSTOMERS);
    }

    @Test
    @DisplayName("detectTablesMentioned is empty for null and blank text")
    void detectsNothingInEmptyText() {
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, null)).isEmpty();
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, "")).isEmpty();
    }

    @Test
    @DisplayName("a table name containing a regex metacharacter is matched literally, not as a pattern")
    void quotesIdentifiers() {
        KnownSchema.Table odd = new KnownSchema.Table("a.b", List.of("id"));
        assertThat(SchemaLookup.detectTablesMentioned(List.of(odd), "select from axb")).isEmpty();
        assertThat(SchemaLookup.detectTablesMentioned(List.of(odd), "select from a.b")).containsExactly(odd);
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

Run from `CAPSTONE CODE/`: `./mvnw test -Dtest=SchemaLookupTest`
Expected: compilation failure — `cannot find symbol: class SchemaLookup`.

- [ ] **Step 3: Implement `SchemaLookup`**

```java
package com.querysense.backend.schema;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SchemaLookup
 *
 * WHY THIS CLASS EXISTS:
 * KnownSchema owned both the built-in schema's DATA (its nine tables) and
 * the LOGIC for matching a table name against free text. The schema-upload
 * feature needs that logic to run against an uploaded schema too, so the
 * logic is lifted here and KnownSchema's static methods now delegate to it.
 * Behaviour is deliberately unchanged — SchemaLookupTest pins it.
 *
 * WHY Pattern.quote:
 * KnownSchema's original matcher interpolated the table name straight into
 * a regex. Every built-in name is [a-z_] so that was safe. Uploaded names
 * are not guaranteed to be, and an unquoted '.' would match any character.
 * SchemaUploadValidator rejects such names anyway; this is the second lock
 * on the same door, and it changes nothing for the built-in names.
 */
public final class SchemaLookup {

    private SchemaLookup() {}

    public static Optional<KnownSchema.Table> findTable(List<KnownSchema.Table> tables, String name) {
        if (tables == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT).trim();
        return tables.stream()
                .filter(t -> t.name().equalsIgnoreCase(lower) || t.singularName().equalsIgnoreCase(lower))
                .findFirst();
    }

    /**
     * Which of the given tables are named (plural or singular) in a piece of
     * free text. Best-effort word-boundary matching, not entity linking —
     * see KnownSchema's own doc for the limits of this, and PROJECT.md's
     * "rule engine resolves multi-word tables to the wrong table" defect for
     * a case it gets wrong. That defect is NOT fixed here: this is a
     * behaviour-preserving lift.
     */
    public static List<KnownSchema.Table> detectTablesMentioned(List<KnownSchema.Table> tables, String text) {
        if (tables == null || text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return tables.stream().filter(t -> mentions(lower, t)).toList();
    }

    /** @param lowercaseText caller must have lowercased it already. */
    public static boolean mentions(String lowercaseText, KnownSchema.Table table) {
        return matchesWholeWord(lowercaseText, table.name())
                || matchesWholeWord(lowercaseText, table.singularName());
    }

    private static boolean matchesWholeWord(String text, String word) {
        return text.matches(".*\\b" + Pattern.quote(word) + "\\b.*");
    }
}
```

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=SchemaLookupTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write `SchemaSnapshot`**

```java
package com.querysense.backend.schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SchemaSnapshot
 *
 * One complete description of the schema QuerySense is currently working
 * against: either the built-in nine-table demo schema, or a schema the user
 * uploaded. Immutable, so swapping schemas is a single reference
 * assignment and no consumer can observe a half-changed one.
 *
 * WHY catalog IS NULL FOR THE BUILT-IN SCHEMA:
 * The built-in schema lives in whatever database spring.datasource.url
 * points at, and that URL reads ${DB_HOST}/${DB_PORT} so it varies per
 * machine. Null means "the connection already points at the right place",
 * which lets ExplainExecutor skip catalog switching entirely rather than
 * compare against a hardcoded name that could drift.
 */
public record SchemaSnapshot(
        String id,
        String label,
        String catalog,
        List<KnownSchema.Table> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        Map<String, Integer> rowCounts,
        Instant loadedAt) {

    public static final String BUILT_IN_ID = "builtin";

    public boolean builtIn() {
        return BUILT_IN_ID.equals(id);
    }

    public List<String> tableNames() {
        return tables.stream().map(KnownSchema.Table::name).toList();
    }

    public int rowCountFor(String tableName) {
        return rowCounts.getOrDefault(tableName, 0);
    }
}
```

- [ ] **Step 6: Add `builtIn()` to `KnownSchema` and delegate its statics**

In `KnownSchema.java`, add these imports: `java.time.Instant`, `java.util.Map`. Then **replace** the existing `findTable` and `detectTablesMentioned` method bodies (keep the Javadoc above `detectTablesMentioned`) and append `builtIn()`:

```java
    public static Optional<Table> findTable(String name) {
        return SchemaLookup.findTable(TABLES, name);
    }

    public static List<Table> detectTablesMentioned(String text) {
        return SchemaLookup.detectTablesMentioned(TABLES, text);
    }

    /**
     * This class's contents as a swappable snapshot. The tables and foreign
     * keys are the same List.of(...) instances declared above — this is a
     * view of the built-in schema, not a copy of it, so KnownSchemaTest
     * still pins exactly what the app uses.
     *
     * rowCounts is empty because nothing measures the live demo tables at
     * startup; the frontend renders a blank count rather than a wrong one.
     */
    public static SchemaSnapshot builtIn() {
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense demo schema (built-in)",
                null,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH);
    }
```

Do not touch `TABLES`, `FOREIGN_KEYS`, the nine `Table` constants, `tableNames()`, or the `Table`/`ForeignKey` records (R7).

- [ ] **Step 7: Write the failing test for `ActiveSchemaProvider`**

Create `ActiveSchemaProviderTest.java`:

```java
package com.querysense.backend.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveSchemaProviderTest {

    private ActiveSchemaProvider provider;

    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "northwind.sql", "querysense_uploads",
            List.of(new KnownSchema.Table("invoices", List.of("id", "total"))),
            List.of(), Map.of("invoices", 500), Instant.now());

    @BeforeEach
    void setUp() {
        provider = new ActiveSchemaProvider();
    }

    @Test
    @DisplayName("starts on the built-in schema, with a null catalog")
    void defaultsToBuiltIn() {
        assertThat(provider.isBuiltIn()).isTrue();
        assertThat(provider.catalog()).isNull();
        assertThat(provider.tableNames()).containsExactlyElementsOf(KnownSchema.tableNames());
        assertThat(provider.foreignKeys()).isEqualTo(KnownSchema.FOREIGN_KEYS);
    }

    @Test
    @DisplayName("publish swaps the active schema and its catalog")
    void publishSwaps() {
        provider.publish(UPLOADED);

        assertThat(provider.isBuiltIn()).isFalse();
        assertThat(provider.catalog()).isEqualTo("querysense_uploads");
        assertThat(provider.tableNames()).containsExactly("invoices");
        assertThat(provider.findTable("invoice")).isPresent();
        assertThat(provider.findTable("customers")).isEmpty();
    }

    @Test
    @DisplayName("resetToBuiltIn restores the built-in schema")
    void resetRestores() {
        provider.publish(UPLOADED);
        provider.resetToBuiltIn();

        assertThat(provider.isBuiltIn()).isTrue();
        assertThat(provider.catalog()).isNull();
        assertThat(provider.findTable("customers")).isPresent();
    }

    @Test
    @DisplayName("every registered listener runs on publish and on reset")
    void notifiesListeners() {
        List<String> calls = new ArrayList<>();
        provider.onSchemaChanged(() -> calls.add("a"));
        provider.onSchemaChanged(() -> calls.add("b"));

        provider.publish(UPLOADED);
        provider.resetToBuiltIn();

        assertThat(calls).containsExactly("a", "b", "a", "b");
    }

    @Test
    @DisplayName("detectTablesMentioned reads the uploaded schema, not the built-in one")
    void detectionFollowsActiveSchema() {
        provider.publish(UPLOADED);

        assertThat(provider.detectTablesMentioned("show all invoices")).hasSize(1);
        assertThat(provider.detectTablesMentioned("show all customers")).isEmpty();
    }
}
```

- [ ] **Step 8: Run and verify it fails**

Run: `./mvnw test -Dtest=ActiveSchemaProviderTest`
Expected: compilation failure — `cannot find symbol: class ActiveSchemaProvider`.

- [ ] **Step 9: Implement `ActiveSchemaProvider`**

```java
package com.querysense.backend.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ActiveSchemaProvider
 *
 * WHY THIS CLASS EXISTS:
 * KnownSchema was static, so every consumer of it was pinned to the
 * built-in nine-table demo schema at compile time. The schema-upload
 * feature needs the same consumers to read an uploaded schema instead.
 * This holds the current SchemaSnapshot and every consumer now injects it.
 *
 * WHY volatile RATHER THAN A LOCK:
 * A snapshot is immutable and swapping is a single reference assignment.
 * volatile gives the visibility guarantee across request threads without a
 * lock on a field read on the hot path of every generated query.
 *
 * WHY LISTENERS RATHER THAN CALLING SchemaMetadataService DIRECTLY:
 * SchemaMetadataService caches live column and value lookups, and those
 * caches MUST be flushed when the schema changes or the first upload keeps
 * serving the demo schema's columns while looking correct (PROJECT.md R4).
 * But SchemaMetadataService already depends on this class for findTable, so
 * calling it from here would be a dependency cycle. It registers a flush
 * callback with onSchemaChanged instead, and the arrow points one way.
 */
@Component
public class ActiveSchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveSchemaProvider.class);

    private volatile SchemaSnapshot active = KnownSchema.builtIn();
    private final List<Runnable> schemaChangedListeners = new CopyOnWriteArrayList<>();

    public SchemaSnapshot active() {
        return active;
    }

    public boolean isBuiltIn() {
        return active.builtIn();
    }

    /** Null means "use the connection's existing catalog" — see SchemaSnapshot. */
    public String catalog() {
        return active.catalog();
    }

    public List<KnownSchema.Table> tables() {
        return active.tables();
    }

    public List<KnownSchema.ForeignKey> foreignKeys() {
        return active.foreignKeys();
    }

    public List<String> tableNames() {
        return active.tableNames();
    }

    public Optional<KnownSchema.Table> findTable(String name) {
        return SchemaLookup.findTable(active.tables(), name);
    }

    public List<KnownSchema.Table> detectTablesMentioned(String text) {
        return SchemaLookup.detectTablesMentioned(active.tables(), text);
    }

    public void onSchemaChanged(Runnable listener) {
        schemaChangedListeners.add(listener);
    }

    public SchemaSnapshot publish(SchemaSnapshot snapshot) {
        this.active = snapshot;
        log.info("Active schema is now '{}' — {} tables, catalog {}",
                snapshot.label(), snapshot.tables().size(),
                snapshot.catalog() == null ? "(connected database)" : snapshot.catalog());
        schemaChangedListeners.forEach(Runnable::run);
        return snapshot;
    }

    public SchemaSnapshot resetToBuiltIn() {
        return publish(KnownSchema.builtIn());
    }
}
```

- [ ] **Step 10: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 44 existing + 11 new = 55 tests. `KnownSchemaTest` still passes — if it does not, `KnownSchema`'s constants were edited; revert that.

- [ ] **Step 11: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/ src/test/java/com/querysense/backend/schema/
git commit -m "feat(schema): add swappable SchemaSnapshot behind ActiveSchemaProvider

KnownSchema's statics become the built-in snapshot and delegate their
matching logic to SchemaLookup. No consumer reads the provider yet, so
behaviour is unchanged."
```

---

## Task 2: Rewire the four consumers onto `ActiveSchemaProvider`

Pure refactor. Every consumer keeps its behaviour on the built-in schema and gains the ability to follow an uploaded one.

**Files:**
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/querybuilder/RuleBasedQueryBuilder.java:48-51, 207-211, 222-230, 341, 433, 479`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/querybuilder/GeneratedQueryValidator.java:65, 86`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/service/impl/OllamaService.java:96-100, 260-289`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java:45-50, 100`
- Modify: `CAPSTONE CODE/src/test/java/com/querysense/backend/service/impl/OllamaServiceTest.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/querybuilder/RuleBasedQueryBuilderSchemaTest.java`

**Interfaces:**
- Consumes: `ActiveSchemaProvider` from Task 1 — `tables()`, `tableNames()`, `findTable(String)`, `detectTablesMentioned(String)`, `foreignKeys()`, `onSchemaChanged(Runnable)`.
- Produces: `SchemaMetadataService.clearCaches()` (package-visible for tests, public for the listener).

- [ ] **Step 1: Write the failing test proving the rule engine follows an uploaded schema**

Create `RuleBasedQueryBuilderSchemaTest.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the rule engine reads whichever schema is active, and — just as
 * importantly — that it still answers the documented sample prompts exactly
 * as before while the built-in schema is active.
 */
class RuleBasedQueryBuilderSchemaTest {

    private ActiveSchemaProvider activeSchema;
    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(metadata, activeSchema);
    }

    @Test
    @DisplayName("built-in schema still answers the documented sample prompts")
    void builtInUnchanged() {
        assertThat(builder.tryBuild("Show all customers", "MySQL"))
                .contains("SELECT * FROM customers;");
        assertThat(builder.tryBuild("Show only the name and email of customers", "MySQL"))
                .contains("SELECT name, email FROM customers;");
        assertThat(builder.tryBuild("How many orders are there", "MySQL"))
                .contains("SELECT COUNT(*) FROM orders;");
    }

    @Test
    @DisplayName("an uploaded table is recognised by the rule engine")
    void recognisesUploadedTable() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show all invoices", "MySQL"))
                .contains("SELECT * FROM invoices;");
    }

    @Test
    @DisplayName("a built-in table is NOT recognised once an upload replaces it")
    void forgetsBuiltInTablesAfterUpload() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show all customers", "MySQL")).isEmpty();
    }

    @Test
    @DisplayName("column projection reads the uploaded table's columns")
    void projectsUploadedColumns() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show only the total and status of invoices", "MySQL"))
                .contains("SELECT total, status FROM invoices;");
    }

    private static SchemaSnapshot uploaded() {
        return new SchemaSnapshot(
                "upload-1", "test.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("id", "total", "status"))),
                List.of(), Map.of("invoices", 500), Instant.now());
    }
}
```

**Signature reference:** `RuleBasedQueryBuilder.tryBuild(String input, String databaseType)` returns `Optional<String>` (`RuleBasedQueryBuilder.java:71`) — hence AssertJ's `.contains(...)` and `.isEmpty()` above, which assert on the Optional's contents rather than on a String.

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=RuleBasedQueryBuilderSchemaTest`
Expected: compilation failure — the two-argument `RuleBasedQueryBuilder` constructor does not exist.

- [ ] **Step 3: Rewire `RuleBasedQueryBuilder`**

Constructor (replaces lines 48–51):

```java
    private final SchemaMetadataService schemaMetadataService;
    private final ActiveSchemaProvider activeSchema;

    public RuleBasedQueryBuilder(SchemaMetadataService schemaMetadataService,
                                 ActiveSchemaProvider activeSchema) {
        this.schemaMetadataService = schemaMetadataService;
        this.activeSchema = activeSchema;
    }
```

Add the import `com.querysense.backend.schema.ActiveSchemaProvider` and `com.querysense.backend.schema.SchemaLookup`. Then four replacements:

In `hasAnyComplexity` (was line 207):

```java
        long tableCount = activeSchema.tables().stream()
                .filter(t -> SchemaLookup.mentions(input, t))
                .count();
        if (tableCount > 1) return true;
```

In `detectTable` (was line 222):

```java
    private Optional<String> detectTable(String input) {
        for (KnownSchema.Table table : activeSchema.tables()) {
            if (SchemaLookup.mentions(input, table)) {
                return Optional.of(table.name());
            }
        }
        return Optional.empty();
    }
```

At the location filter (was line 341): `KnownSchema.tableNames()` → `activeSchema.tableNames()`.

In `detectColumns` (was line 433): `KnownSchema.findTable(tableName)` → `activeSchema.findTable(tableName)`.

Leave `extractTableColumnCompound(String, KnownSchema.Table)` alone — it takes a `Table` and uses `Pattern.quote` already.

- [ ] **Step 4: Rewire `GeneratedQueryValidator`**

Add a constructor and swap the one call site (was line 86):

```java
    private final ActiveSchemaProvider activeSchema;

    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }
```

```java
        List<KnownSchema.Table> mentionedTables = activeSchema.detectTablesMentioned(userInput);
```

- [ ] **Step 5: Rewire `OllamaService` and close the empty-grounding hole**

Constructor (replaces lines 96–100):

```java
    private final RestTemplate restTemplate;
    private final ActiveSchemaProvider activeSchema;

    public OllamaService(RestTemplate restTemplate, ActiveSchemaProvider activeSchema) {
        this.restTemplate = restTemplate;
        this.activeSchema = activeSchema;
    }
```

Replace the body of `buildSchemaContext` (was lines 260–289):

```java
    private String buildSchemaContext(String userInput, String dialect) {
        if (!"MySQL".equalsIgnoreCase(dialect)) {
            return "";
        }

        // WHY WE FALL BACK TO EVERY TABLE RATHER THAN NONE:
        // This used to return "" when the question named no known table,
        // which meant the model got no schema at all for exactly the
        // questions most likely to hallucinate — ones phrased in business
        // words ("buyers", "spend") rather than schema words. PROJECT.md
        // records the result: a confident SELECT against a `transactions`
        // table that does not exist, cached and later served back at
        // confidence 1.0. An over-broad prompt is strictly better than an
        // empty one, so an unmatched question now gets the whole schema.
        List<KnownSchema.Table> mentioned = activeSchema.detectTablesMentioned(userInput);
        List<KnownSchema.Table> tables = mentioned.isEmpty() ? activeSchema.tables() : mentioned;
        if (tables.isEmpty()) {
            return "";
        }

        StringBuilder schema = new StringBuilder(
                "\n            Real schema — use ONLY these tables and columns; never invent a column or table name:\n");
        for (KnownSchema.Table table : tables) {
            schema.append("            - ").append(table.name())
                    .append("(").append(String.join(", ", table.columns())).append(")\n");
        }

        for (KnownSchema.ForeignKey fk : activeSchema.foreignKeys()) {
            boolean fromIncluded = tables.stream().anyMatch(t -> t.name().equals(fk.fromTable()));
            boolean toIncluded = tables.stream().anyMatch(t -> t.name().equals(fk.toTable()));
            if (fromIncluded && toIncluded) {
                schema.append("            - Relationship: ").append(fk.fromTable()).append(".")
                        .append(fk.fromColumn()).append(" references ").append(fk.toTable()).append(".")
                        .append(fk.toColumn())
                        .append(" — use this to JOIN/GROUP BY/HAVING when the request implies it ")
                        .append("(e.g. \"customers with orders more than N\", \"who placed more than N orders\").\n");
            }
        }
        return schema.toString();
    }
```

**Scope note:** this is the one behavioural change in Task 2, and it is deliberate. It is fix direction #1 from PROJECT.md's "the LLM invents tables and columns" defect, it costs four lines, and leaving it out would mean shipping a schema-upload feature where uploading a schema still doesn't ground the model for business-worded questions — the feature's main use case. It does **not** close the whole defect: fix directions #2 (schema-conformance check before caching) and #3 (MongoDB grounding) remain open, and PROJECT.md must still say so after this work.

- [ ] **Step 6: Rewire `SchemaMetadataService` and add the cache flush**

Replace the constructor (was lines 45–50) and add `clearCaches` plus registration:

```java
    private final JdbcTemplate jdbcTemplate;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> columnCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();

    public SchemaMetadataService(JdbcTemplate jdbcTemplate, ActiveSchemaProvider activeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeSchema = activeSchema;
    }

    /**
     * WHY THIS EXISTS:
     * Both caches are keyed by bare table name and never evicted — fine
     * while the schema was static and compiled in. Once the schema can be
     * replaced at runtime, a stale entry means this service keeps answering
     * with the PREVIOUS schema's columns and values, and every caller
     * downstream (SqlPreflightValidator, the rule engine's city safety net)
     * looks correct while being wrong. Registered as a listener rather than
     * called from ActiveSchemaProvider because this class already depends on
     * that one, and the reverse edge would be a cycle.
     */
    @PostConstruct
    void registerSchemaChangeListener() {
        activeSchema.onSchemaChanged(this::clearCaches);
    }

    public void clearCaches() {
        columnCache.clear();
        distinctValueCache.clear();
    }
```

Add imports `jakarta.annotation.PostConstruct` and `com.querysense.backend.schema.ActiveSchemaProvider`; remove the now-unused `com.querysense.backend.schema.KnownSchema` import only if nothing else references it. At the one call site (was line 100):

```java
        boolean recognized = activeSchema.findTable(tableName)
                .map(table -> table.columns().stream().anyMatch(col -> col.equalsIgnoreCase(columnName)))
                .orElse(false);
```

- [ ] **Step 7: Fix `OllamaServiceTest`'s constructor calls**

Every `new OllamaService(restTemplate)` becomes `new OllamaService(restTemplate, new ActiveSchemaProvider())`. Add the import. Using a real provider rather than a mock keeps the built-in schema in play, which is what those tests assume.

If any existing test asserts that an unmatched question produces a prompt with **no** schema block, it is now asserting the defect. Update it to assert the full-schema fallback and note the change in the commit message.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. If `OllamaServiceTest` fails on prompt content, apply Step 7's second paragraph.

- [ ] **Step 9: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java src/test/java
git commit -m "refactor(schema): read the active schema by injection, not statically

RuleBasedQueryBuilder, GeneratedQueryValidator, OllamaService and
SchemaMetadataService now resolve tables through ActiveSchemaProvider.
SchemaMetadataService flushes its caches when the schema changes.

Also grounds the LLM prompt on every table when the question names none,
instead of sending no schema at all — fix direction 1 of the documented
hallucination defect."
```

---

## Task 3: `ExplainExecutor` — reach the active catalog without touching the built-in path

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/analysis/ExplainExecutor.java`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/analysis/MySqlExplainAnalyzer.java:133-143, 232-256`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java` (route both loaders)
- Modify: `CAPSTONE CODE/src/test/java/com/querysense/backend/analysis/MySqlExplainAnalyzerTest.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/analysis/ExplainExecutorTest.java`

**Interfaces:**
- Consumes: `ActiveSchemaProvider.catalog()` from Task 1.
- Produces: `ExplainExecutor.withActiveCatalog(Function<JdbcTemplate, T>) -> T`.

**This task carries the plan's central guarantee.** `ExplainExecutorTest` asserting `setCatalog` is never called on the built-in path is what makes "EXPLAIN is intact" a fact rather than a claim (R5).

- [ ] **Step 1: Write the failing test**

Create `ExplainExecutorTest.java`:

```java
package com.querysense.backend.analysis;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExplainExecutorTest
 *
 * The first test here is the regression guard for the whole schema-upload
 * feature. EXPLAIN worked before this feature existed, and the one way the
 * feature could break it is by switching the catalog on a pooled connection
 * when it had no business doing so. builtInSchemaNeverSwitchesCatalog pins
 * that: on the built-in schema, not one call to setCatalog.
 */
class ExplainExecutorTest {

    private JdbcTemplate jdbcTemplate;
    private Connection connection;
    private ActiveSchemaProvider activeSchema;
    private ExplainExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        jdbcTemplate = mock(JdbcTemplate.class);
        connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("querysense");

        // Make jdbcTemplate.execute(ConnectionCallback) actually invoke the
        // callback against our mock connection, so the catalog handling
        // inside it is what we are testing.
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<Object> cb = inv.getArgument(0);
            return cb.doInConnection(connection);
        });

        activeSchema = new ActiveSchemaProvider();
        executor = new ExplainExecutor(jdbcTemplate, activeSchema);
    }

    @Test
    @DisplayName("built-in schema: setCatalog is never called")
    void builtInSchemaNeverSwitchesCatalog() throws Exception {
        String result = executor.withActiveCatalog(jdbc -> "ran");

        assertThat(result).isEqualTo("ran");
        verify(connection, never()).setCatalog(any());
    }

    @Test
    @DisplayName("uploaded schema: switches to the upload catalog and restores the original")
    void uploadedSchemaSwitchesAndRestores() throws Exception {
        activeSchema.publish(uploaded());

        executor.withActiveCatalog(jdbc -> "ran");

        var order = inOrder(connection);
        order.verify(connection).setCatalog("querysense_uploads");
        order.verify(connection).setCatalog("querysense");
    }

    @Test
    @DisplayName("the original catalog is restored even when the work throws")
    void restoresCatalogOnException() throws Exception {
        activeSchema.publish(uploaded());

        assertThatThrownBy(() -> executor.withActiveCatalog(jdbc -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(connection).setCatalog("querysense");
    }

    @Test
    @DisplayName("no switch when the connection is already on the target catalog")
    void noSwitchWhenAlreadyOnTarget() throws Exception {
        when(connection.getCatalog()).thenReturn("querysense_uploads");
        activeSchema.publish(uploaded());

        executor.withActiveCatalog(jdbc -> "ran");

        verify(connection, never()).setCatalog(any());
    }

    private static SchemaSnapshot uploaded() {
        return new SchemaSnapshot(
                "upload-1", "test.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("id"))),
                List.of(), Map.of(), Instant.now());
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=ExplainExecutorTest`
Expected: compilation failure — `cannot find symbol: class ExplainExecutor`.

- [ ] **Step 3: Implement `ExplainExecutor`**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * ExplainExecutor
 *
 * WHY THIS CLASS EXISTS:
 * EXPLAIN has to run against whichever database the active schema
 * describes. When the user has uploaded a schema, that is
 * `querysense_uploads`, not the database spring.datasource.url points at.
 *
 * WHY A ConnectionCallback RATHER THAN A SECOND DataSource:
 * "USE other_db" then "EXPLAIN ..." only works if both statements run on
 * the same physical connection, and a pooled JdbcTemplate gives no such
 * guarantee across two calls. ConnectionCallback holds one connection for
 * the whole unit of work. The scoped JdbcTemplate built over it is a
 * non-closing wrapper, so callers keep JdbcTemplate's RowMapper support and
 * Spring's exception translation instead of hand-rolling JDBC.
 *
 * WHY THE CATALOG IS RESTORED IN A finally:
 * The connection goes back to the pool afterwards. Leaving it pointed at
 * the wrong database would give the NEXT request a plan for tables the user
 * never asked about — a wrong answer rather than an error, which is the
 * worst failure shape available. The restore is unconditional.
 *
 * WHY THE BUILT-IN SCHEMA IS A NO-OP:
 * activeSchema.catalog() is null for the built-in schema, and this class
 * then runs the work with no catalog handling whatsoever — the same
 * statements, on the same connection, as before this class existed.
 * ExplainExecutorTest pins that.
 */
@Component
public class ExplainExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ActiveSchemaProvider activeSchema;

    public ExplainExecutor(JdbcTemplate jdbcTemplate, ActiveSchemaProvider activeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeSchema = activeSchema;
    }

    public <T> T withActiveCatalog(Function<JdbcTemplate, T> work) {
        String target = activeSchema.catalog();
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            if (target == null) {
                return work.apply(scoped(connection));
            }
            String original = connection.getCatalog();
            boolean switched = !target.equals(original);
            if (switched) {
                connection.setCatalog(target);
            }
            try {
                return work.apply(scoped(connection));
            } finally {
                if (switched) {
                    connection.setCatalog(original);
                }
            }
        });
    }

    /** suppressClose=true: closing this wrapper must not close the pooled connection. */
    private static JdbcTemplate scoped(java.sql.Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
```

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=ExplainExecutorTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Route `MySqlExplainAnalyzer.readPlan` through it**

Constructor (replaces lines 133–143) — the `JdbcTemplate` field goes away entirely:

```java
    private final ExplainExecutor explainExecutor;
    private final MySqlJsonExplainParser jsonExplainParser;
    private final ExplainFormat explainFormat;

    public MySqlExplainAnalyzer(ExplainExecutor explainExecutor,
                                MySqlJsonExplainParser jsonExplainParser,
                                @Value("${querysense.explain.format:TABULAR}") String explainFormat) {
        this.explainExecutor = explainExecutor;
        this.jsonExplainParser = jsonExplainParser;
        this.explainFormat = parseFormat(explainFormat);
    }
```

Then split `readPlan` so the body takes its `JdbcTemplate` as a parameter. Keep the existing comment block about `FORMAT=TRADITIONAL` verbatim — it explains a real MySQL 9.0 behaviour change and is still true:

```java
    private List<TableAnalysis> readPlan(String query) {
        return explainExecutor.withActiveCatalog(jdbc -> readPlanWith(jdbc, query));
    }

    private List<TableAnalysis> readPlanWith(JdbcTemplate jdbc, String query) {
        if (explainFormat == ExplainFormat.JSON) {
            try {
                String json = jdbc.queryForObject("EXPLAIN FORMAT=JSON " + query, String.class);
                List<TableAnalysis> tables = jsonExplainParser.parse(json);
                if (!tables.isEmpty()) {
                    return tables;
                }
                log.warn("EXPLAIN FORMAT=JSON returned no table nodes — falling back to tabular EXPLAIN.");
            } catch (BadSqlGrammarException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("EXPLAIN FORMAT=JSON failed ({}) — falling back to tabular EXPLAIN.", e.toString());
            }
        }
        // [keep the existing FORMAT=TRADITIONAL comment block here, unchanged]
        return jdbc.query("EXPLAIN FORMAT=TRADITIONAL " + query, EXPLAIN_ROW_MAPPER);
    }
```

- [ ] **Step 6: Update `MySqlExplainAnalyzerTest` to construct through the executor**

That test mocks `JdbcTemplate` and asserts on the SQL string passed to it. Keep every existing assertion; only the wiring changes. Add to its `@BeforeEach`:

```java
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);   // the existing mock, kept
        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();  // built-in => no catalog switching
        ExplainExecutor executor = new ExplainExecutor(jdbcTemplate, activeSchema) {
            @Override
            public <T> T withActiveCatalog(java.util.function.Function<JdbcTemplate, T> work) {
                return work.apply(jdbcTemplate);   // hand the test's own mock straight through
            }
        };
        analyzer = new MySqlExplainAnalyzer(executor, jsonParser, "TABULAR");
```

Overriding `withActiveCatalog` keeps every existing `verify(jdbcTemplate).query("EXPLAIN FORMAT=TRADITIONAL ...")` assertion working untouched. The catalog behaviour itself is already covered by `ExplainExecutorTest`, so duplicating it here would test the same thing twice.

- [ ] **Step 7: Route `SchemaMetadataService`'s two loaders through the executor**

Inject `ExplainExecutor` alongside what Task 2 added, and wrap both loader bodies. `loadColumnNames` filters on `table_schema = DATABASE()`, which follows the connection's catalog — that is precisely why it needs the wrapper:

```java
    private List<String> loadColumnNames(String tableName) {
        try {
            return explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND LOWER(table_name) = ?",
                    String.class, tableName));
        } catch (Exception e) {
            log.warn("Could not load column metadata for table '{}': {}", tableName, e.getMessage(), e);
            return List.of();
        }
    }
```

Apply the same wrapper to `loadDistinctValues`'s `jdbcTemplate.queryForList(sql, String.class)` call. The `jdbcTemplate` field can then be removed from this class.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. `MySqlExplainAnalyzerTest` still logs its deliberate `ERROR` line — that is the failure path it exercises, not a regression.

- [ ] **Step 9: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/analysis src/test/java/com/querysense/backend/analysis
git commit -m "feat(analysis): run EXPLAIN in the active schema's catalog

ExplainExecutor sets and restores the catalog on one pooled connection.
On the built-in schema the catalog is null and the executor is a no-op —
ExplainExecutorTest asserts setCatalog is never called there."
```

---

# PHASE 2 — THE UPLOAD PIPELINE

## Task 4: Statement splitting and DDL parsing

Turns a `.sql` file into a structured model. No database access at all — this task is pure parsing and is fully unit-testable.

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ddl/ParsedColumn.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ddl/ParsedTable.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ddl/ParsedSchema.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ddl/SqlStatementSplitter.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/ddl/DdlSchemaParser.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/ddl/SqlStatementSplitterTest.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/ddl/DdlSchemaParserTest.java`

**Interfaces:**
- Consumes: JSqlParser 5.3 (`net.sf.jsqlparser.parser.CCJSqlParserUtil`, `net.sf.jsqlparser.statement.create.table.CreateTable`), `KnownSchema.ForeignKey`.
- Produces:
  - `ParsedColumn(String name, String type, List<String> typeArgs, boolean notNull, boolean autoIncrement, boolean unique, boolean primaryKey)` with `normalisedType()`, `firstArgOr(int)`
  - `ParsedTable(String name, List<ParsedColumn> columns, List<KnownSchema.ForeignKey> foreignKeys)` with `column(String)`, `columnNames()`, `toKnownTable()`
  - `ParsedSchema(List<ParsedTable> tables, List<String> warnings)` with `table(String)`, `allForeignKeys()`, `toKnownTables()`
  - `SqlStatementSplitter.split(String) -> List<String>`
  - `DdlSchemaParser.parse(String ddl) -> ParsedSchema`

**Why a hand-written splitter.** `CCJSqlParserUtil.parseStatements` fails the *whole* input if any one statement is unparseable, and a real `mysqldump` file is full of statements JSqlParser has never claimed to support (`/*!40101 SET ... */`, `LOCK TABLES`, `DELIMITER`). Splitting first and parsing each statement individually is what lets one exotic table cost the user that table instead of the whole upload.

- [ ] **Step 1: Write the failing splitter test**

Create `SqlStatementSplitterTest.java`:

```java
package com.querysense.backend.schema.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementSplitterTest {

    @Test
    @DisplayName("splits on semicolons and drops empty fragments")
    void splitsOnSemicolons() {
        List<String> parts = SqlStatementSplitter.split("CREATE TABLE a (id INT);; CREATE TABLE b (id INT);");
        assertThat(parts).containsExactly("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)");
    }

    @Test
    @DisplayName("a semicolon inside a single-quoted string does not split")
    void ignoresSemicolonInString() {
        List<String> parts = SqlStatementSplitter.split("INSERT INTO a VALUES ('x;y'); CREATE TABLE b (id INT);");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isEqualTo("INSERT INTO a VALUES ('x;y')");
    }

    @Test
    @DisplayName("a semicolon inside backticks does not split")
    void ignoresSemicolonInBackticks() {
        List<String> parts = SqlStatementSplitter.split("CREATE TABLE `we;ird` (id INT);");
        assertThat(parts).containsExactly("CREATE TABLE `we;ird` (id INT)");
    }

    @Test
    @DisplayName("line comments are stripped and their semicolons ignored")
    void stripsLineComments() {
        String sql = "-- a comment; with a semicolon\nCREATE TABLE a (id INT);\n# hash comment;\n";
        assertThat(SqlStatementSplitter.split(sql)).containsExactly("CREATE TABLE a (id INT)");
    }

    @Test
    @DisplayName("block comments are stripped, including mysqldump's executable-comment syntax")
    void stripsBlockComments() {
        String sql = "/*!40101 SET NAMES utf8 */;\nCREATE TABLE a (id INT);";
        assertThat(SqlStatementSplitter.split(sql)).containsExactly("CREATE TABLE a (id INT)");
    }

    @Test
    @DisplayName("an escaped quote inside a string does not end the string")
    void handlesEscapedQuote() {
        List<String> parts = SqlStatementSplitter.split(
                "INSERT INTO a VALUES ('it\\'s; fine'); CREATE TABLE b (id INT);");
        assertThat(parts).hasSize(2);
    }

    @Test
    @DisplayName("a trailing statement with no semicolon is still returned")
    void keepsUnterminatedTail() {
        assertThat(SqlStatementSplitter.split("CREATE TABLE a (id INT)"))
                .containsExactly("CREATE TABLE a (id INT)");
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=SqlStatementSplitterTest`
Expected: compilation failure — `cannot find symbol: class SqlStatementSplitter`.

- [ ] **Step 3: Implement `SqlStatementSplitter`**

```java
package com.querysense.backend.schema.ddl;

import java.util.ArrayList;
import java.util.List;

/**
 * SqlStatementSplitter
 *
 * WHY THIS CLASS EXISTS:
 * JSqlParser's parseStatements() fails the entire input if any one statement
 * in it is unparseable. A real mysqldump file is full of statements it has
 * never claimed to support - executable comments, LOCK TABLES, DELIMITER -
 * so handing it a whole dump means getting nothing back. Splitting first and
 * parsing each statement on its own is what lets one exotic statement cost
 * the user that statement instead of the entire upload.
 *
 * Scope: this is a lexical splitter, not a parser. It knows only enough to
 * decide whether a semicolon terminates a statement: single quotes, double
 * quotes, backticks, backslash escapes, line comments and block comments.
 * That is sufficient for the job and deliberately no more.
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {}

    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && next != 0) {
                    current.append(next);
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '#') {
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }

            if (c == ';') {
                addIfPresent(statements, current);
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        addIfPresent(statements, current);
        return statements;
    }

    private static void addIfPresent(List<String> statements, StringBuilder current) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }
}
```

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=SqlStatementSplitterTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the parsed-model records**

`ParsedColumn.java`:

```java
package com.querysense.backend.schema.ddl;

import java.util.List;
import java.util.Locale;

/**
 * One column as declared in uploaded DDL.
 *
 * `type` is the bare data type as written ("VARCHAR", "INT", "DECIMAL") and
 * `typeArgs` its parenthesised arguments ("255", or "10","2" for
 * DECIMAL(10,2), or every literal of an ENUM). SchemaSeeder reads both to
 * decide what a plausible value looks like, and SchemaProvisioner reads
 * them to re-emit the column.
 */
public record ParsedColumn(
        String name,
        String type,
        List<String> typeArgs,
        boolean notNull,
        boolean autoIncrement,
        boolean unique,
        boolean primaryKey) {

    public String normalisedType() {
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }

    public int firstArgOr(int fallback) {
        if (typeArgs.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(typeArgs.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
```

`ParsedTable.java`:

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

public record ParsedTable(
        String name,
        List<ParsedColumn> columns,
        List<KnownSchema.ForeignKey> foreignKeys) {

    public Optional<ParsedColumn> column(String columnName) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
    }

    public List<String> columnNames() {
        return columns.stream().map(ParsedColumn::name).toList();
    }

    public KnownSchema.Table toKnownTable() {
        return new KnownSchema.Table(name, columnNames());
    }
}
```

`ParsedSchema.java`:

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;

import java.util.List;
import java.util.Optional;

public record ParsedSchema(List<ParsedTable> tables, List<String> warnings) {

    public Optional<ParsedTable> table(String name) {
        return tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<KnownSchema.ForeignKey> allForeignKeys() {
        return tables.stream().flatMap(t -> t.foreignKeys().stream()).toList();
    }

    public List<KnownSchema.Table> toKnownTables() {
        return tables.stream().map(ParsedTable::toKnownTable).toList();
    }
}
```

- [ ] **Step 6: Write the failing parser test**

Create `DdlSchemaParserTest.java`:

```java
package com.querysense.backend.schema.ddl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DdlSchemaParserTest {

    private DdlSchemaParser parser;

    @BeforeEach
    void setUp() {
        parser = new DdlSchemaParser();
    }

    @Test
    @DisplayName("reads table name, column names and declared types")
    void readsColumns() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (
                  id INT NOT NULL AUTO_INCREMENT,
                  name VARCHAR(120) NOT NULL,
                  email VARCHAR(255) UNIQUE,
                  balance DECIMAL(10,2),
                  PRIMARY KEY (id)
                );
                """);

        assertThat(schema.tables()).hasSize(1);
        ParsedTable customers = schema.tables().get(0);
        assertThat(customers.name()).isEqualTo("customers");
        assertThat(customers.columnNames()).containsExactly("id", "name", "email", "balance");

        ParsedColumn id = customers.column("id").orElseThrow();
        assertThat(id.normalisedType()).isEqualTo("INT");
        assertThat(id.notNull()).isTrue();
        assertThat(id.autoIncrement()).isTrue();
        assertThat(id.primaryKey()).isTrue();

        assertThat(customers.column("name").orElseThrow().firstArgOr(0)).isEqualTo(120);
        assertThat(customers.column("email").orElseThrow().unique()).isTrue();
        assertThat(customers.column("balance").orElseThrow().typeArgs()).containsExactly("10", "2");
    }

    @Test
    @DisplayName("reads a table-level FOREIGN KEY constraint")
    void readsTableLevelForeignKey() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (id INT PRIMARY KEY);
                CREATE TABLE orders (
                  id INT PRIMARY KEY,
                  customer_id INT,
                  CONSTRAINT fk_o_c FOREIGN KEY (customer_id) REFERENCES customers (id)
                );
                """);

        assertThat(schema.allForeignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.fromTable()).isEqualTo("orders");
            assertThat(fk.fromColumn()).isEqualTo("customer_id");
            assertThat(fk.toTable()).isEqualTo("customers");
            assertThat(fk.toColumn()).isEqualTo("id");
        });
    }

    @Test
    @DisplayName("reads an inline column REFERENCES clause")
    void readsInlineForeignKey() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (id INT PRIMARY KEY);
                CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customers(id));
                """);

        assertThat(schema.allForeignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.fromTable()).isEqualTo("orders");
            assertThat(fk.toTable()).isEqualTo("customers");
        });
    }

    @Test
    @DisplayName("strips backticks from identifiers")
    void stripsBackticks() {
        ParsedSchema schema = parser.parse("CREATE TABLE `orders` (`id` INT, `order_date` DATE);");

        assertThat(schema.tables().get(0).name()).isEqualTo("orders");
        assertThat(schema.tables().get(0).columnNames()).containsExactly("id", "order_date");
    }

    @Test
    @DisplayName("keeps ENUM values as type arguments")
    void keepsEnumValues() {
        ParsedSchema schema = parser.parse("CREATE TABLE orders (status ENUM('NEW','PAID','SHIPPED'));");

        ParsedColumn status = schema.tables().get(0).column("status").orElseThrow();
        assertThat(status.normalisedType()).isEqualTo("ENUM");
        assertThat(status.typeArgs()).containsExactly("'NEW'", "'PAID'", "'SHIPPED'");
    }

    @Test
    @DisplayName("ignores non-CREATE-TABLE statements without warning about them")
    void ignoresOtherStatements() {
        ParsedSchema schema = parser.parse("""
                DROP TABLE IF EXISTS orders;
                CREATE TABLE orders (id INT PRIMARY KEY);
                INSERT INTO orders VALUES (1);
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.warnings()).isEmpty();
    }

    @Test
    @DisplayName("an unparseable CREATE TABLE becomes a warning, not a failure")
    void unparseableStatementBecomesWarning() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE good (id INT PRIMARY KEY);
                CREATE TABLE bad (((;
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.warnings()).hasSize(1);
        assertThat(schema.warnings().get(0)).contains("bad");
    }

    @Test
    @DisplayName("empty or blank input yields no tables and no warnings")
    void emptyInput() {
        assertThat(parser.parse("").tables()).isEmpty();
        assertThat(parser.parse("   ").warnings()).isEmpty();
    }

    @Test
    @DisplayName("parses a mysqldump preamble without choking on its directives")
    void parsesMysqldumpPreamble() {
        ParsedSchema schema = parser.parse("""
                /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
                /*!40101 SET NAMES utf8mb4 */;
                DROP TABLE IF EXISTS `products`;
                CREATE TABLE `products` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `name` varchar(100) DEFAULT NULL,
                  `price` decimal(10,2) DEFAULT NULL,
                  PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.tables().get(0).columnNames()).containsExactly("id", "name", "price");
    }
}
```

- [ ] **Step 7: Run and verify it fails**

Run: `./mvnw test -Dtest=DdlSchemaParserTest`
Expected: compilation failure — `cannot find symbol: class DdlSchemaParser`.

- [ ] **Step 8: Implement `DdlSchemaParser`**

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DdlSchemaParser
 *
 * Turns uploaded DDL into the structural model the rest of the upload
 * pipeline works from. Reads CREATE TABLE and nothing else.
 *
 * WHY UNPARSEABLE STATEMENTS BECOME WARNINGS RATHER THAN ERRORS:
 * A real schema dump carries plenty this parser has no interest in, and
 * some of it JSqlParser cannot read. Failing the upload because statement
 * 41 of 300 was a LOCK TABLES would make the feature useless on exactly the
 * files people actually have. The user is told what was skipped.
 *
 * WHY NOTHING HERE TOUCHES THE DATABASE:
 * Parsing is separated from provisioning so the whole of it is unit
 * testable without MySQL, and so validation gets to run on the parsed model
 * BEFORE anything is executed.
 */
@Component
public class DdlSchemaParser {

    private static final Logger log = LoggerFactory.getLogger(DdlSchemaParser.class);
    private static final int WARNING_SNIPPET_LENGTH = 80;

    public ParsedSchema parse(String ddl) {
        List<ParsedTable> tables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String statement : SqlStatementSplitter.split(ddl)) {
            if (!startsCreateTable(statement)) {
                continue;
            }
            try {
                Statement parsed = CCJSqlParserUtil.parse(statement);
                if (parsed instanceof CreateTable createTable) {
                    tables.add(toParsedTable(createTable));
                }
            } catch (Exception e) {
                warnings.add("Skipped a statement that could not be parsed: " + snippet(statement));
                log.warn("Skipping unparseable DDL statement: {}", e.getMessage());
            }
        }

        return new ParsedSchema(List.copyOf(tables), List.copyOf(warnings));
    }

    /**
     * Only statements that announce themselves as CREATE TABLE are handed to
     * JSqlParser. Without this, every INSERT and SET in a dump would be
     * parsed and then discarded - and any of them that failed to parse would
     * produce a warning about a statement the user never cared about.
     */
    private boolean startsCreateTable(String statement) {
        String head = statement.stripLeading().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return head.startsWith("CREATE TABLE");
    }

    private ParsedTable toParsedTable(CreateTable createTable) {
        String tableName = unquote(createTable.getTable().getName());
        List<ParsedColumn> columns = new ArrayList<>();
        List<KnownSchema.ForeignKey> foreignKeys = new ArrayList<>();

        List<ColumnDefinition> definitions =
                createTable.getColumnDefinitions() == null ? List.of() : createTable.getColumnDefinitions();

        for (ColumnDefinition definition : definitions) {
            String columnName = unquote(definition.getColumnName());
            List<String> specs = definition.getColumnSpecs() == null ? List.of() : definition.getColumnSpecs();
            String specText = String.join(" ", specs).toUpperCase(Locale.ROOT);

            columns.add(new ParsedColumn(
                    columnName,
                    definition.getColDataType().getDataType(),
                    typeArgs(definition),
                    specText.contains("NOT NULL"),
                    specText.contains("AUTO_INCREMENT"),
                    specText.contains("UNIQUE"),
                    specText.contains("PRIMARY KEY")));

            inlineForeignKey(tableName, columnName, specs).ifPresent(foreignKeys::add);
        }

        markPrimaryKeyColumns(createTable, columns);
        foreignKeys.addAll(tableLevelForeignKeys(createTable, tableName));

        return new ParsedTable(tableName, List.copyOf(columns), List.copyOf(foreignKeys));
    }

    private List<String> typeArgs(ColumnDefinition definition) {
        List<String> args = definition.getColDataType().getArgumentsStringList();
        return args == null ? List.of() : List.copyOf(args);
    }

    /**
     * PRIMARY KEY (id) as a table-level constraint does not set the column's
     * own spec list, so a column declared that way would look non-primary.
     * This walks the table's indexes and back-fills the flag.
     */
    private void markPrimaryKeyColumns(CreateTable createTable, List<ParsedColumn> columns) {
        if (createTable.getIndexes() == null) {
            return;
        }
        for (Index index : createTable.getIndexes()) {
            if (index.getType() == null || !index.getType().toUpperCase(Locale.ROOT).contains("PRIMARY")) {
                continue;
            }
            for (String raw : index.getColumnsNames()) {
                String name = unquote(raw);
                for (int i = 0; i < columns.size(); i++) {
                    ParsedColumn c = columns.get(i);
                    if (c.name().equalsIgnoreCase(name)) {
                        columns.set(i, new ParsedColumn(c.name(), c.type(), c.typeArgs(),
                                true, c.autoIncrement(), c.unique(), true));
                    }
                }
            }
        }
    }

    private List<KnownSchema.ForeignKey> tableLevelForeignKeys(CreateTable createTable, String tableName) {
        List<KnownSchema.ForeignKey> keys = new ArrayList<>();
        if (createTable.getIndexes() == null) {
            return keys;
        }
        for (Index index : createTable.getIndexes()) {
            if (!(index instanceof ForeignKeyIndex fk)) {
                continue;
            }
            List<String> from = fk.getColumnsNames();
            List<String> to = fk.getReferencedColumnNames();
            String toTable = unquote(fk.getTable().getName());
            for (int i = 0; i < from.size() && i < to.size(); i++) {
                keys.add(new KnownSchema.ForeignKey(
                        tableName, unquote(from.get(i)), toTable, unquote(to.get(i))));
            }
        }
        return keys;
    }

    /** Handles `customer_id INT REFERENCES customers(id)`. */
    private Optional<KnownSchema.ForeignKey> inlineForeignKey(
            String tableName, String columnName, List<String> specs) {
        for (int i = 0; i < specs.size(); i++) {
            if (!"REFERENCES".equalsIgnoreCase(specs.get(i)) || i + 1 >= specs.size()) {
                continue;
            }
            String target = specs.get(i + 1);
            String targetTable;
            String targetColumn = "id";

            int open = target.indexOf('(');
            if (open > -1) {
                targetTable = unquote(target.substring(0, open));
                targetColumn = unquote(target.substring(open + 1).replace(")", ""));
            } else {
                targetTable = unquote(target);
                if (i + 2 < specs.size() && specs.get(i + 2).startsWith("(")) {
                    targetColumn = unquote(specs.get(i + 2).replace("(", "").replace(")", ""));
                }
            }
            return Optional.of(new KnownSchema.ForeignKey(tableName, columnName, targetTable, targetColumn));
        }
        return Optional.empty();
    }

    /** Strips backticks, double quotes and any schema qualifier. */
    private String unquote(String identifier) {
        if (identifier == null) {
            return "";
        }
        String cleaned = identifier.replace("`", "").replace("\"", "").trim();
        int dot = cleaned.lastIndexOf('.');
        return dot > -1 ? cleaned.substring(dot + 1) : cleaned;
    }

    private String snippet(String statement) {
        String flat = statement.replaceAll("\\s+", " ").trim();
        return flat.length() <= WARNING_SNIPPET_LENGTH ? flat : flat.substring(0, WARNING_SNIPPET_LENGTH) + "...";
    }
}
```

- [ ] **Step 9: Run and verify it passes**

Run: `./mvnw test -Dtest=DdlSchemaParserTest`
Expected: PASS, 9 tests.

If the inline-`REFERENCES` or ENUM tests fail, JSqlParser 5.3's spec tokenisation differs from the assumption above. Print `definition.getColumnSpecs()` in a scratch test to see its actual shape, then fix the **implementation** to match. Do not weaken the test to match the code.

- [ ] **Step 10: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/ddl src/test/java/com/querysense/backend/schema/ddl
git commit -m "feat(schema): parse uploaded DDL into a structural model

Quote- and comment-aware statement splitting so one unparseable statement
in a mysqldump costs that statement rather than the whole upload."
```

---

## Task 5: `SchemaUploadValidator`

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/upload/SchemaUploadValidator.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/exception/SchemaUploadException.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/upload/SchemaUploadValidatorTest.java`

**Interfaces:**
- Consumes: `ParsedSchema`, `ParsedTable`, `ParsedColumn` from Task 4.
- Produces: `SchemaUploadValidator.validate(ParsedSchema)` returning void, throwing `SchemaUploadException`; `SchemaUploadException extends RuntimeException` with `(String)` and `(String, Throwable)` constructors.

- [ ] **Step 1: Write the failing test**

Create `SchemaUploadValidatorTest.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaUploadValidatorTest {

    private SchemaUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SchemaUploadValidator(40);
    }

    @Test
    @DisplayName("accepts a well-formed schema")
    void acceptsValidSchema() {
        assertThatCode(() -> validator.validate(schemaWith(table("customers", "id", "name"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a schema with no tables, naming what was expected")
    void rejectsEmptySchema() {
        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("CREATE TABLE");
    }

    @Test
    @DisplayName("rejects more tables than the configured cap")
    void rejectsTooManyTables() {
        List<ParsedTable> many = IntStream.range(0, 41).mapToObj(i -> table("t" + i, "id")).toList();

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(many, List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("40");
    }

    @Test
    @DisplayName("rejects a table name outside [A-Za-z0-9_]")
    void rejectsBadTableName() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("bad-name", "id"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("bad-name");
    }

    @Test
    @DisplayName("rejects a column name outside [A-Za-z0-9_]")
    void rejectsBadColumnName() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("customers", "id", "first name"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("first name");
    }

    @Test
    @DisplayName("rejects a table with no columns")
    void rejectsColumnlessTable() {
        assertThatThrownBy(() -> validator.validate(schemaWith(table("customers"))))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    @Test
    @DisplayName("rejects a foreign key pointing at a table that is not in the upload")
    void rejectsUnresolvableForeignKeyTable() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("id"), column("customer_id")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(orders), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    @Test
    @DisplayName("rejects a foreign key pointing at a column that does not exist")
    void rejectsUnresolvableForeignKeyColumn() {
        ParsedTable customers = table("customers", "id");
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("id"), column("customer_id")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "missing")));

        assertThatThrownBy(() -> validator.validate(new ParsedSchema(List.of(customers, orders), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("rejects two tables with the same name")
    void rejectsDuplicateTables() {
        assertThatThrownBy(() -> validator.validate(
                new ParsedSchema(List.of(table("customers", "id"), table("customers", "id")), List.of())))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers");
    }

    private static ParsedSchema schemaWith(ParsedTable... tables) {
        return new ParsedSchema(List.of(tables), List.of());
    }

    private static ParsedTable table(String name, String... columns) {
        return new ParsedTable(name,
                Arrays.stream(columns).map(SchemaUploadValidatorTest::column).toList(),
                List.of());
    }

    private static ParsedColumn column(String name) {
        return new ParsedColumn(name, "INT", List.of(), false, false, false, false);
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=SchemaUploadValidatorTest`
Expected: compilation failure — the classes do not exist.

- [ ] **Step 3: Implement `SchemaUploadException`**

```java
package com.querysense.backend.exception;

/**
 * SchemaUploadException
 *
 * Carries a message written for the person who uploaded the file: what was
 * wrong and, where there is one, what to do about it. GlobalExceptionHandler
 * puts it straight into the response body, the same way
 * LlmUnavailableException's message names the ollama command to run.
 */
public class SchemaUploadException extends RuntimeException {

    public SchemaUploadException(String message) {
        super(message);
    }

    public SchemaUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Implement `SchemaUploadValidator`**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SchemaUploadValidator
 *
 * The gate between parsing and touching the database. Everything downstream
 * builds SQL by string concatenation - identifiers cannot be passed as JDBC
 * parameters - so this is where an identifier is proven safe, once, before
 * any of it runs.
 *
 * WHY THE IDENTIFIER RULE IS STRICTER THAN MySQL's:
 * MySQL permits far more inside backticks than [A-Za-z0-9_]. Accepting all
 * of it would mean every downstream concatenation had to quote and escape
 * correctly forever. The narrow rule costs a user with an exotic name one
 * clear error message; the broad rule is a permanent injection surface.
 */
@Component
public class SchemaUploadValidator {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final int maxTables;

    public SchemaUploadValidator(@Value("${querysense.schema.upload.max-tables:40}") int maxTables) {
        this.maxTables = maxTables;
    }

    public void validate(ParsedSchema schema) {
        if (schema.tables().isEmpty()) {
            throw new SchemaUploadException(
                    "No CREATE TABLE statements were found in the uploaded file. "
                            + "Upload a schema dump - for example the output of `mysqldump --no-data`.");
        }
        if (schema.tables().size() > maxTables) {
            throw new SchemaUploadException(
                    "The uploaded schema has " + schema.tables().size()
                            + " tables, which is more than the " + maxTables + " this build accepts.");
        }

        Set<String> seen = new HashSet<>();
        for (ParsedTable table : schema.tables()) {
            requireSafe(table.name(), "Table name");
            if (!seen.add(table.name().toLowerCase(Locale.ROOT))) {
                throw new SchemaUploadException(
                        "The uploaded schema declares the table '" + table.name() + "' more than once.");
            }
            if (table.columns().isEmpty()) {
                throw new SchemaUploadException("Table '" + table.name() + "' has no columns.");
            }
            for (ParsedColumn column : table.columns()) {
                requireSafe(column.name(), "Column name");
            }
        }

        for (KnownSchema.ForeignKey fk : schema.allForeignKeys()) {
            ParsedTable target = schema.table(fk.toTable()).orElseThrow(() -> new SchemaUploadException(
                    "Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references the table '"
                            + fk.toTable() + "', which is not in the uploaded schema."));
            if (target.column(fk.toColumn()).isEmpty()) {
                throw new SchemaUploadException(
                        "Foreign key " + fk.fromTable() + "." + fk.fromColumn() + " references '"
                                + fk.toTable() + "." + fk.toColumn() + "', which does not exist.");
            }
        }
    }

    private void requireSafe(String identifier, String what) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new SchemaUploadException(
                    what + " '" + identifier + "' is not supported. Names must be 1-64 characters "
                            + "of letters, digits and underscores.");
        }
    }
}
```

- [ ] **Step 5: Run and verify it passes**

Run: `./mvnw test -Dtest=SchemaUploadValidatorTest`
Expected: PASS, 9 tests.

- [ ] **Step 6: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/upload src/main/java/com/querysense/backend/exception/SchemaUploadException.java src/test/java/com/querysense/backend/schema/upload
git commit -m "feat(schema): validate uploaded DDL before any of it reaches MySQL"
```

---

## Task 6: `SchemaProvisioner` — create the database and the tables

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/upload/SchemaProvisioner.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/upload/SchemaProvisionerTest.java`

**Interfaces:**
- Consumes: `ParsedSchema`/`ParsedTable`/`ParsedColumn` (Task 4), `JdbcTemplate`.
- Produces: `SchemaProvisioner.provision(ParsedSchema)` returning void; `SchemaProvisioner.uploadCatalog()` returning the configured catalog name.

**The one rule this class must never break:** it issues DDL, and the only database it may ever create, drop into, or alter is the configured upload catalog (default `querysense_uploads`). No statement it emits may name the connected demo database (R3).

**Why re-emit rather than replay.** The user's file is never executed. Each `CREATE TABLE` is rebuilt from `ParsedTable`, whose identifiers `SchemaUploadValidator` has already constrained to `[A-Za-z0-9_]`. That is what makes "only CREATE TABLE runs" true rather than aspirational (R8).

- [ ] **Step 1: Write the failing test**

Create `SchemaProvisionerTest.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaProvisionerTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaProvisioner provisioner;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString())).thenReturn(List.of());
        provisioner = new SchemaProvisioner(jdbcTemplate, "querysense_uploads");
    }

    @Test
    @DisplayName("creates the upload database and never names any other")
    void createsUploadDatabaseOnly() {
        provisioner.provision(schema());

        List<String> executed = executedStatements();
        assertThat(executed).anyMatch(s -> s.startsWith("CREATE DATABASE IF NOT EXISTS `querysense_uploads`"));
        assertThat(executed).noneMatch(s -> s.contains("querysense`") && !s.contains("querysense_uploads"));
        assertThat(executed).noneMatch(s -> s.toUpperCase().startsWith("DROP DATABASE"));
    }

    @Test
    @DisplayName("emits a CREATE TABLE qualified to the upload catalog, with declared types")
    void emitsCreateTable() {
        provisioner.provision(schema());

        assertThat(executedStatements())
                .anyMatch(s -> s.contains("CREATE TABLE `querysense_uploads`.`customers`")
                        && s.contains("`id` INT NOT NULL AUTO_INCREMENT")
                        && s.contains("`name` VARCHAR(120)")
                        && s.contains("PRIMARY KEY (`id`)"));
    }

    @Test
    @DisplayName("adds foreign keys after every table exists, not inline")
    void addsForeignKeysAfterwards() {
        ParsedTable customers = new ParsedTable("customers", List.of(pk("id")), List.of());
        ParsedTable orders = new ParsedTable("orders",
                List.of(pk("id"), col("customer_id", "INT")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));

        provisioner.provision(new ParsedSchema(List.of(customers, orders), List.of()));

        List<String> executed = executedStatements();
        int lastCreate = lastIndexMatching(executed, "CREATE TABLE");
        int addFk = lastIndexMatching(executed, "ADD CONSTRAINT");

        assertThat(addFk).isGreaterThan(lastCreate);
        assertThat(executed.get(addFk))
                .contains("FOREIGN KEY (`customer_id`) REFERENCES `querysense_uploads`.`customers` (`id`)");
    }

    @Test
    @DisplayName("disables and re-enables foreign key checks around the drop phase")
    void togglesForeignKeyChecks() {
        provisioner.provision(schema());

        List<String> executed = executedStatements();
        assertThat(executed).contains("SET FOREIGN_KEY_CHECKS = 0", "SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("drops the tables already present in the upload catalog")
    void dropsExistingTables() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("stale_table"));

        provisioner.provision(schema());

        assertThat(executedStatements())
                .contains("DROP TABLE IF EXISTS `querysense_uploads`.`stale_table`");
    }

    private List<String> executedStatements() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        return sql.getAllValues();
    }

    private static int lastIndexMatching(List<String> statements, String fragment) {
        int found = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains(fragment)) {
                found = i;
            }
        }
        return found;
    }

    private static ParsedSchema schema() {
        return new ParsedSchema(List.of(new ParsedTable("customers",
                List.of(new ParsedColumn("id", "INT", List.of(), true, true, false, true),
                        new ParsedColumn("name", "VARCHAR", List.of("120"), false, false, false, false)),
                List.of())), List.of());
    }

    private static ParsedColumn pk(String name) {
        return new ParsedColumn(name, "INT", List.of(), true, true, false, true);
    }

    private static ParsedColumn col(String name, String type) {
        return new ParsedColumn(name, type, List.of(), false, false, false, false);
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=SchemaProvisionerTest`
Expected: compilation failure — `cannot find symbol: class SchemaProvisioner`.

- [ ] **Step 3: Implement `SchemaProvisioner`**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SchemaProvisioner
 *
 * Creates the uploaded schema's tables in a database of their own.
 *
 * WHY A SEPARATE DATABASE:
 * The built-in demo schema and query_history live in the connected
 * database. If an upload created its tables there, a user uploading their
 * own `customers` would destroy the demo data, and there would be no way
 * back to the demo schema short of re-seeding by hand. A separate catalog
 * keeps the two apart and makes "reset to built-in" a one-line operation.
 *
 * WHY EVERY STATEMENT IS RE-EMITTED FROM THE PARSED MODEL:
 * The user's uploaded text is never executed. Each CREATE TABLE here is
 * rebuilt from ParsedTable, whose identifiers SchemaUploadValidator has
 * already constrained to [A-Za-z0-9_]. Executing the file as written would
 * run whatever else it happened to contain.
 *
 * WHY FOREIGN KEYS ARE ADDED LAST:
 * A dump can declare `orders` before `customers`. Creating every table
 * first and adding constraints afterwards makes declaration order in the
 * uploaded file irrelevant.
 *
 * SAFETY: the only database this class may create, drop into, or alter is
 * uploadCatalog. No statement it emits names the connected database.
 */
@Component
public class SchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvisioner.class);

    /**
     * Types that MySQL requires a length for, and that a dump may have
     * declared without one because it inherited a default we did not parse.
     */
    private static final Set<String> NEEDS_LENGTH = Set.of("VARCHAR", "CHAR", "VARBINARY");

    private final JdbcTemplate jdbcTemplate;
    private final String uploadCatalog;

    public SchemaProvisioner(JdbcTemplate jdbcTemplate,
                             @Value("${querysense.schema.upload.catalog:querysense_uploads}") String uploadCatalog) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadCatalog = uploadCatalog;
    }

    public String uploadCatalog() {
        return uploadCatalog;
    }

    public void provision(ParsedSchema schema) {
        createDatabase();
        dropExistingTables();
        schema.tables().forEach(this::createTable);
        addForeignKeys(schema);
    }

    private void createDatabase() {
        try {
            jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS `" + uploadCatalog + "`");
        } catch (DataAccessException e) {
            // R1: without CREATE privilege the whole feature is unavailable,
            // and a generic 500 would send the user hunting through logs for
            // a one-line grant. Name the grant.
            throw new SchemaUploadException(
                    "Could not create the database '" + uploadCatalog + "'. The account QuerySense connects "
                            + "with needs the CREATE privilege: "
                            + "GRANT ALL PRIVILEGES ON `" + uploadCatalog + "`.* TO <user>@<host>;", e);
        }
    }

    private void dropExistingTables() {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, uploadCatalog);

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : existing) {
                jdbcTemplate.execute("DROP TABLE IF EXISTS `" + uploadCatalog + "`.`" + table + "`");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        log.info("Cleared {} table(s) from '{}' before provisioning", existing.size(), uploadCatalog);
    }

    private void createTable(ParsedTable table) {
        String columns = table.columns().stream().map(this::columnClause).collect(Collectors.joining(",\n  "));
        String primaryKey = table.columns().stream()
                .filter(ParsedColumn::primaryKey)
                .map(c -> "`" + c.name() + "`")
                .collect(Collectors.joining(", "));

        StringBuilder ddl = new StringBuilder("CREATE TABLE `")
                .append(uploadCatalog).append("`.`").append(table.name()).append("` (\n  ")
                .append(columns);
        if (!primaryKey.isEmpty()) {
            ddl.append(",\n  PRIMARY KEY (").append(primaryKey).append(")");
        }
        ddl.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        jdbcTemplate.execute(ddl.toString());
    }

    private String columnClause(ParsedColumn column) {
        StringBuilder clause = new StringBuilder("`").append(column.name()).append("` ").append(typeClause(column));
        if (column.notNull() || column.primaryKey()) {
            clause.append(" NOT NULL");
        }
        if (column.autoIncrement()) {
            clause.append(" AUTO_INCREMENT");
        }
        // UNIQUE is emitted as a column-level constraint rather than a named
        // index; MySQL creates the index either way and we never need to
        // refer to it by name.
        if (column.unique() && !column.primaryKey()) {
            clause.append(" UNIQUE");
        }
        return clause.toString();
    }

    private String typeClause(ParsedColumn column) {
        String type = column.normalisedType();
        if (column.typeArgs().isEmpty()) {
            return NEEDS_LENGTH.contains(type) ? type + "(255)" : type;
        }
        return type + "(" + String.join(", ", column.typeArgs()) + ")";
    }

    private void addForeignKeys(ParsedSchema schema) {
        int index = 0;
        for (KnownSchema.ForeignKey fk : schema.allForeignKeys()) {
            String constraint = "fk_" + fk.fromTable() + "_" + (index++);
            String sql = "ALTER TABLE `" + uploadCatalog + "`.`" + fk.fromTable() + "`"
                    + " ADD CONSTRAINT `" + constraint + "`"
                    + " FOREIGN KEY (`" + fk.fromColumn() + "`)"
                    + " REFERENCES `" + uploadCatalog + "`.`" + fk.toTable() + "` (`" + fk.toColumn() + "`)";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                // A dump can carry a foreign key MySQL will not accept here -
                // mismatched column types being the usual cause. That costs
                // the constraint, not the upload; the tables are still usable
                // and EXPLAIN still runs.
                log.warn("Skipping foreign key {}.{} -> {}.{}: {}",
                        fk.fromTable(), fk.fromColumn(), fk.toTable(), fk.toColumn(), e.getMessage());
            }
        }
    }

    private String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
```

Remove the unused `upper` helper if the implementation does not end up needing it — do not leave dead code.

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=SchemaProvisionerTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/upload/SchemaProvisioner.java src/test/java/com/querysense/backend/schema/upload/SchemaProvisionerTest.java
git commit -m "feat(schema): provision uploaded tables into a separate catalog

Statements are re-emitted from the parsed model, never replayed from the
uploaded text, and only querysense_uploads is ever written to."
```

---

## Task 7: `ValueGenerator` and `SchemaSeeder`

The task that makes `EXPLAIN` mean something. `PROJECT.md` §7 records that on an empty table MySQL reports `rows=1, filtered=100.00` where a populated one reports `rows=15, filtered=10.00` — same access path, useless magnitude.

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/upload/ValueGenerator.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/upload/SchemaSeeder.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/upload/ValueGeneratorTest.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/upload/SchemaSeederTest.java`

**Interfaces:**
- Consumes: `ParsedSchema`, `ParsedTable`, `ParsedColumn`, `JdbcTemplate`, `SchemaProvisioner.uploadCatalog()`.
- Produces:
  - `ValueGenerator.valueFor(ParsedColumn column, int rowIndex, int totalRows) -> Object`
  - `SchemaSeeder.seed(ParsedSchema) -> Map<String, Integer>` (table name → rows inserted)
  - `SchemaSeeder.orderTables(ParsedSchema) -> List<ParsedTable>` (package-visible, so the topological order is directly testable)

- [ ] **Step 1: Write the failing `ValueGenerator` test**

Create `ValueGeneratorTest.java`:

```java
package com.querysense.backend.schema.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.querysense.backend.schema.ddl.ParsedColumn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValueGeneratorTest {

    private ValueGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ValueGenerator(7L); // fixed seed: generated data must be reproducible
    }

    @Test
    @DisplayName("a VARCHAR value never exceeds its declared length")
    void respectsVarcharLength() {
        ParsedColumn column = col("code", "VARCHAR", List.of("5"));
        for (int i = 0; i < 50; i++) {
            assertThat((String) generator.valueFor(column, i, 500)).hasSizeLessThanOrEqualTo(5);
        }
    }

    @Test
    @DisplayName("an email-shaped column produces something that looks like an email")
    void generatesEmails() {
        assertThat((String) generator.valueFor(col("email", "VARCHAR", List.of("255")), 3, 500))
                .contains("@");
    }

    @Test
    @DisplayName("a status-shaped column has low cardinality, an email-shaped one high")
    void skewsCardinality() {
        Set<Object> statuses = new HashSet<>();
        Set<Object> emails = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            statuses.add(generator.valueFor(col("status", "VARCHAR", List.of("32")), i, 200));
            emails.add(generator.valueFor(col("email", "VARCHAR", List.of("255")), i, 200));
        }
        assertThat(statuses).hasSizeLessThanOrEqualTo(8);
        assertThat(emails).hasSizeGreaterThan(150);
    }

    @Test
    @DisplayName("an ENUM value is always one of its declared literals")
    void picksFromEnum() {
        ParsedColumn column = col("state", "ENUM", List.of("'NEW'", "'PAID'"));
        for (int i = 0; i < 20; i++) {
            assertThat(generator.valueFor(column, i, 500)).isIn("NEW", "PAID");
        }
    }

    @Test
    @DisplayName("a DECIMAL respects its declared precision and scale")
    void respectsDecimalScale() {
        Object value = generator.valueFor(col("price", "DECIMAL", List.of("6", "2")), 1, 500);
        assertThat(value).isInstanceOf(java.math.BigDecimal.class);
        assertThat(((java.math.BigDecimal) value).scale()).isEqualTo(2);
        assertThat(((java.math.BigDecimal) value).precision()).isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("a DATE column produces a java.sql.Date")
    void generatesDates() {
        assertThat(generator.valueFor(col("order_date", "DATE", List.of()), 1, 500))
                .isInstanceOf(java.sql.Date.class);
    }

    @Test
    @DisplayName("a unique column never repeats a value across the run")
    void uniqueColumnsDoNotRepeat() {
        ParsedColumn column = new ParsedColumn("email", "VARCHAR", List.of("255"), true, false, true, false);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(seen.add(generator.valueFor(column, i, 500))).isTrue();
        }
    }

    @Test
    @DisplayName("a nullable non-key column produces some nulls, a NOT NULL column produces none")
    void nullsOnlyWhereAllowed() {
        ParsedColumn nullable = new ParsedColumn("note", "VARCHAR", List.of("64"), false, false, false, false);
        ParsedColumn required = new ParsedColumn("note", "VARCHAR", List.of("64"), true, false, false, false);

        boolean sawNull = false;
        for (int i = 0; i < 300; i++) {
            if (generator.valueFor(nullable, i, 300) == null) {
                sawNull = true;
            }
            assertThat(generator.valueFor(required, i, 300)).isNotNull();
        }
        assertThat(sawNull).isTrue();
    }

    @Test
    @DisplayName("the same seed produces the same data twice")
    void isReproducible() {
        ParsedColumn column = col("name", "VARCHAR", List.of("64"));
        Object first = new ValueGenerator(42L).valueFor(column, 5, 500);
        Object second = new ValueGenerator(42L).valueFor(column, 5, 500);
        assertThat(first).isEqualTo(second);
    }

    private static ParsedColumn col(String name, String type, List<String> args) {
        return new ParsedColumn(name, type, args, false, false, false, false);
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=ValueGeneratorTest`
Expected: compilation failure — `cannot find symbol: class ValueGenerator`.

- [ ] **Step 3: Implement `ValueGenerator`**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.ddl.ParsedColumn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ValueGenerator
 *
 * Produces a plausible value for one column of one row.
 *
 * WHY THE COLUMN NAME STEERS THE VALUE, NOT JUST THE TYPE:
 * Two reasons, and neither is cosmetic. First,
 * SchemaMetadataService.getDistinctValues is the rule engine's live-data
 * safety net - it reads the actual values in a column to notice when a user
 * names one. Fed random hex strings it can never fire. Second, a column
 * called `status` holding 500 distinct values is not what a status column
 * looks like, and EXPLAIN's `filtered` estimate is computed from exactly
 * that distribution.
 *
 * WHY CARDINALITY IS DELIBERATELY SKEWED:
 * Uniform data makes every filtered estimate 100.00 and every index equally
 * selective, which teaches EXPLAIN nothing. A status-like column gets a
 * handful of distinct values; an email-like one gets one per row. The
 * analysis modules then have something real to disagree about.
 *
 * WHY A SEEDED Random:
 * A demo that produces different row estimates on every upload of the same
 * file is not a demo anyone can trust. Seeding makes the generated data
 * reproducible.
 *
 * HONESTY NOTE: this data is generated. It gives EXPLAIN a realistic
 * MAGNITUDE, which an empty table cannot, but the distribution is invented.
 * The UI labels an uploaded schema's plan accordingly (PROJECT.md R6).
 */
public class ValueGenerator {

    private static final List<String> FIRST_NAMES = List.of(
            "Aarav", "Diya", "Rohan", "Isha", "Kabir", "Meera", "Arjun", "Sana",
            "Vikram", "Nisha", "Rahul", "Priya", "Karan", "Anaya", "Dev", "Tara");
    private static final List<String> LAST_NAMES = List.of(
            "Sharma", "Patel", "Nair", "Reddy", "Iyer", "Gupta", "Bose", "Khan",
            "Menon", "Rao", "Desai", "Joshi");
    private static final List<String> CITIES = List.of(
            "Mumbai", "Pune", "Delhi", "Bengaluru", "Hyderabad", "Chennai",
            "Kolkata", "Ahmedabad", "Jaipur", "Surat");
    private static final List<String> STATUSES = List.of(
            "NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED");
    private static final List<String> METHODS = List.of("CARD", "UPI", "NETBANKING", "CASH");
    private static final List<String> DOMAINS = List.of(
            "example.com", "mail.test", "shop.local", "corp.example");
    private static final List<String> WORDS = List.of(
            "alpha", "bravo", "delta", "echo", "gamma", "helix", "ionic", "kappa",
            "lumen", "nova", "orbit", "prism", "quartz", "raven", "sigma", "tempo");

    /** Nullable, non-key columns get this share of NULLs so `filtered` varies. */
    private static final double NULL_RATE = 0.10;

    private final Random random;

    public ValueGenerator(long seed) {
        this.random = new Random(seed);
    }

    public Object valueFor(ParsedColumn column, int rowIndex, int totalRows) {
        if (isNullable(column) && random.nextDouble() < NULL_RATE) {
            return null;
        }

        String type = column.normalisedType();
        return switch (type) {
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT" -> integerValue(column, rowIndex);
            case "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "REAL" -> decimalValue(column);
            case "DATE" -> java.sql.Date.valueOf(dateValue());
            case "DATETIME", "TIMESTAMP" -> java.sql.Timestamp.valueOf(dateValue().atStartOfDay().plusHours(random.nextInt(24)));
            case "TIME" -> java.sql.Time.valueOf(java.time.LocalTime.of(random.nextInt(24), random.nextInt(60)));
            case "BOOLEAN", "BOOL", "BIT" -> random.nextBoolean();
            case "ENUM", "SET" -> enumValue(column);
            default -> textValue(column, rowIndex);
        };
    }

    private boolean isNullable(ParsedColumn column) {
        return !column.notNull() && !column.primaryKey() && !column.unique() && !column.autoIncrement();
    }

    private Object integerValue(ParsedColumn column, int rowIndex) {
        if (column.primaryKey() || column.autoIncrement() || column.unique()) {
            return rowIndex + 1;
        }
        String name = column.name().toLowerCase(Locale.ROOT);
        if (name.contains("quantity") || name.contains("qty")) {
            return 1 + random.nextInt(20);
        }
        if (name.contains("year")) {
            return 2015 + random.nextInt(11);
        }
        return 1 + random.nextInt(1000);
    }

    private BigDecimal decimalValue(ParsedColumn column) {
        int precision = column.typeArgs().isEmpty() ? 10 : column.firstArgOr(10);
        int scale = column.typeArgs().size() > 1 ? parseOr(column.typeArgs().get(1), 2) : 2;
        int wholeDigits = Math.max(1, precision - scale);
        long bound = (long) Math.pow(10, Math.min(wholeDigits, 9));
        double raw = random.nextDouble() * bound;
        return BigDecimal.valueOf(raw).setScale(scale, RoundingMode.HALF_UP);
    }

    private LocalDate dateValue() {
        return LocalDate.now().minusDays(random.nextInt(730));
    }

    private String enumValue(ParsedColumn column) {
        if (column.typeArgs().isEmpty()) {
            return pick(STATUSES);
        }
        String raw = column.typeArgs().get(random.nextInt(column.typeArgs().size()));
        return raw.trim().replaceAll("^'|'$", "");
    }

    /**
     * The name-driven branch. Order matters: `email` is checked before
     * `name`, because a column called `email_name` is far likelier to be an
     * email than a person.
     */
    private String textValue(ParsedColumn column, int rowIndex) {
        int max = column.typeArgs().isEmpty() ? 255 : column.firstArgOr(255);
        String name = column.name().toLowerCase(Locale.ROOT);
        boolean mustBeUnique = column.unique() || column.primaryKey();

        String value;
        if (name.contains("email") || name.contains("mail")) {
            value = (pick(FIRST_NAMES) + "." + pick(LAST_NAMES) + rowIndex).toLowerCase(Locale.ROOT)
                    + "@" + pick(DOMAINS);
        } else if (name.contains("phone") || name.contains("mobile") || name.contains("contact")) {
            value = "+91" + (7000000000L + random.nextInt(999999999));
        } else if (name.contains("city") || name.contains("town")) {
            value = pick(CITIES);
        } else if (name.contains("status") || name.contains("state")) {
            value = pick(STATUSES);
        } else if (name.contains("method") || name.contains("mode") || name.contains("type")) {
            value = pick(METHODS);
        } else if (name.contains("name") || name.contains("title")) {
            value = pick(FIRST_NAMES) + " " + pick(LAST_NAMES);
        } else {
            value = pick(WORDS) + "-" + random.nextInt(10000);
        }

        if (mustBeUnique) {
            value = withUniqueSuffix(value, rowIndex, max);
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * A UNIQUE column must not collide, and truncating to a declared length
     * can create collisions the generator did not intend. The row index is
     * appended and the PREFIX is trimmed, so the part that guarantees
     * uniqueness always survives.
     */
    private String withUniqueSuffix(String value, int rowIndex, int max) {
        String suffix = "-" + rowIndex;
        if (suffix.length() >= max) {
            return String.valueOf(rowIndex);
        }
        int room = max - suffix.length();
        String prefix = value.length() > room ? value.substring(0, room) : value;
        return prefix + suffix;
    }

    private String pick(List<String> pool) {
        return pool.get(random.nextInt(pool.size()));
    }

    private int parseOr(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
```

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=ValueGeneratorTest`
Expected: PASS, 9 tests.

Note the email-uniqueness test: `textValue` appends `rowIndex` to emails already, and `withUniqueSuffix` adds another for a UNIQUE column. That is harmless duplication of intent, not a bug — but if `uniqueColumnsDoNotRepeat` fails, the truncation in `withUniqueSuffix` is the place to look.

- [ ] **Step 5: Write the failing `SchemaSeeder` test**

Create `SchemaSeederTest.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaSeederTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaSeeder seeder;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        seeder = new SchemaSeeder(jdbcTemplate, "querysense_uploads", 50, 7L);
    }

    @Test
    @DisplayName("parents are seeded before the children that reference them")
    void ordersTablesTopologically() {
        List<ParsedTable> ordered = seeder.orderTables(customersAndOrders());

        assertThat(ordered).extracting(ParsedTable::name).containsExactly("customers", "orders");
    }

    @Test
    @DisplayName("declaration order in the file does not change the seeding order")
    void orderIsIndependentOfDeclarationOrder() {
        ParsedSchema reversed = new ParsedSchema(
                List.of(customersAndOrders().tables().get(1), customersAndOrders().tables().get(0)), List.of());

        assertThat(seeder.orderTables(reversed)).extracting(ParsedTable::name)
                .containsExactly("customers", "orders");
    }

    @Test
    @DisplayName("a self-referencing table is seeded once, not treated as a cycle to reject")
    void handlesSelfReference() {
        ParsedTable employees = new ParsedTable("employees",
                List.of(pk("id"), col("manager_id", "INT")),
                List.of(new KnownSchema.ForeignKey("employees", "manager_id", "employees", "id")));

        assertThat(seeder.orderTables(new ParsedSchema(List.of(employees), List.of())))
                .extracting(ParsedTable::name).containsExactly("employees");
    }

    @Test
    @DisplayName("a mutual dependency still yields every table rather than dropping one")
    void toleratesCycle() {
        ParsedTable a = new ParsedTable("a", List.of(pk("id"), col("b_id", "INT")),
                List.of(new KnownSchema.ForeignKey("a", "b_id", "b", "id")));
        ParsedTable b = new ParsedTable("b", List.of(pk("id"), col("a_id", "INT")),
                List.of(new KnownSchema.ForeignKey("b", "a_id", "a", "id")));

        assertThat(seeder.orderTables(new ParsedSchema(List.of(a, b), List.of())))
                .extracting(ParsedTable::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("reports how many rows it inserted per table")
    void reportsRowCounts() {
        Map<String, Integer> counts = seeder.seed(customersAndOrders());

        assertThat(counts).containsEntry("customers", 50).containsEntry("orders", 50);
    }

    @Test
    @DisplayName("inserts through a batch, naming the columns explicitly")
    void insertsInBatches() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(sql.capture(), any(List.class));
        assertThat(sql.getAllValues())
                .anyMatch(s -> s.startsWith("INSERT INTO `querysense_uploads`.`customers` (`id`, `name`)")
                        && s.contains("VALUES (?, ?)"));
    }

    @Test
    @DisplayName("runs ANALYZE TABLE on every table so InnoDB statistics are fresh")
    void analysesEveryTable() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
        assertThat(sql.getAllValues())
                .contains("ANALYZE TABLE `querysense_uploads`.`customers`",
                          "ANALYZE TABLE `querysense_uploads`.`orders`");
    }

    @Test
    @DisplayName("a foreign key column is populated from the parent's real id range")
    void foreignKeyValuesPointAtRealParents() {
        seeder.seed(customersAndOrders());

        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), batches.capture());

        // The orders batch is the one whose rows carry a second column that
        // must fall inside 1..50, the ids customers was seeded with.
        boolean everyFkInRange = batches.getAllValues().stream()
                .flatMap(List::stream)
                .map(row -> (Object[]) row)
                .filter(row -> row.length == 2 && row[1] instanceof Integer)
                .allMatch(row -> (Integer) row[1] >= 1 && (Integer) row[1] <= 50);

        assertThat(everyFkInRange).isTrue();
    }

    private static ParsedSchema customersAndOrders() {
        ParsedTable customers = new ParsedTable("customers",
                List.of(pk("id"), col("name", "VARCHAR")), List.of());
        ParsedTable orders = new ParsedTable("orders",
                List.of(pk("id"), col("customer_id", "INT")),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")));
        return new ParsedSchema(List.of(customers, orders), List.of());
    }

    private static ParsedColumn pk(String name) {
        return new ParsedColumn(name, "INT", List.of(), true, true, false, true);
    }

    private static ParsedColumn col(String name, String type) {
        return new ParsedColumn(name, type, List.of("64"), false, false, false, false);
    }
}
```

- [ ] **Step 6: Run and verify it fails**

Run: `./mvnw test -Dtest=SchemaSeederTest`
Expected: compilation failure — `cannot find symbol: class SchemaSeeder`.

- [ ] **Step 7: Implement `SchemaSeeder`**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SchemaSeeder
 *
 * WHY SEEDING IS PART OF THE UPLOAD AND NOT AN OPTIONAL EXTRA:
 * PROJECT.md records the measurement: on an empty table of identical shape
 * MySQL reports rows=1, filtered=100.00, where the populated table reports
 * rows=15, filtered=10.00. Same access path, useless magnitude. An uploaded
 * schema with no rows would let EXPLAIN run and let every module downstream
 * of it produce output that looks fine and means nothing. Creating the
 * tables is half the feature; filling them is the other half.
 *
 * WHY 500 ROWS BY DEFAULT AND NOT 25:
 * The built-in demo tables hold 5-49 rows, and at that scale every domain
 * suggestion ends with "the estimated row count is low, so the performance
 * impact is likely minor" - the engine can identify a problem but never
 * show one costing anything. At 500 an unindexed scan has a magnitude
 * attached.
 *
 * WHY TOPOLOGICAL ORDER:
 * Foreign keys are real constraints in the provisioned schema, so a child
 * row inserted before its parent is rejected by MySQL. Parents first.
 *
 * WHY A CYCLE IS TOLERATED RATHER THAN REJECTED:
 * employees.manager_id -> employees.id is an ordinary, sensible schema. The
 * self-reference is seeded NULL on the way in and pointed at an earlier row
 * afterwards. A mutual cycle between two tables cannot be resolved that way,
 * so the remaining tables are appended in declaration order and MySQL is
 * left to reject what it will not accept - one lost table beats a rejected
 * upload.
 */
@Component
public class SchemaSeeder {

    private static final Logger log = LoggerFactory.getLogger(SchemaSeeder.class);
    private static final int BATCH_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;
    private final String uploadCatalog;
    private final int seedRows;
    private final long seed;

    public SchemaSeeder(JdbcTemplate jdbcTemplate,
                        @Value("${querysense.schema.upload.catalog:querysense_uploads}") String uploadCatalog,
                        @Value("${querysense.schema.upload.seed-rows:500}") int seedRows,
                        @Value("${querysense.schema.upload.seed:20260910}") long seed) {
        this.jdbcTemplate = jdbcTemplate;
        this.uploadCatalog = uploadCatalog;
        this.seedRows = seedRows;
        this.seed = seed;
    }

    public Map<String, Integer> seed(ParsedSchema schema) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        List<ParsedTable> ordered = orderTables(schema);

        for (ParsedTable table : ordered) {
            rowCounts.put(table.name(), insertRows(table));
        }
        ordered.forEach(this::pointSelfReferencesAtEarlierRows);
        ordered.forEach(this::analyse);

        return rowCounts;
    }

    /**
     * Kahn's algorithm over the foreign-key graph. Self-references are
     * excluded from the edge set - a table does not have to exist before
     * itself - and anything still unresolved after the queue drains (a
     * mutual cycle) is appended in declaration order rather than dropped.
     */
    List<ParsedTable> orderTables(ParsedSchema schema) {
        Map<String, ParsedTable> byName = schema.tables().stream()
                .collect(Collectors.toMap(t -> t.name().toLowerCase(Locale.ROOT), t -> t, (a, b) -> a, LinkedHashMap::new));

        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        byName.keySet().forEach(name -> dependsOn.put(name, new LinkedHashSet<>()));

        for (ParsedTable table : schema.tables()) {
            String from = table.name().toLowerCase(Locale.ROOT);
            for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
                String to = fk.toTable().toLowerCase(Locale.ROOT);
                if (!to.equals(from) && byName.containsKey(to)) {
                    dependsOn.get(from).add(to);
                }
            }
        }

        List<ParsedTable> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progressed = true;

        while (progressed && placed.size() < byName.size()) {
            progressed = false;
            for (Map.Entry<String, Set<String>> entry : dependsOn.entrySet()) {
                if (placed.contains(entry.getKey())) {
                    continue;
                }
                if (placed.containsAll(entry.getValue())) {
                    ordered.add(byName.get(entry.getKey()));
                    placed.add(entry.getKey());
                    progressed = true;
                }
            }
        }

        for (Map.Entry<String, ParsedTable> entry : byName.entrySet()) {
            if (!placed.contains(entry.getKey())) {
                log.warn("Table '{}' is part of a foreign-key cycle; seeding it in declaration order.",
                        entry.getValue().name());
                ordered.add(entry.getValue());
            }
        }
        return ordered;
    }

    private int insertRows(ParsedTable table) {
        List<ParsedColumn> columns = table.columns();
        String columnList = columns.stream().map(c -> "`" + c.name() + "`").collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO `" + uploadCatalog + "`.`" + table.name() + "` ("
                + columnList + ") VALUES (" + placeholders + ")";

        ValueGenerator generator = new ValueGenerator(seed + table.name().hashCode());
        Random fkPicker = new Random(seed + table.name().hashCode() + 1);

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;

        for (int row = 0; row < seedRows; row++) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = valueForColumn(table, columns.get(i), generator, fkPicker, row);
            }
            batch.add(values);

            if (batch.size() == BATCH_SIZE || row == seedRows - 1) {
                inserted += flush(sql, batch, table.name());
                batch.clear();
            }
        }
        return inserted;
    }

    /**
     * A foreign key column must hold an id that actually exists in the
     * parent. Parents are seeded first with sequential ids 1..seedRows, so
     * picking uniformly from that range is both valid and cheap - no read
     * back from the database is needed.
     */
    private Object valueForColumn(ParsedTable table, ParsedColumn column,
                                  ValueGenerator generator, Random fkPicker, int row) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.fromColumn().equalsIgnoreCase(column.name())) {
                continue;
            }
            // Self-reference: NULL now, repaired after the table is full.
            if (fk.toTable().equalsIgnoreCase(table.name())) {
                return null;
            }
            return 1 + fkPicker.nextInt(seedRows);
        }
        return generator.valueFor(column, row, seedRows);
    }

    private int flush(String sql, List<Object[]> batch, String tableName) {
        try {
            jdbcTemplate.batchUpdate(sql, List.copyOf(batch));
            return batch.size();
        } catch (DataAccessException e) {
            log.warn("Could not seed a batch into '{}': {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * The second pass for self-references. Row N points at a row before it,
     * so the graph is acyclic and no row is its own parent.
     */
    private void pointSelfReferencesAtEarlierRows(ParsedTable table) {
        for (KnownSchema.ForeignKey fk : table.foreignKeys()) {
            if (!fk.toTable().equalsIgnoreCase(table.name())) {
                continue;
            }
            String sql = "UPDATE `" + uploadCatalog + "`.`" + table.name() + "`"
                    + " SET `" + fk.fromColumn() + "` = GREATEST(1, FLOOR(`" + fk.toColumn() + "` / 2))"
                    + " WHERE `" + fk.toColumn() + "` > 1";
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException e) {
                log.warn("Could not populate self-reference {}.{}: {}",
                        table.name(), fk.fromColumn(), e.getMessage());
            }
        }
    }

    /**
     * Without this, InnoDB's cardinality statistics can still reflect the
     * empty table, and EXPLAIN's `rows` estimate is then noise - which is
     * the exact failure this whole class exists to prevent.
     */
    private void analyse(ParsedTable table) {
        try {
            jdbcTemplate.execute("ANALYZE TABLE `" + uploadCatalog + "`.`" + table.name() + "`");
        } catch (DataAccessException e) {
            log.warn("ANALYZE TABLE failed for '{}': {}", table.name(), e.getMessage());
        }
    }
}
```

- [ ] **Step 8: Run and verify it passes**

Run: `./mvnw test -Dtest=SchemaSeederTest`
Expected: PASS, 8 tests.

- [ ] **Step 9: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/upload src/test/java/com/querysense/backend/schema/upload
git commit -m "feat(schema): seed uploaded tables so EXPLAIN has real statistics

500 rows per table in foreign-key order, with deliberately skewed
cardinality and ANALYZE TABLE afterwards. An empty table makes MySQL
report rows=1/filtered=100 for everything, which is what this prevents."
```

---

## Task 8: `SchemaUploadService` — orchestration

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/schema/upload/SchemaUploadService.java`
- Modify: `CAPSTONE CODE/src/main/resources/application.properties`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/schema/upload/SchemaUploadServiceTest.java`

**Interfaces:**
- Consumes: `DdlSchemaParser`, `SchemaUploadValidator`, `SchemaProvisioner`, `SchemaSeeder`, `ActiveSchemaProvider`, `Environment`.
- Produces: `SchemaUploadService.upload(String ddl, String label) -> SchemaSnapshot`; `SchemaUploadService.reset() -> SchemaSnapshot`.

- [ ] **Step 1: Write the failing test**

Create `SchemaUploadServiceTest.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaUploadServiceTest {

    private static final String DDL = """
            CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(80));
            CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT,
              CONSTRAINT fk FOREIGN KEY (customer_id) REFERENCES customers (id));
            """;

    private SchemaProvisioner provisioner;
    private SchemaSeeder seeder;
    private ActiveSchemaProvider activeSchema;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        provisioner = mock(SchemaProvisioner.class);
        when(provisioner.uploadCatalog()).thenReturn("querysense_uploads");
        seeder = mock(SchemaSeeder.class);
        when(seeder.seed(any())).thenReturn(Map.of("customers", 500, "orders", 500));
        activeSchema = new ActiveSchemaProvider();
        environment = new MockEnvironment();
    }

    private SchemaUploadService service() {
        return new SchemaUploadService(new DdlSchemaParser(), new SchemaUploadValidator(40),
                provisioner, seeder, activeSchema, environment);
    }

    @Test
    @DisplayName("publishes the uploaded schema with its catalog, tables and row counts")
    void publishesUploadedSchema() {
        SchemaSnapshot snapshot = service().upload(DDL, "shop.sql");

        assertThat(snapshot.builtIn()).isFalse();
        assertThat(snapshot.label()).isEqualTo("shop.sql");
        assertThat(snapshot.catalog()).isEqualTo("querysense_uploads");
        assertThat(snapshot.tableNames()).containsExactly("customers", "orders");
        assertThat(snapshot.rowCountFor("customers")).isEqualTo(500);
        assertThat(snapshot.foreignKeys()).hasSize(1);
        assertThat(activeSchema.active()).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("provisions before seeding")
    void provisionsThenSeeds() {
        service().upload(DDL, "shop.sql");

        var order = org.mockito.Mockito.inOrder(provisioner, seeder);
        order.verify(provisioner).provision(any());
        order.verify(seeder).seed(any());
    }

    @Test
    @DisplayName("refuses under the h2 profile without touching the database")
    void refusesUnderH2() {
        environment.setActiveProfiles("h2");

        assertThatThrownBy(() -> service().upload(DDL, "shop.sql"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("H2");

        verify(provisioner, never()).provision(any());
        verify(seeder, never()).seed(any());
        assertThat(activeSchema.isBuiltIn()).isTrue();
    }

    @Test
    @DisplayName("an invalid schema is rejected before anything is provisioned")
    void validatesBeforeProvisioning() {
        assertThatThrownBy(() -> service().upload("SELECT 1;", "not-a-schema.sql"))
                .isInstanceOf(SchemaUploadException.class);

        verify(provisioner, never()).provision(any());
        assertThat(activeSchema.isBuiltIn()).isTrue();
    }

    @Test
    @DisplayName("blank DDL is rejected with a message that says what to upload")
    void rejectsBlankDdl() {
        assertThatThrownBy(() -> service().upload("   ", "empty.sql"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("reset returns to the built-in schema")
    void resetRestoresBuiltIn() {
        SchemaUploadService service = service();
        service.upload(DDL, "shop.sql");

        SchemaSnapshot snapshot = service.reset();

        assertThat(snapshot.builtIn()).isTrue();
        assertThat(activeSchema.isBuiltIn()).isTrue();
    }

    @Test
    @DisplayName("parser warnings survive onto the published snapshot's label context")
    void keepsWarnings() {
        SchemaUploadService service = service();
        service.upload(DDL + "\nCREATE TABLE broken (((;\n", "shop.sql");

        assertThat(service.lastWarnings()).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run and verify it fails**

Run: `./mvnw test -Dtest=SchemaUploadServiceTest`
Expected: compilation failure — `cannot find symbol: class SchemaUploadService`.

- [ ] **Step 3: Implement `SchemaUploadService`**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.DdlSchemaParser;
import com.querysense.backend.schema.ddl.ParsedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SchemaUploadService
 *
 * The pipeline, in the only order that is safe: parse, validate, provision,
 * seed, publish. Nothing reaches the database until validation has passed,
 * and nothing becomes the active schema until it exists and holds rows.
 *
 * WHY THE H2 CHECK IS FIRST:
 * H2 has no equivalent of MySQL's CREATE DATABASE and catalog semantics, so
 * an upload under that profile would provision partially and then leave the
 * app pointed at a schema whose tables do not exist. PROJECT.md already
 * records the h2 profile as the most confusing failure mode in this project
 * - explainFailed=true with a warning that blames the query. Refusing
 * outright, with a message that names the cause, is the whole mitigation.
 */
@Service
public class SchemaUploadService {

    private static final Logger log = LoggerFactory.getLogger(SchemaUploadService.class);

    private final DdlSchemaParser parser;
    private final SchemaUploadValidator validator;
    private final SchemaProvisioner provisioner;
    private final SchemaSeeder seeder;
    private final ActiveSchemaProvider activeSchema;
    private final Environment environment;

    private volatile List<String> lastWarnings = List.of();

    public SchemaUploadService(DdlSchemaParser parser,
                               SchemaUploadValidator validator,
                               SchemaProvisioner provisioner,
                               SchemaSeeder seeder,
                               ActiveSchemaProvider activeSchema,
                               Environment environment) {
        this.parser = parser;
        this.validator = validator;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.activeSchema = activeSchema;
        this.environment = environment;
    }

    public SchemaSnapshot upload(String ddl, String label) {
        requireMySqlProfile();

        if (ddl == null || ddl.isBlank()) {
            throw new SchemaUploadException(
                    "The uploaded schema was empty. Upload a .sql file containing CREATE TABLE statements.");
        }

        ParsedSchema parsed = parser.parse(ddl);
        validator.validate(parsed);
        this.lastWarnings = parsed.warnings();

        provisioner.provision(parsed);
        Map<String, Integer> rowCounts = seeder.seed(parsed);

        SchemaSnapshot snapshot = new SchemaSnapshot(
                "upload-" + UUID.randomUUID().toString().substring(0, 8),
                label == null || label.isBlank() ? "Uploaded schema" : label,
                provisioner.uploadCatalog(),
                parsed.toKnownTables(),
                parsed.allForeignKeys(),
                rowCounts,
                Instant.now());

        log.info("Uploaded schema '{}': {} tables, {} rows total",
                snapshot.label(), snapshot.tables().size(),
                rowCounts.values().stream().mapToInt(Integer::intValue).sum());

        return activeSchema.publish(snapshot);
    }

    public SchemaSnapshot reset() {
        this.lastWarnings = List.of();
        return activeSchema.resetToBuiltIn();
    }

    public List<String> lastWarnings() {
        return lastWarnings;
    }

    private void requireMySqlProfile() {
        boolean h2 = Arrays.asList(environment.getActiveProfiles()).contains("h2");
        if (h2) {
            throw new SchemaUploadException(
                    "Schema upload needs MySQL and the backend is running under the H2 profile. "
                            + "H2 cannot create a separate catalog or produce MySQL EXPLAIN output, so an "
                            + "uploaded schema could not be analysed. Restart without -Dspring-boot.run.profiles=h2.");
        }
    }
}
```

- [ ] **Step 4: Run and verify it passes**

Run: `./mvnw test -Dtest=SchemaUploadServiceTest`
Expected: PASS, 7 tests.

`MockEnvironment` comes from `spring-boot-starter-test` (`org.springframework.mock.env.MockEnvironment`) — no new dependency.

- [ ] **Step 5: Add the configuration block**

Append to `CAPSTONE CODE/src/main/resources/application.properties`:

```properties
# =========================
# Schema upload
#
# Uploaded DDL is provisioned into a database of its OWN, never into the
# connected `querysense` database — so the built-in demo schema and
# query_history are never at risk, and "reset to built-in" is instant.
#
# WHY THE TABLES ARE SEEDED AT ALL: EXPLAIN needs statistics, not just
# structure. On an empty table MySQL reports rows=1, filtered=100.00 for
# every query — the access path is right and the magnitude is meaningless.
#
# WHY 500 AND NOT 25: the built-in demo tables hold 5-49 rows, and at that
# scale every domain suggestion ends with "the estimated row count is low,
# so the performance impact is likely minor". 500 gives an unindexed scan a
# magnitude worth reporting.
#
# The generated data is REPRODUCIBLE: `seed` fixes the RNG so the same file
# uploaded twice produces the same row estimates.
#
# REQUIRES: the connecting account needs CREATE on the upload catalog.
# NOT AVAILABLE under the h2 profile — upload refuses rather than half-run.
# =========================
querysense.schema.upload.catalog=querysense_uploads
querysense.schema.upload.max-tables=40
querysense.schema.upload.seed-rows=500
querysense.schema.upload.seed=20260910
querysense.schema.upload.max-file-bytes=2097152
```

- [ ] **Step 6: Run the full suite and commit**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/schema/upload/SchemaUploadService.java src/test/java/com/querysense/backend/schema/upload/SchemaUploadServiceTest.java src/main/resources/application.properties
git commit -m "feat(schema): orchestrate parse -> validate -> provision -> seed -> publish

Refuses under the h2 profile before touching the database, rather than
provisioning partially into an engine that cannot support it."
```

---

## Task 9: `SchemaController`, DTOs and error handling

**Files:**
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/dto/SchemaUploadRequest.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/dto/SchemaTableView.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/dto/SchemaUploadResponse.java`
- Create: `CAPSTONE CODE/src/main/java/com/querysense/backend/controller/SchemaController.java`
- Modify: `CAPSTONE CODE/src/main/java/com/querysense/backend/exception/GlobalExceptionHandler.java`
- Test: `CAPSTONE CODE/src/test/java/com/querysense/backend/controller/SchemaControllerTest.java`

**Interfaces:**
- Consumes: `SchemaUploadService.upload(String, String)`, `.reset()`, `.lastWarnings()`; `ActiveSchemaProvider.active()`.
- Produces: `POST /api/schema/upload`, `GET /api/schema/active`, `POST /api/schema/reset`, all returning `SchemaUploadResponse`.

- [ ] **Step 1: Write the DTOs**

`SchemaTableView.java`:

```java
package com.querysense.backend.dto;

import java.util.List;

public record SchemaTableView(String name, List<String> columns, int rowCount) {}
```

`SchemaUploadRequest.java`:

```java
package com.querysense.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON body form of an upload, for pasting DDL rather than choosing a
 * file. The multipart form is handled separately on the controller.
 */
public class SchemaUploadRequest {

    @NotBlank(message = "ddl must not be blank")
    private String ddl;

    private String label;

    public String getDdl() { return ddl; }
    public void setDdl(String ddl) { this.ddl = ddl; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
```

`SchemaUploadResponse.java`:

```java
package com.querysense.backend.dto;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * What the frontend needs to describe the active schema: which one it is,
 * where its tables live, what they hold, and anything the parser skipped.
 *
 * `catalog` is null for the built-in schema — the frontend renders that as
 * "the connected database" rather than inventing a name for it.
 */
public record SchemaUploadResponse(
        String schemaId,
        String label,
        String catalog,
        boolean builtIn,
        List<SchemaTableView> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        List<String> warnings,
        Instant loadedAt) {

    public static SchemaUploadResponse from(SchemaSnapshot snapshot, List<String> warnings) {
        List<SchemaTableView> tables = snapshot.tables().stream()
                .map(t -> new SchemaTableView(t.name(), t.columns(), snapshot.rowCountFor(t.name())))
                .toList();

        return new SchemaUploadResponse(
                snapshot.id(),
                snapshot.label(),
                snapshot.catalog(),
                snapshot.builtIn(),
                tables,
                snapshot.foreignKeys(),
                warnings == null ? List.of() : warnings,
                snapshot.loadedAt());
    }
}
```

- [ ] **Step 2: Write the failing controller test**

Create `SchemaControllerTest.java`:

```java
package com.querysense.backend.controller;

import com.querysense.backend.dto.SchemaUploadRequest;
import com.querysense.backend.dto.SchemaUploadResponse;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.upload.SchemaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaControllerTest {

    private SchemaUploadService uploadService;
    private ActiveSchemaProvider activeSchema;
    private SchemaController controller;

    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "shop.sql", "querysense_uploads",
            List.of(new KnownSchema.Table("customers", List.of("id", "name"))),
            List.of(), Map.of("customers", 500), Instant.now());

    @BeforeEach
    void setUp() {
        uploadService = mock(SchemaUploadService.class);
        when(uploadService.lastWarnings()).thenReturn(List.of());
        activeSchema = new ActiveSchemaProvider();
        controller = new SchemaController(uploadService, activeSchema, 2_097_152);
    }

    @Test
    @DisplayName("a multipart upload passes the file's text and its filename as the label")
    void uploadsMultipart() {
        when(uploadService.upload(anyString(), eq("shop.sql"))).thenReturn(UPLOADED);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shop.sql", "text/plain",
                "CREATE TABLE customers (id INT);".getBytes(StandardCharsets.UTF_8));

        SchemaUploadResponse response = controller.uploadFile(file);

        assertThat(response.schemaId()).isEqualTo("upload-1");
        assertThat(response.builtIn()).isFalse();
        assertThat(response.tables()).singleElement()
                .satisfies(t -> assertThat(t.rowCount()).isEqualTo(500));
        verify(uploadService).upload("CREATE TABLE customers (id INT);", "shop.sql");
    }

    @Test
    @DisplayName("a file larger than the cap is refused before it is read as a schema")
    void refusesOversizeFile() {
        byte[] big = new byte[2_097_153];
        MockMultipartFile file = new MockMultipartFile("file", "big.sql", "text/plain", big);

        assertThatThrownBy(() -> controller.uploadFile(file))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("an empty file is refused with a message naming what to upload")
    void refusesEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.sql", "text/plain", new byte[0]);

        assertThatThrownBy(() -> controller.uploadFile(file))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a JSON upload passes the pasted DDL through")
    void uploadsJson() {
        when(uploadService.upload(anyString(), anyString())).thenReturn(UPLOADED);
        SchemaUploadRequest request = new SchemaUploadRequest();
        request.setDdl("CREATE TABLE customers (id INT);");
        request.setLabel("pasted");

        controller.uploadJson(request);

        verify(uploadService).upload("CREATE TABLE customers (id INT);", "pasted");
    }

    @Test
    @DisplayName("GET /active describes the built-in schema when nothing is uploaded")
    void readsActiveSchema() {
        SchemaUploadResponse response = controller.active();

        assertThat(response.builtIn()).isTrue();
        assertThat(response.catalog()).isNull();
        assertThat(response.tables()).hasSize(KnownSchema.TABLES.size());
    }

    @Test
    @DisplayName("POST /reset returns the built-in schema")
    void resets() {
        when(uploadService.reset()).thenReturn(com.querysense.backend.schema.KnownSchema.builtIn());

        assertThat(controller.reset().builtIn()).isTrue();
    }
}
```

- [ ] **Step 3: Run and verify it fails**

Run: `./mvnw test -Dtest=SchemaControllerTest`
Expected: compilation failure — `cannot find symbol: class SchemaController`.

- [ ] **Step 4: Implement `SchemaController`**

```java
package com.querysense.backend.controller;

import com.querysense.backend.dto.SchemaUploadRequest;
import com.querysense.backend.dto.SchemaUploadResponse;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.upload.SchemaUploadService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SchemaController
 *
 * Three endpoints, deliberately separate from QueryController: uploading a
 * schema is not a query operation, and the two have no shared request shape.
 *
 * WHY BOTH A MULTIPART AND A JSON FORM:
 * A file picker is what someone with a mysqldump wants; a textarea is what
 * someone demoing wants, and pasting four CREATE TABLE statements should
 * not require saving a file first. Both funnel into the same service call.
 *
 * WHY THE SIZE CAP IS CHECKED HERE AND NOT IN THE PARSER:
 * Rejecting a 200MB file should not require reading it into a String and
 * splitting it into statements first.
 */
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaUploadService uploadService;
    private final ActiveSchemaProvider activeSchema;
    private final long maxFileBytes;

    public SchemaController(SchemaUploadService uploadService,
                            ActiveSchemaProvider activeSchema,
                            @Value("${querysense.schema.upload.max-file-bytes:2097152}") long maxFileBytes) {
        this.uploadService = uploadService;
        this.activeSchema = activeSchema;
        this.maxFileBytes = maxFileBytes;
    }

    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public SchemaUploadResponse uploadFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new SchemaUploadException(
                    "The uploaded file was empty. Choose a .sql file containing CREATE TABLE statements.");
        }
        if (file.getSize() > maxFileBytes) {
            throw new SchemaUploadException(
                    "That file is too large (" + (file.getSize() / 1024) + " KB). "
                            + "The limit is " + (maxFileBytes / 1024) + " KB — upload a schema-only dump "
                            + "(`mysqldump --no-data`) rather than one containing data.");
        }

        String ddl;
        try {
            ddl = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SchemaUploadException("The uploaded file could not be read.", e);
        }

        return SchemaUploadResponse.from(
                uploadService.upload(ddl, file.getOriginalFilename()), uploadService.lastWarnings());
    }

    @PostMapping(path = "/upload", consumes = "application/json")
    public SchemaUploadResponse uploadJson(@Valid @RequestBody SchemaUploadRequest request) {
        return SchemaUploadResponse.from(
                uploadService.upload(request.getDdl(), request.getLabel()), uploadService.lastWarnings());
    }

    @GetMapping("/active")
    public SchemaUploadResponse active() {
        return SchemaUploadResponse.from(activeSchema.active(), uploadService.lastWarnings());
    }

    @PostMapping("/reset")
    public SchemaUploadResponse reset() {
        return SchemaUploadResponse.from(uploadService.reset(), java.util.List.of());
    }
}
```

- [ ] **Step 5: Handle `SchemaUploadException` in `GlobalExceptionHandler`**

Add alongside the existing handlers, following the file's established pattern (`buildError(HttpStatus, String)`):

```java
    /**
     * WHY 400 AND WHY THE MESSAGE GOES THROUGH UNCHANGED:
     * Every SchemaUploadException is raised with a sentence written for the
     * person who uploaded the file — a missing GRANT, the h2 profile, an
     * unsupported identifier. The frontend shows `message` directly, so
     * anything vaguer here would cost the user the one thing that tells them
     * what to do. Same contract as LlmUnavailableException's 503, which
     * names the ollama command to run.
     */
    @ExceptionHandler(SchemaUploadException.class)
    public ResponseEntity<Map<String, Object>> handleSchemaUpload(SchemaUploadException ex) {
        log.warn("Schema upload rejected: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(buildError(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }
```

Add the import for `SchemaUploadException`. If the class has no `log` field, use the logger name the surrounding handlers use.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 6 more tests.

- [ ] **Step 7: Verify the endpoints against a running backend**

This is the first point at which the feature can be exercised end to end. Start MySQL and the backend (`./mvnw spring-boot:run`), then:

```bash
curl -s http://localhost:8080/api/schema/active | head -c 400
# expect: "builtIn":true, "catalog":null, nine tables

curl -s -X POST http://localhost:8080/api/schema/upload \
  -H 'Content-Type: application/json' \
  -d '{"ddl":"CREATE TABLE clients (id INT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(80), city VARCHAR(60)); CREATE TABLE invoices (id INT PRIMARY KEY AUTO_INCREMENT, client_id INT, total DECIMAL(10,2), status VARCHAR(20), CONSTRAINT fk_i_c FOREIGN KEY (client_id) REFERENCES clients (id));","label":"demo.sql"}'
# expect: builtIn:false, two tables, rowCount 500 each

curl -s -X POST http://localhost:8080/api/query/generate \
  -H 'Content-Type: application/json' \
  -d '{"query":"Show all clients","databaseType":"MySQL"}'
# expect: SELECT * FROM clients;  handledBy RULE_ENGINE

curl -s -X POST http://localhost:8080/api/query/analyze \
  -H 'Content-Type: application/json' \
  -d '{"query":"SELECT * FROM clients WHERE city = '"'"'Mumbai'"'"';","databaseType":"MySQL","domainProfile":"ECOMMERCE","resourceLevel":"LOW"}'
# THE KEY ASSERTION: explainFailed must be false and estimatedRows must be
# in the hundreds. If explainFailed is true, the catalog switch is not
# reaching MySQL. If estimatedRows is 1, seeding or ANALYZE TABLE did not run.

curl -s -X POST http://localhost:8080/api/schema/reset
curl -s -X POST http://localhost:8080/api/query/analyze \
  -H 'Content-Type: application/json' \
  -d '{"query":"SELECT * FROM customers WHERE city = '"'"'mumbai'"'"';","databaseType":"MySQL","domainProfile":"ECOMMERCE","resourceLevel":"LOW"}'
# expect: back to the demo schema, explainFailed false, ~15 rows
```

Record the actual numbers — they go into `PROJECT.md` in Task 14.

Also confirm R9 while the backend is up: `SHOW TABLES IN querysense_uploads;` must not list `query_history`, and `SHOW TABLES IN querysense;` must still list all nine demo tables plus `query_history`.

- [ ] **Step 8: Commit**

```bash
cd "CAPSTONE CODE"
git add src/main/java/com/querysense/backend/controller/SchemaController.java src/main/java/com/querysense/backend/dto src/main/java/com/querysense/backend/exception src/test/java/com/querysense/backend/controller
git commit -m "feat(api): add POST /api/schema/upload, GET /active and POST /reset

Upload accepts a multipart file or pasted DDL. Rejections carry a sentence
the user can act on, matching the LlmUnavailableException contract."
```

---

# PHASE 3 — FRONTEND

The frontend has no test runner configured — verification is `npm run lint`, `npm run build`, and looking at the screen. Steps below say what to look at.

## Task 10: API layer — contracts, module, fixtures

Follows `PROJECT.md` §4's stated rule for wiring a new endpoint: shape and `ENDPOINT_STATUS` into `contracts.js`, one module in `api/` branching on `USE_MOCK`, exported from `index.js`. Nothing in `components/` or `pages/` moves.

**Files:**
- Modify: `CAPSTONE FRONTEND/src/api/contracts.js` (append typedefs; `ENDPOINT_STATUS` at :187)
- Create: `CAPSTONE FRONTEND/src/api/schemaUpload.js`
- Modify: `CAPSTONE FRONTEND/src/api/index.js`
- Modify: `CAPSTONE FRONTEND/src/mock/fixtures.js`

**Interfaces:**
- Consumes: `apiClient`, `USE_MOCK`, `mockDelay` from `./client`; backend `SchemaUploadResponse` from Task 9.
- Produces: `uploadSchemaFile(file)`, `uploadSchemaText({ ddl, label })`, `getActiveSchema()`, `resetSchema()`, all resolving to a `SchemaSnapshot` object; `mockActiveSchema()`, `mockSchemaUpload({ ddl, label })`.

- [ ] **Step 1: Add the contract shapes**

Append to `contracts.js`, before `ENDPOINT_STATUS`:

```js
/**
 * @typedef {Object} SchemaTableView
 * @property {string} name
 * @property {string[]} columns
 * @property {number} rowCount   0 for the built-in schema — nothing measures the demo tables at startup
 */

/**
 * The active schema, from GET /api/schema/active or either upload form.
 *
 * `catalog` is null for the built-in schema. That is deliberate on the
 * backend: the demo schema lives in whatever database spring.datasource.url
 * points at, and that varies per machine, so null means "the connected
 * database". Render it as that phrase rather than inventing a name.
 *
 * @typedef {Object} SchemaSnapshot
 * @property {string} schemaId
 * @property {string} label
 * @property {string|null} catalog
 * @property {boolean} builtIn
 * @property {SchemaTableView[]} tables
 * @property {{fromTable: string, fromColumn: string, toTable: string, toColumn: string}[]} foreignKeys
 * @property {string[]} warnings    statements the parser skipped; safe to render directly
 * @property {string} loadedAt
 */
```

Then extend `ENDPOINT_STATUS`:

```js
export const ENDPOINT_STATUS = {
  queryBuilder: 'REAL',
  analysis: 'REAL',
  schemaAdvisor: 'REAL',
  history: 'REAL',
  schemaUpload: 'REAL',
  comparison: 'SIMULATED', // no backend endpoint exists at all yet
};
```

Also add a helper other components will use, next to `isLlmSource`:

```js
/**
 * Where the numbers on an analysis screen came from. An uploaded schema's
 * tables are seeded with GENERATED rows — the access path EXPLAIN reports
 * is real, the magnitude is realistic, but the distribution is invented.
 * Screens that show row estimates call this so they can say so, the same
 * way the comparison screen declares itself simulated.
 */
export function isGeneratedData(schema) {
  return Boolean(schema) && schema.builtIn === false;
}
```

- [ ] **Step 2: Write `api/schemaUpload.js`**

```js
import { apiClient, USE_MOCK, mockDelay } from './client';
import { mockActiveSchema, mockSchemaUpload, mockResetSchema } from '../mock/fixtures';

/**
 * POST /api/schema/upload — multipart form.
 *
 * WHY Content-Type IS DELETED RATHER THAN SET:
 * apiClient sets 'application/json' for every request. A multipart body
 * needs a boundary parameter that only the browser can generate, so the
 * header has to be removed and left to the browser rather than overwritten
 * with a boundary-less 'multipart/form-data'.
 *
 * @param {File} file
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function uploadSchemaFile(file) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl: await file.text(), label: file.name });
  }
  const form = new FormData();
  form.append('file', file);
  const { data } = await apiClient.post('/api/schema/upload', form, {
    headers: { 'Content-Type': undefined },
  });
  return data;
}

/**
 * POST /api/schema/upload — JSON form, for DDL pasted into the textarea.
 * @param {{ddl: string, label?: string}} payload
 * @returns {Promise<import('./contracts').SchemaSnapshot>}
 */
export async function uploadSchemaText({ ddl, label }) {
  if (USE_MOCK) {
    await mockDelay(900);
    return mockSchemaUpload({ ddl, label: label || 'Pasted schema' });
  }
  const { data } = await apiClient.post('/api/schema/upload', { ddl, label });
  return data;
}

/** GET /api/schema/active @returns {Promise<import('./contracts').SchemaSnapshot>} */
export async function getActiveSchema() {
  if (USE_MOCK) {
    await mockDelay(200);
    return mockActiveSchema();
  }
  const { data } = await apiClient.get('/api/schema/active');
  return data;
}

/** POST /api/schema/reset @returns {Promise<import('./contracts').SchemaSnapshot>} */
export async function resetSchema() {
  if (USE_MOCK) {
    await mockDelay(300);
    return mockResetSchema();
  }
  const { data } = await apiClient.post('/api/schema/reset');
  return data;
}
```

- [ ] **Step 3: Export from `api/index.js`**

```js
export { uploadSchemaFile, uploadSchemaText, getActiveSchema, resetSchema } from './schemaUpload';
```

- [ ] **Step 4: Add the fixtures**

Append to `src/mock/fixtures.js`:

```js
// The built-in schema, matching the backend's KnownSchema exactly. rowCount
// is 0 because the real GET /active reports 0 for the demo tables —
// nothing measures them at startup, and a made-up number here would make
// mock mode disagree with the backend on a visible field.
const BUILT_IN_TABLES = [
  { name: 'categories', columns: ['id', 'name'], rowCount: 0 },
  { name: 'customers', columns: ['id', 'name', 'email', 'city', 'phone'], rowCount: 0 },
  { name: 'departments', columns: ['id', 'name'], rowCount: 0 },
  { name: 'employees', columns: ['id', 'name', 'email', 'department_id', 'manager_id', 'hire_date'], rowCount: 0 },
  { name: 'order_items', columns: ['id', 'order_id', 'product_id', 'quantity', 'unit_price'], rowCount: 0 },
  { name: 'orders', columns: ['id', 'customer_id', 'employee_id', 'order_date', 'status'], rowCount: 0 },
  { name: 'payments', columns: ['id', 'order_id', 'amount', 'payment_date', 'method'], rowCount: 0 },
  { name: 'products', columns: ['id', 'name', 'price', 'category_id', 'supplier_id'], rowCount: 0 },
  { name: 'suppliers', columns: ['id', 'name', 'contact_email'], rowCount: 0 },
];

const BUILT_IN_FOREIGN_KEYS = [
  { fromTable: 'employees', fromColumn: 'department_id', toTable: 'departments', toColumn: 'id' },
  { fromTable: 'employees', fromColumn: 'manager_id', toTable: 'employees', toColumn: 'id' },
  { fromTable: 'order_items', fromColumn: 'order_id', toTable: 'orders', toColumn: 'id' },
  { fromTable: 'order_items', fromColumn: 'product_id', toTable: 'products', toColumn: 'id' },
  { fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: 'id' },
  { fromTable: 'orders', fromColumn: 'employee_id', toTable: 'employees', toColumn: 'id' },
  { fromTable: 'payments', fromColumn: 'order_id', toTable: 'orders', toColumn: 'id' },
  { fromTable: 'products', fromColumn: 'category_id', toTable: 'categories', toColumn: 'id' },
  { fromTable: 'products', fromColumn: 'supplier_id', toTable: 'suppliers', toColumn: 'id' },
];

function builtInSnapshot() {
  return {
    schemaId: 'builtin',
    label: 'QuerySense demo schema (built-in)',
    catalog: null,
    builtIn: true,
    tables: BUILT_IN_TABLES,
    foreignKeys: BUILT_IN_FOREIGN_KEYS,
    warnings: [],
    loadedAt: new Date(0).toISOString(),
  };
}

// Mock mode keeps its own idea of what is active, so uploading and
// resetting behave like the real thing across navigations.
let mockActive = builtInSnapshot();

export function mockActiveSchema() {
  return mockActive;
}

export function mockResetSchema() {
  mockActive = builtInSnapshot();
  return mockActive;
}

/**
 * A deliberately shallow CREATE TABLE reader — enough to make the mock
 * reflect what the user actually pasted, so the preview is not a lie. The
 * real parsing is JSqlParser's, on the backend.
 */
export function mockSchemaUpload({ ddl, label }) {
  const tables = [];
  const pattern = /create\s+table\s+`?(\w+)`?\s*\(([\s\S]*?)\)\s*(?:engine|;|$)/gi;
  let match = pattern.exec(ddl || '');
  while (match) {
    const columns = match[2]
      .split(',')
      .map((line) => line.trim())
      .filter((line) => line && !/^(primary|foreign|unique|key|constraint|index)\b/i.test(line))
      .map((line) => line.replace(/^`?(\w+)`?[\s\S]*$/, '$1'))
      .filter(Boolean);
    if (columns.length) {
      tables.push({ name: match[1], columns, rowCount: 500 });
    }
    match = pattern.exec(ddl || '');
  }

  mockActive = {
    schemaId: `upload-${Math.random().toString(16).slice(2, 10)}`,
    label: label || 'Uploaded schema',
    catalog: 'querysense_uploads',
    builtIn: false,
    tables,
    foreignKeys: [],
    warnings: tables.length ? [] : ['No CREATE TABLE statements were found.'],
    loadedAt: new Date().toISOString(),
  };
  return mockActive;
}
```

- [ ] **Step 5: Verify lint and build**

Run from `CAPSTONE FRONTEND/`: `npm run lint` then `npm run build`
Expected: 0 errors, at most the 3 pre-existing warnings; build clean.

- [ ] **Step 6: Commit**

```bash
cd "CAPSTONE FRONTEND"
git add src/api src/mock/fixtures.js
git commit -m "feat(api): wire the schema upload endpoints

Contract shapes, a module branching on USE_MOCK, and fixtures whose
built-in schema matches the backend's KnownSchema table for table."
```

---

## Task 11: `ActiveSchemaContext` and the persistent indicator

**Files:**
- Create: `CAPSTONE FRONTEND/src/context/ActiveSchemaContext.jsx`
- Modify: `CAPSTONE FRONTEND/src/App.jsx`
- Modify: `CAPSTONE FRONTEND/src/components/shell/EngineStatusBar.jsx`
- Modify: `CAPSTONE FRONTEND/src/components/shell/EngineStatusBar.css`

**Interfaces:**
- Consumes: `getActiveSchema`, `resetSchema` from Task 10.
- Produces: `useActiveSchema()` returning `{ schema, loading, error, refresh, applySchema, reset }`, where `applySchema(snapshot)` sets the schema from an upload response without a second fetch.

- [ ] **Step 1: Write `ActiveSchemaContext.jsx`**

```jsx
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { getActiveSchema, resetSchema } from '../api';

const ActiveSchemaContext = createContext(null);

/**
 * Which schema the whole app is currently working against.
 *
 * WHY THIS IS GLOBAL AND FETCHED AT STARTUP:
 * The active schema decides what the rule engine recognises, what the LLM
 * is grounded on, and which database EXPLAIN describes. A user who cannot
 * tell which schema is live cannot trust any screen — so it is fetched once
 * here and shown permanently in the status bar, exactly like the engine
 * indicator next to it.
 *
 * WHY A FAILED FETCH IS NOT FATAL:
 * A backend that is down should not blank the app. The schema stays null,
 * the status bar says so, and every other screen fails per-request as it
 * already does.
 */
export function ActiveSchemaProvider({ children }) {
  const [schema, setSchema] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSchema(await getActiveSchema());
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Could not read the active schema.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const reset = useCallback(async () => {
    const restored = await resetSchema();
    setSchema(restored);
    return restored;
  }, []);

  const value = useMemo(
    () => ({ schema, loading, error, refresh, applySchema: setSchema, reset }),
    [schema, loading, error, refresh, reset]
  );

  return <ActiveSchemaContext.Provider value={value}>{children}</ActiveSchemaContext.Provider>;
}

export function useActiveSchema() {
  const ctx = useContext(ActiveSchemaContext);
  if (!ctx) throw new Error('useActiveSchema must be used within ActiveSchemaProvider');
  return ctx;
}
```

- [ ] **Step 2: Wrap the app**

In `App.jsx`, import and nest the provider inside `EngineStatusProvider` and outside `WorkbenchProvider`:

```jsx
import { ActiveSchemaProvider } from './context/ActiveSchemaContext';
```

```jsx
    <EngineStatusProvider>
      <ActiveSchemaProvider>
        <WorkbenchProvider>
          {/* ...existing BrowserRouter... */}
        </WorkbenchProvider>
      </ActiveSchemaProvider>
    </EngineStatusProvider>
```

Add the route inside the `AppShell` route element:

```jsx
              <Route path="schema-source" element={<SchemaSourcePage />} />
```

with `import { SchemaSourcePage } from './pages/SchemaSourcePage';`. That page arrives in Task 12; add the import and route in that task if you would rather keep the build green between commits.

- [ ] **Step 3: Add the schema pill to `EngineStatusBar`**

The bar currently renders one pill. Add a second, to the right, reading the active schema. Keep the existing pill and its animation exactly as they are:

```jsx
import { useActiveSchema } from '../../context/ActiveSchemaContext';
```

Inside the component, before `return`:

```jsx
  const { schema } = useActiveSchema();
```

and after the existing `</AnimatePresence>`, still inside `<header>`:

```jsx
      <div
        className={`engine-status-bar__schema engine-status-bar__schema--${
          schema && !schema.builtIn ? 'uploaded' : 'builtin'
        }`}
        title={
          schema && !schema.builtIn
            ? `Uploaded schema in ${schema.catalog}. Tables are seeded with generated data.`
            : 'The built-in demo schema in the connected database.'
        }
      >
        <span className="engine-status-bar__dot" aria-hidden="true" />
        <span>
          {!schema
            ? 'Schema unknown'
            : schema.builtIn
              ? 'Built-in demo schema'
              : `${schema.label} · ${schema.tables.length} tables · generated data`}
        </span>
      </div>
```

**The word "generated" is load-bearing.** It is the whole of R6's UI mitigation: the row estimates on the analysis screens are real EXPLAIN output over invented rows, and the user is told that wherever they are in the app.

Add matching CSS to `EngineStatusBar.css`, reusing the existing pill's shape:

```css
.engine-status-bar__schema {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.12));
}

.engine-status-bar__schema--builtin {
  color: var(--text-muted, #8b93a7);
}

.engine-status-bar__schema--uploaded {
  color: var(--accent-amber, #d99a4e);
  border-color: color-mix(in srgb, var(--accent-amber, #d99a4e) 40%, transparent);
}
```

Check `src/styles/tokens.css` for the real token names and use those rather than the fallbacks above. If the header is not already a flex row, add `display: flex; align-items: center;` to `.engine-status-bar`.

- [ ] **Step 4: Verify**

Run `npm run lint` and `npm run build` — both clean. Then `npm run dev` with the backend up: the bar shows "Built-in demo schema" on load.

- [ ] **Step 5: Commit**

```bash
cd "CAPSTONE FRONTEND"
git add src/context/ActiveSchemaContext.jsx src/App.jsx src/components/shell
git commit -m "feat(shell): show the active schema permanently in the status bar

An uploaded schema is labelled as carrying generated data, so no screen
can imply its row estimates were measured."
```

---

## Task 12: The schema source page

**Files:**
- Create: `CAPSTONE FRONTEND/src/pages/SchemaSourcePage.jsx`
- Create: `CAPSTONE FRONTEND/src/components/schemaSource/SchemaSourcePanel.jsx` + `.css`
- Create: `CAPSTONE FRONTEND/src/components/schemaSource/SchemaTablePreview.jsx` + `.css`
- Modify: `CAPSTONE FRONTEND/src/components/shell/NavRail.jsx`
- Modify: `CAPSTONE FRONTEND/src/App.jsx` (route, if not added in Task 11)

**Interfaces:**
- Consumes: `useActiveSchema()`, `uploadSchemaFile`, `uploadSchemaText`, existing `PageHeader`, `Button`, `EmptyState` from `components/common/`.
- Produces: the `/schema-source` route.

- [ ] **Step 1: Write `SchemaTablePreview.jsx`**

```jsx
import './SchemaTablePreview.css';

/**
 * What the backend actually provisioned — table by table, with the row
 * count it seeded. Reading the real response rather than echoing the
 * uploaded file is the point: if the parser skipped a table, the user sees
 * that here instead of discovering it when a query fails.
 */
export function SchemaTablePreview({ schema }) {
  if (!schema || !schema.tables.length) return null;

  const fkFor = (table) => schema.foreignKeys.filter((fk) => fk.fromTable === table);

  return (
    <div className="schema-preview">
      <div className="schema-preview__head">
        <h3>{schema.label}</h3>
        <span className="schema-preview__meta">
          {schema.tables.length} tables
          {schema.catalog ? ` · ${schema.catalog}` : ' · connected database'}
        </span>
      </div>

      {!schema.builtIn && (
        <p className="schema-preview__note">
          These tables were created and filled with generated rows so that EXPLAIN has real
          statistics to plan against. The access paths it reports are real; the data itself is
          synthetic.
        </p>
      )}

      <ul className="schema-preview__list">
        {schema.tables.map((table) => (
          <li key={table.name} className="schema-preview__table">
            <div className="schema-preview__table-head">
              <code>{table.name}</code>
              {table.rowCount > 0 && (
                <span className="schema-preview__rows">{table.rowCount.toLocaleString()} rows</span>
              )}
            </div>
            <div className="schema-preview__columns">
              {table.columns.map((column) => (
                <span key={column} className="schema-preview__column">{column}</span>
              ))}
            </div>
            {fkFor(table.name).map((fk) => (
              <div key={`${fk.fromColumn}-${fk.toTable}`} className="schema-preview__fk">
                {fk.fromColumn} → {fk.toTable}.{fk.toColumn}
              </div>
            ))}
          </li>
        ))}
      </ul>

      {schema.warnings.length > 0 && (
        <ul className="schema-preview__warnings">
          {schema.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

`SchemaTablePreview.css` — follow the tokens and spacing of `src/components/schema/RecommendationList.css`; do not invent a new visual language for this screen.

- [ ] **Step 2: Write `SchemaSourcePanel.jsx`**

```jsx
import { useRef, useState } from 'react';
import { Button } from '../common/Button';
import { uploadSchemaFile, uploadSchemaText } from '../../api';
import { useActiveSchema } from '../../context/ActiveSchemaContext';
import './SchemaSourcePanel.css';

/**
 * Both ways in: choose a .sql file, or paste DDL. They hit the same
 * endpoint and the same pipeline; the textarea exists because pasting four
 * CREATE TABLE statements should not require saving a file first.
 */
export function SchemaSourcePanel() {
  const { schema, applySchema, reset } = useActiveSchema();
  const [ddl, setDdl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const fileInput = useRef(null);

  async function run(action) {
    setBusy(true);
    setError(null);
    try {
      applySchema(await action());
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'The schema could not be uploaded.');
    } finally {
      setBusy(false);
    }
  }

  const uploadingReplaces = schema && !schema.builtIn;

  return (
    <div className="schema-source">
      {/* R3: uploading drops the previous upload. Say so before, not after. */}
      <p className="schema-source__warning">
        {uploadingReplaces
          ? `Uploading replaces "${schema.label}" — the previous upload's tables are dropped. The built-in demo schema is never touched.`
          : 'Uploaded tables are created in a separate database. The built-in demo schema is left exactly as it is, and you can return to it at any time.'}
      </p>

      <div className="schema-source__actions">
        <input
          ref={fileInput}
          type="file"
          accept=".sql,text/plain"
          className="schema-source__file"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) run(() => uploadSchemaFile(file));
            e.target.value = '';
          }}
        />
        <Button onClick={() => fileInput.current?.click()} loading={busy}>
          Choose a .sql file
        </Button>
        {schema && !schema.builtIn && (
          <Button variant="ghost" onClick={() => run(reset)} loading={busy}>
            Back to the built-in schema
          </Button>
        )}
      </div>

      <label className="schema-source__label" htmlFor="ddl">
        …or paste CREATE TABLE statements
      </label>
      <textarea
        id="ddl"
        className="schema-source__textarea"
        rows={10}
        spellCheck={false}
        value={ddl}
        placeholder={'CREATE TABLE clients (\n  id INT PRIMARY KEY AUTO_INCREMENT,\n  name VARCHAR(80),\n  city VARCHAR(60)\n);'}
        onChange={(e) => setDdl(e.target.value)}
      />
      <Button
        onClick={() => run(() => uploadSchemaText({ ddl, label: 'Pasted schema' }))}
        loading={busy}
        disabled={!ddl.trim()}
      >
        Use this schema
      </Button>

      {error && <p className="schema-source__error">{error}</p>}
    </div>
  );
}
```

Check `components/common/Button.jsx` for the props it actually accepts (`variant`, `loading`, `disabled`) and adjust if they differ.

- [ ] **Step 3: Write `SchemaSourcePage.jsx`**

```jsx
import { PageHeader } from '../components/common/PageHeader';
import { SchemaSourcePanel } from '../components/schemaSource/SchemaSourcePanel';
import { SchemaTablePreview } from '../components/schemaSource/SchemaTablePreview';
import { useActiveSchema } from '../context/ActiveSchemaContext';

export function SchemaSourcePage() {
  const { schema, loading, error } = useActiveSchema();

  return (
    <div>
      <PageHeader
        title="Schema source"
        description="Upload your own schema and QuerySense works against it — the rule engine, the LLM prompt, and EXPLAIN all follow it. The built-in demo schema stays where it is."
      />
      <SchemaSourcePanel />
      {loading && <p>Reading the active schema…</p>}
      {error && <p role="alert">{error}</p>}
      <SchemaTablePreview schema={schema} />
    </div>
  );
}
```

- [ ] **Step 4: Add the nav entry**

In `NavRail.jsx`, add an icon and an item. Put it after "Workbench" — choosing a schema precedes writing a query against it:

```jsx
  schemaSource: (
    <path d="M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />
  ),
```

```jsx
  { to: '/schema-source', label: 'Schema source', icon: 'schemaSource' },
```

**Label check:** the rail already has "Schema advisor" at `/schema`. "Schema source" sits next to it and they are different things — one changes which schema is live, the other advises on indexes for the current one. If the two read as confusable on screen, rename this one "Data source" rather than shortening either.

- [ ] **Step 5: Verify**

`npm run lint` and `npm run build` clean. With the backend up, `npm run dev`, then in the browser:
- `/schema-source` renders and lists the nine built-in tables.
- Paste the two-table DDL from Task 9 Step 7 and press "Use this schema" — the preview shows both tables at 500 rows and the status bar switches to "generated data".
- Go to Workbench, ask "Show all clients" — it returns `SELECT * FROM clients;` from the rule engine.
- Analysis shows a real plan with `estimatedRows` in the hundreds, not `explainFailed`.
- Press "Back to the built-in schema" — the bar returns to "Built-in demo schema" and "Show all customers" works again.

- [ ] **Step 6: Commit**

```bash
cd "CAPSTONE FRONTEND"
git add src/pages/SchemaSourcePage.jsx src/components/schemaSource src/components/shell/NavRail.jsx src/App.jsx
git commit -m "feat(ui): add the schema source page

File picker and paste-in textarea, a preview of what was actually
provisioned, and a reset back to the built-in schema."
```

---

## Task 13: Label generated data on the analysis screens

The last piece of R6. The analysis and schema-advisor screens show row estimates; when those come from an uploaded schema they describe generated rows, and the screen must say so.

**Files:**
- Modify: `CAPSTONE FRONTEND/src/components/analysis/ExplainPlanView.jsx`
- Modify: `CAPSTONE FRONTEND/src/pages/SchemaAdvisorPage.jsx`
- Modify: `CAPSTONE FRONTEND/src/components/workbench/QueryForm.jsx:27`

- [ ] **Step 1: Add the note to `ExplainPlanView`**

Read the file first — it renders the flat summary plus one block per table from `analysis.tables[]`. Add near the top of its output, above the plan itself:

```jsx
import { useActiveSchema } from '../../context/ActiveSchemaContext';
```

```jsx
  const { schema } = useActiveSchema();
```

```jsx
      {schema && !schema.builtIn && (
        <p className="explain-plan__provenance">
          This plan is MySQL&apos;s, run against <code>{schema.catalog}</code>. The tables there were
          created from your uploaded schema and filled with generated rows — the access paths and
          index choices are real, the row counts describe synthetic data.
        </p>
      )}
```

Style `.explain-plan__provenance` as a muted note, matching whatever the file already uses for its secondary text.

- [ ] **Step 2: Add the same note to `SchemaAdvisorPage`**

That page's description already says recommendations are "anchored to observed EXPLAIN behavior". When an upload is active, append to the rendered description or add a line beneath the `PageHeader`:

```jsx
      {schema && !schema.builtIn && (
        <p className="page-note">
          Anchored to EXPLAIN over your uploaded schema, whose tables hold generated rows.
        </p>
      )}
```

with `const { schema } = useActiveSchema();` added to the component.

- [ ] **Step 3: Make the workbench placeholder follow the schema**

`QueryForm.jsx:27` hardcodes `const PLACEHOLDER = 'show customers from Mumbai';`. Replace the constant with a derivation:

```jsx
import { useActiveSchema } from '../../context/ActiveSchemaContext';
```

```jsx
const BUILT_IN_PLACEHOLDER = 'show customers from Mumbai';

function placeholderFor(schema) {
  if (!schema || schema.builtIn || !schema.tables.length) return BUILT_IN_PLACEHOLDER;
  return `show all ${schema.tables[0].name}`;
}
```

Then inside the component, `const placeholder = placeholderFor(useActiveSchema().schema);` and use `placeholder` where `PLACEHOLDER` was. A placeholder naming a table that no longer exists is a small thing that makes the whole feature look unfinished.

- [ ] **Step 4: Verify**

`npm run lint`, `npm run build` clean. In the browser with an upload active, the analysis screen carries the provenance note; after reset, it does not.

- [ ] **Step 5: Commit**

```bash
cd "CAPSTONE FRONTEND"
git add src/components/analysis src/pages/SchemaAdvisorPage.jsx src/components/workbench/QueryForm.jsx
git commit -m "feat(ui): say when a plan describes generated data

An uploaded schema's row estimates are real EXPLAIN output over synthetic
rows. The screens that show them now say so."
```

---

## Task 14: Documentation and final verification

`PROJECT.md` is the project's living record and it currently describes this feature as "not yet implemented". Leaving that stale would be worse than not writing it.

**Files:**
- Modify: `FULL CAPSTONE/PROJECT.md` (§3 API reference, §5 risk table, §7 verification record)
- Modify: `CAPSTONE CODE/CLAUDE.md` if it documents the schema layer

These files are outside both git repos — edit them, but there is nothing to commit.

- [ ] **Step 1: Run everything one final time**

```bash
cd "CAPSTONE CODE" && ./mvnw test
cd "../CAPSTONE FRONTEND" && npm run lint && npm run build
```

Record the real numbers: total backend tests passing, frontend warning count. **Do not write a number into PROJECT.md that you have not just seen in output.**

- [ ] **Step 2: Document the three endpoints in §3**

Add a `POST /api/schema/upload` subsection alongside the existing four, with the request and response shapes **captured from a live call**, not copied from this plan. Note both content types, and that `catalog` is null for the built-in schema.

- [ ] **Step 3: Update the risk table in §5**

Change its heading from "Planned feature: schema upload — risks accepted going in" to reflect that the feature now exists, and mark each of R1–R10 with what actually happened. Specifically:

- **R9 is a question this work answers.** Report what `SHOW TABLES IN querysense_uploads;` actually returned in Task 9 Step 7 — whether Hibernate touched the upload catalog. Replace the "verify explicitly" wording with the observation.
- **R6 stays open by nature.** Seeding fixes magnitude; the distribution is still invented. Say what the UI does about it and leave the risk standing.
- Keep the closing note that this work does **not** fix the `order_items` defect — and update the LLM-hallucination section to record that fix direction #1 (grounding on all tables when none are named) is now implemented, while #2 and #3 remain open.

- [ ] **Step 4: Add a session entry to §7**

Follow the format of the existing "Session of 2026-09-10 — EXPLAIN repair" entry. Include the actual `estimatedRows` observed on an uploaded table versus a built-in one — that contrast is the evidence the feature does what it claims, and it is exactly the measurement §7 already records for the empty-vs-populated case.

Be explicit about what was **not** verified. If no browser check happened, say so — §7 already carries that caveat for the current frontend and it must not silently disappear.

- [ ] **Step 5: Final review**

Re-read the diff across both repos. Confirm:
- `KnownSchema`'s nine table constants and `FOREIGN_KEYS` are byte-identical to before.
- No code path drops, truncates or alters anything outside `querysense_uploads`.
- `ExplainExecutorTest.builtInSchemaNeverSwitchesCatalog` exists and passes.
- No `TODO`, no commented-out code, no debug logging left behind.

---

## Self-Review

**Spec coverage.** §1 Goal → Tasks 1–3 and 6–7. §2 Decision (separate catalog) → Task 6. §3.1 → Tasks 1–2. §3.2 → Task 4. §3.3 → Task 5. §3.4 → Task 6. §3.5 → Task 7. §3.6 → Task 3. §3.7 → Task 2 Step 6. §4 API → Task 9. §5 Frontend → Tasks 10–13, including the `QueryForm.jsx:27` placeholder row. §6 Testing → every task's own test steps. §7 Out of scope → nothing in this plan implements MongoDB grounding, query rewriting, the comparison endpoint, or multiple retained uploads.

**One deliberate departure from the spec.** Task 2 Step 5 also changes `OllamaService.buildSchemaContext` to ground on every table when the question names none. The spec listed that under "out of scope — the LLM inventing tables". It is included because a schema-upload feature whose main use case (a question phrased in the user's own business vocabulary against their own schema) still sends the model no schema at all would not deliver what the feature is for. It is four lines, it is fix direction #1 exactly as PROJECT.md states it, and the task documents that #2 and #3 stay open. **Flag this to the user at Task 2 rather than letting it pass silently** — if they would rather keep it out, drop that step and the feature still works.

**Placeholder scan.** No TBD/TODO. Every code step carries the actual code. The two places that say "check the real token names" (Task 11 CSS) and "check Button's props" (Task 12) are instructions to read existing code the plan cannot quote without inventing it, not deferred decisions.

**Type consistency.** `SchemaSnapshot` fields are identical across Tasks 1, 8, 9. `catalog` is `null` for built-in in Task 1, Task 3's no-op branch, Task 9's DTO and Task 10's contract. `ActiveSchemaProvider.publish` returns `SchemaSnapshot` in Tasks 1, 8. `SchemaSeeder.seed` returns `Map<String,Integer>` in Tasks 7, 8. `uploadCatalog()` is used in Tasks 6, 8. Frontend `builtIn`/`tables[].rowCount`/`warnings` match the DTO in Task 9. Note the backend class `ActiveSchemaProvider` (Java) and the React `ActiveSchemaProvider` (Task 11) share a name across languages — they are unrelated, and neither file imports the other.
