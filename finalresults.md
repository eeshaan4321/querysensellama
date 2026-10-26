# QuerySense: Final Results on a Company Schema

Tested 2026-10-19 against the Elmasri–Navathe **COMPANY** database, a standard
textbook company schema downloaded from the internet. I uploaded it, tested it
end to end, fixed the defects the testing found, and then tested again from
scratch. Every figure below comes from the **final clean run**
(19:34:07–19:34:23), unless a row says *before fix*.

## Summary

| | Result |
|---|---|
| Schema | COMPANY (6 tables, 8 distinct foreign keys, composite keys, a foreign-key cycle, a self-reference) |
| Upload | 200 OK in **4.6 s**; 72,000 rows; **8/8** foreign keys enforced by MySQL; **0** orphan rows |
| Questions asked | 24 natural-language questions |
| Rule engine | 16 of 24 answered; **16/16** produced runnable SQL; **11/11** with a ground-truth query returned exactly the expected rows |
| LLM fallback | 8 of 24, each declined by the rule engine with a stated reason; 7/7 runnable (the 3 checked against ground truth return the right rows, one with fewer columns); 1 unsafe `DELETE` correctly flagged at 0.20 |
| Cache | 7/7 repeated LLM questions served from cache in a median **31.7 ms**; the unsafe answer was never cached |
| EXPLAIN fidelity | **12/12** analysed queries match MySQL's own `EXPLAIN` field for field |
| Index proof | The advisor's own `CREATE INDEX` took a query from 11,984 rows read to 2, and cost from 1222.65 to 0.70 |
| Defects found | **8** (7 correctness, 1 wording), all fixed and re-verified |
| Test suites | Backend **994/994**, frontend **103/103**, lint clean, production build succeeds |

---

## 1. The schema used

**Source:** the MySQL script for *Fundamentals of Database Systems* (Elmasri &
Navathe), published at
[tolgahanakgun/Elmasri-Database](https://github.com/tolgahanakgun/Elmasri-Database/blob/master/Employee_Database_Script.sql)
and tested on MySQL 5.7 by its author. I also found an older mSQL version from
[UMBC](https://userpages.cs.umbc.edu/finin/461/msql/company-schema.msql), but
it has no foreign keys, so I didn't use it.

| Table | Columns | Keys in the file |
|---|---|---|
| EMPLOYEE | Fname, Minit, Lname, **Ssn**, Bdate, Address, Sex, Salary, Super_ssn, Dno | PK Ssn; FK Super_ssn→EMPLOYEE, Dno→DEPARTMENT (both added by `ALTER TABLE`) |
| DEPARTMENT | Dname, **Dnumber**, Mgr_ssn, Mgr_start_date | PK Dnumber; `UNIQUE(Dname)`; FK Mgr_ssn→EMPLOYEE |
| DEPT_LOCATIONS | **Dnumber, Dlocation** | composite PK; FK Dnumber→DEPARTMENT |
| PROJECT | Pname, **Pnumber**, Plocation, Dnum | PK Pnumber; `UNIQUE(Pname)`; FK Dnum→DEPARTMENT |
| WORKS_ON | **Essn, Pno**, Hours | composite PK; FK Essn→EMPLOYEE, Pno→PROJECT |
| DEPENDENT | **Essn, Dependent_name**, Sex, Bdate, Relationship | composite PK; FK Essn→EMPLOYEE |

Why this is a hard test:
- Upper-case names.
- Four foreign keys declared only by `ALTER TABLE` after the data is inserted, two of them duplicates.
- A cycle: EMPLOYEE.Dno → DEPARTMENT and DEPARTMENT.Mgr_ssn → EMPLOYEE, both NOT NULL.
- A self-reference on a **text** key (Super_ssn CHAR(9)).
- Two different ways to join EMPLOYEE and DEPARTMENT.
- 35 real sample rows.

---

## 2. Test environment

| Component | Version |
|---|---|
| Backend | Spring Boot 3, Java 25 (OpenJDK 25.0.3), branch `feature/schema-adaptive-rule-engine` |
| Database | MySQL 9.7.0 on port 3306 |
| MongoDB | up (health check), not used by this schema |
| LLM | `qwen2.5-coder:7b` on Ollama 0.40.6 |
| GPU | NVIDIA GeForce RTX 5060 Laptop, 8 GB |
| Seeding | 12,000 rows per table (`querysense.schema.upload.seed-rows`); the file's own rows are loaded first |

How it was run: a script uploaded the file to `POST /api/schema/upload` and
called every endpoint. It then ran every generated query, and ground-truth
queries, **directly in MySQL** to check the answers. It also read MySQL's own
`EXPLAIN` independently, to check the analysis against it. The cache rows from
earlier runs were deleted before each clean run.

---

## 3. Defects found, fixed and re-verified

| # | Defect | Evidence before the fix | Root cause | Fix | Evidence after |
|---|---|---|---|---|---|
| 1 | Foreign keys, primary keys and UNIQUE added by `ALTER TABLE` were silently ignored | Only 6 of 8 FKs were recognised. EMPLOYEE.Dno and Super_ssn had no FK, giving 20 orphan `Dno` and 10,131 orphan `Super_ssn` values. No warning was shown | `DdlSchemaParser` sent every statement that wasn't `CREATE TABLE` to the INSERT reader only | Reads `ALTER TABLE [ONLY] … ADD [CONSTRAINT] FOREIGN KEY / PRIMARY KEY / UNIQUE`, including several clauses per statement. Ignores harmless ones quietly (`OWNER TO`, `DISABLE KEYS`, …) and names anything else in a warning. Removes duplicate FKs | 8/8 FKs exist as MySQL constraints, 0 orphans. Also covers pg_dump and phpMyAdmin dumps, which declare all their keys this way |
| 2 | Table-level `UNIQUE (col)` was dropped | DEPARTMENT and PROJECT had no unique index | Only table-level `PRIMARY KEY` was read | Single-column table-level UNIQUE marks the column unique | `Dname` and `Pname` unique indexes exist; `WHERE Dname='Research'` is now a 1-row `const` lookup |
| 3 | Seeding could not satisfy a foreign-key cycle or a text-keyed self-reference (hidden by #1) | Constraints were created **before** seeding, so the first table in the EMPLOYEE↔DEPARTMENT cycle could not insert. The self-reference used `FLOOR(Ssn/2)`, which means nothing for a CHAR key | Provisioning order and an integer-only self-reference routine | Constraints are added **after** seeding (as the textbook script does). Values that match no parent are pointed at real parent rows first. Text self-references link each generated row to an earlier one. A cycle is broken at a table that is actually in it | 11,998 of 12,000 employees have a real supervisor (the 2 roots are James Borg from the file and the first generated row); 0 orphans across all 8 FKs |
| 4 | Fix #3 doubled the upload time | 5.9 s → 12.0 s | Each `ADD FOREIGN KEY` re-checked every row with checks on: **728 ms vs 5.7 ms** per FK, measured | Constraints are added on one connection with `FOREIGN_KEY_CHECKS=0`. The data was already repaired in #3, and a failed constraint is now reported as an upload warning instead of only logged | Upload **4.6 s**, faster than the original 5.9 s |
| 5 | The LLM handed back the unsafe query the rule engine had refused | "Delete dependents": the rule engine declined ("asks to delete rows without saying which ones"). The LLM returned `DELETE FROM DEPENDENT;` at **0.65**, and it **was cached** | LLM output was checked for unknown names and missing parts, never for safety | New `GeneratedQueryValidator.unsafeStatement`: DELETE/UPDATE without WHERE, DROP, TRUNCATE; MongoDB `deleteMany({})`, `remove({})`, `updateMany({},…)`, `drop()`. Flagged at 0.20, never cached, and an older cached copy is not served | **0.20** with "This query would delete every row of 'DEPENDENT'…"; the run-1 cached copy was skipped live; 0 rows written |
| 6 | The schema advisor wrote DDL that cannot run | `CREATE INDEX idx_e_Salary ON e(Salary);` (alias), `… ON SUM(w(Hours));`, `… ON PROJECT(COUNT(*));`, `… ON EMPLOYEE(Salary * 2);` | Filter and sort "columns" were raw expression text; table names came from EXPLAIN, which reports aliases | Only a plain column counts as a filter or sort column; aliases are mapped to real tables in all DDL (advisor, TX1, TX2, RS1, RS3) | `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);`. An aggregate sort gets the placeholder, which the Apply-all script already turns into a review note |
| 7 | Low resources turned "this is ideal" into a warning | `Ssn = '123456789'` under LOW: *"…this is the ideal shape for a transactional lookup"* shown as **WARNING** | RS4 moved every domain suggestion up a step, including TX5's INFO note | An INFO suggestion is not moved up | INFO at LOW, MEDIUM and HIGH |
| 8 | Plain-English text named the alias, not the table | *"Reads the whole **e** table"*, *"Full table scan on '**e**'"* | Same alias issue as #6, in the wording | The verdict and TX1/TX4 messages use the real table name | *"Reads the whole **EMPLOYEE** table — about 11,745 rows — then discards 66.7%"* |

All 8 are covered by tests: 22 new backend tests and 2 updated. The 6
new parser tests were run against the old parser: all 6 failed there and pass
now. `RULES.md` was updated for #5 and #7. The Workbench warning label changed
from "Does not match your schema" to "Check this query before using it",
because it now also carries #5.

---

## 4. Upload fidelity (read from MySQL's `information_schema`)

| Check | Before fixes | Final run |
|---|---|---|
| Foreign keys in the response | 6 | **8** |
| Foreign keys enforced by MySQL | 6 | **8** |
| Unique indexes (non-PK) | 0 | **2** (Dname, Pname) |
| Orphan values, all 8 FKs | 10,151 | **0** |
| File rows kept exactly (e.g. John Smith → supervisor 333445555, Dno 5) | yes | yes |
| File departments (Headquarters 1, Administration 4, Research 5) | yes | yes |
| Rows per table | 12,000 | 12,000 |
| Upload warnings | none (but data lost silently) | none (nothing lost) |
| Upload time | 5.9 s | **4.6 s** |

---

## 5. Query generation: 24 questions

*Truth* = the result rows were compared with a hand-written query run in
MySQL. Every query was run in MySQL to confirm it executes.

| # | Question | Handled by | Conf. | ms | Generated SQL | Runs | Truth |
|---|---|---|---|---|---|---|---|
| 1 | Show all employees | Rule engine | 0.95 | 206 | `SELECT * FROM EMPLOYEE;` | ✓ | ✓ |
| 2 | How many employees are there | Rule engine | 0.95 | 34 | `SELECT COUNT(*) FROM EMPLOYEE;` | ✓ | ✓ |
| 3 | Show the first name and last name of employees | LLM | 0.65 | 690 | `SELECT Fname, Lname FROM EMPLOYEE;` | ✓ | ✓ |
| 4 | Show employees with salary above 40000 | Rule engine | 0.95 | 18 | `… WHERE Salary > 40000;` | ✓ | ✓ |
| 5 | Top 5 employees sorted by salary descending | Rule engine | 0.95 | 14 | `… ORDER BY Salary DESC LIMIT 5;` | ✓ | – |
| 6 | Average salary of employees | Rule engine | 0.95 | 12 | `SELECT AVG(Salary) FROM EMPLOYEE;` | ✓ | ✓ |
| 7 | Maximum salary of employees | Rule engine | 0.95 | 13 | `SELECT MAX(Salary) FROM EMPLOYEE;` | ✓ | ✓ |
| 8 | How many employees per Dno | Rule engine | 0.95 | 35 | `SELECT Dno, COUNT(*) … GROUP BY Dno;` | ✓ | ✓ |
| 9 | Show projects in Houston | Rule engine | 0.95 | 26 | `… WHERE Plocation = 'houston';` | ✓ | ✓ |
| 10 | Show departments sorted by Dname | Rule engine | 0.95 | 12 | `… ORDER BY Dname ASC;` | ✓ | – |
| 11 | Show dependents whose relationship is spouse | Rule engine | 0.95 | 17 | `… WHERE Relationship = 'spouse';` | ✓ | ✓ |
| 12 | Show employees with Dno 5 | Rule engine | 0.95 | 12 | `… WHERE Dno = 5;` | ✓ | ✓ |
| 13 | Show female employees | LLM | 0.65 | 423 | `SELECT Fname, Minit, Lname … WHERE Sex = 'F';` | ✓ | same 3 rows, fewer columns |
| 14 | Total hours per Pno in works_on | LLM | 0.65 | 436 | `SELECT Pno, SUM(Hours) … GROUP BY Pno;` | ✓ | ✓ |
| 15 | How many projects per Dnum | Rule engine | 0.95 | 9 | `SELECT Dnum, COUNT(*) … GROUP BY Dnum;` | ✓ | ✓ |
| 16 | Show distinct plocation of projects | Rule engine | 0.95 | 12 | `SELECT DISTINCT Plocation FROM PROJECT;` | ✓ | ✓ |
| 17 | Show employees born after 1965 | LLM | 0.65 | 558 | `… WHERE YEAR(Bdate) > 1965;` | ✓ | – (10,837 rows) |
| 18 | Show dependents with their employees | Rule engine | 0.95 | 38 | `… JOIN EMPLOYEE ON DEPENDENT.Essn = EMPLOYEE.Ssn;` | ✓ | – |
| 19 | Show projects with their department | Rule engine | 0.95 | 12 | `… JOIN DEPARTMENT ON PROJECT.Dnum = DEPARTMENT.Dnumber;` | ✓ | – |
| 20 | Show employees with their department | LLM | 0.65 | 703 | `… JOIN DEPARTMENT D ON E.Dno = D.Dnumber;` | ✓ | – |
| 21 | Names of employees who work on ProductX | LLM | 0.65 | 1051 | 3-table join EMPLOYEE→WORKS_ON→PROJECT | ✓ | 3 rows: John, Joyce + 1 generated |
| 22 | Employees who have no dependents | Rule engine | 0.95 | 12 | `LEFT JOIN DEPENDENT … WHERE DEPENDENT.Essn IS NULL;` | ✓ | – |
| 23 | Show employees and the name of their supervisor | LLM | 0.65 | 832 | self-join `E.Super_ssn = S.Ssn` | ✓ | – (11,998 rows) |
| 24 | Delete dependents | LLM, **flagged** | **0.20** | 176 | `DELETE FROM DEPENDENT;` + warning | not run | – |

**Why the rule engine declined the 8 it declined.** These are its own words
from the rule trace.

| # | Decline reason | Assessment |
|---|---|---|
| 3, 21 | "The question asks for specific fields, but at least one of them is not a column of EMPLOYEE." | Honest: "first name" isn't `Fname` |
| 13 | 'uses "female", which isn't a table, column or stored value' | Honest |
| 14 | 'No column of WORKS_ON contains the value "works"' | Limitation: "in works_on" was read as a value filter |
| 17 | 'uses "born", which isn't a table, column or stored value' | Honest |
| 20 | "There is more than one way to connect EMPLOYEE and DEPARTMENT (Dno and Mgr_ssn)…" | **Correct by design.** This schema really does have two join paths |
| 23 | 'uses "name", which isn't a table, column or stored value' | Honest |
| 24 | "The question asks to delete rows without saying which ones." | Correct, and now the LLM can't override it (defect #5) |

### Routing and latency

| Path | Questions | Median | Max |
|---|---|---|---|
| Rule engine | 16 | **13.7 ms** | 206 ms (first request after upload) |
| Local LLM | 8 | **624 ms** | 1,051 ms |
| Cache (same questions asked again) | 7 | **31.7 ms** | 44 ms |
| Unsafe answer asked again | 1 | not cached, 0.20 again | – |

---

## 6. Proof for EXPLAIN: QuerySense against MySQL's own output

Left: MySQL's `EXPLAIN FORMAT=TRADITIONAL`, run directly with the `mysql`
client. Right: what QuerySense's `/api/query/analyze` reported for the same
query in the same run. **Every field matches in all 12 queries.**

| # | Query | MySQL `EXPLAIN` (type · key · rows · filtered · Extra) | QuerySense (accessType · keyUsed · estimatedRows · filtered · flags) | Cost | Match |
|---|---|---|---|---|---|
| 1 | `EMPLOYEE WHERE Salary > 40000` | ALL · — · 11745 · 33.33 · Using where | ALL · — · 11745 · 33.33 · full scan | 1262.75 | ✓ |
| 2 | `EMPLOYEE WHERE Ssn = '123456789'` | const · PRIMARY · 1 · 100 · — | const · PRIMARY · 1 · 100 | 1.00 | ✓ |
| 3 | `PROJECT WHERE Plocation = 'houston'` | ALL · — · 11984 · 10.00 · Using where | ALL · — · 11984 · 10.0 · full scan | 1222.65 | ✓ |
| 4 | `EMPLOYEE ORDER BY Salary DESC LIMIT 5` | ALL · — · 11745 · 100 · Using filesort | ALL · — · 11745 · 100 · filesort | 1262.75 | ✓ |
| 5 | `SELECT Dno, COUNT(*) … GROUP BY Dno` | index · fk_EMPLOYEE_1 · 11745 · 100 · Using index | index · fk_EMPLOYEE_1 · 11745 · 100 | 1262.75 | ✓ |
| 6 | `DEPARTMENT WHERE Dname = 'Research'` | const · Dname · 1 · 100 · — | const · Dname · 1 · 100 | 1.00 | ✓ |
| 7 | `EMPLOYEE e JOIN DEPARTMENT d ON e.Dno = d.Dnumber` | e: ALL · — · 11745 · 100; d: eq_ref · PRIMARY · 1 · 100 | e: ALL · — · 11745; d: eq_ref · PRIMARY · 1 | 5373.50 | ✓ |
| 8 | `PROJECT p JOIN WORKS_ON w … GROUP BY … ORDER BY SUM(w.Hours)` | w: ALL · — · 12000 · Using temporary; Using filesort; p: eq_ref · PRIMARY · 1 | w: ALL · 12000 · temporary + filesort; p: eq_ref · PRIMARY · 1 | 5424.25 | ✓ |
| 9 | `EMPLOYEE WHERE Dno = 5 OR Dno = 4` | range · fk_EMPLOYEE_1 · 9 · 100 · Using index condition | range · fk_EMPLOYEE_1 · 9 · 100 | 4.56 | ✓ |
| 10 | `EMPLOYEE e WHERE e.Salary > 40000` | ALL · — · 11745 · 33.33 · Using where | ALL · — · 11745 · 33.33 · full scan | 1262.75 | ✓ |
| 11 | `PROJECT GROUP BY Pname ORDER BY COUNT(*) DESC` | index · Pname · 11984 · 100 · Using index; Using temporary; Using filesort | index · Pname · 11984 · temporary + filesort | 1222.65 | ✓ |
| 12 | `EMPLOYEE WHERE YEAR(Bdate) > 1965` | ALL · — · 11745 · 100 · Using where | ALL · — · 11745 · 100 · full scan | 1262.75 | ✓ |

**The verdict ranks those figures without inventing any.** These are the
headlines from the same run:

| # | Verdict | Headline shown to the user |
|---|---|---|
| 1 | CRITICAL | Reads the whole EMPLOYEE table — about 11,745 rows — then discards 66.7% of what it read. |
| 2 | INFO | Uses the PRIMARY index to find rows instead of reading the whole table. |
| 4 | WARNING | MySQL has to sort roughly 11,745 rows after reading them. |
| 7 | WARNING | An index on EMPLOYEE was available, but MySQL chose not to use it. |
| 12 | INFO | Reads the whole EMPLOYEE table — about 11,745 rows — and returns most of them. *(a full scan correctly not called a fault)* |

### Before/after proof: the advisor's own index, applied by hand

QuerySense recommends and never executes, so the tester ran the `CREATE INDEX`
in MySQL by hand, re-analysed, and then dropped the index again.

| Measure | Before | After `CREATE INDEX idx_PROJECT_Plocation ON PROJECT(Plocation);` |
|---|---|---|
| Access type | ALL (full scan) | **ref** |
| Index used | none | **idx_PROJECT_Plocation** |
| Rows MySQL reads | 11,984 | **2** |
| % of read rows kept | 10% | 100% |
| Query cost (MySQL's unit) | 1222.65 | **0.70** |
| Verdict | CRITICAL — "discards 90% of what it read" | INFO — "Uses the idx_PROJECT_Plocation index…" |
| Domain suggestion | CRITICAL — full table scan | INFO — ideal lookup |
| Advisor recommendations | 1 (this index) | **0** (nothing left to recommend) |
| MySQL's raw EXPLAIN after | – | `ref · idx_PROJECT_Plocation · 2 rows · 100.00` |
| Cleanup | – | index dropped; `SHOW INDEX` shows only PRIMARY, Pname, fk_PROJECT_4 |

---

## 7. Resource levels: LOW, MEDIUM, HIGH

The same query, analysed under each simulated resource level. **D** = domain
(transactional) suggestion, **R** = resource suggestion. Each cell lists the
severities returned.

| # | Query | LOW | MEDIUM | HIGH |
|---|---|---|---|---|
| 1 | `Salary > 40000` (full scan) | D CRITICAL · R WARNING | D CRITICAL | D WARNING |
| 2 | `Ssn = …` (PK lookup) | D INFO | D INFO | D INFO |
| 3 | `Plocation = 'houston'` (full scan) | D CRITICAL · R WARNING | D CRITICAL | D WARNING |
| 4 | `ORDER BY Salary LIMIT 5` (scan + sort) | D CRITICAL ×2 · R CRITICAL · R WARNING | D CRITICAL ×2 · R WARNING | D WARNING ×2 · R INFO |
| 5 | `GROUP BY Dno` (index scan) | none | none | none |
| 6 | `Dname = 'Research'` (unique lookup) | D INFO | D INFO | D INFO |
| 7 | EMPLOYEE ⋈ DEPARTMENT | D CRITICAL · R WARNING | D CRITICAL | D WARNING |
| 8 | PROJECT ⋈ WORKS_ON, group + sort | D CRITICAL ×3 · R CRITICAL ×2 · R WARNING (6) | D CRITICAL ×3 · R WARNING ×2 (5) | D WARNING ×3 · R INFO ×2 (5) |
| 9 | `Dno = 5 OR Dno = 4` (range) | none | none | none |
| 10 | aliased full scan | D CRITICAL · R WARNING | D CRITICAL | D WARNING |
| 11 | `ORDER BY COUNT(*)` (temp + sort) | D CRITICAL ×2 · R CRITICAL ×2 | D CRITICAL ×2 · R WARNING ×2 | D WARNING ×2 · R INFO ×2 |
| 12 | `YEAR(Bdate) > 1965` | D CRITICAL · R WARNING | D CRITICAL | D WARNING |

Totals across the 12 queries:

| Level | Suggestions | CRITICAL | WARNING | INFO |
|---|---|---|---|---|
| LOW | 26 | 17 | 7 | 2 |
| MEDIUM | 19 | 12 | 5 | 2 |
| HIGH | 19 | 0 | 12 | 7 |

**The resource module rewrites its advice for each level, not just the
severity.** For the filesort in query 4:

| Level | Message (resource module) |
|---|---|
| LOW · CRITICAL | "Available memory is low and this filesort covers a significant row count — it is likely to spill to disk, which is much slower than an in-memory sort." |
| MEDIUM · WARNING | "Filesort over a significant row count. At medium available memory this is usually fine, but worth monitoring if memory pressure increases." |
| HIGH · INFO | "Filesort detected, but available memory is high — this is unlikely to cause a disk-based sort or noticeable slowdown." |

Analysis latency was 7–56 ms per call at every level.

---

## 8. Schema advisor and query rewrite

| # | Query | Advisor recommendation | Rewrite (compare) | Rows Δ · Cost Δ |
|---|---|---|---|---|
| 1 | `Salary > 40000` | INDEX — `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);` | RW1: `SELECT *` → the 10 named columns | 0 · 0.0 |
| 3 | `Plocation = 'houston'` | INDEX — `CREATE INDEX idx_PROJECT_Plocation ON PROJECT(Plocation);` | RW1 | 0 · 0.0 |
| 4 | `ORDER BY Salary LIMIT 5` | INDEX on Salary for the sort, with the "re-check after applying" caveat | RW1 | 0 · 0.0 |
| 2, 5, 6, 9 | indexed lookups | none (correct: nothing to fix) | RW1 / RW2 / none | 0 |
| 8 | group + sort by `SUM(w.Hours)` | INDEX placeholder on WORKS_ON (demoted to a review note) + STRUCTURAL review of the GROUP BY | none | – |
| 9 | `Dno = 5 OR Dno = 4` | none | RW1 + **RW2**: OR → `dno IN (5, 4)` | 0 · 0.0 |
| 10 | aliased `e.Salary > 40000` | `CREATE INDEX idx_EMPLOYEE_Salary ON EMPLOYEE(Salary);` *(was `ON e(Salary)` before fix #6)* | RW1 | 0 · 0.0 |
| 12 | `YEAR(Bdate) > 1965` | none | RW1 | 0 · 0.0 |

The rewrites changed the shape of the queries but not the rows read on this
schema: MySQL's estimate was the same before and after. The compare endpoint
says so in words ("changes the shape of the query without changing what it
reads"), so it isn't overclaiming.

---

## 9. User feedback: technical and non-technical

**Read this first.** No real users were surveyed in this session. Tables 9.1
and 9.2 are an evaluation by the tester against stated criteria, using the
exact output QuerySense produced in the final run. They aren't opinions from
participants. Table 9.3 is a ready form for collecting real feedback from
technical and non-technical participants.

### 9.1 Technical evaluation (developer or DBA view)

| Criterion | What was checked | Evidence | Result |
|---|---|---|---|
| SQL correctness | Every generated query run in MySQL; 14 compared with ground truth | 23/23 runnable; 13/14 identical result sets, the 14th the same 3 rows with fewer columns; 1 unsafe query flagged | Pass |
| Transparency | Does every decline say why? | 8/8 declines carry a specific reason naming the word or join at fault | Pass |
| EXPLAIN fidelity | QuerySense fields compared with raw MySQL `EXPLAIN` | 12/12 identical | Pass |
| Advice is actionable | Index advice applied by hand and re-measured | Rows 11,984 → 2; cost 1222.65 → 0.70; advice then disappears | Pass |
| DDL validity | Every recommended statement can run | Before fix: 4 invalid forms. After: all runnable or a clearly marked placeholder | Pass after fix #6 |
| Safety | Can an unsafe statement come back with normal confidence? | Before fix: yes, at 0.65 and cached. After: 0.20, warned, never cached | Pass after fix #5 |
| Schema fidelity | Does the uploaded copy keep the file's keys? | Before fix: 2 FKs and 2 UNIQUE lost silently. After: everything kept, 0 orphans | Pass after fixes #1–#4 |
| Latency | Per path | Rule engine 13.7 ms median; LLM 624 ms; cache 31.7 ms; analysis ≤ 56 ms | Pass |
| Reproducibility | Same answer on a second ask | 7/7 cached answers identical; LLM at temperature 0 with a fixed seed | Pass |
| Rule-engine coverage | Questions answered without a model | 16/24 (67%); the misses are vocabulary (`first name`, `born`, `female`) | Partial |

### 9.2 Non-technical evaluation (plain-language view)

| Aspect | Exact text QuerySense showed | Assessment |
|---|---|---|
| Headline verdict | "Reads the whole EMPLOYEE table — about 11,745 rows — then discards 66.7% of what it read." | Clear: a number, a comparison, no jargon |
| Good news is said plainly | "Uses the PRIMARY index to find rows instead of reading the whole table." | Clear; "PRIMARY index" is the only technical term |
| A full scan isn't blamed when it's right | "…and returns most of them. A full scan is the efficient plan when a query genuinely needs this much of the table…" | Clear and fair |
| Advisor explanation | "An index works like the index at the back of a book: it lets the database jump straight to the matching rows…" | Very clear; uses an analogy |
| Why the system was unsure | 'The question uses "female", which isn't a table, column or stored value…' | Understandable; tells the user which word to change |
| Safety warning | "This query would delete every row of 'DEPENDENT'… say which rows you mean." | Clear, and says what to do next |
| Domain and resource suggestions | "Query requires a filesort…", "…pressure on the buffer pool…", "access type 'const'" | **Jargon.** Fine for a DBA, opaque to a layperson. The verdict and advisor text are the parts written for non-technical readers |
| Mixed signals | Query 12: verdict INFO ("little an index could save") but domain suggestion CRITICAL ("Add an index… immediately") | **Confusing.** The two modules disagree on the same query (see §10) |
| Table names | Plain-English text now says "EMPLOYEE", not the alias "e" | Fixed in this session (#8) |

### 9.3 Form for collecting real feedback

Ask each participant to run three tasks on the company schema, then fill in one
row. Ratings run from 1 (strongly disagree) to 5 (strongly agree).

| Participant | Group (Technical / Non-technical) | Task done (ask a question · read the analysis · follow the advice) | "I understood what the result meant" (1–5) | "I trusted the result" (1–5) | "The explanation was in plain language" (1–5) | "I knew what to do next" (1–5) | Time taken (min) | Comments |
|---|---|---|---|---|---|---|---|---|
| P1 | | | | | | | | |
| P2 | | | | | | | | |
| P3 | | | | | | | | |
| P4 | | | | | | | | |
| P5 | | | | | | | | |
| P6 | | | | | | | | |

Suggested tasks: (1) ask "Show projects in Houston" and say who answered it and
how sure it was; (2) analyse it and explain the verdict in your own words;
(3) find the recommended fix and say what it would change.

---

## 10. Known limitations (found, not fixed)

| Limitation | Effect | Where |
|---|---|---|
| Secondary indexes in an uploaded MySQL file (`KEY …` inside `CREATE TABLE`, or a separate `CREATE INDEX`) are not recreated | The advisor may recommend an index the real database already has. `ALTER TABLE … ADD KEY` now produces a warning; the inline forms are still silent. The COMPANY schema has none, so this run wasn't affected | `SchemaProvisioner` / `DdlSchemaParser` (read in code) |
| A multi-column `UNIQUE (a, b)` is warned about, not recreated | Seeded data may contain duplicate pairs | `DdlSchemaParser` |
| Rule-engine vocabulary | "first name" ≠ `Fname`; "female", "born" are unknown words; "in works_on" is read as a value filter. Each is an honest decline that goes to the LLM | Rule engine (vocabulary) |
| The domain rule and the verdict can disagree | TX1 flags every large full scan CRITICAL (no exception, by design), while the verdict says the scan is the right plan when most rows are returned (query 12, and the full join in query 7) | `TransactionalOptimizationRules` vs `VerdictService` |
| No advice for non-sargable filters | `YEAR(Bdate) > 1965` gets no suggestion to rewrite as `Bdate >= '1966-01-01'` | Advisor / rewriter |
| A sort on an aggregate gets a placeholder index recommendation | An index can't remove a sort on `COUNT(*)`; the placeholder is demoted to a review note but still appears | `MySqlIndexAdvisor` |
| The plan table lists tables by alias | This mirrors MySQL's own EXPLAIN; the plain-English text now uses real names | Frontend plan view |
| Not tested here | MongoDB with this schema (it's MySQL DDL; the MongoDB unsafe-statement check is unit-tested only); the frontend in a real browser (unit tests and a production build only) | – |

---

## 11. Test suites

| Suite | Result |
|---|---|
| Backend `./mvnw test` | **994 passed**, 0 failed, 0 errors (972 before this session's changes, plus 22 new tests) |
| Frontend `vitest run` | **103 passed** (22 files) |
| Frontend lint (`oxlint`) | exit 0 |
| Frontend production build | succeeds |

### Files changed

| Area | Files |
|---|---|
| Upload | `schema/ddl/DdlSchemaParser.java`, `schema/upload/SchemaProvisioner.java`, `SchemaProvisioning.java`, `SchemaSeeder.java`, `SchemaUploadService.java` |
| LLM safety | `querybuilder/GeneratedQueryValidator.java`, `service/impl/QueryGenerationServiceImpl.java`, `frontend/…/workbench/SchemaWarning.jsx` |
| Advisor and analysis | `analysis/SqlStructureParser.java`, `analysis/MySqlPlanAnalyzer.java`, `analysis/VerdictService.java`, `dto/QueryAnalysisResult.java`, `advisor/MySqlIndexAdvisor.java`, `optimization/IndexStatements.java`, `optimization/TransactionalOptimizationRules.java`, `optimization/ResourceConsciousService.java` |
| Tests | 10 backend test classes |
| Docs | `RULES.md` (RS4 and the LLM safety check) |

Nothing has been committed.

---

## 12. Session log

### Runs, in order

| Run | When | What changed before it | Outcome |
|---|---|---|---|
| 1 | 19:07–19:20 | nothing (code as committed at `b6d5134`) | Found defects #1–#3 (upload) and #5 (unsafe DELETE) |
| 2 | 19:24 | fixes #1–#5 | Upload clean and 4.4 s; DELETE flagged at 0.20; found #6 and #7 in the analysis output |
| 3 | 19:30 | fixes #6–#7 | Advisor DDL valid; found #8 (alias in plain-English text) |
| Final | 19:34:07–19:34:23 | fix #8 | The run this report describes |

The backend was stopped before every test-suite run and restarted with
`./mvnw clean spring-boot:run` before every live run. It was left stopped at
the end of the session.

### Changes made to the databases

| Database | Change | Why |
|---|---|---|
| `querysense.query_history` | Deleted the rows with `schema_label = 'company.sql'` written on 2026-10-19 from 19:00: 32 rows before run 2, 30 before run 3, 30 before the final run | So every run started with an empty cache. All of them were written by this session's own runs; no other rows were touched |
| `querysense_uploads` | Rebuilt by each upload; it now holds the COMPANY tables from the final run | Normal upload behaviour |
| `querysense_uploads.PROJECT` | `idx_PROJECT_Plocation` created by hand and dropped again | The before/after proof in §6 |
| `querysense_uploads.WORKS_ON` | Two foreign keys dropped and re-added by hand | The timing measurement behind fix #4 (728 ms vs 5.7 ms). Later uploads rebuilt the table anyway |

The demo tables in `querysense` were not touched.

### Test evidence: which new tests were seen to fail first

| Tests | Run against the old code? |
|---|---|
| 6 parser tests (fixes #1, #2) | Yes. All 6 failed on the old parser and pass now |
| Seeder, provisioner and upload-service tests (fixes #3, #4) | No. They call `finish`, which didn't exist, so they couldn't compile against the old code. The live run-1 evidence in §3 shows the old behaviour |
| LLM safety, advisor alias, severity-shift and verdict tests (fixes #5–#8) | No, for the same reason (new methods). The live probes quoted in §3 are the before-evidence |

### Where the raw material is

The test script and the raw JSON results for every run are in the session's
temporary scratchpad
(`C:\Users\Lenovo\AppData\Local\Temp\claude\…\scratchpad\harness\run_company.py`
and `results_run1.json` … `results_final.json`). They are not in the
repository and will be lost when the temp folder is cleaned.

### Noticed, not changed

- `SchemaProvisioner.dropExistingTables` runs `SET FOREIGN_KEY_CHECKS = 0`
  and the `DROP TABLE` statements as separate `JdbcTemplate` calls, which can
  land on different pooled connections. It didn't fail in any run, but it
  relies on that. Fix #4 avoids the same pattern for adding foreign keys by
  using one connection.

## 13. How to reproduce

```bash
cd backend && ./mvnw clean spring-boot:run          # MySQL on 3306, Ollama running
curl -X POST http://localhost:8080/api/schema/upload -F "file=@Employee_Database_Script.sql"
curl -X POST http://localhost:8080/api/query/generate -H "Content-Type: application/json" \
     -d '{"query":"Show projects in Houston","databaseType":"MySQL"}'
curl -X POST http://localhost:8080/api/query/analyze -H "Content-Type: application/json" \
     -d '{"query":"SELECT * FROM PROJECT WHERE Plocation = '\''houston'\'';","databaseType":"MySQL","resourceLevel":"LOW"}'
```

Seeded row estimates (for example 11,745 against 11,015) vary slightly between
uploads because `ANALYZE TABLE` samples. Within one run, QuerySense and MySQL
always agree.

## Sources

- [tolgahanakgun/Elmasri-Database — Employee_Database_Script.sql](https://github.com/tolgahanakgun/Elmasri-Database/blob/master/Employee_Database_Script.sql) (the schema used)
- [UMBC company-schema.msql](https://userpages.cs.umbc.edu/finin/461/msql/company-schema.msql) (older mSQL version, no foreign keys, not used)
- R. Elmasri and S. B. Navathe, *Fundamentals of Database Systems*, COMPANY database (Figures 5.7 and 6.1)
