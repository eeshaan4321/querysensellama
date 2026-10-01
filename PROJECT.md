# QuerySense

Natural-language questions in, MySQL or MongoDB queries out — with the routing
decision, the execution plan, and the optimisation advice all shown rather than
hidden.

The project is two codebases that talk to each other, in one repository:

```
FULL CAPSTONE/                  → github.com/eeshaan4321/querysensellama (private, branch main)
├── backend/                    Spring Boot 3 backend (Java, Maven)   → :8080
├── frontend/                   React 19 + Vite SPA                   → :5173
├── docs/superpowers/           design specs and implementation plans
└── PROJECT.md                  this file
```

**This is a monorepo, and it is the only place work happens.** Paths quoted
throughout this file are relative to the root above and are literal — no
translation needed.

### History, and two things that will confuse you otherwise

The halves began as separate repositories, `AnantKekre01/querysense-backend`
and `AnantKekre01/querysense-frontend`, and were consolidated here on
2026-09-10. Two artefacts of that move survive locally and are deliberately
untracked:

- **`backend/.git.disabled/` and `frontend/.git.disabled/`** are the original
  per-repo git directories, renamed rather than deleted so their branches and
  history stay recoverable. They are ignored by the root `.gitignore`. Nothing
  reads them; they exist only so the old history is not lost. The old remotes
  are no longer used and are behind this repository.
- **`frontend.local-backup/`** is the previous React 18 frontend, kept for
  reference. See §7 — the verification record was reset when the frontend was
  replaced, and this directory is what it was replaced with.

The directories were also renamed during the move: `CAPSTONE CODE/` →
`backend/` and `CAPSTONE FRONTEND/` → `frontend/`. Any older note quoting the
former names refers to the same code.

---

## 1. Running it

You need three things up, in this order. Only the first is mandatory.

### Backend — required

```bash
cd backend

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
cd frontend
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

**The "Connected to backend" line at the foot of the nav rail is not one.**
`NavRail.jsx` renders it from the `USE_MOCK` flag alone — it means "this
build is configured to call a real backend", never "a backend answered". It
reads exactly the same with nothing listening on 8080. Combined with the
mock-mode trap below (`VITE_USE_MOCK` unset ⇒ mock), the two indicators
between them can state "Connected to backend" while every figure on screen
came from `fixtures.js`, or while the backend is stopped.

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
| `backend/src/main/resources/application.properties` | `spring.datasource.url` | `${DB_HOST:localhost}:${DB_PORT:3306}` — **defaults to 3306**; set `DB_PORT` if your instance differs |
| | `ollama.model` | `qwen2.5-coder:7b` |
| | `http.client.read-timeout-ms` | 60 s — how long the backend waits for the model |
| `frontend/.env` | `VITE_API_BASE_URL` | `http://localhost:8080` — called directly, no proxy |
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
| 2. History cache | `CACHE` | 1.00 | Layer 1 declined, but this exact input was answered before **under the schema now active** — and that answer still fits it |
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
knowing before demoing: the optimisation rules report a full scan as `WARNING`
and only escalate it to `CRITICAL` above 100 rows, because that branch reads
`estimatedRows`. At this scale the optimisation modules can identify a problem
but never show one costing anything.

**Row counts gate how much the optimisation modules can say.** The
thresholds are the optimisation rules' 100 and `ResourceConsciousService`'s
5000, both exclusive, and the built-in tables clear neither. (There used to be
four; the e-commerce and analytical sets went with the domain picker, §9.7.)

**Uploading a schema no longer changes that by default.** `seed-rows` was
12000 — chosen to clear all four, and verified live doing so (§7) — and was
lowered to **100 on 2026-09-10** at the user's direction, because a sample
database should not leave twelve thousand rows per table sitting in MySQL.
At 100 an upload clears no threshold either — the optimisation rules need
*more than* 100 — so a full scan stays at `WARNING` and
`ResourceConsciousService` emits nothing of its own.

**To demo the optimisation modules saying something substantial, raise the
property** — no code change, no rebuild:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--querysense.schema.upload.seed-rows=12000
```

then re-upload. §7 records what the same query returns at each scale. The
engine identifies every problem correctly at 100; it just cannot show one
costing anything.

**Rewritten 2026-09-11 — the decline rule is no longer a word list.** It used
to hand to the LLM anything containing a join, an aggregate, a date range, a
comparison, a question word, or a mention of two tables at once. None of those
signals had anything to do with the schema, so uploading one changed the
engine's vocabulary but never what it was willing to claim — which is why
uploading a schema appeared to change nothing.

It now declines on **binding failure** instead: every noun in the question is
resolved against the active schema, and the engine gives up when

- nothing in the question matches a table, or more than two tables do;
- a table pair has no declared foreign key between them, or more than one;
- a named field is not a column of the bound table;
- two candidates tie on specificity;
- a filter's literal appears in no column's data;
- the question contains a temporal expression (`last month`, `in January`,
  `before 2024`) — date arithmetic is still out of scope, but it declines as a
  binder rather than as a token veto. The old month regex contained `may`, so
  *any* sentence with the word "may" in it was declined.

`who`, `which`, `more than`, `average`, `sum` and single-hop joins are now
handled.

**Updated 2026-09-26 — MongoDB is no longer a blanket decline.** The rule
engine now resolves `databaseType` to a dialect and, for MongoDB, binds and
writes the query the same way it does for SQL, against `KnownMongoSchema` or
an uploaded Mongo schema, through a `MongoEmitter` instead of `SqlEmitter`.
See §7's 2026-09-26 session and `RULES.md` E2. It still declines the same
binder failures as SQL (no table, ambiguous join, unbound column, unbound
value, a temporal expression), plus a few Mongo-specific ones the emitter
declines rather than approximate — a `WHERE` on the joined collection, and a
`WHERE` on an embedded-array field while aggregating (§7). An unrecognised
`databaseType` is now a 400, not a decline. **Not yet verified against a
live `mongod`** — every Mongo behaviour above is unit-tested only.

Its live-data safety net was rebuilt at the same time. It used to emit a
literal `city = …` and then check that against `customers.city` — a table and
column an uploaded schema need not have, where the check went silently inert
and the emitted column was simply wrong. It now works the other way round:
find the column whose data actually contains the value, and decline if none
does.

### Verified behaviour

These are the five sample prompts in the app, run against the live backend:

| Question | Layer | Result |
|---|---|---|
| Show all customers | rule | `SELECT * FROM customers;` |
| Show only the name and email of customers | rule | `SELECT name, email FROM customers;` |
| Show customers in Mumbai | rule | `SELECT * FROM customers WHERE city = 'mumbai';` |
| How many orders are there | rule | `SELECT COUNT(*) FROM orders;` |
| Which customers placed more than three orders | **rule** (since 2026-09-11) | `SELECT customers.id, customers.name FROM customers JOIN orders ON customers.id = orders.customer_id GROUP BY customers.id, customers.name HAVING COUNT(orders.id) > 3;` — the `ON` clause read from the declared FK |

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
// request — all three fields required
{ "query": "SELECT * FROM customers WHERE city = 'mumbai';",
  "databaseType": "MySQL", "resourceLevel": "LOW" }

// response
{ "analysis": { /* flat QueryAnalysisResult, see below */ },
  "suggestions": [ { "message": "...", "severity": "CRITICAL", "source": "DOMAIN" } ] }
```

`resourceLevel` ∈ `LOW | MEDIUM | HIGH`, `severity` ∈ `INFO | WARNING | CRITICAL`,
`source` ∈ `DOMAIN | RESOURCE`.

**`domainProfile` was removed on 2026-09-11**, with the Workbench's domain
picker (§9.7). A client that still sends it is not rejected — Jackson ignores
unknown fields by default — but the value does nothing. `source: "DOMAIN"` kept
its name; it now tags the single optimisation rule set.

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

This one endpoint carries three modules: the analysis itself, the optimisation
rules, and the resource policy. The last two appear only as tagged entries in
`suggestions`.

**Resource level genuinely changes the output.** `LOW` shifts every
`DOMAIN`-tagged suggestion up one severity step, `HIGH` shifts it down, `MEDIUM` leaves it
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

### `GET /api/health` · `GET /test`

```jsonc
// GET /api/health
{ "status": "UP", "schemaLabel": "shop.sql", "builtInSchema": false }
```

Added 2026-09-21, replacing `TestController`. The frontend's shell banner polls
it every 20s. `GET /test` still returns the bare string `QuerySense Running`
for older probes. Neither touches the database, so a probe cannot be used to
tell whether MySQL is reachable — only whether the app is up.

### `POST /api/query/compare`

```jsonc
// request                                          // response
{                                                   {
  "query": "SELECT * FROM customers                   "originalQuery":  "SELECT * FROM …",
            WHERE city = 'mumbai'                     "optimizedQuery": "SELECT id, name, … IN (…)",
            OR city = 'pune';",                       "rewritten": true,
  "databaseType": "MySQL"                             "changes": [ { "ruleId": "RW1",
}                                                                    "explanation": "Named the 5 …" } ],
                                                      "originalAnalysis":  { /* QueryAnalysisResult */ },
                                                      "optimizedAnalysis": { /* QueryAnalysisResult */ },
                                                      "estimatedRowsDelta": -291,
                                                      "queryCostDelta": -57.5,
                                                      "estimate": true,
                                                      "summary": "2 rewrite rules applied. …"
                                                    }
```

- Added 2026-09-21. Runs `QueryRewriter`, then `EXPLAIN`s **both** versions
  through the same `QueryAnalysisService` the Analysis screen uses.
- **A delta is negative when the rewrite is an improvement** — it is
  `after - before`, so `-291` means 291 fewer rows read.
- `estimate` is always `true` and is not a placeholder. Both numbers come from
  `EXPLAIN`, which estimates; nothing here is measured. The summary sentence
  repeats this in words.
- **No rewrite is not an error.** `rewritten: false` with
  `optimizedAnalysis: null` and null deltas means no rule applied. The original
  is still analysed and returned.
- `databaseType: "MongoDB"` returns immediately with `rewritten: false` and a
  summary saying so — the rewriter has no MongoDB rules. **The analyser is not
  called at all** on that path.
- No history row is written. This endpoint reads.

### `GET /api/query/history`

Returns the JPA entity unwrapped:

```jsonc
[ { "id": 3,
    "userInput": "show all customers and give a count of their orders",
    "generatedQuery": "SELECT c.id, …",
    "optimizedQuery": null,          // column exists, nothing writes to it
    "databaseType": "MySQL",
    "source": "OLLAMA",              // RULE_ENGINE | CACHE | OLLAMA
    "confidence": 0.65,              // added 2026-09-21; null on older rows
    "durationMs": 1143,              // added 2026-09-21; null on older rows
    "schemaLabel": "shop.sql",       // added 2026-09-21; which schema was live
    "createdAt": "2026-09-08T19:23:23.644141" } ]
```

`confidence`, `durationMs` and `schemaLabel` were added 2026-09-21 and are
**null for every row written before that** — `ddl-auto=update` adds the columns
without backfilling them. The History screen renders a null as `—`.
`schemaLabel` is the load-bearing one: uploads swap the active schema between
runs, so without it an old row cannot be interpreted.

`createdAt` is a `LocalDateTime` — serialised **without a timezone**, so it is
local server time. Rows come back in insertion order; the frontend sorts newest
first by `id`.

### `POST /api/schema/upload`

Replaces the schema QuerySense works against. Accepts **either** a multipart
file or a JSON body — both funnel into the same pipeline.

```bash
# multipart
curl -X POST http://localhost:8080/api/schema/upload -F 'file=@northwind.sql'

# JSON, for DDL pasted into the UI
curl -X POST http://localhost:8080/api/schema/upload -H 'Content-Type: application/json' \
  -d '{"ddl":"CREATE TABLE clients (id INT PRIMARY KEY, city VARCHAR(60));","label":"demo.sql"}'
```

Response — captured from a live call, not documented from the DTO:

```jsonc
{ "schemaId": "upload-6e1af6fe",
  "label": "demo.sql",
  "catalog": "querysense_uploads",
  "builtIn": false,
  "tables": [ { "name": "clients",
                "columns": ["id","name","city","email"],
                "rowCount": 100 } ],
  "foreignKeys": [ { "fromTable": "invoices", "fromColumn": "client_id",
                     "toTable": "clients", "toColumn": "id" } ],
  "warnings": [],
  "loadedAt": "2026-09-10T08:45:58.155691100Z" }
```

What it does, in order: parse the DDL with JSqlParser, validate it, create
`querysense_uploads` if absent, drop whatever that database already held,
re-emit each `CREATE TABLE` **from the parsed model** (the uploaded text is
never executed), add foreign keys, seed every table, then `ANALYZE TABLE`.

- `warnings` lists statements the parser skipped. A dump full of `LOCK
  TABLES` and `/*!40101 ... */` parses fine; only an unreadable `CREATE
  TABLE` produces a warning.
- **Errors are 400 with an actionable sentence**, not a generic 500 — a
  missing `CREATE` privilege returns the exact `GRANT` statement to run, and
  the `h2` profile is refused before anything is provisioned.
- Uploading again **drops the previous upload's tables**. Only
  `querysense_uploads` is ever dropped; the connected database is never a
  target.

### `GET /api/schema/active` · `POST /api/schema/reset`

Both return the same `SchemaUploadResponse` shape. `/active` describes the
current schema; `/reset` returns to the built-in one and is instant — the
uploaded tables are left where they are and simply stop being what anything
reads.

**`catalog` is `null` for the built-in schema, deliberately.** The demo
schema lives in whatever database `spring.datasource.url` resolves to, which
varies per machine, so the backend refuses to guess a name for it. The
frontend renders `null` as "the connected database", and `ExplainExecutor`
treats it as "do not switch catalog at all" — which is what makes the demo
path provably untouched.

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
is centralised in **`frontend/src/api/contracts.js`** — the only file
in the frontend that knows a backend field name.

| Frontend | Backend |
|---|---|
| `naturalLanguageQuery` | `query` |
| `targetDb: 'SQL'` | `databaseType: "MySQL"` |
| `targetDb: 'MONGODB'` | `databaseType: "MongoDB"` |

The one value the frontend does **not** rename is `handledBy` — it renders the
backend's `RULE_ENGINE` / `CACHE` / `OLLAMA` as-is, because history rows are an
audit trail and the screen's job is to show what actually ran.

`"GEMINI"` is still accepted on read: the backend renamed its LLM tier when the
fallback moved to local Ollama and deliberately left existing rows alone, so
`/history` serves both values. `isLlmSource()` in `contracts.js` is the single
place that knows this — components call it instead of comparing against a
literal, so the amber LLM treatment applies to old and new rows alike and a
future provider change is one edit.

### The pipeline is four requests, not seven

The UI shows six screens. It makes four HTTP calls, and one of them runs
once at startup rather than per query:

```
0. GET  /api/schema/active           → which schema is live (once, at app start)
1. POST /api/query/generate          → Build query
2. POST /api/query/analyze           → Analyse
                                       ├── Optimisation rules (no request)
                                       └── Resource policy    (no request)
3. POST /api/query/schema-advisor    → Schema advice
   (reuses step 3)                   → Optimization, schema half
4. POST /api/query/compare           → Optimization, rewrite half (on click)
```

Stages that make no request are derived from a response already in hand.
History needs no call at all — the backend writes the row during step 1.

**Optimization's real half reuses step 3 rather than repeating it.** The
advisor's result is held in `WorkbenchContext`, and the Schema advisor
screen's own button navigates to Optimization, so the response is normally
already in hand. The page fetches only when someone arrived without passing
through that screen, and writes the result back to context so neither screen
asks twice. This matters because `EXPLAIN` already runs twice per pipeline
(§3, `/schema-advisor`); a naive refetch here would make it four.

**Both halves of Optimization are real as of 2026-09-21.** The rewrite half
calls `POST /api/query/compare`, which `EXPLAIN`s the original and the
rewrite and returns both plans (§3). It fires on a button rather than on
arrival, because it is the only stage that costs two more `EXPLAIN` round
trips. The numbers it shows are `EXPLAIN` estimates and the screen says so —
estimates, but no longer invented.

### Layout

```
frontend/src/
├── api/
│   ├── contracts.js     ← all field translation, enums, isLlmSource, ENDPOINT_STATUS
│   ├── client.js        ← axios instance, USE_MOCK flag
│   ├── index.js         ← re-exports; one module per endpoint alongside it
│   └── queryBuilder.js · analysis.js · schemaAdvisor.js · history.js · comparison.js · schemaUpload.js
├── context/             ← EngineStatusContext (last engine used), ActiveSchemaContext
│                          (which schema is live), WorkbenchContext
├── pages/               ← Workbench, SchemaSource, Analysis, Optimization, SchemaAdvisor,
│                          History
├── components/          ← shell/, common/, then one folder per feature area
├── mock/fixtures.js     ← every generator behind VITE_USE_MOCK
├── lib/                 ← query highlighter, line diff, resource levels,
│                          applyScript.js (+ its test — the only tested file)
└── styles/              ← tokens.css (design tokens), base.css (reset)
```

**To wire a new backend endpoint:** add its shape and `ENDPOINT_STATUS` entry to
`contracts.js`, add a module next to the others in `api/` that branches on
`USE_MOCK`, and export it from `index.js`. Nothing in `components/` or `pages/`
should need to move — they only read shapes defined in `contracts.js`.
`schemaUpload.js` was added exactly this way and touched no existing component
except to add the provenance notes described below.

### What the Analysis screen is actually telling you

Written down because the screen shows sections that look alike, carry
different weight, and are sourced differently — and one of them duplicates
a job the Schema advisor does better.

**Restructured 2026-09-10.** The screen used to open directly on MySQL's own
vocabulary — "Access type: ALL", "Uses filesort: Yes", "Joined on: —". Every
line was true, nothing said which line mattered, and the single number on the
page ("Estimated rows examined: 100") had no denominator, so it could not
answer *is that bad?* for anyone. It now reads top to bottom as verdict →
warnings → metrics → plan: the plain-English answer first, then the evidence
for it, in increasing technical depth. The plan itself is unchanged; it
stopped being the whole answer and became the supporting detail.

**Section 0, `VerdictBanner` — the answer.** One severity chip, one headline
sentence, one supporting sentence, composed server-side by `VerdictService`
(§ below) and rendered before any `EXPLAIN` vocabulary. It carries **no
numbers of its own** — every figure it phrases comes from the same fields the
metrics below are drawn from, so the banner cannot contradict the plan it
sits above. Absent for MongoDB and for non-`SELECT` statements, which are not
graded; the component renders nothing rather than an empty box.

Its `UNKNOWN` state is deliberately not a grade. "MySQL couldn't produce an
execution plan" must never look like a pass — the same distinction
`explainFailed` was introduced to preserve (§7, 2026-09-07).

**Section 1, `PlanMetrics` — the four numbers.** Rows examined, rows
returned, wasted work, query cost — each with the sentence that makes it
mean something. Rows returned is `rows × filtered/100`, which is what finally
gives rows-examined a denominator; wasted work states the ratio outright
("200 rows read for every 1 returned"). A missing value **omits its tile
entirely** rather than rendering a dash, because `filtered` is read as an
optional `EXPLAIN` column and the cost probe is allowed to fail — absence
means "MySQL did not report this", and a dash in a metric row reads as zero.

**Section 2, `ExplainPlanView` — the plan.** Access type, index used,
**indexes considered**, rows examined, rows returned, joined on, full table
scan, filesort, temporary table — each with a one-line plain-English gloss
from `lib/explainGlossary.js`. The glosses are static reference text and live
in the frontend deliberately; the *judgement* is data-dependent and comes
from the backend. Boolean rows gloss only when true, so "Full table scan: No"
stays quiet.

"Indexes considered" is `possible_keys`, which the row mapper had always
captured and which nothing ever rendered. Without it, "no index exists on
this table" and "an index exists and MySQL passed over it" both displayed as
an empty "Index used" — two different problems with two different answers,
shown identically. It is now also the evidence a reader can check the
verdict's "an index was available" finding against.

These flags are MySQL's own report rather than anything inferred, and every
rule downstream — optimisation, resource and schema advisor alike — fires on
exactly them, so this section remains the evidence the rest of the app
reasons from.

What it still does **not** give you: a plan *tree* — `EXPLAIN`'s tabular
output is a flat list of table accesses in join order (§5.3) — and no
measured anything, since the analyzer never executes the query. The per-table
breakdown now renders whenever the backend returns `tables` at all, including
for a single table: `possibleKeys` and `estimatedRowsReturned` exist only on
the per-table rows, so the old `tables.length > 1` condition dropped the two
most useful facts on the page for the most common kind of query. The
collapsed five-row view survives only as the fallback for results carrying no
`tables` (mock mode, Mongo).

Blunt framing worth keeping in mind: **this section is the diagnosis and the
Schema advisor is the prescription for the same finding.** "Unindexed scan
on `gender`" versus "`CREATE INDEX idx_user_details_gender`". If a demo is
short on time, the advisor is the more useful of the two.

**Section 2, `SuggestionsList` — optimisation and resource advice.** One
fixed rule set, the transactional one (§9.7), read against the plan, with
its severities shifted by the resource level. Each entry is tagged
"Optimization" or "Resource".

**The domain picker was removed on 2026-09-11.** This section used to read
the same `EXPLAIN` facts differently per declared workload — e-commerce,
analytical or transactional, chosen from a dropdown on the Workbench.
Schemas are now uploaded directly, so no workload is ever declared; the
dropdown went, and the e-commerce and analytical rule sets went with it.
Transactional was already what a missing profile or "Other" fell back to,
so a run that never picked a domain behaves exactly as before. Its messages
still say "transactional" — they were written for that profile and have not
been reworded.

**Do not confuse this section with the Optimization screen's rewrite rules
(§9.11).** They look alike and are framed alike. This one is advice text from
`/api/query/analyze` and changes nothing; that one rewrites the SQL itself
via `/api/query/compare`.

### Two things the shell shows permanently

The header carries two independent facts, and they are the same *kind* of
fact: provenance you need in order to trust what is on screen.

- **Which engine answered** — `EngineStatusContext`, teal for the rule engine,
  amber for the LLM. Unchanged.
- **Which schema is live** — `ActiveSchemaContext`, purple (`--data`, the
  project's data-access colour). Reads "Built-in demo schema", or the
  upload's name followed by **"generated data"**.

That last phrase is load-bearing. An uploaded schema's tables hold rows this
app generated, so its `estimatedRows` is real `EXPLAIN` output over synthetic
data. `ExplainPlanView` and the schema advisor repeat it in place. Without
that, the comparison screen would stop being the only place in the UI showing
numbers nobody measured.

---

## 5. What is left

Everything below is genuinely absent, not merely unwired. The UI states each one
on the relevant screen rather than filling the gap with plausible-looking
numbers.

### Not a gap: QuerySense recommends, it never executes

**Decided 2026-09-10. This is a non-goal, not a TODO.** No endpoint runs the
DDL the schema advisor produces, and none will be added. The advisor hands
you `CREATE INDEX` statements; running them is the user's, in their own
client.

The reasoning is proportionality. A tool that reads `EXPLAIN` needs only
`SELECT` on the schema it advises about. One that applies its own advice
needs `CREATE`/`ALTER` on a live database, on the strength of five rules that
fire off single `EXPLAIN` flags with **no consistency checking between them**
(§5.2) — two recommendations can already contradict each other, and the
`(gender, status)` composite case is a live example where running both
emitted statements is the wrong move. Advice a human reviews can be wrong.
DDL a tool executes unattended cannot.

It also keeps the project's central claim intact: QuerySense shows its
reasoning rather than acting on it.

**What this forbids, so later work doesn't quietly reintroduce it:** any
`POST /api/schema/apply`-shaped endpoint; any "fix it for me" control in the
UI; and any widening of the upload path's DDL execution beyond the isolated
`querysense_uploads` catalog it already provisions. The upload feature is the
one place this project emits DDL, and it does so only into a scratch catalog
it owns — never the connected database (§3, §6).

The UI carries this in its wording. The Optimization screen's real half is
headed **"Recommended schema changes"** and its script block **"Copy as one
script"** — an earlier draft read "Schema changes to apply" and "Apply all",
which implied an action the app does not perform. The only control on that
block is Copy.

### Not a gap: MongoDB is analysed, never connected

> **REOPENED 2026-09-25 at the user's request** — parity with the SQL pipeline
> is being considered. The decision below is kept in full, because it is still
> the reason the code looks the way it does and still the argument against
> doing this badly. What it forbids is now under review rather than settled.
> The feasibility assessment is the section immediately after this one.
>
> **DECIDED 2026-09-26: full parity, including upload.** The design is
> `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`. The build is in
> progress, and this section is deleted when it is finished (spec step 9).
> Until then the code still behaves as described below, except for what §7's
> 2026-09-26 session lists as done.
>
> **SUPERSEDED 2026-09-26 for stage 1 (the rule engine) only.** The rule
> engine now builds MongoDB questions instead of declining every one of
> them — see §7's 2026-09-26 session and `RULES.md` E2/E23. Spec steps 4–9
> (analysis, advisor, rewrite, upload) are still exactly as this section and
> the next one describe; only the first bullet under "What follows from the
> decision" below is out of date.

**Decided 2026-09-21. This is a non-goal, not a TODO.** QuerySense will not
grow a live MongoDB connection, a Mongo path in the rule engine, or Mongo
support in schema upload. What exists is what will exist: two static text
checks over the query string (`MongoSimpleAnalyzer` — unindexed filter field,
unanchored `$regex`), the two schema-advisor rules those feed, and LLM
generation.

The reasoning is the same proportionality argument as the DDL non-goal above,
pointed at scope rather than privilege. Supporting MongoDB properly is not one
feature, it is four: a live connection, upload provisioning collections and
indexes, schema-grounded Mongo prompts, and a rule-engine Mongo path. Each is
comparable in size to the whole rewrite engine. Done half-way, the result is a
system that *looks* like it supports two databases and is measurably worse at
one of them — which is the single thing this project's central claim cannot
survive. "MySQL, done properly, with the limits stated" is a stronger position
than two databases half-supported.

**What this forbids, so later work doesn't quietly reintroduce it:**
uncommenting `spring.data.mongodb.uri` and wiring a `MongoTemplate`; extending
`SchemaUploadService` to emit Mongo collections; adding a Mongo branch to
`RuleBasedQueryBuilder`. Each would put QuerySense back on the road to a second
half-supported database.

**What follows from the decision, stated rather than discovered:**

- ~~**The rule engine declines every MongoDB question**, so 100% of Mongo
  generation is the LLM. With Ollama down, every Mongo question 503s.~~
  **No longer true as of 2026-09-26** — see the note above. Every other
  bullet below is still current.
- **Mongo LLM generation is ungrounded and unguarded.**
  `buildSchemaContext` returns `""` for any non-MySQL dialect and
  `GeneratedQueryValidator` returns clean for one. Both are deliberate:
  `KnownSchema` describes relational tables, and there is no Mongo schema to
  check output against. **Report Mongo accuracy as its own figure; folding it
  into a blended number hides this.**
- **Schema upload does nothing for MongoDB.** The pipeline emits MySQL DDL,
  seeds through JDBC and reads MySQL `EXPLAIN`, end to end.
- **`MongoSimpleAnalyzer` never runs the query** and produces no plan. Its
  "known indexed fields" list is the hardcoded placeholder `_id`, `email`,
  `userId` — not real index metadata.
- **There is no comparison for MongoDB.** `QueryRewriter` has no Mongo rules,
  and `/api/query/compare` returns `rewritten: false` with a summary saying so,
  without calling the analyser at all.

The UI carries this rather than leaving it to be discovered: the Optimization
screen's rewrite half shows "SQL only, for now" for a Mongo analysis, and the
Analysis screen shows Mongo findings instead of a plan. Neither pretends a
plan is coming.

### What MongoDB parity would actually take — assessed 2026-09-25

Asked directly: can MongoDB have the same structure the SQL side has? **Yes,
and less of it is new work than the non-goal above assumes.** That assessment
was written from scope, before anyone checked what MongoDB's own tooling
offers. This section is the check. Nothing here is built.

#### The fact that changes the argument: MongoDB has a real `explain()`

`db.coll.find({…}).explain("executionStats")` returns a winning plan with
stages, and its figures land on fields `QueryAnalysisResult` **already has**:

| SQL today | MongoDB equivalent |
|---|---|
| `accessType: ALL`, `hasFullTableScan` | a `COLLSCAN` stage |
| `usesIndex`, `keyUsed` | an `IXSCAN` stage, `indexName` |
| `usesFilesort` | an in-memory `SORT` stage |
| `estimatedRows` | `totalDocsExamined` |
| `estimatedRowsReturned` | `nReturned` |
| read amplification | the same ratio, the same meaning |

So the verdict ladder (§9.6), the metric tiles, the wasted-work breakdown and
most of the advisor's rule shapes **transfer rather than being reinvented**.
The original reasoning treated Mongo analysis as something that would have to
be invented; it does not.

#### Stage by stage, against the pipeline in §2

| Stage | What exists for MySQL | What MongoDB needs |
|---|---|---|
| 0 · Schema | DDL parsed into a `SchemaSnapshot` | **The one genuinely new concept.** No DDL to parse, so a `MongoSchemaProvider` samples N documents per collection to infer fields and reads **real** indexes via `listIndexes()`. That also retires `MongoSimpleAnalyzer`'s hardcoded `_id, email, userId` placeholder. |
| 1 · Rule engine | `QueryIntent` → `SqlEmitter` | `QueryIntent` is nearly dialect-neutral already, **but SQL rendering is baked into the record** — `Predicate.render()` emits SQL and `Predicate.in` writes SQL quoting. Lift rendering out into `SqlEmitter`, then add a `MongoEmitter` for `find`/`aggregate`. `SchemaBinder` and `QueryIntentParser` work unchanged. Joins become `$lookup`. **Largest single chunk, and the least necessary for a credible demo.** |
| 2 · Analysis | `MySqlExplainAnalyzer` | `MongoExplainAnalyzer` running the real `explain()` through a Mongo equivalent of `ExplainExecutor`. This is where "never runs the query, produces no plan" stops being true. |
| 3 · Advisor | `CREATE INDEX` from plan flags | `db.coll.createIndex({field: 1})` from the same flags, against real index metadata instead of two regexes. The five-rule shape carries over. |
| 4 · Rewrite + compare | `QueryRewriter`, 3 rules | The same semantics-preserving constraint, with Mongo rules: add a projection (the `SELECT *` analogue), collapse an `$or` chain on one field into `$in`, anchor a prefix regex. Then `explain()` both and compare `totalDocsExamined`. |
| 5 · History | dialect-agnostic | Nothing. |
| Frontend | every screen branches on `targetDb` | Mostly replacing the honest empty states with real content. |

#### The four things that are hard rather than merely long

1. **Inferred schema is sampling, not truth.** A field missing from the sample
   is not missing from the collection. This has to be labelled on screen the
   way "generated data" already is, or the system starts making claims it
   cannot support — the exact failure the non-goal above was written to avoid.
2. **`explain()` output shape varies by server version.** This is the MySQL 9
   `explain_format` trap (§6) in a second dialect: pin the verbosity, assert
   the shape in a test, and do not trust a bare call.
3. **Grounding the prompt** with an inferred schema is new prompt work with its
   own failure modes — though it does end the "ungrounded *and* unguarded"
   state that makes Mongo the least protected path today.
4. **There is nothing to test against on this machine.** `27017` is not
   listening, and §7's 2026-09-09 session records it never having been up
   here. mongod running and seeded is a precondition, not a detail.

#### Recommended order, if this goes ahead

**Stages 2 and 3 first** — real `explain()`, real index metadata. Highest value
per unit of work, each demoable alone, and together they retire the weakest
claim in the project: that `MongoSimpleAnalyzer` never runs the query and its
index list is a placeholder. Stage 1 last.

**The trade-off, stated rather than buried:** this is the largest remaining
build in the project, comparable to everything done in the fortnight to
2026-09-21, and it competes for time with the live verification run — which is
currently the larger risk, since nothing from that fortnight has met a running
backend. Scope and deadline were put back to the user; no decision recorded yet.

**Decision, 2026-09-26: full parity, upload included.** Four choices were
settled in conversation:
- A Mongo upload takes collections with a `$jsonSchema` validator, indexes and
  an optional `references` block. It does not take sample documents.
- MongoDB runs as a local Community Server on `27017`.
- Each database has its own active schema.
- The architecture is one pipeline with a per-dialect implementation of each
  stage, not a parallel Mongo pipeline.

Sampling a schema from documents (stage 0 above) is out of scope. The rule
engine (stage 1) is now required. The order of work is in the spec's §12.

### Missing endpoints

| Endpoint | Status | What is needed |
|---|---|---|
| `POST /api/query/compare` | **Built 2026-09-21.** `QueryComparisonController` → `QueryComparisonServiceImpl` → `QueryRewriter`, returning `CompareResponse`. The frontend calls it; the browser simulation is deleted. | — |
| `POST /api/history` | **No write endpoint.** | Only needed if the frontend should store per-run metadata the backend does not currently see (resource level, confidence, cost delta). Today the backend writes history itself and those fields are not persisted at all. |

### Missing capabilities behind endpoints that do exist

1. **Query rewriting exists, and is deliberately narrow.** Added 2026-09-21.
   `optimization/QueryRewriter` emits a rewritten SELECT plus the rules that
   changed it; `POST /api/query/compare` EXPLAINs both versions and returns the
   row and cost delta with a per-rule trace. Three rules, all of them
   **semantics-preserving** — see §9.11. That property is the whole design
   constraint: a rewrite that returns different rows is a different query, not
   an optimisation, so a rule that cannot guarantee identical rows declines.

   **What is still absent:** `DomainOptimizationService` and
   `ResourceConsciousService` still return advice text only — nothing turns one
   of *their* suggestions into SQL. `QueryHistory.optimizedQuery` is still
   always `null`; the compare endpoint does not write history.

   The Optimization page's two halves remain, but both are now real. They
   optimise different things: the advisor changes the *database* and leaves the
   query alone; the rewriter changes the *query* and leaves the database alone.
   The amber "Simulated" badge is gone, along with `mockSimulateOptimizedQuery`.

2. ~~**`mysql/schema.sql` and `h2/schema.sql` are two tables behind the database
   they are supposed to create.**~~ **FIXED 2026-09-26** — see the last
   paragraph of this item. Restated 2026-09-25 **against the live
   MySQL**, which reverses what this entry said from 2026-09-21 to that date.
   It read "`KnownSchema` and the seeded MySQL tables disagree about columns",
   naming `KnownSchema` as the wrong side because `mysql/schema.sql` creates
   only `customers` and `orders` and neither carries `phone` or `employee_id`.

   **`SHOW TABLES FROM querysense` settles it the other way.** The database
   holds all nine — `categories, customers, departments, employees,
   order_items, orders, payments, products, suppliers` — plus `query_history`,
   and `SHOW COLUMNS` gives `customers(id, name, email, city, phone)` and
   `orders(id, customer_id, employee_id, order_date, status)`, matching
   `KnownSchema` exactly, self-join and all. **`KnownSchema` is right. The two
   `schema.sql` files are stale**, left behind when the schema grew from the
   two-table version §7's 2026-09-09 session created.

   Which makes this a documentation-and-fixtures defect, not a rule-engine one:
   nothing at runtime reads those scripts. They matter for a teammate setting
   up a fresh machine, who would get a two-table database and a rule engine
   describing nine. §5's earlier claim that RW1's `phone` expansion is what
   makes compare withdraw a rewrite was reasoning from the same error and
   should not be repeated without a live check.

   **Fix:** bring both scripts up to the nine tables the database has, with
   seed data, and keep them there with `KnownSchemaMatchesSeedScriptsTest` —
   which parses both with the upload path's own `DdlSchemaParser` and asserts
   the tables, columns and foreign keys match `KnownSchema`. It needs no
   database. Nothing had ever compared the two sides, which is how the drift
   lasted.

   **Done 2026-09-26.** Both `schema.sql` files create all nine tables, in FK
   order, column for column with `KnownSchema`; the old `orders.order_id` and
   `orders.amount` are gone (the amount lives on `payments`). Both `data.sql`
   files seed every table. MySQL generates 303 customers, 30 employees, 60
   products, 900 orders, 1,800 order items and a payment per non-cancelled
   order. H2 keeps its 25 hand-written customers and 40 orders, plus 80 order
   items and 35 payments. `KnownSchemaMatchesSeedScriptsTest` is green. **The
   H2 scripts were executed** through H2's `RunScript` with the profile's JDBC
   URL; **the MySQL `data.sql` was not run**, since that would write to the
   live database. Against an existing database, `CREATE TABLE IF NOT EXISTS`
   and `INSERT IGNORE` leave current rows alone.

3. **Limited consistency checking in the schema advisor.** Each rule still
   reads one `EXPLAIN` signal and cannot see what the others emitted. **Since
   2026-09-21 one pair is reconciled:** a full scan and a filesort on the same
   table now collapse into a single composite `CREATE INDEX`, filtered column
   first, instead of two single-column statements that cannot both apply. That
   was the demonstrable contradiction — the `(gender, status)` case. Other
   overlaps remain possible, and the screen still says so rather than showing a
   green "internally consistent" badge.

4. ~~**Per-table, but still not a plan tree.**~~ **BUILT 2026-09-21.**
   `QueryAnalysisResult.planTree` carries a `PlanNode` graph and the Analysis
   screen renders it under the per-table blocks. It is parsed from
   `EXPLAIN FORMAT=JSON` — but **not** by flipping
   `querysense.explain.format`, which would swap an unexercised reader in front
   of every analysis (CLAUDE.md §18, judgement call 1). Instead the side probe
   that already fetched the cost figure now returns both, so the tree costs no
   extra round trip and the tabular reader stays the authority on the plan.
   A failed probe costs the tree and the cost, never the plan.

5. **QuerySense computes no cost of its own.** Corrected 2026-09-25 — this
   entry used to say there was no cost figure at all, which stopped being true
   on 2026-09-10 and contradicted §8 item 3. What is there:
   `QueryAnalysisResult.queryCost` carries **MySQL's own** `query_cost`, read
   out of the `EXPLAIN FORMAT=JSON` side probe, and the Analysis screen renders
   it as its own tile. What is not there is any cost model QuerySense computes:
   the `rows` estimate stays labelled "Estimated rows" on a separate tile,
   because calling a row count a cost would invent a unit. A failed probe drops
   the cost and the plan tree, never the plan.

6. **MongoDB is still a text-level check — and as of 2026-09-25 that is under
   review rather than decided.** See "Not a gap: MongoDB is analysed, never
   connected" above for the original reasoning, and "What MongoDB parity would
   actually take" after it for what building the SQL structure on this side
   would cost. Until something is built, the state is unchanged: two regex
   checks, no connection, no plan. The one real defect on that path was fixed
   2026-09-21: `MongoSimpleAnalyzer` was called with `collectionName = null`,
   so Mongo recommendations could not name their collection. It now reads the
   collection out of `db.<name>.find(...)` itself, and an explicit name still
   wins.

7. **`EXPLAIN` runs twice per pipeline run**, because the schema advisor re-runs
   the whole analysis. Accepted at this scale; worth revisiting if the analysis
   gets expensive.

8. **History run context — built 2026-09-21, completed 2026-09-26.** `QueryHistory` gained
   `confidence`, `durationMs` and `schemaLabel`, all set in `saveToHistory` and
   shown as columns on the History screen. `schemaLabel` is the one that
   matters most in practice: uploads swap the active schema between runs, so
   without it an old row cannot be interpreted at all.

   ~~**`resourceLevel` is still not stored, and cannot be** without a contract
   change — it is a parameter of `/api/query/analyze`, and history is written
   by `/api/query/generate`, which never sees it.~~ **DONE 2026-09-26.**
   `QueryRequest` gained an optional `resourceLevel` field; `saveToHistory`
   now writes it straight onto `QueryHistory`, and the History screen shows it
   as its own column. Callers that don't send one store null, and rows written
   before this change render `—` rather than a guessed value — same discipline
   as `schemaLabel` above. A caller sending an unrecognised `resourceLevel`
   string, or a body that doesn't parse as JSON at all, now gets a 400 naming
   the field and its allowed values, not the 500 that `HttpMessageNotReadableException`
   used to fall through to. See §7's 2026-09-26 session.

9. ~~**`TestController` (`GET /test`)** is a debug endpoint…~~ **RESOLVED
   2026-09-21.** It became load-bearing the moment the shell's health banner
   started polling it, so it got the real response that note asked for.
   `HealthController` now serves `GET /api/health` returning
   `{status, schemaLabel, builtInSchema}` — a caller branches on a field rather
   than parsing a string, and the probe says *which schema is answering*.
   `GET /test` still returns its old string, because older probes and docs call
   it; `TestController` itself is gone, folded into `HealthController`.

### Correctness defect: the rule engine resolves multi-word tables to the wrong table

**Status: FIXED 2026-09-11** by `SchemaBinder` — see the session entry in §7.
`SchemaBinderTest.orderItemsBeatsOrders` is the regression guard. The account
below is kept because the mechanism is worth understanding and the same trap
waits on any future matcher.

**Status when found: open, reproducible, found 2026-09-10.** Introduced by the
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

#### Fix direction — one of three now done

1. ~~When `detectTablesMentioned` finds nothing, pass **all** of
   `KnownSchema.TABLES` rather than nothing.~~ **Implemented 2026-09-10**
   alongside the schema-upload work. `OllamaService.buildSchemaContext` now
   falls back to every table in the active schema when the question names
   none, instead of sending an empty prompt. An over-broad prompt is strictly
   better than an empty one, and this was the case most likely to
   hallucinate — a question phrased in business words rather than schema
   words. It was included there rather than deferred because the feature's
   main use case is asking about *your own* schema in *your own* vocabulary,
   which is exactly the path that was ungrounded.
2. ~~Add a schema-conformance check over generated identifiers before the
   result is returned *or* cached.~~ **Implemented 2026-09-21.**
   `GeneratedQueryValidator.namesUnknownIdentifiers` parses the generated SQL,
   resolves every table in `FROM`/`JOIN` against the active schema and checks
   each referenced column against the tables that resolved, following aliases.
   A query that fails is returned at confidence **0.2** with the reason in a
   new `schemaWarning` field, and `saveToHistory` is skipped — so it cannot
   come back from the cache at 1.0. `SqlPreflightValidator` was the obvious
   candidate and turned out to be the wrong tool: its column check returns
   clean when the table has no columns, which is exactly the invented-table
   case, and it takes the table name as an argument rather than reading it
   from the SQL.
3. ~~**Still open.** Decide explicitly what MongoDB grounding should look
   like…~~ **DECIDED 2026-09-21: it stays a no-op, and the no-op is now
   deliberate.** See "Not a gap: MongoDB is analysed, never connected" in §5.
   There is no Mongo schema to ground a prompt with or to check output against,
   and creating one is the four-feature build that decision rules out. The
   consequence — Mongo generation is ungrounded and unguarded — is now stated
   in §5 and on screen rather than left to be discovered.

   **Qualified 2026-09-25.** "There is no Mongo schema" is true only of a
   *declared* one. Sampling documents and reading `listIndexes()` produces an
   inferred schema good enough to ground a prompt and to check output against,
   which is stage 0 of the parity assessment in §5. If that is built, this
   no-op stops being defensible and both the grounding and the validator gain
   a Mongo path. Until then the entry stands as written.

### Correctness defect: the cache did not know which schema answered

**Status: FIXED 2026-09-25.** `SchemaScopedCacheTest` is the regression
guard. The account is kept because the shape recurs: a key that was right
when it was written, and wrong once a second dimension appeared.

The Layer 2 lookup was `findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase`
— keyed on the question and the dialect, and on nothing else. Schema upload
added a third dimension in 2026-09-10 and the key did not follow it. So a
client who tried a question against the built-in demo schema, uploaded their
own, and asked the same question in the same words got **the demo schema's
SQL back at confidence 1.0**, against their tables. The practice run poisoned
the real one, and the more confident the label, the more convincing the wrong
answer.

Two things made it worse. The cache branch never called
`namesUnknownIdentifiers`, so the 2026-09-21 off-schema guard — which exists
to stop exactly this — ran on the LLM path only and never saw a cached row.
And `schemaLabel` had been stored on every history row since the run-context
work, so the column needed to prevent it was already populated and simply
never read.

**The fix, both halves.** The lookup is now
`…AndSchemaLabel(input, databaseType, activeSchema.active().label())`, so a
row written under another schema is not a hit. On top of that,
`servableCacheHit` re-runs `namesUnknownIdentifiers` over the row it found
and treats a failure as a miss — a label can be reused by a second upload of
the same filename, and the key alone would trust it. Rows written before the
change carry a null `schemaLabel` and stop matching, which is the right
default: a miss costs one LLM call, a false hit costs a wrong answer at 1.0.

### Schema upload — where the code lives

Added 2026-09-10. Everything below is new unless marked *modified*.

**The seam that made the schema swappable** — `backend/src/main/java/com/querysense/backend/schema/`

| File | Job |
|---|---|
| `SchemaSnapshot.java` | One schema, immutable: tables, FKs, catalog, row counts. `catalog` is null for built-in |
| `SchemaLookup.java` | The name/singular matching logic, lifted out of `KnownSchema` so it runs against any table list |
| `ActiveSchemaProvider.java` | Holds the live snapshot; notifies listeners on change |
| `KnownSchema.java` *(modified)* | Gains `builtIn()`; its two static methods delegate to `SchemaLookup`. **Constants untouched** |

**The upload pipeline** — `schema/ddl/` and `schema/upload/`

| File | Job |
|---|---|
| `ddl/SqlStatementSplitter.java` | Quote- and comment-aware statement splitting, so one bad statement in a dump doesn't cost the upload |
| `ddl/DdlSchemaParser.java` | JSqlParser → `ParsedSchema`. Reads `CREATE TABLE` and nothing else |
| `ddl/ParsedColumn/Table/Schema.java` | The parsed model |
| `upload/SchemaUploadValidator.java` | The gate before anything reaches MySQL |
| `upload/SchemaProvisioner.java` | `CREATE DATABASE`, drop, re-emit `CREATE TABLE`, add FKs. **Only ever writes to `querysense_uploads`** |
| `upload/ValueGenerator.java` | Type- and column-name-driven values, with deliberately skewed cardinality |
| `upload/SchemaSeeder.java` | Topological order, batched inserts, self-FK repair pass, `ANALYZE TABLE` |
| `upload/SchemaUploadService.java` | Orchestration; refuses under `h2` before touching anything |

**Reaching the uploaded catalog** — `analysis/ExplainExecutor.java`. Sets and
restores the catalog inside one `ConnectionCallback`. A no-op when built-in is
active. `MySqlExplainAnalyzer` and `SchemaMetadataService` both route through
it *(both modified)*.

**API** — `controller/SchemaController.java`, `dto/SchemaUploadRequest`,
`SchemaUploadResponse`, `SchemaTableView`, `exception/SchemaUploadException`,
plus a case in `GlobalExceptionHandler` *(modified)*.

**Frontend** — `api/schemaUpload.js`, `context/ActiveSchemaContext.jsx`,
`pages/SchemaSourcePage.jsx`, `components/schemaSource/`. Modified:
`contracts.js`, `index.js`, `mock/fixtures.js`, `App.jsx`, `NavRail.jsx`,
`EngineStatusBar.jsx`, `ExplainPlanView.jsx`, `SchemaAdvisorPage.jsx`,
`QueryForm.jsx`, `styles/base.css`.

**Tests** — 86 new, all plain JUnit + Mockito with a mocked `JdbcTemplate`;
none needs a running MySQL. The load-bearing one is
`ExplainExecutorTest.builtInSchemaNeverSwitchesCatalog`.

**Design and plan** — `docs/superpowers/specs/2026-09-10-schema-upload-design.md`
and `docs/superpowers/plans/2026-09-10-schema-upload.md`. The plan carries a
completion record listing the four things it got wrong.

#### Commit trail

Eleven commits on `main`, on top of the `c386cc1` monorepo snapshot. Each
builds and passes its suite on its own.

```
236f629  docs: design, implementation plan and risk register
2e27450  docs: retarget paths after the move to a single monorepo
6e4af20  feat(schema): swappable SchemaSnapshot behind ActiveSchemaProvider
1c447cb  refactor(schema): read the active schema by injection, not statically
8ec1124  feat(analysis): run EXPLAIN in the active schema's catalog
c72f122  feat(schema): parse uploaded DDL into a structural model
abc0bc8  feat(schema): validate, provision and seed an uploaded schema
17ea652  feat(api): POST /api/schema/upload, GET /active, POST /reset
8d2d3b2  fix(schema): seed 12000 rows, enable rewriteBatchedStatements
09fca74  feat(ui): schema source screen, provenance made visible
d225725  docs: record the feature and what measuring it changed
```

The risk register was committed **first**, before any implementation, which
is why the outcomes below can be read as a reckoning rather than a summary.

#### Still not verified

- **The multipart file-upload path.** Only the JSON/paste form was driven end
  to end. `SchemaControllerTest` covers the size cap and empty-file rejection
  as unit tests; no live multipart request was made.
- **R1's missing-privilege branch.** The dev account is `root`, so the
  `GRANT` message has never actually fired.
- **A real, large mysqldump.** The tables tested were hand-written. The
  parser's dump handling is covered against realistic fragments, not a
  genuine multi-hundred-table export.
- **Optimization, Schema advisor, History and Architecture screens** were not
  opened in the browser this session.

### Schema upload — how each risk actually landed

**Status: implemented and verified live 2026-09-10** against MySQL 9.7 on
3306, with the frontend exercised in a real browser. The risks below were
recorded *before* the code existed so each would be visibly closed or left
open afterwards rather than quietly forgotten. This is that reckoning.

The feature lets a user upload their own DDL, creates those tables in a
**separate MySQL database `querysense_uploads`**, and seeds them with
generated rows so `EXPLAIN` has real statistics to plan against. The
built-in nine-table schema stays untouched and remains the default.

| # | Risk | Outcome |
|---|---|---|
| R1 | Needs `CREATE DATABASE` privilege | **Handled.** `SchemaProvisioner.createDatabase` catches the failure and returns the exact statement to run (`GRANT ALL PRIVILEGES ON \`querysense_uploads\`.* TO <user>@<host>;`) as a 400, not a generic 500. Not exercised live — the dev account is `root`, which already has it. |
| R2 | Cannot work under the `h2` profile | **Closed.** `SchemaUploadService.requireMySqlProfile` refuses *before* parsing, so nothing is provisioned and the active schema is untouched. `SchemaUploadServiceTest.refusesUnderH2` asserts both. |
| R3 | Upload is destructive to the previous upload | **Accepted, and stated in the UI before the fact.** The schema-source screen reads "Uploading replaces "<name>" — that upload's tables are dropped. The built-in demo schema is never touched." `SchemaProvisionerTest.touchesOnlyTheUploadCatalog` asserts no emitted statement names the connected database. |
| R4 | `SchemaMetadataService` caches never evict | **Closed.** `ActiveSchemaProvider.publish` fires listeners; `SchemaMetadataService` registers `clearCaches` in `@PostConstruct`. Listener-based rather than a direct call because that class already depends on the provider — the reverse edge would be a cycle. |
| R5 | Catalog switching can poison the connection pool | **Closed, and asserted rather than argued.** `ExplainExecutor` sets and restores the catalog inside one `ConnectionCallback`, in a `finally`. `ExplainExecutorTest.builtInSchemaNeverSwitchesCatalog` proves `setCatalog` is never called on the demo path, and `restoresCatalogOnException` proves the restore survives a throw. All 22 pre-existing `MySqlExplainAnalyzerTest` assertions still pass unmodified. |
| R6 | Seeded statistics are generated, not real | **Open by nature, and labelled everywhere it shows.** Seeding fixes the magnitude; the distribution is still invented. The status bar reads "generated data" on every screen, and the analysis view carries "the access paths and index choices are real, the row counts describe synthetic data". Cardinality is deliberately skewed (a `status`-like column ~5 distinct values, an `email`-like one one per row) with ~10% NULLs, so `filtered` varies instead of being uniformly 100.00. |
| R7 | `KnownSchemaTest` pins the built-in schema | **Held.** `KnownSchema`'s nine table constants and `FOREIGN_KEYS` are byte-identical — confirmed by diff. Only additive changes: `builtIn()`, and two methods delegating to `SchemaLookup`. `KnownSchemaTest` is unedited and still passes. |
| R8 | Uploaded DDL is user-supplied SQL | **Closed.** Only `CREATE TABLE` is honoured, and every statement is re-emitted from the parsed JSqlParser model — no uploaded string reaches MySQL as written. Identifiers are constrained to `[A-Za-z0-9_]{1,64}`, stricter than MySQL's own rule, because identifiers cannot be JDBC parameters. |
| R9 | Hibernate `ddl-auto=update` vs. the second database | **Answered, not assumed.** After a live upload, `information_schema` showed `querysense` holding all nine demo tables **plus `query_history`**, and `querysense_uploads` holding only `clients` and `invoices`. Hibernate does not touch the upload catalog. |
| R10 | Two schemas, one UI | **Closed.** The active schema is shown permanently in `EngineStatusBar` beside the engine indicator, in the project's existing `--data` purple. Verified in a browser: it persists across navigation and updates the moment an upload lands. |

#### Two things measurement changed, that the design got wrong

Both were caught by running the thing, not by reading it.

1. **The seed row count was wrong at 500.** The analysis modules each carry
   their own row-count threshold — `TRANSACTIONAL` 100, `ECOMMERCE` 1000,
   `ResourceConsciousService` 5000, `ANALYTICAL` 10000, all exclusive — and
   below one, a full scan is reported as *"the estimated row count is low, so
   the performance impact is likely minor"*: the finding without the
   consequence. At 500 rows the same query returned `CRITICAL` under
   `TRANSACTIONAL` and "impact is likely minor" under `ECOMMERCE`. The
   default is now **12000**, which clears all four; every profile returns a
   `CRITICAL` domain finding plus a `RESOURCE` warning.

2. **12000 rows was unaffordable until a driver setting changed.** Seeding
   24,000 rows across two tables took **24.6s**, because the MySQL driver
   sends one `INSERT` per row even inside a JDBC batch. A ten-table schema at
   that rate would have exceeded the frontend's 65s axios timeout and failed
   in the browser while the backend carried on working. Adding
   `rewriteBatchedStatements=true` to the datasource URL took the same upload
   to **1.17s** — a 21x difference, and 12000 rows now costs less than 500
   did before.

**What this feature does *not* change:** `MySqlExplainAnalyzer` never read
`KnownSchema` and still does not. Routing layers, confidence tiers and
history writing are untouched. The `order_items` mis-resolution defect above
is **not** fixed — a dynamic schema runs through the same matcher, so it
follows the uploaded schema rather than being cured by it. Of the LLM
hallucination defect's three fix directions, **#1 is now done** (an unmatched
question is grounded on every table rather than none); #2 (a
schema-conformance check before a result is returned or cached) and #3
(MongoDB grounding) remain open.

### Frontend housekeeping

- ~~**Mock mode exists and fails toward mock.**~~ **FIXED 2026-09-21.** It was
  `VITE_USE_MOCK !== 'false'`, so an unset or misspelled value silently ran the
  whole app on `src/mock/fixtures.js` — the app looked healthy with the backend
  stopped. Mock mode is now opt-in: `resolveUseMock` returns true only for the
  literal `true` or `1`, trimmed and case-insensitive. A typo now costs you the
  fixtures, never the truth. Seven cases in `src/api/client.test.js`.
- ~~**The comparison screen is always simulated.**~~ **FIXED 2026-09-21** — it
  calls `POST /api/query/compare`. See §5 Missing endpoints.
- ~~**No backend health check.**~~ **FIXED 2026-09-21.** `useBackendHealth`
  polls `GET /test` every 20s and `BackendHealthBanner` renders one shell-wide
  notice naming the base URL, saying the screens are empty rather than stale,
  with a Retry. Mock mode gets its own amber banner, since that is not a
  failure. The banner stays hidden before the first probe returns, so a healthy
  load never flashes it.
- **The Architecture screen was removed 2026-09-11.** It was a pipeline diagram
  for reviewers, not something an end user needs. Its nav item, the `/about`
  route, `pages/AboutPage.jsx` and `components/architecture/` are gone.
  ~~`/about` now matches no route and renders an empty shell.~~ **FIXED
  2026-09-21** — a `path="*"` route renders `NotFoundPage`, which names the
  path asked for and says the screen it pointed at was removed.
- ~~3 oxlint warnings are outstanding~~ **FIXED 2026-09-21. `npm run lint` is
  now completely clean.** All three were `only-export-components` on the
  context files, which exported both a provider component and a hook. Each
  context is now split: `context/<name>.js` holds the context object and the
  `use*` hook, `context/<Name>Context.jsx` holds only the provider component.
  Consumers import the hook from the `.js`; only `App.jsx` imports the
  providers. 16 import sites were repointed.

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
- **Row counts on an uploaded schema are generated, not measured.** The plan
  around them is real MySQL output — the access path, the index choice, the
  join order — but the rows those numbers count were produced by
  `SchemaSeeder`. The status bar says "generated data" whenever an upload is
  active and the analysis screen repeats it; if you are quoting a figure from
  a demo, check which schema was live first.
- **Restarting the backend reverts the active schema to the built-in one.**
  `ActiveSchemaProvider` holds the live `SchemaSnapshot` in a `volatile`
  field initialised to `KnownSchema.builtIn()`; nothing persists which
  upload was active. The uploaded tables survive in
  `querysense_uploads` — MySQL still has them — but the app stops reading
  them, so after any restart you must **re-upload** to get back to the
  schema you were working against. The status bar is the fastest check: it
  reads "Built-in demo schema" again. Observed the hard way on 2026-09-11,
  when a backend restart mid-session silently moved every subsequent query
  onto the demo tables while the questions still named the uploaded ones.
- **Uploading a schema drops the previous upload**, and only ever touches
  `querysense_uploads`. The connected `querysense` database — the nine demo
  tables and `query_history` — is never a target of any DDL this feature
  emits. "Reset to built-in" needs no cleanup: the uploaded tables stay where
  they are and simply stop being what anything reads.
- **Schema upload refuses under the `h2` profile**, before provisioning
  anything. That is deliberate: H2 has no equivalent catalog semantics, so a
  half-succeeding upload would leave the app pointing at tables that do not
  exist — the same shape of confusion as the `explainFailed: true` trap two
  bullets up.
- **The Optimization screen's two halves answer different questions**, and
  since 2026-09-21 both are backed by real endpoints. The upper half changes
  the *database* (`/api/query/schema-advisor`); the lower changes the *query*
  (`/api/query/compare`). A row count in the lower half is an `EXPLAIN`
  estimate over a query that does not exist in your history — so say which
  half a figure came from before quoting it.
- **An advisor recommendation is not always runnable.** When `EXPLAIN`
  reports a full scan but no filterable column, `SchemaAdvisorServiceImpl`
  falls back to a placeholder — `CREATE INDEX idx_orders_col ON
  orders(<the column used in the WHERE clause>);` — rather than emit
  `idx_orders_null`. `buildApplyScript` demotes any statement containing a
  `<…>` placeholder, and every `STRUCTURAL` recommendation (whose action is
  English prose, not DDL), into commented review notes, so the "Apply all"
  block is always safe to paste whole. The per-recommendation card above it
  still shows the raw text with its own Copy button, exactly as the Schema
  advisor screen does.
- **`domainProfile: domainProfile must not be null` on Run analysis** means
  the backend was started before 2026-09-11, when the field was removed
  (§9.7). The source is correct; the running JVM is not. Devtools did not
  reload it. Stop the backend and start it with `./mvnw clean
  spring-boot:run` — `clean` also clears the compiled copies of the deleted
  rule classes still sitting in `target/`. Hit live on 2026-09-11.
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
`frontend.local-backup/`, untracked by git) and its 40-check adapter
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

### Session of 2026-09-10 — schema upload, and the move to one repository

Run against the live backend on MySQL 9.7 (port 3306) with Ollama up, and —
for the first time in this project — **with the frontend exercised in a real
browser against that backend**. See the "Not verified" note below, which this
session partially retires.

#### Repository consolidation

The two separate repositories were folded into the `querysensellama`
monorepo, which already held a snapshot of both. `CAPSTONE CODE/` →
`backend/`, `CAPSTONE FRONTEND/` → `frontend/`; the old per-repo `.git`
directories are preserved locally as `.git.disabled` and ignored. Working
tree matched the snapshot commit exactly at the point of the move — 0 files
differing under `backend/` or `frontend/`, 144 tracked files — so nothing was
lost or reconciled. Backend suite verified green immediately after the
rename: **44 tests, 0 failures**.

#### Backend

- `./mvnw test` — **130 tests, 0 failures**, BUILD SUCCESS. 44 pre-existing,
  86 new. Every new test is a plain JUnit + Mockito unit test with a mocked
  `JdbcTemplate`; none needs a running MySQL.
- `KnownSchemaTest` unedited and still green; `KnownSchema`'s nine table
  constants confirmed byte-identical by diff.
- All 22 pre-existing `MySqlExplainAnalyzerTest` assertions still pass with
  no edits to them, which is the concrete evidence the EXPLAIN path is
  unchanged.

#### The upload path, end to end

| Step | Result |
|---|---|
| `GET /api/schema/active` before upload | `builtIn: true`, `catalog: null`, 9 tables |
| Baseline `/analyze` on `customers` | `explainFailed: false`, `ALL`, **15 rows** — matches this file's own figures |
| `POST /api/schema/upload` (2 tables, JSON form) | 2 tables provisioned, **12000 rows each**, FK detected, no warnings |
| `/generate` "Show all clients" | `SELECT * FROM clients;` — `RULE_ENGINE`, 0.95 |
| **`/analyze` on an uploaded table** | **`explainFailed: false`, `ALL`, 11953 rows** |
| `/schema-advisor` on the same query | `CREATE INDEX idx_clients_city ON clients(city);` |
| JOIN across two uploaded tables | both tables in the plan — `c`: `ALL`, 11791 rows; `i`: `ref` on `fk_invoices_0`, 1 row |
| `POST /api/schema/reset` | `builtIn: true`, `catalog: null`, 9 tables |
| `/analyze` on `customers` after reset | `explainFailed: false`, `ALL`, **15 rows** — identical to the baseline |

**The contrast that matters** is the one this file already recorded for the
empty-vs-populated case: an uploaded table reports **11953 rows** where the
built-in `customers` reports **15**, and where an *unseeded* table of the
same shape would report `rows=1, filtered=100.00`. Structure alone gets
EXPLAIN to run; seeding is what gets it to mean something.

Suggestions at 12000 rows, all three domain profiles on the same query:
every one returns a `CRITICAL` domain finding **plus** a `RESOURCE` warning.
None says "the estimated row count is low". At 500 rows — the original
default — only `TRANSACTIONAL` did. See §5 for the thresholds that forced
the change and the driver setting that made it affordable.

#### Frontend, in a browser

`npm run build` clean. `npm run lint` — 0 errors, **4 warnings**: the 3
pre-existing ones plus one `react-refresh` on the new `ActiveSchemaContext`,
matching the two context files that already carry it.

Exercised at `localhost:5173` against the live backend, `VITE_USE_MOCK=false`:

- `/schema-source` renders all nine built-in tables with columns and FK
  arrows, and shows "connected database" for the null catalog.
- Pasting DDL and pressing "Use this schema" provisioned both tables and the
  preview showed **12,000 rows** each.
- The status bar switched to **"Pasted schema · 2 tables · generated data"**
  and persisted across every navigation.
- The replace warning appeared once an upload was active, naming it.
- Workbench placeholder followed the schema: `show customers from Mumbai` →
  **`show all clients`**.
- "show clients in Mumbai" → `SELECT * FROM clients WHERE city = 'mumbai';`
  at `RULE_ENGINE` 0.95. **This exercises the live known-value safety net
  against uploaded data** — `city` resolved and `Mumbai` matched real seeded
  values, which is only possible because the seeder generates
  name-appropriate data rather than random strings.
- Analysis showed **11933 rows**, `ALL`, a `CRITICAL` domain suggestion
  quoting that count, and the provenance note: *"the access paths and index
  choices are real, the row counts describe synthetic data."*
- "Back to the built-in schema" returned the bar to "Built-in demo schema"
  and the preview to the nine tables.
- No console errors or CORS failures.

#### R9, answered rather than assumed

After a live upload, `information_schema` reported `querysense` holding all
nine demo tables **plus `query_history`**, and `querysense_uploads` holding
only `clients` and `invoices`. Hibernate's `ddl-auto=update` does not touch
the upload catalog.

#### Still not verified

- **The multipart file-upload form.** Only the JSON/paste path was exercised
  end to end; the file picker was not driven with a real `.sql` file.
  `SchemaControllerTest` covers its size and empty-file rejections as unit
  tests, but no live multipart request was made.
- **R1's missing-`CREATE`-privilege path.** The dev account is `root`, so the
  error branch and its `GRANT` message have never actually fired.
- **A large or messy real-world dump.** The tables used were hand-written.
  The parser's mysqldump handling is covered by unit tests against realistic
  fragments, not by a genuine multi-hundred-table export.
- **MongoDB is unaffected and untested here**, as ever.

### Session of 2026-09-10 — the Optimization screen gets a real half

Run against the live backend on MySQL 9.7 (port 3306), built-in demo schema
active, and driven in a real browser with `VITE_USE_MOCK=false`.

#### What the screen did before

`OptimizationPage` read only `{ build, analysis, domain }` from
`WorkbenchContext`. It never read `schemaAdvice` — **even though that value
was already in the same context**, populated by the Schema advisor screen,
whose own button navigates straight here. Every figure on the page came from
`mockSimulateOptimizedQuery()` (a regex rewrite in the browser) and
`comparison.js` (no endpoint, generated in all modes).

The one mention of the advisor anywhere on the screen was a **fabricated
trace line** in `fixtures.js`:

```js
{ ruleId: 'SCHEMA-01', explanation: 'Schema Advisor recommended an index on the filtered column.' },
```

It rendered whether or not the advisor had run and whether or not it had
recommended anything. The two `EXPLAIN-0n` lines bracketing it asserted a
full scan and a post-index lookup just as unconditionally. All three are
gone; `EXPLAIN-01` is now gated on `originalAnalysis.hasFullTableScan` and
prints the real `accessType`.

#### What replaced it

Two halves, divided and separately labelled — real advisor output above, the
simulated rewrite below its amber badge. The real half renders on arrival
with no button, because gating real output behind one labelled "Simulate" was
the confusion being removed.

`buildApplyScript` (`src/lib/applyScript.js`) aggregates the recommendations
into one pasteable block. **The risk it exists for is real, not
hypothetical** — it was observed in a live response during this session:

| Verified | Query | Advisor returned | Script emitted |
|---|---|---|---|
| Rule engine → analyse → optimization, **skipping the advisor screen** | `SELECT * FROM customers WHERE city = 'mumbai';` | 1 × `INDEX` on `customers.city` | `1 runnable` — `CREATE INDEX idx_customers_city ON customers(city);` |
| Same path | `SELECT * FROM customers;` | 1 × `INDEX`, `targetColumn: null` | `0 runnable · 1 for review` — the `<the column used in the WHERE clause>` statement demoted to a comment |
| Direct API, `ORDER BY` + `GROUP BY` on `orders` | 2 × `INDEX` (one with a placeholder) + 1 × `STRUCTURAL` | `1 runnable · 2 for review` — only `idx_orders_status` executable |

In the second and third cases the emitted script contains **no executable
line at all** beyond the real statement: asserted directly against the live
response, not against a fixture. The first case also confirms the auto-fetch
path — the advisor had never been visited in that session, and the page
fetched once and cached to context.

Console clean on load and after both interactions: no errors, no React
warnings, no effect loop. `npm run lint` unchanged at 4 warnings (3
react-refresh, 1 unused parameter — all pre-existing); `npm run build` clean.

#### Vitest was added, and this is the frontend's first test file

`vitest` is now a devDependency with `npm test` / `npm run test:watch`.
`applyScript.test.js` is 10 cases, written before the implementation and red
first. It is the **only** tested file in the frontend — the rest of the
directory is still verified by build, lint and hand.

#### Not fixed here, and worth knowing

- `RecommendationList` renders each raw `suggestedAction` with its own Copy
  button, placeholder statements included. Safe on the Schema advisor screen,
  which only diagnoses; slightly sharper now that the same component appears
  on a screen framed as "run this". The aggregated block is guarded; the
  individual cards are not. Left alone because the component is shared.
- `NORMALIZATION` remains a declared type that nothing emits — it exists in
  the DTO comment, in `contracts.js` and in `RecommendationList`'s
  `TYPE_META`, but no rule in `SchemaAdvisorServiceImpl` produces one.
- The advisor still reads the collapsed `QueryAnalysisResult`, not
  `tables[]`, so on a join it advises against whichever table the collapse
  kept. Unchanged by this work; see §5.3.

### Session of 2026-09-10 — the seeder was emptying narrow-integer tables

Found while answering "what am I actually gaining from the Analysis window",
which turned out to have the answer "less than you think, because the table
you are analysing is empty."

#### Root cause

`ValueGenerator.integerValue` ended at `1 + random.nextInt(1000)` and
`valueFor` routed all six integer widths into it:

```java
case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT" -> integerValue(column, rowIndex);
```

A `TINYINT` holds 127, so roughly 87% of generated values were out of
range. MySQL rejects the whole batch on the first bad row, `SchemaSeeder`
caught the `DataAccessException`, logged `WARN` and carried on, and **every
batch died**. The upload returned HTTP 200 with `rowCount: 0` and
`warnings: []`.

Observed on a real upload (`sample1.sql`, one table `user_details` with a
`status TINYINT`): `SELECT COUNT(*)` returned **0**, while `/api/schema/active`
reported the schema as live and every downstream module happily analysed it.
`EXPLAIN` gave `estimatedRows: 1`, so every domain suggestion came back
`INFO` with "the estimated row count is low, so the performance impact is
likely minor" — a screen full of output describing nothing.

This is precisely the failure `SchemaSeeder`'s own class comment says the
component exists to prevent: *"output that looks fine and means nothing —
the worst failure shape this project has, because nothing on screen would
say so."*

#### Three fixes

1. **Width-aware clamping.** `ValueGenerator.maxFor` gives the signed
   maximum per width and every branch of `integerValue` now folds its value
   back into range — including the name heuristics, since a column called
   `year` returned 2015..2025 and overflowed a TINYINT independently of the
   default branch. Signed maxima are the real bound, not a conservative
   one: `ParsedColumn` carries no `UNSIGNED` flag, so `SchemaProvisioner`
   re-emits these columns signed.
2. **Row-count capping for keys.** A primary key, unique or auto-increment
   column gets `rowIndex + 1` and cannot be clamped without breaking
   uniqueness — 12000 distinct ids do not fit in a TINYINT. So a narrow key
   shrinks the *table* instead: `SchemaSeeder.seedableRowsFor` takes the
   minimum over every such column, and the shortfall is warned about.
   Foreign-key picks are clamped the same way, since a `TINYINT` FK would
   otherwise reject every batch of the child table.
3. **Short seeds are now loud.** `SchemaSeeder.seed` returns a
   `SeedReport(rowCounts, warnings)` rather than a bare map, carrying the
   innermost driver message — the one that names the offending column.
   `SchemaUploadService` appends these to the parser's own warnings rather
   than replacing them, so `SchemaUploadResponse.warnings[]` (already
   rendered by the UI) shows both. A 0-row seed stays HTTP 200: the tables
   exist, the rule engine binds to them and `EXPLAIN` runs, so failing the
   whole upload would discard valid work. It just may never again be silent.

#### Verified live

Re-uploaded an identical schema against MySQL 9.7:

| | Before | After |
|---|---|---|
| `SELECT COUNT(*) FROM user_details` | **0** | **12000** |
| `status` values | all rejected | 1..127, 127 distinct |
| `estimatedRows` on a filtered query | 1 | ~12200 |

And the effect on the screens this was blocking, same query and profile,
`WHERE gender='M' ORDER BY status`:

| | Before | After |
|---|---|---|
| Full-scan suggestion | `INFO` — "row count is low… impact likely minor" | **`CRITICAL`** — "over an estimated 12225 rows" |
| Filesort suggestion | `WARNING` | `WARNING` |
| Resource suggestions | **none at all** | 1 at MEDIUM, **2 at LOW** |

**That last row is the significant one.** `ResourceConsciousService` gates
its own messages on `estimatedRows > 5000` (`ResourceConsciousService.java:42`).
The built-in demo tables hold 5-49 rows and every upload was landing empty,
so on this project's data the Resource-Conscious Decision System had — as
far as anything here can tell — **never emitted a suggestion in its life**.
It was visible only as the severity shift it applies to the domain module's
output. It now writes genuinely different text per level:

- **LOW** — "likely to spill to disk", plus a second suggestion about scan
  pressure that the other levels do not produce
- **MEDIUM** — "usually fine, but worth monitoring"
- **HIGH** — "unlikely to cause a disk-based sort"

If that module is being presented as one of the project's objectives, it
could not have been demonstrated before this fix.

Suite: **143 tests, 0 failures**, including 8 new ones. Two of the new
seeder tests were written vacuously at first — `SchemaSeederTest` builds its
seeder with `seedRows = 50`, so a TINYINT cap of 127 never binds and the
assertion passed without testing anything. They now construct a seeder at
the production 12000 and assert the cap actually falls to 127.

#### Then the default came down to 100, deliberately

Immediately after the above was verified, `seed-rows` was lowered from
**12000 to 100** at the user's direction: *"I dont want a sample seeded
database to have 100000 rows."* That is a reasonable thing to want from
something labelled a sample, and it is a real trade, so both halves are
recorded here.

**What it costs.** None of the four thresholds clear at 100. The table
above — `INFO` becoming `CRITICAL`, the Resource module speaking for the
first time — describes behaviour at 12000 and **does not describe the
default any more**. At 100 the modules are back to "impact is likely minor"
and `ResourceConsciousService` is silent again.

**What it does not cost.** The seeding bug itself is still fixed, and that
was the real defect: tables now actually receive rows instead of silently
receiving none, narrow integer columns no longer reject every batch, and a
short seed is reported rather than swallowed. A 100-row table is a small
table; a 0-row table was a broken one that looked healthy. Those are
different problems and only the second was a bug.

**The setting is one flag, not a code change:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--querysense.schema.upload.seed-rows=12000
```

Re-upload the schema afterwards — existing tables keep whatever they were
seeded with.

#### Still open

- **The built-in demo schema is untouched by this.** It holds 5-49 rows by
  design and still produces "row count is low" on every suggestion.
- **At the 100-row default, an upload now behaves the same way**, so the
  advice "upload a schema to see the modules work" is only true with the
  property raised. §2 says so in place.
- No cost figure. Reviewed as item 3 in §8 and still not built.

### Session of 2026-09-10 — plain language on the schema advisor

Prompted by reading the copied script and asking what it actually meant.
The advisor spoke one register only, and it was the wrong one to lead with.

#### A teammate's name was in user-facing output

`SchemaAdvisorServiceImpl` ended every full-scan recommendation with:

> — this is the same signal **Anant's engine** flags via EXPLAIN.

Not a comment. A string, in the `reason` field, rendered on the Schema
advisor screen, on the Optimization screen, and inside the copied script.
It shipped on **every** full-scan recommendation the system produced. A
repo-wide check found it was the only teammate name inside a string
literal — the others are all in Javadoc, where they belong.

It survived because `SchemaAdvisorServiceImpl` had **no tests at all**:
five rules, zero coverage, so nothing ever read those strings back.

#### `summary` alongside `reason`, not instead of it

`SchemaRecommendation` gained a `summary` field carrying the finding in
plain language. `reason` is unchanged and still names the exact EXPLAIN
signal.

**Both registers, deliberately.** `reason` is what makes a recommendation
auditable rather than an assertion — the project's whole argument for a
rule-based system over a black box (§2, and Papers #2/#4/#6 in CLAUDE.md).
Rewriting it for readability would have deleted the evidence. So the UI
leads with `summary` and keeps `reason` behind a "Technical detail"
disclosure, and both travel in the payload.

Sample, for the same finding:

| Register | Text |
|---|---|
| `summary` | "To answer this, the database is reading all 100 rows of user_details and checking each one, because nothing indexes status. An index works like the index at the back of a book: it lets the database jump straight to the matching rows instead of reading everything." |
| `reason` | "Full table scan detected (accessType=ALL) with no index in use on 'status' — the same signal reported by EXPLAIN." |

Summaries say "the database", not "MySQL", because the same class serves
the MongoDB rules. Row counts come from `estimatedRows`, which the advisor
received all along and never read; where it is null (Mongo, or a failed
EXPLAIN) the wording degrades to "every row" rather than leaving a gap.

#### The copied script switched register too

`buildApplyScript` now comments each statement with `summary`, not
`reason`. The script travels — into a ticket, a chat, a teammate's
terminal — where the reader has none of the UI's context and no
disclosure to open. The evidence claim is still in the script header
("Anchored to EXPLAIN over …"), so the per-statement comment is free to be
the readable one. It falls back to `reason` when `summary` is absent.

#### Tests, including two on the writing

New `SchemaAdvisorServiceImplTest`: 12 cases covering all five firing
conditions, both suppression cases, and the null-row-count fallback. Two
of them test the **copy** rather than the logic, because prose drifts back
toward the vocabulary of the code around it:

- `jargonFreeSummaries` — no summary may contain `accessType`, `EXPLAIN`,
  `filesort`, `$regex`, `GROUP BY`, `DISTINCT`, `querysense_uploads`, or a
  teammate name.
- `noTeammateNamesAnywhere` — across `summary`, `reason` and
  `suggestedAction` together, so the original defect cannot return through
  any field.

Backend **155 tests, 0 failures**. Frontend **12**, lint unchanged at 4
pre-existing warnings, build clean. **Not run in a browser or against a
live backend** — at the user's instruction, they test personally.

#### Deliberately not done

Scope was the schema advisor only. The Analysis screen's domain and
resource suggestions (`OptimizationSuggestion.message`) are written in the
same technical register and have the same problem — there are more of
them, across three domain rule sets plus the resource module. Considered
and deferred, not overlooked.

### Session of 2026-09-10 — the Analysis screen gets an answer, not just a report

The complaint that started it: the Analysis screen "is not giving any useful
information for the layman. No metrics." Both halves were true, and they had
different causes.

**Why there were no metrics.** The analyzer only ever runs `EXPLAIN` and
never executes the query, so nothing on the screen was ever a measurement —
that part is by design and stays. But three figures that `EXPLAIN` *does*
report were being discarded:

- **`filtered`** — MySQL's estimate of the percentage of read rows that
  survive the `WHERE` clause — was never read by `EXPLAIN_ROW_MAPPER`, which
  took `table, type, possible_keys, key, key_len, ref, rows, Extra` and
  stopped. It is the only column that distinguishes a scan doing useful work
  from one throwing its work away.
- **`query_cost`** was reachable but unread — see §8 item 3 for the full
  account of how it is now obtained without flipping the EXPLAIN format.
- **`possible_keys`** was captured and never rendered anywhere in the UI.

**Why it read as useless to a layman.** The screen opened on MySQL's
vocabulary with nothing ranking it, and its one number — estimated rows —
had no denominator. Adding `filtered` supplies the denominator; the verdict
supplies the ranking.

**What was built.**

| Piece | Where |
|---|---|
| `AnalysisVerdict` DTO — severity, label, headline, detail | `dto/` |
| `VerdictService` — the nine-rule ladder | `analysis/` |
| `filtered` on `TableAnalysis`, plus derived `estimatedRowsReturned` / `readAmplification` | `dto/` |
| `queryCost` + `verdict` on `QueryAnalysisResult` | `dto/` |
| `parseQueryCost` + the separate cost probe | `MySqlJsonExplainParser`, `MySqlExplainAnalyzer` |
| `VerdictBanner`, `PlanMetrics`, `explainGlossary.js` | frontend |

**The two rules that keep the verdict defensible.** A naive implementation
grades every full table scan as a fault, and a reviewer breaks that in thirty
seconds. Rule 7 catches a big scan that *returns* most of what it reads —
`SELECT * FROM customers` — and grades it `INFO`, because an index cannot
improve a query that genuinely needs the whole table. Rule 8 does the same
for any scan under `querysense.verdict.large-table-rows` (default 1000).
Splitting on `filtered` is what makes rule 7 possible at all, which is the
practical argument for having read that column.

The wording follows the rule already established in
`MySqlExplainAnalyzer.addWarningsFor`: report what MySQL *chose*, never
assert that an index would fix it. Two tests in `VerdictServiceTest` are
regression guards on the writing rather than the logic — one asserts no
headline contains "add an index" / "create index" / "you should", the other
that no headline leaks `access type` / `filesort` / `eq_ref` / `possible_keys`.
That mirrors `SchemaAdvisorServiceImplTest.jargonFreeSummaries`, and exists
for the same reason: copy drifts back toward the vocabulary of the code
around it.

**`seed-rows` changed from 100 to 12000.** Not cosmetic, and reversible in
one word. At 100 rows every table is below the verdict's large-table
threshold *and* below all four domain/resource thresholds, so a fresh install
was configured to produce "reads all 100 rows, which is normal at this size"
for effectively every query — correct, and useless as a demonstration. The
paragraphs recording why 100 was originally chosen are kept in
`application.properties` rather than deleted.

**Verified.** `mvnw test` — full suite green, including 18 new
`VerdictServiceTest` cases covering every rung of the ladder, the
configurable threshold, the near-zero `filtered` cap, and join table
selection. `vite build` — clean, 560 modules.

**Not verified: any of this against the running app.** No query was analyzed
end to end this session; the browser has not rendered the banner, the metric
tiles or the glosses, and no real MySQL `EXPLAIN` has exercised the `filtered`
column or the cost probe against a live server. The evidence above is unit
tests and a production build, nothing more.

### Session of 2026-09-11 — the rule engine follows the schema, and joins

The complaint that started it: uploading a schema still sent everything to the
LLM, and *"there is no novelty in always falling back to the LLM."* Both halves
were accurate. The reason was not the one this file implied.

Design: `docs/superpowers/specs/2026-09-10-schema-adaptive-rule-engine-design.md`.
Plan: `docs/superpowers/plans/2026-09-10-schema-adaptive-rule-engine.md`.

#### Why an upload did not change routing

`RuleBasedQueryBuilder` read `ActiveSchemaProvider`, so **table and column
names** followed an upload. Three things did not, and the third was two bugs
nothing in this file had recorded:

1. **The decline gate was a hardcoded English blacklist** — `who`, `which`,
   `average`, `more than`, month names. Schema-independent by construction, so
   an upload could not affect it.
2. **Two tables mentioned was an automatic decline**, because there was no
   join builder to hand off to.
3. **Both data-driven paths were pinned to the demo schema.** The location
   branch emitted a literal `city = …` regardless of what columns existed, and
   the safety net called `getDistinctValues("customers", "city")` — on an
   uploaded schema `SchemaMetadataService` logged *"Refused to load distinct
   values for unrecognized table/column"* and returned empty, so **the net was
   silently inert on every upload**. This file's own §7 records *"show clients
   in Mumbai"* as evidence of adaptation; it worked because that sample
   happened to have a `city` column.

The engine adapted its nouns, kept someone else's grammar, and turned off its
own seatbelt.

#### What replaced it

Binding-based decline. Every noun is resolved against the active schema, and
the engine gives up when a binding fails or ties — so the decision now depends
on the schema that is loaded. §2 carries the full list.

| Piece | Job |
|---|---|
| `querybuilder/SchemaBinder` | Nouns to tables/columns. Most-specific wins; underscores and spaces normalise to one form |
| `querybuilder/JoinPathResolver` | A table pair to its declared FK. 0 or 2+ edges decline |
| `querybuilder/QueryIntent` | The schema-bound intent — nothing in it is a raw user string |
| `querybuilder/SqlEmitter` | Intent to SQL. Knows no English |
| `querybuilder/QueryIntentParser` | The binders, and every decline reason |
| `querybuilder/RuleBuildResult` · `dto/RuleTrace` | Both outcomes, with the reason |
| `components/analysis/RuleTracePanel` | Renders it, on the Analysis screen only |

`RuleBasedQueryBuilder` is now a 60-line orchestrator. `tryBuild` was kept as
a delegate over the new `build`, so `RuleBasedQueryBuilderSchemaTest` keeps
its four assertions byte-identical.

#### The novelty claim, and why it is checkable

`SchemaSnapshot` has carried `foreignKeys()` since the schema-upload work and
the generator never read it. A rule-built join's `ON` clause is now **read from
the declared foreign key**, and `RuleTrace.JoinEdge.origin` says so in those
words next to the exact columns — so a reviewer confirms it against their own
DDL rather than taking it on trust. Two FKs between the same pair decline as
ambiguous rather than picking one.

**The routing decision itself is now auditable.** `handledBy` has always said
which layer answered and never why the previous one passed. A decline carries
its reason onto the response, so an LLM-answered query shows *why* the rules
declined. That is the half of this project's central claim that was previously
not demonstrable.

#### A regression this session introduced and then fixed

The first version of the parser dropped the previous engine's **weak
projection signal** — the `<table noun> <column>` compound with no
`only`/`just`/`of` to announce it ("customer city", "order amounts"). The old
`detectColumns` had it, documented as opportunistic; the rewrite kept only the
strong signals, so `customer name` silently returned `SELECT * FROM customers;`
at `RULE_ENGINE` 0.95. Found by the user against a live upload, not by any
test.

Restored with two changes rather than one, because the original behaviour was
not quite right either:

- It now binds a **multi-word** tail, so `customer first name` resolves through
  underscore normalisation to `first_name`.
- An unresolvable tail **declines** instead of degrading to `SELECT *`. The
  live schema that exposed this (`movie_rentals.sql`) has `first_name` and
  `last_name` and no `name`, so `customer name` matches two columns. Picking
  one is a confident wrong answer; `SELECT *` is a silently incomplete one.
  Neither belongs at 0.95.

Three gates keep it from firing on ordinary phrasing — the tail must run to the
end of the question, be at most three words, and contain no preposition, copula,
clause word or table name. That is what keeps `customers in pune` a filter
rather than a decline, which was the previous engine's own stated worry about
this branch.

#### Four defects closed

- The `order_items` mis-resolution (§5) — `SchemaBinderTest.orderItemsBeatsOrders`.
- The dropped `per <column>` grouping (§8).
- The hardcoded `city` filter column — **not previously recorded**.
- The inert known-value safety net on uploaded schemas — **not previously
  recorded**.

#### Verified

- `./mvnw test` — **233 tests, 0 failures** (174 before, all green at the
  start of the session).
- `npm test` — **16 tests, 0 failures**; `RuleTracePanel` is the frontend's
  second tested file after `applyScript`.
- `npm run build` clean; `npm run lint` unchanged at the 4 pre-existing warnings.
- `RuleBasedQueryBuilderSchemaTest` green with all four assertions unedited —
  only its `setUp` wiring changed, because the builder takes its collaborators
  now instead of constructing them.
- `DeclineBoundaryTest` — 17 cases pinning what must still decline. It is the
  only thing holding the line against the failure §5 names as the one this
  project's claim cannot survive, and it exists because everything else in this
  session widened what the engine claims.

#### What was verified live, and what still is not

The section that stood here said nothing had been run against a live backend.
That was true when it was written and stopped being true when the user reported
the `SELECT *` regression — reproducing it required the running app. Corrected
rather than left standing, since a stale verification claim is the one thing
this record cannot afford.

**Verified live** against the app on `localhost:8080`, MySQL 9.7 on 3306,
Ollama up — through `POST /api/query/generate` with `curl`, not a browser:

| Input | Result |
|---|---|
| `customer name` (built-in schema) | `SELECT name FROM customers;` · `RULE_ENGINE` 0.95 |
| `customer city` | `SELECT city FROM customers;` · `RULE_ENGINE` 0.95 |
| `customer joined date` (no such column) | declined, handed to `OLLAMA` |
| `show only customer name` on an upload with no `name` column | declined with the reason, then answered from cache — see below |

The `ruleTrace` field was present and correctly populated on every one of
these, in both `BUILT` and `DECLINED` form.

**Still not verified:**

- **No browser has rendered `RuleTracePanel`.** Every live check above was
  `curl` against the API. The component is covered by four vitest cases and
  nothing else.
- **No rule-built join has been run against a live `EXPLAIN`.** The join path
  has only ever executed against the built-in schema's foreign keys, in unit
  tests. A join question against an **uploaded** schema remains the single most
  valuable thing to exercise first.
- The domain, resource and schema-advisor modules were not re-checked against a
  rule-built join's plan.

#### The cache defect, observed live for the first time

While reproducing the regression, `show only customer name` against an upload
whose `customers` table has `first_name`/`last_name` and no `name` returned:

```jsonc
{ "handledBy": "CACHE", "confidence": 1.0,
  "generatedQuery": "SELECT first_name, last_name FROM customers;",
  "ruleTrace": { "outcome": "DECLINED",
                 "declineReason": "The question asks for specific fields, but at
                                   least one of them is not a column of customers." } }
```

The rule engine correctly refused, and the cache served an older LLM answer at
the highest confidence the system reports. This is §5's open cache defect, not
a new one — but it is the first time it has been **visible in a response**
rather than inferred, because the decline reason and the cache hit now travel
together. That contradiction is precisely what the trace was built to expose.

### Session of 2026-09-11 — the domain picker is removed

Schemas are now uploaded directly, so no workload is ever declared, and the
Workbench's Domain dropdown was removed at the user's direction. What went
with it:

| Layer | Change |
|---|---|
| Frontend | Dropdown, `domain` state, `Domain`/`DOMAIN_TO_BACKEND_PROFILE` in `contracts.js`, the `domainProfile` field on `/analyze` and `/schema-advisor` calls, and domain-keyed rule lists in `fixtures.js` |
| Backend | `AnalyzeRequest.domainProfile`, the `DomainProfile` enum, `EcommerceOptimizationRules`, `AnalyticalOptimizationRules`. `DomainOptimizationService.evaluate` now takes only the result |
| Wording | Suggestion chip "Domain" → "Optimization"; Analysis headings and the Optimization page's "Simulated domain rules" no longer mention domains |

Every analysis now runs `TransactionalOptimizationRules` — the set a missing
profile or "Other" already fell back to, so a run that never picked a domain
behaves as before. Rule count 70 → 59 on the day (§9 reads 62 now — the three
rewrite rules arrived on 2026-09-21). The `source: "DOMAIN"` tag kept its name,
and the transactional messages still say "transactional".

#### Verified

- `vite build` clean.
- Backend sources compiled with `javac` into a scratch directory, exit 0 —
  **not** `./mvnw test`, because a backend was running against `target/`
  (§6 on why that races). No test referenced the removed classes.
- Grep for `domainProfile`, `DomainProfile` and the deleted classes across
  `backend/src` and `frontend/src`: none left.

#### Hit live, and fixed

The first Run analysis afterwards failed with *"domainProfile: domainProfile
must not be null"*. The backend then running had been started at 19:57 from
a class compiled at 12:06; the source edit landed at 20:12, and devtools never
picked it up. Stopped (the Maven launcher and its child JVM) and restarted
with `./mvnw clean spring-boot:run` — *"Started BackendApplication in 4.724
seconds"*. §6 now carries this as a gotcha.

#### Not verified

- No `/analyze` call was made after the restart; the user is testing it.
- `./mvnw test` and `npm test` were not run for this change.

### Session of 2026-09-21 — five defects from §5, §9.10 and `RULES.md`

All five were fixed test-first. 260 backend tests and 19 frontend tests pass;
the frontend build is clean. **None of it has been exercised against a running
backend or in a browser** — see Not verified below.

**The cache no longer promotes a hallucination.** `/api/query/generate` had no
schema guard at all. `GeneratedQueryValidator.namesUnknownIdentifiers` now
parses the LLM's SQL and resolves its tables and columns against the active
schema; a mismatch returns the query at confidence 0.2 with the reason in a new
`schemaWarning` field and skips `saveToHistory`. The unsaved half is the half
that mattered — it is what stops `transactions` coming back later as `CACHE`
1.0. `SELECT *`, unparseable SQL, MongoDB and an empty active schema all pass:
none is evidence of a mismatch. The Workbench renders the reason under the
query and says the query was not saved.

**The filter rules stopped over-claiming.** Multiple values now survive as an
`IN` list; a trailing word that is not a value ends the list rather than
declining; values from different columns decline. `with <column> <value>`
declines on an unknown column instead of dropping the filter silently.

**The advisor reconciles its one contradictory pair.** A full scan and a
filesort on the same table become one composite `CREATE INDEX` rather than two
single-column statements MySQL cannot both use.

**The delete verb no longer hides inside other words.** Rule 7 tested
`text.contains("delete") || text.contains("remove")`, so `show removed
customers in mumbai` built `DELETE FROM customers WHERE city = 'mumbai';` at
0.95, and `show deleted customers` was declined as a delete with no `WHERE` —
a read rejected as a malformed write. The verb must now be a whole word opening
the question, optionally after `please`; standing anywhere else it declines,
naming the word it found. This one came from `RULES.md`'s own gap list, whose
three entries are now all closed.

**Worth knowing:** `SqlPreflightValidator` looked like the tool for the cache
defect and was not. Its column check opens by reading the table's columns and
returns clean when that list is empty — an invented table passes it — and it
takes the table name as an argument rather than reading it from the SQL. It is
right for `/analyze`, where the table is known to exist, and useless here.

### Session of 2026-09-21 — the instrumentation, and a real rewrite engine

Phases 0–2 of a plan agreed in-session: make verification trustworthy, then
confirm what was already built, then replace the one simulated screen. Backend
**260 → 286 tests**, frontend **19 → 44**, both suites green, `npm run build`
clean, 3 pre-existing oxlint warnings and no new ones.

**Phase 0 — the instrumentation.** Mock mode fails toward the backend now
(§5 Frontend housekeeping), and a shell-wide health banner reads `GET /test`.
These came first deliberately: the next phase is judging screens by eye, and
"the app looks healthy with the backend stopped" is the worst possible hazard
to carry into that.

**Phase 1 — the §8 reproduction, automated.** `OffSchemaCacheRoundTripTest`
asks the same off-schema question **twice** through a real
`GeneratedQueryValidator` and a genuinely stateful history store, and asserts
the second answer does not come back as `CACHE` at 1.00. The existing
`hallucinatedSqlIsFlaggedAndNotCached` mocked the validator and only called
once, so the round trip — the actual failure shape §5 describes — had no guard.

**These tests were proved to have teeth rather than assumed to.** They were
written against code that already worked, so watching them fail was not
possible the usual way; instead the guard was temporarily removed
(`if (schemaWarning == null)` → `if (true)`) and both cases were confirmed to
fail, then it was restored. A test that has never failed is a test that has
never been shown to test anything.

**One expectation was wrong, and the code was right.** The third case asserted
one history row after asking a *grounded* question twice; there are two. That
is correct — §2 and §3 both say history is a per-run log — so the test was
corrected to pin the real contract (`["OLLAMA", "CACHE"]`) rather than the
code being changed to match a guess.

**Phase 2 — `QueryRewriter` and `POST /api/query/compare`.** §9.11 has the
rules and §3 the endpoint. The shape follows `backend/CLAUDE.md` §8's Task C:
analyse both versions, diff them, and say *which rule fired* — not two strings
side by side. The honesty constraint from Paper #5 is carried in the response
itself (`estimate: true`) and in the summary's wording, not just in a caption.

**The frontend's simulation is deleted, not hidden.** `mockCompare` now returns
the backend's shape; `mockSimulateOptimizedQuery` and `REWRITE_RULES` are gone;
the amber "Simulated — the Comparison Engine has no backend endpoint yet" badge
is gone; `ENDPOINT_STATUS.comparison` is `REAL`. The Optimization page's
descriptions no longer say the rewrite engine does not exist.

**A guard found by reading the seed scripts, not by a test failing.**
`KnownSchema.CUSTOMERS` declares `(id, name, email, city, phone)`, but
`src/main/resources/mysql/schema.sql` creates `customers (id, name, city,
email)` — **there is no `phone` column**, and `orders` disagrees on its key
name and columns too. RW1 materialises the *declared* column list into real
SQL, which turns that long-standing, harmless drift into `Unknown column
'phone' in 'field list'` the moment anyone clicks Compare against the built-in
demo schema on a seeded database.

So the comparison service now **withdraws a rewrite whose EXPLAIN failed**
rather than presenting it: `rewritten: false`, no deltas, and a summary saying
it could not be checked and that the schema and the live tables likely
disagree. That guard is right regardless of this particular drift — any rule,
present or future, that emits SQL the database rejects is caught by it.

**The drift itself is untouched and still open**, because it is older and
wider than this work: `KnownSchema` is the built-in demo schema's static
description and the seed scripts are what actually exists. Uploaded schemas
are unaffected — they are parsed from the DDL that provisioned the tables, so
their column lists match by construction. See §5.

**Deliberately not built:** an "add a LIMIT" rule. The deleted simulation had
one, and it changes the rows returned — which makes it a different query, not
a faster one. Every rule that survived preserves the result set.

### Session of 2026-09-21 — phase 4: the plan tree, run context, and a clean lint

Same session as the entry above, continued. Backend **287 → 308 tests**,
frontend **44 → 59**, both green, build clean, and **`npm run lint` is now at
zero warnings** for the first time.

**The plan tree reuses a probe that was already running.** `EXPLAIN`'s tabular
output is a flat list, so nesting needs `FORMAT=JSON` — but flipping
`querysense.explain.format` would put an unexercised reader in front of every
analysis, which CLAUDE.md §18 rejected for good reason. The cost probe already
fetches that JSON and threw everything but one number away; it now returns the
tree as well. One round trip, tabular still authoritative, and
`PlanTreeWiringTest` asserts the single `queryForObject` call so a later edit
cannot quietly make it two.

**History run context stops short of `resourceLevel`, deliberately.**
`confidence`, `durationMs` and `schemaLabel` are all available where history is
written. `resourceLevel` is not — it belongs to `/api/query/analyze` and
history is written by `/api/query/generate`. Storing it needs a contract change,
so it is named as still-missing rather than faked from a default.

**A mutation test that silently proved nothing, caught and redone.** The
plan-tree wiring tests passed on first run, so the guard was removed to check
they had teeth — and they still passed. The `perl` delete had matched nothing:
the file has CRLF endings and the pattern ended in a bare `
`. Worse, the
"restore" then appended a duplicate line. Deleting by line number instead
produced the expected 2 failures, and the duplicate was removed. **A mutation
test that does not fail is not evidence; it is a second thing to verify.**

**Lint reached zero by splitting each context in two.** The warning was
`only-export-components` — a file exporting both a provider component and a
hook. `context/<name>.js` now holds the context object and the `use*` hook;
`context/<Name>Context.jsx` holds only the provider. 16 import sites moved.

### Session of 2026-09-21 — phase 3: MongoDB becomes a decision

Backend **308 → 315 tests**, frontend **59 → 64**, all green.

**MongoDB is now a named non-goal**, written up in §5 beside the DDL-execution
one and in the same shape: the reasoning, what it forbids so later work does
not quietly reintroduce it, and the consequences stated rather than left to be
discovered. The argument is proportionality about scope — supporting Mongo
properly is four features, each about the size of the rewrite engine, and
half-doing it produces a system that looks like it supports two databases while
being measurably worse at one. That is the one thing this project's central
claim cannot survive.

**The one real defect on that path is fixed.** `MongoSimpleAnalyzer` was called
with `collectionName = null`, so every Mongo schema recommendation was about a
collection it could not name. The collection is right there in
`db.<name>.find(...)`, so the analyzer now reads it out of the query text; an
explicitly supplied name still wins, and text that names no collection leaves
it null rather than guessing. Seven cases in `MongoCollectionNameTest`.

**The Analysis screen now says what it did not do.** `MongoFindingsList`
carries a permanent scope note — the query was never run, MongoDB is never
connected, and "no findings" means two text checks found nothing rather than
that the query is fast. It shows on the clean state *and* alongside findings,
because the clean state is the one most likely to be misread. Its own test
asserts the note survives in both.

**Scoping out is not the same as leaving it broken.** The decision removed four
would-be features and kept two bug fixes and one honesty fix. That split is the
point: a non-goal bounds what gets built, not what gets to be wrong.

### Session of 2026-09-25 — the cache learns which schema answered

Came out of a reading pass, not a failure: the question was whether the
built-in `KnownSchema` matching the demo database still mattered once uploads
became the real path. It does, but the sharper finding was next to it — the
cache key had never been told that a second schema could exist.

Written first, and watched fail: three cases in `SchemaScopedCacheTest`. The
red was honest, not vacuous — the fake repository answers the schema-blind
lookup exactly as the database does, so a service that kept calling it failed
the tests instead of passing them against an empty stub. Two returned `CACHE`
where the test demanded `OLLAMA`, which is the defect reproduced; the third,
the regression guard that caching still works within one schema, passed from
the start.

The fix is in §5, "the cache did not know which schema answered". Three
existing tests failed on the change — `OffSchemaCacheRoundTripTest`,
`QueryGenerationServiceImplTest`, `QueryHistoryRunContextTest` — all three
because their fake repositories stubbed the old finder only. The stubs were
repointed; no assertion was weakened to make anything pass.

**Verified:** `./mvnw test`, **318 tests, 0 failures**. Nothing else — no
backend was started, and no upload was performed in a browser. The behaviour
this changes is exactly the kind a unit test can pin down and a demo cannot:
it only shows up when the same question is asked twice across a schema swap.

Also corrected in the docs the same day: §8's summary still called the query
rewrite simulated, §9's table was missing the three rewrite rules and totalled
59 against `RULES.md`'s 62, and §5 item 5 claimed there was no cost figure at
all, which stopped being true on 2026-09-10.

### Session of 2026-09-25 — the schema mismatch was pointing the wrong way

A drift guard was written first, as `KnownSchemaMatchesSeedScriptsTest`: parse
`mysql/schema.sql` and `h2/schema.sql` with `DdlSchemaParser` and assert the
tables, columns and foreign keys match `KnownSchema`. All six cases failed, as
intended.

**Then `KnownSchema` was shrunk to two tables to make them pass, and that was
wrong.** The premise — held in §5 since 2026-09-21 and never checked against
the database — was that the scripts described reality and the declaration had
drifted. `SHOW TABLES FROM querysense` on the live MySQL says the opposite: all
nine tables are there, `customers` has `phone`, `orders` has `id` and
`employee_id`, and `KnownSchema` matches column for column. The scripts are the
stale side. The shrink and its 14 follow-on test edits were reverted whole.

**What the detour is worth keeping for:** the guard was the right instrument
and found real drift on its first run; the diagnosis of *which side* had
drifted came from reading two files that agreed with each other and never
asking the database. `KnownSchemaTest` had a case named "orders matches the
real table: id/employee_id" — correct, and it was treated as the suspect rather
than the witness. One `SHOW TABLES` would have settled it before any code moved.

**Left in place:** the failing `KnownSchemaMatchesSeedScriptsTest`, deliberately
red, naming the tables the scripts do not create. §5 item 2 now states the
direction correctly. Bringing the scripts up to nine tables is the fix, and
it was done on 2026-09-26; the test is green.

**Also this day: the MongoDB non-goal was reopened.** Asked whether the SQL
structure could exist on the Mongo side, the answer is yes, and §5 now carries
the assessment. The part worth keeping in view is that the 2026-09-21 decision
reasoned from scope without checking what MongoDB offers: `explain(
"executionStats")` returns `COLLSCAN`/`IXSCAN` stages, `totalDocsExamined` and
`nReturned`, which land on `QueryAnalysisResult` fields that already exist — so
the verdict ladder and most advisor rule shapes transfer rather than needing
invention. The decision was not wrong about the cost of doing it badly; it was
working from an incomplete picture of the cost of doing it well. **Nothing is
built, and no decision is recorded** — scope and deadline sit with the user.

### Session of 2026-09-26 — seed scripts, and MongoDB parity, steps 1–3

**Seed scripts.** Both `schema.sql` files now create all nine tables column
for column with `KnownSchema`. Both `data.sql` files seed every table, and
`KnownSchemaMatchesSeedScriptsTest` is green. See §5 item 2. The H2 scripts
were executed through H2's `RunScript`. The MySQL `data.sql` was not run,
because that would write to the live database.

**MongoDB parity was decided and designed.** See the decision note in §5.
- Spec: `docs/superpowers/specs/2026-09-26-mongodb-parity-design.md`, 9 steps.
- Plan for steps 1–3: `docs/superpowers/plans/2026-09-26-mongodb-parity-steps-1-3.md`,
  11 tasks. Each was implemented by a subagent and checked by a separate
  reviewer.

**Done (tasks 1–11 of 11 — steps 1–3 of the spec are complete), 424 backend
tests, 0 failures, frontend 66 tests / lint clean:**

| Task | What exists now | Commits |
|---|---|---|
| 1 | `dialect/Dialect` (MYSQL, MONGODB) and `DialectRegistry`, which fails at construction if a dialect has no implementation | `0216fbf` |
| 2 | `ActiveSchemaProvider` holds one schema per dialect; every accessor takes a `Dialect`. `KnownMongoSchema` is the built-in Mongo demo: 8 collections, snake_case, `order_items` embedded as `orders.items[]` | `aad568a` |
| 3 | `QueryIntent` carries structure (`ColumnRef`, `Aggregate`, `Literal`, …) instead of SQL text; `SqlEmitter` does all the rendering. Every SQL expectation is byte-identical | `cdcaa0d`, `206b0ce` |
| 4 | `mongo/MongoShellParser` reads mongosh `find` / `aggregate` / `countDocuments` / `deleteMany` into a `MongoQuery`, and refuses anything else with a position | `3f6838e`, `8d1070a` |
| 5 | `querybuilder/MongoEmitter` writes the same intent as mongosh. Every output reads back through the parser | `40eeaf6`, `b516f84` |
| 6 | Stored values are read per dialect. MongoDB's come from `distinct()` on the live collection through `MongoSchemaMetadata`, cached, with a 30 s "Mongo unavailable" window after a failed read | `fcab5e7`, `d957eaa` |
| 7 | `RuleBasedQueryBuilder` threads `Dialect` through `QueryIntentParser`. **Rule E2 no longer declines MongoDB** — a bound question is built and written by `MongoEmitter` instead of going to the LLM | `18ac2f0` |
| 8 | A second table-shaped noun that binds to nothing declines, naming it, instead of being silently dropped (new rule E23) | `dfe2777` |
| 9 | `QueryHistory` stores the `resourceLevel` each run was made under. An unknown `resourceLevel` or a malformed request body is a 400, not a 500 | `59aa243`, `2de18ea` |
| 10 | `DialectParityTest` holds 18 of the app's questions to the same built/declined outcome in MySQL and MongoDB, plus three documented differences | `a2329d6`, extended after `face58c` |
| 11 | This entry, and the matching updates to `RULES.md` | — |

**Wired, as of task 7.** The rule engine no longer declines every MongoDB
question. `RuleBasedQueryBuilder` resolves `databaseType` to a `Dialect` and
passes it through `QueryIntentParser`; a MongoDB question is bound against
`KnownMongoSchema` (or an uploaded Mongo schema) exactly as a SQL one is
bound against `KnownSchema`, and written by `MongoEmitter` instead of
`SqlEmitter`. It declines on the same binder failures SQL does (no table,
ambiguous join, an unbound column or value, a temporal expression, the new
task 8 rule), plus two things the emitter declines rather than approximate:
a `WHERE` on the joined collection, and a `WHERE` on an embedded-array field
while aggregating. `DialectParityTest` (task 10) checks 18 questions come out
the same way in both dialects, with three documented, deliberate
differences, all from `order_items` being a table in MySQL but embedded
under `orders.items[]` in MongoDB:
- `show all order items` builds on MySQL and declines on MongoDB, where no
  such collection exists.
- `show orders with products` and `show products and orders` build on
  MongoDB through `orders.items.product_id`, and decline on MySQL, which
  has no direct relationship between the two tables.
- `show unit price of orders` projects `items.unit_price` on MongoDB and
  declines on MySQL, where the column lives on `order_items`.

This is user-visible the moment MongoDB is chosen
as the target — nothing else is needed to reach it, only a live `mongod` to
demo it against.

**Defects found and fixed along the way:**
- **`SchemaMetadataService`'s schema-change listener was never registered.**
  `registerSchemaChangeListener()` had no `@PostConstruct` and no caller. So
  the column and distinct-value caches survived an upload, and the rule
  engine could check values against the previous schema's data. The listener
  is now registered in the constructor. `SchemaMetadataCacheTest` guards it.
- **The task 3 refactor briefly stopped the live column-drift check from
  covering columns inside aggregates.** Two examples: the `price` in
  `AVG(price)`, and the HAVING count target. Caught in review and fixed with
  two tests. The plan's code was the cause.
- **The parser silently dropped extra arguments** (`limit(3, 5)`) and let an
  unmatched `}` drive its depth negative. Both are now refused.
- **Two silently wrong MongoDB results in the emitter.**
  - A HAVING aggregate that is not in the select list was matched on a field
    `$group` never created.
  - A joined `COUNT(*)` had `$limit` placed before `$count`, which capped the
    count.
  Both are fixed, along with three cases that would only go wrong after a
  future parser change.
- **Task 6 — ObjectId and date values came back as unreadable text, and a
  failed Mongo read was cached forever.** `MongoSchemaMetadata.asText` fell
  back to raw `BsonObjectId{...}`/`BsonDateTime{...}` text for those two BSON
  types, and a failed `distinct()` call was cached as `[]` until the next
  schema change — an outage looked identical to "this column has no values."
  Fixed: an ObjectId renders as its hex string, a date as ISO-8601; a failure
  is never cached, and a 30 s "Mongo unavailable" window replaces it instead,
  so an outage costs one 2 s timeout per 30 s rather than one per field per
  request.
- **Task 8 — a second noun that binds to nothing was silently dropped,
  confirming the 2026-09-25 "UNCONFIRMED" entry in `RULES.md`.**
  `customers and suppliers` (no `suppliers` table) built
  `SELECT * FROM customers;` at 0.95 without mentioning "suppliers" anywhere
  in the trace. Fixed with rule E23: it now declines, naming the word. See
  `RULES.md`'s Known gaps and `UnboundNounTest`.
- **Task 9 — an unknown `resourceLevel` or a malformed request body returned
  500.** `HttpMessageNotReadableException` had no handler, so a bad enum
  value or unparsable JSON fell through the catch-all as a generic 500.
  Fixed: it's now a 400 that names the field and, for a bad enum, the values
  it will accept.

**Decisions taken during execution:**
- MongoDB filters use the stored value (`"Mumbai"`); SQL keeps the typed one
  (`'mumbai'`). MongoDB matches case exactly.
- A count across a join ignores sort and limit, which matches SQL's
  single-row result.
- The emitter declines two things rather than approximate them:
  - a WHERE on the joined collection
  - a WHERE on an embedded-array field while aggregating
- `COUNT(column)` excludes nulls through `$cond`, except on `_id`.
- **Task 7:** `SchemaBinder.bindColumn`'s exact-name match is
  case-insensitive (`equalsIgnoreCase`), a deviation from the task's brief
  needed because the brief's own version broke mixed-case uploaded MySQL
  columns. **Deferred minor, not fixed:** the same change can now bind
  differently on a mixed-case upload — "id" can bind `OrderID`, and "date"
  can turn ambiguous between `ShipDate` and `order_date`. The built-in
  schema is unaffected either way. The same matcher also matches `_id` for
  "id", the last segment of a dotted embedded-field path, and a camelCase
  column read as snake_case — see `RULES.md` E5.
- **Task 9:** `resourceLevel` is stored via an optional field on the
  generate request, not by widening `/analyze` or writing history from that
  path. A caller that doesn't send one stores null, and older rows render
  `—` rather than a guessed value. See §5 item 8.

**Differences from SQL, to state on screen once Mongo is live:**
- An aggregate over no documents returns nothing, where SQL returns one
  0/NULL row.
- Grouped keys come back under `_id`.
- A HAVING filter on an embedded-array field would sum to zero with no
  error. The parser cannot produce one today.

**Not verified:** no `mongod` exists on this machine, so every Mongo
behaviour above — including `distinct`, the stored-value cache and the 30 s
outage window — is unit-tested only. Nothing here was run against a live
backend.

**Live checklist, once `mongod` is running and seeded (spec step 4):** ask
the rule engine `customers in mumbai` with the target set to MongoDB, and
confirm it answers built, with `"Mumbai"` — the stored value, not the
lowercased input — inside the `$match` filter.

**Next:** steps 1–5 are complete; steps 4 and 5 are recorded in the two
2026-10-01 sessions below. Step 6 is next: `MongoQueryRewriter`, compare for
MongoDB, and `optimizedQuery` written into history. Its plan is written and
not yet executed: `docs/superpowers/plans/2026-10-01-mongodb-parity-step-6-rewrite.md`
(7 tasks, six departures listed at its top). The progress ledger for
steps 1–3 is at
`.superpowers/sdd/2026-09-26-mongodb-parity-steps-1-3/progress.md`, which is
git-ignored.

**2026-09-28 — step 4 planned, not started.**
- `3e701e8` adds two more documented differences to `DialectParityTest`
  (see task 10 above), bringing the total to three.
- A `mongod` now listens on `127.0.0.1:27017`. It has not been confirmed as
  MongoDB Community Server, and nothing has been run against it yet.
- The step 4 plan is written:
  `docs/superpowers/plans/2026-09-28-mongodb-parity-step-4-upload.md`,
  10 tasks. It was executed on 2026-10-01 — see that session below. The
  tasks:
  - Tasks 1–3: per-dialect upload interfaces, `JsonSchemaUploadParser`,
    and the Mongo validation rules.
  - Tasks 4–5: `MongoProvisioner` and `MongoSeeder`, with an unreachable
    MongoDB returned as a 503.
  - Task 6: `SchemaUploadService` per dialect.
  - Task 7: the built-in demo as `mongo/demo-collections.json`, seeded
    into an empty `querysense` database at startup.
  - Task 8: `dialect` on the schema endpoints.
  - Task 9: a `@Tag("mongo")` integration test against the local `mongod`.
  - Task 10: docs.
- The plan departs from the spec in five places, all listed at its top:
  1. It seeds by generating every collection first and filling references
     afterwards, not in dependency order.
  2. The H2 profile refuses MySQL uploads only.
  3. The demo is seeded by a startup listener, only when the demo
     database is empty.
  4. References must share a `bsonType` with their target and point
     outside embedded arrays.
  5. The demo indexes every reference field, as InnoDB does for foreign
     keys.
- Executed 2026-10-01, inline, in plan order.

### Session of 2026-10-01 — MongoDB parity, step 4 (upload)

Plan: `docs/superpowers/plans/2026-09-28-mongodb-parity-step-4-upload.md`,
executed inline, task by task, test first.

| Task | Delivered | Commit |
|---|---|---|
| 1 | `SchemaUploadParser`, `SchemaProvisioning`, `SchemaSeeding` and a top-level `SeedReport`; `ParsedTable` gains embedded arrays and the validator, `ParsedSchema` gains indexes and field types, `SchemaSnapshot` gains `fieldTypes`. The MySQL classes implement the interfaces unchanged. | `01b934d` |
| 2 | `JsonSchemaUploadParser`: the `{"collections": [...]}` format or `db.getCollectionInfos()` output, dotted leaf paths, embedded arrays, indexes, `references`; views and `system.*` skipped with a warning. | `09d3a58` |
| 3 | `SchemaUploadValidator.validate(schema, dialect)`: names, duplicates, references (declared, same `bsonType`, not inside an array), indexes on declared fields, required fields of a type the seeder can't make, `additionalProperties: false` without `_id`. | `95014fe` |
| 4 | `MongoProvisioner`: drop, `createCollection` with a strict, erroring validator, `createIndex`. `MongoUnavailableException` is a 503. | `8a9ff4c` |
| 5 | `MongoSeeder`: documents generated from `$jsonSchema`, references filled from the target's generated values (through arrays), unordered batches of 1,000; a validator rejection names the collection and field. | `ceb2020` |
| 6 | `SchemaUploadService` runs parser → validator → provisioner → seeder per dialect; warnings kept per dialect; MySQL keeps the H2 refusal, MongoDB needs `spring.data.mongodb.uri`. | `88dffdf` |
| 7 | The built-in demo as `backend/src/main/resources/mongo/demo-collections.json`; `KnownMongoSchema` gains `INDEXES` and `FIELD_TYPES`; `MongoDemoSeeder` seeds `querysense` at startup only when none of the eight collections exist. | `caf8d44` |
| 8 | `dialect` on `POST /api/schema/upload` (multipart part or JSON field, `schema` accepted for `ddl`), `GET /active` and `POST /reset`; a `.json` file needs MongoDB and a `.sql` file needs MySQL. Responses gain `dialect`, `indexes`, and per table `embeddedArrays` and `fieldTypes`. | `9a329f4` |
| 9 | `MongoUploadIntegrationTest` (`@Tag("mongo")`): provisions and seeds the demo into a throwaway `querysense_it_*` database, then drops it. | `0580432` |

**Departures from the spec, on purpose:**
1. Seeding generates every collection first and fills references afterwards,
   not in dependency order; cycles need no special case.
2. The H2 profile refuses MySQL uploads only; a MongoDB upload is guarded by
   `spring.data.mongodb.uri`.
3. The demo is seeded by a startup listener, only when the `querysense`
   database has none of its collections. It never drops anything there.
4. A reference must share a `bsonType` with its target and point outside
   embedded arrays.
5. The demo indexes every reference field, as InnoDB does for foreign keys.

**Calls made during execution:**
- `BackendApplicationTests` turns off `querysense.mongo.demo.seed-on-startup`.
  `@SpringBootTest` fires `ApplicationReadyEvent`, and with a local `mongod`
  the context test would otherwise seed the real `querysense` database.
- The 503 mapping is tested through MockMvc
  (`MongoUnavailableMappingTest`), since no handler test existed.
- The final review found that a MongoDB upload dropped only the collections
  it named, so an earlier upload's other collections stayed in
  `querysense_uploads`. It now drops every non-`system.` collection there
  first, as the MySQL provisioner drops every table (`c0b6bf9`). Provisioning into
  `querysense` for the demo still drops nothing it doesn't create.

**Verified against the real `mongod`:** the server is MongoDB 8.3.11
Community (`buildInfo.modules` is empty). `MongoUploadIntegrationTest` ran
6/6, not skipped: all eight demo collections were seeded with 200 documents
each and accepted by the strict validators. The server rejected an invalid
document with code 121. The unique `email` index and the `items.product_id`
index exist, every embedded item references a seeded product, and `Mumbai`
is among the cities. Afterwards the server listed only `admin`, `config` and
`local`, so nothing was left behind. Full backend suite: 545/545.

**Unit-tested only:** the startup listener, the 503 on an outage, the
duplicate-key warning, the endpoints' `dialect` handling, and everything in
the live checklist below. The app was not started.

**Known gaps, not fixed:**
- Raw `db.getCollectionInfos()` output pasted from mongosh contains shell
  constructors such as `UUID('...')`, which are not JSON. The upload refuses
  it with the line and column. Paste `JSON.stringify(db.getCollectionInfos())`
  or `EJSON.stringify(...)` instead.
- `MongoSeeder` builds every collection's documents in memory before
  inserting. At 12,000 per collection that is fine for the demo, but a
  40-collection upload holds about 480,000 documents at once.
- If the demo's first seeding fails partway, its collections exist, so
  later startups leave it alone. Drop the `querysense` database to retry.

**Live checklist (step 4):**
1. Start the backend with `mongod` running. The log shows
   `Seeded the MongoDB demo into 'querysense'`, or `already has demo
   collections` if it was seeded before.
2. In the Workbench, with the target set to MongoDB, ask `customers in
   mumbai`. It builds, with `"Mumbai"` in the `$match` filter.
3. `curl -F file=@backend/src/main/resources/mongo/demo-collections.json -F
   dialect=MONGODB http://localhost:8080/api/schema/upload` returns
   `"dialect": "MONGODB"`, eight collections, row counts of 12000, and the
   indexes.
4. `curl "http://localhost:8080/api/schema/active?dialect=MONGODB"` shows the
   upload, and `?dialect=MYSQL` is unchanged.
5. `curl -X POST "http://localhost:8080/api/schema/reset?dialect=MONGODB"`
   returns the built-in.
6. With `mongod` stopped, the upload in step 3 returns 503 within a few
   seconds.

Uploads write to `querysense_uploads` and the demo to `querysense`, both on
the local `mongod`.

### Session of 2026-10-01 — MongoDB parity, step 5 (analysis)

Plan: `docs/superpowers/plans/2026-10-01-mongodb-parity-step-5-analysis.md`,
executed inline, task by task, test first.

| Task | Delivered | Commit |
|---|---|---|
| 1 | `MongoExplainCommands` (explain at `executionStats` for find, aggregate, count, delete), and 12 explain fixtures plus `listIndexes` for the demo, captured from the local `mongod` by `MongoExplainFixtureCapture`. | `af72a0b` |
| 2 | `MongoExplainParser` and `MongoShapeGuard`: classic and slot-based explain output, and `$cursor` pipelines, mapped onto `QueryAnalysisResult`; W1, W3, W4 for MongoDB; `executionMillis`. | `b93abdd` |
| 3 | `MongoQueryFields`, `MongoQueryShape`, `MongoPreflightValidator`: collection and field paths checked against the active Mongo schema; `$out`/`$merge` refused. | `60b187b` |
| 4 | `MongoExplainExecutor`: `explain` and `listIndexes` on the active Mongo database; an outage is a 503. | `923888a` |
| 5 | `MongoExplainAnalyzer` and `PlanAnalyzer`; M1 reads real `listIndexes()`, M2 reads the parsed query. | `eb7c104` |
| 6 | `MySqlPlanAnalyzer` (today's SQL path, moved), `QueryAnalysisServiceImpl` routes by dialect, `MongoSimpleAnalyzer` deleted. An unknown `databaseType` is now a 400. | `1bf5632` |
| 7 | Verdicts in MongoDB's words; every MySQL sentence pinned unchanged. | `d186b78` |
| 8 | `IndexStatements` and `OptimizationSuggestion.suggestedChange`, filled for MySQL TX1, TX2, RS1, RS3. | `8469ec2` |
| 9 | MongoDB variants of TX1–TX5, TX7, RS1–RS3; TX6 and RS4 folded. | `7332d3b` |
| 10 | `IndexAdvisor` split into `MySqlIndexAdvisor` (moved byte for byte) and `MongoIndexAdvisor` (equality → sort → range compound, regex and grouping advice). | `deaf668` |
| 11 | `MongoAnalysisIntegrationTest` against the real `mongod`. | `c9ba728` |

**Departures from the spec, on purpose:**
1. The fixture "script" is a JUnit class run on demand
   (`-Dquerysense.capture-fixtures=true`); `mongosh` isn't installed.
2. Per-collection figures exist only where MongoDB reports them per stage.
   For pipelines in the slot-based engine `filtered` stays null, so the
   verdict reports a scan without claiming waste.
3. TX6 and RS4 fold into TX1 and RS3 for MongoDB.
4. `suggestedChange` carries index statements only; `"rewrite"` comes with
   step 6.
5. W2 has no MongoDB variant.
6. Preflight stops checking field paths at the first stage that reshapes
   documents.
7. A collection declared in the schema but missing from `mongod` is V1
   (`UNKNOWN`), with a hint to let the demo seed.

**What the 8.3 server actually returns**, learned while writing the plan:
- Plain finds, count and delete come back as `explainVersion` "1", with
  per-stage `docsExamined` and `nReturned`.
- Pushed-down aggregates come back as "2": the classic stage names sit under
  `winningPlan.queryPlan`, and `executionStages` holds the slot-based
  engine's own lowercase stages.
- `$facet` comes back as a `stages` array led by `$cursor`.
- An equality on a unique index uses `EXPRESS_IXSCAN`.
- A missing collection is `EOF` / `nonExistentNamespace`.
- `explain` on a delete deleted nothing, which was confirmed by counting
  documents afterwards.

**Verified against the real `mongod`:** the fixture capture ran, not skipped.
`MongoAnalysisIntegrationTest` ran 6/6, not skipped:
- a scan on `city` is `CRITICAL` in MongoDB's words, with
  `db.customers.createIndex({ "city": 1 })`;
- an `email` lookup uses `email_1` and the advisor stays quiet;
- a scan plus sort collapses into `{ "city": 1, "name": 1 }`;
- an unfiltered `$group` is flagged for review;
- explaining `deleteMany` left all documents in place;
- no real plan produced an unknown-stage warning.

Afterwards the server listed only `admin`, `config` and `local`. The full
backend suite is 635/635.

**Unit-tested only:** the 503 on an outage, the `$out`/`$merge` refusal
through the analyzer, and everything in the live checklist below. The app was
not started.

**Live checklist (step 5):**
1. Workbench with MongoDB: generate `customers in mumbai`, then open
   Analysis. The verdict reads "Reads the whole customers collection — about
   12,000 documents — then discards …". The warnings include "Collection scan
   detected on 'customers'". The current Mongo findings view still renders;
   step 8 replaces it.
2. Schema advisor on the same query shows
   `db.customers.createIndex({ "city": 1 });`.
3. `POST /api/query/analyze` with
   `{"query": "db.orders.aggregate([{\"$out\": \"x\"}])", "databaseType": "MongoDB", "resourceLevel": "MEDIUM"}`
   returns an `UNKNOWN` verdict whose warning says QuerySense never runs a
   write. No `x` collection appears in `querysense`.
4. With `mongod` stopped, the same analyze call on a `find` returns 503
   naming MongoDB within a few seconds.
5. A MySQL analysis is unchanged: same verdict text and suggestions, plus a
   new `suggestedChange` on TX1, TX2, RS1 and RS3 when a column is known.

### Not verified

- ~~**No screen of the current frontend has been exercised against the live
  backend in a browser.**~~ **Partially retired 2026-09-10** — see the schema
  upload session above, and the Optimization session after it. Workbench,
  Analysis, Schema source and **Optimization** have now been driven in a real
  browser against the live backend, with a console check. **Schema advisor
  and History still have not been** (Architecture was removed 2026-09-11,
  see §5 Frontend housekeeping) — note that Optimization
  renders the advisor's output through the same `RecommendationList` the
  Schema advisor screen uses, so that component is now verified on screen
  even though its own page is not. The per-table plan blocks in
  `ExplainPlanView` have only been seen in their single-table state; the
  multi-table rendering remains verified as data and as a clean build, never
  as pixels.
- The populated states of the analysis, optimisation and schema-advisor screens
  (a real plan tree, non-empty suggestions, real index proposals) still require
  a backend on real MySQL, since H2 cannot produce `EXPLAIN` output.
- The `OLLAMA` rename fix is exactly what an end-to-end fallback run would
  confirm, and is the first thing to check.
- **Nothing from either 2026-09-21 session has met a live backend.** The schema
  conformance guard, the `IN` filter list, the two new decline paths, the
  advisor's composite index, and now `QueryRewriter`, `POST /api/query/compare`,
  the health banner and the mock-mode flip are covered by unit tests and a clean
  build — nothing more. `SchemaWarning` has never been rendered in a browser and
  neither has `BackendHealthBanner` or the rewritten Optimization page.

  The §8 reproduction is **no longer the fastest live check**, because it is now
  an automated test (`OffSchemaCacheRoundTripTest`). What live running is still
  owed is the part no unit test can reach: that `EXPLAIN` over a *rewritten*
  query returns a usable plan, that the row and cost deltas are not degenerate
  on real data, and that the JSON probe on a real MySQL produces a plan tree
  whose shape matches the fixtures in `MySqlPlanTreeTest` — those were written
  from the documented `FORMAT=JSON` structure, not captured from this database.
  Checklist:
  1. Backend on real MySQL, frontend on `npm run dev`. Confirm **no** banner —
     that alone proves the probe and the mock-mode flip.
  2. Stop the backend. Confirm the red banner names the base URL; restart, hit
     Retry, confirm it clears.
  3. Workbench → Analysis → Optimization → **Compare rewrite** on a query with
     `SELECT *` and an `OR` chain on one column, against an uploaded schema.
     Confirm the diff, a per-rule reason for every changed line, and a row
     delta that is not null.
  4. Repeat with a query no rule touches — expect "No rewrite applied", not an
     error.
  5. Repeat with MongoDB selected — expect the "SQL only, for now" empty state
     and, in the network tab, **no** call to `/api/query/analyze` behind it.
  6. On the Analysis screen, confirm the **Plan tree** section renders nested
     nodes for a join, and that a sort is attributed to the operation rather
     than to the tables under it. If it shows the "no plan tree" note instead,
     the JSON probe failed — the per-table plan above it will still be right.
  7. On History, confirm the three new columns are populated for fresh runs and
     show `—` for rows written before today.
  8. Visit `/about` — expect the Not-found page naming the path, not a blank
     shell.
  9. Run a MongoDB question end to end (needs Ollama). Confirm the Analysis
     screen's scope note appears, and that any schema recommendation now names
     its collection rather than saying `null`.

### Behavioural shift worth watching

Widening `KnownSchema` from two tables to nine widened the `tableCount > 1`
complexity gate along with it. Questions like "products in a category" or
"employees in a department" now mention two known tables and route to the LLM,
where before neither table existed and they were handled or declined on other
grounds. **More traffic depends on Ollama than it used to.** With Ollama down,
more questions now fail with a 503 than did under the two-table schema.

The same effect scales with an upload: the gate counts tables in whatever
schema is active, so a client who uploads a twelve-table schema gets more of
their questions routed to the LLM than a small schema would.

---

## 8. Review requirements — what is built, checked 2026-09-10

A six-item list came back from review. Each row below was **re-verified
against the running backend on the day this was written**, with an uploaded
schema (`sample1.sql`, one table `user_details`) active — not read off the
rest of this document. Five were built; the sixth, query cost, was built on
2026-09-10 and its row is updated below — so all six now stand as built.

| # | Requirement | Status |
|---|---|---|
| 1 | Upload schema **and simulate the rules on it** | **Built** — seeding defect fixed 2026-09-10, see §7 |
| 2 | Show code for optimization on the interface | **Built** — schema DDL, and since 2026-09-21 the query rewrite too (§9.11) |
| 3 | Calculate query **cost** in the EXPLAIN | **Built 2026-09-10** — `query_cost` via a separate JSON probe, labelled unitless |
| 4 | Rule-based system customised to schema | **Built** |
| 5 | LLM fallback | **Built** |
| 6 | Cached queries | **Built** |

### 1 · Upload a schema and run the rules against it — built

`POST /api/schema/upload` (multipart or pasted DDL), `GET /api/schema/active`,
`POST /api/schema/reset`, and the Schema source screen — see §3. What review
is really asking is whether the *rules* follow the upload rather than staying
pinned to the built-in demo tables. They do, end to end, verified with
`sample1.sql` active:

- **Rule engine** — see #4 below; it generated against `user_details`.
- **`EXPLAIN`** — runs in the `querysense_uploads` catalog. Real plan output:
  `accessType: ALL`, `explainFailed: false`.
- **Schema advisor** — returned `CREATE INDEX idx_user_details_gender ON
  user_details(gender);` and four more, one per filtered column.
- **Domain + resource modules** — return live suggestions of both kinds.
  Verified as a `DOMAIN` suggestion only at first; the `RESOURCE` half was
  silent because the upload had seeded 0 rows and that module needs >5000.
  After the seeder fix (§7) the same query returns both, and the resource
  text changes per level.

### 2 · Optimisation code on the interface — built for schema changes

Added 2026-09-10; see the §7 session entry. The Optimization screen's upper
half renders the advisor's recommendations with the DDL in a copyable block,
and `buildApplyScript` aggregates them into one pasteable script.

**The distinction to keep straight when demoing:** *schema-change* code
changes the database and leaves the query alone; *query-rewrite* code changes
the query and leaves the database alone. Both come from the backend as of
2026-09-21 — if review meant "show us the optimised **query**", that is the
lower half, and it is now `QueryRewriter` output rather than a browser regex
(§9.11).

Per §5's "Not a gap", the interface shows this code and never runs it.

### 3 · Query cost — built 2026-09-10

`QueryAnalysisResult.queryCost` carries MySQL's own whole-plan cost estimate,
and the Analysis screen renders it as one of four metric tiles. This entry
previously read "NOT built"; the two caveats it recorded are both addressed
below rather than discarded, because both were correct.

**How the number is obtained, and why not the obvious way.** The obvious way
is to set `querysense.explain.format=JSON`, and that is the wrong way. That
setting is an *either/or* — it replaces the tabular reader for every analysis
in the app, and the tabular reader is the one that has actually been
exercised against this project's database. Swapping an unexercised reader in
front of every user to obtain one extra number is a bad trade, and a subtly
different plan (a join ordering, an inherited filesort flag) would pass
silently.

So the plan still comes from the tabular reader, unchanged, and the cost is
asked for on the side: `MySqlExplainAnalyzer.readQueryCost` issues a
**second, separate** `EXPLAIN FORMAT=JSON` purely to read
`query_block.cost_info.query_cost`, via the new
`MySqlJsonExplainParser.parseQueryCost`. The extra round trip is cheap —
`EXPLAIN` does not execute the query, it asks the optimiser for a plan it has
already computed. Every failure in the probe is swallowed and returns `null`;
the caller already has a complete plan, so a cost-format problem must not
cost anyone an analysis. Logged at `DEBUG`, not `WARN`, because a null cost
is an expected outcome rather than a fault.

One parsing detail worth keeping: MySQL emits `query_cost` as a JSON
**string** (`"1264.35"`), not a number, so `asDouble()` on that node returns
`0.0` — a plan silently reported as free. The text is parsed explicitly and
anything that will not parse returns null.

**Caveat 1 was right and is honoured in the UI.** `query_cost` is a unitless
optimiser estimate that looks like milliseconds and is not. It is never shown
bare: `QUERY_COST_CAVEAT` in `lib/explainGlossary.js` travels with it
wherever it appears — *"MySQL's own estimate of the effort this plan takes.
It has no unit — it is only meaningful compared against another plan for the
same query."* That is the same discipline that produced "Estimated rows"
instead of "cost" in the first place (§5.4).

**It is also the one legitimately whole-query figure on the screen**, which
is worth knowing. Rows examined is per-table and deliberately not summed: for
a `JOIN`, MySQL nests loops, so a sum would be a confident wrong number.
`QueryAnalysisResult` documents that reasoning at the field, and the headline
keeps its honest "most rows examined at one table" framing. `query_cost`
covers the whole plan by construction, which makes it the right figure for
the before/after bars on the Optimization screen — those currently chart
estimated rows and label them cost.

**Still deliberately not taken: `EXPLAIN ANALYZE`.** The decision this entry
asked for was made on 2026-09-10: **no**. The analyzer stays non-executing.
MySQL 8.0.18+ would report actual time and actual rows — the only genuine
magnitudes available — but it executes the query, and the query may be
LLM-generated SQL that invented a column (§5). Everything on the Analysis
screen therefore remains an estimate, and `PlanMetrics` says so once, in a
footnote, rather than hedging every tile.

### 4 · Rule engine customised to the uploaded schema — built

The strongest of the six, and the one most worth demonstrating live, because
it is the claim a reviewer is most likely to doubt. With `sample1.sql`
active — a table this project had never seen — the rule engine handled all
three of these itself, no LLM involved:

| Question | `handledBy` | Generated |
|---|---|---|
| Show all user_details | `RULE_ENGINE` | `SELECT * FROM user_details;` |
| Show only the username and status of user_details | `RULE_ENGINE` | `SELECT username, status FROM user_details;` |
| Show user_details with gender M | `RULE_ENGINE` | `SELECT * FROM user_details WHERE gender = 'm';` |

The templates are fixed; the schema they bind to is not.

### 5 · LLM fallback — built · 6 · Cached queries — built

Both verified in a single pair of calls, same input twice:

| Call | `handledBy` | Confidence | Latency |
|---|---|---|---|
| 1st — "Which user_details have a status of active and were created last month" | `OLLAMA` | 0.65 | 0.75 s |
| 2nd — identical input | `CACHE` | 1.00 | 0.07 s |

The question contains a question word (`which`) and a date range (`last
month`), so the rule engine declines it and the LLM answers; the repeat is
served from `query_history`. Ollama has `qwen2.5-coder:7b` pulled.

### Two defects the same verification run exposed

Both matter specifically because review asked for these tiers to be *shown*,
and both are reproducible on the demo path.

**The LLM invented a column, and the cache then served it at confidence 1.00.**
The fallback above returned:

```sql
SELECT * FROM user_details WHERE status = 'active'
  AND created_at >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH);
```

`user_details` has no `created_at` — its columns are `user_id, username,
first_name, last_name, gender, password, status`. This is the hallucination
defect of §5 occurring on an *uploaded* schema, and the second call returned
the same broken SQL labelled `CACHE` at **confidence 1.00**, the highest tier
the system reports.

**Guarded 2026-09-21.** SQL naming a column the active schema does not have is
now returned at 0.2 with the mismatch named, and is never written to history,
so the second call cannot serve it back at 1.00. The observation above stands
as the reason the guard exists. It has not been re-run against the live backend
since — the guard is covered by unit tests only, and this exact reproduction is
the first thing worth trying.

**The rule engine silently drops a grouping. — FIXED 2026-09-11.** `per
<column>` is now bound like any other phrase: it becomes a real `GROUP BY`
when the column resolves, and declines when it does not. "How many user_details
are there per gender" returned `SELECT COUNT(*) FROM user_details;` at
`RULE_ENGINE` confidence 0.95 — the `COUNT` template matched "how many" and
`per gender` was discarded without a word. The engine declines `group by`
as a phrase but does not recognise `per <column>` as asking for one, so
instead of handing this to the LLM it answers a question that was not asked.
Not the same as the multi-word-table defect in §5; a separate, newly observed
case of the rule engine over-claiming.

### Summary for review

Built: schema upload with all rule layers following it, the three-tier
routing (rule engine / cache / LLM) with the tier shown and auditable,
optimisation DDL on screen, and — since 2026-09-10 — MySQL's `query_cost`
alongside a plain-English verdict and a rows-examined/rows-returned/wasted-work
breakdown on the Analysis screen. Since 2026-09-21 the query rewrite is built
too — `QueryRewriter` behind `POST /api/query/compare`, with both versions
EXPLAINed — so **nothing on screen is simulated any more.** The amber
"Simulated" badge and the browser regex behind it are deleted.

Every figure the Analysis screen shows is an optimiser **estimate**. The
analyzer runs `EXPLAIN` and never executes the query — a decision reaffirmed
on 2026-09-10 when `EXPLAIN ANALYZE` was considered and declined (§8 item 3).

---

## 9. Every rule, and how many there are

Checked 2026-09-11 by reading `backend/src/main`, again on 2026-09-21 when
§9.11 was added, and again on 2026-09-26 when rule 2 changed and rule 23 was
added. Nothing here was run. It lists every rule the backend applies, in the
order it applies them.

| Module | Rules | Where |
|---|---|---|
| **Rule engine** (natural language → SQL) | **23** (19 of them can decline) | `querybuilder/` |
| Routing between the three layers | 3 | `QueryGenerationServiceImpl`, `GeneratedQueryValidator` |
| Pre-EXPLAIN checks | 2 | `analysis/SqlPreflightValidator` |
| EXPLAIN warnings | 4 | `analysis/MySqlExplainAnalyzer` |
| MongoDB checks | 2 | `analysis/MongoSimpleAnalyzer` |
| Analysis verdict | 9 | `analysis/VerdictService` |
| Domain optimisation | 7 (transactional — the only set left) | `optimization/TransactionalOptimizationRules` |
| Resource optimisation | 5 | `optimization/ResourceConsciousService` |
| Schema advisor | 5 | `service/impl/SchemaAdvisorServiceImpl` |
| Query rewrite | 3 | `optimization/QueryRewriter` |
| **Total** | **63** | |

Only the first row is the rule engine in the strict sense: layer 1 of query
generation (§2). The rest are the other rule-based modules. They are
listed too, because none of them uses a model either.

### 9.1 The rule engine — 23 rules

The entry point is `RuleBasedQueryBuilder`. The parsing is done by `QueryIntentParser`, tables
and columns are matched by `SchemaBinder` and `JoinPathResolver`, and SQL is
written by `SqlEmitter`. The rules run top to bottom, and the first decline
stops the build. **Every decline carries a plain-English reason**, which the
UI shows in the rule trace and which appears nowhere else.

**What counts as a match.** Every "binds" below means matched against the
**active** schema, whether that's the built-in one or an upload. That dependency is
the whole 2026-09-11 redesign (§2). Nothing is matched against a fixed
English word list except the date check (rule 3).

#### Gates

| # | Rule | What it does | Declines when |
|---|---|---|---|
| 1 | Empty question | — | the question is blank |
| 2 | Dialect | Resolves `databaseType` to `MYSQL` or `MONGODB` and passes it to every rule after it — rules 4 onward bind against that dialect's schema, and at rule 21 the result is written out by a dialect-specific emitter. **Updated 2026-09-26**, replacing the old MongoDB catch-all | nothing, once the dialect resolves. An unrecognised `databaseType` is a **400** from `GlobalExceptionHandler`, not a decline that falls to the LLM |
| 3 | No date arithmetic | Looks for a date *expression*: `last/this/next/past` + a unit, `today/yesterday/tomorrow/recently`, a preposition + a month name, `before/after/since/until` + a year, `date range`, `last N days` | any of those appears. It declines on the expression, not on a single word, so the word `may` no longer counts as a month |

#### Binding the question to the schema

| # | Rule | What it does | Declines when |
|---|---|---|---|
| 4 | Table binding | Finds every table named in the question. Underscores and spaces are treated as the same, so `order items` matches `order_items`. The plural is tried before the singular. A match sitting inside a longer match is dropped, so `orders` cannot win on the word "order" inside "order items". Tables keep sentence order, and **the first one named is the subject** | no table matches, or **more than two** do |
| 5 | Column binding (used by rules 9–15 and 19) | Tries an exact match (spaces read as underscores), then the singular (trailing `s` removed), then a suffix match (`order date` → `order_date`), but only if exactly one column ends that way | — itself; a phrase that matches nothing, or matches two columns equally well, comes back empty and the rule that asked for it declines |
| 6 | Join from a declared foreign key | With two tables, finds the foreign keys that link them. The `ON` clause is read from that key, never guessed from a name like `client_id`. Self-referencing keys (`employees.manager_id`) are ignored | the schema declares **no** key between the two tables, or **more than one** (for example, billing and shipping addresses). The trace names the columns involved |
| 23 | A second unbound noun declines, naming it — **added 2026-09-26**, runs here (right after table binding); it's numbered last, out of order, because it was added last | Looks for `and <word>` / `plus <word>`; if `<word>` doesn't name a table, column, known non-column word or a value a column actually holds, and it ends in `s`, the question declines instead of quietly building against only the first table | the pattern matches an unbound, plural-shaped word. Example: `customers and suppliers` with no `suppliers` table declines naming "suppliers", instead of silently building `SELECT * FROM customers;` |

#### Reading what is being asked

| # | Rule | What it does | Declines when |
|---|---|---|---|
| 7 | Verb | `delete` or `remove` as a whole word **opening** the question (optionally after `please`) means `DELETE`; anything else means `SELECT` | a delete would span two joined tables, or the whole word `delete`/`remove` stands anywhere else in the question — too ambiguous to call either way |
| 8 | Count | `count`, `how many`, `total number`, `number of` produce `COUNT(*)` | — |
| 9 | Explicit projection | `only X` / `just X` / `X of <table>`. The last one isn't used if the question also asks for an average, sum, max or min. The phrase is split on commas and `and`, filler words are dropped (`show`, `the`, `all` …), and each piece is bound; a multi-word piece that fails whole is tried again on its last word | any requested field is not a column of the subject table. It never falls back to `SELECT *` and quietly drops what was asked for |
| 10 | Implicit projection | `customer city`, `customer first name`: a column phrase directly after the table noun. It only runs when rules 8 and 9 didn't, and only if the phrase runs to the end of the sentence, is at most three words, and contains no preposition, verb or table name. Those limits keep `customers in pune` a filter rather than a failed projection | the phrase looks like a column but matches none, or matches two equally well |
| 11 | Aggregate | `average/avg/mean`, `sum`, `maximum/max/highest`, `minimum/min/lowest` + a column produce `AVG` / `SUM` / `MAX` / `MIN`. Skipped when rule 9 or 10 already chose columns. `total` is deliberately not read as `SUM`, because it's a common column name | the column isn't one of the subject table's |
| 12 | Grouping | `per`, `for each`, `grouped by`, `group by` + a column. The column goes into the projection, so `GROUP BY` covers it | the column isn't one of the subject table's |
| 13 | Comparison | `<column> more / greater / larger / higher than, above, over N` → `>`; the less-than forms, `below` and `under` → `<`. `N` can be a digit or a word from one to ten. If the column binds, this becomes a `WHERE` condition. If it doesn't bind but a join exists, it becomes `HAVING COUNT(<related table's first column>)`: "customers who placed more than three orders" | `N` isn't a number, or there's no join and the column doesn't bind |
| 14 | Filter from the data itself | One or more words after `from`, `in`, `at`, `located in`, `living in`, `based in`, `residing in`, joined by `and`. Stopwords, table names and column names are skipped. The engine looks for the subject-table column whose **actual distinct values** contain each word (ignoring case), writing `<column> = '<word>'` for one value and `<column> IN ('a', 'b')` for several. A word after `and` that is not a value ends the list | the first value matches no column of the subject table, or two values match **different** columns |
| 15 | `with <column> <value>` | An explicit column/value pair. It only runs when rules 13 and 14 produced no `WHERE`, and comparison phrasing (`with more than …`) is excluded first. Numbers are left unquoted | the named column is not a column of the subject table |
| 16 | Building the column list | Picked in this order: the grouping column first; then the aggregate; else the key + `name`/`title`/`label` column + `COUNT` for rule 13's `HAVING`; else `COUNT(*)`; else the chosen columns; else `*`. With a join, every column is prefixed with its table name | — |

#### Safety checks

| # | Rule | What it does | Declines when |
|---|---|---|---|
| 17 | No unconditional delete | — | a `DELETE` would have no `WHERE` |
| 18 | Columns must exist right now | Checks every matched column against the live database's column list. If that list can't be fetched, the check is skipped rather than treated as a failure | a column is in the active schema but not in the database |

#### Ordering and output

| # | Rule | What it does | Declines when |
|---|---|---|---|
| 19 | Order by | `order by / ordered by / sort by / sorted by <column>`, plus optionally `asc`/`ascending`/`desc`/`descending`; the default is `ASC` | the sort column isn't one of the subject table's |
| 20 | Limit | `top N`, `first N`, `limit N` (digits only) | — |
| 21 | Writing the SQL | Fixed clause order: `SELECT` / `DELETE` → `FROM` → `JOIN … ON` → `WHERE` (conditions joined by `AND`) → `GROUP BY` → `HAVING` → `ORDER BY` → `LIMIT` → `;`. **`GROUP BY` isn't matched from the question; it's derived** as every column in the SELECT list that isn't aggregated. That always satisfies MySQL's `ONLY_FULL_GROUP_BY`. So a bare `COUNT(*)` gets no `GROUP BY` at all | — |
| 22 | Rule trace | Every result, built or declined, carries what each phrase matched, the query shape (`SELECT + JOIN + GROUP BY + HAVING`), the foreign key used, the checks it passed, or the decline reason | — |

**The 19 ways to decline,** grouped: 3 gates (rules 1–3), 4 about tables and
joins (none, too many, no key, two keys), 1 second noun binding to nothing
(rule 23), 1 delete across a join, 7 where a named thing doesn't match a
column (explicit projection, implicit projection, aggregate, grouping,
comparison, filter value, sort), 1 comparison against something that isn't a
number, and 2 safety checks (unconditional delete, a column missing from the
database).

### 9.2 Routing between the three layers — 3 rules

| # | Rule | What it does |
|---|---|---|
| 1 | Layer order | Rule engine → cache → local LLM, tried in that order. Confidence is a fixed label per layer: **0.95 / 1.00 / 0.65**, or **0.40** when rule 3 fires |
| 2 | Cache match | Matches on the question **and** the target database, ignoring case, so the same sentence asked for MySQL and for MongoDB can't pick up each other's cached answer |
| 3 | LLM incompleteness check (MySQL only) | Two signals. (a) The question names more than one table and the SQL doesn't reference them all. (b) The question has a second-condition word (`with`, `who have/placed/has`, `and`) **and** a number or comparison, but the SQL has no `JOIN`, `HAVING`, `GROUP BY` or `AND`. Either one lowers the confidence to 0.40. The query is still returned |

### 9.3 Pre-EXPLAIN checks — 2 rules (`SqlPreflightValidator`)

Both run on the SQL text before MySQL sees it. Either one stops the query
with a specific message.

| # | Rule | Blocks when |
|---|---|---|
| 1 | Strict GROUP BY | a query with `GROUP BY` selects a column that is neither grouped nor inside a function (what MySQL would reject as error 1055) |
| 2 | Column exists | a `SELECT` or simple `WHERE` column isn't on the table (error 1054). Skipped when the column list can't be fetched |

### 9.4 EXPLAIN warnings — 4 rules (`MySqlExplainAnalyzer`)

Applied to each table in the plan. The same table never gets the same warning twice.
They report what MySQL chose; they don't claim it was wrong.

| # | Fires when | Says |
|---|---|---|
| 1 | the table was scanned in full | full scan on this table; check for an index if the query is slow |
| 2 | no index was used, but it wasn't a full scan | no index used, with the access type and which indexes MySQL considered |
| 3 | `Using temporary` | a temporary table was built for GROUP BY / DISTINCT |
| 4 | `Using filesort` | results were sorted after reading; an index matching ORDER BY may help |

### 9.5 MongoDB checks — 2 rules (`MongoSimpleAnalyzer`)

| # | Fires when |
|---|---|
| 1 | the first filter field that isn't a `$` operator is missing from the **placeholder** index list (`_id`, `email`, `userId`) |
| 2 | a `$regex` value doesn't start with `^`, so no index can serve it |

Both read the query text and neither runs it. **Under review since 2026-09-25**
— if MongoDB parity is built, `listIndexes()` replaces the placeholder in rule
1 and a real `explain()` reader replaces both. See §5.

### 9.6 Analysis verdict — 9 rules (`VerdictService`)

The rules are tried in order and the first match becomes the headline. "Large" means at least
`querysense.verdict.large-table-rows` rows (default **1000**). "Discarded"
comes from MySQL's `filtered`: under 50% kept means most of what was read was thrown away.

| # | Condition | Grade |
|---|---|---|
| 1 | EXPLAIN never ran | `UNKNOWN` — never shown as a pass |
| 2 | the plan reads no table | `INFO` |
| 3 | large full scan, most rows discarded | `CRITICAL` |
| 4 | large full scan, `filtered` not reported | `WARNING` |
| 5 | MySQL listed a usable index and used none | `WARNING` |
| 6 | filesort or temporary table over a large row count | `WARNING` |
| 7 | large full scan that returns most of what it reads | `INFO` — the scan is the right plan here |
| 8 | small full scan | `INFO` |
| 9 | anything else (the rows were found through an index) | `INFO` |

### 9.7 Domain optimisation — 7 rules

One rule set, the transactional one, run on every analysis. A row count has
to be **above** 100 to count as large.

**Removed 2026-09-11: the domain picker.** The Workbench used to offer a
Domain dropdown choosing between three sets — e-commerce (6 rules, threshold
1,000), analytical (5 rules, threshold 10,000) and transactional. Schemas are
now uploaded directly, so no workload is declared. The dropdown, the
`domainProfile` request field, the `DomainProfile` enum and the two sets only
the dropdown could reach were deleted. Transactional was already the fallback
for no profile or `Other`, so it is what remains. The deleted classes are in
git history.

**What still carries the word, verified by grep on 2026-09-25 — names, not
behaviour.** `DomainOptimizationService` (which now hardcodes
`new TransactionalOptimizationRules()`), the `DomainOptimizationRules`
interface it runs, `OptimizationSuggestion.Source.DOMAIN` and its
`domain(...)` factory, and the `DOMAIN: 'Optimization'` label in
`SuggestionsList.jsx`. Renaming them would churn the response contract and the
frontend for no behavioural gain, so they stay. **The only other `domain` in
the tree is unrelated:** `ValueGenerator.DOMAINS`, the list of e-mail hosts the
seeder picks from. No `domainProfile`, `DomainProfile`,
`EcommerceOptimizationRules` or `AnalyticalOptimizationRules` survives
anywhere in `backend/src` or `frontend/src`.

**Transactional — 7 rules (threshold 100):**

| # | Fires when | Severity |
|---|---|---|
| 1 | full table scan | `CRITICAL` if above 100 rows, else `WARNING`. There's no "small table" exception |
| 2 | filesort | `CRITICAL` |
| 3 | temporary table | `CRITICAL` — may not belong on a transactional path |
| 4 | no index used, and not a full scan | `WARNING` |
| 5 | `const` or `ref` access through an index | `INFO` — the ideal shape for this workload |
| 6 | Mongo: unindexed filter | `CRITICAL` |
| 7 | Mongo: unanchored regex | `CRITICAL` |

### 9.8 Resource optimisation — 5 rules (`ResourceConsciousService`)

The resource setting defaults to **medium** when none is given. The threshold is **5,000** rows, and a count has to be above it.

| # | Rule | Effect |
|---|---|---|
| 1 | filesort above 5,000 rows | low `CRITICAL` (likely spills to disk) · medium `WARNING` · high `INFO` |
| 2 | temporary table (any size) | low `CRITICAL` · medium `WARNING` · high `INFO` |
| 3 | full scan above 5,000 rows, low resources | `WARNING` (pressure on the buffer pool) |
| 4 | Mongo: unindexed filter, low resources | `CRITICAL` |
| 5 | Severity shift | every **domain** suggestion moves one step: up under low, unchanged under medium, down under high, capped between `INFO` and `CRITICAL`. The resource module's own suggestions aren't shifted |

### 9.9 Schema advisor — 5 rules (`SchemaAdvisorServiceImpl`)

A recommendation only appears when the specific problem it fixes is present.
The advisor never executes its own DDL (§5).

| # | Fires when | Recommends |
|---|---|---|
| 1 | full table scan **and** no index used | `INDEX`: `CREATE INDEX idx_<table>_<column>` on the filtered column |
| 2 | filesort | `INDEX` on the ORDER BY column. If the query also filters on another column, it says that a composite `(filter, sort)` index may be needed, and to check again with EXPLAIN |
| 3 | temporary table | `STRUCTURAL`: review the GROUP BY / DISTINCT. There's deliberately no ready-made statement, because no single index reliably fixes this |
| 4 | Mongo: unindexed filter | `INDEX`: `db.<collection>.createIndex({ <field>: 1 })` |
| 5 | Mongo: unanchored regex | `STRUCTURAL`: anchor with `^`, or use a text index |

### 9.10 What reading the rules turned up

All of these came from reading the code. **The three dated 2026-09-21 were
fixed**; the accounts are kept because the shape recurs. The fourth is a lead,
not yet a finding.

- **UNCONFIRMED: a table name that cannot be bound may be dropped without a
  word.** Seen on 2026-09-25 while the built-in schema was temporarily shrunk
  to two tables: `customers and suppliers`, with no `suppliers` in the schema,
  built `SELECT * FROM customers;` at 0.95 and never said it had ignored a
  noun — the same silence as the `with <column> <value>` defect below. **It has
  not been reproduced against the real nine-table schema**, where `suppliers`
  binds and the pair declines as unrelated instead, so the honest statement is
  that an unbindable *second* noun is worth a test, not that the defect is
  established. Confirm before acting on it.

- ~~**Only the first filter value is kept, and the rest is dropped without a
  word.**~~ **FIXED.** Rule 14 captured one word after the preposition and
  broke out of its loop, so `customers in mumbai and pune` built
  `WHERE city = 'mumbai'` at confidence 0.95 and lost `pune`. The pattern now
  reads a list joined by `and` and emits `IN` for more than one value — the
  behaviour §15 of `backend/CLAUDE.md` records as Bug 10 in the engine this one
  replaced, which had not carried over. Two guards bound it: a word after `and`
  that is not a value ends the list rather than declining, and values resolving
  to different columns decline. A multi-word value like `new delhi` still
  cannot be matched, and still declines rather than guessing.
- ~~**`with <column> <value>` is the only phrase that fails silently.**~~
  **FIXED.** Rule 15 skipped an unbindable column instead of declining, so
  `customers with gender female` on a schema without a `gender` column came
  back as every row, labelled `RULE_ENGINE`. It now declines with the reason.
  Comparison phrasing is excluded before the check, so `customers with more
  than three orders` still builds its join and `HAVING` rather than hunting for
  a column called `more`.
- ~~**Rule 7 found its verb inside other words.**~~ **FIXED 2026-09-21**, from
  `RULES.md`'s own gap list. It tested `text.contains("delete") ||
  text.contains("remove")`, so `show removed customers in mumbai` built
  `DELETE FROM customers WHERE city = 'mumbai';` at confidence 0.95, and `show
  deleted customers` was declined as "asks to delete rows without saying which
  ones" — a read rejected as a malformed write. The verb must now be a whole
  word opening the question, optionally after `please`; the same word standing
  anywhere else declines, because neither reading is safe to assume. The engine
  never runs generated SQL, so this was wrong rather than dangerous.

### 9.11 Query rewrite — 3 rules (`QueryRewriter`)

Added 2026-09-21, behind `POST /api/query/compare`. **Every rule preserves the
rows the query returns.** That is the design constraint, not a coincidence: a
rewrite that returns different rows is a different query, so a rule that cannot
guarantee identical rows declines instead of firing. This is why there is no
"add a LIMIT" rule, which the deleted browser simulation had — capping an
unbounded result set changes the answer.

All three are MySQL-only and SELECT-only; a write statement, an unparseable
string or a MongoDB query comes back untouched.

| Rule | Fires when | Rewrites to | Why it is safe |
|---|---|---|---|
| **RW1** | `SELECT *`, single table, no join, table in the active schema | the schema's column list | Same columns, named explicitly — lets a covering index answer without reading the row |
| **RW2** | `WHERE` is an `OR` chain of equality tests on **one** column | `col IN (…)` | Identical predicate; MySQL can range-scan an index for `IN` where an `OR` chain often falls back to a scan |
| **RW3** | `ORDER BY` on a bare aggregate (`COUNT`/`SUM`/`AVG`/`MIN`/`MAX`), no `GROUP BY`, no `LIMIT` | the same query without `ORDER BY` | The query returns one row, so the sort could not change the result — it only cost a filesort |

**Where each one declines, and why it matters:**

- **RW1** declines on a join, because `*` spans more than one table there and
  the expansion would have to guess at ordering and duplicate column names. It
  also declines when the table is not in the active schema — the case that
  would otherwise invent columns.
- **RW2** declines when the `OR` spans two different columns (not the same
  predicate), and on a single equality (a one-element `IN` is noise, not an
  optimisation). It walks the `OR` tree and bails on **anything** that is not
  `column = constant`, so an `AND` nested inside, or a column-to-column
  comparison, stops it rather than being silently dropped.
- **RW3** keeps `ORDER BY` when a `GROUP BY` is present, where it orders the
  groups and is load-bearing, and on any non-aggregate query, where it is the
  point of the query.

⚠️ **`IN` lists need `ParenthesedExpressionList`, not `ExpressionList`.**
JSQLParser 5.3's plain `ExpressionList` renders without parentheses, so the
emitted SQL was `WHERE city IN 'mumbai', 'pune'` — not merely ugly but
**invalid**. Two of the 14 `QueryRewriterTest` cases assert the parentheses for
exactly this reason; nothing about the object graph reveals the problem.
