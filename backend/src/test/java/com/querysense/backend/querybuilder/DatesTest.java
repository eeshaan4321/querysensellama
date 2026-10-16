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

/** Dates on the built-in demos, today pinned to Friday 2026-10-02 in Asia/Kolkata. */
class DatesTest {

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
    @DisplayName("a relative period on the only date column")
    void relative() {
        assertThat(sql("orders from last month"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
        assertThat(sql("orders last week"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-21' AND order_date < '2026-09-28';");
    }

    @Test
    @DisplayName("an open range, and a column named by its stem")
    void openAndStem() {
        assertThat(sql("employees hired since 2020")).isEqualTo("SELECT * FROM employees WHERE hire_date >= '2020-01-01';");
    }

    @Test
    @DisplayName("dateSpanIsConsumed: 'in january' is a date, never also a value filter")
    void dateSpanIsConsumed() {
        assertThat(sql("orders in january"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-01-01' AND order_date < '2026-02-01';");
    }

    @Test
    @DisplayName("valueFromStillWorks: 'customers from pune' is a value filter as today")
    void valueFromStillWorks() {
        assertThat(sql("customers from pune")).isEqualTo("SELECT * FROM customers WHERE city = 'pune';");
    }

    @Test
    @DisplayName("a date on a joined table filters that table, each subject once")
    void joinedDate() {
        assertThat(sql("customers and orders in 2025")).isEqualTo(
                "SELECT DISTINCT customers.* FROM customers JOIN orders ON customers.id = orders.customer_id "
                        + "WHERE orders.order_date >= '2025-01-01' AND orders.order_date < '2026-01-01';");
    }

    @Test
    @DisplayName("several date columns with none named decline; naming one by its stem picks it")
    void severalDateColumns() {
        schemas.publish(new SchemaSnapshot("u", "u", "querysense_uploads",
                List.of(new KnownSchema.Table("shipments", List.of("id", "sent_date", "received_date"))),
                List.of(), Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("shipments", Map.of("id", "INT", "sent_date", "DATE", "received_date", "DATE")),
                Map.of(), Map.of()));

        assertThat(declineReason("shipments last week")).contains("shipments.sent_date").contains("shipments.received_date");
        assertThat(sql("shipments received last week")).isEqualTo(
                "SELECT * FROM shipments WHERE received_date >= '2026-09-21' AND received_date < '2026-09-28';");
    }

    @Test
    @DisplayName("no date column, and an unreadable time word, decline")
    void declines() {
        assertThat(declineReason("customers from last month")).contains("date column");
        assertThat(declineReason("orders recently")).contains("recently");
    }

    @Test
    @DisplayName("MongoDB writes the same range as ISODate")
    void mongo() {
        assertThat(parser.parse("orders from last month", Dialect.MONGODB).sql()).isEqualTo(
                "db.orders.find({\"order_date\": {\"$gte\": ISODate(\"2026-08-31T18:30:00Z\"), "
                        + "\"$lt\": ISODate(\"2026-09-30T18:30:00Z\")}})");
    }

    @Test
    @DisplayName("latest, oldest and most recent sort by the date column")
    void recency() {
        assertThat(sql("latest 5 orders")).isEqualTo("SELECT * FROM orders ORDER BY order_date DESC LIMIT 5;");
        assertThat(sql("the oldest employee")).isEqualTo("SELECT * FROM employees ORDER BY hire_date ASC LIMIT 1;");
        assertThat(sql("most recent payments")).isEqualTo("SELECT * FROM payments ORDER BY payment_date DESC;");
    }

    @Test
    @DisplayName("repeatedWordsInADateQuestion: a word used twice does not crash the date reader")
    void repeatedWordsInADateQuestion() {
        assertThat(sql("show the orders from the last month"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
        assertThat(sql("orders between march 2025 and june 2025"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2025-03-01' AND order_date < '2025-07-01';");
        assertThat(sql("orders of customers in mumbai in 2025")).contains("orders.order_date >= '2025-01-01'");
    }

    @Test
    @DisplayName("rangeSharesItsYear: 'between march and june 2025' reads march as march 2025")
    void rangeSharesItsYear() {
        assertThat(sql("orders between march and june 2025"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2025-03-01' AND order_date < '2025-07-01';");
        assertThat(sql("orders from january to march 2024"))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2024-01-01' AND order_date < '2024-04-01';");
    }

    @Test
    @DisplayName("backwardsRangeDeclines: a range that ends before it starts is never built")
    void backwardsRangeDeclines() {
        assertThat(declineReason("orders between june 2025 and march 2025")).contains("ends before it starts");
    }
}
