package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentifyingColumnsTest {

    private static final KnownSchema.Table DEPARTMENTS = new KnownSchema.Table("departments",
            List.of("department_id", "building", "department_name"));
    private static final KnownSchema.Table PROFESSORS = new KnownSchema.Table("professors",
            List.of("professor_id", "professor_name", "department_id"));
    private static final KnownSchema.Table ORDERS = new KnownSchema.Table("orders",
            List.of("order_id", "status", "placed_on"));

    private SchemaBinder binder;
    private SchemaMetadata metadata;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        schemas.publish(new SchemaSnapshot("upload-i", "i", "querysense_uploads",
                List.of(DEPARTMENTS, PROFESSORS, ORDERS),
                List.of(new KnownSchema.ForeignKey("professors", "department_id", "departments", "department_id")),
                Map.of(), Instant.now(), Dialect.MYSQL, List.of(),
                Map.of("departments", Map.of("department_id", "INT", "building", "VARCHAR", "department_name", "VARCHAR"),
                        "professors", Map.of("professor_id", "INT", "professor_name", "VARCHAR", "department_id", "INT"),
                        "orders", Map.of("order_id", "INT", "status", "VARCHAR", "placed_on", "DATE")),
                Map.of(), Map.of(), Map.of("departments", "department_id")));
        binder = new SchemaBinder(schemas);
        metadata = mock(SchemaMetadata.class);
        when(metadata.distinctShare(anyString(), anyString())).thenReturn(OptionalDouble.empty());
    }

    @Test
    @DisplayName("the first text column with mostly distinct values names the rows")
    void namingColumn() {
        when(metadata.distinctShare("departments", "building")).thenReturn(OptionalDouble.of(0.3));
        when(metadata.distinctShare("departments", "department_name")).thenReturn(OptionalDouble.of(1.0));
        assertThat(IdentifyingColumns.of(DEPARTMENTS, binder, metadata))
                .containsExactly("department_id", "department_name");
    }

    @Test
    @DisplayName("a table whose text only categorises shows its key alone")
    void categoriesOnly() {
        when(metadata.distinctShare("orders", "status")).thenReturn(OptionalDouble.of(0.2));
        assertThat(IdentifyingColumns.of(ORDERS, binder, metadata)).containsExactly("order_id");
    }

    @Test
    @DisplayName("an unknown share takes the first text column; keys and foreign keys are never labels")
    void unknownShare() {
        assertThat(IdentifyingColumns.of(PROFESSORS, binder, metadata))
                .containsExactly("professor_id", "professor_name");
    }
}
