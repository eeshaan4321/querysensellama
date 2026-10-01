package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Captures explain and listIndexes output from the local mongod into test fixtures; run on demand only. */
@Tag("mongo")
class MongoExplainFixtureCapture {

    static final int ROWS = 2000;
    private static final String DATABASE = "querysense_fixtures";
    private static final Path EXPLAIN_DIR = Path.of("src/test/resources/mongo/explain");
    private static final Path INDEX_FILE = Path.of("src/test/resources/mongo/listIndexes/demo.json");

    static final Map<String, String> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("find-collscan", "db.customers.find({\"city\": \"Mumbai\"})");
        QUERIES.put("find-express-ixscan", "db.customers.find({\"email\": \"nobody@example.com\"})");
        QUERIES.put("find-fetch-ixscan", "db.customers.find({\"email\": {\"$gte\": \"m\"}})");
        QUERIES.put("find-sort", "db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})");
        QUERIES.put("find-idhack", "db.customers.find({\"_id\": ObjectId(\"000000000000000000000000\")})");
        QUERIES.put("find-multikey",
                "db.orders.find({\"items.product_id\": ObjectId(\"000000000000000000000000\")})");
        QUERIES.put("find-missing-collection", "db.nosuch.find({\"a\": 1})");
        QUERIES.put("agg-group", "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$group\": {\"_id\": \"$customer_id\", \"n\": {\"$sum\": 1}}}])");
        QUERIES.put("agg-lookup-sort", "db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"customer\"}}, "
                + "{\"$sort\": {\"order_date\": -1}}])");
        QUERIES.put("agg-facet", "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, "
                + "{\"$facet\": {\"n\": [{\"$count\": \"n\"}]}}])");
        QUERIES.put("count", "db.orders.countDocuments({\"status\": \"PAID\"})");
        QUERIES.put("delete", "db.orders.deleteMany({\"status\": \"CANCELLED\"})");
    }

    private static boolean mongoListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void capture() throws IOException {
        assumeTrue(Boolean.getBoolean("querysense.capture-fixtures"),
                "run with -Dquerysense.capture-fixtures=true to recapture");
        assumeTrue(mongoListening(), "no mongod on localhost:27017");

        JsonWriterSettings pretty = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build();
        MongoShellParser parser = new MongoShellParser();
        try (MongoClient client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=2000")) {
            MongoDatabase db = client.getDatabase(DATABASE);
            db.drop();
            ParsedSchema demo = new JsonSchemaUploadParser().parse(
                    new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
            new MongoProvisioner(client, DATABASE).provision(demo);
            new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);

            Files.createDirectories(EXPLAIN_DIR);
            for (Map.Entry<String, String> query : QUERIES.entrySet()) {
                Document explain = db.runCommand(MongoExplainCommands.explain(parser.parse(query.getValue())));
                Document fixture = new Document("query", query.getValue()).append("explain", explain);
                Files.writeString(EXPLAIN_DIR.resolve(query.getKey() + ".json"), fixture.toJson(pretty));
            }

            Document indexes = new Document();
            for (KnownSchema.Table table : KnownMongoSchema.TABLES) {
                indexes.append(table.name(), db.getCollection(table.name()).listIndexes().into(new ArrayList<>()));
            }
            Files.createDirectories(INDEX_FILE.getParent());
            Files.writeString(INDEX_FILE, indexes.toJson(pretty));

            db.drop();
        }
    }
}
