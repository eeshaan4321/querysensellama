package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The same question must build in both dialects or decline in both, except the listed differences. */
class DialectParityTest {

    private QueryIntentParser parser;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        parser = TestParsers.parser(schemas, metadata(Dialect.MYSQL), metadata(Dialect.MONGODB));
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        when(m.getDistinctValues(eq("orders"), eq("status"))).thenReturn(List.of("PENDING", "SHIPPED"));
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("builds in both dialects or declines in both")
    @ValueSource(strings = {
            "show all customers",
            "customers in pune",
            "customers in mumbai and pune",
            "how many orders are there",
            "which customers placed more than three orders",
            "average price of products",
            "top 5 products sorted by price descending",
            "show only the name and email of customers",
            "customers with city pune",
            "orders with status shipped",
            "delete customers in pune",
            "delete all customers",
            "orders from last month",
            "customers and suppliers",
            "orders, customers and products together",
            "show me everything interesting",
            "customers sorted by flurb",
            "how many customers are there per city",
            "total amount of payments per method",
            "total quantity of orders per status",
    })
    void sameOutcome(String question) {
        RuleBuildResult mysql = parser.parse(question, Dialect.MYSQL);
        RuleBuildResult mongo = parser.parse(question, Dialect.MONGODB);

        assertThat(mongo.built())
                .as("MySQL %s (%s), MongoDB %s (%s)",
                        mysql.built() ? "built" : "declined", mysql.built() ? mysql.sql() : mysql.trace().declineReason(),
                        mongo.built() ? "built" : "declined", mongo.built() ? mongo.sql() : mongo.trace().declineReason())
                .isEqualTo(mysql.built());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("documented difference: order items are a table in MySQL and embedded in MongoDB")
    @ValueSource(strings = {"show all order items"})
    void orderItemsDiffer(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).built()).isTrue();
        assertThat(parser.parse(question, Dialect.MONGODB).built()).isFalse();
    }
}
