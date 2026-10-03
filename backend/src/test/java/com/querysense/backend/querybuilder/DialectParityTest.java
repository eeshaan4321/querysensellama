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
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi", "New Delhi"));
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
            "orders, customers and products together",
            "show me everything interesting",
            "customers sorted by flurb",
            "how many customers are there per city",
            "total amount of payments per method",
            "total quantity of orders per status",
            "clients in pune",
            "custmers in pune",
            "buyers in mumbai",
            "customers in new delhi",
            "customers in mumbia",
            "top twenty products",
            "products with price above two hundred and fifty",
            "show emial of customers",
            "top 5 products by price",
            "cheapest product",
            "products with price at least 500",
            "products with price between 100 and 500",
            "orders with status not shipped",
            "customers excluding pune and delhi",
            "customers without orders",
            "how many customers without orders",
            "distinct cities of customers",
            "customers in mumbai or pune",
            "average and highest price of products",
            "employees and customers",
            "customers and products",
            "orders of customers in mumbai",
            "how many orders of customers in mumbai",
            "orders and customers sorted by city",
            "orders last week",
            "employees hired since 2020",
            "orders in january",
            "orders between january and march",
            "customers and orders in 2025",
            "latest 5 orders",
            "orders recently",
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

    @ParameterizedTest(name = "{0}")
    @DisplayName("documented difference: MongoDB reaches products through orders.items.product_id,"
            + " while MySQL goes through the order_items table; both build")
    @ValueSource(strings = {"show orders with products", "show products and orders"})
    void embeddedReferenceJoinsDiffer(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).sql()).contains("JOIN order_items");
        assertThat(parser.parse(question, Dialect.MONGODB).sql()).contains("items.product_id");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("documented difference: the embedded reference makes MongoDB's customers-to-suppliers chain"
            + " four collections long, while MySQL's runs through order_items to five tables and declines")
    @ValueSource(strings = {"customers and suppliers"})
    void embeddedReferenceShortensChains(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).built()).isFalse();
        assertThat(parser.parse(question, Dialect.MONGODB).sql()).contains("\"from\": \"suppliers\"");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("documented difference: an order's item fields are embedded in MongoDB orders,"
            + " but live in MySQL's order_items table, so only MongoDB can project them from orders")
    @ValueSource(strings = {"show unit price of orders"})
    void embeddedFieldProjectionsDiffer(String question) {
        assertThat(parser.parse(question, Dialect.MYSQL).built()).isFalse();
        assertThat(parser.parse(question, Dialect.MONGODB).sql())
                .isEqualTo("db.orders.find({}, {\"items.unit_price\": 1, \"_id\": 0})");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("both dialects record the same interpretations")
    @ValueSource(strings = {"clients in pune", "custmers in pune", "buyers in mumbai", "customers in mumbia",
            "show emial of customers"})
    void sameInterpretations(String question) {
        RuleBuildResult sql = parser.parse(question, Dialect.MYSQL);
        RuleBuildResult mongo = parser.parse(question, Dialect.MONGODB);

        assertThat(sql.built()).isTrue();
        assertThat(mongo.built()).isTrue();
        assertThat(mongo.interpretations()).isEqualTo(sql.interpretations());
    }
}
