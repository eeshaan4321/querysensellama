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
