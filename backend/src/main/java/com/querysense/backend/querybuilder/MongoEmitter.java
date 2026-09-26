package com.querysense.backend.querybuilder;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Writes a QueryIntent as a mongosh find, countDocuments, deleteMany or aggregate. */
@Component
public class MongoEmitter implements QueryEmitter {

    private static final JsonWriterSettings JSON =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final ActiveSchemaProvider activeSchema;

    public MongoEmitter(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public String emit(QueryIntent intent) {
        String target = "db." + intent.fromTable();
        Document filter = filter(intent, intent.where());

        if (intent.verb() == QueryIntent.Verb.DELETE) {
            return target + ".deleteMany(" + json(filter) + ")";
        }

        List<String> unwinds = intent.hasAggregate() ? embeddedArraysUsed(intent) : List.of();
        boolean bareCount = isBareCount(intent);

        if (intent.join() == null && unwinds.isEmpty() && intent.having().isEmpty()) {
            if (bareCount) {
                return target + ".countDocuments(" + json(filter) + ")";
            }
            if (!intent.hasAggregate()) {
                return find(target, intent, filter);
            }
        }

        return target + ".aggregate(" + pipeline(intent, filter, unwinds, bareCount).stream()
                .map(this::json)
                .collect(Collectors.joining(", ", "[", "]")) + ")";
    }

    private String find(String target, QueryIntent intent, Document filter) {
        StringBuilder query = new StringBuilder(target).append(".find(").append(json(filter));
        Document projection = projection(intent);
        if (!projection.isEmpty()) {
            query.append(", ").append(json(projection));
        }
        query.append(")");
        if (!intent.orderBy().isEmpty()) {
            query.append(".sort(").append(json(sort(intent))).append(")");
        }
        if (intent.limit() != null) {
            query.append(".limit(").append(intent.limit()).append(")");
        }
        return query.toString();
    }

    private List<Document> pipeline(QueryIntent intent, Document filter, List<String> unwinds, boolean bareCount) {
        List<Document> stages = new ArrayList<>();
        if (!filter.isEmpty()) {
            stages.add(new Document("$match", filter));
        }
        if (intent.join() != null) {
            QueryIntent.JoinSpec join = intent.join();
            stages.add(new Document("$lookup", new Document("from", join.table())
                    .append("localField", field(intent, join.left()))
                    .append("foreignField", join.right().column())
                    .append("as", join.table())));
            stages.add(new Document("$unwind", "$" + join.table()));
        }
        unwinds.forEach(array -> stages.add(new Document("$unwind", "$" + array)));

        if (bareCount && intent.having().isEmpty()) {
            stages.add(new Document("$count", "count"));
            return stages;
        }

        if (intent.hasAggregate()) {
            List<QueryIntent.ColumnRef> keys = groupKeys(intent);
            Document group = new Document("_id", groupId(intent, keys));
            Set<String> selectedAliases = new LinkedHashSet<>();
            for (QueryIntent.SelectItem item : intent.selectItems()) {
                if (item.operand() instanceof QueryIntent.Aggregate agg) {
                    String a = alias(intent, agg);
                    group.append(a, accumulator(intent, agg));
                    selectedAliases.add(a);
                }
            }
            Set<String> havingOnlyAliases = new LinkedHashSet<>();
            for (QueryIntent.Predicate p : intent.having()) {
                QueryIntent.Aggregate agg = (QueryIntent.Aggregate) p.lhs();
                String a = alias(intent, agg);
                if (!group.containsKey(a)) {
                    group.append(a, accumulator(intent, agg));
                }
                if (!selectedAliases.contains(a)) {
                    havingOnlyAliases.add(a);
                }
            }
            stages.add(new Document("$group", group));
            if (!intent.having().isEmpty()) {
                Document having = matchDocument(intent.having(), p -> alias(intent, (QueryIntent.Aggregate) p.lhs()));
                stages.add(new Document("$match", having));
                if (!havingOnlyAliases.isEmpty()) {
                    Document project = new Document();
                    havingOnlyAliases.forEach(a -> project.append(a, 0));
                    stages.add(new Document("$project", project));
                }
            }
            if (!intent.orderBy().isEmpty()) {
                Document sort = new Document();
                for (QueryIntent.OrderItem o : intent.orderBy()) {
                    sort.append(groupedPath(intent, keys, o.column()), direction(o));
                }
                stages.add(new Document("$sort", sort));
            }
            if (intent.limit() != null) {
                stages.add(new Document("$limit", intent.limit()));
            }
            return stages;
        }

        if (!intent.orderBy().isEmpty()) {
            stages.add(new Document("$sort", sort(intent)));
        }
        if (intent.limit() != null) {
            stages.add(new Document("$limit", intent.limit()));
        }
        Document projection = projection(intent);
        if (!projection.isEmpty()) {
            stages.add(new Document("$project", projection));
        }
        return stages;
    }

    private Document filter(QueryIntent intent, List<QueryIntent.Predicate> predicates) {
        for (QueryIntent.Predicate p : predicates) {
            requireFilterable(intent, (QueryIntent.ColumnRef) p.lhs());
        }
        return matchDocument(predicates, p -> field(intent, (QueryIntent.ColumnRef) p.lhs()));
    }

    private void requireFilterable(QueryIntent intent, QueryIntent.ColumnRef ref) {
        if (ref.table() != null && !ref.table().equals(intent.fromTable())) {
            throw new UnexpressibleIntentException("A filter on " + ref.table() + "." + ref.column()
                    + " needs the joined collection, which does not exist yet at $match time;"
                    + " MongoDB cannot match on it before the $lookup that produces it.");
        }
        if (!intent.hasAggregate()) {
            return;
        }
        String field = field(intent, ref);
        for (String array : embeddedArrays(intent)) {
            if (field.startsWith(array + ".")) {
                throw new UnexpressibleIntentException("A filter on " + field
                        + " targets an element inside the embedded array '" + array
                        + "', which MongoDB cannot match per-element before the $unwind that separates them.");
            }
        }
    }

    private Document matchDocument(List<QueryIntent.Predicate> predicates,
                                    Function<QueryIntent.Predicate, String> keyOf) {
        Document out = new Document();
        List<Document> repeated = new ArrayList<>();
        for (QueryIntent.Predicate p : predicates) {
            String key = keyOf.apply(p);
            Object condition = condition(p);
            if (out.containsKey(key)) {
                repeated.add(new Document(key, condition));
            } else {
                out.append(key, condition);
            }
        }
        if (repeated.isEmpty()) {
            return out;
        }
        List<Document> all = new ArrayList<>();
        out.forEach((k, v) -> all.add(new Document(k, v)));
        all.addAll(repeated);
        return new Document("$and", all);
    }

    private static Object condition(QueryIntent.Predicate p) {
        List<Object> values = p.values().stream().map(QueryIntent.Literal::value).toList();
        return switch (p.op()) {
            case EQ -> values.get(0);
            case IN -> new Document("$in", values);
            case GT -> new Document("$gt", values.get(0));
            case GTE -> new Document("$gte", values.get(0));
            case LT -> new Document("$lt", values.get(0));
            case LTE -> new Document("$lte", values.get(0));
        };
    }

    private Document projection(QueryIntent intent) {
        Document projection = new Document();
        for (QueryIntent.SelectItem item : intent.selectItems()) {
            if (item.operand() instanceof QueryIntent.ColumnRef ref) {
                projection.append(field(intent, ref), 1);
            }
        }
        if (!projection.isEmpty() && !projection.containsKey("_id")) {
            projection.append("_id", 0);
        }
        return projection;
    }

    private Document sort(QueryIntent intent) {
        Document sort = new Document();
        intent.orderBy().forEach(o -> sort.append(field(intent, o.column()), direction(o)));
        return sort;
    }

    private static int direction(QueryIntent.OrderItem o) {
        return o.direction() == QueryIntent.Direction.DESC ? -1 : 1;
    }

    private static boolean isBareCount(QueryIntent intent) {
        return intent.selectItems().size() == 1
                && intent.selectItems().get(0).operand() instanceof QueryIntent.Aggregate agg
                && agg.fn() == QueryIntent.Fn.COUNT
                && agg.column() == null;
    }

    private static List<QueryIntent.ColumnRef> groupKeys(QueryIntent intent) {
        return intent.selectItems().stream()
                .map(QueryIntent.SelectItem::operand)
                .filter(o -> o instanceof QueryIntent.ColumnRef)
                .map(o -> (QueryIntent.ColumnRef) o)
                .toList();
    }

    private Object groupId(QueryIntent intent, List<QueryIntent.ColumnRef> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        if (keys.size() == 1) {
            return "$" + field(intent, keys.get(0));
        }
        Document id = new Document();
        keys.forEach(k -> id.append(alias(intent, k), "$" + field(intent, k)));
        return id;
    }

    private String groupedPath(QueryIntent intent, List<QueryIntent.ColumnRef> keys, QueryIntent.ColumnRef column) {
        String wanted = field(intent, column);
        boolean grouped = keys.stream().anyMatch(k -> field(intent, k).equals(wanted));
        if (!grouped) {
            throw new UnexpressibleIntentException("The question sorts by " + column.column()
                    + " after grouping, and a grouped MongoDB result no longer has that field.");
        }
        return keys.size() == 1 ? "_id" : "_id." + alias(intent, column);
    }

    private Document accumulator(QueryIntent intent, QueryIntent.Aggregate agg) {
        if (agg.fn() == QueryIntent.Fn.COUNT) {
            if (agg.column() == null || "_id".equals(agg.column().column())) {
                return new Document("$sum", 1);
            }
            String field = field(intent, agg.column());
            Document ifNull = new Document("$ifNull", Arrays.asList("$" + field, null));
            Document eq = new Document("$eq", Arrays.asList(ifNull, null));
            Document cond = new Document("$cond", Arrays.asList(eq, 0, 1));
            return new Document("$sum", cond);
        }
        String operator = "$" + agg.fn().name().toLowerCase();
        return new Document(operator, "$" + field(intent, agg.column()));
    }

    private String alias(QueryIntent intent, QueryIntent.ColumnRef ref) {
        return field(intent, ref).replace('.', '_');
    }

    private String alias(QueryIntent intent, QueryIntent.Aggregate agg) {
        if (agg.column() == null) {
            return "count";
        }
        return agg.fn().name().toLowerCase() + "_" + alias(intent, agg.column());
    }

    private static String field(QueryIntent intent, QueryIntent.ColumnRef ref) {
        boolean base = ref.table() == null || ref.table().equals(intent.fromTable());
        return base ? ref.column() : ref.table() + "." + ref.column();
    }

    private List<String> embeddedArrays(QueryIntent intent) {
        return activeSchema.findTable(Dialect.MONGODB, intent.fromTable())
                .map(KnownSchema.Table::embeddedArrays)
                .orElse(List.of());
    }

    private List<String> embeddedArraysUsed(QueryIntent intent) {
        List<String> arrays = embeddedArrays(intent);
        Set<String> used = new LinkedHashSet<>();
        for (QueryIntent.SelectItem item : intent.selectItems()) {
            QueryIntent.ColumnRef ref = switch (item.operand()) {
                case QueryIntent.ColumnRef c -> c;
                case QueryIntent.Aggregate a -> a.column();
                case QueryIntent.Star s -> null;
            };
            if (ref == null) {
                continue;
            }
            String field = field(intent, ref);
            arrays.stream().filter(a -> field.startsWith(a + ".")).forEach(used::add);
        }
        return List.copyOf(used);
    }

    private String json(Document document) {
        return document.toJson(JSON);
    }
}
