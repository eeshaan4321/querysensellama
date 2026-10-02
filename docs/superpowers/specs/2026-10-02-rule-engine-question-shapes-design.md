# Rule engine question shapes — design

**Date:** 2026-10-02
**Status:** approved in conversation; awaiting review of this written spec.
Nothing below is built.
**Spans:** `backend/`, `frontend/` (the rule trace only)
**Part of:** the rule engine adaptive to any schema. This is sub-project 2 of 4:
1. Vocabulary.
2. Question shapes (this spec).
3. Joins across three or more tables.
4. Dates.

**Builds on:** sub-project 1 (`2026-10-02-rule-engine-vocabulary-design.md`):
- `SchemaLexicon` and its match kinds;
- `NumberReader`;
- interpretations and the 0.85 tier;
- `AmbiguousWordException`.

---

## 1. Goal

The rule engine answers six shapes of question it declines today, on any
schema, in MySQL and MongoDB, with the same never-guess rule:
- ranking and superlatives;
- ranges and inclusive bounds;
- not-equal and exclusion;
- "without" (anti-join);
- DISTINCT;
- OR across conditions;
- several totals in one question.

**Success means:**
- Each example in §3 builds by rule in both dialects.
- Every question that builds today builds byte-for-byte the same, at the same
  confidence, except the two silent drops in §3.8.
- Every new ambiguity declines with a sentence naming what it couldn't choose
  between.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| Which shapes | All four groups offered: ranking and superlatives; ranges and not-equal; without / never; DISTINCT, OR and several totals. |
| Dialects | Every shape ships for MySQL and MongoDB together. |
| Approach | Extend `QueryIntent` and both emitters. The parser recognises each shape with its own small reader. |

## 3. Shapes

### 3.1 Ranking and superlatives

| Question | SQL | MongoDB |
|---|---|---|
| `top 5 products by price` | `ORDER BY price DESC LIMIT 5` | `.sort({price: -1}).limit(5)` |
| `bottom 3 products by price` | `ORDER BY price ASC LIMIT 3` | `.sort({price: 1}).limit(3)` |
| `the 3 highest prices of products` / `products with the 3 highest prices` | `ORDER BY price DESC LIMIT 3` | same, as a find |
| `cheapest product` | `ORDER BY price ASC LIMIT 1` | `.sort({price: 1}).limit(1)` |
| `3 most expensive products` | `ORDER BY price DESC LIMIT 3` | same |
| `largest orders` | `ORDER BY amount DESC` (no limit: plural, no number) | same |

**Limit:**
- A number given before or after the ranking word is the limit.
- With no number, a singular subject (`cheapest product`) gets a limit of 1.
- A plural subject is sorted only.
- `top` and `bottom` also read through the existing limit rule, which gains
  `bottom`.

**Adjectives:**
- They come from a reviewed resource,
  `backend/src/main/resources/querybuilder/adjectives.json`. Each entry names
  the column words it can reach and a direction.
- Superlatives stand alone: `cheapest`, `priciest`, `largest`, `biggest`,
  `smallest`, `heaviest`, `lightest`, `longest`, `shortest`.
- A base adjective needs `most` or `least`: `expensive`, `costly`. `least`
  reverses the direction.
- An adjective binds the subject's column through the vocabulary matcher, as a
  `SYNONYM` interpretation (0.85):
  - several of its words fitting different columns → a decline naming them;
  - none fitting → a decline.
- Recency words (`latest`, `newest`, `oldest`, `earliest`) belong to
  sub-project 4.

**Kept as today:** `highest price of products` and `products with the highest
price` (no number) still build `MAX(price)`. Today they build that, and the
guard forbids changing them.

### 3.2 Ranges

| Question | SQL | MongoDB |
|---|---|---|
| `products with price at least 500` | `price >= 500` | `{price: {$gte: 500}}` |
| `products with price at most 500` / `no more than` / `up to` | `price <= 500` | `{price: {$lte: 500}}` |
| `products with price no less than 500` | `price >= 500` | `{price: {$gte: 500}}` |
| `products with price between 100 and 500` | `price BETWEEN 100 AND 500` | `{price: {$gte: 100, $lte: 500}}` |
| `customers who placed at least three orders` | `HAVING COUNT(orders.id) >= 3` | `$match` on the count, `$gte` |

- Both ends of `between` are read by `NumberReader`. The first end is read
  whole before the `and` that separates them, so `between two hundred and
  fifty and 500` works.
- Ends that aren't both numbers decline. Dates come with sub-project 4.

### 3.3 Not-equal and exclusion

| Question | SQL | MongoDB |
|---|---|---|
| `orders with status not shipped` / `status is not shipped` | `status != 'shipped'` | `{status: {$ne: "SHIPPED"}}` |
| `customers other than pune` / `excluding pune` / `except pune` / `not in pune` | `city != 'pune'` | `{city: {$ne: "Pune"}}` |
| `customers excluding pune and delhi` | `city NOT IN ('pune', 'delhi')` | `{city: {$nin: ["Pune", "Delhi"]}}` |

- **Values:** they are checked against stored data exactly as E14 checks them,
  with multi-word runs and the single-word typo rule. A value in two columns
  declines. The excluded words are consumed, so the `in` of `not in pune` is
  not read again as an equality filter.
- **`<column> not <value>`:** binds the column first, then the value within
  that column.

### 3.4 Without (anti-join)

| Question | SQL | MongoDB |
|---|---|---|
| `customers without orders` / `with no orders` / `who have no orders` | `SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id WHERE orders.customer_id IS NULL;` | `$lookup` as `orders`, `$match {orders: {$size: 0}}`, `$project {orders: 0}` |
| `how many customers without orders` | `SELECT COUNT(*) …` with the same join and `IS NULL` | the same pipeline ending `$count` |
| `customers in pune without orders` | `… WHERE customers.city = 'pune' AND orders.customer_id IS NULL` | `$match` on `city` first |
| `products never ordered` | the verb's stem `order` names `orders`. In the demo, products and orders aren't directly related, so this declines and says so. | the same |

- **Linking key:** an anti-join needs exactly one declared foreign key
  directly linking the two tables. With none, or two, it declines, using
  today's join messages.
- **OR:** an anti-join cannot be combined with OR. That mix declines.

### 3.5 DISTINCT

| Question | SQL | MongoDB |
|---|---|---|
| `distinct cities of customers` / `unique cities of customers` / `different cities of customers` | `SELECT DISTINCT city FROM customers;` | `aggregate([{$group: {_id: "$city"}}])` |
| `which cities do customers live in` / `what statuses do orders have` | the same | the same |

- The word after `which`/`what` must bind a column of the subject. If it binds
  a table (`which customers are in mumbai`), the question is read as today.
- In MongoDB the values come back under `_id`. This is the documented
  difference already recorded in `DialectParityTest`.

### 3.6 OR across conditions

| Question | SQL | MongoDB |
|---|---|---|
| `customers in mumbai or pune` | `city IN ('mumbai', 'pune')` | `{city: {$in: […]}}` |
| `customers in mumbai or with name asha` | `WHERE (city = 'mumbai' OR name = 'asha')` | `{$or: [{city: "Mumbai"}, {name: "Asha"}]}` |
| `products with price below 100 or price above 900` | `WHERE (price < 100 OR price > 900)` | `{$or: [{price: {$lt: 100}}, {price: {$gt: 900}}]}` |

- **Separators and connectors:** within a value list, `or` separates values
  like `and`. Between conditions, the connector words decide: each recognised
  condition records the span of text it came from, and the words between
  consecutive spans are read.
- **Mixed `and` and `or`:** between conditions this declines: `The question
  joins its conditions with both "and" and "or", so this engine can't tell how
  they group.`

### 3.7 Several totals

| Question | SQL | MongoDB |
|---|---|---|
| `average and highest price of products` | `SELECT AVG(price), MAX(price) FROM products;` | one `$group` with `avg_price` and `max_price` |
| `count and average amount of payments per method` | `SELECT method, COUNT(*), AVG(amount) … GROUP BY method` | `$group` with `count` and `avg_amount` |

- The functions are joined by `and` or commas and share one column. `count`
  in the list means `COUNT(*)`.
- A single aggregate keeps today's path and output.

### 3.8 Two silent drops fixed (intended changes)

Today two kinds of question build while quietly ignoring part of what was
asked. Both now use every condition:
1. **A second comparison.** `products with price below 100 or price above
   900` built only `price < 100`. Every comparison in the question is now
   read.
2. **A `with` filter next to another filter.** `customers in mumbai with name
   asha` built only the city filter. A `with` filter is now added whenever its
   words aren't already part of another condition.

These are the only changes to questions that build today. Both are recorded
in `RULES.md` and `PROJECT.md`.

## 4. `QueryIntent` changes

- `Op` gains `NE("!=")`, `NOT_IN("NOT IN")` and `BETWEEN("BETWEEN")`.
- `Operand` gains `TableStar(String table)`, rendered `table.*` in SQL and as
  "no projection" in MongoDB.
- `JoinSpec` gains `JoinKind kind` (`INNER`, `ANTI`). Its 3-argument
  constructor means `INNER`.
- `QueryIntent` gains `boolean distinct` and `boolean anyOf` (the `where`
  predicates are ORed). An 8-argument constructor keeps every existing call.

## 5. Errors

Every new decline is a full sentence, shown in the trace and sent to the LLM
fallback:
- an adjective fitting two columns, or none;
- a `between` whose ends aren't both numbers;
- `and` and `or` mixed between conditions;
- an anti-join with no linking key or two;
- an anti-join with OR;
- a limit that isn't a whole number (already from sub-project 1).

## 6. Testing

All tests are written first.
- **Emitter tests:** each new operator, `distinct`, `anyOf`, `TableStar` and
  the anti-join, in SQL and MongoDB.
- **`QuestionShapesTest`:** every example in §3 against an alias-free schema,
  plus each decline.
- **`DialectParityTest`:** every example builds in both dialects or declines
  in both.
- **Regression guard:** the full existing suite passes unchanged.
- **Frontend:** none needed. The trace and response shapes don't change.

## 7. Out of scope

- Recency superlatives (sub-project 4).
- Joins beyond two tables (sub-project 3).
- `HAVING` with OR.
- OR across an anti-join.
- Nested boolean expressions with brackets.
- Changing what "the highest X" (no number) builds today.

## 8. Docs

- **`RULES.md`:** new rules E24–E30, one per shape, and the new declines in
  the decline table.
- **`PROJECT.md`:** the session is recorded, and the roadmap is updated.
