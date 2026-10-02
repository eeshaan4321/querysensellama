package com.querysense.backend.schema.upload;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

/** Fills uploaded MongoDB collections with generated documents that satisfy their $jsonSchema. */
@Component
public class MongoSeeder implements SchemaSeeding {

    private static final Logger log = LoggerFactory.getLogger(MongoSeeder.class);
    private static final int BATCH_SIZE = 1000;
    private static final int DOCUMENT_VALIDATION_FAILURE = 121;
    private static final double OMIT_RATE = 0.10;
    private static final Object NOT_GENERATED = new Object();

    private final MongoClient client;
    private final String uploadDatabase;
    private final int seedRows;
    private final long seed;

    public MongoSeeder(MongoClient client,
                       @Value("${querysense.schema.upload.mongo-database:querysense_uploads}") String uploadDatabase,
                       @Value("${querysense.schema.upload.seed-rows:100}") int seedRows,
                       @Value("${querysense.schema.upload.seed:20260910}") long seed) {
        this.client = client;
        this.uploadDatabase = uploadDatabase;
        this.seedRows = seedRows;
        this.seed = seed;
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public SeedReport seed(ParsedSchema schema) {
        return seed(schema, uploadDatabase);
    }

    public SeedReport seed(ParsedSchema schema, String database) {
        Map<String, List<Object>> pools = referencePools(schema);
        MongoDatabase db = client.getDatabase(database);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try {
            for (ParsedTable collection : schema.tables()) {
                counts.put(collection.name(), insert(db.getCollection(collection.name()), collection.name(),
                        documents(collection, pools), warnings));
            }
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw new MongoUnavailableException(
                    "MongoDB stopped answering while seeding '" + database + "'. Check that mongod is running "
                            + "and upload again.", e);
        }
        return new SeedReport(counts, warnings);
    }

    Map<String, List<Document>> generate(ParsedSchema schema) {
        Map<String, List<Object>> pools = referencePools(schema);
        Map<String, List<Document>> documents = new LinkedHashMap<>();
        for (ParsedTable collection : schema.tables()) {
            List<Document> generated = new ArrayList<>(seedRows);
            documents(collection, pools).forEachRemaining(generated::add);
            documents.put(collection.name(), generated);
        }
        return documents;
    }

    Map<String, List<Object>> referencePools(ParsedSchema schema) {
        Map<String, List<Object>> pools = new LinkedHashMap<>();
        for (ParsedTable collection : schema.tables()) {
            for (KnownSchema.ForeignKey reference : collection.foreignKeys()) {
                pool(schema, reference.toTable(), reference.toColumn(), pools, new HashSet<>());
            }
        }
        return pools;
    }

    private List<Object> pool(ParsedSchema schema, String table, String field, Map<String, List<Object>> pools,
                              Set<String> resolving) {
        List<Object> known = pools.get(table + "." + field);
        if (known != null) {
            return known;
        }
        ParsedTable target = schema.tables().stream().filter(t -> t.name().equals(table)).findFirst().orElse(null);
        if (target == null) {
            return List.of();
        }
        boolean resolvingHere = resolving.add(table);
        Map<String, List<Object>> targetPools = new HashMap<>();
        if (resolvingHere) {
            for (KnownSchema.ForeignKey reference : target.foreignKeys()) {
                targetPools.put(poolKey(reference),
                        pool(schema, reference.toTable(), reference.toColumn(), pools, resolving));
            }
            resolving.remove(table);
        }
        List<Object> values = new ArrayList<>(seedRows);
        Iterator<Document> documents = documents(target, targetPools);
        while (documents.hasNext()) {
            Object value = valueAt(documents.next(), field);
            if (value != null) {
                values.add(value);
            }
        }
        if (resolvingHere) {
            pools.put(table + "." + field, values);
        }
        return values;
    }

    private static String poolKey(KnownSchema.ForeignKey reference) {
        return reference.toTable() + "." + reference.toColumn();
    }

    private Iterator<Document> documents(ParsedTable collection, Map<String, List<Object>> pools) {
        ValueGenerator values = new ValueGenerator(seed + collection.name().hashCode());
        Random random = new Random(seed + collection.name().hashCode() + 1);
        Document jsonSchema = jsonSchemaOf(collection);
        Document idSchema = propertiesOf(jsonSchema).get("_id") instanceof Document d ? d : null;
        List<KnownSchema.ForeignKey> references = collection.foreignKeys().stream()
                .filter(r -> !pools.getOrDefault(poolKey(r), List.of()).isEmpty())
                .toList();
        List<Random> pickers = references.stream()
                .map(r -> new Random(seed + r.fromTable().hashCode() + r.fromColumn().hashCode()))
                .toList();

        return new Iterator<>() {
            private int row;

            @Override
            public boolean hasNext() {
                return row < seedRows;
            }

            @Override
            public Document next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Object id = idSchema == null
                        ? objectId(random, row)
                        : valueFor(collection, "_id", idSchema, values, random, row);
                Document document = new Document("_id", id);
                fillObject(collection, "", jsonSchema, document, values, random, row);
                for (int r = 0; r < references.size(); r++) {
                    assign(document, references.get(r).fromColumn().split("\\."), 0,
                            pools.get(poolKey(references.get(r))), pickers.get(r));
                }
                row++;
                return document;
            }
        };
    }

    private void fillObject(ParsedTable collection, String prefix, Document schema, Document target,
                            ValueGenerator values, Random random, int row) {
        List<String> required = stringList(schema.get("required"));
        for (Map.Entry<String, Object> property : propertiesOf(schema).entrySet()) {
            if (prefix.isEmpty() && "_id".equals(property.getKey())) {
                continue;
            }
            if (!(property.getValue() instanceof Document node)) {
                continue;
            }
            String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
            boolean alwaysPresent = required.contains(property.getKey()) || isUnique(collection, path);
            if (!alwaysPresent && random.nextDouble() < OMIT_RATE) {
                continue;
            }
            Object value = valueFor(collection, path, node, values, random, row);
            if (value != NOT_GENERATED) {
                target.put(property.getKey(), value);
            }
        }
    }

    private Object valueFor(ParsedTable collection, String path, Document node,
                            ValueGenerator values, Random random, int row) {
        if (node.get("enum") instanceof List<?> choices && !choices.isEmpty()) {
            return choices.get(random.nextInt(choices.size()));
        }
        return switch (typeOf(node)) {
            case "object" -> {
                Document child = new Document();
                fillObject(collection, path, node, child, values, random, row);
                yield child;
            }
            case "array" -> arrayOf(collection, path, node, values, random, row);
            case "string", "any" -> values.valueFor(column(collection, path, "VARCHAR", maxLength(node)), row, seedRows);
            case "int", "number" ->
                    ((Number) values.valueFor(column(collection, path, "INT", List.of()), row, seedRows)).intValue();
            case "long" ->
                    ((Number) values.valueFor(column(collection, path, "BIGINT", List.of()), row, seedRows)).longValue();
            case "double" ->
                    ((BigDecimal) values.valueFor(column(collection, path, "DOUBLE", List.of()), row, seedRows)).doubleValue();
            case "decimal" ->
                    new Decimal128((BigDecimal) values.valueFor(column(collection, path, "DECIMAL", List.of()), row, seedRows));
            case "bool" -> values.valueFor(column(collection, path, "BOOLEAN", List.of()), row, seedRows);
            case "date" -> new Date(((java.util.Date) values.valueFor(
                    column(collection, path, "DATETIME", List.of()), row, seedRows)).getTime());
            case "objectId" -> objectId(random, row);
            default -> NOT_GENERATED;
        };
    }

    private Object arrayOf(ParsedTable collection, String path, Document node,
                           ValueGenerator values, Random random, int row) {
        Document items = node.get("items") instanceof Document d ? d : new Document();
        int size = 1 + random.nextInt(4);
        List<Object> elements = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object element = valueFor(collection, path, items, values, random, row);
            if (element == NOT_GENERATED) {
                return NOT_GENERATED;
            }
            elements.add(element);
        }
        return elements;
    }

    static String typeOf(Document node) {
        Object declared = node.containsKey("bsonType") ? node.get("bsonType") : node.get("type");
        if (declared instanceof String s) {
            return JsonSchemaUploadParser.normalise(s);
        }
        if (declared instanceof List<?> list) {
            for (Object t : list) {
                if (t instanceof String s && !"null".equals(s)) {
                    return JsonSchemaUploadParser.normalise(s);
                }
            }
        }
        if (node.containsKey("properties")) {
            return "object";
        }
        if (node.containsKey("items")) {
            return "array";
        }
        return "any";
    }

    private static ParsedColumn column(ParsedTable collection, String path, String sqlType, List<String> args) {
        String name = path.substring(path.lastIndexOf('.') + 1);
        return new ParsedColumn(name, sqlType, args, true, false, isUnique(collection, path), "_id".equals(path));
    }

    private static boolean isUnique(ParsedTable collection, String path) {
        return collection.columns().stream().anyMatch(c -> c.name().equals(path) && c.unique());
    }

    private static List<String> maxLength(Document node) {
        return node.get("maxLength") instanceof Number n ? List.of(String.valueOf(n.intValue())) : List.of();
    }

    private static ObjectId objectId(Random random, int row) {
        return new ObjectId(ByteBuffer.allocate(12).putInt(1_700_000_000 + row).putLong(random.nextLong()).array());
    }

    private static Document jsonSchemaOf(ParsedTable collection) {
        return collection.validator() != null && collection.validator().get("$jsonSchema") instanceof Document d
                ? d
                : new Document();
    }

    private static Document propertiesOf(Document schema) {
        return schema.get("properties") instanceof Document d ? d : new Document();
    }

    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static Object valueAt(Document document, String path) {
        Object current = document;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Document d)) {
                return null;
            }
            current = d.get(segment);
        }
        return current;
    }

    private static void assign(Object node, String[] segments, int depth, List<Object> pool, Random picker) {
        if (node instanceof List<?> list) {
            for (Object element : list) {
                assign(element, segments, depth, pool, picker);
            }
            return;
        }
        if (!(node instanceof Document document) || !document.containsKey(segments[depth])) {
            return;
        }
        if (depth < segments.length - 1) {
            assign(document.get(segments[depth]), segments, depth + 1, pool, picker);
            return;
        }
        if (document.get(segments[depth]) instanceof List<?> list) {
            List<Object> replaced = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                replaced.add(pool.get(picker.nextInt(pool.size())));
            }
            document.put(segments[depth], replaced);
        } else {
            document.put(segments[depth], pool.get(picker.nextInt(pool.size())));
        }
    }

    private int insert(MongoCollection<Document> collection, String name, Iterator<Document> documents,
                       List<String> warnings) {
        int inserted = 0;
        int generated = 0;
        String firstFailure = null;
        while (documents.hasNext()) {
            List<Document> batch = new ArrayList<>(BATCH_SIZE);
            while (documents.hasNext() && batch.size() < BATCH_SIZE) {
                batch.add(documents.next());
            }
            generated += batch.size();
            try {
                collection.insertMany(batch, new InsertManyOptions().ordered(false));
                inserted += batch.size();
            } catch (MongoBulkWriteException e) {
                for (BulkWriteError error : e.getWriteErrors()) {
                    if (error.getCode() == DOCUMENT_VALIDATION_FAILURE) {
                        throw validationFailure(name, error);
                    }
                }
                inserted += e.getWriteResult().getInsertedCount();
                if (firstFailure == null && !e.getWriteErrors().isEmpty()) {
                    firstFailure = e.getWriteErrors().get(0).getMessage();
                }
            }
        }
        if (inserted < generated) {
            warnings.add("Seeded only " + inserted + " of " + generated + " documents into '" + name + "'"
                    + (firstFailure == null ? "." : " -- " + firstFailure + ".")
                    + " Document estimates for this collection describe what was actually inserted.");
        }
        log.info("Seeded {} document(s) into '{}'", inserted, name);
        return inserted;
    }

    private static SchemaUploadException validationFailure(String collection, BulkWriteError error) {
        String field = offendingField(error.getDetails());
        return new SchemaUploadException(
                "MongoDB's validator rejected a seeded document in '" + collection + "'"
                        + (field == null ? "" : " at the field '" + field + "'")
                        + ". Seeded values follow bsonType, enum, required and maxLength, but not pattern, "
                        + "ranges or minimum lengths -- loosen that rule in the upload, or remove it, and upload again.");
    }

    static String offendingField(BsonValue details) {
        if (details == null) {
            return null;
        }
        if (details.isDocument()) {
            BsonDocument document = details.asDocument();
            if (document.isString("propertyName")) {
                return document.getString("propertyName").getValue();
            }
            if (document.isArray("missingProperties") && !document.getArray("missingProperties").isEmpty()) {
                return document.getArray("missingProperties").get(0).asString().getValue();
            }
            for (BsonValue value : document.values()) {
                String found = offendingField(value);
                if (found != null) {
                    return found;
                }
            }
        } else if (details.isArray()) {
            for (BsonValue value : details.asArray()) {
                String found = offendingField(value);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
