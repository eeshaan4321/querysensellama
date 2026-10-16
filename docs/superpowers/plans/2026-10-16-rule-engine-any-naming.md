# Rule Engine for Any Naming — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The rule engine reads table and column names from the uploaded schema whatever their style, so `student name`, `admission year` and `stu nm` bind as readily as `name` and `city`.

**Architecture:** One splitter (`NameWords`) turns every schema name into words. `SchemaLexicon` matches typed phrases against those words, including a column's name without its table's word. A new `ColumnPhrase` reader lets the parser's patterns find instruction words only and ask the schema for the longest column phrase beside them. Primary keys travel on `SchemaSnapshot`; the readable "label" column is chosen by data (≥ 90% distinct).

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5, AssertJ, Mockito, Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-10-16-rule-engine-any-naming-design.md`

## Global Constraints

- No naming style is assumed: no word list, no abbreviation list, no column treated specially because of its name.
- Ambiguous still declines, naming the candidates. Adapting never becomes guessing at 0.95.
- Lookup order exact → alias → synonym → typo and confidence 0.95 / 0.85 are unchanged.
- Every question that builds today builds byte-for-byte the same at the same confidence: the existing suite must pass with no test edited.
- MySQL and MongoDB both.
- Code style: one-line class Javadoc summaries only; no architecture, history or rationale comments in source (user preference).
- Run tests from `backend/`: `./mvnw -q test -Dtest=<Class>` for one class, `./mvnw -q test` for the suite. Do not start the application; the user tests it personally.
- Commit only the files the task names (`git add <paths>`), never `git add -A`. The working tree carries unrelated uncommitted work.

## Departures from the spec

1. **§5.5, the unread-word check.** Table matches don't expose text spans, so a table word still counts as read anywhere. A column word counts only if the column it names is used by the built query. This still declines the reproduced bug, because `name` and `email` name columns the query doesn't use.
2. **§5.1, ambiguity at the suffix tier.** Today two columns sharing a last word is Unbound. It becomes Ambiguous only after aliases and synonyms have also failed, so a declared alias still wins.
3. **§5.1, run-together names.** A typed phrase also matches when its words, with spaces removed, equal the column's words with spaces removed. Then `admissionyear` and `studentname` columns bind too. Review Focus item 2.
4. **§6.2, unknown share.** When the distinct share can't be read (no data, MongoDB down, a test mock), the first text column is the label. That keeps the built-in demos' output unchanged under test mocks.

## Review Focus

1. **A column phrase followed by a value word**, e.g. `enrollments with grade a`. Longest-match must stop at `grade` and leave `a` as the value. Test in Task 8.
2. **A user typing the exact camelCase name**, e.g. `students with admissionYear 2022`. Input is lowercased to `admissionyear` and must still bind `admissionYear`. Test in Task 2.
3. **Digits in names**, e.g. `address2`, typed as `address2` or `address 2`. Both bind. Test in Task 2.
4. **A table with a composite primary key.** `primaryKeyFor` falls back instead of picking one half. Test in Task 3.
5. **A table whose text columns all repeat values** (only categories, like `orders.status`). Grouped counts show the key alone, never a categorising column. Test in Task 5.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `backend/src/main/java/com/querysense/backend/querybuilder/NameWords.java` | Create | Split any identifier into lowercase words |
| `backend/src/main/java/com/querysense/backend/querybuilder/SchemaLexicon.java` | Modify | Index and match names through `NameWords`; owner-stripped and suffix tiers; ambiguity |
| `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java` | Modify | Primary key, FK columns, text types, longest column phrase |
| `backend/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java` | Modify | Carry primary keys; `primaryKeyFor` |
| `backend/src/main/java/com/querysense/backend/schema/ddl/ParsedSchema.java` | Modify | `primaryKeys()` from parsed columns |
| `backend/src/main/java/com/querysense/backend/schema/upload/SchemaUploadService.java` | Modify | Pass primary keys into the snapshot |
| `backend/src/main/java/com/querysense/backend/schema/KnownSchema.java`, `KnownMongoSchema.java` | Modify | Declare `id` / `_id` |
| `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadata.java` | Modify | `distinctShare` default method |
| `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java` | Modify | MySQL `distinctShare` |
| `backend/src/main/java/com/querysense/backend/analysis/MongoSchemaMetadata.java` | Modify | MongoDB `distinctShare` |
| `backend/src/main/java/com/querysense/backend/querybuilder/IdentifyingColumns.java` | Create | Key plus label column of a table |
| `backend/src/main/java/com/querysense/backend/querybuilder/ColumnPhrase.java` | Create | Longest run of question words naming one column |
| `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` | Modify | Use the above at every column site |
| Tests under `backend/src/test/java/com/querysense/backend/...` | Create/Modify | Listed per task |
| `PROJECT.md` | Modify | Record the result |

---

### Task 0: Settle the pending working-tree changes (user decision, no code)

The files this plan modifies, `QueryIntentParser.java` above all, already carry uncommitted work. A task commit would sweep it in with this feature.

- [ ] **Step 1:** Ask the user whether to commit the pending work first, as its own commit(s), or to let it ride along. Do what they choose. Don't stash, reset or discard anything.
- [ ] **Step 2:** Run `./mvnw -q test` from `backend/` and record the result as the baseline. If anything already fails, report it to the user before Task 1. Those failures are not this plan's to fix.

---

### Task 1: NameWords

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/NameWords.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/NameWordsTest.java`

**Interfaces:**
- Produces: `static List<String> NameWords.words(String name)`, `static String NameWords.phrase(String name)` (words joined by one space), `static String NameWords.compact(String phrase)` (spaces removed).

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NameWordsTest {

    @Test
    @DisplayName("every naming style splits into the same words")
    void styles() {
        assertThat(NameWords.phrase("student_name")).isEqualTo("student name");
        assertThat(NameWords.phrase("studentName")).isEqualTo("student name");
        assertThat(NameWords.phrase("StudentName")).isEqualTo("student name");
        assertThat(NameWords.phrase("STUDENT_NAME")).isEqualTo("student name");
        assertThat(NameWords.phrase("student-name")).isEqualTo("student name");
        assertThat(NameWords.phrase("CustomerID")).isEqualTo("customer id");
        assertThat(NameWords.phrase("DepartmentID")).isEqualTo("department id");
        assertThat(NameWords.phrase("XMLData")).isEqualTo("xml data");
        assertThat(NameWords.phrase("address2")).isEqualTo("address 2");
        assertThat(NameWords.phrase("items.productId")).isEqualTo("items product id");
        assertThat(NameWords.phrase("cust_nm")).isEqualTo("cust nm");
        assertThat(NameWords.phrase("_id")).isEqualTo("id");
    }

    @Test
    @DisplayName("words come back as a list, and compact joins them")
    void wordsAndCompact() {
        assertThat(NameWords.words("AdmissionYear")).isEqualTo(List.of("admission", "year"));
        assertThat(NameWords.compact("admission year")).isEqualTo("admissionyear");
        assertThat(NameWords.words("")).isEmpty();
        assertThat(NameWords.words(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=NameWordsTest`
Expected: compilation failure, `cannot find symbol: class NameWords`.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.querybuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Splits any table or column name into lowercase words. */
public final class NameWords {

    private NameWords() {}

    public static List<String> words(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String spaced = name
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2");
        return Arrays.stream(spaced.split("[^A-Za-z0-9]+"))
                .filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT))
                .toList();
    }

    public static String phrase(String name) {
        return String.join(" ", words(name));
    }

    public static String compact(String phrase) {
        return phrase.replace(" ", "");
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q test -Dtest=NameWordsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/NameWords.java backend/src/test/java/com/querysense/backend/querybuilder/NameWordsTest.java
git commit -m "feat(rules): NameWords splits any table or column name into words"
```

---

### Task 2: The lexicon matches through NameWords

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaLexicon.java` (`column`, `exactOrAlias`, `exactColumn`, `base`, `build`)
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`dateColumn`, `extractTrailingColumnPhrase`)
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java` (remove `snakeCase` once unused)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingLexiconTest.java`

**Interfaces:**
- Consumes: `NameWords.words/phrase/compact` (Task 1).
- Produces: `SchemaLexicon.column(...)` may now return `Ambiguous` for a phrase that two columns end with. `static List<String> SchemaLexicon.ownerWords(KnownSchema.Table)` returns the table's words and their singular form, used by Task 6.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnyNamingLexiconTest {

    private final ActiveSchemaProvider schemas = new ActiveSchemaProvider();
    private final SchemaLexicon lexicon = new SchemaLexicon(schemas, Synonyms.builtIn());

    private void publish(Dialect dialect, KnownSchema.Table... tables) {
        schemas.publish(new SchemaSnapshot("upload-n", "n", "querysense_uploads", List.of(tables), List.of(),
                Map.of(), Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of()));
    }

    private String bound(KnownSchema.Table table, String phrase) {
        SchemaLexicon.ColumnMatch match = lexicon.column(table, phrase, SchemaAliases.TableAliases.NONE);
        assertThat(match).as(phrase).isInstanceOf(SchemaLexicon.Bound.class);
        return ((SchemaLexicon.Bound) match).binding().item();
    }

    @Test
    @DisplayName("a prefixed column binds by its full words and without its table's word")
    void prefixed() {
        KnownSchema.Table students = new KnownSchema.Table("students",
                List.of("student_id", "student_name", "admission_year"));
        publish(Dialect.MYSQL, students);
        assertThat(bound(students, "student name")).isEqualTo("student_name");
        assertThat(bound(students, "name")).isEqualTo("student_name");
        assertThat(bound(students, "admission year")).isEqualTo("admission_year");
        assertThat(bound(students, "admission years")).isEqualTo("admission_year");
        assertThat(bound(students, "year")).isEqualTo("admission_year");
    }

    @Test
    @DisplayName("camelCase, PascalCase and UPPER_SNAKE bind the same way")
    void otherStyles() {
        KnownSchema.Table camel = new KnownSchema.Table("students", List.of("studentId", "studentName", "admissionYear"));
        publish(Dialect.MYSQL, camel);
        assertThat(bound(camel, "student name")).isEqualTo("studentName");
        assertThat(bound(camel, "admissionyear")).isEqualTo("admissionYear");

        KnownSchema.Table pascal = new KnownSchema.Table("Students", List.of("StudentID", "StudentName"));
        publish(Dialect.MYSQL, pascal);
        assertThat(bound(pascal, "student id")).isEqualTo("StudentID");
        assertThat(bound(pascal, "name")).isEqualTo("StudentName");

        KnownSchema.Table upper = new KnownSchema.Table("STUDENTS", List.of("STUDENT_ID", "STUDENT_NAME"));
        publish(Dialect.MYSQL, upper);
        assertThat(bound(upper, "student name")).isEqualTo("STUDENT_NAME");
    }

    @Test
    @DisplayName("digits split off and still bind when typed together")
    void digits() {
        KnownSchema.Table people = new KnownSchema.Table("people", List.of("id", "address2"));
        publish(Dialect.MYSQL, people);
        assertThat(bound(people, "address 2")).isEqualTo("address2");
        assertThat(bound(people, "address2")).isEqualTo("address2");
    }

    @Test
    @DisplayName("abbreviations bind only as written")
    void abbreviations() {
        KnownSchema.Table students = new KnownSchema.Table("students", List.of("stu_id", "stu_nm"));
        publish(Dialect.MYSQL, students);
        assertThat(bound(students, "stu nm")).isEqualTo("stu_nm");
        assertThat(lexicon.column(students, "student name", SchemaAliases.TableAliases.NONE))
                .isInstanceOf(SchemaLexicon.Unbound.class);
    }

    @Test
    @DisplayName("a word two columns end with is ambiguous, and their full words still bind")
    void ambiguous() {
        KnownSchema.Table sections = new KnownSchema.Table("sections",
                List.of("section_id", "academic_year", "semester_year"));
        publish(Dialect.MYSQL, sections);
        SchemaLexicon.ColumnMatch year = lexicon.column(sections, "year", SchemaAliases.TableAliases.NONE);
        assertThat(year).isInstanceOf(SchemaLexicon.Ambiguous.class);
        assertThat(((SchemaLexicon.Ambiguous) year).ambiguity().candidates())
                .containsExactlyInAnyOrder("academic_year", "semester_year");
        assertThat(bound(sections, "academic year")).isEqualTo("academic_year");
    }

    @Test
    @DisplayName("table names in any style are found in a question")
    void tables() {
        publish(Dialect.MYSQL, new KnownSchema.Table("StudentRecords", List.of("id")));
        SchemaLexicon.TableMatches matches = lexicon.tables("show student records", Dialect.MYSQL);
        assertThat(matches.hits()).hasSize(1);
        assertThat(matches.hits().get(0).table().name()).isEqualTo("StudentRecords");
        assertThat(lexicon.tables("each student record", Dialect.MYSQL).hits()).hasSize(1);
    }

    @Test
    @DisplayName("an embedded MongoDB field binds without its parent's words")
    void embedded() {
        KnownSchema.Table orders = new KnownSchema.Table("orders", List.of("_id", "items.productId"));
        publish(Dialect.MONGODB, orders);
        assertThat(bound(orders, "product id")).isEqualTo("items.productId");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=AnyNamingLexiconTest`
Expected: FAIL in `otherStyles` (`admissionyear`), `digits`, `ambiguous` (Unbound instead of Ambiguous), `tables` (`StudentRecords`), and possibly `embedded`.

- [ ] **Step 3: Implement in `SchemaLexicon`**

Replace `exactColumn` and `base`, and change `column` and `exactOrAlias` to use the new `ColumnMatch`-returning version:

```java
    public Optional<Binding> exactOrAlias(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        String token = SchemaBinder.normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        if (exactColumn(table, token) instanceof Bound bound) {
            return Optional.of(bound.binding());
        }
        List<String> aliased = aliasedColumns(table, token, aliases);
        return aliased.size() == 1
                ? Optional.of(new Binding(aliased.get(0), token, MatchKind.SCHEMA_ALIAS))
                : Optional.empty();
    }

    public ColumnMatch column(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        String token = SchemaBinder.normalize(phrase);
        if (token.isEmpty()) {
            return new Unbound();
        }
        ColumnMatch exact = exactColumn(table, token);
        if (exact instanceof Bound) {
            return exact;
        }
        ColumnMatch aliased = pick(table, token, aliasedColumns(table, token, aliases), MatchKind.SCHEMA_ALIAS);
        if (!(aliased instanceof Unbound)) {
            return aliased;
        }
        List<String> synonymous = table.columns().stream()
                .filter(c -> isSynonym(token, base(c)))
                .toList();
        ColumnMatch synonym = pick(table, token, synonymous, MatchKind.SYNONYM);
        if (!(synonym instanceof Unbound)) {
            return synonym;
        }
        if (exact instanceof Ambiguous) {
            return exact;
        }
        if (token.contains(" ") || token.length() < Edits.MIN_TYPO_LENGTH || isKnownWord(table, token, aliases)) {
            return new Unbound();
        }
        List<String> near = table.columns().stream()
                .filter(c -> columnWords(c, aliases).stream().anyMatch(w -> Edits.withinOne(token, w)))
                .toList();
        return pick(table, token, near, MatchKind.TYPO);
    }

    private static ColumnMatch exactColumn(KnownSchema.Table table, String token) {
        String typed = NameWords.phrase(token);
        Set<String> candidates = phraseForms(typed);
        for (String candidate : candidates) {
            for (String col : table.columns()) {
                String words = NameWords.phrase(col);
                if (words.equals(candidate) || NameWords.compact(words).equals(NameWords.compact(candidate))) {
                    return new Bound(new Binding(col, token,
                            candidate.equals(typed) ? MatchKind.EXACT : MatchKind.INFLECTION));
                }
            }
        }
        List<String> owned = table.columns().stream()
                .filter(col -> withoutOwner(table, col).filter(candidates::contains).isPresent())
                .distinct()
                .toList();
        if (!owned.isEmpty()) {
            return pick(table, token, owned, MatchKind.INFLECTION);
        }
        List<String> suffixed = table.columns().stream()
                .filter(col -> candidates.stream().anyMatch(f -> (" " + NameWords.phrase(col)).endsWith(" " + f)))
                .distinct()
                .toList();
        return pick(table, token, suffixed, MatchKind.INFLECTION);
    }

    static List<List<String>> ownerWords(KnownSchema.Table table) {
        List<String> words = NameWords.words(table.name());
        if (words.isEmpty()) {
            return List.of();
        }
        List<String> singular = new ArrayList<>(words);
        singular.set(singular.size() - 1, singular(words.get(words.size() - 1)));
        return singular.equals(words) ? List.of(words) : List.of(words, singular);
    }

    private static Optional<String> withoutOwner(KnownSchema.Table table, String column) {
        List<String> words = NameWords.words(column);
        List<List<String>> owners = new ArrayList<>(ownerWords(table));
        int dot = column.lastIndexOf('.');
        if (dot > 0) {
            owners.add(NameWords.words(column.substring(0, dot)));
        }
        for (List<String> owner : owners) {
            if (!owner.isEmpty() && words.size() > owner.size() && words.subList(0, owner.size()).equals(owner)) {
                return Optional.of(String.join(" ", words.subList(owner.size(), words.size())));
            }
        }
        return Optional.empty();
    }

    private static String base(String column) {
        return NameWords.phrase(column.substring(column.lastIndexOf('.') + 1));
    }
```

In `build(...)`, replace the two lines that compute `name` and `singular`:

```java
            String name = NameWords.phrase(table.name());
            List<List<String>> owners = ownerWords(table);
            String singular = String.join(" ", owners.get(owners.size() - 1));
```

- [ ] **Step 4: Update the two parser call sites**

In `QueryIntentParser.dateColumn`, replace the `stem` and `spaced` lines:

```java
                String spaced = NameWords.phrase(column);
                String stem = spaced.replaceAll(" ?\\b(?:date|at|on)$", "");
```

In `QueryIntentParser.extractTrailingColumnPhrase`, replace the `pattern` construction:

```java
        List<List<String>> owners = SchemaLexicon.ownerWords(table);
        String names = owners.stream().map(w -> Pattern.quote(String.join(" ", w))).collect(Collectors.joining("|"));
        Pattern pattern = Pattern.compile("\\b(?:" + names + ")\\s+([a-z0-9 ]+)$");
```

Then delete `SchemaBinder.snakeCase`. Confirm nothing else calls it: `grep -rn "snakeCase" backend/src` returns nothing.

- [ ] **Step 5: Run the new test, then the suite**

Run: `./mvnw -q test -Dtest=AnyNamingLexiconTest` → PASS.
Run: `./mvnw -q test` → same result as the Task 0 baseline. If a previously passing test now fails, read its assertion before changing anything. The likely cause is a phrase that is now `Ambiguous` where it used to be `Unbound`. Fix the lexicon, not the test.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/SchemaLexicon.java backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingLexiconTest.java
git commit -m "feat(rules): names in any style bind by their words, with or without their table's word"
```

---

### Task 3: Primary keys on the snapshot

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java`
- Modify: `backend/src/main/java/com/querysense/backend/schema/ddl/ParsedSchema.java`
- Modify: `backend/src/main/java/com/querysense/backend/schema/upload/SchemaUploadService.java:87-99`
- Modify: `backend/src/main/java/com/querysense/backend/schema/KnownSchema.java:94-108`, `KnownMongoSchema.java:81-95`
- Test: `backend/src/test/java/com/querysense/backend/schema/PrimaryKeyTest.java`

**Interfaces:**
- Produces: record component `Map<String, String> primaryKeys` (last), `String SchemaSnapshot.primaryKeyFor(KnownSchema.Table table)`, `Map<String, String> ParsedSchema.primaryKeys()`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrimaryKeyTest {

    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_name", "department_id"));
    private static final KnownSchema.Table STUDENTS = new KnownSchema.Table("students",
            List.of("student_name", "student_id", "department_id"));

    private static SchemaSnapshot snapshot(Dialect dialect, Map<String, String> keys,
                                           List<KnownSchema.ForeignKey> fks, KnownSchema.Table... tables) {
        return new SchemaSnapshot("upload-k", "k", "querysense_uploads", List.of(tables), fks, Map.of(),
                Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of(), keys);
    }

    @Test
    @DisplayName("a declared key wins, even when it isn't the first column")
    void declared() {
        assertThat(snapshot(Dialect.MYSQL, Map.of("students", "student_id"), List.of(), STUDENTS)
                .primaryKeyFor(STUDENTS)).isEqualTo("student_id");
    }

    @Test
    @DisplayName("without a declared key, the column foreign keys point at is the key")
    void foreignKeyTarget() {
        KnownSchema.ForeignKey fk = new KnownSchema.ForeignKey("students", "department_id", "departments", "department_id");
        assertThat(snapshot(Dialect.MYSQL, Map.of(), List.of(fk), DEPARTMENTS, STUDENTS)
                .primaryKeyFor(DEPARTMENTS)).isEqualTo("department_id");
    }

    @Test
    @DisplayName("MongoDB uses _id, and anything else falls back to the first column")
    void fallbacks() {
        KnownSchema.Table orders = new KnownSchema.Table("orders", List.of("status", "_id"));
        assertThat(snapshot(Dialect.MONGODB, Map.of(), List.of(), orders).primaryKeyFor(orders)).isEqualTo("_id");
        assertThat(snapshot(Dialect.MYSQL, Map.of(), List.of(), STUDENTS).primaryKeyFor(STUDENTS)).isEqualTo("student_name");
    }

    @Test
    @DisplayName("uploaded DDL declares single-column keys; a composite key is left to the fallback")
    void parsed() {
        ParsedTable students = new ParsedTable("students", List.of(
                new ParsedColumn("student_name", "VARCHAR", List.of(), false, false, false, false),
                new ParsedColumn("student_id", "INT", List.of(), true, true, false, true)), List.of());
        ParsedTable enrolments = new ParsedTable("enrolments", List.of(
                new ParsedColumn("student_id", "INT", List.of(), true, false, false, true),
                new ParsedColumn("course_id", "INT", List.of(), true, false, false, true)), List.of());
        assertThat(new ParsedSchema(List.of(students, enrolments), List.of()).primaryKeys())
                .isEqualTo(Map.of("students", "student_id"));
    }

    @Test
    @DisplayName("the built-in demos declare their keys")
    void builtIns() {
        assertThat(KnownSchema.builtIn().primaryKeyFor(KnownSchema.CUSTOMERS)).isEqualTo("id");
        SchemaSnapshot mongo = KnownMongoSchema.builtIn();
        assertThat(mongo.primaryKeyFor(mongo.tables().get(0))).isEqualTo("_id");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=PrimaryKeyTest`
Expected: compilation failure (13-argument constructor, `primaryKeyFor`, `primaryKeys()` don't exist).

- [ ] **Step 3: Implement**

In `SchemaSnapshot`, add the component `Map<String, String> primaryKeys` after `aliases`. Turn the old 12-argument canonical form into a delegating constructor:

```java
    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes,
                          Map<String, Map<String, String>> fieldTypes,
                          Map<String, Map<String, List<String>>> enums,
                          Map<String, SchemaAliases.TableAliases> aliases) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, fieldTypes, enums,
                aliases, Map.of());
    }

    public String primaryKeyFor(KnownSchema.Table table) {
        String declared = primaryKeys.get(table.name());
        if (declared != null) {
            return declared;
        }
        if (dialect == Dialect.MONGODB && table.columns().contains("_id")) {
            return "_id";
        }
        return foreignKeys.stream()
                .filter(fk -> fk.toTable().equalsIgnoreCase(table.name()))
                .map(KnownSchema.ForeignKey::toColumn)
                .filter(table.columns()::contains)
                .findFirst()
                .orElse(table.columns().get(0));
    }
```

The existing 11-argument constructor currently calls `this(..., enums, Map.of())`; it keeps resolving to the 12-argument constructor and needs no edit.

In `ParsedSchema`:

```java
    public Map<String, String> primaryKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (ParsedTable table : tables) {
            List<String> key = table.columns().stream().filter(ParsedColumn::primaryKey).map(ParsedColumn::name).toList();
            if (key.size() == 1) {
                keys.put(table.name(), key.get(0));
            }
        }
        return keys;
    }
```

In `SchemaUploadService`, add `parsed.primaryKeys()` as the last argument after `aliases.aliases()`.

In `KnownSchema.builtIn()`, add the last argument:

```java
                ALIASES,
                TABLES.stream().collect(java.util.stream.Collectors.toMap(Table::name, t -> "id")));
```

In `KnownMongoSchema.builtIn()`, likewise with `t -> "_id"`. Keep the file's existing import style: if it imports `java.util.stream.Collectors`, use the short name.

- [ ] **Step 4: Run the test, then the suite**

Run: `./mvnw -q test -Dtest=PrimaryKeyTest` → PASS. Then `./mvnw -q test` → baseline.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java backend/src/main/java/com/querysense/backend/schema/ddl/ParsedSchema.java backend/src/main/java/com/querysense/backend/schema/upload/SchemaUploadService.java backend/src/main/java/com/querysense/backend/schema/KnownSchema.java backend/src/main/java/com/querysense/backend/schema/KnownMongoSchema.java backend/src/test/java/com/querysense/backend/schema/PrimaryKeyTest.java
git commit -m "feat(schema): the active schema carries each table's primary key"
```

---

### Task 4: Distinct share in schema metadata

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadata.java`
- Modify: `backend/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java`
- Modify: `backend/src/main/java/com/querysense/backend/analysis/MongoSchemaMetadata.java`
- Test: `backend/src/test/java/com/querysense/backend/analysis/DistinctShareTest.java`

**Interfaces:**
- Produces: `default OptionalDouble SchemaMetadata.distinctShare(String tableName, String columnName)`. It returns distinct non-null values ÷ all rows, or empty when unknown. Cached per schema like distinct values. MongoDB adds package-private `long countAll(String table)` and `long countDistinct(String table, String field)`, which tests can override.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistinctShareTest {

    @Test
    @DisplayName("MySQL: distinct values over all rows, read once per schema")
    void mysql() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(new long[]{9, 10});
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        assertThat(metadata.distinctShare("customers", "name")).isEqualTo(OptionalDouble.of(0.9));
        metadata.distinctShare("customers", "name");
        verify(executor, times(1)).withActiveCatalog(any());
    }

    @Test
    @DisplayName("MySQL: an empty table or an unknown column has no share")
    void mysqlUnknown() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(new long[]{0, 0});
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        assertThat(metadata.distinctShare("customers", "name")).isEmpty();
        assertThat(metadata.distinctShare("customers", "no_such_column")).isEmpty();
    }

    @Test
    @DisplayName("MongoDB: distinct values over all documents")
    void mongo() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        provider.publish(new SchemaSnapshot("upload-m", "m", "uploads",
                List.of(new KnownSchema.Table("students", List.of("_id", "studentName"))),
                List.of(), Map.of(), Instant.now(), Dialect.MONGODB, List.of()));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(mock(MongoClient.class), provider, () -> 0L) {
            @Override
            long countAll(String table) {
                return 20;
            }

            @Override
            long countDistinct(String table, String field) {
                return 19;
            }
        };
        assertThat(metadata.distinctShare("students", "studentName")).isEqualTo(OptionalDouble.of(0.95));
        assertThat(metadata.distinctShare("students", "missing")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=DistinctShareTest`
Expected: compilation failure, `distinctShare` / `countAll` don't exist.

- [ ] **Step 3: Implement**

In `SchemaMetadata`:

```java
    default OptionalDouble distinctShare(String tableName, String columnName) {
        return OptionalDouble.empty();
    }
```

In `SchemaMetadataService`, add `private final Map<String, OptionalDouble> shareCache = new ConcurrentHashMap<>();` and clear it in `clearCaches()`. Pull the existing "recognized" check out of `loadDistinctValues` into a private `boolean recognized(String tableName, String columnName)` method that both use. Then add:

```java
    @Override
    public OptionalDouble distinctShare(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return OptionalDouble.empty();
        }
        String key = tableName.toLowerCase() + "." + columnName.toLowerCase();
        return shareCache.computeIfAbsent(key, k -> loadShare(tableName, columnName));
    }

    private OptionalDouble loadShare(String tableName, String columnName) {
        if (!recognized(tableName, columnName)) {
            return OptionalDouble.empty();
        }
        try {
            String sql = "SELECT COUNT(DISTINCT " + columnName + "), COUNT(*) FROM " + tableName;
            long[] counts = explainExecutor.withActiveCatalog(jdbc -> jdbc.queryForObject(sql,
                    (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}));
            return counts == null || counts[1] == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) counts[0] / counts[1]);
        } catch (Exception e) {
            log.warn("Could not count distinct values for {}.{}: {}", tableName, columnName, e.getMessage());
            return OptionalDouble.empty();
        }
    }
```

`recognized` must return false (without the warn-level log) for the unknown column in `mysqlUnknown`. The default built-in MySQL schema has `customers.name`.

In `MongoSchemaMetadata`, add `private final Map<String, OptionalDouble> shareCache = new ConcurrentHashMap<>();` and clear it in the existing `onSchemaChanged` listener next to `distinctValueCache.clear()`. Then add:

```java
    @Override
    public OptionalDouble distinctShare(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return OptionalDouble.empty();
        }
        String key = tableName + "." + columnName;
        OptionalDouble cached = shareCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean declared = activeSchema.findTable(Dialect.MONGODB, tableName)
                .map(t -> t.columns().contains(columnName))
                .orElse(false);
        if (!declared || nowMillis.getAsLong() < unavailableUntil) {
            return OptionalDouble.empty();
        }
        try {
            long all = countAll(tableName);
            OptionalDouble share = all == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) countDistinct(tableName, columnName) / all);
            shareCache.put(key, share);
            return share;
        } catch (MongoTimeoutException | MongoSocketException e) {
            unavailableUntil = nowMillis.getAsLong() + UNAVAILABLE_MS;
            return OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Could not count distinct values for {}.{} in MongoDB: {}", tableName, columnName, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    long countAll(String tableName) {
        return client.getDatabase(activeSchema.catalog(Dialect.MONGODB)).getCollection(tableName).countDocuments();
    }

    long countDistinct(String tableName, String field) {
        Document first = client.getDatabase(activeSchema.catalog(Dialect.MONGODB)).getCollection(tableName)
                .aggregate(List.of(new Document("$group", new Document("_id", "$" + field)),
                        new Document("$count", "n")))
                .first();
        return first == null ? 0 : ((Number) first.get("n")).longValue();
    }
```

Import `org.bson.Document` and `java.util.OptionalDouble`. The anonymous subclass in the test needs `MongoSchemaMetadata` to stay non-final, which it is.

- [ ] **Step 4: Run the test, then the suite**

Run: `./mvnw -q test -Dtest=DistinctShareTest` → PASS. Then `./mvnw -q test` → baseline.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/analysis/SchemaMetadata.java backend/src/main/java/com/querysense/backend/analysis/SchemaMetadataService.java backend/src/main/java/com/querysense/backend/analysis/MongoSchemaMetadata.java backend/src/test/java/com/querysense/backend/analysis/DistinctShareTest.java
git commit -m "feat(metadata): the share of distinct values in a column, for MySQL and MongoDB"
```

---

### Task 5: Identifying columns from schema facts

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/IdentifyingColumns.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`identifyingColumns` removed; three call sites)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/IdentifyingColumnsTest.java`

**Interfaces:**
- Consumes: `SchemaSnapshot.primaryKeyFor` (Task 3), `SchemaMetadata.distinctShare` (Task 4).
- Produces:
  - `String SchemaBinder.primaryKey(KnownSchema.Table)`;
  - `Set<String> SchemaBinder.foreignKeyColumns(KnownSchema.Table)`;
  - `static boolean SchemaBinder.isTextType(String type)`;
  - `static List<String> IdentifyingColumns.of(KnownSchema.Table, SchemaBinder, SchemaMetadata)`.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentifyingColumnsTest {

    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_id", "building", "department_name"));
    private static final KnownSchema.Table PROFESSORS = new KnownSchema.Table("professors",
            List.of("professor_id", "professor_name", "department_id"));
    private static final KnownSchema.Table ORDERS = new KnownSchema.Table("orders",
            List.of("order_id", "status", "placed_on"));

    private SchemaBinder binder;
    private SchemaMetadata metadata;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-i", "i", "querysense_uploads",
                List.of(DEPARTMENTS, PROFESSORS, ORDERS),
                List.of(new KnownSchema.ForeignKey("professors", "department_id", "departments", "department_id")),
                Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("departments", Map.of("department_id", "INT", "building", "VARCHAR", "department_name", "VARCHAR"),
                        "professors", Map.of("professor_id", "INT", "professor_name", "VARCHAR", "department_id", "INT"),
                        "orders", Map.of("order_id", "INT", "status", "VARCHAR", "placed_on", "DATE")),
                Map.of(), Map.of(), Map.of("departments", "department_id")));
        binder = new SchemaBinder(schemas);
        metadata = mock(SchemaMetadata.class);
        when(metadata.distinctShare(anyString(), anyString())).thenReturn(OptionalDouble.empty());
    }

    @Test
    @DisplayName("the first text column with mostly distinct values names the rows")
    void namingColumn() {
        when(metadata.distinctShare("departments", "building")).thenReturn(OptionalDouble.of(0.3));
        when(metadata.distinctShare("departments", "department_name")).thenReturn(OptionalDouble.of(1.0));
        assertThat(IdentifyingColumns.of(DEPARTMENTS, binder, metadata))
                .containsExactly("department_id", "department_name");
    }

    @Test
    @DisplayName("a table whose text only categorises shows its key alone")
    void categoriesOnly() {
        when(metadata.distinctShare("orders", "status")).thenReturn(OptionalDouble.of(0.2));
        assertThat(IdentifyingColumns.of(ORDERS, binder, metadata)).containsExactly("order_id");
    }

    @Test
    @DisplayName("an unknown share takes the first text column; keys and foreign keys are never labels")
    void unknownShare() {
        assertThat(IdentifyingColumns.of(PROFESSORS, binder, metadata))
                .containsExactly("professor_id", "professor_name");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=IdentifyingColumnsTest`
Expected: compilation failure, `IdentifyingColumns` doesn't exist.

- [ ] **Step 3: Implement**

In `SchemaBinder`, refactor `aliasesFor` and `fieldType` onto one private lookup, then add the new methods:

```java
    private Optional<SchemaSnapshot> snapshotOf(KnownSchema.Table table) {
        for (Dialect dialect : Dialect.values()) {
            SchemaSnapshot snapshot = activeSchema.active(dialect);
            if (snapshot.tables().contains(table)) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    public String primaryKey(KnownSchema.Table table) {
        return snapshotOf(table).map(s -> s.primaryKeyFor(table)).orElse(table.columns().get(0));
    }

    public Set<String> foreignKeyColumns(KnownSchema.Table table) {
        return snapshotOf(table).map(s -> s.foreignKeys().stream()
                        .filter(fk -> fk.fromTable().equalsIgnoreCase(table.name()))
                        .map(KnownSchema.ForeignKey::fromColumn)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public static boolean isTextType(String type) {
        return type != null && Set.of("VARCHAR", "CHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
                "NVARCHAR", "NCHAR", "STRING").contains(type.toUpperCase(Locale.ROOT));
    }
```

`aliasesFor` becomes `snapshotOf(table).map(s -> s.aliasesFor(table.name())).orElse(SchemaAliases.TableAliases.NONE)`. `fieldType` becomes `snapshotOf(table).map(s -> s.fieldTypesFor(table.name()).get(column)).orElse(null)`.

Create `IdentifyingColumns`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.schema.KnownSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/** A table's key plus the text column that names its rows. */
final class IdentifyingColumns {

    static final double NAMING_SHARE = 0.9;

    private IdentifyingColumns() {}

    static List<String> of(KnownSchema.Table table, SchemaBinder binder, SchemaMetadata metadata) {
        String key = binder.primaryKey(table);
        Set<String> foreign = binder.foreignKeyColumns(table);
        List<String> chosen = new ArrayList<>(List.of(key));
        for (String column : table.columns()) {
            if (column.equals(key) || foreign.contains(column) || column.contains(".")
                    || !SchemaBinder.isTextType(binder.fieldType(table, column))) {
                continue;
            }
            OptionalDouble share = metadata.distinctShare(table.name(), column);
            if (share.isEmpty() || share.getAsDouble() >= NAMING_SHARE) {
                chosen.add(column);
                break;
            }
        }
        return chosen;
    }
}
```

In `QueryIntentParser`:
- Delete `identifyingColumns(...)` and replace its one caller with `IdentifyingColumns.of(subject, binder, metadata)`. The loop keeps `selectItems.add(0, ...)` so column order stays as today.
- `countTargetColumn = related.columns().get(0);` becomes `countTargetColumn = binder.primaryKey(related);`.
- `new QueryIntent.ColumnRef(subject.name(), subject.columns().get(0))` in the `countRequested` branch becomes `new QueryIntent.ColumnRef(subject.name(), binder.primaryKey(subject))`.

- [ ] **Step 4: Run the test, then the suite**

Run: `./mvnw -q test -Dtest=IdentifyingColumnsTest` → PASS. Then `./mvnw -q test` → baseline. `MultiTableJoinTest` and `QuestionShapesTest` must still expect `SELECT customers.name, customers.id, COUNT(orders.id)`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/IdentifyingColumns.java backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/IdentifyingColumnsTest.java
git commit -m "feat(rules): grouped counts show the declared key and the column that names each row"
```

---

### Task 6: ColumnPhrase

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/ColumnPhrase.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java` (add `longestColumnPhrase`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/ColumnPhraseTest.java`

**Interfaces:**
- Consumes: `SchemaBinder.columnMatch`, `bindColumn`; `QueryIntentParser.ambiguityMessage` (package-private static, exists).
- Produces:
  - `record ColumnPhrase.Reading(KnownSchema.Table table, String column, String phrase, int start, int end, SchemaLexicon.MatchKind kind)`;
  - `Optional<Reading> after(String text, int from, List<KnownSchema.Table> tables)`;
  - `Optional<Reading> before(String text, int to, List<KnownSchema.Table> tables)`;
  - `Optional<Reading> beforeExact(String text, int to, KnownSchema.Table table)`;
  - `int SchemaBinder.longestColumnPhrase(List<KnownSchema.Table>)`.

  `tables.get(0)` is the subject. `after`/`before` throw `AmbiguousWordException` when the longest binding run is ambiguous.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnPhraseTest {

    private static final KnownSchema.Table STUDENTS = new KnownSchema.Table("students",
            List.of("student_id", "student_name", "admission_year", "grade", "department_id"));
    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_id", "department_name"));
    private static final KnownSchema.Table SECTIONS = new KnownSchema.Table("sections",
            List.of("section_id", "academic_year", "semester_year"));

    private ColumnPhrase phrases;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-c", "c", "querysense_uploads",
                List.of(STUDENTS, DEPARTMENTS, SECTIONS), List.of(), Map.of(), Instant.now()));
        phrases = new ColumnPhrase(new SchemaBinder(schemas));
    }

    @Test
    @DisplayName("after a keyword, the longest run naming one column wins")
    void after() {
        String text = "students where admission year is 2022";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("admission"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("admission_year");
        assertThat(reading.phrase()).isEqualTo("admission year");
        assertThat(text.substring(reading.start(), reading.end())).isEqualTo("admission year");
    }

    @Test
    @DisplayName("a value word after the column is left alone")
    void valueAfter() {
        String text = "students with grade a";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("grade"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("grade");
        assertThat(reading.end()).isEqualTo(text.indexOf(" a"));
    }

    @Test
    @DisplayName("before a keyword, the longest run ending there wins")
    void before() {
        String text = "students with admission year above 2020";
        ColumnPhrase.Reading reading = phrases.before(text, text.indexOf("above"), List.of(STUDENTS)).orElseThrow();
        assertThat(reading.column()).isEqualTo("admission_year");
    }

    @Test
    @DisplayName("a phrase only a joined table has binds there")
    void chain() {
        String text = "students with department name physics";
        ColumnPhrase.Reading reading = phrases.after(text, text.indexOf("department"),
                List.of(STUDENTS, DEPARTMENTS)).orElseThrow();
        assertThat(reading.table()).isEqualTo(DEPARTMENTS);
        assertThat(reading.column()).isEqualTo("department_name");
    }

    @Test
    @DisplayName("an ambiguous phrase declines, and nothing found is empty")
    void ambiguousAndEmpty() {
        String text = "sections with year 2024";
        assertThatThrownBy(() -> phrases.after(text, text.indexOf("year"), List.of(SECTIONS)))
                .isInstanceOf(AmbiguousWordException.class)
                .hasMessageContaining("sections.academic_year")
                .hasMessageContaining("sections.semester_year");
        assertThat(phrases.after("students with cats", 14, List.of(STUDENTS))).isEmpty();
    }

    @Test
    @DisplayName("the exact-only form reads the column ending before the keyword")
    void exactOnly() {
        String text = "students with student name not asha";
        assertThat(phrases.beforeExact(text, text.indexOf(" not") + 1, STUDENTS).orElseThrow().column())
                .isEqualTo("student_name");
    }

    @Test
    @DisplayName("a phrase two joined tables both have declines")
    void chainAmbiguous() {
        String text = "sections with department id 4";
        assertThatThrownBy(() -> phrases.after(text, text.indexOf("department"),
                List.of(SECTIONS, STUDENTS, DEPARTMENTS)))
                .isInstanceOf(AmbiguousWordException.class)
                .hasMessageContaining("students.department_id")
                .hasMessageContaining("departments.department_id");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=ColumnPhraseTest`
Expected: compilation failure, `ColumnPhrase` doesn't exist.

- [ ] **Step 3: Implement**

In `SchemaBinder`:

```java
    public int longestColumnPhrase(List<KnownSchema.Table> tables) {
        int longest = 1;
        for (KnownSchema.Table table : tables) {
            SchemaAliases.TableAliases aliases = aliasesFor(table);
            for (String column : table.columns()) {
                longest = Math.max(longest, NameWords.words(column).size());
                for (String alias : aliases.column(column)) {
                    longest = Math.max(longest, alias.trim().split("\\s+").length);
                }
            }
        }
        return longest;
    }
```

Create `ColumnPhrase`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.KnownSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads the longest run of question words that names exactly one column. */
final class ColumnPhrase {

    record Reading(KnownSchema.Table table, String column, String phrase, int start, int end,
                   SchemaLexicon.MatchKind kind) {}

    private record Word(String text, int start, int end) {}

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private final SchemaBinder binder;

    ColumnPhrase(SchemaBinder binder) {
        this.binder = binder;
    }

    Optional<Reading> after(String text, int from, List<KnownSchema.Table> tables) {
        List<Word> words = words(text).stream().filter(w -> w.start() >= from).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(tables)); n >= 1; n--) {
            Optional<Reading> found = read(words.subList(0, n), tables);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    Optional<Reading> before(String text, int to, List<KnownSchema.Table> tables) {
        List<Word> words = words(text).stream().filter(w -> w.end() <= to).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(tables)); n >= 1; n--) {
            Optional<Reading> found = read(words.subList(words.size() - n, words.size()), tables);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    Optional<Reading> beforeExact(String text, int to, KnownSchema.Table table) {
        List<Word> words = words(text).stream().filter(w -> w.end() <= to).toList();
        for (int n = Math.min(words.size(), binder.longestColumnPhrase(List.of(table))); n >= 1; n--) {
            List<Word> run = words.subList(words.size() - n, words.size());
            String phrase = join(run);
            Optional<String> column = binder.bindColumn(table, phrase);
            if (column.isPresent()) {
                return Optional.of(new Reading(table, column.get(), phrase, run.get(0).start(),
                        run.get(run.size() - 1).end(), SchemaLexicon.MatchKind.EXACT));
            }
        }
        return Optional.empty();
    }

    private Optional<Reading> read(List<Word> run, List<KnownSchema.Table> tables) {
        String phrase = join(run);
        int start = run.get(0).start();
        int end = run.get(run.size() - 1).end();
        KnownSchema.Table subject = tables.get(0);
        SchemaLexicon.ColumnMatch own = binder.columnMatch(subject, phrase);
        if (own instanceof SchemaLexicon.Ambiguous ambiguous) {
            throw new AmbiguousWordException(QueryIntentParser.ambiguityMessage(ambiguous.ambiguity().phrase(),
                    ambiguous.ambiguity().candidates().stream().map(c -> subject.name() + "." + c).toList()));
        }
        if (own instanceof SchemaLexicon.Bound bound) {
            return Optional.of(new Reading(subject, bound.binding().item(), phrase, start, end, bound.binding().kind()));
        }
        List<Reading> found = new ArrayList<>();
        for (KnownSchema.Table table : tables.subList(1, tables.size())) {
            if (binder.columnMatch(table, phrase) instanceof SchemaLexicon.Bound bound) {
                found.add(new Reading(table, bound.binding().item(), phrase, start, end, bound.binding().kind()));
            }
        }
        if (found.size() > 1) {
            throw new AmbiguousWordException(QueryIntentParser.ambiguityMessage(phrase,
                    found.stream().map(r -> r.table().name() + "." + r.column()).toList()));
        }
        return found.stream().findFirst();
    }

    private static List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new Word(m.group(), m.start(), m.end()));
        }
        return words;
    }

    private static String join(List<Word> run) {
        return run.stream().map(Word::text).collect(Collectors.joining(" "));
    }
}
```

`AmbiguousWordException` must have a `(String)` constructor. It does (it's thrown that way in `QueryIntentParser`).

- [ ] **Step 4: Run it to see it pass**

Run: `./mvnw -q test -Dtest=ColumnPhraseTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/ColumnPhrase.java backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java backend/src/test/java/com/querysense/backend/querybuilder/ColumnPhraseTest.java
git commit -m "feat(rules): ColumnPhrase reads the longest run of words that names one column"
```

---

### Task 7: Column lists keep their columns

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`ONLY_JUST`, the projection block after `distinctColumn`, `extractStrongProjectionPhrase`, constructor)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java`

**Interfaces:**
- Consumes: `ColumnPhrase` (Task 6) via a new field `private final ColumnPhrase phrases;`, set in the constructor with `this.phrases = new ColumnPhrase(binder);`.
- Produces: `private QueryIntent.ColumnRef use(ColumnPhrase.Reading reading, boolean joined, List<Interpretation> interpretations)`. It notes the interpretation and returns `qualify(column, subject, joined)` when `reading.table()` is the subject, otherwise `new ColumnRef(table.name(), column)`. Tasks 8–9 use it.

- [ ] **Step 1: Write the failing test**

This file grows in Tasks 8–10. Create it with the university schema the bug was found on:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnyNamingParserTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-u", "university.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("departments", List.of("department_id", "department_name", "building")),
                        new KnownSchema.Table("professors", List.of("professor_id", "professor_name", "email", "department_id")),
                        new KnownSchema.Table("students", List.of("student_id", "student_name", "email", "department_id", "admission_year")),
                        new KnownSchema.Table("enrollments", List.of("enrollment_id", "student_id", "enrollment_date", "grade"))),
                List.of(new KnownSchema.ForeignKey("professors", "department_id", "departments", "department_id"),
                        new KnownSchema.ForeignKey("students", "department_id", "departments", "department_id"),
                        new KnownSchema.ForeignKey("enrollments", "student_id", "students", "student_id")),
                Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("departments", Map.of("department_id", "INT", "department_name", "VARCHAR", "building", "VARCHAR"),
                        "professors", Map.of("professor_id", "INT", "professor_name", "VARCHAR", "email", "VARCHAR", "department_id", "INT"),
                        "students", Map.of("student_id", "INT", "student_name", "VARCHAR", "email", "VARCHAR",
                                "department_id", "INT", "admission_year", "INT"),
                        "enrollments", Map.of("enrollment_id", "INT", "student_id", "INT", "enrollment_date", "DATE", "grade", "VARCHAR")),
                Map.of(), Map.of(),
                Map.of("departments", "department_id", "professors", "professor_id", "students", "student_id",
                        "enrollments", "enrollment_id")));
        SchemaMetadata mysql = mock(SchemaMetadata.class);
        when(mysql.dialect()).thenReturn(Dialect.MYSQL);
        when(mysql.getColumnNames(anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(mysql.getDistinctValues(eq("enrollments"), eq("grade"))).thenReturn(List.of("A", "B", "C"));
        when(mysql.getDistinctValues(eq("students"), eq("student_name"))).thenReturn(List.of("Asha", "Ravi"));
        SchemaMetadata mongo = mock(SchemaMetadata.class);
        when(mongo.dialect()).thenReturn(Dialect.MONGODB);
        parser = TestParsers.parser(schemas, mysql, mongo);
    }

    private String sql(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(question + " → " + result.trace().declineReason()).isTrue();
        return result.sql();
    }

    @Test
    @DisplayName("a column list naming the table's own word keeps its columns")
    void columnListKeepsColumns() {
        assertThat(sql("show only the student name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show only the student_name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show the student name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
        assertThat(sql("show only the name and email of students"))
                .isEqualTo("SELECT student_name, email FROM students;");
    }
}
```

`result.trace()` must be non-null on a decline. Check `RuleBuildResult.declined` before relying on it. If it isn't, use `String.valueOf(result)` in the `as(...)` message instead.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest`
Expected: `columnListKeepsColumns` FAILS with `SELECT * FROM students;`.

- [ ] **Step 3: Implement**

`ONLY_JUST` becomes:

```java
    private static final Pattern ONLY_JUST = Pattern.compile(
            "(?:only|just)\\s+(?:the\\s+)?([a-z][a-z0-9 ,]*?)(?:\\s+of\\s+|\\s+from\\s+|\\s+for\\s+|$)");
```

`extractStrongProjectionPhrase` stops judging tables:

```java
    private Optional<String> extractStrongProjectionPhrase(String text) {
        Matcher onlyJust = ONLY_JUST.matcher(text);
        if (onlyJust.find()) {
            return Optional.of(onlyJust.group(1));
        }
        Matcher of = OF_PHRASE.matcher(text);
        if (of.find() && !AGGREGATE_PHRASE.matcher(text).find()) {
            return Optional.of(of.group(1));
        }
        return Optional.empty();
    }
```

The projection block (from `Optional<String> strongPhrase = ...` through the closing brace of `if (projectionRequested) {...}`) becomes:

```java
        Optional<String> strongPhrase = distinct || countRequested ? Optional.empty() : extractStrongProjectionPhrase(text);
        List<String> projected = List.of();
        boolean projectionRequested = false;
        if (strongPhrase.isPresent()) {
            List<String> resolved;
            try {
                resolved = resolveChunks(strongPhrase.get(), subject, interpretations);
            } catch (AmbiguousWordException e) {
                if (namesTable(strongPhrase.get(), dialect)) {
                    resolved = null;
                } else {
                    throw e;
                }
            }
            if (resolved != null) {
                projected = resolved;
                projectionRequested = true;
                projected.forEach(c -> bindings.add(
                        new RuleTrace.Binding(c, "COLUMN", subject.name() + "." + c)));
            } else if (!namesTable(strongPhrase.get(), dialect)) {
                return RuleBuildResult.declined(
                        "The question asks for specific fields, but at least one of them is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
        }
```

Add the `use` helper near `chainColumn`:

```java
    private QueryIntent.ColumnRef use(ColumnPhrase.Reading reading, boolean joined, List<Interpretation> interpretations) {
        note(reading.phrase(), reading.table().name() + "." + reading.column(), reading.kind(), interpretations);
        return qualify(reading.column(), reading.table(), joined);
    }
```

`qualify(column, table, joined)` already returns the table name when joined. For a non-subject table, `joined` is always true, so this matches `chainColumn`.

- [ ] **Step 4: Run the test, then the suite**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest` → PASS. Then `./mvnw -q test` → baseline.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java
git commit -m "fix(rules): a column list that shares a word with its table keeps its columns"
```

---

### Task 8: Conditions read column phrases

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`COMPARISON`, `BETWEEN`, `NOT_VALUE`, `WITH_VALUE` and their blocks; two helpers)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java` (add)

**Interfaces:**
- Consumes: `phrases`, `use(...)` (Task 7).
- Produces: private helpers `Optional<int[]> wordBefore(String text, int to)` (span of the last word ending at or before `to`) and `List<int[]> wordsFrom(String text, int from, int max)` (spans of up to `max` words starting at or after `from`).

- [ ] **Step 1: Add the failing tests**

```java
    @Test
    @DisplayName("filters and comparisons read multi-word columns")
    void conditions() {
        assertThat(sql("students with admission year 2022"))
                .isEqualTo("SELECT * FROM students WHERE admission_year = 2022;");
        assertThat(sql("students where admission_year is 2022"))
                .isEqualTo("SELECT * FROM students WHERE admission_year = 2022;");
        assertThat(sql("students with admission year above 2020"))
                .isEqualTo("SELECT * FROM students WHERE admission_year > 2020;");
        assertThat(sql("students with student name not asha"))
                .isEqualTo("SELECT * FROM students WHERE student_name <> 'asha';");
    }

    @Test
    @DisplayName("a value right after the column is the value, not part of the column")
    void valueAfterColumn() {
        assertThat(sql("enrollments with grade a")).isEqualTo("SELECT * FROM enrollments WHERE grade = 'a';");
    }
```

Before trusting the expected strings, compare them with how the existing suite renders the same shapes: `QuestionShapesTest` for `<>` vs `!=`, and `VocabularyTest` for the value text. Adjust **only** the expected strings in these new tests to match the emitter's existing rendering.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest`
Expected: `conditions` FAILS (declined: "filters on \"admission\"").

- [ ] **Step 3: Implement**

Patterns:

```java
    private static final Pattern COMPARISON = Pattern.compile(
            "\\b(?:is\\s+)?(" + alternation(COMPARISON_OPS.keySet()) + ")\\s+(.+)$");

    private static final Pattern BETWEEN = Pattern.compile("\\b(?:is\\s+)?between\\s+(.+)$");

    private static final Pattern NOT_VALUE = Pattern.compile("\\b(?:is\\s+)?not\\s+(?=[a-z])");

    private static final Pattern WITH_VALUE = Pattern.compile("\\b(?:with|whose|where)\\s+(?=[a-z0-9])");

    private static final Pattern VALUE_START = Pattern.compile("\\G\\s+(?:(?:is|equals|=)\\s+)?(?=[a-z0-9_])");

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
```

Helpers:

```java
    private static Optional<int[]> wordBefore(String text, int to) {
        Matcher m = WORD.matcher(text);
        int[] last = null;
        while (m.find() && m.end() <= to) {
            last = new int[]{m.start(), m.end()};
        }
        return Optional.ofNullable(last);
    }

    private static List<int[]> wordsFrom(String text, int from, int max) {
        List<int[]> found = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (found.size() < max && m.find(found.isEmpty() ? from : found.get(found.size() - 1)[1])) {
            found.add(new int[]{m.start(), m.end()});
        }
        return found;
    }
```

**BETWEEN block.** Replace the block from `Matcher between = BETWEEN.matcher(text);` to its closing brace:

```java
        Matcher between = BETWEEN.matcher(text);
        if (between.find()) {
            String rest = between.group(1);
            Optional<ColumnPhrase.Reading> rangeRead = phrases.before(text, between.start(), List.of(subject));
            String rangePhrase = rangeRead.map(ColumnPhrase.Reading::phrase)
                    .orElse(wordBefore(text, between.start()).map(w -> text.substring(w[0], w[1])).orElse(""));
            Optional<NumberReader.Reading> low = NumberReader.leading(rest);
            String after = low.map(r -> rest.substring(r.phrase().length()).trim()).orElse("");
            Optional<NumberReader.Reading> high = after.startsWith("and ")
                    ? NumberReader.leading(after.substring(4))
                    : Optional.empty();
            if (low.isEmpty() || high.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for " + rangePhrase + " between \"" + rest
                                + "\", but this engine can only read a range between two numbers.",
                        bindings);
            }
            if (rangeRead.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for a range of \"" + rangePhrase + "\", which is not a column of "
                                + subject.name() + ".",
                        bindings);
            }
            QueryIntent.ColumnRef rangeRef = use(rangeRead.get(), joined, interpretations);
            QueryIntent.Literal from = QueryIntent.Literal.number(low.get().value());
            QueryIntent.Literal to = QueryIntent.Literal.number(high.get().value());
            where.add(new QueryIntent.Predicate(rangeRef, QueryIntent.Op.BETWEEN, List.of(from, to)));
            conditionSpans.add(new int[]{rangeRead.get().start(), between.start(1)
                    + rest.indexOf(high.get().phrase(), low.get().phrase().length()) + high.get().phrase().length()});
            String lhs = joined ? subject.name() + "." + rangeRead.get().column() : rangeRead.get().column();
            bindings.add(new RuleTrace.Binding(
                    rangePhrase + " between " + low.get().phrase() + " and " + high.get().phrase(),
                    "COMPARISON", lhs + " BETWEEN " + from.text() + " AND " + to.text()));
        }
```

**COMPARISON loop.** Inside the `while`, `group(2)` (operator) becomes `group(1)` and `group(3)` (rest) becomes `group(2)`. The column is read before the match:

```java
        while (searchFrom < text.length() && comparison.find(searchFrom)) {
            if (insideAny(comparison.start(), conditionSpans)) {
                searchFrom = comparison.end(1);
                continue;
            }
            QueryIntent.Op op = COMPARISON_OPS.get(comparison.group(1));
            String operator = op.sql();
            String rest = comparison.group(2);
            Optional<NumberReader.Reading> reading = NumberReader.leading(rest);
            if (reading.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question compares against \"" + rest.split("\\s+")[0]
                                + "\", which is not a number this engine can read.",
                        bindings);
            }
            QueryIntent.Literal number = QueryIntent.Literal.number(reading.get().value());
            Optional<ColumnPhrase.Reading> lhsRead = phrases.before(text, comparison.start(), chainTables);
            Optional<int[]> previous = wordBefore(text, comparison.start());
            String lhsPhrase = lhsRead.map(ColumnPhrase.Reading::phrase)
                    .orElse(previous.map(w -> text.substring(w[0], w[1])).orElse(""));
            int start = lhsRead.map(ColumnPhrase.Reading::start).orElse(previous.map(w -> w[0]).orElse(comparison.start()));
            int[] span = {start, comparison.start(2) + reading.get().phrase().length()};

            if (lhsRead.isPresent()) {
                QueryIntent.ColumnRef lhsRef = use(lhsRead.get(), joined, interpretations);
                String lhs = SqlEmitter.column(lhsRef);
                where.add(new QueryIntent.Predicate(lhsRef, op, List.of(number)));
                conditionSpans.add(span);
                bindings.add(new RuleTrace.Binding(
                        lhsPhrase + " " + comparison.group(1) + " " + reading.get().phrase(),
                        "COMPARISON", lhs + " " + operator + " " + number.text()));
            } else if (joined) {
                if (countTargetColumn != null) {
                    return RuleBuildResult.declined(
                            "The question compares two counts, which this engine won't combine.", bindings);
                }
                countTargetColumn = binder.primaryKey(related);
                String rendered = "COUNT(" + related.name() + "." + countTargetColumn + ")";
                having.add(new QueryIntent.Predicate(
                        new QueryIntent.Aggregate(QueryIntent.Fn.COUNT,
                                new QueryIntent.ColumnRef(related.name(), countTargetColumn)),
                        op, List.of(number)));
                conditionSpans.add(span);
                bindings.add(new RuleTrace.Binding(
                        comparison.group(1) + " " + reading.get().phrase() + " " + related.name(),
                        "COMPARISON", rendered + " " + operator + " " + number.text()));
            } else {
                return RuleBuildResult.declined(
                        "The question compares \"" + lhsPhrase + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            searchFrom = span[1];
        }
```

**NOT_VALUE block** (inside the `else` of `excluding`):

```java
            Matcher notValue = NOT_VALUE.matcher(text);
            if (notValue.find()) {
                Optional<ColumnPhrase.Reading> notRead = phrases.beforeExact(text, notValue.start(), subject);
                if (notRead.isPresent()) {
                    String notColumn = notRead.get().column();
                    Optional<ValueList> list = readValues(text, notValue.end(), chainTables, joined, notColumn,
                            metadata, dialect, interpretations);
                    if (list.isEmpty()) {
                        return RuleBuildResult.declined(
                                "The question says " + notRead.get().phrase() + " is not something, but no stored value "
                                        + "of " + subject.name() + "." + notColumn + " follows.",
                                bindings);
                    }
                    where.add(new QueryIntent.Predicate(qualify(notColumn, subject, joined),
                            list.get().typed().size() == 1 ? QueryIntent.Op.NE : QueryIntent.Op.NOT_IN,
                            List.copyOf(list.get().literals())));
                    bindings.add(new RuleTrace.Binding(notRead.get().phrase() + " not "
                            + String.join(" and ", list.get().typed()), "FILTER",
                            subject.name() + "." + notColumn));
                    consumed.add(new int[]{notRead.get().start(), list.get().end()});
                    conditionSpans.add(new int[]{notRead.get().start(), list.get().end()});
                }
            }
```

**WITH block.** Replace from `Matcher with = WITH_VALUE.matcher(text);` to the end of its `if` block:

```java
        Matcher with = WITH_VALUE.matcher(text);
        int[] withFirst = null;
        int withSecondEnd = -1;
        while (with.find()) {
            List<int[]> following = wordsFrom(text, with.end(), 2);
            if (following.size() == 2 && following.get(0)[0] == with.end()) {
                withFirst = following.get(0);
                withSecondEnd = following.get(1)[1];
                break;
            }
        }
        if (withFirst != null) {
            String first = text.substring(withFirst[0], withFirst[1]);
            int withStart = with.start();
            int matchEnd = withSecondEnd;
            if (!insideAny(withFirst[0], conditionSpans) && !insideAny(withStart, conditionSpans)
                    && !VALUE_STOPWORDS.contains(first) && !NON_COLUMN_WORDS.contains(first)
                    && conditionSpans.stream().noneMatch(s -> s[0] >= withStart && s[0] < matchEnd)) {
                Optional<ColumnPhrase.Reading> withRead = phrases.after(text, withFirst[0], chainTables);
                if (withRead.isEmpty()) {
                    return RuleBuildResult.declined(
                            "The question filters on \"" + first + "\", which is not a column of "
                                    + subject.name() + ".",
                            bindings);
                }
                Matcher valueStart = VALUE_START.matcher(text);
                if (valueStart.find(withRead.get().end())) {
                    QueryIntent.ColumnRef withRef = use(withRead.get(), joined, interpretations);
                    KnownSchema.Table withTable = withRead.get().table();
                    int valueAt = valueStart.end();
                    String value = wordsFrom(text, valueAt, 1).stream().findFirst()
                            .map(w -> text.substring(w[0], w[1])).orElse("");
                    Optional<NumberReader.Reading> number = NumberReader.leading(text.substring(valueAt));
                    QueryIntent.Literal literalValue;
                    int valueEnd;
                    if (number.isPresent()) {
                        literalValue = QueryIntent.Literal.number(number.get().value());
                        value = number.get().phrase();
                        valueEnd = valueAt + value.length();
                    } else {
                        List<String> run = valueWords(text.substring(valueAt), chainTables, dialect);
                        Optional<String> stored = Optional.empty();
                        int n = run.size();
                        for (; n >= 1 && stored.isEmpty(); n--) {
                            stored = storedValueOf(withTable, withRead.get().column(), String.join(" ", run.subList(0, n)), metadata);
                        }
                        if (stored.isPresent()) {
                            run = run.subList(0, n + 1);
                        }
                        value = String.join(" ", run);
                        if (stored.isEmpty() && dialect == Dialect.MONGODB) {
                            return RuleBuildResult.declined(
                                    "Couldn't confirm how \"" + value + "\" is stored in " + withTable.name() + "."
                                            + withRead.get().column() + ", and MongoDB matches text exactly, so this "
                                            + "engine won't guess its capitalisation.",
                                    bindings);
                        }
                        literalValue = QueryIntent.Literal.text(value, stored.orElse(value));
                        valueEnd = text.indexOf(value, valueAt) + value.length();
                    }
                    where.add(new QueryIntent.Predicate(withRef, QueryIntent.Op.EQ, List.of(literalValue)));
                    conditionSpans.add(new int[]{withStart, valueEnd});
                    bindings.add(new RuleTrace.Binding(withRead.get().phrase() + " " + value, "FILTER",
                            withTable.name() + "." + withRead.get().column()));
                }
            }
        }
```

`chainColumn` has no callers left in the WITH and COMPARISON paths; it is still used by `ORDER_BY` until Task 9.

- [ ] **Step 4: Run the tests, then the suite**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest` → PASS. Then `./mvnw -q test` → baseline. Pay attention to `RuleTraceWordingTest` and `QuestionShapesTest`: binding phrases for one-word columns must be unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java
git commit -m "feat(rules): filters, comparisons, ranges and not-equal read columns of any length"
```

---

### Task 9: Sorting, ranking, grouping, distinct and totals read column phrases

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`ORDER_BY`, `PER_GROUP`, `TOP_BY`, `RANK_N` and their blocks; `distinctColumn`; the `AGGREGATES` / `AGGREGATE_PHRASE` lookups; `chainColumn`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java` (add)

**Interfaces:**
- Consumes: `phrases`, `use(...)`, `wordsFrom(...)` (Tasks 7–8).

- [ ] **Step 1: Add the failing tests**

```java
    @Test
    @DisplayName("sorting, ranking, grouping, distinct and totals read multi-word columns")
    void shapes() {
        assertThat(sql("students sorted by admission year descending"))
                .isEqualTo("SELECT * FROM students ORDER BY admission_year DESC;");
        assertThat(sql("top 3 students by admission year"))
                .isEqualTo("SELECT * FROM students ORDER BY admission_year DESC LIMIT 3;");
        assertThat(sql("count students per admission year")).contains("GROUP BY admission_year");
        assertThat(sql("distinct admission years of students"))
                .isEqualTo("SELECT DISTINCT admission_year FROM students;");
        assertThat(sql("average and highest admission year of students"))
                .contains("AVG(admission_year)").contains("MAX(admission_year)");
    }

    @Test
    @DisplayName("a grouped count shows the department's key and its name")
    void groupedCount() {
        assertThat(sql("which departments have more than three professors"))
                .startsWith("SELECT departments.department_name, departments.department_id, COUNT(professors.professor_id)");
    }
```

As in Task 8, align these expected strings with the emitter's rendering in `QuestionShapesTest` (e.g. `ORDER BY … DESC LIMIT`), without changing what is asserted.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest`
Expected: `shapes` FAILS on the first multi-word column. `groupedCount` passes already (Task 5) and stays as a guard.

- [ ] **Step 3: Implement**

Patterns:

```java
    private static final Pattern PER_GROUP = Pattern.compile(
            "\\b(?:per|for each|in each|by each|for every|in every|grouped by|group by)\\s+(?=[a-z0-9])");

    private static final Pattern DISTINCT_WORD = Pattern.compile("\\b(?:distinct|unique|different)\\s+(?=[a-z0-9])");

    private static final Pattern WHICH_START = Pattern.compile("^(?:which|what)\\s+(?=[a-z0-9])");

    private static final Pattern WHICH_VERB = Pattern.compile("\\G\\s+(?:do|does|did|are|have|has)\\b");

    private static final Pattern TOP_BY = Pattern.compile("\\b(top|bottom)\\b.*?\\bby\\s+(?:the\\s+)?(?=[a-z0-9])");

    private static final Pattern RANK_N = Pattern.compile(
            "\\b(\\S+)\\s+(highest|lowest|greatest)\\s+(?=[a-z0-9])"
                    + "|\\b(highest|lowest|greatest)\\s+(\\S+)\\s+(?=[a-z0-9])");

    private static final Pattern ORDER_BY = Pattern.compile(
            "\\b(?:order(?:ed)?\\s+by|sort(?:ed)?\\s+by)\\s+(?=[a-z0-9])");

    private static final Pattern DIRECTION = Pattern.compile("\\G\\s+(desc(?:ending)?|asc(?:ending)?)\\b");
```

Keep `DISTINCT_PHRASE` and `WHICH_PHRASE` unchanged; they become the fallback in `distinctColumn`.

Add a helper for messages:

```java
    private static String firstWordAt(String text, int from) {
        return wordsFrom(text, from, 1).stream().findFirst().map(w -> text.substring(w[0], w[1])).orElse("");
    }
```

**PER_GROUP block:**

```java
        Matcher per = PER_GROUP.matcher(text);
        if (per.find()) {
            Optional<ColumnPhrase.Reading> groupRead = phrases.after(text, per.end(), List.of(subject));
            groupPhrase = groupRead.map(ColumnPhrase.Reading::phrase).orElse(firstWordAt(text, per.end()));
            if (groupRead.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks for a breakdown by \"" + groupPhrase
                                + "\", which is not a column of " + subject.name() + ".",
                        bindings);
            }
            use(groupRead.get(), false, interpretations);
            groupColumn = groupRead.get().column();
            bindings.add(new RuleTrace.Binding(groupPhrase, "GROUPING", subject.name() + "." + groupColumn));
        }
```

**ORDER_BY block:**

```java
        List<QueryIntent.OrderItem> orderBy = new ArrayList<>();
        Matcher order = ORDER_BY.matcher(text);
        Optional<ColumnPhrase.Reading> orderRead = Optional.empty();
        String orderPhrase = null;
        if (order.find()) {
            orderRead = phrases.after(text, order.end(), chainTables);
            orderPhrase = orderRead.map(ColumnPhrase.Reading::phrase).orElse(firstWordAt(text, order.end()));
        }
        if (orderPhrase != null && (orderRead.isPresent() || binder.tableMatches(orderPhrase, dialect).hits().isEmpty())) {
            if (orderRead.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question asks to sort by \"" + orderPhrase + "\", which is not a "
                                + "column of " + subject.name() + ".",
                        bindings);
            }
            Matcher direction = DIRECTION.matcher(text);
            boolean descending = direction.find(orderRead.get().end()) && direction.group(1).startsWith("desc");
            orderBy.add(new QueryIntent.OrderItem(use(orderRead.get(), joined, interpretations),
                    descending ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC));
            consumed.add(new int[]{order.start(), orderRead.get().end()});
        }
```

**`ranking(...)`, TOP_BY part:**

```java
        Matcher top = TOP_BY.matcher(text);
        if (top.find()) {
            Optional<ColumnPhrase.Reading> topRead = phrases.after(text, top.end(), List.of(subject));
            if (topRead.isEmpty()) {
                throw new DeclineException("The question ranks by \"" + firstWordAt(text, top.end())
                        + "\", which is not a column of " + subject.name() + ".");
            }
            use(topRead.get(), false, interpretations);
            QueryIntent.Direction direction = "top".equals(top.group(1)) ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC;
            return Optional.of(new Ranking(qualify(topRead.get().column(), subject, joined), direction, singular ? 1 : null));
        }
```

**RANK_N part.** The group numbers change: count is 1 or 4, word is 2 or 3.

```java
        Matcher rankN = RANK_N.matcher(text);
        if (rankN.find()) {
            String count = rankN.group(1) != null ? rankN.group(1) : rankN.group(4);
            String word = rankN.group(2) != null ? rankN.group(2) : rankN.group(3);
            Optional<NumberReader.Reading> n = NumberReader.leading(count);
            if (n.isPresent() && n.get().integral() && n.get().phrase().equals(count)) {
                Optional<ColumnPhrase.Reading> rankRead = phrases.after(text, rankN.end(), List.of(subject));
                if (rankRead.isPresent()) {
                    use(rankRead.get(), false, interpretations);
                    QueryIntent.Direction direction = "lowest".equals(word) ? QueryIntent.Direction.ASC : QueryIntent.Direction.DESC;
                    return Optional.of(new Ranking(qualify(rankRead.get().column(), subject, joined), direction,
                            n.get().value().intValueExact()));
                }
            }
        }
```

**`distinctColumn`.** Try the phrase reader first, then fall back to the current body unchanged:

```java
        Matcher distinctWord = DISTINCT_WORD.matcher(text);
        if (distinctWord.find()) {
            Optional<ColumnPhrase.Reading> read = phrases.after(text, distinctWord.end(), List.of(subject));
            if (read.isPresent()) {
                use(read.get(), false, interpretations);
                return Optional.of(read.get().column());
            }
        }
        Matcher whichStart = WHICH_START.matcher(text);
        if (whichStart.find()) {
            Optional<ColumnPhrase.Reading> read = phrases.after(text, whichStart.end(), List.of(subject));
            if (read.isPresent() && WHICH_VERB.matcher(text).find(read.get().end())) {
                use(read.get(), false, interpretations);
                return Optional.of(read.get().column());
            }
        }
```

`Matcher.find(int)` resets the matcher and `\G` anchors at that index, which is the intent here. Write it as `WHICH_VERB.matcher(text).find(read.get().end())`.

**Totals.** In the `AGGREGATES` branch, replace `Optional<String> col = column(subject, many.group(2), interpretations);` with:

```java
                Optional<ColumnPhrase.Reading> manyRead = phrases.after(text, many.start(2), List.of(subject));
                manyRead.ifPresent(r -> use(r, false, interpretations));
                Optional<String> col = manyRead.map(ColumnPhrase.Reading::column)
                        .or(() -> column(subject, many.group(2), interpretations));
```

In the `AGGREGATE_PHRASE` branch, replace `Optional<String> col = column(subject, agg.group(2), interpretations);` likewise with `agg.start(2)` and `agg.group(2)`. Keep the `followingColumn` fallback after it. The binding phrases keep using `many.group(2)` / `agg.group(2)`.

**`chainColumn`** now delegates:

```java
    private Optional<QueryIntent.ColumnRef> chainColumn(String phrase, List<KnownSchema.Table> chainTables,
                                                        boolean joined, List<Interpretation> interpretations) {
        List<KnownSchema.Table> scope = joined ? chainTables : List.of(chainTables.get(0));
        return phrases.after(phrase, 0, scope)
                .filter(r -> r.end() == phrase.length())
                .map(r -> use(r, joined, interpretations));
    }
```

If `grep -n "chainColumn(" QueryIntentParser.java` shows no callers left, delete it instead.

- [ ] **Step 4: Run the tests, then the suite**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest` → PASS. Then `./mvnw -q test` → baseline.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java
git commit -m "feat(rules): sorting, ranking, grouping, distinct and totals read columns of any length"
```

---

### Task 10: A column word counts only if the query uses its column

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (`unreadWord`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java` (add)

- [ ] **Step 1: Add the failing test**

```java
    @Test
    @DisplayName("a column the query doesn't use is an unread word")
    void unusedColumnWordDeclines() {
        RuleBuildResult result = parser.parse("email students with admission year 2022", Dialect.MYSQL);
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("\"email\"");
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest#unusedColumnWordDeclines`
Expected: FAIL. It builds `SELECT * FROM students WHERE admission_year = 2022;` and silently drops `email`.

- [ ] **Step 3: Implement**

In `unreadWord`, build the set of columns the query uses from the bindings' `resolvedTo`. Replace the final column clause of the big `if`:

```java
        Set<String> usedColumns = new HashSet<>();
        for (RuleTrace.Binding b : bindings) {
            for (String token : b.resolvedTo().toLowerCase(Locale.ROOT).split("[^a-z0-9_.]+")) {
                for (int dot = -1; ; dot = token.indexOf('.', dot + 1)) {
                    usedColumns.add(token.substring(dot + 1));
                    if (token.indexOf('.', dot + 1) < 0) {
                        break;
                    }
                }
            }
        }
```

The clause becomes:

```java
                    || chainTables.stream().anyMatch(t -> binder.columnMatch(t, w) instanceof SchemaLexicon.Bound b
                            && usedColumns.contains(b.binding().item().toLowerCase(Locale.ROOT)))) {
```

The table clause `!binder.tableMatches(w, dialect).hits().isEmpty()` stays (see Departure 1).

- [ ] **Step 4: Run the test, then the suite**

Run: `./mvnw -q test -Dtest=AnyNamingParserTest` → PASS. Then `./mvnw -q test` → baseline. If an existing test now declines on a column word, find which binding should have named that column. The usual cause is a binding whose `resolvedTo` omits the column. Fix the binding text so it names the column, not the check. Report every such case in the task summary.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java backend/src/test/java/com/querysense/backend/querybuilder/AnyNamingParserTest.java
git commit -m "fix(rules): a column word the query doesn't use declines instead of being dropped"
```

---

### Task 11: Six naming styles, one question set, both dialects

**Files:**
- Create: `backend/src/test/java/com/querysense/backend/querybuilder/NamingStylesTest.java`

**Interfaces:**
- Consumes: everything above. Test only.

The prefixed style is the reference. For every other style, the expected query is the prefixed query with each prefixed name replaced by that style's name. Every prefixed name is distinct, so this direction never collides.

- [ ] **Step 1: Write the test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NamingStylesTest {

    private static final Map<String, List<String>> PREFIXED = new LinkedHashMap<>();
    private static final Map<String, Map<String, String>> TYPES = new LinkedHashMap<>();

    static {
        PREFIXED.put("departments", List.of("department_id", "department_name"));
        PREFIXED.put("professors", List.of("professor_id", "professor_name", "email", "department_id"));
        PREFIXED.put("students", List.of("student_id", "student_name", "email", "admission_year", "joined_date", "department_id"));
        PREFIXED.put("courses", List.of("course_id", "course_name", "course_credits", "professor_id"));
        TYPES.put("department_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("department_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("professor_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("professor_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("email", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("student_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("student_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("admission_year", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("joined_date", Map.of("MYSQL", "DATE", "MONGODB", "date"));
        TYPES.put("course_id", Map.of("MYSQL", "INT", "MONGODB", "int"));
        TYPES.put("course_name", Map.of("MYSQL", "VARCHAR", "MONGODB", "string"));
        TYPES.put("course_credits", Map.of("MYSQL", "INT", "MONGODB", "int"));
    }

    enum Style {
        PREFIXED_SNAKE(t -> t, c -> c, "student name", "admission year", "admission years", "credits"),
        CAMEL(t -> t, NamingStylesTest::camel, "student name", "admission year", "admission years", "credits"),
        PASCAL(NamingStylesTest::pascal, c -> pascal(c).replaceAll("Id$", "ID"),
                "student name", "admission year", "admission years", "credits"),
        UPPER(String::toUpperCase, String::toUpperCase, "student name", "admission year", "admission years", "credits"),
        PLAIN(t -> t, c -> Map.of("department_name", "name", "professor_name", "name", "student_name", "name",
                "course_name", "name", "admission_year", "year", "joined_date", "joined",
                "course_credits", "credits").getOrDefault(c, c), "name", "year", "years", "credits"),
        ABBREVIATED(t -> t, c -> Map.ofEntries(Map.entry("department_id", "dept_id"), Map.entry("department_name", "dept_nm"),
                Map.entry("professor_id", "prof_id"), Map.entry("professor_name", "prof_nm"),
                Map.entry("student_id", "stu_id"), Map.entry("student_name", "stu_nm"),
                Map.entry("admission_year", "adm_yr"), Map.entry("joined_date", "jnd_dt"),
                Map.entry("course_id", "crs_id"), Map.entry("course_name", "crs_nm"),
                Map.entry("course_credits", "crs_cr")).getOrDefault(c, c), "stu nm", "adm yr", "adm yrs", "crs cr");

        final UnaryOperator<String> table;
        final UnaryOperator<String> column;
        final String studentName;
        final String year;
        final String years;
        final String credits;

        Style(UnaryOperator<String> table, UnaryOperator<String> column, String studentName, String year,
              String years, String credits) {
            this.table = table;
            this.column = column;
            this.studentName = studentName;
            this.year = year;
            this.years = years;
            this.credits = credits;
        }
    }

    private static String camel(String snake) {
        String pascal = pascal(snake);
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String pascal(String snake) {
        StringBuilder out = new StringBuilder();
        for (String part : snake.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static List<String> questions(Style s) {
        return List.of(
                "show only the " + s.studentName + " and email of students",
                "students with " + s.year + " 2022",
                "courses with " + s.credits + " above 3",
                "courses with " + s.credits + " between 2 and 4",
                "students with " + s.studentName + " not asha",
                "students sorted by " + s.year + " descending",
                "top 3 students by " + s.year,
                "count students per " + s.year,
                "distinct " + s.years + " of students",
                "average and highest " + s.credits + " of courses",
                "students from last month",
                "students of departments in physics",
                "which departments have more than three professors");
    }

    private static final int JOINED_FILTER = 11;
    private static final int GROUPED_COUNT = 12;

    private static QueryIntentParser parser(Style s, Dialect dialect) {
        List<KnownSchema.Table> tables = new ArrayList<>();
        Map<String, Map<String, String>> fieldTypes = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        PREFIXED.forEach((table, columns) -> {
            List<String> named = new ArrayList<>();
            Map<String, String> types = new LinkedHashMap<>();
            if (dialect == Dialect.MONGODB) {
                named.add("_id");
                types.put("_id", "objectId");
            }
            for (String c : columns) {
                named.add(s.column.apply(c));
                types.put(s.column.apply(c), TYPES.get(c).get(dialect.name()));
            }
            tables.add(new KnownSchema.Table(s.table.apply(table), named));
            fieldTypes.put(s.table.apply(table), types);
            keys.put(s.table.apply(table), s.column.apply(columns.get(0)));
        });
        List<KnownSchema.ForeignKey> fks = List.of(
                new KnownSchema.ForeignKey(s.table.apply("professors"), s.column.apply("department_id"),
                        s.table.apply("departments"), s.column.apply("department_id")),
                new KnownSchema.ForeignKey(s.table.apply("students"), s.column.apply("department_id"),
                        s.table.apply("departments"), s.column.apply("department_id")),
                new KnownSchema.ForeignKey(s.table.apply("courses"), s.column.apply("professor_id"),
                        s.table.apply("professors"), s.column.apply("professor_id")));
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-" + s, s.name(), "querysense_uploads", tables, fks, Map.of(),
                Instant.now(), dialect, List.of(), fieldTypes, Map.of(), Map.of(), keys));
        SchemaMetadata data = mock(SchemaMetadata.class);
        when(data.dialect()).thenReturn(dialect);
        when(data.getColumnNames(anyString())).thenReturn(List.of());
        when(data.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(data.getDistinctValues(eq(s.table.apply("students")), eq(s.column.apply("student_name"))))
                .thenReturn(List.of("Asha", "Ravi"));
        when(data.getDistinctValues(eq(s.table.apply("departments")), eq(s.column.apply("department_name"))))
                .thenReturn(List.of("Physics", "Chemistry"));
        SchemaMetadata other = mock(SchemaMetadata.class);
        when(other.dialect()).thenReturn(dialect == Dialect.MYSQL ? Dialect.MONGODB : Dialect.MYSQL);
        return dialect == Dialect.MYSQL ? TestParsers.parser(schemas, data, other) : TestParsers.parser(schemas, other, data);
    }

    private static String translate(String query, Style s) {
        Map<String, String> names = new LinkedHashMap<>();
        PREFIXED.forEach((table, columns) -> {
            names.put(table, s.table.apply(table));
            columns.forEach(c -> names.put(c, s.column.apply(c)));
        });
        List<String> order = new ArrayList<>(names.keySet());
        order.sort(Comparator.comparingInt(String::length).reversed());
        Pattern any = Pattern.compile("(?<![A-Za-z0-9_])(" + String.join("|", order) + ")(?![A-Za-z0-9_])");
        Matcher m = any.matcher(query);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(names.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String outcome(RuleBuildResult result) {
        return result.built() ? result.sql() : "DECLINED";
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    @DisplayName("the reference style builds every question it should")
    void referenceBuilds(Dialect dialect) {
        QueryIntentParser parser = parser(Style.PREFIXED_SNAKE, dialect);
        List<String> qs = questions(Style.PREFIXED_SNAKE);
        for (int i = 0; i < qs.size(); i++) {
            if (dialect == Dialect.MONGODB && (i == JOINED_FILTER || i == GROUPED_COUNT)) {
                continue;
            }
            RuleBuildResult result = parser.parse(qs.get(i), dialect);
            assertThat(result.built()).as(dialect + ": " + qs.get(i) + " → " + result.trace().declineReason()).isTrue();
        }
        assertThat(outcome(parser.parse(qs.get(0), dialect)))
                .isEqualTo(dialect == Dialect.MYSQL ? "SELECT student_name, email FROM students;"
                        : "db.students.find({}, {\"student_name\": 1, \"email\": 1, \"_id\": 0})");
    }

    @ParameterizedTest
    @EnumSource(Style.class)
    @DisplayName("every style builds the same query as the reference, with its own names, in both dialects")
    void everyStyleMatches(Style style) {
        for (Dialect dialect : Dialect.values()) {
            QueryIntentParser reference = parser(Style.PREFIXED_SNAKE, dialect);
            QueryIntentParser styled = parser(style, dialect);
            List<String> referenceQs = questions(Style.PREFIXED_SNAKE);
            List<String> styledQs = questions(style);
            for (int i = 0; i < referenceQs.size(); i++) {
                String expected = outcome(reference.parse(referenceQs.get(i), dialect));
                String actual = outcome(styled.parse(styledQs.get(i), dialect));
                assertThat(actual).as(style + " " + dialect + ": " + styledQs.get(i))
                        .isEqualTo("DECLINED".equals(expected) ? expected : translate(expected, style));
            }
        }
    }

    @Test
    @DisplayName("on the abbreviated schema, words the schema doesn't use decline")
    void abbreviationBoundary() {
        QueryIntentParser parser = parser(Style.ABBREVIATED, Dialect.MYSQL);
        assertThat(parser.parse("show only the stu nm and email of students", Dialect.MYSQL).built()).isTrue();
        assertThat(parser.parse("show only the student name and email of students", Dialect.MYSQL).built()).isFalse();
    }
}
```

Two expected values come from the emitter: the MongoDB projection string in `referenceBuilds` and the `outcome` of a decline. Check the projection against `MongoEmitterTest` and correct only that literal if the emitter renders projections differently. Don't loosen any assertion.

- [ ] **Step 2: Run it**

Run: `./mvnw -q test -Dtest=NamingStylesTest`
Expected: PASS. A failure here is a real gap in Tasks 2–10. Fix it in the owning task's code; don't change the question set. If a reference question declines for a reason unrelated to naming (an existing documented decline), report it to the user rather than removing it.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/querysense/backend/querybuilder/NamingStylesTest.java
git commit -m "test(rules): six naming styles build the same queries in MySQL and MongoDB"
```

---

### Task 12: Record it in PROJECT.md

**Files:**
- Modify: `PROJECT.md`

- [ ] **Step 1:** In §5, after the "A rule engine that adapts to any schema" section, add a short dated subsection: "Any naming — built 2026-10-16". It names the spec and plan, says what changed (names in any style, multi-word columns at every site, primary keys on the snapshot, label column by ≥ 90% distinct values), the four departures above, and what still goes to the LLM (abbreviation meanings and the existing list).
- [ ] **Step 2:** In §2's "What the rule engine will and will not take", add one bullet stating that column names are read in any style, with the university examples (`student name`, `admission year`).
- [ ] **Step 3:** In §7, add a dated entry listing the new test classes and the final `./mvnw -q test` result, and stating that the running application was not exercised, since the user tests it personally.
- [ ] **Step 4: Commit**

```bash
git add PROJECT.md
git commit -m "docs: record the rule engine reading any naming style"
```

PROJECT.md already has uncommitted edits (see Task 0). If the user chose to let pending work ride along, this commit includes them. Say so in the task summary.
