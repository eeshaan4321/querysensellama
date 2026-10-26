# QuerySense end-to-end test: COMPANY schema, 50 questions

Run on **2026-10-20, 19:48:58–19:55:58 IST**, as one continuous run. Every figure
below comes from that run. Where a number could not be measured, the report says
so instead of estimating.

This file is self-contained. Appendices A–H at the end carry the raw data,
generated from `results_final.json` by `build_appendix.py`:
- the upload checks;
- the full generated and ground-truth SQL for all 50 questions;
- per-question token counts;
- every EXPLAIN row, verdict, suggestion and advisor recommendation at every resource level;
- every timed run and `EXPLAIN ANALYZE` output;
- every rewrite and every MongoDB query.

---

## Headline results

| Result | Value |
|---|---|
| Questions answered by the rule engine, no model called | **33 of 50 (66%)** |
| Rule-engine answers matching hand-written ground truth (same result rows) | **32 of 33**. The one miss is a confident wrong answer (defect D1) |
| Generated queries that execute in MySQL (excluding the 3 flagged unsafe ones) | **47 of 47** |
| Unsafe requests (DELETE, UPDATE without WHERE, DROP) | **3 of 3** flagged at 0.20; none reached MySQL; none cached |
| QuerySense plan fields matching MySQL's own `EXPLAIN`, field by field | **26 of 26** queries (37 table rows); query cost **26 of 26** |
| LLM tokens, QuerySense pipeline vs LLM-only (same 50 questions) | **13,178 vs 38,363 tokens (−65.6%)**; 17 vs 50 model calls; 10.5 s vs 23.0 s of model time (−54.3%) |
| Repeated LLM questions served from cache | **14 of 14**, identical answers, median **29.1 ms** (LLM first ask: 725 ms) |
| Advisor's own index, applied by hand (7 queries where it helped) | estimated rows read −99.96% to −99.99%; measured median time −90.5% to −97.2% |
| Advisor indexes that did **not** help | 2 of 9 (one duplicated an existing unique index; one was never used by MySQL) |

---

## 0. Environment and setup

| Item | Value |
|---|---|
| Date and time | 2026-10-20, run 19:48:58–19:55:58 (+0530) |
| Branch / commit | `feature/schema-adaptive-rule-engine` at `b6d5134f7317fb1c417c97ee6566e4b0351fa16b`, **plus 55 uncommitted files**. The code under test is the working tree, not the commit: it includes today's uncommitted rule-engine work (join choice by wording, thesaurus, year columns, narrower words) and the earlier uncommitted fixes |
| Java | OpenJDK 25.0.3 (2026-04-21 LTS) |
| MySQL | 9.7.0, port 3306. `explain_json_format_version = 2` (see §4 method) |
| Ollama / model | Ollama 0.40.6; `qwen2.5-coder:7b` (7.6B, Q4_K_M); request options `temperature 0`, `seed 42`, `num_predict 300`, `keep_alive 30m` |
| GPU | NVIDIA GeForce RTX 5060 Laptop GPU, 8151 MiB, driver 595.95 |
| Seeding | `querysense.schema.upload.seed-rows=12000`, `seed=20260910` (application.properties). The file's own rows are loaded first |
| Test client | Python 3.14.3, standard library only; MySQL accessed with the `mysql` 9.7 command-line client |

**Schema file.** `Employee_Database_Script.sql` from tolgahanakgun/Elmasri-Database.
The copy used (in this folder) has SHA-256
`24e02e175162d7989dd2e537c5226151ddcfd77e8980416aa1d0e5dcace64687`, identical
to the file downloaded from GitHub during this run.

**Procedure.**
1. Temporary instrumentation was applied (below).
2. The backend was restarted fresh with `./mvnw clean spring-boot:run`.
3. The file was uploaded to `POST /api/schema/upload`.
4. History rows for the schema label were cleared.
5. The phases of `run_company50.py` were run in order: `env`, `upload`, `clear-cache`, `generate`, `tokens`, `analyze`, `compare`, `index`, `mongo`, `merge`.
6. `summarize.py` produced the numbers below from `raw/*.json`.

**Temporary instrumentation (reverted).** One block was added to
`OllamaService.post()`. It logs Ollama's `prompt_eval_count`, `eval_count`,
`total_duration` and the exact prompt (base64) for every model call. The exact
diff is saved as `TEMP-instrumentation.patch`.
- After the run the block was removed by hand. `git diff` on
  `OllamaService.java` is now empty, and no `TEMP-TOKEN` text remains under
  `backend/src`.
- The backend kept running the patched build after the source was reverted,
  because devtools did not recompile. It was therefore stopped and restarted
  from the clean source after the measurements.
- No other production code was touched.

**Cache cleared.**
- Label `Employee_Database_Script.sql`: deleted **0** rows. This label had never been used; the previous run used the label `company.sql`, and the cache key includes the label.
- MongoDB set (§9): deleted the **21** history rows whose `database_type='MongoDB'` matched one of the 10 Mongo questions. They are listed in `raw/mongo_cache_deleted.tsv`.
- No other rows were touched.

### Upload (read from `information_schema`)

| Check | Result |
|---|---|
| HTTP status, time | 200, **5,887 ms** |
| Rows per table | EMPLOYEE, DEPARTMENT, DEPT_LOCATIONS, PROJECT, WORKS_ON, DEPENDENT: **12,000 each** |
| Foreign keys enforced by MySQL | **8 of 8**: DEPARTMENT.Mgr_ssn, DEPENDENT.Essn, DEPT_LOCATIONS.Dnumber, EMPLOYEE.Dno, EMPLOYEE.Super_ssn, PROJECT.Dnum, WORKS_ON.Essn, WORKS_ON.Pno |
| Orphan values across the 8 FKs | **0** for each |
| File rows kept | John Smith (123456789, 30000, supervisor 333445555, Dno 5), Franklin Wong (333445555, 40000, 888665555, 5), James Borg (888665555, 55000, NULL, 1) |
| Upload warnings | none |

### The 50 questions

Final category counts:

| Category | Count | Change from the requested mix |
|---|---|---|
| simple select / filter | 8 | — |
| aggregates | 7 | +1 (COUNT, AVG, MAX, MIN and SUM all covered) |
| GROUP BY | 5 | −1 |
| sort / top-N | 6 | — |
| joins | 8 | — (2-table ×5, 3-table ×2, 4-table ×1) |
| self-join / supervisor | 3 | — |
| anti-join | 3 | — |
| synonyms / typos | 4 | — |
| deliberately vague | 3 | — |
| unsafe | 3 | — |

**Paraphrase groups (8 groups, 16 questions):**
- P1: 2/3
- P2: 9/10
- P3: 11/12
- P4: 1/41
- P5: 29/30
- P6: 38/39
- P7: 21/22
- P8: 5/6

Ground truth was written for **46 of 50** questions; there is none for 47 (no table named) or 48–50 (unsafe).

---

## 2. Generation: routing and correctness

**Method.**
- Each question was sent once to `POST /api/query/generate` (MySQL, MEDIUM).
- Every returned SELECT was run directly in MySQL. Flagged and unsafe answers were never run.
- Ground truth was run in MySQL, and the two results were compared as **multisets of rows, ignoring row and column order**.
- Labels used:
  - *identical*;
  - *fewer columns*: same rows; the generated columns are a subset of the truth's;
  - *more columns*: same rows plus extra columns, verified by projecting the generated query onto the truth's columns in MySQL with `EXCEPT ALL` (0 rows either way);
  - *different*.
- Row order is **not** compared, so the sort questions are checked on their row set only.

| # | Cat. | Question | Path | Conf. | ms | Generated SQL | Runs | Truth |
|---|---|---|---|---|---|---|---|---|
| 1 | simple | Show all employees | Rule | 0.95 | 284.1 | `SELECT * FROM EMPLOYEE;` | ✓ 12000 | identical |
| 2 | simple | Show employees with salary above 40000 | Rule | 0.95 | 74.5 | `… WHERE Salary > 40000;` | ✓ 2 | identical |
| 3 | simple | Which employees earn more than 40000 | LLM | 0.65 | 1042.6 | `SELECT Fname, Minit, Lname … WHERE Salary > 40000;` | ✓ 2 | fewer columns |
| 4 | simple | Show employees with salary between 30000 and 50000 | Rule | 0.95 | 36.7 | `… WHERE Salary BETWEEN 30000 AND 50000;` | ✓ 4 | identical |
| 5 | simple | Show projects in Houston | Rule | 0.95 | 41.6 | `… WHERE Plocation = 'houston';` | ✓ 2 | identical |
| 6 | simple | Which projects are located in Houston | Rule | 0.95 | 30.4 | `… WHERE Plocation = 'houston';` | ✓ 2 | identical |
| 7 | simple | Show employees with Dno 5 | Rule | 0.95 | 41.5 | `… WHERE Dno = 5;` | ✓ 5 | identical |
| 8 | simple | Show dependents whose relationship is spouse | Rule | 0.95 | 29.1 | `… WHERE Relationship = 'spouse';` | ✓ 3 | identical |
| 9 | agg | How many employees are there | Rule | 0.95 | 13.1 | `SELECT COUNT(*) FROM EMPLOYEE;` | ✓ | identical |
| 10 | agg | Count the employees | Rule | 0.95 | 23.8 | `SELECT COUNT(*) FROM EMPLOYEE;` | ✓ | identical |
| 11 | agg | Average salary of employees | Rule | 0.95 | 14.1 | `SELECT AVG(Salary) FROM EMPLOYEE;` | ✓ | identical |
| 12 | agg | What is the mean salary of employees | Rule | 0.95 | 27.8 | `SELECT AVG(Salary) FROM EMPLOYEE;` | ✓ | identical |
| 13 | agg | Maximum salary of employees | Rule | 0.95 | 18.5 | `SELECT MAX(Salary) FROM EMPLOYEE;` | ✓ | identical |
| 14 | agg | Minimum salary of employees | Rule | 0.95 | 25.5 | `SELECT MIN(Salary) FROM EMPLOYEE;` | ✓ | identical |
| 15 | agg | Sum of hours of works_on | Rule | 0.95 | 20.0 | `SELECT SUM(Hours) FROM WORKS_ON;` | ✓ | identical |
| 16 | group | How many employees per Dno | Rule | 0.95 | 9.1 | `SELECT Dno, COUNT(*) … GROUP BY Dno;` | ✓ 7521 | identical |
| 17 | group | How many projects per Dnum | Rule | 0.95 | 11.0 | `SELECT Dnum, COUNT(*) … GROUP BY Dnum;` | ✓ 7602 | identical |
| 18 | group | Average salary per Dno | LLM | 0.65 | 388.4 | `SELECT AVG(Salary), Dno … GROUP BY Dno;` | ✓ 7521 | identical |
| 19 | group | Count of dependents per relationship | Rule | 0.95 | 11.2 | `SELECT Relationship, COUNT(*) … GROUP BY Relationship;` | ✓ 2166 | identical |
| 20 | group | Total hours per Pno in works_on | LLM | 0.65 | 505.2 | `SELECT Pno, SUM(Hours) AS Total_Hours … GROUP BY Pno;` | ✓ 7577 | identical |
| 21 | sort | Top 5 employees by salary | Rule | 0.95 | 35.6 | `… ORDER BY Salary DESC LIMIT 5;` | ✓ 5 | identical |
| 22 | sort | Show the 5 highest paid employees | LLM | 0.65 | 506.4 | `SELECT Fname, Minit, Lname, Salary … ORDER BY Salary DESC LIMIT 5;` | ✓ 5 | fewer columns |
| 23 | sort | Show departments sorted by Dname | Rule | 0.95 | 24.0 | `… ORDER BY Dname ASC;` | ✓ 12000 | identical (set) |
| 24 | sort | Show projects sorted by Pname descending | Rule | 0.95 | 28.8 | `… ORDER BY Pname DESC;` | ✓ 12000 | identical (set) |
| 25 | sort | Show the oldest employee | Rule | 0.95 | 10.3 | `… ORDER BY Bdate ASC LIMIT 1;` | ✓ 1 | identical |
| 26 | sort | First 10 employees sorted by Lname | Rule | 0.95 | 27.8 | `… ORDER BY Lname ASC LIMIT 10;` | ✓ 10 | identical |
| 27 | join | Show dependents with their employees | Rule | 0.95 | 16.5 | `DEPENDENT JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn` | ✓ 12000 | identical |
| 28 | join | Show projects with their department | Rule | 0.95 | 10.8 | `PROJECT JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber` | ✓ 12000 | identical |
| 29 | join | Show employees with their department | Rule | **0.85** | 11.9 | `EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber` (join chosen by wording: Dno over Mgr_ssn) | ✓ 12000 | identical |
| 30 | join | List each employee and the department they work in | LLM | 0.65 | 737.6 | `E.Fname, E.Minit, E.Lname, D.Dname … JOIN … E.Dno = D.Dnumber` | ✓ 12000 | fewer columns |
| 31 | join | Show departments and their locations | LLM | 0.65 | 712.8 | `D.Dname, DL.Dlocation … JOIN DEPT_LOCATIONS` | ✓ 12000 | fewer columns |
| 32 | join (3) | Names of employees who work on ProductX | LLM | 0.65 | 1322.5 | EMPLOYEE→WORKS_ON→PROJECT, `Pname = 'ProductX'`, returns Fname, Minit, Lname | ✓ 3 | **more columns** (same people; extra Minit) |
| 33 | join (3) | Show employees and the projects they work on | LLM | 0.65 | 913.5 | EMPLOYEE→WORKS_ON→PROJECT | ✓ 12000 | fewer columns |
| 34 | join (4) | Show dependents of employees who work on ProductX | LLM | 0.65 | 1354.6 | DEPENDENT→EMPLOYEE→WORKS_ON→PROJECT, returns Dependent_name | ✓ 3 | fewer columns |
| 35 | self | Show employees and the name of their supervisor | LLM | 0.65 | 998.3 | self-join `E.Super_ssn = S.Ssn`, 6 name columns | ✓ 11998 | **more columns** (verified) |
| 36 | self | Show employees supervised by Franklin Wong | LLM | 0.65 | 1109.6 | self-join, `M.Fname='Franklin' AND M.Lname='Wong'` | ✓ 3 | fewer columns |
| 37 | self | How many employees does each supervisor have | LLM | 0.65 | 564.2 | `COUNT(*), Super_ssn … GROUP BY Super_ssn` | ✓ 6000 | **different**: includes the NULL-supervisor group (2 employees), which the ground truth excluded. Every other group matches. A reasonable reading, not an error |
| 38 | anti | Employees who have no dependents | Rule | 0.95 | 12.8 | `LEFT JOIN DEPENDENT … WHERE DEPENDENT.Essn IS NULL` | ✓ 4354 | identical |
| 39 | anti | Which employees do not have any dependents | Rule | **0.95** | 22.7 | `SELECT * FROM EMPLOYEE JOIN DEPENDENT ON …` (**inner join**) | ✓ 12000 | **different, opposite meaning** (defect D1) |
| 40 | anti | Departments without projects | Rule | 0.95 | 13.7 | `LEFT JOIN PROJECT … WHERE PROJECT.Dnum IS NULL` | ✓ 4398 | identical |
| 41 | syn | Show all staff | Rule | 0.85 | 10.6 | `SELECT * FROM EMPLOYEE;` (synonym) | ✓ | identical |
| 42 | typo | Show all employes | Rule | 0.85 | 24.7 | `SELECT * FROM EMPLOYEE;` (typo) | ✓ | identical |
| 43 | syn | Show the wage of employees | Rule | 0.85 | 38.7 | `SELECT Salary FROM EMPLOYEE;` (synonym) | ✓ | identical |
| 44 | syn | Show all divisions | Rule | 0.85 | 10.3 | `SELECT * FROM DEPARTMENT;` (synonym) | ✓ | identical |
| 45 | vague | Show female employees | LLM | 0.65 | 416.2 | `SELECT Fname, Minit, Lname … WHERE Sex = 'F';` | ✓ 3 | fewer columns |
| 46 | vague | Show employees born after 1965 | **Rule** | 0.85 | 11.5 | `… WHERE Bdate >= '1966-01-01';` ("born" read as Bdate) | ✓ 10837 | identical |
| 47 | vague | Show the first name | LLM | 0.65 | 222.5 | `SELECT Fname FROM EMPLOYEE;` | ✓ | not checked |
| 48 | unsafe | Delete dependents | LLM, **flagged** | **0.20** | 188.6 | `DELETE FROM DEPENDENT;` + warning | not run | – |
| 49 | unsafe | Set the salary of all employees to 0 | LLM, **flagged** | **0.20** | 268.4 | `UPDATE EMPLOYEE SET Salary = 0;` + warning | not run | – |
| 50 | unsafe | Drop the employee table | LLM, **flagged** | **0.20** | 183.5 | `DROP TABLE EMPLOYEE;` + warning | not run | – |

*50 questions; 47 executed in MySQL, all 47 ran; 46 with ground truth, of which 46 were compared.*

**By path.**

| Path | Questions | Runnable | Truth checked | Identical | Same rows (fewer / more columns) | Different | Median ms | Max ms |
|---|---|---|---|---|---|---|---|---|
| Rule engine | **33** | 33/33 | 33 | **32** | 0 | **1** (#39, wrong) | **22.7** | 284.1 (first request after upload) |
| LLM (not flagged) | 14 | 14/14 | 13 | 2 | 8 fewer, 2 more | 1 (#37, NULL group) | **725.2** | 1354.6 |
| LLM, flagged unsafe | 3 | not run | – | – | – | – | 188.6 | 268.4 |
| Cache (14 LLM questions asked again) | 14 | 14/14 identical to first answer | – | – | – | – | **29.1** | 49.5 |

Rule-engine share: **33 of 50 (66%)**. By category:

| Category | Rule engine |
|---|---|
| simple | 7/8 |
| aggregate | 7/7 |
| group-by | 3/5 |
| sort | 5/6 |
| join | 3/8 |
| self-join | 0/3 |
| anti-join | 3/3 |
| synonym/typo | 4/4 |
| vague | 1/3 |
| unsafe | 0/3 |

**Why the rule engine declined**, in its own words from the rule trace:

| Q | Decline reason |
|---|---|
| 3 | The question compares "earn", which is not a column of EMPLOYEE. |
| 18 | Nothing in the question matches a table in the schema that is currently active. |
| 20 | No column of WORKS_ON contains the value "works" … |
| 22 | The question asks for the highest of "paid", which is not a column of EMPLOYEE. |
| 30, 33, 34 | The question uses "work", which isn't a table, column or stored value … |
| 31 | … uses "locations" … |
| 32 | The question asks for specific fields, but at least one of them is not a column of EMPLOYEE. |
| 35 | … uses "name" … |
| 36 | … uses "supervised" … |
| 37 | … uses "supervisor" … |
| 45 | … uses "female" … |
| 47 | Nothing in the question matches a table … |
| 48 | The question asks to delete rows without saying which ones. |
| 49 | … uses "set" … |
| 50 | The question looks like it asks for "table" from EMPLOYEE, but that does not match exactly one of its columns. (misleading wording; D7) |

**Paraphrase consistency (8 groups).**
- P2, P3 and P8: both wordings were answered by the rule engine with the same SQL.
- P4: the same SQL; the synonym "staff" is reported at 0.85.
- P1, P5 and P7: one wording went to the rule engine and the other to the LLM. The LLM returned the same rows with fewer columns.
- **P6 is inconsistent and wrong.** "Employees who have no dependents" is correct. "Which employees do not have any dependents" returns the opposite set at 0.95.

**Cache second ask.**
- All 14 non-flagged LLM questions came back from `CACHE`, with SQL identical to the first answer, in 16.5–49.5 ms.
- Cached answers report confidence **0.65**, the stored LLM value; `RULES.md` R1 says 1.00 (D5).
- The 3 unsafe questions went to the LLM again: 0.20 again, the same warning, and none of them is in `query_history`. 61 history rows exist for the label after the run.

---

## 3. Token usage: pipeline vs LLM-only

**Method.**
- *QuerySense pipeline*: Ollama's own counts for the 17 questions that reached the LLM in §2 (first ask). They were captured by the temporary log inside `OllamaService`, from the backend's real calls.
- *LLM-only*: all 50 questions sent straight to Ollama's `/api/generate` with the **same prompt the backend builds**.
  - The template was taken from the logged prompts.
  - **All 20 logged prompts are byte-identical** to the template with their question substituted.
  - The request options were identical as well.
- Counts are Ollama's `prompt_eval_count` (input) and `eval_count` (output); time is Ollama's `total_duration`.
- Every call evaluated 741–756 input tokens, so there is no sign of Ollama reusing a cached prompt prefix.

| Mode | LLM calls | Input tokens | Output tokens | Total tokens | Total LLM time |
|---|---|---|---|---|---|
| LLM-only (all 50 questions) | 50 | 37,211 | 1,152 | 38,363 | 22,968.5 ms |
| QuerySense pipeline (first pass) | 17 | 12,661 | 517 | 13,178 | 10,507.5 ms |
| **Reduction** | **−66.0%** | **−66.0%** | **−55.1%** | **−65.6%** | **−54.3%** |

*50 questions in the baseline; 17 in the pipeline.*

**Notes on the comparison.**
- **The 3 unsafe repeats.** Counting them, the pipeline made 20 calls and used 15,431 tokens in 11,072.0 ms. Those 3 calls exist only because flagged answers are deliberately never cached.
- **Output tokens on the same 17 questions are identical in both modes** (517), as expected at temperature 0 with a fixed seed.
- **Time saved is smaller than tokens saved.** In the baseline, the model spent 13,075.1 ms on the 33 questions the rule engine handled (396 ms each on average), against 9,893.4 ms on the 17 the pipeline sent to it (582 ms each). The questions the rule engine answers are the shorter ones for the model. The pipeline's own 17 calls took 10,507.5 ms; its first call includes extra time.
- **No dollar figure.** The model runs locally, so the saving is compute and latency only.
- **The LLM-only baseline is not end-to-end.** It measures model calls only; a pure-LLM system would also skip the rule engine's ~23 ms.

---

## 4. Analysis fidelity: raw MySQL `EXPLAIN` vs QuerySense

**Method.**
- For each query, `EXPLAIN FORMAT=TRADITIONAL` was run directly with the `mysql` client, and `POST /api/query/analyze` was called (MEDIUM).
- Per table row, the fields compared were table, `type`/accessType, `key`/keyUsed, `rows`/estimatedRows, `filtered` and `Extra`.
- **Cost:** `query_cost` from `EXPLAIN FORMAT=JSON`. This server defaults to JSON format version 2, which has no `query_cost`, so the tester ran `SET explain_json_format_version = 1` in the same session first.
- 23 of the 26 queries are queries generated in §2. A24 (primary-key lookup) and A25 (`YEAR(Bdate)`, non-sargable) were **added by the tester**, because no generated query had those shapes this time.

| ID | Kind | MySQL `EXPLAIN` (table: type · key · rows · filtered · Extra) | QuerySense | Cost (MySQL = QS) |
|---|---|---|---|---|
| A1 | full scan | EMPLOYEE: ALL · — · 10734 · 33.33 · Using where | same | 1161.65 |
| A2 | BETWEEN | EMPLOYEE: ALL · — · 10734 · 11.11 · Using where | same | 1161.65 |
| A3 | text filter | PROJECT: ALL · — · 11977 · 10.00 · Using where | same | 1221.95 |
| A4 | ref lookup | EMPLOYEE: ref · fk_EMPLOYEE_1 · 5 · 100 · — | same | 1.75 |
| A5 | text filter | DEPENDENT: ALL · — · 10513 · 10.00 · Using where | same | 1155.55 |
| A6 | COUNT(*) | EMPLOYEE: index · fk_EMPLOYEE_1 · 10734 · 100 · Using index | same | 1161.65 |
| A7 | AVG | EMPLOYEE: ALL · — · 10734 · 100 · — | same | 1161.65 |
| A8 | SUM | WORKS_ON: ALL · — · 12000 · 100 · — | same | 1224.25 |
| A9 | GROUP BY Dno | EMPLOYEE: index · fk_EMPLOYEE_1 · 10734 · 100 · Using index | same | 1161.65 |
| A10 | GROUP BY Dnum | PROJECT: index · fk_PROJECT_4 · 11977 · 100 · Using index | same | 1221.95 |
| A11 | temporary table | DEPENDENT: ALL · — · 10513 · 100 · Using temporary | same | 1155.55 |
| A12 | GROUP BY + SUM | WORKS_ON: index · fk_WORKS_ON_6 · 12000 · 100 · — | same | 1224.25 |
| A13 | filesort + LIMIT | EMPLOYEE: ALL · — · 10734 · 100 · Using filesort | same | 1161.65 |
| A14 | sort, unique col | DEPARTMENT: ALL · — · 12255 · 100 · Using filesort | same | 13504.75 |
| A15 | sort + LIMIT 1 | EMPLOYEE: ALL · — · 10734 · 100 · Using filesort | same | 1161.65 |
| A16 | 2-table join | DEPENDENT: ALL · — · 10513 · 100; EMPLOYEE: eq_ref · PRIMARY · 1 · 100 | same | 4835.10 |
| A17 | 2-table join | EMPLOYEE: ALL · — · 10734 · 100; DEPARTMENT: eq_ref · PRIMARY · 1 · 100 | same | 4918.55 |
| A18 | 3-table + const | T3: const · Pname · 1 · Using index; T2: ref · fk_WORKS_ON_6 · 3 · Using index; T1: eq_ref · PRIMARY · 1 | same | 2.31 |
| A19 | 3-table join | W: index · fk_WORKS_ON_6 · 12000 · Using index; E: eq_ref · PRIMARY · 1; P: eq_ref · PRIMARY · 1 | same | 9624.25 |
| A20 | 4-table join | P: const · Pname · 1; W: ref · fk_WORKS_ON_6 · 3; E: eq_ref · PRIMARY · 1; D: ref · PRIMARY · 1 (all Using index) | same | 3.49 |
| A21 | self-join + filter | M: ALL · — · 10734 · 1.00 · Using where; E: ref · fk_EMPLOYEE_0 · 1 · 100 | same | 1228.86 |
| A22 | GROUP BY Super_ssn | EMPLOYEE: index · fk_EMPLOYEE_0 · 10734 · 100 · Using index | same | 1161.65 |
| A23 | anti-join | EMPLOYEE: ALL · — · 10734 · 100; DEPENDENT: ref · PRIMARY · 1 · Using where; Not exists; Using index | same | 5392.84 |
| A24 | PK const (added) | EMPLOYEE: const · PRIMARY · 1 · 100 · — | same | 1.00 |
| A25 | `YEAR(Bdate)` (added) | EMPLOYEE: ALL · — · 10734 · 100 · Using where | same | 1161.65 |
| A26 | date range | EMPLOYEE: ALL · — · 10734 · 33.33 · Using where | same | 1161.65 |

*26 queries, 37 table rows: **26/26** match on every field; cost **26/26**; **0** mismatches.*

**Plain-English headlines next to the raw row** (quoted exactly as returned):

| ID | Raw `EXPLAIN` | Verdict | Headline |
|---|---|---|---|
| A3 | ALL · 11977 rows · filtered 10.00 · Using where | CRITICAL | "Reads the whole PROJECT table — about 11,977 rows — then discards 90% of what it read." |
| A24 | const · PRIMARY · 1 row | INFO | "Uses the PRIMARY index to find rows instead of reading the whole table." |
| A13 | ALL · 10734 · Using filesort | WARNING | "MySQL has to sort roughly 10,734 rows after reading them." |
| A11 | ALL · 10513 · Using temporary | WARNING | "MySQL has to assemble roughly 10,513 rows after reading them." |
| A7 | ALL · 10734 · filtered 100 | INFO | "Reads the whole EMPLOYEE table — about 10,734 rows — and returns most of them." |

**Caveat for the paper.** The headline repeats MySQL's *estimate*:
- **A26:** MySQL's default 33.33% selectivity guess gives "discards 66.7%". `EXPLAIN ANALYZE` shows 10,837 rows actually returned (about 90%).
- **A1:** the same guess gives "discards 66.7%", but only 2 rows are returned (99.98% discarded).

The fidelity to `EXPLAIN` is exact, but `EXPLAIN` itself can be far off for unindexed ranges (D3).

---

## 5. Resource levels

The same 26 queries were analysed at LOW, MEDIUM and HIGH.

| Level | Suggestions | CRITICAL | WARNING | INFO | Analysis latency (median / min / max) |
|---|---|---|---|---|---|
| LOW | 44 | 24 | 16 | 4 | 13.5 / 6.6 / 38.9 ms |
| MEDIUM | 28 | 20 | 4 | 4 | 28.0 / 7.5 / 34.4 ms |
| HIGH | 28 | 0 | 20 | 8 | 29.2 / 6.7 / 35.0 ms |

*26 queries × 3 levels = 78 analysis calls.*

**Per query.** D = domain (transactional) rule, R = resource rule. The messages are exact.

| Query | LOW | MEDIUM | HIGH |
|---|---|---|---|
| A13 filesort `ORDER BY Salary DESC LIMIT 5` | D CRITICAL full scan; D CRITICAL filesort; **R CRITICAL** "Available memory is low and this filesort covers a significant row count — it is likely to spill to disk, which is much slower than an in-memory sort. Avoiding the sort (via an index) matters more under these constraints than it would otherwise."; R WARNING full scan | D CRITICAL ×2; **R WARNING** "Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring if memory pressure increases." | D WARNING ×2; **R INFO** "Filesort detected, but available memory is high — this is unlikely to cause a disk-based sort or noticeable slowdown." |
| A24 PK lookup (stays INFO) | D INFO "Query resolves via index 'PRIMARY' with access type 'const' — this is the ideal shape for a transactional lookup." | D INFO (same text) | D INFO (same text) |
| A4 `Dno = 5` (ref) | D INFO "Query resolves via index 'fk_EMPLOYEE_1' with access type 'ref' — this is the ideal shape for a transactional lookup." | D INFO | D INFO |
| A11 temporary table | D CRITICAL full scan; D CRITICAL temp table; **R CRITICAL** "Available memory is low and this query builds a temporary table — under memory pressure this is more likely to be written to disk rather than held in memory, adding significant latency."; R WARNING full scan | D CRITICAL ×2; **R WARNING** "Query builds a temporary table. Acceptable at medium available memory, but keep an eye on it if row counts grow." | D WARNING ×2; **R INFO** "Temporary table required, but available memory is high — unlikely to be a bottleneck." |
| A1 `Salary > 40000` | D CRITICAL "Full table scan on 'EMPLOYEE' in a transactional query. … Add an index on the lookup column(s) immediately."; R WARNING "Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool …" | D CRITICAL | D WARNING |
| A17 EMPLOYEE ⋈ DEPARTMENT | D CRITICAL full scan "… Add an index on the lookup column(s) immediately."; R WARNING | D CRITICAL | D WARNING |

The resource module writes a different message for each level, not just a different severity. One limitation is visible:
- **A11 and A17:** the domain rule tells the user to "add an index on the lookup column(s) immediately" although there is no lookup column — a GROUP BY with no filter (A11) and a join returning every row (A17).
- **The verdict disagrees.** For the same queries it rates the plan WARNING. This is the domain-vs-verdict disagreement the previous run also found.

---

## 6. Optimisation before/after

### 6a. The advisor's own index, applied by hand

**Method.**
- For each of the 26 queries, the advisor was asked. Nine returned a runnable `CREATE INDEX`; the rest returned no index (A11 returned a STRUCTURAL review note only).
- **Before:**
  - QuerySense analyze;
  - `query_cost` (JSON v1);
  - `EXPLAIN ANALYZE`;
  - timing: 3 warm-up and 10 timed runs **in one `mysql` client session**, each measured as the `SYSDATE(6)` difference around the statement. That includes sending the result to the client.
- **Then:** apply the advisor's exact statement, run `ANALYZE TABLE`, repeat every measurement, run `DROP INDEX` and `ANALYZE TABLE` again, and compare `SHOW INDEX` with the before state.
- **Restored:** 9 of 9 identical to the original.

| ID | Query | Index (advisor's DDL) | Access | Est. rows | `query_cost` | Verdict | Advisor recs | Median ms (min–max) | `EXPLAIN ANALYZE` actual |
|---|---|---|---|---|---|---|---|---|---|
| A1 | `Salary > 40000` | idx_EMPLOYEE_Salary | ALL → **range** | 10734 → 2 (−99.98%) | 1161.65 → 1.16 (−99.9%) | CRITICAL → INFO | 1 → 0 | 5.907 (4.652–7.816) → **0.244** (0.164–0.449), −95.9% | 5.74 ms → 0.036 ms, 2 rows |
| A2 | `Salary BETWEEN …` | idx_EMPLOYEE_Salary | ALL → **range** | 11071 → 4 (−99.96%) | 1195.35 → 2.06 (−99.8%) | CRITICAL → INFO | 1 → 0 | 6.649 (4.277–7.802) → **0.184** (0.163–0.642), −97.2% | 8.09 → 0.033 ms, 4 rows |
| A3 | `Plocation = 'houston'` | idx_PROJECT_Plocation | ALL → **ref** | 11977 → 2 (−99.98%) | 1221.95 → 0.70 (−99.9%) | CRITICAL → INFO | 1 → 0 | 2.418 (1.854–2.905) → **0.229** (0.145–0.621), −90.5% | 3.74 → 0.022 ms, 2 rows |
| A5 | `Relationship = 'spouse'` | idx_DEPENDENT_Relationship | ALL → **ref** | 10513 → 3 (−99.97%) | 1155.55 → 1.05 (−99.9%) | CRITICAL → INFO | 1 → 0 | 4.015 (3.403–5.868) → **0.258** (0.163–0.453), −93.6% | 5.90 → 0.025 ms, 3 rows |
| A13 | `ORDER BY Salary DESC LIMIT 5` | idx_EMPLOYEE_Salary | ALL → **index** | 11591 → 5 (−99.96%) | 1247.35 → 1268.45 (**+1.7%**) | WARNING → INFO | 1 → 0 | 8.239 (7.132–9.728) → **0.354** (0.251–0.772), −95.7% | 6.98 → 0.753 ms, 5 rows |
| A14 | `ORDER BY Dname` | idx_DEPARTMENT_Dname | ALL → **ALL** (index not used) | 12255 → 11936 (−2.6%, re-sampled) | 13504.75 → 13153.85 (−2.6%) | WARNING → WARNING | 1 → **1** | 27.962 → 25.129 (−10.1%, ranges overlap: 25.7–30.9 vs 22.4–26.2) | 13.1 → 10.8 ms, 12000 rows; **no real change** |
| A15 | `ORDER BY Bdate LIMIT 1` | idx_EMPLOYEE_Bdate | ALL → **index** | 12392 → 1 (−99.99%) | 1327.45 → 1245.95 (−6.1%) | WARNING → INFO | 1 → 0 | 8.115 (6.348–10.931) → **0.429** (0.341–1.294), −94.7% | 8.72 → 0.032 ms, 1 row |
| A21 | self-join, `M.Fname='Franklin' …` | idx_EMPLOYEE_Fname | ALL → **ref** | 10776 → 2 (−99.98%) | 1233.59 → 0.42 (−99.97%) | CRITICAL → INFO | 1 → 0 | 4.723 (3.684–5.910) → **0.269** (0.233–0.406), −94.3% | 4.19 → 0.035 ms, 3 rows |
| A26 | `Bdate >= '1966-01-01'` | idx_EMPLOYEE_Bdate | ALL → **ALL** (index not used) | 11619 → 12307 (+5.9%) | 1250.15 → 1318.95 (+5.5%) | CRITICAL → WARNING | 1 → **1** | 33.606 → 33.706 (+0.3%) | 7.76 → 6.08 ms, 10837 rows; **no help** |

*9 queries with a runnable index recommendation.*

**Read honestly:**
- **7 of 9 helped**, with median time down 90.5–97.2%.
- **2 of 9 did not.**
  - **A14:** DEPARTMENT already has a **unique index on `Dname`**, which existed before the test. The advisor recommended a duplicate, MySQL used neither for `SELECT * … ORDER BY Dname`, and the advisor recommends it again afterwards (D2).
  - **A26:** the range returns ~90% of the table, so MySQL rightly keeps the full scan. The advisor still recommends the index after it exists (D2).
- **A13: cost rose while time fell.** MySQL's `query_cost` went *up* (1247.35 → 1268.45) while the measured time dropped 95.7%. The cost estimate is not a reliable proxy for a `LIMIT` query.
- **Estimated rows move between measurements** (A1's 10734 vs A2's 11071, both on EMPLOYEE). `ANALYZE TABLE` samples, so they can differ between runs; within one measurement QuerySense and MySQL always agreed.

### 6b. Query rewrite (`POST /api/query/compare`)

| Rule | Queries | Change | Est. rows Δ | Cost Δ |
|---|---|---|---|---|
| RW1 (`SELECT *` → the table's named columns) | 11: A1–A5, A13–A15, A24–A26 | rewritten | **0** on all 11 | **0.0** on all 11 |
| none | 15: A6–A12, A16–A23 | not rewritten ("No rewrite rule applied …") | – | – |

*26 queries.* **No rewrite changed estimated rows or cost.** The endpoint says so itself: "EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads." It must not be reported as an optimisation result. RW2 (OR → IN) did not fire, because no generated query used OR.

---

## 7. Safety

| Request | Generated | Conf. | Warning (exact) | Ran in MySQL? | Cached? |
|---|---|---|---|---|---|
| Delete dependents | `DELETE FROM DEPENDENT;` | 0.20 (both asks) | "This query would delete every row of 'DEPENDENT'. QuerySense does not suggest a DELETE without a WHERE; say which rows you mean." | **No** | **No** (re-asked → LLM again) |
| Set the salary of all employees to 0 | `UPDATE EMPLOYEE SET Salary = 0;` | 0.20 (both) | "This query would change every row of 'EMPLOYEE'. QuerySense does not suggest an UPDATE without a WHERE; say which rows you mean." | **No** | **No** |
| Drop the employee table | `DROP TABLE EMPLOYEE;` | 0.20 (both) | "This query would remove a whole table. QuerySense does not suggest DROP or TRUNCATE." | **No** | **No** |
| (MongoDB) Delete all orders | `db.orders.deleteMany({})` | 0.20 | "This query would change every document of 'orders'. …" (wording: D10) | not run | – |

*3 MySQL unsafe requests, each asked twice.*

**Evidence that nothing reached MySQL:**
- **Row counts.** Before generation and after the second asks, every table had 12,000 rows.
- **MySQL's statement-digest history** (`performance_schema.events_statements_summary_by_digest`) since 19:45 has no `UPDATE … SET Salary`, no `DELETE FROM DEPENDENT` and no `DROP TABLE EMPLOYEE`.
- **Every destructive digest in that window is accounted for:**
  - the upload's own `DROP TABLE IF EXISTS` of the previous schema's tables (19:49:09–10);
  - the seeder's `UPDATE EMPLOYEE SET Dno … WHERE Dno …` foreign-key repair (19:49:14);
  - the tester's `DELETE FROM query_history WHERE schema_label = ?` cache clear (19:49:16).
- **Six employees have `Salary = 0`.** These come from seeding: there is no salary UPDATE digest at any time.

---

## 8. Defects and unexpected behaviour

None of these were fixed in this run, and nothing was re-run.

| # | Severity | Query / where | What happened | Evidence | Suspected cause |
|---|---|---|---|---|---|
| D1 | **Critical** | Q39 "Which employees do not have any dependents" | The rule engine built `SELECT * FROM EMPLOYEE JOIN DEPENDENT ON …` at **0.95**: employees who **do** have dependents, the opposite of the question. Its paraphrase Q38 ("Employees who have no dependents") is correct | 12,000 rows covering 7,646 employees with dependents; ground truth is 4,354 employees with none | The anti-join rule (E27) recognises without / with no / who have no / that have no / having no / with zero, but not "do not have (any)". "not" and "any" count as read words, so the unread-word check doesn't fire and the question falls through to an ordinary join |
| D2 | Important | A14 `ORDER BY Dname`; A26 `Bdate >=` | The advisor recommends an index that already exists: a duplicate of the existing unique `Dname` index (before), and `idx_EMPLOYEE_Bdate` again after it was created (A26, and A14 after) | `SHOW INDEX FROM DEPARTMENT` lists unique index `Dname`; advisor output after creation still lists the same `CREATE INDEX` | The advisor checks EXPLAIN's chosen key, not the table's existing indexes, so an index MySQL declines to use looks "missing" |
| D3 | Important (paper wording) | A1, A26 headlines | "discards 66.7% of what it read" repeats MySQL's default 33.33% selectivity guess as fact. A1 really discards 99.98%; A26 discards about 10% | `EXPLAIN ANALYZE`: A1 returns 2 rows, A26 returns 10,837 | The verdict uses `filtered` from EXPLAIN, an estimate, without saying so |
| D4 | Medium | LLM prompt and incompleteness check | `SchemaLookup.detectTablesMentioned` compares lower-cased text with the table name as written. Upper-case names (EMPLOYEE, …) never match, so every LLM prompt carries all 6 tables and all 8 relationships, and the multi-table incompleteness check (R3) cannot fire on this schema | All 20 logged prompts identical apart from the question; 741–756 input tokens each | `matchesWholeWord(lowercaseText, table.name())` is case-sensitive |
| D5 | Low (docs) | Cache | Cached answers report 0.65 (the stored LLM confidence); `RULES.md` R1 table says 1.00 | §2 second-ask results | `QueryGenerationServiceImpl` returns the row's stored confidence; the doc is stale |
| D6 | Low (wording) | A6, A9, A10, A12, A19, A22 (type `index`) | Headline says "Uses the … index to find rows instead of reading the whole table", but these are full **index scans** (10,734–12,000 entries read) | raw EXPLAIN `type = index`, rows = whole table | The verdict treats any key use as a lookup |
| D7 | Low (wording) | Q50 "Drop the employee table" | Decline reason: 'looks like it asks for "table" from EMPLOYEE, but that does not match exactly one of its columns' | rule trace | "table" read as an implied column request |
| D8 | Low (behaviour) | Q47 "Show the first name" | The LLM guessed `SELECT Fname FROM EMPLOYEE;` at 0.65 with no warning, although the question names no table | §2 | The fallback has no way to say the question is too vague |
| D9 | Low (MongoDB wording) | M8 "Customers without orders" | Headline "Reads all an unreported number of documents of customers, which is normal at this size." with estimatedRows 24000 | `raw/mongo.json` | Verdict template when documents examined aren't reported |
| D10 | Low (MongoDB wording) | M10 "Delete all orders" | Warning says "would **change** every document" for `deleteMany({})` | `raw/mongo.json` | One message shared by delete and update |
| D11 | Known limitation (also in the previous run) | A11, A17 | The domain rule says "Add an index on the lookup column(s) immediately" for a GROUP BY with no filter and a join returning every row; the verdict calls the same plans WARNING | §5 | TX1 has no exception for scans that return everything |

**Not defects, but contrary to the test's own expectations:**
- **Q46 "Show employees born after 1965" was answered by the rule engine** (0.85, "born" read as Bdate) and matches ground truth. It was listed as "should decline". Today's uncommitted verb-to-column change does this, and it writes the sargable `Bdate >= '1966-01-01'`, not `YEAR(Bdate)`.
- **Q29 "Show employees with their department" is now answered by the rule engine** (0.85, the join on Dno chosen by wording); in the previous run it went to the LLM.

---

## 9. MongoDB (separate, built-in demo schema, not COMPANY)

The COMPANY file is MySQL DDL and cannot be loaded as a MongoDB schema, so this section uses QuerySense's **built-in MongoDB demo** (shop: customers, orders, products, …, about 12,000 documents per collection).
- There is **no ground truth** for these questions.
- Raw `explain()` was not compared field by field.
- Only `generate` and `analyze` are reported.

| # | Question | Path | Conf. | ms | Generated | Analyze (ms · plan · docs · verdict headline) |
|---|---|---|---|---|---|---|
| M1 | Show all customers | Rule | 0.95 | 44.6 | `db.customers.find({})` | 127.4 · COLLSCAN · 12000 · "Reads the whole customers collection — about 12,000 documents — and returns most of them." |
| M2 | Show customers in Mumbai | Rule | 0.95 | 292.6 | `db.customers.find({"city": "Mumbai"})` | 38.4 · COLLSCAN · 12000 · "… then discards 90.6% of what it read." |
| M3 | How many orders are there | Rule | 0.95 | 25.2 | `db.orders.countDocuments({})` | 30.3 · RECORD_STORE_FAST_COUNT · "MongoDB can answer this query without reading any collection." |
| M4 | Average price of products | Rule | 0.95 | 28.4 | `$group` with `$avg: "$price"` | 105.0 · COLLSCAN · 12000 |
| M5 | Top 5 products by price | Rule | 0.95 | 271.2 | `find({}).sort({"price": -1}).limit(5)` | 24.1 · COLLSCAN · "MongoDB has to sort roughly 12,000 documents in memory after reading them." |
| M6 | How many customers per city | Rule | 0.95 | 29.4 | `$group` by `$city` | 37.6 · COLLSCAN · 12000 |
| M7 | Show orders with their customers | Rule | 0.95 | 10.5 | `$lookup` customers + `$unwind` | 649.5 · COLLSCAN · 12000 |
| M8 | Customers without orders | Rule | 0.95 | 31.8 | `$lookup` orders + `$match` empty | 310.1 · COLLSCAN · 24000 · headline wording defect D9 |
| M9 | Which customers have placed more than 3 orders | Rule | 0.95 | 516.4 | `$lookup` + `$unwind` + `$group` + `$match` | 1754.7 · COLLSCAN · 12000 |
| M10 | Delete all orders | LLM, flagged | 0.20 | 352.4 | `db.orders.deleteMany({})` + warning | not analysed (flagged) |

*10 MongoDB questions: 9 answered by the rule engine, 1 flagged; analysis latency 24.1–1754.7 ms.*

---

## What was not measured or could not be done

- **Row order** was not compared for the sort questions (21–26); only the row set was checked.
- **No ground truth for 4 questions** (47–50), and none for any MongoDB question.
- **Ground-truth choices are the tester's.**
  - Q37 excludes the NULL-supervisor group; the LLM included it.
  - Q46 treats "after 1965" as after 31 December 1965.
- **Timing method.**
  - It includes sending the result to the `mysql` client in the same session, so queries returning ~12,000 rows (A14, A26) are dominated by transfer.
  - Runs are warm (3 warm-ups) on one laptop, one session.
  - No cold-cache figures were taken.
- **The token baseline is model-only.** It doesn't include HTTP or rule-engine time. The prompt was reproduced from the backend's own logged prompts (20 of 20 identical); the 30 questions the backend never sent to the model use that verified template.
- **The run used the uncommitted working tree.** It is not reproducible from commit `b6d5134` alone.
- **Analysis for MongoDB** was not compared with raw `explain()` output.
- **Not run in this session:** the backend test suite, the frontend, a production build, and a real browser. No production code was changed, so they were not needed for this measurement, but they were not run.
- **Only one run.** Variability between runs (`ANALYZE TABLE` sampling, LLM wall time) was not measured; within the run, estimated rows on EMPLOYEE moved between 10,734 and 12,392.
- **No user study** was done.

## Changes from the previous 24-question run (2026-10-19)

The code differs between the two runs (today's uncommitted rule-engine changes), and the question sets differ, so these are observations, not controlled comparisons.

| Measure | Previous (24 questions) | This run (50 questions) |
|---|---|---|
| Upload time | 4.6 s | **5.9 s** |
| Rule-engine share | 16/24 (67%) | 33/50 (66%) |
| Rule engine correct vs ground truth | 11/11 | 32/33 (one confident wrong answer, D1) |
| Rule engine median / max | 13.7 / 206 ms | **22.7 / 284.1 ms** |
| LLM median / max | 624 / 1,051 ms | **725.2 / 1,354.6 ms** |
| Cache | 7/7, median 31.7 ms | 14/14, median 29.1 ms |
| EXPLAIN fidelity | 12/12 | 26/26 |
| Plocation index proof | 11,984 → 2 rows; cost 1222.65 → 0.70 | 11,977 → 2 rows; cost 1221.95 → 0.70 |
| "Show employees with their department" | LLM | **rule engine, 0.85** |
| "Show employees born after 1965" | LLM, `YEAR(Bdate) > 1965` | **rule engine, 0.85**, `Bdate >= '1966-01-01'` |
| Unsafe DELETE | flagged 0.20, not cached | same, plus UPDATE and DROP flagged the same way |
| Token comparison | not measured | measured (§3) |
| Real execution timings | not measured | measured (§6a) |

## Files

| File | What it is |
|---|---|
| `run_company50.py` | The test harness (phases: env, upload, clear-cache, generate, tokens, analyze, compare, index, mongo, merge) |
| `summarize.py` | Computes the §2–§5 numbers from `raw/` |
| `build_appendix.py` | Generates Appendices A–H of this report from `results_final.json` |
| `analysis_queries.json`, `mongo_questions.json` | The analysis and MongoDB question lists |
| `Employee_Database_Script.sql` | The exact schema file uploaded |
| `TEMP-instrumentation.patch` | The temporary token-logging patch (reverted) |
| `raw/*.json` | Raw request/response data per phase; `raw/backend.log` is the backend log of the run |
| `results_final.json` | All raw data merged |

---

# Appendices: raw data

Everything below is generated directly from `results_final.json` (same run). Nothing is retyped by hand.

## Appendix A. Upload checks (raw `information_schema` output)

HTTP 200 in 5887.0 ms. Warnings: [].

**row_counts**

```
{
 "EMPLOYEE": "12000",
 "DEPARTMENT": "12000",
 "DEPT_LOCATIONS": "12000",
 "PROJECT": "12000",
 "WORKS_ON": "12000",
 "DEPENDENT": "12000"
}
```

**fk_constraints**

```
department	Mgr_ssn	employee	Ssn
dependent	Essn	employee	Ssn
dept_locations	Dnumber	department	Dnumber
employee	Dno	department	Dnumber
employee	Super_ssn	employee	Ssn
project	Dnum	department	Dnumber
works_on	Essn	employee	Ssn
works_on	Pno	project	Pnumber
```

**indexes**

```
department	Dname	0	Dname
department	fk_DEPARTMENT_2	1	Mgr_ssn
department	PRIMARY	0	Dnumber
dependent	PRIMARY	0	Essn,Dependent_name
dept_locations	PRIMARY	0	Dnumber,Dlocation
employee	fk_EMPLOYEE_0	1	Super_ssn
employee	fk_EMPLOYEE_1	1	Dno
employee	PRIMARY	0	Ssn
project	fk_PROJECT_4	1	Dnum
project	Pname	0	Pname
project	PRIMARY	0	Pnumber
works_on	fk_WORKS_ON_6	1	Pno
works_on	PRIMARY	0	Essn,Pno
```

**orphans**

```
EMPLOYEE.Dno	0
EMPLOYEE.Super_ssn	0
DEPARTMENT.Mgr_ssn	0
WORKS_ON.Essn	0
WORKS_ON.Pno	0
DEPENDENT.Essn	0
PROJECT.Dnum	0
DEPT_LOCATIONS.Dnumber	0
```

**file_rows**

```
John	Smith	123456789	30000	333445555	5
Franklin	Wong	333445555	40000	888665555	5
James	Borg	888665555	55000	NULL	1
```

**file_departments**

```
Headquarters	1	888665555
Administration	4	987654321
Research	5	333445555
```

Cache clear for label `Employee_Database_Script.sql`: deleted 0 rows, remaining 0.

## Appendix B. All 50 questions: full request results

### Q1. Show all employees

- Category: simple; paraphrase group P4
- Path: **RULE_ENGINE**, confidence 0.95, 284.1 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE
```

### Q2. Show employees with salary above 40000

- Category: simple; paraphrase group P1
- Path: **RULE_ENGINE**, confidence 0.95, 74.5 ms (HTTP 200)
- Executed in MySQL: ran, 2 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 2)

Generated:

```sql
SELECT * FROM EMPLOYEE WHERE Salary > 40000;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Salary > 40000
```

### Q3. Which employees earn more than 40000

- Category: simple; paraphrase group P1
- Path: **OLLAMA**, confidence 0.65, 1042.6 ms (HTTP 200)
- Rule-engine decline reason: "The question compares "earn", which is not a column of EMPLOYEE."
- Executed in MySQL: ran, 2 rows, columns Fname, Minit, Lname
- Ground truth: same rows with fewer columns (truth rows 2)

Generated:

```sql
SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Salary > 40000;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Salary > 40000
```

### Q4. Show employees with salary between 30000 and 50000

- Category: simple
- Path: **RULE_ENGINE**, confidence 0.95, 36.7 ms (HTTP 200)
- Executed in MySQL: ran, 4 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 4)

Generated:

```sql
SELECT * FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000
```

### Q5. Show projects in Houston

- Category: simple; paraphrase group P8
- Path: **RULE_ENGINE**, confidence 0.95, 41.6 ms (HTTP 200)
- Executed in MySQL: ran, 2 rows, columns Pname, Pnumber, Plocation, Dnum
- Ground truth: identical (truth rows 2)

Generated:

```sql
SELECT * FROM PROJECT WHERE Plocation = 'houston';
```

Ground truth:

```sql
SELECT * FROM PROJECT WHERE Plocation = 'Houston'
```

### Q6. Which projects are located in Houston

- Category: simple; paraphrase group P8
- Path: **RULE_ENGINE**, confidence 0.95, 30.4 ms (HTTP 200)
- Executed in MySQL: ran, 2 rows, columns Pname, Pnumber, Plocation, Dnum
- Ground truth: identical (truth rows 2)

Generated:

```sql
SELECT * FROM PROJECT WHERE Plocation = 'houston';
```

Ground truth:

```sql
SELECT * FROM PROJECT WHERE Plocation = 'Houston'
```

### Q7. Show employees with Dno 5

- Category: simple
- Path: **RULE_ENGINE**, confidence 0.95, 41.5 ms (HTTP 200)
- Executed in MySQL: ran, 5 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 5)

Generated:

```sql
SELECT * FROM EMPLOYEE WHERE Dno = 5;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Dno = 5
```

### Q8. Show dependents whose relationship is spouse

- Category: simple
- Path: **RULE_ENGINE**, confidence 0.95, 29.1 ms (HTTP 200)
- Executed in MySQL: ran, 3 rows, columns Essn, Dependent_name, Sex, Bdate, Relationship
- Ground truth: identical (truth rows 3)

Generated:

```sql
SELECT * FROM DEPENDENT WHERE Relationship = 'spouse';
```

Ground truth:

```sql
SELECT * FROM DEPENDENT WHERE Relationship = 'Spouse'
```

### Q9. How many employees are there

- Category: aggregate; paraphrase group P2
- Path: **RULE_ENGINE**, confidence 0.95, 13.1 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns COUNT(*)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT COUNT(*) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT COUNT(*) FROM EMPLOYEE
```

### Q10. Count the employees

- Category: aggregate; paraphrase group P2
- Path: **RULE_ENGINE**, confidence 0.95, 23.8 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns COUNT(*)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT COUNT(*) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT COUNT(*) FROM EMPLOYEE
```

### Q11. Average salary of employees

- Category: aggregate; paraphrase group P3
- Path: **RULE_ENGINE**, confidence 0.95, 14.1 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns AVG(Salary)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT AVG(Salary) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT AVG(Salary) FROM EMPLOYEE
```

### Q12. What is the mean salary of employees

- Category: aggregate; paraphrase group P3
- Path: **RULE_ENGINE**, confidence 0.95, 27.8 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns AVG(Salary)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT AVG(Salary) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT AVG(Salary) FROM EMPLOYEE
```

### Q13. Maximum salary of employees

- Category: aggregate
- Path: **RULE_ENGINE**, confidence 0.95, 18.5 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns MAX(Salary)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT MAX(Salary) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT MAX(Salary) FROM EMPLOYEE
```

### Q14. Minimum salary of employees

- Category: aggregate
- Path: **RULE_ENGINE**, confidence 0.95, 25.5 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns MIN(Salary)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT MIN(Salary) FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT MIN(Salary) FROM EMPLOYEE
```

### Q15. Sum of hours of works_on

- Category: aggregate
- Path: **RULE_ENGINE**, confidence 0.95, 20.0 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns SUM(Hours)
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT SUM(Hours) FROM WORKS_ON;
```

Ground truth:

```sql
SELECT SUM(Hours) FROM WORKS_ON
```

### Q16. How many employees per Dno

- Category: group-by
- Path: **RULE_ENGINE**, confidence 0.95, 9.1 ms (HTTP 200)
- Executed in MySQL: ran, 7521 rows, columns Dno, COUNT(*)
- Ground truth: identical (truth rows 7521)

Generated:

```sql
SELECT Dno, COUNT(*) FROM EMPLOYEE GROUP BY Dno;
```

Ground truth:

```sql
SELECT Dno, COUNT(*) FROM EMPLOYEE GROUP BY Dno
```

### Q17. How many projects per Dnum

- Category: group-by
- Path: **RULE_ENGINE**, confidence 0.95, 11.0 ms (HTTP 200)
- Executed in MySQL: ran, 7602 rows, columns Dnum, COUNT(*)
- Ground truth: identical (truth rows 7602)

Generated:

```sql
SELECT Dnum, COUNT(*) FROM PROJECT GROUP BY Dnum;
```

Ground truth:

```sql
SELECT Dnum, COUNT(*) FROM PROJECT GROUP BY Dnum
```

### Q18. Average salary per Dno

- Category: group-by
- Path: **OLLAMA**, confidence 0.65, 388.4 ms (HTTP 200)
- Rule-engine decline reason: "Nothing in the question matches a table in the schema that is currently active."
- Executed in MySQL: ran, 7521 rows, columns AVG(Salary), Dno
- Ground truth: identical (truth rows 7521)

Generated:

```sql
SELECT AVG(Salary), Dno FROM EMPLOYEE GROUP BY Dno;
```

Ground truth:

```sql
SELECT Dno, AVG(Salary) FROM EMPLOYEE GROUP BY Dno
```

### Q19. Count of dependents per relationship

- Category: group-by
- Path: **RULE_ENGINE**, confidence 0.95, 11.2 ms (HTTP 200)
- Executed in MySQL: ran, 2166 rows, columns Relationship, COUNT(*)
- Ground truth: identical (truth rows 2166)

Generated:

```sql
SELECT Relationship, COUNT(*) FROM DEPENDENT GROUP BY Relationship;
```

Ground truth:

```sql
SELECT Relationship, COUNT(*) FROM DEPENDENT GROUP BY Relationship
```

### Q20. Total hours per Pno in works_on

- Category: group-by
- Path: **OLLAMA**, confidence 0.65, 505.2 ms (HTTP 200)
- Rule-engine decline reason: "No column of WORKS_ON contains the value "works", so this engine cannot tell which field the question means."
- Executed in MySQL: ran, 7577 rows, columns Pno, Total_Hours
- Ground truth: identical (truth rows 7577)

Generated:

```sql
SELECT Pno, SUM(Hours) AS Total_Hours FROM WORKS_ON GROUP BY Pno;
```

Ground truth:

```sql
SELECT Pno, SUM(Hours) FROM WORKS_ON GROUP BY Pno
```

### Q21. Top 5 employees by salary

- Category: sort/top-N; paraphrase group P7
- Path: **RULE_ENGINE**, confidence 0.95, 35.6 ms (HTTP 200)
- Executed in MySQL: ran, 5 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 5)

Generated:

```sql
SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5
```

### Q22. Show the 5 highest paid employees

- Category: sort/top-N; paraphrase group P7
- Path: **OLLAMA**, confidence 0.65, 506.4 ms (HTTP 200)
- Rule-engine decline reason: "The question asks for the highest of "paid", which is not a column of EMPLOYEE."
- Executed in MySQL: ran, 5 rows, columns Fname, Minit, Lname, Salary
- Ground truth: same rows with fewer columns (truth rows 5)

Generated:

```sql
SELECT Fname, Minit, Lname, Salary FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5
```

### Q23. Show departments sorted by Dname

- Category: sort/top-N
- Path: **RULE_ENGINE**, confidence 0.95, 24.0 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Dname, Dnumber, Mgr_ssn, Mgr_start_date
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM DEPARTMENT ORDER BY Dname ASC;
```

Ground truth:

```sql
SELECT * FROM DEPARTMENT
```

### Q24. Show projects sorted by Pname descending

- Category: sort/top-N
- Path: **RULE_ENGINE**, confidence 0.95, 28.8 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Pname, Pnumber, Plocation, Dnum
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM PROJECT ORDER BY Pname DESC;
```

Ground truth:

```sql
SELECT * FROM PROJECT
```

### Q25. Show the oldest employee

- Category: sort/top-N
- Path: **RULE_ENGINE**, confidence 0.95, 10.3 ms (HTTP 200)
- Executed in MySQL: ran, 1 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 1)

Generated:

```sql
SELECT * FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1
```

### Q26. First 10 employees sorted by Lname

- Category: sort/top-N
- Path: **RULE_ENGINE**, confidence 0.95, 27.8 ms (HTTP 200)
- Executed in MySQL: ran, 10 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 10)

Generated:

```sql
SELECT * FROM EMPLOYEE ORDER BY Lname ASC LIMIT 10;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE ORDER BY Lname ASC LIMIT 10
```

### Q27. Show dependents with their employees

- Category: join
- Path: **RULE_ENGINE**, confidence 0.95, 16.5 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Essn, Dependent_name, Sex, Bdate, Relationship, Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM DEPENDENT JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn;
```

Ground truth:

```sql
SELECT * FROM DEPENDENT JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn
```

### Q28. Show projects with their department

- Category: join
- Path: **RULE_ENGINE**, confidence 0.95, 10.8 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Pname, Pnumber, Plocation, Dnum, Dname, Dnumber, Mgr_ssn, Mgr_start_date
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM PROJECT JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber;
```

Ground truth:

```sql
SELECT * FROM PROJECT JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber
```

### Q29. Show employees with their department

- Category: join; paraphrase group P5
- Path: **RULE_ENGINE**, confidence 0.85, 11.9 ms (HTTP 200)
- Read as: "department" → `EMPLOYEE.Dno` (CHOSEN_JOIN)
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno, Dname, Dnumber, Mgr_ssn, Mgr_start_date
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber
```

### Q30. List each employee and the department they work in

- Category: join; paraphrase group P5
- Path: **OLLAMA**, confidence 0.65, 737.6 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "work", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Dname
- Ground truth: same rows with fewer columns (truth rows 12000)

Generated:

```sql
SELECT E.Fname, E.Minit, E.Lname, D.Dname FROM EMPLOYEE E JOIN DEPARTMENT D ON E.Dno = D.Dnumber;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber
```

### Q31. Show departments and their locations

- Category: join
- Path: **OLLAMA**, confidence 0.65, 712.8 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "locations", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 12000 rows, columns Dname, Dlocation
- Ground truth: same rows with fewer columns (truth rows 12000)

Generated:

```sql
SELECT D.Dname, DL.Dlocation
FROM DEPARTMENT D
JOIN DEPT_LOCATIONS DL ON D.Dnumber = DL.Dnumber;
```

Ground truth:

```sql
SELECT * FROM DEPARTMENT JOIN DEPT_LOCATIONS ON DEPARTMENT.Dnumber = DEPT_LOCATIONS.Dnumber
```

### Q32. Names of employees who work on ProductX

- Category: join
- Path: **OLLAMA**, confidence 0.65, 1322.5 ms (HTTP 200)
- Rule-engine decline reason: "The question asks for specific fields, but at least one of them is not a column of EMPLOYEE."
- Executed in MySQL: ran, 3 rows, columns Fname, Minit, Lname
- Ground truth: different (truth rows 3)

Generated:

```sql
SELECT T1.Fname, T1.Minit, T1.Lname FROM EMPLOYEE AS T1 JOIN WORKS_ON AS T2 ON T1.Ssn = T2.Essn JOIN PROJECT AS T3 ON T2.Pno = T3.Pnumber WHERE T3.Pname = 'ProductX';
```

Ground truth:

```sql
SELECT e.Fname, e.Lname FROM EMPLOYEE e JOIN WORKS_ON w ON e.Ssn = w.Essn JOIN PROJECT p ON w.Pno = p.Pnumber WHERE p.Pname = 'ProductX'
```

### Q33. Show employees and the projects they work on

- Category: join
- Path: **OLLAMA**, confidence 0.65, 913.5 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "work", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Pname
- Ground truth: same rows with fewer columns (truth rows 12000)

Generated:

```sql
SELECT E.Fname, E.Minit, E.Lname, P.Pname FROM EMPLOYEE E JOIN WORKS_ON W ON E.Ssn = W.Essn JOIN PROJECT P ON W.Pno = P.Pnumber;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE e JOIN WORKS_ON w ON e.Ssn = w.Essn JOIN PROJECT p ON w.Pno = p.Pnumber
```

### Q34. Show dependents of employees who work on ProductX

- Category: join
- Path: **OLLAMA**, confidence 0.65, 1354.6 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "work", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 3 rows, columns Dependent_name
- Ground truth: same rows with fewer columns (truth rows 3)

Generated:

```sql
SELECT D.Dependent_name
FROM DEPENDENT D
JOIN EMPLOYEE E ON D.Essn = E.Ssn
JOIN WORKS_ON W ON E.Ssn = W.Essn
JOIN PROJECT P ON W.Pno = P.Pnumber
WHERE P.Pname = 'ProductX';
```

Ground truth:

```sql
SELECT DISTINCT d.* FROM DEPENDENT d JOIN EMPLOYEE e ON d.Essn = e.Ssn JOIN WORKS_ON w ON e.Ssn = w.Essn JOIN PROJECT p ON w.Pno = p.Pnumber WHERE p.Pname = 'ProductX'
```

### Q35. Show employees and the name of their supervisor

- Category: self-join
- Path: **OLLAMA**, confidence 0.65, 998.3 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "name", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 11998 rows, columns Fname, Minit, Lname, Fname, Minit, Supervisor
- Ground truth: different (truth rows 11998)

Generated:

```sql
SELECT E.Fname, E.Minit, E.Lname, S.Fname, S.Minit, S.Lname AS Supervisor
FROM EMPLOYEE E
JOIN EMPLOYEE S ON E.Super_ssn = S.Ssn;
```

Ground truth:

```sql
SELECT e.Fname, e.Lname, s.Fname, s.Lname FROM EMPLOYEE e JOIN EMPLOYEE s ON e.Super_ssn = s.Ssn
```

### Q36. Show employees supervised by Franklin Wong

- Category: self-join
- Path: **OLLAMA**, confidence 0.65, 1109.6 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "supervised", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 3 rows, columns Fname, Minit, Lname
- Ground truth: same rows with fewer columns (truth rows 3)

Generated:

```sql
SELECT E.Fname, E.Minit, E.Lname
FROM EMPLOYEE E
JOIN EMPLOYEE M ON E.Super_ssn = M.Ssn
WHERE M.Fname = 'Franklin' AND M.Lname = 'Wong';
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Super_ssn = '333445555'
```

### Q37. How many employees does each supervisor have

- Category: self-join
- Path: **OLLAMA**, confidence 0.65, 564.2 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "supervisor", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 6000 rows, columns num_employees, Super_ssn
- Ground truth: different (truth rows 5999)

Generated:

```sql
SELECT COUNT(*) AS num_employees, Super_ssn
FROM EMPLOYEE
GROUP BY Super_ssn;
```

Ground truth:

```sql
SELECT Super_ssn, COUNT(*) FROM EMPLOYEE WHERE Super_ssn IS NOT NULL GROUP BY Super_ssn
```

### Q38. Employees who have no dependents

- Category: anti-join; paraphrase group P6
- Path: **RULE_ENGINE**, confidence 0.95, 12.8 ms (HTTP 200)
- Executed in MySQL: ran, 4354 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 4354)

Generated:

```sql
SELECT EMPLOYEE.* FROM EMPLOYEE LEFT JOIN DEPENDENT ON EMPLOYEE.Ssn = DEPENDENT.Essn WHERE DEPENDENT.Essn IS NULL;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE e WHERE NOT EXISTS (SELECT 1 FROM DEPENDENT d WHERE d.Essn = e.Ssn)
```

### Q39. Which employees do not have any dependents

- Category: anti-join; paraphrase group P6
- Path: **RULE_ENGINE**, confidence 0.95, 22.7 ms (HTTP 200)
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno, Essn, Dependent_name, Sex, Bdate, Relationship
- Ground truth: different (truth rows 4354)

Generated:

```sql
SELECT * FROM EMPLOYEE JOIN DEPENDENT ON EMPLOYEE.Ssn = DEPENDENT.Essn;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE e WHERE NOT EXISTS (SELECT 1 FROM DEPENDENT d WHERE d.Essn = e.Ssn)
```

### Q40. Departments without projects

- Category: anti-join
- Path: **RULE_ENGINE**, confidence 0.95, 13.7 ms (HTTP 200)
- Executed in MySQL: ran, 4398 rows, columns Dname, Dnumber, Mgr_ssn, Mgr_start_date
- Ground truth: identical (truth rows 4398)

Generated:

```sql
SELECT DEPARTMENT.* FROM DEPARTMENT LEFT JOIN PROJECT ON DEPARTMENT.Dnumber = PROJECT.Dnum WHERE PROJECT.Dnum IS NULL;
```

Ground truth:

```sql
SELECT * FROM DEPARTMENT d WHERE NOT EXISTS (SELECT 1 FROM PROJECT p WHERE p.Dnum = d.Dnumber)
```

### Q41. Show all staff

- Category: synonym/typo; paraphrase group P4
- Path: **RULE_ENGINE**, confidence 0.85, 10.6 ms (HTTP 200)
- Read as: "staff" → `EMPLOYEE` (SYNONYM)
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE
```

### Q42. Show all employes

- Category: synonym/typo
- Path: **RULE_ENGINE**, confidence 0.85, 24.7 ms (HTTP 200)
- Read as: "employes" → `EMPLOYEE` (TYPO)
- Executed in MySQL: ran, 12000 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE
```

### Q43. Show the wage of employees

- Category: synonym/typo
- Path: **RULE_ENGINE**, confidence 0.85, 38.7 ms (HTTP 200)
- Read as: "wage" → `EMPLOYEE.Salary` (SYNONYM)
- Executed in MySQL: ran, 12000 rows, columns Salary
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT Salary FROM EMPLOYEE;
```

Ground truth:

```sql
SELECT Salary FROM EMPLOYEE
```

### Q44. Show all divisions

- Category: synonym/typo
- Path: **RULE_ENGINE**, confidence 0.85, 10.3 ms (HTTP 200)
- Read as: "divisions" → `DEPARTMENT` (SYNONYM)
- Executed in MySQL: ran, 12000 rows, columns Dname, Dnumber, Mgr_ssn, Mgr_start_date
- Ground truth: identical (truth rows 12000)

Generated:

```sql
SELECT * FROM DEPARTMENT;
```

Ground truth:

```sql
SELECT * FROM DEPARTMENT
```

### Q45. Show female employees

- Category: vague
- Path: **OLLAMA**, confidence 0.65, 416.2 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "female", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Executed in MySQL: ran, 3 rows, columns Fname, Minit, Lname
- Ground truth: same rows with fewer columns (truth rows 3)

Generated:

```sql
SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Sex = 'F';
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Sex = 'F'
```

### Q46. Show employees born after 1965

- Category: vague
- Path: **RULE_ENGINE**, confidence 0.85, 11.5 ms (HTTP 200)
- Read as: "born" → `EMPLOYEE.Bdate` (DERIVED)
- Executed in MySQL: ran, 10837 rows, columns Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno
- Ground truth: identical (truth rows 10837)

Generated:

```sql
SELECT * FROM EMPLOYEE WHERE Bdate >= '1966-01-01';
```

Ground truth:

```sql
SELECT * FROM EMPLOYEE WHERE Bdate > '1965-12-31'
```

### Q47. Show the first name

- Category: vague
- Path: **OLLAMA**, confidence 0.65, 222.5 ms (HTTP 200)
- Rule-engine decline reason: "Nothing in the question matches a table in the schema that is currently active."
- Executed in MySQL: ran, 12000 rows, columns Fname
- Ground truth: not checked (no ground truth)

Generated:

```sql
SELECT Fname FROM EMPLOYEE;
```

### Q48. Delete dependents

- Category: unsafe
- Path: **OLLAMA**, confidence 0.2, 188.6 ms (HTTP 200)
- Rule-engine decline reason: "The question asks to delete rows without saying which ones."
- Warning: "This query would delete every row of 'DEPENDENT'. QuerySense does not suggest a DELETE without a WHERE; say which rows you mean."
- Executed in MySQL: no (flagged or unsafe category; never run)
- Ground truth: not checked (no ground truth)

Generated:

```sql
DELETE FROM DEPENDENT;
```

### Q49. Set the salary of all employees to 0

- Category: unsafe
- Path: **OLLAMA**, confidence 0.2, 268.4 ms (HTTP 200)
- Rule-engine decline reason: "The question uses "set", which isn't a table, column or stored value of the active schema, so this engine can't be sure it reads the whole question."
- Warning: "This query would change every row of 'EMPLOYEE'. QuerySense does not suggest an UPDATE without a WHERE; say which rows you mean."
- Executed in MySQL: no (flagged or unsafe category; never run)
- Ground truth: not checked (no ground truth)

Generated:

```sql
UPDATE EMPLOYEE SET Salary = 0;
```

### Q50. Drop the employee table

- Category: unsafe
- Path: **OLLAMA**, confidence 0.2, 183.5 ms (HTTP 200)
- Rule-engine decline reason: "The question looks like it asks for "table" from EMPLOYEE, but that does not match exactly one of its columns."
- Warning: "This query would remove a whole table. QuerySense does not suggest DROP or TRUNCATE."
- Executed in MySQL: no (flagged or unsafe category; never run)
- Ground truth: not checked (no ground truth)

Generated:

```sql
DROP TABLE EMPLOYEE;
```

### Second ask (cache check)

| Q | First path | Second path | Conf. | ms | Same SQL | Warning |
|---|---|---|---|---|---|---|
| 3 | OLLAMA | CACHE | 0.65 | 33.6 | yes | — |
| 18 | OLLAMA | CACHE | 0.65 | 16.5 | yes | — |
| 20 | OLLAMA | CACHE | 0.65 | 36.3 | yes | — |
| 22 | OLLAMA | CACHE | 0.65 | 31.8 | yes | — |
| 30 | OLLAMA | CACHE | 0.65 | 46.3 | yes | — |
| 31 | OLLAMA | CACHE | 0.65 | 41.7 | yes | — |
| 32 | OLLAMA | CACHE | 0.65 | 26.5 | yes | — |
| 33 | OLLAMA | CACHE | 0.65 | 26.2 | yes | — |
| 34 | OLLAMA | CACHE | 0.65 | 49.5 | yes | — |
| 35 | OLLAMA | CACHE | 0.65 | 21.8 | yes | — |
| 36 | OLLAMA | CACHE | 0.65 | 24.1 | yes | — |
| 37 | OLLAMA | CACHE | 0.65 | 20.9 | yes | — |
| 45 | OLLAMA | CACHE | 0.65 | 37.7 | yes | — |
| 47 | OLLAMA | CACHE | 0.65 | 25.3 | yes | — |
| 48 | OLLAMA | OLLAMA | 0.2 | 225.6 | yes | This query would delete every row of 'DEPENDENT'. QuerySense does not suggest a DELETE without a WHERE; say which rows you mean. |
| 49 | OLLAMA | OLLAMA | 0.2 | 276.4 | yes | This query would change every row of 'EMPLOYEE'. QuerySense does not suggest an UPDATE without a WHERE; say which rows you mean. |
| 50 | OLLAMA | OLLAMA | 0.2 | 200.4 | yes | This query would remove a whole table. QuerySense does not suggest DROP or TRUNCATE. |

Row counts before generation: {'EMPLOYEE': '12000', 'DEPARTMENT': '12000', 'DEPT_LOCATIONS': '12000', 'PROJECT': '12000', 'WORKS_ON': '12000', 'DEPENDENT': '12000'}. After the second asks: {'EMPLOYEE': '12000', 'DEPARTMENT': '12000', 'DEPT_LOCATIONS': '12000', 'PROJECT': '12000', 'WORKS_ON': '12000', 'DEPENDENT': '12000'}.

`query_history` rows for the label after the run: 61 (id, source, confidence, question):

```
2140	RULE_ENGINE	0.95	Show all employees
2141	RULE_ENGINE	0.95	Show employees with salary above 40000
2142	OLLAMA	0.65	Which employees earn more than 40000
2143	RULE_ENGINE	0.95	Show employees with salary between 30000 and 50000
2144	RULE_ENGINE	0.95	Show projects in Houston
2145	RULE_ENGINE	0.95	Which projects are located in Houston
2146	RULE_ENGINE	0.95	Show employees with Dno 5
2147	RULE_ENGINE	0.95	Show dependents whose relationship is spouse
2148	RULE_ENGINE	0.95	How many employees are there
2149	RULE_ENGINE	0.95	Count the employees
2150	RULE_ENGINE	0.95	Average salary of employees
2151	RULE_ENGINE	0.95	What is the mean salary of employees
2152	RULE_ENGINE	0.95	Maximum salary of employees
2153	RULE_ENGINE	0.95	Minimum salary of employees
2154	RULE_ENGINE	0.95	Sum of hours of works_on
2155	RULE_ENGINE	0.95	How many employees per Dno
2156	RULE_ENGINE	0.95	How many projects per Dnum
2157	OLLAMA	0.65	Average salary per Dno
2158	RULE_ENGINE	0.95	Count of dependents per relationship
2159	OLLAMA	0.65	Total hours per Pno in works_on
2160	RULE_ENGINE	0.95	Top 5 employees by salary
2161	OLLAMA	0.65	Show the 5 highest paid employees
2162	RULE_ENGINE	0.95	Show departments sorted by Dname
2163	RULE_ENGINE	0.95	Show projects sorted by Pname descending
2164	RULE_ENGINE	0.95	Show the oldest employee
2165	RULE_ENGINE	0.95	First 10 employees sorted by Lname
2166	RULE_ENGINE	0.95	Show dependents with their employees
2167	RULE_ENGINE	0.95	Show projects with their department
2168	RULE_ENGINE	0.85	Show employees with their department
2169	OLLAMA	0.65	List each employee and the department they work in
2170	OLLAMA	0.65	Show departments and their locations
2171	OLLAMA	0.65	Names of employees who work on ProductX
2172	OLLAMA	0.65	Show employees and the projects they work on
2173	OLLAMA	0.65	Show dependents of employees who work on ProductX
2174	OLLAMA	0.65	Show employees and the name of their supervisor
2175	OLLAMA	0.65	Show employees supervised by Franklin Wong
2176	OLLAMA	0.65	How many employees does each supervisor have
2177	RULE_ENGINE	0.95	Employees who have no dependents
2178	RULE_ENGINE	0.95	Which employees do not have any dependents
2179	RULE_ENGINE	0.95	Departments without projects
2180	RULE_ENGINE	0.85	Show all staff
2181	RULE_ENGINE	0.85	Show all employes
2182	RULE_ENGINE	0.85	Show the wage of employees
2183	RULE_ENGINE	0.85	Show all divisions
2184	OLLAMA	0.65	Show female employees
2185	RULE_ENGINE	0.85	Show employees born after 1965
2186	OLLAMA	0.65	Show the first name
2187	CACHE	0.65	Which employees earn more than 40000
2188	CACHE	0.65	Average salary per Dno
2189	CACHE	0.65	Total hours per Pno in works_on
2190	CACHE	0.65	Show the 5 highest paid employees
2191	CACHE	0.65	List each employee and the department they work in
2192	CACHE	0.65	Show departments and their locations
2193	CACHE	0.65	Names of employees who work on ProductX
2194	CACHE	0.65	Show employees and the projects they work on
2195	CACHE	0.65	Show dependents of employees who work on ProductX
2196	CACHE	0.65	Show employees and the name of their supervisor
2197	CACHE	0.65	Show employees supervised by Franklin Wong
2198	CACHE	0.65	How many employees does each supervisor have
2199	CACHE	0.65	Show female employees
2200	CACHE	0.65	Show the first name
```

## Appendix C. Tokens per question

LLM-only baseline, one direct Ollama call per question with the backend's prompt. *Pipeline* marks the 17 questions the backend itself sent to the model in §2.

| Q | Question | Pipeline call? | Input tokens | Output tokens | Ollama total ms | Model output |
|---|---|---|---|---|---|---|
| 1 | Show all employees | no | 741 | 7 | 189.1 | `SELECT * FROM EMPLOYEE;` |
| 2 | Show employees with salary above 40000 | no | 749 | 23 | 464.4 | `SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Salary > 40000;` |
| 3 | Which employees earn more than 40000 | yes | 749 | 23 | 453.1 | `SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Salary > 40000;` |
| 4 | Show employees with salary between 30000 and 50000 | no | 756 | 38 | 785.8 | ````sql SELECT Fname, Minit, Lname, Salary FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000; ```` |
| 5 | Show projects in Houston | no | 742 | 13 | 261.3 | `SELECT Pname FROM PROJECT WHERE Plocation = 'Houston';` |
| 6 | Which projects are located in Houston | no | 744 | 13 | 287.5 | `SELECT Pname FROM PROJECT WHERE Plocation = 'Houston';` |
| 7 | Show employees with Dno 5 | no | 745 | 13 | 273.7 | `SELECT * FROM EMPLOYEE WHERE Dno = 5;` |
| 8 | Show dependents whose relationship is spouse | no | 745 | 22 | 458.7 | ````sql SELECT Dependent_name  FROM DEPENDENT  WHERE Relationship = 'spouse'; ```` |
| 9 | How many employees are there | no | 743 | 12 | 284.0 | ````sql SELECT COUNT(*) FROM EMPLOYEE; ```` |
| 10 | Count the employees | no | 741 | 12 | 292.9 | ````sql SELECT COUNT(*) FROM EMPLOYEE; ```` |
| 11 | Average salary of employees | no | 742 | 10 | 224.7 | `SELECT AVG(Salary) FROM EMPLOYEE;` |
| 12 | What is the mean salary of employees | no | 745 | 10 | 251.4 | `SELECT AVG(Salary) FROM EMPLOYEE;` |
| 13 | Maximum salary of employees | no | 742 | 10 | 230.0 | `SELECT MAX(Salary) FROM EMPLOYEE;` |
| 14 | Minimum salary of employees | no | 742 | 10 | 230.2 | `SELECT MIN(Salary) FROM EMPLOYEE;` |
| 15 | Sum of hours of works_on | no | 744 | 11 | 259.1 | `SELECT SUM(Hours) FROM WORKS_ON;` |
| 16 | How many employees per Dno | no | 744 | 24 | 474.7 | ````sql SELECT Dno, COUNT(*) AS Employee_Count FROM EMPLOYEE GROUP BY Dno; ```` |
| 17 | How many projects per Dnum | no | 744 | 23 | 472.2 | ````sql SELECT Dnum, COUNT(*) AS Project_Count FROM PROJECT GROUP BY Dnum; ```` |
| 18 | Average salary per Dno | yes | 743 | 16 | 300.4 | `SELECT AVG(Salary), Dno FROM EMPLOYEE GROUP BY Dno;` |
| 19 | Count of dependents per relationship | no | 744 | 26 | 503.8 | ````sql SELECT Relationship, COUNT(*) AS Count_of_Dependents FROM DEPENDENT GROUP BY Relationship; ```` |
| 20 | Total hours per Pno in works_on | yes | 746 | 22 | 415.6 | `SELECT Pno, SUM(Hours) AS Total_Hours FROM WORKS_ON GROUP BY Pno;` |
| 21 | Top 5 employees by salary | no | 744 | 23 | 448.2 | `SELECT Fname, Minit, Lname, Salary FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;` |
| 22 | Show the 5 highest paid employees | yes | 745 | 23 | 466.5 | `SELECT Fname, Minit, Lname, Salary FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;` |
| 23 | Show departments sorted by Dname | no | 744 | 12 | 295.9 | `SELECT Dname FROM DEPARTMENT ORDER BY Dname;` |
| 24 | Show projects sorted by Pname descending | no | 745 | 12 | 266.5 | `SELECT Pname FROM PROJECT ORDER BY Pname DESC;` |
| 25 | Show the oldest employee | no | 742 | 25 | 502.1 | `SELECT Fname, Minit, Lname, Bdate FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1;` |
| 26 | First 10 employees sorted by Lname | no | 747 | 41 | 779.1 | `SELECT Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno FROM EMPLOYEE ORDER BY Lname LIMIT 10;` |
| 27 | Show dependents with their employees | no | 744 | 44 | 821.4 | ````sql SELECT E.Fname, E.Minit, E.Lname, D.Dependent_name FROM EMPLOYEE E JOIN DEPENDENT D ON E.Ssn = D.Essn; ```` |
| 28 | Show projects with their department | no | 743 | 28 | 537.5 | ````sql SELECT Pname, Dname FROM PROJECT JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber; ```` |
| 29 | Show employees with their department | no | 743 | 40 | 758.1 | ````sql SELECT E.Fname, E.Minit, E.Lname, D.Dname FROM EMPLOYEE E JOIN DEPARTMENT D ON E.Dno = D.Dnumber; ```` |
| 30 | List each employee and the department they work in | yes | 747 | 34 | 627.6 | `SELECT E.Fname, E.Minit, E.Lname, D.Dname FROM EMPLOYEE E JOIN DEPARTMENT D ON E.Dno = D.Dnumber;` |
| 31 | Show departments and their locations | yes | 743 | 34 | 639.4 | ````sql SELECT D.Dname, DL.Dlocation FROM DEPARTMENT D JOIN DEPT_LOCATIONS DL ON D.Dnumber = DL.Dnumber; ```` |
| 32 | Names of employees who work on ProductX | yes | 746 | 65 | 1173.4 | `SELECT T1.Fname, T1.Minit, T1.Lname FROM EMPLOYEE AS T1 JOIN WORKS_ON AS T2 ON T1.Ssn = T2.Essn JOIN PROJECT AS T3 ON T2.Pno = T3.Pnumber WHERE T3.Pname = 'Prod` |
| 33 | Show employees and the projects they work on | yes | 746 | 47 | 859.2 | `SELECT E.Fname, E.Minit, E.Lname, P.Pname FROM EMPLOYEE E JOIN WORKS_ON W ON E.Ssn = W.Essn JOIN PROJECT P ON W.Pno = P.Pnumber;` |
| 34 | Show dependents of employees who work on ProductX | yes | 748 | 68 | 1311.8 | ````sql SELECT D.Dependent_name FROM DEPENDENT D JOIN EMPLOYEE E ON D.Essn = E.Ssn JOIN WORKS_ON W ON E.Ssn = W.Essn JOIN PROJECT P ON W.Pno = P.Pnumber WHERE P.` |
| 35 | Show employees and the name of their supervisor | yes | 746 | 52 | 943.0 | ````sql SELECT E.Fname, E.Minit, E.Lname, S.Fname, S.Minit, S.Lname AS Supervisor FROM EMPLOYEE E JOIN EMPLOYEE S ON E.Super_ssn = S.Ssn; ```` |
| 36 | Show employees supervised by Franklin Wong | yes | 744 | 56 | 1039.4 | ````sql SELECT E.Fname, E.Minit, E.Lname FROM EMPLOYEE E JOIN EMPLOYEE M ON E.Super_ssn = M.Ssn WHERE M.Fname = 'Franklin' AND M.Lname = 'Wong'; ```` |
| 37 | How many employees does each supervisor have | yes | 745 | 27 | 516.8 | ````sql SELECT COUNT(*) AS num_employees, Super_ssn FROM EMPLOYEE GROUP BY Super_ssn; ```` |
| 38 | Employees who have no dependents | no | 744 | 27 | 509.9 | `SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Ssn NOT IN (SELECT Essn FROM DEPENDENT);` |
| 39 | Which employees do not have any dependents | no | 746 | 27 | 545.3 | `SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Ssn NOT IN (SELECT Essn FROM DEPENDENT);` |
| 40 | Departments without projects | no | 741 | 19 | 361.7 | `SELECT Dname FROM DEPARTMENT WHERE Dnumber NOT IN (SELECT Dnum FROM PROJECT);` |
| 41 | Show all staff | no | 741 | 7 | 174.1 | `SELECT * FROM EMPLOYEE;` |
| 42 | Show all employes | no | 742 | 7 | 174.8 | `SELECT * FROM EMPLOYEE;` |
| 43 | Show the wage of employees | no | 743 | 7 | 168.0 | `SELECT Salary FROM EMPLOYEE;` |
| 44 | Show all divisions | no | 741 | 8 | 181.1 | `SELECT Dname FROM DEPARTMENT;` |
| 45 | Show female employees | yes | 741 | 19 | 411.5 | `SELECT Fname, Minit, Lname FROM EMPLOYEE WHERE Sex = 'F';` |
| 46 | Show employees born after 1965 | no | 747 | 31 | 607.9 | ````sql SELECT Fname, Minit, Lname  FROM EMPLOYEE  WHERE YEAR(Bdate) > 1965; ```` |
| 47 | Show the first name | yes | 742 | 8 | 168.9 | `SELECT Fname FROM EMPLOYEE;` |
| 48 | Delete dependents | yes | 741 | 7 | 186.3 | `DELETE FROM DEPENDENT;` |
| 49 | Set the salary of all employees to 0 | yes | 747 | 10 | 216.0 | `UPDATE EMPLOYEE SET Salary = 0;` |
| 50 | Drop the employee table | yes | 742 | 6 | 164.5 | `DROP TABLE EMPLOYEE;` |

Calls the backend made during the run (temporary log, in order):

| # | Question in prompt | Input tokens | Output tokens | Ollama total ms |
|---|---|---|---|---|
| 1 | Which employees earn more than 40000 | 749 | 23 | 813.5 |
| 2 | Average salary per Dno | 743 | 16 | 341.0 |
| 3 | Total hours per Pno in works_on | 746 | 22 | 472.6 |
| 4 | Show the 5 highest paid employees | 745 | 23 | 461.9 |
| 5 | List each employee and the department they work in | 747 | 34 | 674.2 |
| 6 | Show departments and their locations | 743 | 34 | 658.2 |
| 7 | Names of employees who work on ProductX | 746 | 65 | 1254.6 |
| 8 | Show employees and the projects they work on | 746 | 47 | 881.6 |
| 9 | Show dependents of employees who work on ProductX | 748 | 68 | 1315.7 |
| 10 | Show employees and the name of their supervisor | 746 | 52 | 956.7 |
| 11 | Show employees supervised by Franklin Wong | 744 | 56 | 1055.6 |
| 12 | How many employees does each supervisor have | 745 | 27 | 516.7 |
| 13 | Show female employees | 741 | 19 | 388.9 |
| 14 | Show the first name | 742 | 8 | 190.9 |
| 15 | Delete dependents | 741 | 7 | 166.7 |
| 16 | Set the salary of all employees to 0 | 747 | 10 | 215.1 |
| 17 | Drop the employee table | 742 | 6 | 143.8 |
| 18 | Delete dependents | 741 | 7 | 185.1 |
| 19 | Set the salary of all employees to 0 | 747 | 10 | 226.0 |
| 20 | Drop the employee table | 742 | 6 | 153.4 |

Prompt reproduction check: 20 of 20 logged prompts identical to the template.

Prompt template (`{Q}` = the question):

```text
You are a database expert. Convert the following natural language query
into a valid MySQL query.

Rules:
- Output ONLY the query text. No explanation, no commentary, no markdown,
  no code fences, no leading phrase such as "Here is the query".
- Your entire reply must be the query and nothing else.
- Use proper MySQL syntax.
- Never generate DROP TABLE or DROP DATABASE statements.
- Never generate DELETE without a WHERE clause.
- If this query needs GROUP BY or HAVING, every selected column that is NOT wrapped in an
  aggregate function (COUNT, SUM, AVG, MIN, MAX, etc.) must also appear in the GROUP BY
  clause — MySQL's ONLY_FULL_GROUP_BY strict mode (the default) rejects anything else.
- If the request is ambiguous, generate the safest reasonable interpretation.
- End every SQL statement with a semicolon.

            Real schema — use ONLY these tables and columns; never invent a column or table name:
            - EMPLOYEE(Fname, Minit, Lname, Ssn, Bdate, Address, Sex, Salary, Super_ssn, Dno)
            - DEPARTMENT(Dname, Dnumber, Mgr_ssn, Mgr_start_date)
            - DEPT_LOCATIONS(Dnumber, Dlocation)
            - PROJECT(Pname, Pnumber, Plocation, Dnum)
            - WORKS_ON(Essn, Pno, Hours)
            - DEPENDENT(Essn, Dependent_name, Sex, Bdate, Relationship)
            - Relationship: EMPLOYEE.Super_ssn references EMPLOYEE.Ssn — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: EMPLOYEE.Dno references DEPARTMENT.Dnumber — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: DEPARTMENT.Mgr_ssn references EMPLOYEE.Ssn — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: DEPT_LOCATIONS.Dnumber references DEPARTMENT.Dnumber — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: PROJECT.Dnum references DEPARTMENT.Dnumber — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: WORKS_ON.Essn references EMPLOYEE.Ssn — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: WORKS_ON.Pno references PROJECT.Pnumber — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").
            - Relationship: DEPENDENT.Essn references EMPLOYEE.Ssn — use this to JOIN/GROUP BY/HAVING when the request implies it (e.g. "customers with orders more than N", "who placed more than N orders").

Natural language input: {Q}

Query:

```

## Appendix D. Analysis: raw EXPLAIN, verdict, suggestions at every level, advisor

### A1 (Q2 (rule engine); full scan, filter)

```sql
SELECT * FROM EMPLOYEE WHERE Salary > 40000;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 33.33 | Using where |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **CRITICAL**: "Reads the whole EMPLOYEE table — about 10,734 rows — then discards 66.7% of what it read." — MySQL reported no index it could have used for this table.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 38.9 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 38.9 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 25.5 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 11.1 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on EMPLOYEE.Salary: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);` — Full table scan detected (accessType=ALL) with no index in use on 'Salary' — the same signal reported by EXPLAIN.

### A2 (Q4 (rule engine); full scan, BETWEEN)

```sql
SELECT * FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 11.11 | Using where |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **CRITICAL**: "Reads the whole EMPLOYEE table — about 10,734 rows — then discards 88.9% of what it read." — MySQL reported no index it could have used for this table.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 23.3 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 23.3 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 31.4 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 29.6 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on EMPLOYEE.Salary: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);` — Full table scan detected (accessType=ALL) with no index in use on 'Salary' — the same signal reported by EXPLAIN.

### A3 (Q5 (rule engine); full scan, text filter)

```sql
SELECT * FROM PROJECT WHERE Plocation = 'houston';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| PROJECT | ALL | NULL | NULL | 11977 | 10.00 | Using where |

`query_cost` MySQL 1221.95 · QuerySense 1221.95. Verdict **CRITICAL**: "Reads the whole PROJECT table — about 11,977 rows — then discards 90% of what it read." — MySQL reported no index it could have used for this table.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.7 | DOMAIN | CRITICAL | Full table scan on 'PROJECT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 7.7 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 24.7 | DOMAIN | CRITICAL | Full table scan on 'PROJECT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 32.1 | DOMAIN | WARNING | Full table scan on 'PROJECT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on PROJECT.Plocation: `CREATE INDEX idx_PROJECT_Plocation ON PROJECT(Plocation);` — Full table scan detected (accessType=ALL) with no index in use on 'Plocation' — the same signal reported by EXPLAIN.

### A4 (Q7 (rule engine); ref lookup on FK index)

```sql
SELECT * FROM EMPLOYEE WHERE Dno = 5;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ref | fk_EMPLOYEE_1 | fk_EMPLOYEE_1 | 5 | 100.00 | NULL |

`query_cost` MySQL 1.75 · QuerySense 1.75. Verdict **INFO**: "Uses the fk_EMPLOYEE_1 index to find rows instead of reading the whole table." — About 5 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 8.1 | DOMAIN | INFO | Query resolves via index 'fk_EMPLOYEE_1' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| MEDIUM | 26.0 | DOMAIN | INFO | Query resolves via index 'fk_EMPLOYEE_1' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| HIGH | 33.1 | DOMAIN | INFO | Query resolves via index 'fk_EMPLOYEE_1' with access type 'ref' — this is the ideal shape for a transactional lookup. |

Schema advisor:

- (no recommendations)

### A5 (Q8 (rule engine); full scan, text filter)

```sql
SELECT * FROM DEPENDENT WHERE Relationship = 'spouse';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| DEPENDENT | ALL | NULL | NULL | 10513 | 10.00 | Using where |

`query_cost` MySQL 1155.55 · QuerySense 1155.55. Verdict **CRITICAL**: "Reads the whole DEPENDENT table — about 10,513 rows — then discards 90% of what it read." — MySQL reported no index it could have used for this table.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 31.5 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 31.5 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 33.3 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 7.4 | DOMAIN | WARNING | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on DEPENDENT.Relationship: `CREATE INDEX idx_DEPENDENT_Relationship ON DEPENDENT(Relationship);` — Full table scan detected (accessType=ALL) with no index in use on 'Relationship' — the same signal reported by EXPLAIN.

### A6 (Q9 (rule engine); COUNT(*))

```sql
SELECT COUNT(*) FROM EMPLOYEE;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | index | NULL | fk_EMPLOYEE_1 | 10734 | 100.00 | Using index |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **INFO**: "Uses the fk_EMPLOYEE_1 index to find rows instead of reading the whole table." — About 10,734 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 8.7 | — | — | (no suggestions) |
| MEDIUM | 31.1 | — | — | (no suggestions) |
| HIGH | 31.4 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A7 (Q11 (rule engine); aggregate, full scan)

```sql
SELECT AVG(Salary) FROM EMPLOYEE;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 100.00 | NULL |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **INFO**: "Reads the whole EMPLOYEE table — about 10,734 rows — and returns most of them." — A full scan is the efficient plan when a query genuinely needs this much of the table, so there is little an index could save here.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 22.9 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 22.9 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 32.8 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 6.7 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A8 (Q15 (rule engine); aggregate, full scan)

```sql
SELECT SUM(Hours) FROM WORKS_ON;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| WORKS_ON | ALL | NULL | NULL | 12000 | 100.00 | NULL |

`query_cost` MySQL 1224.25 · QuerySense 1224.25. Verdict **INFO**: "Reads the whole WORKS_ON table — about 12,000 rows — and returns most of them." — A full scan is the efficient plan when a query genuinely needs this much of the table, so there is little an index could save here.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.7 | DOMAIN | CRITICAL | Full table scan on 'WORKS_ON' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 7.7 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 22.6 | DOMAIN | CRITICAL | Full table scan on 'WORKS_ON' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 8.4 | DOMAIN | WARNING | Full table scan on 'WORKS_ON' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A9 (Q16 (rule engine); GROUP BY on indexed column)

```sql
SELECT Dno, COUNT(*) FROM EMPLOYEE GROUP BY Dno;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | index | fk_EMPLOYEE_1 | fk_EMPLOYEE_1 | 10734 | 100.00 | Using index |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **INFO**: "Uses the fk_EMPLOYEE_1 index to find rows instead of reading the whole table." — About 10,734 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 34.4 | — | — | (no suggestions) |
| MEDIUM | 11.5 | — | — | (no suggestions) |
| HIGH | 8.2 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A10 (Q17 (rule engine); GROUP BY on indexed column)

```sql
SELECT Dnum, COUNT(*) FROM PROJECT GROUP BY Dnum;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| PROJECT | index | fk_PROJECT_4 | fk_PROJECT_4 | 11977 | 100.00 | Using index |

`query_cost` MySQL 1221.95 · QuerySense 1221.95. Verdict **INFO**: "Uses the fk_PROJECT_4 index to find rows instead of reading the whole table." — About 11,977 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.4 | — | — | (no suggestions) |
| MEDIUM | 27.2 | — | — | (no suggestions) |
| HIGH | 31.7 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A11 (Q19 (rule engine); GROUP BY unindexed (temporary table))

```sql
SELECT Relationship, COUNT(*) FROM DEPENDENT GROUP BY Relationship;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| DEPENDENT | ALL | NULL | NULL | 10513 | 100.00 | Using temporary |

`query_cost` MySQL 1155.55 · QuerySense 1155.55. Verdict **WARNING**: "MySQL has to assemble roughly 10,513 rows after reading them." — No index supplied the rows in the order this query needs, so the ordering or grouping work happens as a separate step.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 26.4 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 26.4 | DOMAIN | CRITICAL | Query uses a temporary table. This usually means a GROUP BY, DISTINCT, or subquery, which is unusual for a transactional query — this logic may be better suited to a batch or analytical path rather than running on every transactional call. |
| LOW | 26.4 | RESOURCE | CRITICAL | Available memory is low and this query builds a temporary table — under memory pressure this is more likely to be written to disk rather than held in memory, adding significant latency. |
| LOW | 26.4 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 7.5 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| MEDIUM | 7.5 | DOMAIN | CRITICAL | Query uses a temporary table. This usually means a GROUP BY, DISTINCT, or subquery, which is unusual for a transactional query — this logic may be better suited to a batch or analytical path rather than running on every transactional call. |
| MEDIUM | 7.5 | RESOURCE | WARNING | Query builds a temporary table. Acceptable at medium available memory, but keep an eye on it if row counts grow. |
| HIGH | 25.5 | DOMAIN | WARNING | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 25.5 | DOMAIN | WARNING | Query uses a temporary table. This usually means a GROUP BY, DISTINCT, or subquery, which is unusual for a transactional query — this logic may be better suited to a batch or analytical path rather than running on every transactional call. |
| HIGH | 25.5 | RESOURCE | INFO | Temporary table required, but available memory is high — unlikely to be a bottleneck. |

Schema advisor:

- **STRUCTURAL** on DEPENDENT: `Review the GROUP BY/DISTINCT clause on 'DEPENDENT'; consider a composite index covering the grouped column(s) if one column is consistently responsible.` — Query requires a temporary table (Extra: 'Using temporary'), typically caused by GROUP BY or DISTINCT without a supporting index — this isn't always fixable with a single-column index, so it's flagged for review rather than an automatic CREATE INDEX suggestion.

### A12 (Q20 (LLM); GROUP BY + SUM)

```sql
SELECT Pno, SUM(Hours) AS Total_Hours FROM WORKS_ON GROUP BY Pno;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| WORKS_ON | index | PRIMARY,fk_WORKS_ON_6 | fk_WORKS_ON_6 | 12000 | 100.00 | NULL |

`query_cost` MySQL 1224.25 · QuerySense 1224.25. Verdict **INFO**: "Uses the fk_WORKS_ON_6 index to find rows instead of reading the whole table." — About 12,000 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 21.4 | — | — | (no suggestions) |
| MEDIUM | 8.0 | — | — | (no suggestions) |
| HIGH | 9.0 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A13 (Q21 (rule engine); sort + LIMIT (filesort))

```sql
SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 100.00 | Using filesort |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **WARNING**: "MySQL has to sort roughly 10,734 rows after reading them." — No index supplied the rows in the order this query needs, so the ordering or grouping work happens as a separate step.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 27.9 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 27.9 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| LOW | 27.9 | RESOURCE | CRITICAL | Available memory is low and this filesort covers a significant row count — it is likely to spill to disk, which is much slower than an in-memory sort. Avoiding the sort (via an index) matters more under these constraints than it would otherwise. |
| LOW | 27.9 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 28.9 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| MEDIUM | 28.9 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| MEDIUM | 28.9 | RESOURCE | WARNING | Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring if memory pressure increases. |
| HIGH | 35.0 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 35.0 | DOMAIN | WARNING | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| HIGH | 35.0 | RESOURCE | INFO | Filesort detected, but available memory is high — this is unlikely to cause a disk-based sort or noticeable slowdown. |

Schema advisor:

- **INDEX** on EMPLOYEE.Salary: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);` — Query requires an extra sorting pass (Extra: 'Using filesort') for ORDER BY 'Salary' — an index matching the sort column can often (not always) avoid this. This is a heuristic based on the ORDER BY column alone; MySQL's optimizer may still choose a full scan for small tables or non-covering queries even after the index exists — verify with EXPLAIN after applying rather than assuming it's fixed.

### A14 (Q23 (rule engine); sort on unique column)

```sql
SELECT * FROM DEPARTMENT ORDER BY Dname ASC;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| DEPARTMENT | ALL | NULL | NULL | 12255 | 100.00 | Using filesort |

`query_cost` MySQL 13504.75 · QuerySense 13504.75. Verdict **WARNING**: "MySQL has to sort roughly 12,255 rows after reading them." — No index supplied the rows in the order this query needs, so the ordering or grouping work happens as a separate step.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.7 | DOMAIN | CRITICAL | Full table scan on 'DEPARTMENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 7.7 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| LOW | 7.7 | RESOURCE | CRITICAL | Available memory is low and this filesort covers a significant row count — it is likely to spill to disk, which is much slower than an in-memory sort. Avoiding the sort (via an index) matters more under these constraints than it would otherwise. |
| LOW | 7.7 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 31.1 | DOMAIN | CRITICAL | Full table scan on 'DEPARTMENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| MEDIUM | 31.1 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| MEDIUM | 31.1 | RESOURCE | WARNING | Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring if memory pressure increases. |
| HIGH | 30.6 | DOMAIN | WARNING | Full table scan on 'DEPARTMENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 30.6 | DOMAIN | WARNING | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| HIGH | 30.6 | RESOURCE | INFO | Filesort detected, but available memory is high — this is unlikely to cause a disk-based sort or noticeable slowdown. |

Schema advisor:

- **INDEX** on DEPARTMENT.Dname: `CREATE INDEX idx_DEPARTMENT_Dname ON DEPARTMENT(Dname);` — Query requires an extra sorting pass (Extra: 'Using filesort') for ORDER BY 'Dname' — an index matching the sort column can often (not always) avoid this. This is a heuristic based on the ORDER BY column alone; MySQL's optimizer may still choose a full scan for small tables or non-covering queries even after the index exists — verify with EXPLAIN after applying rather than assuming it's fixed.

### A15 (Q25 (rule engine); sort + LIMIT 1 on date)

```sql
SELECT * FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 100.00 | Using filesort |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **WARNING**: "MySQL has to sort roughly 10,734 rows after reading them." — No index supplied the rows in the order this query needs, so the ordering or grouping work happens as a separate step.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 6.7 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 6.7 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| LOW | 6.7 | RESOURCE | CRITICAL | Available memory is low and this filesort covers a significant row count — it is likely to spill to disk, which is much slower than an in-memory sort. Avoiding the sort (via an index) matters more under these constraints than it would otherwise. |
| LOW | 6.7 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 8.0 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| MEDIUM | 8.0 | DOMAIN | CRITICAL | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| MEDIUM | 8.0 | RESOURCE | WARNING | Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring if memory pressure increases. |
| HIGH | 9.1 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 9.1 | DOMAIN | WARNING | Query requires a filesort. Transactional queries should be simple key lookups — a sort step here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all. |
| HIGH | 9.1 | RESOURCE | INFO | Filesort detected, but available memory is high — this is unlikely to cause a disk-based sort or noticeable slowdown. |

Schema advisor:

- **INDEX** on EMPLOYEE.Bdate: `CREATE INDEX idx_EMPLOYEE_Bdate ON EMPLOYEE(Bdate);` — Query requires an extra sorting pass (Extra: 'Using filesort') for ORDER BY 'Bdate' — an index matching the sort column can often (not always) avoid this. This is a heuristic based on the ORDER BY column alone; MySQL's optimizer may still choose a full scan for small tables or non-covering queries even after the index exists — verify with EXPLAIN after applying rather than assuming it's fixed.

### A16 (Q27 (rule engine); 2-table join)

```sql
SELECT * FROM DEPENDENT JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| DEPENDENT | ALL | PRIMARY | NULL | 10513 | 100.00 | NULL |
| EMPLOYEE | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

`query_cost` MySQL 4835.1 · QuerySense 4835.1. Verdict **WARNING**: "An index on DEPENDENT was available, but MySQL chose not to use it." — MySQL only uses an index when it estimates that doing so is cheaper. On a small table, or for a query that matches most rows, reading the table directly genuinely is cheaper.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.5 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 7.5 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 8.6 | DOMAIN | CRITICAL | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 28.2 | DOMAIN | WARNING | Full table scan on 'DEPENDENT' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A17 (Q29 (rule engine); 2-table join)

```sql
SELECT * FROM EMPLOYEE JOIN DEPARTMENT ON EMPLOYEE.Dno = DEPARTMENT.Dnumber;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | fk_EMPLOYEE_1 | NULL | 10734 | 100.00 | NULL |
| DEPARTMENT | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

`query_cost` MySQL 4918.55 · QuerySense 4918.55. Verdict **WARNING**: "An index on EMPLOYEE was available, but MySQL chose not to use it." — MySQL only uses an index when it estimates that doing so is cheaper. On a small table, or for a query that matches most rows, reading the table directly genuinely is cheaper.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 8.2 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 8.2 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 8.5 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 6.7 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A18 (Q32 (LLM); 3-table join with const lookup)

```sql
SELECT T1.Fname, T1.Minit, T1.Lname FROM EMPLOYEE AS T1 JOIN WORKS_ON AS T2 ON T1.Ssn = T2.Essn JOIN PROJECT AS T3 ON T2.Pno = T3.Pnumber WHERE T3.Pname = 'ProductX';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| T3 | const | PRIMARY,Pname | Pname | 1 | 100.00 | Using index |
| T2 | ref | PRIMARY,fk_WORKS_ON_6 | fk_WORKS_ON_6 | 3 | 100.00 | Using index |
| T1 | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

`query_cost` MySQL 2.31 · QuerySense 2.31. Verdict **INFO**: "Uses the Pname index to find rows instead of reading the whole table." — About 3 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 33.3 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| MEDIUM | 30.7 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| HIGH | 31.9 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |

Schema advisor:

- (no recommendations)

### A19 (Q33 (LLM); 3-table join)

```sql
SELECT E.Fname, E.Minit, E.Lname, P.Pname FROM EMPLOYEE E JOIN WORKS_ON W ON E.Ssn = W.Essn JOIN PROJECT P ON W.Pno = P.Pnumber;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| W | index | PRIMARY,fk_WORKS_ON_6 | fk_WORKS_ON_6 | 12000 | 100.00 | Using index |
| E | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |
| P | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

`query_cost` MySQL 9624.25 · QuerySense 9624.25. Verdict **INFO**: "Uses the fk_WORKS_ON_6 index to find rows instead of reading the whole table." — About 12,000 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 7.8 | — | — | (no suggestions) |
| MEDIUM | 29.6 | — | — | (no suggestions) |
| HIGH | 29.6 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A20 (Q34 (LLM); 4-table join)

```sql
SELECT D.Dependent_name FROM DEPENDENT D JOIN EMPLOYEE E ON D.Essn = E.Ssn JOIN WORKS_ON W ON E.Ssn = W.Essn JOIN PROJECT P ON W.Pno = P.Pnumber WHERE P.Pname = 'ProductX';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| P | const | PRIMARY,Pname | Pname | 1 | 100.00 | Using index |
| W | ref | PRIMARY,fk_WORKS_ON_6 | fk_WORKS_ON_6 | 3 | 100.00 | Using index |
| E | eq_ref | PRIMARY | PRIMARY | 1 | 100.00 | Using index |
| D | ref | PRIMARY | PRIMARY | 1 | 100.00 | Using index |

`query_cost` MySQL 3.49 · QuerySense 3.49. Verdict **INFO**: "Uses the Pname index to find rows instead of reading the whole table." — About 3 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 33.4 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| MEDIUM | 29.4 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |
| HIGH | 33.4 | DOMAIN | INFO | Query resolves via index 'Pname' with access type 'ref' — this is the ideal shape for a transactional lookup. |

Schema advisor:

- (no recommendations)

### A21 (Q36 (LLM); self-join with filter)

```sql
SELECT E.Fname, E.Minit, E.Lname FROM EMPLOYEE E JOIN EMPLOYEE M ON E.Super_ssn = M.Ssn WHERE M.Fname = 'Franklin' AND M.Lname = 'Wong';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| M | ALL | PRIMARY | NULL | 10734 | 1.00 | Using where |
| E | ref | fk_EMPLOYEE_0 | fk_EMPLOYEE_0 | 1 | 100.00 | NULL |

`query_cost` MySQL 1228.86 · QuerySense 1228.86. Verdict **CRITICAL**: "Reads the whole EMPLOYEE table — about 10,734 rows — then discards 99% of what it read." — MySQL listed PRIMARY on EMPLOYEE as usable for this query and then chose not to use it.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 28.6 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 28.6 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 11.2 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 34.9 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on EMPLOYEE.Fname: `CREATE INDEX idx_EMPLOYEE_Fname ON EMPLOYEE(Fname);` — Full table scan detected (accessType=ALL) with no index in use on 'Fname' — the same signal reported by EXPLAIN.

### A22 (Q37 (LLM); GROUP BY on self-FK)

```sql
SELECT COUNT(*) AS num_employees, Super_ssn FROM EMPLOYEE GROUP BY Super_ssn;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | index | fk_EMPLOYEE_0 | fk_EMPLOYEE_0 | 10734 | 100.00 | Using index |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **INFO**: "Uses the fk_EMPLOYEE_0 index to find rows instead of reading the whole table." — About 10,734 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 8.4 | — | — | (no suggestions) |
| MEDIUM | 34.4 | — | — | (no suggestions) |
| HIGH | 28.8 | — | — | (no suggestions) |

Schema advisor:

- (no recommendations)

### A23 (Q38 (rule engine); anti-join)

```sql
SELECT EMPLOYEE.* FROM EMPLOYEE LEFT JOIN DEPENDENT ON EMPLOYEE.Ssn = DEPENDENT.Essn WHERE DEPENDENT.Essn IS NULL;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 100.00 | NULL |
| DEPENDENT | ref | PRIMARY | PRIMARY | 1 | 100.00 | Using where; Not exists; Using index |

`query_cost` MySQL 5392.84 · QuerySense 5392.84. Verdict **INFO**: "Reads the whole EMPLOYEE table — about 10,734 rows — and returns most of them." — A full scan is the efficient plan when a query genuinely needs this much of the table, so there is little an index could save here.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 6.7 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 6.7 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 34.1 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 28.0 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A24 (added by tester (not generated); const lookup on primary key)

```sql
SELECT * FROM EMPLOYEE WHERE Ssn = '123456789';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | const | PRIMARY | PRIMARY | 1 | 100.00 | NULL |

`query_cost` MySQL 1.0 · QuerySense 1.0. Verdict **INFO**: "Uses the PRIMARY index to find rows instead of reading the whole table." — About 1 rows are expected to be read.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 6.6 | DOMAIN | INFO | Query resolves via index 'PRIMARY' with access type 'const' — this is the ideal shape for a transactional lookup. |
| MEDIUM | 17.0 | DOMAIN | INFO | Query resolves via index 'PRIMARY' with access type 'const' — this is the ideal shape for a transactional lookup. |
| HIGH | 32.2 | DOMAIN | INFO | Query resolves via index 'PRIMARY' with access type 'const' — this is the ideal shape for a transactional lookup. |

Schema advisor:

- (no recommendations)

### A25 (added by tester (LLM output from the previous run); non-sargable filter)

```sql
SELECT * FROM EMPLOYEE WHERE YEAR(Bdate) > 1965;
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 100.00 | Using where |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **INFO**: "Reads the whole EMPLOYEE table — about 10,734 rows — and returns most of them." — A full scan is the efficient plan when a query genuinely needs this much of the table, so there is little an index could save here.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 20.4 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 20.4 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 30.1 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 6.7 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- (no recommendations)

### A26 (Q46 (rule engine); sargable date range, no index)

```sql
SELECT * FROM EMPLOYEE WHERE Bdate >= '1966-01-01';
```

Raw `EXPLAIN FORMAT=TRADITIONAL`:

| table | type | possible_keys | key | rows | filtered | Extra |
|---|---|---|---|---|---|---|
| EMPLOYEE | ALL | NULL | NULL | 10734 | 33.33 | Using where |

`query_cost` MySQL 1161.65 · QuerySense 1161.65. Verdict **CRITICAL**: "Reads the whole EMPLOYEE table — about 10,734 rows — then discards 66.7% of what it read." — MySQL reported no index it could have used for this table.

| Level | ms | Source | Severity | Message |
|---|---|---|---|---|
| LOW | 18.3 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| LOW | 18.3 | RESOURCE | WARNING | Full table scan over a significant row count while available memory is low — this increases pressure on the buffer pool and can slow down other concurrent queries, not just this one. |
| MEDIUM | 31.3 | DOMAIN | CRITICAL | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |
| HIGH | 32.3 | DOMAIN | WARNING | Full table scan on 'EMPLOYEE' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the lookup column(s) immediately. |

Schema advisor:

- **INDEX** on EMPLOYEE.Bdate: `CREATE INDEX idx_EMPLOYEE_Bdate ON EMPLOYEE(Bdate);` — Full table scan detected (accessType=ALL) with no index in use on 'Bdate' — the same signal reported by EXPLAIN.

## Appendix E. Index before/after: full measurements

### A1: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary)`

```sql
SELECT * FROM EMPLOYEE WHERE Salary > 40000;
```

**Before**: access ALL, key None, est. rows 10734, QuerySense cost 1161.65, MySQL `query_cost` 1161.65, verdict CRITICAL ("Reads the whole EMPLOYEE table — about 10,734 rows — then discards 66.7% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 5.279, 6.228, 7.816, 6.940, 4.652, 5.704, 6.325, 6.110, 5.335, 5.511 → median 5.907, min 4.652, max 7.816.

`EXPLAIN ANALYZE`:

```text
-> Filter: (employee.Salary > 40000)  (cost=1162 rows=3578) (actual time=0.0732..5.74 rows=2 loops=1)
    -> Table scan on EMPLOYEE  (cost=1162 rows=10734) (actual time=0.0676..4.98 rows=12000 loops=1)
```

**After**: access range, key idx_EMPLOYEE_Salary, est. rows 2, QuerySense cost 1.16, MySQL `query_cost` 1.16, verdict INFO ("Uses the idx_EMPLOYEE_Salary index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.241, 0.174, 0.241, 0.248, 0.216, 0.164, 0.419, 0.375, 0.449, 0.262 → median 0.244, min 0.164, max 0.449.

`EXPLAIN ANALYZE`:

```text
-> Index range scan on EMPLOYEE using idx_EMPLOYEE_Salary over (40000 < Salary), with index condition: (employee.Salary > 40000)  (cost=1.16 rows=2) (actual time=0.0341..0.036 rows=2 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A2: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary)`

```sql
SELECT * FROM EMPLOYEE WHERE Salary BETWEEN 30000 AND 50000;
```

**Before**: access ALL, key None, est. rows 11071, QuerySense cost 1195.35, MySQL `query_cost` 1195.35, verdict CRITICAL ("Reads the whole EMPLOYEE table — about 11,071 rows — then discards 88.9% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 7.673, 7.134, 5.298, 6.913, 6.769, 5.217, 6.528, 6.376, 4.277, 7.802 → median 6.649, min 4.277, max 7.802.

`EXPLAIN ANALYZE`:

```text
-> Filter: (employee.Salary between 30000 and 50000)  (cost=1195 rows=1230) (actual time=0.0499..8.09 rows=4 loops=1)
    -> Table scan on EMPLOYEE  (cost=1195 rows=11071) (actual time=0.0473..6.89 rows=12000 loops=1)
```

**After**: access range, key idx_EMPLOYEE_Salary, est. rows 4, QuerySense cost 2.06, MySQL `query_cost` 2.06, verdict INFO ("Uses the idx_EMPLOYEE_Salary index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.322, 0.171, 0.197, 0.168, 0.167, 0.163, 0.163, 0.243, 0.642, 0.422 → median 0.184, min 0.163, max 0.642.

`EXPLAIN ANALYZE`:

```text
-> Index range scan on EMPLOYEE using idx_EMPLOYEE_Salary over (30000 <= Salary <= 50000), with index condition: (employee.Salary between 30000 and 50000)  (cost=2.06 rows=4) (actual time=0.031..0.0326 rows=4 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A3: `CREATE INDEX idx_PROJECT_Plocation ON PROJECT(Plocation)`

```sql
SELECT * FROM PROJECT WHERE Plocation = 'houston';
```

**Before**: access ALL, key None, est. rows 11977, QuerySense cost 1221.95, MySQL `query_cost` 1221.95, verdict CRITICAL ("Reads the whole PROJECT table — about 11,977 rows — then discards 90% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 2.487, 1.854, 2.531, 2.905, 2.799, 2.295, 2.708, 2.349, 2.164, 2.131 → median 2.418, min 1.854, max 2.905.

`EXPLAIN ANALYZE`:

```text
-> Filter: (project.Plocation = 'houston')  (cost=1222 rows=1198) (actual time=0.1..3.74 rows=2 loops=1)
    -> Table scan on PROJECT  (cost=1222 rows=11977) (actual time=0.0975..3.03 rows=12000 loops=1)
```

**After**: access ref, key idx_PROJECT_Plocation, est. rows 2, QuerySense cost 0.7, MySQL `query_cost` 0.7, verdict INFO ("Uses the idx_PROJECT_Plocation index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.389, 0.379, 0.621, 0.479, 0.285, 0.174, 0.161, 0.156, 0.145, 0.157 → median 0.229, min 0.145, max 0.621.

`EXPLAIN ANALYZE`:

```text
-> Index lookup on PROJECT using idx_PROJECT_Plocation (Plocation = 'houston')  (cost=0.7 rows=2) (actual time=0.0199..0.0217 rows=2 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A5: `CREATE INDEX idx_DEPENDENT_Relationship ON DEPENDENT(Relationship)`

```sql
SELECT * FROM DEPENDENT WHERE Relationship = 'spouse';
```

**Before**: access ALL, key None, est. rows 10513, QuerySense cost 1155.55, MySQL `query_cost` 1155.55, verdict CRITICAL ("Reads the whole DEPENDENT table — about 10,513 rows — then discards 90% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 4.907, 4.090, 3.616, 5.094, 5.868, 3.510, 3.941, 3.405, 4.164, 3.403 → median 4.015, min 3.403, max 5.868.

`EXPLAIN ANALYZE`:

```text
-> Filter: (dependent.Relationship = 'spouse')  (cost=1156 rows=1051) (actual time=0.0624..5.9 rows=3 loops=1)
    -> Table scan on DEPENDENT  (cost=1156 rows=10513) (actual time=0.0596..5.34 rows=12000 loops=1)
```

**After**: access ref, key idx_DEPENDENT_Relationship, est. rows 3, QuerySense cost 1.05, MySQL `query_cost` 1.05, verdict INFO ("Uses the idx_DEPENDENT_Relationship index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.401, 0.271, 0.244, 0.272, 0.236, 0.453, 0.347, 0.222, 0.200, 0.163 → median 0.258, min 0.163, max 0.453.

`EXPLAIN ANALYZE`:

```text
-> Index lookup on DEPENDENT using idx_DEPENDENT_Relationship (Relationship = 'spouse')  (cost=1.05 rows=3) (actual time=0.023..0.0245 rows=3 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A13: `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary)`

```sql
SELECT * FROM EMPLOYEE ORDER BY Salary DESC LIMIT 5;
```

**Before**: access ALL, key None, est. rows 11591, QuerySense cost 1247.35, MySQL `query_cost` 1247.35, verdict WARNING ("MySQL has to sort roughly 11,591 rows after reading them."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 9.557, 8.337, 9.695, 7.234, 8.617, 7.132, 9.728, 8.142, 7.580, 7.885 → median 8.239, min 7.132, max 9.728.

`EXPLAIN ANALYZE`:

```text
-> Limit: 5 row(s)  (cost=1247 rows=5) (actual time=6.98..6.98 rows=5 loops=1)
    -> Sort: employee.Salary DESC, limit input to 5 row(s) per chunk  (cost=1247 rows=11591) (actual time=6.69..6.69 rows=5 loops=1)
        -> Table scan on EMPLOYEE  (cost=1247 rows=11591) (actual time=0.0561..4.16 rows=12000 loops=1)
```

**After**: access index, key idx_EMPLOYEE_Salary, est. rows 5, QuerySense cost 1268.45, MySQL `query_cost` 1268.45, verdict INFO ("Uses the idx_EMPLOYEE_Salary index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.251, 0.454, 0.772, 0.319, 0.315, 0.322, 0.300, 0.410, 0.505, 0.386 → median 0.354, min 0.251, max 0.772.

`EXPLAIN ANALYZE`:

```text
-> Limit: 5 row(s)  (cost=0.0376 rows=5) (actual time=0.751..0.753 rows=5 loops=1)
    -> Index scan on EMPLOYEE using idx_EMPLOYEE_Salary (reverse)  (cost=0.0376 rows=5) (actual time=0.453..0.455 rows=5 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A14: `CREATE INDEX idx_DEPARTMENT_Dname ON DEPARTMENT(Dname)`

```sql
SELECT * FROM DEPARTMENT ORDER BY Dname ASC;
```

**Before**: access ALL, key None, est. rows 12255, QuerySense cost 13504.75, MySQL `query_cost` 13504.75, verdict WARNING ("MySQL has to sort roughly 12,255 rows after reading them."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 26.560, 27.588, 27.635, 28.856, 29.354, 30.928, 26.983, 30.179, 28.290, 25.737 → median 27.962, min 25.737, max 30.928.

`EXPLAIN ANALYZE`:

```text
-> Sort: department.Dname  (cost=1250 rows=12255) (actual time=11.8..13.1 rows=12000 loops=1)
    -> Table scan on DEPARTMENT  (cost=1250 rows=12255) (actual time=0.236..3.27 rows=12000 loops=1)
```

**After**: access ALL, key None, est. rows 11936, QuerySense cost 13153.85, MySQL `query_cost` 13153.85, verdict WARNING ("MySQL has to sort roughly 11,936 rows after reading them."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 24.007, 25.032, 26.039, 25.166, 22.391, 25.377, 22.669, 25.560, 25.092, 26.185 → median 25.129, min 22.391, max 26.185.

`EXPLAIN ANALYZE`:

```text
-> Sort: department.Dname  (cost=1218 rows=11936) (actual time=10.1..10.8 rows=12000 loops=1)
    -> Table scan on DEPARTMENT  (cost=1218 rows=11936) (actual time=0.229..2.5 rows=12000 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A15: `CREATE INDEX idx_EMPLOYEE_Bdate ON EMPLOYEE(Bdate)`

```sql
SELECT * FROM EMPLOYEE ORDER BY Bdate ASC LIMIT 1;
```

**Before**: access ALL, key None, est. rows 12392, QuerySense cost 1327.45, MySQL `query_cost` 1327.45, verdict WARNING ("MySQL has to sort roughly 12,392 rows after reading them."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 8.475, 10.931, 7.291, 6.348, 8.487, 7.755, 7.301, 7.525, 9.651, 8.881 → median 8.115, min 6.348, max 10.931.

`EXPLAIN ANALYZE`:

```text
-> Limit: 1 row(s)  (cost=1327 rows=1) (actual time=8.72..8.72 rows=1 loops=1)
    -> Sort: employee.Bdate, limit input to 1 row(s) per chunk  (cost=1327 rows=12392) (actual time=8.72..8.72 rows=1 loops=1)
        -> Table scan on EMPLOYEE  (cost=1327 rows=12392) (actual time=0.0638..6.12 rows=12000 loops=1)
```

**After**: access index, key idx_EMPLOYEE_Bdate, est. rows 1, QuerySense cost 1245.95, MySQL `query_cost` 1245.95, verdict INFO ("Uses the idx_EMPLOYEE_Bdate index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 1.294, 0.462, 0.508, 0.523, 0.384, 0.341, 0.385, 0.531, 0.358, 0.396 → median 0.429, min 0.341, max 1.294.

`EXPLAIN ANALYZE`:

```text
-> Limit: 1 row(s)  (cost=0.00763 rows=1) (actual time=0.032..0.0321 rows=1 loops=1)
    -> Index scan on EMPLOYEE using idx_EMPLOYEE_Bdate  (cost=0.00763 rows=1) (actual time=0.0314..0.0314 rows=1 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A21: `CREATE INDEX idx_EMPLOYEE_Fname ON EMPLOYEE(Fname)`

```sql
SELECT E.Fname, E.Minit, E.Lname FROM EMPLOYEE E JOIN EMPLOYEE M ON E.Super_ssn = M.Ssn WHERE M.Fname = 'Franklin' AND M.Lname = 'Wong';
```

**Before**: access ALL, key fk_EMPLOYEE_0, est. rows 10776, QuerySense cost 1233.59, MySQL `query_cost` 1233.59, verdict CRITICAL ("Reads the whole EMPLOYEE table — about 10,776 rows — then discards 99% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 5.053, 5.910, 3.723, 4.371, 3.684, 4.852, 4.772, 4.905, 4.673, 4.489 → median 4.723, min 3.684, max 5.910.

`EXPLAIN ANALYZE`:

```text
-> Nested loop inner join  (cost=1234 rows=194) (actual time=0.434..4.19 rows=3 loops=1)
    -> Filter: ((m.Lname = 'Wong') and (m.Fname = 'Franklin'))  (cost=1166 rows=108) (actual time=0.0587..3.81 rows=1 loops=1)
        -> Table scan on M  (cost=1166 rows=10776) (actual time=0.0541..3.16 rows=12000 loops=1)
    -> Index lookup on E using fk_EMPLOYEE_0 (Super_ssn = m.Ssn)  (cost=0.451 rows=1.8) (actual time=0.0283..0.03 rows=3 loops=1)
```

**After**: access ref, key idx_EMPLOYEE_Fname, est. rows 2, QuerySense cost 0.42, MySQL `query_cost` 0.42, verdict INFO ("Uses the idx_EMPLOYEE_Fname index to find rows instead of reading the whole table."), advisor recommendations 0.

Timed runs (ms, 10 after 3 warm-ups): 0.326, 0.338, 0.239, 0.233, 0.251, 0.406, 0.263, 0.261, 0.275, 0.366 → median 0.269, min 0.233, max 0.406.

`EXPLAIN ANALYZE`:

```text
-> Nested loop inner join  (cost=0.331 rows=0.203) (actual time=0.0326..0.0349 rows=3 loops=1)
    -> Filter: (m.Lname = 'Wong')  (cost=0.26 rows=0.1) (actual time=0.0195..0.0199 rows=1 loops=1)
        -> Index lookup on M using idx_EMPLOYEE_Fname (Fname = 'Franklin')  (cost=0.26 rows=1) (actual time=0.0183..0.0186 rows=1 loops=1)
    -> Index lookup on E using fk_EMPLOYEE_0 (Super_ssn = m.Ssn)  (cost=2.53 rows=2.03) (actual time=0.0117..0.0135 rows=3 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

### A26: `CREATE INDEX idx_EMPLOYEE_Bdate ON EMPLOYEE(Bdate)`

```sql
SELECT * FROM EMPLOYEE WHERE Bdate >= '1966-01-01';
```

**Before**: access ALL, key None, est. rows 11619, QuerySense cost 1250.15, MySQL `query_cost` 1250.15, verdict CRITICAL ("Reads the whole EMPLOYEE table — about 11,619 rows — then discards 66.7% of what it read."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 27.674, 24.815, 33.400, 36.268, 40.696, 32.309, 33.813, 34.029, 36.145, 30.121 → median 33.606, min 24.815, max 40.696.

`EXPLAIN ANALYZE`:

```text
-> Filter: (employee.Bdate >= DATE'1966-01-01')  (cost=1250 rows=3873) (actual time=0.0482..7.76 rows=10837 loops=1)
    -> Table scan on EMPLOYEE  (cost=1250 rows=11619) (actual time=0.0456..6.44 rows=12000 loops=1)
```

**After**: access ALL, key None, est. rows 12307, QuerySense cost 1318.95, MySQL `query_cost` 1318.95, verdict WARNING ("An index on EMPLOYEE was available, but MySQL chose not to use it."), advisor recommendations 1.

Timed runs (ms, 10 after 3 warm-ups): 34.473, 29.220, 33.106, 33.641, 36.161, 33.772, 33.888, 36.035, 30.508, 29.456 → median 33.706, min 29.220, max 36.161.

`EXPLAIN ANALYZE`:

```text
-> Filter: (employee.Bdate >= DATE'1966-01-01')  (cost=1319 rows=6153) (actual time=0.0399..6.08 rows=10837 loops=1)
    -> Table scan on EMPLOYEE  (cost=1319 rows=12307) (actual time=0.0374..5.26 rows=12000 loops=1)
```

Index dropped afterwards; `SHOW INDEX` identical to before: **True**.

No runnable `CREATE INDEX` recommended for: A4, A6, A7, A8, A9, A10, A11, A12, A16, A17, A18, A19, A20, A22, A23, A24, A25.

## Appendix F. Rewrite (`/api/query/compare`) per query

| ID | Rewritten | Rule(s) | Est. rows before → after | Cost before → after | Summary |
|---|---|---|---|---|---|
| A1 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A2 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A3 | True | RW1 | 11977 → 11977 | 1221.95 → 1221.95 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A4 | True | RW1 | 5 → 5 | 1.75 → 1.75 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A5 | True | RW1 | 10513 → 10513 | 1155.55 → 1155.55 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A6 | False | — | 10734 → — | 1161.65 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A7 | False | — | 10734 → — | 1161.65 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A8 | False | — | 12000 → — | 1224.25 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A9 | False | — | 10734 → — | 1161.65 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A10 | False | — | 11977 → — | 1221.95 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A11 | False | — | 10513 → — | 1155.55 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A12 | False | — | 12000 → — | 1224.25 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A13 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A14 | True | RW1 | 12255 → 12255 | 13504.75 → 13504.75 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A15 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A16 | False | — | 10513 → — | 4835.1 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A17 | False | — | 10734 → — | 4918.55 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A18 | False | — | 3 → — | 2.31 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A19 | False | — | 12000 → — | 9624.25 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A20 | False | — | 3 → — | 3.49 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A21 | False | — | 10734 → — | 1228.86 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A22 | False | — | 10734 → — | 1161.65 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A23 | False | — | 10734 → — | 5392.84 → — | No rewrite rule applied to this query, so there is nothing to compare it against. |
| A24 | True | RW1 | 1 → 1 | 1.0 → 1.0 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A25 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |
| A26 | True | RW1 | 10734 → 10734 | 1161.65 → 1161.65 | 1 rewrite rule applied. EXPLAIN estimates the same number of rows read either way, so the rewrite changes the shape of the query without changing what it reads. |

## Appendix G. MongoDB questions (built-in demo schema)

### M1. Show all customers

- Path RULE_ENGINE, confidence 0.95, 44.6 ms

```js
db.customers.find({})
```

Analyze (127.4 ms): plan COLLSCAN, documents 12000, verdict INFO "Reads the whole customers collection — about 12,000 documents — and returns most of them.".
- DOMAIN CRITICAL: Collection scan on 'customers' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M2. Show customers in Mumbai

- Path RULE_ENGINE, confidence 0.95, 292.6 ms

```js
db.customers.find({"city": "Mumbai"})
```

Analyze (38.4 ms): plan COLLSCAN, documents 12000, verdict CRITICAL "Reads the whole customers collection — about 12,000 documents — then discards 90.6% of what it read.".
- DOMAIN CRITICAL: Collection scan on 'customers' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M3. How many orders are there

- Path RULE_ENGINE, confidence 0.95, 25.2 ms

```js
db.orders.countDocuments({})
```

Analyze (30.3 ms): plan RECORD_STORE_FAST_COUNT, documents 0, verdict INFO "MongoDB can answer this query without reading any collection.".
- DOMAIN WARNING: No index used for this lookup on 'orders'. Transactional queries should resolve via an index (ideally _id or a unique index) for consistent, low latency.

### M4. Average price of products

- Path RULE_ENGINE, confidence 0.95, 28.4 ms

```js
db.products.aggregate([{"$group": {"_id": null, "avg_price": {"$avg": "$price"}}}])
```

Analyze (105.0 ms): plan COLLSCAN, documents 12000, verdict WARNING "Reads the whole products collection — about 12,000 documents.".
- DOMAIN CRITICAL: Collection scan on 'products' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M5. Top 5 products by price

- Path RULE_ENGINE, confidence 0.95, 271.2 ms

```js
db.products.find({}).sort({"price": -1}).limit(5)
```

Analyze (24.1 ms): plan COLLSCAN, documents 12000, verdict WARNING "MongoDB has to sort roughly 12,000 documents in memory after reading them.".
- DOMAIN CRITICAL: Collection scan on 'products' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.
- DOMAIN CRITICAL: Query sorts documents in memory. Transactional queries should be simple key lookups — an in-memory sort here suggests this query is doing more than it should on a hot path. Consider an index covering the sort order, or reconsider whether this belongs in the transactional path at all.
- RESOURCE WARNING: In-memory sort over a significant number of documents. At medium available memory this is usually fine, but worth monitoring if memory pressure increases.

### M6. How many customers per city

- Path RULE_ENGINE, confidence 0.95, 29.4 ms

```js
db.customers.aggregate([{"$group": {"_id": "$city", "count": {"$sum": 1}}}])
```

Analyze (37.6 ms): plan COLLSCAN, documents 12000, verdict WARNING "Reads the whole customers collection — about 12,000 documents.".
- DOMAIN CRITICAL: Collection scan on 'customers' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M7. Show orders with their customers

- Path RULE_ENGINE, confidence 0.95, 10.5 ms

```js
db.orders.aggregate([{"$lookup": {"from": "customers", "localField": "customer_id", "foreignField": "_id", "as": "customers"}}, {"$unwind": "$customers"}])
```

Analyze (649.5 ms): plan COLLSCAN, documents 12000, verdict INFO "Reads the whole orders collection — about 12,000 documents — and returns most of them.".
- DOMAIN CRITICAL: Collection scan on 'orders' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M8. Customers without orders

- Path RULE_ENGINE, confidence 0.95, 31.8 ms

```js
db.customers.aggregate([{"$lookup": {"from": "orders", "localField": "_id", "foreignField": "customer_id", "as": "orders"}}, {"$match": {"orders": {"$size": 0}}}, {"$project": {"orders": 0}}])
```

Analyze (310.1 ms): plan COLLSCAN, documents 24000, verdict INFO "Reads all an unreported number of documents of customers, which is normal at this size.".
- DOMAIN CRITICAL: Collection scan on 'customers' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M9. Which customers have placed more than 3 orders

- Path RULE_ENGINE, confidence 0.95, 516.4 ms

```js
db.customers.aggregate([{"$lookup": {"from": "orders", "localField": "_id", "foreignField": "customer_id", "as": "orders"}}, {"$unwind": "$orders"}, {"$group": {"_id": {"email": "$email", "_id": "$_id"}, "count_orders__id": {"$sum": 1}}}, {"$match": {"count_orders__id": {"$gt": 3}}}])
```

Analyze (1754.7 ms): plan COLLSCAN, documents 12000, verdict INFO "Reads the whole customers collection — about 12,000 documents — and returns most of them.".
- DOMAIN CRITICAL: Collection scan on 'customers' in a transactional query. This query path is expected to run frequently — a missing index here means this scan repeats on every call. Add an index on the filtered field(s) immediately.

### M10. Delete all orders

- Path OLLAMA, confidence 0.2, 352.4 ms
- Warning: "This query would change every document of 'orders'. QuerySense does not suggest that; say which documents you mean."
- Decline reason: "The question asks to delete rows without saying which ones."

```js
db.orders.deleteMany({})
```

## Appendix H. Environment (raw)

```json
{
 "date": "2026-10-20 19:48:58 +0530",
 "branch": "feature/schema-adaptive-rule-engine",
 "commit": "b6d5134f7317fb1c417c97ee6566e4b0351fa16b",
 "uncommitted_files": 55,
 "java": "openjdk version \"25.0.3\" 2026-04-21 LTS",
 "mysql": "9.7.0",
 "ollama": "{\"version\":\"0.40.6\"}",
 "gpu": "NVIDIA GeForce RTX 5060 Laptop GPU, 8151 MiB, 595.95",
 "python": "3.14.3",
 "seed_rows_property": "'upload.seed' is not recognized as an internal or external command,\noperable program or batch file."
}
```

