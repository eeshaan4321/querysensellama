package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoExplainExecutorTest {

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private ListIndexesIterable<Document> listIndexes;
    private ActiveSchemaProvider activeSchema;
    private MongoExplainExecutor executor;
    private final MongoQuery query = new MongoShellParser().parse("db.customers.find({\"city\": \"Mumbai\"})");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        listIndexes = mock(ListIndexesIterable.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        when(collection.listIndexes()).thenReturn(listIndexes);
        activeSchema = new ActiveSchemaProvider();
        executor = new MongoExplainExecutor(client, activeSchema);
    }

    private static MongoCommandException commandError(int code) {
        return new MongoCommandException(new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(code)).append("errmsg", new BsonString("boom")), new ServerAddress());
    }

    @Test
    @DisplayName("explain runs the pinned command on the active Mongo schema's database")
    void explainRunsOnActiveDatabase() {
        Document plan = new Document("ok", 1.0);
        when(database.runCommand(any(Bson.class))).thenReturn(plan);

        assertThat(executor.explain(query)).isSameAs(plan);

        verify(client).getDatabase("querysense");
        ArgumentCaptor<Bson> command = ArgumentCaptor.forClass(Bson.class);
        verify(database).runCommand(command.capture());
        assertThat(command.getValue()).isEqualTo(MongoExplainCommands.explain(query));
    }

    @Test
    @DisplayName("after a Mongo upload, explain goes to the upload database")
    void followsTheActiveSchema() {
        activeSchema.publish(new SchemaSnapshot("u", "x.json", "querysense_uploads", List.of(), List.of(),
                Map.of(), Instant.now(), Dialect.MONGODB, List.of()));
        when(database.runCommand(any(Bson.class))).thenReturn(new Document());

        executor.explain(query);

        verify(client).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("an unreachable server during explain is a 503 naming MongoDB")
    void unreachableExplainIs503() {
        when(database.runCommand(any(Bson.class))).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThatThrownBy(() -> executor.explain(query))
                .isInstanceOf(MongoUnavailableException.class)
                .hasMessageContaining("MongoDB")
                .hasMessageContaining("not run");
    }

    @Test
    @DisplayName("an explain error from the server propagates as itself")
    void commandErrorPropagates() {
        when(database.runCommand(any(Bson.class))).thenThrow(commandError(2));

        assertThatThrownBy(() -> executor.explain(query)).isInstanceOf(MongoCommandException.class);
    }

    @Test
    @DisplayName("indexes come from listIndexes on the named collection")
    @SuppressWarnings("unchecked")
    void indexesAreListed() {
        Document emailIndex = new Document("key", new Document("email", 1)).append("name", "email_1");
        when(listIndexes.into(any())).thenAnswer(inv -> {
            Collection<Document> target = inv.getArgument(0);
            target.add(emailIndex);
            return target;
        });

        assertThat(executor.indexes("customers")).containsExactly(emailIndex);
        verify(database).getCollection("customers");
    }

    @Test
    @DisplayName("a collection missing from the database has no indexes")
    void missingCollectionHasNoIndexes() {
        when(listIndexes.into(any())).thenThrow(commandError(26));

        assertThat(executor.indexes("customers")).isEmpty();
    }

    @Test
    @DisplayName("an unreachable server during listIndexes is a 503 too")
    void unreachableIndexesIs503() {
        when(listIndexes.into(any())).thenThrow(new MongoSocketOpenException("refused", new ServerAddress()));

        assertThatThrownBy(() -> executor.indexes("customers")).isInstanceOf(MongoUnavailableException.class);
    }
}
