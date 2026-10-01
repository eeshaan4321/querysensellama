package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParseException;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.mongo.MongoShellWriter;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.BsonRegularExpression;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        MongoQuery rewritten = collapseOrChains(query, changes);
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
