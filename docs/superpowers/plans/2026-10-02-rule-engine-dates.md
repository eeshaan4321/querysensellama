# Rule Engine Dates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Date expressions become real date ranges on the right date column, in MySQL and MongoDB. This replaces E3's blanket decline. What the reader can't read still declines.

**Architecture:**
- `DateReader`, a pure function, finds the first date expression and returns a half-open day range with its text span.
- `RulesClock` supplies today in a configured zone, pinnable for tests and demos.
- The parser picks the date column (named, by stem, or the only one) across the chain, writes `>=`/`<` predicates with a new date literal, and consumes the span so no other rule rereads it.
- `MongoEmitter` writes dates as `ISODate("…")` and merges operator conditions on the same field.
- Recency adjectives join sub-project 2's adjective list as `date` entries.

**Tech Stack:** Java 21 (`java.time`), Spring Boot 3.5, MongoDB Java driver BSON JSON writer, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-02-rule-engine-dates-design.md`

**Precondition:** sub-projects 1–3 are complete. This plan quotes the parser as they leave it:
- `consumed`, `conditionSpans`, `insideAny`, `chainTables`, `chainColumn`, `joinedFilter`;
- `ranking(...)`, `Adjectives`, `DeclineException`.

## Global Constraints

- Every question that builds today builds byte-for-byte the same.
- Questions that declined only because of E3 now build. The tests asserting those declines are updated, each with a `Ruling:` line.
- Confidence stays 0.95 for dates and recency. They are read by fixed rules, not interpreted.
- Weeks start on Monday.
- Ranges are half-open: `from` inclusive, `until` exclusive.
- `querysense.rules.time-zone` (default `Asia/Kolkata`) and `querysense.rules.today` (optional ISO date) are the only new properties.
- Source comments: a one-line class summary at most.
- Backend tests: `cd backend && ./mvnw -q test -Dtest=<Class>`.
- Do not start the app. Commits end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **"customers from pune"** must stay a value filter, not a failed date. Pinned in Task 1 (`fromAValueIsNotADate`) and Task 3 (`valueFromStillWorks`).
2. **"last month" in January** must reach back to December of the previous year. Pinned in Task 1 (`yearBoundary`).
3. **An impossible date** (`on february 30 2025`) must read as nothing, not throw. Pinned in Task 1 (`impossibleDate`).
4. **"in january" must not also become an E14 value filter.** Pinned in Task 3 (`dateSpanIsConsumed`).
5. **MongoDB `>=` and `<` on one field** must be one condition document, not two clashing keys. Pinned in Task 2 (`mergedRange`).

## Departures from the spec, on purpose

1. `RulesClock` is a small component wrapping a `java.time.Clock`, rather than a bare `Clock` bean. That avoids any clash with another `Clock` bean, and gives tests a `RulesClock.fixed(...)` factory.
2. `MongoEmitter` merges operator conditions on one field (`{$gte: …, $lt: …}`), which the spec's example shows. Repeated keys whose conditions can't merge keep today's `$and` form.

---

### Task 0: Reconcile

- [ ] **Step 1:** Read `QueryIntentParser.java`, `QueryIntent.java`, `MongoEmitter.java`, `SchemaBinder.java`, `Adjectives.java`, `adjectives.json` and `TestParsers.java` as sub-projects 1–3 left them. Confirm the names quoted here; record a `Ruling:` for any difference.
- [ ] **Step 2:** `cd backend && ./mvnw -q test` is green.

---

### Task 1: `DateReader`

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/DateReader.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DateReaderTest.java`

**Interfaces:**
- Produces:
  - `record DateReader.DateRange(LocalDate from, LocalDate until, int start, int end, String phrase)`
  - `static Optional<DateRange> DateReader.read(String normalizedText, LocalDate today)`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateReaderTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);

    private static String range(String text) {
        return range(text, FRIDAY);
    }

    private static String range(String text, LocalDate today) {
        return DateReader.read(text, today)
                .map(r -> (r.from() == null ? "-" : r.from().toString()) + ".." + (r.until() == null ? "-" : r.until().toString()))
                .orElse("none");
    }

    @Test
    @DisplayName("relative periods")
    void relative() {
        assertThat(range("orders today")).isEqualTo("2026-10-02..2026-10-03");
        assertThat(range("orders yesterday")).isEqualTo("2026-10-01..2026-10-02");
        assertThat(range("orders this week")).isEqualTo("2026-09-28..2026-10-05");
        assertThat(range("orders last week")).isEqualTo("2026-09-21..2026-09-28");
        assertThat(range("orders previous week")).isEqualTo("2026-09-21..2026-09-28");
        assertThat(range("orders next week")).isEqualTo("2026-10-05..2026-10-12");
        assertThat(range("orders this month")).isEqualTo("2026-10-01..2026-11-01");
        assertThat(range("orders from last month")).isEqualTo("2026-09-01..2026-10-01");
        assertThat(range("orders next month")).isEqualTo("2026-11-01..2026-12-01");
        assertThat(range("orders this quarter")).isEqualTo("2026-10-01..2027-01-01");
        assertThat(range("orders last quarter")).isEqualTo("2026-07-01..2026-10-01");
        assertThat(range("orders this year")).isEqualTo("2026-01-01..2027-01-01");
        assertThat(range("orders last year")).isEqualTo("2025-01-01..2026-01-01");
        assertThat(range("orders in the last 7 days")).isEqualTo("2026-09-25..2026-10-03");
        assertThat(range("orders in the past three weeks")).isEqualTo("2026-09-11..2026-10-03");
        assertThat(range("orders in the last 2 months")).isEqualTo("2026-08-02..2026-10-03");
    }

    @Test
    @DisplayName("named periods; a month with no year is its latest start")
    void named() {
        assertThat(range("orders in 2025")).isEqualTo("2025-01-01..2026-01-01");
        assertThat(range("orders in march 2025")).isEqualTo("2025-03-01..2025-04-01");
        assertThat(range("orders in mar 2025")).isEqualTo("2025-03-01..2025-04-01");
        assertThat(range("orders in q1 2025")).isEqualTo("2025-01-01..2025-04-01");
        assertThat(range("orders in the first quarter of 2025")).isEqualTo("2025-01-01..2025-04-01");
        assertThat(range("orders in january")).isEqualTo("2026-01-01..2026-02-01");
        assertThat(range("orders in november")).isEqualTo("2025-11-01..2025-12-01");
    }

    @Test
    @DisplayName("single dates in four spellings")
    void singleDates() {
        assertThat(range("orders on 1 march 2025")).isEqualTo("2025-03-01..2025-03-02");
        assertThat(range("orders on march 1 2025")).isEqualTo("2025-03-01..2025-03-02");
        assertThat(range("orders on march 1, 2025")).isEqualTo("2025-03-01..2025-03-02");
        assertThat(range("orders on 2025-03-01")).isEqualTo("2025-03-01..2025-03-02");
        assertThat(range("orders on 1st march 2025")).isEqualTo("2025-03-01..2025-03-02");
    }

    @Test
    @DisplayName("open and closed ranges")
    void ranges() {
        assertThat(range("orders since january")).isEqualTo("2026-01-01..-");
        assertThat(range("orders before 2024")).isEqualTo("-..2024-01-01");
        assertThat(range("orders after 1 march 2025")).isEqualTo("2025-03-02..-");
        assertThat(range("orders until march 2025")).isEqualTo("-..2025-04-01");
        assertThat(range("orders up to march 2025")).isEqualTo("-..2025-04-01");
        assertThat(range("orders between january and march")).isEqualTo("2026-01-01..2026-04-01");
        assertThat(range("orders from january 2025 to march 2025")).isEqualTo("2025-01-01..2025-04-01");
        assertThat(range("orders from march 2025 onwards")).isEqualTo("2025-03-01..-");
    }

    @Test
    @DisplayName("yearBoundary: last month in January is last December")
    void yearBoundary() {
        assertThat(range("orders last month", LocalDate.of(2027, 1, 15))).isEqualTo("2026-12-01..2027-01-01");
    }

    @Test
    @DisplayName("impossibleDate: an impossible date reads as nothing; a leap day reads")
    void impossibleDate() {
        assertThat(range("orders on february 30 2025")).isEqualTo("none");
        assertThat(range("orders on february 29 2024")).isEqualTo("2024-02-29..2024-03-01");
    }

    @Test
    @DisplayName("fromAValueIsNotADate: words that aren't dates read as nothing")
    void fromAValueIsNotADate() {
        assertThat(range("customers from pune")).isEqualTo("none");
        assertThat(range("customers in mumbai")).isEqualTo("none");
        assertThat(range("orders recently")).isEqualTo("none");
        assertThat(range("top 5 products")).isEqualTo("none");
    }

    @Test
    @DisplayName("the span covers the whole expression, its preposition included")
    void span() {
        DateReader.DateRange r = DateReader.read("orders from last month", FRIDAY).orElseThrow();
        assertThat("orders from last month".substring(r.start(), r.end())).isEqualTo("from last month");
        assertThat(r.phrase()).isEqualTo("from last month");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=DateReaderTest`
Expected: compilation failure — `DateReader` doesn't exist.

- [ ] **Step 3: Implement**

```java
package com.querysense.backend.querybuilder;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the first date expression in a question as a half-open range of days. */
public final class DateReader {

    public record DateRange(LocalDate from, LocalDate until, int start, int end, String phrase) {}

    private record Word(String text, int start, int end) {}

    private record Period(LocalDate start, LocalDate end, int next) {}

    private static final Pattern WORD = Pattern.compile("[a-z0-9-]+");
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    private static final Pattern DAY = Pattern.compile("(\\d{1,2})(?:st|nd|rd|th)?");
    private static final Pattern QUARTER = Pattern.compile("q([1-4])");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("jan", 1), Map.entry("february", 2), Map.entry("feb", 2),
            Map.entry("march", 3), Map.entry("mar", 3), Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("june", 6), Map.entry("jun", 6), Map.entry("july", 7), Map.entry("jul", 7),
            Map.entry("august", 8), Map.entry("aug", 8), Map.entry("september", 9), Map.entry("sep", 9),
            Map.entry("sept", 9), Map.entry("october", 10), Map.entry("oct", 10), Map.entry("november", 11),
            Map.entry("nov", 11), Map.entry("december", 12), Map.entry("dec", 12));

    private static final Map<String, Integer> ORDINALS = Map.of("first", 1, "second", 2, "third", 3, "fourth", 4);
    private static final Set<String> PERIOD_UNITS = Set.of("week", "month", "quarter", "year");
    private static final Map<String, ChronoUnit> COUNT_UNITS = Map.of(
            "day", ChronoUnit.DAYS, "days", ChronoUnit.DAYS, "week", ChronoUnit.WEEKS, "weeks", ChronoUnit.WEEKS,
            "month", ChronoUnit.MONTHS, "months", ChronoUnit.MONTHS, "year", ChronoUnit.YEARS, "years", ChronoUnit.YEARS);

    private DateReader() {}

    public static Optional<DateRange> read(String text, LocalDate today) {
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new Word(m.group(), m.start(), m.end()));
        }
        for (int i = 0; i < words.size(); i++) {
            Optional<DateRange> found = at(text, words, i, today);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<DateRange> at(String text, List<Word> w, int i, LocalDate today) {
        String word = w.get(i).text();
        switch (word) {
            case "since" -> {
                return period(w, i + 1, today, true).map(p -> range(text, w, i, p.next(), p.start(), null));
            }
            case "before" -> {
                return period(w, i + 1, today, true).map(p -> range(text, w, i, p.next(), null, p.start()));
            }
            case "after" -> {
                return period(w, i + 1, today, true).map(p -> range(text, w, i, p.next(), p.end(), null));
            }
            case "until", "till" -> {
                return period(w, i + 1, today, true).map(p -> range(text, w, i, p.next(), null, p.end()));
            }
            case "up" -> {
                if (is(w, i + 1, "to")) {
                    return period(w, i + 2, today, true).map(p -> range(text, w, i, p.next(), null, p.end()));
                }
                return Optional.empty();
            }
            case "between" -> {
                Optional<Period> x = period(w, i + 1, today, true);
                if (x.isPresent() && is(w, x.get().next(), "and")) {
                    return period(w, x.get().next() + 1, today, true)
                            .map(y -> range(text, w, i, y.next(), x.get().start(), y.end()));
                }
                return Optional.empty();
            }
            case "from" -> {
                Optional<Period> x = period(w, i + 1, today, true);
                if (x.isEmpty()) {
                    return Optional.empty();
                }
                int next = x.get().next();
                if (is(w, next, "to") || is(w, next, "until") || is(w, next, "till")) {
                    Optional<Period> y = period(w, next + 1, today, true);
                    if (y.isPresent()) {
                        return Optional.of(range(text, w, i, y.get().next(), x.get().start(), y.get().end()));
                    }
                }
                if (is(w, next, "onwards") || is(w, next, "onward")) {
                    return Optional.of(range(text, w, i, next + 1, x.get().start(), null));
                }
                return Optional.of(range(text, w, i, next, x.get().start(), x.get().end()));
            }
            case "in", "during", "for", "on" -> {
                int from = is(w, i + 1, "the") && (is(w, i + 2, "last") || is(w, i + 2, "past")) ? i + 2 : i + 1;
                return period(w, from, today, true).map(p -> range(text, w, i, p.next(), p.start(), p.end()));
            }
            default -> {
                return period(w, i, today, false).map(p -> range(text, w, i, p.next(), p.start(), p.end()));
            }
        }
    }

    private static DateRange range(String text, List<Word> w, int first, int next, LocalDate from, LocalDate until) {
        int start = w.get(first).start();
        int end = w.get(next - 1).end();
        return new DateRange(from, until, start, end, text.substring(start, end));
    }

    private static boolean is(List<Word> w, int i, String text) {
        return i < w.size() && w.get(i).text().equals(text);
    }

    private static String at(List<Word> w, int i) {
        return i < w.size() ? w.get(i).text() : "";
    }

    private static Optional<Period> period(List<Word> w, int i, LocalDate today, boolean named) {
        String word = at(w, i);
        if ("today".equals(word)) {
            return Optional.of(new Period(today, today.plusDays(1), i + 1));
        }
        if ("yesterday".equals(word)) {
            return Optional.of(new Period(today.minusDays(1), today, i + 1));
        }
        if (Set.of("this", "last", "next", "past", "previous").contains(word) && PERIOD_UNITS.contains(at(w, i + 1))) {
            int shift = switch (word) {
                case "this" -> 0;
                case "next" -> 1;
                default -> -1;
            };
            LocalDate[] bounds = calendar(at(w, i + 1), today, shift);
            return Optional.of(new Period(bounds[0], bounds[1], i + 2));
        }
        if (Set.of("last", "past", "previous").contains(word)) {
            StringBuilder rest = new StringBuilder();
            for (int k = i + 1; k < w.size(); k++) {
                rest.append(w.get(k).text()).append(' ');
            }
            Optional<NumberReader.Reading> n = NumberReader.leading(rest.toString());
            if (n.isPresent() && n.get().integral()) {
                int used = n.get().phrase().split("\\s+").length;
                ChronoUnit unit = COUNT_UNITS.get(at(w, i + 1 + used));
                if (unit != null) {
                    long count = n.get().value().longValueExact();
                    return Optional.of(new Period(today.minus(count, unit), today.plusDays(1), i + 2 + used));
                }
            }
        }
        return named ? namedPeriod(w, i, today) : Optional.empty();
    }

    private static LocalDate[] calendar(String unit, LocalDate today, int shift) {
        return switch (unit) {
            case "week" -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(shift);
                yield new LocalDate[]{monday, monday.plusWeeks(1)};
            }
            case "month" -> {
                LocalDate first = today.withDayOfMonth(1).plusMonths(shift);
                yield new LocalDate[]{first, first.plusMonths(1)};
            }
            case "quarter" -> {
                LocalDate first = LocalDate.of(today.getYear(), ((today.getMonthValue() - 1) / 3) * 3 + 1, 1)
                        .plusMonths(3L * shift);
                yield new LocalDate[]{first, first.plusMonths(3)};
            }
            default -> {
                LocalDate first = LocalDate.of(today.getYear() + shift, 1, 1);
                yield new LocalDate[]{first, first.plusYears(1)};
            }
        };
    }

    private static Optional<Period> namedPeriod(List<Word> w, int i, LocalDate today) {
        try {
            String word = at(w, i);
            if (ISO.matcher(word).matches()) {
                LocalDate day = LocalDate.parse(word);
                return Optional.of(new Period(day, day.plusDays(1), i + 1));
            }
            Matcher quarter = QUARTER.matcher(word);
            if (quarter.matches() && YEAR.matcher(at(w, i + 1)).matches()) {
                return Optional.of(quarter(Integer.parseInt(at(w, i + 1)), Integer.parseInt(quarter.group(1)), i + 2));
            }
            if ("the".equals(word) && ORDINALS.containsKey(at(w, i + 1)) && "quarter".equals(at(w, i + 2))
                    && "of".equals(at(w, i + 3)) && YEAR.matcher(at(w, i + 4)).matches()) {
                return Optional.of(quarter(Integer.parseInt(at(w, i + 4)), ORDINALS.get(at(w, i + 1)), i + 5));
            }
            if (YEAR.matcher(word).matches()) {
                int year = Integer.parseInt(word);
                return Optional.of(new Period(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1), i + 1));
            }
            Matcher day = DAY.matcher(word);
            if (day.matches() && MONTHS.containsKey(at(w, i + 1))) {
                return Optional.of(dayOf(Integer.parseInt(day.group(1)), MONTHS.get(at(w, i + 1)), w, i + 2, today));
            }
            if (MONTHS.containsKey(word)) {
                int month = MONTHS.get(word);
                Matcher dayAfter = DAY.matcher(at(w, i + 1));
                if (dayAfter.matches() && !YEAR.matcher(at(w, i + 1)).matches()) {
                    return Optional.of(dayOf(Integer.parseInt(dayAfter.group(1)), month, w, i + 2, today));
                }
                if (YEAR.matcher(at(w, i + 1)).matches()) {
                    LocalDate first = LocalDate.of(Integer.parseInt(at(w, i + 1)), month, 1);
                    return Optional.of(new Period(first, first.plusMonths(1), i + 2));
                }
                int year = month <= today.getMonthValue() ? today.getYear() : today.getYear() - 1;
                LocalDate first = LocalDate.of(year, month, 1);
                return Optional.of(new Period(first, first.plusMonths(1), i + 1));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Period quarter(int year, int q, int next) {
        LocalDate first = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        return new Period(first, first.plusMonths(3), next);
    }

    private static Period dayOf(int dayOfMonth, int month, List<Word> w, int yearIndex, LocalDate today) {
        if (YEAR.matcher(at(w, yearIndex)).matches()) {
            LocalDate day = LocalDate.of(Integer.parseInt(at(w, yearIndex)), month, dayOfMonth);
            return new Period(day, day.plusDays(1), yearIndex + 1);
        }
        LocalDate thisYear = LocalDate.of(today.getYear(), month, dayOfMonth);
        LocalDate day = thisYear.isAfter(today) ? thisYear.minusYears(1) : thisYear;
        return new Period(day, day.plusDays(1), yearIndex);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=DateReaderTest`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): DateReader turns relative, named, single and ranged dates into half-open day ranges

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `RulesClock`, demo field types, the date literal, and `ISODate` in MongoDB

**Files:**
- Create: `backend/src/main/java/com/querysense/backend/querybuilder/RulesClock.java`
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntent.java`, `MongoEmitter.java`, `SchemaBinder.java`, `backend/src/main/java/com/querysense/backend/schema/KnownSchema.java`, `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DateEmissionTest.java` (new)

**Interfaces:**
- Produces:
  - `RulesClock(String zone, String today)` (Spring), with `RulesClock.fixed(LocalDate, ZoneId)`, `LocalDate today()` and `ZoneId zone()`
  - `QueryIntent.Literal.date(LocalDate day, ZoneId zone)`
  - `KnownSchema.FIELD_TYPES`
  - `SchemaBinder.fieldType(KnownSchema.Table table, String column)` and `static boolean SchemaBinder.isDateType(String type)`

- [ ] **Step 1: Write the failing test**

```java
package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DateEmissionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static QueryIntent lastMonth() {
        QueryIntent.ColumnRef date = new QueryIntent.ColumnRef(null, "order_date");
        return new QueryIntent(QueryIntent.Verb.SELECT, "orders", null,
                List.of(new QueryIntent.SelectItem(QueryIntent.Star.INSTANCE)),
                List.of(new QueryIntent.Predicate(date, QueryIntent.Op.GTE, List.of(QueryIntent.Literal.date(LocalDate.of(2026, 9, 1), IST))),
                        new QueryIntent.Predicate(date, QueryIntent.Op.LT, List.of(QueryIntent.Literal.date(LocalDate.of(2026, 10, 1), IST)))),
                List.of(), List.of(), null);
    }

    @Test
    @DisplayName("SQL writes ISO dates")
    void sql() {
        assertThat(new SqlEmitter().emit(lastMonth()))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
    }

    @Test
    @DisplayName("mergedRange: MongoDB writes ISODate at local midnight, both bounds in one condition")
    void mergedRange() {
        assertThat(new MongoEmitter(new ActiveSchemaProvider()).emit(lastMonth())).isEqualTo(
                "db.orders.find({\"order_date\": {\"$gte\": ISODate(\"2026-08-31T18:30:00Z\"), "
                        + "\"$lt\": ISODate(\"2026-09-30T18:30:00Z\")}})");
    }

    @Test
    @DisplayName("a pinned clock reads today in its zone; the demo knows its date columns")
    void clockAndTypes() {
        RulesClock clock = RulesClock.fixed(LocalDate.of(2026, 10, 2), IST);
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(new RulesClock("Asia/Kolkata", "2026-10-02").today()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(KnownSchema.FIELD_TYPES.get("orders").get("order_date")).isEqualTo("DATE");
        assertThat(SchemaBinder.isDateType("datetime")).isTrue();
        assertThat(SchemaBinder.isDateType("date")).isTrue();
        assertThat(SchemaBinder.isDateType("VARCHAR")).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=DateEmissionTest`
Expected: compilation failure — `Literal.date`, `RulesClock`, `FIELD_TYPES` and `isDateType` don't exist.

- [ ] **Step 3: Implement**

`RulesClock.java`:

```java
package com.querysense.backend.querybuilder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Today's date for the rule engine, in the configured zone, optionally pinned for tests and demos. */
@Component
public class RulesClock {

    private final Clock clock;

    @Autowired
    public RulesClock(@Value("${querysense.rules.time-zone:Asia/Kolkata}") String zone,
                      @Value("${querysense.rules.today:}") String today) {
        ZoneId id = ZoneId.of(zone);
        this.clock = today == null || today.isBlank()
                ? Clock.system(id)
                : Clock.fixed(LocalDate.parse(today).atStartOfDay(id).toInstant(), id);
    }

    private RulesClock(Clock clock) {
        this.clock = clock;
    }

    public static RulesClock fixed(LocalDate today, ZoneId zone) {
        return new RulesClock(Clock.fixed(today.atStartOfDay(zone).toInstant(), zone));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }
}
```

`application.properties` — append:

```
# Today for the rule engine's date reading, in this zone. Set querysense.rules.today=2026-10-02 to pin it.
querysense.rules.time-zone=Asia/Kolkata
querysense.rules.today=
```

`QueryIntent.java` — in `Literal`, add (with the imports `java.time.LocalDate` and `java.time.ZoneId`):

```java
        public static Literal date(LocalDate day, ZoneId zone) {
            return new Literal(day.toString(), false, java.util.Date.from(day.atStartOfDay(zone).toInstant()));
        }
```

`MongoEmitter.java`:
1. Settings:

```java
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .dateTimeConverter((value, writer) ->
                    writer.writeRaw("ISODate(\"" + java.time.Instant.ofEpochMilli(value) + "\")"))
            .build();
```

2. In `matchDocument`, merge operator documents on a repeated key:

```java
            if (out.containsKey(key)) {
                Object existing = out.get(key);
                if (existing instanceof Document a && condition instanceof Document b
                        && a.keySet().stream().allMatch(k -> k.startsWith("$"))
                        && b.keySet().stream().allMatch(k -> k.startsWith("$"))
                        && a.keySet().stream().noneMatch(b::containsKey)) {
                    a.putAll(b);
                } else {
                    repeated.add(new Document(key, condition));
                }
            } else {
                out.append(key, condition);
            }
```

`KnownSchema.java` — add (from `mysql/schema.sql`), and pass `FIELD_TYPES` as `builtIn()`'s `fieldTypes` argument:

```java
    public static final Map<String, Map<String, String>> FIELD_TYPES = Map.of(
            "categories", Map.of("id", "INT", "name", "VARCHAR"),
            "customers", Map.of("id", "INT", "name", "VARCHAR", "email", "VARCHAR", "city", "VARCHAR", "phone", "VARCHAR"),
            "departments", Map.of("id", "INT", "name", "VARCHAR"),
            "employees", Map.of("id", "INT", "name", "VARCHAR", "email", "VARCHAR", "department_id", "INT",
                    "manager_id", "INT", "hire_date", "DATE"),
            "order_items", Map.of("id", "INT", "order_id", "INT", "product_id", "INT", "quantity", "INT",
                    "unit_price", "DECIMAL"),
            "orders", Map.of("id", "INT", "customer_id", "INT", "employee_id", "INT", "order_date", "DATE",
                    "status", "VARCHAR"),
            "payments", Map.of("id", "INT", "order_id", "INT", "amount", "DECIMAL", "payment_date", "DATE",
                    "method", "VARCHAR"),
            "products", Map.of("id", "INT", "name", "VARCHAR", "price", "DECIMAL", "category_id", "INT",
                    "supplier_id", "INT"),
            "suppliers", Map.of("id", "INT", "name", "VARCHAR", "contact_email", "VARCHAR"));
```

`SchemaBinder.java` — add:

```java
    public String fieldType(KnownSchema.Table table, String column) {
        for (Dialect dialect : Dialect.values()) {
            SchemaSnapshot snapshot = activeSchema.active(dialect);
            if (snapshot.tables().contains(table)) {
                return snapshot.fieldTypesFor(table.name()).get(column);
            }
        }
        return null;
    }

    public static boolean isDateType(String type) {
        return type != null && Set.of("DATE", "DATETIME", "TIMESTAMP").contains(type.toUpperCase(Locale.ROOT));
    }
```

`isDateType("date")` is true through `toUpperCase`. Add `import java.util.Set;` if it is missing.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='DateEmissionTest,MongoEmitterTest,ShapeEmitterTest,ChainEmitterTest,SchemaAliasesTest,BackendApplicationTests'`
Expected: PASS. `BackendApplicationTests` proves the new component wires.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): a pinnable rules clock, demo column types, date literals and ISODate in MongoDB

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: The parser reads dates (E3 replaced)

**Files:**
- Modify: `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`, `backend/src/test/java/com/querysense/backend/querybuilder/TestParsers.java`
- Test: `backend/src/test/java/com/querysense/backend/querybuilder/DatesTest.java` (new); update `DeclineBoundaryTest` and any test asserting E3's message

**Interfaces:**
- Consumes: `DateReader` (Task 1); `RulesClock`, `Literal.date`, `fieldType` and `isDateType` (Task 2).
- Produces:
  - a `QueryIntentParser` constructor that takes `RulesClock` as its last parameter
  - `TestParsers.parser(...)`, passing `RulesClock.fixed(LocalDate.of(2026, 10, 2), ZoneId.of("Asia/Kolkata"))`

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dates on the built-in demos, today pinned to Friday 2026-10-02 in Asia/Kolkata. */
class DatesTest {

    private ActiveSchemaProvider schemas;
    private QueryIntentParser parser;

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune"));
        return m;
    }

    @BeforeEach
    void setUp() {
        schemas = new ActiveSchemaProvider();
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    String sql(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        return result.sql();
    }

    String declineReason(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).isFalse();
        return result.trace().declineReason();
    }

    @Test
    @DisplayName("a relative period on the only date column")
    void relative() {
        assertThat(sql("orders from last month"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
        assertThat(sql("orders last week"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-21' AND order_date < '2026-09-28';");
    }

    @Test
    @DisplayName("an open range, and a column named by its stem")
    void openAndStem() {
        assertThat(sql("employees hired since 2020")).isEqualTo("SELECT * FROM employees WHERE hire_date >= '2020-01-01';");
    }

    @Test
    @DisplayName("dateSpanIsConsumed: 'in january' is a date, never also a value filter")
    void dateSpanIsConsumed() {
        assertThat(sql("orders in january"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-01-01' AND order_date < '2026-02-01';");
    }

    @Test
    @DisplayName("valueFromStillWorks: 'customers from pune' is a value filter as today")
    void valueFromStillWorks() {
        assertThat(sql("customers from pune")).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("a date on a joined table filters that table, each subject once")
    void joinedDate() {
        assertThat(sql("customers and orders in 2025")).isEqualTo(
                "SELECT DISTINCT customers.* FROM customers JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.order_date >= '2025-01-01' AND orders.order_date < '2026-01-01';");
    }

    @Test
    @DisplayName("several date columns with none named decline; naming one by its stem picks it")
    void severalDateColumns() {
        schemas.publish(new SchemaSnapshot("u", "u", "querysense_uploads",
                List.of(new KnownSchema.Table("shipments", List.of("id", "sent_date", "received_date"))),
                List.of(), Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("shipments", Map.of("id", "INT", "sent_date", "DATE", "received_date", "DATE")),
                Map.of(), Map.of()));

        assertThat(declineReason("shipments last week")).contains("shipments.sent_date").contains("shipments.received_date");
        assertThat(sql("shipments received last week")).isEqualTo(
                "SELECT * FROM shipments WHERE received_date >= '2026-09-21' AND received_date < '2026-09-28';");
    }

    @Test
    @DisplayName("no date column, and an unreadable time word, decline")
    void declines() {
        assertThat(declineReason("customers from last month")).contains("date column");
        assertThat(declineReason("orders recently")).contains("recently");
    }

    @Test
    @DisplayName("MongoDB writes the same range as ISODate")
    void mongo() {
        assertThat(parser.parse("orders from last month", Dialect.MONGODB).sql()).isEqualTo(
                "db.orders.find({\"order_date\": {\"$gte\": ISODate(\"2026-08-31T18:30:00Z\"), "
                        + "\"$lt\": ISODate(\"2026-09-30T18:30:00Z\")}})");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=DatesTest`
Expected: compilation failure in `TestParsers` once Step 3 changes the constructor. Before that, the date questions decline under E3.

- [ ] **Step 3: Implement**

`TestParsers.java`: add `RulesClock.fixed(java.time.LocalDate.of(2026, 10, 2), java.time.ZoneId.of("Asia/Kolkata"))` as the parser's last constructor argument.

`QueryIntentParser.java`:

1. Add the field `private final RulesClock clock;`, a constructor parameter `RulesClock clock` as the last one, and the assignment.

2. Keep `TEMPORAL`, renamed `TIME_WORDS` and extended:

```java
    private static final Pattern TIME_WORDS = Pattern.compile(
            "\\b(?:last|this|next|past|previous)\\s+(?:month|week|year|day|quarter|decade)\\b"
                    + "|\\b(?:today|yesterday|tomorrow|recently|lately)\\b"
                    + "|\\b(?:in|during|before|after|since|by|until|till)\\s+"
                    + "(?:january|february|march|april|may|june|july|august"
                    + "|september|october|november|december)\\b"
                    + "|\\b(?:before|after|since|until)\\s+\\d{4}\\b"
                    + "|\\b(?:date range|last \\d+ days)\\b");
```

3. Replace the parser's first line `String text = SchemaBinder.normalize(rawInput);` with the following. Then replace E3's early decline (`if (TEMPORAL.matcher(text).find()) { return …; }`) with the decline below.

   Once read, the date expression is blanked out of `text`, keeping every character offset. No later rule ever sees its words:
   - the trailing-column rule would otherwise read "last week" in `orders last week` as a column request;
   - E14 would otherwise read `january` in `in january` as a value.

   `normalized` keeps the original words, for choosing the date column by stem.

```java
        String normalized = SchemaBinder.normalize(rawInput);
        Optional<DateReader.DateRange> date = DateReader.read(normalized, clock.today());
        String text = date.map(r -> normalized.substring(0, r.start()) + " ".repeat(r.end() - r.start())
                + normalized.substring(r.end())).orElse(normalized);
```

```java
        Matcher timeWords = TIME_WORDS.matcher(normalized);
        if (date.isEmpty() && timeWords.find()) {
            return RuleBuildResult.declined(
                    "The question mentions a time period (\"" + timeWords.group() + "\") this engine can't read as "
                            + "dates, so it went to the language model instead.",
                    bindings);
        }
```

4. Add the column chooser:

```java
    private record DateChoice(QueryIntent.ColumnRef ref, String namingWord) {}

    private DateChoice dateColumn(String text, List<KnownSchema.Table> chainTables, boolean joined, String phrase) {
        List<QueryIntent.ColumnRef> named = new ArrayList<>();
        List<String> namingWords = new ArrayList<>();
        List<QueryIntent.ColumnRef> all = new ArrayList<>();
        Set<String> words = Set.of(text.split("[^a-z0-9_]+"));
        for (KnownSchema.Table table : chainTables) {
            for (String column : table.columns()) {
                if (!SchemaBinder.isDateType(binder.fieldType(table, column))) {
                    continue;
                }
                QueryIntent.ColumnRef ref = new QueryIntent.ColumnRef(joined ? table.name() : null, column);
                all.add(ref);
                String stem = SchemaBinder.snakeCase(column).replaceAll("_?(date|at|on)$", "");
                String spaced = SchemaBinder.normalize(column);
                String naming = !stem.isEmpty() && words.contains(stem + "ed") ? stem + "ed"
                        : !stem.isEmpty() && words.contains(stem + "d") ? stem + "d"
                        : !stem.isEmpty() && words.contains(stem) ? stem
                        : text.contains(spaced) ? spaced : null;
                if (naming != null) {
                    named.add(ref);
                    namingWords.add(naming);
                }
            }
        }
        List<QueryIntent.ColumnRef> pick = !named.isEmpty() ? named
                : all.stream().filter(r -> r.table() == null || r.table().equals(chainTables.get(0).name())).toList();
        if (pick.isEmpty()) {
            pick = all;
        }
        if (pick.isEmpty()) {
            throw new DeclineException("No table in the question has a date column, so this engine can't place \""
                    + phrase + "\".");
        }
        if (pick.size() > 1) {
            String tableName = chainTables.get(0).name();
            throw new DeclineException("\"" + phrase + "\" could be checked against " + listWith("or", pick.stream()
                    .map(r -> (r.table() == null ? tableName : r.table()) + "." + r.column()).toList())
                    + "; name one in the question.");
        }
        return new DateChoice(pick.get(0), named.size() == 1 ? namingWords.get(0) : null);
    }
```

5. Immediately after the chain is built (sub-project 3's join block), and before the `between` reader:

```java
        if (date.isPresent()) {
            DateReader.DateRange range = date.get();
            DateChoice choice = dateColumn(normalized, chainTables, joined, range.phrase());
            QueryIntent.ColumnRef dateRef = choice.ref();
            if (choice.namingWord() != null) {
                Matcher naming = Pattern.compile("\\b" + Pattern.quote(choice.namingWord()) + "\\b").matcher(text);
                if (naming.find()) {
                    consumed.add(new int[]{naming.start(), naming.end()});
                }
            }
            if (range.from() != null) {
                where.add(new QueryIntent.Predicate(dateRef, QueryIntent.Op.GTE,
                        List.of(QueryIntent.Literal.date(range.from(), clock.zone()))));
            }
            if (range.until() != null) {
                where.add(new QueryIntent.Predicate(dateRef, QueryIntent.Op.LT,
                        List.of(QueryIntent.Literal.date(range.until(), clock.zone()))));
            }
            String owner = dateRef.table() == null ? subject.name() : dateRef.table();
            bindings.add(new RuleTrace.Binding(range.phrase(), "DATE_RANGE", owner + "." + dateRef.column()
                    + (range.from() == null ? "" : " from " + range.from())
                    + (range.until() == null ? "" : " up to " + range.until())));
            consumed.add(new int[]{range.start(), range.end()});
            conditionSpans.add(new int[]{range.start(), range.end()});
        }
```

   `consumed` and `conditionSpans` must be declared before this block. Move their declarations up if sub-projects 2–3 placed them later.

6. **The trailing-column rule must skip consumed words.** Otherwise `hired` in `employees hired since 2020` is read as a requested column. Where the parser calls `extractTrailingColumnPhrase(…)`, ignore the result when it starts inside a consumed span:

```java
            Optional<String> tail = extractTrailingColumnPhrase(text, subject, dialect)
                    .filter(t -> !insideAny(text.lastIndexOf(t), consumed));
```

7. Leave the `between` reader unchanged. A date `between january and march` is already blanked out of `text` before it runs.

8. In the OR check (sub-project 2), the date's two predicates come from one span, so they count as one condition. Change `if (where.size() >= 2)` to `if (conditionSpans.size() >= 2)`.

9. Update `DeclineBoundaryTest`: remove `"orders from last month"` from its must-decline lists (it now builds), and write a `Ruling:` line. Update any test asserting E3's old message ("The question asks about a period of time…") to the new wording, or to a question that still declines (`orders recently`), each with a `Ruling:` line.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='DatesTest,DateReaderTest,QueryIntentParserTest,DialectParityTest,DeclineBoundaryTest,RuleTraceWordingTest,QuestionShapesTest,MultiTableJoinTest,VocabularyTest,MongoRuleEngineTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): dates filter the named or only date column; E3's blanket decline is replaced

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Recency superlatives

**Files:**
- Modify: `backend/src/main/resources/querybuilder/adjectives.json`, `backend/src/main/java/com/querysense/backend/querybuilder/QueryIntentParser.java`
- Test: add to `DatesTest.java`

**Interfaces:**
- Consumes: `ranking(...)` and `Adjectives` (sub-project 2); `dateColumn(...)` (Task 3).

- [ ] **Step 1: Write the failing test**

```java
    @Test
    @DisplayName("latest, oldest and most recent sort by the date column")
    void recency() {
        assertThat(sql("latest 5 orders")).isEqualTo("SELECT * FROM orders ORDER BY order_date DESC LIMIT 5;");
        assertThat(sql("the oldest employee")).isEqualTo("SELECT * FROM employees ORDER BY hire_date ASC LIMIT 1;");
        assertThat(sql("most recent payments")).isEqualTo("SELECT * FROM payments ORDER BY payment_date DESC;");
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=DatesTest#recency`
Expected: FAIL — these words aren't adjectives yet.

- [ ] **Step 3: Implement**

`adjectives.json` — add:

```json
  "latest":   {"words": [], "direction": "DESC", "needsMostOrLeast": false, "type": "date"},
  "newest":   {"words": [], "direction": "DESC", "needsMostOrLeast": false, "type": "date"},
  "recent":   {"words": [], "direction": "DESC", "needsMostOrLeast": true,  "type": "date"},
  "oldest":   {"words": [], "direction": "ASC",  "needsMostOrLeast": false, "type": "date"},
  "earliest": {"words": [], "direction": "ASC",  "needsMostOrLeast": false, "type": "date"}
```

`ranking(...)` gains `List<KnownSchema.Table> chainTables` and `String normalized` parameters, and its caller passes them. In its adjective loop, replace the line that skips non-`column` entries with:

```java
            if (entry.needsMostOrLeast() && adjective.group(2) == null) {
                continue;
            }
            if ("date".equals(entry.type())) {
                QueryIntent.ColumnRef dateRef = dateColumn(normalized, chainTables, joined, adjective.group(3)).ref();
                QueryIntent.Direction direction = "least".equals(adjective.group(2))
                        ? (entry.direction() == QueryIntent.Direction.ASC ? QueryIntent.Direction.DESC : QueryIntent.Direction.ASC)
                        : entry.direction();
                Integer limit = null;
                for (String candidate : new String[]{adjective.group(1), adjective.group(4)}) {
                    Optional<NumberReader.Reading> n = candidate == null ? Optional.empty() : NumberReader.leading(candidate);
                    if (n.isPresent() && n.get().integral() && n.get().phrase().equals(candidate)) {
                        limit = n.get().value().intValueExact();
                    }
                }
                return Optional.of(new Ranking(dateRef, direction, limit != null ? limit : (singular ? 1 : null)));
            }
            if (!"column".equals(entry.type())) {
                continue;
            }
```

No interpretation is noted for a date adjective, so confidence stays 0.95.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='DatesTest,QuestionShapesTest,DialectParityTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(rules): latest, newest, most recent, oldest and earliest sort by the date column

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Parity, full checks and the record

**Files:**
- Modify: `backend/src/test/java/com/querysense/backend/querybuilder/DialectParityTest.java`, `RULES.md`, `PROJECT.md`

- [ ] **Step 1: Parity**

`DialectParityTest` builds its parser through `TestParsers`, so it already has the pinned clock. Add to its `@ValueSource`:

```java
            "orders last week",
            "employees hired since 2020",
            "orders in january",
            "orders between january and march",
            "customers and orders in 2025",
            "latest 5 orders",
            "orders recently",
```

`"orders from last month"` is already in the list. It now builds in both dialects, so the both-or-neither assertion still holds.

Run: `cd backend && ./mvnw -q test -Dtest=DialectParityTest`
Expected: PASS.

- [ ] **Step 2: Full checks**

Run: `cd backend && ./mvnw test`, then `cd frontend && npm test && npm run lint && npm run build`.
Expected: all green.

- [ ] **Step 3: `RULES.md`**

1. Replace E3 with **E3 · Dates**:
   - every expression of spec §4, each with its range relative to a stated today;
   - the column choice (named, stem, the only one; then the chain);
   - its declines;
   - the half-open ranges and Monday weeks;
   - the configured zone and the `querysense.rules.today` pin.
2. Add recency to E24 (ranking).
3. Update the decline table:
   - E3's "date expression" row becomes "an unreadable time word";
   - add "no date column" and "several date columns, none named".
4. Update the counts.

- [ ] **Step 4: `PROJECT.md`**

1. Add `### Session of <date> — rule engine dates (sub-project 4 of 4)`, with:
   - the task/commit table;
   - the departures;
   - the tests that moved from decline to build;
   - the test counts;
   - "not run in the app".
2. In the §5 roadmap, mark all four sub-projects built. Write one closing paragraph on what the rule engine now covers and what still goes to the language model.
3. **Live checklist**, with `querysense.rules.today` unset:
   1. `orders from last month` in the Workbench on MySQL and MongoDB. The trace shows the range from the 1st of last month up to the 1st of this month.
   2. `employees hired since 2020`.
   3. `latest 5 orders`.
   4. `orders recently` still goes to the language model, with the new reason.

- [ ] **Step 5: Commit**

```bash
git add backend/src RULES.md PROJECT.md
git commit -m "docs: record rule engine dates — the four-part adaptive rule engine is complete

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
