package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Runs explain and listIndexes against the active MongoDB schema's database; it never runs the query itself. */
@Component
public class MongoExplainExecutor {

    private static final int NAMESPACE_NOT_FOUND = 26;

    private final MongoClient client;
    private final ActiveSchemaProvider activeSchema;

    public MongoExplainExecutor(MongoClient client, ActiveSchemaProvider activeSchema) {
        this.client = client;
        this.activeSchema = activeSchema;
    }

    public Document explain(MongoQuery query) {
        try {
            return database().runCommand(MongoExplainCommands.explain(query));
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw unavailable(e);
        }
    }

    public List<Document> indexes(String collection) {
        try {
            return database().getCollection(collection).listIndexes().into(new ArrayList<>());
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == NAMESPACE_NOT_FOUND) {
                return List.of();
            }
            throw e;
        } catch (MongoTimeoutException | MongoSocketException e) {
            throw unavailable(e);
        }
    }

    private MongoDatabase database() {
        return client.getDatabase(activeSchema.catalog(Dialect.MONGODB));
    }

    private static MongoUnavailableException unavailable(RuntimeException e) {
        return new MongoUnavailableException("MongoDB is not reachable, so this query's plan could not be read. "
                + "Check that mongod is running at the address in spring.data.mongodb.uri. The query itself "
                + "was not run.", e);
    }
}
