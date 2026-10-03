package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The shapes of sub-project 2 on a schema that declares no aliases. */
class QuestionShapesTest {

    static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("customers", List.of("id", "name", "city", "email")),
            new KnownSchema.Table("orders", List.of("id", "customer_id", "amount", "status")),
            new KnownSchema.Table("products", List.of("id", "name", "price")),
            new KnownSchema.Table("payments", List.of("id", "order_id", "amount", "method")),
            new KnownSchema.Table("listings", List.of("id", "price", "cost")));

    static final List<KnownSchema.ForeignKey> KEYS = List.of(
            new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id"),
            new KnownSchema.ForeignKey("payments", "order_id", "orders", "id"));

    private QueryIntentParser parser;

    static SchemaSnapshot upload(Dialect dialect) {
        return new SchemaSnapshot("upload-s", "shop", "querysense_uploads", TABLES, KEYS, Map.of(),
                Instant.now(), dialect, List.of(), Map.of(), Map.of(), Map.of());
    }

    static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(m.getDistinctValues(eq("customers"), eq("name"))).thenReturn(List.of("Asha", "Ravi"));
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("SHIPPED", "PENDING"));
        return m;
    }

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(upload(Dialect.MYSQL));
        schemas.publish(upload(Dialect.MONGODB));
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    String sql(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).as(result.trace().declineReason()).isTrue();
        return result.sql();
    }

    String declineReason(String question) {
        RuleBuildResult result = parser.parse(question, Dialect.MYSQL);
        assertThat(result.built()).isFalse();
        return result.trace().declineReason();
    }

    @Test
    @DisplayName("inclusive bounds")
    void inclusiveBounds() {
        assertThat(sql("products with price at least 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
        assertThat(sql("products with price no less than 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
        assertThat(sql("products with price at most 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("products with price no more than 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("products with price up to 500")).isEqualTo("SELECT * FROM products WHERE price <= 500;");
        assertThat(sql("which customers placed at least three orders")).isEqualTo(
                "SELECT customers.name, customers.id, COUNT(orders.id) FROM customers JOIN orders "
                        + "ON customers.id = orders.customer_id "
                        + "GROUP BY customers.name, customers.id HAVING COUNT(orders.id) >= 3;");
    }

    @Test
    @DisplayName("between")
    void between() {
        assertThat(sql("products with price between 100 and 500"))
                .isEqualTo("SELECT * FROM products WHERE price BETWEEN 100 AND 500;");
    }

    @Test
    @DisplayName("betweenWithAndInsideNumber: the low end is read whole before the separating 'and'")
    void betweenWithAndInsideNumber() {
        assertThat(sql("products with price between two hundred and fifty and 500"))
                .isEqualTo("SELECT * FROM products WHERE price BETWEEN 250 AND 500;");
    }

    @Test
    @DisplayName("a between whose ends aren't numbers declines")
    void betweenNonNumbers() {
        assertThat(declineReason("products with price between cheap and dear")).contains("two numbers");
    }

    @Test
    @DisplayName("not-equal on a named column")
    void notEqualColumn() {
        assertThat(sql("orders with status not shipped")).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
        assertThat(sql("orders with status is not shipped")).isEqualTo("SELECT * FROM orders WHERE status != 'shipped';");
    }

    @Test
    @DisplayName("exclusion finds its column from the data")
    void exclusion() {
        assertThat(sql("customers other than pune")).isEqualTo("SELECT * FROM customers WHERE city != 'pune';");
        assertThat(sql("customers excluding pune and delhi"))
                .isEqualTo("SELECT * FROM customers WHERE city NOT IN ('pune', 'delhi');");
        assertThat(sql("customers except mumbai")).isEqualTo("SELECT * FROM customers WHERE city != 'mumbai';");
    }

    @Test
    @DisplayName("notInIsOnePredicate: 'not in pune' is one not-equal, never also an equality")
    void notInIsOnePredicate() {
        assertThat(sql("customers not in pune")).isEqualTo("SELECT * FROM customers WHERE city != 'pune';");
    }

    @Test
    @DisplayName("an exclusion whose value is nowhere declines")
    void exclusionUnknownValue() {
        assertThat(declineReason("customers excluding atlantis")).contains("\"atlantis\"");
    }

    @Test
    @DisplayName("without: rows with no related row")
    void without() {
        String expected = "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                + "WHERE orders.customer_id IS NULL;";
        assertThat(sql("customers without orders")).isEqualTo(expected);
        assertThat(sql("customers with no orders")).isEqualTo(expected);
        assertThat(sql("customers who have no orders")).isEqualTo(expected);
    }

    @Test
    @DisplayName("without, counted and filtered")
    void withoutCountedAndFiltered() {
        assertThat(sql("how many customers without orders")).isEqualTo(
                "SELECT COUNT(*) FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.customer_id IS NULL;");
        assertThat(sql("customers in pune without orders")).isEqualTo(
                "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE customers.city = 'pune' AND orders.customer_id IS NULL;");
    }

    @Test
    @DisplayName("never <verb>ed reads the verb's stem as a table; with no direct key it declines")
    void never() {
        assertThat(sql("customers who never ordered")).isEqualTo(
                "SELECT customers.* FROM customers LEFT JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.customer_id IS NULL;");
        assertThat(declineReason("products never ordered")).contains("no relationship");
    }

    @Test
    @DisplayName("an anti-join with no linking key declines")
    void withoutNoKey() {
        assertThat(declineReason("products without payments")).contains("no relationship");
    }

    @Test
    @DisplayName("distinct values of a column")
    void distinct() {
        assertThat(sql("distinct cities of customers")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("unique cities of customers")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("which cities do customers live in")).isEqualTo("SELECT DISTINCT city FROM customers;");
        assertThat(sql("what statuses do orders have")).isEqualTo("SELECT DISTINCT status FROM orders;");
    }

    @Test
    @DisplayName("whichTableIsNotDistinct: 'which customers are in mumbai' reads as today")
    void whichTableIsNotDistinct() {
        assertThat(sql("which customers are in mumbai")).isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("or inside a value list is IN; or between conditions is OR")
    void or() {
        assertThat(sql("customers in mumbai or pune")).isEqualTo("SELECT * FROM customers WHERE city IN ('mumbai', 'pune');");
        assertThat(sql("customers in mumbai or with name asha"))
                .isEqualTo("SELECT * FROM customers WHERE (city = 'mumbai' OR name = 'asha');");
        assertThat(sql("products with price below 100 or price above 900"))
                .isEqualTo("SELECT * FROM products WHERE (price < 100 OR price > 900);");
    }

    @Test
    @DisplayName("every condition is used: a second comparison and a with-filter beside another filter (spec §3.8)")
    void silentDropsFixed() {
        assertThat(sql("products with price above 100 and price below 900"))
                .isEqualTo("SELECT * FROM products WHERE price > 100 AND price < 900;");
        assertThat(sql("customers in mumbai with name asha"))
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai' AND name = 'asha';");
    }

    @Test
    @DisplayName("withInsideComparisonIsNotAFilter: 'with price at least 500' is one predicate")
    void withInsideComparisonIsNotAFilter() {
        assertThat(sql("products with price at least 500")).isEqualTo("SELECT * FROM products WHERE price >= 500;");
    }

    @Test
    @DisplayName("and mixed with or between conditions declines; or with an anti-join declines")
    void mixedDeclines() {
        assertThat(declineReason("products with price below 100 or price above 900 and price below 950"))
                .contains("both \"and\" and \"or\"");
        assertThat(declineReason("customers in pune or with name asha without orders")).contains("without");
    }
}
