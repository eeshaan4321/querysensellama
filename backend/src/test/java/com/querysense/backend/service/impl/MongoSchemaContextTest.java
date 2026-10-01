package com.querysense.backend.service.impl;

import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSchemaContextTest {

    private static final SchemaSnapshot DEMO = KnownMongoSchema.builtIn();

    private static KnownSchema.Table table(String name) {
        return DEMO.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("mentioned collections: typed dotted paths, enums, embedded arrays, and the references between them")
    void mentionedCollections() {
        String context = MongoSchemaContext.describe(DEMO, List.of(table("orders"), table("customers")));

        assertThat(context).isEqualTo("""

                            Real MongoDB schema — use ONLY these collections and fields; never invent a collection or field name:
                            - orders: _id (objectId), customer_id (objectId), employee_id (objectId), order_date (date), status (string, one of "NEW", "PENDING", "PAID", "SHIPPED", "CANCELLED"), items.product_id (objectId), items.quantity (int), items.unit_price (decimal); embedded array: items
                            - customers: _id (objectId), name (string), email (string), city (string), phone (string)
                            - Reference: orders.customer_id → customers._id — use $lookup with localField "customer_id" and foreignField "_id" to combine them.
                """);
    }

    @Test
    @DisplayName("no collection mentioned means every collection is described")
    void allWhenNoneMentioned() {
        String context = MongoSchemaContext.describe(DEMO, List.of());

        for (KnownSchema.Table t : DEMO.tables()) {
            assertThat(context).contains("- " + t.name() + ": ");
        }
        assertThat(context).contains("- Reference: employees.manager_id → employees._id");
    }

    @Test
    @DisplayName("a reference into an embedded array names the dotted local field")
    void embeddedReference() {
        assertThat(MongoSchemaContext.describe(DEMO, List.of(table("orders"), table("products"))))
                .contains("- Reference: orders.items.product_id → products._id — use $lookup with localField "
                        + "\"items.product_id\" and foreignField \"_id\"");
    }

    @Test
    @DisplayName("fields without a declared type are listed by name alone, and an empty schema says nothing")
    void untypedAndEmpty() {
        SchemaSnapshot bare = new SchemaSnapshot("u", "u", "db", List.of(new KnownSchema.Table("logs", List.of("_id", "level"))),
                List.of(), Map.of(), Instant.EPOCH);

        assertThat(MongoSchemaContext.describe(bare, List.of())).contains("- logs: _id, level\n");
        SchemaSnapshot empty = new SchemaSnapshot("e", "e", "db", List.of(), List.of(), Map.of(), Instant.EPOCH);
        assertThat(MongoSchemaContext.describe(empty, List.of())).isEmpty();
    }
}
