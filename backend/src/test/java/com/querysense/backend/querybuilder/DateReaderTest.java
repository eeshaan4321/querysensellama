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
