package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadataService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RuleBasedQueryBuilderSchemaTest
 *
 * Proves the rule engine reads whichever schema is active — and, just as
 * importantly, that it still answers the documented sample prompts exactly
 * as before while the built-in schema is active. The second guarantee is
 * the one a refactor is most likely to break quietly.
 */
class RuleBasedQueryBuilderSchemaTest {

    private ActiveSchemaProvider activeSchema;
    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        SchemaMetadataService metadata = mock(SchemaMetadataService.class);
        when(metadata.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        activeSchema = new ActiveSchemaProvider();
        builder = new RuleBasedQueryBuilder(metadata, activeSchema);
    }

    @Test
    @DisplayName("built-in schema still answers the documented sample prompts")
    void builtInUnchanged() {
        assertThat(builder.tryBuild("Show all customers", "MySQL"))
                .contains("SELECT * FROM customers;");
        assertThat(builder.tryBuild("Show only the name and email of customers", "MySQL"))
                .contains("SELECT name, email FROM customers;");
        assertThat(builder.tryBuild("How many orders are there", "MySQL"))
                .contains("SELECT COUNT(*) FROM orders;");
    }

    @Test
    @DisplayName("an uploaded table is recognised by the rule engine")
    void recognisesUploadedTable() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show all invoices", "MySQL"))
                .contains("SELECT * FROM invoices;");
    }

    @Test
    @DisplayName("a built-in table is NOT recognised once an upload replaces it")
    void forgetsBuiltInTablesAfterUpload() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show all customers", "MySQL")).isEmpty();
    }

    @Test
    @DisplayName("column projection reads the uploaded table's columns")
    void projectsUploadedColumns() {
        activeSchema.publish(uploaded());

        assertThat(builder.tryBuild("Show only the total and status of invoices", "MySQL"))
                .contains("SELECT total, status FROM invoices;");
    }

    private static SchemaSnapshot uploaded() {
        return new SchemaSnapshot(
                "upload-1", "test.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("invoices", List.of("id", "total", "status"))),
                List.of(), Map.of("invoices", 500), Instant.now());
    }
}
