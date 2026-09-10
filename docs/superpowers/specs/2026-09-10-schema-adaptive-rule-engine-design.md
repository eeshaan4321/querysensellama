# Schema-adaptive rule engine — design

**Status: design complete and agreed. Nothing here is implemented.** No
implementation plan exists yet; that is the next step.

Written 2026-09-10. Supersedes nothing; extends the rule engine described in
PROJECT.md §2.

It closes two defects PROJECT.md already records as open — the `order_items`
multi-word table mis-resolution (§5) and the dropped `per <column>` grouping
(§8) — and two found while writing this design and **not** recorded anywhere
before it: the hardcoded `city` filter column and the inert known-value safety
net, both in §1 below.

---

## 1. Goal

Make the rule engine adapt to an uploaded schema in fact, not only in
vocabulary, and let it claim small joins and aggregates instead of deferring
every one of them to the LLM.

The complaint that prompted this, stated plainly: *"There is no novelty in
always falling back to the LLM."* That is correct as a description of current
behaviour, and the reason is not the one PROJECT.md implies.

### What actually follows an upload today, and what does not

`RuleBasedQueryBuilder` reads `ActiveSchemaProvider`, so **table and column
names** follow an uploaded schema. Three things do not:

1. **The decline gate is a hardcoded English blacklist.**
   `RuleBasedQueryBuilder.hasAnyComplexity` (`:176`) rejects on `who`, `which`,
   `average`, `sum`, `more than`, `above`, month names, `last month`, `recent`
   and more. None of these has anything to do with the schema. Upload a perfect
   schema and *"which clients are in Mumbai"* still routes to the LLM — not
   because the rules cannot build it, but because the word `which` is on a
   list. This is the main reason uploading feels like it changed nothing.

2. **Any two tables mentioned is an automatic decline.** `tableCount > 1`
   (`:223`) routes to the LLM. The gate exists precisely because there is no
   join builder, so it is the thing a join capability has to replace.

3. **Both data-driven paths are still hardwired to the demo schema.** These are
   bugs, not merely limitations:
   - `:355` emits a literal `city = '…'` for any *"in/at/from &lt;value&gt;"*
     phrasing. The column name is a string constant. On a schema with no `city`
     column this is a confidently-wrong query that dies at EXPLAIN with error
     1054.
   - `:401` calls `getDistinctValues("customers", "city")`. On an uploaded
     schema there is no `customers` table, so `SchemaMetadataService` logs
     *"Refused to load distinct values for unrecognized table/column"* and
     returns an empty list. **The known-value safety net is silently inert on
     every uploaded schema.**

   PROJECT.md §7 records *"show clients in Mumbai"* working against an uploaded
   schema as evidence of adaptation. It worked because that sample happened to
   have a `city` column. That was luck, and it is recorded as proof.

Summary: the engine adapts its nouns, keeps someone else's grammar, and turns
off its own seatbelt.

---

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| D1 | How ambitious? | **Full inversion + FK joins.** Replace the blacklist with binding-based decline, add single-hop FK joins, aggregates and comparison predicates. Fixing the hardwired `city`/`customers` bugs is included and was never optional. |
| D2 | Where does the rule trace surface? | **The Analysis screen only.** Workbench keeps showing only the query. |
| D3 | Which query shapes may the rules claim? | **Join + aggregates + HAVING** — COUNT/SUM/AVG/MIN/MAX, GROUP BY, comparison predicates, and post-aggregate filters. |
| D4 | Confidence tiering | **One flat 0.95 for every rule-engine answer.** The "one fixed tier per layer" claim in PROJECT.md §2 and CLAUDE.md §2 stays true; the nuance moves to the rule trace, which is more informative than a number because it names the FK edge and column bindings a reviewer can check. |
| D5 | Are declines traced too? | **Yes — the trace covers both outcomes.** A declined query renders why the rules passed and which layer answered instead, making the routing boundary itself auditable. Supersedes the narrower reading of D2; see §5.2. |

### D2 in detail — why Analysis, not Workbench

The trace is produced by `POST /api/query/generate`, but the Analysis screen
renders `POST /api/query/analyze`, which never sees the natural-language input
and so cannot reconstruct how the SQL was built. It does not need to:
`AnalysisPage` already destructures `build` from `WorkbenchContext`
(`AnalysisPage.jsx:16`) and already blocks with an empty state when there is no
build. So the trace rides along in the generate response, sits in
`build.ruleTrace`, and renders from context.

**`/api/query/analyze` does not change, and no new HTTP call is added.** The
pipeline stays at four requests (PROJECT.md §4).

It sits beside the verdict, where a reader is already asking *why is this query
shaped like this?* — a better question for it to answer than Workbench's *is
this the right query?*

**Superseded by D5:** an earlier draft of this section had the panel render only
for rule-built queries, so an LLM- or cache-sourced query would show nothing. D5
below changes that — a declined query renders the reason instead. §5.3 has the
exact render table.

### D3 in detail — why HAVING is worth the extra work

*"Which customers placed more than three orders"* is one of the five sample
prompts shipped in the app. It is currently LLM-only (PROJECT.md §2). It is a
JOIN + GROUP BY + HAVING. Claiming that shape makes the app's own sample-prompt
list the demonstration, rather than something a demo has to argue verbally.

### The novelty claim this buys

`SchemaSnapshot` already carries `foreignKeys()`, and the generator has never
read it. A rule-built join emits an ON clause **read from the declared foreign
key, not inferred** — which is the thing the LLM cannot claim, and which the
rule trace makes checkable rather than asserted.

### The counterweight, stated up front

A rule engine that claims more will sometimes be confidently wrong at 0.95, and
PROJECT.md §5 already names that as the one failure the project's central claim
cannot survive. Both live examples are over-claims of exactly this kind: the
`order_items` mis-resolution, and *"how many user_details are there per gender"*
answering without the grouping. Widening the engine is therefore paired with
binding-based decline (§3) — every widening below makes the engine decline
**more** in the ambiguous cases, not less.

---

## 3. Architecture

### 3.1 The inversion

Today `tryBuild` normalizes, detects a table, asks *"does this text contain a
scary word?"*, and emits.

Proposed: parse the question into a **schema-bound intent**, then emit from it.
Decline happens when a noun cannot be bound to something real in the active
schema, or when a binding is ambiguous. The word list disappears entirely.

```
input ──► PhraseBinders ──► QueryIntent ──► SqlEmitter ──► SQL
             │  (bind every noun against         │
             │   the ACTIVE SchemaSnapshot)      └──► RuleTrace
             │
             └── can't bind, or ties ──► Optional.empty() ──► cache ──► LLM
```

`QueryIntent` is the load-bearing piece: a record holding primary table, join
edges, projections, filters, grouping, having, order and limit, where **every
element is a real table or column already resolved from the snapshot**. Nothing
in it is a raw string from the user. That is what makes "declines correctly" a
structural property rather than a list someone has to keep extending.

### 3.2 Class breakdown

`RuleBasedQueryBuilder` is ~600 lines doing eight jobs. It becomes an
orchestrator over the following, in `querybuilder/`:

| Class | Job |
|---|---|
| `SchemaBinder` | Resolve a noun/phrase to a real table or column. Owns singular/plural **and underscore↔space** normalisation. Most-specific match wins; ties decline. |
| `JoinPathResolver` | Two bound tables to FK edges from `SchemaSnapshot.foreignKeys()`. 0 edges decline, 1 edge joins, 2+ decline as ambiguous. |
| `PhraseBinder` set | One each for projection, filter/comparison, aggregate, grouping, having, order, limit. Each returns a bound fragment, "not present", or "present but unbindable". |
| `QueryIntent` | The structural record above. |
| `SqlEmitter` | Intent to SQL. Deterministic, knows no English. |
| `RuleTrace` | Built alongside the intent; what the Analysis screen renders. |

`SchemaBinder` is where the `order_items` defect dies: `"order items"` and
`order_items` normalise to the same token before matching, and `orders` no
longer wins by being a substring, because most-specific beats first-found.

### 3.3 The value safety net is replaced, not generalised

The first instinct was to make `getDistinctValues("customers", "city")`
schema-aware. Inverting it is better: instead of hardcoding `city = '…'` and
then checking the result, bind the preposition's object **by looking for it in
the data**. *"clients in mumbai"* asks which column of `clients` actually
contains the value `mumbai`, and uses that column. If no column does, decline.

This kills the hardcoded `city` bug and makes the net stronger than it is now,
because the filter column is derived from the data rather than assumed.

Cost is bounded the same way it is today: distinct-value lookups only on columns
of tables already in the intent, cached in `SchemaMetadataService`, flushed on
schema change via the existing `onSchemaChanged` listener.

### 3.4 Live-drift protection: not `SqlPreflightValidator`

An earlier draft proposed gating `/generate` through `SqlPreflightValidator`.
Rejected after reading it: its signature is `validate(sql, tableName)` — single
table — and its own scope note limits it to simple SELECT/WHERE/GROUP BY shapes.
Forcing joins through it would mean contorting a class that currently works.

The protection it offers is better had directly. `SchemaBinder` checks each
resolved column against `SchemaMetadataService.getColumnNames(table)` — the live
`information_schema` list — at bind time. This works naturally for joins, gives
the same drift protection, and leaves `SqlPreflightValidator` alone on the
`/analyze` path where it fits.

---

## 4. Join resolution and the decline boundary

### 4.1 Picking the join

Bind the tables mentioned. More than two, decline. For a pair, look up edges in
`SchemaSnapshot.foreignKeys()` in either direction, excluding self-referencing
edges:

| Edges found | Action |
|---|---|
| 0 | **Decline.** No declared relationship. The engine does not invent one from a name convention like `client_id`. |
| 1 | Join on it. |
| 2+ | **Decline as ambiguous.** |

The built-in schema has no ambiguous pair, so this always resolves there. An
uploaded schema easily can — `orders.billing_address_id` and
`orders.shipping_address_id` both pointing at `addresses.id` is the ordinary
case, and *"orders with addresses"* genuinely has two right answers. Declining
is correct, and the trace names the two edges that collided.

**Which table is FROM:** the one the projection or aggregate subject binds to;
the other is joined. Falls back to first-mentioned only when the projection is
`*`.

### 4.2 Worked example — the sample prompt that is currently LLM-only

*"Which customers placed more than three orders"*:

```
customers ─┬─ bound: table customers
orders    ─┴─ bound: table orders
FK edge   ─── orders.customer_id → customers.id   (exactly one, unambiguous)
projection ── customers (subject)                 → FROM customers
aggregate  ── COUNT over orders
comparison ── "more than three" → > 3, target is an aggregate → HAVING
```

```sql
SELECT customers.id, customers.name FROM customers
  JOIN orders ON orders.customer_id = customers.id
  GROUP BY customers.id, customers.name
  HAVING COUNT(orders.id) > 3;
```

`GROUP BY` is emitted over exactly the non-aggregated projected columns — always
`ONLY_FULL_GROUP_BY`-safe by construction, rather than relying on MySQL's
functional-dependency detection.

**WHERE vs HAVING falls out of one rule:** a comparison whose target is an
aggregate becomes HAVING; a plain column becomes WHERE.

### 4.3 The decline boundary

The engine declines when any of the following holds:

- more than two tables bind;
- a table pair has 0 or 2+ FK edges between them;
- a noun in a strong-signal position fails to bind;
- two candidates tie on specificity;
- a value-grounded filter's literal is not present in the column's data (§3.3);
- the bound tables are not directly related (multi-hop).

### 4.4 Temporal phrases still decline — but as a binder, not a word list

Removing the blacklist wholesale would let date expressions through, and date
arithmetic has no correct rule-based answer here. So temporal phrases still
decline, via a `TemporalPhraseBinder` that recognises *"last month"*, *"in
January"*, *"before 2024"* and returns unbindable — a marked extension point
rather than a hidden veto.

The difference is not cosmetic. Today's month regex (`:178`) contains `may`, so
**any sentence containing the word "may" is declined** — *"customers who may
have churned"* routes to the LLM because of a modal verb. A binder that fires on
temporal *expressions* rather than tokens does not have that failure mode.

## 5. The rule trace

### 5.1 Shape

A new DTO on `QueryResponse`, populated for every Workbench-sourced query —
carrying either how the rules built it, or why they declined:

```java
public record RuleTrace(
    String outcome,                // "BUILT" | "DECLINED"
    String shape,                  // "SELECT + JOIN + GROUP BY + HAVING"; null when declined
    List<Binding> bindings,        // what each phrase resolved to
    JoinEdge joinEdge,             // nullable — the FK the ON clause came from
    List<String> checksPassed,
    String declineReason           // plain language; null when built
) {}

record Binding(String phrase, String kind, String resolvedTo);
// "customers"       · TABLE      · customers
// "more than three" · COMPARISON · COUNT(orders.id) > 3

record JoinEdge(String fromTable, String fromColumn,
                String toTable, String toColumn, String origin);
// origin = "declared foreign key"
```

`origin` is the load-bearing field. It turns *"the join came from your schema,
not a model's guess"* from a claim in a slide into something a reviewer verifies
against their own DDL in ten seconds.

### 5.2 Declines are traced too

`tryBuild` currently returns `Optional.empty()` on decline and the reason
evaporates. `handledBy: OLLAMA` records *which* layer answered but never *why
the previous one passed*.

With the reason carried, the Analysis screen can say:

> **The rule engine declined this.** Two foreign key paths exist between
> `orders` and `addresses` (`billing_address_id`, `shipping_address_id`) — the
> question does not say which. Handed to the local model.

This makes the **routing decision itself** auditable. PROJECT.md §2 claims
routing is "auditable rather than asserted"; today the project can show what
each layer produced but not why the boundary between them fell where it did.
This is the first time that claim is demonstrable in full.

It is cheap, because the decline reasons must exist as logic anyway (§4.3) —
this stops discarding them. `tryBuild` returns a small result type instead of
`Optional<String>`, a signature change confined to one caller
(`QueryGenerationServiceImpl`).

Accepted costs, stated plainly:

- a second render path on the panel;
- decline reasons must be written in plain language, under the same wording
  discipline `SchemaAdvisorServiceImplTest.jargonFreeSummaries` already
  enforces;
- the panel now appears for LLM- and cache-answered queries too, so "no panel"
  stops meaning "not rule-built" and starts meaning "not built from a
  Workbench question".

### 5.3 Frontend

One new component, `RuleTracePanel`, rendered on the Analysis screen from
`build.ruleTrace`.

**When it renders, precisely** — §5.2 changes this from the obvious reading, so
it is worth stating exactly:

| `build.ruleTrace` | Panel |
|---|---|
| `outcome: BUILT` | How the rules built the query, with the FK edge named. |
| `outcome: DECLINED` | Why the rules passed, and which layer answered instead. |
| absent | Nothing rendered — no empty box. |

Absent means *there is no traced generation behind this analysis*: history
replay, a query that did not come from the Workbench, or a response predating
this field. It does **not** mean "the rules declined" — that case is
`DECLINED`, and it renders. The empty-render discipline is `VerdictBanner`'s;
the two-outcome split is this component's own.

Contract work is the usual three touches described in PROJECT.md §4: a shape in
`contracts.js`, an `ENDPOINT_STATUS` entry, and a fixture in `mock/fixtures.js`
so mock mode does not diverge from real mode.

**Placement: below `VerdictBanner`, above `PlanMetrics`.** The verdict answers
*is this query any good*; the trace answers *why is it shaped this way*; the
metrics and plan are the evidence. That preserves the screen's established
top-to-bottom logic — answer, then reasoning, then evidence in increasing
technical depth (PROJECT.md §4).

Workbench is unchanged and still shows only the query (D2).

## 6. Testing

Test-driven, plain JUnit + Mockito with a mocked `JdbcTemplate` and
`SchemaMetadataService`. No test needs a running MySQL — the same standard the
86 schema-upload tests were written to.

| Class | Covers |
|---|---|
| `SchemaBinderTest` | Singular/plural, underscore↔space, most-specific-wins, ties decline. **Includes the `order_items` regression** — *"show all order items"* must produce `order_items`, not `orders`. |
| `JoinPathResolverTest` | 0 / 1 / 2+ edges, self-FK exclusion, direction-agnostic lookup. |
| `SqlEmitterTest` | GROUP BY covers every non-aggregated projection; the WHERE vs HAVING split. |
| `RuleBasedQueryBuilderTest` | The five sample prompts end to end, plus *"which customers placed more than three orders"* landing as `RULE_ENGINE`. |
| **`DeclineBoundaryTest`** | A table of inputs that **must** decline. |
| Wording guards | Decline reasons and trace text carry no `accessType` / `possible_keys` / EXPLAIN jargon, and no teammate names. |

`DeclineBoundaryTest` is the load-bearing one. Everything else in this design
widens what the engine claims; that file is the only thing holding the line, and
it is the direct guard on the failure PROJECT.md §5 says the project's central
claim cannot survive — a wrong answer returned at `RULE_ENGINE` 0.95.

The wording guards mirror `SchemaAdvisorServiceImplTest.jargonFreeSummaries` and
the two regression guards in `VerdictServiceTest`, and exist for the same
reason recorded there: copy drifts back toward the vocabulary of the code around
it.

### 6.1 Existing tests

`RuleBasedQueryBuilderSchemaTest` (4 tests) pins schema adaptivity and should
stay green **unedited** — the same discipline applied to `KnownSchemaTest` under
R7 of the schema-upload work. If it needs editing, something regressed.

Tests elsewhere that assert a decline for `who` / `which` / `more than`
phrasings will need updating. Each such edit is a deliberate behaviour change
and should be justified individually rather than bulk-applied, and each belongs
in the session record.

### 6.2 Frontend

`RuleTracePanel` gets a vitest file — the second tested file in the frontend
after `applyScript.test.js`.

### 6.3 Division of verification

Unit suite green and `npm run build` clean are verified here. **Live MySQL and
browser verification are the user's** — they test personally (recorded in
PROJECT.md §7 for the schema-advisor session). No live result will be claimed
that was not produced.

### 6.4 A preservation risk worth naming

`RuleBasedQueryBuilder`'s 600 lines are dense with WHY comments encoding real
bug history — Bugs 7, 8, 9 and 10, each explaining why a naive fix failed. A
decomposition that drops them would lose more than it gains. Those comments move
with the logic they explain, into whichever new class inherits the behaviour.

---

## 7. Out of scope

- **MongoDB is untouched.** The rule engine still declines every Mongo request;
  this work is MySQL-only, exactly as schema upload was (PROJECT.md §5.5).
- Multi-hop joins, subqueries, UNION/EXCEPT, window functions, date arithmetic.
- **The LLM hallucination defect stays open.** This work reduces LLM traffic; it
  does not add the schema-conformance check before a result is returned or
  cached (PROJECT.md §5, fix direction #2).
- No DDL execution, per the standing non-goal in PROJECT.md §5.
- Query rewriting and the Comparison Engine — untouched, still simulated.
- Confidence tiers unchanged (D4).
- The built-in demo schema's contents and row counts are unchanged.
