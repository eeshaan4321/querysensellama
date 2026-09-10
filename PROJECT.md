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

**Row counts gate how much the optimisation modules can say.** The
thresholds are `TRANSACTIONAL` 100, `ECOMMERCE` 1000,
`ResourceConsciousService` 5000 and `ANALYTICAL` 10000, all exclusive, and
the built-in tables clear none of them.

**Uploading a schema no longer changes that by default.** `seed-rows` was
12000 — chosen to clear all four, and verified live doing so (§7) — and was
lowered to **100 on 2026-09-10** at the user's direction, because a sample
database should not leave twelve thousand rows per table sitting in MySQL.
At 100 an upload clears no threshold either, so every domain suggestion
reads "the estimated row count is low, so the performance impact is likely
minor" and `ResourceConsciousService` emits nothing of its own.

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
handled. **It still declines every MongoDB request outright**, so with the
target set to MongoDB every question goes to the LLM.

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

### The pipeline is four requests, not seven

The UI shows seven screens. It makes four HTTP calls, and one of them runs
once at startup rather than per query:

```
0. GET  /api/schema/active           → which schema is live (once, at app start)
1. POST /api/query/generate          → Build query
2. POST /api/query/analyze           → Analyse
                                       ├── Domain rules      (no request)
                                       └── Resource policy   (no request)
3. POST /api/query/schema-advisor    → Schema advice
   (reuses step 3)                   → Optimization, real half
   (no request)                      → Optimization, simulated half
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

**The simulated half is the one place the UI shows invented numbers.**
`src/api/comparison.js` has no endpoint to call, so it always returns
generated data regardless of `VITE_USE_MOCK`. Treat the before/after diff and
the cost chart as a preview of a feature, not a measurement — the schema
changes above them on the same screen are real.

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
│                          History, About
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
rule downstream — domain, resource and schema advisor alike — fires on
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

**Section 2, `SuggestionsList` — domain and resource advice.** This is the
part with no equivalent anywhere else in the app, and the project's most
defensible novelty claim: identical `EXPLAIN` facts, read differently
according to declared context. Same query, same plan, three domains:

| Domain | Reading |
|---|---|
| E-commerce | filesort framed as a product-listing "sort by" |
| Analytical | full scan "often acceptable for analytical queries" — **and no filesort complaint at all** |
| Transactional | "should be simple key lookups" — escalated to `CRITICAL` |

The analytical profile actively *withholds* a warning the others raise,
which is the clearest demonstration that these are separate rule sets and
not one rule set with different wording.

**Do not confuse this section with the Optimization screen's "Simulated
domain rules" list.** They look alike and are framed alike. This one is
`/api/query/analyze`; that one is `fixtures.js`.

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

   **This is now stated by the screen's shape rather than only in its
   caption.** The Optimization page was split on 2026-09-10 into a real half
   (the schema advisor's recommendations, with the DDL to run) above a
   simulated half (this rewrite preview, behind the amber badge). The two
   optimise different things: the advisor changes the *database* and leaves
   the query alone; the preview changes the *query* and leaves the database
   alone. Only the first has a backend behind it. See §7 for what the split
   removed.

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

5. **MongoDB is barely supported**, and schema upload does nothing for it.
   The upload pipeline is MySQL-only end to end: it emits MySQL DDL, seeds
   through JDBC and reads MySQL `EXPLAIN`. Uploading a schema does not give
   the Mongo path a collection, an index, or a grounded prompt.
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
2. **Still open.** Add a schema-conformance check over generated identifiers
   before the result is returned *or* cached, so a query naming an unknown
   table/column is never written to history. Until this exists, a
   hallucinated query is still cached and still served back at confidence
   1.0 — and note that an uploaded schema widens the surface, since
   `SqlPreflightValidator` now validates against whatever schema is live.
3. **Still open.** Decide explicitly what MongoDB grounding should look like,
   rather than leaving it as an inherited no-op. Schema upload does not help
   here; see §5.5.

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
- **Row counts on an uploaded schema are generated, not measured.** The plan
  around them is real MySQL output — the access path, the index choice, the
  join order — but the rows those numbers count were produced by
  `SchemaSeeder`. The status bar says "generated data" whenever an upload is
  active and the analysis screen repeats it; if you are quoting a figure from
  a demo, check which schema was live first.
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
- **Numbers in the Optimization screen's lower half are invented.** The
  rewrite preview has no endpoint and never did; it is simulated even in real
  mode. The schema changes in the upper half of the same screen are real
  `/api/query/schema-advisor` output — the divider and the amber badge are
  the only thing separating them, so read which half a figure came from
  before quoting it.
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

#### Four defects closed

- The `order_items` mis-resolution (§5) — `SchemaBinderTest.orderItemsBeatsOrders`.
- The dropped `per <column>` grouping (§8).
- The hardcoded `city` filter column — **not previously recorded**.
- The inert known-value safety net on uploaded schemas — **not previously
  recorded**.

#### Verified

- `./mvnw test` — **229 tests, 0 failures** (174 before, all green at the
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

#### Not verified

**Nothing in this session was run against a live backend or in a browser.**
No query was generated end to end, no real MySQL `EXPLAIN` ran against a
rule-built join, and the `RuleTracePanel` has never been rendered by a browser.
The evidence is unit tests, a production build and a clean lint — nothing more.
The user tests personally; this is theirs to confirm.

Specifically worth exercising first: a join question against an **uploaded**
schema, since the join path has only ever run against the built-in demo
schema's foreign keys in tests.

### Not verified

- ~~**No screen of the current frontend has been exercised against the live
  backend in a browser.**~~ **Partially retired 2026-09-10** — see the schema
  upload session above, and the Optimization session after it. Workbench,
  Analysis, Schema source and **Optimization** have now been driven in a real
  browser against the live backend, with a console check. **Schema advisor,
  History and Architecture still have not been** — note that Optimization
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

### Behavioural shift worth watching

Widening `KnownSchema` from two tables to nine widened the `tableCount > 1`
complexity gate along with it. Questions like "products in a category" or
"employees in a department" now mention two known tables and route to the LLM,
where before neither table existed and they were handled or declined on other
grounds. **More traffic depends on Ollama than it used to.** With Ollama down,
more questions now fail with a 503 than did under the two-table schema.

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
| 2 | Show code for optimization on the interface | **Built** — for schema DDL; the query rewrite is still simulated |
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

**The distinction to keep straight when demoing:** *schema-change* code is
real and comes from the backend. *Query-rewrite* code — the before/after diff
in the lower half — is generated by a regex in the browser, because the
Comparison Engine does not exist (§5). If review meant "show us the optimised
**query**", that is the simulated half and is not built.

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
the system reports. Demoing the LLM and the cache with one question shows
both tiers working and one wrong answer being promoted from 0.65 to 1.00.
Pick the demo question deliberately.

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
breakdown on the Analysis screen. Simulated and clearly labelled as such: the
query rewrite and its before/after numbers.

Every figure the Analysis screen shows is an optimiser **estimate**. The
analyzer runs `EXPLAIN` and never executes the query — a decision reaffirmed
on 2026-09-10 when `EXPLAIN ANALYZE` was considered and declined (§8 item 3).
