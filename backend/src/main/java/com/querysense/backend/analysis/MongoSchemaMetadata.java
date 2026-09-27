package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads stored field values from the active MongoDB schema's database with distinct. */
@Component
public class MongoSchemaMetadata implements SchemaMetadata {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaMetadata.class);
    private static final int MAX_VALUES = 500;

    private final MongoClient client;
    private final ActiveSchemaProvider activeSchema;
    private final Map<String, List<String>> distinctValueCache = new ConcurrentHashMap<>();

    public MongoSchemaMetadata(MongoClient client, ActiveSchemaProvider activeSchema) {
        this.client = client;
        this.activeSchema = activeSchema;
        activeSchema.onSchemaChanged(dialect -> {
            if (dialect == Dialect.MONGODB) {
                distinctValueCache.clear();
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
        return distinctValueCache.computeIfAbsent(tableName + "." + columnName,
                k -> load(tableName, columnName));
    }

    private List<String> load(String tableName, String columnName) {
        boolean declared = activeSchema.findTable(Dialect.MONGODB, tableName)
                .map(t -> t.columns().contains(columnName))
                .orElse(false);
        if (!declared) {
            log.warn("Refused to load distinct values for undeclared field: {}.{}", tableName, columnName);
            return List.of();
        }
        try {
            List<String> values = new ArrayList<>();
            client.getDatabase(activeSchema.catalog(Dialect.MONGODB))
                    .getCollection(tableName)
                    .distinct(columnName, BsonValue.class)
                    .forEach(v -> {
                        if (values.size() < MAX_VALUES && v != null && !v.isNull()) {
                            values.add(asText(v));
                        }
                    });
            return List.copyOf(values);
        } catch (Exception e) {
            log.warn("Could not load distinct values for {}.{} from MongoDB: {}",
                    tableName, columnName, e.getMessage());
            return List.of();
        }
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
        return value.toString();
    }
}
