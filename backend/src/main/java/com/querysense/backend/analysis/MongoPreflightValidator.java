package com.querysense.backend.analysis;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Catches unknown collections, unknown field paths and write stages before a MongoDB query reaches explain. */
@Component
public class MongoPreflightValidator {

    private final ActiveSchemaProvider activeSchema;

    public MongoPreflightValidator(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    public Optional<String> validate(MongoQuery query) {
        if (MongoQueryFields.writesOutput(query)) {
            return Optional.of("This pipeline contains $out or $merge, which write documents. QuerySense plans "
                    + "queries and never runs a write, so it was not sent to MongoDB.");
        }
        Optional<KnownSchema.Table> base = collection(query.collection());
        if (base.isEmpty()) {
            return Optional.of(unknownCollection(query.collection()));
        }
        for (String path : MongoQueryFields.referencedPaths(query)) {
            if (!declares(base.get(), path)) {
                return Optional.of(unknownField(path, query.collection()));
            }
        }
        for (MongoQueryFields.Lookup lookup : MongoQueryFields.lookups(query)) {
            if (lookup.from() == null) {
                continue;
            }
            Optional<KnownSchema.Table> from = collection(lookup.from());
            if (from.isEmpty()) {
                return Optional.of(unknownCollection(lookup.from()));
            }
            if (lookup.fromBase() && lookup.localField() != null && !declares(base.get(), lookup.localField())) {
                return Optional.of(unknownField(lookup.localField(), query.collection()));
            }
            if (lookup.foreignField() != null && !declares(from.get(), lookup.foreignField())) {
                return Optional.of(unknownField(lookup.foreignField(), lookup.from()));
            }
        }
        return Optional.empty();
    }

    private Optional<KnownSchema.Table> collection(String name) {
        return activeSchema.tables(Dialect.MONGODB).stream().filter(t -> t.name().equals(name)).findFirst();
    }

    private boolean declares(KnownSchema.Table table, String rawPath) {
        String path = Arrays.stream(rawPath.split("\\."))
                .filter(s -> !s.matches("\\d+") && !s.startsWith("$"))
                .collect(Collectors.joining("."));
        if (path.isEmpty() || "_id".equals(path) || path.startsWith("_id.")) {
            return true;
        }
        if (table.columns().contains(path) || table.embeddedArrays().contains(path)) {
            return true;
        }
        String prefix = path + ".";
        if (table.columns().stream().anyMatch(c -> c.startsWith(prefix))) {
            return true;
        }
        Map<String, String> types = activeSchema.active(Dialect.MONGODB).fieldTypesFor(table.name());
        return table.columns().stream().anyMatch(c -> path.startsWith(c + ".")
                && ("object".equals(types.get(c)) || "any".equals(types.get(c))));
    }

    private static String unknownCollection(String collection) {
        return "Collection '" + collection + "' is not in the active MongoDB schema — this query references "
                + "a collection that isn't part of the real schema.";
    }

    private static String unknownField(String path, String collection) {
        return "Field '" + path + "' does not exist on '" + collection + "' — this query references a field "
                + "that isn't part of the real schema.";
    }
}
