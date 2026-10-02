# Rule engine joins across up to four tables — design

**Date:** 2026-10-02
**Status:** approved in conversation; awaiting review of this written spec.
Nothing below is built.
**Spans:** `backend/`, `frontend/` (the rule trace)
**Part of:** the rule engine adaptive to any schema. This is sub-project 3 of 4:
1. Vocabulary.
2. Question shapes.
3. Joins across three or more tables (this spec).
4. Dates.

**Builds on:**
- **Sub-project 1:** `SchemaLexicon`, interpretations, `AmbiguousWordException`.
- **Sub-project 2:** `TableStar`, the `distinct` flag, `anyOf`, condition
  spans.

---

## 1. Goal

A question may name up to four tables. The engine follows the shortest
chain of declared foreign keys that links them, adding unnamed tables the
chain passes through. Filters, values and columns may sit on any table in
the chain. Both dialects build it, and every edge used is shown in the
trace.

**Success means:**
- `orders of customers in mumbai` and `products ordered by customers in
  pune` build by rule in both dialects, with every edge named.
- Every question that builds today builds byte-for-byte the same, with one
  documented exception (§7).
- Two equally short chains decline, naming both.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| Reach | Up to four tables in the chain, named or passed through. |
| Filters | Any table in the chain. MongoDB applies each filter right after the `$lookup` that brings its collection in. |
| Ambiguity | Two equally short chains decline. |
| Dialects | Both, always. |

## 3. Finding the chain — `JoinPathResolver.resolveChain`

- **The graph:** every declared foreign key is an undirected edge between its
  two tables. Self-references (`employees.manager_id → employees.id`) are
  left out, as today.
- **The search:**
  - From the subject (the first table named), a breadth-first search finds
    the shortest paths to every other named table, at most three edges
    long.
  - If any named table has more than one shortest path, the result is
    `Ambiguous`, listing the paths. Two foreign keys between the same two
    tables count as two paths, as today.
  - The union of the chosen paths must hold at most four distinct tables.
    Otherwise it is `TooFar`.
  - A named table with no path within three edges gives `None`.
- **The result:** `Chain(List<KnownSchema.ForeignKey> edges)`, ordered so that
  each edge's near table is already in the chain.
- **Two tables:** direct edges give exactly today's answers. A pair with no
  direct edge, but a two-hop path, now builds through the middle table where
  today it declines.

## 4. The intent

- `QueryIntent.join` becomes `List<JoinSpec> joins`. A `join()` accessor
  returns the first or null, so existing callers and two-table output are
  unchanged. A constructor taking a single `JoinSpec` remains.
- `RuleTrace` gains `List<JoinEdge> joinEdges`. `joinEdge` stays as the first,
  for compatibility.

## 5. Columns and values across the chain

- **Columns:** a column phrase is bound in the subject first. Otherwise it is
  bound in the other chain tables, in chain order.
  - One table fitting gives a qualified `ColumnRef`.
  - Two give a decline: `"name" could mean customers.name or products.name, so
    this engine won't pick one.`
  - **No table-qualified reading** (`customer city`). The subject is the first
    table named, so a qualifying table word would always arrive before it.
    Such a phrase declines as ambiguous instead.
  - **Phrases that name a table are not columns:**
    - an "of" phrase (`orders of customers in mumbai`) is not read as a column
      projection;
    - an `ordered by` / `sorted by` target (`products ordered by customers`) is
      not read as a sort column.
- **Values (E14 and exclusion):** stored values are searched in the subject
  first, then in the other chain tables. A value found in two tables
  declines.
- **Comparisons:**
  - A comparison word that is a column of a joined table now filters that
    column. Today, with two tables, it counted the related table instead
    (§7).
  - A comparison word that is no column anywhere keeps today's count reading
    (`which customers placed more than three orders`). It counts the last
    named table.

## 6. Output

- **SQL:**
  - One `JOIN … ON` per edge.
  - When filters sit on joined tables but only the subject's columns are
    asked for (`*` or subject columns), the result is `SELECT DISTINCT
    subject.*` (or `DISTINCT` subject columns), so each subject row appears
    once.
  - Otherwise it is the joined rows, as today.
- **MongoDB:**
  - Per edge, a `$lookup` with `from` the new collection, `localField` the
    known side's path, `foreignField` the new side's field and `as` the new
    collection's name, then `$unwind`. The known side's path is `field` on the
    base collection and `<collection>.field` on a joined one.
  - An edge whose known side is inside an embedded array
    (`orders.items.product_id`) needs no extra unwind. `$lookup` matches every
    element of an array `localField`, and the `$unwind` of the looked-up
    collection gives one row per match.
  - A filter on a joined collection is a `$match` right after its `$unwind`.
    This replaces today's refusal ("…would apply that filter before the … are
    brought in…").
  - An `anyOf` (OR) group that touches a joined collection is applied after
    the last `$unwind`.
  - **Each subject document once:** `$group {_id: "$_id", doc: {$first:
    "$$ROOT"}}`, `$replaceRoot {newRoot: "$doc"}`, and `$project` removing the
    looked-up fields.
- **Trace:** `joinEdges` lists every edge in order, each "read from your
  schema's declared foreign key". Tables the chain passes through are named
  ("through orders"). `RuleTracePanel` shows every edge.

- **Counting once:** when the each-subject-once rule applies to a bare count
  (`how many customers ordered a laptop`), SQL counts `COUNT(DISTINCT
  subject.<key>)`, and MongoDB counts after the each-subject-once stages.

## 7. The one intended change to something that builds today

Today, with two tables, a comparison on a column that exists only in the
related table (`customers with orders amount over 3`, where `amount` is an
`orders` column) builds a `HAVING COUNT(orders.id) > 3`. That counts orders,
not amounts, and is wrong. It now builds `WHERE orders.amount > 3`. This is
the only change to an existing build, and it is recorded in `RULES.md` and
`PROJECT.md`.

## 8. Errors

- More than four tables named.
- No chain within four tables.
- Two equally short chains: `There are two equally short ways to connect
  customers and products (through orders and order_items, or through …), and
  the question doesn't say which one it means.`
- A column or value fitting two chain tables.
- Deleting across a join is still declined.
- An anti-join (sub-project 2) still needs a direct key.

## 9. Testing

All tests are written first.
- **`JoinPathResolverTest`:** direct edge, two-hop and three-hop chains,
  ambiguous chains, too far, self-references ignored, and two-table results
  unchanged.
- **Emitter tests:** a three-table chain, filters on joined tables, the
  each-subject-once form and its count, and an embedded-array hop, in SQL and
  MongoDB.
- **`MultiTableJoinTest`:** the success examples, an ambiguous column, an
  ambiguous chain, a chain too far, and §7.
- **`DialectParityTest`:** the same questions in both dialects.
- **Frontend:** `RuleTracePanel` lists several edges.
- **Regression guard:** the full suite passes. Only the test covering §7, if
  one exists, changes, with its reason recorded.

## 10. Out of scope

- Self-joins.
- More than four tables.
- Choosing between equally short chains by meaning.
- `OUTER` joins other than the sub-project 2 anti-join.
- Deleting across joins.

## 11. Docs

- **`RULES.md`:** E4 and E6 are rewritten for chains; E13 gains the §7 note.
- **`PROJECT.md`:** the session is recorded, and the roadmap is updated.
