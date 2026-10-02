# Rule engine vocabulary — design

**Date:** 2026-10-02
**Status:** approved in conversation, section by section. This written spec
is awaiting review. Nothing below is built.
**Spans:** `backend/` and `frontend/`
**Part of:** making the rule engine adaptive to any schema. This is
sub-project 1 of 4:
1. Vocabulary (this spec).
2. More question shapes: top-N, average per group, DISTINCT, NOT, OR.
3. Joins across three or more tables.
4. Dates.

Each later sub-project gets its own spec, plan and commits, in that order.

---

## 1. Goal

The rule engine answers questions phrased in words the schema doesn't
literally use, on any schema, in both MySQL and MongoDB, without ever
turning an ambiguity into a guess:
- synonyms (`clients` for `customers`);
- aliases the schema declares;
- one-letter typos;
- irregular plurals;
- multi-word values (`new delhi`);
- numbers beyond ten.

**Success means:**
- On a schema with no annotations at all, questions like `clients in mumbai`,
  `custmers in pune`, `customers in new delhi` and
  `orders above two hundred and fifty` build by rule, in both dialects.
- Every question that builds today builds byte-for-byte the same, at the
  same confidence.
- Every new loose match is named in the rule trace, and lowers confidence
  when it came from QuerySense's own list rather than from the schema.
- A loose match that fits more than one schema item declines, naming the
  candidates.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| Which rules | The rule engine (question → query). Analysis thresholds are not in this project. |
| Dialects | Every capability ships for MySQL and MongoDB together, with parity tests. |
| Synonym source | Aliases the schema declares, plus a built-in English synonym list. A schema alias is trusted like an exact name. A built-in synonym binds only when exactly one item fits. |
| Typos | Yes, but small and unique only: edit distance 1, words of five or more letters, exactly one candidate. |
| Confidence | 0.95 as today. **0.85** when any binding came from a built-in synonym or a typo. |
| Approach | A vocabulary index (`SchemaLexicon`) behind `SchemaBinder`. Rewriting the question before parsing, and similarity scoring, were both rejected. |

## 3. Schema-declared aliases

`SchemaSnapshot` gains:

```java
Map<String, TableAliases> aliases   // keyed by table name
record TableAliases(List<String> table, Map<String, List<String>> columns)
```

**MySQL (`DdlSchemaParser`):**
- A table `COMMENT` or a column `COMMENT` that contains `aka:` declares
  aliases. The names after `aka:` are comma-separated, up to the end of the
  comment or a `;`. For example, `COMMENT 'Registered buyers. aka: clients,
  buyers'`.
- Text before `aka:` is ignored, so ordinary comments are unaffected.
- The parser keeps comments from now on. Today it discards them.

**MongoDB (`JsonSchemaUploadParser`):**
- An `aka:` in a collection's `$jsonSchema` `description`, or in a field's
  `description`, is read the same way.
- The upload format also accepts an optional top-level block:
  `"aliases": {"customers": {"table": ["clients"], "columns": {"city": ["town"]}}}`.
- Where both declare aliases for the same item, they are merged.

**Built-in demos:** `KnownSchema` and `KnownMongoSchema` each declare
`customers` aka `buyers`, so a declared alias is visible without an upload.
`clients` is left to the built-in synonym list, so the demo shows both
kinds.

**Validation (`SchemaUploadValidator`):** an alias is dropped, with an upload
warning naming it, when it:
- equals a real table or column name in the schema; or
- is declared for two different items at the same level.

Aliases are lowercased and normalised like names: underscores and spaces
are equivalent, and camelCase is split.

## 4. Built-in synonym list

`backend/src/main/resources/querybuilder/synonyms.json` is a reviewed list
of English synonym groups:

```json
[["customer", "client", "buyer", "shopper", "patron"],
 ["price", "cost"],
 ["city", "town"],
 ["product", "item", "article"],
 ["employee", "staff", "worker"],
 ["quantity", "qty"],
 ["phone", "telephone", "mobile"]]
```

The file in the plan is the starting set, reviewed in the plan's task. It
holds no table names from any particular schema. A word in a group can reach
a schema item whose name, singular or plural, is in the same group. Groups
are symmetric. The list is static reference data, versioned with the code,
and is never applied to data values.

The list must not contain a word the parser already reads as an
instruction: `count`, `number`, `total`, `average`, `sum`, `top`, `first`,
`all` or `only`. `SynonymListTest` asserts this against the parser's own
word sets.

## 5. `SchemaLexicon`

A new `querybuilder/SchemaLexicon`, rebuilt per dialect from the active
snapshot whenever `ActiveSchemaProvider.onSchemaChanged` fires.

```java
enum MatchKind { EXACT, INFLECTION, SCHEMA_ALIAS, SYNONYM, TYPO }
record Binding(String item, String phrase, MatchKind kind)

List<Binding> tables(String normalizedText)            // spans, as bindTables today
Optional<Binding> column(Table table, String phrase)   // as bindColumn today
```

**What reaches each table and column:**
- **`EXACT`:** its name, with underscores and spaces equivalent and
  camelCase split.
- **`INFLECTION`:**
  - the plural and singular forms: `-s`, `-es`, and `-ies` ↔ `-y`;
  - a short irregular list: person/people, child/children, man/men,
    woman/women, address/addresses;
  - a column's existing behaviours: the `_id` ↔ "id" reading, the last
    segment of a dotted path, and the unique-suffix match.
- **`SCHEMA_ALIAS`:** the declared aliases and their inflections.
- **`SYNONYM`:** every word in a built-in group containing the item's name
  or singular, and their inflections.
- **`TYPO`:** at lookup time only. A word of five or more letters at edit
  distance 1 from any of the phrases above for exactly one item in scope. It
  is not applied to a word that is itself a schema name, a declared alias or
  a word in the synonym list.

**Precedence:**
- `EXACT`, `INFLECTION` and `SCHEMA_ALIAS` win outright, as today's exact
  matches do.
- `SYNONYM`, then `TYPO`, are tried only when nothing better matched. Each
  binds only when exactly one item in scope fits.
- In scope means the active schema's tables for a table lookup, and the
  table's columns for a column lookup.
- Two or more candidates are a decline that names them.

**Table spans:** the longest-match-wins and "first named is the subject"
rules of E4 apply to every match kind. A span reached by an alias or synonym
competes on its own length.

`SchemaBinder` keeps its public methods and delegates to the lexicon:
- `bindTables` and `bindColumn` gain `Binding`-returning variants
  (`bindTablesWithKind` and `bindColumnWithKind`).
- The existing signatures return `binding.item()`, so callers that don't
  need the kind are unchanged.

## 6. Values and numbers

**Multi-word values (E14):**
- After a preposition (`from`, `in`, `at`, `located in`, `living in`,
  `based in`, `residing in`), the engine takes up to four words, stopping
  at `and`, `or`, punctuation or a schema name.
- It tries the longest run first against the stored values of the subject
  table's columns, case-insensitively.
- `and` still separates values, so `in new delhi and pune` gives
  `IN ('New Delhi', 'Pune')`.
- The value written is the stored spelling, as today.

**Value typo:** when no run matches exactly, a single word of five or more
letters at edit distance 1 from exactly one stored value, across the subject
table, binds as `TYPO`.

**Value in two columns:** a value stored in more than one column of the
subject table now **declines**, naming the columns. Today
`columnHolding` returns the first column in schema order. That is a guess at
0.95, and this spec removes it.

**No value synonyms.** `bombay` does not find `Mumbai`.

**Both dialects:** stored values come from `SchemaMetadata.getDistinctValues`
(MySQL `SELECT DISTINCT`, MongoDB `distinct`), unchanged.

**`NumberReader`** (new, `querybuilder/NumberReader`) replaces the
`NUMBER_WORDS` map:
- digits, optionally with thousands separators (`1,000`), and decimals
  (`4.5`);
- spelled-out numbers from zero to the millions, with an optional `and`
  (`twenty five`, `two hundred and fifty`, `a thousand`, `three million`);
- `a dozen`.

It returns a `BigDecimal`, or empty when the words are not entirely a
number. A partial read is never returned.

Where it's used:
- E13 comparisons.
- E15 `with <column> <value>`, when the value is numeric.
- E20 limits.

A decimal limit declines. A decimal compared against an integer column is
written as given.

## 7. Result, confidence, API and screens

**`RuleBuildResult`** gains `List<Interpretation> interpretations`:

```java
record Interpretation(String phrase, String item, MatchKind kind)
```

There is one entry per binding whose kind is `SCHEMA_ALIAS`, `SYNONYM` or
`TYPO`. `EXACT` and `INFLECTION` are not listed.

**Confidence (`QueryGenerationServiceImpl`):**
- A new constant, `CONFIDENCE_RULE_ENGINE_INTERPRETED = 0.85`, documented
  beside the others as a fixed heuristic.
- It applies when any interpretation is `SYNONYM` or `TYPO`.
- `handledBy` stays `RULE_ENGINE`. A schema alias keeps 0.95.
- The cache is unaffected. A question the rule engine can build never
  reaches Layer 2.

**API:** the `/api/query/generate` response (`QueryResponse`) gains
`interpretations`, the same three fields per entry. It is an empty list when
there are none.

**Frontend:**
- `RuleTracePanel` shows a "Read as" block above the steps, one line per
  interpretation. For example: `"clients" → customers (built-in synonym)`,
  `"emial" → email (one letter off)`, `"buyers" → customers (alias declared
  by the schema)`.
- The Workbench confidence reads 85% on its own.
- The Schema source cards list declared aliases under each table, collection
  and field: "also: clients, buyers".

**LLM prompt:**
- Each table and column with declared aliases is written with them:
  `customers (also called: clients, buyers)`.
- This applies to `OllamaService`'s MySQL schema block and to
  `MongoSchemaContext`.
- Built-in synonyms are not added.

## 8. Errors

Each new decline is a complete sentence, shown in the trace and sent to
the LLM fallback like the existing ones:
- **A synonym or typo fits several items:**
  `"cost" could mean products.price or products.cost_price, so this engine
  won't pick one.`
- **A value is stored in several columns:**
  `"Pune" is stored in both customers.city and customers.branch, so this
  engine can't tell which one the question means.`
- **A number can't be fully read:**
  `"fifty-ish" isn't a number this engine can read.`

An alias collision is an upload warning, not an error. The upload succeeds
without that alias.

## 9. Testing

All tests are written first.
- **`SchemaLexiconTest`:**
  - each match kind;
  - precedence;
  - synonyms that are unique versus ambiguous;
  - typo limits: a four-letter word, distance 2, and a word that is itself
    a schema name;
  - irregular plurals.
- **`SynonymListTest`:** no group holds a word the parser reads as an
  instruction.
- **Alias parsing:**
  - `DdlSchemaParserTest`: table and column `COMMENT` with `aka:`, and
    ordinary comments ignored.
  - `JsonSchemaUploadParserTest`: `description` and the `aliases` block,
    merged.
  - `SchemaUploadValidatorTest`: collisions dropped with a warning.
- **`NumberReaderTest`:** digits, commas, decimals, compounds, `a dozen`,
  and partial reads rejected.
- **Parser tests on an alias-free schema:**
  - `clients in mumbai` → customers, 0.85, one interpretation;
  - `custmers in pune` → 0.85, a `TYPO` interpretation;
  - `customers in new delhi` → `city = 'New Delhi'`;
  - `orders above two hundred and fifty` → `amount > 250`;
  - `top twenty customers` → limit 20;
  - `cost of products` with `price` and `cost_price` both present → decline
    naming both;
  - a value stored in two columns → decline.
- **`DialectParityTest`:** the same questions against the MongoDB demo
  produce identical intents.
- **Regression guard:** every existing rule-engine and generation test
  passes unchanged, same output and same confidence.
- **Frontend:** `RuleTracePanel` renders the "Read as" block, and a Schema
  source card renders aliases.

## 10. Out of scope

- Synonyms for data values.
- Joins across three or more tables (sub-project 3).
- Dates (sub-project 4).
- New question shapes (sub-project 2).
- Analysis and optimization thresholds.
- Learning synonyms from history or from LLM answers.

## 11. Docs

- **`RULES.md`:**
  - E4, E5, E13, E14, E15 and E20 updated;
  - a new note, "Matching words to the schema", for the match kinds and
    precedence;
  - the three new declines added to the decline table;
  - the rule count updated if a rule number is added.
- **`PROJECT.md`:** the session is recorded.
