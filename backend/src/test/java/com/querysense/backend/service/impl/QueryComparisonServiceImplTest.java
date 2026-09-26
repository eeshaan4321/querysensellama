package com.querysense.backend.service.impl;

import com.querysense.backend.dto.CompareResponse;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.optimization.QueryRewriter;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.service.QueryAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryComparisonServiceImplTest {

    private QueryAnalysisService analysis;
    private QueryComparisonServiceImpl service;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider activeSchema = mock(ActiveSchemaProvider.class);
        when(activeSchema.tables(Dialect.MYSQL)).thenReturn(KnownSchema.TABLES);
        when(activeSchema.findTable(eq(Dialect.MYSQL), anyString())).thenAnswer(call ->
                KnownSchema.TABLES.stream()
                        .filter(t -> t.name().equalsIgnoreCase(call.getArgument(1)))
                        .findFirst());

        analysis = mock(QueryAnalysisService.class);
        service = new QueryComparisonServiceImpl(new QueryRewriter(activeSchema), analysis);
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

    @Test
    @DisplayName("MongoDB is declined outright rather than given a fake comparison")
    void mongoIsDeclined() {
        String query = "db.customers.find({ city: 'mumbai' })";

        CompareResponse response = service.compare(query, "MongoDB");

        assertThat(response.isRewritten()).isFalse();
        assertThat(response.getSummary()).containsIgnoringCase("MongoDB");
        verify(analysis, never()).analyze(anyString(), eq("MongoDB"));
    }
}
