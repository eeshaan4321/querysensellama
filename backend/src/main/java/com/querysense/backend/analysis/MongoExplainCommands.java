package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import org.bson.Document;

import java.util.List;

/** Builds the explain command, pinned to executionStats, for a parsed MongoDB query. */
public final class MongoExplainCommands {

    public static final String VERBOSITY = "executionStats";

    private MongoExplainCommands() {
    }

    public static Document explain(MongoQuery query) {
        return new Document("explain", command(query)).append("verbosity", VERBOSITY);
    }

    private static Document command(MongoQuery query) {
        Document filter = query.filter() == null ? new Document() : query.filter();
        return switch (query.operation()) {
            case FIND -> {
                Document find = new Document("find", query.collection()).append("filter", filter);
                if (query.projection() != null) {
                    find.append("projection", query.projection());
                }
                if (query.sort() != null) {
                    find.append("sort", query.sort());
                }
                if (query.limit() != null) {
                    find.append("limit", query.limit());
                }
                yield find;
            }
            case AGGREGATE -> new Document("aggregate", query.collection())
                    .append("pipeline", query.pipeline() == null ? List.of() : query.pipeline())
                    .append("cursor", new Document());
            case COUNT -> new Document("count", query.collection()).append("query", filter);
            case DELETE -> new Document("delete", query.collection())
                    .append("deletes", List.of(new Document("q", filter).append("limit", 0)));
        };
    }
}
