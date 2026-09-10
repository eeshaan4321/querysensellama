# Schema Advisor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Inline execution in the current session is required here (not subagent dispatch) — the user needs to see real curl output and live-DB verification directly, and the environment (running MySQL instance, Spring Boot process) is stateful across steps.

**Goal:** Build a Schema Advisor module that consumes `QueryAnalysisResult` and returns a list of `SchemaRecommendation`s (index/normalization/structural advice), exposed via `POST /api/query/schema-advisor`, matching Objective #5 of the synopsis.

**Architecture:** Interface + Impl service (`SchemaAdvisorService` / `SchemaAdvisorServiceImpl`) that applies pure rule logic over an already-populated `QueryAnalysisResult` — no new DB access of its own. A small, additive extension to `QueryAnalysisResult` (`filteredColumn`, `orderByColumn`) is required first, because neither field exists today and the Schema Advisor cannot name a column to index without them. These two fields are populated by `SqlStructureParser` (from the JSQL Parser AST it already builds) and `MongoSimpleAnalyzer` (from the field name its regex already matches) — no new parsing logic is introduced, existing parsing is just exposed one level further.

**Tech Stack:** Java 17, Spring Boot 3, JSQL Parser 5.3 (already a dependency, used by `SqlStructureParser`). No new dependencies.

**Spec:** User's task message (this session) — Task A, verbatim rule logic and verification steps. Cross-referenced against `CLAUDE.md` Sections 8, 9, 11.

## Global Constraints

- Every new class gets a Javadoc header explaining WHY, matching `analysis/MySqlExplainAnalyzer.java`'s tone/depth — not restating what the code does.
- Interface + Impl pattern for the service layer; controller depends on the interface.
- Never crash on bad/unparseable input — degrade to a warning, matching `SqlStructureParser`/`MySqlExplainAnalyzer`.
- Consistency guard (Paper #8, MonoM): never emit an INDEX recommendation when the relevant flag indicates nothing is wrong.
- Work on branch `feature/schema-advisor`. No commit/push without showing the diff and getting explicit confirmation first.
- Verification is real integration testing against the actual local MySQL instance (mirrors how Anant's module was proven) — not JUnit unit tests. This codebase has no unit test suite for the analysis layer; the established convention is curl + live EXPLAIN before/after. Following it here for consistency, per user instruction to use "the same approach used for Anant's Query Analysis Engine."

---

### Task 1: Extend `QueryAnalysisResult` with column-level signals

**Why this task exists:** `QueryAnalysisResult` currently tells downstream modules *that* a query has a full-table-scan or filesort problem, but not *which column* is responsible. The Schema Advisor cannot write `CREATE INDEX ... ON table(column)` without a column name. Extending the shared contract (rather than having the Schema Advisor re-parse the raw query string itself) keeps the "parse once, expose to everyone" architecture that `SqlStructureParser`'s own Javadoc argues for, and avoids a second, divergent SQL-parsing implementation living in the Schema Advisor.

**Files:**
- Modify: `src/main/java/com/querysense/backend/dto/QueryAnalysisResult.java`
- Modify: `src/main/java/com/querysense/backend/analysis/SqlStructureParser.java`
- Modify: `src/main/java/com/querysense/backend/analysis/MongoSimpleAnalyzer.java`

**Interfaces:**
- Produces: `QueryAnalysisResult.getFilteredColumn(): String` (nullable — first WHERE-clause column for MySQL, offending filter field for MongoDB), `QueryAnalysisResult.getOrderByColumn(): String` (nullable — first ORDER BY column, MySQL only).

- [ ] **Step 1: Add the two fields + getters/setters to `QueryAnalysisResult`**

Add after the existing MongoDB fields (`hasUnindexedFilter`/`hasUnanchoredRegex`), before the shared `warnings` field:

```java
    // ---- Column-level signals (populated where structurally determinable) ----
    // WHY these exist: hasFullTableScan/usesFilesort/hasUnindexedFilter tell
    // downstream modules THAT something is wrong, but the Schema Advisor
    // needs to know WHICH column to name in a CREATE INDEX suggestion. Rather
    // than have the Schema Advisor re-parse the raw query string itself
    // (duplicating SqlStructureParser's JSQL Parser logic), these are
    // populated once, here, by the modules that already build the AST/regex
    // match — same "parse once" principle as tableName/queryType.
    private String filteredColumn;   // first WHERE-clause column (MySQL) or offending filter field (MongoDB); null if not determinable
    private String orderByColumn;    // first ORDER BY column (MySQL only); null if no ORDER BY or not determinable
```

And getters/setters alongside the others:

```java
    public String getFilteredColumn() { return filteredColumn; }
    public void setFilteredColumn(String filteredColumn) { this.filteredColumn = filteredColumn; }

    public String getOrderByColumn() { return orderByColumn; }
    public void setOrderByColumn(String orderByColumn) { this.orderByColumn = orderByColumn; }
```

- [ ] **Step 2: Populate `filteredColumn`/`orderByColumn` in `SqlStructureParser.handleSelect`**

Add imports:
```java
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
```

Inside `handleSelect`, after the existing `hasOrderBy`/`hasLimit` warning block, add:

```java
            if (hasWhere) {
                result.setFilteredColumn(extractFirstWhereColumn(plainSelect.getWhere()));
            }
            if (hasOrderBy) {
                result.setOrderByColumn(
                        plainSelect.getOrderByElements().get(0).getExpression().toString());
            }
```

New private helper method (degrades to `null` rather than throwing, per class convention):

```java
    /**
     * Extracts the left-hand column name from a simple WHERE clause
     * (e.g. "city = 'Mumbai'" -> "city"). For an AND-chain, takes the
     * first condition, since that's enough signal for an index
     * recommendation — this is not a full expression evaluator, just a
     * best-effort column hint for the Schema Advisor. Anything more
     * complex (OR, subqueries, function calls) degrades to null; the
     * caller must handle a missing column name gracefully.
     */
    private String extractFirstWhereColumn(Expression where) {
        if (where instanceof AndExpression andExpression) {
            return extractFirstWhereColumn(andExpression.getLeftExpression());
        }
        if (where instanceof ComparisonOperator comparison) {
            return comparison.getLeftExpression().toString();
        }
        return null;
    }
```

- [ ] **Step 3: Populate `filteredColumn` in `MongoSimpleAnalyzer.checkUnindexedFilter`**

In the existing loop, right before `break;`, add:

```java
                result.setFilteredColumn(field);
```

so the method body reads:

```java
                result.setHasUnindexedFilter(true);
                result.setFilteredColumn(field);
                result.addWarning("Filtering on '" + field + "' with no known index — "
                        + "this may cause a full collection scan.");
                break; // one clear warning is enough for MVP scope
```

- [ ] **Step 4: Rebuild and confirm no compile errors**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Manual smoke check via existing `/api/query/analyze` endpoint**

Start the app (`./mvnw spring-boot:run`), then:

```bash
curl -s -X POST http://localhost:8080/api/query/analyze \
  -H "Content-Type: application/json" \
  -d '{"query":"SELECT * FROM customers WHERE email = '\''alice@test.com'\''","databaseType":"MySQL"}'
```

Expected: response now includes `"filteredColumn":"email"` in addition to the existing fields. This confirms Task 1 before building anything that depends on it.

---

### Task 2: `SchemaRecommendation` DTO

**Files:**
- Create: `src/main/java/com/querysense/backend/dto/SchemaRecommendation.java`

**Interfaces:**
- Produces: `SchemaRecommendation` with fields `type` (String: `"INDEX"` | `"NORMALIZATION"` | `"STRUCTURAL"`), `targetTable` (String, nullable), `targetColumn` (String, nullable), `reason` (String), `suggestedAction` (String). No-arg constructor + getters/setters (matches `QueryAnalysisResult`'s style, not the Lombok style used in `QueryResponse` — chosen for consistency with the sibling DTO this one is modeled after and consumed alongside).

- [ ] **Step 1: Write the DTO**

```java
package com.querysense.backend.dto;

/**
 * SchemaRecommendation
 *
 * WHY THIS CLASS EXISTS:
 * This is the Schema Advisor's half of the shared-contract pattern already
 * established by QueryAnalysisResult — SchemaAdvisorService reads a
 * QueryAnalysisResult and produces a list of these. Keeping it a plain DTO
 * (not a JPA @Entity) mirrors QueryAnalysisResult's own choice: persistence
 * for the SCHEMA_RECOMMENDATION ER entity is a separate concern (History
 * module territory) that hasn't been wired up yet, and this class shouldn't
 * block on that decision.
 *
 * FIELD NOTES:
 * - targetColumn is nullable: a STRUCTURAL recommendation (e.g. "review
 *   your GROUP BY/DISTINCT usage") isn't about one column, and even an
 *   INDEX recommendation can't always name a column (see
 *   QueryAnalysisResult.filteredColumn's own nullability notes).
 * - reason must reference the actual QueryAnalysisResult signal that fired
 *   it (e.g. "hasFullTableScan was true and no index was used"), not a
 *   generic template sentence — same "warnings are human-readable, not
 *   codes" principle QueryAnalysisResult already follows.
 * - This shape has NOT been reconciled against the SCHEMA_RECOMMENDATION
 *   entity in the ER diagram (CLAUDE.md Section 6 flags this as an
 *   existing gap — no column-level field list for that entity could be
 *   found in the repo). Treat this as provisional until checked against
 *   the actual diagram export.
 */
public class SchemaRecommendation {

    private String type;           // "INDEX", "NORMALIZATION", "STRUCTURAL"
    private String targetTable;
    private String targetColumn;   // nullable
    private String reason;
    private String suggestedAction;

    public SchemaRecommendation() {}

    public SchemaRecommendation(String type, String targetTable, String targetColumn,
                                 String reason, String suggestedAction) {
        this.type = type;
        this.targetTable = targetTable;
        this.targetColumn = targetColumn;
        this.reason = reason;
        this.suggestedAction = suggestedAction;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }

    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
}
```

- [ ] **Step 2: Compile check**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`

---

### Task 3: `SchemaAdvisorService` interface + impl

**Files:**
- Create: `src/main/java/com/querysense/backend/service/SchemaAdvisorService.java`
- Create: `src/main/java/com/querysense/backend/service/impl/SchemaAdvisorServiceImpl.java`

**Interfaces:**
- Consumes: `QueryAnalysisResult` (all getters from Task 1 + existing fields).
- Produces: `SchemaAdvisorService.recommend(QueryAnalysisResult result): List<SchemaRecommendation>`.

- [ ] **Step 1: Write the interface**

```java
package com.querysense.backend.service;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;

import java.util.List;

/**
 * SchemaAdvisorService
 *
 * WHY AN INTERFACE: same reasoning as QueryAnalysisService — the
 * Comparison Engine (Task C) will need to run the Schema Advisor on both
 * the original and optimized query's QueryAnalysisResult, and should
 * depend on this interface rather than SchemaAdvisorServiceImpl directly,
 * in case the rule logic changes shape later (e.g. Eeshaan's
 * Domain-Sensitive module ends up wanting to filter/reweight
 * recommendations by domain profile).
 */
public interface SchemaAdvisorService {

    /**
     * Applies rule-based schema/index recommendations against an already
     * analyzed query.
     *
     * @param result a populated QueryAnalysisResult (from QueryAnalysisService)
     * @return a list of recommendations; empty (never null) if nothing is wrong
     */
    List<SchemaRecommendation> recommend(QueryAnalysisResult result);
}
```

- [ ] **Step 2: Write the impl**

```java
package com.querysense.backend.service.impl;

import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.SchemaAdvisorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SchemaAdvisorServiceImpl
 *
 * WHY THIS CLASS EXISTS:
 * Implements Objective #5 from the synopsis ("schema analysis and
 * recommendations, such as indexing, normalization, and structural
 * additions"). Every rule here reads a QueryAnalysisResult signal that
 * Anant's engine already proved is grounded in real EXPLAIN/regex
 * behavior (not invented heuristics) — see Paper #7 (DBA Bandits) in
 * CLAUDE.md, which is exactly the justification for anchoring index
 * advice to observed behavior rather than generic static rules like
 * "index every filtered column regardless of evidence."
 *
 * WHY A SINGLE FLAT METHOD INSTEAD OF ONE RULE-CLASS PER RECOMMENDATION:
 * There are five rules total (three MySQL, two MongoDB) and none of them
 * share state or need independent unit isolation yet. Matches the scale
 * QueryAnalysisServiceImpl operates at — introducing a Strategy-pattern
 * rule engine here would be solving a problem this project doesn't have
 * yet (YAGNI); if the rule count grows substantially this can be revisited.
 *
 * CONSISTENCY GUARD (Paper #8, MonoM):
 * Every rule below is gated on the specific problem flag that justifies
 * it — usesIndex being true never by itself suppresses a rule, because
 * a query can use an index for its WHERE clause and still need filesort
 * for its ORDER BY (they're independent EXPLAIN signals). What Paper #8
 * actually rules out is recommending an index for a problem that isn't
 * present — hence hasFullTableScan's rule requires BOTH the scan flag
 * AND !usesIndex, so we never suggest "add an index" when one is already
 * doing the job for that specific problem.
 */
@Service
public class SchemaAdvisorServiceImpl implements SchemaAdvisorService {

    @Override
    public List<SchemaRecommendation> recommend(QueryAnalysisResult result) {
        List<SchemaRecommendation> recommendations = new ArrayList<>();

        if ("MongoDB".equalsIgnoreCase(result.getDatabaseType())) {
            addMongoRecommendations(result, recommendations);
        } else {
            addMySqlRecommendations(result, recommendations);
        }

        return recommendations;
    }

    private void addMySqlRecommendations(QueryAnalysisResult result, List<SchemaRecommendation> recommendations) {
        if (result.isHasFullTableScan() && !result.isUsesIndex()) {
            String column = result.getFilteredColumn();
            String columnClause = column != null ? column : "<the column used in the WHERE clause>";
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
                    "Full table scan detected (accessType=ALL) with no index in use"
                            + (column != null ? " on '" + column + "'" : "")
                            + " — this is the same signal Anant's engine flags via EXPLAIN.",
                    "CREATE INDEX idx_" + safeName(result.getTableName(), column)
                            + " ON " + result.getTableName() + "(" + columnClause + ");"
            ));
        }

        if (result.isUsesFilesort()) {
            String column = result.getOrderByColumn();
            String columnClause = column != null ? column : "<the ORDER BY column>";
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    result.getTableName(),
                    column,
                    "Query requires an extra sorting pass (Extra: 'Using filesort')"
                            + (column != null ? " for ORDER BY '" + column + "'" : "")
                            + " — an index matching the sort column can eliminate this.",
                    "CREATE INDEX idx_" + safeName(result.getTableName(), column)
                            + " ON " + result.getTableName() + "(" + columnClause + ");"
            ));
        }

        if (result.isUsesTemporaryTable()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    result.getTableName(),
                    null,
                    "Query requires a temporary table (Extra: 'Using temporary'), typically caused "
                            + "by GROUP BY or DISTINCT without a supporting index — this isn't always "
                            + "fixable with a single-column index, so it's flagged for review rather "
                            + "than an automatic CREATE INDEX suggestion.",
                    "Review the GROUP BY/DISTINCT clause on '" + result.getTableName()
                            + "'; consider a composite index covering the grouped column(s) if one column is consistently responsible."
            ));
        }
    }

    private void addMongoRecommendations(QueryAnalysisResult result, List<SchemaRecommendation> recommendations) {
        if (result.isHasUnindexedFilter()) {
            String field = result.getFilteredColumn();
            String collection = result.getTableName(); // null today — see MongoSimpleAnalyzer's collectionName gap
            recommendations.add(new SchemaRecommendation(
                    "INDEX",
                    collection,
                    field,
                    "Filtering on '" + field + "' with no known index — MongoSimpleAnalyzer flagged this "
                            + "as a likely full collection scan.",
                    "db." + (collection != null ? collection : "<collection>")
                            + ".createIndex({ \"" + field + "\": 1 });"
            ));
        }

        if (result.isHasUnanchoredRegex()) {
            recommendations.add(new SchemaRecommendation(
                    "STRUCTURAL",
                    result.getTableName(),
                    null,
                    "Unanchored $regex pattern detected — MongoDB cannot use an index for a regex "
                            + "that isn't left-anchored, regardless of whether an index exists on the field.",
                    "Rewrite the regex with a leading '^' if a prefix match is intended, "
                            + "or create a text index (db.<collection>.createIndex({ <field>: \"text\" })) "
                            + "if full substring search is required."
            ));
        }
    }

    /**
     * Builds a safe index-name suggestion even when the column name is
     * unknown — falls back to a generic "col" placeholder rather than
     * producing "idx_customers_null" or throwing on a null column.
     */
    private String safeName(String tableName, String column) {
        String table = tableName != null ? tableName : "table";
        String col = column != null ? column : "col";
        return table + "_" + col;
    }
}
```

- [ ] **Step 3: Compile check**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`

---

### Task 4: `SchemaAdvisorController`

**Files:**
- Create: `src/main/java/com/querysense/backend/controller/SchemaAdvisorController.java`

**Interfaces:**
- Consumes: `AnalyzeRequest` (existing DTO — same shape reused, no new request DTO needed), `QueryAnalysisService.analyze(...)`, `SchemaAdvisorService.recommend(...)`.
- Produces: `POST /api/query/schema-advisor` returning `List<SchemaRecommendation>`.

- [ ] **Step 1: Write the controller**

```java
package com.querysense.backend.controller;

import com.querysense.backend.dto.AnalyzeRequest;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.service.QueryAnalysisService;
import com.querysense.backend.service.SchemaAdvisorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SchemaAdvisorController
 *
 * WHY A SEPARATE CONTROLLER (not folded into QueryAnalysisController):
 * Same reasoning QueryAnalysisController already documents for staying
 * separate from QueryController — this is a distinct concern (schema
 * recommendations vs. raw analysis) with its own service
 * (SchemaAdvisorService), so it gets its own controller rather than
 * growing QueryAnalysisController into a class with two responsibilities.
 *
 * WHY IT RE-RUNS QueryAnalysisService INSTEAD OF TAKING A
 * QueryAnalysisResult DIRECTLY IN THE REQUEST BODY:
 * Matches AnalyzeRequest's existing shape (query + databaseType) so the
 * frontend can call this endpoint the same way it calls /analyze,
 * without needing to first call /analyze, copy the response, and paste
 * it into a second request. The tradeoff (EXPLAIN runs twice if a caller
 * wants both endpoints) is acceptable at this project's scale.
 */
@RestController
@RequestMapping("/api/query")
public class SchemaAdvisorController {

    private final QueryAnalysisService queryAnalysisService;
    private final SchemaAdvisorService schemaAdvisorService;

    public SchemaAdvisorController(QueryAnalysisService queryAnalysisService,
                                    SchemaAdvisorService schemaAdvisorService) {
        this.queryAnalysisService = queryAnalysisService;
        this.schemaAdvisorService = schemaAdvisorService;
    }

    @PostMapping("/schema-advisor")
    public List<SchemaRecommendation> schemaAdvisor(@Valid @RequestBody AnalyzeRequest request) {
        QueryAnalysisResult analysisResult =
                queryAnalysisService.analyze(request.getQuery(), request.getDatabaseType());
        return schemaAdvisorService.recommend(analysisResult);
    }
}
```

- [ ] **Step 2: Full rebuild**

Run: `./mvnw clean spring-boot:run` (per CLAUDE.md gotcha: always clean rebuild after touching analyzer classes; check port 8080 isn't already occupied first if this fails to start)

---

### Task 5: Verification (live MySQL before/after + MongoDB path)

**No new files.** This task is the gate before Task A can be marked done.

**Starting state check (already confirmed this session):** `customers` table has `PRIMARY(id)` and `idx_city(city)` already — `idx_city` was created during Anant's original before/after test and never dropped. `email` is the unindexed column available for a fresh before/after cycle. Sample rows exist (`alice@test.com` etc.).

- [ ] **Step 1: BEFORE — full table scan / no index, on `email`**

```bash
curl -s -X POST http://localhost:8080/api/query/schema-advisor \
  -H "Content-Type: application/json" \
  -d '{"query":"SELECT * FROM customers WHERE email = '\''alice@test.com'\''","databaseType":"MySQL"}'
```

Expected: one `"type":"INDEX"` recommendation, `"targetColumn":"email"`, `"suggestedAction":"CREATE INDEX idx_customers_email ON customers(email);"`.

- [ ] **Step 2: Apply the suggested index for real**

```bash
mysql -h127.0.0.1 -P3307 -uroot -ppassword querysense -e "CREATE INDEX idx_customers_email ON customers(email);"
```

- [ ] **Step 3: AFTER — same query, same endpoint**

Re-run the exact curl from Step 1.
Expected: empty list `[]` — the full-table-scan flag is gone because `usesIndex` is now true, so the consistency guard suppresses the recommendation.

- [ ] **Step 4: Show both responses side by side to the user** before claiming Task A done.

- [ ] **Step 5: Filesort path**

```bash
curl -s -X POST http://localhost:8080/api/query/schema-advisor \
  -H "Content-Type: application/json" \
  -d '{"query":"SELECT * FROM customers ORDER BY name","databaseType":"MySQL"}'
```

Expected: `"type":"INDEX"`, `"targetColumn":"name"`, reason mentioning filesort.

- [ ] **Step 6: Temporary-table path**

```bash
curl -s -X POST http://localhost:8080/api/query/schema-advisor \
  -H "Content-Type: application/json" \
  -d '{"query":"SELECT city, COUNT(*) FROM customers GROUP BY city","databaseType":"MySQL"}'
```

Expected: either `[]` (if MySQL's optimizer avoids a temp table here since `city` is already indexed and the table is tiny — plausible given only 3 rows) or one `"type":"STRUCTURAL"` recommendation if `usesTemporaryTable` fires. Either outcome is valid — report whichever actually happens, don't force a particular result.

- [ ] **Step 7: MongoDB path — unindexed filter**

```bash
curl -s -X POST http://localhost:8080/api/query/schema-advisor \
  -H "Content-Type: application/json" \
  -d '{"query":"{ \"name\": \"Bob\" }","databaseType":"MongoDB"}'
```

Expected: `"type":"INDEX"`, `"targetColumn":"name"`, `"suggestedAction":"db.<collection>.createIndex({ \"name\": 1 });"` — note `targetTable`/`collection` will be null/`<collection>`, a pre-existing gap (MongoSimpleAnalyzer is always called with `collectionName=null` today) — call this out explicitly rather than silently working around it.

- [ ] **Step 8: MongoDB path — unanchored regex**

```bash
curl -s -X POST http://localhost:8080/api/query/schema-advisor \
  -H "Content-Type: application/json" \
  -d '{"query":"{ \"name\": { \"$regex\": \"Bob\" } }","databaseType":"MongoDB"}'
```

Expected: `"type":"STRUCTURAL"` recommendation about the missing `^` anchor.

- [ ] **Step 9: If any result doesn't match expectations, stop and show the actual failure** — do not silently adjust the rule logic and re-report success without flagging the discrepancy.

---

### Task 6: Documentation + branch hygiene (only after Task 5 passes)

**Files:**
- Modify: `CLAUDE.md` (Sections 7 and 8 — mark Schema Advisor complete, same style as Anant's entry)

- [ ] **Step 1: Create the feature branch** (if not already on it): `git checkout -b feature/schema-advisor`
- [ ] **Step 2: Update CLAUDE.md** with a "Vedant's Schema Advisor — COMPLETE" entry under Section 7, matching Anant's entry's tone (what was built, what was proven, any known gaps carried over: ER diagram reconciliation, Mongo `collectionName` still null).
- [ ] **Step 3: Show the full diff to the user** (`git diff` / `git status`) and ask for explicit confirmation before any commit.
- [ ] **Step 4: Do not commit or push until the user confirms.**

---

## Self-Review Notes

- **Spec coverage:** All five rules from the user's Task A spec are covered (full-scan/index, filesort, temp-table, Mongo unindexed filter, Mongo unanchored regex), plus the MonoM consistency guard, plus the explicit column-name decision (extending `QueryAnalysisResult`/`SqlStructureParser`, not re-parsing in the Schema Advisor — reasoning stated in Task 1's header for the user to review).
- **Placeholder scan:** No TBD/TODO markers; every step has literal code. The one open item (SCHEMA_RECOMMENDATION ER field reconciliation) is explicitly flagged as provisional in the DTO's own Javadoc, per the user's explicit instruction to note this rather than invent fields silently.
- **Type consistency:** `SchemaAdvisorService.recommend(QueryAnalysisResult): List<SchemaRecommendation>` is the same signature used in Task 3's interface, impl, and Task 4's controller call site.
