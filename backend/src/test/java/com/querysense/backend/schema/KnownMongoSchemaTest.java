package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnownMongoSchemaTest {

    @Test
    @DisplayName("the demo is the shop domain with order items embedded in orders")
    void shopDomainWithEmbeddedItems() {
        assertThat(KnownMongoSchema.builtIn().tableNames()).containsExactlyInAnyOrder(
                "categories", "customers", "departments", "employees",
                "orders", "payments", "products", "suppliers");
        KnownSchema.Table orders = KnownMongoSchema.builtIn().tables().stream()
                .filter(t -> t.name().equals("orders")).findFirst().orElseThrow();
        assertThat(orders.embeddedArrays()).containsExactly("items");
        assertThat(orders.columns()).contains("items.product_id", "items.quantity", "items.unit_price");
    }

    @Test
    @DisplayName("every collection's first field is _id, which the rule engine uses as the count target")
    void idComesFirst() {
        KnownMongoSchema.TABLES.forEach(t -> assertThat(t.columns().get(0)).as(t.name()).isEqualTo("_id"));
    }

    @Test
    @DisplayName("the snapshot says it is MongoDB and names its database")
    void snapshotIsMongo() {
        SchemaSnapshot snapshot = KnownMongoSchema.builtIn();
        assertThat(snapshot.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(snapshot.builtIn()).isTrue();
        assertThat(snapshot.catalog()).isEqualTo("querysense");
    }

    @Test
    @DisplayName("every reference points at a declared collection and field")
    void referencesResolve() {
        for (KnownSchema.ForeignKey fk : KnownMongoSchema.FOREIGN_KEYS) {
            assertThat(KnownMongoSchema.TABLES).anySatisfy(t -> {
                assertThat(t.name()).isEqualTo(fk.fromTable());
                assertThat(t.columns()).contains(fk.fromColumn());
            });
            assertThat(KnownMongoSchema.TABLES).anySatisfy(t -> {
                assertThat(t.name()).isEqualTo(fk.toTable());
                assertThat(t.columns()).contains(fk.toColumn());
            });
        }
    }
}
