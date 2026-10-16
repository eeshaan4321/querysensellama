package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** The built-in demo schema: tables, columns and foreign keys. */
public final class KnownSchema {

    public record Table(String name, List<String> columns, List<String> embeddedArrays) {
        public Table(String name, List<String> columns) {
            this(name, columns, List.of());
        }

        public String singularName() {
            if (name.endsWith("ies")) {
                return name.substring(0, name.length() - 3) + "y";
            }
            return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        }
    }

    public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {}

    public static final Table CATEGORIES = new Table("categories", List.of("id", "name"));
    public static final Table CUSTOMERS = new Table(
            "customers", List.of("id", "name", "email", "city", "phone"));
    public static final Table DEPARTMENTS = new Table("departments", List.of("id", "name"));
    public static final Table EMPLOYEES = new Table(
            "employees", List.of("id", "name", "email", "department_id", "manager_id", "hire_date"));
    public static final Table ORDER_ITEMS = new Table(
            "order_items", List.of("id", "order_id", "product_id", "quantity", "unit_price"));
    public static final Table ORDERS = new Table(
            "orders", List.of("id", "customer_id", "employee_id", "order_date", "status"));
    public static final Table PAYMENTS = new Table(
            "payments", List.of("id", "order_id", "amount", "payment_date", "method"));
    public static final Table PRODUCTS = new Table(
            "products", List.of("id", "name", "price", "category_id", "supplier_id"));
    public static final Table SUPPLIERS = new Table(
            "suppliers", List.of("id", "name", "contact_email"));

    public static final List<Table> TABLES = List.of(
            CATEGORIES, CUSTOMERS, DEPARTMENTS, EMPLOYEES,
            ORDER_ITEMS, ORDERS, PAYMENTS, PRODUCTS, SUPPLIERS);

    public static final List<ForeignKey> FOREIGN_KEYS = List.of(
            new ForeignKey("employees", "department_id", "departments", "id"),
            new ForeignKey("employees", "manager_id", "employees", "id"),
            new ForeignKey("order_items", "order_id", "orders", "id"),
            new ForeignKey("order_items", "product_id", "products", "id"),
            new ForeignKey("orders", "customer_id", "customers", "id"),
            new ForeignKey("orders", "employee_id", "employees", "id"),
            new ForeignKey("payments", "order_id", "orders", "id"),
            new ForeignKey("products", "category_id", "categories", "id"),
            new ForeignKey("products", "supplier_id", "suppliers", "id")
    );

    private KnownSchema() {}

    public static List<String> tableNames() {
        return TABLES.stream().map(Table::name).toList();
    }

    public static Optional<Table> findTable(String name) {
        return SchemaLookup.findTable(TABLES, name);
    }

    public static List<Table> detectTablesMentioned(String text) {
        return SchemaLookup.detectTablesMentioned(TABLES, text);
    }

    public static final Map<String, Map<String, String>> FIELD_TYPES = Map.of(
            "categories", Map.of("id", "INT", "name", "VARCHAR"),
            "customers", Map.of("id", "INT", "name", "VARCHAR", "email", "VARCHAR", "city", "VARCHAR", "phone", "VARCHAR"),
            "departments", Map.of("id", "INT", "name", "VARCHAR"),
            "employees", Map.of("id", "INT", "name", "VARCHAR", "email", "VARCHAR", "department_id", "INT",
                    "manager_id", "INT", "hire_date", "DATE"),
            "order_items", Map.of("id", "INT", "order_id", "INT", "product_id", "INT", "quantity", "INT",
                    "unit_price", "DECIMAL"),
            "orders", Map.of("id", "INT", "customer_id", "INT", "employee_id", "INT", "order_date", "DATE",
                    "status", "VARCHAR"),
            "payments", Map.of("id", "INT", "order_id", "INT", "amount", "DECIMAL", "payment_date", "DATE",
                    "method", "VARCHAR"),
            "products", Map.of("id", "INT", "name", "VARCHAR", "price", "DECIMAL", "category_id", "INT",
                    "supplier_id", "INT"),
            "suppliers", Map.of("id", "INT", "name", "VARCHAR", "contact_email", "VARCHAR"));

    public static final Map<String, SchemaAliases.TableAliases> ALIASES = Map.of(
            "customers", new SchemaAliases.TableAliases(List.of("buyers"), Map.of()));

    public static SchemaSnapshot builtIn() {
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense demo schema (built-in)",
                null,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH,
                Dialect.MYSQL,
                List.of(),
                FIELD_TYPES,
                Map.of(),
                ALIASES,
                TABLES.stream().collect(Collectors.toMap(Table::name, t -> "id")));
    }
}
