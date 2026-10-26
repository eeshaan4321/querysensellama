# QuerySense — Rules

Every rule QuerySense applies, one at a time: what it does, why it exists,
and an example.

Read from `backend/src/main` on 2026-09-11. Examples use the built-in demo
schema and were traced by hand from the code, not run. SQL shown is exactly
what the code writes, including lowercase values, because the question is
lowercased before it is read.

## How many rules

| Module | Rules | IDs | Source |
|---|---|---|---|
| **Rule engine** (question → SQL) | **31** | E1–E31 | `querybuilder/` |
| Routing between the three layers | 3 | R1–R3 | `QueryGenerationServiceImpl`, `GeneratedQueryValidator` |
| Checks before EXPLAIN | 2 | P1–P2 | `analysis/SqlPreflightValidator`, `analysis/MongoPreflightValidator` (P2 only) |
| EXPLAIN warnings | 4 | W1–W4 | `analysis/MySqlExplainAnalyzer`, `analysis/MongoExplainParser` (W1, W3, W4) |
| MongoDB checks | 2 | M1–M2 | `analysis/MongoExplainAnalyzer` |
| Analysis verdict | 9 | V1–V9 | `analysis/VerdictService` |
| Domain optimisation | 6 | TX1–TX6 | `optimization/TransactionalOptimizationRules` |
| Resource optimisation | 4 | RS1–RS4 | `optimization/ResourceConsciousService` |
| Schema advisor | 3 | SA1–SA3 | `advisor/MySqlIndexAdvisor`, `advisor/MongoIndexAdvisor` |
| Query rewrite | 4 | RW1–RW4 (RW4 MongoDB only) | `optimization/QueryRewriter`, `optimization/MongoQueryRewriter` |
| **Total** | **68** | | |

**The rule engine has 31 rules.** Of those, 46 are conditions under which it
refuses a question and hands it to the language model. The other 37 rules
belong to the modules that analyse and optimise a query after it has been
generated. None of the 68 uses a model.

**Renumbered 2026-10-02 (MongoDB parity, step 9).** The four MongoDB-only
IDs folded on 2026-10-01 are gone: the old TX6 lives in TX1, the old RS4 in
RS3, and the old SA4 and SA5 in SA1. The old TX7 is now TX6, and the old RS5
is now RS4. No ID appears in an API response except the RW IDs, which did
not change.

Read again on 2026-10-20, when the rule engine gained a dictionary thesaurus,
verb-to-noun column naming, whole-year ranges on integer year columns and
narrower words (E31). The count of declines rose from 39 to 44 because the
year-column decline (one, in E3) and the narrower-word declines (four, in
E31) are new decline reasons. The final review of that feature added two
more E31 reasons, a negation and a check that something is missing, which
brings the count to 46.

Read again on 2026-09-21, when section 10 was added, and again on
2026-09-26, when the rule engine became dialect-aware, gained a MongoDB
emitter (E2) and a decline for a second unbound noun (E23).

Read again on 2026-10-01, when MongoDB analysis became a real `explain()`
reader (MongoDB parity, step 5). Every rule from section 3 on now has a
MySQL variant and a MongoDB variant. Four MongoDB-only rules were folded
into others then, and their IDs were retired on 2026-10-02.

---

## 1. The rule engine — 31 rules

The rule engine is layer 1 of query generation. It turns a plain-English
question into SQL without a model, or it declines, and the question moves
on to the cache and then the local LLM.

Two principles run through every rule:

- **Match against the active schema, never a word list.** Tables and
  columns are matched against whichever schema is loaded right now: the
  built-in demo or an uploaded one. Uploading a schema therefore changes what
  the engine is willing to answer. Dates (E3) are read by fixed rules, then
  placed on a date column the active schema actually has.
- **When unsure, decline.** A declined question still gets answered, by the
  LLM, at a lower confidence. A wrong query labelled `RULE_ENGINE` at 0.95
  looks certain and isn't. So every rule that can't resolve what it read
  declines instead of guessing, and every decline carries a plain-English
  reason that the UI shows in the rule trace.

The rules run in the order below. The first decline stops the build.

### Gates

#### E1 · Empty question
- **What it does:** declines a blank question straight away.
- **Why:** there's nothing to match.
- **Declines with:** "The question was empty."

#### E2 · Dialect
- **What it does:** resolves `databaseType` to a `Dialect` (`MYSQL` or
  `MONGODB`, `Dialect.from`) before any matching is attempted, and passes it
  down to every rule that follows. **Updated 2026-09-26 — no longer a
  MongoDB catch-all.** Rules E4 onward now bind against whichever schema the
  resolved dialect has active, and at E21 the result is written out by a
  dialect-specific emitter (`SqlEmitter` or `MongoEmitter`), so a MongoDB
  question is built, not deferred.
- **Why:** SQL and MongoDB need different tables, different columns and a
  different output writer; resolving the dialect once, at the gate, is what
  lets every rule after it read `dialect` rather than assume SQL. What
  doesn't belong here is a request the engine can't even address — that's
  not a business decline, it's a malformed request.
- **Declines with:** nothing, once the dialect resolves. An unrecognised
  `databaseType` throws in `Dialect.from` and is caught by
  `GlobalExceptionHandler` as a **400** naming the value it couldn't
  resolve — a request error, not a rule-trace decline, and it never reaches
  the LLM.

#### E3 · Dates
- **Replaced 2026-10-03 (sub-project 4).** Until then this rule declined
  every question that mentioned a period of time. It now reads the first
  date expression (`DateReader`) as a half-open range of days, `from`
  inclusive and `until` exclusive, and filters a date column with `>=` and
  `<` (MongoDB: `$gte`/`$lt` on `ISODate(...)` at local midnight).
- **Today** is the server's date in `querysense.rules.time-zone` (default
  `Asia/Kolkata`); `querysense.rules.today=2026-10-02` pins it for tests and
  demos. Weeks start on Monday.
- **What it reads** (today = Friday 2026-10-02):

  | Expression | Range |
  |---|---|
  | `today` / `yesterday` | [10-02, 10-03) / [10-01, 10-02) |
  | `this / last / previous / past / next week` | [09-28, 10-05) / [09-21, 09-28) / … / [10-05, 10-12) |
  | `this / last / next month` | [10-01, 11-01) / [09-01, 10-01) / [11-01, 12-01) |
  | `this / last quarter`, `this / last year` | [10-01, 2027-01-01) / [07-01, 10-01); [2026-01-01, 2027-01-01) / [2025-01-01, 2026-01-01) |
  | `last 7 days`, `past three weeks`, `last 2 months` | [today − N units, tomorrow) |
  | `in 2025`, `in march 2025`, `in q1 2025`, `in the first quarter of 2025` | the whole year, month or quarter |
  | `in january` (no year) | its most recent start: [2026-01-01, 2026-02-01); `in november` is 2025 |
  | `on 1 march 2025`, `on march 1, 2025`, `on 2025-03-01`, `on 1st march 2025` | that one day |
  | `since X`, `from X onwards` / `before X` / `after X` / `until X`, `up to X` | [start of X, —) / [—, start of X) / [end of X, —) / [—, end of X) |
  | `between X and Y`, `from X to Y` | [start of X, end of Y) |

  An impossible date (`february 30`) reads as nothing.
- **Which column:**
  1. the one the question names, by name (`order date`) or by its stem
     with `-ed`/`-d` (`hired since 2020` → `hire_date`, `received last week`
     → `received_date`), searched across the join chain (E6);
  2. otherwise the subject's only date-typed column; if it has none, the
     chain's only one.

  Date types come from the schema: MySQL `DATE`, `DATETIME`, `TIMESTAMP`;
  MongoDB `date`. The built-in MySQL demo now carries its column types.
- **Year columns, added 2026-10-20.** An integer column whose name ends in
  `year` (`admission_year SMALLINT`; MongoDB `int`) counts as a date column
  only when its stored values can be read and every one is a whole number
  from 1000 to 2999. A `year` column holding 1 to 4 (a year of study), or one
  whose values can't be read, is not a year column, and a year in the
  question then declines as before. A year column counts for **whole-year**
  ranges: `in 2024` → `admission_year >= 2024 AND
  admission_year < 2025`, `since 2020` → `>= 2020`, `before 2024` → `< 2024`
  (MongoDB: numeric `$gte`/`$lt`, no `ISODate`). A range that does not start
  and end on a year boundary (`last month`) declines with `"last month" is
  finer than a year, and students.admission_year only holds years.` When a
  table has both a date column and a year column and none is named, the usual
  several-columns decline applies. The words of the range never name a year
  column (`orders last year` does not pick a column called `year`).
- **A verb names its column, added 2026-10-20 (`DERIVED`).** A verb in the
  question is turned into the noun formed from it, and the noun is matched
  against **column names only**: `admitted in 2024` → `admission_year`,
  `enrolled last month` → `enrollment_date`, `born after 1990` → `Bdate`. It
  uses the verb's first meaning, from WordNet plus a hand-written pair
  (`born` → `birth`), never reaches a table or a
  value, and never applies to a word the parser reads as an instruction
  (`show`, `list`, `count`, `delete`, `order`). If the noun reaches two
  columns, the ambiguity decline applies. Recorded as `DERIVED`, 0.85, and
  shown as `"admitted" → students.admission_year`.
- **The words are consumed:** `in` of `in january` is never read again as
  a value filter (E14), and `hired` is not a requested column (E10).
- **Declines when:**
  - a time word is present but the expression can't be read whole
    (`orders recently` → `The question mentions a time period ("recently")
    this engine can't read as dates, so it went to the language model
    instead.`);
  - no table in the question has a date column;
  - several date columns fit and none is named (`"last week" could be
    checked against shipments.sent_date or shipments.received_date; name one
    in the question.`).
- **Confidence:** stays 0.95. A date is read by fixed rules, not
  interpreted.
- **Example:** `orders from last month` →
  `SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date <
  '2026-10-01';`. `customers who may have churned` → not a date.

### Matching the question to the schema

#### E4 · Table matching
- **What it does:** finds every table of the active schema named in the
  question:
  - underscores and spaces count as the same, so `order items` matches
    `order_items`;
  - the plural is tried before the singular;
  - a match sitting inside a longer match is dropped;
  - tables keep the order they appear in the sentence, and **the first
    one named is the subject** of the query.
- **Why:** the previous engine returned the first table it found. `show all
  order items` matched `orders` on the word "order", and `order_items` could
  never match at all, because nobody types an underscore. Letting the
  longest match win fixes both.
- **Declines when:** no table matches, or more than four do. Since
  2026-10-03 (sub-project 3) the engine joins up to four tables (E6); until
  then it joined two at most.
- **Example:** `show all order items` → `order_items`. `customers, orders
  and payments` → three tables, joined along their declared keys (E6).
- **Vocabulary, added 2026-10-03 (sub-project 1):** a table is also reached
  by:
  - an alias the schema declares (`aka:` in a comment or description, or an
    `aliases` upload block);
  - a word from the built-in synonym list (`clients` → `customers`);
  - an `-es`/`-ies` plural or an irregular one (`people` → `person`);
  - only when no table matched any other way, a one-edit typo of a word of
    five or more letters (`custmers` → `customers`).

  - **Since 2026-10-20:** a word from the dictionary (WordNet) thesaurus,
    after the curated list and before a typo: `cars` → `autos`. See "Matching
    words to the schema".

  A word that reaches two tables declines with `"<word>" could mean A or B,
  so this engine won't pick one.` The longest-match and first-named rules
  above apply to every kind.

#### E5 · Column matching
- **What it does:** turns a word or phrase into a column of a given table,
  trying three things in order:
  1. exact name, reading spaces as underscores (`order date` → `order_date`);
  2. the singular, with a trailing `s` removed (`emails` → `email`);
  3. a suffix match (`date` → `order_date`), but **only if exactly one
     column ends that way**.
- **Why:** people don't type identifiers. If a word matches two columns
  equally well, the engine doesn't pick one: on a table with `first_name`
  and `last_name`, a request for `name` is ambiguous, and choosing either
  would be a guess that looks certain.
- **Declines when:** this rule never declines on its own. When it comes back
  empty, the rule that asked (E9–E15, E19) declines.
- **MongoDB, added 2026-09-26:** the same matcher (`SchemaBinder.bindColumn`)
  also matches `_id` for the word "id", the **last segment of a dotted
  path** (`items.product_id` matches "product id", for an embedded array
  field), and a **camelCase column name** against its snake_case reading
  (`orderId` matches "order id") — all by snake-casing the column before
  comparing, so the SQL-side matching above needs no change to also serve
  Mongo's field-naming conventions.
- **Vocabulary, added 2026-10-03 (sub-project 1):** after those, a column is
  tried in this order: exact → inflection (plurals, irregulars) → declared
  alias → built-in synonym → one-edit typo (five or more letters, never on a
  word that is itself a name, an alias or a synonym). Each looser kind is
  tried only when the stricter ones found nothing, and binds only when
  exactly one column fits. Two or more decline, naming them:
  `"telephone" could mean suppliers.phone or suppliers.mobile, so this
  engine won't pick one.`

#### Matching words to the schema (match kinds)

Added 2026-10-03. Every word that reaches a table or column does so through
one of these kinds, strictest first:

| Kind | Example | Confidence | Shown under "Read as" |
|---|---|---|---|
| `EXACT` | `customers` | 0.95 | no |
| `INFLECTION` | `customer`, `people` | 0.95 | no |
| `SCHEMA_ALIAS` | `buyers`, declared by the schema | 0.95 | yes |
| `SYNONYM` | `clients`, from `querybuilder/synonyms.json` | **0.85** | yes |
| `THESAURUS` | `cars` → `autos`, `given name` → `first_name` (dictionary synonym) | **0.85** | yes |
| `DERIVED` | `admitted` → `admission_year` (from the verb) | **0.85** | yes |
| `NARROWER` | `surgeons` → `physicians.department = 'Surgery'` (a kind of, from your data) | **0.85** | yes |
| `TYPO` | `custmers`, `emial` | **0.85** | yes |
| `ABBREVIATION` | `first name` → `Fname` | **0.85** | yes |
| `CHOSEN_JOIN` | `head` → `departments.head_faculty_id` (E6) | **0.85** | yes |

- A declared alias counts as exact, because the schema's owner declared it.
  A built-in synonym or a typo correction is QuerySense's own reading, so a
  query built through one reports 0.85.
- Every non-exact reading is listed under "Read as" in the rule trace and
  in the response's `interpretations` field.
- Values never use synonyms: `bombay` does not find `Mumbai`.
- The synonym list holds no word the parser reads as an instruction
  (`count`, `total`, `top` …); `SynonymListTest` enforces it.
- **Order, since 2026-10-20.** Tables: exact → inflection → declared alias →
  curated synonym → WordNet → narrower word plus a filter (E31) → typo.
  Columns: exact → inflection/suffix/owner → declared alias → abbreviation
  → curated synonym → WordNet → typo. Verb to noun (`DERIVED`) is not a
  general column step: it applies only inside E3's choice of a date or year
  column. A
  stricter match on any item always beats a looser one on another, and a word
  that reaches two items still declines.
- **The thesaurus (`THESAURUS`, 2026-10-20).** `querybuilder/thesaurus/` holds
  a small precomputed extract of WordNet (licence notice alongside). Two words
  link only when the first meaning of each, counting only database-like
  categories (people, groups, artifacts, places, acts, events, possessions,
  communication, time, quantities, attributes), is the same WordNet synset,
  and that meaning carries a clear majority of the word's usage. So `pupil` and
  `student`, `given name` and `first name`, `doctor` and `physician` link;
  `class`/`course`, `module`/`faculty`, `faculty`/`ability`,
  `section`/`department` and `order`/`command` do not. Instruction words never
  link. It works offline, gives the same answer every time, and reaches a whole
  column name or one word of a multi-word column. A hand-written deny list
  (`thesaurus/deny.json`) unlinks pairs a person judged loose, both ways
  round: `task`/`project`, `undertaking`/`project`, `hour`/`hr`, `sexual
  activity`/`sex`, `last`/`close`, `title`/`statute title`, `ctc`/
  `counterterrorist center`.
- **Curated domains, 2026-10-20.** `synonyms.json` grew from 12 groups to
  57, one person-reviewed group at a time, covering retail, education
  (`teacher, faculty, instructor`; `student, pupil, learner`), healthcare
  (`doctor, physician`; `medication, medicine, drug`), HR (`candidate,
  applicant`; `manager, supervisor`), banking (`invoice, bill`; `transaction,
  txn`), hotels (`reservation, booking`), transport (`car, automobile`;
  `driver, chauffeur`) and events (`attendee, participant`). No word is in two
  groups and none is an instruction word.
- **Since 2026-10-20:**
  - A synonym can reach **one word of a multi-word column**: `salary` →
    `offers.ctc_lpa` through the group `salary, ctc, pay, wage,
    compensation`. Before, synonyms only reached one-word columns.
  - An **abbreviated column name** is reached by the words it stands for,
    from `querybuilder/abbreviations.json`: `first name` → `Fname`,
    `birth date` → `Bdate`, `department number` → `Dno`. This is tried
    after aliases and before synonyms, and binds only when exactly one
    column fits.

#### E6 · Joins come from declared foreign keys, along the shortest chain
- **What it does:** when two or more tables are named, it follows the
  **shortest chain of declared foreign keys** from the subject (the first
  table named) to each other named table, at most three keys long, and writes
  one `JOIN … ON` per key. Tables the chain passes through are joined too and
  named in the trace ("through orders"). The whole chain may hold at most
  four tables. Two directly linked tables give exactly the join they always
  did. Foreign keys that point back at their own table
  (`employees.manager_id → employees.id`) are ignored.
- **Since 2026-10-03 (sub-project 3):**
  - **Columns and values across the chain.** A column phrase binds in the
    subject first, then in the other chain tables in order; a stored value
    is searched the same way. One fitting table qualifies it; two decline,
    naming both (`"name" could mean products.name or categories.name, so this
    engine won't pick one.`).
  - **Each subject row once.** When a filter sits on a joined table and only
    the subject's rows are asked for, the result is `SELECT DISTINCT
    subject.*`, and a count is `COUNT(DISTINCT subject.<key>)`. MongoDB
    filters each joined collection right after its `$lookup` and then keeps
    one document per subject.
  - **Phrases that name a table are not columns.** `orders of customers` is
    not a request for a column called "orders", and `products ordered by
    customers` is not a sort.
  - An anti-join (E27) still needs one direct key.
- **Why:** the `ON` clause is the easiest part of a join to get wrong and the
  hardest to spot. Reading it from the schema's own constraints, never
  guessing from a name like `client_id`, is what makes a rule-built join
  checkable. The trace names the exact key used. A self-join is ignored
  because "employees and their managers" and "managers and their reports"
  need opposite sides of the key, and nothing in the wording says which one
  is meant.
- **Declines when:**
  - there is **no** chain within four tables (`The schema declares no
    relationship between …` for two tables, `… no chain of relationships
    linking … within four tables` for more);
  - the chain would need **more than four** tables;
  - two chains are **equally short** and the wording doesn't settle it (see
    below): two keys between the same pair (`orders.billing_address_id` and
    `orders.shipping_address_id` both point at `addresses`, so "orders with
    addresses" has two right answers), or two different routes of the same
    length (`There are two equally short ways to connect … (through faculty
    (faculty.dept_id, students.advisor_id), or …)` — each route names its
    keys, so two routes through the same table read differently).
- **Choosing among equals by wording (2026-10-20):** a key's **role** is
  what its column name says beyond the table it points at: `head` in
  `departments.head_faculty_id → faculty.faculty_id`, `shipping` in
  `shipping_address_id`, `manager` in `Mgr_ssn`. A key whose name says
  nothing more (`faculty.dept_id → departments`, `EMPLOYEE.Dno →
  DEPARTMENT.Dnumber`) has no role. Among equally short chains:
  1. a chain is dropped if it uses a key whose role the question doesn't
     name;
  2. of the rest, the one naming the most roles is used, if there is
     exactly one.

  So `faculty in the mathematics department` uses `faculty.dept_id` (the
  question doesn't say "head"), `departments with their head faculty` uses
  `head_faculty_id`, and `departments and students` goes through `programs`
  rather than through a faculty advisor. `orders with addresses` still
  declines; `orders with their shipping addresses` builds. The choice is
  listed under "Read as" (`CHOSEN_JOIN`) and the query reports 0.85.
- **A role names a table (2026-10-20):** a word in the question that spells
  the role of a key on a named table also reaches the table that key points
  at, joined on that key. `list each department and their department heads`
  → `departments JOIN faculty ON departments.head_faculty_id =
  faculty.faculty_id`; `departments and their managers` → `DEPARTMENT JOIN
  EMPLOYEE ON DEPARTMENT.Mgr_ssn = EMPLOYEE.Ssn`. It does not apply when
  another column of the same table carries the role word (`shipping` with
  both `shipping_address_id` and `shipping_date`), or when one word spells
  roles leading to two tables.
- **Example:** `which customers placed more than three orders` → `customers
  JOIN orders ON customers.id = orders.customer_id`. `customers and products`
  → `customers JOIN orders … JOIN order_items … JOIN products …` (until
  2026-10-03 this declined, because there is no direct key).
- **Documented difference:** in the MongoDB demo, orders embed
  `items.product_id`, so `customers and suppliers` is a four-collection chain
  and builds, while MySQL needs `order_items` too (five tables) and declines.

#### E23 · A second unbound noun declines, naming it
- **Added 2026-09-26.** Runs here, right after table binding and before the
  subject table is fixed — it is numbered 23, after the rules below it,
  because it was added last; the order it runs in is where it sits in this
  document, not its number.
- **What it does:** looks for `and <word>` or `plus <word>` anywhere in the
  question (`CONJOINED_NOUN`). If `<word>` doesn't name a table, a column of
  any named table, a known non-column/stopword, or a value a column actually
  holds — and it ends in `s` (has the shape of a plural table reference) —
  the question declines instead of quietly building against only the first
  table.
- **Why:** before this rule, `customers and suppliers` on a schema with no
  `suppliers` table built `SELECT * FROM customers;` at 0.95, never
  mentioning "suppliers" anywhere in the trace — a confident answer to a
  question the engine only half read. See "Known gaps" below.
- **Declines with:** `"suppliers" isn't a table in the active schema, so
  this engine can't tell what the question joins customers to.` — naming the
  exact unbound word.
- **Example:** `customers and suppliers` (no `suppliers` table) → declined,
  naming "suppliers". `customers and orders` → still joins normally, since
  `orders` binds. `customers and their orders` → still builds; "their"
  doesn't end in `s` and is a stopword regardless.

### Working out what is being asked

#### E7 · Verb
- **What it does:** `delete` or `remove` as a whole word **opening** the
  question — optionally after `please` — makes it a `DELETE`; anything else is
  a `SELECT`. `SELECT` needs no verb at all, so `customers in mumbai` works.
- **Why:** questions are overwhelmingly reads, so reading is the default. The
  verb has to lead because a `DELETE` is the highest-stakes thing the engine
  can write, and a word buried mid-sentence is not a command.
- **Declines when:** a delete would span two joined tables, or the whole word
  `delete`/`remove` stands anywhere other than the opening — `customers in
  mumbai i want to delete` could mean either a read or a write, and the engine
  will not pick one.
- **Example:** `delete customers in mumbai` →
  `DELETE FROM customers WHERE city = 'mumbai';`. `show removed customers in
  mumbai` → a plain `SELECT`, because `removed` is not the verb `remove`.

#### E8 · Count
- **What it does:** `count`, `how many`, `total number` or `number of` produces
  `COUNT(*)`.
- **Example:** `how many orders are there` → `SELECT COUNT(*) FROM orders;`

#### E9 · Explicit column request
- **What it does:** handles `only X`, `just X` and `X of <table>`. It splits
  the phrase on commas and `and`, and drops filler words (`show`, `get`,
  `the`, `all` …). Then it matches each piece to a column (E5). A multi-word
  piece that doesn't match as a whole is tried again on its last word.
  `X of` isn't used if the question also asks for an average, sum, max or
  min, because "average price of products" is an aggregate (E11).
- **Why:** if someone names the columns they want, dropping one and
  returning `SELECT *` is wrong in a way nobody would notice.
- **Declines when:** any requested field is not a column of the subject
  table.
- **Example:** `show only the name and email of customers` →
  `SELECT name, email FROM customers;`. `show only the age of customers` →
  declined.

#### E10 · Implied column request
- **What it does:** reads a column phrase placed straight after the table
  word, with no `only` or `of`: `customer city`. It only runs when E8 and
  E9 didn't, and only if the phrase:
  - runs to the end of the sentence;
  - is at most three words;
  - contains no preposition, verb or table name.
- **Why:** people ask for "customer city" as often as for "the city of
  customers". The limits keep `customers in mumbai` a filter (E14)
  instead of a failed column request.
- **Declines when:** the phrase clearly is a column request but matches no
  column, or matches two equally well.
- **Example:** `customer city` → `SELECT city FROM customers;`

#### E11 · Aggregate
- **What it does:** turns these words plus a column into an SQL function:
  - `average` / `avg` / `mean` → `AVG`
  - `sum` → `SUM`
  - `maximum` / `max` / `highest` → `MAX`
  - `minimum` / `min` / `lowest` → `MIN`

  It is skipped when E9 or E10 already chose columns.
- **Why:** `total` is deliberately *not* read as `SUM`. It's a common column
  name (`invoices.total`), and reading it as `SUM` would quietly turn a
  column request into an aggregate.
- **Declines when:** the column isn't one of the subject table's.
- **Example:** `average price of products` → `SELECT AVG(price) FROM products;`

#### E12 · Breakdown ("per")
- **What it does:** `per`, `for each`, `grouped by` or `group by` + a column. The
  column goes into the output, so the query is grouped by it.
- **Why:** the previous engine ignored `per city` and answered a question
  that wasn't asked.
- **Declines when:** the column isn't one of the subject table's.
- **Example:** `how many customers per city` →
  `SELECT city, COUNT(*) FROM customers GROUP BY city;`

#### E13 · Comparison
- **What it does:** reads `<column> [is] more / greater / larger / higher
  than, above or over N` as `>`, and the less-than forms, `below` and
  `under` as `<`. `N` is read by `NumberReader` (since 2026-10-03): digits
  with thousands separators (`1,000`), decimals (`4.5`), words up to the
  millions (`two hundred and fifty`) and `a dozen`.
  - If the column matches, it becomes a `WHERE` condition.
  - If it doesn't match **and** two tables are joined, it becomes a count of
    the related table: `HAVING COUNT(<related table's first column>)`. The
    output then shows the subject table's key column, plus its `name`,
    `title` or `label` column if it has one.
- **Why:** in "customers who **placed** more than three orders", the word
  in front of the comparison is a verb, not a column. With a join present,
  a count of the other table is the only thing it can mean.
- **Declines when:** `N` isn't a number the engine can read (`fifty-ish`),
  or the column doesn't match and there is no join.
- **Example:** `products with price above 500` →
  `SELECT * FROM products WHERE price > 500;`
  `which customers placed more than three orders` →
  `SELECT customers.name, customers.id, COUNT(orders.id) FROM customers JOIN
  orders ON customers.id = orders.customer_id GROUP BY customers.name,
  customers.id HAVING COUNT(orders.id) > 3;` (corrected 2026-10-03: this
  example used to show `customers.id, customers.name` without the count,
  which is not what the code writes).
- **Every comparison is read, since 2026-10-03 (sub-project 2).** Until then
  only the first comparison was used, so `products with price below 100 or
  price above 900` silently built `price < 100`. Now each comparison adds a
  condition, joined by the words between them (E29). A second comparison that
  would also need the count of the related table declines: `The question
  compares two counts, which this engine won't combine.` The operators gain
  `at least`, `no less than` (`>=`) and `at most`, `no more than`, `up to`
  (`<=`); see E25.
- **A column only the joined table has, since 2026-10-03 (sub-project 3).**
  Until then, with two tables, a comparison on a column found only in the
  related table (`orders with payments amount over 100`) built a `HAVING
  COUNT(…)` on the related table, which counts rows rather than comparing the
  column. It now filters that column: `WHERE payments.amount > 100`. A word
  that is no column of any chain table keeps the count reading, counting the
  last table named.

#### E14 · Filter checked against the data
- **What it does:** takes the word after `from`, `in`, `at`, `located in`,
  `living in`, `based in` or `residing in`, and any further words joined to it
  by `and`. Stopwords (`the`, `all`, `which` …), table names and column names
  are skipped. For anything else, the engine looks for the subject-table
  column whose **actual stored values** contain that word (ignoring case). One
  value writes `<column> = '<word>'`; several write `<column> IN ('a', 'b')`.
  A word after `and` that is not a value ends the list.
- **Why:** the previous engine assumed any location meant `city` and then
  checked `customers.city`. On a schema without that column it wrote the
  wrong column, and its own safety check silently stopped working. Asking
  the data which column holds the value works on any schema.
- **Since 2026-10-03:**
  - a value is a run of up to four words, longest first, so `new delhi`
    matches one stored value;
  - the run stops at punctuation, so `clients in mumbai?` keeps its filter;
  - when no run matches, a single word of five or more letters one edit
    from exactly one stored value is corrected, and the stored spelling is
    written (`mumbia` → `'Mumbai'`, recorded as a `TYPO`);
  - a value stored in more than one column of the subject table declines.
    Until then the engine silently took the first column, a guess at 0.95.
- **Declines when:** the first value matches no column of the subject table,
  two values match different columns, or one value is stored in two columns
  (`"Central" is stored in both stores.city and stores.region, so this
  engine can't tell which one the question means.`).
- **Example:** `show customers in mumbai` →
  `SELECT * FROM customers WHERE city = 'mumbai';`. `customers in mumbai and
  pune` → `… WHERE city IN ('mumbai', 'pune');`. `customers in atlantis` →
  declined.

#### E15 · `with <column> <value>`
- **What it does:** reads an explicit column/value pair. It only runs when
  E13 and E14 produced no `WHERE`, and comparison phrasing (`with more than
  three orders`) is excluded before the column is looked up. Numbers are left
  unquoted; anything else is quoted. Since 2026-10-03 the value is read by
  the same `NumberReader` as E13, so `with amount twenty five` writes
  `amount = 25`.
- **Why:** it covers questions that name both the column and the value.
- **Declines when:** the named column is not a column of the subject table.
- **Example:** `orders with status pending` →
  `SELECT * FROM orders WHERE status = 'pending';`
- **Beside other filters, since 2026-10-03 (sub-project 2).** Until then this
  rule only ran when nothing else had produced a `WHERE`, so `customers in
  mumbai with name asha` silently dropped the name. Now a `with` filter is
  added whenever its words are not already part of another condition (a
  comparison such as `with price at least 500` stays one condition).

#### E16 · Choosing what to select
- **What it does:** decides the output columns from what the earlier rules
  found, taking the first that applies:
  1. the breakdown column (E12) goes first, if there is one;
  2. then the aggregate (E11);
  3. else the key + label + `COUNT` needed by E13's `HAVING`;
  4. else `COUNT(*)` (E8);
  5. else the requested columns (E9/E10);
  6. else `*`.

  When two tables are joined, every column is prefixed with its table name.
- **Why:** one fixed order gives the same output shape for the same kind
  of question every time.

### Question shapes (sub-project 2, added 2026-10-03)

Each shape is written by both emitters, so it builds in MySQL and MongoDB
alike.

#### E24 · Ranking and superlatives
- **What it does:** orders and limits by a column in three ways:
  - `top N <table> by <column>` / `bottom N … by …` → `ORDER BY column
    DESC/ASC LIMIT N`;
  - `the N highest/lowest <column>` → the same, with `N` as the limit;
  - a superlative adjective from `querybuilder/adjectives.json`
    (`cheapest`, `largest`, `most expensive`, `least costly` …), whose entry
    names the column words it can reach. `least` reverses the direction.

  With no number, a singular subject (`cheapest product`) gets `LIMIT 1`; a
  plural one is only sorted.
- **Why:** "the cheapest product" is one of the most common questions, and
  until now it went to the language model.
- **Kept as before:** `highest price of products` with no number still
  builds `MAX(price)` (E11).
- **Declines when:** `top … by` names something that isn't a column; an
  adjective's words reach two columns (`"cheapest" could mean listings.price
  or listings.cost …`); or none.
- **Interpretation:** an adjective binding is recorded as a `SYNONYM`, so the
  query reports 0.85.
- **Recency, added 2026-10-03 (sub-project 4):** `latest`, `newest`, `most
  recent` sort by the date column (chosen as in E3) descending; `oldest`,
  `earliest` ascending. The same limit rules apply, and confidence stays
  0.95. `latest 5 orders` → `ORDER BY order_date DESC LIMIT 5`.
- **Example:** `top 5 products by price` →
  `SELECT * FROM products ORDER BY price DESC LIMIT 5;`. `cheapest product`
  → `… ORDER BY price ASC LIMIT 1;`

#### E25 · Inclusive bounds and between
- **What it does:** `at least` / `no less than` → `>=`; `at most` / `no more
  than` / `up to` → `<=`; `<column> between A and B` → `BETWEEN A AND B`
  (MongoDB `$gte`/`$lte`). Both ends are read by `NumberReader`, the first
  one whole before the separating `and`, so `between two hundred and fifty
  and 500` reads 250 to 500.
- **Declines when:** the ends aren't both numbers, or the column isn't one of
  the subject table's.
- **Example:** `products with price between 100 and 500` →
  `SELECT * FROM products WHERE price BETWEEN 100 AND 500;`

#### E26 · Not-equal and exclusion
- **What it does:**
  - `<column> [is] not <value>` → `!=`, the value checked within that
    column;
  - `other than`, `excluding`, `except`, `not in`, `not from` + values →
    `!=` for one value, `NOT IN` for several, the column found from the
    stored data exactly as E14 finds it (runs of words, value typos, a value
    in two columns declines).

  The excluded words are consumed, so the `in` of `not in pune` is never
  read again as an equality.
- **Declines when:** no stored value follows.
- **Example:** `customers excluding pune and delhi` →
  `SELECT * FROM customers WHERE city NOT IN ('pune', 'delhi');`

#### E27 · Without (anti-join)
- **What it does:** `without`, `with no`, `who have no`, `that have no`,
  `having no`, `with zero` + a table, or `never <verb>ed` whose stem names a
  table, builds a `LEFT JOIN … WHERE <key> IS NULL` (MongoDB: `$lookup`, then
  `$match` on an empty array). The join key comes from a declared foreign key
  exactly as in E6.
- **Declines when:** no second table is named; there is no key or more than
  one (E6's messages); or the question also compares a count of the related
  table.
- **Example:** `customers without orders` →
  `SELECT customers.* FROM customers LEFT JOIN orders ON customers.id =
  orders.customer_id WHERE orders.customer_id IS NULL;`

#### E28 · Distinct
- **What it does:** `distinct/unique/different <column> of …` and `which/what
  <column> do/does/are/have …` select the distinct values of that column
  (MongoDB: a `$group` on it). When the word after `which` names a table
  (`which customers are in mumbai`), the question is read as before.
- **Example:** `which cities do customers live in` →
  `SELECT DISTINCT city FROM customers;`

#### E29 · OR across conditions
- **What it does:** every condition records the stretch of the question it
  came from. The words between consecutive conditions decide how they join:
  `or` → `(a OR b)` (MongoDB `$or`), otherwise `AND`. Inside a value list,
  `or` separates values like `and`, so `customers in mumbai or pune` is an
  `IN`.
- **Declines when:** both `and` and `or` join conditions (`… so this engine
  can't tell how they group.`), or `or` is combined with `without`.
- **Example:** `customers in mumbai or with name asha` →
  `SELECT * FROM customers WHERE (city = 'mumbai' OR name = 'asha');`

#### E30 · Several totals
- **What it does:** two or more aggregate words joined by `and` or commas,
  sharing one column, give one output column each. `count` in the list means
  `COUNT(*)`. A single aggregate keeps E11's path.
- **Example:** `count and average amount of payments per method` →
  `SELECT method, COUNT(*), AVG(amount) FROM payments GROUP BY method;`

#### E31 · Narrower word plus a filter
- **Added 2026-10-20.** A word naming a *kind of* what a table holds
  (`surgeons` on `physicians`) is read as that table filtered to the kind. The
  filter is what makes the answer right: the rows returned are exactly the
  ones asked about.
- **All three must hold, or the word stays unread and the engine declines as
  before:**
  1. **Kind of.** In WordNet the word's first database-like meaning is a kind
     of the table's, at most two steps up (`surgeon` is a kind of
     `physician`). Only class links count: an instance (`paris` of `city`)
     is never a kind, and a pair in the deny list never links.
  2. **The data names it.** Exactly one (table, column) stores a value equal,
     ignoring case, to the word or a related form of it (`surgeon`,
     `Surgery`, `Surgical`), looked up from the stored data as in E14.
  3. **Exact.** A whole stored value only: `General Surgery` does not match
     `surgeons`; two columns holding a match decline.

  Only category-like columns are searched. A column whose name words, after
  abbreviations are expanded (`Lname` → `last name`), include `name`,
  `surname`, `title`, `email`, `address`, `phone`, `code` or `id` is never
  searched, so `show cooks` never filters on a surname `Cook`. Nor is a
  column whose share of distinct values, when the database reports it, is
  above 0.5.
- **It reaches the table** whether or not the question also names it (`show
  surgeons`, `physicians who are surgeons`), joins to a named table along
  declared keys (`patients of surgeons`), and combines with a filter on a
  different column. The narrowed table takes the place of its word in the
  question, so the first thing named stays the subject: `surgeons with
  patients` lists physicians, as `physicians with patients` does. Recorded
  as `NARROWER`, 0.85; "Read as" shows `"surgeons"
  → physicians.department = 'Surgery'`.
- **MongoDB:** the same reading gives `db.physicians.find({"department":
  "Surgery"})`.
- **Declines when:**
  - the question joins its conditions with `or` (`show surgeons with salary
    over 100 or salary under 5`);
  - the question is a delete (`delete surgeons`): a narrowing never chooses
    rows to delete;
  - the question also filters the narrowed column another way (`show surgeons
    in cardiology`);
  - the question holds a second narrower word (`show surgeons and
    cardiologists`);
  - the question holds a negation or exclusion word (`not`, `no`, `non`,
    `never`, any word ending in `n't`, `except`, `excluding`, `other than`,
    `without`, `but`): `physicians who are not cardiologists` declines rather
    than read as cardiologists;
  - the question checks that something is missing (E27's `without`, `with
    no`, `with zero`, `never …ed`): `surgeons with zero patients`.
- **Never read:** a broader word (`people` → `patients`), which no filter
  could make correct.
- **Example:** `show surgeons` → `SELECT * FROM physicians WHERE department =
  'Surgery';`

### Safety checks

#### E17 · No unconditional delete
- **What it does:** refuses to write a `DELETE` with no `WHERE`.
- **Why:** a delete without a condition empties the table. The engine never
  writes one, even when the question literally asks for it. (QuerySense
  never runs generated SQL anyway, but it shouldn't produce this.)
- **Example:** `delete all customers` → refused.
- **Refused, not declined (2026-10-26):** a declined question goes on to the
  cache and the language model; a refused one stops here. The response
  carries no query, the trace outcome `REFUSED` with the reason, confidence
  0, and nothing is saved to history. Before this, the model was asked
  anyway and wrote `DELETE FROM customers;`, which the output check then
  flagged — a second of work to put a destructive statement on screen.

#### E17a · No dropping or emptying a table
- **What it does:** a question that opens with `drop` or `truncate`
  (optionally after `please`) is refused, whether or not it names a known
  table, in both dialects.
- **Why:** the engine never writes `DROP` or `TRUNCATE`, and neither should
  the model it would otherwise hand the question to.
- **Not refused:** `drop` anywhere but the opening — `show the drop rate of
  orders` reads as an ordinary question.
- **Example:** `drop table orders` → refused.

#### E18 · Columns must exist right now
- **What it does:** checks every matched column against the live database's
  column list before writing the SQL. If that list can't be fetched, the
  check is skipped rather than failing the query.
- **Why:** the active schema is a description, and the database can drift
  from it. An unreachable lookup shouldn't block a valid query.
- **Declines when:** a column is in the active schema but not in the
  database.

### Ordering and output

#### E19 · Order by
- **What it does:** `order by`, `ordered by`, `sort by` or `sorted by` + a
  column, optionally followed by `asc`, `ascending`, `desc` or `descending`.
  The default is ascending.
- **Declines when:** the sort column isn't one of the subject table's.
- **Example:** `show products sorted by price descending` →
  `SELECT * FROM products ORDER BY price DESC;`

#### E20 · Limit
- **What it does:** `top N`, `first N` or `limit N` produces `LIMIT N`. Since
  2026-10-03 `N` is read by `NumberReader`, so `top twenty customers` gives
  `LIMIT 20`. `top` with no number builds no limit, as before.
- **Declines when:** `N` isn't a whole, positive number of rows.
- **Example:** `top 5 products sorted by price descending` →
  `SELECT * FROM products ORDER BY price DESC LIMIT 5;`

#### E21 · Writing the SQL
- **What it does:** writes the clauses in a fixed order: `SELECT` / `DELETE` →
  `FROM` → `JOIN … ON` → `WHERE` (conditions joined by `AND`) → `GROUP BY` →
  `HAVING` → `ORDER BY` → `LIMIT` → `;`.
  **`GROUP BY` is never read from the question.** It's derived as every
  selected column that isn't aggregated.
- **Why:** MySQL's `ONLY_FULL_GROUP_BY` mode (on by default) rejects any
  selected column that is neither grouped nor aggregated. Grouping by exactly
  the non-aggregated columns always passes. This rule is what makes a bare
  `COUNT(*)` produce no `GROUP BY` at all. It also knows no English, so its
  output can be tested character for character.

#### E22 · Rule trace
- **What it does:** attaches a trace to every result, built or declined:
  - which phrase matched which table or column;
  - the query's shape (e.g. `SELECT + JOIN + GROUP BY + HAVING`);
  - the foreign key a join used;
  - the checks it passed;
  - or the reason it declined.
- **Why:** `handledBy` says which layer answered, never why an earlier
  layer passed. Without the trace, "rule-based and explainable" is a claim;
  with it, it can be checked. The Analysis screen shows it.

### The 46 ways the rule engine declines

| Group | Count | Rules |
|---|---|---|
| Gates | 3 | E1 empty · E2 dialect · E3 an unreadable time word |
| Tables and joins | 5 | E4 no table · E4 more than four tables · E6 no chain within four tables · E6 two equally short chains · E6 a chain needing more than four tables |
| A second noun binds to nothing | 1 | E23 |
| Delete across a join | 1 | E7 |
| A named thing matches no column | 7 | E9 · E10 · E11 · E12 · E13 · E14 · E19 |
| Comparison value isn't a number | 1 | E13 |
| Safety | 2 | E17 unconditional delete · E18 column missing from the database |
| A word reaches two schema items (synonym or typo) | 1 | E4 · E5 |
| A value is stored in two columns | 1 | E14 |
| A limit isn't a whole number | 1 | E20 |
| Two comparisons both need a count | 1 | E13 |
| Ranking: by a non-column, or an adjective reaching two columns or none | 3 | E24 |
| A range whose ends aren't two numbers, or of a non-column | 2 | E25 |
| An exclusion with no stored value after it, by value or by column | 2 | E26 |
| Without: no second table, or combined with a count | 2 | E27 |
| `and` and `or` mixed between conditions, or `or` with `without` | 2 | E29 |
| A column, or a stored value, on two chain tables | 2 | E6 |
| A date with no date column, several date columns and none named, or a range finer than a year on a year column | 3 | E3 |
| A narrower word (E31) combined with `or`, with a delete, with another filter on its column, with a second narrower word, with a negation, or with a check that something is missing | 6 | E31 |

---

## 2. Routing between the three layers — 3 rules

#### R1 · Layer order and confidence
- **What it does:** tries three layers in order. The confidence is a fixed
  label per layer, not a calculated score.

  | Layer | `handledBy` | Confidence |
  |---|---|---|
  | Rule engine | `RULE_ENGINE` | 0.95 |
  | Cache | `CACHE` | 1.00 |
  | Local LLM | `OLLAMA` | 0.65, or 0.40 if R3 fires |
- **Why:** the free, instant, explainable path runs first. The model only
  runs when both of the others miss.

#### R2 · Cache key
- **What it does:** a cached answer is reused only when both the question
  **and** the target database match, ignoring case.
- **Why:** the same sentence asked for MySQL and for MongoDB needs two
  different queries. Matching on the question alone once returned the wrong
  language.

#### R3 · LLM incompleteness check
- **What it does:** compares the question with the SQL the model wrote.
  There are two signals:
  1. the question names more than one table, but the SQL doesn't reference
     all of them;
  2. the question has a second-condition word (`with`, `who have`, `who
     placed`, `who has`, `and`) **and** a number or comparison (`more than`,
     `at least`, `over`, …), but the SQL has no `JOIN`, `HAVING`, `GROUP BY`
     or `AND`.

  Either signal lowers the confidence from 0.65 to 0.40. The query is still
  returned.
- **Why:** a small local model sometimes drops half of a two-part question.
  "Customers in mumbai with more than 3 orders" once came back as just the
  city filter. A lower label is honest; rejecting the query would hide the
  answer.
- **MongoDB (2026-10-01):** one signal. The question names two collections
  joined by a declared reference, but the query uses neither a `$lookup`
  nor a path into its collection's embedded arrays → 0.40. "Orders with
  their customers" answered with `db.orders.find({})` fires;
  `db.orders.find({"items.product_id": …})` for "orders and their products"
  doesn't.

#### Grounding and the off-schema check (MySQL since 2026-09-21, MongoDB 2026-10-01)
- **Prompt:** the model is given the active schema. For MongoDB that is the
  active Mongo schema: each collection's dotted field paths with their BSON
  types, enum values, embedded arrays, and the references with the `$lookup`
  each implies; every collection when the question names none. It is asked
  for exactly one mongosh statement in `find`, `aggregate`, `countDocuments`
  or `deleteMany`, and its reply is cut to that statement.
- **Check:** before an LLM answer is used, it is checked against the active
  schema. For MongoDB:
  - unreadable output, an unknown collection or field (including `$lookup`'s
    fields and the first `$group`'s references), or an `$out`/`$merge` stage
    → confidence 0.20 with a `schemaWarning` naming the problem;
  - such an answer is not saved, so it is never served from the cache;
  - a cached MongoDB answer is rechecked the same way before it is served,
    so a change of active schema retires it.
- **Unsafe statement (2026-10-19):** an LLM answer that would touch every row
  is treated the same way, before the schema check: a `DELETE` or `UPDATE` with
  no `WHERE`, a `DROP` or `TRUNCATE`; for MongoDB `deleteMany({})`,
  `remove({})`, `updateMany({}, …)` or `drop()`. It gets confidence 0.20 and a
  `schemaWarning` saying so, is never saved, and a cached copy is not served.
  The rule engine already refuses these (E-rules); this stops the model
  handing back what the rule engine declined.
- **Unrelated join (MySQL, 2026-10-20):** when the active schema declares
  foreign keys, an LLM `JOIN … ON a.x = b.y` must match one of them (either
  direction), or join two columns of the same name. Otherwise it is
  reported like an unknown column — confidence 0.20, a `schemaWarning`
  naming both columns, never saved or served from the cache. It was added
  after the model answered "each department with its average student
  admission year" with `departments.dept_id = students.program_id`.

---

## 3. Checks before EXPLAIN — 2 rules

Both read the SQL text before MySQL sees it. Either one stops the query with
a specific message instead of a raw database error.

#### P1 · Strict GROUP BY
- **Blocks when:** a query with `GROUP BY` selects a column that is neither
  grouped nor inside a function. MySQL would reject it as error 1055.
- **Why:** the LLM wrote exactly this more than once. Catching it here
  gives a message the user can act on.
- **Example:** `SELECT city, name FROM customers GROUP BY city;` → blocked:
  `name` isn't grouped or aggregated.

#### P2 · Column exists
- **Blocks when:** a column in `SELECT`, or on the left of a `WHERE`
  comparison, isn't on the table. MySQL would reject it as error 1054.
  Skipped when the column list can't be fetched.
- **Why:** the LLM once invented an `age` column even when told the real
  columns. This check is the safety net behind that prompt.
- **Example:** `SELECT age FROM customers;` → blocked.
- **MongoDB (2026-10-01):** the collection, and every field path in the
  filter, projection, sort and pipeline, must exist in the active Mongo
  schema. Checking stops at the first stage that reshapes documents
  (`$group`, `$project`, `$lookup`, `$unwind`, …). A `$lookup`'s `from`,
  `localField` and `foreignField` are always checked. Same wording: "Field
  'age' does not exist on 'customers' — …". A pipeline containing `$out` or
  `$merge` is refused here as well: QuerySense never runs a write.

---

## 4. EXPLAIN warnings — 4 rules

Applied to each table in MySQL's execution plan. The same table never gets
the same warning twice. The wording reports what MySQL *chose* and never
claims the choice was wrong; recommendations belong to the schema advisor.

#### W1 · Full table scan
- **Fires when:** MySQL reads the table from start to end.
- **Says:** a full scan happened; if the query is slow, check whether an
  index on the filtered or joined columns would help.
- **MongoDB:** a `COLLSCAN` stage, or a `$lookup` joining without an index —
  "Collection scan detected on '<collection>'".

#### W2 · No index used
- **Fires when:** the table used no index but *wasn't* a full scan (so W1
  didn't already cover it).
- **Says:** which access type MySQL used, and which indexes it considered.
- **Why:** it's per table. In a join where one side uses an index, the
  other side still gets reported correctly. The old version reported "no
  index is being used" for the whole query.
- **MongoDB:** no variant. Every Mongo access stage either scans the
  collection or uses an index.

#### W3 · Temporary table
- **Fires when:** MySQL built a temporary table (`Using temporary`), usually
  for `GROUP BY` or `DISTINCT`.
- **MongoDB:** a grouping stage reported `usedDisk: true` — "The grouping
  stage on '<collection>' spilled to disk".

#### W4 · Filesort
- **Fires when:** MySQL had to sort the rows after reading them
  (`Using filesort`).
- **Says:** an index matching the `ORDER BY` columns may reduce the sorting.
- **MongoDB:** a blocking `SORT` stage (or a `$sort` stage MongoDB didn't push
  into the query) — "In-memory sort used for '<collection>'".

---

## 5. MongoDB checks — 2 rules

> **Replaced 2026-10-01 (MongoDB parity, step 5).** MongoDB queries are now
> planned by the real `mongod` with `explain` at `executionStats`, and these
> two checks read the parsed query (`MongoQuery`) and the collection's real
> `listIndexes()` output. The hardcoded `_id, email, userId` list is gone.
> The query is never run: only `explain` and `listIndexes` are sent.

#### M1 · Unindexed filter
- **Fires when:** an equality or range field in the filter (or an
  aggregate's leading `$match`) is the leading key of no index on the
  collection, according to `listIndexes()`.
- **Says:** names the field. It warns on its own only when the plan did
  *not* already scan the collection (W1 covers that case). Either way it
  sets the field the advisor indexes.

#### M2 · Unanchored regex
- **Fires when:** a `$regex` value (or a regex literal) in the filter doesn't
  start with `^`. Read from the parsed query, not the text.
- **Why:** MongoDB can only use an index for a regex anchored to the start of
  the value. This behaviour doesn't vary between MongoDB versions, which is
  what makes it safe as a fixed rule.

---

## 6. Analysis verdict — 9 rules

The one plain-English sentence at the top of the Analysis screen. The rules
are tried in order and the first match becomes the headline.

- **Large** means at least `querysense.verdict.large-table-rows` rows
  (default **1000**).
- **Most rows discarded** means MySQL's `filtered` figure is under 50%.

The verdict calculates nothing new. It only ranks and words figures already
on the screen, so the headline can't contradict the numbers below it.

**MongoDB (2026-10-01):** V1–V9 run unchanged on MongoDB's mapped figures
(`totalDocsExamined` → rows read, `nReturned` → rows returned, `COLLSCAN` →
full scan, a blocking `SORT` → sort, a spilled group → temporary). Only the
words change: rows → documents, table → collection, MySQL → MongoDB, a sort
happens "in memory", a temporary table is "the grouping spilled to disk".
MongoDB reports no cost figure, so none is shown. Where MongoDB reports no
per-collection discard figure (count, delete, pipelines run in its
slot-based engine), V3 can't fire and V4 reports the scan without claiming
waste.

#### V1 · EXPLAIN never ran → `UNKNOWN`
"MySQL couldn't produce an execution plan for this query." It's never shown
as a pass: "we couldn't look" and "we looked and found nothing" are opposite
claims.

#### V2 · The plan reads no table → `INFO`
"MySQL can answer this query without reading any table." For example, a
`WHERE` that can never be true.

#### V3 · Large scan, most rows discarded → `CRITICAL`
"Reads the whole *table* table — about *N* rows — then discards *X%* of what
it read." This is the one clearly wasteful case.

#### V4 · Large scan, discard rate not reported → `WARNING`
"Reads the whole *table* table — about *N* rows." It reports the scan but
claims no waste, because without `filtered` any waste figure would be
invented.

#### V5 · An index was available and not used → `WARNING`
"An index on *table* was available, but MySQL chose not to use it." MySQL
listed a usable index (`possible_keys`) and used none. This is the most
actionable finding, and before this rule the screen couldn't tell it apart
from "no index exists".

#### V6 · Sorting or grouping many rows → `WARNING`
"MySQL has to sort (or assemble) roughly *N* rows after reading them." Fires on
a filesort or temporary table over a large row count.

#### V7 · Large scan that returns most of the table → `INFO`
"Reads the whole *table* table — about *N* rows — and returns most of them."
**This is deliberately not a problem.** A query that needs most of the table
has nothing to gain from an index, so reading the table directly *is* the
cheaper plan. `SELECT * FROM customers` must not be graded as a fault.

#### V8 · Small scan → `INFO`
"Reads all *N* rows of *table*, which is normal at this size." Reading every
row of a small table costs almost nothing, and MySQL will choose that even
when an index exists.

#### V9 · Anything else → `INFO`
"Uses the *key* index to find rows instead of reading the whole table."
The rows were found through an index.

---

## 7. Domain optimisation — 6 rules

One rule set, run on every analysis: the transactional one. "Large" means
**above 100 rows**.

Until 2026-09-11 the Workbench had a domain picker choosing between three
sets — e-commerce (EC1–EC6), analytical (AN1–AN5) and transactional.
Schemas are now uploaded directly, so no workload is ever declared; the
picker, and the two sets only it could reach, were removed. Transactional
was already what a missing domain, or `Other`, used, so a run that never
picked a domain behaves as before.

The section keeps the word because the code does: these suggestions still
arrive tagged `source: "DOMAIN"`, through a class still called
`DomainOptimizationService`. Those are names left standing so the response
contract does not move — no rule below reads a domain, and nothing asks for
one. See PROJECT.md §9.7 for the full list of what kept the name.

### Transactional — 6 rules (large = above 100 rows)

Short, frequent, time-critical lookups. Whatever a query does, it does again
on every call, so this set has the lowest threshold and the harshest
severities.

- **TX1 · Full table scan** — `CRITICAL` above 100 rows, otherwise `WARNING`.
  There's no "small table" exception, because the scan repeats on every call.
- **TX2 · Filesort** — `CRITICAL`. A key lookup shouldn't need a sort.
- **TX3 · Temporary table** — `CRITICAL`. A `GROUP BY` or `DISTINCT` suggests
  this query may not be transactional at all.
- **TX4 · No index used, not a full scan** — `WARNING`. It catches what TX1
  can't, such as a small join with no key on one side.
- **TX5 · `const` or `ref` access through an index** — `INFO`. This is the
  ideal shape for a lookup.
- **TX6 · MongoDB: unanchored regex** (TX7 until 2026-10-02) — `CRITICAL`.
  A regex scan doesn't belong on a hot path.

**MongoDB variants (2026-10-01).** TX1 fires on a `COLLSCAN` ("Collection
scan on …"). `explain` reports that scan directly, where the old
MongoDB-only unindexed-filter rule could only guess at it. TX2 fires on an
in-memory sort, TX3 on a grouping that spilled to disk, TX4 when no index was used and nothing was scanned, TX5 on a
single-document lookup (`EXPRESS_IXSCAN`, `EXPRESS_IDHACK`, `IDHACK`).

**`suggestedChange` (2026-10-01).** A suggestion whose fix is an index now
carries the statement: TX1 the index on the filtered column (for MongoDB, the
equality → sort → range compound), TX2 the index on the sort column (for
MongoDB, equality then sort). It is left out when the column isn't known.
QuerySense never runs it.

---

## 8. Resource optimisation — 4 rules

Resource levels are simulated categories (low / medium / high), not live
measurements of the machine. No level given means **medium**. "Significant"
means **above 5,000 rows**.

#### RS1 · Filesort above 5,000 rows
Low → `CRITICAL` (likely to spill to disk) · medium → `WARNING` · high →
`INFO`. A sort that fits in memory is cheap; one that spills to disk is much
slower.

#### RS2 · Temporary table (any size)
Low → `CRITICAL` · medium → `WARNING` · high → `INFO`. A temporary table uses
working memory directly, so the reasoning is the same as RS1.

#### RS3 · Full scan above 5,000 rows, low resources
`WARNING`. A large scan pushes other data out of memory, which slows down
*other* queries too, not just this one.

**MongoDB variants (2026-10-01).** RS1 fires on an in-memory sort over more
than 5,000 documents, RS2 on a grouping that spilled to disk, RS3 on a
collection scan over more than 5,000 documents under low memory. That takes
over from the old MongoDB-only unindexed-filter rule. RS1 and RS3 carry the same `suggestedChange` index statements as TX2 and TX1.

#### RS4 · Severity shift (RS5 until 2026-10-02)
Moves every **domain** suggestion (section 7) one step: up under low, unchanged
under medium, down under high, never below `INFO` or above `CRITICAL`. So the
same finding reads as `CRITICAL` on a small machine and `INFO` on a large one.
The resource module's own suggestions (RS1–RS3) aren't shifted. An `INFO`
suggestion is not moved up (since 2026-10-19): the only one is TX5, which says
the query is already ideal, and low memory doesn't make that a warning.

---

## 9. Schema advisor — 3 rules

Each recommendation appears only when the specific problem it fixes is
present. For example, an index is never suggested while one is already doing
that job. QuerySense shows the DDL but **never runs it**. Applying it is the
user's decision, in their own database.

#### SA1 · Index for a full scan
- **Fires when:** a full table scan **and** no index used.
- **Recommends:** `CREATE INDEX idx_<table>_<column> ON <table>(<column>);`
  on the filtered column. If no filtered column is known, it leaves a
  placeholder.

#### SA2 · Index for a sort
- **Fires when:** filesort.
- **Recommends:** an index on the `ORDER BY` column, with a caveat. If the
  query also filters on a different column, a single index may not remove the
  sort: MySQL uses one index per table here, so a composite `(filter, sort)`
  index may be needed. It says to check again with EXPLAIN. This caveat was
  proven necessary live: a single-column sort index did *not* remove the sort
  in testing.

#### SA3 · Review grouping
- **Fires when:** temporary table.
- **Recommends:** a structural review of the `GROUP BY` / `DISTINCT`.
  There's deliberately no ready-made statement, because no single index
  reliably fixes this.

#### MongoDB variants (2026-10-01)
- **SA1:** a `COLLSCAN` with no index in use → one compound
  `db.<c>.createIndex({...})`, ordered equality → sort → range (SA4 until
  2026-10-02). It absorbs the sort, so MongoDB's reconciliation happens
  inside SA1.
- **SA1, regex case (SA5 until 2026-10-02):** an unanchored regex →
  structural advice to anchor it, or a text index on that field.
- **SA2:** an in-memory sort when SA1 didn't fire → an index on the
  equality fields, then the sort fields.
- **SA3:** a `$group` with no `$match` on an indexed field before it →
  flagged for review, with no statement.

#### Reconciliation — added 2026-09-21
The rules above each read a single signal and cannot see what the others
emitted, so SA1 and SA2 could propose two separate single-column indexes on
one table. MySQL uses one index per table access, so running both gives
neither rule what it wanted — the composite SA2's caveat already described.
After the rules run, two index proposals on the same table are now merged into
one composite `CREATE INDEX`, **filtered column first**, naming both signals
in its reason. The same column proposed twice collapses to a single statement.
Structural flags and lone index proposals are untouched. This reconciles the
one pair known to contradict; it is not general consistency checking.

---

## 10. Query rewrite — 4 rules

Added 2026-09-21, behind `POST /api/query/compare`. These are the only rules
in QuerySense that produce a **new query** rather than advice about one.

**Every rule here preserves the rows the query returns.** This is the whole
design constraint. A rewrite that returns different rows is a different
question, not a faster answer to the same one — so a rule that cannot
guarantee an identical result set declines rather than fires. The practical
consequence: there is no "add a LIMIT" rule, even though capping an unbounded
scan is the most obvious cheap win available, because it changes the answer.

MySQL has RW1–RW3, for SELECT only. MongoDB has RW1–RW4 (added 2026-10-01,
`MongoQueryRewriter`). An unparseable query, a write statement in SQL, or a
table or collection that isn't in the active schema comes back exactly as it
arrived.

#### RW1 · Name the columns
- **Fires when:** `SELECT *`, one table, no join, and the table is in the
  active schema.
- **Rewrites to:** the schema's column list, in declaration order.
- **Why:** an index covering those columns can answer the query without
  reading the row. Naming them changes nothing about the result.
- **Example:** `SELECT * FROM customers WHERE city = 'mumbai';` →
  `SELECT id, name, email, city, phone FROM customers WHERE city = 'mumbai';`
- **Declines on a join**, where `*` spans more than one table and the
  expansion would have to guess at column ordering and duplicate names.
  **Declines on a table it does not know** — the case that would otherwise
  invent columns, which is the same failure the LLM guard exists for.
- **MongoDB:** a `find` with no projection gets one naming the collection's
  declared top-level fields (`_id` is returned by default). It declines when
  the filter or sort names an undeclared field, and on pipelines, where
  MongoDB already fetches only the fields the pipeline uses. The "identical
  documents" guarantee rests on the declared schema: QuerySense's databases
  are provisioned and seeded from it, so they hold no other fields.
  `db.orders.find({"status": "PAID"})` →
  `db.orders.find({"status": "PAID"}, {"customer_id": 1, "employee_id": 1, "order_date": 1, "status": 1, "items": 1})`

#### RW2 · Collapse an OR chain into IN
- **Fires when:** the `WHERE` clause is an `OR` chain of equality tests
  against a single column, two or more values.
- **Rewrites to:** `column IN (v1, v2, …)`.
- **Why:** MySQL can range-scan an index for `IN`; a chain of `OR`s on the
  same column often falls back to a scan. The predicate is identical.
- **Example:** `WHERE city = 'mumbai' OR city = 'pune' OR city = 'delhi'` →
  `WHERE city IN ('mumbai', 'pune', 'delhi')`
- **Declines across two columns** — `city = 'mumbai' OR name = 'asha'` is not
  one predicate. **Declines on a single equality**, because a one-element
  `IN` is noise. It walks the `OR` tree and bails on anything that is not
  `column = constant`, so an `AND` nested inside, or a column-to-column
  comparison, stops the rule rather than being quietly dropped — the same
  "keep every condition or decline" principle as E14.
- **MongoDB:** an `$or` whose branches are all exact matches on one field
  (plain values or `$eq`) becomes `{field: {$in: [...]}}`, in a `find`,
  `countDocuments`, `deleteMany` filter or any `$match`. Declines across two
  fields, on operators, regexes, arrays or embedded documents, on a single
  branch, and when the filter already names the field outside the `$or`.

#### RW3 · Drop a sort that cannot sort anything
- **Fires when:** `ORDER BY` is present on a bare aggregate
  (`COUNT`/`SUM`/`AVG`/`MIN`/`MAX`) with no `GROUP BY` and no `LIMIT`.
- **Rewrites to:** the same query without the `ORDER BY`.
- **Why:** the query returns exactly one row. The sort cannot change the
  result; it only costs a filesort.
- **Example:** `SELECT COUNT(*) FROM customers ORDER BY name;` →
  `SELECT COUNT(*) FROM customers;`
- **Keeps `ORDER BY` when a `GROUP BY` is present**, where it orders the
  groups and is load-bearing, and on any non-aggregate query, where sorting
  is the point.
- **MongoDB:** a `find`'s `.sort()` is dropped when its filter matches `_id`,
  or the sole key of a unique index declared on the collection, exactly. At
  most one document can match, so the sort can't change anything; it only
  cost an in-memory sort. A range on a unique field, or an exact match on a
  non-unique one, keeps the sort.

#### RW4 · Filter before the join (MongoDB only)
- **Fires when:** a `$match` comes straight after a run of `$lookup` and/or
  `$unwind` stages, and every field it filters on is a declared field of the
  base collection.
- **Rewrites to:** the same pipeline with the `$match` moved ahead of that
  run, which keeps its own order.
- **Why:** the same documents survive, but they are filtered before the
  join, so fewer are joined.
- **Declines when** the `$match` filters on a `$lookup`'s `as` field, on an
  `$unwind`'s path or `includeArrayIndex`, or uses an operator such as
  `$expr`. Moving `{"items.quantity": {"$gt": 2}}` ahead of
  `{"$unwind": "$items"}` would keep whole orders instead of matching items —
  a different answer — so it stays where it is.

#### Compare, for both databases (2026-10-01)
`POST /api/query/compare` rewrites with the dialect's rules and analyses both
versions. For MongoDB the figures come from `explain` at `executionStats`,
which runs the plan, so `estimate` is `false`, and the summary reports
documents examined and any change of plan stage (for example COLLSCAN →
IXSCAN). A rewrite the database rejects is withheld. An accepted rewrite is
written into `QueryHistory.optimizedQuery` on the newest history row for the
same question (the request's optional `userInput`) or, without one, the same
generated query, database type and schema label.

#### An implementation trap worth recording
JSQLParser 5.3's plain `ExpressionList` renders an `IN` list **without
parentheses**, so RW2's first implementation emitted
`WHERE city IN 'mumbai', 'pune'` — invalid SQL, not merely ugly formatting.
`ParenthesedExpressionList` is the correct type. Nothing about the object
graph reveals this; only rendering the SQL and reading it does. Two of the 14
`QueryRewriterTest` cases assert the parentheses for that reason.

---

## Known gaps in these rules

Found by reading the code. The four dated ones below were fixed on the dates
given and are covered by unit tests; none of those fixes has been run against
a live backend.

- ~~**UNCONFIRMED — a table name the schema cannot bind may be dropped in
  silence.**~~ **FIXED 2026-09-26 (E23).** Reproduced exactly as suspected,
  for the "and"-conjoined case: with `suppliers` undeclared, both `customers
  and suppliers` and `customers and widgets` built `SELECT * FROM customers;`
  at 0.95, naming neither ignored word anywhere in the trace — the same shape
  as E15 before it was fixed. (`show customers plus invoices` had already
  declined before the fix, but for an unrelated reason — a trailing-phrase
  match against `customers` — and without isolating "invoices" in its
  message.) E23 now declines all of these uniformly:
  `"suppliers" isn't a table in the active schema, so this engine can't tell
  what the question joins customers to.` See `UnboundNounTest` and
  PROJECT.md §9.10.

- ~~**E14 keeps only the first filter value.**~~ **FIXED 2026-09-21.**
  `customers in mumbai and pune` built `WHERE city = 'mumbai'` at 0.95 and
  quietly lost `pune`; it now writes `city IN ('mumbai', 'pune')`, the
  behaviour the engine this one replaced had. Multi-word values such as `new
  delhi` still can't match, and still decline rather than guess.
- ~~**E15 is the only rule that fails silently.**~~ **FIXED 2026-09-21.** An
  unmatched column in `with <column> <value>` was dropped instead of declined,
  so `customers with gender female` on a schema without `gender` returned every
  customer labelled `RULE_ENGINE`. It now declines with the reason.
- ~~**E7 finds its verb inside other words.**~~ **FIXED 2026-09-21.** It tested
  for `delete` or `remove` anywhere in the text, so `show removed customers in
  mumbai` became `DELETE FROM customers WHERE city = 'mumbai';`, and `show
  deleted customers` was declined as a delete with no `WHERE`. The verb must
  now be a whole word opening the question; standing anywhere else it declines.
  QuerySense never runs generated SQL, so this was wrong rather than dangerous.
