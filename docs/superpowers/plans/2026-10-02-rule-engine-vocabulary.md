# Rule Engine Vocabulary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The rule engine reads words the schema doesn't literally use: declared aliases, built-in synonyms, one-letter typos, irregular plurals, multi-word values and spelled-out numbers. It does this on any schema, for MySQL and MongoDB alike, and declines whenever a loose match fits more than one item.

**Architecture:**
- A new `SchemaLexicon` sits behind `SchemaBinder`. It indexes every phrase that reaches a table or column of the active snapshot, with how it got there: `EXACT`, `INFLECTION`, `SCHEMA_ALIAS`, `SYNONYM` or `TYPO`.
- `QueryIntentParser` asks it for bindings and records non-exact ones as `Interpretation`s on the `RuleBuildResult`. It declines on ambiguity.
- The emitters are untouched, so both dialects gain everything at once.
- Aliases come from `aka:` in SQL `COMMENT`s and MongoDB `description`s, plus an `aliases` upload block. They ride on `SchemaSnapshot`.
- `NumberReader` replaces the one-to-ten map.

**Tech Stack:** Java 21, Spring Boot 3.5, JSqlParser 5.3, Jackson, JUnit 5, AssertJ, Mockito; React 19, Vitest 3, Testing Library, oxlint.

**Spec:** `docs/superpowers/specs/2026-10-02-rule-engine-vocabulary-design.md`

## Global Constraints

- Every question that builds today builds byte-for-byte the same, at the same confidence. The existing rule-engine, parity and generation tests are the guard and must pass unchanged, except where a task names a test it updates and says why.
- Confidence: `0.95` for rule-built queries. `0.85` (`CONFIDENCE_RULE_ENGINE_INTERPRETED`) when any interpretation is `SYNONYM` or `TYPO`. A schema-declared alias keeps `0.95`.
- A `SYNONYM` or `TYPO` binds only when exactly one item in scope fits. Two or more is a decline naming them.
- Typo means one edit (insert, delete, substitute, or swap two adjacent letters), on words of **5 or more** letters, never on a word that is itself a schema name, a declared alias or a word in the synonym list.
- No synonyms for data values.
- Both dialects, always. Every capability is reached through `QueryIntentParser`, which both emitters share.
- Source comments: a one-line summary above a class at most. No other comments.
- Backend tests: `cd backend && ./mvnw -q test -Dtest=<Class>` for one class; `./mvnw test` for all. Frontend: `cd frontend && npx vitest run <path>`; `npm test`; `npm run lint` clean; `npm run build` at the end.
- Do not start the app. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **Punctuation after a value** (`clients in mumbai?`): the filter must still be found, not dropped. Pinned in Task 7 (`punctuationEndsTheValue`).
2. **The real name and a synonym of the same table in one question** (`customers and clients in pune`): one table, no "isn't a table" decline. Pinned in Task 6 (`nameAndSynonymOfOneTable`).
3. **A declared alias that is also a built-in synonym of a different table:** the alias wins. Pinned in Task 5 (`aliasBeatsSynonym`).
4. **Aliases written in capitals** (`aka: Clients, BUYERS`): stored lowercase and matched. Pinned in Task 3 (`aliasesAreLowercased`).
5. **A limit word with no number** (`top customers in pune`): builds with no limit, as today. Pinned in Task 8 (`limitWordWithoutNumber`).

## Departures from the spec, on purpose

1. Alias collisions are cleaned by `SchemaAliases.clean`, called from `SchemaUploadService`, rather than inside `SchemaUploadValidator`. The validator throws on errors; a collision is a warning that must not fail the upload.
2. `SchemaLexicon` rebuilds its index lazily when the active snapshot object changes, rather than through `onSchemaChanged`. `ActiveSchemaProvider.publish` replaces the object, so this is equivalent and needs no listener wiring.
3. A comparison against an unreadable number keeps today's message ("…which is not a number this engine can read."), so existing wording tests stand.
4. A typo counts an adjacent swap (`emial` → `email`) as one edit. The spec's own example needs it.
5. The table-level typo is tried only when no table matched by any other kind. A stray English word one letter from a table name can't add a second table to a question that already names one.
6. `SchemaBinder.bindColumn` keeps its exact-or-alias meaning, for the parser's probe checks. Real bindings go through the new `columnMatch`.

## File Structure

| File | Responsibility |
|---|---|
| `backend/.../querybuilder/Edits.java` (new) | One-edit comparison |
| `backend/.../querybuilder/NumberReader.java` (new) | Digits and English number words at the start of a phrase |
| `backend/.../querybuilder/QueryIntent.java` (modify) | `Literal.number(BigDecimal)` |
| `backend/src/main/resources/querybuilder/synonyms.json` (new) | Built-in synonym groups |
| `backend/.../querybuilder/Synonyms.java` (new) | Loads and answers the groups |
| `backend/.../schema/SchemaAliases.java` (new) | `aka:` parsing, `TableAliases`, collision cleaning, prompt wording |
| `backend/.../schema/SchemaSnapshot.java`, `schema/ddl/ParsedSchema.java` (modify) | `aliases` component |
| `backend/.../schema/KnownSchema.java`, `KnownMongoSchema.java` (modify) | Built-in aliases |
| `backend/.../schema/ddl/DdlSchemaParser.java`, `schema/json/JsonSchemaUploadParser.java`, `schema/upload/SchemaUploadService.java` (modify) | Reading and publishing aliases |
| `backend/.../querybuilder/SchemaLexicon.java` (new) | Phrase index, match kinds, table and column lookup |
| `backend/.../querybuilder/SchemaBinder.java` (modify) | Delegates to the lexicon |
| `backend/.../querybuilder/Interpretation.java`, `AmbiguousWordException.java` (new); `RuleBuildResult.java`, `QueryIntentParser.java` (modify) | Interpretations, ambiguity declines, values, numbers |
| `backend/.../service/impl/QueryGenerationServiceImpl.java`, `dto/QueryResponse.java` (modify) | Confidence tier, `interpretations` |
| `backend/.../service/impl/OllamaService.java`, `MongoSchemaContext.java` (modify) | Aliases in the prompt |
| `backend/.../dto/SchemaTableView.java`, `SchemaUploadResponse.java` (modify) | Aliases to the frontend |
| `frontend/src/api/queryBuilder.js`, `components/analysis/RuleTracePanel.jsx/.css`, `pages/AnalysisPage.jsx`, `components/schemaSource/SchemaTablePreview.jsx/.css`, `mock/fixtures.js` (modify) | "Read as" block, alias display |

`backend/...` is `backend/src/main/java/com/querysense/backend`; tests mirror it under `backend/src/test/java/com/querysense/backend`.

---

### Task 1: `Edits`, `NumberReader` and `Literal.number(BigDecimal)`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/Edits.java`, `NumberReader.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/EditsTest.java`, `NumberReaderTest.java`

**Interfaces:**
- Produces:
  - `Edits.MIN_TYPO_LENGTH = 5`
  - `static boolean Edits.withinOne(String a, String b)`
  - `NumberReader.leading(String text) → Optional<NumberReader.Reading>`
  - `record Reading(BigDecimal value, String phrase)` with `boolean integral()`
  - `QueryIntent.Literal.number(BigDecimal n)`

- [ ] **Step 1: Write the failing tests**

`EditsTest.java`:

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EditsTest {

    @Test
    @DisplayName("one insertion, deletion, substitution or adjacent swap is one edit")
    void oneEdit() {
        assertThat(Edits.withinOne("custmers", "customers")).isTrue();
        assertThat(Edits.withinOne("customerss", "customers")).isTrue();
        assertThat(Edits.withinOne("delhy", "delhi")).isTrue();
        assertThat(Edits.withinOne("emial", "email")).isTrue();
        assertThat(Edits.withinOne("mumbia", "mumbai")).isTrue();
    }

    @Test
    @DisplayName("identical words and two edits are not one edit")
    void notOneEdit() {
        assertThat(Edits.withinOne("pune", "pune")).isFalse();
        assertThat(Edits.withinOne("custmrs", "customers")).isFalse();
        assertThat(Edits.withinOne("cat", "dog")).isFalse();
    }
}
```

`NumberReaderTest.java`:

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NumberReaderTest {

    private static BigDecimal read(String text) {
        return NumberReader.leading(text).orElseThrow().value();
    }

    private static String phrase(String text) {
        return NumberReader.leading(text).orElseThrow().phrase();
    }

    @Test
    @DisplayName("digits, thousands separators and decimals")
    void digits() {
        assertThat(read("250 dollars")).isEqualByComparingTo("250");
        assertThat(read("1,000 orders")).isEqualByComparingTo("1000");
        assertThat(read("4.5 stars")).isEqualByComparingTo("4.5");
        assertThat(phrase("1,000 orders")).isEqualTo("1,000");
    }

    @Test
    @DisplayName("spelled-out numbers up to the millions, hyphenated or not")
    void words() {
        assertThat(read("three orders")).isEqualByComparingTo("3");
        assertThat(read("twenty five products")).isEqualByComparingTo("25");
        assertThat(read("twenty-five")).isEqualByComparingTo("25");
        assertThat(read("two hundred and fifty")).isEqualByComparingTo("250");
        assertThat(read("a thousand rows")).isEqualByComparingTo("1000");
        assertThat(read("three million")).isEqualByComparingTo("3000000");
        assertThat(read("ten thousand")).isEqualByComparingTo("10000");
        assertThat(read("a dozen")).isEqualByComparingTo("12");
        assertThat(phrase("twenty five products")).isEqualTo("twenty five");
    }

    @Test
    @DisplayName("the longest valid prefix is read, and a dangling 'and' is left out")
    void longestValidPrefix() {
        assertThat(phrase("two hundred and orders")).isEqualTo("two hundred");
        assertThat(phrase("five five")).isEqualTo("five");
    }

    @Test
    @DisplayName("anything that doesn't start with a whole number reads as nothing")
    void notNumbers() {
        assertThat(NumberReader.leading("fifty-ish")).isEmpty();
        assertThat(NumberReader.leading("a few")).isEmpty();
        assertThat(NumberReader.leading("a customer")).isEmpty();
        assertThat(NumberReader.leading("customers")).isEmpty();
        assertThat(NumberReader.leading("")).isEmpty();
    }

    @Test
    @DisplayName("integral() tells whole numbers from decimals")
    void integral() {
        assertThat(NumberReader.leading("20").orElseThrow().integral()).isTrue();
        assertThat(NumberReader.leading("2.5").orElseThrow().integral()).isFalse();
    }

    @Test
    @DisplayName("a literal from a reading writes plain text and keeps a whole number whole")
    void literals() {
        QueryIntent.Literal whole = QueryIntent.Literal.number(new BigDecimal("250"));
        assertThat(whole.text()).isEqualTo("250");
        assertThat(whole.value()).isEqualTo(250);
        QueryIntent.Literal decimal = QueryIntent.Literal.number(new BigDecimal("4.5"));
        assertThat(decimal.text()).isEqualTo("4.5");
        assertThat(decimal.value()).isEqualTo(4.5);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='EditsTest,NumberReaderTest'`
Expected: compilation failure — `Edits`, `NumberReader` and `Literal.number(BigDecimal)` don't exist.

- [ ] **Step 3: Implement**

`Edits.java`:

```java
package com.querysense.backend.querybuilder;

/** One-edit comparison for the rule engine's typo matching. */
final class Edits {

    static final int MIN_TYPO_LENGTH = 5;

    private Edits() {}

    static boolean withinOne(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        int k = 0;
        while (k < Math.min(la, lb) && a.charAt(k) == b.charAt(k)) {
            k++;
        }
        if (la == lb) {
            if (a.substring(k + 1).equals(b.substring(k + 1))) {
                return true;
            }
            return k + 1 < la && a.charAt(k) == b.charAt(k + 1) && a.charAt(k + 1) == b.charAt(k)
                    && a.substring(k + 2).equals(b.substring(k + 2));
        }
        return la > lb ? a.substring(k + 1).equals(b.substring(k)) : a.substring(k).equals(b.substring(k + 1));
    }
}
```

`NumberReader.java`:

```java
package com.querysense.backend.querybuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads a whole number written in digits or English words at the start of a phrase. */
public final class NumberReader {

    public record Reading(BigDecimal value, String phrase) {
        public boolean integral() {
            return value.stripTrailingZeros().scale() <= 0;
        }
    }

    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

    private static final Map<String, Integer> UNITS = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5,
            "six", 6, "seven", 7, "eight", 8, "nine", 9);

    private static final Map<String, Integer> TEENS = Map.of(
            "ten", 10, "eleven", 11, "twelve", 12, "thirteen", 13, "fourteen", 14,
            "fifteen", 15, "sixteen", 16, "seventeen", 17, "eighteen", 18, "nineteen", 19);

    private static final Map<String, Integer> TENS = Map.of(
            "twenty", 20, "thirty", 30, "forty", 40, "fifty", 50,
            "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90);

    private static final Map<String, Long> SCALES = Map.of("thousand", 1_000L, "million", 1_000_000L);

    private static final Set<String> OTHER_WORDS = Set.of("zero", "a", "and", "hundred", "dozen");

    private NumberReader() {}

    public static Optional<Reading> leading(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String[] tokens = text.trim().split("\\s+");
        if (DIGITS.matcher(tokens[0]).matches()) {
            return Optional.of(new Reading(new BigDecimal(tokens[0].replace(",", "")), tokens[0]));
        }
        int usable = 0;
        while (usable < tokens.length && Arrays.stream(tokens[usable].split("-")).allMatch(NumberReader::isNumberWord)) {
            usable++;
        }
        for (int end = usable; end >= 1; end--) {
            List<String> words = new ArrayList<>();
            for (int i = 0; i < end; i++) {
                words.addAll(List.of(tokens[i].split("-")));
            }
            Optional<Long> value = valueOf(words);
            if (value.isPresent()) {
                return Optional.of(new Reading(BigDecimal.valueOf(value.get()),
                        String.join(" ", Arrays.copyOfRange(tokens, 0, end))));
            }
        }
        return Optional.empty();
    }

    private static boolean isNumberWord(String word) {
        return UNITS.containsKey(word) || TEENS.containsKey(word) || TENS.containsKey(word)
                || SCALES.containsKey(word) || OTHER_WORDS.contains(word);
    }

    private static Optional<Long> valueOf(List<String> words) {
        if (words.size() == 1 && "zero".equals(words.get(0))) {
            return Optional.of(0L);
        }
        long total = 0;
        long current = 0;
        long lastScale = Long.MAX_VALUE;
        String prev = null;
        boolean any = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            boolean last = i == words.size() - 1;
            if ("a".equals(word)) {
                if (i != 0 || last || !Set.of("hundred", "thousand", "million", "dozen").contains(words.get(1))) {
                    return Optional.empty();
                }
                current = 1;
            } else if ("and".equals(word)) {
                if (last || !("hundred".equals(prev) || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
            } else if (UNITS.containsKey(word)) {
                if (!(prev == null || TENS.containsKey(prev) || "hundred".equals(prev) || "and".equals(prev)
                        || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
                current += UNITS.get(word);
                any = true;
            } else if (TEENS.containsKey(word) || TENS.containsKey(word)) {
                if (!(prev == null || "hundred".equals(prev) || "and".equals(prev) || SCALES.containsKey(prev))) {
                    return Optional.empty();
                }
                current += TEENS.containsKey(word) ? TEENS.get(word) : TENS.get(word);
                any = true;
            } else if ("hundred".equals(word)) {
                if (!(UNITS.containsKey(prev) || "a".equals(prev))) {
                    return Optional.empty();
                }
                current *= 100;
                any = true;
            } else if ("dozen".equals(word)) {
                if (!last || !("a".equals(prev) || UNITS.containsKey(prev))) {
                    return Optional.empty();
                }
                current *= 12;
                any = true;
            } else if (SCALES.containsKey(word)) {
                long scale = SCALES.get(word);
                if (current == 0 || scale >= lastScale) {
                    return Optional.empty();
                }
                total += current * scale;
                current = 0;
                lastScale = scale;
                any = true;
            } else {
                return Optional.empty();
            }
            prev = word;
        }
        return any ? Optional.of(total + current) : Optional.empty();
    }
}
```

`QueryIntent.java` — inside `record Literal`, after `number(int n)`, add (and `import java.math.BigDecimal;`):

```java
        public static Literal number(BigDecimal n) {
            BigDecimal plain = n.stripTrailingZeros();
            Object value;
            if (plain.scale() <= 0 && plain.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
                long whole = plain.longValueExact();
                value = whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? (Object) (int) whole : (Object) whole;
            } else {
                value = plain.doubleValue();
            }
            return new Literal(plain.toPlainString(), true, value);
        }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='EditsTest,NumberReaderTest'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): one-edit comparison and a number reader for digits and English number words

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: The built-in synonym list

**Files:**
- Create: `backend/src/main/resources/querybuilder/synonyms.json`, `backend/src/main/java/com/querysense/backend/querybuilder/Synonyms.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java` (add `instructionWords()`)
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SynonymListTest.java`

**Interfaces:**
- Produces:
  - `Synonyms.builtIn()`
  - `new Synonyms(List<List<String>> groups)`
  - `Set<String> Synonyms.of(String word)`, meaning the other words of every group containing `word`
  - `Set<String> Synonyms.allWords()`
  - `static Set<String> QueryIntentParser.instructionWords()`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SynonymListTest {

    @Test
    @DisplayName("the built-in list loads, and a group answers in both directions")
    void loadsAndAnswers() {
        Synonyms synonyms = Synonyms.builtIn();

        assertThat(synonyms.of("client")).contains("customer");
        assertThat(synonyms.of("customer")).contains("client", "buyer");
        assertThat(synonyms.of("customer")).doesNotContain("customer");
        assertThat(synonyms.of("nothing")).isEmpty();
    }

    @Test
    @DisplayName("no synonym is a word the parser reads as an instruction")
    void noInstructionWords() {
        Set<String> clash = new HashSet<>(Synonyms.builtIn().allWords());
        clash.retainAll(QueryIntentParser.instructionWords());

        assertThat(clash).isEmpty();
    }

    @Test
    @DisplayName("no word sits in two groups")
    void noWordInTwoGroups() {
        Synonyms synonyms = new Synonyms(List.of(List.of("a1", "b1"), List.of("c1", "d1")));
        assertThat(synonyms.allWords()).containsExactlyInAnyOrder("a1", "b1", "c1", "d1");

        List<List<String>> groups = Synonyms.builtInGroups();
        Set<String> seen = new HashSet<>();
        groups.forEach(group -> group.forEach(word -> assertThat(seen.add(word)).as(word).isTrue()));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SynonymListTest`
Expected: compilation failure — `Synonyms` and `instructionWords` don't exist.

- [ ] **Step 3: Implement**

`synonyms.json`:

```json
[
  ["customer", "client", "buyer", "shopper", "patron"],
  ["price", "cost"],
  ["city", "town"],
  ["product", "goods", "merchandise"],
  ["employee", "staff", "worker"],
  ["quantity", "qty"],
  ["phone", "telephone", "mobile"],
  ["supplier", "vendor"],
  ["department", "division"],
  ["email", "mail"]
]
```

`Synonyms.java`:

```java
package com.querysense.backend.querybuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The built-in English synonym groups the rule engine may use to reach a table or column. */
public final class Synonyms {

    private static final String RESOURCE = "/querybuilder/synonyms.json";

    private final List<Set<String>> groups;

    public Synonyms(List<List<String>> groups) {
        this.groups = groups.stream()
                .map(g -> {
                    Set<String> words = new LinkedHashSet<>();
                    g.forEach(w -> words.add(w.toLowerCase(Locale.ROOT).trim()));
                    return Set.copyOf(words);
                })
                .toList();
    }

    public static Synonyms builtIn() {
        return new Synonyms(builtInGroups());
    }

    static List<List<String>> builtInGroups() {
        try (InputStream in = Synonyms.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new ObjectMapper().readValue(in, new TypeReference<List<List<String>>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    public Set<String> of(String word) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> group : groups) {
            if (group.contains(word)) {
                group.stream().filter(w -> !w.equals(word)).forEach(out::add);
            }
        }
        return out;
    }

    public Set<String> allWords() {
        Set<String> out = new LinkedHashSet<>();
        groups.forEach(out::addAll);
        return out;
    }
}
```

`QueryIntentParser.java` — add after the constructor:

```java
    static Set<String> instructionWords() {
        Set<String> words = new java.util.HashSet<>();
        COUNT_WORDS.forEach(w -> words.addAll(List.of(w.split(" "))));
        words.addAll(AGGREGATE_FUNCTIONS.keySet());
        words.addAll(PROJECTION_FILLER_WORDS);
        words.addAll(NON_COLUMN_WORDS);
        words.addAll(VALUE_STOPWORDS);
        words.addAll(List.of("top", "first", "limit", "only", "just", "number", "total", "delete", "remove"));
        return Set.copyOf(words);
    }
```

- [ ] **Step 4: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=SynonymListTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): a reviewed built-in synonym list, kept clear of the parser's instruction words

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `SchemaAliases`, and aliases on the snapshot

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/schema/SchemaAliases.java`
- Modify: `backend/src/main/java/com/querysense/backend/schema/SchemaSnapshot.java`, `schema/ddl/ParsedSchema.java`, `schema/KnownSchema.java`, `schema/KnownMongoSchema.java`
- Test: `backend/src/test/java/com/querysense/backend/schema/SchemaAliasesTest.java`

**Interfaces:**
- Produces:
  - `record SchemaAliases.TableAliases(List<String> table, Map<String, List<String>> columns)`, with `TableAliases.NONE` and `List<String> column(String name)`
  - `static List<String> SchemaAliases.fromComment(String text)`
  - `static String SchemaAliases.normalize(String alias)`
  - `static TableAliases SchemaAliases.merge(TableAliases a, TableAliases b)`
  - `record SchemaAliases.Cleaned(Map<String, TableAliases> aliases, List<String> warnings)`
  - `static Cleaned SchemaAliases.clean(List<KnownSchema.Table> tables, Map<String, TableAliases> declared)`
  - `static String SchemaAliases.describe(String name, List<String> aliases)`
  - `SchemaSnapshot`'s 12th component `Map<String, SchemaAliases.TableAliases> aliases`, an 11-argument constructor without it, and `TableAliases aliasesFor(String table)`
  - `ParsedSchema`'s 4th component `Map<String, SchemaAliases.TableAliases> aliases`
  - `KnownSchema.ALIASES` and `KnownMongoSchema.ALIASES`, both `customers` aka `buyers`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaAliasesTest {

    private static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city")),
            new KnownSchema.Table("accounts", List.of("id", "owner")));

    @Test
    @DisplayName("aka: in a comment declares aliases; text before it is ignored")
    void fromComment() {
        assertThat(SchemaAliases.fromComment("'Registered buyers. aka: clients, patrons'"))
                .containsExactly("clients", "patrons");
        assertThat(SchemaAliases.fromComment("\"aka: mail\"")).containsExactly("mail");
        assertThat(SchemaAliases.fromComment("aka: order lines; internal use")).containsExactly("order lines");
        assertThat(SchemaAliases.fromComment("Where they live")).isEmpty();
        assertThat(SchemaAliases.fromComment(null)).isEmpty();
    }

    @Test
    @DisplayName("aliasesAreLowercased: capitals and underscores are normalised like names")
    void aliasesAreLowercased() {
        assertThat(SchemaAliases.fromComment("aka: Clients, BUYERS, Order_Lines"))
                .containsExactly("clients", "buyers", "order lines");
    }

    @Test
    @DisplayName("clean keeps good aliases and drops collisions, each with a warning")
    void clean() {
        Map<String, SchemaAliases.TableAliases> declared = Map.of(
                "customers", new SchemaAliases.TableAliases(List.of("clients", "accounts", "members"),
                        Map.of("city", List.of("town", "name"), "name", List.of("label"))),
                "accounts", new SchemaAliases.TableAliases(List.of("members"), Map.of()),
                "ghosts", new SchemaAliases.TableAliases(List.of("spirits"), Map.of()));

        SchemaAliases.Cleaned cleaned = SchemaAliases.clean(TABLES, declared);

        assertThat(cleaned.aliases().get("customers").table()).containsExactly("clients");
        assertThat(cleaned.aliases().get("customers").column("city")).containsExactly("town");
        assertThat(cleaned.aliases().get("customers").column("name")).containsExactly("label");
        assertThat(cleaned.aliases()).doesNotContainKey("accounts");
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"accounts\"").contains("table"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"members\"").contains("more than one"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("\"name\"").contains("customers.city"));
        assertThat(cleaned.warnings()).anySatisfy(w -> assertThat(w).contains("ghosts"));
    }

    @Test
    @DisplayName("the built-in schemas declare customers aka buyers; an unknown table has none")
    void builtIns() {
        assertThat(KnownSchema.builtIn().aliasesFor("customers").table()).containsExactly("buyers");
        assertThat(KnownMongoSchema.builtIn().aliasesFor("customers").table()).containsExactly("buyers");
        assertThat(KnownSchema.builtIn().aliasesFor("orders")).isEqualTo(SchemaAliases.TableAliases.NONE);
    }

    @Test
    @DisplayName("describe writes the aliases a prompt shows, or the bare name")
    void describe() {
        assertThat(SchemaAliases.describe("customers", List.of("clients", "buyers")))
                .isEqualTo("customers (also called: clients, buyers)");
        assertThat(SchemaAliases.describe("orders", List.of())).isEqualTo("orders");
    }

    @Test
    @DisplayName("merge unions two declarations without repeats")
    void merge() {
        SchemaAliases.TableAliases merged = SchemaAliases.merge(
                new SchemaAliases.TableAliases(List.of("clients"), Map.of("city", List.of("town"))),
                new SchemaAliases.TableAliases(List.of("clients", "patrons"), Map.of("city", List.of("locality"))));

        assertThat(merged.table()).containsExactly("clients", "patrons");
        assertThat(merged.column("city")).containsExactly("town", "locality");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaAliasesTest`
Expected: compilation failure — `SchemaAliases` and `aliasesFor` don't exist.

- [ ] **Step 3: Implement**

`SchemaAliases.java`:

```java
package com.querysense.backend.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extra names a schema declares for its tables and columns, read from "aka:" in comments and descriptions. */
public final class SchemaAliases {

    public record TableAliases(List<String> table, Map<String, List<String>> columns) {
        public static final TableAliases NONE = new TableAliases(List.of(), Map.of());

        public List<String> column(String name) {
            return columns.getOrDefault(name, List.of());
        }

        public boolean isEmpty() {
            return table.isEmpty() && columns.isEmpty();
        }
    }

    public record Cleaned(Map<String, TableAliases> aliases, List<String> warnings) {}

    private static final Pattern AKA = Pattern.compile("(?i)\\baka:\\s*([^;]*)");

    private SchemaAliases() {}

    public static List<String> fromComment(String text) {
        if (text == null) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && (trimmed.startsWith("'") || trimmed.startsWith("\""))
                && trimmed.endsWith(trimmed.substring(0, 1))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Matcher m = AKA.matcher(trimmed);
        if (!m.find()) {
            return List.of();
        }
        return Arrays.stream(m.group(1).split(","))
                .map(s -> normalize(s.replaceAll("[.\\s]+$", "")))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public static String normalize(String alias) {
        return alias == null ? "" : alias.toLowerCase(Locale.ROOT).replaceAll("[_\\s]+", " ").trim();
    }

    public static TableAliases merge(TableAliases a, TableAliases b) {
        Set<String> table = new LinkedHashSet<>(a.table());
        table.addAll(b.table());
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (TableAliases source : List.of(a, b)) {
            source.columns().forEach((column, names) -> {
                Set<String> merged = new LinkedHashSet<>(columns.getOrDefault(column, List.of()));
                merged.addAll(names);
                columns.put(column, List.copyOf(merged));
            });
        }
        return new TableAliases(List.copyOf(table), Map.copyOf(columns));
    }

    public static String describe(String name, List<String> aliases) {
        return aliases.isEmpty() ? name : name + " (also called: " + String.join(", ", aliases) + ")";
    }

    public static Cleaned clean(List<KnownSchema.Table> tables, Map<String, TableAliases> declared) {
        List<String> warnings = new ArrayList<>();
        Set<String> tableNames = new LinkedHashSet<>();
        Map<String, KnownSchema.Table> byName = new LinkedHashMap<>();
        for (KnownSchema.Table table : tables) {
            tableNames.add(normalize(table.name()));
            tableNames.add(normalize(table.singularName()));
            byName.put(table.name(), table);
        }
        declared.keySet().stream().filter(t -> !byName.containsKey(t)).sorted().forEach(t -> warnings.add(
                "Aliases were declared for \"" + t + "\", which isn't a table in this schema, so they were dropped."));

        Map<String, List<String>> owners = new HashMap<>();
        declared.forEach((table, aliases) -> {
            if (byName.containsKey(table)) {
                aliases.table().forEach(a -> owners.computeIfAbsent(a, k -> new ArrayList<>()).add(table));
            }
        });

        Map<String, TableAliases> kept = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (KnownSchema.Table table : tables) {
            TableAliases aliases = declared.getOrDefault(table.name(), TableAliases.NONE);
            List<String> keptTable = new ArrayList<>();
            for (String alias : aliases.table()) {
                if (tableNames.contains(alias)) {
                    warnings.add("The alias \"" + alias + "\" declared for " + table.name()
                            + " is already the name of a table, so it was dropped.");
                } else if (owners.get(alias).size() > 1) {
                    if (reported.add(alias)) {
                        warnings.add("The alias \"" + alias + "\" was declared for more than one table ("
                                + String.join(" and ", owners.get(alias)) + "), so none of them keeps it.");
                    }
                } else {
                    keptTable.add(alias);
                }
            }
            Map<String, List<String>> keptColumns = keptColumns(table, aliases, warnings);
            if (!keptTable.isEmpty() || !keptColumns.isEmpty()) {
                kept.put(table.name(), new TableAliases(List.copyOf(keptTable), Map.copyOf(keptColumns)));
            }
        }
        return new Cleaned(Map.copyOf(kept), List.copyOf(warnings));
    }

    private static Map<String, List<String>> keptColumns(KnownSchema.Table table, TableAliases aliases,
                                                         List<String> warnings) {
        Set<String> columnNames = new LinkedHashSet<>();
        table.columns().forEach(c -> columnNames.add(normalize(c.replaceAll("([a-z0-9])([A-Z])", "$1_$2"))));
        Map<String, List<String>> owners = new HashMap<>();
        aliases.columns().forEach((column, names) ->
                names.forEach(n -> owners.computeIfAbsent(n, k -> new ArrayList<>()).add(column)));

        Map<String, List<String>> kept = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : aliases.columns().entrySet()) {
            String column = entry.getKey();
            if (!table.columns().contains(column)) {
                warnings.add("Aliases were declared for " + table.name() + "." + column
                        + ", which isn't a column of that table, so they were dropped.");
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (String alias : entry.getValue()) {
                if (columnNames.contains(alias)) {
                    warnings.add("The alias \"" + alias + "\" declared for " + table.name() + "." + column
                            + " is already the name of a column of " + table.name() + ", so it was dropped.");
                } else if (owners.get(alias).size() > 1) {
                    if (reported.add(alias)) {
                        warnings.add("The alias \"" + alias + "\" was declared for more than one column of "
                                + table.name() + ", so none of them keeps it.");
                    }
                } else {
                    keep.add(alias);
                }
            }
            if (!keep.isEmpty()) {
                kept.put(column, List.copyOf(keep));
            }
        }
        return kept;
    }
}
```

`SchemaSnapshot.java`:
- Add the component `Map<String, SchemaAliases.TableAliases> aliases` after `enums`.
- Add this constructor, which keeps every existing 11-argument call compiling:

```java
    public SchemaSnapshot(String id, String label, String catalog,
                          List<KnownSchema.Table> tables,
                          List<KnownSchema.ForeignKey> foreignKeys,
                          Map<String, Integer> rowCounts,
                          Instant loadedAt,
                          Dialect dialect,
                          List<IndexSpec> indexes,
                          Map<String, Map<String, String>> fieldTypes,
                          Map<String, Map<String, List<String>>> enums) {
        this(id, label, catalog, tables, foreignKeys, rowCounts, loadedAt, dialect, indexes, fieldTypes, enums, Map.of());
    }

    public SchemaAliases.TableAliases aliasesFor(String tableName) {
        return aliases.getOrDefault(tableName, SchemaAliases.TableAliases.NONE);
    }
```

- The three shorter constructors already delegate through `this(...)` with eleven arguments, so they now reach the constructor above unchanged.

`ParsedSchema.java`:
- The record becomes `ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes, Map<String, SchemaAliases.TableAliases> aliases)`.
- Keep the 2-argument constructor; it now calls `this(tables, warnings, List.of(), Map.of())`.
- Add `public ParsedSchema(List<ParsedTable> tables, List<String> warnings, List<IndexSpec> indexes) { this(tables, warnings, indexes, Map.of()); }`.
- Add `import com.querysense.backend.schema.SchemaAliases;`.

`KnownSchema.java`: add `import com.querysense.backend.dialect.Dialect;` and `import com.querysense.backend.schema.IndexSpec;` if missing, and:

```java
    public static final Map<String, SchemaAliases.TableAliases> ALIASES = Map.of(
            "customers", new SchemaAliases.TableAliases(List.of("buyers"), Map.of()));
```

and make `builtIn()` return:

```java
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense demo schema (built-in)",
                null,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH,
                Dialect.MYSQL,
                List.of(),
                Map.of(),
                Map.of(),
                ALIASES);
```

`KnownMongoSchema.java`: add the same `ALIASES` constant, and pass `ALIASES` as a twelfth argument after `ENUMS` in `builtIn()`.

- [ ] **Step 4: Run the test, then the schema and query builder suites**

Run: `cd backend && ./mvnw -q test -Dtest='SchemaAliasesTest,*Schema*Test,QueryIntentParserTest,DialectParityTest'`
Expected: PASS. Nothing reads aliases yet, so no other test changes.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(schema): aliases declared with aka:, cleaned of collisions, carried on the snapshot

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Reading aliases from uploads

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/schema/ddl/DdlSchemaParser.java`, `schema/json/JsonSchemaUploadParser.java`, `schema/upload/SchemaUploadService.java`
- Test: add to `backend/src/test/java/com/querysense/backend/schema/ddl/DdlSchemaParserTest.java`, `schema/json/JsonSchemaUploadParserTest.java`, `schema/upload/SchemaUploadServiceTest.java`

**Interfaces:**
- Consumes: `SchemaAliases.fromComment`, `merge`, `clean`, and `TableAliases` (Task 3); `ParsedSchema`'s 4-argument constructor (Task 3).
- Produces: `ParsedSchema.aliases()`, filled by both parsers; snapshots published with cleaned aliases, and the cleaning warnings in `lastWarnings`.

- [ ] **Step 1: Write the failing tests**

Add to `DdlSchemaParserTest` (also add `import com.querysense.backend.schema.SchemaAliases;`):

```java
    @Test
    @DisplayName("aka: in table and column COMMENTs declares aliases; ordinary comments don't")
    void commentAliases() {
        ParsedSchema schema = parser.parse("""
                CREATE TABLE customers (
                  id INT PRIMARY KEY,
                  city VARCHAR(60) NOT NULL COMMENT 'Where they live. aka: town, locality',
                  email VARCHAR(80) COMMENT "aka: mail",
                  phone VARCHAR(20) COMMENT 'Mobile number'
                ) ENGINE=InnoDB COMMENT='Registered buyers. aka: clients, patrons';
                CREATE TABLE notes (id INT PRIMARY KEY) COMMENT 'internal';
                """);

        SchemaAliases.TableAliases customers = schema.aliases().get("customers");
        assertThat(customers.table()).containsExactly("clients", "patrons");
        assertThat(customers.column("city")).containsExactly("town", "locality");
        assertThat(customers.column("email")).containsExactly("mail");
        assertThat(customers.column("phone")).isEmpty();
        assertThat(schema.aliases()).doesNotContainKey("notes");
    }
```

Add to `JsonSchemaUploadParserTest`:

```java
    @Test
    @DisplayName("aka: in collection and field descriptions, and the aliases block, merge into one declaration")
    void descriptionAndBlockAliases() {
        ParsedSchema schema = parser.parse("""
                {"collections": [{"name": "customers", "validator": {"$jsonSchema": {
                    "description": "People who buy. aka: clients",
                    "properties": {
                      "city": {"bsonType": "string", "description": "aka: town"},
                      "address": {"bsonType": "object", "properties": {
                        "zip": {"bsonType": "string", "description": "aka: postcode"}}}}}}}],
                 "aliases": {"customers": {"table": ["patrons"], "columns": {"city": ["locality"]}}}}
                """);

        assertThat(schema.aliases().get("customers").table()).containsExactly("clients", "patrons");
        assertThat(schema.aliases().get("customers").column("city")).containsExactly("town", "locality");
        assertThat(schema.aliases().get("customers").column("address.zip")).containsExactly("postcode");
    }
```

Add to `SchemaUploadServiceTest`:

```java
    @Test
    @DisplayName("declared aliases are published, and a colliding one is dropped with a warning")
    void aliasesArePublished() {
        SchemaUploadService uploader = service();
        SchemaSnapshot snapshot = uploader.upload("""
                CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(80) COMMENT 'aka: label')
                  COMMENT 'aka: clients, orders';
                CREATE TABLE orders (id INT PRIMARY KEY);
                """, "shop.sql");

        assertThat(snapshot.aliasesFor("customers").table()).containsExactly("clients");
        assertThat(snapshot.aliasesFor("customers").column("name")).containsExactly("label");
        assertThat(uploader.lastWarnings(Dialect.MYSQL))
                .anySatisfy(w -> assertThat(w).contains("\"orders\"").contains("already the name of a table"));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='DdlSchemaParserTest,JsonSchemaUploadParserTest,SchemaUploadServiceTest'`
Expected: the three new tests fail. `aliases()` is empty, and the snapshot has no aliases.

- [ ] **Step 3: Implement**

`DdlSchemaParser.java`:
- Add the imports `com.querysense.backend.schema.SchemaAliases`, `java.util.LinkedHashMap` and `java.util.Map`.
- In `parse`, declare `Map<String, SchemaAliases.TableAliases> aliases = new LinkedHashMap<>();` beside `warnings`.
- Replace `tables.add(toParsedTable(createTable));` with:

```java
                    ParsedTable table = toParsedTable(createTable);
                    tables.add(table);
                    aliasesOf(createTable).ifPresent(a -> aliases.put(table.name(), a));
```

- Return `new ParsedSchema(List.copyOf(tables), List.copyOf(warnings), List.of(), Map.copyOf(aliases));`.
- Add:

```java
    private Optional<SchemaAliases.TableAliases> aliasesOf(CreateTable createTable) {
        List<String> table = SchemaAliases.fromComment(commentIn(createTable.getTableOptionsStrings()));
        Map<String, List<String>> columns = new LinkedHashMap<>();
        if (createTable.getColumnDefinitions() != null) {
            for (ColumnDefinition definition : createTable.getColumnDefinitions()) {
                List<String> found = SchemaAliases.fromComment(commentIn(definition.getColumnSpecs()));
                if (!found.isEmpty()) {
                    columns.put(unquote(definition.getColumnName()), found);
                }
            }
        }
        return table.isEmpty() && columns.isEmpty()
                ? Optional.empty()
                : Optional.of(new SchemaAliases.TableAliases(table, Map.copyOf(columns)));
    }

    private static String commentIn(List<String> tokens) {
        if (tokens == null) {
            return null;
        }
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("COMMENT".equalsIgnoreCase(tokens.get(i))) {
                String next = tokens.get(i + 1);
                return "=".equals(next) && i + 2 < tokens.size() ? tokens.get(i + 2) : next;
            }
        }
        return null;
    }
```

`JsonSchemaUploadParser.java`:
- Add `import com.querysense.backend.schema.SchemaAliases;` and `java.util.LinkedHashMap`.
- `private record Collection(ParsedTable table, List<IndexSpec> indexes, SchemaAliases.TableAliases aliases)`.
- In `parseCollection`, return `new Collection(table, indexes, aliasesOf(jsonSchema));`.
- In `parse`:

```java
        Map<String, SchemaAliases.TableAliases> aliases = new LinkedHashMap<>();
        for (JsonNode entry : collectionEntries(root)) {
            Collection parsed = parseCollection(entry, warnings);
            if (parsed != null) {
                tables.add(parsed.table());
                indexes.addAll(parsed.indexes());
                if (!parsed.aliases().isEmpty()) {
                    aliases.put(parsed.table().name(), parsed.aliases());
                }
            }
        }
        if (root.isObject()) {
            for (Map.Entry<String, JsonNode> declared : root.path("aliases").properties()) {
                SchemaAliases.TableAliases block = aliasBlock(declared.getValue());
                aliases.merge(declared.getKey(), block, SchemaAliases::merge);
            }
        }
        return new ParsedSchema(List.copyOf(tables), List.copyOf(warnings), List.copyOf(indexes), Map.copyOf(aliases));
```

- Add:

```java
    private static SchemaAliases.TableAliases aliasesOf(JsonNode jsonSchema) {
        List<String> table = SchemaAliases.fromComment(jsonSchema.path("description").asText(null));
        Map<String, List<String>> columns = new LinkedHashMap<>();
        collectFieldAliases(jsonSchema, "", columns);
        return new SchemaAliases.TableAliases(table, Map.copyOf(columns));
    }

    private static void collectFieldAliases(JsonNode schema, String prefix, Map<String, List<String>> out) {
        for (Map.Entry<String, JsonNode> field : schema.path("properties").properties()) {
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode node = field.getValue();
            List<String> found = SchemaAliases.fromComment(node.path("description").asText(null));
            if (!found.isEmpty()) {
                out.put(path, found);
            }
            collectFieldAliases(node, path, out);
            if (node.path("items").isObject()) {
                collectFieldAliases(node.get("items"), path, out);
            }
        }
    }

    private static SchemaAliases.TableAliases aliasBlock(JsonNode block) {
        List<String> table = new ArrayList<>();
        block.path("table").forEach(n -> table.add(SchemaAliases.normalize(n.asText())));
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> column : block.path("columns").properties()) {
            List<String> names = new ArrayList<>();
            column.getValue().forEach(n -> names.add(SchemaAliases.normalize(n.asText())));
            columns.put(column.getKey(), List.copyOf(names));
        }
        return new SchemaAliases.TableAliases(List.copyOf(table), Map.copyOf(columns));
    }
```

`SchemaUploadService.java`:
- Add `import com.querysense.backend.schema.SchemaAliases;`.
- Replace `lastWarnings.put(dialect, parsed.warnings());` with:

```java
        SchemaAliases.Cleaned aliases = SchemaAliases.clean(parsed.toKnownTables(), parsed.aliases());
        List<String> uploadWarnings = new ArrayList<>(parsed.warnings());
        uploadWarnings.addAll(aliases.warnings());
        lastWarnings.put(dialect, List.copyOf(uploadWarnings));
```

- In the seeded-warnings branch, use `new ArrayList<>(uploadWarnings)` in place of `new ArrayList<>(parsed.warnings())`.
- Pass `aliases.aliases()` as the twelfth argument after `parsed.enums()`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='DdlSchemaParserTest,JsonSchemaUploadParserTest,SchemaUploadServiceTest,*Upload*Test'`
Expected: PASS, the new tests included.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(upload): aliases are read from SQL COMMENTs, MongoDB descriptions and an aliases block

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `SchemaLexicon` behind `SchemaBinder`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaLexicon.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/SchemaBinder.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/SchemaLexiconTest.java`; `SchemaBinderTest` must pass unchanged

**Interfaces:**
- Consumes: `Synonyms` (Task 2); `Edits` (Task 1); `SchemaSnapshot.aliasesFor` and `SchemaAliases` (Task 3).
- Produces:
  - `SchemaLexicon.MatchKind { EXACT, INFLECTION, SCHEMA_ALIAS, SYNONYM, TYPO }`
  - `record Binding(String item, String phrase, MatchKind kind)`
  - `record TableHit(KnownSchema.Table table, Binding binding)`
  - `record Ambiguity(String phrase, List<String> candidates)`
  - `record TableMatches(List<TableHit> hits, Optional<Ambiguity> ambiguity)`
  - `sealed interface ColumnMatch permits Bound, Ambiguous, Unbound`, with `record Bound(Binding binding)`, `record Ambiguous(Ambiguity ambiguity)` and `record Unbound()`
  - `TableMatches SchemaLexicon.tables(String normalizedText, Dialect dialect)`
  - `boolean SchemaLexicon.isTableWord(String word, Dialect dialect)`
  - `ColumnMatch SchemaLexicon.column(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases)`
  - `Optional<Binding> SchemaLexicon.exactOrAlias(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases)`
  - `SchemaBinder.tableMatches(String, Dialect)`, `SchemaBinder.isTableWord(String, Dialect)` and `SchemaBinder.columnMatch(KnownSchema.Table, String)`
  - `SchemaBinder.bindColumn` keeps its signature, with exact-or-alias meaning; `SchemaBinder.bindTables` returns every hit's table.

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaLexiconTest {

    private static final KnownSchema.Table CUSTOMERS = new KnownSchema.Table("customers", List.of("id", "name", "city", "email"));
    private static final KnownSchema.Table SUPPLIERS = new KnownSchema.Table("suppliers", List.of("id", "phone", "mobile"));
    private static final KnownSchema.Table ACCOUNTS = new KnownSchema.Table("accounts", List.of("id", "owner"));
    private static final KnownSchema.Table PEOPLE = new KnownSchema.Table("person", List.of("id", "addresses"));

    private ActiveSchemaProvider schemas;
    private SchemaLexicon lexicon;

    private void publish(List<KnownSchema.Table> tables, Map<String, SchemaAliases.TableAliases> aliases) {
        schemas.publish(new SchemaSnapshot("upload-l", "l", "querysense_uploads", tables, List.of(), Map.of(),
                Instant.now(), Dialect.MYSQL, List.of(), Map.of(), Map.of(), aliases));
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        lexicon = new SchemaLexicon(schemas, Synonyms.builtIn());
        publish(List.of(CUSTOMERS, SUPPLIERS, PEOPLE), Map.of());
    }

    private SchemaLexicon.Binding onlyHit(String text) {
        SchemaLexicon.TableMatches matches = lexicon.tables(text, Dialect.MYSQL);
        assertThat(matches.ambiguity()).isEmpty();
        assertThat(matches.hits()).hasSize(1);
        return matches.hits().get(0).binding();
    }

    @Test
    @DisplayName("each match kind reaches its table")
    void tableKinds() {
        assertThat(onlyHit("show customers")).isEqualTo(new SchemaLexicon.Binding("customers", "customers", SchemaLexicon.MatchKind.EXACT));
        assertThat(onlyHit("each customer").kind()).isEqualTo(SchemaLexicon.MatchKind.INFLECTION);
        assertThat(onlyHit("all people").item()).isEqualTo("person");
        assertThat(onlyHit("clients in pune")).isEqualTo(new SchemaLexicon.Binding("customers", "clients", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(onlyHit("custmers in pune")).isEqualTo(new SchemaLexicon.Binding("customers", "custmers", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("aliasBeatsSynonym: a declared alias wins over a built-in synonym of another table")
    void aliasBeatsSynonym() {
        publish(List.of(CUSTOMERS, ACCOUNTS), Map.of("accounts", new SchemaAliases.TableAliases(List.of("clients"), Map.of())));

        assertThat(onlyHit("clients in pune")).isEqualTo(new SchemaLexicon.Binding("accounts", "clients", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
    }

    @Test
    @DisplayName("a typo is only tried when no table matched otherwise, and never on short words")
    void typoLimits() {
        assertThat(lexicon.tables("customers near the bordr", Dialect.MYSQL).hits()).extracting(h -> h.table().name())
                .containsExactly("customers");
        assertThat(lexicon.tables("custs", Dialect.MYSQL).hits()).isEmpty();
    }

    @Test
    @DisplayName("columns: exact, inflection, alias, synonym, typo, and ambiguity")
    void columns() {
        SchemaAliases.TableAliases aliases = new SchemaAliases.TableAliases(List.of(), Map.of("city", List.of("locality")));

        assertThat(bound(CUSTOMERS, "email", SchemaAliases.TableAliases.NONE).kind()).isEqualTo(SchemaLexicon.MatchKind.EXACT);
        assertThat(bound(CUSTOMERS, "emails", SchemaAliases.TableAliases.NONE).kind()).isEqualTo(SchemaLexicon.MatchKind.INFLECTION);
        assertThat(bound(CUSTOMERS, "locality", aliases)).isEqualTo(new SchemaLexicon.Binding("city", "locality", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
        assertThat(bound(CUSTOMERS, "town", SchemaAliases.TableAliases.NONE)).isEqualTo(new SchemaLexicon.Binding("city", "town", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(bound(CUSTOMERS, "emial", SchemaAliases.TableAliases.NONE)).isEqualTo(new SchemaLexicon.Binding("email", "emial", SchemaLexicon.MatchKind.TYPO));
        assertThat(bound(PEOPLE, "address", SchemaAliases.TableAliases.NONE).item()).isEqualTo("addresses");

        SchemaLexicon.ColumnMatch telephone = lexicon.column(SUPPLIERS, "telephone", SchemaAliases.TableAliases.NONE);
        assertThat(telephone).isInstanceOfSatisfying(SchemaLexicon.Ambiguous.class,
                a -> assertThat(a.ambiguity().candidates()).containsExactlyInAnyOrder("phone", "mobile"));
        assertThat(lexicon.column(CUSTOMERS, "flurb", SchemaAliases.TableAliases.NONE)).isInstanceOf(SchemaLexicon.Unbound.class);
    }

    @Test
    @DisplayName("exactOrAlias ignores synonyms and typos")
    void exactOrAlias() {
        assertThat(lexicon.exactOrAlias(CUSTOMERS, "town", SchemaAliases.TableAliases.NONE)).isEmpty();
        assertThat(lexicon.exactOrAlias(CUSTOMERS, "city", SchemaAliases.TableAliases.NONE)).isPresent();
    }

    private SchemaLexicon.Binding bound(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        SchemaLexicon.ColumnMatch match = lexicon.column(table, phrase, aliases);
        assertThat(match).isInstanceOf(SchemaLexicon.Bound.class);
        return ((SchemaLexicon.Bound) match).binding();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaLexiconTest`
Expected: compilation failure — `SchemaLexicon` doesn't exist.

- [ ] **Step 3: Implement**

`SchemaLexicon.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
import com.querysense.backend.schema.SchemaSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every name, declared alias, inflection and built-in synonym that reaches a table or column of the active schema. */
public class SchemaLexicon {

    public enum MatchKind { EXACT, INFLECTION, SCHEMA_ALIAS, SYNONYM, TYPO }

    public record Binding(String item, String phrase, MatchKind kind) {}

    public record TableHit(KnownSchema.Table table, Binding binding) {}

    public record Ambiguity(String phrase, List<String> candidates) {}

    public record TableMatches(List<TableHit> hits, Optional<Ambiguity> ambiguity) {}

    public sealed interface ColumnMatch permits Bound, Ambiguous, Unbound {}

    public record Bound(Binding binding) implements ColumnMatch {}

    public record Ambiguous(Ambiguity ambiguity) implements ColumnMatch {}

    public record Unbound() implements ColumnMatch {}

    private static final Map<String, String> IRREGULAR_PLURALS = Map.of(
            "person", "people", "child", "children", "man", "men", "woman", "women");

    private record Entry(KnownSchema.Table table, MatchKind kind) {}

    private record Index(Map<String, List<Entry>> phrases, Set<String> knownWords) {}

    private record Cached(SchemaSnapshot snapshot, Index index) {}

    private record Span(int start, int end, String phrase, List<Entry> entries) {
        boolean containedIn(Span other) {
            boolean same = start == other.start && end == other.end;
            return !same && start >= other.start && end <= other.end;
        }
    }

    private final ActiveSchemaProvider activeSchema;
    private final Synonyms synonyms;
    private final Map<Dialect, Cached> cache = new ConcurrentHashMap<>();

    public SchemaLexicon(ActiveSchemaProvider activeSchema, Synonyms synonyms) {
        this.activeSchema = activeSchema;
        this.synonyms = synonyms;
    }

    public TableMatches tables(String text, Dialect dialect) {
        Index index = index(dialect);
        List<Span> spans = new ArrayList<>();
        index.phrases().forEach((phrase, entries) -> {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(text);
            if (m.find()) {
                spans.add(new Span(m.start(), m.end(), phrase, entries));
            }
        });

        Map<KnownSchema.Table, Span> perTable = new LinkedHashMap<>();
        for (Span span : spans) {
            if (span.entries().size() != 1) {
                continue;
            }
            KnownSchema.Table table = span.entries().get(0).table();
            Span current = perTable.get(table);
            if (current == null || better(span, current)) {
                perTable.put(table, span);
            }
        }
        List<Span> candidates = new ArrayList<>(perTable.values());
        spans.stream().filter(s -> s.entries().size() > 1).forEach(candidates::add);
        candidates.sort(Comparator.comparingInt(Span::start));

        List<TableHit> hits = new ArrayList<>();
        Optional<Ambiguity> ambiguity = Optional.empty();
        for (Span span : candidates) {
            if (candidates.stream().anyMatch(span::containedIn)) {
                continue;
            }
            if (span.entries().size() > 1) {
                if (ambiguity.isEmpty()) {
                    ambiguity = Optional.of(new Ambiguity(span.phrase(),
                            span.entries().stream().map(e -> e.table().name()).toList()));
                }
                continue;
            }
            Entry entry = span.entries().get(0);
            hits.add(new TableHit(entry.table(), new Binding(entry.table().name(), span.phrase(), entry.kind())));
        }

        if (hits.isEmpty() && ambiguity.isEmpty()) {
            for (String word : text.split(" ")) {
                if (word.length() < Edits.MIN_TYPO_LENGTH || index.knownWords().contains(word)) {
                    continue;
                }
                Set<KnownSchema.Table> near = new LinkedHashSet<>();
                index.phrases().forEach((phrase, entries) -> {
                    if (!phrase.contains(" ") && Edits.withinOne(word, phrase)) {
                        entries.forEach(e -> near.add(e.table()));
                    }
                });
                if (near.size() == 1) {
                    KnownSchema.Table table = near.iterator().next();
                    hits.add(new TableHit(table, new Binding(table.name(), word, MatchKind.TYPO)));
                    break;
                }
                if (near.size() > 1) {
                    ambiguity = Optional.of(new Ambiguity(word, near.stream().map(KnownSchema.Table::name).toList()));
                    break;
                }
            }
        }
        return new TableMatches(List.copyOf(hits), ambiguity);
    }

    public boolean isTableWord(String word, Dialect dialect) {
        return index(dialect).phrases().containsKey(SchemaBinder.normalize(word));
    }

    public Optional<Binding> exactOrAlias(KnownSchema.Table table, String phrase, SchemaAliases.TableAliases aliases) {
        String token = SchemaBinder.normalize(phrase);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Optional<Binding> exact = exactColumn(table, token);
        if (exact.isPresent()) {
            return exact;
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
        Optional<Binding> exact = exactColumn(table, token);
        if (exact.isPresent()) {
            return new Bound(exact.get());
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
        if (token.contains(" ") || token.length() < Edits.MIN_TYPO_LENGTH || isKnownWord(table, token, aliases)) {
            return new Unbound();
        }
        List<String> near = table.columns().stream()
                .filter(c -> columnWords(c, aliases).stream().anyMatch(w -> Edits.withinOne(token, w)))
                .toList();
        return pick(table, token, near, MatchKind.TYPO);
    }

    private static ColumnMatch pick(KnownSchema.Table table, String token, List<String> columns, MatchKind kind) {
        List<String> distinct = columns.stream().distinct().toList();
        if (distinct.size() == 1) {
            return new Bound(new Binding(distinct.get(0), token, kind));
        }
        if (distinct.size() > 1) {
            return new Ambiguous(new Ambiguity(token, distinct));
        }
        return new Unbound();
    }

    private static Optional<Binding> exactColumn(KnownSchema.Table table, String token) {
        String underscored = token.replace(' ', '_');
        String singular = underscored.endsWith("s") && underscored.length() > 1
                ? underscored.substring(0, underscored.length() - 1)
                : underscored;
        List<String> candidates = new ArrayList<>(List.of(underscored, singular));
        for (String form : forms(underscored)) {
            if (!candidates.contains(form)) {
                candidates.add(form);
            }
        }
        for (String candidate : candidates) {
            for (String col : table.columns()) {
                String snake = SchemaBinder.snakeCase(col);
                if (col.equalsIgnoreCase(candidate) || snake.equals(candidate) || snake.equals("_" + candidate)) {
                    return Optional.of(new Binding(col, token,
                            candidate.equals(underscored) ? MatchKind.EXACT : MatchKind.INFLECTION));
                }
            }
        }
        List<String> suffixMatches = table.columns().stream()
                .filter(col -> {
                    String snake = SchemaBinder.snakeCase(col);
                    return snake.endsWith("_" + singular) || snake.endsWith("_" + underscored)
                            || snake.endsWith("." + singular) || snake.endsWith("." + underscored);
                })
                .distinct()
                .toList();
        return suffixMatches.size() == 1
                ? Optional.of(new Binding(suffixMatches.get(0), token, MatchKind.INFLECTION))
                : Optional.empty();
    }

    private static List<String> aliasedColumns(KnownSchema.Table table, String token, SchemaAliases.TableAliases aliases) {
        return table.columns().stream()
                .filter(c -> aliases.column(c).stream().anyMatch(a -> phraseForms(a).contains(token)))
                .toList();
    }

    private boolean isSynonym(String token, String base) {
        if (base.contains(" ") || token.equals(base)) {
            return false;
        }
        Set<String> related = new HashSet<>(synonyms.of(base));
        related.addAll(synonyms.of(singular(base)));
        return related.contains(token) || related.contains(singular(token));
    }

    private boolean isKnownWord(KnownSchema.Table table, String token, SchemaAliases.TableAliases aliases) {
        return synonyms.allWords().contains(token)
                || table.columns().stream().anyMatch(c -> columnWords(c, aliases).contains(token));
    }

    private static Set<String> columnWords(String column, SchemaAliases.TableAliases aliases) {
        Set<String> words = new LinkedHashSet<>();
        String base = base(column);
        words.add(base);
        words.add(singular(base));
        words.addAll(aliases.column(column));
        return words;
    }

    private static String base(String column) {
        String last = column.substring(column.lastIndexOf('.') + 1);
        return SchemaBinder.normalize(SchemaBinder.snakeCase(last));
    }

    private Index index(Dialect dialect) {
        SchemaSnapshot snapshot = activeSchema.active(dialect);
        Cached cached = cache.get(dialect);
        if (cached == null || cached.snapshot() != snapshot) {
            cached = new Cached(snapshot, build(snapshot));
            cache.put(dialect, cached);
        }
        return cached.index();
    }

    private Index build(SchemaSnapshot snapshot) {
        Map<String, List<Entry>> all = new LinkedHashMap<>();
        for (KnownSchema.Table table : snapshot.tables()) {
            String name = SchemaBinder.normalize(table.name());
            String singular = SchemaBinder.normalize(table.singularName());
            put(all, name, table, MatchKind.EXACT);
            put(all, singular, table, MatchKind.INFLECTION);
            phraseForms(name).forEach(f -> put(all, f, table, MatchKind.INFLECTION));
            for (String alias : snapshot.aliasesFor(table.name()).table()) {
                phraseForms(alias).forEach(f -> put(all, f, table, MatchKind.SCHEMA_ALIAS));
            }
            if (!name.contains(" ")) {
                Set<String> related = new LinkedHashSet<>(synonyms.of(name));
                related.addAll(synonyms.of(singular));
                related.forEach(word -> forms(word).forEach(f -> put(all, f, table, MatchKind.SYNONYM)));
            }
        }
        Map<String, List<Entry>> best = new LinkedHashMap<>();
        Set<String> known = new HashSet<>(synonyms.allWords());
        all.forEach((phrase, entries) -> {
            MatchKind top = entries.stream().map(Entry::kind).min(Comparator.naturalOrder()).orElseThrow();
            Map<KnownSchema.Table, Entry> byTable = new LinkedHashMap<>();
            entries.stream().filter(e -> e.kind() == top).forEach(e -> byTable.putIfAbsent(e.table(), e));
            best.put(phrase, List.copyOf(byTable.values()));
            known.addAll(List.of(phrase.split(" ")));
        });
        return new Index(best, known);
    }

    private static void put(Map<String, List<Entry>> all, String phrase, KnownSchema.Table table, MatchKind kind) {
        if (!phrase.isEmpty()) {
            all.computeIfAbsent(phrase, k -> new ArrayList<>()).add(new Entry(table, kind));
        }
    }

    static Set<String> phraseForms(String phrase) {
        int space = phrase.lastIndexOf(' ');
        String head = space < 0 ? "" : phrase.substring(0, space + 1);
        String last = space < 0 ? phrase : phrase.substring(space + 1);
        Set<String> out = new LinkedHashSet<>();
        forms(last).forEach(f -> out.add(head + f));
        return out;
    }

    static Set<String> forms(String word) {
        Set<String> out = new LinkedHashSet<>();
        out.add(word);
        out.add(plural(word));
        out.add(singular(word));
        IRREGULAR_PLURALS.forEach((one, many) -> {
            if (word.equals(one)) {
                out.add(many);
            }
            if (word.equals(many)) {
                out.add(one);
            }
        });
        return out;
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.matches(".*(?:ses|xes|zes|ches|shes)$")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    static String plural(String word) {
        if (word.matches(".*[^aeiou]y$")) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.matches(".*(?:s|x|z|ch|sh)$")) {
            return word + "es";
        }
        return word + "s";
    }

    private static boolean better(Span candidate, Span current) {
        MatchKind a = candidate.entries().get(0).kind();
        MatchKind b = current.entries().get(0).kind();
        return a.compareTo(b) < 0 || (a == b && candidate.start() < current.start());
    }
}
```

`SchemaBinder.java`:
- Add the imports `com.querysense.backend.schema.SchemaAliases` and `com.querysense.backend.schema.SchemaSnapshot`.
- Keep `normalize` and `snakeCase` as they are.
- Replace the body from the field declarations through `bindColumn` with:

```java
    private final ActiveSchemaProvider activeSchema;
    private final SchemaLexicon lexicon;

    public SchemaBinder(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
        this.lexicon = new SchemaLexicon(activeSchema, Synonyms.builtIn());
    }

    public List<KnownSchema.Table> bindTables(String normalizedText, Dialect dialect) {
        return lexicon.tables(normalizedText, dialect).hits().stream().map(SchemaLexicon.TableHit::table).toList();
    }

    public SchemaLexicon.TableMatches tableMatches(String normalizedText, Dialect dialect) {
        return lexicon.tables(normalizedText, dialect);
    }

    public boolean isTableWord(String word, Dialect dialect) {
        return lexicon.isTableWord(word, dialect);
    }

    public Optional<String> bindColumn(KnownSchema.Table table, String phrase) {
        return lexicon.exactOrAlias(table, phrase, aliasesFor(table)).map(SchemaLexicon.Binding::item);
    }

    public SchemaLexicon.ColumnMatch columnMatch(KnownSchema.Table table, String phrase) {
        return lexicon.column(table, phrase, aliasesFor(table));
    }

    private SchemaAliases.TableAliases aliasesFor(KnownSchema.Table table) {
        for (Dialect dialect : Dialect.values()) {
            SchemaSnapshot snapshot = activeSchema.active(dialect);
            if (snapshot.tables().contains(table)) {
                return snapshot.aliasesFor(table.name());
            }
        }
        return SchemaAliases.TableAliases.NONE;
    }
```

- Delete the now-unused `Span` record, `longestSpanFor` and `firstMatch`.
- Remove any imports that are no longer used (`ArrayList`, `Comparator`, `Matcher`, `Pattern`).

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='SchemaLexiconTest,SchemaBinderTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,MongoRuleEngineTest,RuleBasedQueryBuilderSchemaTest'`
Expected: PASS. Every existing binder and parser test passes unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): SchemaLexicon indexes names, inflections, aliases, synonyms and typos behind SchemaBinder

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: The parser records interpretations and declines on ambiguity

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/Interpretation.java`, `AmbiguousWordException.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/RuleBuildResult.java`, `QueryIntentParser.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/VocabularyTest.java` (new)

**Interfaces:**
- Consumes: `SchemaBinder.tableMatches`, `isTableWord` and `columnMatch`, plus the `SchemaLexicon` types (Task 5).
- Produces:
  - `record Interpretation(String phrase, String item, SchemaLexicon.MatchKind kind)`
  - `RuleBuildResult(String sql, RuleTrace trace, List<Interpretation> interpretations)`, with a 2-argument constructor, `built(sql, trace, interpretations)` and `boolean interpreted()`, which is true when any kind is `SYNONYM` or `TYPO`
  - `static String QueryIntentParser.ambiguityMessage(String phrase, List<String> candidates)`

- [ ] **Step 1: Write the failing test**

`VocabularyTest.java`, which Tasks 7 and 8 extend:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaAliases;
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

/** The rule engine on a schema that declares no aliases: synonyms, typos, values and numbers. */
class VocabularyTest {

    private static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city", "email")),
            new KnownSchema.Table("orders", List.of("id", "customer_id", "amount", "status")),
            new KnownSchema.Table("suppliers", List.of("id", "name", "phone", "mobile")),
            new KnownSchema.Table("stores", List.of("id", "city", "region")));

    private ActiveSchemaProvider schemas;
    private QueryIntentParser parser;

    static SchemaSnapshot upload(Dialect dialect, Map<String, SchemaAliases.TableAliases> aliases) {
        return new SchemaSnapshot("upload-v", "shop", "querysense_uploads", TABLES,
                List.of(new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id")),
                Map.of(), Instant.now(), dialect, List.of(), Map.of(), Map.of(), aliases);
    }

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "New Delhi"));
        when(m.getDistinctValues(eq("stores"), eq("city"))).thenReturn(List.of("Central", "Mumbai"));
        when(m.getDistinctValues(eq("stores"), eq("region"))).thenReturn(List.of("Central", "West"));
        return m;
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        schemas.publish(upload(Dialect.MYSQL, Map.of()));
        schemas.publish(upload(Dialect.MONGODB, Map.of()));
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private RuleBuildResult sql(String question) {
        return parser.parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("a built-in synonym reaches the table, and is recorded as an interpretation")
    void synonymTable() {
        RuleBuildResult result = sql("clients in mumbai");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("clients", "customers", SchemaLexicon.MatchKind.SYNONYM));
        assertThat(result.interpreted()).isTrue();
    }

    @Test
    @DisplayName("a one-letter typo in the table name is corrected and recorded")
    void typoTable() {
        RuleBuildResult result = sql("custmers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("custmers", "customers", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("a typo in a column is corrected and recorded")
    void typoColumn() {
        RuleBuildResult result = sql("show emial of customers");

        assertThat(result.sql()).isEqualTo("SELECT email FROM customers;");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("emial", "customers.email", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("a synonym that fits two columns declines, naming both")
    void ambiguousSynonymDeclines() {
        RuleBuildResult result = sql("telephone of suppliers");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason())
                .isEqualTo("\"telephone\" could mean suppliers.phone or suppliers.mobile, so this engine won't pick one.");
    }

    @Test
    @DisplayName("a declared alias is recorded, but doesn't count as an interpretation that lowers confidence")
    void aliasIsNotInterpreted() {
        schemas.publish(upload(Dialect.MYSQL, Map.of("customers",
                new SchemaAliases.TableAliases(List.of("regulars"), Map.of()))));

        RuleBuildResult result = sql("regulars in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("regulars", "customers", SchemaLexicon.MatchKind.SCHEMA_ALIAS));
        assertThat(result.interpreted()).isFalse();
    }

    @Test
    @DisplayName("nameAndSynonymOfOneTable: the real name and a synonym of it are one table")
    void nameAndSynonymOfOneTable() {
        RuleBuildResult result = sql("customers and clients in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("an exact question has no interpretations")
    void exactHasNone() {
        RuleBuildResult result = sql("customers in pune");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(result.interpretations()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=VocabularyTest`
Expected: compilation failure — `Interpretation` and `RuleBuildResult.interpretations()` don't exist.

- [ ] **Step 3: Implement**

`Interpretation.java`:

```java
package com.querysense.backend.querybuilder;

/** A word in the question read as a schema item through an alias, a synonym or a typo correction. */
public record Interpretation(String phrase, String item, SchemaLexicon.MatchKind kind) {}
```

`AmbiguousWordException.java`:

```java
package com.querysense.backend.querybuilder;

/** A word reaches more than one schema item, so the rule engine declines rather than pick one. */
class AmbiguousWordException extends RuntimeException {

    AmbiguousWordException(String message) {
        super(message);
    }
}
```

`RuleBuildResult.java`:

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.dto.RuleTrace;

import java.util.List;
import java.util.Optional;

/** SQL built by the rule engine, or the reason it declined, with its trace and how it read the question. */
public record RuleBuildResult(String sql, RuleTrace trace, List<Interpretation> interpretations) {

    public RuleBuildResult(String sql, RuleTrace trace) {
        this(sql, trace, List.of());
    }

    public static RuleBuildResult built(String sql, RuleTrace trace) {
        return new RuleBuildResult(sql, trace);
    }

    public static RuleBuildResult built(String sql, RuleTrace trace, List<Interpretation> interpretations) {
        return new RuleBuildResult(sql, trace, List.copyOf(interpretations));
    }

    public static RuleBuildResult declined(String reason, List<RuleTrace.Binding> partialBindings) {
        return new RuleBuildResult(null,
                new RuleTrace(RuleTrace.DECLINED, null, partialBindings, null, List.of(), reason));
    }

    public boolean built() {
        return sql != null;
    }

    public boolean interpreted() {
        return interpretations.stream().anyMatch(i -> i.kind() == SchemaLexicon.MatchKind.SYNONYM
                || i.kind() == SchemaLexicon.MatchKind.TYPO);
    }

    public Optional<String> toOptional() {
        return Optional.ofNullable(sql);
    }
}
```

`QueryIntentParser.java`:

1. Rename today's `public RuleBuildResult parse(String rawInput, Dialect dialect)` to
   `private RuleBuildResult parse(String rawInput, Dialect dialect, List<RuleTrace.Binding> bindings, List<Interpretation> interpretations)`.
   Delete its first-lines declaration `List<RuleTrace.Binding> bindings = new ArrayList<>();`. Add a new public entry point above it:

```java
    public RuleBuildResult parse(String rawInput, Dialect dialect) {
        List<RuleTrace.Binding> bindings = new ArrayList<>();
        List<Interpretation> interpretations = new ArrayList<>();
        try {
            return parse(rawInput, dialect, bindings, interpretations);
        } catch (AmbiguousWordException e) {
            return RuleBuildResult.declined(e.getMessage(), bindings);
        }
    }
```

2. Replace `List<KnownSchema.Table> tables = binder.bindTables(text, dialect);` with:

```java
        SchemaLexicon.TableMatches tableMatches = binder.tableMatches(text, dialect);
        if (tableMatches.ambiguity().isPresent()) {
            SchemaLexicon.Ambiguity ambiguity = tableMatches.ambiguity().get();
            throw new AmbiguousWordException(ambiguityMessage(ambiguity.phrase(), ambiguity.candidates()));
        }
        List<KnownSchema.Table> tables = tableMatches.hits().stream().map(SchemaLexicon.TableHit::table).toList();
        tableMatches.hits().forEach(hit -> note(hit.binding().phrase(), hit.table().name(), hit.binding().kind(), interpretations));
```

3. In the conjoined-noun loop, extend `known` with `|| binder.isTableWord(word, dialect)` after `binder.tableNamed(word, dialect).isPresent()`.

4. Add helpers:

```java
    private Optional<String> column(KnownSchema.Table table, String phrase, List<Interpretation> interpretations) {
        SchemaLexicon.ColumnMatch match = binder.columnMatch(table, phrase);
        if (match instanceof SchemaLexicon.Ambiguous ambiguous) {
            throw new AmbiguousWordException(ambiguityMessage(ambiguous.ambiguity().phrase(),
                    ambiguous.ambiguity().candidates().stream().map(c -> table.name() + "." + c).toList()));
        }
        if (match instanceof SchemaLexicon.Bound bound) {
            note(bound.binding().phrase(), table.name() + "." + bound.binding().item(), bound.binding().kind(), interpretations);
            return Optional.of(bound.binding().item());
        }
        return Optional.empty();
    }

    private static void note(String phrase, String item, SchemaLexicon.MatchKind kind, List<Interpretation> interpretations) {
        if (kind == SchemaLexicon.MatchKind.SCHEMA_ALIAS || kind == SchemaLexicon.MatchKind.SYNONYM
                || kind == SchemaLexicon.MatchKind.TYPO) {
            Interpretation interpretation = new Interpretation(phrase, item, kind);
            if (!interpretations.contains(interpretation)) {
                interpretations.add(interpretation);
            }
        }
    }

    static String ambiguityMessage(String phrase, List<String> candidates) {
        return "\"" + phrase + "\" could mean " + listWith("or", candidates) + ", so this engine won't pick one.";
    }

    static String listWith(String conjunction, List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " " + conjunction + " " + items.get(items.size() - 1);
    }
```

5. Route every real column binding through `column(…, interpretations)`:
   - In the aggregate branch: `Optional<String> col = column(subject, agg.group(2), interpretations);`
   - In the grouping branch: `Optional<String> col = column(subject, per.group(1), interpretations);`
   - In the comparison branch: `Optional<String> lhsColumn = column(subject, comparison.group(1), interpretations);`
   - In the `with` branch: `Optional<String> column = column(subject, with.group(1), interpretations);` — rename that local to `withColumn` and use `withColumn` in place of `column` for the rest of that block, so it doesn't shadow the method.
   - In the order-by branch: `Optional<String> orderColumn = column(subject, order.group(1), interpretations);`, then use `orderColumn` in place of `column` for the rest of that block.
   - `resolveChunks(String rawPhrase, KnownSchema.Table table)` becomes `resolveChunks(String rawPhrase, KnownSchema.Table table, List<Interpretation> interpretations)`. Its two `binder.bindColumn(table, …)` calls become `column(table, …, interpretations)`, and both callers pass `interpretations`.
   - The probes `isColumnOfEither` and the conjoined-noun `binder.bindColumn` stay on `binder.bindColumn`.

6. The final return becomes `return RuleBuildResult.built(sql, trace, interpretations);`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='VocabularyTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,RuleBuildResultTest,RuleTraceWordingTest,MongoRuleEngineTest,RuleBasedQueryBuilderSchemaTest'`
Expected: PASS. The 7 `VocabularyTest` cases pass, and every other test is unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): synonyms and typos bind tables and columns, are recorded as interpretations, and decline when ambiguous

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Multi-word values, value typos, and a value in two columns

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `VocabularyTest.java`

**Interfaces:**
- Consumes: `Edits` (Task 1); `Interpretation`, `AmbiguousWordException` and `listWith` (Task 6).
- Produces: E14 reads runs of up to four words, corrects a single-word value typo, and declines on a value stored in more than one column.

- [ ] **Step 1: Write the failing tests**

Add to `VocabularyTest`:

```java
    @Test
    @DisplayName("a two-word value is matched as one value")
    void multiWordValue() {
        assertThat(sql("customers in new delhi").sql()).isEqualTo("SELECT * FROM customers WHERE city = 'new delhi';");
        assertThat(sql("customers in new delhi and pune").sql())
                .isEqualTo("SELECT * FROM customers WHERE city IN ('new delhi', 'pune');");
    }

    @Test
    @DisplayName("a one-letter typo in a value writes the stored value and is recorded")
    void valueTypo() {
        RuleBuildResult result = sql("customers in mumbia");

        assertThat(result.sql()).isEqualTo("SELECT * FROM customers WHERE city = 'Mumbai';");
        assertThat(result.interpretations()).containsExactly(
                new Interpretation("mumbia", "customers.city = Mumbai", SchemaLexicon.MatchKind.TYPO));
    }

    @Test
    @DisplayName("a value stored in two columns declines, naming both")
    void valueInTwoColumns() {
        RuleBuildResult result = sql("stores in central");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).isEqualTo(
                "\"Central\" is stored in both stores.city and stores.region, so this engine can't tell which one "
                        + "the question means.");
    }

    @Test
    @DisplayName("punctuationEndsTheValue: a question mark after the value doesn't lose the filter")
    void punctuationEndsTheValue() {
        assertThat(sql("clients in mumbai?").sql()).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=VocabularyTest`
Expected: the four new tests fail:
- `new delhi` declines on "new";
- `mumbia` declines;
- `central` builds on `stores.city`;
- the `?` case either declines or builds without a `WHERE`.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Replace `PREPOSITION_VALUE` with:

```java
    private static final Pattern PREPOSITION = Pattern.compile(
            "\\b(?:from|in|at|located in|living in|based in|residing in)\\s+(?=[a-z])");

    private static final int LONGEST_VALUE_WORDS = 4;
```

2. Replace `columnHolding` and the `ValueMatch` record with:

```java
    private record ValueMatch(String column, String stored) {}

    private record ValueRun(String typed, String column, String stored, int words, boolean typo) {}

    private List<ValueMatch> columnsHolding(KnownSchema.Table table, String value, SchemaMetadata metadata) {
        List<ValueMatch> matches = new ArrayList<>();
        for (String column : table.columns()) {
            storedValueOf(table, column, value, metadata).ifPresent(stored -> matches.add(new ValueMatch(column, stored)));
        }
        return matches;
    }

    private ValueRun valueRun(KnownSchema.Table table, String[] words, int from, SchemaMetadata metadata) {
        for (int n = Math.min(LONGEST_VALUE_WORDS, words.length - from); n >= 1; n--) {
            String phrase = String.join(" ", java.util.Arrays.copyOfRange(words, from, from + n));
            List<ValueMatch> matches = columnsHolding(table, phrase, metadata);
            if (matches.size() > 1) {
                List<String> columns = matches.stream().map(m -> table.name() + "." + m.column()).toList();
                throw new AmbiguousWordException("\"" + matches.get(0).stored() + "\" is stored in "
                        + (columns.size() == 2 ? "both " : "") + listWith("and", columns)
                        + ", so this engine can't tell which one the question means.");
            }
            if (matches.size() == 1) {
                return new ValueRun(phrase, matches.get(0).column(), matches.get(0).stored(), n, false);
            }
        }
        String word = words[from];
        if (word.length() < Edits.MIN_TYPO_LENGTH) {
            return null;
        }
        List<ValueMatch> near = new ArrayList<>();
        for (String column : table.columns()) {
            metadata.getDistinctValues(table.name(), column).stream()
                    .filter(v -> v != null && Edits.withinOne(word, v.toLowerCase(Locale.ROOT)))
                    .distinct()
                    .forEach(v -> near.add(new ValueMatch(column, v)));
        }
        if (near.size() > 1) {
            throw new AmbiguousWordException(ambiguityMessage(word,
                    near.stream().map(m -> table.name() + "." + m.column() + " = " + m.stored()).toList()));
        }
        return near.size() == 1 ? new ValueRun(word, near.get(0).column(), near.get(0).stored(), 1, true) : null;
    }
```

3. In the conjoined-noun check, replace `columnHolding(t, word, metadata).isPresent()` with `!columnsHolding(t, word, metadata).isEmpty()`.

4. Replace the whole `Matcher preposition = PREPOSITION_VALUE.matcher(text); while (preposition.find()) { … }` block with:

```java
        Matcher preposition = PREPOSITION.matcher(text);
        while (preposition.find()) {
            String rest = text.substring(preposition.end()).split("[^a-z\\s]", 2)[0].trim();
            String[] words = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            List<String> values = new ArrayList<>();
            List<QueryIntent.Literal> literals = new ArrayList<>();
            String valueColumn = null;

            int i = 0;
            while (i < words.length) {
                String first = words[i];
                if (VALUE_STOPWORDS.contains(first)
                        || binder.tableNamed(first, dialect).isPresent()
                        || isColumnOfEither(first, subject, related)) {
                    break;
                }
                ValueRun run = valueRun(subject, words, i, metadata);
                if (run == null) {
                    if (!values.isEmpty()) {
                        break;
                    }
                    return RuleBuildResult.declined(
                            "No column of " + subject.name() + " contains the value \"" + first
                                    + "\", so this engine cannot tell which field the question means.",
                            bindings);
                }
                if (valueColumn != null && !valueColumn.equals(run.column())) {
                    return RuleBuildResult.declined(
                            "\"" + values.get(0) + "\" and \"" + run.typed() + "\" are values of different "
                                    + "columns (" + valueColumn + " and " + run.column() + "), so this "
                                    + "engine cannot tell which one the question filters on.",
                            bindings);
                }
                valueColumn = run.column();
                values.add(run.typed());
                if (run.typo()) {
                    literals.add(QueryIntent.Literal.text(run.stored(), run.stored()));
                    note(run.typed(), subject.name() + "." + run.column() + " = " + run.stored(),
                            SchemaLexicon.MatchKind.TYPO, interpretations);
                } else {
                    literals.add(QueryIntent.Literal.text(run.typed(), run.stored()));
                }
                i += run.words();
                if (i < words.length && "and".equals(words[i])) {
                    i++;
                    continue;
                }
                break;
            }

            if (values.isEmpty()) {
                continue;
            }

            where.add(new QueryIntent.Predicate(qualify(valueColumn, subject, joined),
                    values.size() == 1 ? QueryIntent.Op.EQ : QueryIntent.Op.IN, List.copyOf(literals)));
            bindings.add(new RuleTrace.Binding(String.join(" and ", values), "FILTER",
                    subject.name() + "." + valueColumn));
            break;
        }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='VocabularyTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,UnboundNounTest,MongoRuleEngineTest,RuleTraceWordingTest'`
Expected: PASS. The 11 `VocabularyTest` cases pass, and the existing tests are unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): multi-word values, one-letter value typos, and a decline when a value sits in two columns

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Numbers in comparisons, `with` and limits

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `VocabularyTest.java`

**Interfaces:**
- Consumes: `NumberReader` and `Literal.number(BigDecimal)` (Task 1).
- Produces: `NUMBER_WORDS` and `numberFrom` are removed. E13, E15 and E20 read numbers through `NumberReader`.

- [ ] **Step 1: Write the failing tests**

Add to `VocabularyTest`:

```java
    @Test
    @DisplayName("spelled-out and separated numbers in a comparison")
    void comparisonNumbers() {
        assertThat(sql("orders with amount above two hundred and fifty").sql())
                .isEqualTo("SELECT * FROM orders WHERE amount > 250;");
        assertThat(sql("orders with amount over 1,000").sql())
                .isEqualTo("SELECT * FROM orders WHERE amount > 1000;");
        assertThat(sql("orders with amount below 4.5").sql())
                .isEqualTo("SELECT * FROM orders WHERE amount < 4.5;");
    }

    @Test
    @DisplayName("a comparison against words that aren't a number still declines")
    void unreadableNumber() {
        RuleBuildResult result = sql("orders with amount above fifty-ish");

        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("\"fifty-ish\"").contains("not a number");
    }

    @Test
    @DisplayName("a spelled-out limit")
    void wordLimit() {
        assertThat(sql("top twenty customers").sql()).isEqualTo("SELECT * FROM customers LIMIT 20;");
    }

    @Test
    @DisplayName("limitWordWithoutNumber: 'top' with no number builds with no limit, as today")
    void limitWordWithoutNumber() {
        assertThat(sql("top customers in pune").sql()).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("a with-filter reads a spelled-out number as a number")
    void withNumber() {
        assertThat(sql("orders with amount twenty five").sql())
                .isEqualTo("SELECT * FROM orders WHERE amount = 25;");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=VocabularyTest`
Expected:
- `comparisonNumbers` fails, since only one to ten are read;
- `wordLimit` fails, with no `LIMIT`;
- `withNumber` fails, with `'twenty'` as a quoted text value.

`unreadableNumber` and `limitWordWithoutNumber` may already pass.

- [ ] **Step 3: Implement**

In `QueryIntentParser.java`:

1. Change the patterns:

```java
    private static final Pattern COMPARISON = Pattern.compile(
            "\\b([a-z_]+)\\s+(?:is\\s+)?"
                    + "(more than|greater than|larger than|higher than"
                    + "|less than|fewer than|lower than|smaller than"
                    + "|above|over|below|under)\\s+(.+)$");

    private static final Pattern LIMIT = Pattern.compile("\\b(?:top|first|limit)\\s+(.+)$");
```

2. Delete `NUMBER_WORDS` and `numberFrom`. Add `import java.math.BigDecimal;`.

3. In the comparison branch, replace the lines from `Optional<Integer> literal = numberFrom(comparison.group(3));` down to the decline with:

```java
            String rest = comparison.group(3);
            Optional<NumberReader.Reading> reading = NumberReader.leading(rest);
            if (reading.isEmpty()) {
                return RuleBuildResult.declined(
                        "The question compares against \"" + rest.split("\\s+")[0]
                                + "\", which is not a number this engine can read.",
                        bindings);
            }
            QueryIntent.Literal number = QueryIntent.Literal.number(reading.get().value());
```

   In that branch:
   - replace `List.of(QueryIntent.Literal.number(literal.get()))` with `List.of(number)`, in both the `WHERE` and `HAVING` predicates;
   - replace `comparison.group(3)` with `reading.get().phrase()` in both binding phrases;
   - replace `literal.get()` with `number.text()` in both rendered strings.

4. In the `with` branch (where Task 6 introduced `withColumn`), replace

```java
            Optional<Integer> number = numberFrom(value);
            QueryIntent.Literal literalValue;
            if (number.isPresent()) {
                literalValue = new QueryIntent.Literal(value, true, number.get());
            } else {
```

with

```java
            Optional<NumberReader.Reading> number = NumberReader.leading(text.substring(with.start(2)));
            QueryIntent.Literal literalValue;
            if (number.isPresent()) {
                literalValue = QueryIntent.Literal.number(number.get().value());
                value = number.get().phrase();
            } else {
```

5. Replace the limit block with:

```java
        Integer limit = null;
        Matcher limitMatcher = LIMIT.matcher(text);
        if (limitMatcher.find()) {
            Optional<NumberReader.Reading> reading = NumberReader.leading(limitMatcher.group(1));
            if (reading.isPresent()) {
                BigDecimal value = reading.get().value();
                if (!reading.get().integral() || value.signum() <= 0
                        || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    return RuleBuildResult.declined(
                            "The question asks for the top \"" + reading.get().phrase() + "\", which is not a "
                                    + "whole number of rows this engine can limit to.",
                            bindings);
                }
                limit = value.intValueExact();
            }
        }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='VocabularyTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,MongoRuleEngineTest,RuleTraceWordingTest,SqlEmitterTest,MongoEmitterTest'`
Expected: PASS. The 16 `VocabularyTest` cases pass, and the existing tests are unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): comparisons, with-filters and limits read digits with separators, decimals and number words

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Confidence, the response, and aliases in the LLM prompt

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/service/impl/QueryGenerationServiceImpl.java`, `dto/QueryResponse.java`, `service/impl/OllamaService.java`, `service/impl/MongoSchemaContext.java`
- Test: add to `backend/src/test/java/com/querysense/backend/service/impl/QueryGenerationServiceImplTest.java`, `OllamaServiceTest.java`; update `MongoSchemaContextTest.java`

**Interfaces:**
- Consumes: `RuleBuildResult.interpretations()` and `interpreted()` (Task 6); `SchemaAliases.describe` and `SchemaSnapshot.aliasesFor` (Task 3).
- Produces:
  - `QueryResponse.interpretations` (`List<Interpretation>`, empty by default), with a 6-argument constructor kept;
  - confidence `0.85` when `interpreted()`;
  - the prompt naming declared aliases.

- [ ] **Step 1: Write the failing tests**

Add to `QueryGenerationServiceImplTest` (add the imports `com.querysense.backend.querybuilder.Interpretation` and `com.querysense.backend.querybuilder.SchemaLexicon`):

```java
    @Test
    @DisplayName("a query built through a synonym or typo reports 0.85 and carries its interpretations")
    void interpretedIsLower() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        List<Interpretation> read = List.of(new Interpretation("clients", "customers", SchemaLexicon.MatchKind.SYNONYM));
        when(rules.build(anyString(), anyString()))
                .thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace, read));

        QueryResponse response = service.generateQuery("clients", "MySQL");

        assertThat(response.getConfidence()).isEqualTo(0.85);
        assertThat(response.getInterpretations()).isEqualTo(read);
    }

    @Test
    @DisplayName("a schema-declared alias keeps 0.95")
    void aliasKeepsFullConfidence() {
        RuleTrace trace = new RuleTrace(RuleTrace.BUILT, "SELECT", List.of(), null, List.of(), null);
        when(rules.build(anyString(), anyString())).thenReturn(RuleBuildResult.built("SELECT * FROM customers;", trace,
                List.of(new Interpretation("buyers", "customers", SchemaLexicon.MatchKind.SCHEMA_ALIAS))));

        assertThat(service.generateQuery("buyers", "MySQL").getConfidence()).isEqualTo(0.95);
    }
```

Add to `OllamaServiceTest`, beside `mysqlPromptUnchanged`. It uses the file's `modelReplies` and `promptSent` helpers:

```java
    @Test
    @DisplayName("the MySQL schema block names a table's declared aliases")
    void mysqlPromptNamesAliases() {
        modelReplies("SELECT 1;");

        service.generateQuery("customers in pune", "MySQL");

        assertThat(promptSent()).contains("- customers(id, name, email, city, phone) — also called: buyers");
    }
```

Update `MongoSchemaContextTest.mentionedCollections`. The built-in now declares `customers` aka `buyers`, so its expected `customers` line becomes:

```
                            - customers (also called: buyers): _id (objectId), name (string), email (string), city (string), phone (string)
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='QueryGenerationServiceImplTest,OllamaServiceTest,MongoSchemaContextTest'`
Expected:
- the two generation tests fail to compile (`getInterpretations`);
- the prompt test lacks "also called";
- the Mongo context test fails on the `customers` line.

- [ ] **Step 3: Implement**

`QueryResponse.java`: add `import com.querysense.backend.querybuilder.Interpretation;` and `import java.util.List;`, the field `private List<Interpretation> interpretations = List.of();` after `schemaWarning`, and:

```java
    public QueryResponse(String generatedQuery, String targetDb, String handledBy, Double confidence,
                         RuleTrace ruleTrace, String schemaWarning) {
        this(generatedQuery, targetDb, handledBy, confidence, ruleTrace, schemaWarning, List.of());
    }
```

`QueryGenerationServiceImpl.java`:
- Add `private static final double CONFIDENCE_RULE_ENGINE_INTERPRETED = 0.85;` after `CONFIDENCE_RULE_ENGINE`.
- In the rule branch: `confidence = ruleResult.interpreted() ? CONFIDENCE_RULE_ENGINE_INTERPRETED : CONFIDENCE_RULE_ENGINE;`.
- The return becomes:

```java
        return new QueryResponse(generatedSql, databaseType, source, confidence, trace, schemaWarning,
                ruleResult.built() ? ruleResult.interpretations() : List.of());
```

  and add `import java.util.List;`.

`OllamaService.java`, in `buildSchemaContext`:
- Add `import com.querysense.backend.schema.SchemaAliases;` and `import java.util.stream.Collectors;`.
- Replace the table-line loop with:

```java
        SchemaSnapshot snapshot = activeSchema.active(Dialect.MYSQL);
        for (KnownSchema.Table table : tables) {
            SchemaAliases.TableAliases aliases = snapshot.aliasesFor(table.name());
            String columns = table.columns().stream()
                    .map(c -> SchemaAliases.describe(c, aliases.column(c)))
                    .collect(Collectors.joining(", "));
            schema.append("            - ").append(table.name()).append("(").append(columns).append(")");
            if (!aliases.table().isEmpty()) {
                schema.append(" — also called: ").append(String.join(", ", aliases.table()));
            }
            schema.append("\n");
        }
```

- Add `import com.querysense.backend.schema.SchemaSnapshot;` if it isn't already imported.

`MongoSchemaContext.java`, in `describe`:
- Change the field mapping to `.map(column -> SchemaAliases.describe(describeField(column, types.get(column), enums.get(column)), snapshot.aliasesFor(table.name()).column(column)))`.
- Change the table line to `out.append(INDENT).append("- ").append(SchemaAliases.describe(table.name(), snapshot.aliasesFor(table.name()).table())).append(": ").append(fields);`.
- Add `import com.querysense.backend.schema.SchemaAliases;`.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='QueryGenerationServiceImplTest,OllamaServiceTest,MongoSchemaContextTest,QueryHistoryRunContextTest,MongoGenerationValidationTest,SchemaScopedCacheTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(generate): interpreted rule-built queries report 0.85 and list their interpretations; prompts name declared aliases

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Aliases and interpretations on screen

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/dto/SchemaTableView.java`, `dto/SchemaUploadResponse.java`
- Modify: `frontend/src/api/queryBuilder.js`, `frontend/src/components/analysis/RuleTracePanel.jsx`, `RuleTracePanel.css`, `frontend/src/pages/AnalysisPage.jsx`, `frontend/src/components/schemaSource/SchemaTablePreview.jsx`, `SchemaTablePreview.css`, `frontend/src/mock/fixtures.js`
- Test: add to `backend/src/test/java/com/querysense/backend/controller/SchemaControllerTest.java`, `frontend/src/components/analysis/RuleTracePanel.test.jsx` and `frontend/src/components/schemaSource/SchemaTablePreview.test.jsx`

**Interfaces:**
- Consumes: `SchemaSnapshot.aliasesFor` (Task 3); the response `interpretations` (Task 9).
- Produces:
  - `SchemaTableView(…, List<String> aliases, Map<String, List<String>> columnAliases)`, with the 5-argument constructor kept;
  - `buildQuery` returns `interpretations` (`[]` when absent);
  - `RuleTracePanel({ trace, handledBy, interpretations })`.

- [ ] **Step 1: Write the failing tests**

Add to `SchemaControllerTest`, which calls the controller directly with a real `ActiveSchemaProvider`:

```java
    @Test
    @DisplayName("the active schema lists each table's declared aliases")
    void activeSchemaListsAliases() {
        SchemaUploadResponse response = controller.active(null);

        assertThat(response.tables()).filteredOn(t -> t.name().equals("customers")).singleElement()
                .satisfies(t -> assertThat(t.aliases()).containsExactly("buyers"));
    }
```

Add to `RuleTracePanel.test.jsx`:

```jsx
  it('shows how the engine read words the schema does not use', () => {
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        interpretations={[
          { phrase: 'clients', item: 'customers', kind: 'SYNONYM' },
          { phrase: 'emial', item: 'customers.email', kind: 'TYPO' },
          { phrase: 'buyers', item: 'customers', kind: 'SCHEMA_ALIAS' },
        ]}
        trace={{ outcome: 'BUILT', shape: 'SELECT', bindings: [], joinEdge: null, checksPassed: [], declineReason: null }}
      />
    );

    expect(screen.getByText('Read as')).toBeInTheDocument();
    expect(screen.getByText('built-in synonym')).toBeInTheDocument();
    expect(screen.getByText('one letter off')).toBeInTheDocument();
    expect(screen.getByText('alias declared by the schema')).toBeInTheDocument();
  });
```

Add to `SchemaTablePreview.test.jsx`:

```jsx
  it('lists declared aliases for a table and its columns', () => {
    render(<SchemaTablePreview schema={{
      ...MYSQL,
      tables: [{ ...MYSQL.tables[0], aliases: ['clients', 'buyers'], columnAliases: { name: ['label'] } }],
    }} />);

    expect(screen.getByText('also: clients, buyers')).toBeInTheDocument();
    expect(screen.getByText('also: label')).toBeInTheDocument();
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaControllerTest` and `cd frontend && npx vitest run src/components/analysis/RuleTracePanel.test.jsx src/components/schemaSource/SchemaTablePreview.test.jsx`
Expected: the new tests fail — `SchemaTableView` has no `aliases()`, and there is no "Read as" block and no "also:" text.

- [ ] **Step 3: Implement**

`SchemaTableView.java`:

```java
public record SchemaTableView(
        String name,
        List<String> columns,
        int rowCount,
        List<String> embeddedArrays,
        Map<String, String> fieldTypes,
        List<String> aliases,
        Map<String, List<String>> columnAliases) {

    public SchemaTableView(String name, List<String> columns, int rowCount, List<String> embeddedArrays,
                           Map<String, String> fieldTypes) {
        this(name, columns, rowCount, embeddedArrays, fieldTypes, List.of(), Map.of());
    }
}
```

`SchemaUploadResponse.from`: the table mapping becomes

```java
                .map(t -> new SchemaTableView(t.name(), t.columns(), snapshot.rowCountFor(t.name()),
                        t.embeddedArrays(), snapshot.fieldTypesFor(t.name()),
                        snapshot.aliasesFor(t.name()).table(), snapshot.aliasesFor(t.name()).columns()))
```

`frontend/src/api/queryBuilder.js`: add `interpretations: data.interpretations ?? [],` to the returned object.

`frontend/src/mock/fixtures.js`: in `mockBuildQuery`'s returned object, add `interpretations: [],`.

`RuleTracePanel.jsx`:
- Add after `LAYER_NAME`:

```jsx
const KIND_LABEL = {
  SCHEMA_ALIAS: 'alias declared by the schema',
  SYNONYM: 'built-in synonym',
  TYPO: 'one letter off',
};
```

- Change the signature to `export function RuleTracePanel({ trace, handledBy, interpretations = [] })`.
- In the built branch, before `{trace.joinEdge && (`, add:

```jsx
          {interpretations.length > 0 && (
            <div className="rule-trace__read-as">
              <span className="rule-trace__read-as-label">Read as</span>
              <ul className="rule-trace__read-as-list">
                {interpretations.map((reading, i) => (
                  <li key={i}>
                    &ldquo;{reading.phrase}&rdquo;
                    <span aria-hidden="true"> → </span>
                    <code>{reading.item}</code>{' '}
                    <span className="rule-trace__kind">{KIND_LABEL[reading.kind] || reading.kind}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
```

`RuleTracePanel.css`, appended:

```css
.rule-trace__read-as {
  margin-bottom: var(--space-3);
  font-size: var(--text-sm);
}

.rule-trace__read-as-label {
  display: block;
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ink-secondary);
  margin-bottom: var(--space-1);
}

.rule-trace__read-as-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
```

`AnalysisPage.jsx`: `<RuleTracePanel trace={build.ruleTrace} handledBy={build.handledBy} interpretations={build.interpretations} />`.

`SchemaTablePreview.jsx`:
- After the `schema-preview__table-head` `div`, add:

```jsx
            {table.aliases?.length > 0 && (
              <div className="schema-preview__aliases">{`also: ${table.aliases.join(', ')}`}</div>
            )}
```

- In the column `span`, after the column content (both the MongoDB fragment and the plain `column`), add:

```jsx
                  {table.columnAliases?.[column]?.length > 0 && (
                    <span className="schema-preview__alias">{`also: ${table.columnAliases[column].join(', ')}`}</span>
                  )}
```

`SchemaTablePreview.css`, appended:

```css
.schema-preview__aliases,
.schema-preview__alias {
  font-size: var(--text-xs);
  color: var(--ink-muted);
}

.schema-preview__alias {
  margin-left: var(--space-1);
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='SchemaControllerTest,*SchemaUpload*Test'` and `cd frontend && npx vitest run && npm run lint`
Expected: PASS. Every frontend test passes and lint is clean.

- [ ] **Step 5: Commit**

```bash
git add backend/src frontend/src
git commit -m "feat(frontend): the rule trace shows how words were read, and schema cards list declared aliases

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Parity, full checks and the record

**Files:**
- Modify: `backend/src/test/java/com/querysense/backend/querybuilder/DialectParityTest.java`, `RULES.md`, `PROJECT.md`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write the failing parity cases**

In `DialectParityTest`:
- add `"New Delhi"` to the `customers`/`city` distinct values in `metadata(...)`, giving `List.of("Mumbai", "Pune", "Delhi", "New Delhi")`;
- add to the `@ValueSource` strings:

```java
            "clients in pune",
            "custmers in pune",
            "buyers in mumbai",
            "customers in new delhi",
            "customers in mumbia",
            "top twenty products",
            "products with price above two hundred and fifty",
            "show emial of customers",
```

Then add a test that both dialects read the question the same way:

```java
    @ParameterizedTest(name = "{0}")
    @DisplayName("both dialects record the same interpretations")
    @ValueSource(strings = {"clients in pune", "custmers in pune", "buyers in mumbai", "customers in mumbia",
            "show emial of customers"})
    void sameInterpretations(String question) {
        RuleBuildResult sql = parser.parse(question, Dialect.MYSQL);
        RuleBuildResult mongo = parser.parse(question, Dialect.MONGODB);

        assertThat(sql.built()).isTrue();
        assertThat(mongo.built()).isTrue();
        assertThat(mongo.interpretations()).isEqualTo(sql.interpretations());
    }
```

The interpretation `item` of a column names the dialect's own column. `customers.email` exists in both demos, so the two lists compare equal.

- [ ] **Step 2: Run them**

Run: `cd backend && ./mvnw -q test -Dtest=DialectParityTest`
Expected: PASS. If a question builds in one dialect and not the other, that is a defect in Tasks 5–8; fix it there, not in this test.

- [ ] **Step 3: Full checks**

Run: `cd backend && ./mvnw test`
Expected: all green, the Mongo integration tests included when `mongod` is running.

Run: `cd frontend && npm test && npm run lint && npm run build`
Expected: all green, lint clean, the build succeeds.

- [ ] **Step 4: `RULES.md`**

1. **E4:** add a paragraph:
   - Tables are also reached by declared aliases, the built-in synonym list, irregular and `-es`/`-ies` plurals, and — only when no table matched otherwise — a one-edit typo of five or more letters.
   - A word reaching two tables declines with `"<word>" could mean A or B, so this engine won't pick one.`
2. **E5:** add the same kinds for columns, in precedence order exact → inflection → alias → synonym → typo, with the uniqueness rule.
3. **E13, E15, E20:** numbers are read by one reader:
   - digits with separators;
   - decimals;
   - words up to the millions;
   - `a dozen`.
   A limit must be a whole number.
4. **E14:**
   - runs of up to four words, longest first;
   - a single-word value one edit from exactly one stored value is corrected, and the stored spelling is written;
   - a value stored in two columns declines;
   - the run stops at punctuation.
5. **New subsection "Matching words to the schema"**, after E5:
   - the five match kinds;
   - that a declared alias counts as exact (0.95) and a synonym or typo lowers confidence to 0.85;
   - that every non-exact reading is listed under "Read as" in the trace;
   - that values never use synonyms.
6. **"The 19 ways the rule engine declines":** it becomes 22. Add three rows:
   - "A word reaches two schema items (synonym or typo)";
   - "A value is stored in two columns";
   - "A limit isn't a whole number".
   Update the "How many rules" prose to match.

- [ ] **Step 5: `PROJECT.md`**

- Add `### Session of <execution date> — rule engine vocabulary (sub-project 1 of 4)` before `### Not verified`, with:
  - the plan path;
  - a table of Tasks 1–10 with commits;
  - the six departures above;
  - the backend and frontend test counts;
  - the note that no screen was opened in a browser and no question was sent to Ollama.
- Update this plan's "planned" entry in §5's rule-engine roadmap (added when the plan was written) to say sub-project 1 is built and sub-project 2 (question shapes) is next.
- **Live checklist:**
  1. Workbench, MySQL: `clients in mumbai` builds at 85% with a "Read as: "clients" → customers (built-in synonym)" line on Analysis.
  2. `custmers in pune` builds at 85%, and `buyers in pune` at 95% (a declared alias).
  3. `customers in new delhi` builds a two-word value if the data has one. `top twenty customers` has `LIMIT 20`.
  4. The same three questions with the Workbench on MongoDB build the matching `find`.
  5. Upload a `.sql` with `COMMENT 'aka: town'` on a column. The Schema source card shows "also: town", and a question using "town" binds it at 95%.

- [ ] **Step 6: Commit**

```bash
git add backend/src RULES.md PROJECT.md
git commit -m "docs: record rule engine vocabulary — aliases, synonyms, typos, multi-word values and numbers

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
