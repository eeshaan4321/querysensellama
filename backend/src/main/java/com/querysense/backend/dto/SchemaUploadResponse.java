package com.querysense.backend.dto;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;

import java.time.Instant;
import java.util.List;

/** Description of the active schema returned by the schema endpoints. */
public record SchemaUploadResponse(
        String schemaId,
        String label,
        String catalog,
        boolean builtIn,
        List<SchemaTableView> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        List<String> warnings,
        Instant loadedAt,
        Dialect dialect,
        List<IndexSpec> indexes) {

    public static SchemaUploadResponse from(SchemaSnapshot snapshot, List<String> warnings) {
        List<SchemaTableView> tables = snapshot.tables().stream()
                .map(t -> new SchemaTableView(t.name(), t.columns(), snapshot.rowCountFor(t.name()),
                        t.embeddedArrays(), snapshot.fieldTypesFor(t.name())))
                .toList();

        return new SchemaUploadResponse(
                snapshot.id(),
                snapshot.label(),
                snapshot.catalog(),
                snapshot.builtIn(),
                tables,
                snapshot.foreignKeys(),
                warnings == null ? List.of() : warnings,
                snapshot.loadedAt(),
                snapshot.dialect(),
                snapshot.indexes());
    }
}
