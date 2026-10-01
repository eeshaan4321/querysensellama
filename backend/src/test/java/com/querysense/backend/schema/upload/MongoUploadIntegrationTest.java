package com.querysense.backend.schema.upload;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("mongo")
class MongoUploadIntegrationTest {

    private static final int ROWS = 200;
    private static final String DATABASE = "querysense_it_" + System.nanoTime();

    private static MongoClient client;
    private static ParsedSchema demo;

    private static boolean mongoListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    static void provisionAndSeed() throws IOException {
        assumeTrue(mongoListening(), "no mongod on localhost:27017");
        client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000");
        demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
        new SchemaUploadValidator(40).validate(demo, Dialect.MONGODB);
        new MongoProvisioner(client, DATABASE).provision(demo);
        SeedReport report = new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);
        assertThat(report.warnings()).isEmpty();
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    private MongoDatabase db() {
        return client.getDatabase(DATABASE);
    }

    @Test
    @DisplayName("every demo collection holds the seeded documents, all accepted by the strict validator")
    void everyCollectionSeeded() {
        demo.tables().forEach(t ->
                assertThat(db().getCollection(t.name()).countDocuments()).as(t.name()).isEqualTo(ROWS));
    }

    @Test
    @DisplayName("collections are created with a strict, erroring $jsonSchema validator")
    void validatorIsStrict() {
        Document info = db().listCollections().filter(new Document("name", "orders")).first();
        Document options = info.get("options", Document.class);

        assertThat(options.get("validator", Document.class)).containsKey("$jsonSchema");
        assertThat(options.getString("validationLevel")).isEqualTo("strict");
        assertThat(options.getString("validationAction")).isEqualTo("error");
    }

    @Test
    @DisplayName("the server rejects a document the validator forbids")
    void validatorRejects() {
        assertThatThrownBy(() -> db().getCollection("orders").insertOne(new Document("status", "NEW")))
                .isInstanceOf(MongoWriteException.class)
                .satisfies(e -> assertThat(((MongoWriteException) e).getCode()).isEqualTo(121));
    }

    @Test
    @DisplayName("declared indexes exist, unique where declared")
    void indexesExist() {
        List<Document> indexes = db().getCollection("customers").listIndexes().into(new java.util.ArrayList<>());

        assertThat(indexes).anySatisfy(i -> {
            assertThat(i.get("key", Document.class)).isEqualTo(new Document("email", 1));
            assertThat(i.getBoolean("unique")).isTrue();
        });
        assertThat(db().getCollection("orders").listIndexes().into(new java.util.ArrayList<>()))
                .anySatisfy(i -> assertThat(i.get("key", Document.class)).isEqualTo(new Document("items.product_id", 1)));
    }

    @Test
    @DisplayName("every embedded item references a seeded product")
    void referencesResolve() {
        Set<Object> productIds = new HashSet<>();
        db().getCollection("products").find().forEach(p -> productIds.add(p.get("_id")));

        db().getCollection("orders").find().forEach(o -> o.getList("items", Document.class)
                .forEach(i -> assertThat(productIds).contains(i.get("product_id"))));
    }

    @Test
    @DisplayName("the demo's city values include Mumbai, as the live checklist expects")
    void mumbaiIsSeeded() {
        assertThat(db().getCollection("customers").distinct("city", String.class).into(new HashSet<>()))
                .contains("Mumbai");
    }
}
