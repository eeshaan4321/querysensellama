package com.querysense.backend.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaLookupTest
 *
 * SchemaLookup is the matching logic lifted out of KnownSchema's static
 * methods so it can run against an uploaded schema as well as the built-in
 * one. These tests pin it to the behaviour KnownSchema had before the lift,
 * because the whole point of extracting it was that nothing observable
 * changes.
 */
class SchemaLookupTest {

    private static final KnownSchema.Table CUSTOMERS =
            new KnownSchema.Table("customers", List.of("id", "name", "city"));
    private static final KnownSchema.Table ORDER_ITEMS =
            new KnownSchema.Table("order_items", List.of("id", "order_id", "quantity"));
    private static final List<KnownSchema.Table> TABLES = List.of(CUSTOMERS, ORDER_ITEMS);

    @Test
    @DisplayName("findTable matches the plural name")
    void findsByPluralName() {
        assertThat(SchemaLookup.findTable(TABLES, "customers")).contains(CUSTOMERS);
    }

    @Test
    @DisplayName("findTable matches the singular name")
    void findsBySingularName() {
        assertThat(SchemaLookup.findTable(TABLES, "customer")).contains(CUSTOMERS);
    }

    @Test
    @DisplayName("findTable is empty for null, blank and unknown names")
    void emptyForMisses() {
        assertThat(SchemaLookup.findTable(TABLES, null)).isEmpty();
        assertThat(SchemaLookup.findTable(TABLES, "   ")).isEmpty();
        assertThat(SchemaLookup.findTable(TABLES, "invoices")).isEmpty();
    }

    @Test
    @DisplayName("detectTablesMentioned finds tables named in prose")
    void detectsMentions() {
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, "show all customers in Mumbai"))
                .containsExactly(CUSTOMERS);
    }

    @Test
    @DisplayName("detectTablesMentioned is empty for null and blank text")
    void detectsNothingInEmptyText() {
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, null)).isEmpty();
        assertThat(SchemaLookup.detectTablesMentioned(TABLES, "")).isEmpty();
    }

    @Test
    @DisplayName("a table name containing a regex metacharacter is matched literally, not as a pattern")
    void quotesIdentifiers() {
        KnownSchema.Table odd = new KnownSchema.Table("a.b", List.of("id"));
        assertThat(SchemaLookup.detectTablesMentioned(List.of(odd), "select from axb")).isEmpty();
        assertThat(SchemaLookup.detectTablesMentioned(List.of(odd), "select from a.b")).containsExactly(odd);
    }
}
