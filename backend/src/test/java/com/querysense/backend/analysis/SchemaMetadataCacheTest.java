package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaMetadataCacheTest {

    @Test
    @DisplayName("publishing a MySQL schema clears the cached values, with no extra wiring")
    void mysqlPublishClearsCache() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(List.of("Mumbai"));
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        metadata.getDistinctValues("customers", "city");
        provider.publish(new SchemaSnapshot("upload-1", "a.sql", "querysense_uploads",
                List.of(new KnownSchema.Table("customers", List.of("id", "city"))),
                List.of(), Map.of(), Instant.now()));
        metadata.getDistinctValues("customers", "city");

        verify(executor, times(2)).withActiveCatalog(any());
    }

    @Test
    @DisplayName("publishing a MongoDB schema leaves the MySQL cache alone")
    void mongoPublishLeavesMysqlCache() {
        ActiveSchemaProvider provider = new ActiveSchemaProvider();
        ExplainExecutor executor = mock(ExplainExecutor.class);
        when(executor.withActiveCatalog(any())).thenReturn(List.of("Mumbai"));
        SchemaMetadataService metadata = new SchemaMetadataService(executor, provider);

        metadata.getDistinctValues("customers", "city");
        provider.resetToBuiltIn(Dialect.MONGODB);
        metadata.getDistinctValues("customers", "city");

        verify(executor, times(1)).withActiveCatalog(any());
        assertThat(metadata.dialect()).isEqualTo(Dialect.MYSQL);
    }
}
