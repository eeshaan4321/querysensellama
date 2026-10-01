package com.querysense.backend.service.impl;

import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.optimization.MongoQueryRewriter;
import com.querysense.backend.optimization.QueryRewriter;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.service.QueryAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryComparisonServiceImplTest {

    private QueryAnalysisService analysis;
    private QueryComparisonServiceImpl service;
    private OptimizedQueryRecorder recorder;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.tables(Dialect.MYSQL)).thenReturn(KnownSchema.TABLES);
        when(activeSchema.findTable(eq(Dialect.MYSQL), anyString())).thenAnswer(call ->
                KnownSchema.TABLES.stream()
                        .filter(t -> t.name().equalsIgnoreCase(call.getArgument(1)))
                        .findFirst());

        analysis = mock(QueryAnalysisService.class);
        recorder = mock(OptimizedQueryRecorder.class);
        service = new QueryComparisonServiceImpl(List.of(new QueryRewriter(activeSchema),
                new MongoQueryRewriter(new ActiveSchemaProvider(), new MongoShellParser())), analysis, recorder);
    }

    private QueryAnalysisResult resultWith(long rows, Double cost) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setEstimatedRows(rows);
        result.setQueryCost(cost);
        result.setDatabaseType("MySQL");
        return result;
    }

    @Test
    @DisplayName("both queries are analysed, and the row delta is reported")
    void reportsRowDelta() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(eq(original), anyString())).thenReturn(resultWith(303L, 62.0));
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(303L, 62.0));

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOriginalQuery()).isEqualTo(original);
        assertThat(response.getOptimizedQuery()).contains("IN ('mumbai', 'pune')");
        assertThat(response.getEstimatedRowsDelta()).isZero();
    }

    @Test
    @DisplayName("a negative delta means the rewrite reads fewer rows")
    void negativeDeltaMeansImprovement() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(eq(original), anyString())).thenReturn(resultWith(303L, 62.0));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q != null && q.contains("IN")),
                anyString())).thenReturn(resultWith(12L, 4.5));

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.getEstimatedRowsDelta()).isEqualTo(-291L);
        assertThat(response.getQueryCostDelta()).isEqualTo(-57.5);
    }

    @Test
    @DisplayName("an unchanged query is not analysed twice, and says nothing changed")
    void unchangedQueryIsNotAnalysedTwice() {
        String original = "SELECT id FROM customers WHERE city = 'mumbai';";
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(3L, 1.2));

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.isRewritten()).isFalse();
        assertThat(response.getOptimizedQuery()).isEqualTo(original);
        assertThat(response.getChanges()).isEmpty();
        assertThat(response.getOptimizedAnalysis()).isNull();
        assertThat(response.getEstimatedRowsDelta()).isNull();
        verify(analysis).analyze(eq(original), anyString());
    }

    @Test
    @DisplayName("every rule that fired is named, so a diff line always has a reason")
    void everyChangeIsExplained() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(303L, 62.0));

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.getChanges()).hasSize(2);
        assertThat(response.getChanges()).allSatisfy(change -> {
            assertThat(change.ruleId()).isNotBlank();
            assertThat(change.explanation()).isNotBlank();
        });
    }

    @Test
    @DisplayName("the delta is labelled an estimate, because EXPLAIN estimates rows")
    void deltaIsLabelledAnEstimate() {
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(303L, 62.0));

        CompareResponse response = service.compare(
                "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';", "MySQL");

        assertThat(response.isEstimate()).isTrue();
    }

    @Test
    @DisplayName("a missing cost on either side leaves the cost delta null rather than zero")
    void missingCostLeavesDeltaNull() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(eq(original), anyString())).thenReturn(resultWith(303L, null));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q != null && q.contains("IN")),
                anyString())).thenReturn(resultWith(12L, 4.5));

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.getQueryCostDelta()).isNull();
        assertThat(response.getEstimatedRowsDelta()).isEqualTo(-291L);
    }

    @Test
    @DisplayName("a rewrite the database rejects is withdrawn, not offered as an improvement")
    void rewriteThatFailsExplainIsWithdrawn() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        QueryAnalysisResult rejected = resultWith(0L, null);
        rejected.setExplainFailed(true);
        rejected.addWarning("Unknown column 'phone' in 'field list'");

        when(analysis.analyze(eq(original), anyString())).thenReturn(resultWith(303L, 62.0));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q != null && !q.equals(original)),
                anyString())).thenReturn(rejected);

        CompareResponse response = service.compare(original, "MySQL");

        assertThat(response.isRewritten())
                .as("SQL the database rejects is not an optimisation")
                .isFalse();
        assertThat(response.getOptimizedQuery()).isEqualTo(original);
        assertThat(response.getEstimatedRowsDelta()).isNull();
        assertThat(response.getSummary()).containsIgnoringCase("could not be checked");
        assertThat(response.getOriginalAnalysis()).isNotNull();
    }

    private QueryAnalysisResult mongoResult(long docs, String accessType, boolean failed) {
        QueryAnalysisResult result = new QueryAnalysisResult();
        result.setDatabaseType("MongoDB");
        result.setEstimatedRows(docs);
        result.setAccessType(accessType);
        result.setExplainFailed(failed);
        return result;
    }

    private static final String MONGO_OR = "db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]}, {\"name\": 1})";

    @Test
    @DisplayName("MongoDB is compared on real plans: documents examined, the plan change, measured not estimated")
    void mongoIsCompared() {
        when(analysis.analyze(eq(MONGO_OR), eq("MongoDB"))).thenReturn(mongoResult(2000, "COLLSCAN", false));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q.contains("$in")), eq("MongoDB")))
                .thenReturn(mongoResult(180, "IXSCAN", false));

        CompareResponse response = service.compare(MONGO_OR, "MongoDB", "customers in pune or delhi");

        assertThat(response.isRewritten()).isTrue();
        assertThat(response.getOptimizedQuery()).contains("\"$in\"");
        assertThat(response.getChanges()).extracting(c -> c.ruleId()).containsExactly("RW2");
        assertThat(response.getEstimatedRowsDelta()).isEqualTo(-1820L);
        assertThat(response.getQueryCostDelta()).isNull();
        assertThat(response.isEstimate()).isFalse();
        assertThat(response.getSummary())
                .contains("MongoDB examined 1820 fewer documents")
                .contains("from COLLSCAN to IXSCAN")
                .doesNotContain("EXPLAIN estimates");
        verify(recorder).record(Dialect.MONGODB, "customers in pune or delhi", MONGO_OR, response.getOptimizedQuery());
    }

    @Test
    @DisplayName("a MongoDB rewrite that explain rejects is withheld, and not written into history")
    void rejectedRewriteIsWithheldAndNotRecorded() {
        when(analysis.analyze(eq(MONGO_OR), eq("MongoDB"))).thenReturn(mongoResult(2000, "COLLSCAN", false));
        when(analysis.analyze(org.mockito.ArgumentMatchers.argThat(q -> q.contains("$in")), eq("MongoDB")))
                .thenReturn(mongoResult(0, null, true));

        CompareResponse response = service.compare(MONGO_OR, "MongoDB");

        assertThat(response.isRewritten()).isFalse();
        assertThat(response.getOptimizedQuery()).isEqualTo(MONGO_OR);
        assertThat(response.getSummary()).contains("MongoDB's explain rejected it");
        verify(recorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an accepted MySQL rewrite is recorded too, and an unchanged query is not")
    void mysqlRecording() {
        String original = "SELECT * FROM customers WHERE city = 'mumbai' OR city = 'pune';";
        when(analysis.analyze(anyString(), anyString())).thenReturn(resultWith(303L, 62.0));

        CompareResponse response = service.compare(original, "MySQL", "mumbai or pune customers");
        service.compare("SELECT COUNT(*) FROM customers;", "MySQL", "how many customers");

        verify(recorder).record(Dialect.MYSQL, "mumbai or pune customers", original, response.getOptimizedQuery());
        verify(recorder, never()).record(any(), eq("how many customers"), any(), any());
    }

    @Test
    @DisplayName("an unknown database type is refused, which the API returns as a 400")
    void unknownTypeRefused() {
        assertThatThrownBy(() -> service.compare("SELECT 1", "Oracle"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
