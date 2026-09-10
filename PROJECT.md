# QuerySense

Natural-language questions in, MySQL or MongoDB queries out — with the routing
decision, the execution plan, and the optimisation advice all shown rather than
hidden.

The project is two codebases that now talk to each other:

```
FULL CAPSTONE/
├── CAPSTONE CODE/       Spring Boot 3 backend (Java, Maven)   → :8080
├── CAPSTONE FRONTEND/   React 19 + Vite SPA                   → :5173
└── PROJECT.md           this file
```

Each half is its own git repository (`querysense-backend`, `querysense-frontend`);
this file spans both and lives in neither.

**A combined snapshot is also published** at
`github.com/eeshaan4321/querysensellama` (private) — a monorepo holding
`backend/`, `frontend/` and this file, pushed as a single commit. It is a
snapshot, not a working remote: the two repositories above remain the places
work happens, and the monorepo does not track them. Note the directory names
differ there (`backend/`, `frontend/` rather than `CAPSTONE CODE/`,
`CAPSTONE FRONTEND/`), so paths quoted in this file need translating when read
from that repo.

---

## 1. Running it

You need three things up, in this order. Only the first is mandatory.

### Backend — required

```bash
cd "CAPSTONE CODE"

# Default profile: expects MySQL on ${DB_PORT:-3306}, database `querysense`
./mvnw spring-boot:run

# H2 profile: no MySQL needed, in-memory database, seeded automatically
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Confirm it is up: `curl http://localhost:8080/test` → `QuerySense Running`

**Which profile to use.** The H2 profile lets the whole app start and generate
queries on a machine with no MySQL. It is not equivalent: H2 cannot produce
MySQL's `EXPLAIN` output, so `/api/query/analyze` and `/api/query/schema-advisor`
return `explainFailed: true` and empty results under it. Query generation, the
cache, history and the LLM fallback all work normally. Use MySQL when you need
the analysis modules to actually say something.

### Frontend — required

```bash
cd "CAPSTONE FRONTEND"
npm install
npm run dev          # → http://localhost:5173
```

There is **no Vite proxy**. The frontend calls `VITE_API_BASE_URL`
(`http://localhost:8080`) cross-origin and directly; the backend allows all
origins itself (`AppConfig.corsConfigurer`), so this works in development and
against a remote backend alike.

The frontend has **no backend-reachability indicator** — it never calls `/test`.
If the backend is down, the failure surfaces per-request as an error on the
screen that made the call, not as a global banner. Check the backend yourself
with `curl http://localhost:8080/test`.

### Ollama — optional, only for the LLM fallback

```bash
ollama pull qwen2.5-coder:7b     # ~4.7 GB
```

Without it the app runs fine; only questions the rule engine declines will fail,
with a 503 that names the fix. The model is local — nothing is sent to a hosted
API and no key exists.

### Configuration

| File | Setting | Meaning |
|---|---|---|
| `CAPSTONE CODE/src/main/resources/application.properties` | `spring.datasource.url` | `${DB_HOST:localhost}:${DB_PORT:3306}` — **defaults to 3306**; set `DB_PORT` if your instance differs |
| | `ollama.model` | `qwen2.5-coder:7b` |
| | `http.client.read-timeout-ms` | 60 s — how long the backend waits for the model |
| `CAPSTONE FRONTEND/.env` | `VITE_API_BASE_URL` | `http://localhost:8080` — called directly, no proxy |
| | `VITE_API_TIMEOUT` | 65000 ms. Must stay above the backend's 60 s read timeout above |
| | `VITE_USE_MOCK` | `false`. **Any other value, including unset, means mock mode** — the check is `!== 'false'` |

---

## 2. How a question becomes a query

Generation is three layers, tried in order. Which one answered is returned as
`handledBy` and stored in every history row — this is the project's central
claim and it is auditable rather than asserted.

| Layer | Source label | Confidence | When it runs |
|---|---|---|---|
| 1. Rule engine | `RULE_ENGINE` | 0.95 | A template matches the phrasing |
| 2. History cache | `CACHE` | 1.00 | Layer 1 declined, but this exact input was answered before |
| 3. Local LLM | `OLLAMA` | 0.65 | Both missed |
| 3b. Local LLM, flagged | `OLLAMA` | 0.40 | As above, but the validator found the question implied more than the SQL expresses |

**Confidence is a fixed tier, not a computed score.** Routing is decided by
whether a rule template matched — the number is a label attached afterwards, one
per layer. The UI is careful about this wording because describing it as a
threshold would describe a mechanism the backend does not have.

### What the rule engine will and will not take

It knows the nine user-facing tables of the `querysense` database
(`KnownSchema`), transcribed from `information_schema`:

```
categories  (id, name)
customers   (id, name, email, city, phone)
departments (id, name)
employees   (id, name, email, department_id, manager_id, hire_date)
order_items (id, order_id, product_id, quantity, unit_price)
orders      (id, customer_id, employee_id, order_date, status)
payments    (id, amount, order_id, payment_date, method)
products    (id, name, price, category_id, supplier_id)
suppliers   (id, name, contact_email)
```

`query_history` is excluded on purpose — it backs the History module and is not
a target for NL generation.

**This list was wrong until recently, and the symptom was EXPLAIN.** `orders`
was documented and coded as `(order_id, customer_id, order_date, amount,
status)`; neither `order_id` nor `amount` is on that table — they belong to
`order_items` and `payments`. Any generated query naming them died at EXPLAIN
with MySQL error 1054 and surfaced in the UI as "Unavailable — EXPLAIN failed".
`KnownSchemaTest` now pins every table, column and FK to the real database, so
the next drift fails at build time instead.

**Index reality, straight from `information_schema.statistics`:** every foreign
key is indexed and *nothing else is*. There is no index on `customers.city`,
`orders.order_date`, `orders.status`, `products.price` or
`payments.payment_date` — precisely the columns a user filters on. This is why
`accessType` comes back `ALL` for almost every generated query, and it is the
raw material the schema advisor exists to act on.

**Row counts are small** — 5 to 49 per table (`order_items` 49, `orders` 25,
`payments` 21, `products` 20, `customers` 15, the rest 5-10). Consequence worth
knowing before demoing: every domain suggestion appends "the estimated row count
is low, so the performance impact is likely minor," because that branch reads
`estimatedRows`. At this scale the optimisation modules can identify a problem
but never show one costing anything.

It declines — and hands to the LLM — anything containing a join, an aggregate
(`average`, `sum`, `maximum`, `group by`), a date range (`last month`, `today`,
`recent`), a comparison (`more than`, `above`, `between`), a question word
(`who`, `which`), or a mention of two tables at once. **It also declines every
MongoDB request outright**, so with the target set to MongoDB every question
goes to the LLM.

It has one live-data safety net: if the input names a city that exists in
`customers.city` but the `WHERE` clause it was about to build does not contain
that city, it bails to the LLM rather than emit a query it half-understood.

### Verified behaviour

These are the five sample prompts in the app, run against the live backend:

| Question | Layer | Result |
|---|---|---|
| Show all customers | rule | `SELECT * FROM customers;` |
| Show only the name and email of customers | rule | `SELECT name, email FROM customers;` |
| Show customers in Mumbai | rule | `SELECT * FROM customers WHERE city = 'mumbai';` |
| How many orders are there | rule | `SELECT COUNT(*) FROM orders;` |
| Which customers placed more than three orders | LLM → cache on a repeat | a `LEFT JOIN` with `GROUP BY` |

Re-verified against the live backend after the schema fix below, on tables and
columns the engine could not previously name at all:

| Question | Layer | Result | `explainFailed` |
|---|---|---|---|
| Show all payments | rule | `SELECT * FROM payments;` | false |
| Show all products | rule | `SELECT * FROM products;` | false |
| Show only the name and price of products | rule | `SELECT name, price FROM products;` | false |
| Show only the amount and method of payments | rule | `SELECT amount, method FROM payments;` | false |
| Show only the name and phone of customers | rule | `SELECT name, phone FROM customers;` | false |

`amount`, `method`, `price` and `phone` were all unknown to the engine before —
`phone` was missing from the `customers` list and the rest belong to tables it
had never heard of.

---

## 3. API reference

Every path below was called against a running backend and the shapes are as
recorded, not as documented elsewhere.

### `POST /api/query/generate` — the primary endpoint

```jsonc
// request                          // response
{                                   {
  "query": "Show all customers",      "generatedQuery": "SELECT * FROM customers;",
  "databaseType": "MySQL"             "targetDb": "MySQL",
}                                     "handledBy": "RULE_ENGINE",
                                      "confidence": 0.95
                                    }
```

- `query` is validated `@NotBlank @Size(min = 3, max = 500)`.
- `databaseType` is the literal string `"MySQL"` or `"MongoDB"` — it is matched
  case-insensitively against `"MongoDB"` and **echoed back verbatim** as
  `targetDb`.
- A history row is written **before** this returns, so history reflects the run
  immediately.

### `POST /api/query/analyze`

```jsonc
// request — all four fields required
{ "query": "SELECT * FROM customers WHERE city = 'mumbai';",
  "databaseType": "MySQL", "domainProfile": "ECOMMERCE", "resourceLevel": "LOW" }

// response
{ "analysis": { /* flat QueryAnalysisResult, see below */ },
  "suggestions": [ { "message": "...", "severity": "CRITICAL", "source": "DOMAIN" } ] }
```

`domainProfile` ∈ `ECOMMERCE | ANALYTICAL | TRANSACTIONAL`,
`resourceLevel` ∈ `LOW | MEDIUM | HIGH`, `severity` ∈ `INFO | WARNING | CRITICAL`,
`source` ∈ `DOMAIN | RESOURCE`.

`analysis` is **flat**, not a plan tree:

```
tableName, queryType, databaseType, estimatedRows, accessType, usesIndex,
hasFullTableScan, usesFilesort, usesTemporaryTable, keyUsed,
hasUnindexedFilter, hasUnanchoredRegex, filteredColumn, orderByColumn,
warnings[], explainFailed
```

`MySqlExplainAnalyzer` collapses every `EXPLAIN` row into this one object,
keeping the largest row estimate. The un-collapsed rows are also returned, one
per table, in `analysis.tables[]` (plus `fullScanTables[]`) — the collapsed
fields are a whole-query summary and merge rows describing *different* tables,
so a join must be read from `tables[]`, not from them. `ExplainPlanView`
renders `tables[]` separately whenever there is more than one.

This one endpoint carries three modules: the analysis itself, the domain rules,
and the resource policy. The last two appear only as tagged entries in
`suggestions`.

**Resource level genuinely changes the output.** `LOW` shifts every domain
suggestion up one severity step, `HIGH` shifts it down, `MEDIUM` leaves it
alone — and the resource module writes different text per level for the same
finding.

### `POST /api/query/schema-advisor`

Same request shape as `/analyze`. Returns a bare array:

```jsonc
[ { "type": "INDEX",           // INDEX | NORMALIZATION | STRUCTURAL
    "targetTable": "customers",
    "targetColumn": "city",    // nullable
    "reason": "Full table scan detected (accessType=ALL) with no index in use…",
    "suggestedAction": "CREATE INDEX …" } ]
```

It re-runs `QueryAnalysisService` internally, so **`EXPLAIN` executes twice per
pipeline run**. That is the backend's documented, deliberate trade-off: it keeps
the request shape identical to `/analyze` instead of making callers pass an
analysis object back in.

### `GET /api/query/history`

Returns the JPA entity unwrapped:

```jsonc
[ { "id": 3,
    "userInput": "show all customers and give a count of their orders",
    "generatedQuery": "SELECT c.id, …",
    "optimizedQuery": null,          // column exists, nothing writes to it
    "databaseType": "MySQL",
    "source": "OLLAMA",              // RULE_ENGINE | CACHE | OLLAMA
    "createdAt": "2026-09-08T19:23:23.644141" } ]
```

`createdAt` is a `LocalDateTime` — serialised **without a timezone**, so it is
local server time. Rows come back in insertion order; the frontend sorts newest
first by `id`.

### `GET /test`

Returns the plain string `QuerySense Running`. Used as the reachability probe.

### Errors

Every error comes through `GlobalExceptionHandler` in one shape:

```jsonc
{ "timestamp": "2026-09-08T19:57:24.670998600",
  "status": 400, "error": "Bad Request",
  "message": "query: Query must be between 3 and 500 characters" }
```

`message` is the sentence written for a human; the frontend shows it directly.
A **503** specifically means the local model is unreachable and its message
names the fix.

---

## 4. How the frontend connects

The two halves were built in parallel and did not agree on names. Rather than
rename fields across twenty React files or across the Java DTOs, all translation
is centralised in **`CAPSTONE FRONTEND/src/api/contracts.js`** — the only file
in the frontend that knows a backend field name.

| Frontend | Backend |
|---|---|
| `naturalLanguageQuery` | `query` |
| `targetDb: 'SQL'` | `databaseType: "MySQL"` |
| `targetDb: 'MONGODB'` | `databaseType: "MongoDB"` |
| `domain` | `domainProfile` |
| `domain: 'OTHER'` | `domainProfile: "TRANSACTIONAL"` (no `OTHER` in the enum) |

The one value the frontend does **not** rename is `handledBy` — it renders the
backend's `RULE_ENGINE` / `CACHE` / `OLLAMA` as-is, because history rows are an
audit trail and the screen's job is to show what actually ran.

`"GEMINI"` is still accepted on read: the backend renamed its LLM tier when the
fallback moved to local Ollama and deliberately left existing rows alone, so
`/history` serves both values. `isLlmSource()` in `contracts.js` is the single
place that knows this — components call it instead of comparing against a
literal, so the amber LLM treatment applies to old and new rows alike and a
future provider change is one edit.

### The pipeline is three requests, not six

The UI shows six modules. It makes three HTTP calls:

```
1. POST /api/query/generate          → Build query
2. POST /api/query/analyze           → Analyse
                                       ├── Domain rules      (no request)
                                       └── Resource policy   (no request)
3. POST /api/query/schema-advisor    → Schema advice
   (no request)                      → Compare: simulated in the browser
```

Stages that make no request are derived from a response already in hand.
History needs no call at all — the backend writes the row during step 1.

**Compare is the exception, and it is the one place the UI shows invented
numbers.** `src/api/comparison.js` has no endpoint to call, so it always
returns generated data regardless of `VITE_USE_MOCK`. Treat anything on the
comparison screen as a preview of a feature, not a measurement.

### Layout

```
CAPSTONE FRONTEND/src/
├── api/
│   ├── contracts.js     ← all field translation, enums, isLlmSource, ENDPOINT_STATUS
│   ├── client.js        ← axios instance, USE_MOCK flag
│   ├── index.js         ← re-exports; one module per endpoint alongside it
│   └── queryBuilder.js · analysis.js · schemaAdvisor.js · history.js · comparison.js
├── context/             ← EngineStatusContext (last engine used), WorkbenchContext
├── pages/               ← Workbench, Analysis, Optimization, SchemaAdvisor, History, About
├── components/          ← shell/, common/, then one folder per feature area
├── mock/fixtures.js     ← every generator behind VITE_USE_MOCK
├── lib/                 ← query highlighter, line diff, resource levels
└── styles/              ← tokens.css (design tokens), base.css (reset)
```

**To wire a new backend endpoint:** add its shape and `ENDPOINT_STATUS` entry to
`contracts.js`, add a module next to the others in `api/` that branches on
`USE_MOCK`, and export it from `index.js`. Nothing in `components/` or `pages/`
should need to move — they only read shapes defined in `contracts.js`.

---

## 5. What is left

Everything below is genuinely absent, not merely unwired. The UI states each one
on the relevant screen rather than filling the gap with plausible-looking
numbers.

### Missing endpoints

| Endpoint | Status | What is needed |
|---|---|---|
| `POST /api/query/compare` | **Nothing exists.** No controller, no service, no DTO. The frontend simulates it in the browser instead of hiding the screen. | A comparison engine returning a cost/row delta and a per-rule trace. It also needs the item below to exist first — there is no "after" query to compare a "before" against. |
| `POST /api/history` | **No write endpoint.** | Only needed if the frontend should store per-run metadata the backend does not currently see (domain, resource level, confidence, cost delta). Today the backend writes history itself and those fields are not persisted at all. |

### Missing capabilities behind endpoints that do exist

1. **Nothing rewrites a query.** `DomainOptimizationService` and
   `ResourceConsciousService` both return `List<OptimizationSuggestion>` —
   advice text with a severity. No code path edits SQL. Consequences:
   `QueryHistory.optimizedQuery` is always `null`, and the optimisation screen's
   `BeforeAfterDiff` has no real "after" to render — it diffs against a rewrite
   the browser simulated, not one the backend produced.

2. **No consistency checking in the schema advisor.**
   `SchemaAdvisorServiceImpl` emits each recommendation independently from a
   single `EXPLAIN` signal. Two proposals can overlap or conflict. The screen
   says so instead of showing a green "internally consistent" badge.

3. **Per-table, but still not a plan tree.** `MySqlExplainAnalyzer` keeps every
   `EXPLAIN` row in `tables[]` alongside the collapsed summary, and
   `ExplainPlanView` renders one block per table for a join — so per-table
   access type, index and row estimate *are* shown. What is still absent is a
   plan *tree*: `EXPLAIN`'s tabular output is a flat list of table accesses in
   join order, not a node graph, so nesting and operator flow are not
   recoverable from it. That needs `EXPLAIN FORMAT=JSON`, which exists behind
   `querysense.explain.format=JSON` but is not what the analysis screen reads.

4. **No optimiser cost figure.** The backend reads `EXPLAIN`'s `rows` estimate
   and computes no cost. The UI therefore says "Estimated rows" — labelling a
   row count as a cost would invent a unit.

5. **MongoDB is barely supported.**
   - `spring.data.mongodb.uri` is commented out; there is no Mongo connection.
   - `MongoSimpleAnalyzer` is a static text check over the query string — two
     regexes, for unindexed filter fields and unanchored `$regex`. It never runs
     the query and produces no plan.
   - The rule engine refuses all MongoDB input, so **every** MongoDB question
     goes to the LLM and fails without Ollama.
   - `MongoSimpleAnalyzer` is called with `collectionName = null`, so Mongo
     schema recommendations cannot name their collection.

6. **`EXPLAIN` runs twice per pipeline run**, because the schema advisor re-runs
   the whole analysis. Accepted at this scale; worth revisiting if the analysis
   gets expensive.

7. **History stores no run context.** No domain, resource level, confidence or
   timing is persisted — only input, query, database type, source and timestamp.
   Adding columns to `QueryHistory` is the prerequisite for any analytics over
   past runs.

8. **`TestController` (`GET /test`)** is a debug endpoint that returns a bare
   string. It is used as the health probe; if that becomes load-bearing it
   deserves a real health response.

### Correctness defect: the rule engine resolves multi-word tables to the wrong table

**Status: open, reproducible, found 2026-09-10.** Introduced by the
`KnownSchema` expansion documented above — adding tables whose names contain
underscores without teaching the matcher to recognise them in prose.

```
"Show all order items"            -> SELECT * FROM orders;          RULE_ENGINE  0.95
"how many order items are there"  -> SELECT COUNT(*) FROM orders;   RULE_ENGINE  0.95
"Show all order_items"            -> SELECT * FROM order_items;     RULE_ENGINE  0.95   correct
```

Only the phrasing that spells the identifier with an underscore is right.
`order_items` has 49 rows and holds `quantity` and `unit_price`; `orders` has 25
and holds neither, so the answer is the wrong table, the wrong row count and a
column set that cannot answer the question.

#### Mechanism

`detectTable` walks `KnownSchema.TABLES` and returns the first table whose name
or singular appears in the input as a whole word. Traced on
`"show all order items"`:

```
order_items   plural=false   singular(order_item)=false     underscore != space, both miss
orders        plural=false   singular(order)=true           "order" is a standalone word here
=> returns: orders
```

`order_items` is tested **first** and still loses, so declaration order is not
the cause — the cause is that an underscored identifier can never match natural
phrasing, while `orders`' singular `order` happily matches the word "order"
sitting inside the phrase "order items".

`hasAnyComplexity` has a guard for exactly this shape: two known tables
mentioned means decline to the LLM. It reuses the same matcher, so it counts one
table here, not two. The gate meant to catch ambiguity is blind to it for the
same reason.

#### Why it matters more than its size suggests

The project's claim is that routing is auditable — the engine claims only what
it is certain of and defers the rest. A wrong table returned as `RULE_ENGINE`
at `0.95` is the one output that claim cannot survive, because nothing on screen
gives the user a reason to doubt it. A deferral to the LLM would have been fine.

The same trap waits on every other underscored name (`payment_date`,
`unit_price`, `hire_date`). `categories` is safe only because `singularName()`
was taught the `-ies` case at the same time.

#### Fix shape (not implemented)

Match on the most specific candidate rather than the first; normalise
identifiers to prose forms (`order_items` <-> "order items", "line items") on
both sides of the comparison; and when two tables tie, decline to the LLM rather
than pick. All three make the engine decline **more**, not less.

---

### Correctness defect: the LLM invents tables and columns

**Status: open, reproducible on demand, verified 2026-09-09 against the live
backend on MySQL.** This is not a missing feature — it is the existing
`/api/query/generate` path returning confidently-labelled wrong SQL.

#### What happens

Two semantically identical questions, differing only in vocabulary:

| Input | Grounded? | Generated |
|---|---|---|
| "list the top 5 **buyers** by total **spend** last month" | no | `SELECT buyer_id, SUM(amount) … FROM transactions WHERE transaction_date …` — table and two of three columns **do not exist** |
| "list the top 5 **customers** by total **order** amount last month" | yes | `FROM customers c JOIN orders o ON c.id = o.customer_id …` — correct, uses the real FK |

Both returned `handledBy: OLLAMA`, `confidence: 0.65`. The wrong one is not
labelled any differently from the right one.

#### Root cause

`OllamaService.buildSchemaContext` gates schema grounding on the user's
phrasing:

```java
List<KnownSchema.Table> tables = KnownSchema.detectTablesMentioned(userInput);
if (tables.isEmpty()) {
    return "";          // model gets NO schema at all
}
```

`KnownSchema.detectTablesMentioned` is a literal word-boundary match for
`customers`/`customer`/`orders`/`order` **in the question text**. A question
phrased in business words rather than schema words matches nothing, so the
prompt carries no tables, no columns and no foreign key — and a 7B model with no
schema invents a plausible one.

The grounding is therefore keyed on the user's vocabulary, which is backwards:
the questions most likely to hallucinate are precisely the ones that avoid
schema nouns.

#### Why neither guard catches it

1. **`GeneratedQueryValidator` does not check columns.** Despite the name,
   `looksIncomplete` only detects *dropped conditions* — it compares tables
   named in the input against tables present in the SQL, and looks for
   relational phrasing without a `JOIN`/`HAVING`/`GROUP BY`. It never validates
   a generated identifier against `KnownSchema`. It also calls the same
   `detectTablesMentioned`, so on an ungrounded input it gets an empty list and
   *both* of its signals are inert. Confidence stays at the clean-generation
   0.65.

2. **`SqlPreflightValidator` — the actual hallucinated-column check — is not in
   this path.** It is injected only into `QueryAnalysisServiceImpl`
   (`POST /api/query/analyze`). `QueryGenerationServiceImpl` does not reference
   it. `POST /api/query/generate` has no schema-conformance guard at all.

#### The cache promotes the hallucination to confidence 1.0

`saveToHistory` persists every LLM result unconditionally, and the Layer 2
lookup serves it back. Asking the same question a second time returns the same
invented `transactions` query as:

```json
{"handledBy": "CACHE", "confidence": 1.0}
```

Maximum confidence on SQL that references a table which does not exist. This is
the same failure shape the comment at `QueryGenerationServiceImpl:139-141`
describes for the old "Gemini unavailable" placeholder. That one instance was
fixed by throwing instead of returning a string; the general case — bad LLM
output being cached and later relabelled 1.0 — is still open.

#### MongoDB is affected worse

Every layer of protection is MySQL-only:

- `RuleBasedQueryBuilder` declines all MongoDB requests, so 100% of Mongo
  generation goes to the LLM.
- `buildSchemaContext` returns `""` for any non-MySQL dialect — deliberately, as
  its own comment states, because `KnownSchema` describes relational tables.
- `GeneratedQueryValidator.looksIncomplete` returns `false` for any non-MySQL
  dialect.

Mongo generation is therefore ungrounded *and* unguarded end to end. Report
Mongo accuracy as its own figure; folding it into a blended number hides this.

#### Fix direction (not yet implemented)

1. When `detectTablesMentioned` finds nothing, pass **all** of
   `KnownSchema.TABLES` rather than nothing — an over-broad prompt is strictly
   better than an empty one.
2. Add a schema-conformance check over generated identifiers before the result
   is returned *or* cached, so a query naming an unknown table/column is never
   written to history.
3. Decide explicitly what MongoDB grounding should look like, rather than
   leaving it as an inherited no-op.

### Planned feature: schema upload — risks accepted going in

**Status: design approved 2026-09-10, not yet implemented.** Recorded here
before the code exists so that each item is either closed by the
implementation or still visibly open afterwards, rather than being
rediscovered.

The feature lets a user upload their own DDL, creates those tables in a
**separate MySQL database `querysense_uploads`**, and seeds them with
generated rows so `EXPLAIN` has real statistics to plan against. The
built-in nine-table `querysense` schema stays untouched and remains the
default. Every risk below follows from one of those three choices.

| # | Risk | Why it bites | Mitigation in the design |
|---|---|---|---|
| R1 | Needs `CREATE DATABASE` privilege | The app connects as `${DB_USER:root}`, so it works here, but the grant is a real deployment constraint. Without it the whole feature 500s. | Detect the failure and return a message naming the missing grant, the way the 503 names Ollama. Never a generic 500. |
| R2 | Cannot work under the `h2` profile | H2 has no `CREATE DATABASE`/catalog semantics matching MySQL's, and §6 already records that the H2 profile is the most confusing failure mode in the project. A half-succeeding upload would be worse than the `explainFailed: true` confusion it resembles. | Upload **refuses** under `h2` with an explicit message. It does not partially provision. |
| R3 | Upload is destructive to the previous upload | `querysense_uploads` is dropped and recreated per upload. Fine for a single-user MVP, silently lossy for anyone who assumed it accumulated. | UI states it before the upload is committed. Only `querysense_uploads` is ever dropped — the demo database is never a target. |
| R4 | `SchemaMetadataService` caches never evict | `columnCache` and `distinctValueCache` are `ConcurrentHashMap`s keyed by table name with no eviction, written before this feature existed. Without a flush, the first upload keeps serving the **demo** schema's columns to `SqlPreflightValidator` and the demo's city values to the rule engine — silently, and looking correct. | Publishing a new snapshot flushes both caches. This is a defect the feature *introduces*, so it is on the critical path, not a nice-to-have. |
| R5 | Catalog switching can poison the connection pool | `EXPLAIN` runs on a pooled `JdbcTemplate`. Switching catalog without restoring it hands the next borrower a connection pointing at the wrong database — and the symptom would be a wrong-but-plausible plan, not an error. | `ExplainExecutor` sets and restores the catalog inside one `ConnectionCallback`, in a `finally`. When the built-in schema is active, `target` equals the pool's own catalog and the branch is a **no-op** — the demo path issues exactly the statements it does today. |
| R6 | Seeded statistics are generated, not real | §7 already records the underlying fact: on an empty table MySQL reports `rows=1, filtered=100.00` where the populated one reports `rows=15, filtered=10.00` — same access path, useless magnitude. Seeding fixes the magnitude but the *distribution* is invented, so selectivity findings describe generated data. | Seed with deliberately skewed cardinality (a `status`-like column ~5 distinct values, an `email`-like one ~500) and ~10% NULLs, so `filtered` varies. The UI must label an uploaded schema's plan as describing generated data — this is the one place the feature could otherwise present invented numbers as measurements, which §4 already calls out as the comparison screen's sin. |
| R7 | `KnownSchemaTest` pins the built-in schema to the live DB | That test is the guard that caught the `orders` drift documented in §2. Making the schema swappable must not weaken it. | `KnownSchema`'s constants stay exactly as they are and become the *built-in snapshot*; the test is unchanged and still pins them. Swappability is added around it, not to it. |
| R8 | Uploaded DDL is user-supplied SQL | Executing an uploaded file verbatim would run whatever it contains. | Only `CREATE TABLE` is honoured, and statements are **re-emitted from the parsed JSqlParser model rather than the user's raw text**, so no uploaded string reaches the database as written. Identifiers are constrained to `[A-Za-z0-9_]{1,64}`. |
| R9 | Hibernate `ddl-auto=update` vs. the second database | `spring.jpa.hibernate.ddl-auto=update` manages `query_history`. The uploaded catalog sits outside JPA's control, but that is an assumption until observed. | Verify explicitly after implementation that Hibernate does not touch `querysense_uploads`. Record the result here rather than assuming it. |
| R10 | Two schemas, one UI | The uploaded schema drives generation while a plan may describe a different database. A user who cannot tell which is active cannot trust either. | Active schema is shown persistently in `EngineStatusBar`, alongside the engine indicator — same class of provenance fact, same treatment. |

**What this feature does *not* change:** `MySqlExplainAnalyzer` never read
`KnownSchema` and still does not. The routing layers, confidence tiers and
history writing are untouched. The two open correctness defects above
(`order_items` mis-resolution, LLM inventing tables) are **not** fixed by
this work — a dynamic schema runs through the same matcher, so both defects
follow the uploaded schema rather than being cured by it.

### Frontend housekeeping

- **Mock mode exists and fails toward mock.** `client.js` checks
  `VITE_USE_MOCK !== 'false'`, so an unset or misspelled value silently runs the
  whole app on `src/mock/fixtures.js`. `.env` ships with the literal `false`.
  This is the single most misleading failure mode in the project: the app looks
  healthy with the backend stopped.
- **The comparison screen is always simulated**, mock mode or not. See §4.
- **No backend health check.** Nothing calls `/test`; a dead backend shows up as
  a per-request error, not a banner.
- 3 oxlint warnings are outstanding (two react-refresh, one unused parameter in
  the fixtures). No errors; `npm run build` is clean.

---

## 6. Gotchas worth knowing before debugging

- **MySQL defaults to 3306** here. The URL is `${DB_HOST:localhost}:${DB_PORT:3306}`; override `DB_PORT` rather than editing the file if your instance listens elsewhere.
- **`explainFailed: true` on every analyse** almost always means the backend is
  running under the `h2` profile. H2 cannot produce MySQL `EXPLAIN` output. This
  is expected, not a bug.
- **Empty `suggestions` and empty schema advice** follow from the same cause:
  both modules read `EXPLAIN` flags, and under H2 there are none. They are also
  legitimately empty for a clean indexed query.
- **The generated SQL is lowercased in `WHERE` values** (`city = 'mumbai'`)
  because the rule engine normalises input to lower case before matching. The
  seeded data uses `'Mumbai'`; MySQL's default collation is case-insensitive, so
  the query still matches.
- **A 503 from any endpoint** is the local model, not the server. Check
  `ollama list` and that `qwen2.5-coder:7b` is pulled.
- **A browser timeout on a fallback question** while the backend keeps working
  means `VITE_API_TIMEOUT` is below what the cold model needs. It ships at 65 s
  precisely because the backend itself waits 60 s; don't lower it.
- **The app showing data with the backend stopped** means `VITE_USE_MOCK` isn't
  the literal string `false`. Unset counts as mock. Check `.env` before
  debugging anything else that looks impossible.
- **Numbers on the comparison screen are invented.** That screen has no
  endpoint and never did; it is simulated even in real mode.
- **Generated SQL naming a table you do not have** (`transactions`, `buyer_id`)
  is the schema-grounding gap, not a model quirk: if the question does not
  literally contain `customer(s)`/`order(s)`, the LLM prompt carries no schema
  at all. Rephrase using the table names to confirm. See §5, "Correctness
  defect: the LLM invents tables and columns" — and note the bad query is
  cached, so the repeat comes back at confidence 1.0.

---

## 7. Verification record

**This record was reset when the frontend was replaced.** The previous entry
described a different React 18 codebase (now kept at
`CAPSTONE FRONTEND.local-backup/`, untracked by git) and its 40-check adapter
suite, none of which exists in the current frontend. Do not read the claims
below as covering more than they say.

### Backend — still verified

Unchanged from the earlier record; the backend was not modified by the swap.

- All four rule-engine sample prompts return exactly the documented SQL.
- All three answering layers observed in real history rows: `RULE_ENGINE`,
  `OLLAMA`, and `CACHE` on a repeated question.
- Request/response shapes for all four endpoints captured from live calls.
- `GET /test` answers `QuerySense Running`.

### Current frontend — what has actually been checked

- Its API layer was read against the backend's DTOs field by field. Two
  mismatches were found and fixed: `handledBy` compared against the retired
  `GEMINI` literal in five places, and a 15 s axios timeout sitting below the
  backend's 60 s LLM wait.
- `npm run lint` — 0 errors, 3 pre-existing warnings.
- `npm run build` — clean.
- Dev server and backend confirmed listening on 5173 and 8080 together.

### Session of 2026-09-10 — EXPLAIN repair

Run against the live backend on MySQL 3306 with Ollama up.

- `mvn test` — **44/44 pass**, BUILD SUCCESS, including the 6 new
  `KnownSchemaTest` cases. (`MySqlExplainAnalyzerTest` logs an `ERROR` line by
  design; it exercises the failure path.)
- `npm run build` clean; `npm run lint` 0 errors, 3 pre-existing warnings, none
  in the changed files.
- Single-table EXPLAIN: `explainFailed: false`, full scan, 15 rows on
  `customers`; 25 on `orders`.
- **Join EXPLAIN returns both tables** — `customers c LEFT JOIN orders o`
  yields `c: ALL, no key, 15 rows, Using temporary` and
  `o: ref, customer_id, 1 row, Using index`, plus three domain/resource
  suggestions. This is what `ExplainPlanView` now renders per table.
- Five NL round-trips through generate then analyze on previously-unknown
  tables and columns, all `explainFailed: false` (table in section 2).
- Confirmed the failure that started it: `EXPLAIN SELECT order_id, amount FROM
  orders` returns `ERROR 1054 (42S22): Unknown column 'order_id' in 'field
  list'`.
- Confirmed EXPLAIN's dependency on live tables and statistics:
  `EXPLAIN ... FROM invoices` (absent from the connected database) returns
  `ERROR 1146`, and on an empty table of identical shape MySQL reports
  `rows=1, filtered=100.00` where the populated table reports
  `rows=15, filtered=10.00` — same access path, useless magnitude. Relevant to
  any future "upload a schema" feature: structure alone gets EXPLAIN to run,
  but not to mean anything.

### Not verified

- **No screen of the current frontend has been exercised against the live
  backend in a browser.** No page render, no request, no console check. The
  contract alignment above is a code-level review, not an observation. This
  still holds after the 2026-09-10 changes: the per-table plan blocks added to
  `ExplainPlanView` have been verified as data and as a clean build, **never as
  pixels** — the browser extension was unavailable throughout.
- The populated states of the analysis, optimisation and schema-advisor screens
  (a real plan tree, non-empty suggestions, real index proposals) still require
  a backend on real MySQL, since H2 cannot produce `EXPLAIN` output.
- The `OLLAMA` rename fix is exactly what an end-to-end fallback run would
  confirm, and is the first thing to check.

### Behavioural shift worth watching

Widening `KnownSchema` from two tables to nine widened the `tableCount > 1`
complexity gate along with it. Questions like "products in a category" or
"employees in a department" now mention two known tables and route to the LLM,
where before neither table existed and they were handled or declined on other
grounds. **More traffic depends on Ollama than it used to.** With Ollama down,
more questions now fail with a 503 than did under the two-table schema.
