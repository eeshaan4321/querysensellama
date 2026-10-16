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
                            .map(y -> range(text, w, i, y.next(), shareYear(w, i + 1, x.get(), y).start(), y.end()));
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
                        return Optional.of(range(text, w, i, y.get().next(),
                                shareYear(w, i + 1, x.get(), y.get()).start(), y.get().end()));
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

    private static Period shareYear(List<Word> w, int at, Period x, Period y) {
        if (!MONTHS.containsKey(at(w, at)) || x.next() != at + 1 || !YEAR.matcher(at(w, y.next() - 1)).matches()) {
            return x;
        }
        LocalDate first = LocalDate.of(y.start().getYear(), MONTHS.get(at(w, at)), 1);
        return new Period(first, first.plusMonths(1), x.next());
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
