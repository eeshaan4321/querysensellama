# Schema upload — design

**Date:** 2026-09-10
**Status:** approved, not yet implemented
**Spans:** `backend/` and `frontend/` in the querysensellama monorepo
**Risk register:** `PROJECT.md` §5, "Planned feature: schema upload — risks
accepted going in" (R1–R10). That table is the live tracker; this document
does not duplicate it.

---

## 1. Goal

Let a user upload their own database schema as DDL, and have QuerySense work
against it — rule-engine table detection, LLM grounding, preflight
validation, `EXPLAIN` analysis and schema advice — without breaking the
existing built-in path.

"Without breaking `EXPLAIN`" is the load-bearing constraint, and it has a
specific meaning here. `PROJECT.md` §7 already records the underlying fact:

> `EXPLAIN ... FROM invoices` (absent from the connected database) returns
> `ERROR 1146`, and on an empty table of identical shape MySQL reports
> `rows=1, filtered=100.00` where the populated table reports `rows=15,
> filtered=10.00` — same access path, useless magnitude. Relevant to any
> future "upload a schema" feature: structure alone gets EXPLAIN to run, but
> not to mean anything.

So structure is not enough. The uploaded tables must actually exist in MySQL
**and hold rows**, or the analysis modules downstream of `EXPLAIN` produce
output that looks fine and means nothing.

## 2. Decision

Uploaded tables are created in a **separate MySQL database,
`querysense_uploads`**, and seeded with generated data. The built-in
nine-table `querysense` database is never written to and remains the
default.

Rejected alternatives:

- **Create the tables inside `querysense`.** No catalog switching needed, but
  a user uploading their own `customers` collides with or drops the seeded
  demo table, `query_history` shares the namespace, and there is no way back
  to the demo schema without a reset script.
- **Prefix the tables (`up_customers`) inside `querysense`.** Isolates
  without a second database, but every generated query then needs rewriting
  between generation and `EXPLAIN`, and the LLM would have to be prompted
  with names the user never wrote.

The separate database is more code than either, and is the only option under
which "the demo path is unchanged" is literally true rather than
approximately true.

## 3. Architecture

```
POST /api/schema/upload
      │
      ├─ DdlSchemaParser        JSqlParser 5.3 → parsed structure
      ├─ SchemaUploadValidator  identifier / count / FK-resolution checks
      ├─ SchemaProvisioner      CREATE DATABASE + re-emitted CREATE TABLEs
      ├─ SchemaSeeder           topologically ordered generated rows
      ├─ ANALYZE TABLE          refresh InnoDB statistics
      └─ ActiveSchemaProvider.publish(snapshot)
               │
               ├─ RuleBasedQueryBuilder      table detection
               ├─ OllamaService              LLM schema grounding
               ├─ GeneratedQueryValidator    dropped-condition check
               ├─ SchemaMetadataService      live lookups + cache flush
               └─ ExplainExecutor            catalog for EXPLAIN
```

### 3.1 `SchemaSnapshot` and `ActiveSchemaProvider`

`KnownSchema`'s static constants are **kept exactly as they are** and become
the built-in snapshot. Nothing is deleted, and `KnownSchemaTest` is unchanged
(R7).

```java
public record SchemaSnapshot(
    String id,                              // "builtin" | generated upload id
    String label,                           // display name
    String catalog,                         // "querysense" | "querysense_uploads"
    List<KnownSchema.Table> tables,
    List<KnownSchema.ForeignKey> foreignKeys,
    Map<String, Integer> rowCounts,
    Instant loadedAt) {}
```

`ActiveSchemaProvider` is a `@Component` holding a `volatile SchemaSnapshot`,
initialised to `KnownSchema.builtIn()`.

The name/singular matching logic currently living in `KnownSchema`'s static
methods (`findTable`, `detectTablesMentioned`, `singularName`) moves verbatim
into a `SchemaLookup` helper that operates on a snapshot. `KnownSchema`'s
static methods delegate to it against the built-in snapshot, so their
behaviour is unchanged for any existing caller.

All four consumers are already Spring beans, so this is constructor
injection:

| Class | Current call | Becomes |
|---|---|---|
| `RuleBasedQueryBuilder` | `KnownSchema.TABLES`, `.tableNames()`, `.findTable()` | `activeSchema.tables()`, `.tableNames()`, `.findTable()` |
| `OllamaService` | `KnownSchema.detectTablesMentioned()`, `.FOREIGN_KEYS` | `activeSchema.detectTablesMentioned()`, `.foreignKeys()` |
| `GeneratedQueryValidator` | `KnownSchema.detectTablesMentioned()` | `activeSchema.detectTablesMentioned()` |
| `SchemaMetadataService` | `KnownSchema.findTable()` | `activeSchema.findTable()` |

**Invariant:** with no upload performed, every one of these resolves to the
built-in snapshot and returns what it returns today.

### 3.2 Parsing

`DdlSchemaParser` uses `CCJSqlParserUtil.parseStatements` (JSqlParser 5.3,
already a dependency — no new library). It keeps `CreateTable` statements and
ignores everything else, so a `mysqldump` file with `INSERT`s, `SET`
statements, `DROP TABLE IF EXISTS` and `/*!40101 ... */` directives parses
without special handling.

Extracted per table: name, ordered columns with declared type and args,
`NOT NULL`, `AUTO_INCREMENT`, `PRIMARY KEY`, `UNIQUE`, and foreign keys from
both inline column specs and table-level constraints.

Statements JSqlParser cannot parse are collected as warnings and skipped
rather than failing the whole upload — a single exotic table should not cost
the user the other thirty.

### 3.3 Validation

Rejects the upload outright when: zero tables parsed; more than
`querysense.schema.upload.max-tables` (default 40); any identifier outside
`[A-Za-z0-9_]{1,64}`; a foreign key references a table or column not present
in the upload.

### 3.4 Provisioning

1. `CREATE DATABASE IF NOT EXISTS querysense_uploads`
2. `SET FOREIGN_KEY_CHECKS = 0`, drop every existing table in that catalog,
   `SET FOREIGN_KEY_CHECKS = 1` (R3 — only ever this catalog)
3. For each parsed table, **emit a fresh `CREATE TABLE` from the parsed
   model.** The user's original text is never executed (R8).
4. Add foreign keys after all tables exist, so declaration order in the
   uploaded file does not matter.

### 3.5 Seeding

Tables are seeded in topological order over the foreign-key graph. A
self-referencing column (`employees.manager_id` → `employees.id`) is seeded
`NULL` on the first pass, then a second `UPDATE` pass points each row at an
earlier row — this is the only cycle shape that needs handling, and breaking
it this way keeps referential integrity without deferring constraints.

**Default 500 rows per table**, via `querysense.schema.upload.seed-rows`.

Why not match the demo's 5–49: `PROJECT.md` §2 records that at that scale
every domain suggestion appends *"the estimated row count is low, so the
performance impact is likely minor"*, because that branch reads
`estimatedRows`. The engine can identify a problem but never show one
costing anything. At 500+ rows an unindexed `type=ALL` has a magnitude
attached, so the uploaded path demonstrates the analysis engine better than
the built-in schema does.

Value generation is driven by declared type, with the **column name steering
the value pool** (`email`, `name`, `city`, `phone`, `status`, `price`,
`*_date`). This matters beyond cosmetics:
`SchemaMetadataService.getDistinctValues` is the rule engine's live-value
safety net for Bugs 8/10, and it needs plausible values to do its job.

Cardinality is deliberately skewed — a `status`-like column gets ~5 distinct
values, an `email`-like one gets one per row — and nullable non-FK columns
get ~10% `NULL`. Uniform data makes `filtered` uniformly `100.00` and teaches
`EXPLAIN` nothing (R6).

Inserts go through `JdbcTemplate.batchUpdate` in chunks of 200. `ANALYZE
TABLE` runs on each table afterwards; without it InnoDB's statistics are
stale and `EXPLAIN`'s `rows` estimate is noise.

### 3.6 `EXPLAIN` against the active catalog

`MySqlExplainAnalyzer` today calls
`jdbcTemplate.query("EXPLAIN FORMAT=TRADITIONAL " + query, mapper)` directly.
It has never read `KnownSchema` and still will not.

A new `ExplainExecutor` wraps every such call:

```java
jdbcTemplate.execute((ConnectionCallback<T>) conn -> {
    String original = conn.getCatalog();
    boolean switched = !target.equals(original);
    if (switched) conn.setCatalog(target);
    try {
        return work.doInConnection(conn);   // EXPLAIN, same physical connection
    } finally {
        if (switched) conn.setCatalog(original);
    }
});
```

Both statements land on one physical connection, and the catalog is restored
in a `finally` so a pooled connection is never handed on pointing at the
wrong database (R5).

**When the built-in schema is active, `target` equals the pool's own catalog,
`switched` is `false`, and the executor issues exactly the statements the
current code issues.** This is the "EXPLAIN intact" guarantee, and §6 below
asserts it rather than claiming it.

`SchemaMetadataService`'s `information_schema` queries filter on
`table_schema = DATABASE()`, which follows the same connection's catalog, so
they take the same wrapper.

### 3.7 Cache invalidation

`ActiveSchemaProvider.publish` clears `SchemaMetadataService`'s `columnCache`
and `distinctValueCache`. Both are unevicted `ConcurrentHashMap`s keyed by
bare table name, so without this the first upload continues serving the demo
schema's columns to `SqlPreflightValidator` and the demo's city values to the
rule engine — silently, and looking correct (R4).

## 4. API

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/schema/upload` | `multipart/form-data` file, or `{ "ddl": "..." }` | `SchemaUploadResponse` |
| `GET /api/schema/active` | — | `SchemaUploadResponse` for the current snapshot |
| `POST /api/schema/reset` | — | `SchemaUploadResponse` for the built-in snapshot |

```jsonc
// SchemaUploadResponse
{ "schemaId": "upload-7f3c",
  "label": "northwind.sql",
  "catalog": "querysense_uploads",
  "builtIn": false,
  "tables": [ { "name": "customers",
                "columns": ["id","name","email","city"],
                "rowCount": 500 } ],
  "foreignKeys": [ { "fromTable": "orders", "fromColumn": "customer_id",
                     "toTable": "customers", "toColumn": "id" } ],
  "warnings": ["Skipped 1 statement JSqlParser could not parse (line 812)."],
  "loadedAt": "2026-09-10T14:02:11" }
```

Errors keep the existing `GlobalExceptionHandler` shape. Two get specific,
human-readable messages rather than a generic 500: a missing
`CREATE DATABASE` grant (R1), and an attempted upload under the `h2` profile,
which is refused before any provisioning happens (R2).

## 5. Frontend

Wired per `PROJECT.md` §4's stated rule — shape and `ENDPOINT_STATUS` into
`contracts.js`, one module in `api/` branching on `USE_MOCK`, exported from
`index.js`, fixtures in `mock/fixtures.js`.

| File | Change |
|---|---|
| `src/api/contracts.js` | `SchemaSnapshot` shape, `ENDPOINT_STATUS.schemaUpload: 'REAL'` |
| `src/api/schemaUpload.js` | new — `uploadSchema`, `getActiveSchema`, `resetSchema` |
| `src/api/index.js` | re-export the three |
| `src/mock/fixtures.js` | `mockSchemaUpload`, `mockActiveSchema` |
| `src/context/ActiveSchemaContext.jsx` | new — fetched at app start, exposes `{ active, isBuiltIn, refresh }` |
| `src/pages/SchemaSourcePage.jsx` | new — route `/schema-source` |
| `src/components/schemaSource/` | drop zone, paste textarea, parsed-table preview, reset control |
| `src/components/shell/NavRail.jsx` | nav entry |
| `src/components/shell/EngineStatusBar.jsx` | persistent active-schema indicator |
| `src/App.jsx` | route + `ActiveSchemaProvider` |
| `src/components/workbench/QueryForm.jsx:27` | `PLACEHOLDER` derived from an active table name instead of the hardcoded `'show customers from Mumbai'` |

Note: `PROJECT.md` §2 refers to "the five sample prompts in the app". Those
belonged to the **previous** React 18 frontend, now at
`frontend.local-backup/`. The current frontend has no sample-prompt
chips — only the single `PLACEHOLDER` string above.

Two UI rules carried from the risk register:

- The active schema is **always visible** in `EngineStatusBar`. The app
  already surfaces which engine answered; which database a plan describes is
  the same class of provenance fact (R10).
- When an uploaded schema is active, the analysis screens label the plan as
  describing **generated** data. §4 of `PROJECT.md` already names the
  comparison screen as the one place the UI shows invented numbers; this must
  not quietly become the second (R6).

The upload screen states that uploading replaces the previous upload, before
the upload is committed (R3).

## 6. Testing

| Test | Covers |
|---|---|
| `DdlSchemaParserTest` | mysqldump output, inline + table-level FKs, backticked identifiers, `ENUM`, unparseable statements collected as warnings |
| `SchemaUploadValidatorTest` | identifier charset, table cap, unresolvable FK |
| `SchemaSeederTest` | topological order, self-FK cycle, `UNIQUE` columns, type coverage, skewed cardinality |
| `ActiveSchemaProviderTest` | defaults to built-in; publish swaps; reset restores; publish flushes metadata caches |
| `ExplainExecutorTest` | **asserts `setCatalog` is never called while the built-in schema is active**, and that the original catalog is restored after a switch, including on exception |
| `KnownSchemaTest` | unchanged — still pins the built-in schema to the live database |

Every test above is a **plain JUnit + Mockito + AssertJ unit test with a
mocked `JdbcTemplate`** — matching `MySqlExplainAnalyzerTest`, which is how
this codebase already tests database-touching classes. None of them needs a
live MySQL. `KnownSchemaTest` stays what it is: static assertions transcribed
from the live database, not a query against it.

The existing 44 backend tests must stay green. `npm run build` clean and
`npm run lint` at no more than its 3 pre-existing warnings.

## 7. Out of scope

- **MongoDB.** `PROJECT.md` §5.5 records that Mongo has no connection, no
  live analyzer and no rule-engine support. Uploading a Mongo schema is a
  separate problem and is not addressed here.
- **The two open correctness defects.** `order_items` mis-resolution and the
  LLM inventing tables both run through the same matcher a dynamic schema
  feeds, so they follow the uploaded schema rather than being cured by it.
  Fixing them is separate work.
- **Multiple retained uploads.** One upload at a time (R3).
- **Rewriting queries, and the comparison endpoint.** Untouched.
