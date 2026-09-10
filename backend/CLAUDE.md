# QuerySense — Full Project Context for Claude Code

This file is the persistent context bridge for Claude Code sessions on this
repo. It's derived from design discussions and documentation that live
outside this codebase (synopsis, literature review, feature-justification
mapping, and team planning). Read this fully before making architectural
decisions — not just before writing a single class.

---

## 1. Project Overview

**QuerySense** — A Structured and Unstructured Data Query Generation,
Optimization and Advisory System based on Domain Knowledge. Final-year
capstone project. SDG 9 and SDG 4 aligned.

**Team:** Vedant Desai (A014), Eeshaan Garkhedkar (A020), Anant Kekre (A030).
**Mentor:** Prof. Dhanashree Huddedar.
**Repo:** querysense-backend (default branch: `master`).

**What it does:** Lets users write queries in natural language, which get
translated into executable SQL (MySQL) or MongoDB queries. The system then
analyzes the query's real execution behavior, applies rule-based
optimization strategies, and gives schema/index recommendations — combining
domain-aware logic (e-commerce / analytical / transactional) with
resource-aware logic (low / medium / high system resources).

**Why it exists (the gap in existing tools):** Tools like MySQL EXPLAIN,
Oracle SQL Tuning Advisor, and SQL Server DB Engine Tuning Advisor analyze
execution plans but lack: domain-specific optimization, natural language
query generation, resource-aware perspectives, and unified structured +
semi-structured (SQL + NoSQL) support in one system. QuerySense's pitch is
filling that gap with a lightweight, explainable, rule-based-first system.

---

## 2. Core Architecture Philosophy — READ THIS BEFORE CHANGING ANYTHING

**Hybrid, rule-based-first, with LLM as fallback only.**

- The rule-based engine (MySQL EXPLAIN analysis, JSQL Parser structural
  parsing, domain/resource profiles) handles the majority of queries.
- A local LLM (`qwen2.5-coder:7b` on Ollama) is invoked ONLY when the rule
  engine declines a query — never on a query the rules can handle. It is
  the *only* place in the system that uses a model at all; analysis,
  optimization and schema advice are entirely rule-based.
  **→ Section 16 documents the fallback in full: the six conditions that
  trigger it, the model, the prompt, the response handling, and its known
  weaknesses. Don't restate those details here; link to them.**
- This is a **deliberate, defensible design choice**, not a limitation.
  If a reviewer/panelist challenges "why not just use an LLM for
  everything," the answer is: rule-based systems are interpretable,
  explainable, and don't require trusting a black box — literature
  (Papers #2, #4, #6 below) backs this as a legitimate architecture, not
  a corner cut. **Do not suggest replacing this with a pure-LLM approach.**
- Open architectural question not yet fully resolved: whether LLM fallback
  should apply to SQL queries, MongoDB queries, or both. Currently it
  applies to both (`OllamaService` handles dialect-aware prompting for
  either). Flagged as a likely panel question — team's stance is to
  defend the current dual-dialect fallback design in Q&A rather than
  restrict it. Be aware when defending it that the Mongo side is measurably
  the weaker path (Section 16.8).

---

## 3. Tech Stack

**Backend:** Java 17+ (project currently built against Java 21), Spring
Boot 3, MySQL 8+, MongoDB, JDBC/HikariCP, JSQL Parser 5.3, Maven.
**Frontend:** React (Vite), Axios, CSS/Tailwind. Built by Claude Code in a
**separate sibling repo**, `../frontend` (not a folder in this repo, and
not pushed anywhere yet — it's the user's own project to iterate on and
push when ready). See Section 14 for full detail: it's wired to four of
this repo's real endpoints (`/generate`, `/analyze`, `/schema-advisor`,
`/history`), with a `VITE_USE_MOCK` flag to fall back to mock data for
anything not built yet (the Comparison Engine, and any MongoDB-specific
optimization preview).
**LLM:** `qwen2.5-coder:7b` running locally on **Ollama**, via
`OllamaService` behind the `LlmFallbackService` interface — fully offline,
no API key. Full detail in **Section 16**; per-machine setup in Section 12.

---

## 4. Project Objectives (from synopsis — do not silently revise scope)

1. Rule-based NL query builder → SQL and MongoDB.
2. Query analysis engine: MySQL EXPLAIN analysis + simple MongoDB checks.
3. Domain-sensitive optimization module (e-commerce, analytical,
   transactional).
4. Resource-conscious decision system (low/medium/high resource levels).
5. Schema analysis and recommendations (indexing, normalization,
   structural additions).
6. Optimal query suggestions + before/after comparison.
7. History module (queries, optimizations, suggestions).

**Scope boundaries (explicitly out of reach for this project):**
machine-learning-based optimization, automatic schema modification,
distributed database systems, production-scale deployment. Domain
awareness is restricted to three fixed profiles; resource levels are
simulated categories (low/medium/high), not live system monitoring.

**Team's stance on scope challenges:** if a reviewer questions whether the
objectives should be revised or narrowed, the agreed response is a verbal
Q&A defense of the existing hybrid architecture — not scope revision.

---

## 5. Team Task Division

- **Anant** — Query Analysis Engine. **STATUS: COMPLETE**, merged to
  `master`. See Section 7 for full detail.
- **Eeshaan** — Domain-Sensitive Optimization Module + Resource-Conscious
  Decision System. **STATUS: COMPLETE**, merged to `master` (feature
  branch `feature/domain-optimization`, merge commit `82b534d`). Consumes
  `QueryAnalysisResult`. See Section 7 for full detail, including an
  integration gap (the modules existed but nothing called them) that
  Claude Code found and fixed after the merge — Section 14 has the story.
- **Vedant** — Schema Advisor + MongoDB Query Builder, then Comparison
  Engine last (depends on the other pieces existing). See Section 8.

The dependency structure: Anant's `QueryAnalysisResult` is the shared
contract everyone else builds against. Eeshaan and Vedant can (and should)
mock this object by hand to build/test their own rule logic without
waiting on each other.

---

## 6. Database Schema (ER diagram — six entities)

Entities: **USER, QUERY, ANALYSIS_RESULT, OPTIMIZATION_SUGGESTION,
SCHEMA_RECOMMENDATION, HISTORY_LOG**, with PKs/FKs/relationships modeled
in a draw.io ER diagram (finalized).

`QueryHistory.java` gained a `source` column (`RULE_ENGINE`/`CACHE`/
`GEMINI`) on 2026-09-06 — see Section 14. `spring.jpa.hibernate.ddl-auto=
update` means this was added to the live `query_history` table
automatically on next app start; no manual migration was needed.

⚠️ **Gap to flag:** the exact column-level field lists for each entity
beyond `QUERY_HISTORY` (which exists in code as `QueryHistory.java`) are
not fully captured in this file. Before building `SchemaRecommendation` as
a JPA entity, check the actual draw.io ER diagram (ask the team for the
current export) rather than inventing field names that might not match
what's already been designed and possibly presented to the mentor.

**Diagram visual conventions** (for any diagram work, draw.io/Mermaid):
teal = core rule-based components, amber = LLM Fallback Service,
blue = frontend/API, purple = data access, red/pink = data layer and
error handling.

---

## 7. Current Implementation Status (as of this file's writing)

### Anant's Query Analysis Engine — COMPLETE, tested, merged to `master`

- `dto/QueryAnalysisResult.java` — the shared contract (see Section 9).
- `analysis/SqlStructureParser.java` — JSQL Parser-based structural
  parsing (table name, JOIN/WHERE/GROUP BY/ORDER BY detection).
- `analysis/MySqlExplainAnalyzer.java` — runs real `EXPLAIN` via
  `JdbcTemplate`, reads `type`/`key`/`rows`/`Extra` columns. **Verified
  with a real before/after test**: unindexed query on `customers.city`
  showed `accessType: ALL, usesIndex: false`; after `CREATE INDEX
  idx_city`, the same query showed `accessType: ref, usesIndex: true,
  keyUsed: idx_city`, with `estimatedRows` dropping 3→2. This is the
  literal proof-of-concept underpinning the whole "evidence-grounded
  recommendations" pitch to the panel.
- `analysis/MongoSimpleAnalyzer.java` — lightweight regex-based checks:
  unindexed filter field (against a **hardcoded placeholder list**:
  `_id`, `email`, `userId` — not real index metadata, acceptable for MVP
  scope but worth being honest about if asked), and unanchored `$regex`
  (missing `^` anchor, which prevents index usage in MongoDB regardless
  of MongoDB version).
- `service/QueryAnalysisService.java` (interface) +
  `service/impl/QueryAnalysisServiceImpl.java` — orchestrates structural
  parsing → EXPLAIN (MySQL) or Mongo checks (MongoDB) → merges results.
- `controller/QueryAnalysisController.java` — exposes
  `POST /api/query/analyze`.
- `ExplainTestController.java` was a **temporary manual-test-only**
  controller and should be deleted if still present — fully superseded.

### Vedant's Schema Advisor — COMPLETE, tested, on branch `feature/schema-advisor`

- `dto/SchemaRecommendation.java` — the DTO (`type`, `targetTable`,
  `targetColumn` nullable, `reason`, `suggestedAction`). Not yet
  reconciled against the `SCHEMA_RECOMMENDATION` ER entity (no
  column-level field list for that entity could be found in the repo —
  flagged in the DTO's own Javadoc as provisional).
- `service/SchemaAdvisorService.java` (interface) +
  `service/impl/SchemaAdvisorServiceImpl.java` — five rules: MySQL full
  table scan without index, filesort, temporary table; MongoDB
  unindexed filter, unanchored `$regex`. Includes a MonoM-style
  consistency guard (Paper #8) — an index is only ever recommended
  against the specific problem flag that justifies it, never suppressed
  wholesale just because `usesIndex` is true elsewhere in the query.
- `controller/SchemaAdvisorController.java` — exposes
  `POST /api/query/schema-advisor`, same request shape as `/analyze`.
- **Column-name gap closed:** `QueryAnalysisResult` didn't expose which
  column a WHERE/ORDER BY clause targets, so two fields were added —
  `filteredColumn` and `orderByColumn` — populated by `SqlStructureParser`
  (from the JSQL Parser AST it already builds) and `MongoSimpleAnalyzer`
  (from the field name its regex already matches), rather than having
  the Schema Advisor re-parse the raw query string itself.
- **Bug caught during verification (now fixed):**
  `QueryAnalysisServiceImpl.mergeResults` only copied `warnings` from the
  structural result into the EXPLAIN result, silently dropping
  `filteredColumn`/`orderByColumn` for every MySQL query. Fixed by
  copying those two fields across in the merge as well.
- **Verified with a real before/after test**, same pattern as Anant's
  `idx_city` proof: `SELECT * FROM customers WHERE email = 'alice@test.com'`
  showed `accessType: ALL, usesIndex: false` and one `INDEX`
  recommendation on `email`; after `CREATE INDEX idx_customers_email`,
  the same query showed `accessType: ref, usesIndex: true,
  keyUsed: idx_customers_email`, and the recommendation list became `[]`.
  Temporary-table and both MongoDB rules were also verified firing for
  real (not just compiling) — the temp-table code path had never
  actually executed until `GROUP BY name` (unindexed) on the enlarged
  dataset produced a genuine `usesTemporaryTable: true`.
- **Filesort rule — honest finding, not a guaranteed fix:** verified live
  that a single-column index on the ORDER BY column does NOT reliably
  make MySQL drop `Using filesort`. Tested with `customers` enlarged from
  3 to 303 rows specifically to rule out "table too small to matter" as
  an excuse: `WHERE city='Mumbai' ORDER BY name` kept using `idx_city`
  for the filter and still needed a separate sort pass even after
  `idx_customers_name` was created (MySQL can only use one index per
  table access — a composite `(city, name)` index would be needed
  instead), and a pure `ORDER BY name` with no WHERE clause still chose
  a full scan + filesort over the index because `SELECT *` needs every
  column and a secondary-index scan would mean a per-row lookup back to
  the primary key. Rather than chase the optimizer further or silently
  claim success, `SchemaAdvisorServiceImpl`'s filesort recommendation now
  says so explicitly in its `reason` text (composite-index note when a
  different filtered column is also present; a "verify with EXPLAIN,
  don't assume it's fixed" note otherwise) — matching Paper #5's caveat
  in this same file about not presenting EXPLAIN-based estimates as
  guaranteed results. No suppression of the recommendation itself; the
  suggestion stays, framed honestly.
- **Shared dev database state changed** (affects Anant's/Eeshaan's local
  MySQL too, since it's the same `querysense` database): `customers` grew
  from 3 rows to 303 (needed to prove the filesort finding above wasn't
  just a "table too small" artifact) and gained two new indexes,
  `idx_customers_email` and `idx_customers_name`, alongside the
  pre-existing `PRIMARY` and `idx_city`.
- **Known gap carried over, not introduced here:** MongoDB
  recommendations have `targetTable: null` because
  `QueryAnalysisServiceImpl` always calls `MongoSimpleAnalyzer.analyze()`
  with `collectionName=null` — pre-existing, will be addressed once the
  MongoDB Query Builder (Task B) exists.

### Eeshaan's Domain-Sensitive Optimization + Resource-Conscious Decision System — COMPLETE, merged to `master`

- `enums/DomainProfile.java` (`ECOMMERCE`/`ANALYTICAL`/`TRANSACTIONAL`),
  `enums/ResourceLevel.java` (`LOW`/`MEDIUM`/`HIGH`),
  `enums/SuggestionSeverity.java` (`INFO`/`WARNING`/`CRITICAL`) — new
  enums backing both modules.
- `dto/OptimizationSuggestion.java` — the shared output unit both modules
  produce (`message`, `severity`, `source: DOMAIN|RESOURCE`). Factory
  methods `OptimizationSuggestion.domain(...)` /
  `.resource(...)` so rule classes don't repeat `Source.DOMAIN` everywhere.
- `dto/AnalyzeResponse.java` — wraps `QueryAnalysisResult` together with
  the merged `List<OptimizationSuggestion>` for `/api/query/analyze`.
- `dto/AnalyzeRequest.java` — gained `domainProfile` (`@NotNull`) and
  `resourceLevel` (`@NotNull`) fields alongside the original
  `query`/`databaseType`, since the two new modules need to know which
  rule set / threshold tier to apply.
- `optimization/DomainOptimizationRules.java` (interface) +
  `EcommerceOptimizationRules.java` / `AnalyticalOptimizationRules.java` /
  `TransactionalOptimizationRules.java` — one rule set per profile, each
  branching on `databaseType` to read only the dialect-appropriate
  `QueryAnalysisResult` fields (MySQL: `accessType`/`hasFullTableScan`/
  `usesFilesort`/`usesTemporaryTable`/`keyUsed`/`estimatedRows`; MongoDB:
  `hasUnindexedFilter`/`hasUnanchoredRegex`).
- `optimization/DomainOptimizationService.java` — dispatches to the
  correct rule set via an `EnumMap<DomainProfile, DomainOptimizationRules>`;
  a null profile defaults to `TRANSACTIONAL` as "the strictest, safest
  default" (its own documented choice — the frontend's "Other" domain
  option reuses this exact fallback rather than inventing a new one).
- `optimization/ResourceConsciousService.java` — two independent
  responsibilities: `evaluate(...)` produces its own memory-pressure
  suggestions (filesort/temp-table/full-scan spilling to disk under LOW
  resources), and `adjustSeverity(...)` re-scales severities the
  *domain* module already produced (escalate under LOW, unchanged under
  MEDIUM, de-escalate under HIGH) without mutating the input list.
- Literature grounding already covered in Section 10: Song et al. 2023
  (HTAP workload-aware optimization) for keeping the three domain profiles
  as separate, non-merged rule sets; "How Good Are Learned Cost Models,
  Really?" for keeping resource-awareness threshold-based and rule-driven
  rather than a black-box/learned model.

**Integration gap found and fixed post-merge (Claude Code, see Section
14):** `AnalyzeRequest` required `domainProfile`/`resourceLevel` on every
`/analyze` call, but **nothing in the codebase actually read them** —
`QueryAnalysisController` still called only
`queryAnalysisService.analyze(...)` and returned a bare
`QueryAnalysisResult`, never touching `DomainOptimizationService` or
`ResourceConsciousService`. Both modules compiled and were unit-testable
in isolation but were dead code from the API's perspective. Fixed by
updating `QueryAnalysisController` to call both services (rescaling the
domain suggestions' severity via `adjustSeverity` before merging in the
resource suggestions) and return `AnalyzeResponse` instead of the bare
result — this is a **breaking response-shape change** for `/analyze`
(now `{ analysis, suggestions }` instead of a flat `QueryAnalysisResult`)
that any caller written against the pre-fix shape needs to account for.

**Bug found and fixed in all three rule classes' MongoDB path:** the
"unindexed filter" suggestion message read `"Filter on '" +
result.getTableName() + "'..."` — but `getTableName()` is always `null`
for Mongo results (see `MongoSimpleAnalyzer`'s own documented
`collectionName` gap), so every one of these suggestions literally said
`Filter on 'null' has no supporting index`. Should have been
`getFilteredColumn()`. Fixed in `EcommerceOptimizationRules`,
`AnalyticalOptimizationRules`, and `TransactionalOptimizationRules`.

### Pre-existing, from before this session (per original codebase)
- `queryBuilder/RuleBasedQueryBuilder.java` — rule-based NL→SQL, handles
  SELECT/COUNT/DELETE, table detection, WHERE/ORDER BY/LIMIT parsing,
  `hasAnyComplexity()` gate that routes complex queries to the LLM fallback.
- ~~`service/impl/GeminiService.java`~~ — **deleted 2026-09-08**, replaced
  by `service/impl/OllamaService.java` (Section 16). Still in git history
  if a hosted provider is ever wanted back.
- `service/impl/QueryGenerationServiceImpl.java` — orchestrates the
  hybrid flow: Rule Engine → Cache (history lookup) → LLM fallback.
- `repository/QueryHistoryRepository.java` + `model/QueryHistory` —
  History module, persists every query regardless of source. The `source`
  field (RULE_ENGINE/CACHE/OLLAMA) is now persisted as a column (was a
  known gap; closed 2026-09-06). The `GEMINI` value can no longer be
  produced — pre-2026-09-08 rows still carry it.
- `controller/QueryController.java` — `/api/query/generate` and
  `/api/query/history` endpoints, with `@Valid` validation.
- `exception/GlobalExceptionHandler.java` — handles validation errors,
  `LlmUnavailableException` (503), LLM 4xx/5xx, catch-all.

### Not yet started
- MongoDB Query Builder — real `MongoTemplate` wiring (Vedant, next up).
  `spring-boot-starter-data-mongodb` is a pom.xml dependency but unused;
  Mongo URI is commented out in `application.properties` (though a
  Claude Code integration-test session on 2026-09-06 confirmed a local
  MongoDB *is* reachable on the default port and Spring Boot autoconnects
  to it regardless — the URI being commented out just means nothing reads
  or writes through it yet). Until this exists, `RuleBasedQueryBuilder`
  deliberately refuses every MongoDB request (see Section 14) and the LLM
  fallback handles 100% of Mongo query generation — there is no rule-based
  Mongo path today, simple-sounding requests included. Since 2026-09-08
  that fallback is a local 7B model with no schema grounding and no
  incompleteness check on the Mongo path, making this the least-protected
  part of the pipeline (Section 16).
- Comparison Engine (Vedant, last — needs the above pieces first). No
  endpoint exists at all; the frontend's Optimization page shows a
  clearly-labeled simulated preview instead (Section 14).
- Frontend (React/Vite) — **now exists**, built by Claude Code in a
  sibling repo (`../frontend`, not part of this repo). See Section 14.

---

## 8. Vedant's Task List (in dependency order)

### Task A — Schema Advisor — **COMPLETE**, verified, on branch `feature/schema-advisor`
See Section 7's "Vedant's Schema Advisor" entry for the full build/verify
summary. Task B (MongoDB Query Builder) is next.

Consumes `QueryAnalysisResult`. Suggested rule logic:
- `hasFullTableScan == true && usesIndex == false` → suggest an index on
  the filtered column.
- `usesFilesort == true` → suggest an index matching the ORDER BY clause.
- `usesTemporaryTable == true` → flag GROUP BY/DISTINCT inefficiency.
- MongoDB: `hasUnindexedFilter` / `hasUnanchoredRegex` → equivalent
  Mongo-side suggestions.
Maps conceptually to the `SCHEMA_RECOMMENDATION` ER entity — confirm
exact fields against the real diagram before finalizing the JPA entity
(see Section 6 gap note).

**Literature backing:**
- Paper #7, DBA Bandits (Perera et al., 2021, ICDE) — index
  recommendations should be anchored to observed/EXPLAIN behavior, not
  generic static rules ("index every filtered column").
- Paper #8, MonoM (Yi et al., 2025/26, arXiv) — recommendations must be
  internally consistent; add a check that a proposed index never makes
  an equality lookup logically worse.
- Paper #14, MongoDB Index Performance Evaluation (2025) — justifies
  giving MongoDB-specific index-type guidance (simple/composite/text)
  rather than reusing MySQL-style single-column rules.

### Task B — MongoDB Query Builder
Build real `MongoTemplate`-based query construction so MongoDB queries
become executable objects, not just Gemini-generated text. Uncomment
and configure the Mongo URI in `application.properties`.

### Task C — Comparison Engine (last)
Runs the Analysis Engine on both the original and optimized query,
diffs results, and explains *why* something changed (which rule fired),
not just two query strings side by side.

**Literature backing:**
- Paper #5, Learned Cardinality Estimation (Kim et al., 2022, SIGMOD) —
  frame before/after comparisons around estimated cost changes with an
  explicit caveat that estimates can be imperfect — don't present the
  optimized query's improvement as a guaranteed result.
- Paper #15, Data-Aware Socratic Query Refinement (Zhang et al., 2025,
  arXiv) — the comparison view should explain the reasoning, not just
  show two strings side by side.

---

## 9. The Shared Contract — `dto/QueryAnalysisResult.java`

```java
public class QueryAnalysisResult {
    private String tableName;
    private String queryType;        // SELECT, DELETE, FIND, etc.
    private String databaseType;     // "MySQL" or "MongoDB"

    // EXPLAIN-derived (MySQL only)
    private Long estimatedRows;
    private String accessType;       // ALL, ref, index, range, const...
    private boolean usesIndex;
    private boolean hasFullTableScan;
    private boolean usesFilesort;
    private boolean usesTemporaryTable;
    private String keyUsed;

    // Simple rule-based (MongoDB only)
    private boolean hasUnindexedFilter;
    private boolean hasUnanchoredRegex;

    // Column-level signals (MySQL: WHERE/ORDER BY column; MongoDB: offending filter field)
    private String filteredColumn;
    private String orderByColumn;    // MySQL only

    // Shared
    private List<String> warnings;   // complete human-readable sentences

    // MySQL only — true when EXPLAIN could not run at all (preflight-blocked
    // or a real JDBC failure); distinct from "EXPLAIN ran and found nothing
    // wrong." Added 2026-09-07 (Section 15) so the frontend can render an
    // explicit "Unavailable" state instead of a bare "—". See warnings for
    // the human-readable reason.
    private boolean explainFailed;
}
```

Call it via:
```
POST /api/query/analyze
Body: {
  "query": "<sql or mongo query text>",
  "databaseType": "MySQL" | "MongoDB",
  "domainProfile": "ECOMMERCE" | "ANALYTICAL" | "TRANSACTIONAL",
  "resourceLevel": "LOW" | "MEDIUM" | "HIGH"
}
Response: AnalyzeResponse — { "analysis": QueryAnalysisResult, "suggestions": OptimizationSuggestion[] }
```

**Updated 2026-09-06:** `domainProfile`/`resourceLevel` are now required
(`@NotNull` on `AnalyzeRequest`) and the response is `AnalyzeResponse`,
not a bare `QueryAnalysisResult` — see Section 7's Eeshaan subsection and
Section 14 for why. `POST /api/query/schema-advisor` takes the identical
request shape (it re-runs analysis internally) but still returns a raw
`SchemaRecommendation[]`, unchanged.

MySQL-specific fields default/false for Mongo results and vice versa —
always check `databaseType` before reading dialect-specific fields.

---

## 10. Literature Review Summary (15 papers, 2021–2026, all peer-reviewed)

Full source document exists separately (`QuerySense_Recent_Literature_
Review_Sources.docx`) — this is a condensed index for quick reference.
All entries are original research (empirical studies/novel methods), no
surveys or whitepapers, per project sourcing discipline.

**A. NL-to-Query Generation:** #1 NatSQL (Gan et al. 2021) — intermediate
representation layer. #2 Evaluating Text-to-SQL LLMs (Rajkumar et al.
2022) — justifies rule/template-driven design over fine-tuned models.
#3 Classification-Based Table Selection (Chopra & Azam 2024) — schema
linking as its own step.

**B. Rule-Based/Cost-Based Optimization:** #4 Analyzing Query Optimizer
Performance (2023) — heuristic rules + cardinality estimates, direct
justification for the EXPLAIN-driven approach. #5 Learned Cardinality
Estimation (Kim et al. 2022, SIGMOD). #6 How Good Are Learned Cost
Models, Really? (Lehmann et al. 2025) — cautions against black-box cost
models, supports rule-based/threshold design.

**C. Self-Driving Index Tuning:** #7 DBA Bandits (Perera et al. 2021,
ICDE) — evidence-anchored index advice. #8 MonoM (Yi et al. 2025/26) —
logical consistency in recommendations.

**D. Domain/Resource-Aware Optimization:** #9 Rethink Query Optimization
in HTAP (Song et al. 2023, SIGMOD) — workload-type-aware optimization,
up to 11x speedups from matching plan to workload. #10 Query Optimization
for Hybrid Plans in HTAP (2026). #11 Memory-Aware Query Optimization
(2025/26, IEEE) — memory as a first-class optimization input.

**E. MySQL vs MongoDB Performance:** #12 PostgreSQL vs MongoDB E-Commerce
(2026, MDPI). #13 MongoDB Aggregation Pipeline Performance (2026, MDPI) —
justifies scoping MongoDB analysis to "simple" checks since MongoDB's own
optimizer is inconsistent across versions. #14 MongoDB Index Performance
Evaluation (2025).

**F. Interactive Query Refinement:** #15 Data-Aware Socratic Query
Refinement (Zhang et al. 2025, arXiv) — explainable, reasoned comparisons.

**Sourcing caveats to remember:** Entries #10–13 need author-list
verification against publisher pages. #14 is ResearchGate-hosted without
an independently resolvable DOI. #4 is an anonymized preprint pending
final author list. Verify before final citation in the report.

---

## 11. Coding Conventions (matches existing codebase style)

- Every new class gets a Javadoc header explaining **WHY** it exists and
  **WHY** specific design choices were made — not just what it does.
  Match the tone/format already used in `analysis/` classes.
- Interface + Impl pattern for all services (`QueryGenerationService` /
  `Impl`, `QueryAnalysisService` / `Impl`) — new modules depend on the
  interface, never the concrete class.
- Don't crash the pipeline on a bad/unparseable query — degrade to a
  result object with a warning message instead (see
  `SqlStructureParser`/`MySqlExplainAnalyzer` exception handling).
- `warnings` fields must be complete, human-readable sentences — may be
  surfaced directly to the frontend.
- Quantitative claims used in presentations (e.g. token-reduction
  estimates vs. pure-LLM approaches) must be clearly labeled as
  estimates with stated assumptions — not presented as measured results.

---

## 12. Known Open Items / Gotchas

- ~~Gemini API key plaintext in `application.properties`~~ — **MOOT since
  2026-09-08.** The whole class of problem is gone: the fallback runs
  locally on Ollama, so there is no API key anywhere in the project and
  nothing to set before running the app. The `GEMINI_API_KEY` environment
  variable is no longer read and can be deleted from your machine.
- **New per-machine setup requirement (replaces the API key):** each
  developer needs Ollama installed plus `ollama pull qwen2.5-coder:7b`
  (~4.7GB, not committed). Without it, everything except Layer 3 fallback
  generation still works, and `/api/query/generate` returns a 503 naming
  the exact command to run.
- **MySQL is per-machine and is NOT set up everywhere.** The `querysense`
  database described throughout Sections 7/14/15 (customers/orders, the
  303 rows, `idx_city`) exists only on the machine those sessions ran on.
  On 2026-09-08 a second team machine was found to have MySQL Server 9.7
  *installed but never configured* — no `my.ini`, no data directory, no
  registered service — so `spring-boot:run` couldn't start at all. If the
  backend fails at startup with a Hikari/`ConnectException` on 3307, check
  whether MySQL was ever actually configured on that machine before
  assuming the code is broken.
- **`h2` Spring profile — run query generation without MySQL.**
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=h2` swaps in an
  in-memory H2 database so `/api/query/generate` (rule engine + cache +
  Ollama fallback + history) can be tested on a machine where MySQL isn't
  set up. `src/main/resources/h2/schema.sql` + `data.sql` seed
  customers/orders mirroring `KnownSchema`, which matters: without rows,
  `RuleBasedQueryBuilder`'s city safety net degrades silently to an empty
  list and the rule engine claims queries it should have deferred — i.e.
  you'd be testing different routing than production.
  **`/api/query/analyze` and `/api/query/schema-advisor` CANNOT work under
  this profile** — they read MySQL's specific EXPLAIN columns, which H2
  doesn't produce. It is a bridge for generation testing, not a substitute
  for MySQL. The default profile is untouched and still targets 3307.
  Restarting wipes the in-memory DB, which conveniently means a clean
  cache each run.
- Port 8080 conflicts have recurred repeatedly in local dev — if
  `spring-boot:run` fails with "port already in use," find and kill the
  stale process first (`netstat -ano | findstr :8080` on Windows,
  `taskkill /PID <pid> /F`) rather than assuming the code is broken.
- Always do a clean rebuild (`./mvnw clean spring-boot:run`) after
  editing analyzer classes — a stale compiled class has caused false
  "the fix didn't work" debugging sessions before (specifically bit us
  on a regex fix in `MongoSimpleAnalyzer` that appeared not to take
  effect purely because of a stale running process/unsaved file).
- ~~`QueryHistory`'s `source` field (RULE_ENGINE/CACHE/GEMINI) is computed
  but not yet persisted~~ — **RESOLVED 2026-09-06** (Section 14). It's now
  a real column, set in `QueryGenerationServiceImpl.saveToHistory` and
  returned by `GET /api/query/history`.
- **CORS was only ever configured per-controller** (`QueryController` had
  `@CrossOrigin`, the other two controllers didn't) — a global
  `WebMvcConfigurer` bean now lives in `AppConfig`. If you add a new
  `@RestController`, you do **not** need to remember `@CrossOrigin` on it;
  the global mapping already covers `/**`. This was invisible to `curl`
  during manual testing (curl doesn't enforce CORS) and only surfaced once
  a real browser called `/analyze`/`/schema-advisor` from a different
  origin — worth remembering if a future endpoint "works in curl but not
  the frontend."
- **Any new query-generation or cache-lookup logic must treat
  `databaseType` as load-bearing, not decorative.** Two real bugs shipped
  from treating it as an afterthought: `RuleBasedQueryBuilder.tryBuild`
  used to not even receive it (so it always emitted SQL, Mongo included),
  and the cache lookup used to match on `userInput` alone (so an
  identical NL string asked for MySQL vs. MongoDB would return the wrong
  dialect's cached result). Both fixed 2026-09-06 — see Section 14.
- devtools (`spring-boot-devtools`) hot-restarts the app automatically on
  any `src/main` change while `spring-boot:run` is running — you generally
  don't need to manually stop/restart during a dev session, just watch
  the log for "Started BackendApplication" again after saving. This
  doesn't replace the "clean rebuild" advice above for cases where a
  restart alone doesn't pick up the change.
- **Never run `./mvnw compile` (or any other Maven goal) in a separate
  terminal/process while `spring-boot:run` is already running against the
  same `target/classes`.** Discovered 2026-09-07 (Section 15): a manual
  `mvnw compile` racing against devtools' own recompile-and-restart cycle
  produced a genuinely broken restart — Spring failed to wire
  `queryAnalysisController` (`UnsatisfiedDependencyException`, missing
  `QueryAnalysisService` bean) from a half-written classpath, and the
  *next* restart after that silently came up "successfully" while still
  serving stale bean wiring. This cost real debugging time chasing a
  phantom regression in `RuleBasedQueryBuilder` that didn't actually
  exist. If you need to force a rebuild, stop the running process first
  (`taskkill /PID <pid> /F /T` — devtools spawns a child JVM, so use `/T`
  to kill the tree), then `./mvnw clean spring-boot:run`.
- ~~**Gemini free-tier API keys hit their quota fast**~~ — **OBSOLETE
  since 2026-09-08.** This was a significant recurring blocker: the free
  tier's 5 req/min and low daily cap meant a single thorough bug-fix
  session could exhaust the day's quota, after which every call 502'd
  regardless of waiting, and Section 15 records a bug whose live
  verification was never completed because of exactly this. Local
  inference removes it entirely — there is no quota, no rate limit, and
  testing is free and unlimited. Kept here only so anyone reading the
  older session logs understands why they mention quota exhaustion.
- **A slow first fallback query is expected, not a bug** — cold model load.
  See Section 16.7 for the measured figures and why `LlmWarmUpRunner` and
  the 30m `keep-alive` exist. Neither should be "simplified" away.
- **If fallback generation suddenly takes many seconds instead of ~1s,**
  suspect VRAM rather than the code: the model needs ~5GB free on the
  8GB GPU, and another application holding VRAM forces a partial CPU
  offload that is roughly 6x slower. `nvidia-smi` shows what's resident.

---

## 13. Diagrams and Documentation Already Completed

All six mentor-requested diagrams (ER, component, architecture, use
case, activity, sequence) have been worked through in draw.io/Mermaid,
with the component diagram going through the most revision (Comparison
Engine "before" input, Schema Advisor↔EXPLAIN link, History Module
persistence, fallback decision path, error handler paths all patched).
The literature review and feature-justification mapping documents are
both complete (see the two .docx files referenced in Section 10).

---

## 14. Claude Code Session Log — Backend Verification & Frontend Build (2026-09-06)

This section documents a single Claude Code session that (1) merged
Eeshaan's and Vedant's branches into `master`, (2) ran the merged backend
for real and verified every endpoint against live MySQL/MongoDB/Gemini
rather than just reading the code, (3) fixed six real bugs the live
testing surfaced, and (4) built the project's first frontend. Kept as its
own dated section rather than folded silently into Sections 5–7 so the
team can see exactly what changed and why, separate from each person's
own work.

### Merge sequence

`feature/schema-advisor` (Vedant) was pushed and fast-forward-merged into
`master` first. Eeshaan's `feature/domain-optimization` branch had
already merged `origin/master` into itself before pushing, so merging it
into `master` afterward was also a clean fast-forward — no conflicts,
because he synced with the Schema Advisor work before building on top of
it. `master` now contains all three modules: Query Analysis Engine,
Schema Advisor, Domain-Sensitive Optimization + Resource-Conscious
Decision System.

### Why a live verification pass, not just a code read

The ask was explicit: check that everything actually works before wiring
a frontend to it. A code read alone would have missed every bug below —
they only surfaced by starting `spring-boot:run` against the real
`querysense` MySQL database (port 3307) and a local MongoDB instance,
and hitting each endpoint with `curl`, then later with a real browser.
Two of the six bugs specifically would not have shown up in `curl`
testing at all (see the CORS entry in Section 12).

### The six bugs found and fixed (backend)

1. **`RuleBasedQueryBuilder` had no MongoDB awareness at all.**
   `tryBuild(String input)` didn't even take `databaseType` as a
   parameter — it always emitted SQL syntax, so a "simple-sounding"
   MongoDB request (e.g. "show users from Mumbai") was wrongly claimed by
   the rule engine and returned syntactically valid but completely wrong
   SQL, labeled as if the rule engine had correctly handled a Mongo
   request. Fixed: `tryBuild` now takes `databaseType` and returns
   `Optional.empty()` immediately for MongoDB, deferring to Gemini until
   a real Mongo query builder exists (Task B, still not started).
2. **Cache lookup ignored `databaseType`.**
   `findFirstByUserInputIgnoreCase(String)` matched on input text alone,
   so identical NL text asked once for MySQL and again for MongoDB would
   return the *other* dialect's cached query. Fixed: replaced with
   `findFirstByUserInputIgnoreCaseAndDatabaseTypeIgnoreCase(userInput,
   databaseType)`.
3. **Domain/Resource modules were dead code from the API's perspective.**
   See Section 7's Eeshaan subsection for full detail — `AnalyzeRequest`
   required `domainProfile`/`resourceLevel` but nothing read them.
   `QueryAnalysisController` now calls both services and returns
   `AnalyzeResponse`.
4. **`/api/query/generate` returned only `{ generatedQuery }`.**
   No `handledBy`, `confidence`, or `targetDb` — the entire "which path
   handled this" pitch had nothing to bind to in the response, and
   `source` was computed in `QueryGenerationServiceImpl` but discarded
   rather than returned or persisted. Fixed: `QueryResponse` gained
   `targetDb`/`handledBy`/`confidence` fields; `confidence` is a **fixed
   per-source heuristic** (`RULE_ENGINE`=0.95, `CACHE`=1.0, `GEMINI`=0.65
   — see the constants and Javadoc in `QueryGenerationServiceImpl` and
   `QueryResponse`), not a computed statistical score — deliberately kept
   simple rather than dressed up as more precise than it is, consistent
   with this project's rule-based/interpretable design goal. `source` is
   now persisted to `QueryHistory` too (Section 6/12).
5. **No global CORS configuration.** Only `QueryController` had
   `@CrossOrigin`; `QueryAnalysisController` and `SchemaAdvisorController`
   had none, so `/analyze` and `/schema-advisor` were silently blocked by
   browser CORS (not visible to `curl`, which is why this wasn't caught
   until the frontend actually tried to call them). Fixed with a global
   `WebMvcConfigurer` bean in `AppConfig` — see Section 12.
6. **Domain rule Mongo messages said `'null'` instead of the field
   name.** All three `*OptimizationRules.evaluateMongo` methods built
   their "unindexed filter" message from `result.getTableName()`, which
   `MongoSimpleAnalyzer` always leaves `null` (documented gap) —
   should have been `result.getFilteredColumn()`. Fixed in all three.

### The frontend

Built from scratch in a **separate sibling repository**,
`C:\Users\anant\OneDrive\Desktop\QuerySense\frontend` — not a subfolder
of this repo, and **not pushed anywhere** (its own local git repo,
untracked/uncommitted-to-remote by request, left for the user to push
when ready). Reasoning: keeping a large Vite/React tree out of this
Maven repo's history, and because the user explicitly wanted to keep
iterating on it locally first.

**Stack:** React 19 (Vite), React Router, Axios, `framer-motion` for the
handful of deliberate motion moments, plain CSS with a token file
(`src/styles/tokens.css`) — no CSS framework. Typography is IBM Plex Sans
(UI/headings) + IBM Plex Mono (queries/identifiers/metrics only). Color
system reuses the team's own diagram convention (teal=rule engine,
amber=LLM fallback, blue=frontend/API, purple=data access, red=errors)
as CSS variables, validated for colorblind-safe adjacency with the
`dataviz` skill's palette validator.

**Pages (React Router, shared shell w/ nav rail + persistent engine-status
header):** Workbench (NL input → generated query), Analysis (EXPLAIN /
Mongo findings + domain/resource suggestions), Schema Advisor
(recommendations), Optimization (simulated Comparison Engine preview,
clearly labeled), History (searchable/filterable), Architecture (a
presentation-aid pipeline diagram reusing the same color tokens).

**API integration — real where the backend is real, simulated where it
isn't:**

| Frontend calls | Status | Notes |
|---|---|---|
| `POST /api/query/generate` | REAL | Workbench |
| `POST /api/query/analyze` | REAL | sends `domainProfile`/`resourceLevel`; unwraps `AnalyzeResponse` |
| `POST /api/query/schema-advisor` | REAL | same request shape as analyze |
| `GET /api/query/history` | REAL | no POST — the backend saves history automatically, frontend never writes it |
| Comparison Engine | SIMULATED | no endpoint exists; frontend's `mockCompare`/`mockSimulateOptimizedQuery` always run regardless of `VITE_USE_MOCK`, clearly labeled "Simulated" in the UI |
| MongoDB optimization preview | SIMULATED | Optimization page shows an honest "SQL only, for now" empty state for Mongo analyses rather than faking a Mongo rewrite |

`VITE_USE_MOCK=false` in the frontend's `.env` currently points it at the
real backend on `localhost:8080`; flip to `true` to fall back to realistic
mock data (`src/mock/fixtures.js`) shaped identically to the verified
real responses, useful when the backend isn't running.

**Two mapping decisions worth knowing about if the contracts change:**
- The frontend's fourth domain option, "Other" (not a real
  `DomainProfile` enum value), is mapped to `TRANSACTIONAL` before being
  sent to the backend — reusing `DomainOptimizationService`'s own
  documented null-profile fallback rather than inventing a new one.
- The frontend's `TargetDb.SQL`/`TargetDb.MONGODB` internal values are
  mapped to the backend's literal `"MySQL"`/`"MongoDB"` strings at the
  API-call boundary (`TARGET_DB_TO_BACKEND` in `src/api/contracts.js`).

### A frontend-only bug found during the integration pass (not a backend issue)

Building a new query in the Workbench didn't clear the previous query's
`analysis`/`suggestions`/`schemaAdvice` state, so navigating to Analysis
after replacing a query could show stale results for the query you'd
already moved on from. Fixed by resetting that state on every successful
new build. Mentioned here because it was only catchable by testing the
full click-through flow in a real browser session, not by testing pages
in isolation.

### What's still open

- MongoDB Query Builder (Task B) — still nothing but Gemini for Mongo.
- Comparison Engine (Task C) — still no endpoint.
- The frontend has never been pushed to a remote. It also hasn't been
  run through a production build (`npm run build`) — only `npm run dev`.
- `application.properties` MySQL config is hardcoded (`localhost:3307`,
  `root`/`password`) — fine for a single shared dev machine, would need
  externalizing (env vars, like `GEMINI_API_KEY`) before this runs
  anywhere else.

---

## 15. Claude Code Session Log — Two Rounds of Bug Fixes: Query Generation & Analysis (2026-09-07)

Two consecutive Claude Code sessions found and fixed bugs via live manual
testing of the running app (backend + frontend, both real MySQL/MongoDB/
Gemini) — not code reads or unit tests. Round 2 specifically re-opened two
of Round 1's fixes because they turned out to be hardcoded to their exact
repro sentence rather than the general case; that lesson is now the
organizing principle for how schema knowledge is stored (see the
Round 2 architecture note below) — read it before touching
`RuleBasedQueryBuilder` or `GeminiService` again.

### Round 1 — 8 bugs, all verified live

1. **Gemini SQL failed MySQL's `ONLY_FULL_GROUP_BY` strict mode.**
   `GeminiService`'s prompt now explicitly states the rule (every
   non-aggregated SELECT column must appear in GROUP BY). New
   `analysis/SqlPreflightValidator.java` also catches a violation
   structurally (JSQL Parser AST, no DB round trip) before EXPLAIN ever
   runs, short-circuiting with a specific message.
2. **Raw JDBC exception text (`BadSqlGrammarException.toString()`) was
   shown verbatim in the UI.** `MySqlExplainAnalyzer` now catches it
   specifically, translates the real MySQL error code (1054 = unknown
   column, 1055 = GROUP BY violation, else generic) into a friendly
   sentence, and `log.warn`s the real exception server-side.
3. **EXPLAIN-failure fields rendered as a bare `—`, indistinguishable
   from "this is a Mongo result."** Added `explainFailed` (boolean) to
   `QueryAnalysisResult`/`AnalyzeResponse` (see Section 9). Frontend's
   `ExplainPlanView.jsx` now renders "Unavailable — EXPLAIN failed" for
   every EXPLAIN-derived field when set, instead of a silent dash.
4. **Gemini hallucinated a nonexistent `age` column on `customers`.**
   Confirmed live via `DESCRIBE customers` that no such column exists.
   Fixed with schema-grounding (real column names injected into the
   prompt) plus `SqlPreflightValidator`'s column-existence check as a
   safety net — **and the safety net turned out to be load-bearing**: in
   live testing, Gemini still occasionally emitted `age` even with the
   schema hint in the prompt (LLMs don't guarantee 100% instruction
   compliance), so the validator catching it after the fact is what
   actually makes the UX reliable, not the prompt grounding alone.
5. **NL input text disappeared navigating Workbench → Analysis → back.**
   It lived in `QueryForm`'s local `useState`, which reset on unmount.
   Lifted into `WorkbenchContext` (`nlInput`/`setNlInput`) alongside the
   `build` state that already persisted correctly.
6. **Simulated Optimization preview changed the filter value's case**
   (`'mumbai'` → `'Mumbai'`) **and showed diff lines with no
   justification.** `mock/fixtures.js`'s `mockSimulateOptimizedQuery` now
   derives the WHERE clause verbatim from the real query (case
   preserved) and only prunes columns/adds LIMIT when a domain rule in
   `rulesApplied` actually justifies it — each such change is appended to
   `mockCompare`'s rule trace so "Why it would change" always covers
   every diff line.
7. **Rule engine ignored column-projection intent** ("select only names
   of..." → still `SELECT *`). First-pass fix only recognized the
   literal words "name"/"names" via a hardcoded alias map — **reopened in
   Round 2**, see below.
8. **Rule engine silently dropped the WHERE clause on preposition
   variance** ("customers in mumbai" → no WHERE at all, still 95%
   confidence). First-pass fix added "in"/"at"/"located in" to a literal
   list — **reopened in Round 2**, see below.

### Round 2 — generalizing Bugs 7/8, two new bugs, centralized schema

**Why Bugs 7 and 8 were reopened:** live retesting showed the Round 1
fixes only worked for the exact sentence tested — "select email of
customers in mumbai" still returned `SELECT *` (only "name" was
hardcoded), and "customers living in mumbai" still dropped the WHERE
clause (only a fixed preposition list was extended). The root cause was
architectural: table/column knowledge was duplicated as incomplete,
independent hardcoded lists in `RuleBasedQueryBuilder` and
`GeminiService`, so fixing one never generalized to the other or to
unseen phrasing.

**Real schema now includes a real `orders` table** (previously only
`customers` + the internal `query_history` existed):
```
customers(id, name, city, email)
orders(order_id, customer_id → customers.id, order_date, amount, status)
```
`customer_id` and `order_id` are indexed (FK + PK); `order_date`/
`amount`/`status` are not.

**Architecture fix — `schema/KnownSchema.java`:** a new static,
central source of truth for table/column structure (matching the schema
above exactly) and the one real FK relationship. `RuleBasedQueryBuilder`
and `GeminiService` both read from it now — no more per-class hardcoded
table/column lists. Deliberately static, not live `information_schema`
introspection, per this project's documented "fixed rule sets" scope
(Section 4) — `analysis/SchemaMetadataService.java` still does live
DB queries, but only for what a static list genuinely can't express: live
schema-drift detection (`SqlPreflightValidator`'s column-existence check)
and real DATA values (`getDistinctValues`, new this round — see Bug 8/10
below).

7. **Column projection — rebuilt generically.** `RuleBasedQueryBuilder`
   now resolves projection phrases ("only X", "X of Y", "X and Y",
   compound nouns like "customer ids") against `KnownSchema`'s real
   column list for whichever table was detected — not a hardcoded alias
   map — with plural-form and suffix matching (`ids` → `id`, `amounts` →
   `amount`). Supports multi-column projection. If a projection phrase is
   present but references something unresolvable against the real
   schema, that's treated as ambiguous and routed to Gemini rather than
   silently falling back to `SELECT *`.
8. **WHERE-clause phrasing — rebuilt with a generalized matcher AND a
   data-driven safety net.** The preposition list is now one extensible
   alternation group (`from|in|at|living in|located in|based in|residing
   in`) instead of literal enumeration, and — the more important half —
   `isSafeLocationCondition()` queries the REAL distinct values of
   `customers.city` live (`SchemaMetadataService.getDistinctValues`) and
   bails to Gemini if a known real city is mentioned in the input but
   doesn't appear in the WHERE clause about to be built. This is a
   general safeguard against the whole "WHERE clause silently
   dropped/incomplete" bug class (catches "near mumbai" and any future
   unhandled phrasing alike), not a per-phrasing patch. Also discovered
   and fixed a related issue in the same pass: bare noun-phrase inputs
   with no lead verb at all ("customers from mumbai", no "show"/"get")
   were being rejected by `isSimpleSelect`'s keyword-list check before
   ever reaching the location matcher — SELECT is now the rule engine's
   default intent (anything that isn't a count/delete request and has no
   complexity signal), not something requiring an explicit verb.
9. **NEW — Gemini silently dropped an entire join/aggregate condition.**
   "customers living in mumbai with orders more than 3" (correctly routed
   to Gemini) generated only the city filter, silently losing the
   orders-related condition, no error, no lowered confidence.
   `GeminiService`'s prompt is now schema-grounded for BOTH tables plus
   the FK relationship (via `KnownSchema`) whenever a request mentions
   both. Added `GeminiService.looksIncomplete(...)`, checked by
   `QueryGenerationServiceImpl` after every Gemini call: if the input
   mentions more than one `KnownSchema` table but the generated SQL
   doesn't reference all of them (schema-driven signal — catches "total
   order amount for each customer" too, which has no "with"/digit
   language at all), OR relational language + a quantitative cue implies
   a condition the SQL doesn't show, the reported confidence drops to a
   distinct `CONFIDENCE_GEMINI_INCOMPLETE = 0.4` tier instead of the
   normal 0.65 — same "fixed heuristic, not a computed score" philosophy
   as the other confidence constants.
10. **NEW — compound/multi-value location conditions collapsed to one
    value.** "customers in mumbai and ahmedabad" → only `mumbai` kept,
    `ahmedabad` silently dropped. The location matcher's value capture
    now supports conjunctions ("X and Y", "X or Y", "X, Y, and Z") and
    generates `city IN (...)` for 2+ values.

**Verification:** every Round 2 fix was re-tested live against the real
running app with the full test matrix (not just the original repro
sentence) — 6 projection phrasings, 7 WHERE-clause phrasings, 3 compound-
condition cases, cross-cutting sanity (control SELECT, DELETE, MongoDB
routing) — all passing. Bugs 1 and 4 were re-verified specifically
against the new real `orders` table (both the violation and the
safe/valid case). Bug 6 was retested and confirmed still fixed.

**Frontend also updated for the new schema:** `QueryForm.jsx` had a
comment claiming "customers in Mumbai" would silently drop its WHERE
clause — no longer true, removed. `mock/fixtures.js`'s `guessTable`
listed several fictional tables (`products`, `users`, `payments`,
`inventory`) that don't exist in the real backend; narrowed to
`customers`/`orders` to match `KnownSchema` exactly. `KNOWN_INDEXED_COLUMNS`
extended with `order_id`/`customer_id` (confirmed indexed via
`SHOW INDEX FROM orders`).

**Not fully verified — Bug 9's live Gemini output quality.** The routing
logic (all 4 Bug 9 test inputs correctly bypass the rule engine and
reach Gemini) was confirmed live, and the `looksIncomplete` logic was
traced by hand against all 4 cases and is believed sound, but a live
Gemini call actually succeeding wasn't achieved in this session — the
project's Gemini API key hit its free-tier quota partway through this
round's testing (see Section 12's new gotcha) and didn't reset within
the session. A replacement key was supplied but turned out to be the
wrong credential type (`AQ.` prefix, not `AIzaSy...` — see Section 12).
~~**Next session: get a valid `AIzaSy...` key from
https://aistudio.google.com/apikey**~~ — **superseded 2026-09-08
(Section 16): there is no API key any more.** The test matrix itself is
still owed and still worth running, just against the local model instead:
re-run **"customers in mumbai with orders more than 3", "customers who
have placed more than 5 orders", "total order amount for each customer in
delhi", "customers with no orders"** to confirm the schema-grounded prompt
and the confidence-lowering signal behave correctly end-to-end. The reason
this was blocked no longer exists — local inference has no quota, so this
matrix can now be run as many times as needed at no cost. One of these
("customers in mumbai who placed more than 3 orders") was already
confirmed to produce correct SQL directly against the model in Section 16;
the rest, and the full path through Spring, remain unverified.

---

## 16. The LLM Fallback — How It Works

**This is the authoritative description of the fallback.** Sections 2, 3, 7
and 12 mention it in passing; this section is where the mechanism actually
lives. Current as of 2026-09-08, when the fallback moved from hosted Gemini
2.5 Flash to local inference — see 16.10 for that migration.

### 16.1 Where it sits

Query generation (`POST /api/query/generate`) is three layers, tried in
order by `QueryGenerationServiceImpl`. The LLM is the last of them.

| Layer | Component | `handledBy` | Confidence | Cost |
|---|---|---|---|---|
| 1 | `RuleBasedQueryBuilder.tryBuild()` | `RULE_ENGINE` | 0.95 | free, ~ms |
| 2 | History-table lookup | `CACHE` | 1.0 | free, one indexed read |
| 3 | `OllamaService.generateQuery()` | `OLLAMA` | 0.65 / 0.4 | ~1s, local GPU |

**The LLM is used in exactly one place in the entire system.** Query
analysis, domain optimization, resource decisions and schema advice are
100% rule-based and never call a model. When defending the hybrid
architecture, this is the concrete fact to point at: the "AI part" is one
replaceable component behind one interface, not something woven throughout.

Layer 2 matches case-insensitively on **both** `userInput` and
`databaseType`, so the same sentence asked for MySQL and for MongoDB does
not collide.

### 16.2 What actually triggers it

Not just "complex queries" — there are **six distinct bail-out conditions**
in `RuleBasedQueryBuilder`, and every one routes to the LLM. Anyone
describing the routing should describe these, not a vague notion of
complexity:

1. **Any MongoDB request** (`:80-82`) — unconditional. Every builder method
   emits SQL syntax, so until the Mongo builder (Task B) exists, **100% of
   Mongo generation is the LLM.** This is the largest single source of
   fallback traffic and is easy to understate.
2. **No known table detected** (`:88-91`) — matched against `KnownSchema`,
   singular or plural.
3. **`hasAnyComplexity()` fires** (`:163-214`) — month names; relative time
   (`last month`); `today`/`recent`/`latest`/`oldest`; comparisons (`more
   than`, `at least`, `between`); relational words (`who`, `which`, `join`,
   `across`); aggregates beyond COUNT (`average`, `sum`, `max`, `min`); or
   **more than one known table mentioned**, which implies a JOIN.
4. **Ambiguous projection** (`:238-243`) — an explicit "only X" / "X of Y"
   phrase whose nouns don't resolve to a real column. Refuses to silently
   degrade to `SELECT *`.
5. **Unsafe WHERE clause** (`isSafeLocationCondition`, `:389-400`) — queries
   `customers.city` **live** and bails if a real city appears in the input
   but not in the clause it was about to build.
6. **DELETE with no WHERE** (`:287-290`) — never built by rules.

The design is deliberately inverted: it does not enumerate what is complex
(an unbounded list), it enumerates what is *provably simple* and defers
everything else. A confidently wrong query is worse than an honest
low-confidence one.

### 16.3 The model

`qwen2.5-coder:7b`, q4_K_M quantisation, ~4.7GB, served by Ollama at
`http://localhost:11434/api/generate`.

**Why 7B is the ceiling, not a compromise:** at q4_K_M the weights are
~4.7GB, which fits an 8GB GPU with real KV-cache headroom. A 14B at the
same quantisation is ~9GB, does **not** fit, and spills to system RAM for
roughly a 6x slowdown.

**Why a code-tuned generalist rather than a text-to-SQL specialist:**
`sqlcoder` and similar are stronger on pure SQL but cannot emit MongoDB and
follow general formatting instructions poorly. This service must do both
dialects.

Key request settings, all in `OllamaService.requestBody()`:

- **`stream: false`** — mandatory. Ollama defaults to streaming NDJSON,
  which `RestTemplate` would fail to deserialise in a way that looks
  nothing like the real cause. Do not remove.
- **`temperature: 0` with a fixed `seed`** — query generation is not a
  creative task. The same input must produce the same query, or the
  before/after comparisons this project is built on aren't reproducible.
  A reviewer can re-run a result with the network unplugged and get the
  same output.
- **`keep_alive: 30m`** — see 16.7; the 5m default is not enough.
- **`num_predict: 300`** — bounds the worst case when the model appends
  commentary despite being told not to.

### 16.4 What the model receives

`buildPrompt()` sends the dialect, formatting constraints ("output ONLY the
query", stated three times), and safety rules (no `DROP`, no unqualified
`DELETE`, `ONLY_FULL_GROUP_BY` compliance, semicolon terminator).

The important part is **`buildSchemaContext()`**: if the input mentions any
`KnownSchema` table, the prompt is injected with the real table and column
lists plus any foreign key linking two mentioned tables, with an
instruction to use it for the JOIN. This is retrieval-grounded prompting,
not blind text-to-SQL, and **it is the single largest accuracy lever for a
small local model** — it matters considerably more at 7B than it did
against a frontier model, because a smaller model has far less capacity to
guess a plausible schema correctly.

⚠️ **This is MySQL-only.** `buildSchemaContext()` returns empty for
MongoDB, because `KnownSchema` describes relational tables. See 16.8.

### 16.5 What happens to the reply

`cleanResponse()` reduces the raw reply to just the query, in this order:

1. **Unwrap a fenced block** if present — if the model fenced its answer,
   the fence contents *are* the answer and any prose outside is noise.
   Handles any language tag, not just `sql`.
2. **Drop conversational lead-in lines** ("Here is the query:"). The
   pattern requires a trailing colon, which is what makes it safe — a line
   of SQL never ends in one.
3. **For SQL only, cut after the first `;`** — the prompt mandates a
   terminator, so anything past it is commentary. MongoDB is exempt: its
   queries are brace-delimited expressions with no terminator, and cutting
   on a stray semicolon inside a pipeline would corrupt it.

**This is not defensive over-engineering.** Verified live on 2026-09-08:
asked three separate times in the prompt to emit no markdown, the model
wrapped its otherwise-correct SQL in a fenced block anyway. That is the
ordinary integration cost of a smaller model, and it belongs in code rather
than in more prompting.

**Why there are no `stop` sequences:** the obvious choice is the fence
marker, and it is a trap — when the model fences its answer, the *opening*
fence is the first thing it emits, so that stop would truncate the response
to an empty string. Every other candidate risks cutting a legitimate
multi-line MongoDB pipeline. Trimming after the fact cannot destroy output
that was already produced; a stop sequence can.

### 16.6 When it fails

An unreachable or uninstalled model throws `LlmUnavailableException`,
surfaced by `GlobalExceptionHandler` as **503** with a message naming the
exact command to run.

**Why this matters more than it looks:** the previous implementation
*returned a string* — `"-- Gemini unavailable, please try again shortly."`
— which was then treated as a successful generation: labeled confidence
0.65, returned as HTTP 200, and **persisted to `query_history`**. Since
that table is also the Layer 2 cache, the next identical request got a
cache hit on the error text and returned it at confidence 1.0 forever
after. Survivable against a hosted API that is almost always up; not
survivable against a local runtime where "Ollama isn't running" is an
everyday state.

**There is no retry loop**, deliberately. Retrying with backoff is correct
for a hosted 503 ("overloaded, try shortly"). A local runtime's failures —
server down, model not pulled — are permanent for the lifetime of the
request, so retrying only adds seconds to an outcome already decided.

### 16.7 Performance (measured, not estimated)

RTX 5060 Laptop 8GB, 2026-09-08:

| | Result |
|---|---|
| Warm generation, 60-token query | **1.14s** (~53 tok/s) |
| First-ever cold model load | **49s** |
| Rule-engine path, for contrast | milliseconds |

Warm latency is at rough parity with the hosted round-trip it replaced.

**The cold-load figure is why `LlmWarmUpRunner` exists.** 49s was a worst
case (cold OS page cache on a just-downloaded 4.7GB blob); later cold loads
are faster but still seconds. The warm-up fires a throwaway one-token
request at startup on a daemon thread, swallowing all failures — a missing
model must never prevent the backend from starting, since everything except
Layer 3 works without it. Combined with the 30m `keep_alive`, the load cost
is paid before anyone is watching. **Do not "simplify away" either one.**

The fallback does not fire on most queries — rule-engine and cache hits
return in milliseconds. Report the hit-rate split rather than one blended
latency figure; "82% answer in under 20ms, the rest take ~1.5s" is a far
better number than an average.

### 16.8 Known weaknesses — state these honestly

- **The MongoDB path is the least protected part of the pipeline.** It is
  both *ungrounded* (no schema block — 16.4) and *unguarded*
  (`GeneratedQueryValidator` returns false for non-MySQL). Since the rule
  engine defers *all* Mongo requests, 100% of Mongo generation is an
  unchecked 7B model. **Evaluate and report Mongo accuracy separately
  rather than folding it into one blended figure.**
- **The cache does not know which model produced a row.** It is keyed on
  `(userInput, databaseType)` only. Rows generated by the old provider are
  still served as `CACHE` hits at confidence 1.0, and this will silently
  corrupt any provider-comparison benchmark unless the cache is bypassed or
  `query_history` cleared first. Not yet fixed.
- **Confidence values are four fixed constants**, not computed scores. This
  is documented candidly in the code and should be said out loud rather
  than left to be discovered.
- **`HttpMessageNotReadableException` is unhandled** in
  `GlobalExceptionHandler` — a missing or malformed request body returns
  **500 "An unexpected error occurred"** when it should be a 400. Hit for
  real during testing. One method fixes it.

### 16.9 Confidence and the incompleteness check

`GeneratedQueryValidator.looksIncomplete()` lowers reported confidence from
0.65 to **0.4** when the generated SQL looks like it dropped part of the
request. It lives outside the provider class because it compares input to
output using `KnownSchema` and is not provider-specific. Two signals:

1. **Schema-driven** — the input names more than one known table but the
   SQL doesn't reference them all. Doesn't depend on guessing phrasing.
2. **Phrase-based** — relational language plus a quantitative cue implies a
   HAVING/GROUP BY shape that the SQL doesn't show.

It flags rather than rejects: the query is still returned, just labeled
lower. MySQL-only (see 16.8).

### 16.10 Migration from Gemini (2026-09-08)

Moved from hosted Gemini 2.5 Flash to local Ollama. Complete replacement —
`GeminiService` deleted, still in git history if a hosted provider is ever
wanted again. **The hybrid architecture and all seven project objectives
are unchanged; only the provider moved.**

Rationale: the demo must work offline (the fallback previously depended on
campus wifi *and* Google's uptime *and* a valid API key); the free-tier
quota had already blocked a verification in a prior session; local
inference fits the interpretable, inspectable architecture the project
argues for; and no secrets remain in the repo.

Structural changes made at the same time, each because the swap exposed
them:

- Added the `LlmFallbackService` interface. `QueryGenerationServiceImpl`
  had depended on the concrete `GeminiService`, breaking the project's own
  interface-and-impl convention (Section 11).
- Extracted `looksIncomplete()` into `GeneratedQueryValidator`.
- `AppConfig.restTemplate()` had **no connect or read timeout at all**; now
  3s/60s, configurable. ⚠️ The frontend's axios client uses 20s — consider
  the two together when tuning either.

**Breaking for callers:** `handledBy` / `QueryHistory.source` is now
`"OLLAMA"`, never `"GEMINI"` (pre-migration rows keep the old value), and a
failed fallback is a 503 rather than a 200 containing a comment string.

**Verification status:** clean compile; 10 unit tests passing
(`OllamaServiceTest` — fence stripping across language tags, lead-in prose,
trailing commentary, the Mongo semicolon exemption, both failure paths;
mocks `RestTemplate`, so it needs no GPU or network); a live call to the
real model with the exact prompt and request body the service sends; and
the endpoint confirmed working through Spring under the `h2` profile
(Section 12) for the rule-engine path. **Not yet verified: a request
traversing Layer 3 end-to-end inside the running application.**

---

## 17. Claude Code Session Log — "EXPLAIN failed" on every analyze call (2026-09-09)

Symptom reported: the frontend showed "Unavailable — EXPLAIN failed" for
every MySQL analysis, on every query. It turned out to be **two unrelated
environment/code faults stacked on top of each other**, on a team machine
(`C:\Users\Lenovo\`) that is NOT the machine Sections 14/15 were written
on (`C:\Users\anant\`). The first fault masked the second, and fixing only
the first changed nothing — worth knowing, because the obvious diagnosis
was wrong on its own.

### Fault 1 — the app was talking to the wrong MySQL (config)

This machine runs **two MySQL instances at once**: MySQL 9.7 on **3306**,
holding the real `querysense` database, and a separate, empty MySQL 8.0 on
**3307**. `application.properties` pointed at 3307, where no `querysense`
database exists, so the backend could not start on the default profile at
all — and the workaround in use was to start it with
`-Dspring-boot.run.profiles=h2`. That boots, but silently redirects EXPLAIN
to H2, which does not emit MySQL's plan columns, so every analyze call
returned `explainFailed: true` with a warning blaming the user's SQL.

Fixed by pointing the datasource at 3306. Host/port/user/password now read
environment variables with defaults
(`jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/querysense`), so a
teammate whose instance is on 3307 sets `DB_PORT=3307` rather than editing
and then accidentally committing the file. This closes half of Section 14's
"MySQL config is hardcoded" open item.

⚠️ **Check the port and the active profile before suspecting the analyzer.**
`netstat -ano | findstr :3306` and the JVM's own command line
(`Get-CimInstance Win32_Process | Select CommandLine`) settle both in
seconds; the running process's `--spring.profiles.active=h2` flag is what
actually proved the profile here, not any log line.

### Fault 2 — MySQL 9 changed the EXPLAIN default (real code bug)

With the app on the correct database, **every analyze call still failed
identically.** The server-side log carried the real reason, which the API
response deliberately does not:

```
EXPLAIN did not return MySQL's execution-plan columns
(expected at least 'table' and 'type'; got [explain])
```

**MySQL 9.0 changed the default value of the `explain_format` system
variable from `TRADITIONAL` to `TREE`.** Verified directly on both local
servers: `SELECT @@explain_format` returns `TREE` on 9.7 and `TRADITIONAL`
on 8.0. So a bare `EXPLAIN <query>` — which is what
`MySqlExplainAnalyzer.readPlan` sent — returns a single column named
`EXPLAIN` containing a tree diagram, and `EXPLAIN_ROW_MAPPER` (which needs
`table`, `type`, `key`, `rows`, `Extra`) cannot read any of it.

Fixed by naming the format explicitly:
`EXPLAIN FORMAT=TRADITIONAL <query>`. Confirmed accepted by **both** 8.0.46
and 9.7, so it is safe for teammates on either. This also makes the
analyzer immune to a session-level `explain_format` set by a DBA.

**This bug is version-triggered, not query-triggered** — it would have
appeared for every teammate the moment anyone upgraded to MySQL 9.x, and
nothing about the failure message points at the server version. Regression
test `MySqlExplainAnalyzerTest.tabularModeRequestsTraditionalFormat`
asserts the SQL string, because no assertion on the returned object can
distinguish this failure from a genuinely invalid query.

### The database was also empty

`customers` and `orders` existed on 3306 with the correct columns but
**0 rows**. Two things degrade silently on empty tables: EXPLAIN produces
degenerate plans (so analysis returns successfully and says nothing real),
and `RuleBasedQueryBuilder.isSafeLocationCondition()` reads the live
DISTINCT values of `customers.city` — an empty list stops that safety net
firing, so the rule engine starts claiming queries it should defer.

Added **`src/main/resources/mysql/schema.sql` + `data.sql`** (mirroring
`h2/`, which had no MySQL counterpart despite its own comments referring to
one). Run by hand, never by Spring:

```
mysql -h 127.0.0.1 -P 3306 -u root -p < src/main/resources/mysql/schema.sql
mysql -h 127.0.0.1 -P 3306 -u root -p < src/main/resources/mysql/data.sql
```

- `schema.sql` is `IF NOT EXISTS` throughout with **no DROP anywhere**, so
  it is a no-op on a machine that already has the tables and cannot cost
  anyone their data.
- `data.sql` seeds 303 customers over the 5 real cities + 900 orders, using
  `INSERT IGNORE` so re-running changes nothing. All values are computed,
  **no `RAND()`** — every machine gets identical rows, so an EXPLAIN result
  recorded on one laptop reproduces on another.
- **Neither script creates an index on `customers.city`, deliberately.**
  That is the variable the project's central before/after demo manipulates;
  pre-creating `idx_city` would delete the "before" half and leave the
  Schema Advisor with nothing to report.

### Verified live after the fix (not just compiled)

- `SELECT * FROM customers WHERE city='Mumbai'` → `explainFailed: false`,
  `accessType: ALL`, `estimatedRows: 303`, `hasFullTableScan: true`.
- `/schema-advisor` on the same query → one INDEX recommendation,
  `CREATE INDEX idx_customers_city ON customers(city);`.
- A JOIN with GROUP BY + ORDER BY → both tables present and distinct
  (`c`: ALL, 303, "Using temporary; Using filesort"; `o`: ref, 2, "Using
  index"), 3 suggestions. This exercises the multi-table path Section 7's
  rewrite added.
- Full suite: **38 tests, 0 failures** (22 analyzer, 5 JSON parser, 10
  Ollama, 1 context load).

### Still open after this session

- The `h2` profile remains a genuine trap, now documented in
  `application.properties` itself: it boots fine and makes analysis fail
  with a message that blames the query. Section 12's entry stands.
- MongoDB is not running on this machine (`localhost:27017` refused during
  the test run) — harmless for the suite, but Mongo analysis is untested here.
- Not re-verified this session: Section 15's owed Bug 9 test matrix, and
  Section 16's "Layer 3 end-to-end inside the running application".

### State this session left the machine in

**The 3306 database is already seeded — do not re-run `data.sql` expecting
it to do something.** `customers` = 303 rows, `orders` = 900, 5 distinct
cities, `ANALYZE TABLE` run. Both scripts are safe to re-run regardless
(`IF NOT EXISTS` / `INSERT IGNORE`), they just won't change anything.

**On empty tables — the answer is yes, it is a real problem, not cosmetic.**
Worth stating plainly because it fails silently rather than erroring:
EXPLAIN on a 0-row table returns a degenerate plan, so the analysis engine
succeeds and reports nothing meaningful, and `isSafeLocationCondition()`
reads an empty city list and stops deferring queries it should defer. A
teammate who sets up MySQL but skips the seed step gets a system that looks
like it works.

**Ports this project occupies on this machine:** 8080 (backend), 5173
(frontend Vite dev server, sibling repo), 3306 (MySQL 9.7 — the real
`querysense`), 3307 (MySQL 8.0 — empty, not used by the app any more),
11434 (Ollama). MongoDB's 27017 was NOT running.

**Nothing from this session is committed.** The working tree carries the
port change, the `EXPLAIN FORMAT=TRADITIONAL` fix, its regression test, the
two new `mysql/` scripts and this section, on top of a large body of
pre-existing uncommitted work from earlier sessions (the Ollama migration,
the multi-table EXPLAIN rewrite, the H2 profile). Anyone committing should
separate those rather than sweeping them into one commit.
