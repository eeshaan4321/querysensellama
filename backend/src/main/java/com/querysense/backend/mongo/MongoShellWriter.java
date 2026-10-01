package com.querysense.backend.mongo;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.List;
import java.util.stream.Collectors;

/** Writes a MongoQuery back as mongosh text that MongoShellParser reads to the same query. */
public final class MongoShellWriter {

    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.SHELL).build();

    private MongoShellWriter() {
    }

    public static String write(MongoQuery query) {
        String target = "db." + query.collection();
        Document filter = query.filter() == null ? new Document() : query.filter();
        return switch (query.operation()) {
            case FIND -> {
                StringBuilder out = new StringBuilder(target).append(".find(").append(json(filter));
                if (query.projection() != null) {
                    out.append(", ").append(json(query.projection()));
                }
                out.append(")");
                if (query.sort() != null) {
                    out.append(".sort(").append(json(query.sort())).append(")");
                }
                if (query.limit() != null) {
                    out.append(".limit(").append(query.limit()).append(")");
                }
                yield out.toString();
            }
            case AGGREGATE -> target + ".aggregate("
                    + (query.pipeline() == null ? List.<Document>of() : query.pipeline()).stream()
                            .map(MongoShellWriter::json)
                            .collect(Collectors.joining(", ", "[", "]"))
                    + ")";
            case COUNT -> target + ".countDocuments(" + json(filter) + ")";
            case DELETE -> target + ".deleteMany(" + json(filter) + ")";
        };
    }

    private static String json(Document document) {
        return document.toJson(JSON);
    }
}
