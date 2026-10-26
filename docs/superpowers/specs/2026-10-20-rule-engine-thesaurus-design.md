# Rule engine thesaurus — design

Date: 2026-10-20 · Branch: `feature/schema-adaptive-rule-engine`

## Intent

The rule engine should understand a question worded differently from the
schema, on **any** uploaded schema, with no aliases or other per-file setup.
The motivating case: a panel uploads a schema we have never seen and asks
questions in their own words ("show all pupils", "list the teachers",
"students admitted in 2024"). Today those reach the schema only through the
built-in synonym list (`querybuilder/synonyms.json`, 12 groups of mostly shop
vocabulary), so most rewordings decline and go to the LLM.

## The one rule that outranks coverage

**A wrong confident answer is far worse than a fallback.** The user's words:
a wrong link in front of the panel would be "very bad". Coverage is wanted —
the user asked for more of it — but every design choice below still prefers
declining to guessing. A link that is sometimes wrong is a failure even if it
raises the hit rate.

## Goals

- Reworded nouns reach tables and columns of any schema: `pupils` →
  `students`, `teachers` → `faculty` or `instructors`, `physicians` →
  `doctors`, `given name` → `first_name`.
- A verb reaches the column named by its noun: `admitted in 2024` →
  `admission_year`, `enrolled last month` → `enrollment_date`, `born after
  1990` → `birth_date`.
- Works offline and is deterministic: same question, same schema, same SQL.
- Every such reading is listed under "Read as" and reports confidence 0.85.
- Same behaviour for MySQL and MongoDB (they share `SchemaLexicon`).

## Non-goals

- Verb-to-verb synonyms ("joined" for "admitted"), adjectives and
  free phrasing.
- Values. As today, values never use synonyms: `bombay` does not find
  `Mumbai`.
- Any model, embedding or network call. The LLM stays in exactly one place
  (the fallback).

## Anti-goals (would make this a failure even if it works)

- A loose link: a word reaching a table or column through a secondary or
  distant meaning (`class` → `courses`, `module` → `faculty`).
- A link that returns a **different set of rows** from the one asked about:
  "surgeons" reaching a `physicians` table with no filter would list every
  physician. A narrower word is linked only together with a filter taken
  from the stored data (source 4); a broader word never is.
- A word the parser reads as an instruction (`count`, `total`, `top`, `show`,
  `delete` …) becoming a link of any kind.
- Any existing test changing its expected output.

## Decided

Four new sources of vocabulary, tried in this order after everything
stricter has failed. Sources 1 and 2 are noun links, source 3 is the verb
link, source 4 turns a narrower noun into a table plus a filter.

### 1. Hand-written synonyms, widened (curated)

`querybuilder/synonyms.json` stays first and grows from 12 groups to roughly
50, covering the domains capstone and demo schemas use most: education,
healthcare, HR, retail, library, banking, hotels, transport, events. Examples
of the kind of group added:

- `faculty, teacher, professor, lecturer, instructor`
- `student, pupil, learner`
- `doctor, physician`
- `patient` / `ward` / `appointment, booking` / `book, title`
- `account, ledger` (only if no clash) / `guest` / `flight` / `vehicle`

Every group is reviewed by a person before it is added. The existing guards
stay: no word in two groups, no instruction word (`SynonymListTest`). This is
where most of the extra coverage comes from, because a person can see that
"teachers" means a `faculty` table and WordNet cannot.

### 2. WordNet thesaurus (generated)

For words the curated list has never seen.

- **Source:** WordNet, shipped as a small precomputed resource inside the
  backend (`querybuilder/thesaurus.tsv`). Offline, free (its licence permits
  redistribution with its notice), auditable.
- **Tightness test.** Two words are linked only when **the first meaning of
  each, counting only meanings in database-like categories, is the same
  WordNet synset.** WordNet lists a word's meanings most-common first;
  "database-like categories" are the WordNet groups for people, groups,
  artifacts, places, acts, events, possessions, communication, time,
  quantities and attributes. Meanings in other categories (cognition,
  feelings, body, plants, animals, substances …) are skipped when finding
  "the first meaning".

  Skipping rather than rejecting is the coverage increase over a plain
  first-sense rule: `faculty`'s most common meaning overall is the *mental*
  faculty (cognition), so it is skipped and `faculty`'s first database-like
  meaning becomes "the body of teachers and administrators".

  **Third filter — a clearly dominant meaning (added while planning).**
  `order`'s first database-like meaning is "a command", so "show commands"
  would reach an `orders` table. A word — on either side — takes part in
  thesaurus links only when that meaning carries a clear majority of the
  word's WordNet usage counts. The exact threshold is set in the plan's
  first task from the real data, and must keep `orders`/`commands` unlinked.

  Expected behaviour, **to be verified against WordNet as the first step of
  the plan** — these are the designer's reading, not checked facts:
  - `pupil` ↔ `student`: same first meaning → linked.
  - `class` ↔ `course`: `class`'s first database-like meaning is "a
    category" → not linked.
  - `module` ↔ `faculty`: shared only through the cognition meaning, which
    is skipped → not linked.
  - `given name` ↔ `first name`: same synset → linked.
- **Generated, then reviewed.** A one-off script writes the resource: one
  line per lemma (up to three words) mapping it to the synset of its first
  database-like meaning, kept only when another lemma shares that synset.
  Instruction words and stopwords are dropped at load. The script, the
  generated file and the WordNet licence notice are committed. A person reads
  the links that touch the four acceptance schemas before the file is
  accepted.

### 3. Verb → noun (WordNet derivation)

WordNet records which nouns are formed from which verbs (`admit` →
`admission`, `enroll` → `enrollment`, `bear`/`born` → `birth`). A verb in
the question is turned into its noun, and the noun is matched against
**column names only** — the same column matching E3 already does for `hired`
→ `hire_date`, but no longer limited to identical spelling.

- Only the verb's first meaning is used, and only its derived noun in a
  database-like category.
- **Year columns (added while planning).** `admission_year` is an integer,
  not a date, and E3 only reads date-typed columns. An integer column whose
  name ends in `year` is therefore also a date column for **whole-year**
  ranges (`in 2024` → `= 2024`; `since 2020` → `>= 2020`; `between 2019 and
  2021` → `>= 2019 AND < 2022`). A range that doesn't start and end on a
  year boundary (`last month`) on such a column declines.
- Never applied to a command or instruction word, so `show`, `count`,
  `delete`, `remove`, `list` can never become columns.
- Never reaches a table or a value.
- If the noun reaches two columns, the existing ambiguity decline applies.

### 4. Narrower word plus a filter from the data

A word naming a *kind of* what a table holds (`surgeons` on a `physicians`
table) is answered as that table filtered to the kind:
`SELECT * FROM physicians WHERE department = 'Surgery'`. The filter is what
makes it correct — the rows returned are exactly the ones asked about.

All three must hold, or the word stays unread and the engine declines as
today:

1. **Kind of.** The word's first database-like meaning is, in WordNet, a
   direct or near kind of the table's first database-like meaning (at most
   two steps up the is-a ladder). `surgeon` is a kind of `physician`.
2. **The data names it.** Exactly **one column** of that table stores a
   value equal, ignoring case, to the word or one of its WordNet-related
   forms: singular/plural, and derivations (`surgeon` → `Surgeon`,
   `Surgery`, `Surgical`). Values are looked up the way E14 already does,
   from the stored data.
3. **Exact.** Whole stored value only. `General Surgery` does not match
   `surgeon`; two columns holding a match (`department` and `specialty`)
   decline.

It reaches a table whether or not the question also names it ("show
surgeons" and "physicians who are surgeons" both work). It combines with
other filters like any E14 value. Recorded as `NARROWER` ("a kind of, from
your data"), 0.85, and "Read as" shows `"surgeons" → physicians.department =
'Surgery'`.

Further examples: `cardiologists` → `department = 'Cardiology'`; `sedans`
on `vehicles` → `body_type = 'Sedan'`; `interns` on `employees` → `role =
'Intern'`.

The reverse — a broader word (`people` → `patients`) — stays rejected: no
filter can make it correct.

### 5. Typo correction

Unchanged, still last.

### Order in full

- **Tables:** exact → inflection → declared alias → curated synonym →
  **WordNet** → **narrower word + filter** → typo.
- **Columns:** exact → inflection/suffix/owner → declared alias →
  abbreviation → curated synonym → **WordNet** → **verb → noun** → typo.

### Shared rules

- **Ambiguity declines.** A word reaching two tables, or two columns of one
  table, declines with the existing `"<word>" could mean A or B, so this
  engine won't pick one.` A stricter match on any item always beats a looser
  one on another.
- **Match kinds.** Curated synonyms keep `SYNONYM`. WordNet links get
  `THESAURUS` ("dictionary synonym"), verb links get `DERIVED`
  ("from the verb"), narrower words get `NARROWER` ("a kind of, from your
  data"). All count as interpreted: 0.85.
- WordNet links may reach a whole column name or one word of a multi-word
  column, as curated synonyms now do. Plural and singular forms are tried.

## Considered and rejected

- **More-specific → more-general words without a filter** (`surgeons` → all
  of `physicians`): the answer would include rows the question excluded.
  Rejected under the anti-goals; source 4 allows it only with a filter the
  data supplies.
- **More-general → more-specific** (`people` → `patients`): the same
  problem the other way.
- **Asking the LLM for aliases at upload:** best coverage, but uses the
  model in a second place.

## What this will still not cover

- Domain words the curated list doesn't have and WordNet doesn't link, e.g.
  `earnings` → `ctc_lpa` (`ctc` is not an English word).
- Verb-to-verb rewording: "students who **joined** in 2024".
- Anything WordNet links only through a rare meaning.

A schema owner can still declare aliases, and the curated list can grow.

## Acceptance

- A table of reworded questions over **four schemas** — the built-in shop
  demo, `campus_db`-shaped, `company`-shaped, and one schema none of the
  rules was written against (a hospital: `patients`, `physicians`,
  `admissions`, `wards`; `physicians.department` holding `Surgery`,
  `Cardiology` …) — each with the SQL it must build. It covers all four new
  sources.
- A **guard list** of cases that must *not* link, each asserted to decline
  or stay unread: `class`/`courses`, `module`/`faculty`,
  `faculty`/`ability`, `section`/`department`, `people`/`patients`,
  `surgeons` when no column stores a surgery value, `surgeons` when two
  columns do, `surgeons` when only `General Surgery` is stored, every
  instruction word as a verb link, plus any loose pair found while
  reviewing the generated file.
- The full existing suite passes unchanged.
- `RULES.md` gains the new match kinds, the order above and the curated
  domains; the decline table count is unchanged (no new decline kind).

## Left to the builder

- How to obtain WordNet for the generator (NLTK's corpus or the Princeton
  files) and the resource format, as long as it stays small (target under
  4 MB — the narrower-word source needs every database-like noun and its
  parents, not only synonym pairs) and loads once per JVM — this machine
  runs short of memory.
- Exactly which curated groups to add, within the domains above and the
  review rule.
- Whether the table index stores links eagerly per schema snapshot (as
  synonyms are today) or looks them up on demand.
- Frontend label wording.
