package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveSchemaProviderTest {

    private ActiveSchemaProvider provider;

    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "northwind.sql", "querysense_uploads",
            List.of(new KnownSchema.Table("invoices", List.of("id", "total"))),
            List.of(), Map.of("invoices", 500), Instant.now());

    private static final SchemaSnapshot MONGO_UPLOADED = new SchemaSnapshot(
            "upload-2", "shop.json", "querysense_uploads",
            List.of(new KnownSchema.Table("carts", List.of("_id", "total"))),
            List.of(), Map.of("carts", 50), Instant.now(), Dialect.MONGODB, List.of());

    @BeforeEach
    void setUp() {
        provider = new ActiveSchemaProvider();
    }

    @Test
    @DisplayName("each dialect starts on its own built-in schema")
    void defaultsToBuiltInPerDialect() {
        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isTrue();
        assertThat(provider.catalog(Dialect.MYSQL)).isNull();
        assertThat(provider.tableNames(Dialect.MYSQL)).containsExactlyElementsOf(KnownSchema.tableNames());
        assertThat(provider.foreignKeys(Dialect.MYSQL)).isEqualTo(KnownSchema.FOREIGN_KEYS);

        assertThat(provider.isBuiltIn(Dialect.MONGODB)).isTrue();
        assertThat(provider.catalog(Dialect.MONGODB)).isEqualTo("querysense");
        assertThat(provider.tableNames(Dialect.MONGODB)).contains("orders").doesNotContain("order_items");
    }

    @Test
    @DisplayName("publish swaps only the uploaded snapshot's own dialect")
    void publishSwapsOneSlot() {
        provider.publish(UPLOADED);

        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isFalse();
        assertThat(provider.catalog(Dialect.MYSQL)).isEqualTo("querysense_uploads");
        assertThat(provider.findTable(Dialect.MYSQL, "invoice")).isPresent();
        assertThat(provider.findTable(Dialect.MYSQL, "customers")).isEmpty();
        assertThat(provider.isBuiltIn(Dialect.MONGODB)).isTrue();

        provider.publish(MONGO_UPLOADED);

        assertThat(provider.tableNames(Dialect.MONGODB)).containsExactly("carts");
        assertThat(provider.tableNames(Dialect.MYSQL)).containsExactly("invoices");
    }

    @Test
    @DisplayName("reset restores one dialect and leaves the other alone")
    void resetRestoresOneSlot() {
        provider.publish(UPLOADED);
        provider.publish(MONGO_UPLOADED);

        provider.resetToBuiltIn(Dialect.MYSQL);

        assertThat(provider.isBuiltIn(Dialect.MYSQL)).isTrue();
        assertThat(provider.findTable(Dialect.MYSQL, "customers")).isPresent();
        assertThat(provider.tableNames(Dialect.MONGODB)).containsExactly("carts");
    }

    @Test
    @DisplayName("listeners hear which dialect changed")
    void listenersReceiveTheDialect() {
        List<Dialect> calls = new ArrayList<>();
        provider.onSchemaChanged(calls::add);

        provider.publish(UPLOADED);
        provider.publish(MONGO_UPLOADED);
        provider.resetToBuiltIn(Dialect.MONGODB);

        assertThat(calls).containsExactly(Dialect.MYSQL, Dialect.MONGODB, Dialect.MONGODB);
    }

    @Test
    @DisplayName("detectTablesMentioned reads the named dialect's schema")
    void detectionFollowsTheDialect() {
        provider.publish(UPLOADED);

        assertThat(provider.detectTablesMentioned(Dialect.MYSQL, "show all invoices")).hasSize(1);
        assertThat(provider.detectTablesMentioned(Dialect.MYSQL, "show all customers")).isEmpty();
        assertThat(provider.detectTablesMentioned(Dialect.MONGODB, "show all customers")).hasSize(1);
    }
}
