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
| **Rule engine** (question → SQL) | **22** | E1–E22 | `querybuilder/` |
| Routing between the three layers | 3 | R1–R3 | `QueryGenerationServiceImpl`, `GeneratedQueryValidator` |
| Checks before EXPLAIN | 2 | P1–P2 | `analysis/SqlPreflightValidator` |
| EXPLAIN warnings | 4 | W1–W4 | `analysis/MySqlExplainAnalyzer` |
| MongoDB checks | 2 | M1–M2 | `analysis/MongoSimpleAnalyzer` |
| Analysis verdict | 9 | V1–V9 | `analysis/VerdictService` |
| Domain optimisation | 7 | TX1–TX7 | `optimization/TransactionalOptimizationRules` |
| Resource optimisation | 5 | RS1–RS5 | `optimization/ResourceConsciousService` |
| Schema advisor | 5 | SA1–SA5 | `service/impl/SchemaAdvisorServiceImpl` |
| **Total** | **59** | | |

**The rule engine has 22 rules.** Of those, 18 are conditions under which it
refuses a question and hands it to the language model. The other 37 rules
belong to the modules that analyse and optimise a query after it has been
generated. None of the 59 uses a model.

---

## 1. The rule engine — 22 rules

The rule engine is layer 1 of query generation. It turns a plain-English
question into SQL without a model, or it declines, and the question moves
on to the cache and then the local LLM.

Two principles run through every rule:

- **Match against the active schema, never a word list.** Tables and
  columns are matched against whichever schema is loaded right now: the
  built-in demo or an uploaded one. Uploading a schema therefore changes what
  the engine is willing to answer. The date check (E3) is the only exception.
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

#### E2 · SQL only
- **What it does:** declines every question whose target is MongoDB, before
  any matching is attempted.
- **Why:** every later rule writes SQL syntax. Claiming a MongoDB request
  would return a query in the wrong language, labelled as a confident
  rule-engine answer. It stays this way until a MongoDB builder exists, so
  today **every MongoDB question goes to the LLM.**
- **Declines with:** "This engine builds SQL only, so MongoDB questions go to
  the language model."

#### E3 · No date arithmetic
- **What it does:** declines any question containing a date *expression*:
  - `last / this / next / past` + `month / week / year / day / quarter / decade`
  - `today`, `yesterday`, `tomorrow`, `recently`
  - `in / during / before / after / since / by` + a month name
  - `before / after / since / until` + a four-digit year
  - `date range`, `last N days`
- **Why:** turning "last month" into a correct date range depends on the
  current date, the column's type and the time zone, and a rule can't get
  that right in general. This is the one rule that still reads fixed
  English words. It looks for the whole expression, not a single word. The
  previous engine declined any sentence containing `may`, because it
  treated `may` as a month.
- **Example:** `orders in january` → declined. `customers who may have
  churned` → *not* declined by this rule.

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
- **Declines when:** no table matches, or more than two do. The engine
  joins two tables at most.
- **Example:** `show all order items` → `order_items`. `customers, orders
  and payments` → declined (three tables).

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

#### E6 · Joins come from a declared foreign key
- **What it does:** when two tables are named, looks for the declared foreign
  keys that link them and writes `JOIN … ON` from the one it finds.
  Foreign keys that point back at their own table (`employees.manager_id →
  employees.id`) are ignored.
- **Why:** the `ON` clause is the easiest part of a join to get wrong and the
  hardest to spot. Reading it from the schema's own constraints, never
  guessing from a name like `client_id`, is what makes a rule-built join
  checkable. The trace names the exact key used. A self-join is ignored
  because "employees and their managers" and "managers and their reports"
  need opposite sides of the key, and nothing in the wording says which one
  is meant.
- **Declines when:** the schema declares **no** key between the two
  tables, or **more than one**. Example: `orders.billing_address_id` and
  `orders.shipping_address_id` both point at `addresses`. Here "orders with
  addresses" has two right answers, so the engine declines instead of
  picking one.
- **Example:** `which customers placed more than three orders` → `customers
  JOIN orders ON customers.id = orders.customer_id`. `customers and products`
  → declined (no direct key).

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
  `under` as `<`. `N` can be a digit or a word from `one` to `ten`.
  - If the column matches, it becomes a `WHERE` condition.
  - If it doesn't match **and** two tables are joined, it becomes a count of
    the related table: `HAVING COUNT(<related table's first column>)`. The
    output then shows the subject table's key column, plus its `name`,
    `title` or `label` column if it has one.
- **Why:** in "customers who **placed** more than three orders", the word
  in front of the comparison is a verb, not a column. With a join present,
  a count of the other table is the only thing it can mean.
- **Declines when:** `N` isn't a number the engine can read (`fifty` is
  beyond `ten`), or the column doesn't match and there is no join.
- **Example:** `products with price above 500` →
  `SELECT * FROM products WHERE price > 500;`
  `which customers placed more than three orders` →
  `SELECT customers.id, customers.name FROM customers JOIN orders ON
  customers.id = orders.customer_id GROUP BY customers.id, customers.name
  HAVING COUNT(orders.id) > 3;`

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
- **Declines when:** the first value matches no column of the subject table,
  or two values match different columns — the engine cannot tell which field
  the question means.
- **Example:** `show customers in mumbai` →
  `SELECT * FROM customers WHERE city = 'mumbai';`. `customers in mumbai and
  pune` → `… WHERE city IN ('mumbai', 'pune');`. `customers in atlantis` →
  declined.

#### E15 · `with <column> <value>`
- **What it does:** reads an explicit column/value pair. It only runs when
  E13 and E14 produced no `WHERE`, and comparison phrasing (`with more than
  three orders`) is excluded before the column is looked up. Numbers are left
  unquoted; anything else is quoted.
- **Why:** it covers questions that name both the column and the value.
- **Declines when:** the named column is not a column of the subject table.
- **Example:** `orders with status pending` →
  `SELECT * FROM orders WHERE status = 'pending';`

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

### Safety checks

#### E17 · No unconditional delete
- **What it does:** refuses to write a `DELETE` with no `WHERE`.
- **Why:** a delete without a condition empties the table. The engine never
  writes one, even when the question literally asks for it. (QuerySense
  never runs generated SQL anyway, but it shouldn't produce this.)
- **Example:** `delete all customers` → declined.

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
- **What it does:** `top N`, `first N` or `limit N` produces `LIMIT N`. Digits
  only: `top five` isn't read.
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

### The 18 ways the rule engine declines

| Group | Count | Rules |
|---|---|---|
| Gates | 3 | E1 empty · E2 MongoDB · E3 date expression |
| Tables and joins | 4 | E4 no table · E4 more than two tables · E6 no foreign key · E6 two or more foreign keys |
| Delete across a join | 1 | E7 |
| A named thing matches no column | 7 | E9 · E10 · E11 · E12 · E13 · E14 · E19 |
| Comparison value isn't a number | 1 | E13 |
| Safety | 2 | E17 unconditional delete · E18 column missing from the database |

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

#### R3 · LLM incompleteness check (MySQL only)
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

---

## 4. EXPLAIN warnings — 4 rules

Applied to each table in MySQL's execution plan. The same table never gets
the same warning twice. The wording reports what MySQL *chose* and never
claims the choice was wrong; recommendations belong to the schema advisor.

#### W1 · Full table scan
- **Fires when:** MySQL reads the table from start to end.
- **Says:** a full scan happened; if the query is slow, check whether an
  index on the filtered or joined columns would help.

#### W2 · No index used
- **Fires when:** the table used no index but *wasn't* a full scan (so W1
  didn't already cover it).
- **Says:** which access type MySQL used, and which indexes it considered.
- **Why:** it's per table. In a join where one side uses an index, the
  other side still gets reported correctly. The old version reported "no
  index is being used" for the whole query.

#### W3 · Temporary table
- **Fires when:** MySQL built a temporary table (`Using temporary`), usually
  for `GROUP BY` or `DISTINCT`.

#### W4 · Filesort
- **Fires when:** MySQL had to sort the rows after reading them
  (`Using filesort`).
- **Says:** an index matching the `ORDER BY` columns may reduce the sorting.

---

## 5. MongoDB checks — 2 rules

MongoDB queries aren't run through MongoDB's `explain()`. These two checks
read the query text instead.

#### M1 · Unindexed filter
- **Fires when:** the first filter field that isn't a `$` operator is missing
  from the known-index list.
- **Honest caveat:** that list is a **placeholder** (`_id`, `email`,
  `userId`), not real index metadata read from MongoDB.

#### M2 · Unanchored regex
- **Fires when:** a `$regex` value doesn't start with `^`.
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

## 7. Domain optimisation — 7 rules

One rule set, run on every analysis: the transactional one. "Large" means
**above 100 rows**.

Until 2026-09-11 the Workbench had a domain picker choosing between three
sets — e-commerce (EC1–EC6), analytical (AN1–AN5) and transactional.
Schemas are now uploaded directly, so no workload is ever declared; the
picker, and the two sets only it could reach, were removed. Transactional
was already what a missing domain, or `Other`, used, so a run that never
picked a domain behaves as before.

### Transactional — 7 rules (large = above 100 rows)

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
- **TX6 · MongoDB: unindexed filter** — `CRITICAL`.
- **TX7 · MongoDB: unanchored regex** — `CRITICAL`. A regex scan doesn't
  belong on a hot path.

---

## 8. Resource optimisation — 5 rules

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

#### RS4 · MongoDB: unindexed filter, low resources
`CRITICAL`. The collection scan competes for the same memory as every other
query.

#### RS5 · Severity shift
Moves every **domain** suggestion (section 7) one step: up under low, unchanged
under medium, down under high, never below `INFO` or above `CRITICAL`. So the
same finding reads as `CRITICAL` on a small machine and `INFO` on a large one.
The resource module's own suggestions (RS1–RS4) aren't shifted.

---

## 9. Schema advisor — 5 rules

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

#### SA4 · MongoDB index
- **Fires when:** unindexed filter (M1).
- **Recommends:** `db.<collection>.createIndex({ <field>: 1 });`

#### SA5 · MongoDB regex
- **Fires when:** unanchored regex (M2).
- **Recommends:** anchor the regex with `^`, or create a text index if
  searching within the text is needed.

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

## Known gaps in these rules

Found by reading the code. The first two were fixed on 2026-09-21 and are
covered by unit tests; neither fix has been run against a live backend.

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
