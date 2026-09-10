package com.querysense.backend.dto;

import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.SchemaSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * What the frontend needs to describe the active schema: which one it is,
 * where its tables live, what they hold, and anything the parser skipped.
 *
 * `catalog` is null for the built-in schema. That is deliberate rather than
 * missing — the demo schema lives in whatever database spring.datasource.url
 * resolves to, which varies per machine, so naming it here would be a
 * guess. The frontend renders null as "the connected database".
 */
public record SchemaUploadResponse(
        String schemaId,
        String label,
        String catalog,
        boolean builtIn,
        List<SchemaTableView> tables,
        List<KnownSchema.ForeignKey> foreignKeys,
        List<String> warnings,
        Instant loadedAt) {

    public static SchemaUploadResponse from(SchemaSnapshot snapshot, List<String> warnings) {
        List<SchemaTableView> tables = snapshot.tables().stream()
                .map(t -> new SchemaTableView(t.name(), t.columns(), snapshot.rowCountFor(t.name())))
                .toList();

        return new SchemaUploadResponse(
                snapshot.id(),
                snapshot.label(),
                snapshot.catalog(),
                snapshot.builtIn(),
                tables,
                snapshot.foreignKeys(),
                warnings == null ? List.of() : warnings,
                snapshot.loadedAt());
    }
}
