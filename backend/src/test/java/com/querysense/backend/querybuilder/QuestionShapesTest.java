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
}
