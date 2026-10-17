package com.querysense.backend.analysis;

import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Reads stored field values from the active MongoDB schema's database with distinct. */
@Component
public class MongoSchemaMetadata implements SchemaMetadata {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaMetadata.class);
    private static final int MAX_VALUES = 500;
    private static final long UNAVAILABLE_MS = 30_000;

    private final MongoClient client;
    private final ActiveSchemaProvider activeSchema;
    private final LongSupplier nowMillis;
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();
    private final Map<String, OptionalDouble> shareCache = new ConcurrentHashMap<>();
    private volatile long unavailableUntil = 0L;

    @Autowired
    public MongoSchemaMetadata(MongoClient client, ActiveSchemaProvider activeSchema) {
        this(client, activeSchema, System::currentTimeMillis);
    }

    MongoSchemaMetadata(MongoClient client, ActiveSchemaProvider activeSchema, LongSupplier nowMillis) {
        this.client = client;
        this.activeSchema = activeSchema;
        this.nowMillis = nowMillis;
        activeSchema.onSchemaChanged(dialect -> {
            if (dialect == Dialect.MONGODB) {
                distinctValueCache.clear();
                shareCache.clear();
            }
        });
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public List<String> getColumnNames(String tableName) {
        return List.of();
    }

    @Override
    public List<String> getDistinctValues(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return List.of();
        }
        String key = tableName + "." + columnName;
        List<String> cached = distinctValueCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean declared = activeSchema.findTable(Dialect.MONGODB, tableName)
                .map(t -> t.columns().contains(columnName))
                .orElse(false);
        if (!declared) {
            log.warn("Refused to load distinct values for undeclared field: {}.{}", tableName, columnName);
            List<String> empty = List.of();
            distinctValueCache.put(key, empty);
            return empty;
        }
        if (nowMillis.getAsLong() < unavailableUntil) {
            return List.of();
        }
        try {
            List<String> values = List.copyOf(readDistinct(tableName, columnName));
            distinctValueCache.put(key, values);
            return values;
        } catch (MongoTimeoutException | MongoSocketException e) {
            log.warn("MongoDB unreachable loading distinct values for {}.{}; not retrying for {} ms: {}",
                    tableName, columnName, UNAVAILABLE_MS, e.getMessage());
            unavailableUntil = nowMillis.getAsLong() + UNAVAILABLE_MS;
            return List.of();
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{} from MongoDB: {}",
                    tableName, columnName, e.getMessage());
            return List.of();
        }
    }

    @Override
    public OptionalDouble distinctShare(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return OptionalDouble.empty();
        }
        String key = tableName + "." + columnName;
        OptionalDouble cached = shareCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean declared = activeSchema.findTable(Dialect.MONGODB, tableName)
                .map(t -> t.columns().contains(columnName))
                .orElse(false);
        if (!declared || nowMillis.getAsLong() < unavailableUntil) {
            return OptionalDouble.empty();
        }
        try {
            long all = countAll(tableName);
            OptionalDouble share = all == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) countDistinct(tableName, columnName) / all);
            shareCache.put(key, share);
            return share;
        } catch (MongoTimeoutException | MongoSocketException e) {
            unavailableUntil = nowMillis.getAsLong() + UNAVAILABLE_MS;
            return OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Could not count distinct values for {}.{} in MongoDB: {}", tableName, columnName, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    long countAll(String tableName) {
        return client.getDatabase(activeSchema.catalog(Dialect.MONGODB)).getCollection(tableName).countDocuments();
    }

    long countDistinct(String tableName, String field) {
        Document first = client.getDatabase(activeSchema.catalog(Dialect.MONGODB)).getCollection(tableName)
                .aggregate(List.of(new Document("$match", new Document(field, new Document("$ne", null))),
                        new Document("$group", new Document("_id", "$" + field)),
                        new Document("$count", "n")))
                .first();
        return first == null ? 0 : ((Number) first.get("n")).longValue();
    }

    List<String> readDistinct(String tableName, String columnName) {
        List<String> values = new ArrayList<>();
        try (MongoCursor<BsonValue> cursor = client.getDatabase(activeSchema.catalog(Dialect.MONGODB))
                .getCollection(tableName)
                .distinct(columnName, BsonValue.class)
                .iterator()) {
            while (values.size() < MAX_VALUES && cursor.hasNext()) {
                BsonValue value = cursor.next();
                if (value != null && !value.isNull()) {
                    values.add(asText(value));
                }
            }
        }
        return values;
    }

    static String asText(BsonValue value) {
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isInt32()) {
            return String.valueOf(value.asInt32().getValue());
        }
        if (value.isInt64()) {
            return String.valueOf(value.asInt64().getValue());
        }
        if (value.isDouble()) {
            return String.valueOf(value.asDouble().getValue());
        }
        if (value.isBoolean()) {
            return String.valueOf(value.asBoolean().getValue());
        }
        if (value.isObjectId()) {
            return value.asObjectId().getValue().toHexString();
        }
        if (value.isDateTime()) {
            return Instant.ofEpochMilli(value.asDateTime().getValue()).toString();
        }
        return value.toString();
    }
}
