package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaBinderTest
 *
 * The first two cases are the PROJECT.md §5 defect: "show all order items"
 * used to resolve to `orders` — the wrong table, the wrong row count, and a
 * column set that cannot answer the question — and returned it as
 * RULE_ENGINE at confidence 0.95.
 */
class SchemaBinderTest {

    private SchemaBinder binder;

    @BeforeEach
    void setUp() {
        binder = new SchemaBinder(new ActiveSchemaProvider());
    }

    @Test
    @DisplayName("multi-word table beats the shorter table nested inside it")
    void orderItemsBeatsOrders() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order items")))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("the underscored spelling resolves to the same table")
    void underscoredSpellingResolvesIdentically() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show all order_items")))
                .extracting(KnownSchema.Table::name)
                .containsExactly("order_items");
    }

    @Test
    @DisplayName("a genuine two-table question binds both")
    void bindsTwoDistinctTables() {
        assertThat(binder.bindTables(SchemaBinder.normalize("customers and their orders")))
                .extracting(KnownSchema.Table::name)
                .containsExactlyInAnyOrder("customers", "orders");
    }

    @Test
    @DisplayName("singular and -ies plurals both bind")
    void singularAndIesPlural() {
        assertThat(binder.bindTables(SchemaBinder.normalize("show every category")))
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
        assertThat(binder.bindTables(SchemaBinder.normalize("show all invoices"))).isEmpty();
    }
}
