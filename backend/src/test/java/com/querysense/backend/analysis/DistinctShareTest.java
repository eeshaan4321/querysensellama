package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistinctShareTest {

    @Test
    @DisplayName("MySQL: distinct values over all rows, read once per schema")
    void mysql() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(new long[]{9, 10});
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        assertThat(metadata.distinctShare("customers", "name")).isEqualTo(OptionalDouble.of(0.9));
        metadata.distinctShare("customers", "name");
        verify(executor, times(1)).withActiveCatalog(any());
    }

    @Test
    @DisplayName("MySQL: an empty table or an unknown column has no share")
    void mysqlUnknown() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(new long[]{0, 0});
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        assertThat(metadata.distinctShare("customers", "name")).isEmpty();
        assertThat(metadata.distinctShare("customers", "no_such_column")).isEmpty();
    }

    @Test
    @DisplayName("MongoDB: distinct values over all documents")
    void mongo() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        provider.publish(new SchemaSnapshot("upload-m", "m", "uploads",
                List.of(new KnownSchema.Table("students", List.of("_id", "studentName"))),
                List.of(), Map.of(), Instant.now(), Dialect.MONGODB, List.of()));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(mock(MongoClient.class), provider, () -> 0L) {
            @Override
            long countAll(String table) {
                return 20;
            }

            @Override
            long countDistinct(String table, String field) {
                return 19;
            }
        };
        assertThat(metadata.distinctShare("students", "studentName")).isEqualTo(OptionalDouble.of(0.95));
        assertThat(metadata.distinctShare("students", "missing")).isEmpty();
    }
}
