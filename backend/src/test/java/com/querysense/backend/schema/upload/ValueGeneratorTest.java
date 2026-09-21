package com.querysense.backend.schema.upload;

import com.querysense.backend.schema.ddl.ParsedColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValueGeneratorTest {

    private ValueGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ValueGenerator(7L);
    }

    @Test
    @DisplayName("a VARCHAR value never exceeds its declared length")
    void respectsVarcharLength() {
        ParsedColumn column = col("code", "VARCHAR", List.of("5"));
        for (int i = 0; i < 50; i++) {
            assertThat((String) generator.valueFor(column, i, 500)).hasSizeLessThanOrEqualTo(5);
        }
    }

    @Test
    @DisplayName("an email-shaped column produces something that looks like an email")
    void generatesEmails() {
        assertThat((String) generator.valueFor(col("email", "VARCHAR", List.of("255")), 3, 500))
                .contains("@");
    }

    @Test
    @DisplayName("a status-shaped column has low cardinality, an email-shaped one high")
    void skewsCardinality() {
        Set<Object> statuses = new HashSet<>();
        Set<Object> emails = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            statuses.add(generator.valueFor(col("status", "VARCHAR", List.of("32")), i, 200));
            emails.add(generator.valueFor(col("email", "VARCHAR", List.of("255")), i, 200));
        }
        assertThat(statuses).hasSizeLessThanOrEqualTo(8);
        assertThat(emails).hasSizeGreaterThan(150);
    }

    @Test
    @DisplayName("an ENUM value is always one of its declared literals")
    void picksFromEnum() {
        ParsedColumn column = col("state", "ENUM", List.of("'NEW'", "'PAID'"));
        for (int i = 0; i < 20; i++) {
            assertThat(generator.valueFor(column, i, 500)).isIn("NEW", "PAID");
        }
    }

    @Test
    @DisplayName("a DECIMAL respects its declared precision and scale")
    void respectsDecimalScale() {
        Object value = generator.valueFor(col("price", "DECIMAL", List.of("6", "2")), 1, 500);
        assertThat(value).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) value).scale()).isEqualTo(2);
        assertThat(((BigDecimal) value).precision()).isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("a DATE column produces a java.sql.Date")
    void generatesDates() {
        assertThat(generator.valueFor(col("order_date", "DATE", List.of()), 1, 500))
                .isInstanceOf(java.sql.Date.class);
    }

    @Test
    @DisplayName("a unique column never repeats a value across the run")
    void uniqueColumnsDoNotRepeat() {
        ParsedColumn column = new ParsedColumn("email", "VARCHAR", List.of("255"), true, false, true, false);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(seen.add(generator.valueFor(column, i, 500))).isTrue();
        }
    }

    @Test
    @DisplayName("a nullable non-key column produces some nulls, a NOT NULL column produces none")
    void nullsOnlyWhereAllowed() {
        ParsedColumn nullable = new ParsedColumn("note", "VARCHAR", List.of("64"), false, false, false, false);
        ParsedColumn required = new ParsedColumn("note", "VARCHAR", List.of("64"), true, false, false, false);

        boolean sawNull = false;
        for (int i = 0; i < 300; i++) {
            if (generator.valueFor(nullable, i, 300) == null) {
                sawNull = true;
            }
            assertThat(generator.valueFor(required, i, 300)).isNotNull();
        }
        assertThat(sawNull).isTrue();
    }

    @Test
    @DisplayName("a primary key is never null and never repeats")
    void primaryKeysAreUsable() {
        ParsedColumn pk = new ParsedColumn("id", "INT", List.of(), true, true, false, true);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Object value = generator.valueFor(pk, i, 100);
            assertThat(value).isNotNull();
            assertThat(seen.add(value)).isTrue();
        }
    }

    @Test
    @DisplayName("the same seed produces the same data twice")
    void isReproducible() {
        ParsedColumn column = col("name", "VARCHAR", List.of("64"));
        Object first = new ValueGenerator(42L).valueFor(column, 5, 500);
        Object second = new ValueGenerator(42L).valueFor(column, 5, 500);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a TINYINT value never exceeds the signed TINYINT maximum")
    void respectsTinyintRange() {
        ParsedColumn column = col("status", "TINYINT", List.of());
        for (int i = 0; i < 500; i++) {
            Number value = (Number) generator.valueFor(column, i, 12000);
            assertThat(value.longValue()).isBetween(1L, 127L);
        }
    }

    @Test
    @DisplayName("a SMALLINT value never exceeds the signed SMALLINT maximum")
    void respectsSmallintRange() {
        ParsedColumn column = col("score", "SMALLINT", List.of());
        for (int i = 0; i < 500; i++) {
            Number value = (Number) generator.valueFor(column, i, 12000);
            assertThat(value.longValue()).isBetween(1L, 32767L);
        }
    }

    @Test
    @DisplayName("a MEDIUMINT value never exceeds the signed MEDIUMINT maximum")
    void respectsMediumintRange() {
        ParsedColumn column = col("hits", "MEDIUMINT", List.of());
        for (int i = 0; i < 200; i++) {
            Number value = (Number) generator.valueFor(column, i, 12000);
            assertThat(value.longValue()).isBetween(1L, 8388607L);
        }
    }

    @Test
    @DisplayName("the year heuristic still respects a narrow declared type")
    void yearHeuristicRespectsNarrowType() {
        ParsedColumn column = col("year", "TINYINT", List.of());
        for (int i = 0; i < 200; i++) {
            Number value = (Number) generator.valueFor(column, i, 12000);
            assertThat(value.longValue()).isBetween(1L, 127L);
        }
    }

    @Test
    @DisplayName("a wide INT column keeps its existing range")
    void wideIntegersAreUnchanged() {
        ParsedColumn column = col("amount", "INT", List.of());
        for (int i = 0; i < 200; i++) {
            Number value = (Number) generator.valueFor(column, i, 12000);
            assertThat(value.longValue()).isBetween(1L, 1000L);
        }
    }

    @Test
    @DisplayName("a TINYINT primary key caps how many rows the table can hold")
    void tinyintPrimaryKeyCapsRowCount() {
        ParsedColumn pk = new ParsedColumn("id", "TINYINT", List.of(), true, false, false, true);
        assertThat(ValueGenerator.maxSeedableRows(pk, 12000)).isEqualTo(127);
    }

    @Test
    @DisplayName("a wide primary key does not cap the row count")
    void widePrimaryKeyDoesNotCap() {
        ParsedColumn pk = new ParsedColumn("id", "INT", List.of(), true, false, false, true);
        assertThat(ValueGenerator.maxSeedableRows(pk, 12000)).isEqualTo(12000);
    }

    @Test
    @DisplayName("a non-integer primary key does not cap the row count")
    void textPrimaryKeyDoesNotCap() {
        ParsedColumn pk = new ParsedColumn("code", "VARCHAR", List.of("32"), true, false, false, true);
        assertThat(ValueGenerator.maxSeedableRows(pk, 12000)).isEqualTo(12000);
    }

    private static ParsedColumn col(String name, String type, List<String> args) {
        return new ParsedColumn(name, type, args, true, false, false, false);
    }
}
