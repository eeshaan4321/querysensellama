package com.querysense.backend.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnownSchemaTest {

    @Test
    @DisplayName("orders matches the real table: id/employee_id, and no order_id or amount")
    void ordersMatchesRealSchema() {
        assertThat(KnownSchema.ORDERS.columns())
                .containsExactly("id", "customer_id", "employee_id", "order_date", "status");

        assertThat(KnownSchema.ORDERS.columns())
                .as("order_id belongs to order_items/payments, not orders")
                .doesNotContain("order_id");
        assertThat(KnownSchema.ORDERS.columns())
                .as("amount belongs to payments, not orders")
                .doesNotContain("amount");
    }

    @Test
    @DisplayName("customers matches the real table, including phone")
    void customersMatchesRealSchema() {
        assertThat(KnownSchema.CUSTOMERS.columns())
                .containsExactly("id", "name", "email", "city", "phone");
    }

    @Test
    @DisplayName("every user-facing table in the database is known, and query_history stays excluded")
    void allUserFacingTablesAreKnown() {
        assertThat(KnownSchema.tableNames())
                .containsExactlyInAnyOrder(
                        "categories", "customers", "departments", "employees",
                        "order_items", "orders", "payments", "products", "suppliers");

        assertThat(KnownSchema.tableNames())
                .as("query_history backs the History module; it is not a target for NL generation")
                .doesNotContain("query_history");
    }

    @Test
    @DisplayName("foreign keys mirror the real constraints, including the employees self-join")
    void foreignKeysMatchRealConstraints() {
        assertThat(KnownSchema.FOREIGN_KEYS).containsExactlyInAnyOrder(
                new KnownSchema.ForeignKey("employees", "department_id", "departments", "id"),
                new KnownSchema.ForeignKey("employees", "manager_id", "employees", "id"),
                new KnownSchema.ForeignKey("order_items", "order_id", "orders", "id"),
                new KnownSchema.ForeignKey("order_items", "product_id", "products", "id"),
                new KnownSchema.ForeignKey("orders", "customer_id", "customers", "id"),
                new KnownSchema.ForeignKey("orders", "employee_id", "employees", "id"),
                new KnownSchema.ForeignKey("payments", "order_id", "orders", "id"),
                new KnownSchema.ForeignKey("products", "category_id", "categories", "id"),
                new KnownSchema.ForeignKey("products", "supplier_id", "suppliers", "id"));
    }

    @Test
    @DisplayName("singularName handles -ies, so 'category' resolves to the categories table")
    void singularNameHandlesIesPlural() {
        assertThat(KnownSchema.findTable("category"))
                .as("naive 's'-stripping yields 'categorie', which no user ever types")
                .isPresent()
                .get()
                .extracting(KnownSchema.Table::name)
                .isEqualTo("categories");
    }

    @Test
    @DisplayName("detectTablesMentioned finds both tables in a join-shaped question")
    void detectTablesMentionedFindsBothSidesOfAJoin() {
        List<KnownSchema.Table> mentioned =
                KnownSchema.detectTablesMentioned("total payment amount per customer");

        assertThat(mentioned).extracting(KnownSchema.Table::name)
                .contains("payments", "customers");
    }
}
