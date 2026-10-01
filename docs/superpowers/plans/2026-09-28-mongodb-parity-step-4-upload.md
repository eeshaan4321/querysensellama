# MongoDB Parity, Step 4 — Mongo Upload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A MongoDB schema — a JSON file of collections with `$jsonSchema` validators, indexes and references — can be uploaded, provisioned into a real `mongod`, seeded with documents that pass its validators, and made the active MongoDB schema, exactly as a `.sql` upload works for MySQL. The built-in Mongo demo gets the same treatment from a checked-in JSON file.

**Architecture:** Three per-dialect strategy interfaces (`SchemaUploadParser`, `SchemaProvisioning`, `SchemaSeeding`) are picked through the existing `DialectRegistry`. The MySQL classes implement them unchanged; `JsonSchemaUploadParser`, `MongoProvisioner` and `MongoSeeder` are the MongoDB implementations. Both parsers produce the same `ParsedSchema`, which grows indexes, embedded arrays, field types and the raw validator. `SchemaUploadService` and `SchemaController` take a `Dialect`.

**Tech Stack:** Java 21, Spring Boot 3.5, MongoDB Java sync driver 5.5.2 (`MongoClient` bean from `spring-boot-starter-data-mongodb`, already used by `MongoSchemaMetadata`), Jackson 2 for reading the upload, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md` (§3.1 upload interfaces, §3.3 built-in demo, §4.1–4.7, §10 parser and integration tests, §12 step 4)

## Global Constraints

- QuerySense never executes a generated query. The only Mongo writes in this plan are provisioning and seeding QuerySense's own databases: `querysense_uploads` for uploads, and `querysense` for the built-in demo **only when none of its eight collections exist**.
- Source comments: a one-line class summary only (`/** ... */` above the class). No other comments.
- Backend tests: `cd backend && ./mvnw test` (one class: `./mvnw test -Dtest=ClassName`). The `@Tag("mongo")` integration test (Task 9) runs against `localhost:27017` when that port is open, and skips when it is closed.
- Do not start the app (`spring-boot:run`, `npm run dev`). The user runs live checks.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- MySQL upload behaviour and every existing MySQL error message stay byte-identical. Existing tests change only where a signature changes (listed per task).
- Seeding uses the existing `querysense.schema.upload.seed-rows` and `querysense.schema.upload.seed` properties, and inserts in batches of 1,000 (spec §4.6).

## Review Focus

1. **An optional field with a unique index.** MongoDB indexes a missing field as `null`, so a second document without it breaks the unique index with E11000. A unique field must be present in every seeded document. Pinned in Task 5 (`MongoSeederTest.uniqueOptionalFieldIsAlwaysPresent`).
2. **`additionalProperties: false` without `_id` in `properties`.** MongoDB would reject every document, including every seeded one. This must be refused before anything is provisioned, and the message must say how to fix it. Pinned in Task 3 (`closedSchemaWithoutIdIsRefused`).
3. **A reference whose type doesn't match its target** (`customer_id: int` → `customers._id: objectId`). Every seeded document would fail validation. Refused up front, naming both types. Pinned in Task 3 (`referenceTypeMismatchIsRefused`).
4. **`db.getCollectionInfos()` output that includes views and `system.*` collections.** These are skipped with a warning, and the upload still succeeds. Pinned in Task 2 (`collectionInfosSkipViewsAndSystemCollections`).
5. **`mongod` stops during an upload.** The request returns 503 with a message naming MongoDB within the 2 s selection timeout. It is never a 500. Pinned in Task 4 (`unreachableServerIs503`) and Task 5 (`serverLostWhileSeedingIs503`).

A sixth, carried by Task 5's error mapping: a validator rule the seeder cannot follow (`pattern`, `minimum`) fails the upload **naming the collection and the field** (`validationFailureNamesCollectionAndField`), and the parser warns about it beforehand (Task 2, `ignoredConstraintsWarn`).

## Departures from the spec, on purpose

- **Seeding order.** Spec §4.6 seeds in reference order and breaks cycles the SQL way. MongoDB enforces no references when inserting, so `MongoSeeder` generates every collection's documents first, then fills each reference from the target's generated values, then inserts. The ordering has no effect and cycles, self-references included, need no special case.
- **Profile guard.** Spec §4.7 says the H2 profile leaves `spring.data.mongodb.uri` unset. It doesn't: `application.properties` sets it and the H2 profile inherits it, and an empty URI breaks the autoconfigured `MongoClient`. A Mongo upload never touches MySQL, so under H2 it is guarded by the URI only. The H2 refusal stays on MySQL uploads.
- **"Provisioned on first use"** (spec §3.3) becomes a startup listener, `MongoDemoSeeder`. It provisions and seeds the demo into `querysense` only when none of the eight demo collections exist. It never drops anything there.
- **Reference rules the spec doesn't state.** A reference must point at a declared field outside any embedded array, with the same `bsonType` as its source. Without that, seeded documents would fail validation.
- **The demo gets reference indexes.** InnoDB indexes every MySQL foreign key automatically. The Mongo demo declares an ascending index on each reference field, so plan analysis (step 5) compares like with like.

## Deferred to later plans

- `GET /api/health` `mongo: UP|DOWN|NOT_CONFIGURED` and the shell banner (spec §11): step 8, together with the frontend that shows them.
- `explain`/`listIndexes` fixtures (spec §10): step 5, which consumes them.
- MySQL snapshots carrying their parsed indexes (spec §3.2): step 5, where the advisor first reads them. `DdlSchemaParser` still produces no indexes.
- The frontend's MongoDB side of the Schema source page: step 8. The response fields added here are additive, so the current frontend is unaffected.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../schema/upload/SchemaUploadParser.java` (new) | Text → `ParsedSchema`, one per dialect |
| `backend/.../schema/upload/SchemaProvisioning.java` (new) | Creates a parsed schema's tables/collections in the upload database |
| `backend/.../schema/upload/SchemaSeeding.java` (new) | Fills them with generated rows/documents |
| `backend/.../schema/upload/SeedReport.java` (new, moved out of `SchemaSeeder`) | Per-table counts and warnings |
| `backend/.../schema/ddl/ParsedTable.java`, `ParsedSchema.java` (modify) | Gain embedded arrays, validator, indexes, field types |
| `backend/.../schema/SchemaSnapshot.java` (modify) | Gains `fieldTypes` |
| `backend/.../schema/ddl/DdlSchemaParser.java`, `upload/SchemaProvisioner.java`, `upload/SchemaSeeder.java` (modify) | Implement the interfaces as MYSQL |
| `backend/.../schema/json/JsonSchemaUploadParser.java` (new) | MongoDB upload JSON → `ParsedSchema` |
| `backend/.../schema/upload/SchemaUploadValidator.java` (modify) | MongoDB rules alongside the SQL ones |
| `backend/.../exception/MongoUnavailableException.java` (new), `GlobalExceptionHandler.java` (modify) | MongoDB unreachable → 503 |
| `backend/.../schema/upload/MongoProvisioner.java` (new) | Drop, `createCollection` with a strict validator, `createIndex` |
| `backend/.../schema/upload/MongoSeeder.java` (new) | Documents from `$jsonSchema`, references filled, batched inserts |
| `backend/.../schema/upload/SchemaUploadService.java` (modify) | Per-dialect pipeline, URI guard, warnings per dialect |
| `backend/src/main/resources/mongo/demo-collections.json` (new) | The built-in demo in upload format |
| `backend/.../schema/KnownMongoSchema.java` (modify) | Gains `INDEXES`, `FIELD_TYPES` |
| `backend/.../schema/upload/MongoDemoSeeder.java` (new) | Seeds the demo into `querysense` when it is empty |
| `backend/.../controller/SchemaController.java`, `dto/SchemaUploadRequest.java`, `dto/SchemaUploadResponse.java`, `dto/SchemaTableView.java` (modify) | `dialect` on every endpoint; response gains dialect, indexes, field types, embedded arrays |
| `backend/src/main/resources/application.properties` (modify) | `mongo-database`, demo seeding switch |

`backend/...` = `backend/src/main/java/com/querysense/backend`. Tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: Upload strategy interfaces, and the parsed model grows

A refactor with no change in behaviour. Every existing test stays green, and only the two `SchemaSeeder.SeedReport` references in tests change.

**Files:**
- Create: `backend/.../schema/upload/SchemaUploadParser.java`, `SchemaProvisioning.java`, `SchemaSeeding.java`, `SeedReport.java`
- Modify: `backend/.../schema/ddl/ParsedTable.java`, `ParsedSchema.java`, `DdlSchemaParser.java`
- Modify: `backend/.../schema/SchemaSnapshot.java`
- Modify: `backend/.../schema/upload/SchemaProvisioner.java`, `SchemaSeeder.java`, `SchemaUploadService.java`
- Modify tests: `SchemaSeederTest.java:91,118`, `SchemaUploadServiceTest.java:43` (`SchemaSeeder.SeedReport` → `SeedReport`)
- Test: `backend/src/test/java/com/querysense/backend/schema/ddl/ParsedSchemaTest.java`

**Interfaces:**
- Produces:
  - `interface SchemaUploadParser extends DialectAware { ParsedSchema parse(String text); }`
  - `interface SchemaProvisioning extends DialectAware { String uploadDatabase(); void provision(ParsedSchema schema); }`
  - `interface SchemaSeeding extends DialectAware { SeedReport seed(ParsedSchema schema); }`
  - `record SeedReport(Map<String, Integer> rowCounts, List<String> warnings)`
  - `record ParsedTable(String name, List<ParsedColumn> columns, List<KnownSchema.ForeignKey> foreignKeys, List<String> embeddedArrays, org.bson.Document validator)`. The 3-argument constructor is kept (no arrays, `null` validator). `toKnownTable()` carries `embeddedArrays`.
  - `record ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes)`. The 2-argument constructor is kept (no indexes). Plus `List<IndexSpec> indexesOf(String table)` and `Map<String, Map<String, String>> fieldTypes()` (table → column/path → declared type, in declaration order).
  - `SchemaSnapshot` gains a 10th component, `Map<String, Map<String, String>> fieldTypes`. The 7- and 9-argument constructors are kept (empty map). Plus `Map<String, String> fieldTypesFor(String table)`.
  - `DdlSchemaParser`, `SchemaProvisioner` and `SchemaSeeder` return `Dialect.MYSQL` from `dialect()`. `SchemaProvisioner.uploadDatabase()` returns the same value as `uploadCatalog()`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.upload.SchemaProvisioner;
import com.querysense.backend.schema.upload.SchemaSeeder;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ParsedSchemaTest {

    private static ParsedColumn column(String name, String type) {
        return new ParsedColumn(name, type, List.of(), false, false, false, false);
    }

    @Test
    @DisplayName("field types map each table's columns to their declared type, in declaration order")
    void fieldTypesFollowDeclarations() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("_id", "objectId"), column("items.quantity", "int")),
                List.of(), List.of("items"), new Document("$jsonSchema", new Document()));
        ParsedSchema schema = new ParsedSchema(List.of(orders), List.of(), List.of());

        assertThat(schema.fieldTypes()).containsOnlyKeys("orders");
        assertThat(schema.fieldTypes().get("orders"))
                .containsExactly(Map.entry("_id", "objectId"), Map.entry("items.quantity", "int"));
    }

    @Test
    @DisplayName("indexesOf returns only the named table's indexes, ignoring case")
    void indexesOfFiltersByTable() {
        IndexSpec email = new IndexSpec("customers", List.of(new IndexSpec.KeyPart("email", 1)), true);
        IndexSpec status = new IndexSpec("orders", List.of(new IndexSpec.KeyPart("status", 1)), false);
        ParsedSchema schema = new ParsedSchema(List.of(), List.of(), List.of(email, status));

        assertThat(schema.indexesOf("CUSTOMERS")).containsExactly(email);
    }

    @Test
    @DisplayName("the SQL-shaped constructors keep meaning what they meant")
    void sqlConstructorsDefaultTheNewParts() {
        ParsedTable table = new ParsedTable("customers", List.of(column("id", "INT")), List.of());
        ParsedSchema schema = new ParsedSchema(List.of(table), List.of());

        assertThat(table.embeddedArrays()).isEmpty();
        assertThat(table.validator()).isNull();
        assertThat(schema.indexes()).isEmpty();
        assertThat(table.toKnownTable()).isEqualTo(new KnownSchema.Table("customers", List.of("id")));
    }

    @Test
    @DisplayName("toKnownTable carries embedded arrays")
    void knownTableCarriesEmbeddedArrays() {
        ParsedTable orders = new ParsedTable("orders",
                List.of(column("_id", "objectId"), column("items.quantity", "int")),
                List.of(), List.of("items"), null);

        assertThat(orders.toKnownTable().embeddedArrays()).containsExactly("items");
    }

    @Test
    @DisplayName("older SchemaSnapshot constructors have no field types")
    void snapshotDefaultsFieldTypes() {
        SchemaSnapshot snapshot = new SchemaSnapshot("x", "x", null, List.of(), List.of(), Map.of(), Instant.EPOCH);

        assertThat(snapshot.fieldTypes()).isEmpty();
        assertThat(snapshot.fieldTypesFor("customers")).isEmpty();
    }

    @Test
    @DisplayName("the SQL upload strategies serve MySQL")
    void sqlStrategiesAreMySql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new DdlSchemaParser().dialect()).isEqualTo(Dialect.MYSQL);
        assertThat(new SchemaProvisioner(jdbc, "querysense_uploads").dialect()).isEqualTo(Dialect.MYSQL);
        assertThat(new SchemaProvisioner(jdbc, "querysense_uploads").uploadDatabase()).isEqualTo("querysense_uploads");
        assertThat(new SchemaSeeder(jdbc, "querysense_uploads", 10, 1L).dialect()).isEqualTo(Dialect.MYSQL);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ParsedSchemaTest`
Expected: compilation failure (`ParsedTable` has no 5-argument constructor, no `fieldTypes()`, no `dialect()`).

- [ ] **Step 3: Implement**

`SchemaUploadParser.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

/** Reads an uploaded schema file into a parsed schema, for one dialect. */
public interface SchemaUploadParser extends DialectAware {
    ParsedSchema parse(String text);
}
```

`SchemaProvisioning.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

/** Creates an uploaded schema's tables or collections in the upload database, for one dialect. */
public interface SchemaProvisioning extends DialectAware {
    String uploadDatabase();

    void provision(ParsedSchema schema);
}
```

`SchemaSeeding.java`:

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.DialectAware;
import com.querysense.backend.schema.ddl.ParsedSchema;

/** Fills an uploaded schema with generated data, for one dialect. */
public interface SchemaSeeding extends DialectAware {
    SeedReport seed(ParsedSchema schema);
}
```

`SeedReport.java`:

```java
package com.querysense.backend.schema.upload;

import java.util.List;
import java.util.Map;

/** How many rows or documents each table received, and what fell short. */
public record SeedReport(Map<String, Integer> rowCounts, List<String> warnings) {
}
```

`ParsedTable.java`, replacing the whole file:

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;

import java.util.List;
import java.util.Optional;

/** A table or collection declared in an uploaded schema. */
public record ParsedTable(
        String name,
        List<ParsedColumn> columns,
        List<KnownSchema.ForeignKey> foreignKeys,
        List<String> embeddedArrays,
        Document validator) {

    public ParsedTable(String name, List<ParsedColumn> columns, List<KnownSchema.ForeignKey> foreignKeys) {
        this(name, columns, foreignKeys, List.of(), null);
    }

    public Optional<ParsedColumn> column(String columnName) {
        return columns.stream().filter(c -> c.name().equalsIgnoreCase(columnName)).findFirst();
    }

    public List<String> columnNames() {
        return columns.stream().map(ParsedColumn::name).toList();
    }

    public KnownSchema.Table toKnownTable() {
        return new KnownSchema.Table(name, columnNames(), embeddedArrays);
    }
}
```

`ParsedSchema.java`, replacing the whole file:

```java
package com.querysense.backend.schema.ddl;

import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The tables parsed from an uploaded file, with their indexes and any skipped statements. */
public record ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes) {

    public ParsedSchema(List<ParsedTable> tables, List<String> warnings) {
        this(tables, warnings, List.of());
    }

    public Optional<ParsedTable> table(String name) {
        return tables.stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<KnownSchema.ForeignKey> allForeignKeys() {
        return tables.stream().flatMap(t -> t.foreignKeys().stream()).toList();
    }

    public List<KnownSchema.Table> toKnownTables() {
        return tables.stream().map(ParsedTable::toKnownTable).toList();
    }

    public List<IndexSpec> indexesOf(String table) {
        return indexes.stream().filter(i -> i.table().equalsIgnoreCase(table)).toList();
    }

    public Map<String, Map<String, String>> fieldTypes() {
        Map<String, Map<String, String>> byTable = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            Map<String, String> types = new LinkedHashMap<>();
            table.columns().forEach(c -> types.put(c.name(), c.type()));
            byTable.put(table.name(), types);
        }
        return byTable;
    }
}
```

`SchemaSnapshot.java`: add the component, keep both older constructors, and add the accessor:

```java
public record SchemaSnapshot(
        String id,
        String label,
        String catalog,
        List<KnownSchema.Table> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        Map<String, Integer> rowCounts,
        Instant loadedAt,
        Dialect dialect,
        List<IndexSpec> indexes,
        Map<String, Map<String, String>> fieldTypes) {

    public static final String BUILT_IN_ID = "builtin";

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, Dialect.MYSQL, List.of(), Map.of());
    }

    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, Map.of());
    }

    public Map<String, String> fieldTypesFor(String tableName) {
        return fieldTypes.getOrDefault(tableName, Map.of());
    }

    // builtIn(), tableNames(), rowCountFor() unchanged
}
```

`DdlSchemaParser`: `public class DdlSchemaParser implements SchemaUploadParser`, plus:

```java
    @Override
    public Dialect dialect() {
        return Dialect.MYSQL;
    }
```

`SchemaProvisioner`: `implements SchemaProvisioning`, plus `dialect()` returning `Dialect.MYSQL`, and:

```java
    @Override
    public String uploadDatabase() {
        return uploadCatalog;
    }
```

Add `@Override` to `provision`.

`SchemaSeeder`: `implements SchemaSeeding`, plus `dialect()` returning `Dialect.MYSQL`. Delete the nested `SeedReport` record, return the top-level `SeedReport`, and add `@Override` to `seed`.

`SchemaUploadService`: change `SchemaSeeder.SeedReport seeded` to `SeedReport seeded`. Nothing else changes in this task.

In the three test lines listed above, replace `SchemaSeeder.SeedReport` with `SeedReport`.

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green, including `ParsedSchemaTest` and `KnownSchemaMatchesSeedScriptsTest`.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "refactor(upload): per-dialect upload interfaces, and a parsed schema that can hold collections

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `JsonSchemaUploadParser`

**Files:**
- Create: `backend/.../schema/json/JsonSchemaUploadParser.java`
- Test: `backend/src/test/java/com/querysense/backend/schema/json/JsonSchemaUploadParserTest.java`

**Interfaces:**
- Consumes: `SchemaUploadParser`, `ParsedSchema`, `ParsedTable` (5-argument constructor), `ParsedColumn`, `IndexSpec` (Task 1).
- Produces: a `@Component JsonSchemaUploadParser implements SchemaUploadParser` whose `dialect()` returns `MONGODB`, plus:
  - `public static final Set<String> GENERATED_TYPES` — the types the seeder can generate: `string, int, long, double, decimal, bool, date, objectId, number, object, array, any`.
  - `public static String normalise(String type)` — `boolean`→`bool`, `integer`→`int`, anything else unchanged.
  - Each collection becomes a `ParsedTable`:
    - `columns`: `_id` first (`primaryKey`, `unique`, `notNull`; type from `properties._id` or `objectId`), then leaf paths depth-first in declaration order. `notNull` means required. `unique` means a single-key unique index is on that path. `typeArgs` is `[maxLength]` when one is set.
    - `embeddedArrays`: every array path.
    - `foreignKeys`: built from `references`.
    - `validator`: the whole validator as an `org.bson.Document`, or `null` when there isn't one.
  - Indexes go in `ParsedSchema.indexes` as `IndexSpec(collection, keys, unique)`. A lone `{_id: 1}` index is dropped silently. A non-numeric key (`"text"`, `"2dsphere"`, `"hashed"`) drops that index with a warning.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.schema.json;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaUploadParserTest {

    private final JsonSchemaUploadParser parser = new JsonSchemaUploadParser();

    private static final String SPEC_EXAMPLE = """
            {
              "collections": [{
                "name": "orders",
                "validator": { "$jsonSchema": {
                  "bsonType": "object",
                  "required": ["customerId", "status"],
                  "properties": {
                    "customerId": {"bsonType": "objectId"},
                    "status": {"bsonType": "string", "enum": ["PENDING", "SHIPPED"]},
                    "items": {"bsonType": "array", "items": {"bsonType": "object",
                      "properties": {"productId": {"bsonType": "objectId"},
                                     "quantity": {"bsonType": "int"}}}}
                  }}},
                "indexes": [{"key": {"customerId": 1}}, {"key": {"email": 1}, "unique": true}],
                "references": {"customerId": "customers._id", "items.productId": "products._id"}
              }]
            }
            """;

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(parser.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("the documented format: dotted paths, _id first, embedded arrays, types and required")
    void documentedFormat() {
        ParsedTable orders = parser.parse(SPEC_EXAMPLE).table("orders").orElseThrow();

        assertThat(orders.columnNames())
                .containsExactly("_id", "customerId", "status", "items.productId", "items.quantity");
        assertThat(orders.embeddedArrays()).containsExactly("items");
        assertThat(orders.column("_id").orElseThrow().type()).isEqualTo("objectId");
        assertThat(orders.column("items.quantity").orElseThrow().type()).isEqualTo("int");
        assertThat(orders.column("customerId").orElseThrow().notNull()).isTrue();
        assertThat(orders.column("items.productId").orElseThrow().notNull()).isFalse();
        assertThat(orders.validator().get("$jsonSchema")).isInstanceOf(Document.class);
    }

    @Test
    @DisplayName("references become foreign keys, and indexes keep their order, direction and uniqueness")
    void referencesAndIndexes() {
        ParsedSchema schema = parser.parse(SPEC_EXAMPLE);

        assertThat(schema.allForeignKeys()).containsExactly(
                new KnownSchema.ForeignKey("orders", "customerId", "customers", "_id"),
                new KnownSchema.ForeignKey("orders", "items.productId", "products", "_id"));
        assertThat(schema.indexes()).containsExactly(
                new IndexSpec("orders", List.of(new IndexSpec.KeyPart("customerId", 1)), false),
                new IndexSpec("orders", List.of(new IndexSpec.KeyPart("email", 1)), true));
    }

    @Test
    @DisplayName("a single-key unique index marks its field unique")
    void uniqueIndexMarksField() {
        ParsedTable customers = parser.parse("""
                {"collections": [{"name": "customers",
                  "validator": {"$jsonSchema": {"properties": {"email": {"bsonType": "string"}}}},
                  "indexes": [{"key": {"email": 1}, "unique": true}]}]}
                """).table("customers").orElseThrow();

        assertThat(customers.column("email").orElseThrow().unique()).isTrue();
    }

    @Test
    @DisplayName("getCollectionInfos output: views and system collections are skipped with a warning")
    void collectionInfosSkipViewsAndSystemCollections() {
        ParsedSchema schema = parser.parse("""
                [
                  {"name": "customers", "type": "collection",
                   "options": {"validator": {"$jsonSchema": {"properties": {"city": {"bsonType": "string"}}}}},
                   "info": {"readOnly": false}, "idIndex": {"v": 2, "key": {"_id": 1}, "name": "_id_"}},
                  {"name": "big_spenders", "type": "view", "options": {"viewOn": "customers", "pipeline": []}},
                  {"name": "system.views", "type": "collection", "options": {}}
                ]
                """);

        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("customers");
        assertThat(schema.table("customers").orElseThrow().columnNames()).containsExactly("_id", "city");
        assertThat(schema.warnings()).hasSize(2)
                .anySatisfy(w -> assertThat(w).contains("big_spenders").contains("view"))
                .anySatisfy(w -> assertThat(w).contains("system.views"));
    }

    @Test
    @DisplayName("nested and scalar arrays: every array path is an embedded array, a scalar array is its own leaf")
    void nestedArrays() {
        ParsedTable orders = parser.parse("""
                {"collections": [{"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "tags": {"bsonType": "array", "items": {"bsonType": "string"}},
                  "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                    "sku": {"bsonType": "string"},
                    "options": {"bsonType": "array", "items": {"bsonType": "string"}}}}}}}}}]}
                """).table("orders").orElseThrow();

        assertThat(orders.columnNames()).containsExactly("_id", "tags", "items.sku", "items.options");
        assertThat(orders.embeddedArrays()).containsExactly("tags", "items", "items.options");
        assertThat(orders.column("tags").orElseThrow().type()).isEqualTo("string");
    }

    @Test
    @DisplayName("an object without bsonType but with properties is still walked")
    void untypedObjectIsWalked() {
        ParsedTable customers = parser.parse("""
                {"collections": [{"name": "customers", "validator": {"$jsonSchema": {"properties": {
                  "address": {"properties": {"city": {"bsonType": "string"}}}}}}}]}
                """).table("customers").orElseThrow();

        assertThat(customers.columnNames()).containsExactly("_id", "address.city");
    }

    @Test
    @DisplayName("a type list takes its first non-null type; JSON Schema's type names are normalised")
    void typeListsAndJsonTypes() {
        ParsedTable t = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "a": {"bsonType": ["null", "string"]},
                  "b": {"type": "boolean"},
                  "c": {}}}}}]}
                """).table("t").orElseThrow();

        assertThat(t.column("a").orElseThrow().type()).isEqualTo("string");
        assertThat(t.column("b").orElseThrow().type()).isEqualTo("bool");
        assertThat(t.column("c").orElseThrow().type()).isEqualTo("any");
    }

    @Test
    @DisplayName("maxLength becomes the column's length argument")
    void maxLengthIsKept() {
        ParsedColumn code = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "code": {"bsonType": "string", "maxLength": 12}}}}}]}
                """).table("t").orElseThrow().column("code").orElseThrow();

        assertThat(code.typeArgs()).containsExactly("12");
    }

    @Test
    @DisplayName("unread keywords such as oneOf warn rather than fail")
    void unreadKeywordsWarn() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "v": {"oneOf": [{"bsonType": "int"}, {"bsonType": "string"}]}}}}}]}
                """);

        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("oneOf").contains("v"));
    }

    @Test
    @DisplayName("constraints the seeder cannot follow warn up front")
    void ignoredConstraintsWarn() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "zip": {"bsonType": "string", "pattern": "^[0-9]{6}$"}}}}}]}
                """);

        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("t.zip").contains("pattern"));
    }

    @Test
    @DisplayName("an optional field of a type that cannot be generated warns; the field is still declared")
    void unsupportedOptionalTypeWarns() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "blob": {"bsonType": "binData"}}}}}]}
                """);

        assertThat(schema.table("t").orElseThrow().column("blob")).isPresent();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("t.blob").contains("binData"));
    }

    @Test
    @DisplayName("a collection without a $jsonSchema knows only _id, and says so")
    void noValidator() {
        ParsedSchema schema = parser.parse("{\"collections\": [{\"name\": \"logs\"}]}");

        assertThat(schema.table("logs").orElseThrow().columnNames()).containsExactly("_id");
        assertThat(schema.table("logs").orElseThrow().validator()).isNull();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("logs").contains("$jsonSchema"));
    }

    @Test
    @DisplayName("the _id index is implicit; a text index is skipped with a warning")
    void specialIndexes() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "t",
                  "validator": {"$jsonSchema": {"properties": {"body": {"bsonType": "string"}}}},
                  "indexes": [{"key": {"_id": 1}}, {"key": {"body": "text"}}]}]}
                """);

        assertThat(schema.indexes()).isEmpty();
        assertThat(schema.warnings()).anySatisfy(w -> assertThat(w).contains("body").contains("t"));
    }

    @Test
    @DisplayName("mongosh-style unquoted keys and single quotes are accepted")
    void relaxedJson() {
        ParsedSchema schema = parser.parse("{collections: [{name: 'customers'}]}");

        assertThat(schema.tables()).extracting(ParsedTable::name).containsExactly("customers");
    }

    @Test
    @DisplayName("malformed JSON names the line and column")
    void malformedJsonNamesPosition() {
        String broken = "{\n  \"collections\": [\n    {\"name\": \"x\",, }\n  ]\n}";

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("line 3");
    }

    @Test
    @DisplayName("JSON of the wrong shape says what shape is expected")
    void wrongShape() {
        assertThatThrownBy(() -> parser.parse("{\"tables\": []}"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("\"collections\"");
    }

    @Test
    @DisplayName("a collection without a name is refused")
    void missingName() {
        assertThatThrownBy(() -> parser.parse("{\"collections\": [{\"validator\": {}}]}"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a reference that is not collection.field is refused")
    void malformedReference() {
        assertThatThrownBy(() -> parser.parse("""
                {"collections": [{"name": "orders", "references": {"customer_id": "customers"}}]}
                """))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("orders.customer_id")
                .hasMessageContaining("customers._id");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=JsonSchemaUploadParserTest`
Expected: compilation failure (`JsonSchemaUploadParser` does not exist).

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.schema.json;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.upload.SchemaUploadParser;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses an uploaded MongoDB schema: collections with $jsonSchema validators, indexes and references. */
@Component
public class JsonSchemaUploadParser implements SchemaUploadParser {

    public static final Set<String> GENERATED_TYPES = Set.of(
            "string", "int", "long", "double", "decimal", "bool", "date", "objectId",
            "number", "object", "array", "any");

    private static final List<String> UNREAD_KEYWORDS = List.of(
            "oneOf", "anyOf", "allOf", "not", "patternProperties", "$ref", "dependencies");
    private static final List<String> IGNORED_CONSTRAINTS = List.of(
            "pattern", "minLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private record Collection(ParsedTable table, List<IndexSpec> indexes) {
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public ParsedSchema parse(String text) {
        JsonNode root = read(text);
        List<String> warnings = new ArrayList<>();
        List<ParsedTable> tables = new ArrayList<>();
        List<IndexSpec> indexes = new ArrayList<>();
        for (JsonNode entry : collectionEntries(root)) {
            Collection parsed = parseCollection(entry, warnings);
            if (parsed != null) {
                tables.add(parsed.table());
                indexes.addAll(parsed.indexes());
            }
        }
        return new ParsedSchema(List.copyOf(tables), List.copyOf(warnings), List.copyOf(indexes));
    }

    public static String normalise(String type) {
        return switch (type) {
            case "boolean" -> "bool";
            case "integer" -> "int";
            default -> type;
        };
    }

    static String typeOf(JsonNode node) {
        JsonNode declared = node.has("bsonType") ? node.get("bsonType") : node.get("type");
        if (declared != null && declared.isTextual()) {
            return normalise(declared.asText());
        }
        if (declared != null && declared.isArray()) {
            for (JsonNode t : declared) {
                if (t.isTextual() && !"null".equals(t.asText())) {
                    return normalise(t.asText());
                }
            }
        }
        if (node.has("properties")) {
            return "object";
        }
        if (node.has("items")) {
            return "array";
        }
        return "any";
    }

    private JsonNode read(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root == null || root.isMissingNode()) {
                throw new SchemaUploadException(
                        "The uploaded schema was empty. Upload a .json file describing the collections.");
            }
            return root;
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : " at line " + at.getLineNr() + ", column " + at.getColumnNr();
            throw new SchemaUploadException(
                    "The uploaded file is not valid JSON" + where + ": " + e.getOriginalMessage(), e);
        }
    }

    private Iterable<JsonNode> collectionEntries(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.isObject() && root.path("collections").isArray()) {
            return root.get("collections");
        }
        throw new SchemaUploadException(
                "Expected a JSON object with a \"collections\" array, or the array that "
                        + "db.getCollectionInfos() returns.");
    }

    private Collection parseCollection(JsonNode entry, List<String> warnings) {
        JsonNode nameNode = entry.path("name");
        if (!nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new SchemaUploadException("Every collection in the upload needs a \"name\".");
        }
        String name = nameNode.asText();
        if (name.startsWith("system.")) {
            warnings.add("Skipped '" + name + "': system collections are not uploaded.");
            return null;
        }
        if ("view".equals(entry.path("type").asText())) {
            warnings.add("Skipped '" + name + "': it is a view, and QuerySense uploads collections only.");
            return null;
        }

        JsonNode validator = entry.has("validator") ? entry.get("validator") : entry.path("options").path("validator");
        JsonNode jsonSchema = validator.path("$jsonSchema");
        if (validator.isObject() && validator.size() > (jsonSchema.isMissingNode() ? 0 : 1)) {
            warnings.add("The validator of '" + name + "' has query operators besides $jsonSchema. They are "
                    + "provisioned, but QuerySense reads fields from $jsonSchema only.");
        }

        List<IndexSpec> indexes = parseIndexes(name, entry.path("indexes"), warnings);
        Set<String> uniquePaths = indexes.stream()
                .filter(IndexSpec::unique)
                .filter(i -> i.keys().size() == 1)
                .map(i -> i.keys().get(0).column())
                .collect(Collectors.toSet());

        List<ParsedColumn> columns = new ArrayList<>();
        List<String> embeddedArrays = new ArrayList<>();
        JsonNode idNode = jsonSchema.path("properties").path("_id");
        columns.add(new ParsedColumn("_id", idNode.isMissingNode() ? "objectId" : typeOf(idNode),
                List.of(), true, false, true, true));
        if (jsonSchema.isMissingNode()) {
            warnings.add("'" + name + "' has no $jsonSchema validator, so only _id is known about its documents.");
        } else {
            walk(name, "", jsonSchema, columns, embeddedArrays, uniquePaths, warnings);
        }

        Document validatorDocument = validator.isObject() ? Document.parse(validator.toString()) : null;
        ParsedTable table = new ParsedTable(name, List.copyOf(columns), parseReferences(name, entry.path("references")),
                List.copyOf(embeddedArrays), validatorDocument);
        return new Collection(table, indexes);
    }

    private void walk(String collection, String prefix, JsonNode schema, List<ParsedColumn> columns,
                      List<String> embeddedArrays, Set<String> uniquePaths, List<String> warnings) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        warnUnread(collection, prefix.isEmpty() ? "(document)" : prefix, schema, warnings);

        for (Map.Entry<String, JsonNode> field : schema.path("properties").properties()) {
            if (prefix.isEmpty() && "_id".equals(field.getKey())) {
                continue;
            }
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode node = field.getValue();
            String type = typeOf(node);
            boolean isRequired = required.contains(field.getKey());

            if ("object".equals(type) && node.has("properties")) {
                walk(collection, path, node, columns, embeddedArrays, uniquePaths, warnings);
            } else if ("array".equals(type)) {
                embeddedArrays.add(path);
                JsonNode items = node.path("items");
                String itemType = items.isObject() ? typeOf(items) : "any";
                if ("object".equals(itemType) && items.has("properties")) {
                    walk(collection, path, items, columns, embeddedArrays, uniquePaths, warnings);
                } else {
                    warnUnread(collection, path, items.isObject() ? items : node, warnings);
                    addLeaf(collection, path, items.isObject() ? items : node, itemType, isRequired,
                            columns, uniquePaths, warnings);
                }
            } else {
                warnUnread(collection, path, node, warnings);
                addLeaf(collection, path, node, type, isRequired, columns, uniquePaths, warnings);
            }
        }
    }

    private void addLeaf(String collection, String path, JsonNode node, String type, boolean required,
                         List<ParsedColumn> columns, Set<String> uniquePaths, List<String> warnings) {
        if (!GENERATED_TYPES.contains(type) && !required) {
            warnings.add("Field '" + collection + "." + path + "' has bsonType '" + type + "', which QuerySense "
                    + "cannot generate values for, so seeded documents leave it out.");
        }
        List<String> args = node.path("maxLength").canConvertToInt()
                ? List.of(node.get("maxLength").asText())
                : List.of();
        columns.add(new ParsedColumn(path, type, args, required, false, uniquePaths.contains(path), false));
    }

    private void warnUnread(String collection, String path, JsonNode node, List<String> warnings) {
        for (String keyword : UNREAD_KEYWORDS) {
            if (node.has(keyword)) {
                warnings.add("'" + collection + "' uses " + keyword + " at " + path
                        + ", which QuerySense does not read; those alternatives are ignored.");
            }
        }
        for (String keyword : IGNORED_CONSTRAINTS) {
            if (node.has(keyword)) {
                warnings.add("Field '" + collection + "." + path + "' sets " + keyword + ". Seeded values do not "
                        + "follow it, so MongoDB's validator may reject them.");
            }
        }
    }

    private List<IndexSpec> parseIndexes(String collection, JsonNode indexes, List<String> warnings) {
        List<IndexSpec> out = new ArrayList<>();
        for (JsonNode index : indexes) {
            JsonNode key = index.path("key");
            if (!key.isObject() || key.isEmpty()) {
                warnings.add("Skipped an index on '" + collection + "' that has no \"key\".");
                continue;
            }
            List<IndexSpec.KeyPart> parts = new ArrayList<>();
            boolean plain = true;
            for (Map.Entry<String, JsonNode> part : key.properties()) {
                if (!part.getValue().isNumber()) {
                    plain = false;
                    break;
                }
                parts.add(new IndexSpec.KeyPart(part.getKey(), part.getValue().asDouble() < 0 ? -1 : 1));
            }
            if (!plain) {
                warnings.add("Skipped the index " + key + " on '" + collection + "': only ascending (1) and "
                        + "descending (-1) keys are provisioned.");
                continue;
            }
            if (parts.size() == 1 && "_id".equals(parts.get(0).column())) {
                continue;
            }
            out.add(new IndexSpec(collection, List.copyOf(parts), index.path("unique").asBoolean(false)));
        }
        return out;
    }

    private List<KnownSchema.ForeignKey> parseReferences(String collection, JsonNode references) {
        List<KnownSchema.ForeignKey> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> reference : references.properties()) {
            String target = reference.getValue().asText("");
            int dot = target.indexOf('.');
            if (!reference.getValue().isTextual() || dot <= 0 || dot == target.length() - 1) {
                throw new SchemaUploadException("The reference " + collection + "." + reference.getKey()
                        + " must name a collection and a field, like \"customers._id\".");
            }
            out.add(new KnownSchema.ForeignKey(collection, reference.getKey(),
                    target.substring(0, dot), target.substring(dot + 1)));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=JsonSchemaUploadParserTest`
Expected: PASS. If `malformedJsonNamesPosition` reports a line other than 3, print the message and fix the fixture, not the assertion's intent. The message must carry the reader's line and column.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(upload): read a MongoDB schema from collections with \$jsonSchema validators

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: MongoDB rules in `SchemaUploadValidator`

**Files:**
- Modify: `backend/.../schema/upload/SchemaUploadValidator.java`
- Test: `backend/src/test/java/com/querysense/backend/schema/upload/MongoSchemaUploadValidatorTest.java`

**Interfaces:**
- Consumes: `JsonSchemaUploadParser.GENERATED_TYPES` (Task 2), `ParsedTable.embeddedArrays()`, `validator()`, `ParsedSchema.indexes()` (Task 1).
- Produces: `public void validate(ParsedSchema schema, Dialect dialect)`. `validate(schema)` stays and means `validate(schema, Dialect.MYSQL)`. The SQL body moves unchanged into `private void validateSql(ParsedSchema)`. `SchemaUploadValidatorTest` is untouched and stays green.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.schema.upload;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoSchemaUploadValidatorTest {

    private final JsonSchemaUploadParser parser = new JsonSchemaUploadParser();
    private final SchemaUploadValidator validator = new SchemaUploadValidator(40);

    private static final String CUSTOMERS = """
            {"name": "customers", "validator": {"$jsonSchema": {"properties": {
              "name": {"bsonType": "string"}, "email": {"bsonType": "string"}}}},
             "indexes": [{"key": {"email": 1}, "unique": true}]}
            """;

    private static final String ORDERS = """
            {"name": "orders", "validator": {"$jsonSchema": {"required": ["customer_id"], "properties": {
              "customer_id": {"bsonType": "objectId"},
              "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                "sku": {"bsonType": "string"}}}}}}},
             "indexes": [{"key": {"customer_id": 1}}, {"key": {"items": 1}}],
             "references": {"customer_id": "customers._id"}}
            """;

    private ParsedSchema schema(String... collections) {
        return parser.parse("{\"collections\": [" + String.join(",", collections) + "]}");
    }

    private void assertRefused(ParsedSchema schema, String... fragments) {
        var assertion = assertThatThrownBy(() -> validator.validate(schema, Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class);
        for (String fragment : fragments) {
            assertion.hasMessageContaining(fragment);
        }
    }

    @Test
    @DisplayName("a well-formed schema with references and indexes passes")
    void validSchemaPasses() {
        assertThatCode(() -> validator.validate(schema(CUSTOMERS, ORDERS), Dialect.MONGODB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no collections says what to upload")
    void emptyIsRefused() {
        assertRefused(parser.parse("{\"collections\": []}"), "No collections", "$jsonSchema");
    }

    @Test
    @DisplayName("more collections than the cap are refused")
    void capIsEnforced() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 41; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\": \"c").append(i).append("\"}");
        }
        assertRefused(schema(many.toString()), "41 collections", "40");
    }

    @Test
    @DisplayName("an unsafe collection name is refused")
    void unsafeCollectionName() {
        assertRefused(schema("{\"name\": \"my-coll\"}"), "Collection name", "my-coll");
    }

    @Test
    @DisplayName("an unsafe segment in a dotted field path is refused")
    void unsafeFieldSegment() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"properties": {
                  "a": {"properties": {"bad field": {"bsonType": "string"}}}}}}}
                """), "Field name", "bad field");
    }

    @Test
    @DisplayName("a collection declared twice is refused")
    void duplicateCollection() {
        assertRefused(schema(CUSTOMERS, CUSTOMERS), "more than once", "customers");
    }

    @Test
    @DisplayName("a reference from an undeclared field is refused")
    void referenceSourceUndeclared() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "references": {"customer_id": "customers._id"}}
                """), "orders.customer_id", "not declared");
    }

    @Test
    @DisplayName("a reference to a missing collection is refused")
    void referenceTargetCollectionMissing() {
        assertRefused(schema(ORDERS), "customers", "not in the uploaded schema");
    }

    @Test
    @DisplayName("a reference to an undeclared target field is refused")
    void referenceTargetFieldMissing() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "customer_code": {"bsonType": "string"}}}},
                 "references": {"customer_code": "customers.code"}}
                """), "customers.code", "not declared");
    }

    @Test
    @DisplayName("a reference into an embedded array is refused: the target must have one value per document")
    void referenceIntoArray() {
        assertRefused(schema(ORDERS.replace("customers._id", "customers._id"), CUSTOMERS, """
                {"name": "returns", "validator": {"$jsonSchema": {"properties": {
                  "sku": {"bsonType": "string"}}}},
                 "references": {"sku": "orders.items.sku"}}
                """), "orders.items.sku", "embedded array");
    }

    @Test
    @DisplayName("a reference whose bsonType differs from its target's is refused, naming both types")
    void referenceTypeMismatchIsRefused() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "customer_id": {"bsonType": "int"}}}},
                 "references": {"customer_id": "customers._id"}}
                """), "orders.customer_id", "int", "objectId");
    }

    @Test
    @DisplayName("an index on an undeclared field is refused (the spec's own example indexes email on orders)")
    void indexOnUndeclaredField() {
        assertRefused(schema(CUSTOMERS, """
                {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                  "status": {"bsonType": "string"}}}},
                 "indexes": [{"key": {"email": 1}, "unique": true}]}
                """), "orders", "email", "not declared");
    }

    @Test
    @DisplayName("a required field of a type that cannot be generated is refused")
    void requiredUnsupportedType() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"required": ["blob"], "properties": {
                  "blob": {"bsonType": "binData"}}}}}
                """), "t.blob", "binData", "required");
    }

    @Test
    @DisplayName("additionalProperties: false without _id in properties is refused, saying how to fix it")
    void closedSchemaWithoutIdIsRefused() {
        assertRefused(schema("""
                {"name": "t", "validator": {"$jsonSchema": {"additionalProperties": false, "properties": {
                  "name": {"bsonType": "string"}}}}}
                """), "additionalProperties", "_id");
    }

    @Test
    @DisplayName("additionalProperties: false with _id declared passes")
    void closedSchemaWithIdPasses() {
        ParsedSchema closed = schema("""
                {"name": "t", "validator": {"$jsonSchema": {"additionalProperties": false, "properties": {
                  "_id": {"bsonType": "objectId"}, "name": {"bsonType": "string"}}}}}
                """);

        assertThatCode(() -> validator.validate(closed, Dialect.MONGODB)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaUploadValidatorTest`
Expected: compilation failure (`validate(ParsedSchema, Dialect)` does not exist).

- [ ] **Step 3: Implement**

In `SchemaUploadValidator`, rename the current `validate(ParsedSchema)` body to `private void validateSql(ParsedSchema schema)` without changing a character, then add:

```java
    public void validate(ParsedSchema schema) {
        validate(schema, Dialect.MYSQL);
    }

    public void validate(ParsedSchema schema, Dialect dialect) {
        if (dialect == Dialect.MONGODB) {
            validateMongo(schema);
        } else {
            validateSql(schema);
        }
    }

    private void validateMongo(ParsedSchema schema) {
        if (schema.tables().isEmpty()) {
            throw new SchemaUploadException(
                    "No collections were found in the uploaded file. Upload a JSON document with a "
                            + "\"collections\" array, each collection with a $jsonSchema validator.");
        }
        if (schema.tables().size() > maxTables) {
            throw new SchemaUploadException(
                    "The uploaded schema has " + schema.tables().size()
                            + " collections, which is more than the " + maxTables + " this build accepts.");
        }

        Set<String> seen = new HashSet<>();
        for (ParsedTable collection : schema.tables()) {
            requireSafe(collection.name(), "Collection name");
            if (!seen.add(collection.name().toLowerCase(Locale.ROOT))) {
                throw new SchemaUploadException(
                        "The uploaded schema declares the collection '" + collection.name() + "' more than once.");
            }
            for (ParsedColumn field : collection.columns()) {
                for (String segment : field.name().split("\\.")) {
                    requireSafe(segment, "Field name");
                }
                if (field.notNull() && !JsonSchemaUploadParser.GENERATED_TYPES.contains(field.type())) {
                    throw new SchemaUploadException(
                            "Field '" + collection.name() + "." + field.name() + "' is required and has bsonType '"
                                    + field.type() + "', which QuerySense cannot generate values for. Make it "
                                    + "optional, or use one of: string, int, long, double, decimal, bool, date, "
                                    + "objectId, object, array.");
                }
            }
            if (collection.validator() != null
                    && collection.validator().get("$jsonSchema") instanceof Document jsonSchema
                    && Boolean.FALSE.equals(jsonSchema.get("additionalProperties"))
                    && !(jsonSchema.get("properties") instanceof Document properties && properties.containsKey("_id"))) {
                throw new SchemaUploadException(
                        "Collection '" + collection.name() + "' sets additionalProperties: false but does not "
                                + "declare _id in properties, so MongoDB would reject every document. Add "
                                + "\"_id\": {\"bsonType\": \"objectId\"} to its properties.");
            }
        }

        for (KnownSchema.ForeignKey reference : schema.allForeignKeys()) {
            String name = reference.fromTable() + "." + reference.fromColumn();
            ParsedColumn source = schema.table(reference.fromTable())
                    .flatMap(t -> t.column(reference.fromColumn()))
                    .orElseThrow(() -> new SchemaUploadException(
                            "The reference " + name + " names a field that is not declared in '"
                                    + reference.fromTable() + "''s $jsonSchema."));
            ParsedTable target = schema.table(reference.toTable()).orElseThrow(() -> new SchemaUploadException(
                    "The reference " + name + " points at the collection '" + reference.toTable()
                            + "', which is not in the uploaded schema."));
            String targetName = reference.toTable() + "." + reference.toColumn();
            ParsedColumn targetField = target.column(reference.toColumn()).orElseThrow(() -> new SchemaUploadException(
                    "The reference " + name + " points at '" + targetName + "', which is not declared."));
            for (String array : target.embeddedArrays()) {
                if (reference.toColumn().equals(array) || reference.toColumn().startsWith(array + ".")) {
                    throw new SchemaUploadException(
                            "The reference " + name + " points at '" + targetName + "', which is inside the "
                                    + "embedded array '" + array + "'. A reference must point at a field with "
                                    + "one value per document.");
                }
            }
            if (!"any".equals(source.type()) && !"any".equals(targetField.type())
                    && !source.type().equals(targetField.type())) {
                throw new SchemaUploadException(
                        "The reference " + name + " is bsonType '" + source.type() + "' but '" + targetName
                                + "' is '" + targetField.type() + "', so MongoDB's validator would reject the "
                                + "seeded values. Declare both with the same bsonType.");
            }
        }

        for (IndexSpec index : schema.indexes()) {
            ParsedTable collection = schema.table(index.table()).orElseThrow();
            for (IndexSpec.KeyPart part : index.keys()) {
                String path = part.column();
                boolean declared = collection.column(path).isPresent()
                        || collection.embeddedArrays().contains(path)
                        || collection.columns().stream().anyMatch(c -> c.name().startsWith(path + "."));
                if (!declared) {
                    throw new SchemaUploadException(
                            "The index on '" + index.table() + "' names the field '" + path
                                    + "', which is not declared in its $jsonSchema.");
                }
            }
        }
    }
```

New imports: `com.querysense.backend.dialect.Dialect`, `com.querysense.backend.schema.IndexSpec`, `com.querysense.backend.schema.json.JsonSchemaUploadParser`, `org.bson.Document`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaUploadValidatorTest+SchemaUploadValidatorTest`
Expected: PASS, both classes.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(upload): validate a MongoDB schema's names, references, indexes and seedable types

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `MongoProvisioner`, and an unreachable MongoDB is a 503

**Files:**
- Create: `backend/.../exception/MongoUnavailableException.java`
- Modify: `backend/.../exception/GlobalExceptionHandler.java`
- Create: `backend/.../schema/upload/MongoProvisioner.java`
- Modify: `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/querysense/backend/schema/upload/MongoProvisionerTest.java`
- Test: add one case to the existing handler test if one exists (`grep -rl GlobalExceptionHandler backend/src/test`); otherwise the 503 mapping is covered in Task 8's controller test.

**Interfaces:**
- Consumes: `SchemaProvisioning`, `ParsedSchema.indexesOf`, `ParsedTable.validator()` (Task 1).
- Produces:
  - `class MongoUnavailableException extends RuntimeException` with `(String message, Throwable cause)`. `GlobalExceptionHandler` maps it to 503 through `buildError`.
  - `@Component MongoProvisioner implements SchemaProvisioning` with `MongoProvisioner(MongoClient client, @Value("${querysense.schema.upload.mongo-database:querysense_uploads}") String uploadDatabase)`, `dialect()` = `MONGODB`, `uploadDatabase()`, `provision(ParsedSchema)` (into the upload database) and `provision(ParsedSchema, String database)`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
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
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoProvisionerTest {

    private static final String UPLOAD = """
            {"collections": [{"name": "customers",
              "validator": {"$jsonSchema": {"required": ["email"], "properties": {"email": {"bsonType": "string"}}}},
              "indexes": [{"key": {"email": 1}, "unique": true}, {"key": {"email": 1, "_id": -1}}]}]}
            """;

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private MongoProvisioner provisioner;
    private ParsedSchema schema;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        provisioner = new MongoProvisioner(client, "querysense_uploads");
        schema = new JsonSchemaUploadParser().parse(UPLOAD);
    }

    @Test
    @DisplayName("serves MongoDB and names its upload database")
    void identity() {
        assertThat(provisioner.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(provisioner.uploadDatabase()).isEqualTo("querysense_uploads");
    }

    @Test
    @DisplayName("drops, then creates the collection with the uploaded validator, strict and erroring")
    void createsWithStrictValidator() {
        provisioner.provision(schema);

        verify(client).getDatabase("querysense_uploads");
        InOrder order = inOrder(collection, database);
        order.verify(collection).drop();
        ArgumentCaptor<CreateCollectionOptions> options = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        order.verify(database).createCollection(eq("customers"), options.capture());

        var validation = options.getValue().getValidationOptions();
        assertThat(((Document) validation.getValidator()).get("$jsonSchema")).isInstanceOf(Document.class);
        assertThat(validation.getValidationLevel()).isEqualTo(ValidationLevel.STRICT);
        assertThat(validation.getValidationAction()).isEqualTo(ValidationAction.ERROR);
    }

    @Test
    @DisplayName("creates each index with its key order, direction and uniqueness")
    void createsIndexes() {
        provisioner.provision(schema);

        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, org.mockito.Mockito.times(2)).createIndex(keys.capture(), options.capture());

        assertThat(keys.getAllValues().get(0)).isEqualTo(new Document("email", 1));
        assertThat(options.getAllValues().get(0).isUnique()).isTrue();
        assertThat(((Document) keys.getAllValues().get(1)).keySet()).containsExactly("email", "_id");
        assertThat(((Document) keys.getAllValues().get(1)).get("_id")).isEqualTo(-1);
        assertThat(options.getAllValues().get(1).isUnique()).isFalse();
    }

    @Test
    @DisplayName("provision can target another database, for the built-in demo")
    void targetsNamedDatabase() {
        provisioner.provision(schema, "querysense");

        verify(client).getDatabase("querysense");
        verify(client, never()).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("a collection with no validator is created without validation options")
    void noValidator() {
        provisioner.provision(new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}"));

        ArgumentCaptor<CreateCollectionOptions> options = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        verify(database).createCollection(eq("logs"), options.capture());
        assertThat(options.getValue().getValidationOptions().getValidator()).isNull();
    }

    @Test
    @DisplayName("a missing privilege names the roles the account needs")
    void unauthorizedNamesRoles() {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(13))
                .append("errmsg", new BsonString("not authorized on querysense_uploads"));
        doThrow(new MongoCommandException(response, new ServerAddress()))
                .when(database).createCollection(anyString(), any(CreateCollectionOptions.class));

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("readWrite")
                .hasMessageContaining("dbAdmin")
                .hasMessageContaining("querysense_uploads");
    }

    @Test
    @DisplayName("another command failure is reported with MongoDB's own message")
    void otherCommandFailure() {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(2))
                .append("errmsg", new BsonString("$jsonSchema keyword 'foo' is not currently supported"));
        doThrow(new MongoCommandException(response, new ServerAddress()))
                .when(database).createCollection(anyString(), any(CreateCollectionOptions.class));

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers")
                .hasMessageContaining("'foo' is not currently supported");
    }

    @Test
    @DisplayName("an unreachable server is a MongoUnavailableException (a 503), not a 500")
    void unreachableServerIs503() {
        doThrow(new MongoTimeoutException("Timed out after 2000 ms")).when(collection).drop();

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(MongoUnavailableException.class)
                .hasMessageContaining("MongoDB");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoProvisionerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`MongoUnavailableException.java`:

```java
package com.querysense.backend.exception;

/** Thrown when MongoDB cannot be reached for a request that needs it. */
public class MongoUnavailableException extends RuntimeException {

    public MongoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`GlobalExceptionHandler`, next to the `LlmUnavailableException` handler:

```java
    @ExceptionHandler(MongoUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleMongoUnavailable(MongoUnavailableException ex) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }
```

`MongoProvisioner.java`:

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.mongodb.client.model.ValidationOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates an uploaded MongoDB schema's collections, validators and indexes. */
@Component
public class MongoProvisioner implements SchemaProvisioning {

    private static final Logger log = LoggerFactory.getLogger(MongoProvisioner.class);
    private static final int UNAUTHORIZED = 13;

    private final MongoClient client;
    private final String uploadDatabase;

    public MongoProvisioner(MongoClient client,
                            @Value("${querysense.schema.upload.mongo-database:querysense_uploads}") String uploadDatabase) {
        this.client = client;
        this.uploadDatabase = uploadDatabase;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public String uploadDatabase() {
        return uploadDatabase;
    }

    @Override
    public void provision(ParsedSchema schema) {
        provision(schema, uploadDatabase);
    }

    public void provision(ParsedSchema schema, String database) {
        MongoDatabase db = client.getDatabase(database);
        String current = null;
        try {
            for (ParsedTable collection : schema.tables()) {
                current = collection.name();
                db.getCollection(collection.name()).drop();
                db.createCollection(collection.name(), options(collection));
                for (IndexSpec index : schema.indexesOf(collection.name())) {
                    db.getCollection(collection.name()).createIndex(keys(index), new IndexOptions().unique(index.unique()));
                }
            }
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw new MongoUnavailableException(
                    "MongoDB is not reachable, so the schema could not be provisioned into '" + database
                            + "'. Check that mongod is running at the address in spring.data.mongodb.uri.", e);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == UNAUTHORIZED) {
                throw new SchemaUploadException(
                        "MongoDB refused to create collections in '" + database + "'. The account QuerySense "
                                + "connects with needs the readWrite and dbAdmin roles on that database.", e);
            }
            throw new SchemaUploadException(
                    "MongoDB could not create the collection '" + current + "': " + e.getErrorMessage(), e);
        }
        log.info("Provisioned {} collection(s) into '{}'", schema.tables().size(), database);
    }

    private static CreateCollectionOptions options(ParsedTable collection) {
        CreateCollectionOptions options = new CreateCollectionOptions();
        if (collection.validator() != null) {
            options.validationOptions(new ValidationOptions()
                    .validator(collection.validator())
                    .validationLevel(ValidationLevel.STRICT)
                    .validationAction(ValidationAction.ERROR));
        }
        return options;
    }

    private static Document keys(IndexSpec index) {
        Document keys = new Document();
        index.keys().forEach(part -> keys.append(part.column(), part.direction()));
        return keys;
    }
}
```

`application.properties`, under `# Schema upload`:

```properties
querysense.schema.upload.mongo-database=querysense_uploads
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoProvisionerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(upload): provision MongoDB collections with strict validators and their indexes

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `MongoSeeder`

**Files:**
- Create: `backend/.../schema/upload/MongoSeeder.java`
- Test: `backend/src/test/java/com/querysense/backend/schema/upload/MongoSeederTest.java`

**Interfaces:**
- Consumes: `SchemaSeeding`, `SeedReport` (Task 1), `JsonSchemaUploadParser.normalise` (Task 2), `MongoUnavailableException` (Task 4), `ValueGenerator` (existing, unchanged).
- Produces: `@Component MongoSeeder implements SchemaSeeding` with `MongoSeeder(MongoClient, @Value mongo-database, @Value seed-rows, @Value seed)`, `seed(ParsedSchema)` (into the upload database), `seed(ParsedSchema, String database)`, and package-private `Map<String, List<Document>> generate(ParsedSchema)` and `static String offendingField(BsonValue details)`.

Generation rules:
- `_id` is generated first: an `ObjectId` built from the fixed seed, or the declared `_id` type through `ValueGenerator` as a unique column.
- A required or unique field is always present. Any other field is left out 10% of the time.
- `enum` picks from the enum.
- Leaf types map through `ValueGenerator`: `string`/`any` → VARCHAR (`maxLength` honoured), `int`/`number` → INT → `Integer`, `long` → BIGINT → `Long`, `double` → `Double`, `decimal` → `Decimal128`, `bool` → `Boolean`, `date` → `java.util.Date`. `objectId` gets a new seeded `ObjectId`. Other types are left out.
- Arrays get 1–4 elements.
- After every collection is generated, each reference is overwritten with a value drawn from its target's generated values, through arrays, so `items.product_id` is filled in every element.

- [ ] **Step 1: Write the failing tests**

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSeederTest {

    private static final String SHOP = """
            {"collections": [
              {"name": "customers", "validator": {"$jsonSchema": {"required": ["name"], "properties": {
                 "name": {"bsonType": "string"}, "email": {"bsonType": "string"}, "city": {"bsonType": "string"},
                 "code": {"bsonType": "string", "maxLength": 6}}}},
               "indexes": [{"key": {"email": 1}, "unique": true}]},
              {"name": "products", "validator": {"$jsonSchema": {"required": ["price"], "properties": {
                 "price": {"bsonType": "decimal"}, "stock": {"bsonType": "int"}, "active": {"bsonType": "bool"}}}}},
              {"name": "employees", "validator": {"$jsonSchema": {"properties": {
                 "manager_id": {"bsonType": "objectId"}}}},
               "references": {"manager_id": "employees._id"}},
              {"name": "orders", "validator": {"$jsonSchema": {
                 "required": ["customer_id", "status", "order_date", "items"], "properties": {
                 "customer_id": {"bsonType": "objectId"},
                 "status": {"bsonType": "string", "enum": ["NEW", "SHIPPED"]},
                 "order_date": {"bsonType": "date"},
                 "blob": {"bsonType": "binData"},
                 "items": {"bsonType": "array", "items": {"bsonType": "object",
                   "required": ["product_id", "quantity"], "properties": {
                   "product_id": {"bsonType": "objectId"}, "quantity": {"bsonType": "int"}}}}}}},
               "references": {"customer_id": "customers._id", "items.product_id": "products._id"}}
            ]}
            """;

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private ParsedSchema schema;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        schema = new JsonSchemaUploadParser().parse(SHOP);
    }

    private MongoSeeder seeder(int rows) {
        return new MongoSeeder(client, "querysense_uploads", rows, 20260910L);
    }

    private static Set<Object> ids(List<Document> documents) {
        return documents.stream().map(d -> d.get("_id")).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(seeder(1).dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("every collection gets seed-rows documents with distinct ObjectIds")
    void rowsAndIds() {
        Map<String, List<Document>> docs = seeder(50).generate(schema);

        assertThat(docs).containsOnlyKeys("customers", "products", "employees", "orders");
        docs.values().forEach(list -> {
            assertThat(list).hasSize(50);
            assertThat(ids(list)).hasSize(50).allSatisfy(id -> assertThat(id).isInstanceOf(ObjectId.class));
        });
    }

    @Test
    @DisplayName("required fields are always present, and values have the BSON types the validator demands")
    void typesAndRequired() {
        Map<String, List<Document>> docs = seeder(50).generate(schema);

        assertThat(docs.get("orders")).allSatisfy(o -> {
            assertThat(o.get("customer_id")).isInstanceOf(ObjectId.class);
            assertThat(o.getString("status")).isIn("NEW", "SHIPPED");
            assertThat(o.get("order_date")).isExactlyInstanceOf(Date.class);
            assertThat(o).doesNotContainKey("blob");
        });
        assertThat(docs.get("products")).allSatisfy(p -> assertThat(p.get("price")).isInstanceOf(Decimal128.class));
        assertThat(docs.get("products")).filteredOn(p -> p.containsKey("stock"))
                .allSatisfy(p -> assertThat(p.get("stock")).isInstanceOf(Integer.class));
        assertThat(docs.get("products")).filteredOn(p -> p.containsKey("active"))
                .allSatisfy(p -> assertThat(p.get("active")).isInstanceOf(Boolean.class));
        assertThat(docs.get("customers")).allSatisfy(c -> assertThat(c.getString("name")).isNotBlank());
    }

    @Test
    @DisplayName("an optional field is sometimes left out")
    void optionalFieldsAreSometimesOmitted() {
        List<Document> customers = seeder(200).generate(schema).get("customers");

        assertThat(customers).anySatisfy(c -> assertThat(c).doesNotContainKey("city"));
        assertThat(customers).anySatisfy(c -> assertThat(c).containsKey("city"));
    }

    @Test
    @DisplayName("an optional field with a unique index is in every document, with distinct values")
    void uniqueOptionalFieldIsAlwaysPresent() {
        List<Document> customers = seeder(200).generate(schema).get("customers");

        assertThat(customers).allSatisfy(c -> assertThat(c).containsKey("email"));
        assertThat(customers.stream().map(c -> c.get("email")).collect(Collectors.toSet())).hasSize(200);
    }

    @Test
    @DisplayName("maxLength is honoured")
    void maxLengthIsHonoured() {
        assertThat(seeder(100).generate(schema).get("customers"))
                .filteredOn(c -> c.containsKey("code"))
                .allSatisfy(c -> assertThat(c.getString("code").length()).isLessThanOrEqualTo(6));
    }

    @Test
    @DisplayName("embedded arrays get 1-4 elements, each with its required fields")
    void embeddedArrays() {
        assertThat(seeder(100).generate(schema).get("orders")).allSatisfy(o -> {
            List<?> items = o.getList("items", Object.class);
            assertThat(items).hasSizeBetween(1, 4);
            assertThat(items).allSatisfy(i -> {
                assertThat(((Document) i).get("product_id")).isInstanceOf(ObjectId.class);
                assertThat(((Document) i).get("quantity")).isInstanceOf(Integer.class);
            });
        });
    }

    @Test
    @DisplayName("references draw from the target's _ids, through arrays and onto the same collection")
    void referencesPointAtSeededIds() {
        Map<String, List<Document>> docs = seeder(80).generate(schema);
        Set<Object> customerIds = ids(docs.get("customers"));
        Set<Object> productIds = ids(docs.get("products"));
        Set<Object> employeeIds = ids(docs.get("employees"));

        assertThat(docs.get("orders")).allSatisfy(o -> {
            assertThat(customerIds).contains(o.get("customer_id"));
            o.getList("items", Document.class)
                    .forEach(i -> assertThat(productIds).contains(i.get("product_id")));
        });
        assertThat(docs.get("employees")).filteredOn(e -> e.containsKey("manager_id"))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(employeeIds).contains(e.get("manager_id")));
    }

    @Test
    @DisplayName("the same seed generates the same documents")
    void deterministic() {
        assertThat(seeder(30).generate(schema)).isEqualTo(seeder(30).generate(schema));
    }

    @Test
    @DisplayName("documents go in unordered batches of 1,000, and the report counts them")
    @SuppressWarnings("unchecked")
    void insertsInBatches() {
        ParsedSchema one = new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}");

        SeedReport report = seeder(2500).seed(one);

        ArgumentCaptor<List<Document>> batches = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<InsertManyOptions> options = ArgumentCaptor.forClass(InsertManyOptions.class);
        verify(collection, times(3)).insertMany(batches.capture(), options.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(1000, 1000, 500);
        assertThat(options.getAllValues()).allSatisfy(o -> assertThat(o.isOrdered()).isFalse());
        assertThat(report.rowCounts()).containsEntry("logs", 2500);
        assertThat(report.warnings()).isEmpty();
        verify(client).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("a validation failure fails the upload, naming the collection and the field")
    void validationFailureNamesCollectionAndField() {
        BsonDocument details = new BsonDocument("failingDocumentId", new BsonString("x"))
                .append("details", new BsonDocument("schemaRulesNotSatisfied", new BsonArray(List.of(
                        new BsonDocument("operatorName", new BsonString("properties"))
                                .append("propertiesNotSatisfied", new BsonArray(List.of(
                                        new BsonDocument("propertyName", new BsonString("zip")))))))));
        BulkWriteError error = new BulkWriteError(121, "Document failed validation", details, 0);
        doThrow(new MongoBulkWriteException(mock(BulkWriteResult.class), List.of(error), null,
                new ServerAddress(), Set.of()))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));

        assertThatThrownBy(() -> seeder(10).seed(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("'customers'")
                .hasMessageContaining("'zip'");
    }

    @Test
    @DisplayName("missingProperties is found as the offending field too")
    void missingPropertiesIsNamed() {
        BsonDocument details = new BsonDocument("details", new BsonDocument("schemaRulesNotSatisfied",
                new BsonArray(List.of(new BsonDocument("operatorName", new BsonString("required"))
                        .append("missingProperties", new BsonArray(List.of(new BsonString("sku"))))))));

        assertThat(MongoSeeder.offendingField(details)).isEqualTo("sku");
        assertThat(MongoSeeder.offendingField(new BsonDocument())).isNull();
    }

    @Test
    @DisplayName("duplicate-key rejections become a warning, with the inserted count from MongoDB")
    void duplicatesWarn() {
        BulkWriteResult result = mock(BulkWriteResult.class);
        when(result.getInsertedCount()).thenReturn(8);
        BulkWriteError duplicate = new BulkWriteError(11000, "E11000 duplicate key error", new BsonDocument(), 3);
        doThrow(new MongoBulkWriteException(result, List.of(duplicate, duplicate), null, new ServerAddress(), Set.of()))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));
        ParsedSchema one = new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}");

        SeedReport report = seeder(10).seed(one);

        assertThat(report.rowCounts()).containsEntry("logs", 8);
        assertThat(report.warnings()).singleElement()
                .satisfies(w -> assertThat(w).contains("8 of 10").contains("logs").contains("E11000"));
    }

    @Test
    @DisplayName("losing the server mid-seed is a MongoUnavailableException (a 503)")
    void serverLostWhileSeedingIs503() {
        doThrow(new MongoTimeoutException("Timed out after 2000 ms"))
                .when(collection).insertMany(anyList(), any(InsertManyOptions.class));

        assertThatThrownBy(() -> seeder(10).seed(schema)).isInstanceOf(MongoUnavailableException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoSeederTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Fills uploaded MongoDB collections with generated documents that satisfy their $jsonSchema. */
@Component
public class MongoSeeder implements SchemaSeeding {

    private static final Logger log = LoggerFactory.getLogger(MongoSeeder.class);
    private static final int BATCH_SIZE = 1000;
    private static final int DOCUMENT_VALIDATION_FAILURE = 121;
    private static final double OMIT_RATE = 0.10;
    private static final Object NOT_GENERATED = new Object();

    private final MongoClient client;
    private final String uploadDatabase;
    private final int seedRows;
    private final long seed;

    public MongoSeeder(MongoClient client,
                       @Value("${querysense.schema.upload.mongo-database:querysense_uploads}") String uploadDatabase,
                       @Value("${querysense.schema.upload.seed-rows:100}") int seedRows,
                       @Value("${querysense.schema.upload.seed:20260910}") long seed) {
        this.client = client;
        this.uploadDatabase = uploadDatabase;
        this.seedRows = seedRows;
        this.seed = seed;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public SeedReport seed(ParsedSchema schema) {
        return seed(schema, uploadDatabase);
    }

    public SeedReport seed(ParsedSchema schema, String database) {
        Map<String, List<Document>> documents = generate(schema);
        MongoDatabase db = client.getDatabase(database);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try {
            for (ParsedTable collection : schema.tables()) {
                counts.put(collection.name(), insert(db.getCollection(collection.name()), collection.name(),
                        documents.get(collection.name()), warnings));
            }
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw new MongoUnavailableException(
                    "MongoDB stopped answering while seeding '" + database + "'. Check that mongod is running "
                            + "and upload again.", e);
        }
        return new SeedReport(counts, warnings);
    }

    Map<String, List<Document>> generate(ParsedSchema schema) {
        Map<String, List<Document>> documents = new LinkedHashMap<>();
        for (ParsedTable collection : schema.tables()) {
            documents.put(collection.name(), generateCollection(collection));
        }
        for (ParsedTable collection : schema.tables()) {
            for (KnownSchema.ForeignKey reference : collection.foreignKeys()) {
                fillReference(documents, reference);
            }
        }
        return documents;
    }

    private List<Document> generateCollection(ParsedTable collection) {
        ValueGenerator values = new ValueGenerator(seed + collection.name().hashCode());
        Random random = new Random(seed + collection.name().hashCode() + 1);
        Document jsonSchema = jsonSchemaOf(collection);
        Document idSchema = propertiesOf(jsonSchema).get("_id") instanceof Document d ? d : null;

        List<Document> out = new ArrayList<>(seedRows);
        for (int row = 0; row < seedRows; row++) {
            Object id = idSchema == null
                    ? objectId(random, row)
                    : valueFor(collection, "_id", idSchema, values, random, row);
            Document document = new Document("_id", id);
            fillObject(collection, "", jsonSchema, document, values, random, row);
            out.add(document);
        }
        return out;
    }

    private void fillObject(ParsedTable collection, String prefix, Document schema, Document target,
                            ValueGenerator values, Random random, int row) {
        List<String> required = stringList(schema.get("required"));
        for (Map.Entry<String, Object> property : propertiesOf(schema).entrySet()) {
            if (prefix.isEmpty() && "_id".equals(property.getKey())) {
                continue;
            }
            if (!(property.getValue() instanceof Document node)) {
                continue;
            }
            String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
            boolean alwaysPresent = required.contains(property.getKey()) || isUnique(collection, path);
            if (!alwaysPresent && random.nextDouble() < OMIT_RATE) {
                continue;
            }
            Object value = valueFor(collection, path, node, values, random, row);
            if (value != NOT_GENERATED) {
                target.put(property.getKey(), value);
            }
        }
    }

    private Object valueFor(ParsedTable collection, String path, Document node,
                            ValueGenerator values, Random random, int row) {
        if (node.get("enum") instanceof List<?> choices && !choices.isEmpty()) {
            return choices.get(random.nextInt(choices.size()));
        }
        return switch (typeOf(node)) {
            case "object" -> {
                Document child = new Document();
                fillObject(collection, path, node, child, values, random, row);
                yield child;
            }
            case "array" -> arrayOf(collection, path, node, values, random, row);
            case "string", "any" -> values.valueFor(column(collection, path, "VARCHAR", maxLength(node)), row, seedRows);
            case "int", "number" ->
                    ((Number) values.valueFor(column(collection, path, "INT", List.of()), row, seedRows)).intValue();
            case "long" ->
                    ((Number) values.valueFor(column(collection, path, "BIGINT", List.of()), row, seedRows)).longValue();
            case "double" ->
                    ((BigDecimal) values.valueFor(column(collection, path, "DOUBLE", List.of()), row, seedRows)).doubleValue();
            case "decimal" ->
                    new Decimal128((BigDecimal) values.valueFor(column(collection, path, "DECIMAL", List.of()), row, seedRows));
            case "bool" -> values.valueFor(column(collection, path, "BOOLEAN", List.of()), row, seedRows);
            case "date" -> new Date(((java.util.Date) values.valueFor(
                    column(collection, path, "DATETIME", List.of()), row, seedRows)).getTime());
            case "objectId" -> objectId(random, row);
            default -> NOT_GENERATED;
        };
    }

    private Object arrayOf(ParsedTable collection, String path, Document node,
                           ValueGenerator values, Random random, int row) {
        Document items = node.get("items") instanceof Document d ? d : new Document();
        int size = 1 + random.nextInt(4);
        List<Object> elements = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object element = valueFor(collection, path, items, values, random, row);
            if (element == NOT_GENERATED) {
                return NOT_GENERATED;
            }
            elements.add(element);
        }
        return elements;
    }

    static String typeOf(Document node) {
        Object declared = node.containsKey("bsonType") ? node.get("bsonType") : node.get("type");
        if (declared instanceof String s) {
            return JsonSchemaUploadParser.normalise(s);
        }
        if (declared instanceof List<?> list) {
            for (Object t : list) {
                if (t instanceof String s && !"null".equals(s)) {
                    return JsonSchemaUploadParser.normalise(s);
                }
            }
        }
        if (node.containsKey("properties")) {
            return "object";
        }
        if (node.containsKey("items")) {
            return "array";
        }
        return "any";
    }

    private static ParsedColumn column(ParsedTable collection, String path, String sqlType, List<String> args) {
        String name = path.substring(path.lastIndexOf('.') + 1);
        return new ParsedColumn(name, sqlType, args, true, false, isUnique(collection, path), "_id".equals(path));
    }

    private static boolean isUnique(ParsedTable collection, String path) {
        return collection.columns().stream().anyMatch(c -> c.name().equals(path) && c.unique());
    }

    private static List<String> maxLength(Document node) {
        return node.get("maxLength") instanceof Number n ? List.of(String.valueOf(n.intValue())) : List.of();
    }

    private static ObjectId objectId(Random random, int row) {
        return new ObjectId(ByteBuffer.allocate(12).putInt(1_700_000_000 + row).putLong(random.nextLong()).array());
    }

    private static Document jsonSchemaOf(ParsedTable collection) {
        return collection.validator() != null && collection.validator().get("$jsonSchema") instanceof Document d
                ? d
                : new Document();
    }

    private static Document propertiesOf(Document schema) {
        return schema.get("properties") instanceof Document d ? d : new Document();
    }

    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private void fillReference(Map<String, List<Document>> documents, KnownSchema.ForeignKey reference) {
        List<Object> pool = documents.getOrDefault(reference.toTable(), List.of()).stream()
                .map(d -> valueAt(d, reference.toColumn()))
                .filter(Objects::nonNull)
                .toList();
        if (pool.isEmpty()) {
            return;
        }
        Random picker = new Random(seed + reference.fromTable().hashCode() + reference.fromColumn().hashCode());
        String[] segments = reference.fromColumn().split("\\.");
        for (Document document : documents.getOrDefault(reference.fromTable(), List.of())) {
            assign(document, segments, 0, pool, picker);
        }
    }

    private static Object valueAt(Document document, String path) {
        Object current = document;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Document d)) {
                return null;
            }
            current = d.get(segment);
        }
        return current;
    }

    private static void assign(Object node, String[] segments, int depth, List<Object> pool, Random picker) {
        if (node instanceof List<?> list) {
            for (Object element : list) {
                assign(element, segments, depth, pool, picker);
            }
            return;
        }
        if (!(node instanceof Document document) || !document.containsKey(segments[depth])) {
            return;
        }
        if (depth < segments.length - 1) {
            assign(document.get(segments[depth]), segments, depth + 1, pool, picker);
            return;
        }
        if (document.get(segments[depth]) instanceof List<?> list) {
            List<Object> replaced = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                replaced.add(pool.get(picker.nextInt(pool.size())));
            }
            document.put(segments[depth], replaced);
        } else {
            document.put(segments[depth], pool.get(picker.nextInt(pool.size())));
        }
    }

    private int insert(MongoCollection<Document> collection, String name, List<Document> documents,
                       List<String> warnings) {
        int inserted = 0;
        String firstFailure = null;
        for (int from = 0; from < documents.size(); from += BATCH_SIZE) {
            List<Document> batch = documents.subList(from, Math.min(from + BATCH_SIZE, documents.size()));
            try {
                collection.insertMany(batch, new InsertManyOptions().ordered(false));
                inserted += batch.size();
            } catch (MongoBulkWriteException e) {
                for (BulkWriteError error : e.getWriteErrors()) {
                    if (error.getCode() == DOCUMENT_VALIDATION_FAILURE) {
                        throw validationFailure(name, error);
                    }
                }
                inserted += e.getWriteResult().getInsertedCount();
                if (firstFailure == null && !e.getWriteErrors().isEmpty()) {
                    firstFailure = e.getWriteErrors().get(0).getMessage();
                }
            }
        }
        if (inserted < documents.size()) {
            warnings.add("Seeded only " + inserted + " of " + documents.size() + " documents into '" + name + "'"
                    + (firstFailure == null ? "." : " -- " + firstFailure + ".")
                    + " Document estimates for this collection describe what was actually inserted.");
        }
        log.info("Seeded {} document(s) into '{}'", inserted, name);
        return inserted;
    }

    private static SchemaUploadException validationFailure(String collection, BulkWriteError error) {
        String field = offendingField(error.getDetails());
        return new SchemaUploadException(
                "MongoDB's validator rejected a seeded document in '" + collection + "'"
                        + (field == null ? "" : " at the field '" + field + "'")
                        + ". Seeded values follow bsonType, enum, required and maxLength, but not pattern, "
                        + "ranges or minimum lengths -- loosen that rule in the upload, or remove it, and upload again.");
    }

    static String offendingField(BsonValue details) {
        if (details == null) {
            return null;
        }
        if (details.isDocument()) {
            BsonDocument document = details.asDocument();
            if (document.isString("propertyName")) {
                return document.getString("propertyName").getValue();
            }
            if (document.isArray("missingProperties") && !document.getArray("missingProperties").isEmpty()) {
                return document.getArray("missingProperties").get(0).asString().getValue();
            }
            for (BsonValue value : document.values()) {
                String found = offendingField(value);
                if (found != null) {
                    return found;
                }
            }
        } else if (details.isArray()) {
            for (BsonValue value : details.asArray()) {
                String found = offendingField(value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=MongoSeederTest+ValueGeneratorTest`
Expected: PASS. If `insertsInBatches` fails because `subList` views are captured after the fact, wrap the batch in `List.copyOf(...)` before `insertMany` (SQL's `flush` does the same). Leave the assertion unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(upload): seed MongoDB collections with documents their validators accept

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `SchemaUploadService` runs the pipeline per dialect

**Files:**
- Modify: `backend/.../schema/upload/SchemaUploadService.java`
- Modify test: `backend/src/test/java/com/querysense/backend/schema/upload/SchemaUploadServiceTest.java`

**Interfaces:**
- Consumes: the three interfaces and `SeedReport` (Task 1), `JsonSchemaUploadParser` (Task 2), `validate(schema, dialect)` (Task 3), `DialectRegistry` (existing).
- Produces:
  - `SchemaUploadService(List<SchemaUploadParser>, SchemaUploadValidator, List<SchemaProvisioning>, List<SchemaSeeding>, ActiveSchemaProvider, Environment)`
  - `SchemaSnapshot upload(String text, String label, Dialect dialect)`; `upload(text, label)` stays and means MYSQL.
  - `SchemaSnapshot reset(Dialect)`
  - `List<String> lastWarnings(Dialect)`
  - The no-argument `reset()` and `lastWarnings()` are **removed**. Their callers are this task's test and Task 8's controller.
  - The Mongo snapshot carries `dialect`, `indexes`, `fieldTypes`, embedded arrays (through `toKnownTables`), `catalog = provisioner.uploadDatabase()` and the seeded counts.
  - MySQL uploads keep the H2 refusal. Mongo uploads are refused when `spring.data.mongodb.uri` is blank.

- [ ] **Step 1: Update the existing test and add the MongoDB cases**

In `SchemaUploadServiceTest`:
- Add fields `SchemaProvisioning mongoProvisioner` and `SchemaSeeding mongoSeeder`.
- In `setUp`, add `when(provisioner.dialect()).thenReturn(Dialect.MYSQL)` and `when(seeder.dialect()).thenReturn(Dialect.MYSQL)`, and build the Mongo mocks:

```java
        mongoProvisioner = mock(SchemaProvisioning.class);
        when(mongoProvisioner.dialect()).thenReturn(Dialect.MONGODB);
        when(mongoProvisioner.uploadDatabase()).thenReturn("querysense_uploads");
        mongoSeeder = mock(SchemaSeeding.class);
        when(mongoSeeder.dialect()).thenReturn(Dialect.MONGODB);
        when(mongoSeeder.seed(any())).thenReturn(new SeedReport(Map.of("orders", 12), List.of("seed note")));
        environment.setProperty("spring.data.mongodb.uri", "mongodb://localhost:27017/querysense");
```

  (Move `environment = new MockEnvironment()` above these lines.)

- Change `service()` to:

```java
    private SchemaUploadService service() {
        return new SchemaUploadService(
                List.of(new DdlSchemaParser(), new JsonSchemaUploadParser()),
                new SchemaUploadValidator(40),
                List.of(provisioner, mongoProvisioner),
                List.of(seeder, mongoSeeder),
                activeSchema, environment);
    }
```

- `service.reset()` → `service.reset(Dialect.MYSQL)`; `service.lastWarnings()` → `service.lastWarnings(Dialect.MYSQL)`.

Add:

```java
    private static final String MONGO = """
            {"collections": [
              {"name": "customers", "validator": {"$jsonSchema": {"properties": {"email": {"bsonType": "string"}}}},
               "indexes": [{"key": {"email": 1}, "unique": true}]},
              {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                "customer_id": {"bsonType": "objectId"},
                "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                  "quantity": {"bsonType": "int"}}}}}}},
               "references": {"customer_id": "customers._id"}}]}
            """;

    @Test
    @DisplayName("a MongoDB upload goes through the Mongo parser, provisioner and seeder, and fills only the Mongo slot")
    void mongoUploadUsesMongoPipeline() {
        SchemaSnapshot snapshot = service().upload(MONGO, "shop.json", Dialect.MONGODB);

        verify(mongoProvisioner).provision(any());
        verify(mongoSeeder).seed(any());
        verify(provisioner, never()).provision(any());
        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(snapshot.catalog()).isEqualTo("querysense_uploads");
        assertThat(snapshot.tableNames()).containsExactly("customers", "orders");
        assertThat(snapshot.tables().get(1).embeddedArrays()).containsExactly("items");
        assertThat(snapshot.indexes()).singleElement().satisfies(i -> assertThat(i.unique()).isTrue());
        assertThat(snapshot.fieldTypesFor("orders")).containsEntry("items.quantity", "int");
        assertThat(snapshot.rowCountFor("orders")).isEqualTo(12);
        assertThat(snapshot.foreignKeys()).hasSize(1);
        assertThat(activeSchema.active(Dialect.MONGODB)).isEqualTo(snapshot);
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isTrue();
    }

    @Test
    @DisplayName("warnings are kept per dialect")
    void warningsPerDialect() {
        SchemaUploadService service = service();
        service.upload(MONGO, "shop.json", Dialect.MONGODB);

        assertThat(service.lastWarnings(Dialect.MONGODB)).contains("seed note");
        assertThat(service.lastWarnings(Dialect.MYSQL)).isEmpty();
    }

    @Test
    @DisplayName("a MongoDB upload without spring.data.mongodb.uri is refused before anything is touched")
    void mongoNeedsUri() {
        environment.setProperty("spring.data.mongodb.uri", "");

        assertThatThrownBy(() -> service().upload(MONGO, "shop.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("spring.data.mongodb.uri");
        verify(mongoProvisioner, never()).provision(any());
    }

    @Test
    @DisplayName("the H2 profile refuses MySQL uploads only; a MongoDB upload does not touch MySQL")
    void h2AllowsMongo() {
        environment.setActiveProfiles("h2");

        service().upload(MONGO, "shop.json", Dialect.MONGODB);

        verify(mongoProvisioner).provision(any());
    }

    @Test
    @DisplayName("an invalid MongoDB schema is rejected before provisioning")
    void mongoValidatesBeforeProvisioning() {
        assertThatThrownBy(() -> service().upload("{\"collections\": []}", "empty.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("No collections");
        verify(mongoProvisioner, never()).provision(any());
    }

    @Test
    @DisplayName("blank MongoDB text says to upload a .json file")
    void mongoBlank() {
        assertThatThrownBy(() -> service().upload("  ", "x.json", Dialect.MONGODB))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining(".json");
    }

    @Test
    @DisplayName("reset of MongoDB restores the Mongo built-in and leaves MySQL alone")
    void resetMongoOnly() {
        SchemaUploadService service = service();
        service.upload(DDL, "shop.sql");
        service.upload(MONGO, "shop.json", Dialect.MONGODB);

        SchemaSnapshot snapshot = service.reset(Dialect.MONGODB);

        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(activeSchema.isBuiltIn(Dialect.MONGODB)).isTrue();
        assertThat(activeSchema.isBuiltIn(Dialect.MYSQL)).isFalse();
        assertThat(service.lastWarnings(Dialect.MONGODB)).isEmpty();
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SchemaUploadServiceTest`
Expected: compilation failure (constructor, `upload(..., Dialect)`, `reset(Dialect)`).

- [ ] **Step 3: Implement**

Replace the body of `SchemaUploadService` (keep the class comment line):

```java
@Service
public class SchemaUploadService {

    private static final Logger log = LoggerFactory.getLogger(SchemaUploadService.class);

    private final DialectRegistry<SchemaUploadParser> parsers;
    private final SchemaUploadValidator validator;
    private final DialectRegistry<SchemaProvisioning> provisioners;
    private final DialectRegistry<SchemaSeeding> seeders;
    private final ActiveSchemaProvider activeSchema;
    private final Environment environment;
    private final Map<Dialect, List<String>> lastWarnings = new ConcurrentHashMap<>();

    public SchemaUploadService(List<SchemaUploadParser> parsers,
                               SchemaUploadValidator validator,
                               List<SchemaProvisioning> provisioners,
                               List<SchemaSeeding> seeders,
                               ActiveSchemaProvider activeSchema,
                               Environment environment) {
        this.parsers = new DialectRegistry<>(parsers, "schema upload parser");
        this.validator = validator;
        this.provisioners = new DialectRegistry<>(provisioners, "schema provisioner");
        this.seeders = new DialectRegistry<>(seeders, "schema seeder");
        this.activeSchema = activeSchema;
        this.environment = environment;
    }

    public SchemaSnapshot upload(String text, String label) {
        return upload(text, label, Dialect.MYSQL);
    }

    public SchemaSnapshot upload(String text, String label, Dialect dialect) {
        if (dialect == Dialect.MONGODB) {
            requireMongoUri();
        } else {
            requireMySqlProfile();
        }

        if (text == null || text.isBlank()) {
            throw new SchemaUploadException(dialect == Dialect.MONGODB
                    ? "The uploaded schema was empty. Upload a .json file describing the collections, each with "
                            + "a $jsonSchema validator."
                    : "The uploaded schema was empty. Upload a .sql file containing CREATE TABLE statements.");
        }

        ParsedSchema parsed = parsers.get(dialect).parse(text);
        validator.validate(parsed, dialect);
        lastWarnings.put(dialect, parsed.warnings());

        SchemaProvisioning provisioner = provisioners.get(dialect);
        provisioner.provision(parsed);

        SeedReport seeded = seeders.get(dialect).seed(parsed);
        Map<String, Integer> rowCounts = seeded.rowCounts();
        if (!seeded.warnings().isEmpty()) {
            List<String> combined = new ArrayList<>(parsed.warnings());
            combined.addAll(seeded.warnings());
            lastWarnings.put(dialect, List.copyOf(combined));
        }

        SchemaSnapshot snapshot = new SchemaSnapshot(
                "upload-" + UUID.randomUUID().toString().substring(0, 8),
                label == null || label.isBlank() ? "Uploaded schema" : label,
                provisioner.uploadDatabase(),
                parsed.toKnownTables(),
                parsed.allForeignKeys(),
                rowCounts,
                Instant.now(),
                dialect,
                parsed.indexes(),
                parsed.fieldTypes());

        log.info("Uploaded {} schema '{}': {} tables, {} rows total",
                dialect.label(), snapshot.label(), snapshot.tables().size(),
                rowCounts.values().stream().mapToInt(Integer::intValue).sum());

        return activeSchema.publish(snapshot);
    }

    public SchemaSnapshot reset(Dialect dialect) {
        lastWarnings.put(dialect, List.of());
        return activeSchema.resetToBuiltIn(dialect);
    }

    public List<String> lastWarnings(Dialect dialect) {
        return lastWarnings.getOrDefault(dialect, List.of());
    }

    private void requireMongoUri() {
        String uri = environment.getProperty("spring.data.mongodb.uri");
        if (uri == null || uri.isBlank()) {
            throw new SchemaUploadException(
                    "MongoDB upload needs spring.data.mongodb.uri (set it with the MONGO_URI environment "
                            + "variable) pointing at a running mongod, and it is not set.");
        }
    }

    // requireMySqlProfile() unchanged
}
```

Imports: drop `DdlSchemaParser`, and add `DialectRegistry`, `java.util.concurrent.ConcurrentHashMap`. The `Arrays` import stays, for `requireMySqlProfile`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=SchemaUploadServiceTest`
Expected: PASS. `SchemaControllerTest` won't compile yet; it is updated in Task 8. Run only this class.

- [ ] **Step 5: Commit**

Commit after Task 8 compiles, or now with `SchemaControllerTest` minimally fixed. The executor may fold the four call-site changes from Task 8 Step 1 into this commit so the tree compiles. **Do not commit a tree that does not compile.**

```bash
git add backend/src
git commit -m "feat(upload): run the upload pipeline for either dialect, guarded per dialect

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: The built-in Mongo demo as a checked-in upload, seeded when empty

**Files:**
- Create: `backend/src/main/resources/mongo/demo-collections.json`
- Modify: `backend/.../schema/KnownMongoSchema.java`
- Create: `backend/.../schema/upload/MongoDemoSeeder.java`
- Modify: `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/querysense/backend/schema/KnownMongoSchemaMatchesSeedTest.java`
- Test: `backend/src/test/java/com/querysense/backend/schema/upload/MongoDemoSeederTest.java`

**Interfaces:**
- Consumes: `JsonSchemaUploadParser` (Task 2), `validate(schema, MONGODB)` (Task 3), `MongoProvisioner.provision(schema, database)` (Task 4), `MongoSeeder.seed(schema, database)` (Task 5), `SchemaSnapshot` 10-argument constructor (Task 1).
- Produces:
  - `KnownMongoSchema.INDEXES` (`List<IndexSpec>`) and `KnownMongoSchema.FIELD_TYPES` (`Map<String, Map<String, String>>`), both carried by `builtIn()`.
  - `@Component MongoDemoSeeder` with `boolean seedIfEmpty()` (package-private, returns whether it seeded) and an `ApplicationReadyEvent` listener that runs it on a daemon thread when `querysense.mongo.demo.seed-on-startup` is true.

- [ ] **Step 1: Write the demo file**

`backend/src/main/resources/mongo/demo-collections.json`:

```json
{
  "collections": [
    {
      "name": "categories",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name"], "properties": {
        "name": {"bsonType": "string"}}}}
    },
    {
      "name": "customers",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name", "email"], "properties": {
        "name": {"bsonType": "string"},
        "email": {"bsonType": "string"},
        "city": {"bsonType": "string"},
        "phone": {"bsonType": "string"}}}},
      "indexes": [{"key": {"email": 1}, "unique": true}]
    },
    {
      "name": "departments",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name"], "properties": {
        "name": {"bsonType": "string"}}}}
    },
    {
      "name": "employees",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name", "email", "department_id"], "properties": {
        "name": {"bsonType": "string"},
        "email": {"bsonType": "string"},
        "department_id": {"bsonType": "objectId"},
        "manager_id": {"bsonType": "objectId"},
        "hire_date": {"bsonType": "date"}}}},
      "indexes": [
        {"key": {"email": 1}, "unique": true},
        {"key": {"department_id": 1}},
        {"key": {"manager_id": 1}}
      ],
      "references": {"department_id": "departments._id", "manager_id": "employees._id"}
    },
    {
      "name": "orders",
      "validator": {"$jsonSchema": {"bsonType": "object",
        "required": ["customer_id", "order_date", "status", "items"], "properties": {
        "customer_id": {"bsonType": "objectId"},
        "employee_id": {"bsonType": "objectId"},
        "order_date": {"bsonType": "date"},
        "status": {"bsonType": "string", "enum": ["NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED"]},
        "items": {"bsonType": "array", "items": {"bsonType": "object",
          "required": ["product_id", "quantity", "unit_price"], "properties": {
          "product_id": {"bsonType": "objectId"},
          "quantity": {"bsonType": "int"},
          "unit_price": {"bsonType": "decimal"}}}}}}},
      "indexes": [
        {"key": {"customer_id": 1}},
        {"key": {"employee_id": 1}},
        {"key": {"items.product_id": 1}}
      ],
      "references": {"customer_id": "customers._id", "employee_id": "employees._id",
                     "items.product_id": "products._id"}
    },
    {
      "name": "payments",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["order_id", "amount"], "properties": {
        "order_id": {"bsonType": "objectId"},
        "amount": {"bsonType": "decimal"},
        "payment_date": {"bsonType": "date"},
        "method": {"bsonType": "string", "enum": ["CARD", "UPI", "NETBANKING", "CASH"]}}}},
      "indexes": [{"key": {"order_id": 1}}],
      "references": {"order_id": "orders._id"}
    },
    {
      "name": "products",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name", "price"], "properties": {
        "name": {"bsonType": "string"},
        "price": {"bsonType": "decimal"},
        "category_id": {"bsonType": "objectId"},
        "supplier_id": {"bsonType": "objectId"}}}},
      "indexes": [{"key": {"category_id": 1}}, {"key": {"supplier_id": 1}}],
      "references": {"category_id": "categories._id", "supplier_id": "suppliers._id"}
    },
    {
      "name": "suppliers",
      "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name"], "properties": {
        "name": {"bsonType": "string"},
        "contact_email": {"bsonType": "string"}}}}
    }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

`KnownMongoSchemaMatchesSeedTest.java`:

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.SchemaUploadValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnownMongoSchemaMatchesSeedTest {

    private static ParsedSchema demo;

    @BeforeAll
    static void parseDemo() throws IOException {
        demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the JSON declares exactly KnownMongoSchema's collections, fields and embedded arrays, in order")
    void collectionsMatch() {
        assertThat(demo.toKnownTables()).containsExactlyElementsOf(KnownMongoSchema.TABLES);
    }

    @Test
    @DisplayName("the JSON's references are KnownMongoSchema's foreign keys")
    void referencesMatch() {
        assertThat(demo.allForeignKeys()).containsExactlyInAnyOrderElementsOf(KnownMongoSchema.FOREIGN_KEYS);
    }

    @Test
    @DisplayName("the JSON's indexes are KnownMongoSchema's indexes")
    void indexesMatch() {
        assertThat(demo.indexes()).containsExactlyInAnyOrderElementsOf(KnownMongoSchema.INDEXES);
    }

    @Test
    @DisplayName("the JSON's field types are KnownMongoSchema's field types")
    void fieldTypesMatch() {
        assertThat(demo.fieldTypes()).isEqualTo(KnownMongoSchema.FIELD_TYPES);
    }

    @Test
    @DisplayName("the demo passes the upload validator without warnings")
    void demoIsAValidUpload() {
        assertThat(demo.warnings()).isEmpty();
        assertThatCode(() -> new SchemaUploadValidator(40).validate(demo, Dialect.MONGODB))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the built-in snapshot carries the indexes and field types")
    void builtInCarriesThem() {
        SchemaSnapshot builtIn = KnownMongoSchema.builtIn();

        assertThat(builtIn.indexes()).isEqualTo(KnownMongoSchema.INDEXES);
        assertThat(builtIn.fieldTypesFor("orders")).containsEntry("items.unit_price", "decimal");
    }
}
```

`MongoDemoSeederTest.java`:

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoDemoSeederTest {

    private MongoClient client;
    private ListCollectionNamesIterable names;
    private MongoProvisioner provisioner;
    private MongoSeeder seeder;
    private ActiveSchemaProvider activeSchema;
    private MongoDemoSeeder demoSeeder;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        names = mock(ListCollectionNamesIterable.class);
        when(client.getDatabase("querysense")).thenReturn(database);
        when(database.listCollectionNames()).thenReturn(names);
        provisioner = mock(MongoProvisioner.class);
        seeder = mock(MongoSeeder.class);
        when(seeder.seed(any(), eq("querysense"))).thenReturn(new SeedReport(Map.of("orders", 5), List.of()));
        activeSchema = new ActiveSchemaProvider();
        demoSeeder = new MongoDemoSeeder(client, new JsonSchemaUploadParser(), new SchemaUploadValidator(40),
                provisioner, seeder, activeSchema, true);
    }

    @SuppressWarnings("unchecked")
    private void existing(String... collections) {
        when(names.into(any())).thenAnswer(inv -> {
            Collection<String> target = inv.getArgument(0);
            target.addAll(List.of(collections));
            return target;
        });
    }

    @Test
    @DisplayName("an empty querysense database gets the demo provisioned and seeded, and the Mongo slot refreshed")
    void seedsWhenEmpty() {
        existing("unrelated");
        AtomicInteger changes = new AtomicInteger();
        activeSchema.onSchemaChanged(d -> {
            if (d == Dialect.MONGODB) {
                changes.incrementAndGet();
            }
        });

        assertThat(demoSeeder.seedIfEmpty()).isTrue();

        verify(provisioner).provision(any(), eq("querysense"));
        verify(seeder).seed(any(), eq("querysense"));
        assertThat(changes).hasValue(1);
        assertThat(activeSchema.isBuiltIn(Dialect.MONGODB)).isTrue();
    }

    @Test
    @DisplayName("any demo collection already present means nothing is touched")
    void leavesExistingDemoAlone() {
        existing("orders");

        assertThat(demoSeeder.seedIfEmpty()).isFalse();

        verify(provisioner, never()).provision(any(), any());
        verify(seeder, never()).seed(any(), any());
    }

    @Test
    @DisplayName("an unreachable MongoDB is logged and skipped, never thrown")
    void unreachableIsSkipped() {
        when(names.into(any())).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThat(demoSeeder.seedIfEmpty()).isFalse();

        verify(provisioner, never()).provision(any(), any());
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=KnownMongoSchemaMatchesSeedTest+MongoDemoSeederTest`
Expected: compilation failure (`INDEXES`, `FIELD_TYPES`, `MongoDemoSeeder`).

- [ ] **Step 4: Implement**

`KnownMongoSchema`: add these and pass them to `builtIn()` through the 10-argument constructor:

```java
    public static final List<IndexSpec> INDEXES = List.of(
            index("customers", "email", true),
            index("employees", "email", true),
            index("employees", "department_id", false),
            index("employees", "manager_id", false),
            index("orders", "customer_id", false),
            index("orders", "employee_id", false),
            index("orders", "items.product_id", false),
            index("payments", "order_id", false),
            index("products", "category_id", false),
            index("products", "supplier_id", false));

    public static final Map<String, Map<String, String>> FIELD_TYPES = Map.of(
            "categories", Map.of("_id", "objectId", "name", "string"),
            "customers", Map.of("_id", "objectId", "name", "string", "email", "string",
                    "city", "string", "phone", "string"),
            "departments", Map.of("_id", "objectId", "name", "string"),
            "employees", Map.of("_id", "objectId", "name", "string", "email", "string",
                    "department_id", "objectId", "manager_id", "objectId", "hire_date", "date"),
            "orders", Map.of("_id", "objectId", "customer_id", "objectId", "employee_id", "objectId",
                    "order_date", "date", "status", "string", "items.product_id", "objectId",
                    "items.quantity", "int", "items.unit_price", "decimal"),
            "payments", Map.of("_id", "objectId", "order_id", "objectId", "amount", "decimal",
                    "payment_date", "date", "method", "string"),
            "products", Map.of("_id", "objectId", "name", "string", "price", "decimal",
                    "category_id", "objectId", "supplier_id", "objectId"),
            "suppliers", Map.of("_id", "objectId", "name", "string", "contact_email", "string"));

    private static IndexSpec index(String collection, String field, boolean unique) {
        return new IndexSpec(collection, List.of(new IndexSpec.KeyPart(field, 1)), unique);
    }

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
                INDEXES,
                FIELD_TYPES);
    }
```

Declare `INDEXES` and `FIELD_TYPES` above `builtIn()`. The helper `index` must be declared before first use in static-initialiser order, or referenced from a static method, which it is. `fieldTypes()` in `ParsedSchema` returns a `LinkedHashMap`, and `Map.equals` ignores the implementation, so the equality test holds.

`MongoDemoSeeder.java`:

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Provisions and seeds the built-in MongoDB demo the first time its database has none of its collections. */
@Component
public class MongoDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(MongoDemoSeeder.class);
    private static final String DEMO_FILE = "mongo/demo-collections.json";

    private final MongoClient client;
    private final JsonSchemaUploadParser parser;
    private final SchemaUploadValidator validator;
    private final MongoProvisioner provisioner;
    private final MongoSeeder seeder;
    private final ActiveSchemaProvider activeSchema;
    private final boolean enabled;

    public MongoDemoSeeder(MongoClient client,
                           JsonSchemaUploadParser parser,
                           SchemaUploadValidator validator,
                           MongoProvisioner provisioner,
                           MongoSeeder seeder,
                           ActiveSchemaProvider activeSchema,
                           @Value("${querysense.mongo.demo.seed-on-startup:true}") boolean enabled) {
        this.client = client;
        this.parser = parser;
        this.validator = validator;
        this.provisioner = provisioner;
        this.seeder = seeder;
        this.activeSchema = activeSchema;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!enabled) {
            return;
        }
        Thread seeding = new Thread(this::seedIfEmpty, "mongo-demo-seed");
        seeding.setDaemon(true);
        seeding.start();
    }

    boolean seedIfEmpty() {
        String database = KnownMongoSchema.CATALOG;
        try {
            Set<String> existing = client.getDatabase(database).listCollectionNames().into(new HashSet<>());
            boolean present = KnownMongoSchema.TABLES.stream().map(KnownSchema.Table::name).anyMatch(existing::contains);
            if (present) {
                log.info("MongoDB database '{}' already has demo collections; left as it is. Drop the database "
                        + "to have the demo provisioned and seeded again.", database);
                return false;
            }
            ParsedSchema demo = parser.parse(readDemo());
            validator.validate(demo, Dialect.MONGODB);
            provisioner.provision(demo, database);
            SeedReport report = seeder.seed(demo, database);
            log.info("Seeded the MongoDB demo into '{}': {}", database, report.rowCounts());
            report.warnings().forEach(w -> log.warn("MongoDB demo: {}", w));
            if (activeSchema.isBuiltIn(Dialect.MONGODB)) {
                activeSchema.resetToBuiltIn(Dialect.MONGODB);
            }
            return true;
        } catch (MongoUnavailableException | MongoTimeoutException | MongoSocketException e) {
            log.warn("MongoDB is not reachable, so the demo was not seeded: {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Could not seed the MongoDB demo into '{}': {}", database, e.getMessage());
            return false;
        }
    }

    private static String readDemo() {
        try {
            return new ClassPathResource(DEMO_FILE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DEMO_FILE, e);
        }
    }
}
```

`application.properties`, next to the MongoDB URI:

```properties
querysense.mongo.demo.seed-on-startup=true
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=KnownMongoSchemaMatchesSeedTest+MongoDemoSeederTest+KnownMongoSchemaTest`
Expected: PASS. If `collectionsMatch` fails, fix the **JSON** to match `KnownMongoSchema.TABLES`. The Java declaration is what steps 1–3 bound against, and changing it would change rule-engine output.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(mongo): the built-in demo as an upload file, seeded into an empty querysense database

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: The schema API takes a dialect

**Files:**
- Modify: `backend/.../controller/SchemaController.java`
- Modify: `backend/.../dto/SchemaUploadRequest.java`, `SchemaUploadResponse.java`, `SchemaTableView.java`
- Modify test: `backend/src/test/java/com/querysense/backend/controller/SchemaControllerTest.java`

**Interfaces:**
- Consumes: `SchemaUploadService.upload(text, label, dialect)`, `reset(Dialect)`, `lastWarnings(Dialect)` (Task 6); `SchemaSnapshot.fieldTypesFor`, `indexes` (Tasks 1, 7).
- Produces (spec §4.1):
  - `POST /api/schema/upload` (multipart) takes an optional `dialect` part.
  - The JSON body gains `dialect`, and `schema` is accepted as an alias of `ddl`.
  - `GET /api/schema/active?dialect=` and `POST /api/schema/reset?dialect=`.
  - Every `dialect` defaults to MySQL. An unknown value is a 400 (through `Dialect.from`'s `IllegalArgumentException`).
  - A `.json` file without MongoDB, or a `.sql` file with MongoDB, is refused with a message naming the database to choose.
  - `SchemaUploadResponse` gains `Dialect dialect` and `List<IndexSpec> indexes` as its last two components.
  - `SchemaTableView` gains `List<String> embeddedArrays` and `Map<String, String> fieldTypes` as its last two components.

- [ ] **Step 1: Update the existing tests and add the dialect cases**

In `SchemaControllerTest`:
- `when(uploadService.lastWarnings()).thenReturn(List.of())` → `when(uploadService.lastWarnings(any())).thenReturn(List.of())`
- `uploadService.upload(anyString(), eq("shop.sql"))` → `uploadService.upload(anyString(), eq("shop.sql"), eq(Dialect.MYSQL))`
- `uploadService.upload(anyString(), anyString())` → `uploadService.upload(anyString(), anyString(), any())`
- `verify(uploadService).upload("CREATE TABLE customers (id INT);", "shop.sql")` → add `, Dialect.MYSQL`
- `controller.uploadFile(file)` → `controller.uploadFile(file, null)`
- `controller.active()` → `controller.active(null)`
- `when(uploadService.reset())` → `when(uploadService.reset(Dialect.MYSQL))`, and `controller.reset()` → `controller.reset(null)`

Add:

```java
    private static final SchemaSnapshot MONGO_UPLOADED = new SchemaSnapshot(
            "upload-2", "shop.json", "querysense_uploads",
            List.of(new KnownSchema.Table("orders", List.of("_id", "items.quantity"), List.of("items"))),
            List.of(), Map.of("orders", 40), Instant.now(), Dialect.MONGODB,
            List.of(new IndexSpec("orders", List.of(new IndexSpec.KeyPart("items.quantity", 1)), false)),
            Map.of("orders", Map.of("_id", "objectId", "items.quantity", "int")));

    @Test
    @DisplayName("a .json file with dialect MongoDB is uploaded as MongoDB, and the response says so")
    void uploadsMongoFile() {
        when(uploadService.upload(anyString(), eq("shop.json"), eq(Dialect.MONGODB))).thenReturn(MONGO_UPLOADED);
        MockMultipartFile file = new MockMultipartFile("file", "shop.json", "application/json",
                "{\"collections\": []}".getBytes(StandardCharsets.UTF_8));

        SchemaUploadResponse response = controller.uploadFile(file, "MONGODB");

        assertThat(response.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(response.indexes()).hasSize(1);
        assertThat(response.tables()).singleElement().satisfies(t -> {
            assertThat(t.embeddedArrays()).containsExactly("items");
            assertThat(t.fieldTypes()).containsEntry("items.quantity", "int");
            assertThat(t.rowCount()).isEqualTo(40);
        });
        verify(uploadService).lastWarnings(Dialect.MONGODB);
    }

    @Test
    @DisplayName("a .json file without dialect MongoDB is refused, saying to choose MongoDB")
    void jsonNeedsMongo() {
        MockMultipartFile file = new MockMultipartFile("file", "shop.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.uploadFile(file, null))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("MongoDB");
        verify(uploadService, never()).upload(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a .sql file with dialect MongoDB is refused, saying to choose MySQL")
    void sqlNeedsMySql() {
        MockMultipartFile file = new MockMultipartFile("file", "shop.sql", "text/plain",
                "CREATE TABLE t (id INT);".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.uploadFile(file, "MongoDB"))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("MySQL");
    }

    @Test
    @DisplayName("an unknown dialect is a 400")
    void unknownDialect() {
        assertThatThrownBy(() -> controller.active("oracle")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("active with dialect MongoDB describes the built-in Mongo demo")
    void activeMongo() {
        SchemaUploadResponse response = controller.active("MONGODB");

        assertThat(response.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(response.builtIn()).isTrue();
        assertThat(response.catalog()).isEqualTo("querysense");
        assertThat(response.tables()).filteredOn(t -> t.name().equals("orders")).singleElement()
                .satisfies(t -> assertThat(t.embeddedArrays()).containsExactly("items"));
        assertThat(response.indexes()).isNotEmpty();
    }

    @Test
    @DisplayName("reset with dialect MongoDB resets MongoDB")
    void resetMongo() {
        when(uploadService.reset(Dialect.MONGODB)).thenReturn(KnownMongoSchema.builtIn());

        assertThat(controller.reset("MongoDB").dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("the JSON body's dialect is honoured, and 'schema' is accepted for 'ddl'")
    void jsonBodyDialectAndAlias() throws Exception {
        SchemaUploadRequest request = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"schema\": \"{}\", \"dialect\": \"MONGODB\", \"label\": \"shop.json\"}",
                SchemaUploadRequest.class);
        when(uploadService.upload("{}", "shop.json", Dialect.MONGODB)).thenReturn(MONGO_UPLOADED);

        assertThat(controller.uploadJson(request).dialect()).isEqualTo(Dialect.MONGODB);
    }
```

Imports to add: `Dialect`, `IndexSpec`, `KnownMongoSchema`, `org.mockito.ArgumentMatchers.any`, `org.mockito.Mockito.never`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SchemaControllerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`SchemaTableView.java`:

```java
/** A table or collection of the active schema as shown to the frontend. */
public record SchemaTableView(
        String name,
        List<String> columns,
        int rowCount,
        List<String> embeddedArrays,
        Map<String, String> fieldTypes) {}
```

`SchemaUploadResponse.java`: add `Dialect dialect, List<IndexSpec> indexes` after `loadedAt`, and in `from`:

```java
        List<SchemaTableView> tables = snapshot.tables().stream()
                .map(t -> new SchemaTableView(t.name(), t.columns(), snapshot.rowCountFor(t.name()),
                        t.embeddedArrays(), snapshot.fieldTypesFor(t.name())))
                .toList();

        return new SchemaUploadResponse(
                snapshot.id(),
                snapshot.label(),
                snapshot.catalog(),
                snapshot.builtIn(),
                tables,
                snapshot.foreignKeys(),
                warnings == null ? List.of() : warnings,
                snapshot.loadedAt(),
                snapshot.dialect(),
                snapshot.indexes());
```

`SchemaUploadRequest.java`: annotate `ddl` with `@JsonAlias("schema")`, keep `@NotBlank`, and add:

```java
    private String dialect;

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }
```

Change the class comment to `/** JSON request body for uploading a schema as text: DDL for MySQL, a collections document for MongoDB. */`.

`SchemaController.java`, the endpoints:

```java
    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public SchemaUploadResponse uploadFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "dialect", required = false) String dialect) {
        Dialect target = dialectOf(dialect);
        if (file == null || file.isEmpty()) {
            throw new SchemaUploadException(target == Dialect.MONGODB
                    ? "The uploaded file was empty. Choose a .json file describing the collections."
                    : "The uploaded file was empty. Choose a .sql file containing CREATE TABLE statements.");
        }
        requireMatchingExtension(file.getOriginalFilename(), target);
        if (file.getSize() > maxFileBytes) {
            throw new SchemaUploadException(
                    "That file is too large (" + (file.getSize() / 1024) + " KB). The limit is "
                            + (maxFileBytes / 1024) + " KB — "
                            + (target == Dialect.MONGODB
                                    ? "upload the collection definitions only, not documents."
                                    : "upload a schema-only dump (`mysqldump --no-data`) rather than one containing data."));
        }

        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SchemaUploadException("The uploaded file could not be read.", e);
        }

        return SchemaUploadResponse.from(
                uploadService.upload(text, file.getOriginalFilename(), target), uploadService.lastWarnings(target));
    }

    @PostMapping(path = "/upload", consumes = "application/json")
    public SchemaUploadResponse uploadJson(@Valid @RequestBody SchemaUploadRequest request) {
        Dialect target = dialectOf(request.getDialect());
        return SchemaUploadResponse.from(
                uploadService.upload(request.getDdl(), request.getLabel(), target), uploadService.lastWarnings(target));
    }

    @GetMapping("/active")
    public SchemaUploadResponse active(@RequestParam(value = "dialect", required = false) String dialect) {
        Dialect target = dialectOf(dialect);
        return SchemaUploadResponse.from(activeSchema.active(target), uploadService.lastWarnings(target));
    }

    @PostMapping("/reset")
    public SchemaUploadResponse reset(@RequestParam(value = "dialect", required = false) String dialect) {
        return SchemaUploadResponse.from(uploadService.reset(dialectOf(dialect)), List.of());
    }

    private static Dialect dialectOf(String dialect) {
        return dialect == null || dialect.isBlank() ? Dialect.MYSQL : Dialect.from(dialect);
    }

    private static void requireMatchingExtension(String filename, Dialect target) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (target == Dialect.MYSQL && name.endsWith(".json")) {
            throw new SchemaUploadException(
                    "That is a .json file, which QuerySense reads as a MongoDB schema. Choose MongoDB as the "
                            + "database and upload it again.");
        }
        if (target == Dialect.MONGODB && name.endsWith(".sql")) {
            throw new SchemaUploadException(
                    "That is a .sql file, which QuerySense reads as a MySQL schema. Choose MySQL as the database, "
                            + "or upload a .json file of MongoDB collections.");
        }
    }
```

Import `java.util.Locale`. The MySQL empty-file and oversize messages are byte-identical to today's.

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green. Task 9's integration test doesn't exist yet.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(api): schema upload, active and reset take a dialect; responses carry indexes and field types

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Integration test against a real `mongod`

**Files:**
- Test: `backend/src/test/java/com/querysense/backend/schema/upload/MongoUploadIntegrationTest.java`

**Interfaces:**
- Consumes: `JsonSchemaUploadParser`, `SchemaUploadValidator`, `MongoProvisioner`, `MongoSeeder`, and the demo JSON.
- Produces: an `@Tag("mongo")` test. It skips (JUnit assumption) when nothing listens on `localhost:27017`. It works in a throwaway database, `querysense_it_<nanoTime>`, and drops that database afterwards. It never touches `querysense` or `querysense_uploads`.

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.schema.upload;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.Document;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("mongo")
class MongoUploadIntegrationTest {

    private static final int ROWS = 200;
    private static final String DATABASE = "querysense_it_" + System.nanoTime();

    private static MongoClient client;
    private static ParsedSchema demo;

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
        demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
        new SchemaUploadValidator(40).validate(demo, Dialect.MONGODB);
        new MongoProvisioner(client, DATABASE).provision(demo);
        SeedReport report = new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);
        assertThat(report.warnings()).isEmpty();
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    private MongoDatabase db() {
        return client.getDatabase(DATABASE);
    }

    @Test
    @DisplayName("every demo collection holds the seeded documents, all accepted by the strict validator")
    void everyCollectionSeeded() {
        demo.tables().forEach(t ->
                assertThat(db().getCollection(t.name()).countDocuments()).as(t.name()).isEqualTo(ROWS));
    }

    @Test
    @DisplayName("collections are created with a strict, erroring $jsonSchema validator")
    void validatorIsStrict() {
        Document info = db().listCollections().filter(new Document("name", "orders")).first();
        Document options = info.get("options", Document.class);

        assertThat(options.get("validator", Document.class)).containsKey("$jsonSchema");
        assertThat(options.getString("validationLevel")).isEqualTo("strict");
        assertThat(options.getString("validationAction")).isEqualTo("error");
    }

    @Test
    @DisplayName("the server rejects a document the validator forbids")
    void validatorRejects() {
        assertThatThrownBy(() -> db().getCollection("orders").insertOne(new Document("status", "NEW")))
                .isInstanceOf(MongoWriteException.class)
                .satisfies(e -> assertThat(((MongoWriteException) e).getCode()).isEqualTo(121));
    }

    @Test
    @DisplayName("declared indexes exist, unique where declared")
    void indexesExist() {
        List<Document> indexes = db().getCollection("customers").listIndexes().into(new java.util.ArrayList<>());

        assertThat(indexes).anySatisfy(i -> {
            assertThat(i.get("key", Document.class)).isEqualTo(new Document("email", 1));
            assertThat(i.getBoolean("unique")).isTrue();
        });
        assertThat(db().getCollection("orders").listIndexes().into(new java.util.ArrayList<>()))
                .anySatisfy(i -> assertThat(i.get("key", Document.class)).isEqualTo(new Document("items.product_id", 1)));
    }

    @Test
    @DisplayName("every embedded item references a seeded product")
    void referencesResolve() {
        Set<Object> productIds = new HashSet<>();
        db().getCollection("products").find().forEach(p -> productIds.add(p.get("_id")));

        db().getCollection("orders").find().forEach(o -> o.getList("items", Document.class)
                .forEach(i -> assertThat(productIds).contains(i.get("product_id"))));
    }

    @Test
    @DisplayName("the demo's city values include Mumbai, as the live checklist expects")
    void mumbaiIsSeeded() {
        assertThat(db().getCollection("customers").distinct("city", String.class).into(new HashSet<>()))
                .contains("Mumbai");
    }
}
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw test -Dtest=MongoUploadIntegrationTest`

Expected, with `mongod` listening on 27017: PASS, and the `querysense_it_*` database is gone afterwards.

Expected, with nothing listening: all tests reported as skipped, never failed.

If a test fails against the real server, that is a real finding. Fix the production code (most likely `MongoSeeder`'s type mapping) and re-run the Task 5 unit tests. Do not loosen the assertion.

- [ ] **Step 3: Confirm nothing was left behind**

Run: `mongosh --quiet --eval "db.adminCommand('listDatabases').databases.map(d => d.name)"` if `mongosh` is on PATH. Otherwise report that it couldn't be checked. Expected: no `querysense_it_` entry.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test
git commit -m "test(upload): provision and seed the Mongo demo against a real mongod, skipped when none is running

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Record it in `PROJECT.md`

**Files:**
- Modify: `PROJECT.md`

- [ ] **Step 1: Add a session entry** after "Session of 2026-09-26 — seed scripts, and MongoDB parity, steps 1–3", titled `### Session of 2026-09-28 — MongoDB parity, step 4 (upload)`. It should contain:
  - The plan path.
  - A table of Tasks 1–9: one line on what each delivered, and its commit hash from `git log`.
  - The five "Departures from the spec" from this plan, one line each.
  - What was verified against the real `mongod` (Task 9's result: passed, or skipped because nothing was listening) and what is unit-tested only.

- [ ] **Step 2: Replace the "**Next:**" paragraph** of the steps 1–3 entry. Steps 1–4 are complete. Step 5 is next: `MongoExplainAnalyzer`, preflight, verdict vocabulary, dialect-aware rules and advisor, with `explain`/`listIndexes` fixtures captured from the local `mongod`.

- [ ] **Step 3: Extend the live checklist** with steps for the user to run:
  1. Start the backend with `mongod` running. The log shows `Seeded the MongoDB demo into 'querysense'`, or `already has demo collections` if it was seeded before.
  2. In the Workbench, with the target set to MongoDB, ask `customers in mumbai`. It builds, with `"Mumbai"` in the filter.
  3. `curl -F file=@backend/src/main/resources/mongo/demo-collections.json -F dialect=MONGODB http://localhost:8080/api/schema/upload` returns `"dialect": "MONGODB"`, eight collections, row counts of 12000, and the indexes.
  4. `curl http://localhost:8080/api/schema/active?dialect=MONGODB` shows the upload, and `?dialect=MYSQL` is unchanged.
  5. `curl -X POST "http://localhost:8080/api/schema/reset?dialect=MONGODB"` returns the built-in.
  6. With `mongod` stopped, the upload in step 3 returns 503 within a few seconds.

  Note that uploads write to `querysense_uploads` and the demo to `querysense`, both on the local `mongod`.

- [ ] **Step 4: Commit**

```bash
git add PROJECT.md
git commit -m "docs: record MongoDB parity step 4 — upload, the demo seeding, and the live checklist

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
