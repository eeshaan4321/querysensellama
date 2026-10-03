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

    @Test
    @DisplayName("a value found on a joined table filters it, and each subject row comes once")
    void valueOnJoinedTable() {
        assertThat(mysql("orders of customers in mumbai").sql()).isEqualTo(
                "SELECT DISTINCT orders.* FROM orders JOIN customers ON orders.customer_id = customers.id "
                        + "WHERE customers.city = 'mumbai';");
        assertThat(mysql("how many orders of customers in mumbai").sql()).isEqualTo(
                "SELECT COUNT(DISTINCT orders.id) FROM orders JOIN customers ON orders.customer_id = customers.id "
                        + "WHERE customers.city = 'mumbai';");
    }

    @Test
    @DisplayName("orderedByTableIsNotSort: products ordered by customers in pune is a four-table chain")
    void orderedByTableIsNotSort() {
        assertThat(mysql("products ordered by customers in pune").sql()).isEqualTo(
                "SELECT DISTINCT products.* FROM products JOIN order_items ON products.id = order_items.product_id "
                        + "JOIN orders ON order_items.order_id = orders.id "
                        + "JOIN customers ON orders.customer_id = customers.id WHERE customers.city = 'pune';");
    }

    @Test
    @DisplayName("ofTableIsNotProjection: 'orders of customers' is not a request for an 'orders' column")
    void ofTableIsNotProjection() {
        assertThat(mysql("orders of customers in mumbai").built()).isTrue();
    }

    @Test
    @DisplayName("a sort column found only on a joined table sorts by it")
    void sortOnJoinedTable() {
        assertThat(mysql("orders and customers sorted by city").sql()).isEqualTo(
                "SELECT * FROM orders JOIN customers ON orders.customer_id = customers.id ORDER BY customers.city ASC;");
    }

    @Test
    @DisplayName("a column fitting two chain tables declines, naming both")
    void ambiguousColumn() {
        assertThat(mysql("orders and categories sorted by name").trace().declineReason())
                .contains("products.name").contains("categories.name");
    }

    @Test
    @DisplayName("spec §7: a comparison on a joined table's column filters it instead of counting")
    void comparisonOnJoinedColumn() {
        assertThat(mysql("orders with payments amount over 100").sql()).isEqualTo(
                "SELECT DISTINCT orders.* FROM orders JOIN payments ON orders.id = payments.order_id "
                        + "WHERE payments.amount > 100;");
    }

    @Test
    @DisplayName("MongoDB: the joined filter follows its lookup, each order once")
    void mongoJoinedFilter() {
        assertThat(parser.parse("orders of customers in mumbai", Dialect.MONGODB).sql()).isEqualTo(
                "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", \"localField\": \"customer_id\", "
                        + "\"foreignField\": \"_id\", \"as\": \"customers\"}}, {\"$unwind\": \"$customers\"}, "
                        + "{\"$match\": {\"customers.city\": \"Mumbai\"}}, "
                        + "{\"$group\": {\"_id\": \"$_id\", \"doc\": {\"$first\": \"$$ROOT\"}}}, "
                        + "{\"$replaceRoot\": {\"newRoot\": \"$doc\"}}, {\"$project\": {\"customers\": 0}}])");
    }
}
