package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaBinderTest {

    private SchemaBinder binder;

    @BeforeEach
    void setUp() {
        binder = new SchemaBinder(new ActiveSchemaProvider());
    }

    @Test
    @DisplayName("multi-word table beats the shorter table nested inside it")
    void orderItemsBeatsOrders() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("the underscored spelling resolves to the same table")
    void underscoredSpellingResolvesIdentically() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order_items"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("a genuine two-table question binds both")
    void bindsTwoDistinctTables() {
        assertThat(binder.bindTables(SchemaBinder.normalize("customers and their orders"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name)
                .containsExactlyInAnyOrder("customers", "orders");
    }

    @Test
    @DisplayName("singular and -ies plurals both bind")
    void singularAndIesPlural() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show every category"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name)
                .containsExactly("categories");
    }

    @Test
    @DisplayName("columns resolve exactly, by singular, and by unambiguous suffix")
    void bindsColumns() {
        KnownSchema.Table orders = KnownSchema.ORDERS;
        assertThat(binder.bindColumn(orders, "status")).contains("status");
        assertThat(binder.bindColumn(orders, "order date")).contains("order_date");
        assertThat(binder.bindColumn(orders, "nonsense")).isEmpty();
    }

    @Test
    @DisplayName("a word matching no table binds nothing")
    void unknownNounBindsNothing() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all invoices"), Dialect.MYSQL)).isEmpty();
    }

    @Test
    @DisplayName("'id' binds MongoDB's _id rather than being ambiguous among the *_id fields")
    void idBindsUnderscoreId() {
        KnownSchema.Table orders = KnownMongoSchema.TABLES.stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(binder.bindColumn(orders, "ids")).contains("_id");
    }

    @Test
    @DisplayName("a field inside an embedded document binds by its last segment")
    void dottedPathBinds() {
        KnownSchema.Table orders = KnownMongoSchema.TABLES.stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(binder.bindColumn(orders, "quantity")).contains("items.quantity");
        assertThat(binder.bindColumn(orders, "unit price")).contains("items.unit_price");
    }

    @Test
    @DisplayName("a camelCase field from an uploaded schema binds from plain words")
    void camelCaseFieldBinds() {
        KnownSchema.Table employees = new KnownSchema.Table("employees",
                List.of("_id", "fullName", "hireDate", "departmentId"));
        assertThat(binder.bindColumn(employees, "hire date")).contains("hireDate");
        assertThat(binder.bindColumn(employees, "full name")).contains("fullName");
    }

    @Test
    @DisplayName("a mixed-case column still binds from its name typed as one word")
    void mixedCaseColumnBindsAsOneWord() {
        KnownSchema.Table customers = new KnownSchema.Table("customers",
                List.of("CustomerID", "CustomerName"));
        assertThat(binder.bindColumn(customers, "customername")).contains("CustomerName");
        assertThat(binder.bindColumn(customers, "customerid")).contains("CustomerID");
    }

    @Test
    @DisplayName("table binding reads the named dialect's schema")
    void bindTablesPerDialect() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items"), Dialect.MYSQL))
                .extracting(KnownSchema.Table::name).containsExactly("order_items");
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items"), Dialect.MONGODB))
                .extracting(KnownSchema.Table::name).containsExactly("orders");
    }
}
