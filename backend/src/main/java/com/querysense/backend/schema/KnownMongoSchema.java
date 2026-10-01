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

    public static final List<IndexSpec> INDEXES = List.of(
            index("customers", "email", true),
            index("employees", "email", true),
            index("employees", "department_id", false),
            index("employees", "manager_id", false),
            index("orders", "customer_id", false),
            index("orders", "employee_id", false),
            index("orders", "items.product_id", false),
            index("payments", "order_id", false),
            index("products", "category_id", false),
            index("products", "supplier_id", false));

    public static final Map<String, Map<String, String>> FIELD_TYPES = Map.of(
            "categories", Map.of("_id", "objectId", "name", "string"),
            "customers", Map.of("_id", "objectId", "name", "string", "email", "string",
                    "city", "string", "phone", "string"),
            "departments", Map.of("_id", "objectId", "name", "string"),
            "employees", Map.of("_id", "objectId", "name", "string", "email", "string",
                    "department_id", "objectId", "manager_id", "objectId", "hire_date", "date"),
            "orders", Map.of("_id", "objectId", "customer_id", "objectId", "employee_id", "objectId",
                    "order_date", "date", "status", "string", "items.product_id", "objectId",
                    "items.quantity", "int", "items.unit_price", "decimal"),
            "payments", Map.of("_id", "objectId", "order_id", "objectId", "amount", "decimal",
                    "payment_date", "date", "method", "string"),
            "products", Map.of("_id", "objectId", "name", "string", "price", "decimal",
                    "category_id", "objectId", "supplier_id", "objectId"),
            "suppliers", Map.of("_id", "objectId", "name", "string", "contact_email", "string"));

    private static IndexSpec index(String collection, String field, boolean unique) {
        return new IndexSpec(collection, List.of(new IndexSpec.KeyPart(field, 1)), unique);
    }

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
                INDEXES,
                FIELD_TYPES);
    }
}
