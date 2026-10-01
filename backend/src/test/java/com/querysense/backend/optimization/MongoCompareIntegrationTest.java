package com.querysense.backend.optimization;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.querysense.backend.analysis.MongoExplainAnalyzer;
import com.querysense.backend.analysis.MongoExplainExecutor;
import com.querysense.backend.analysis.MongoExplainParser;
import com.querysense.backend.analysis.MongoPreflightValidator;
import com.querysense.backend.analysis.PlanAnalyzer;
import com.querysense.backend.analysis.VerdictService;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.SchemaSnapshot;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import com.querysense.backend.schema.upload.MongoProvisioner;
import com.querysense.backend.schema.upload.MongoSeeder;
import com.querysense.backend.service.impl.OptimizedQueryRecorder;
import com.querysense.backend.service.impl.QueryAnalysisServiceImpl;
import com.querysense.backend.service.impl.QueryComparisonServiceImpl;
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
import static org.mockito.Mockito.mock;

@Tag("mongo")
class MongoCompareIntegrationTest {

    private static final int ROWS = 2000;
    private static final String DATABASE = "querysense_it_compare_" + System.nanoTime();

    private static MongoClient client;
    private static QueryComparisonServiceImpl compare;

    private record NoMySql() implements PlanAnalyzer {
        @Override
        public Dialect dialect() {
            return Dialect.MYSQL;
        }

        @Override
        public QueryAnalysisResult analyze(String query) {
            throw new UnsupportedOperationException();
        }
    }

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
        MongoShellParser parser = new MongoShellParser();
        MongoExplainAnalyzer analyzer = new MongoExplainAnalyzer(parser, new MongoPreflightValidator(activeSchema),
                new MongoExplainExecutor(client, activeSchema), new MongoExplainParser(), new VerdictService(1000L));
        compare = new QueryComparisonServiceImpl(
                List.of(new QueryRewriter(activeSchema), new MongoQueryRewriter(activeSchema, parser)),
                new QueryAnalysisServiceImpl(List.of(analyzer, new NoMySql())),
                mock(OptimizedQueryRecorder.class));
    }

    @AfterAll
    static void dropDatabase() {
        if (client != null) {
            client.getDatabase(DATABASE).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("RW3 on a real server: the sort after a unique match disappears from the plan")
    void sortDropped() {
        CompareResponse response = compare.compare(
                "db.customers.find({\"email\": \"nobody@example.com\"}, {\"name\": 1}).sort({\"name\": 1})", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getChanges()).extracting(RewriteChange::ruleId).containsExactly("RW3");
        assertThat(response.getOriginalAnalysis().isUsesFilesort()).isTrue();
        assertThat(response.getOptimizedAnalysis().isUsesFilesort()).isFalse();
        assertThat(response.isEstimate()).isFalse();
    }

    @Test
    @DisplayName("RW4 on a real server: the $match moves ahead of the $lookup, and MongoDB examines no more documents")
    void matchAheadOfLookup() {
        CompareResponse response = compare.compare("db.orders.aggregate([{\"$lookup\": {\"from\": \"customers\", "
                + "\"localField\": \"customer_id\", \"foreignField\": \"_id\", \"as\": \"customer\"}}, "
                + "{\"$match\": {\"status\": \"PAID\"}}])", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).startsWith("db.orders.aggregate([{\"$match\"");
        assertThat(response.getEstimatedRowsDelta()).isNotNull().isLessThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("RW2 on a real server: $or becomes $in and both plans are read")
    void orToIn() {
        CompareResponse response = compare.compare("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, "
                + "{\"city\": \"Delhi\"}]}, {\"name\": 1})", "MongoDB");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).contains("\"$in\"");
        assertThat(response.getEstimatedRowsDelta()).isNotNull();
        assertThat(response.getSummary()).startsWith("1 rewrite rule applied. MongoDB examined");
    }
}
