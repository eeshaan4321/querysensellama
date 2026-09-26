package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The built-in MongoDB demo: the shop domain as collections, with order items embedded. */
public final class KnownMongoSchema {

    public static final String CATALOG = "querysense";

    public static final List<KnownSchema.Table> TABLES = List.of(
            new KnownSchema.Table("categories", List.of("_id", "name")),
            new KnownSchema.Table("customers", List.of("_id", "name", "email", "city", "phone")),
            new KnownSchema.Table("departments", List.of("_id", "name")),
            new KnownSchema.Table("employees",
                    List.of("_id", "name", "email", "department_id", "manager_id", "hire_date")),
            new KnownSchema.Table("orders",
                    List.of("_id", "customer_id", "employee_id", "order_date", "status",
                            "items.product_id", "items.quantity", "items.unit_price"),
                    List.of("items")),
            new KnownSchema.Table("payments",
                    List.of("_id", "order_id", "amount", "payment_date", "method")),
            new KnownSchema.Table("products",
                    List.of("_id", "name", "price", "category_id", "supplier_id")),
            new KnownSchema.Table("suppliers", List.of("_id", "name", "contact_email")));

    public static final List<KnownSchema.ForeignKey> FOREIGN_KEYS = List.of(
            new KnownSchema.ForeignKey("employees", "department_id", "departments", "_id"),
            new KnownSchema.ForeignKey("employees", "manager_id", "employees", "_id"),
            new KnownSchema.ForeignKey("orders", "customer_id", "customers", "_id"),
            new KnownSchema.ForeignKey("orders", "employee_id", "employees", "_id"),
            new KnownSchema.ForeignKey("orders", "items.product_id", "products", "_id"),
            new KnownSchema.ForeignKey("payments", "order_id", "orders", "_id"),
            new KnownSchema.ForeignKey("products", "category_id", "categories", "_id"),
            new KnownSchema.ForeignKey("products", "supplier_id", "suppliers", "_id"));

    private KnownMongoSchema() {}

    public static SchemaSnapshot builtIn() {
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense MongoDB demo (built-in)",
                CATALOG,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH,
                Dialect.MONGODB,
                List.of());
    }
}
