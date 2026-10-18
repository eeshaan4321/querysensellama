package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Questions the engine used to build with part of the question silently dropped. */
class UnreadWordsTest {

    private QueryIntentParser parser;

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune"));
        when(m.getDistinctValues(eq("customers"), eq("name"))).thenReturn(List.of("Aarav Sharma", "Tara Patel"));
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("SHIPPED", "PENDING"));
        when(m.getDistinctValues(eq("departments"), eq("name"))).thenReturn(List.of("Sales", "Finance"));
        return m;
    }

    @BeforeEach
    void setUp() {
        parser = TestParsers.parser(new ActiveSchemaProvider(), metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private String sql(String question, Dialect dialect) {
        RuleBuildResult result = parser.parse(question, dialect);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        return result.sql();
    }

    @Test
    @DisplayName("columnIsValue: 'whose city is mumbai' and 'where city is pune' filter on the column")
    void columnIsValue() {
        assertThat(sql("customers whose city is mumbai", Dialect.MYSQL))
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
        assertThat(sql("customers where city is pune", Dialect.MYSQL))
                .isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
        assertThat(sql("orders whose status is shipped", Dialect.MONGODB))
                .isEqualTo("db.orders.find({\"status\": \"SHIPPED\"})");
    }

    @Test
    @DisplayName("multiWordWithValue: 'with name aarav sharma' keeps both words")
    void multiWordWithValue() {
        assertThat(sql("customers in mumbai with name aarav sharma", Dialect.MYSQL))
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai' AND name = 'aarav sharma';");
        assertThat(sql("customers with name tara patel", Dialect.MONGODB))
                .isEqualTo("db.customers.find({\"name\": \"Tara Patel\"})");
    }

    @ParameterizedTest
    @DisplayName("unreadWordDeclines: a word the engine can't place declines instead of being dropped")
    @ValueSource(strings = {"which customers spent the most money", "customers who spent the most",
            "list customers by money spent"})
    void unreadWordDeclines(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as("built %s", result.sql()).isFalse();
        assertThat(result.trace().declineReason()).containsAnyOf("\"spent\"", "\"money\"");
    }

    @Test
    @DisplayName("bareByDeclines: 'customers by city' could mean sort or group, so it declines")
    void bareByDeclines() {
        RuleBuildResult result = parser.parse("customers by city", Dialect.MYSQL);
        assertThat(result.built()).as("built %s", result.sql()).isFalse();
        assertThat(result.trace().declineReason()).contains("\"by city\"");
    }

    @Test
    @DisplayName("totalIsASum: 'total amount of payments' sums the column")
    void totalIsASum() {
        assertThat(sql("total amount of payments", Dialect.MYSQL)).isEqualTo("SELECT SUM(amount) FROM payments;");
        assertThat(sql("total number of orders", Dialect.MYSQL)).isEqualTo("SELECT COUNT(*) FROM orders;");
        assertThat(sql("count the number of orders", Dialect.MYSQL)).isEqualTo("SELECT COUNT(*) FROM orders;");
        assertThat(sql("total orders", Dialect.MYSQL)).isEqualTo("SELECT COUNT(*) FROM orders;");
    }

    @Test
    @DisplayName("twoWordSortColumn: 'sorted by order date descending' sorts on order_date")
    void twoWordSortColumn() {
        assertThat(sql("list employees ordered by hire date", Dialect.MYSQL))
                .isEqualTo("SELECT * FROM employees ORDER BY hire_date ASC;");
        assertThat(sql("orders sorted by order date descending", Dialect.MYSQL))
                .isEqualTo("SELECT * FROM orders ORDER BY order_date DESC;");
    }

    @Test
    @DisplayName("countBeforeTable: 'show 3 employees' limits to three")
    void countBeforeTable() {
        assertThat(sql("show 3 employees", Dialect.MYSQL)).isEqualTo("SELECT * FROM employees LIMIT 3;");
        assertThat(sql("list five products", Dialect.MYSQL)).isEqualTo("SELECT * FROM products LIMIT 5;");
    }

    @Test
    @DisplayName("comparativeAdjective: 'more expensive than 500' and 'cheaper than 100' compare the price")
    void comparativeAdjective() {
        assertThat(sql("products more expensive than 500", Dialect.MYSQL)).isEqualTo("SELECT * FROM products WHERE price > 500;");
        assertThat(sql("products less expensive than 300", Dialect.MYSQL)).isEqualTo("SELECT * FROM products WHERE price < 300;");
        assertThat(sql("products cheaper than 100", Dialect.MYSQL)).isEqualTo("SELECT * FROM products WHERE price < 100;");
    }

    @Test
    @DisplayName("tableWordBeforeValue: 'employees in department sales' filters the department")
    void tableWordBeforeValue() {
        assertThat(sql("employees in department sales", Dialect.MYSQL)).contains("WHERE departments.name = 'sales'");
    }

    @Test
    @DisplayName("unusedValueDeclines: a stored value the question names but nothing used declines")
    void unusedValueDeclines() {
        RuleBuildResult result = parser.parse("show orders shipped", Dialect.MYSQL);
        assertThat(result.built()).as("built %s", result.sql()).isFalse();
        assertThat(result.trace().declineReason()).contains("\"shipped\"");
    }

    @Test
    @DisplayName("pleaseIsPolite: 'please' is never read as a column")
    void pleaseIsPolite() {
        assertThat(sql("can you list all the customers please", Dialect.MYSQL)).isEqualTo("SELECT * FROM customers;");
    }

    @Test
    @DisplayName("unusedStoredValueDeclines: 'orders that are pending' names a value nothing filtered on")
    void unusedStoredValueDeclines() {
        RuleBuildResult result = parser.parse("show orders that are pending", Dialect.MYSQL);
        assertThat(result.built()).as("built %s", result.sql()).isFalse();
        assertThat(result.trace().declineReason()).contains("\"pending\"");
    }

    @Test
    @DisplayName("columnListAfterTable: 'customer names and emails' projects both columns")
    void columnListAfterTable() {
        assertThat(sql("show customer names and emails", Dialect.MYSQL)).isEqualTo("SELECT name, email FROM customers;");
    }

    @Test
    @DisplayName("inEachGroups: 'in each department' is a breakdown, never one total")
    void inEachGroups() {
        assertThat(sql("number of orders in each status", Dialect.MYSQL))
                .isEqualTo("SELECT status, COUNT(*) FROM orders GROUP BY status;");
        assertThat(sql("how many employees in each department", Dialect.MYSQL))
                .isEqualTo("SELECT departments.id, departments.name, COUNT(*) FROM employees JOIN departments "
                        + "ON employees.department_id = departments.id GROUP BY departments.id, departments.name;");
    }

    @Test
    @DisplayName("aggregateOfQualifiedColumn: 'sum of payment amounts' sums amount")
    void aggregateOfQualifiedColumn() {
        assertThat(sql("sum of payment amounts", Dialect.MYSQL)).isEqualTo("SELECT SUM(amount) FROM payments;");
        assertThat(sql("average payment amount per method", Dialect.MYSQL))
                .isEqualTo("SELECT method, AVG(amount) FROM payments GROUP BY method;");
    }

    @Test
    @DisplayName("differentValues: 'the different order statuses' selects distinct statuses")
    void differentValues() {
        assertThat(sql("what are the different order statuses", Dialect.MYSQL)).isEqualTo("SELECT DISTINCT status FROM orders;");
    }
}
