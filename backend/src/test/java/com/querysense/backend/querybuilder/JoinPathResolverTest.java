package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinPathResolverTest {

    private ActiveSchemaProvider activeSchema;
    private JoinPathResolver resolver;

    @BeforeEach
    void setUp() {
        activeSchema = new ActiveSchemaProvider();
        resolver = new JoinPathResolver(activeSchema);
    }

    @Test
    @DisplayName("one declared FK resolves, whichever order the tables are given")
    void singleEdgeResolvesInEitherDirection() {
        assertThat(resolver.resolve("orders", "customers"))
                .isInstanceOfSatisfying(JoinPathResolver.Resolved.class, r ->
                        assertThat(r.edge().fromColumn()).isEqualTo("customer_id"));
        assertThat(resolver.resolve("customers", "orders"))
                .isInstanceOf(JoinPathResolver.Resolved.class);
    }

    @Test
    @DisplayName("no declared relationship means None — a name convention is not a join")
    void unrelatedTablesResolveToNone() {
        assertThat(resolver.resolve("customers", "suppliers"))
                .isInstanceOf(JoinPathResolver.None.class);
    }

    @Test
    @DisplayName("two FKs between the same pair are Ambiguous, not first-wins")
    void twoEdgesAreAmbiguous() {
        activeSchema.publish(twoPathSchema());

        assertThat(resolver.resolve("orders", "addresses"))
                .isInstanceOfSatisfying(JoinPathResolver.Ambiguous.class, a ->
                        assertThat(a.edges()).hasSize(2));
    }

    @Test
    @DisplayName("a self-referencing FK never produces a join")
    void selfReferencingEdgeExcluded() {
        assertThat(resolver.resolve("employees", "employees"))
                .isInstanceOf(JoinPathResolver.None.class);
    }

    private static SchemaSnapshot twoPathSchema() {
        return new SchemaSnapshot(
                "upload-2", "two-path.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("orders",
                                List.of("id", "billing_address_id", "shipping_address_id")),
                        new KnownSchema.Table("addresses", List.of("id", "city"))),
                List.of(new KnownSchema.ForeignKey("orders", "billing_address_id", "addresses", "id"),
                        new KnownSchema.ForeignKey("orders", "shipping_address_id", "addresses", "id")),
                Map.of("orders", 100, "addresses", 100), Instant.now());
    }
}
