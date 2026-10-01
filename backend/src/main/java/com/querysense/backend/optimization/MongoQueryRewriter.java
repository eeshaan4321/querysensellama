package com.querysense.backend.optimization;

import com.querysense.backend.analysis.MongoQueryFields;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.mongo.MongoShellWriter;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Rewrites a MongoDB query into one MongoDB can serve more cheaply that returns the same documents; a rule that cannot guarantee that declines. */
@Component
public class MongoQueryRewriter implements Rewriter {

    private final ActiveSchemaProvider activeSchema;
    private final MongoShellParser parser;

    public MongoQueryRewriter(ActiveSchemaProvider activeSchema, MongoShellParser parser) {
        this.activeSchema = activeSchema;
        this.parser = parser;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public QueryRewrite rewrite(String text) {
        if (text == null) {
            return QueryRewrite.unchanged(null);
        }
        MongoQuery query;
        try {
            query = parser.parse(text);
        } catch (MongoShellParseException e) {
            return QueryRewrite.unchanged(text);
        }
        Optional<KnownSchema.Table> table = activeSchema.tables(Dialect.MONGODB).stream()
                .filter(t -> t.name().equals(query.collection()))
                .findFirst();
        if (table.isEmpty()) {
            return QueryRewrite.unchanged(text);
        }

        List<RewriteChange> changes = new ArrayList<>();
        MongoQuery rewritten = nameTheFields(query, table.get(), changes);
        rewritten = collapseOrChains(rewritten, changes);
        rewritten = dropSortOnUniqueMatch(rewritten, changes);
        if (changes.isEmpty()) {
            return QueryRewrite.unchanged(text);
        }
        String out = MongoShellWriter.write(rewritten);
        return new QueryRewrite(text.strip().endsWith(";") ? out + ";" : out, List.copyOf(changes));
    }

    private MongoQuery collapseOrChains(MongoQuery query, List<RewriteChange> changes) {
        if (query.operation() == MongoQuery.Operation.AGGREGATE) {
            if (query.pipeline() == null) {
                return query;
            }
            List<Document> stages = new ArrayList<>();
            boolean changed = false;
            for (Document stage : query.pipeline()) {
                if (stage.get("$match") instanceof Document match) {
                    Document collapsed = collapse(match, changes);
                    if (collapsed != null) {
                        stages.add(new Document("$match", collapsed));
                        changed = true;
                        continue;
                    }
                }
                stages.add(stage);
            }
            return changed ? withPipeline(query, stages) : query;
        }
        Document collapsed = collapse(query.filter(), changes);
        return collapsed == null ? query : withFilter(query, collapsed);
    }

    private static Document collapse(Document filter, List<RewriteChange> changes) {
        if (filter == null || !(filter.get("$or") instanceof List<?> clauses) || clauses.size() < 2) {
            return null;
        }
        String field = null;
        List<Object> values = new ArrayList<>();
        for (Object clause : clauses) {
            if (!(clause instanceof Document branch) || branch.size() != 1) {
                return null;
            }
            Map.Entry<String, Object> only = branch.entrySet().iterator().next();
            if (only.getKey().startsWith("$") || (field != null && !field.equals(only.getKey()))) {
                return null;
            }
            field = only.getKey();
            Object value = only.getValue();
            if (value instanceof Document operator) {
                if (operator.size() != 1 || !operator.containsKey("$eq") || !plain(operator.get("$eq"))) {
                    return null;
                }
                value = operator.get("$eq");
            } else if (!plain(value)) {
                return null;
            }
            values.add(value);
        }
        if (filter.containsKey(field)) {
            return null;
        }
        Document out = new Document();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if ("$or".equals(entry.getKey())) {
                out.append(field, new Document("$in", values));
            } else {
                out.append(entry.getKey(), entry.getValue());
            }
        }
        changes.add(new RewriteChange("RW2",
                "Collapsed " + values.size() + " $or'd equality tests on '" + field + "' into a single $in. "
                        + "MongoDB plans $in as one index scan over several values, where an $or plans each "
                        + "branch separately. The documents returned are identical."));
        return out;
    }

    private MongoQuery nameTheFields(MongoQuery query, KnownSchema.Table table, List<RewriteChange> changes) {
        if (query.operation() != MongoQuery.Operation.FIND || query.projection() != null) {
            return query;
        }
        List<String> roots = topLevelFields(table);
        if (roots.isEmpty()) {
            return query;
        }
        Set<String> declared = new HashSet<>(roots);
        declared.add("_id");
        for (String path : MongoQueryFields.referencedPaths(query)) {
            if (!declared.contains(path.split("\\.")[0])) {
                return query;
            }
        }
        Document projection = new Document();
        roots.forEach(root -> projection.append(root, 1));
        changes.add(new RewriteChange("RW1",
                "Named the " + roots.size() + " fields of '" + table.name() + "' in a projection instead of "
                        + "returning whole documents, so an index holding those fields could answer the query "
                        + "without fetching each document. The collection's schema declares no other fields, so "
                        + "the documents returned are identical."));
        return new MongoQuery(query.collection(), query.operation(), query.filter(), projection, query.sort(),
                query.limit(), query.pipeline());
    }

    private MongoQuery dropSortOnUniqueMatch(MongoQuery query, List<RewriteChange> changes) {
        if (query.operation() != MongoQuery.Operation.FIND || query.sort() == null || query.sort().isEmpty()
                || query.filter() == null) {
            return query;
        }
        Set<String> unique = uniqueFields(query.collection());
        for (Map.Entry<String, Object> entry : query.filter().entrySet()) {
            if (unique.contains(entry.getKey()) && equality(entry.getValue())) {
                changes.add(new RewriteChange("RW3",
                        "Dropped .sort() because the filter matches '" + entry.getKey() + "' exactly and a "
                                + "unique index on it allows at most one document. Sorting one document cannot "
                                + "change the result; it only cost an in-memory sort."));
                return new MongoQuery(query.collection(), query.operation(), query.filter(), query.projection(),
                        null, query.limit(), query.pipeline());
            }
        }
        return query;
    }

    private Set<String> uniqueFields(String collection) {
        Set<String> unique = new HashSet<>(Set.of("_id"));
        for (IndexSpec index : activeSchema.active(Dialect.MONGODB).indexes()) {
            if (index.unique() && index.keys().size() == 1 && index.table().equals(collection)) {
                unique.add(index.keys().get(0).column());
            }
        }
        return unique;
    }

    private static boolean equality(Object value) {
        if (value instanceof Document operator) {
            return operator.size() == 1 && operator.containsKey("$eq") && plain(operator.get("$eq"));
        }
        return plain(value);
    }

    static List<String> topLevelFields(KnownSchema.Table table) {
        Set<String> roots = new LinkedHashSet<>();
        for (String column : table.columns()) {
            String root = column.split("\\.")[0];
            if (!"_id".equals(root)) {
                roots.add(root);
            }
        }
        return List.copyOf(roots);
    }

    static boolean plain(Object value) {
        return !(value instanceof Document) && !(value instanceof List)
                && !(value instanceof Pattern) && !(value instanceof BsonRegularExpression);
    }

    static MongoQuery withFilter(MongoQuery q, Document filter) {
        return new MongoQuery(q.collection(), q.operation(), filter, q.projection(), q.sort(), q.limit(), q.pipeline());
    }

    static MongoQuery withPipeline(MongoQuery q, List<Document> pipeline) {
        return new MongoQuery(q.collection(), q.operation(), q.filter(), q.projection(), q.sort(), q.limit(),
                List.copyOf(pipeline));
    }
}
