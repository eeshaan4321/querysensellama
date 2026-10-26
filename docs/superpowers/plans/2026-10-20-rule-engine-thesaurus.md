# Rule Engine Thesaurus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the rule engine read reworded questions on any uploaded schema — synonyms, verbs that name columns, and narrower words filtered by stored data — without ever answering a different question confidently.

**Architecture:** A one-off Python generator turns WordNet into three small TSV resources; a `Thesaurus` class loads them once. `SchemaLexicon` gains a `THESAURUS` step for tables and columns, `QueryIntentParser`'s date-column choice (E3) gains year-number columns and verb→noun (`DERIVED`) naming, and a new `NarrowerReader` turns a narrower noun into its table plus an equality filter from stored values (`NARROWER`). The curated `synonyms.json` grows to ~50 groups and stays first.

**Tech Stack:** Java 21 / Spring Boot 3 / JUnit 5 + AssertJ + Mockito; Python 3 + NLTK (generator only, never at runtime); React (one label map).

**Spec:** `docs/superpowers/specs/2026-10-20-rule-engine-thesaurus-design.md`

## Global Constraints

- A wrong confident answer is far worse than a fallback: when any check is unsure, decline (the word stays unread) — never pick.
- Offline and deterministic at runtime: no network, no model, no randomness; resources load once per JVM.
- Resources under `backend/src/main/resources/querybuilder/thesaurus/`, total under 4 MB, with `WORDNET-LICENSE.txt` beside them.
- Database-like WordNet categories (lexnames), exactly: `noun.person, noun.group, noun.artifact, noun.location, noun.act, noun.event, noun.possession, noun.communication, noun.time, noun.quantity, noun.attribute`.
- Match order, tables: exact → inflection → declared alias → curated synonym → THESAURUS → NARROWER → typo. Columns: exact → inflection/suffix/owner → declared alias → abbreviation → curated synonym → THESAURUS → typo; DERIVED applies only inside E3's date/year column choice.
- New `SchemaLexicon.MatchKind` values `THESAURUS`, `DERIVED`, `NARROWER`; all count as interpreted (0.85). Frontend labels: "dictionary synonym", "from the verb", "a kind of, from your data".
- Values never use thesaurus links; instruction words (`QueryIntentParser.instructionWords()`) never take part in any link.
- No existing test's expected output may change.
- Stop the running backend before any `./mvnw` command (both write `target/`); restart with `./mvnw clean spring-boot:run` afterwards.
- Do not commit unless the user asks; the commit steps below apply only then.
- Source comments: one-line class summaries only, no rationale or history (project convention).

## Review Focus

1. **A schema word whose most common meaning isn't the schema's** (`orders` ↔ "commands") — expect no link; pinned in Task 1 (data review) and Task 3 (`ordersNotCommands`).
2. **A narrower word whose value sits in two tables** (`Surgery` stored in both `physicians.department` and `doctors.department`) — expect a decline, not a pick; pinned in Task 6 (`narrowerInTwoTablesDeclines`).
3. **A narrower word whose table can't be joined to the named table** — expect the existing no-relationship decline; pinned in Task 6 (`narrowerUnjoinableDeclines`).
4. **A month-level date on a year column** ("students admitted last month") — expect a decline, never `admission_year >= 2026`; pinned in Task 4 (`monthOnYearColumnDeclines`).
5. **A command verb read as a derived noun** ("list", "show", "count", "delete", "order") — expect no `DERIVED` reading; pinned in Task 5 (`commandVerbsNeverDerive`).

---

### Task 1: WordNet generator, resources and `Thesaurus` loader (checkpoint)

**Files:**
- Create: `backend/tools/thesaurus/generate.py`, `backend/tools/thesaurus/README.md`
- Create (generated): `backend/src/main/resources/querybuilder/thesaurus/senses.tsv`, `parents.tsv`, `derivations.tsv`, `WORDNET-LICENSE.txt`
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/Thesaurus.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/ThesaurusTest.java`

**Interfaces:**
- Produces:
  - `senses.tsv`: `lemma \t synsetId \t share` — every noun lemma (≤3 words, lowercase, spaces) whose first database-like sense is in the allowed lexnames, with `share` = that sense's tagged count ÷ the lemma's total noun tagged counts (0–1).
  - `parents.tsv`: `synsetId \t parentId[,parentId…]` — direct hypernyms (and instance hypernyms) of every synset in `senses.tsv`, transitively up to 2 levels.
  - `derivations.tsv`: `form \t noun[,noun…]` — every verb form (base, `-s`, `-ed`, `-ing`, WordNet `verb.exc` irregulars such as `born`→`bear`) of a verb's first sense, mapped to derivationally related nouns in allowed lexnames; and every noun lemma mapped to its derivationally related nouns/adjectives (`surgeon` → `surgery,surgical`).
  - `public final class Thesaurus`:
    - `static Thesaurus builtIn()` — loads once, cached.
    - `Set<String> synonymsOf(String phrase)` — other lemmas sharing `phrase`'s synset, only when **both** shares ≥ `DOMINANT_SHARE`; excludes `instructionWords()`.
    - `boolean isKindOf(String narrower, String broader, int maxSteps)` — `narrower`'s synset reaches `broader`'s synset by ≤ `maxSteps` parent hops; both must pass `DOMINANT_SHARE`.
    - `Set<String> relatedForms(String noun)` — the noun's singular/plural plus derivations.
    - `Set<String> nounsOfVerb(String verbForm)` — derived nouns, empty for any `instructionWords()` member and for `show, list, give, find, get, display, sort, order, group`.
    - `static final double DOMINANT_SHARE` — value set in Step 4.

- [ ] **Step 1: Write `generate.py`** using `nltk.corpus.wordnet` (install into a throwaway venv: `pip install nltk` then `python -m nltk.downloader wordnet omw-1.4`). It writes the three files above, sorted, UTF-8, and copies WordNet's licence text to `WORDNET-LICENSE.txt`. Unknown tagged counts count as 0; a lemma with total count 0 gets `share` 1.0 only if it has a single noun sense, else 0.
- [ ] **Step 2: Run it** — `python backend/tools/thesaurus/generate.py backend/src/main/resources/querybuilder/thesaurus` — expect three non-empty TSVs, total under 4 MB (`du -ch …/thesaurus/*.tsv`).
- [ ] **Step 3: Write a review report** (`backend/tools/thesaurus/review.md`, regenerated by the script with `--review`) listing, for each spec example and guard pair, the two synsets, shares and whether they link: `pupil/student`, `given name/first name`, `teacher/instructor`, `doctor/physician`, `class/course`, `module/faculty`, `faculty/ability`, `section/department`, `order/command`, `surgeon` kind-of `physician`, `cardiologist` kind-of `doctor`, `admitted`→`admission`, `enrolled`→`enrollment`, `born`→`birth`, `surgeon`→`surgery`.
- [ ] **Step 4: Choose `DOMINANT_SHARE`** as the lowest value in {0.5, 0.6, 0.7, 0.8} under which every guard pair stays unlinked; record the value and the full review table in `README.md`.
- [ ] **Step 5: CHECKPOINT — stop and show the user the review table**, including every spec example that WordNet does *not* support. Continue only on their go-ahead; examples WordNet doesn't support move to the curated list (Task 2) or are dropped.
- [ ] **Step 6: Write `ThesaurusTest`** with one assertion per row of the agreed review table, e.g.:

```java
assertThat(Thesaurus.builtIn().synonymsOf("pupil")).contains("student");
assertThat(Thesaurus.builtIn().synonymsOf("class")).doesNotContain("course");
assertThat(Thesaurus.builtIn().synonymsOf("command")).doesNotContain("order");
assertThat(Thesaurus.builtIn().isKindOf("surgeon", "physician", 2)).isTrue();
assertThat(Thesaurus.builtIn().isKindOf("physician", "surgeon", 2)).isFalse();
assertThat(Thesaurus.builtIn().nounsOfVerb("admitted")).contains("admission");
assertThat(Thesaurus.builtIn().nounsOfVerb("show")).isEmpty();
assertThat(Thesaurus.builtIn().relatedForms("surgeon")).contains("surgery");
```
plus `noInstructionWordLinks`: no member of `QueryIntentParser.instructionWords()` appears in any `synonymsOf` result or as a `nounsOfVerb` key with results.
- [ ] **Step 7: Run** `./mvnw -q test -Dtest=ThesaurusTest` — expect FAIL (class missing), implement `Thesaurus`, rerun — expect PASS.
- [ ] **Step 8: Commit** (if the user has asked) — `git add backend/tools/thesaurus backend/src/main/resources/querybuilder/thesaurus backend/src/main/java/com/querysense/backend/querybuilder/Thesaurus.java backend/src/test/java/com/querysense/backend/querybuilder/ThesaurusTest.java` and `git commit -m "feat(rules): WordNet thesaurus resources and loader"`.

### Task 2: Widen the curated synonym list

**Files:**
- Modify: `backend/src/main/resources/querybuilder/synonyms.json`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SynonymListTest.java`

**Interfaces:**
- Consumes: nothing new. Produces: ~50 groups read by the existing `Synonyms.builtIn()`.

- [ ] **Step 1: Add the failing test** `domainVocabulary`:

```java
Synonyms s = Synonyms.builtIn();
assertThat(s.of("teacher")).contains("faculty", "professor", "lecturer", "instructor");
assertThat(s.of("pupil")).contains("student", "learner");
assertThat(s.of("physician")).contains("doctor");
assertThat(Synonyms.builtInGroups()).hasSizeGreaterThanOrEqualTo(45);
```
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=SynonymListTest` — expect FAIL on `domainVocabulary`.
- [ ] **Step 3: Add groups** for education, healthcare, HR, retail, library, banking, hotels, transport and events, plus any example the Task 1 checkpoint moved here. Each group is a set of words a person would accept as the *same thing* in every schema; drop any word that is a narrower or broader kind (no `surgeon` in the doctor group).
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=SynonymListTest` — expect PASS, including the existing `noInstructionWords` and `noWordInTwoGroups`.
- [ ] **Step 5: Run the whole suite** `./mvnw -q test` — expect 0 failures (a new group can create a new ambiguity in an existing test; resolve by removing the word from the group, never by editing the test).
- [ ] **Step 6: Commit** (if asked) — `git commit -m "feat(rules): curated synonyms for common schema domains"`.

### Task 3: `THESAURUS` links for tables and columns

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaLexicon.java` (enum `MatchKind`; `build`/`putName`; `column`)
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java:25` (construct the lexicon with `Thesaurus.builtIn()`)
- Modify: `frontend/src/components/analysis/RuleTracePanel.jsx` (`KIND_LABEL`: `THESAURUS`, `DERIVED`, `NARROWER`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/ThesaurusLexiconTest.java`

**Interfaces:**
- Consumes: `Thesaurus.synonymsOf(String)` (Task 1).
- Produces: `MatchKind.THESAURUS, DERIVED, NARROWER` (appended after `CHOSEN_JOIN`); constructor `SchemaLexicon(ActiveSchemaProvider, Synonyms, Thesaurus)` (keep the two-argument one delegating with `Thesaurus.builtIn()`).

- [ ] **Step 1: Write the failing tests** in `ThesaurusLexiconTest`, on a schema published like `JoinWordingTest.schema(...)`, using pairs confirmed in Task 1:
  - `pupilsReachStudents`: `parse("show all pupils")` on `students(student_id, full_name)` → `SELECT * FROM students;`, interpretation kind `THESAURUS`, `interpreted()` true.
  - `givenNameReachesFirstName`: `parse("show the given name of people")` on `people(person_id, first_name, last_name)` → `SELECT first_name FROM people;`.
  - `ordersNotCommands`: on the built-in demo (`new ActiveSchemaProvider()`), `parse("show commands")` declines.
  - `classNotCourses`: on `courses(course_id, title)`, `parse("show classes")` declines.
  - `stricterMatchWins`: a schema with both `students` and `pupils` tables → `parse("show pupils")` reads `pupils` exactly.
  - `twoTablesDecline`: two tables both reachable from one word through the thesaurus → declines with `could mean`.
  - `mongoParity`: `parse("show all pupils", Dialect.MONGODB)` on the same schema published for MongoDB → `db.students.find({})`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ThesaurusLexiconTest` — expect FAIL.
- [ ] **Step 3: Implement.** In `putName`, after the curated synonyms, add each `thesaurus.synonymsOf(name)` / `synonymsOf(singular)` form with `MatchKind.THESAURUS` (the existing best-kind pass already lets stricter kinds win). In `column`, after the curated `synonym` step, add a thesaurus step mirroring `isSynonym` (whole base phrase or one word of it) with `MatchKind.THESAURUS`. Add the three frontend labels.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=ThesaurusLexiconTest` — expect PASS; then `./mvnw -q test` — expect 0 failures.
- [ ] **Step 5: Commit** (if asked) — `git commit -m "feat(rules): dictionary synonyms reach tables and columns"`.

### Task 4: Year-number columns in E3

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`dateColumn`, and where the date range becomes predicates, ~lines 410–430)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/YearColumnTest.java`

**Interfaces:**
- Produces: a year column is any column whose type is an integer type (`INT, INTEGER, SMALLINT, TINYINT, MEDIUMINT, BIGINT, YEAR`, or Mongo `int`/`long`) and whose name's last word is `year`; `dateColumn` treats it as a candidate. When the chosen column is a year column, both bounds must fall on 1 January: `from` → `>= <year>`, `until` → `< <year>` with `QueryIntent.Literal.number(int)`; otherwise throw `DeclineException("\"<phrase>\" is finer than a year, and <table>.<column> only holds years.")`.

- [ ] **Step 1: Write the failing tests** on `students(student_id, full_name, admission_year SMALLINT)` (field types passed through the `SchemaSnapshot` constructor that takes `fieldTypes`):
  - `wholeYear`: `"students with admission year in 2024"` → `SELECT * FROM students WHERE admission_year >= 2024 AND admission_year < 2025;`.
  - `since`: `"students with admission year since 2020"` → `WHERE admission_year >= 2020;`.
  - `monthOnYearColumnDeclines`: `"students with admission year last month"` → decline reason contains `finer than a year`.
  - `dateColumnUnchanged`: an existing DATE-column question from `DatesTest` still builds identically (re-assert one).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=YearColumnTest` — expect FAIL.
- [ ] **Step 3: Implement** as specified in Interfaces.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=YearColumnTest` then `./mvnw -q test` — expect PASS, 0 failures.
- [ ] **Step 5: Commit** (if asked) — `git commit -m "feat(rules): whole-year ranges on year-number columns"`.

### Task 5: `DERIVED` — a verb names its noun's column

**Files:**
- Modify: `QueryIntentParser.java` (`dateColumn` naming)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DerivedVerbTest.java`

**Interfaces:**
- Consumes: `Thesaurus.nounsOfVerb(String)` (Task 1); year columns (Task 4); `MatchKind.DERIVED` (Task 3).
- Produces: in `dateColumn`, a column also counts as *named* when a question word `w` has a noun `n` in `nounsOfVerb(w)` such that `n` equals the column's stem — the column phrase (abbreviations expanded via `Abbreviations.builtIn().expand`) minus a trailing `date|at|on|year`. The existing exact-stem naming is tried first; a derived naming records `note(w, table.column, MatchKind.DERIVED, …)`, and `w` counts as read.

- [ ] **Step 1: Write the failing tests:**
  - `admittedInYear`: on `students(..., admission_year SMALLINT)`, `"students admitted in 2024"` → `… WHERE admission_year >= 2024 AND admission_year < 2025;`, interpretation `("admitted", "students.admission_year", DERIVED)`.
  - `enrolledLastMonth`: on `enrollments(enrollment_id, student_id, enrollment_date DATE, completed_on DATE)`, `"enrollments enrolled last month"` → filters `enrollment_date` (two date columns, so without the verb it would decline).
  - `bornAfter`: on the company-shaped `EMPLOYEE` with `Bdate DATE` and `hire_date DATE`, `"employees born after 1990"` → filters `Bdate`.
  - `commandVerbsNeverDerive`: on a table with columns `listing_date DATE, showing_date DATE, order_date DATE`, each of `"list rows last week"`, `"show rows last week"`, `"rows ordered by order date"` produces no `DERIVED` interpretation.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DerivedVerbTest` — expect FAIL.
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=DerivedVerbTest` then `./mvnw -q test` — PASS, 0 failures.
- [ ] **Step 5: Commit** (if asked) — `git commit -m "feat(rules): a verb names its noun's date column"`.

### Task 6: `NARROWER` — a narrower word becomes its table plus a filter

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/NarrowerReader.java`
- Modify: `QueryIntentParser.java` (constructor wiring; table binding before the "Nothing in the question matches a table" decline; predicate added to `where`)
- Modify: `backend/src/test/java/com/querysense/backend/querybuilder/TestParsers.java` only if the constructor signature changes
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/NarrowerWordTest.java`

**Interfaces:**
- Consumes: `Thesaurus.isKindOf`, `Thesaurus.relatedForms` (Task 1); `MatchKind.NARROWER` (Task 3); `SchemaMetadata.getDistinctValues(table, column)`.
- Produces:

```java
record Narrowing(String word, KnownSchema.Table table, String column, String stored) {}
Optional<Narrowing> read(String text, List<KnownSchema.Table> named, List<KnownSchema.Table> all,
                         SchemaMetadata metadata, Set<String> readWords)
```
  For each unread word (not in `readWords`, not an instruction/filler word, not a table word), try candidate tables (`named` if any, else `all`) where `isKindOf(singular(word), tableWord, 2)` for the table name's head word. Within each such table collect `(column, stored)` where a stored value equals, ignoring case, any of `relatedForms(singular(word))`. Return a `Narrowing` only when exactly **one** `(table, column)` pair exists across all candidates; otherwise empty. The parser adds the table (if not named) with a `TABLE` binding on `word`, adds `Predicate(ColumnRef(table, column), EQ, List.of(Literal.text(word, stored)))`, and notes `(word, table.column + " = '" + stored + "'", NARROWER)`.

- [ ] **Step 1: Write the failing tests** on a hospital schema `physicians(physician_id, full_name, department)`, `patients(patient_id, full_name, physician_id → physicians)`, `wards(ward_id, name)`, with mocked values `physicians.department` = `["Surgery", "Cardiology", "Pediatrics"]`:
  - `surgeons`: `"show surgeons"` → `SELECT * FROM physicians WHERE department = 'Surgery';`, interpretation kind `NARROWER`.
  - `namedTableToo`: `"physicians who are surgeons"` → same SQL.
  - `withJoin`: `"patients of surgeons"` → joins `patients`→`physicians` and filters `physicians.department = 'Surgery'`.
  - `noValueDeclines`: values without a surgery form → declines.
  - `twoColumnsDecline`: add `specialty` also holding `Surgery` → declines.
  - `partialValueDeclines`: only `General Surgery` stored → declines.
  - `narrowerInTwoTablesDeclines`: add a `doctors(doctor_id, full_name, department)` table whose `department` also holds `Surgery` (a surgeon is a kind of both) → declines.
  - `narrowerUnjoinableDeclines`: `"wards with surgeons"` (no key between wards and physicians) → the existing `declares no relationship` decline.
  - `broaderNeverLinks`: `"show people"` on this schema → declines.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=NarrowerWordTest` — expect FAIL.
- [ ] **Step 3: Implement** `NarrowerReader` and the parser wiring as in Interfaces. The parser runs it only after normal table binding, so any stricter reading wins.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=NarrowerWordTest` then `./mvnw -q test` — PASS, 0 failures.
- [ ] **Step 5: Commit** (if asked) — `git commit -m "feat(rules): a narrower word reads as its table filtered by stored data"`.

### Task 7: Acceptance across four schemas, and RULES.md

**Files:**
- Create: `backend/src/test/java/com/querysense/backend/querybuilder/RewordingAcceptanceTest.java`
- Modify: `RULES.md` (match-kinds table; E3 year columns and `DERIVED`; new section for `NARROWER`; E4/E5 order)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write `RewordingAcceptanceTest`** as one `@ParameterizedTest` over rows `(schema, question, expected SQL or "DECLINE")`: at least six rows per schema — built-in shop demo, campus-shaped (`JoinWordingTest`'s tables plus `admission_year SMALLINT`), company-shaped (`JoinWordingTest`'s tables plus `Bdate DATE`), hospital (Task 6's) — covering curated synonyms, `THESAURUS`, `DERIVED`, year columns and `NARROWER`, and every guard from the spec's Acceptance list that earlier tasks don't already assert. Add MongoDB rows for one `DERIVED` and one `NARROWER` question on the hospital and campus schemas, so both dialects are pinned.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=RewordingAcceptanceTest` — expect PASS (if a row fails, fix the owning task's code, not the row, unless the Task 1 checkpoint dropped that example).
- [ ] **Step 3: Update `RULES.md`**: the three new kinds in the match-kinds table (0.85, shown under "Read as"); the order from Global Constraints; a year-column paragraph and a `DERIVED` paragraph under E3; a new `E31 · Narrower word plus a filter` section; the curated domains under Vocabulary. The decline table count stays at 39.
- [ ] **Step 4: Run the full suites** — `./mvnw test` (expect `BUILD SUCCESS`, 0 failures) and `npx vitest run` in `frontend` (expect all pass).
- [ ] **Step 5: Restart the backend** — `./mvnw clean spring-boot:run` — and tell the user to re-upload their schema before trying it.
- [ ] **Step 6: Commit** (if asked) — `git commit -m "test(rules): rewording acceptance across four schemas; docs"`.
