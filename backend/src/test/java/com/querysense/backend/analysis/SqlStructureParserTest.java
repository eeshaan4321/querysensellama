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
    @DisplayName("an expression or aggregate is not a column an index can be put on, so it names nothing")
    void expressionsNameNoColumn() {
        assertThat(parser.parse("SELECT * FROM EMPLOYEE WHERE YEAR(Bdate) > 1965").getFilteredColumn()).isNull();
        assertThat(parser.parse("SELECT * FROM EMPLOYEE ORDER BY Salary * 2").getOrderByColumn()).isNull();
        assertThat(parser.parse("SELECT p.Pname, SUM(w.Hours) FROM PROJECT p JOIN WORKS_ON w ON p.Pnumber = w.Pno "
                + "GROUP BY p.Pname ORDER BY SUM(w.Hours) DESC").getOrderByColumn()).isNull();
        assertThat(parser.parse("SELECT * FROM EMPLOYEE ORDER BY e.Lname").getOrderByColumn()).isEqualTo("e.Lname");
    }

    @Test
    @DisplayName("table aliases in FROM and JOIN map back to their tables")
    void aliasesMapToTables() {
        var result = parser.parse("SELECT e.Fname FROM EMPLOYEE e JOIN DEPARTMENT AS d ON e.Dno = d.Dnumber");

        assertThat(result.realTableName("e")).isEqualTo("EMPLOYEE");
        assertThat(result.realTableName("D")).isEqualTo("DEPARTMENT");
        assertThat(result.realTableName("EMPLOYEE")).isEqualTo("EMPLOYEE");
    }

    @Test
    @DisplayName("an OR across two columns names neither, since no single index serves it")
    void orAcrossColumns() {
        assertThat(parser.parse("SELECT * FROM customers WHERE city = 'pune' OR name = 'asha'").getFilteredColumn())
                .isNull();
    }
}
