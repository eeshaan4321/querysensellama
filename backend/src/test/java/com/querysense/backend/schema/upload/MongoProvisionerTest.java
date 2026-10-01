package com.querysense.backend.schema.upload;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ValidationAction;
import com.mongodb.client.model.ValidationLevel;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
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
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoProvisionerTest {

    private static final String UPLOAD = """
            {"collections": [{"name": "customers",
              "validator": {"$jsonSchema": {"required": ["email"], "properties": {"email": {"bsonType": "string"}}}},
              "indexes": [{"key": {"email": 1}, "unique": true}, {"key": {"email": 1, "_id": -1}}]}]}
            """;

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private MongoProvisioner provisioner;
    private ParsedSchema schema;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection(anyString())).thenReturn(collection);
        provisioner = new MongoProvisioner(client, "querysense_uploads");
        schema = new JsonSchemaUploadParser().parse(UPLOAD);
    }

    @Test
    @DisplayName("serves MongoDB and names its upload database")
    void identity() {
        assertThat(provisioner.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(provisioner.uploadDatabase()).isEqualTo("querysense_uploads");
    }

    @Test
    @DisplayName("drops, then creates the collection with the uploaded validator, strict and erroring")
    void createsWithStrictValidator() {
        provisioner.provision(schema);

        verify(client).getDatabase("querysense_uploads");
        InOrder order = inOrder(collection, database);
        order.verify(collection).drop();
        ArgumentCaptor<CreateCollectionOptions> options = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        order.verify(database).createCollection(eq("customers"), options.capture());

        var validation = options.getValue().getValidationOptions();
        assertThat(((Document) validation.getValidator()).get("$jsonSchema")).isInstanceOf(Document.class);
        assertThat(validation.getValidationLevel()).isEqualTo(ValidationLevel.STRICT);
        assertThat(validation.getValidationAction()).isEqualTo(ValidationAction.ERROR);
    }

    @Test
    @DisplayName("creates each index with its key order, direction and uniqueness")
    void createsIndexes() {
        provisioner.provision(schema);

        ArgumentCaptor<Bson> keys = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(collection, org.mockito.Mockito.times(2)).createIndex(keys.capture(), options.capture());

        assertThat(keys.getAllValues().get(0)).isEqualTo(new Document("email", 1));
        assertThat(options.getAllValues().get(0).isUnique()).isTrue();
        assertThat(((Document) keys.getAllValues().get(1)).keySet()).containsExactly("email", "_id");
        assertThat(((Document) keys.getAllValues().get(1)).get("_id")).isEqualTo(-1);
        assertThat(options.getAllValues().get(1).isUnique()).isFalse();
    }

    @Test
    @DisplayName("provision can target another database, for the built-in demo")
    void targetsNamedDatabase() {
        provisioner.provision(schema, "querysense");

        verify(client).getDatabase("querysense");
        verify(client, never()).getDatabase("querysense_uploads");
    }

    @Test
    @DisplayName("a collection with no validator is created without validation options")
    void noValidator() {
        provisioner.provision(new JsonSchemaUploadParser().parse("{\"collections\": [{\"name\": \"logs\"}]}"));

        ArgumentCaptor<CreateCollectionOptions> options = ArgumentCaptor.forClass(CreateCollectionOptions.class);
        verify(database).createCollection(eq("logs"), options.capture());
        assertThat(options.getValue().getValidationOptions().getValidator()).isNull();
    }

    @Test
    @DisplayName("a missing privilege names the roles the account needs")
    void unauthorizedNamesRoles() {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(13))
                .append("errmsg", new BsonString("not authorized on querysense_uploads"));
        doThrow(new MongoCommandException(response, new ServerAddress()))
                .when(database).createCollection(anyString(), any(CreateCollectionOptions.class));

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("readWrite")
                .hasMessageContaining("dbAdmin")
                .hasMessageContaining("querysense_uploads");
    }

    @Test
    @DisplayName("another command failure is reported with MongoDB's own message")
    void otherCommandFailure() {
        BsonDocument response = new BsonDocument("ok", new BsonDouble(0))
                .append("code", new BsonInt32(2))
                .append("errmsg", new BsonString("$jsonSchema keyword 'foo' is not currently supported"));
        doThrow(new MongoCommandException(response, new ServerAddress()))
                .when(database).createCollection(anyString(), any(CreateCollectionOptions.class));

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(SchemaUploadException.class)
                .hasMessageContaining("customers")
                .hasMessageContaining("'foo' is not currently supported");
    }

    @Test
    @DisplayName("an unreachable server is a MongoUnavailableException (a 503), not a 500")
    void unreachableServerIs503() {
        doThrow(new MongoTimeoutException("Timed out after 2000 ms")).when(collection).drop();

        assertThatThrownBy(() -> provisioner.provision(schema))
                .isInstanceOf(MongoUnavailableException.class)
                .hasMessageContaining("MongoDB");
    }
}
