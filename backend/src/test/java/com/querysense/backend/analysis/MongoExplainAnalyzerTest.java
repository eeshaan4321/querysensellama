package com.querysense.backend.analysis;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.dto.AnalysisVerdict;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.exception.MongoUnavailableException;
import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoExplainAnalyzerTest {

    private MongoExplainExecutor executor;
    private MongoExplainAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        executor = mock(MongoExplainExecutor.class);
        when(executor.indexes(anyString())).thenAnswer(inv -> MongoFixtures.indexes(inv.getArgument(0)));
        analyzer = new MongoExplainAnalyzer(new MongoShellParser(),
                new MongoPreflightValidator(new ActiveSchemaProvider()), executor, new MongoExplainParser(),
                new VerdictService(1000L));
    }

    private QueryAnalysisResult analyze(String mongosh, String fixture) {
        when(executor.explain(any(MongoQuery.class))).thenReturn(MongoFixtures.explain(fixture).explain());
        return analyzer.analyze(mongosh);
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(analyzer.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("a scanned, unindexed filter: plan figures, the field named, a verdict, and no second warning")
    void collscanEndToEnd() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-collscan");

        assertThat(result.getDatabaseType()).isEqualTo("MongoDB");
        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.getQueryType()).isEqualTo("FIND");
        assertThat(result.isHasFullTableScan()).isTrue();
        assertThat(result.isHasUnindexedFilter()).isTrue();
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getMongoShape().equalityFields()).containsExactly("city");
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.CRITICAL);
        assertThat(result.getWarnings()).noneSatisfy(w -> assertThat(w).contains("no index starts with"));
    }

    @Test
    @DisplayName("an indexed lookup is not an unindexed filter")
    void indexedLookup() {
        QueryAnalysisResult result = analyze("db.customers.find({\"email\": \"nobody@example.com\"})",
                "find-express-ixscan");

        assertThat(result.isUsesIndex()).isTrue();
        assertThat(result.isHasUnindexedFilter()).isFalse();
        assertThat(result.getFilteredColumn()).isEqualTo("email");
    }

    @Test
    @DisplayName("M1 warns about an unindexed field when the plan used a different index")
    void unindexedFieldBesideAnIndex() {
        QueryAnalysisResult result = analyze("db.customers.find({\"email\": \"x\", \"city\": \"Mumbai\"})",
                "find-express-ixscan");

        assertThat(result.isHasUnindexedFilter()).isTrue();
        assertThat(result.getFilteredColumn()).isEqualTo("city");
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w)
                .contains("'city'").contains("no index starts with"));
    }

    @Test
    @DisplayName("M2 flags an unanchored regex with today's wording")
    void unanchoredRegex() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": {\"$regex\": \"mum\"}})", "find-collscan");

        assertThat(result.isHasUnanchoredRegex()).isTrue();
        assertThat(result.getWarnings()).contains("Unanchored $regex pattern detected — "
                + "prefix with '^' to allow index usage, if an index exists on this field.");
    }

    @Test
    @DisplayName("the sort field is the order-by column")
    void sortField() {
        assertThat(analyze("db.customers.find({\"city\": \"Mumbai\"}).sort({\"name\": 1})", "find-sort")
                .getOrderByColumn()).isEqualTo("name");
    }

    @Test
    @DisplayName("count and delete keep their operation as the query type")
    void operationTypes() {
        assertThat(analyze("db.orders.countDocuments({\"status\": \"PAID\"})", "count").getQueryType())
                .isEqualTo("COUNT");
        assertThat(analyze("db.orders.deleteMany({\"status\": \"CANCELLED\"})", "delete").getQueryType())
                .isEqualTo("DELETE");
    }

    @Test
    @DisplayName("unreadable text fails with the parser's reason, names the collection, and never reaches MongoDB")
    void unparseable() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({city: )");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getTableName()).isEqualTo("customers");
        assertThat(result.getWarnings()).singleElement()
                .satisfies(w -> assertThat(w).startsWith("This MongoDB query could not be read"));
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a preflight refusal fails the analysis before MongoDB is asked anything")
    void preflightRefusal() {
        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"age\": 30})");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).singleElement().satisfies(w -> assertThat(w).contains("'age'"));
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a pipeline that writes never reaches MongoDB")
    void writePipelineNeverReachesMongo() {
        QueryAnalysisResult result = analyzer.analyze("db.orders.aggregate([{\"$out\": \"copy\"}])");

        assertThat(result.isExplainFailed()).isTrue();
        verify(executor, never()).explain(any());
        verify(executor, never()).indexes(anyString());
    }

    @Test
    @DisplayName("a collection declared but missing from mongod is UNKNOWN, naming it")
    void missingCollectionIsUnknown() {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-missing-collection");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getVerdict().getSeverity()).isEqualTo(AnalysisVerdict.Severity.UNKNOWN);
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("'customers'"));
    }

    @Test
    @DisplayName("a server error while planning fails the analysis with MongoDB's message")
    void commandError() {
        when(executor.explain(any(MongoQuery.class))).thenThrow(new MongoCommandException(
                new BsonDocument("ok", new BsonDouble(0)).append("code", new BsonInt32(2))
                        .append("errmsg", new BsonString("unknown operator: $bogus")), new ServerAddress()));

        QueryAnalysisResult result = analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})");

        assertThat(result.isExplainFailed()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w)
                .isEqualTo("MongoDB could not plan this query: unknown operator: $bogus"));
    }

    @Test
    @DisplayName("an unreachable MongoDB propagates as a 503")
    void unreachableMongoPropagates() {
        when(executor.indexes(anyString())).thenThrow(new MongoUnavailableException("MongoDB is not reachable",
                new MongoTimeoutException("Timed out")));

        assertThatThrownBy(() -> analyzer.analyze("db.customers.find({\"city\": \"Mumbai\"})"))
                .isInstanceOf(MongoUnavailableException.class);
    }

    @Test
    @DisplayName("the analysis result never serialises the shape")
    void shapeIsNotSerialised() throws Exception {
        QueryAnalysisResult result = analyze("db.customers.find({\"city\": \"Mumbai\"})", "find-collscan");

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);

        assertThat(json).doesNotContain("mongoShape").contains("executionMillis");
    }
}
