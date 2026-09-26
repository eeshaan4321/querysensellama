# MongoDB Parity, Steps 1–3 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the rule engine dialect-aware and able to answer MongoDB questions against a built-in Mongo demo schema, plus the two backlog items (B1 silent-drop check, B2 `resourceLevel` in history). None of it needs a running `mongod`.

**Architecture:** A `Dialect` enum and a `DialectRegistry` select per-dialect implementations. `ActiveSchemaProvider` holds one schema per dialect. `QueryIntent` stops carrying SQL text, so the same intent can be rendered by `SqlEmitter` or by a new `MongoEmitter` that writes mongosh syntax. `MongoShellParser` reads mongosh text back into a structure, and emitter output must round-trip through it.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5 + AssertJ + Mockito, `org.bson` (already on the classpath via `spring-boot-starter-data-mongodb`), React + Vitest for the one frontend task.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md` (§3, §5, §12 steps 1–3, and B from §12 step 2)

## Global Constraints

- Every existing rule-engine test must produce **byte-identical SQL** after Task 3. No expected SQL string in any existing test is edited.
- `ActiveSchemaProvider` has no no-argument `active()`, `tables()`, `catalog()`, `findTable(name)`, `foreignKeys()`, `tableNames()`, `detectTablesMentioned(text)` or `resetToBuiltIn()` after Task 2 — every caller names a `Dialect`.
- QuerySense never executes a generated query. Nothing in this plan runs a Mongo write.
- Source comments: one-line class summary only (`/** ... */` above the class). No other comments.
- Backend tests: `cd backend && ./mvnw test` (single class: `./mvnw test -Dtest=ClassName`). Frontend: `cd frontend && npx vitest run <path>` and `npm run lint`.
- Do not start the app (`spring-boot:run`, `npm run dev`). The user runs live checks.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Mongo demo field names are snake_case (`customer_id`, `order_date`) so they bind exactly like the SQL columns; uploaded camelCase names must still bind (Task 7).

## Review Focus

1. **Case-sensitive values on MongoDB.** MySQL's collation makes `city = 'mumbai'` match `Mumbai`; MongoDB does not. `customers in mumbai` must emit the **stored** value (`"Mumbai"`) for Mongo while SQL keeps `'mumbai'`. Pinned in Task 3 (`Literal.value` carries the stored value) and Task 5 (`MongoEmitterTest.usesStoredValueNotTypedValue`).
2. **`mongod` not running.** A Mongo question must decline or build within seconds, never hang for the driver's 30 s default server-selection timeout and never 500. Pinned in Task 6 (`serverSelectionTimeoutMS=2000`, `MongoSchemaMetadataTest.serverDownGivesNoValues`).
3. **camelCase fields in an uploaded Mongo schema.** `employees hired by hire date` must bind `hireDate`. Pinned in Task 7 (`SchemaBinderTest.camelCaseFieldBinds`).
4. **Aggregating a field inside an embedded array.** `total quantity of orders per status` over `items.quantity` needs `$unwind: "$items"` before `$group`, or `$sum` adds arrays. Pinned in Task 5 (`MongoEmitterTest.embeddedArrayIsUnwoundBeforeGrouping`).
5. **A value containing a quote.** `customers with name o'brien` must stay valid in both dialects' output. Pinned in Task 5 (`MongoEmitterTest.quoteInValueRoundTrips`).

---

## Deferred to later plans, on purpose

- `mongo/demo-collections.json` and `KnownMongoSchemaMatchesSeedTest` (spec §3.3) need the upload parser, so they arrive with step 4. `KnownMongoSchema` in Task 2 is the declaration that file will be checked against.
- The other five `"MongoDB".equalsIgnoreCase` checks (spec §3.1) sit in analysis, advice, optimisation and the LLM path. They are replaced in steps 5 and 7, when those paths get a Mongo implementation. Task 7 removes only the rule engine's.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../dialect/Dialect.java` (new) | The two dialects, parsed from `databaseType` |
| `backend/.../dialect/DialectAware.java` (new) | `Dialect dialect()` marker for per-dialect implementations |
| `backend/.../dialect/DialectRegistry.java` (new) | Looks up the one implementation per dialect; fails fast if missing |
| `backend/.../schema/IndexSpec.java` (new) | An index: table, ordered key parts, unique |
| `backend/.../schema/SchemaSnapshot.java` (modify) | Gains `dialect`, `indexes` |
| `backend/.../schema/KnownSchema.java` (modify) | `Table` gains `embeddedArrays` |
| `backend/.../schema/KnownMongoSchema.java` (new) | Built-in Mongo demo (8 collections) |
| `backend/.../schema/ActiveSchemaProvider.java` (modify) | One slot per dialect |
| `backend/.../querybuilder/QueryIntent.java` (modify) | Structured intent, no SQL text |
| `backend/.../querybuilder/SqlEmitter.java` (modify) | Renders structured intent as SQL |
| `backend/.../querybuilder/QueryEmitter.java` (new) | Emitter interface per dialect |
| `backend/.../querybuilder/UnexpressibleIntentException.java` (new) | Emitter refuses an intent it cannot express faithfully |
| `backend/.../querybuilder/MongoEmitter.java` (new) | Renders intent as mongosh |
| `backend/.../mongo/MongoQuery.java` (new) | Parsed mongosh query |
| `backend/.../mongo/MongoShellParser.java` (new) | mongosh text → `MongoQuery` |
| `backend/.../mongo/MongoShellParseException.java` (new) | Parse failure with position |
| `backend/.../analysis/SchemaMetadata.java` (new) | Live columns/distinct values per dialect |
| `backend/.../analysis/SchemaMetadataService.java` (modify) | MySQL `SchemaMetadata`; listener actually registered |
| `backend/.../analysis/MongoSchemaMetadata.java` (new) | Mongo `SchemaMetadata` via `distinct` |
| `backend/.../querybuilder/SchemaBinder.java`, `JoinPathResolver.java`, `QueryIntentParser.java`, `RuleBasedQueryBuilder.java` (modify) | Take a `Dialect` |
| `backend/.../dto/QueryRequest.java`, `model/QueryHistory.java`, `service/QueryGenerationService.java`, `service/impl/QueryGenerationServiceImpl.java`, `controller/QueryController.java` (modify) | B2 |
| `frontend/src/api/queryBuilder.js`, `pages/WorkbenchPage.jsx`, `components/history/HistoryTable.jsx` (modify) | B2 |

`backend/...` = `backend/src/main/java/com/querysense/backend`; tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: `Dialect` and `DialectRegistry`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/dialect/Dialect.java`
- Create: `backend/src/main/java/com/querysense/backend/dialect/DialectAware.java`
- Create: `backend/src/main/java/com/querysense/backend/dialect/DialectRegistry.java`
- Test: `backend/src/test/java/com/querysense/backend/dialect/DialectTest.java`
- Test: `backend/src/test/java/com/querysense/backend/dialect/DialectRegistryTest.java`

**Interfaces:**
- Produces: `enum Dialect { MYSQL, MONGODB }` with `static Dialect from(String databaseType)` (throws `IllegalArgumentException`, which `GlobalExceptionHandler` already maps to 400) and `String label()` (`"MySQL"`, `"MongoDB"`); `interface DialectAware { Dialect dialect(); }`; `final class DialectRegistry<T extends DialectAware>` with `DialectRegistry(List<T> implementations, String role)` and `T get(Dialect)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectTest {

    @Test
    @DisplayName("the strings the frontend sends parse, in any case")
    void parsesFrontendStrings() {
        assertThat(Dialect.from("MySQL")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.from("mongodb")).isEqualTo(Dialect.MONGODB);
        assertThat(Dialect.from(" MONGODB ")).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("an unknown database type is refused with a message naming both choices")
    void unknownIsRefused() {
        assertThatThrownBy(() -> Dialect.from("postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("MySQL")
                .hasMessageContaining("MongoDB");
        assertThatThrownBy(() -> Dialect.from(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the label is the string history and responses already use")
    void labelMatchesStoredStrings() {
        assertThat(Dialect.MYSQL.label()).isEqualTo("MySQL");
        assertThat(Dialect.MONGODB.label()).isEqualTo("MongoDB");
    }
}
```

```java
package com.querysense.backend.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectRegistryTest {

    private record Impl(Dialect dialect) implements DialectAware {}

    @Test
    @DisplayName("returns the implementation registered for each dialect")
    void looksUpByDialect() {
        Impl mysql = new Impl(Dialect.MYSQL);
        Impl mongo = new Impl(Dialect.MONGODB);

        DialectRegistry<Impl> registry = new DialectRegistry<>(List.of(mongo, mysql), "emitter");

        assertThat(registry.get(Dialect.MYSQL)).isSameAs(mysql);
        assertThat(registry.get(Dialect.MONGODB)).isSameAs(mongo);
    }

    @Test
    @DisplayName("a dialect with no implementation fails at construction, naming the role")
    void missingDialectFailsFast() {
        assertThatThrownBy(() -> new DialectRegistry<>(List.of(new Impl(Dialect.MYSQL)), "emitter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emitter")
                .hasMessageContaining("MONGODB");
    }

    @Test
    @DisplayName("two implementations for one dialect fail at construction")
    void duplicateFailsFast() {
        assertThatThrownBy(() -> new DialectRegistry<>(List.of(
                new Impl(Dialect.MYSQL), new Impl(Dialect.MYSQL), new Impl(Dialect.MONGODB)), "emitter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MYSQL");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='DialectTest,DialectRegistryTest'`
Expected: compilation failure, `Dialect` does not exist.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.dialect;

import java.util.Locale;

/** The database dialects QuerySense generates and analyses queries for. */
public enum Dialect {
    MYSQL("MySQL"),
    MONGODB("MongoDB");

    private final String label;

    Dialect(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Dialect from(String databaseType) {
        if (databaseType != null) {
            String wanted = databaseType.trim().toUpperCase(Locale.ROOT);
            for (Dialect d : values()) {
                if (d.name().equals(wanted)) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported databaseType '" + databaseType
                + "'. Use MySQL or MongoDB.");
    }
}
```

```java
package com.querysense.backend.dialect;

/** An implementation that serves exactly one dialect. */
public interface DialectAware {
    Dialect dialect();
}
```

```java
package com.querysense.backend.dialect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One implementation per dialect, checked complete when built. */
public final class DialectRegistry<T extends DialectAware> {

    private final Map<Dialect, T> byDialect = new EnumMap<>(Dialect.class);

    public DialectRegistry(List<T> implementations, String role) {
        for (T impl : implementations) {
            if (byDialect.putIfAbsent(impl.dialect(), impl) != null) {
                throw new IllegalStateException("Two " + role + " implementations for " + impl.dialect());
            }
        }
        for (Dialect d : Dialect.values()) {
            if (!byDialect.containsKey(d)) {
                throw new IllegalStateException("No " + role + " implementation for " + d);
            }
        }
    }

    public T get(Dialect dialect) {
        return byDialect.get(dialect);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest='DialectTest,DialectRegistryTest'`
Expected: 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/dialect backend/src/test/java/com/querysense/backend/dialect
git commit -m "feat(dialect): a Dialect enum and a registry that fails fast when one is missing"
```

---

### Task 2: One active schema per dialect, and the built-in Mongo demo

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/schema/IndexSpec.java`
- Create: `backend/src/main/java/com/querysense/backend/schema/KnownMongoSchema.java`
- Modify: `backend/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java`
- Modify: `backend/src/main/java/com/querysense/backend/schema/KnownSchema.java` (record `Table`)
- Modify: `backend/src/main/java/com/querysense/backend/schema/ActiveSchemaProvider.java`
- Modify (callers): `analysis/ExplainExecutor.java:25`, `analysis/SchemaMetadataService.java:25-32,67`, `controller/HealthController.java:21`, `controller/SchemaController.java:71`, `optimization/QueryRewriter.java:158`, `querybuilder/GeneratedQueryValidator.java:57,82,101`, `querybuilder/JoinPathResolver.java:32`, `querybuilder/SchemaBinder.java:41,101,105`, `schema/upload/SchemaUploadService.java:89`, `service/impl/OllamaService.java:131,132,144`, `service/impl/QueryGenerationServiceImpl.java:107,122`
- Test: `backend/src/test/java/com/querysense/backend/schema/ActiveSchemaProviderTest.java` (rewrite)
- Test: `backend/src/test/java/com/querysense/backend/schema/KnownMongoSchemaTest.java` (new)
- Test: `backend/src/test/java/com/querysense/backend/analysis/SchemaMetadataCacheTest.java` (new)
- Test (mechanical): every test that stubs `activeSchema.active()`

**Interfaces:**
- Consumes: `Dialect` (Task 1).
- Produces:
  - `record IndexSpec(String table, List<IndexSpec.KeyPart> keys, boolean unique)` with `record KeyPart(String column, int direction)`.
  - `SchemaSnapshot(String id, String label, String catalog, List<KnownSchema.Table> tables, List<KnownSchema.ForeignKey> foreignKeys, Map<String,Integer> rowCounts, Instant loadedAt, Dialect dialect, List<IndexSpec> indexes)`. The old 7-argument constructor remains and means `MYSQL`, no indexes.
  - `KnownSchema.Table(String name, List<String> columns, List<String> embeddedArrays)`. The 2-argument constructor remains and means no embedded arrays.
  - `KnownMongoSchema.TABLES`, `FOREIGN_KEYS`, `CATALOG = "querysense"`, `builtIn()`.
  - `ActiveSchemaProvider`: `active(Dialect)`, `isBuiltIn(Dialect)`, `catalog(Dialect)`, `tables(Dialect)`, `foreignKeys(Dialect)`, `tableNames(Dialect)`, `findTable(Dialect, String)`, `detectTablesMentioned(Dialect, String)`, `publish(SchemaSnapshot)` (replaces `snapshot.dialect()`'s slot only), `resetToBuiltIn(Dialect)`, `onSchemaChanged(Consumer<Dialect>)`.

- [ ] **Step 1: Write the failing tests**

Replace `ActiveSchemaProviderTest.java` entirely:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
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

    private static final SchemaSnapshot MONGO_UPLOADED = new SchemaSnapshot(
            "upload-2", "shop.json", "querysense_uploads",
            List.of(new KnownSchema.Table("carts", List.of("_id", "total"))),
            List.of(), Map.of("carts", 50), Instant.now(), Dialect.MONGODB, List.of());

    @BeforeEach
    void setUp() {
        provider = new ActiveSchemaProvider();
    }

    @Test
    @DisplayName("each dialect starts on its own built-in schema")
    void defaultsToBuiltInPerDialect() {
        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isTrue();
        assertThat(provider.catalog(Dialect.MYSQL)).isNull();
        assertThat(provider.tableNames(Dialect.MYSQL)).containsExactlyElementsOf(KnownSchema.tableNames());
        assertThat(provider.foreignKeys(Dialect.MYSQL)).isEqualTo(KnownSchema.FOREIGN_KEYS);

        assertThat(provider.isBuiltIn(Dialect.MONGODB)).isTrue();
        assertThat(provider.catalog(Dialect.MONGODB)).isEqualTo("querysense");
        assertThat(provider.tableNames(Dialect.MONGODB)).contains("orders").doesNotContain("order_items");
    }

    @Test
    @DisplayName("publish swaps only the uploaded snapshot's own dialect")
    void publishSwapsOneSlot() {
        provider.publish(UPLOADED);

        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isFalse();
        assertThat(provider.catalog(Dialect.MYSQL)).isEqualTo("querysense_uploads");
        assertThat(provider.findTable(Dialect.MYSQL, "invoice")).isPresent();
        assertThat(provider.findTable(Dialect.MYSQL, "customers")).isEmpty();
        assertThat(provider.isBuiltIn(Dialect.MONGODB)).isTrue();

        provider.publish(MONGO_UPLOADED);

        assertThat(provider.tableNames(Dialect.MONGODB)).containsExactly("carts");
        assertThat(provider.tableNames(Dialect.MYSQL)).containsExactly("invoices");
    }

    @Test
    @DisplayName("reset restores one dialect and leaves the other alone")
    void resetRestoresOneSlot() {
        provider.publish(UPLOADED);
        provider.publish(MONGO_UPLOADED);

        provider.resetToBuiltIn(Dialect.MYSQL);

        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isTrue();
        assertThat(provider.findTable(Dialect.MYSQL, "customers")).isPresent();
        assertThat(provider.tableNames(Dialect.MONGODB)).containsExactly("carts");
    }

    @Test
    @DisplayName("listeners hear which dialect changed")
    void listenersReceiveTheDialect() {
        List<Dialect> calls = new ArrayList<>();
        provider.onSchemaChanged(calls::add);

        provider.publish(UPLOADED);
        provider.publish(MONGO_UPLOADED);
        provider.resetToBuiltIn(Dialect.MONGODB);

        assertThat(calls).containsExactly(Dialect.MYSQL, Dialect.MONGODB, Dialect.MONGODB);
    }

    @Test
    @DisplayName("detectTablesMentioned reads the named dialect's schema")
    void detectionFollowsTheDialect() {
        provider.publish(UPLOADED);

        assertThat(provider.detectTablesMentioned(Dialect.MYSQL, "show all invoices")).hasSize(1);
        assertThat(provider.detectTablesMentioned(Dialect.MYSQL, "show all customers")).isEmpty();
        assertThat(provider.detectTablesMentioned(Dialect.MONGODB, "show all customers")).hasSize(1);
    }
}
```

Create `KnownMongoSchemaTest.java`:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnownMongoSchemaTest {

    @Test
    @DisplayName("the demo is the shop domain with order items embedded in orders")
    void shopDomainWithEmbeddedItems() {
        assertThat(KnownMongoSchema.builtIn().tableNames()).containsExactlyInAnyOrder(
                "categories", "customers", "departments", "employees",
                "orders", "payments", "products", "suppliers");
        KnownSchema.Table orders = KnownMongoSchema.builtIn().tables().stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(orders.embeddedArrays()).containsExactly("items");
        assertThat(orders.columns()).contains("items.product_id", "items.quantity", "items.unit_price");
    }

    @Test
    @DisplayName("every collection's first field is _id, which the rule engine uses as the count target")
    void idComesFirst() {
        KnownMongoSchema.TABLES.forEach(t -> assertThat(t.columns().get(0)).as(t.name()).isEqualTo("_id"));
    }

    @Test
    @DisplayName("the snapshot says it is MongoDB and names its database")
    void snapshotIsMongo() {
        SchemaSnapshot snapshot = KnownMongoSchema.builtIn();
        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(snapshot.builtIn()).isTrue();
        assertThat(snapshot.catalog()).isEqualTo("querysense");
    }

    @Test
    @DisplayName("every reference points at a declared collection and field")
    void referencesResolve() {
        for (KnownSchema.ForeignKey fk : KnownMongoSchema.FOREIGN_KEYS) {
            assertThat(KnownMongoSchema.TABLES).anySatisfy(t -> {
                assertThat(t.name()).isEqualTo(fk.fromTable());
                assertThat(t.columns()).contains(fk.fromColumn());
            });
            assertThat(KnownMongoSchema.TABLES).anySatisfy(t -> {
                assertThat(t.name()).isEqualTo(fk.toTable());
                assertThat(t.columns()).contains(fk.toColumn());
            });
        }
    }
}
```

Create `SchemaMetadataCacheTest.java`. This pins a defect found while writing this plan: `registerSchemaChangeListener()` is never called, so an upload never clears the distinct-value cache.

```java
package com.querysense.backend.analysis;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaMetadataCacheTest {

    @Test
    @DisplayName("publishing a MySQL schema clears the cached values, with no extra wiring")
    void mysqlPublishClearsCache() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(List.of("Mumbai"));
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        metadata.getDistinctValues("customers", "city");
        provider.publish(new SchemaSnapshot("upload-1", "a.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers", List.of("id", "city"))),
                List.of(), Map.of(), Instant.now()));
        metadata.getDistinctValues("customers", "city");

        verify(executor, times(2)).withActiveCatalog(any());
    }

    @Test
    @DisplayName("publishing a MongoDB schema leaves the MySQL cache alone")
    void mongoPublishLeavesMysqlCache() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(List.of("Mumbai"));
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        metadata.getDistinctValues("customers", "city");
        provider.resetToBuiltIn(Dialect.MONGODB);
        metadata.getDistinctValues("customers", "city");

        verify(executor, times(1)).withActiveCatalog(any());
        assertThat(metadata.dialect()).isEqualTo(Dialect.MYSQL);
    }
}
```

`metadata.dialect()` is added in Task 6. For this task, add it now as a plain method `public Dialect dialect() { return Dialect.MYSQL; }`. Task 6 adds the `implements SchemaMetadata`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='ActiveSchemaProviderTest,KnownMongoSchemaTest,SchemaMetadataCacheTest'`
Expected: compilation failure (`isBuiltIn(Dialect)`, `KnownMongoSchema` missing).

- [ ] **Step 3: Implement the model**

`IndexSpec.java`:

```java
package com.querysense.backend.schema;

import java.util.List;

/** An index on one table or collection: its ordered key parts and whether it is unique. */
public record IndexSpec(String table, List<IndexSpec.KeyPart> keys, boolean unique) {
    public record KeyPart(String column, int direction) {}
}
```

In `KnownSchema.java`, replace the `Table` record with:

```java
    public record Table(String name, List<String> columns, List<String> embeddedArrays) {
        public Table(String name, List<String> columns) {
            this(name, columns, List.of());
        }

        public String singularName() {
            if (name.endsWith("ies")) {
                return name.substring(0, name.length() - 3) + "y";
            }
            return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        }
    }
```

Replace `SchemaSnapshot.java` with:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A complete schema for one dialect: its tables, foreign keys, indexes and database. */
public record SchemaSnapshot(
        String id,
        String label,
        String catalog,
        List<KnownSchema.Table> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        Map<String, Integer> rowCounts,
        Instant loadedAt,
        Dialect dialect,
        List<IndexSpec> indexes) {

    public static final String BUILT_IN_ID = "builtin";

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, Dialect.MYSQL, List.of());
    }

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

`KnownMongoSchema.java`:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The built-in MongoDB demo: the shop domain as collections, with order items embedded. */
public final class KnownMongoSchema {

    public static final String CATALOG = "querysense";

    public static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("categories", List.of("_id", "name")),
            new KnownSchema.Table("customers", List.of("_id", "name", "email", "city", "phone")),
            new KnownSchema.Table("departments", List.of("_id", "name")),
            new KnownSchema.Table("employees",
                    List.of("_id", "name", "email", "department_id", "manager_id", "hire_date")),
            new KnownSchema.Table("orders",
                    List.of("_id", "customer_id", "employee_id", "order_date", "status",
                            "items.product_id", "items.quantity", "items.unit_price"),
                    List.of("items")),
            new KnownSchema.Table("payments",
                    List.of("_id", "order_id", "amount", "payment_date", "method")),
            new KnownSchema.Table("products",
                    List.of("_id", "name", "price", "category_id", "supplier_id")),
            new KnownSchema.Table("suppliers", List.of("_id", "name", "contact_email")));

    public static final List<KnownSchema.ForeignKey> FOREIGN_KEYS = List.of(
            new KnownSchema.ForeignKey("employees", "department_id", "departments", "_id"),
            new KnownSchema.ForeignKey("employees", "manager_id", "employees", "_id"),
            new KnownSchema.ForeignKey("orders", "customer_id", "customers", "_id"),
            new KnownSchema.ForeignKey("orders", "employee_id", "employees", "_id"),
            new KnownSchema.ForeignKey("orders", "items.product_id", "products", "_id"),
            new KnownSchema.ForeignKey("payments", "order_id", "orders", "_id"),
            new KnownSchema.ForeignKey("products", "category_id", "categories", "_id"),
            new KnownSchema.ForeignKey("products", "supplier_id", "suppliers", "_id"));

    private KnownMongoSchema() {}

    public static SchemaSnapshot builtIn() {
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense MongoDB demo (built-in)",
                CATALOG,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH,
                Dialect.MONGODB,
                List.of());
    }
}
```

Replace `ActiveSchemaProvider.java` with:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Holds the schema currently in use for each dialect, built-in or uploaded. */
@Component
public class ActiveSchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveSchemaProvider.class);

    private final Map<Dialect, SchemaSnapshot> active = new EnumMap<>(Dialect.class);
    private final List<Consumer<Dialect>> schemaChangedListeners = new CopyOnWriteArrayList<>();

    public ActiveSchemaProvider() {
        active.put(Dialect.MYSQL, KnownSchema.builtIn());
        active.put(Dialect.MONGODB, KnownMongoSchema.builtIn());
    }

    public synchronized SchemaSnapshot active(Dialect dialect) {
        return active.get(dialect);
    }

    public boolean isBuiltIn(Dialect dialect) {
        return active(dialect).builtIn();
    }

    public String catalog(Dialect dialect) {
        return active(dialect).catalog();
    }

    public List<KnownSchema.Table> tables(Dialect dialect) {
        return active(dialect).tables();
    }

    public List<KnownSchema.ForeignKey> foreignKeys(Dialect dialect) {
        return active(dialect).foreignKeys();
    }

    public List<String> tableNames(Dialect dialect) {
        return active(dialect).tableNames();
    }

    public Optional<KnownSchema.Table> findTable(Dialect dialect, String name) {
        return SchemaLookup.findTable(tables(dialect), name);
    }

    public List<KnownSchema.Table> detectTablesMentioned(Dialect dialect, String text) {
        return SchemaLookup.detectTablesMentioned(tables(dialect), text);
    }

    public void onSchemaChanged(Consumer<Dialect> listener) {
        schemaChangedListeners.add(listener);
    }

    public SchemaSnapshot publish(SchemaSnapshot snapshot) {
        synchronized (this) {
            active.put(snapshot.dialect(), snapshot);
        }
        log.info("Active {} schema is now '{}' — {} tables, catalog {}",
                snapshot.dialect().label(), snapshot.label(), snapshot.tables().size(),
                snapshot.catalog() == null ? "(connected database)" : snapshot.catalog());
        schemaChangedListeners.forEach(l -> l.accept(snapshot.dialect()));
        return snapshot;
    }

    public SchemaSnapshot resetToBuiltIn(Dialect dialect) {
        return publish(dialect == Dialect.MONGODB ? KnownMongoSchema.builtIn() : KnownSchema.builtIn());
    }
}
```

- [ ] **Step 4: Repoint the callers (all to `Dialect.MYSQL` for now, except generation)**

Add `import com.querysense.backend.dialect.Dialect;` to each file below.

| File | Old | New |
|---|---|---|
| `analysis/ExplainExecutor.java:25` | `activeSchema.catalog()` | `activeSchema.catalog(Dialect.MYSQL)` |
| `analysis/SchemaMetadataService.java:67` | `activeSchema.findTable(tableName)` | `activeSchema.findTable(Dialect.MYSQL, tableName)` |
| `controller/HealthController.java:21` | `activeSchema.active()` | `activeSchema.active(Dialect.MYSQL)` |
| `controller/SchemaController.java:71` | `activeSchema.active()` | `activeSchema.active(Dialect.MYSQL)` |
| `optimization/QueryRewriter.java:158` | `activeSchema.findTable(table.getName())` | `activeSchema.findTable(Dialect.MYSQL, table.getName())` |
| `querybuilder/GeneratedQueryValidator.java:57` | `activeSchema.detectTablesMentioned(userInput)` | `activeSchema.detectTablesMentioned(Dialect.MYSQL, userInput)` |
| `querybuilder/GeneratedQueryValidator.java:82` | `activeSchema.tables()` | `activeSchema.tables(Dialect.MYSQL)` |
| `querybuilder/GeneratedQueryValidator.java:101` | `activeSchema.findTable(table.getName())` | `activeSchema.findTable(Dialect.MYSQL, table.getName())` |
| `querybuilder/JoinPathResolver.java:32` | `activeSchema.foreignKeys()` | `activeSchema.foreignKeys(Dialect.MYSQL)` |
| `querybuilder/SchemaBinder.java:41,101` | `activeSchema.tables()` | `activeSchema.tables(Dialect.MYSQL)` |
| `querybuilder/SchemaBinder.java:105` | `activeSchema.findTable(name)` | `activeSchema.findTable(Dialect.MYSQL, name)` |
| `schema/upload/SchemaUploadService.java:89` | `activeSchema.resetToBuiltIn()` | `activeSchema.resetToBuiltIn(Dialect.MYSQL)` |
| `service/impl/OllamaService.java:131` | `activeSchema.detectTablesMentioned(userInput)` | `activeSchema.detectTablesMentioned(Dialect.MYSQL, userInput)` |
| `service/impl/OllamaService.java:132` | `activeSchema.tables()` | `activeSchema.tables(Dialect.MYSQL)` |
| `service/impl/OllamaService.java:144` | `activeSchema.foreignKeys()` | `activeSchema.foreignKeys(Dialect.MYSQL)` |
| `service/impl/QueryGenerationServiceImpl.java:107` | `activeSchema.active().label()` | `activeSchema.active(Dialect.from(databaseType)).label()` |
| `service/impl/QueryGenerationServiceImpl.java:122` | `activeSchema.active().label()` | `activeSchema.active(Dialect.from(databaseType)).label()` |

`OllamaService.buildSchemaContext` already returns `""` for MongoDB before line 131, so `MYSQL` is correct there until step 7 of the spec.

In `SchemaMetadataService.java`, delete `void registerSchemaChangeListener() { ... }` and the unused `jakarta.annotation.PostConstruct` import. Register in the constructor instead, and add the dialect method:

```java
    public SchemaMetadataService(ExplainExecutor explainExecutor, ActiveSchemaProvider activeSchema) {
        this.explainExecutor = explainExecutor;
        this.activeSchema = activeSchema;
        activeSchema.onSchemaChanged(dialect -> {
            if (dialect == Dialect.MYSQL) {
                clearCaches();
            }
        });
    }

    public Dialect dialect() {
        return Dialect.MYSQL;
    }
```

- [ ] **Step 5: Repoint the test stubs**

In each of these test files, replace `when(activeSchema.active())` with `when(activeSchema.active(Dialect.MYSQL))`, and add `import com.querysense.backend.dialect.Dialect;`:

Run: `cd backend && grep -rln "activeSchema.active()" src/test/java`
Expected list (edit each): `service/impl/QueryHistoryRunContextTest.java`, `service/impl/SchemaScopedCacheTest.java`, `service/impl/OffSchemaCacheRoundTripTest.java`, `service/impl/QueryGenerationServiceImplTest.java`, and any other file the grep prints.

Also run `grep -rn "provider\.\(catalog\|tableNames\|findTable\|isBuiltIn\|detectTablesMentioned\|resetToBuiltIn\)(\|activeSchema\.\(catalog\|tables\|findTable\|resetToBuiltIn\)()" src/test/java`. Every hit outside `ActiveSchemaProviderTest` gets `Dialect.MYSQL` as its first argument.

- [ ] **Step 6: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: 0 failures. The count rises by the 6 new tests (4 Mongo schema, 2 cache); the 5 provider tests replace 5 old ones.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(schema): one active schema per dialect, and a built-in MongoDB demo

Also registers SchemaMetadataService's schema-change listener, which was
never called, so an upload now clears the cached column values."
```

---

### Task 3: A structured `QueryIntent` (refactor, SQL byte-identical)

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java` (replace)
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SqlEmitter.java` (replace body)
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (regions listed below)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SqlEmitterTest.java` (replace; every expected string unchanged)

**Interfaces:**
- Produces (used by Tasks 5, 7, 10):
  - `QueryIntent(Verb verb, String fromTable, JoinSpec join, List<SelectItem> selectItems, List<Predicate> where, List<Predicate> having, List<OrderItem> orderBy, Integer limit)`
  - `enum Fn { COUNT, SUM, AVG, MIN, MAX }`, `enum Op { EQ, GT, GTE, LT, LTE, IN }` with `sql()`, `enum Direction { ASC, DESC }`
  - `sealed interface Operand permits ColumnRef, Aggregate, Star`; `record ColumnRef(String table, String column)` (table null = unqualified); `record Aggregate(Fn fn, ColumnRef column)` (column null = `*`); `enum Star { INSTANCE }`
  - `record Literal(String text, boolean numeric, Object value)`: `text` is what SQL prints, and `value` is the typed value in storage (Integer for numbers, the stored-case String for text). Factories `Literal.number(int)` and `Literal.text(String typed, String stored)`.
  - `record SelectItem(Operand operand)` with `aggregated()`; `record Predicate(Operand lhs, Op op, List<Literal> values)`; `record OrderItem(ColumnRef column, Direction direction)`; `record JoinSpec(String table, ColumnRef left, ColumnRef right)`; `boolean hasAggregate()`
  - `SqlEmitter.column(ColumnRef)` static helper.

- [ ] **Step 1: Rewrite `SqlEmitterTest` against the new types (same expected strings)**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.querybuilder.QueryIntent.Aggregate;
import com.querysense.backend.querybuilder.QueryIntent.ColumnRef;
import com.querysense.backend.querybuilder.QueryIntent.Direction;
import com.querysense.backend.querybuilder.QueryIntent.Fn;
import com.querysense.backend.querybuilder.QueryIntent.JoinSpec;
import com.querysense.backend.querybuilder.QueryIntent.Literal;
import com.querysense.backend.querybuilder.QueryIntent.Op;
import com.querysense.backend.querybuilder.QueryIntent.OrderItem;
import com.querysense.backend.querybuilder.QueryIntent.Predicate;
import com.querysense.backend.querybuilder.QueryIntent.SelectItem;
import com.querysense.backend.querybuilder.QueryIntent.Star;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlEmitterTest {

    private final SqlEmitter emitter = new SqlEmitter();

    private static ColumnRef col(String column) {
        return new ColumnRef(null, column);
    }

    private static ColumnRef col(String table, String column) {
        return new ColumnRef(table, column);
    }

    @Test
    @DisplayName("a single-table select stays unqualified and byte-identical to today's output")
    void singleTableSelectIsUnqualified() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("a join qualifies every column and reads its ON clause from the intent")
    void joinQualifiesColumns() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "name"))),
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
                new JoinSpec("orders", col("customers", "id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "id")),
                        new SelectItem(col("customers", "name")),
                        new SelectItem(new Aggregate(Fn.COUNT, col("orders", "id")))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, col("orders", "id")), Op.GT,
                        List.of(Literal.number(3)))),
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
                List.of(new SelectItem(col("name"))),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("mumbai", "Mumbai")))),
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
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(), List.of(), List.of(new OrderItem(col("price"), Direction.DESC)), 5);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM products ORDER BY price DESC LIMIT 5;");
    }

    @Test
    @DisplayName("DELETE emits its WHERE and never a projection")
    void deleteEmitsWhere() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.DELETE, "customers", null,
                List.of(),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("DELETE FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("several predicates are joined with AND")
    void multiplePredicatesAreAnded() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("status"), Op.EQ, List.of(Literal.text("shipped", "SHIPPED"))),
                        new Predicate(col("customer_id"), Op.GT, List.of(Literal.number(10)))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo(
                "SELECT * FROM orders WHERE status = 'shipped' AND customer_id > 10;");
    }

    @Test
    @DisplayName("COUNT(*) is an aggregate but groups by nothing when it is the only projection")
    void bareCountHasNoGroupBy() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "orders", null,
                List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT COUNT(*) FROM orders;")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("IN quotes every value, and prints the typed text rather than the stored value")
    void inQuotesTypedText() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("city"), Op.IN, List.of(
                        Literal.text("mumbai", "Mumbai"), Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent))
                .isEqualTo("SELECT * FROM customers WHERE city IN ('mumbai', 'pune');");
    }

    @Test
    @DisplayName("a number written as a word prints as typed, exactly as before the refactor")
    void numberWordPrintsAsTyped() {
        QueryIntent intent = new QueryIntent(
                QueryIntent.Verb.SELECT, "customers", null,
                List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("id"), Op.EQ, List.of(new Literal("three", true, 3)))),
                List.of(), List.of(), null);

        assertThat(emitter.emit(intent)).isEqualTo("SELECT * FROM customers WHERE id = three;");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SqlEmitterTest`
Expected: compilation failure (`ColumnRef`, `Star` etc. do not exist).

- [ ] **Step 3: Replace `QueryIntent.java`**

```java
package com.querysense.backend.querybuilder;

import java.util.List;

/** A question resolved against the active schema, in a form any dialect's emitter can write. */
public record QueryIntent(
        Verb verb,
        String fromTable,
        JoinSpec join,
        List<SelectItem> selectItems,
        List<Predicate> where,
        List<Predicate> having,
        List<OrderItem> orderBy,
        Integer limit) {

    public enum Verb { SELECT, DELETE }

    public enum Fn { COUNT, SUM, AVG, MIN, MAX }

    public enum Direction { ASC, DESC }

    public enum Op {
        EQ("="), GT(">"), GTE(">="), LT("<"), LTE("<="), IN("IN");

        private final String sql;

        Op(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    public sealed interface Operand permits ColumnRef, Aggregate, Star {}

    public record ColumnRef(String table, String column) implements Operand {}

    public record Aggregate(Fn fn, ColumnRef column) implements Operand {}

    public enum Star implements Operand { INSTANCE }

    public record Literal(String text, boolean numeric, Object value) {
        public static Literal number(int n) {
            return new Literal(String.valueOf(n), true, n);
        }

        public static Literal text(String typed, String stored) {
            return new Literal(typed, false, stored);
        }
    }

    public record SelectItem(Operand operand) {
        public boolean aggregated() {
            return operand instanceof Aggregate;
        }
    }

    public record Predicate(Operand lhs, Op op, List<Literal> values) {}

    public record OrderItem(ColumnRef column, Direction direction) {}

    public record JoinSpec(String table, ColumnRef left, ColumnRef right) {}

    public boolean hasAggregate() {
        return selectItems.stream().anyMatch(SelectItem::aggregated);
    }
}
```

- [ ] **Step 4: Replace the body of `SqlEmitter.java`**

```java
package com.querysense.backend.querybuilder;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Writes SQL from a QueryIntent. */
@Component
public class SqlEmitter {

    public String emit(QueryIntent intent) {
        StringBuilder sql = new StringBuilder();

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            sql.append("DELETE FROM ").append(intent.fromTable());
        } else {
            sql.append("SELECT ")
                    .append(intent.selectItems().stream()
                            .map(item -> operand(item.operand()))
                            .collect(Collectors.joining(", ")))
                    .append(" FROM ")
                    .append(intent.fromTable());
        }

        if (intent.join() != null) {
            sql.append(" JOIN ").append(intent.join().table())
                    .append(" ON ").append(column(intent.join().left()))
                    .append(" = ").append(column(intent.join().right()));
        }

        if (!intent.where().isEmpty()) {
            sql.append(" WHERE ").append(renderAll(intent.where()));
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
            sql.append(" HAVING ").append(renderAll(intent.having()));
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

    public static String column(QueryIntent.ColumnRef ref) {
        return ref.table() == null ? ref.column() : ref.table() + "." + ref.column();
    }

    private static String operand(QueryIntent.Operand operand) {
        return switch (operand) {
            case QueryIntent.Star star -> "*";
            case QueryIntent.ColumnRef ref -> column(ref);
            case QueryIntent.Aggregate agg -> agg.fn().name() + "("
                    + (agg.column() == null ? "*" : column(agg.column())) + ")";
        };
    }

    private String renderAll(List<QueryIntent.Predicate> predicates) {
        return predicates.stream()
                .map(this::predicate)
                .collect(Collectors.joining(" AND "));
    }

    private String predicate(QueryIntent.Predicate p) {
        String lhs = operand(p.lhs());
        if (p.op() == QueryIntent.Op.IN) {
            return lhs + " IN " + p.values().stream()
                    .map(v -> "'" + v.text() + "'")
                    .collect(Collectors.joining(", ", "(", ")"));
        }
        return lhs + " " + p.op().sql() + " " + literal(p.values().get(0));
    }

    private static String literal(QueryIntent.Literal literal) {
        return literal.numeric() ? literal.text() : "'" + literal.text() + "'";
    }
}
```

- [ ] **Step 5: Update `QueryIntentParser.java` region by region**

(a) Replace `joinSpecFor` (lines 442–452):

```java
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
```

(b) Replace `qualify` (lines 454–459):

```java
    private QueryIntent.ColumnRef qualify(String column, KnownSchema.Table table, boolean joined) {
        return new QueryIntent.ColumnRef(joined ? table.name() : null, column);
    }
```

(c) In the comparison block (lines 266–297), directly after `String operator = ...;` add:

```java
            QueryIntent.Op op = COMPARISON_GREATER.contains(comparison.group(2))
                    ? QueryIntent.Op.GT : QueryIntent.Op.LT;
```

Replace line 279 with:

```java
                where.add(new QueryIntent.Predicate(qualify(lhsColumn.get(), subject, joined), op,
                        List.of(QueryIntent.Literal.number(literal.get()))));
```

Replace lines 286–287 with:

```java
                having.add(new QueryIntent.Predicate(
                        new QueryIntent.Aggregate(QueryIntent.Fn.COUNT,
                                new QueryIntent.ColumnRef(related.name(), countTargetColumn)),
                        op, List.of(QueryIntent.Literal.number(literal.get()))));
```

(Leave `String lhs = ...` on line 278 and `String rendered = ...` on line 285; the binding text still uses them.)

(d) Replace the preposition loop body (lines 300–341) with:

```java
        while (preposition.find()) {
            List<String> values = new ArrayList<>();
            List<String> storedValues = new ArrayList<>();
            String valueColumn = null;

            for (String candidate : preposition.group(1).split("\s+and\s+")) {
                if (VALUE_STOPWORDS.contains(candidate)
                        || binder.tableNamed(candidate).isPresent()
                        || isColumnOfEither(candidate, subject, related)) {
                    break;
                }
                Optional<ValueMatch> match = columnHolding(subject, candidate);
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
```

(e) Replace lines 354–357 (the `with` value) with:

```java
            String value = with.group(2);
            Optional<Integer> number = numberFrom(value);
            QueryIntent.Literal literalValue = number.isPresent()
                    ? new QueryIntent.Literal(value, true, number.get())
                    : QueryIntent.Literal.text(value, storedValueOf(subject, column.get(), value));
            where.add(new QueryIntent.Predicate(qualify(column.get(), subject, joined),
                    QueryIntent.Op.EQ, List.of(literalValue)));
```

(f) Replace the select-item block (lines 362–381) with:

```java
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
```

(g) Replace the order-by block (lines 407–419) with:

```java
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
```

(h) Replace `columnHolding`, `boundColumnsOf` and `bareColumnFor` (lines 479–523) with:

```java
    private record ValueMatch(String column, String stored) {}

    private Optional<ValueMatch> columnHolding(KnownSchema.Table table, String value) {
        for (String column : table.columns()) {
            Optional<String> stored = metadata.getDistinctValues(table.name(), column).stream()
                    .filter(v -> v != null && v.equalsIgnoreCase(value))
                    .findFirst();
            if (stored.isPresent()) {
                return Optional.of(new ValueMatch(column, stored.get()));
            }
        }
        return Optional.empty();
    }

    private String storedValueOf(KnownSchema.Table table, String column, String value) {
        return metadata.getDistinctValues(table.name(), column).stream()
                .filter(v -> v != null && v.equalsIgnoreCase(value))
                .findFirst()
                .orElse(value);
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
        if (!(operand instanceof QueryIntent.ColumnRef ref)) {
            return Optional.empty();
        }
        if (ref.table() != null && !ref.table().equals(table.name())) {
            return Optional.empty();
        }
        boolean known = table.columns().stream().anyMatch(c -> c.equalsIgnoreCase(ref.column()));
        return known ? Optional.of(ref.column()) : Optional.empty();
    }
```

- [ ] **Step 6: Run the whole suite — the SQL must not have moved**

Run: `cd backend && ./mvnw test`
Expected: 0 failures; the count rises by the 2 new emitter cases. If any parser or builder test fails on SQL text, the refactor changed output. Fix the parser region, never the expected string.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "refactor(rule-engine): QueryIntent carries structure, not SQL text

SqlEmitter now does all the rendering, so a second emitter can write the
same intent in another dialect. Every existing SQL expectation is
unchanged. Literals keep the stored-case value next to the typed text,
which MongoDB's case-sensitive matching will need."
```

---

### Task 4: `MongoShellParser`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/mongo/MongoQuery.java`
- Create: `backend/src/main/java/com/querysense/backend/mongo/MongoShellParseException.java`
- Create: `backend/src/main/java/com/querysense/backend/mongo/MongoShellParser.java`
- Test: `backend/src/test/java/com/querysense/backend/mongo/MongoShellParserTest.java`

**Interfaces:**
- Produces: `record MongoQuery(String collection, Operation operation, Document filter, Document projection, Document sort, Integer limit, List<Document> pipeline)` with `enum Operation { FIND, AGGREGATE, COUNT, DELETE }`; `MongoShellParser.parse(String) → MongoQuery` (throws `MongoShellParseException` with `position()`). `projection`, `sort`, `limit` and `pipeline` are null when absent, and `filter` is an empty Document when absent.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.mongo;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoShellParserTest {

    private final MongoShellParser parser = new MongoShellParser();

    @Test
    @DisplayName("find with filter, projection, sort and limit")
    void findWithEverything() {
        MongoQuery q = parser.parse(
                "db.customers.find({\"city\": \"Mumbai\"}, {\"name\": 1, \"_id\": 0}).sort({\"name\": -1}).limit(10)");

        assertThat(q.collection()).isEqualTo("customers");
        assertThat(q.operation()).isEqualTo(MongoQuery.Operation.FIND);
        assertThat(q.filter()).isEqualTo(new Document("city", "Mumbai"));
        assertThat(q.projection()).isEqualTo(new Document("name", 1).append("_id", 0));
        assertThat(q.sort()).isEqualTo(new Document("name", -1));
        assertThat(q.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("shell syntax: unquoted keys, single quotes, a trailing semicolon")
    void shellSyntax() {
        MongoQuery q = parser.parse("db.orders.find({ status: 'SHIPPED', total: { $gt: 100 } });");

        assertThat(q.filter().getString("status")).isEqualTo("SHIPPED");
        assertThat(q.filter().get("total", Document.class)).isEqualTo(new Document("$gt", 100));
    }

    @Test
    @DisplayName("find() with no argument has an empty filter")
    void emptyFind() {
        MongoQuery q = parser.parse("db.products.find()");
        assertThat(q.filter()).isEmpty();
        assertThat(q.projection()).isNull();
    }

    @Test
    @DisplayName("aggregate reads its pipeline as a list of stages")
    void aggregatePipeline() {
        MongoQuery q = parser.parse(
                "db.orders.aggregate([{\"$match\": {\"status\": \"SHIPPED\"}}, {\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])");

        assertThat(q.operation()).isEqualTo(MongoQuery.Operation.AGGREGATE);
        assertThat(q.pipeline()).hasSize(2);
        assertThat(q.pipeline().get(0).containsKey("$match")).isTrue();
    }

    @Test
    @DisplayName("countDocuments and count are COUNT, deleteMany is DELETE")
    void countAndDelete() {
        assertThat(parser.parse("db.orders.countDocuments({})").operation()).isEqualTo(MongoQuery.Operation.COUNT);
        assertThat(parser.parse("db.orders.count()").operation()).isEqualTo(MongoQuery.Operation.COUNT);
        MongoQuery delete = parser.parse("db.customers.deleteMany({\"city\": \"Pune\"})");
        assertThat(delete.operation()).isEqualTo(MongoQuery.Operation.DELETE);
        assertThat(delete.filter()).isEqualTo(new Document("city", "Pune"));
    }

    @Test
    @DisplayName("a comma or bracket inside a string does not split the arguments")
    void delimitersInsideStrings() {
        MongoQuery q = parser.parse("db.customers.find({\"name\": \"o'brien, (jr) {x}\"})");
        assertThat(q.filter().getString("name")).isEqualTo("o'brien, (jr) {x}");
    }

    @Test
    @DisplayName("ObjectId and ISODate are understood")
    void shellTypes() {
        MongoQuery q = parser.parse(
                "db.orders.find({_id: ObjectId(\"64b7f0c2a1b2c3d4e5f60718\"), order_date: {$gte: ISODate(\"2026-01-01T00:00:00Z\")}})");
        assertThat(q.filter().get("_id")).isNotNull();
        assertThat(q.filter().get("order_date", Document.class).get("$gte")).isInstanceOf(java.util.Date.class);
    }

    @Test
    @DisplayName("anything that is not a supported query fails with a position")
    void failures() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM customers"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("db.");
        assertThatThrownBy(() -> parser.parse("db.customers.drop()"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("drop");
        assertThatThrownBy(() -> parser.parse("db.customers.find({city: 'x'}"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("closing");
        assertThatThrownBy(() -> parser.parse("db.customers.find({city: }"))
                .isInstanceOf(MongoShellParseException.class);
        assertThatThrownBy(() -> parser.parse("db.customers.find({}).explain()"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("explain");
        assertThatThrownBy(() -> parser.parse("db.orders.aggregate([]).limit(3)"))
                .isInstanceOf(MongoShellParseException.class).hasMessageContaining("find()");
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(MongoShellParseException.class);
    }

    @Test
    @DisplayName("text after the query is refused, not ignored")
    void trailingTextRefused() {
        assertThatThrownBy(() -> parser.parse("db.customers.find({}) db.orders.find({})"))
                .isInstanceOf(MongoShellParseException.class)
                .satisfies(e -> assertThat(((MongoShellParseException) e).position()).isGreaterThan(0));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoShellParserTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.mongo;

import org.bson.Document;

import java.util.List;

/** A mongosh query broken into the parts analysis, rewriting and validation read. */
public record MongoQuery(
        String collection,
        Operation operation,
        Document filter,
        Document projection,
        Document sort,
        Integer limit,
        List<Document> pipeline) {

    public enum Operation { FIND, AGGREGATE, COUNT, DELETE }
}
```

```java
package com.querysense.backend.mongo;

/** A mongosh query that could not be read, with the character position where reading stopped. */
public class MongoShellParseException extends RuntimeException {

    private final int position;

    public MongoShellParseException(String message, int position) {
        super(message);
        this.position = position;
    }

    public int position() {
        return position;
    }
}
```

```java
package com.querysense.backend.mongo;

import org.bson.Document;
import org.bson.json.JsonParseException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads mongosh query text (find, aggregate, countDocuments, deleteMany) into a MongoQuery. */
@Component
public class MongoShellParser {

    private static final Pattern HEAD = Pattern.compile("^db\\.([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z]+)\\(");
    private static final Pattern CHAIN = Pattern.compile("\\s*\\.([A-Za-z]+)\\(");

    public MongoQuery parse(String text) {
        if (text == null || text.isBlank()) {
            throw new MongoShellParseException("The query is empty.", 0);
        }
        String src = text.strip();
        if (src.endsWith(";")) {
            src = src.substring(0, src.length() - 1).stripTrailing();
        }

        Matcher head = HEAD.matcher(src);
        if (!head.find()) {
            throw new MongoShellParseException(
                    "A MongoDB query must start with db.<collection>.<method>(", 0);
        }
        String collection = head.group(1);
        String method = head.group(2);
        int[] pos = {head.end()};
        List<String> args = arguments(src, pos);

        MongoQuery.Operation operation;
        Document filter = new Document();
        Document projection = null;
        List<Document> pipeline = null;
        switch (method) {
            case "find" -> {
                operation = MongoQuery.Operation.FIND;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
                projection = args.size() > 1 ? document(args.get(1), head.end()) : null;
            }
            case "aggregate" -> {
                operation = MongoQuery.Operation.AGGREGATE;
                pipeline = args.isEmpty() ? List.of() : stages(args.get(0), head.end());
            }
            case "countDocuments", "count" -> {
                operation = MongoQuery.Operation.COUNT;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
            }
            case "deleteMany" -> {
                operation = MongoQuery.Operation.DELETE;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
            }
            default -> throw new MongoShellParseException("db." + collection + "." + method
                    + "() is not one of find, aggregate, countDocuments or deleteMany.", head.start(2));
        }

        Document sort = null;
        Integer limit = null;
        while (pos[0] < src.length()) {
            Matcher chained = CHAIN.matcher(src).region(pos[0], src.length());
            if (!chained.lookingAt()) {
                throw new MongoShellParseException(
                        "Unexpected text after the query: \"" + src.substring(pos[0]).strip() + "\"", pos[0]);
            }
            String name = chained.group(1);
            if (operation != MongoQuery.Operation.FIND) {
                throw new MongoShellParseException("." + name + "() can only follow find().", chained.start(1));
            }
            pos[0] = chained.end();
            List<String> chainArgs = arguments(src, pos);
            switch (name) {
                case "sort" -> sort = chainArgs.isEmpty() ? new Document() : document(chainArgs.get(0), chained.end());
                case "limit" -> limit = integer(chainArgs, chained.end());
                case "projection" -> projection = chainArgs.isEmpty() ? null : document(chainArgs.get(0), chained.end());
                default -> throw new MongoShellParseException(
                        "." + name + "() is not supported; use sort, limit or projection.", chained.start(1));
            }
        }

        return new MongoQuery(collection, operation, filter, projection, sort, limit, pipeline);
    }

    private List<String> arguments(String src, int[] pos) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = pos[0]; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    current.append(src.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
                current.append(c);
            } else if (c == '}' || c == ']' || (c == ')' && depth > 0)) {
                depth--;
                current.append(c);
            } else if (c == ')') {
                addArgument(args, current);
                pos[0] = i + 1;
                return args;
            } else if (c == ',' && depth == 0) {
                addArgument(args, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        throw new MongoShellParseException("The query has no closing ')'.", src.length());
    }

    private static void addArgument(List<String> args, StringBuilder current) {
        String arg = current.toString().strip();
        if (!arg.isEmpty()) {
            args.add(arg);
        }
    }

    private static Document document(String json, int position) {
        try {
            return Document.parse(json);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new MongoShellParseException("Could not read " + json + ": " + e.getMessage(), position);
        }
    }

    private static List<Document> stages(String json, int position) {
        try {
            return Document.parse("{\"p\": " + json + "}").getList("p", Document.class);
        } catch (JsonParseException | IllegalArgumentException | ClassCastException e) {
            throw new MongoShellParseException(
                    "Could not read the pipeline " + json + ": " + e.getMessage(), position);
        }
    }

    private static Integer integer(List<String> args, int position) {
        try {
            return Integer.parseInt(args.get(0).strip());
        } catch (RuntimeException e) {
            throw new MongoShellParseException("limit() needs a whole number.", position);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=MongoShellParserTest`
Expected: 9 tests, 0 failures. If `shellTypes` fails because the bson reader returns a `BsonDateTime` rather than `java.util.Date`, assert on `q.filter().get("order_date", Document.class).get("$gte")` being non-null instead. The requirement is that it parses, not the Java type.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/mongo backend/src/test/java/com/querysense/backend/mongo
git commit -m "feat(mongo): read mongosh find/aggregate/count/delete text into a structure"
```

---

### Task 5: `QueryEmitter` and `MongoEmitter`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/QueryEmitter.java`
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/UnexpressibleIntentException.java`
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/MongoEmitter.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SqlEmitter.java` (implements `QueryEmitter`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/MongoEmitterTest.java`

**Interfaces:**
- Consumes: `QueryIntent` (Task 3), `ActiveSchemaProvider.findTable(Dialect, String)` and `KnownMongoSchema` (Task 2), `MongoShellParser` (Task 4, tests only).
- Produces: `interface QueryEmitter extends DialectAware { String emit(QueryIntent intent); }`; `class UnexpressibleIntentException extends RuntimeException` (message = the decline reason); `MongoEmitter(ActiveSchemaProvider)`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.querybuilder.QueryIntent.Aggregate;
import com.querysense.backend.querybuilder.QueryIntent.ColumnRef;
import com.querysense.backend.querybuilder.QueryIntent.Direction;
import com.querysense.backend.querybuilder.QueryIntent.Fn;
import com.querysense.backend.querybuilder.QueryIntent.JoinSpec;
import com.querysense.backend.querybuilder.QueryIntent.Literal;
import com.querysense.backend.querybuilder.QueryIntent.Op;
import com.querysense.backend.querybuilder.QueryIntent.OrderItem;
import com.querysense.backend.querybuilder.QueryIntent.Predicate;
import com.querysense.backend.querybuilder.QueryIntent.SelectItem;
import com.querysense.backend.querybuilder.QueryIntent.Star;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoEmitterTest {

    private final MongoEmitter emitter = new MongoEmitter(new ActiveSchemaProvider());
    private final MongoShellParser parser = new MongoShellParser();

    private static ColumnRef col(String column) {
        return new ColumnRef(null, column);
    }

    private static ColumnRef col(String table, String column) {
        return new ColumnRef(table, column);
    }

    private static QueryIntent select(String from, List<SelectItem> items, List<Predicate> where,
                                      List<OrderItem> orderBy, Integer limit) {
        return new QueryIntent(QueryIntent.Verb.SELECT, from, null, items, where, List.of(), orderBy, limit);
    }

    private String roundTrip(QueryIntent intent) {
        String emitted = emitter.emit(intent);
        MongoQuery parsed = parser.parse(emitted);
        assertThat(parsed.collection()).isEqualTo(intent.fromTable());
        return emitted;
    }

    @Test
    @DisplayName("serves MongoDB")
    void dialect() {
        assertThat(emitter.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("SELECT * is a find with no projection")
    void selectStar() {
        assertThat(roundTrip(select("customers", List.of(new SelectItem(Star.INSTANCE)), List.of(), List.of(), null)))
                .isEqualTo("db.customers.find({})");
    }

    @Test
    @DisplayName("a filter uses the stored value, not the typed one, because MongoDB matches case")
    void usesStoredValueNotTypedValue() {
        QueryIntent intent = select("customers", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("mumbai", "Mumbai")))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
    }

    @Test
    @DisplayName("IN becomes $in, comparisons become $gt/$lt with numbers unquoted")
    void inAndComparisons() {
        QueryIntent intent = select("orders", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("status"), Op.IN, List.of(
                                Literal.text("pending", "PENDING"), Literal.text("shipped", "SHIPPED"))),
                        new Predicate(col("customer_id"), Op.GT, List.of(Literal.number(10)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.orders.find({\"status\": {\"$in\": [\"PENDING\", \"SHIPPED\"]}, \"customer_id\": {\"$gt\": 10}})");
    }

    @Test
    @DisplayName("two conditions on one field are kept, under $and")
    void sameFieldTwiceUsesAnd() {
        QueryIntent intent = select("products", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("price"), Op.GT, List.of(Literal.number(10))),
                        new Predicate(col("price"), Op.LT, List.of(Literal.number(50)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.products.find({\"$and\": [{\"price\": {\"$gt\": 10}}, {\"price\": {\"$lt\": 50}}]})");
    }

    @Test
    @DisplayName("named columns project those fields and hide _id, as SQL would not return it")
    void projectionHidesId() {
        QueryIntent intent = select("customers",
                List.of(new SelectItem(col("name")), new SelectItem(col("email"))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.find({}, {\"name\": 1, \"email\": 1, \"_id\": 0})");
    }

    @Test
    @DisplayName("sort and limit chain onto find")
    void sortAndLimit() {
        QueryIntent intent = select("products", List.of(new SelectItem(Star.INSTANCE)), List.of(),
                List.of(new OrderItem(col("price"), Direction.DESC)), 5);

        assertThat(roundTrip(intent)).isEqualTo("db.products.find({}).sort({\"price\": -1}).limit(5)");
    }

    @Test
    @DisplayName("a bare COUNT(*) is countDocuments")
    void bareCount() {
        QueryIntent intent = select("orders", List.of(new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.countDocuments({})");
    }

    @Test
    @DisplayName("DELETE is deleteMany with the filter")
    void delete() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.DELETE, "customers", null, List.of(),
                List.of(new Predicate(col("city"), Op.EQ, List.of(Literal.text("pune", "Pune")))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.deleteMany({\"city\": \"Pune\"})");
    }

    @Test
    @DisplayName("an aggregate with no grouping groups everything under a null _id")
    void ungroupedAggregate() {
        QueryIntent intent = select("products",
                List.of(new SelectItem(new Aggregate(Fn.AVG, col("price")))), List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo(
                "db.products.aggregate([{\"$group\": {\"_id\": null, \"avg_price\": {\"$avg\": \"$price\"}}}])");
    }

    @Test
    @DisplayName("a join through a reference is $lookup + $unwind, and HAVING is a $match after $group")
    void joinWithHaving() {
        QueryIntent intent = new QueryIntent(QueryIntent.Verb.SELECT, "customers",
                new JoinSpec("orders", col("customers", "_id"), col("orders", "customer_id")),
                List.of(new SelectItem(col("customers", "_id")),
                        new SelectItem(col("customers", "name")),
                        new SelectItem(new Aggregate(Fn.COUNT, col("orders", "_id")))),
                List.of(),
                List.of(new Predicate(new Aggregate(Fn.COUNT, col("orders", "_id")), Op.GT,
                        List.of(Literal.number(3)))),
                List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.customers.aggregate(["
                + "{\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\", \"as\": \"orders\"}}, "
                + "{\"$unwind\": \"$orders\"}, "
                + "{\"$group\": {\"_id\": {\"_id\": \"$_id\", \"name\": \"$name\"}, \"count_orders__id\": {\"$sum\": 1}}}, "
                + "{\"$match\": {\"count_orders__id\": {\"$gt\": 3}}}])");
    }

    @Test
    @DisplayName("aggregating a field inside an embedded array unwinds the array first")
    void embeddedArrayIsUnwoundBeforeGrouping() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")),
                        new SelectItem(new Aggregate(Fn.SUM, col("items.quantity")))),
                List.of(), List.of(), null);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.aggregate(["
                + "{\"$unwind\": \"$items\"}, "
                + "{\"$group\": {\"_id\": \"$status\", \"sum_items_quantity\": {\"$sum\": \"$items.quantity\"}}}])");
    }

    @Test
    @DisplayName("sorting a grouped result by its group key sorts on _id")
    void sortAfterGroup() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")), new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(new OrderItem(col("status"), Direction.ASC)), 3);

        assertThat(roundTrip(intent)).isEqualTo("db.orders.aggregate(["
                + "{\"$group\": {\"_id\": \"$status\", \"count\": {\"$sum\": 1}}}, "
                + "{\"$sort\": {\"_id\": 1}}, {\"$limit\": 3}])");
    }

    @Test
    @DisplayName("sorting a grouped result by a field it no longer has is refused, not approximated")
    void unexpressibleSortDeclines() {
        QueryIntent intent = select("orders",
                List.of(new SelectItem(col("status")), new SelectItem(new Aggregate(Fn.COUNT, null))),
                List.of(), List.of(new OrderItem(col("order_date"), Direction.ASC)), null);

        assertThatThrownBy(() -> emitter.emit(intent))
                .isInstanceOf(UnexpressibleIntentException.class)
                .hasMessageContaining("order_date");
    }

    @Test
    @DisplayName("a quote inside a value stays valid and reads back unchanged")
    void quoteInValueRoundTrips() {
        QueryIntent intent = select("customers", List.of(new SelectItem(Star.INSTANCE)),
                List.of(new Predicate(col("name"), Op.EQ, List.of(Literal.text("o'brien", "O'Brien \"Jr\"")))),
                List.of(), null);

        MongoQuery parsed = parser.parse(emitter.emit(intent));
        assertThat(parsed.filter().getString("name")).isEqualTo("O'Brien \"Jr\"");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoEmitterTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.DialectAware;

/** Writes a QueryIntent as a query in one dialect. */
public interface QueryEmitter extends DialectAware {
    String emit(QueryIntent intent);
}
```

```java
package com.querysense.backend.querybuilder;

/** An intent the dialect cannot express faithfully; the message is the decline reason. */
public class UnexpressibleIntentException extends RuntimeException {
    public UnexpressibleIntentException(String reason) {
        super(reason);
    }
}
```

In `SqlEmitter.java` change the class line to `public class SqlEmitter implements QueryEmitter {`, add `import com.querysense.backend.dialect.Dialect;`, add `@Override` on `emit`, and add:

```java
    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }
```

`MongoEmitter.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Writes a QueryIntent as a mongosh find, countDocuments, deleteMany or aggregate. */
@Component
public class MongoEmitter implements QueryEmitter {

    private static final JsonWriterSettings JSON =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final ActiveSchemaProvider activeSchema;

    public MongoEmitter(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public String emit(QueryIntent intent) {
        String target = "db." + intent.fromTable();
        Document filter = filter(intent, intent.where());

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            return target + ".deleteMany(" + json(filter) + ")";
        }

        List<String> unwinds = intent.hasAggregate() ? embeddedArraysUsed(intent) : List.of();
        boolean bareCount = isBareCount(intent);

        if (intent.join() == null && unwinds.isEmpty() && intent.having().isEmpty()) {
            if (bareCount) {
                return target + ".countDocuments(" + json(filter) + ")";
            }
            if (!intent.hasAggregate()) {
                return find(target, intent, filter);
            }
        }

        return target + ".aggregate(" + pipeline(intent, filter, unwinds, bareCount).stream()
                .map(this::json)
                .collect(Collectors.joining(", ", "[", "]")) + ")";
    }

    private String find(String target, QueryIntent intent, Document filter) {
        StringBuilder query = new StringBuilder(target).append(".find(").append(json(filter));
        Document projection = projection(intent);
        if (!projection.isEmpty()) {
            query.append(", ").append(json(projection));
        }
        query.append(")");
        if (!intent.orderBy().isEmpty()) {
            query.append(".sort(").append(json(sort(intent))).append(")");
        }
        if (intent.limit() != null) {
            query.append(".limit(").append(intent.limit()).append(")");
        }
        return query.toString();
    }

    private List<Document> pipeline(QueryIntent intent, Document filter, List<String> unwinds, boolean bareCount) {
        List<Document> stages = new ArrayList<>();
        if (!filter.isEmpty()) {
            stages.add(new Document("$match", filter));
        }
        if (intent.join() != null) {
            QueryIntent.JoinSpec join = intent.join();
            stages.add(new Document("$lookup", new Document("from", join.table())
                    .append("localField", field(intent, join.left()))
                    .append("foreignField", join.right().column())
                    .append("as", join.table())));
            stages.add(new Document("$unwind", "$" + join.table()));
        }
        unwinds.forEach(array -> stages.add(new Document("$unwind", "$" + array)));

        if (intent.hasAggregate() && !(bareCount && intent.having().isEmpty() && intent.orderBy().isEmpty())) {
            List<QueryIntent.ColumnRef> keys = groupKeys(intent);
            Document group = new Document("_id", groupId(intent, keys));
            for (QueryIntent.SelectItem item : intent.selectItems()) {
                if (item.operand() instanceof QueryIntent.Aggregate agg) {
                    group.append(alias(intent, agg), accumulator(intent, agg));
                }
            }
            stages.add(new Document("$group", group));
            if (!intent.having().isEmpty()) {
                Document having = new Document();
                for (QueryIntent.Predicate p : intent.having()) {
                    having.append(alias(intent, (QueryIntent.Aggregate) p.lhs()), condition(p));
                }
                stages.add(new Document("$match", having));
            }
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

        if (!intent.orderBy().isEmpty()) {
            stages.add(new Document("$sort", sort(intent)));
        }
        if (intent.limit() != null) {
            stages.add(new Document("$limit", intent.limit()));
        }
        if (bareCount) {
            stages.add(new Document("$count", "count"));
        } else {
            Document projection = projection(intent);
            if (!projection.isEmpty()) {
                stages.add(new Document("$project", projection));
            }
        }
        return stages;
    }

    private Document filter(QueryIntent intent, List<QueryIntent.Predicate> predicates) {
        Document out = new Document();
        List<Document> repeated = new ArrayList<>();
        for (QueryIntent.Predicate p : predicates) {
            String field = field(intent, (QueryIntent.ColumnRef) p.lhs());
            Object condition = condition(p);
            if (out.containsKey(field)) {
                repeated.add(new Document(field, condition));
            } else {
                out.append(field, condition);
            }
        }
        if (repeated.isEmpty()) {
            return out;
        }
        List<Document> all = new ArrayList<>();
        out.forEach((k, v) -> all.add(new Document(k, v)));
        all.addAll(repeated);
        return new Document("$and", all);
    }

    private static Object condition(QueryIntent.Predicate p) {
        List<Object> values = p.values().stream().map(QueryIntent.Literal::value).toList();
        return switch (p.op()) {
            case EQ -> values.get(0);
            case IN -> new Document("$in", values);
            case GT -> new Document("$gt", values.get(0));
            case GTE -> new Document("$gte", values.get(0));
            case LT -> new Document("$lt", values.get(0));
            case LTE -> new Document("$lte", values.get(0));
        };
    }

    private Document projection(QueryIntent intent) {
        Document projection = new Document();
        for (QueryIntent.SelectItem item : intent.selectItems()) {
            if (item.operand() instanceof QueryIntent.ColumnRef ref) {
                projection.append(field(intent, ref), 1);
            }
        }
        if (!projection.isEmpty() && !projection.containsKey("_id")) {
            projection.append("_id", 0);
        }
        return projection;
    }

    private Document sort(QueryIntent intent) {
        Document sort = new Document();
        intent.orderBy().forEach(o -> sort.append(field(intent, o.column()), direction(o)));
        return sort;
    }

    private static int direction(QueryIntent.OrderItem o) {
        return o.direction() == QueryIntent.Direction.DESC ? -1 : 1;
    }

    private static boolean isBareCount(QueryIntent intent) {
        return intent.selectItems().size() == 1
                && intent.selectItems().get(0).operand() instanceof QueryIntent.Aggregate agg
                && agg.fn() == QueryIntent.Fn.COUNT
                && agg.column() == null;
    }

    private static List<QueryIntent.ColumnRef> groupKeys(QueryIntent intent) {
        return intent.selectItems().stream()
                .map(QueryIntent.SelectItem::operand)
                .filter(o -> o instanceof QueryIntent.ColumnRef)
                .map(o -> (QueryIntent.ColumnRef) o)
                .toList();
    }

    private Object groupId(QueryIntent intent, List<QueryIntent.ColumnRef> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() == 1) {
            return "$" + field(intent, keys.get(0));
        }
        Document id = new Document();
        keys.forEach(k -> id.append(alias(intent, k), "$" + field(intent, k)));
        return id;
    }

    private String groupedPath(QueryIntent intent, List<QueryIntent.ColumnRef> keys, QueryIntent.ColumnRef column) {
        String wanted = field(intent, column);
        boolean grouped = keys.stream().anyMatch(k -> field(intent, k).equals(wanted));
        if (!grouped) {
            throw new UnexpressibleIntentException("The question sorts by " + column.column()
                    + " after grouping, and a grouped MongoDB result no longer has that field.");
        }
        return keys.size() == 1 ? "_id" : "_id." + alias(intent, column);
    }

    private Document accumulator(QueryIntent intent, QueryIntent.Aggregate agg) {
        if (agg.fn() == QueryIntent.Fn.COUNT) {
            return new Document("$sum", 1);
        }
        String operator = "$" + agg.fn().name().toLowerCase();
        return new Document(operator, "$" + field(intent, agg.column()));
    }

    private String alias(QueryIntent intent, QueryIntent.ColumnRef ref) {
        return field(intent, ref).replace('.', '_');
    }

    private String alias(QueryIntent intent, QueryIntent.Aggregate agg) {
        if (agg.column() == null) {
            return "count";
        }
        return agg.fn().name().toLowerCase() + "_" + alias(intent, agg.column());
    }

    private static String field(QueryIntent intent, QueryIntent.ColumnRef ref) {
        boolean base = ref.table() == null || ref.table().equals(intent.fromTable());
        return base ? ref.column() : ref.table() + "." + ref.column();
    }

    private List<String> embeddedArraysUsed(QueryIntent intent) {
        List<String> arrays = activeSchema.findTable(Dialect.MONGODB, intent.fromTable())
                .map(KnownSchema.Table::embeddedArrays)
                .orElse(List.of());
        Set<String> used = new LinkedHashSet<>();
        for (QueryIntent.SelectItem item : intent.selectItems()) {
            QueryIntent.ColumnRef ref = switch (item.operand()) {
                case QueryIntent.ColumnRef c -> c;
                case QueryIntent.Aggregate a -> a.column();
                case QueryIntent.Star s -> null;
            };
            if (ref == null) {
                continue;
            }
            String field = field(intent, ref);
            arrays.stream().filter(a -> field.startsWith(a + ".")).forEach(used::add);
        }
        return List.copyOf(used);
    }

    private String json(Document document) {
        return document.toJson(JSON);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest='MongoEmitterTest,SqlEmitterTest'`
Expected: 15 + 10 tests, 0 failures. `joinWithHaving` asserts the key order `{"_id": "$_id", "name": "$name"}`, which is the order the intent lists them in, and the emitter must preserve it.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rule-engine): a MongoDB emitter that writes the same intent as mongosh

find, countDocuments, deleteMany, and aggregate for grouping, HAVING,
reference joins ($lookup) and embedded arrays ($unwind). Output reads
back through MongoShellParser, and anything it cannot say faithfully
is refused rather than approximated."
```

---

### Task 6: `SchemaMetadata` per dialect, and `MongoSchemaMetadata`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadata.java`
- Create: `backend/src/main/java/com/querysense/backend/analysis/MongoSchemaMetadata.java`
- Modify: `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java` (implements `SchemaMetadata`)
- Modify: `backend/src/main/resources/application.properties` (uncomment the Mongo URI, with a short timeout)
- Test: `backend/src/test/java/com/querysense/backend/analysis/MongoSchemaMetadataTest.java`

**Interfaces:**
- Consumes: `ActiveSchemaProvider` (Task 2), `DialectAware` (Task 1).
- Produces: `interface SchemaMetadata extends DialectAware { List<String> getColumnNames(String table); List<String> getDistinctValues(String table, String column); }`. `MongoSchemaMetadata(MongoClient, ActiveSchemaProvider)` returns `List.of()` for `getColumnNames`, meaning "no live list, skip the drift check". `static String asText(BsonValue)`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.analysis;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonBoolean;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSchemaMetadataTest {

    @Test
    @DisplayName("serves MongoDB, and has no live column list to check drift against")
    void dialectAndColumns() {
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(mock(MongoClient.class), new ActiveSchemaProvider());
        assertThat(metadata.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(metadata.getColumnNames("customers")).isEmpty();
    }

    @Test
    @DisplayName("a field the active Mongo schema does not declare is never queried")
    void undeclaredFieldIsNotQueried() {
        MongoClient client = mock(MongoClient.class);
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());

        assertThat(metadata.getDistinctValues("customers", "age")).isEmpty();
        verify(client, never()).getDatabase(anyString());
    }

    @Test
    @DisplayName("a server that is down gives no values rather than an error")
    void serverDownGivesNoValues() {
        MongoClient client = mock(MongoClient.class);
        when(client.getDatabase(anyString())).thenThrow(new MongoTimeoutException("no server"));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());

        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();
    }

    @Test
    @DisplayName("stored values become the text the rule engine compares against")
    void valuesAsText() {
        assertThat(MongoSchemaMetadata.asText(new BsonString("Mumbai"))).isEqualTo("Mumbai");
        assertThat(MongoSchemaMetadata.asText(new BsonInt32(3))).isEqualTo("3");
        assertThat(MongoSchemaMetadata.asText(new BsonInt64(4L))).isEqualTo("4");
        assertThat(MongoSchemaMetadata.asText(new BsonDouble(2.5))).isEqualTo("2.5");
        assertThat(MongoSchemaMetadata.asText(new BsonBoolean(true))).isEqualTo("true");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaMetadataTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.analysis;

import com.querysense.backend.dialect.DialectAware;

import java.util.List;

/** Live column names and stored values for one dialect's active schema. */
public interface SchemaMetadata extends DialectAware {
    List<String> getColumnNames(String tableName);

    List<String> getDistinctValues(String tableName, String columnName);
}
```

In `SchemaMetadataService.java` change the class line to `public class SchemaMetadataService implements SchemaMetadata {` and add `@Override` to `dialect()`, `getColumnNames` and `getDistinctValues`.

```java
package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads stored field values from the active MongoDB schema's database with distinct. */
@Component
public class MongoSchemaMetadata implements SchemaMetadata {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaMetadata.class);
    private static final int MAX_VALUES = 500;

    private final MongoClient client;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();

    public MongoSchemaMetadata(MongoClient client, ActiveSchemaProvider activeSchema) {
        this.client = client;
        this.activeSchema = activeSchema;
        activeSchema.onSchemaChanged(dialect -> {
            if (dialect == Dialect.MONGODB) {
                distinctValueCache.clear();
            }
        });
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public List<String> getColumnNames(String tableName) {
        return List.of();
    }

    @Override
    public List<String> getDistinctValues(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return List.of();
        }
        return distinctValueCache.computeIfAbsent(tableName + "." + columnName,
                k -> load(tableName, columnName));
    }

    private List<String> load(String tableName, String columnName) {
        boolean declared = activeSchema.findTable(Dialect.MONGODB, tableName)
                .map(t -> t.columns().contains(columnName))
                .orElse(false);
        if (!declared) {
            log.warn("Refused to load distinct values for undeclared field: {}.{}", tableName, columnName);
            return List.of();
        }
        try {
            List<String> values = new ArrayList<>();
            client.getDatabase(activeSchema.catalog(Dialect.MONGODB))
                    .getCollection(tableName)
                    .distinct(columnName, BsonValue.class)
                    .forEach(v -> {
                        if (values.size() < MAX_VALUES && v != null && !v.isNull()) {
                            values.add(asText(v));
                        }
                    });
            return List.copyOf(values);
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{} from MongoDB: {}",
                    tableName, columnName, e.getMessage());
            return List.of();
        }
    }

    static String asText(BsonValue value) {
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isInt32()) {
            return String.valueOf(value.asInt32().getValue());
        }
        if (value.isInt64()) {
            return String.valueOf(value.asInt64().getValue());
        }
        if (value.isDouble()) {
            return String.valueOf(value.asDouble().getValue());
        }
        if (value.isBoolean()) {
            return String.valueOf(value.asBoolean().getValue());
        }
        return value.toString();
    }
}
```

In `application.properties`, replace the commented line `# spring.data.mongodb.uri=mongodb://localhost:27017/querysense` with:

```properties
spring.data.mongodb.uri=mongodb://localhost:27017/querysense?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000
```

A failed load is cached as an empty list, exactly as `SchemaMetadataService` does. So a stopped server costs one 2-second wait per field, then the rule engine declines value filters until the next Mongo schema change.

- [ ] **Step 4: Run to verify it passes, then the whole suite**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaMetadataTest` then `./mvnw test`
Expected: 4 tests pass, then the full suite has 0 failures. `BackendApplicationTests` loads the context with the new bean, which needs only the auto-configured `MongoClient`, so no server is required.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(analysis): stored values per dialect, with MongoDB read through distinct

The Mongo URI is now configured, with a 2 s server-selection timeout so a
stopped mongod slows one lookup instead of hanging every request."
```

---

### Task 7: The rule engine takes a dialect, and MongoDB stops being declined

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/JoinPathResolver.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/RuleBasedQueryBuilder.java`
- Test: `SchemaBinderTest.java`, `JoinPathResolverTest.java`, `QueryIntentParserTest.java`, `DeclineBoundaryTest.java`, `RuleBasedQueryBuilderSchemaTest.java`, `RuleTraceWordingTest.java` (mechanical updates plus new cases)
- Create: `backend/src/test/java/com/querysense/backend/querybuilder/TestParsers.java`
- Create: `backend/src/test/java/com/querysense/backend/querybuilder/MongoRuleEngineTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `SchemaBinder.bindTables(String normalizedText, Dialect)`, `tableNamed(String, Dialect)`, `tables(Dialect)`. `bindColumn(Table, String)` is unchanged in signature but learns `_id`, dotted paths and camelCase.
  - `JoinPathResolver.resolve(String a, String b, Dialect)`.
  - `QueryIntentParser(SchemaBinder, JoinPathResolver, List<SchemaMetadata>, List<QueryEmitter>)` and `parse(String input, Dialect)`.
  - `RuleBasedQueryBuilder.build(String input, String databaseType)` (signature unchanged; parses the dialect, no Mongo gate).
  - Test helper `TestParsers.parser(ActiveSchemaProvider, SchemaMetadata mysql, SchemaMetadata mongo)`.

- [ ] **Step 1: Write the failing tests**

`TestParsers.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.schema.ActiveSchemaProvider;

import java.util.List;

final class TestParsers {

    private TestParsers() {}

    static QueryIntentParser parser(ActiveSchemaProvider schemas, SchemaMetadata mysql, SchemaMetadata mongo) {
        return new QueryIntentParser(
                new SchemaBinder(schemas),
                new JoinPathResolver(schemas),
                List.of(mysql, mongo),
                List.of(new SqlEmitter(), new MongoEmitter(schemas)));
    }
}
```

`MongoRuleEngineTest.java`:

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

class MongoRuleEngineTest {

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        SchemaMetadata mysql = metadata(Dialect.MYSQL);
        SchemaMetadata mongo = metadata(Dialect.MONGODB);
        builder = new RuleBasedQueryBuilder(TestParsers.parser(schemas, mysql, mongo));
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        return m;
    }

    @Test
    @DisplayName("a simple MongoDB question is now built by the rule engine")
    void mongoIsBuilt() {
        RuleBuildResult result = builder.build("Show all customers", "MongoDB");
        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("db.customers.find({})");
    }

    @Test
    @DisplayName("the filter carries the stored value for MongoDB and the typed value for SQL")
    void storedValueForMongoTypedForSql() {
        assertThat(builder.build("customers in mumbai", "MongoDB").sql())
                .isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
        assertThat(builder.build("customers in mumbai", "MySQL").sql())
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("the join sample prompt builds a $lookup on the declared reference")
    void joinBuildsLookup() {
        RuleBuildResult result = builder.build("Which customers placed more than three orders", "MongoDB");
        assertThat(result.built()).isTrue();
        assertThat(result.sql())
                .startsWith("db.customers.aggregate([")
                .contains("\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\"")
                .contains("{\"$gt\": 3}");
        assertThat(result.trace().joinEdge().fromColumn()).isEqualTo("customer_id");
    }

    @Test
    @DisplayName("counting and deleting use countDocuments and deleteMany")
    void countAndDelete() {
        assertThat(builder.build("how many orders are there", "MongoDB").sql())
                .isEqualTo("db.orders.countDocuments({})");
        assertThat(builder.build("delete customers in pune", "MongoDB").sql())
                .isEqualTo("db.customers.deleteMany({\"city\": \"Pune\"})");
        assertThat(builder.build("delete all customers", "MongoDB").built()).isFalse();
    }

    @Test
    @DisplayName("an emitter refusal becomes a decline with the emitter's reason")
    void emitterRefusalDeclines() {
        RuleBuildResult result = builder.build("how many customers per city sorted by name", "MongoDB");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("name");
    }

    @Test
    @DisplayName("an unknown database type is refused")
    void unknownDatabaseType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build("Show all customers", "Oracle"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Append to `SchemaBinderTest.java`, which already builds `binder` from an `ActiveSchemaProvider`. Add `import com.querysense.backend.dialect.Dialect;` and `import com.querysense.backend.schema.KnownMongoSchema;` if they're missing:

```java
    @Test
    @DisplayName("'id' binds MongoDB's _id rather than being ambiguous among the *_id fields")
    void idBindsUnderscoreId() {
        KnownSchema.Table orders = KnownMongoSchema.TABLES.stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(binder.bindColumn(orders, "ids")).contains("_id");
    }

    @Test
    @DisplayName("a field inside an embedded document binds by its last segment")
    void dottedPathBinds() {
        KnownSchema.Table orders = KnownMongoSchema.TABLES.stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(binder.bindColumn(orders, "quantity")).contains("items.quantity");
        assertThat(binder.bindColumn(orders, "unit price")).contains("items.unit_price");
    }

    @Test
    @DisplayName("a camelCase field from an uploaded schema binds from plain words")
    void camelCaseFieldBinds() {
        KnownSchema.Table employees = new KnownSchema.Table("employees",
                List.of("_id", "fullName", "hireDate", "departmentId"));
        assertThat(binder.bindColumn(employees, "hire date")).contains("hireDate");
        assertThat(binder.bindColumn(employees, "full name")).contains("fullName");
    }

    @Test
    @DisplayName("table binding reads the named dialect's schema")
    void bindTablesPerDialect() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name).containsExactly("order_items");
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items"), Dialect.MONGODB))
                .extracting(KnownSchema.Table::name).containsExactly("orders");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='MongoRuleEngineTest,SchemaBinderTest'`
Expected: compilation failure.

- [ ] **Step 3: `SchemaBinder`**

Add `import com.querysense.backend.dialect.Dialect;`. Change the three schema-reading methods:

```java
    public List<KnownSchema.Table> bindTables(String normalizedText, Dialect dialect) {
        List<Span> spans = new ArrayList<>();
        for (KnownSchema.Table table : activeSchema.tables(dialect)) {
            longestSpanFor(normalizedText, table).ifPresent(spans::add);
        }
```

(the rest of the method body is unchanged)

```java
    public List<KnownSchema.Table> tables(Dialect dialect) {
        return activeSchema.tables(dialect);
    }

    public Optional<KnownSchema.Table> tableNamed(String name, Dialect dialect) {
        return activeSchema.findTable(dialect, name);
    }
```

Replace `bindColumn` with:

```java
    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        String token = normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        String underscored = token.replace(' ', '_');
        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1)
                : underscored;

        for (String candidate : List.of(underscored, singular)) {
            for (String col : table.columns()) {
                String snake = snakeCase(col);
                if (snake.equals(candidate) || snake.equals("_" + candidate)) {
                    return Optional.of(col);
                }
            }
        }

        List<String> suffixMatches = table.columns().stream()
                .filter(col -> {
                    String snake = snakeCase(col);
                    return snake.endsWith("_" + singular) || snake.endsWith("_" + underscored)
                            || snake.endsWith("." + singular) || snake.endsWith("." + underscored);
                })
                .distinct()
                .toList();
        return suffixMatches.size() == 1 ? Optional.of(suffixMatches.get(0)) : Optional.empty();
    }

    static String snakeCase(String column) {
        return column.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
```

The exact-match loop now tries the plain token and then the singular. That's the same order as before, with `_id` and camelCase added. Existing SQL expectations must not change. If one does, the ordering above is the cause.

- [ ] **Step 4: `JoinPathResolver`**

Add the import and change `resolve`:

```java
    public JoinResolution resolve(String tableA, String tableB, Dialect dialect) {
        if (tableA == null || tableB == null) {
            return new None();
        }

        List<KnownSchema.ForeignKey> edges = activeSchema.foreignKeys(dialect).stream()
```

(the rest is unchanged)

- [ ] **Step 5: `QueryIntentParser`**

Replace the fields and constructor (lines 103–114) with:

```java
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
```

Imports: replace `com.querysense.backend.analysis.SchemaMetadataService` with `com.querysense.backend.analysis.SchemaMetadata`, and add `com.querysense.backend.dialect.Dialect` and `com.querysense.backend.dialect.DialectRegistry`.

Change the signature and start of `parse`:

```java
    public RuleBuildResult parse(String rawInput, Dialect dialect) {
        String text = SchemaBinder.normalize(rawInput);
        List<RuleTrace.Binding> bindings = new ArrayList<>();
        SchemaMetadata metadata = metadataByDialect.get(dialect);
```

Inside `parse`:
- `binder.bindTables(text)` → `binder.bindTables(text, dialect)`
- `joins.resolve(subject.name(), related.name())` → `joins.resolve(subject.name(), related.name(), dialect)`
- `binder.tableNamed(candidate)` → `binder.tableNamed(candidate, dialect)`
- `metadata.getColumnNames(table.name())` stays as written, and now reads the local `metadata`.
- `columnHolding(subject, candidate)` → `columnHolding(subject, candidate, metadata)`
- `storedValueOf(subject, column.get(), value)` → `storedValueOf(subject, column.get(), value, metadata)`
- `extractTrailingColumnPhrase(text, subject)` → `extractTrailingColumnPhrase(text, subject, dialect)`

Replace `String sql = emitter.emit(intent);` with:

```java
        String sql;
        try {
            sql = emitters.get(dialect).emit(intent);
        } catch (UnexpressibleIntentException e) {
            return RuleBuildResult.declined(e.getMessage(), bindings);
        }
```

Give `columnHolding` and `storedValueOf` a trailing `SchemaMetadata metadata` parameter. Give `extractTrailingColumnPhrase` a trailing `Dialect dialect` parameter, and change its `binder.tableNamed(word)` to `binder.tableNamed(word, dialect)`.

- [ ] **Step 6: `RuleBasedQueryBuilder`**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Rule engine entry point: builds a query in the requested dialect from a question, or declines. */
@Component
public class RuleBasedQueryBuilder {

    private final QueryIntentParser parser;

    public RuleBasedQueryBuilder(QueryIntentParser parser) {
        this.parser = parser;
    }

    public RuleBuildResult build(String input, String databaseType) {
        Dialect dialect = Dialect.from(databaseType);
        if (input == null || input.isBlank()) {
            return RuleBuildResult.declined("The question was empty.", List.of());
        }
        return parser.parse(input, dialect);
    }

    public Optional<String> tryBuild(String input, String databaseType) {
        return build(input, databaseType).toOptional();
    }
}
```

- [ ] **Step 7: Mechanical test updates**

In `DeclineBoundaryTest`, `QueryIntentParserTest`, `RuleBasedQueryBuilderSchemaTest` and `RuleTraceWordingTest`, replace each

```java
new QueryIntentParser(
        new SchemaBinder(activeSchema),
        new JoinPathResolver(activeSchema),
        metadata,
        new SqlEmitter())
```

with `TestParsers.parser(activeSchema, metadata, mongoMetadata)`. Declare in each class's setup:

```java
        when(metadata.dialect()).thenReturn(Dialect.MYSQL);
        SchemaMetadata mongoMetadata = mock(SchemaMetadata.class);
        when(mongoMetadata.dialect()).thenReturn(Dialect.MONGODB);
```

Change the `metadata` field's type from `SchemaMetadataService` to `SchemaMetadata` (import `com.querysense.backend.analysis.SchemaMetadata`).

Then run these substitutions:

```bash
cd backend/src/test/java/com/querysense/backend/querybuilder
sed -i 's/parser\.parse(\("[^"]*"\))/parser.parse(\1, Dialect.MYSQL)/g' QueryIntentParserTest.java RuleTraceWordingTest.java
sed -i 's/binder\.bindTables(\(SchemaBinder\.normalize("[^"]*")\))/binder.bindTables(\1, Dialect.MYSQL)/g' SchemaBinderTest.java
sed -i 's/resolver\.resolve(\("[^"]*"\), \("[^"]*"\))/resolver.resolve(\1, \2, Dialect.MYSQL)/g' JoinPathResolverTest.java
```

Add `import com.querysense.backend.dialect.Dialect;` to each edited file. Then run `grep -n "parser.parse(\|bindTables(\|resolve(" *.java`. Any remaining call without `Dialect.` gets it by hand (for example, a call whose argument is a variable).

In `DeclineBoundaryTest`, replace `mongoAlwaysDeclines` with:

```java
    @ParameterizedTest
    @DisplayName("MongoDB declines the same questions MySQL does")
    @ValueSource(strings = {"orders from last month", "customers and suppliers", "delete all customers"})
    void mongoDeclinesWhatMysqlDeclines(String input) {
        assertThat(builder.build(input, "MongoDB").built()).as("input: %s", input).isFalse();
    }
```

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: 0 failures. If a MySQL expectation changed, the cause is in `bindColumn`'s new matching. Fix the binder, not the expectation.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "feat(rule-engine): MongoDB questions are built, not sent to the LLM

The binder, join resolver and parser take a dialect; the emitter and the
stored-value source come from per-dialect registries. The MongoDB gate is
gone. Field binding learns _id, embedded paths and camelCase names."
```

---

### Task 8: B1 — a noun that binds to nothing is named, not dropped

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/UnboundNounTest.java`

**Interfaces:**
- Consumes: `TestParsers.parser` (Task 7).
- Produces: a new decline, placed right after the table-count checks: `"\"<word>\" isn't a table in the active schema, so this engine can't tell what the question joins <subject> to."`

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnboundNounTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-b1", "two.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers", List.of("id", "name", "city")),
                        new KnownSchema.Table("orders", List.of("id", "customer_id", "status"))),
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")),
                Map.of(), Instant.now()));
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        parser = TestParsers.parser(schemas, mysql, mongo);
    }

    @ParameterizedTest
    @DisplayName("a second noun the schema cannot bind declines, naming the word")
    @ValueSource(strings = {"customers and suppliers", "customers and widgets", "show customers plus invoices"})
    void unboundSecondNounDeclines(String input) {
        RuleBuildResult result = parser.parse(input, Dialect.MYSQL);

        assertThat(result.built()).as("input: %s built %s", input, result.sql()).isFalse();
        String word = input.substring(input.lastIndexOf(' ') + 1);
        assertThat(result.trace().declineReason()).contains("\"" + word + "\"");
    }

    @Test
    @DisplayName("two nouns that both bind still build their join")
    void boundPairStillJoins() {
        RuleBuildResult result = parser.parse("customers and orders", Dialect.MYSQL);
        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("JOIN orders ON customers.id = orders.customer_id");
    }

    @Test
    @DisplayName("'and' followed by a word that is not a noun is left alone")
    void nonNounAfterAndIsIgnored() {
        assertThat(parser.parse("customers and their orders", Dialect.MYSQL).built()).isTrue();
    }
}
```

- [ ] **Step 2: Run it and record what happens**

Run: `cd backend && ./mvnw test -Dtest=UnboundNounTest`
Expected (from reading the parser): `unboundSecondNounDeclines` **fails** for all three inputs with `SELECT * FROM customers;`. That confirms the RULES.md "UNCONFIRMED" entry. The other two pass.

**If the three cases pass instead**, the defect does not reproduce. Skip Step 3, keep the test as a regression guard, and in Task 11 record the entry as "checked 2026-09-26, not a defect".

- [ ] **Step 3: Implement the decline**

Add a pattern next to the others:

```java
    private static final Pattern CONJOINED_NOUN = Pattern.compile("\\b(?:and|plus)\\s+([a-z_]+)\\b");
```

Insert directly after `tables.forEach(t -> bindings.add(...));` (line 138), before `KnownSchema.Table subject = ...`:

```java
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
```

The `endsWith("s")` guard limits this to plural nouns, the shape of a table reference. `customers in mumbai and pune` never reaches it: `pune` is a stored value, so `columnHolding` finds it.

- [ ] **Step 4: Run the test, then the whole suite**

Run: `cd backend && ./mvnw test -Dtest=UnboundNounTest` then `./mvnw test`
Expected: 5 tests pass, and the suite has 0 failures. If an existing test now declines, read its input. If an unbound plural follows `and` in it, the old expectation was the defect, so report it rather than weakening the new rule.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "fix(rule-engine): a second noun that binds to nothing declines, naming it

'customers and suppliers' on a schema without suppliers built
SELECT * FROM customers at 0.95 and never mentioned the word it ignored."
```

---

### Task 9: B2 — `resourceLevel` is stored with each run

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/dto/QueryRequest.java`
- Modify: `backend/src/main/java/com/querysense/backend/model/QueryHistory.java`
- Modify: `backend/src/main/java/com/querysense/backend/service/QueryGenerationService.java`
- Modify: `backend/src/main/java/com/querysense/backend/service/impl/QueryGenerationServiceImpl.java`
- Modify: `backend/src/main/java/com/querysense/backend/controller/QueryController.java`
- Modify: `frontend/src/api/queryBuilder.js`, `frontend/src/pages/WorkbenchPage.jsx`, `frontend/src/components/history/HistoryTable.jsx`
- Test: `backend/src/test/java/com/querysense/backend/service/impl/QueryHistoryRunContextTest.java` (2 new cases)
- Test: `frontend/src/components/history/HistoryTable.test.jsx` (2 new cases)

**Interfaces:**
- Produces: `QueryGenerationService.generateQuery(String input, String databaseType, ResourceLevel resourceLevel)`. A `default` 2-argument overload passes `null`, so existing callers are unchanged. `QueryHistory.resourceLevel` (`ResourceLevel`, stored as its name). The generate request accepts an optional `"resourceLevel": "LOW" | "MEDIUM" | "HIGH"`.

- [ ] **Step 1: Write the failing backend tests**

Append to `QueryHistoryRunContextTest` (add `import com.querysense.backend.enums.ResourceLevel;`):

```java
    @Test
    @DisplayName("the resource level the run was made under is stored")
    void storesResourceLevel() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL", ResourceLevel.LOW);

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getResourceLevel)
                .isEqualTo(ResourceLevel.LOW);
    }

    @Test
    @DisplayName("a caller that sends no resource level stores none, rather than a guessed default")
    void noResourceLevelStoresNull() {
        ruleEngineAnswers();

        service.generateQuery("Show all customers", "MySQL");

        assertThat(stored).singleElement()
                .extracting(QueryHistory::getResourceLevel)
                .isNull();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=QueryHistoryRunContextTest`
Expected: compilation failure (`getResourceLevel`, 3-argument `generateQuery`).

- [ ] **Step 3: Implement the backend**

`QueryRequest.java`: add `import com.querysense.backend.enums.ResourceLevel;` and:

```java
    private ResourceLevel resourceLevel;

    public ResourceLevel getResourceLevel() { return resourceLevel; }
    public void setResourceLevel(ResourceLevel resourceLevel) { this.resourceLevel = resourceLevel; }
```

`QueryHistory.java`: add `import com.querysense.backend.enums.ResourceLevel;` and, after `schemaLabel`:

```java
    @Enumerated(EnumType.STRING)
    private ResourceLevel resourceLevel;
```

`QueryGenerationService.java`:

```java
package com.querysense.backend.service;

import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.enums.ResourceLevel;

/** Generates a query from a natural-language question. */
public interface QueryGenerationService {

    QueryResponse generateQuery(String input, String databaseType, ResourceLevel resourceLevel);

    default QueryResponse generateQuery(String input, String databaseType) {
        return generateQuery(input, databaseType, null);
    }
}
```

`QueryGenerationServiceImpl.java`: change the method to `public QueryResponse generateQuery(String input, String databaseType, ResourceLevel resourceLevel)`, pass `resourceLevel` into `saveToHistory(input, generatedSql, databaseType, source, confidence, durationMs, resourceLevel)`, add a `ResourceLevel resourceLevel` parameter to `saveToHistory`, and set `history.setResourceLevel(resourceLevel);` there. Import `com.querysense.backend.enums.ResourceLevel`.

`QueryController.generate`:

```java
    @PostMapping("/generate")
    public QueryResponse generate(@Valid @RequestBody QueryRequest request) {
        return service.generateQuery(
                request.getQuery(),
                request.getDatabaseType(),
                request.getResourceLevel()
        );
    }
```

Existing tests that stub `generateQuery(anyString(), anyString())` on a **mock** `QueryGenerationService` now need `generateQuery(anyString(), anyString(), any())`. Run `grep -rn "generateQuery(anyString(), anyString())" backend/src/test` and update each hit.

- [ ] **Step 4: Run the backend suite**

Run: `cd backend && ./mvnw test`
Expected: 0 failures.

- [ ] **Step 5: Write the failing frontend tests**

Append inside the `describe` in `HistoryTable.test.jsx`:

```jsx
  it('shows the resource level the run was made under', () => {
    expect(within(rowFor({ ...RECORD, resourceLevel: 'LOW' })).getByText('Low')).toBeInTheDocument();
  });

  it('shows a dash for a run recorded before resource level was stored', () => {
    const row = rowFor({ ...RECORD, resourceLevel: null, confidence: null, durationMs: null, schemaLabel: null });
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(4);
  });
```

Run: `cd frontend && npx vitest run src/components/history/HistoryTable.test.jsx`
Expected: the 2 new tests fail ("Low" not found).

- [ ] **Step 6: Implement the frontend**

`HistoryTable.jsx`: add

```jsx
function formatResourceLevel(level) {
  return level ? level.charAt(0) + level.slice(1).toLowerCase() : EMPTY;
}
```

Add `<th>Resources</th>` after `<th>Schema</th>`, and `<td className="history-table__meta">{formatResourceLevel(r.resourceLevel)}</td>` after the schema cell.

`api/queryBuilder.js`: change the signature to `buildQuery({ naturalLanguageQuery, targetDb, resourceLevel })`, pass `resourceLevel` to `mockBuildQuery` too, and add `resourceLevel,` to the POST body after `databaseType`.

`pages/WorkbenchPage.jsx`: change the call to `buildQuery({ naturalLanguageQuery, targetDb, resourceLevel })`.

- [ ] **Step 7: Run the frontend tests and lint**

Run: `cd frontend && npx vitest run && npm run lint`
Expected: all tests pass; lint clean.

- [ ] **Step 8: Commit**

```bash
git add backend/src frontend/src
git commit -m "feat(history): store the resource level each run was made under

The generate request takes an optional resourceLevel; callers that do
not send one store null rather than a guessed default. History shows it
as its own column."
```

---

### Task 10: The parity suite

**Files:**
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DialectParityTest.java`

**Interfaces:**
- Consumes: `TestParsers.parser` (Task 7).

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The same question must build in both dialects or decline in both, except the listed differences. */
class DialectParityTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("PENDING", "SHIPPED"));
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("builds in both dialects or declines in both")
    @ValueSource(strings = {
            "show all customers",
            "customers in pune",
            "customers in mumbai and pune",
            "how many orders are there",
            "which customers placed more than three orders",
            "average price of products",
            "top 5 products sorted by price descending",
            "show only the name and email of customers",
            "customers with city pune",
            "orders with status shipped",
            "delete customers in pune",
            "delete all customers",
            "orders from last month",
            "customers and suppliers",
            "orders, customers and products together",
            "show me everything interesting",
            "customers sorted by flurb",
            "how many customers are there per city",
    })
    void sameOutcome(String question) {
        RuleBuildResult mysql = parser.parse(question, Dialect.MYSQL);
        RuleBuildResult mongo = parser.parse(question, Dialect.MONGODB);

        assertThat(mongo.built())
                .as("MySQL %s (%s), MongoDB %s (%s)",
                        mysql.built() ? "built" : "declined", mysql.built() ? mysql.sql() : mysql.trace().declineReason(),
                        mongo.built() ? "built" : "declined", mongo.built() ? mongo.sql() : mongo.trace().declineReason())
                .isEqualTo(mysql.built());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("documented difference: order items are a table in MySQL and embedded in MongoDB")
    @ValueSource(strings = {"show all order items"})
    void orderItemsDiffer(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).built()).isTrue();
        assertThat(parser.parse(question, Dialect.MONGODB).built()).isFalse();
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=DialectParityTest`
Expected: all pass. If a question differs, the assertion message shows both outcomes. Read it:
- **The difference comes from a real schema difference** (embedding, `_id`): move the question to a documented-difference method, with its reason in the `@DisplayName`.
- **Otherwise**, it's a binder or emitter defect: fix it in the class responsible, and rerun the whole suite.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test
git commit -m "test(rule-engine): a parity suite holding MySQL and MongoDB to the same outcomes"
```

---

### Task 11: Record it in the docs

**Files:**
- Modify: `RULES.md` (E2, the B1 gap entry, the binder rules, totals)
- Modify: `PROJECT.md` (§2 routing note, §5 Mongo entries, §7 session entry, §8/§9 counts)

- [ ] **Step 1: `RULES.md`**
- Replace E2's section with: E2 no longer declines MongoDB. It resolves `databaseType` to a dialect and rejects an unknown one with a 400. Keep the ID so later numbering holds, and change its summary-table cell to "Gates | 3 | E1 empty · E2 dialect · E3 date expression".
- In "Known gaps", replace the UNCONFIRMED entry with the Task 8 outcome: either **FIXED 2026-09-26** plus the new decline's wording, or **checked 2026-09-26, not a defect**.
- Under the binding rules, add a line saying `bindColumn` also matches `_id` for "id", the last segment of a dotted path, and camelCase names.
- Add a new rule entry for the Task 8 decline if it was implemented, and update the rule-engine count and the total in the summary table to match.

- [ ] **Step 2: `PROJECT.md`**
- §5 "Not a gap: MongoDB is analysed, never connected": prepend **SUPERSEDED 2026-09-26** with a pointer to the spec, and say the rule engine now builds MongoDB (steps 4–9 remain). Leave the deletion to spec step 9.
- §5 item 8 (history run context): `resourceLevel` is now stored via an optional field on the generate request.
- §7: add "Session of 2026-09-26 — MongoDB parity, steps 1–3", covering what was built, the listener defect Task 2 fixed, the Task 8 outcome, and the test count from the final `./mvnw test`. **Not verified:** no live `mongod` was used, and `distinct` has only been exercised with mocks.
- Add a live checklist item for the user: with `mongod` running and seeded (step 4), ask `customers in mumbai` against MongoDB and confirm the rule engine answers with `"Mumbai"` in the filter.

- [ ] **Step 3: Run the full suites one last time**

Run: `cd backend && ./mvnw test` and `cd frontend && npx vitest run && npm run lint`
Expected: 0 failures, lint clean. Put the exact backend count into the §7 entry.

- [ ] **Step 4: Commit**

```bash
git add RULES.md PROJECT.md
git commit -m "docs: record MongoDB parity steps 1-3, the B1 outcome and resourceLevel in history"
```
