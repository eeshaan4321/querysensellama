package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinChainTest {

    private final JoinPathResolver resolver = new JoinPathResolver(new ActiveSchemaProvider());

    private static List<String> tables(JoinPathResolver.ChainResolution resolution) {
        return ((JoinPathResolver.Chain) resolution).edges().stream()
                .map(e -> e.fromTable() + "." + e.fromColumn() + "->" + e.toTable())
                .toList();
    }

    @Test
    @DisplayName("a direct key is a one-edge chain, as today")
    void direct() {
        assertThat(tables(resolver.resolveChain("customers", List.of("orders"), Dialect.MYSQL)))
                .containsExactly("orders.customer_id->customers");
    }

    @Test
    @DisplayName("two and three hops, ordered outward from the subject")
    void hops() {
        assertThat(tables(resolver.resolveChain("employees", List.of("customers"), Dialect.MYSQL)))
                .containsExactly("orders.employee_id->employees", "orders.customer_id->customers");
        assertThat(tables(resolver.resolveChain("customers", List.of("products"), Dialect.MYSQL)))
                .containsExactly("orders.customer_id->customers", "order_items.order_id->orders",
                        "order_items.product_id->products");
    }

    @Test
    @DisplayName("no chain within three edges, and too many tables, are refused")
    void refused() {
        assertThat(resolver.resolveChain("departments", List.of("products"), Dialect.MYSQL))
                .isInstanceOf(JoinPathResolver.NoChain.class);
        assertThat(resolver.resolveChain("orders", List.of("categories", "departments"), Dialect.MYSQL))
                .isInstanceOf(JoinPathResolver.TooFar.class);
    }

    @Test
    @DisplayName("two equally short chains are ambiguous; self-references are ignored")
    void ambiguous() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("u", "u", "querysense_uploads", List.of(
                new KnownSchema.Table("orders", List.of("id", "billing_address_id", "shipping_address_id")),
                new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(new KnownSchema.ForeignKey("orders", "billing_address_id", "addresses", "id"),
                        new KnownSchema.ForeignKey("orders", "shipping_address_id", "addresses", "id")),
                Map.of(), Instant.now()));
        JoinPathResolver custom = new JoinPathResolver(schemas);

        assertThat(custom.resolveChain("orders", List.of("addresses"), Dialect.MYSQL))
                .isInstanceOfSatisfying(JoinPathResolver.AmbiguousChain.class, a -> assertThat(a.paths()).hasSize(2));
        assertThat(tables(resolver.resolveChain("employees", List.of("departments"), Dialect.MYSQL)))
                .containsExactly("employees.department_id->departments");
    }
}
