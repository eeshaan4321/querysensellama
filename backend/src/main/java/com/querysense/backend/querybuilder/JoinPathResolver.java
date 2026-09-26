package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/** Finds the declared foreign key that joins two tables. */
@Component
public class JoinPathResolver {

    public sealed interface JoinResolution permits Resolved, None, Ambiguous {}

    public record Resolved(KnownSchema.ForeignKey edge) implements JoinResolution {}

    public record None() implements JoinResolution {}

    public record Ambiguous(List<KnownSchema.ForeignKey> edges) implements JoinResolution {}

    private final ActiveSchemaProvider activeSchema;

    public JoinPathResolver(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public JoinResolution resolve(String tableA, String tableB) {
        if (tableA == null || tableB == null) {
            return new None();
        }

        List<KnownSchema.ForeignKey> edges = activeSchema.foreignKeys(Dialect.MYSQL).stream()
                .filter(fk -> !fk.fromTable().equalsIgnoreCase(fk.toTable()))
                .filter(fk -> relates(fk, tableA, tableB))
                .toList();

        if (edges.isEmpty()) {
            return new None();
        }
        if (edges.size() > 1) {
            return new Ambiguous(edges);
        }
        return new Resolved(edges.get(0));
    }

    private boolean relates(KnownSchema.ForeignKey fk, String a, String b) {
        return (fk.fromTable().equalsIgnoreCase(a) && fk.toTable().equalsIgnoreCase(b))
                || (fk.fromTable().equalsIgnoreCase(b) && fk.toTable().equalsIgnoreCase(a));
    }
}
