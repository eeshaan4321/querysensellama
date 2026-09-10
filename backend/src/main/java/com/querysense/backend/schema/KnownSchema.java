package com.querysense.backend.schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * KnownSchema
 *
 * WHY THIS CLASS EXISTS:
 * The previous round's fixes for column projection (Bug 7) and WHERE-clause
 * matching (Bug 8) only ever worked for the exact repro sentence tested,
 * because table/column knowledge was duplicated as one-off string literals
 * in two different places — RuleBasedQueryBuilder's own hardcoded
 * KNOWN_TABLES/COLUMN_ALIASES, and the LLM fallback service's separate live
 * information_schema lookup via SchemaMetadataService. Neither one's
 * knowledge of the schema was complete, and neither got updated when the
 * other did. This class is the single, static source of truth both now
 * read from, so a schema fact only ever needs to be stated once.
 *
 * WHY STATIC/HARDCODED RATHER THAN LIVE INTROSPECTION:
 * Matches this project's documented scope (CLAUDE.md Section 4): fixed
 * rule sets, not dynamic discovery. The schema is small and effectively
 * fixed for this MVP.
 *
 * SchemaMetadataService's live information_schema queries still exist and
 * still matter — but for a DIFFERENT job than this class: catching schema
 * DRIFT a static list can't see (SqlPreflightValidator's hallucinated-
 * column safety net), and looking up actual DATA values, which static
 * schema knowledge can't express (RuleBasedQueryBuilder's known-city-value
 * safety net for Bugs 8/10). This class is about STRUCTURE (tables,
 * columns, relationships), which is genuinely fixed for this project.
 */
public final class KnownSchema {

    public record Table(String name, List<String> columns) {
        /**
         * "orders" -> "order", "categories" -> "category". The -ies case is
         * handled explicitly because naive 's'-stripping yields "categorie",
         * a word no user types, so `category` would never match its table.
         */
        public String singularName() {
            if (name.endsWith("ies")) {
                return name.substring(0, name.length() - 3) + "y";
            }
            return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
        }
    }

    public record ForeignKey(String fromTable, String fromColumn, String toTable, String toColumn) {}

    // Transcribed from information_schema on the live `querysense` database.
    // Column order matches ordinal_position so this reads the same as DESCRIBE.
    //
    // NOTE ON A PAST DRIFT: `orders` was previously declared here as
    // (order_id, customer_id, order_date, amount, status). Neither `order_id`
    // nor `amount` is on that table — they belong to `order_items` and
    // `payments`. Every generated query touching them died at EXPLAIN with
    // MySQL error 1054 and surfaced as "Unavailable — EXPLAIN failed".
    // KnownSchemaTest pins all of this to the real schema so the next drift
    // fails at build time instead.
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

    // query_history is internal (backs the History module) — deliberately
    // excluded, since it is not a target table for user-facing NL query
    // generation.
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

    /**
     * Which known tables are mentioned by name (plural or singular) in a
     * piece of free text — used both to detect the table a rule-engine
     * request targets, and to decide what to include in OllamaService's
     * schema-grounding prompt. Best-effort word-boundary matching, not a
     * full entity-linking system.
     */
    public static List<Table> detectTablesMentioned(String text) {
        return SchemaLookup.detectTablesMentioned(TABLES, text);
    }

    /**
     * This class's contents as a swappable snapshot, for the schema-upload
     * feature: ActiveSchemaProvider starts here and returns here on reset.
     *
     * The tables and foreign keys are the same List.of(...) instances
     * declared above — this is a VIEW of the built-in schema, not a copy of
     * it, so KnownSchemaTest still pins exactly what the app uses.
     *
     * catalog is null on purpose. See SchemaSnapshot for why: the built-in
     * schema lives in whatever database spring.datasource.url resolves to,
     * which is machine-dependent, so naming it here would be a guess.
     *
     * rowCounts is empty because nothing measures the live demo tables at
     * startup; the frontend renders no count rather than a wrong one.
     */
    public static SchemaSnapshot builtIn() {
        return new SchemaSnapshot(
                SchemaSnapshot.BUILT_IN_ID,
                "QuerySense demo schema (built-in)",
                null,
                TABLES,
                FOREIGN_KEYS,
                Map.of(),
                Instant.EPOCH);
    }
}
