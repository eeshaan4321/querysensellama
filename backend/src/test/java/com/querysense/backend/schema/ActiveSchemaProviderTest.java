package com.querysense.backend.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ActiveSchemaProviderTest
 *
 * The first test is the one that matters most: with no upload performed the
 * provider must resolve to the built-in schema and hand back exactly what
 * KnownSchema's statics hand back. Every consumer switching from static
 * calls to this bean depends on that being true.
 */
class ActiveSchemaProviderTest {

    private ActiveSchemaProvider provider;

    private static final SchemaSnapshot UPLOADED = new SchemaSnapshot(
            "upload-1", "northwind.sql", "querysense_uploads",
            List.of(new KnownSchema.Table("invoices", List.of("id", "total"))),
            List.of(), Map.of("invoices", 500), Instant.now());

    @BeforeEach
    void setUp() {
        provider = new ActiveSchemaProvider();
    }

    @Test
    @DisplayName("starts on the built-in schema, with a null catalog")
    void defaultsToBuiltIn() {
        assertThat(provider.isBuiltIn()).isTrue();
        assertThat(provider.catalog()).isNull();
        assertThat(provider.tableNames()).containsExactlyElementsOf(KnownSchema.tableNames());
        assertThat(provider.foreignKeys()).isEqualTo(KnownSchema.FOREIGN_KEYS);
    }

    @Test
    @DisplayName("publish swaps the active schema and its catalog")
    void publishSwaps() {
        provider.publish(UPLOADED);

        assertThat(provider.isBuiltIn()).isFalse();
        assertThat(provider.catalog()).isEqualTo("querysense_uploads");
        assertThat(provider.tableNames()).containsExactly("invoices");
        assertThat(provider.findTable("invoice")).isPresent();
        assertThat(provider.findTable("customers")).isEmpty();
    }

    @Test
    @DisplayName("resetToBuiltIn restores the built-in schema")
    void resetRestores() {
        provider.publish(UPLOADED);
        provider.resetToBuiltIn();

        assertThat(provider.isBuiltIn()).isTrue();
        assertThat(provider.catalog()).isNull();
        assertThat(provider.findTable("customers")).isPresent();
    }

    @Test
    @DisplayName("every registered listener runs on publish and on reset")
    void notifiesListeners() {
        List<String> calls = new ArrayList<>();
        provider.onSchemaChanged(() -> calls.add("a"));
        provider.onSchemaChanged(() -> calls.add("b"));

        provider.publish(UPLOADED);
        provider.resetToBuiltIn();

        assertThat(calls).containsExactly("a", "b", "a", "b");
    }

    @Test
    @DisplayName("detectTablesMentioned reads the uploaded schema, not the built-in one")
    void detectionFollowsActiveSchema() {
        provider.publish(UPLOADED);

        assertThat(provider.detectTablesMentioned("show all invoices")).hasSize(1);
        assertThat(provider.detectTablesMentioned("show all customers")).isEmpty();
    }
}
