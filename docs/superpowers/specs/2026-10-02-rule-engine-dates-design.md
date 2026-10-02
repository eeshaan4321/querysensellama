# Rule engine dates — design

**Date:** 2026-10-02
**Status:** approved in conversation; awaiting review of this written spec.
Nothing below is built.
**Spans:** `backend/`
**Part of:** the rule engine adaptive to any schema. This is sub-project 4 of 4:
1. Vocabulary.
2. Question shapes.
3. Joins across three or more tables.
4. Dates (this spec).

**Builds on:**
- **Sub-project 1:** `NumberReader`, `SchemaLexicon`.
- **Sub-project 2:** condition spans, ranking with limits, `adjectives.json`.
- **Sub-project 3:** chain-wide column binding.

---

## 1. Goal

Date expressions become real date ranges on the right date column, in both
dialects. Today, E3 declines every question that mentions a period of time;
that rule is replaced. Anything the reader can't read in full still declines,
with the reason.

**Success means:**
- Each example in §4 builds by rule in MySQL and MongoDB, and the trace shows
  the exact range and column.
- Every question that builds today builds byte-for-byte the same.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| What "today" is | The backend's current date in a configured time zone. A property can pin it. |
| Expressions | Relative periods, named periods, open and closed ranges, recency superlatives. |
| Dialects | Both, always. |

## 3. Today

- `querysense.rules.time-zone` sets the zone, default `Asia/Kolkata`.
- `querysense.rules.today` optionally pins today's date (ISO `2026-10-02`) for
  tests and demos.
- A `Clock` bean, `rulesClock`, provides both, and the parser takes it.
- Weeks start on Monday.

## 4. Expressions — `DateReader`

`DateReader.read(String text, LocalDate today)` finds the first date
expression in the question. It returns a `DateRange(LocalDate from,
LocalDate until, int start, int end, String phrase)`, half-open: `from`
inclusive, `until` exclusive, either end possibly null for an open range.

### 4.1 Relative periods

| Expression | Range (today = Fri 2 Oct 2026) |
|---|---|
| `today` | [2026-10-02, 2026-10-03) |
| `yesterday` | [2026-10-01, 2026-10-02) |
| `this week` | [2026-09-28, 2026-10-05) |
| `last week` / `previous week` / `past week` | [2026-09-21, 2026-09-28) |
| `next week` | [2026-10-05, 2026-10-12) |
| `this month` / `last month` / `next month` | [2026-10-01, 2026-11-01) / [2026-09-01, 2026-10-01) / [2026-11-01, 2026-12-01) |
| `this quarter` / `last quarter` | [2026-10-01, 2027-01-01) / [2026-07-01, 2026-10-01) |
| `this year` / `last year` | [2026-01-01, 2027-01-01) / [2025-01-01, 2026-01-01) |
| `last 7 days` / `past three weeks` / `last 2 months` | [today − N units, tomorrow) |

### 4.2 Named periods

Each follows `in`, `during` or `for`.

| Expression | Range |
|---|---|
| `in 2025` | [2025-01-01, 2026-01-01) |
| `in march 2025` / `in mar 2025` | [2025-03-01, 2025-04-01) |
| `in q1 2025` / `in the first quarter of 2025` | [2025-01-01, 2025-04-01) |
| `in january` (no year) | its most recent occurrence that has started: [2026-01-01, 2026-02-01) |
| `in november` (no year, asked in October 2026) | [2025-11-01, 2025-12-01) |

### 4.3 Single dates

`on 1 march 2025`, `on march 1 2025`, `on march 1, 2025` and `on
2025-03-01` each give a one-day range.

### 4.4 Open and closed ranges

`X` is any period or single date above.

| Expression | Range |
|---|---|
| `since X` / `from X onwards` | [start of X, —) |
| `before X` | [—, start of X) |
| `after X` | [end of X, —) |
| `until X` / `till X` / `up to X` | [—, end of X) |
| `between X and Y` / `from X to Y` | [start of X, end of Y) |

### 4.5 Recency superlatives

These come from `adjectives.json` (sub-project 2) as entries of type
`date`:
- `latest`, `newest`, `most recent` → sort by the date column, descending;
- `oldest`, `earliest` → ascending.

The limit rules are the same as ranking: a number is the limit; a singular
subject is 1; a plural subject is sorted only.

### 4.6 What still declines

A recognised date word (today's E3 pattern, plus month names after a
preposition and the words above) that the reader can't read as a whole
expression declines: `The question mentions a time period ("around
christmas") this engine can't read as dates, so it went to the language
model instead.`

## 5. Which date column

1. **The column the question names:**
   - its name through the vocabulary matcher (`order date`);
   - or the column's stem, its name without `_date`/`_at`/`_on`/`date`,
     appearing as a word or with `-ed`/`-d` (`hired since 2020` →
     `hire_date`; `ordered in 2025` → `order_date`).

   This is searched across the chain (sub-project 3).
2. **Otherwise the subject's only date-typed column.** If the subject has
   none, the chain's only date-typed column.
3. **Several date columns, none named:** a decline naming them.
4. **No date column at all:** a decline.

Date types come from `SchemaSnapshot.fieldTypes`:
- MySQL `DATE`, `DATETIME`, `TIMESTAMP` (any case);
- MongoDB `date`.

The built-in MySQL schema gains `KnownSchema.FIELD_TYPES`, taken from
`mysql/schema.sql`, which it lacks today.

## 6. Writing it

- `QueryIntent.Literal.date(LocalDate day, ZoneId zone)`:
  - its text is the ISO date;
  - its value is a `java.util.Date` at local midnight in the zone.
- **SQL:** `order_date >= '2026-09-01' AND order_date < '2026-10-01'`, or one
  side for an open range.
- **MongoDB:**
  - `{order_date: {$gte: ISODate("2026-08-31T18:30:00Z"), $lt:
    ISODate("2026-09-30T18:30:00Z")}}`.
  - `MongoEmitter`'s JSON settings gain a date converter that writes
    `ISODate("…")`. Only dates are affected, so every existing output is
    unchanged.
  - QuerySense's MongoDB parser already reads `ISODate(...)`, so analysis and
    rewrite keep working.
- **Spans:** the date expression's span is consumed, so `in` of `in january`
  is never read as an E14 value filter.
- **Trace and confidence:**
  - A `DATE_RANGE` binding: `"last month"` → `orders.order_date from
    2026-09-01 up to 2026-10-01`.
  - Confidence stays 0.95. A date is read by fixed rules, not interpreted.

## 7. Errors

- An unreadable date expression.
- No date column.
- Several date columns, none named.
- A date range combined with `between` numbers in the same condition: the
  reader takes dates first, and sub-project 2's numeric `between` applies
  only when the ends are numbers.

## 8. Testing

All tests are written first.
- **`DateReaderTest`:** every row of §4, against a pinned today of Friday
  2026-10-02, plus the year boundary (last month asked in January), a leap
  year, and partial reads that decline.
- **`DatesTest`:** column selection by name, by stem, by the only date column,
  and the two declines; emission in both dialects; consumed spans; recency.
- **`MongoEmitterTest`:** `ISODate` output for dates, and no change for any
  existing case.
- **`DialectParityTest`:** `orders from last month` and `employees hired since
  2020` now build in both dialects. Today they decline in both.
- **Regression guard:** the full suite passes. Questions that declined because
  of E3 now build, and the tests asserting those declines are updated, each
  noted.

## 9. Out of scope

- Times of day.
- Fiscal years.
- Holidays.
- Week numbers.
- Relative expressions in other languages.
- Time-zone-aware DATETIME columns stored in UTC on MySQL: they are compared
  as written.

## 10. Docs

- **`RULES.md`:** E3 is replaced by a date rule, with the column-selection
  order and the declines.
- **`PROJECT.md`:** the session is recorded, and the roadmap is marked
  complete.
