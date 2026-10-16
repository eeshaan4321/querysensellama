# Rule engine for any naming — design

**Date:** 2026-10-16
**Status:** approved in conversation; awaiting review of this written spec.
Nothing below is built.
**Spans:** `backend/`
**Builds on:** the four rule engine sub-projects (vocabulary, question shapes,
multi-table joins, dates), all built 2026-10-03.

---

## 1. Goal

The rule engine reads table and column names from the uploaded schema,
whatever they are. No naming style is assumed: no word list, no abbreviation
list, no special column names. A user names a column the way the schema
names it, and the engine finds it.

**Success means:**
- The question matrix in §8 builds the same query shape on six schemas that
  share a structure and differ only in naming style, in MySQL and MongoDB.
- Anything ambiguous still declines, naming the candidates.
- Every question that builds today builds byte-for-byte the same, at the same
  confidence.

## 2. Decisions taken in conversation

| Question | Decision |
|---|---|
| Which naming styles | None assumed. Words come only from the schema's own names. |
| Approach | Derive every phrase from schema names; the parser stops pre-judging column phrases (approach A). B (patch three sites) and C (rename at upload) rejected. |
| Identifying label column | The first text column with ≥ 90% distinct stored values, in declaration order, excluding key and foreign-key columns. |
| Abbreviations (`cust_nm` = "customer name") | Out of scope. `aka:` aliases remain the way to declare a meaning. |
| Dialects | Both, always. |

## 3. Today

Reproduced on 2026-10-16 against `university_practice_9_tables.sql`
(`student_name`, `admission_year`, `department_id`, …):

| Question | Result |
|---|---|
| `show only the name and email of students` | `SELECT student_name, email FROM students;` ✓ |
| `show only the student name of students` | `SELECT * FROM students;` at 0.95 ✗ |
| `show the course name and credits of courses` | `SELECT * FROM courses;` at 0.95 ✗ |
| `show only the student_name and email of students` | `SELECT * FROM students;` at 0.95 ✗ |
| `show students with admission year 2022` | declined: "admission" is not a column ✗ |
| `which departments have more than three professors` | lists `department_id` only, no readable column ✗ |

Causes, all in `QueryIntentParser`:

1. **`namesTable()`** (`:1490`) discards a projection phrase if any word in
   it matches a table. *student* matches `students`, so *student name and
   email* is treated as a table mention.
2. **Pattern capture groups** (`:52-145`) capture column names as
   `([a-z_]+)`, at most two words, never digits. The pattern, not the schema,
   decides how long a column name is; *admission year* is cut to *admission*.
3. **The unread-word check** (`unreadWord`, `:1240`) counts any word that is a
   table or column on its own as read, so a dropped column list passes.
4. **`identifyingColumns()`** (`:1082`) picks the first column plus a column
   literally named `name`, `title` or `label`.
5. **The first column is assumed to be the key** (`:795`, `:1084`).

`SchemaLexicon` is mostly sound: `exactColumn` already resolves
*student name* → `student_name` and suffixes such as *year* →
`admission_year`. Its name splitting (`SchemaBinder.normalize` /
`snakeCase`) handles `_` and lower-to-upper transitions only.

## 4. Name words

A new `NameWords` helper turns any identifier into lowercase words. It splits
on `_`, `-`, `.`, spaces, case transitions (including `ID` in `CustomerID`)
and letter-digit boundaries. Nothing else: no dictionary.

| Name | Words |
|---|---|
| `student_name` | student name |
| `studentName`, `StudentName` | student name |
| `STUDENT_NAME`, `student-name` | student name |
| `CustomerID` | customer id |
| `address2` | address 2 |
| `items.productId` | items product id |
| `cust_nm` | cust nm |

Tables and columns both use it. It replaces `normalize`/`snakeCase` wherever
a schema name becomes words. User input keeps its current normalisation, which
already turns `_` into a space.

## 5. Column phrases

### 5.1 The index

Each column is indexed under phrases built only from its own words. For
`student_name` on `students`:

- **Full phrase:** *student name*, with plural forms of the last word.
- **Without its owner's word:** *name*. Leading words are dropped when they
  equal the table's name or its singular. For an embedded field the owner is
  its parent path (*items product id* → *product id*).
- **Last-word phrase:** *name*, as the current suffix match does.
- **Declared aliases** and **built-in synonyms** as today. Synonyms apply to
  single-word names only.

A phrase that reaches two or more columns of one table is ambiguous: the
engine declines and names the candidates. Lookup order (exact, alias,
synonym, typo) and confidence (0.95; 0.85 for synonym or typo) are
unchanged. Typos still apply only to single words of five or more letters.

### 5.2 Reading a phrase from the question

Patterns match instruction words only: `with`/`whose`/`where`, comparison
operators, `between`, `not`, `order by`/`sort by`, `top … by`,
`per`/`for each`/`group by`, `distinct`, `which … do`, aggregate words,
`total`, `highest`/`lowest`, and the projection forms `only …` / `… of` /
trailing lists. They no longer capture the column.

One method reads the column:

```
ColumnPhrase.read(text, anchorPosition, direction, tables) -> Optional<Reading>
Reading(column, table, span, binding)
```

It takes the words after (or before) the anchor and tries the longest run
first, down to one word. The longest run tried is the longest indexed phrase
in the active schema. It returns the first run that binds to exactly one
column. If the longest binding run is ambiguous, it declines as today.

The value reader, number reader and operator reader start where the column
span ends.

### 5.3 Column lists

`only …`, `… of`, and trailing lists split on commas and `and` as now. Each
piece is read with longest-match against the subject table. `namesTable()` is
removed: a phrase is a table reference only when it does not resolve to
columns of the subject.

### 5.4 Which tables

The subject first, then the tables on its join chain, as today. A phrase
that binds in more than one of them declines.

### 5.5 The unread-word check

A table or column word counts as read only if it lies inside a span that a
binding used, which stored values already require. The check against words
that bind on their own is removed.

## 6. Keys and identifying columns

### 6.1 Primary key on the snapshot

`SchemaSnapshot` carries each table's primary key:

- **MySQL upload:** from `ParsedColumn.primaryKey`, already parsed.
- **MongoDB:** `_id`.
- **Built-in demos:** declared (`id`; `_id`).
- **None declared:** the column other tables' foreign keys point at;
  otherwise the first column.

`COUNT(subject.columns().get(0))` and `identifyingColumns()` use it.

### 6.2 The label column

`identifyingColumns()` returns the primary key, plus the first column that:

- is text-typed (VARCHAR, CHAR, TEXT; `string` in MongoDB);
- is not a key or foreign-key column; and
- has ≥ 90% distinct values among its stored rows.

The distinct ratio is read through `SchemaMetadata` and cached with the
snapshot. No qualifying column means the key alone.

| Table | Picked | Note |
|---|---|---|
| `customers` (demo) | `id`, `name` | as today |
| `orders` (demo) | `id` | `status` has 5 values in 25 rows; as today |
| `departments` (upload) | `department_id`, `department_name` | |
| `students` (upload) | `student_id`, `student_name` | |

## 7. MongoDB

The same changes apply. `NameWords` splits on `.`, the key is `_id`, and the
label column is the first `string` field with ≥ 90% distinct values. The
emitters are unchanged: they already receive resolved names.

## 8. Testing

1. **Six schemas, one structure.** Four tables with declared foreign keys:
   `departments` ← `professors`, `departments` ← `students`,
   `professors` ← `courses`. Naming differs only:

   | Style | Example columns |
   |---|---|
   | Prefixed snake | `student_name`, `admission_year`, `department_id` |
   | camelCase | `studentName`, `admissionYear`, `departmentId` |
   | PascalCase + ID | `StudentName`, `AdmissionYear`, `DepartmentID` |
   | UPPER_SNAKE | `STUDENT_NAME`, `ADMISSION_YEAR` |
   | Plain | `name`, `year`, `department_id` |
   | Abbreviated | `stu_nm`, `adm_yr`, `dept_id` |

2. **One question matrix across all six.** Questions use each schema's own
   words. Shapes: column list, equality filter, comparison, between,
   not-equal, order by, top-by, group by, distinct, several totals, join
   filter, grouped count with identifying columns, a date. Each must build
   the same shape with that schema's names.
3. **Boundary:** on the abbreviated schema *stu nm* binds; *student name*
   declines.
4. **Ambiguity:** with `academic_year` and `semester_year` on one table,
   *year* declines and *academic year* binds. A phrase binding on two chain
   tables declines.
5. **Regression:** `show only the student name and email of students` builds
   `SELECT student_name, email FROM students;`, never `SELECT *`.
6. **Guard:** the existing suite passes unchanged.
7. Items 1–5 run for MySQL and MongoDB.

## 9. Out of scope

- Inferring meaning the schema doesn't state, including abbreviations.
- Everything PROJECT.md lists as LLM-only: nested and/or, HAVING with OR,
  self-joins, chains beyond four tables, subqueries, times of day,
  column-to-column comparisons.
- Frontend changes. The rule trace already shows each binding.
