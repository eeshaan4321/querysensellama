package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexStatementsTest {

    @Test
    @DisplayName("MySQL statements match the advisor's existing CREATE INDEX format")
    void mysql() {
        assertThat(IndexStatements.create(Dialect.MYSQL, "orders", "status"))
                .isEqualTo("CREATE INDEX idx_orders_status ON orders(status);");
        assertThat(IndexStatements.create(Dialect.MYSQL, "orders", List.of(
                new IndexSpec.KeyPart("status", 1), new IndexSpec.KeyPart("order_date", -1))))
                .isEqualTo("CREATE INDEX idx_orders_status_order_date ON orders(status, order_date);");
    }

    @Test
    @DisplayName("MongoDB statements are createIndex with each key's direction")
    void mongo() {
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", "status"))
                .isEqualTo("db.orders.createIndex({ \"status\": 1 });");
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", List.of(
                new IndexSpec.KeyPart("status", 1), new IndexSpec.KeyPart("order_date", -1))))
                .isEqualTo("db.orders.createIndex({ \"status\": 1, \"order_date\": -1 });");
    }

    @Test
    @DisplayName("qualifiedColumns: a column qualified with its own table is stripped; another table's column gives nothing")
    void qualifiedColumns() {
        assertThat(IndexStatements.create(Dialect.MYSQL, "customers", "customers.city"))
                .isEqualTo("CREATE INDEX idx_customers_city ON customers(city);");
        assertThat(IndexStatements.create(Dialect.MYSQL, "orders", "customers.city")).isNull();
    }

    @Test
    @DisplayName("an unknown table or column gives no statement rather than a broken one")
    void unknownGivesNull() {
        assertThat(IndexStatements.create(Dialect.MYSQL, null, "status")).isNull();
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", (String) null)).isNull();
        assertThat(IndexStatements.create(Dialect.MONGODB, "orders", List.of())).isNull();
    }
}
