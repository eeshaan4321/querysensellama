package com.querysense.backend.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStructureParserTest {

    private final SqlStructureParser parser = new SqlStructureParser();

    @Test
    @DisplayName("a plain comparison names its column")
    void comparison() {
        assertThat(parser.parse("SELECT * FROM customers WHERE city = 'mumbai'").getFilteredColumn()).isEqualTo("city");
    }

    @Test
    @DisplayName("filterColumnInsideInBetweenAndOr: IN, BETWEEN and an OR on one column all name that column")
    void filterColumnInsideInBetweenAndOr() {
        assertThat(parser.parse("SELECT * FROM customers WHERE city IN ('mumbai', 'pune')").getFilteredColumn())
                .isEqualTo("city");
        assertThat(parser.parse("SELECT * FROM products WHERE price BETWEEN 100 AND 500").getFilteredColumn())
                .isEqualTo("price");
        assertThat(parser.parse("SELECT * FROM products WHERE (price < 100 OR price > 900)").getFilteredColumn())
                .isEqualTo("price");
    }

    @Test
    @DisplayName("an OR across two columns names neither, since no single index serves it")
    void orAcrossColumns() {
        assertThat(parser.parse("SELECT * FROM customers WHERE city = 'pune' OR name = 'asha'").getFilteredColumn())
                .isNull();
    }
}
