package com.querysense.backend.schema.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InsertRowsTest {

    @Test
    @DisplayName("reads the table, column list and literal rows of an INSERT")
    void readsRows() {
        Optional<SampleInsert> insert = InsertRows.read("""
                INSERT INTO departments (code, name, parent_dept_id) VALUES
                 ('CS', 'Computer Science', 1),
                 ('MATH', 'O''Brien Hall', NULL)
                """);

        assertThat(insert).isPresent();
        assertThat(insert.get().table()).isEqualTo("departments");
        assertThat(insert.get().columns()).containsExactly("code", "name", "parent_dept_id");
        assertThat(insert.get().rows()).containsExactly(
                List.of("CS", "Computer Science", BigDecimal.ONE),
                Arrays.asList("MATH", "O'Brien Hall", null));
    }

    @Test
    @DisplayName("numbers, booleans, negative values and typed date literals are read")
    void literals() {
        SampleInsert insert = InsertRows.read(
                "INSERT INTO `t` VALUES (-2.50, TRUE, false, DATE '2024-01-15', TIMESTAMP '2024-01-15 10:00:00')").orElseThrow();

        assertThat(insert.table()).isEqualTo("t");
        assertThat(insert.columns()).isEmpty();
        assertThat(insert.rows()).containsExactly(
                List.of(new BigDecimal("-2.50"), true, false, "2024-01-15", "2024-01-15 10:00:00"));
    }

    @Test
    @DisplayName("a row with a function call or subquery is skipped, and the rest are kept")
    void skipsComputedRows() {
        SampleInsert insert = InsertRows.read("""
                INSERT INTO terms (name, start_date) VALUES
                 ('Fall', now()),
                 ('Spring', '2025-01-10')
                """).orElseThrow();

        assertThat(insert.rows()).containsExactly(List.of("Spring", "2025-01-10"));
        assertThat(insert.skippedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("an INSERT ... SELECT or anything that is not a VALUES list is not read")
    void ignoresNonValues() {
        assertThat(InsertRows.read("INSERT INTO a SELECT * FROM b")).isEmpty();
        assertThat(InsertRows.read("UPDATE a SET x = 1")).isEmpty();
    }

    @Test
    @DisplayName("a schema-qualified or quoted table name is read as its bare name")
    void qualifiedName() {
        assertThat(InsertRows.read("INSERT INTO campus.\"students\" (id) VALUES (1)").orElseThrow().table())
                .isEqualTo("students");
    }
}
