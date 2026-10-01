# MongoDB Parity, Step 7 — LLM Grounding and Validation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the rule engine declines a MongoDB question, the LLM is told the active Mongo schema (collections, typed dotted paths, enums, embedded arrays and references) and asked for one mongosh query in a supported operation. What comes back is checked against that schema exactly as SQL is: unreadable or off-schema output is flagged at confidence 0.2 and never cached, and output that misses a relation the question implies is marked incomplete.

**Architecture:** Enum values travel with the schema: `ParsedSchema.enums()` reads them from `$jsonSchema`, `SchemaSnapshot` gains an `enums` component, and `KnownMongoSchema` declares the built-in demo's. A new `MongoSchemaContext` renders the prompt's schema block. `OllamaService` picks a MongoDB prompt and cuts the reply to its first statement. `GeneratedQueryValidator` gains a MongoDB path built on `MongoShellParser` and step 5's `MongoPreflightValidator`. `QueryGenerationServiceImpl` doesn't change: its existing off-schema and incomplete handling now applies to MongoDB.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, Mockito. Ollama isn't called by any test.

**Spec:** `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`: §8, §3.4 (cache hits revalidated by the dialect's check), §12 step 7.

## Global Constraints

- Every MySQL prompt, cleaning rule, validator message and confidence stays byte-identical.
- Confidence values are unchanged: LLM 0.65, incomplete 0.4, off-schema 0.2. An off-schema answer is never saved to history, so it is never cached.
- QuerySense never executes a generated query. The validator only parses and checks it.
- Source comments: a one-line class summary only. No other comments.
- Backend tests: `cd backend && ./mvnw test`. Use `-Dtest=A,B` with a comma.
- Do not start the app. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **A reply with prose after the query** (`db.orders.find({...})` and then "This finds…") must reach the validator as the query alone. Otherwise every honest answer reads as unparseable. Pinned in Task 3 (`mongoReplyCutToFirstStatement`).
2. **A chained `find`** (`.sort(...).limit(5)`) must survive that cut, along with a `)` inside a string. Pinned in Task 3 (`chainAndQuotedParenSurvive`).
3. **A cached MongoDB answer from an earlier schema** must not be served once the active Mongo schema changes. The cache check now runs the Mongo validator. Pinned in Task 4 (`staleMongoCacheIsNotServed`).
4. **An LLM `$out` pipeline** must be flagged, never served or cached as a good answer. Pinned in Task 4 (`writeStageIsOffSchema`).
5. **Two related collections answered from one** (`orders with their customers` → `db.orders.find({})`) must be marked incomplete. When the query reads the embedded array instead, it must not be. Pinned in Task 4 (`mongoIncompleteness`).

## Departures from the spec, on purpose

- **Enums ride on `SchemaSnapshot`** as a new `enums` component (table → path → values). It is filled from uploaded `$jsonSchema` validators, and from a declared `KnownMongoSchema.ENUMS` for the demo, which a test checks against the demo JSON. MySQL snapshots carry none for now.
- **The Mongo validator reuses step 5's `MongoPreflightValidator`.** Its messages are the schema warnings, and it refuses `$out`/`$merge` here too. Field checks stop at the first stage that reshapes documents. The spec's "`$group` expressions" are covered when the `$group` is that first stage, which is where the LLM writes them.
- **The incompleteness analogue is the spec's single rule.** Two mentioned collections joined by a declared reference, answered with neither a `$lookup` nor a path into an embedded array. The SQL "multiple conditions" heuristic isn't carried over.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../schema/ddl/ParsedSchema.java` (modify) | `enums()` from `$jsonSchema` validators |
| `backend/.../schema/SchemaSnapshot.java` (modify) | `enums` component, `enumsFor(table)` |
| `backend/.../schema/KnownMongoSchema.java` (modify) | `ENUMS`, carried by `builtIn()` |
| `backend/.../schema/upload/SchemaUploadService.java` (modify) | Passes the parsed enums into the snapshot |
| `backend/.../service/impl/MongoSchemaContext.java` (new) | The prompt's MongoDB schema block |
| `backend/.../service/impl/OllamaService.java` (modify) | MongoDB prompt; reply cut to its first statement |
| `backend/.../querybuilder/GeneratedQueryValidator.java` (modify) | MongoDB path of both checks |

`backend/...` = `backend/src/main/java/com/querysense/backend`. Tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: Enum values travel with the schema

**Files:**
- Modify: `backend/.../schema/ddl/ParsedSchema.java`, `backend/.../schema/SchemaSnapshot.java`, `backend/.../schema/KnownMongoSchema.java`, `backend/.../schema/upload/SchemaUploadService.java`
- Modify test: `backend/src/test/java/com/querysense/backend/schema/KnownMongoSchemaMatchesSeedTest.java` (add one test)
- Test: `backend/src/test/java/com/querysense/backend/schema/SchemaEnumsTest.java`

**Interfaces:**
- Produces:
  - `ParsedSchema`: `public Map<String, Map<String, List<String>>> enums()`. It maps table → dotted path → values as strings, in declaration order, and includes only tables that have at least one enum. It reads `properties` recursively, plus `items` under arrays.
  - `SchemaSnapshot` gains an 11th component, `Map<String, Map<String, List<String>>> enums`. The 7-, 9- and 10-argument constructors are kept (empty map). It also gains `Map<String, List<String>> enumsFor(String table)`.
  - `KnownMongoSchema.ENUMS` covers `orders.status` and `payments.method`. `builtIn()` uses the 11-argument constructor.
  - `SchemaUploadService` builds every snapshot with `parsed.enums()`.

- [ ] **Step 1: Write the failing tests**

`SchemaEnumsTest.java`:

```java
package com.querysense.backend.schema;

import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaEnumsTest {

    @Test
    @DisplayName("enums are read from $jsonSchema, including inside embedded arrays, as strings in order")
    void enumsFromValidators() {
        ParsedSchema schema = new JsonSchemaUploadParser().parse("""
                {"collections": [
                  {"name": "orders", "validator": {"$jsonSchema": {"properties": {
                    "status": {"bsonType": "string", "enum": ["NEW", "PAID"]},
                    "items": {"bsonType": "array", "items": {"bsonType": "object", "properties": {
                      "size": {"bsonType": "int", "enum": [1, 2, 3]}}}}}}}},
                  {"name": "logs"}]}
                """);

        assertThat(schema.enums()).containsOnlyKeys("orders");
        assertThat(schema.enums().get("orders")).containsExactly(
                Map.entry("status", List.of("NEW", "PAID")),
                Map.entry("items.size", List.of("1", "2", "3")));
    }

    @Test
    @DisplayName("older snapshot constructors carry no enums")
    void olderConstructorsHaveNone() {
        SchemaSnapshot snapshot = new SchemaSnapshot("x", "x", null, List.of(), List.of(), Map.of(), Instant.EPOCH);

        assertThat(snapshot.enums()).isEmpty();
        assertThat(snapshot.enumsFor("orders")).isEmpty();
    }

    @Test
    @DisplayName("the built-in Mongo demo carries its enums")
    void builtInCarriesEnums() {
        assertThat(KnownMongoSchema.builtIn().enumsFor("orders"))
                .containsEntry("status", List.of("NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED"));
    }
}
```

In `KnownMongoSchemaMatchesSeedTest`, add:

```java
    @Test
    @DisplayName("the JSON's enums are KnownMongoSchema's enums")
    void enumsMatch() {
        assertThat(demo.enums()).isEqualTo(KnownMongoSchema.ENUMS);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=SchemaEnumsTest,KnownMongoSchemaMatchesSeedTest`
Expected: compilation failure (`enums()`, `enumsFor`, `ENUMS` missing).

- [ ] **Step 3: Implement**

`ParsedSchema`: add `import org.bson.Document;` and:

```java
    public Map<String, Map<String, List<String>>> enums() {
        Map<String, Map<String, List<String>>> byTable = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            Map<String, List<String>> found = new LinkedHashMap<>();
            if (table.validator() != null && table.validator().get("$jsonSchema") instanceof Document schema) {
                collectEnums(schema, "", found);
            }
            if (!found.isEmpty()) {
                byTable.put(table.name(), found);
            }
        }
        return byTable;
    }

    private static void collectEnums(Document schema, String prefix, Map<String, List<String>> out) {
        if (!(schema.get("properties") instanceof Document properties)) {
            return;
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof Document node)) {
                continue;
            }
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (node.get("enum") instanceof List<?> values) {
                out.put(path, values.stream().map(String::valueOf).toList());
            }
            collectEnums(node, path, out);
            if (node.get("items") instanceof Document items) {
                if (items.get("enum") instanceof List<?> values) {
                    out.put(path, values.stream().map(String::valueOf).toList());
                }
                collectEnums(items, path, out);
            }
        }
    }
```

`SchemaSnapshot`: add the 11th component `Map<String, Map<String, List<String>>> enums` after `fieldTypes`. The existing 10-argument canonical constructor becomes a delegating constructor:

```java
    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes,
                          Map<String, Map<String, String>> fieldTypes) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, fieldTypes, Map.of());
    }

    public Map<String, List<String>> enumsFor(String tableName) {
        return enums.getOrDefault(tableName, Map.of());
    }
```

The 7- and 9-argument constructors pass one more `Map.of()`, so they call the 11-argument canonical constructor.

`KnownMongoSchema`: declare above `builtIn()`

```java
    public static final Map<String, Map<String, List<String>>> ENUMS = Map.of(
            "orders", Map.of("status", List.of("NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED")),
            "payments", Map.of("method", List.of("CARD", "UPI", "NETBANKING", "CASH")));
```

and add `ENUMS` as the last argument of the `SchemaSnapshot` call in `builtIn()`.

`SchemaUploadService.upload`: add `parsed.enums()` as the last argument of its `new SchemaSnapshot(...)`.

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(schema): enum values travel with the schema, from \$jsonSchema and the Mongo demo

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `MongoSchemaContext`

**Files:**
- Create: `backend/.../service/impl/MongoSchemaContext.java`
- Test: `backend/src/test/java/com/querysense/backend/service/impl/MongoSchemaContextTest.java`

**Interfaces:**
- Consumes: `SchemaSnapshot.fieldTypesFor`, `enumsFor` (Task 1), `KnownSchema.Table.embeddedArrays()`, `SchemaSnapshot.foreignKeys()`.
- Produces: `final class MongoSchemaContext` (package-private) with `static String describe(SchemaSnapshot snapshot, List<KnownSchema.Table> mentioned)`. It describes the mentioned collections, or all of them when none are mentioned. It returns `""` when the snapshot has no collections. The format is fixed by the test below; it is indented to match the MySQL block's twelve spaces.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSchemaContextTest {

    private static final SchemaSnapshot DEMO = KnownMongoSchema.builtIn();

    private static KnownSchema.Table table(String name) {
        return DEMO.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("mentioned collections: typed dotted paths, enums, embedded arrays, and the references between them")
    void mentionedCollections() {
        String context = MongoSchemaContext.describe(DEMO, List.of(table("orders"), table("customers")));

        assertThat(context).isEqualTo("""

                            Real MongoDB schema — use ONLY these collections and fields; never invent a collection or field name:
                            - orders: _id (objectId), customer_id (objectId), employee_id (objectId), order_date (date), status (string, one of "NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED"), items.product_id (objectId), items.quantity (int), items.unit_price (decimal); embedded array: items
                            - customers: _id (objectId), name (string), email (string), city (string), phone (string)
                            - Reference: orders.customer_id → customers._id — use $lookup with localField "customer_id" and foreignField "_id" to combine them.
                """);
    }

    @Test
    @DisplayName("no collection mentioned means every collection is described")
    void allWhenNoneMentioned() {
        String context = MongoSchemaContext.describe(DEMO, List.of());

        for (KnownSchema.Table t : DEMO.tables()) {
            assertThat(context).contains("- " + t.name() + ": ");
        }
        assertThat(context).contains("- Reference: employees.manager_id → employees._id");
    }

    @Test
    @DisplayName("a reference into an embedded array names the dotted local field")
    void embeddedReference() {
        assertThat(MongoSchemaContext.describe(DEMO, List.of(table("orders"), table("products"))))
                .contains("- Reference: orders.items.product_id → products._id — use $lookup with localField "
                        + "\"items.product_id\" and foreignField \"_id\"");
    }

    @Test
    @DisplayName("fields without a declared type are listed by name alone, and an empty schema says nothing")
    void untypedAndEmpty() {
        SchemaSnapshot bare = new SchemaSnapshot("u", "u", "db", List.of(new KnownSchema.Table("logs", List.of("_id", "level"))),
                List.of(), Map.of(), Instant.EPOCH);

        assertThat(MongoSchemaContext.describe(bare, List.of())).contains("- logs: _id, level\n");
        SchemaSnapshot empty = new SchemaSnapshot("e", "e", "db", List.of(), List.of(), Map.of(), Instant.EPOCH);
        assertThat(MongoSchemaContext.describe(empty, List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaContextTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Describes the active MongoDB schema to the language model: collections, typed field paths, enums, embedded arrays and references. */
final class MongoSchemaContext {

    private static final String INDENT = "            ";

    private MongoSchemaContext() {
    }

    static String describe(SchemaSnapshot snapshot, List<KnownSchema.Table> mentioned) {
        List<KnownSchema.Table> tables = mentioned.isEmpty() ? snapshot.tables() : mentioned;
        if (tables.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n" + INDENT + "Real MongoDB schema — use ONLY these collections "
                + "and fields; never invent a collection or field name:\n");
        for (KnownSchema.Table table : tables) {
            Map<String, String> types = snapshot.fieldTypesFor(table.name());
            Map<String, List<String>> enums = snapshot.enumsFor(table.name());
            String fields = table.columns().stream()
                    .map(column -> describeField(column, types.get(column), enums.get(column)))
                    .collect(Collectors.joining(", "));
            out.append(INDENT).append("- ").append(table.name()).append(": ").append(fields);
            if (!table.embeddedArrays().isEmpty()) {
                out.append("; embedded array").append(table.embeddedArrays().size() == 1 ? "" : "s").append(": ")
                        .append(String.join(", ", table.embeddedArrays()));
            }
            out.append("\n");
        }
        Set<String> names = tables.stream().map(KnownSchema.Table::name).collect(Collectors.toSet());
        for (KnownSchema.ForeignKey reference : snapshot.foreignKeys()) {
            if (names.contains(reference.fromTable()) && names.contains(reference.toTable())) {
                out.append(INDENT).append("- Reference: ").append(reference.fromTable()).append(".")
                        .append(reference.fromColumn()).append(" → ").append(reference.toTable()).append(".")
                        .append(reference.toColumn()).append(" — use $lookup with localField \"")
                        .append(reference.fromColumn()).append("\" and foreignField \"")
                        .append(reference.toColumn()).append("\" to combine them.\n");
            }
        }
        return out.toString();
    }

    private static String describeField(String path, String type, List<String> values) {
        if (type == null) {
            return path;
        }
        if (values == null || values.isEmpty()) {
            return path + " (" + type + ")";
        }
        return path + " (" + type + ", one of "
                + values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ")) + ")";
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd backend && ./mvnw test -Dtest=MongoSchemaContextTest`
Expected: PASS, 4 tests. If `mentionedCollections` differs only in the text block's indentation, print `context` with visible line ends, fix the expected literal to match the twelve-space `INDENT`, and record a ruling. The content must not change.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(llm): describe the active MongoDB schema for the prompt — typed paths, enums, arrays, references

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `OllamaService` asks for mongosh and keeps only the query

**Files:**
- Modify: `backend/.../service/impl/OllamaService.java`
- Test: add to `backend/src/test/java/com/querysense/backend/service/impl/OllamaServiceTest.java`

**Interfaces:**
- Consumes: `MongoSchemaContext.describe` (Task 2), `ActiveSchemaProvider.active(Dialect.MONGODB)`, `detectTablesMentioned(Dialect.MONGODB, input)`.
- Produces:
  - For `databaseType` "MongoDB" (case ignored), the prompt is the MongoDB prompt below with `MongoSchemaContext`. Every other dialect gets today's prompt, unchanged.
  - A MongoDB reply is cut to its first `db.` statement: from the first `db.` to the `)` that closes the call chain, with balanced brackets and quotes respected. A reply with no `db.` is returned as cleaned today.

- [ ] **Step 1: Write the failing tests**

Add to `OllamaServiceTest`. It needs the imports `org.mockito.ArgumentCaptor`, `static org.mockito.Mockito.verify` and `static org.mockito.ArgumentMatchers.eq`.

```java
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String promptSent() {
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), request.capture(), eq(Map.class));
        return (String) ((Map<String, Object>) request.getValue().getBody()).get("prompt");
    }

    @Test
    @DisplayName("a MongoDB question gets the mongosh prompt and the Mongo schema, never the SQL rules")
    void mongoPrompt() {
        modelReplies("db.orders.find({\"status\": \"PAID\"})");

        service.generateQuery("paid orders", "MongoDB");

        String prompt = promptSent();
        assertThat(prompt)
                .contains("into one MongoDB shell (mongosh) query")
                .contains("find, aggregate, countDocuments, deleteMany")
                .contains("Real MongoDB schema")
                .contains("status (string, one of \"NEW\"")
                .contains("Natural language input: paid orders")
                .doesNotContain("GROUP BY")
                .doesNotContain("semicolon");
    }

    @Test
    @DisplayName("the MySQL prompt is unchanged")
    void mysqlPromptUnchanged() {
        modelReplies("SELECT 1;");

        service.generateQuery("all customers", "MySQL");

        assertThat(promptSent())
                .contains("into a valid MySQL query")
                .contains("End every SQL statement with a semicolon.")
                .contains("Real schema — use ONLY these tables and columns")
                .doesNotContain("mongosh");
    }

    @Test
    @DisplayName("a MongoDB reply with prose after the query is cut to the query")
    void mongoReplyCutToFirstStatement() {
        modelReplies("db.orders.find({\"status\": \"PAID\"})\n\nThis query finds every paid order.");

        assertThat(service.generateQuery("paid orders", "MongoDB"))
                .isEqualTo("db.orders.find({\"status\": \"PAID\"})");
    }

    @Test
    @DisplayName("a chained find, and a bracket inside a string, survive the cut")
    void chainAndQuotedParenSurvive() {
        modelReplies("Here is the query:\ndb.customers.find({\"name\": \"a)b\"}).sort({\"name\": 1}).limit(5); // top 5");

        assertThat(service.generateQuery("customers", "MongoDB"))
                .isEqualTo("db.customers.find({\"name\": \"a)b\"}).sort({\"name\": 1}).limit(5)");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=OllamaServiceTest`
Expected:
- `mongoPrompt` fails (the SQL prompt is sent).
- `mongoReplyCutToFirstStatement` and `chainAndQuotedParenSurvive` fail (the prose is kept).
- `mysqlPromptUnchanged` and every existing test pass.

- [ ] **Step 3: Implement**

In `OllamaService`:

1. In `buildPrompt`, return the MongoDB prompt first:

```java
    private String buildPrompt(String userInput, String dialect) {
        if ("MongoDB".equalsIgnoreCase(dialect)) {
            return buildMongoPrompt(userInput);
        }
        String schemaContext = buildSchemaContext(userInput, dialect);
```

(The rest of `buildPrompt` is unchanged.)

2. Add:

```java
    private String buildMongoPrompt(String userInput) {
        String schemaContext = MongoSchemaContext.describe(activeSchema.active(Dialect.MONGODB),
                activeSchema.detectTablesMentioned(Dialect.MONGODB, userInput));

        return String.format("""
            You are a MongoDB expert. Convert the following natural language query
            into one MongoDB shell (mongosh) query.

            Rules:
            - Output ONLY the query text. No explanation, no commentary, no markdown,
              no code fences, no leading phrase such as "Here is the query".
            - Your entire reply must be the query and nothing else.
            - Write exactly one statement of the form db.<collection>.<method>(...), where <method> is
              one of: find, aggregate, countDocuments, deleteMany. A find may be followed by
              .sort(...) and .limit(n).
            - Use double-quoted field names and string values.
            - Use only the collections and fields listed below; never invent one.
            - A field inside an embedded array is written with a dot, such as items.quantity; $unwind
              the array before grouping by one of its fields.
            - To combine two collections, use $lookup on one of the listed references.
            - Never generate deleteMany without a filter, and never use $out or $merge.
            - If the request is ambiguous, generate the safest reasonable interpretation.
            %s
            Natural language input: %s

            Query:
            """,
                schemaContext, userInput
        );
    }
```

3. In `cleanResponse`, replace the dialect branch with:

```java
        if ("MongoDB".equalsIgnoreCase(dialect)) {
            text = firstMongoStatement(text);
        } else {
            int terminator = text.indexOf(';');
            if (terminator >= 0) {
                text = text.substring(0, terminator + 1);
            }
        }
```

4. Add:

```java
    static String firstMongoStatement(String text) {
        int start = text.indexOf("db.");
        if (start < 0) {
            return text;
        }
        int depth = 0;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && c == ')') {
                    int next = i + 1;
                    while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                        next++;
                    }
                    if (next >= text.length() || text.charAt(next) != '.') {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }
        return text.substring(start).trim();
    }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw test -Dtest=OllamaServiceTest`
Expected: PASS. Every existing test is still green, including `doesNotApplySemicolonCutToMongo`.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(llm): a MongoDB prompt grounded in the active Mongo schema, and the reply cut to its query

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `GeneratedQueryValidator` checks MongoDB output

**Files:**
- Modify: `backend/.../querybuilder/GeneratedQueryValidator.java`
- Modify test: `backend/src/test/java/com/querysense/backend/querybuilder/GeneratedQueryValidatorTest.java` (the "MongoDB output is not checked" test is replaced)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/MongoGeneratedQueryValidatorTest.java`
- Test: `backend/src/test/java/com/querysense/backend/service/impl/MongoGenerationValidationTest.java`

**Interfaces:**
- Consumes: `MongoShellParser`, `MongoPreflightValidator`, `MongoQueryFields.referencedPaths` (step 5).
- Produces:
  - Constructors:
    - `@Autowired GeneratedQueryValidator(ActiveSchemaProvider, MongoShellParser, MongoPreflightValidator)`
    - `GeneratedQueryValidator(ActiveSchemaProvider)`, which builds its own parser and preflight. Existing tests keep using it.
  - `namesUnknownIdentifiers(query, "MongoDB")`:
    - `"This is not a MongoDB query QuerySense can read: <parser message>"` when unparseable;
    - otherwise `MongoPreflightValidator.validate`'s message (unknown collection, unknown field, `$lookup` fields, `$out`/`$merge`);
    - otherwise empty.
  - `looksIncomplete(input, query, "MongoDB")` is true when all of these hold:
    - at least two collections are mentioned;
    - a declared reference joins two different ones of them;
    - the query parses, has no `$lookup`, and references no path in its collection's embedded arrays.
  - MySQL is unchanged on both methods. Any other `databaseType` is empty or false, as today.

- [ ] **Step 1: Write the failing tests**

In `GeneratedQueryValidatorTest`, delete the test at "MongoDB output is not checked — the schema here describes relational tables" (its `@Test` through its closing brace). Its replacement is below.

`MongoGeneratedQueryValidatorTest.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoGeneratedQueryValidatorTest {

    private final GeneratedQueryValidator validator = new GeneratedQueryValidator(new ActiveSchemaProvider());

    @Test
    @DisplayName("an on-schema MongoDB query raises nothing")
    void onSchemaPasses() {
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$lookup\": {\"from\": \"customers\", "
                        + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB"))
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown collection, field or $lookup field is named, in the preflight's words")
    void offSchemaIsNamed() {
        assertThat(validator.namesUnknownIdentifiers("db.transactions.find({\"buyer\": 1})", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'transactions'"));
        assertThat(validator.namesUnknownIdentifiers("db.customers.find({\"age\": 30})", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'age'"));
        assertThat(validator.namesUnknownIdentifiers("db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"buyer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'buyer_id'"));
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$group\": {\"_id\": \"$region\", \"n\": {\"$sum\": 1}}}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("'region'"));
    }

    @Test
    @DisplayName("output that is not a readable mongosh query is flagged with the parser's reason")
    void unreadableIsFlagged() {
        assertThat(validator.namesUnknownIdentifiers("SELECT * FROM orders;", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).startsWith("This is not a MongoDB query QuerySense can read: "));
        assertThat(validator.namesUnknownIdentifiers("db.orders.update({}, {\"$set\": {\"a\": 1}})", "MongoDB"))
                .isPresent();
    }

    @Test
    @DisplayName("an LLM pipeline that writes is flagged")
    void writeStageIsOffSchema() {
        assertThat(validator.namesUnknownIdentifiers(
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$out\": \"paid\"}])", "MongoDB"))
                .hasValueSatisfying(w -> assertThat(w).contains("never runs a write"));
    }

    @Test
    @DisplayName("two related collections answered from one is incomplete; a $lookup or an embedded path is not")
    void mongoIncompleteness() {
        String question = "orders with their customers";

        assertThat(validator.looksIncomplete(question, "db.orders.find({})", "MongoDB")).isTrue();
        assertThat(validator.looksIncomplete(question, "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("orders and their products",
                "db.orders.find({\"items.product_id\": {\"$exists\": true}})", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("paid orders", "db.orders.find({})", "MongoDB")).isFalse();
        assertThat(validator.looksIncomplete("customers and suppliers", "db.customers.find({})", "MongoDB")).isFalse();
    }

    @Test
    @DisplayName("an unknown database type is still left alone")
    void otherTypesUnchecked() {
        assertThat(validator.namesUnknownIdentifiers("anything", "Oracle")).isEmpty();
        assertThat(validator.looksIncomplete("orders with customers", "anything", "Oracle")).isFalse();
    }
}
```

`MongoGenerationValidationTest.java`:

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.QueryResponse;
import com.querysense.backend.model.QueryHistory;
import com.querysense.backend.querybuilder.GeneratedQueryValidator;
import com.querysense.backend.querybuilder.RuleBasedQueryBuilder;
import com.querysense.backend.querybuilder.RuleBuildResult;
import com.querysense.backend.repository.QueryHistoryRepository;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.service.LlmFallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoGenerationValidationTest {

    private List<QueryHistory> stored;
    private ActiveSchemaProvider activeSchema;
    private LlmFallbackService llm;
    private QueryGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        stored = new ArrayList<>();
        activeSchema = new ActiveSchemaProvider();

        QueryHistoryRepository history = mock(QueryHistoryRepository.class);
        when(history.save(any())).thenAnswer(call -> {
            stored.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(history.findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCaseAndSchemaLabel(
                anyString(), anyString(), anyString()))
                .thenAnswer(call -> stored.stream()
                        .filter(h -> h.getUserInput().equalsIgnoreCase(call.getArgument(0)))
                        .filter(h -> h.getDatabaseType().equalsIgnoreCase(call.getArgument(1)))
                        .filter(h -> call.getArgument(2).equals(h.getSchemaLabel()))
                        .findFirst());

        RuleBasedQueryBuilder rules = mock(RuleBasedQueryBuilder.class);
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.declined("too complex for the rule engine", null));
        llm = mock(LlmFallbackService.class);

        service = new QueryGenerationServiceImpl(rules, history, llm, new GeneratedQueryValidator(activeSchema), activeSchema);
    }

    @Test
    @DisplayName("an off-schema MongoDB answer is 0.2, carries the warning, and is never saved")
    void offSchemaMongoAnswer() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.transactions.find({\"buyer\": 1})");

        QueryResponse response = service.generateQuery("top buyers", "MongoDB");

        assertThat(response.getConfidence()).isEqualTo(0.2);
        assertThat(response.getSchemaWarning()).contains("'transactions'");
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("a grounded MongoDB answer is 0.65 and saved; one missing the join is 0.4")
    void groundedAndIncomplete() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.orders.find({\"status\": \"PAID\"})");

        assertThat(service.generateQuery("paid orders", "MongoDB").getConfidence()).isEqualTo(0.65);
        assertThat(service.generateQuery("orders with their customers", "MongoDB").getConfidence()).isEqualTo(0.4);
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("a cached MongoDB answer written for an earlier schema is not served after the schema changes")
    void staleMongoCacheIsNotServed() {
        when(llm.generateQuery(anyString(), anyString())).thenReturn("db.orders.find({\"status\": \"PAID\"})");
        service.generateQuery("paid orders", "MongoDB");
        assertThat(service.generateQuery("paid orders", "MongoDB").getHandledBy()).isEqualTo("CACHE");

        activeSchema.publish(new SchemaSnapshot("u", activeSchema.active(Dialect.MONGODB).label(), "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("_id", "total"))), List.of(), Map.of(),
                Instant.now(), Dialect.MONGODB, List.of()));

        QueryResponse after = service.generateQuery("paid orders", "MongoDB");

        assertThat(after.getHandledBy()).isEqualTo("OLLAMA");
        assertThat(after.getSchemaWarning()).contains("'orders'");
    }
}
```

The stale-cache test publishes a snapshot under the same label on purpose, so the cache key matches and only the validator can refuse the row.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=MongoGeneratedQueryValidatorTest,MongoGenerationValidationTest`
Expected: failures. Today the validator returns empty or false for MongoDB, so:
- `offSchemaIsNamed`, `unreadableIsFlagged`, `writeStageIsOffSchema` and `mongoIncompleteness` fail;
- `offSchemaMongoAnswer` gets 0.65;
- `groundedAndIncomplete` gets 0.65 twice;
- `staleMongoCacheIsNotServed` serves the cache.

`onSchemaPasses` and `otherTypesUnchecked` pass already.

- [ ] **Step 3: Implement**

In `GeneratedQueryValidator`:
- Add the imports `com.querysense.backend.analysis.MongoPreflightValidator`, `com.querysense.backend.analysis.MongoQueryFields`, `com.querysense.backend.mongo.MongoQuery`, `com.querysense.backend.mongo.MongoShellParseException`, `com.querysense.backend.mongo.MongoShellParser` and `org.springframework.beans.factory.annotation.Autowired`.
- Replace the field and constructor with:

```java
    private final ActiveSchemaProvider activeSchema;
    private final MongoShellParser mongoParser;
    private final MongoPreflightValidator mongoPreflight;

    @Autowired
    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema,
                                   MongoShellParser mongoParser,
                                   MongoPreflightValidator mongoPreflight) {
        this.activeSchema = activeSchema;
        this.mongoParser = mongoParser;
        this.mongoPreflight = mongoPreflight;
    }

    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema) {
        this(activeSchema, new MongoShellParser(), new MongoPreflightValidator(activeSchema));
    }
```

At the top of `looksIncomplete`, before the existing MySQL guard, add:

```java
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return userInput != null && generatedQuery != null && mongoLooksIncomplete(userInput, generatedQuery);
        }
```

At the top of `namesUnknownIdentifiers`, before the existing MySQL guard, add:

```java
        if ("MongoDB".equalsIgnoreCase(databaseType)) {
            return generatedQuery == null ? Optional.empty() : mongoIssue(generatedQuery);
        }
```

Add:

```java
    private Optional<String> mongoIssue(String generatedQuery) {
        MongoQuery query;
        try {
            query = mongoParser.parse(generatedQuery);
        } catch (MongoShellParseException e) {
            return Optional.of("This is not a MongoDB query QuerySense can read: " + e.getMessage());
        }
        return mongoPreflight.validate(query);
    }

    private boolean mongoLooksIncomplete(String userInput, String generatedQuery) {
        List<KnownSchema.Table> mentioned = activeSchema.detectTablesMentioned(Dialect.MONGODB, userInput);
        if (mentioned.size() < 2) {
            return false;
        }
        Set<String> names = new LinkedHashSet<>();
        mentioned.forEach(t -> names.add(t.name()));
        boolean related = activeSchema.foreignKeys(Dialect.MONGODB).stream()
                .anyMatch(fk -> !fk.fromTable().equals(fk.toTable())
                        && names.contains(fk.fromTable()) && names.contains(fk.toTable()));
        if (!related) {
            return false;
        }

        MongoQuery query;
        try {
            query = mongoParser.parse(generatedQuery);
        } catch (MongoShellParseException e) {
            return false;
        }
        boolean joins = query.pipeline() != null
                && query.pipeline().stream().anyMatch(stage -> stage.containsKey("$lookup"));
        if (joins) {
            return false;
        }
        List<String> arrays = activeSchema.tables(Dialect.MONGODB).stream()
                .filter(t -> t.name().equals(query.collection()))
                .findFirst()
                .map(KnownSchema.Table::embeddedArrays)
                .orElse(List.of());
        boolean readsEmbedded = MongoQueryFields.referencedPaths(query).stream()
                .anyMatch(path -> arrays.stream().anyMatch(a -> path.equals(a) || path.startsWith(a + ".")));
        return !readsEmbedded;
    }
```

- [ ] **Step 4: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: all green. That includes:
- `GeneratedQueryValidatorTest` (MySQL, unchanged apart from the deleted MongoDB case);
- `OffSchemaCacheRoundTripTest` and `SchemaScopedCacheTest` (MySQL only);
- `BackendApplicationTests`, whose context picks the `@Autowired` constructor.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(llm): check MongoDB output against the active schema — off-schema at 0.2 and uncached, missing joins at 0.4

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Record it in `RULES.md` and `PROJECT.md`

**Files:**
- Modify: `RULES.md`, `PROJECT.md`

- [ ] **Step 1: `RULES.md` section 2**
  - **R3** "LLM incompleteness check (MySQL only)" becomes "LLM incompleteness check". Add the MongoDB rule: two mentioned collections joined by a declared reference, answered with neither a `$lookup` nor a path into an embedded array → 0.4.
  - **Off-schema check:** wherever section 2 describes it (the paragraph naming `namesUnknownIdentifiers` / confidence 0.2), add the MongoDB path:
    - unreadable output, an unknown collection or field (including `$lookup` fields and the first `$group`'s references), or a `$out`/`$merge` stage → 0.2, with the warning, not saved;
    - cached MongoDB answers are rechecked the same way before they are served.
  - **Prompt:** add one line that the MongoDB prompt describes the active Mongo schema (typed dotted paths, enums, embedded arrays, references, all collections when none is mentioned) and asks for one mongosh statement in `find`, `aggregate`, `countDocuments` or `deleteMany`.

- [ ] **Step 2: `PROJECT.md`**
  - **New session entry:** add `### Session of <execution date> — MongoDB parity, step 7 (LLM grounding and validation)` after the step 6 session. It contains:
    - the plan path;
    - a table of Tasks 1–4 with commits from `git log`;
    - the three departures above;
    - the note that nothing here called Ollama: the prompt is asserted from the request body sent to a mocked `RestTemplate`.
  - **"Next:" line:** in the steps 1–3 entry, it becomes: steps 1–7 are complete, and step 8 (the frontend's MongoDB side) is next.
  - **Live checklist:** add these steps to the new entry, run with Ollama up.
    1. Ask a MongoDB question the rule engine declines (for example `total order value per customer city`). The answer is handled by `OLLAMA`, is a single `db.…` statement, and uses `$lookup` on `customer_id`.
    2. If the model invents a field, the response carries a `schemaWarning` naming it at confidence 0.2, and asking again does not come back as `CACHE`.

- [ ] **Step 3: Commit**

```bash
git add RULES.md PROJECT.md
git commit -m "docs: record MongoDB parity step 7 — the LLM grounded in and checked against the Mongo schema

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
