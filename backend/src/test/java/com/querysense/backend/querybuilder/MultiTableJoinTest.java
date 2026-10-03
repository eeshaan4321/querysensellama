package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Chains of up to four tables on the built-in demo schemas. */
class MultiTableJoinTest {

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

    RuleBuildResult mysql(String question) {
        return parser.parse(question, Dialect.MYSQL);
    }

    @Test
    @DisplayName("a three-table chain through an unnamed table, with every edge in the trace")
    void threeTables() {
        RuleBuildResult result = mysql("employees and customers");

        assertThat(result.sql()).isEqualTo("SELECT * FROM employees JOIN orders ON employees.id = orders.employee_id "
                + "JOIN customers ON orders.customer_id = customers.id;");
        assertThat(result.trace().joinEdges()).hasSize(2);
    }

    @Test
    @DisplayName("a four-table chain")
    void fourTables() {
        assertThat(mysql("customers and products").sql()).isEqualTo("SELECT * FROM customers "
                + "JOIN orders ON customers.id = orders.customer_id "
                + "JOIN order_items ON orders.id = order_items.order_id "
                + "JOIN products ON order_items.product_id = products.id;");
    }

    @Test
    @DisplayName("too many tables, and no chain within four, decline")
    void refused() {
        assertThat(mysql("orders, categories and departments").trace().declineReason()).contains("four");
        assertThat(mysql("departments and products").trace().declineReason()).contains("departments").contains("products");
    }

    @Test
    @DisplayName("twoTableQuestionUnchanged: today's two-table output, word for word")
    void twoTableQuestionUnchanged() {
        assertThat(mysql("which customers placed more than three orders").sql()).isEqualTo(
                "SELECT customers.name, customers.id, COUNT(orders.id) FROM customers JOIN orders "
                        + "ON customers.id = orders.customer_id "
                        + "GROUP BY customers.name, customers.id HAVING COUNT(orders.id) > 3;");
    }
}
