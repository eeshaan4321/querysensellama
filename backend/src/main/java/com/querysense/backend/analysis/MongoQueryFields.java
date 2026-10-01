package com.querysense.backend.analysis;

import com.querysense.backend.dto.MongoQueryShape;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.IndexSpec;
import org.bson.BsonRegularExpression;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads field paths out of a parsed MongoDB query: every one for preflight, and the leading filter and sort, classified, for the advisor. */
public final class MongoQueryFields {

    public record Lookup(String from, String localField, String foreignField, boolean fromBase) {
    }

    private static final Set<String> SHAPE_PRESERVING = Set.of("$match", "$sort", "$limit", "$skip");
    private static final Set<String> EXPRESSION_STAGES = Set.of(
            "$group", "$project", "$addFields", "$set", "$bucket", "$sortByCount");
    private static final Set<String> WRITE_STAGES = Set.of("$out", "$merge");
    private static final Set<String> LOGICAL = Set.of("$and", "$or", "$nor");
    private static final Set<String> RANGE_OPERATORS = Set.of(
            "$gt", "$gte", "$lt", "$lte", "$ne", "$nin", "$exists", "$type", "$not");

    private MongoQueryFields() {
    }

    public static boolean writesOutput(MongoQuery query) {
        return pipelineOf(query).stream().anyMatch(stage -> WRITE_STAGES.contains(stageName(stage)));
    }

    public static List<String> referencedPaths(MongoQuery query) {
        Set<String> paths = new LinkedHashSet<>();
        if (query.operation() != MongoQuery.Operation.AGGREGATE) {
            filterPaths(query.filter(), "", paths);
            if (query.projection() != null) {
                query.projection().forEach((key, value) -> {
                    if (included(value)) {
                        paths.add(key);
                    }
                });
            }
            if (query.sort() != null) {
                paths.addAll(query.sort().keySet());
            }
            return List.copyOf(paths);
        }
        for (Document stage : pipelineOf(query)) {
            String name = stageName(stage);
            Object body = stage.get(name);
            if ("$match".equals(name)) {
                filterPaths(asDocument(body), "", paths);
            } else if ("$sort".equals(name)) {
                paths.addAll(asDocument(body).keySet());
            } else if (!"$limit".equals(name) && !"$skip".equals(name)) {
                if (EXPRESSION_STAGES.contains(name)) {
                    expressionPaths(body, paths);
                    if ("$project".equals(name)) {
                        asDocument(body).forEach((key, value) -> {
                            if (included(value)) {
                                paths.add(key);
                            }
                        });
                    }
                } else if ("$unwind".equals(name)) {
                    expressionPaths(body instanceof Document d ? d.get("path") : body, paths);
                }
                break;
            }
        }
        return List.copyOf(paths);
    }

    public static List<Lookup> lookups(MongoQuery query) {
        List<Lookup> out = new ArrayList<>();
        boolean fromBase = true;
        for (Document stage : pipelineOf(query)) {
            String name = stageName(stage);
            if ("$lookup".equals(name) && stage.get(name) instanceof Document body) {
                out.add(new Lookup(text(body.get("from")), text(body.get("localField")),
                        text(body.get("foreignField")), fromBase));
            }
            if (!SHAPE_PRESERVING.contains(name)) {
                fromBase = false;
            }
        }
        return out;
    }

    public static MongoQueryShape shape(MongoQuery query, Set<String> leadingIndexFields) {
        List<Document> filters = new ArrayList<>();
        Document sort = null;
        boolean groupsWithoutIndexedMatch = false;
        if (query.operation() == MongoQuery.Operation.AGGREGATE) {
            boolean leading = true;
            boolean indexedMatch = false;
            boolean seenGroup = false;
            for (Document stage : pipelineOf(query)) {
                String name = stageName(stage);
                if ("$group".equals(name) && !seenGroup) {
                    seenGroup = true;
                    groupsWithoutIndexedMatch = !indexedMatch;
                }
                if ("$match".equals(name)) {
                    Document match = asDocument(stage.get(name));
                    if (!seenGroup && match.keySet().stream().anyMatch(leadingIndexFields::contains)) {
                        indexedMatch = true;
                    }
                    if (leading) {
                        filters.add(match);
                    }
                } else if ("$sort".equals(name)) {
                    if (leading && sort == null) {
                        sort = asDocument(stage.get(name));
                    }
                } else if (!SHAPE_PRESERVING.contains(name)) {
                    leading = false;
                }
            }
        } else {
            if (query.filter() != null) {
                filters.add(query.filter());
            }
            sort = query.sort();
        }

        Set<String> equality = new LinkedHashSet<>();
        Set<String> range = new LinkedHashSet<>();
        Set<String> regex = new LinkedHashSet<>();
        filters.forEach(f -> classify(f, equality, range, regex));
        List<IndexSpec.KeyPart> sortKeys = new ArrayList<>();
        if (sort != null) {
            for (Map.Entry<String, Object> entry : sort.entrySet()) {
                if (entry.getValue() instanceof Number direction) {
                    sortKeys.add(new IndexSpec.KeyPart(entry.getKey(), direction.intValue() < 0 ? -1 : 1));
                }
            }
        }
        return new MongoQueryShape(query.collection(), List.copyOf(equality), List.copyOf(sortKeys),
                List.copyOf(range), List.copyOf(regex), groupsWithoutIndexedMatch);
    }

    private static void classify(Document filter, Set<String> equality, Set<String> range, Set<String> regex) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("$and".equals(key) && value instanceof List<?> clauses) {
                clauses.stream().filter(Document.class::isInstance).map(Document.class::cast)
                        .forEach(c -> classify(c, equality, range, regex));
                continue;
            }
            if (key.startsWith("$")) {
                continue;
            }
            if (value instanceof Pattern || value instanceof BsonRegularExpression) {
                (anchored(value) ? range : regex).add(key);
            } else if (value instanceof Document ops && ops.keySet().stream().anyMatch(k -> k.startsWith("$"))) {
                if (ops.containsKey("$regex")) {
                    (anchored(ops.get("$regex")) ? range : regex).add(key);
                } else if (ops.containsKey("$eq") || ops.containsKey("$in")) {
                    equality.add(key);
                } else if (ops.keySet().stream().anyMatch(RANGE_OPERATORS::contains)) {
                    range.add(key);
                }
            } else {
                equality.add(key);
            }
        }
    }

    private static boolean anchored(Object regex) {
        String pattern = regex instanceof Pattern p ? p.pattern()
                : regex instanceof BsonRegularExpression r ? r.getPattern()
                : String.valueOf(regex);
        return pattern.startsWith("^") || pattern.startsWith("\\A");
    }

    private static void filterPaths(Document filter, String prefix, Set<String> paths) {
        if (filter == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            if (LOGICAL.contains(key) && entry.getValue() instanceof List<?> clauses) {
                clauses.stream().filter(Document.class::isInstance).map(Document.class::cast)
                        .forEach(c -> filterPaths(c, prefix, paths));
            } else if (!key.startsWith("$")) {
                String path = prefix + key;
                paths.add(path);
                if (entry.getValue() instanceof Document ops && ops.get("$elemMatch") instanceof Document inner) {
                    filterPaths(inner, path + ".", paths);
                }
            }
        }
    }

    private static void expressionPaths(Object value, Set<String> paths) {
        if (value instanceof String s && s.startsWith("$") && !s.startsWith("$$")) {
            paths.add(s.substring(1));
        } else if (value instanceof Document document) {
            document.values().forEach(v -> expressionPaths(v, paths));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> expressionPaths(v, paths));
        }
    }

    private static boolean included(Object value) {
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.TRUE.equals(value);
    }

    private static List<Document> pipelineOf(MongoQuery query) {
        return query.operation() == MongoQuery.Operation.AGGREGATE && query.pipeline() != null
                ? query.pipeline()
                : List.of();
    }

    private static String stageName(Document stage) {
        return stage.keySet().stream().findFirst().orElse("");
    }

    private static Document asDocument(Object value) {
        return value instanceof Document document ? document : new Document();
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
