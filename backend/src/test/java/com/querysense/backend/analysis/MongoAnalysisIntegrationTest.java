package com.querysense.backend.analysis;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.querysense.backend.advisor.MongoIndexAdvisor;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.SchemaRecommendation;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("mongo")
class MongoAnalysisIntegrationTest {

    private static final int ROWS = 2000;
    private static final String DATABASE = "querysense_it_analysis_" + System.nanoTime();

    private static MongoClient client;
    private static MongoExplainAnalyzer analyzer;
    private static final MongoIndexAdvisor advisor = new MongoIndexAdvisor();

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
        ParsedSchema demo = new JsonSchemaUploadParser().parse(
                new ClassPathResource("mongo/demo-collections.json").getContentAsString(StandardCharsets.UTF_8));
        new MongoProvisioner(client, DATABASE).provision(demo);
        new MongoSeeder(client, DATABASE, ROWS, 20260910L).seed(demo);

        ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
        activeSchema.publish(new SchemaSnapshot("it", "it", DATABASE, demo.toKnownTables(), demo.allForeignKeys(),
                Map.of(), Instant.now(), Dialect.MONGODB, demo.indexes(), demo.fieldTypes()));
        analyzer = new MongoExplainAnalyzer(new MongoShellParser(), new MongoPreflightValidator(activeSchema),
                new MongoExplainExecutor(client, activeSchema), new MongoExplainParser(), new VerdictService(1000L));
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("an unindexed filter: a real collection scan, a CRITICAL verdict, and createIndex on the field")
    void unindexedFilter() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})");

        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.getEstimatedRows()).isEqualTo(ROWS);
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.CRITICAL);
        assertThat(result.getVerdict().getHeadline()).contains("customers collection").contains("documents");
        assertThat(advisor.recommend(result)).extracting(SchemaRecommendation::getSuggestedAction)
                .containsExactly("db.customers.createIndex({ \"city\": 1 });");
    }

    @Test
    @DisplayName("a unique-index lookup uses the index, and the advisor stays quiet")
    void indexedLookup() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"email\": \"nobody@example.com\"})");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.getKeyUsed()).isEqualTo("email_1");
        assertThat(advisor.recommend(result)).isEmpty();
    }

    @Test
    @DisplayName("a scan with a sort collapses into one compound index")
    void scanAndSort() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})");

        assertThat(result.isUsesFilesort()).isTrue();
        assertThat(advisor.recommend(result)).extracting(SchemaRecommendation::getSuggestedAction)
                .containsExactly("db.customers.createIndex({ \"city\": 1, \"name\": 1 });");
    }

    @Test
    @DisplayName("a $group with no filter is flagged for review")
    void ungroupedFilter() {
        QueryAnalysisResult result = analyzer.analyze(
                "db.orders.aggregate([{\"$group\": {\"_id\": \"$status\", \"n\": {\"$sum\": 1}}}])");

        assertThat(advisor.recommend(result)).anySatisfy(r -> assertThat(r.getType()).isEqualTo("STRUCTURAL"));
    }

    @Test
    @DisplayName("explaining deleteMany deletes nothing")
    void deleteExplainDeletesNothing() {
        QueryAnalysisResult result = analyzer.analyze("db.orders.deleteMany({\"status\": \"CANCELLED\"})");

        assertThat(result.isExplainFailed()).isFalse();
        assertThat(client.getDatabase(DATABASE).getCollection("orders").countDocuments()).isEqualTo(ROWS);
    }

    @Test
    @DisplayName("every plan from the real server is read without an unknown-stage warning")
    void noUnknownStages() {
        for (String query : List.of(
                "db.customers.find({\"city\": \"Mumbai\"})",
                "db.orders.aggregate([{\"$match\": {\"status\": \"PAID\"}}, {\"$lookup\": {\"from\": \"customers\", "
                        + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"c\"}}])",
                "db.orders.countDocuments({\"status\": \"PAID\"})")) {
            assertThat(analyzer.analyze(query).getWarnings()).as(query)
                    .noneSatisfy(w -> assertThat(w).contains("does not read"));
        }
    }
}
