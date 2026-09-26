# MongoDB parity — design

**Date:** 2026-09-26
**Status:** approved in conversation section by section; awaiting review of
this written spec. Nothing below is built.
**Spans:** `backend/` and `frontend/`
**Supersedes:** `PROJECT.md` §5, "Not a gap: MongoDB is analysed, never
connected" (decided 2026-09-21, reopened 2026-09-25) and "What MongoDB parity
would actually take". Those sections are deleted in step 9 and replaced by a
record of this decision.

---

## 1. Goal

MongoDB works the same as MySQL across the whole pipeline: schema upload,
rule engine, cache, LLM fallback with grounding and validation, real plan
analysis, verdict, domain/resource/advisor rules, rewrite and compare, and
history. "The same" is enforced structurally (§3) and by a parity test suite
(§10), not by convention.

The one principle carried over unchanged from the SQL side: **QuerySense
recommends, it never executes.** `explain()` plans a query and never runs a
write; advisor output (`createIndex`) is shown, never applied.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| Scope | Full parity, including upload. Rule engine is required, not optional. |
| Mongo upload input | A JSON file of collections, each with a `$jsonSchema` validator, index specs and an optional `references` block. Raw `db.getCollectionInfos()` output is also accepted. Sample-document upload is **not** built. |
| Where MongoDB runs | Local MongoDB Community Server on `localhost:27017`, installed by the user before step 4. Unit tests use fixtures captured from that server. |
| Active schema | One slot per database. MySQL and MongoDB each have their own active schema (built-in demo or latest upload); the Workbench toggle picks which answers. |
| Architecture | One pipeline with per-dialect strategies (approach 1). A parallel Mongo pipeline and a Mongo→SQL translation were both rejected. |
| Schema sampling (stage 0 of the 2026-09-25 assessment) | Out of scope. Declared schemas cover every path QuerySense owns. |

## 3. Dialect and schema model

### 3.1 `Dialect`

`enum Dialect { MYSQL, MONGODB }`, parsed once from `databaseType` at the
controller edge. Unknown values are a 400. It replaces every
`"MongoDB".equalsIgnoreCase(...)` check (six today: `RuleBasedQueryBuilder`,
`QueryAnalysisServiceImpl`, `SchemaAdvisorServiceImpl`,
`ResourceConsciousService`, `TransactionalOptimizationRules`,
`OllamaService`).

Each pipeline stage is an interface with `Dialect dialect()`:

| Interface | MYSQL implementation | MONGODB implementation |
|---|---|---|
| `SchemaUploadParser` | `DdlSchemaParser` | `JsonSchemaUploadParser` |
| `SchemaProvisioning` | `SchemaProvisioner` | `MongoProvisioner` |
| `SchemaSeeding` | `SchemaSeeder` | `MongoSeeder` |
| `QueryEmitter` | `SqlEmitter` | `MongoEmitter` |
| `PlanAnalyzer` | `MySqlExplainAnalyzer` | `MongoExplainAnalyzer` |
| `IndexAdvisor` | SQL half of `SchemaAdvisorServiceImpl` | Mongo half |
| `Rewriter` | `QueryRewriter` | `MongoQueryRewriter` |
| `GeneratedQueryCheck` | SQL path of `GeneratedQueryValidator` | Mongo path |

`DialectRegistry<T>` collects the beans of each interface and fails at startup
if a dialect has no implementation.

### 3.2 Schema model

- `SchemaSnapshot` gains `Dialect dialect` and `List<IndexSpec> indexes`
  (`IndexSpec(table, List<KeyPart(column, direction)>, unique)`). MySQL
  snapshots carry their indexes too.
- A Mongo collection is a `KnownSchema.Table`; its fields are `columns`.
  Embedded fields are flattened to dotted paths (`items.productId`).
  `Table` gains `List<String> embeddedArrays` (empty for SQL) and a
  `Map<String, String> fieldTypes` (column/path → SQL type or BSON type).
- References are `KnownSchema.ForeignKey`s, declared in the upload's
  `references` block (`{"customerId": "customers._id"}`), so
  `JoinPathResolver` walks one graph for both dialects.
- `ActiveSchemaProvider` holds `Map<Dialect, SchemaSnapshot>`:
  `active(Dialect)`, `publish(snapshot)` (replaces that snapshot's dialect
  slot only), `resetToBuiltIn(Dialect)`. Listeners receive the dialect that
  changed. The no-argument `active()` is removed rather than defaulted, so
  every caller states its dialect.

### 3.3 Built-in Mongo demo

`KnownMongoSchema`: the shop domain as 8 collections — `categories`,
`customers`, `departments`, `employees`, `orders` (with `items[]` embedded,
replacing `order_items`), `payments`, `products`, `suppliers` — with the same
references as the MySQL FKs. Its source of truth is
`backend/src/main/resources/mongo/demo-collections.json`, in the upload
format, provisioned into database `querysense` through the upload path on
first use. `KnownMongoSchemaMatchesSeedTest` asserts the Java declaration and
the JSON file agree.

### 3.4 Cache and history

The cache key is already `(userInput, databaseType, schemaLabel)` and
`databaseType` identifies the dialect, so no new key column is needed. Cache
hits are revalidated by the dialect's `GeneratedQueryCheck`.

## 4. Upload, provisioning and seeding

### 4.1 API

- `POST /api/schema/upload` takes an optional `dialect` (multipart param or
  JSON field), default `MYSQL`. JSON body field `ddl` is renamed in meaning
  only: for MongoDB it carries the JSON document; a `schema` alias is
  accepted. A `.json` file uploaded without `dialect=MONGODB` is rejected with
  a message saying to choose MongoDB.
- `GET /api/schema/active?dialect=` and `POST /api/schema/reset?dialect=`,
  default `MYSQL`.
- `SchemaUploadResponse` gains `dialect` and `indexes`.

### 4.2 Upload format

```json
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
```

A top-level array in `db.getCollectionInfos()` shape (`{name, options:
{validator}}`) is also accepted; indexes may then be supplied as
`"indexes"` on each entry.

### 4.3 Parse — `JsonSchemaUploadParser`

Produces the same `ParsedSchema` as `DdlSchemaParser`. Recurses through
`object.properties` and `array.items` to build dotted paths; records
`bsonType`, `required`, `enum`, indexes and references. Unsupported keywords
(`oneOf`, `anyOf`, `patternProperties`, `$ref`) become warnings, not
failures. Malformed JSON is a `SchemaUploadException` naming the line and
column from the JSON reader.

### 4.4 Validate — `SchemaUploadValidator`

Existing rules (40-collection cap, name rules) plus: every reference target
is a declared collection and field; every index key is a declared path;
`_id` is implicit and always present.

### 4.5 Provision — `MongoProvisioner`

Target database `querysense_uploads` (property
`querysense.schema.upload.mongo-database`, default equal to the MySQL catalog
name). Per collection: drop, `createCollection` with the uploaded
`$jsonSchema` as validator (`validationLevel: strict`,
`validationAction: error`), `createIndex` per index. An authorization error
is rethrown naming the role needed (`readWrite` + `dbAdmin` on the
database).

### 4.6 Seed — `MongoSeeder`

Reuses `ValueGenerator` through a BSON→generator mapping (`string`, `int`,
`long`, `double`, `decimal`, `bool`, `date`, `objectId`, `enum`). Embedded
arrays get 1–4 elements. References draw from the parent collection's
seeded `_id`s; collections are seeded in reference-dependency order, and a
reference cycle is broken the way the SQL seeder breaks FK cycles (nullable
side first, else a warning). Same `seed-rows` target and fixed seed as SQL.
Documents are inserted in batches of 1,000; a validation failure fails the
upload naming the collection and the offending field.

### 4.7 Profile guard

Mongo upload requires `spring.data.mongodb.uri`; without it the upload fails
with a message saying so. `application.properties` gets
`spring.data.mongodb.uri=mongodb://localhost:27017/querysense` uncommented.
The H2 profile leaves it unset.

## 5. Rule engine

### 5.1 Refactor (ships first, no behaviour change)

`QueryIntent` stops carrying SQL text:

- `ColumnRef(String table, String column)` — `table` null when unqualified.
- `Operand` = `ColumnRef` | `Aggregate(Fn fn, ColumnRef column)`;
  `Fn { COUNT, SUM, AVG, MIN, MAX }`; `COUNT(*)` is `Aggregate(COUNT, null)`.
- `Predicate(Operand lhs, Op op, List<Literal> values)`;
  `Op { EQ, GT, GTE, LT, LTE, IN }`; `Literal(String text, boolean numeric)`.
- `OrderItem(ColumnRef column, Direction direction)`.
- `SelectItem(Operand)`; `JoinSpec(String table, ColumnRef left, ColumnRef right)`.

`QueryIntentParser` builds these; `SqlEmitter` renders them. Acceptance: every
existing rule-engine test produces byte-identical SQL.

### 5.2 `MongoEmitter`

Writes mongosh syntax.

| Intent | Output |
|---|---|
| select, filters, sort, limit, no aggregate, no join | `db.c.find(filter, projection).sort(s).limit(n)`; `SELECT *` → no projection |
| `COUNT(*)` only, no grouping | `db.c.countDocuments(filter)` |
| aggregate, group, having | `db.c.aggregate([$match, $group, $match, $sort, $limit])` |
| join through a reference | `$lookup` + `$unwind` in the pipeline |
| join to an embedded array | `$unwind: "$items"` |
| DELETE | `db.c.deleteMany(filter)`; empty filter declines, as rule 7 does for SQL |

Literals: numeric unquoted, strings JSON-quoted; `IN` → `$in`; comparisons →
`$gt/$gte/$lt/$lte`. An intent the emitter cannot express faithfully
declines with a reason naming what could not be expressed.

### 5.3 `RuleBasedQueryBuilder`

E2 (the Mongo gate) is removed. The builder binds against
`active(dialect)` and emits with the dialect's `QueryEmitter`. Decline rules
are unchanged and dialect-neutral.

### 5.4 `MongoShellParser`

mongosh text → `MongoQuery(collection, operation, filter, projection, sort,
limit, pipeline)`, `operation ∈ {FIND, AGGREGATE, COUNT, DELETE}`. Arguments
are read with `org.bson.json.JsonReader` (relaxed: unquoted keys,
`ObjectId()`, `ISODate()`, `NumberLong()`). Anything else — other methods,
chained calls it does not know, trailing code — is a parse failure with a
position. Analysis, rewrite and validation all consume `MongoQuery`.
`MongoEmitter` output must round-trip through it.

## 6. Analysis, verdict and rules

### 6.1 `MongoExplainAnalyzer`

Runs `explain` with verbosity **`executionStats`**, pinned, for `find`,
`aggregate`, `count` and `delete` against the dialect's active database.
`explain` on `delete` does not delete. Mapping onto `QueryAnalysisResult`:

| Mongo | Field |
|---|---|
| `COLLSCAN` stage | `hasFullTableScan`, `accessType = "COLLSCAN"` |
| `IXSCAN` stage | `usesIndex`, `keyUsed = indexName` |
| `totalDocsExamined` | `estimatedRows` |
| `nReturned` | `estimatedRowsReturned` |
| blocking `SORT` stage | `usesFilesort` |
| `$group` with `usedDisk` | `usesTemporary` |
| `executionTimeMillis` | new optional `executionMillis` |

`queryCost` stays null for Mongo (MongoDB reports no cost figure), and the
Analysis screen says so. The winning plan's stage tree becomes `planTree`.

`ShapeGuard` holds the set of stage names and required keys this parser
understands; an unknown shape adds a warning and leaves the affected fields
unset rather than guessing.

### 6.2 Preflight

The collection and every filter, projection, sort and pipeline field path
must exist in the active Mongo schema before `explain` runs, reported with
the same wording as SQL's P1–P2.

### 6.3 Verdict

V1–V9 run unchanged on the mapped fields. Wording goes through a dialect
vocabulary: rows→documents, table→collection, filesort→in-memory sort,
full table scan→collection scan.

### 6.4 Rules

Rule IDs keep their meaning; each rule has a MySQL and a MongoDB variant.

- TX, RS and SA "full scan" rules read `COLLSCAN` for Mongo.
- SA1 emits `db.c.createIndex({field: 1})`.
- SA2 emits a compound index ordered **equality → sort → range**.
- SA3 flags a `$group` not preceded by an index-usable `$match`.
- The scan + sort reconciliation collapses into one compound index, as SQL
  collapses into one composite.
- M1 and M2 become Mongo variants reading `MongoQuery` and real
  `listIndexes()`. The hardcoded `_id, email, userId` list is deleted.
  SA4/SA5 fold into SA1/SA2; `RULES.md` is renumbered and its totals updated.

### 6.5 Sub-project C

`OptimizationSuggestion` gains optional `suggestedChange` (DDL string for the
dialect, or `"rewrite"` pointing at compare). Domain and resource rules fill
it where an index is the fix.

## 7. Rewrite and compare

`MongoQueryRewriter`, same constraint as SQL: identical documents or decline.

| ID | Mongo rule |
|---|---|
| RW1 | Add a projection naming the schema's fields. Declines if the query or a later stage references a field outside the schema. |
| RW2 | `$or` of equalities on one field → `$in`. |
| RW3 | Drop `.sort()` when an equality on a unique index limits the result to one document. |
| RW4 | Move a `$match` ahead of `$lookup`/`$unwind` when it references only base-collection fields. Mongo only. |

`POST /api/query/compare` runs `explain` on both versions for Mongo and
reports `totalDocsExamined` and plan deltas with per-rule reasons; response
shape unchanged. For both dialects, compare writes the rewritten query into
`QueryHistory.optimizedQuery` on the most recent history row for the same
input, database type and schema label.

## 8. LLM grounding and validation

- `OllamaService.buildSchemaContext` sends the active Mongo schema for
  MongoDB: collections, dotted paths with BSON types, enums, references,
  embedded arrays; all collections when none are mentioned. The Mongo prompt
  requires mongosh syntax in one of the four supported operations.
- `GeneratedQueryValidator` Mongo path: unparseable output, an unknown
  collection, or an unknown field path (including `$lookup`
  `localField`/`foreignField` and `$group` expressions) → confidence 0.2,
  `schemaWarning`, not cached. `looksIncomplete` analogue: two related
  collections mentioned, no `$lookup` and no embedded path used.

## 9. Frontend

- **Schema source page:** MySQL | MongoDB switch; each side has its own
  active-schema card, upload (`.sql` / `.json`), paste box and reset. Mongo
  card shows collections, dotted paths with BSON types, embedded arrays,
  references, indexes and document counts. `api/schema.js` functions take
  `dialect`.
- **Shell schema indicator** follows the Workbench toggle.
- **Workbench:** unchanged structurally; Mongo shows `RULE_ENGINE` and rule
  traces.
- **Analysis:** `MongoFindingsList` and the Mongo scope note are deleted;
  Mongo uses `ExplainPlanView`, plan tree, tiles and verdict with backend
  vocabulary.
- **Optimization:** the "SQL only, for now" empty state is deleted; compare
  and advisor show Mongo output.
- **History:** new Database column; `resourceLevel` column (from B).
- **Mock fixtures** gain Mongo equivalents for every call.

## 10. Testing

- Refactor: byte-identical SQL from every existing rule-engine test.
- Round trip: every `MongoEmitter` output parses back through
  `MongoShellParser` to the same structure.
- Parsers: `JsonSchemaUploadParser` on the documented format, the
  `getCollectionInfos()` shape, nested arrays, warnings, malformed JSON.
- Fixtures: `explain` and `listIndexes` output **captured from the local
  mongod** by a script checked in under `backend/src/test/resources/mongo/`,
  not hand-written.
- `KnownMongoSchemaMatchesSeedTest`.
- **Parity suite:** one parameterised set of rule-engine questions run
  against both built-in schemas; each must build in both or decline in both,
  except a listed set of documented differences.
- Integration (`@Tag("mongo")`): provision → seed → explain against
  `localhost:27017`; skipped, not failed, when the port is closed.
- Frontend: Vitest for the schema-page switch, removal of both Mongo
  placeholders, History columns. `npm run lint` clean.
- The app is not started by Claude. Live-only checks are written as a
  checklist in `PROJECT.md` §7 for the user to run.

## 11. Errors

- Mongo unreachable: `GET /api/health` gains `mongo: UP|DOWN|NOT_CONFIGURED`;
  the shell banner names it; MySQL screens keep working; Mongo requests
  return 503 with the reason.
- Missing privilege, unknown explain shape, seed validation failure: named
  errors through the existing error envelope.

## 12. Build order

| # | Sub-project | Needs mongod |
|---|---|---|
| 1 | Intent refactor; `Dialect`, registry, per-dialect `ActiveSchemaProvider` | no |
| 2 | B: silent-drop test (fix if it reproduces); `resourceLevel` stored in history via optional field on the generate request | no |
| 3 | `MongoShellParser`, `MongoEmitter`, rule engine on Mongo, `KnownMongoSchema` | no |
| 4 | Mongo upload: parser, validator, provisioner, seeder, API | yes |
| 5 | `MongoExplainAnalyzer`, preflight, verdict vocabulary, dialect-aware rules and advisor, C's `suggestedChange` | yes (fixtures) |
| 6 | `MongoQueryRewriter`, compare, `optimizedQuery` in history | yes (fixtures) |
| 7 | LLM grounding and Mongo validator | no |
| 8 | Frontend | no |
| 9 | Docs: `PROJECT.md` §5 records this decision and removes the two Mongo non-goal sections; `RULES.md` renumbered | — |

Each step gets its own implementation plan and commits. Before step 4 the
user installs MongoDB Community Server and confirms `27017` is listening.

## 13. Out of scope

- Inferring a schema by sampling documents.
- Uploading sample documents or data dumps.
- Executing any generated query, index or rewrite (unchanged non-goal).
- Mongo operations beyond `find`, `aggregate`, `countDocuments`,
  `deleteMany`.
- A QuerySense-computed cost model for either dialect.
